package com.ritesh.cashiro.data.manager

import android.util.Log
import com.ritesh.cashiro.BuildConfig
import com.ritesh.parser.core.ParsedTransaction
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.entity.CardEntity
import com.ritesh.cashiro.data.database.entity.CardType
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import com.ritesh.cashiro.data.mapper.toEntityType
import com.ritesh.cashiro.data.currency.CurrencyConverter
import com.ritesh.cashiro.data.repository.AccountBalanceRepository
import com.ritesh.cashiro.data.repository.CardRepository
import com.ritesh.cashiro.data.repository.linkedAccountOf
import com.ritesh.cashiro.utils.PiiRedactor
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class BalanceUpdateProcessor @Inject constructor(
    private val cardRepository: CardRepository,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val currencyConversionService: CurrencyConverter
) {
    companion object {
        private const val TAG = "BalanceUpdateProcessor"
    }

    /**
     * Where an alert was booked.
     *
     * [accountLast4] is the account the money left or arrived on; it is null only
     * for a payment whose source account could not be told, where the
     * destination alone was credited. [transactionType] and [toAccount] are set
     * when booking changed what the transaction is: a payment to one of the
     * user's own cards is a transfer to that card, not spending.
     */
    data class BookedAccount(
        val bankName: String,
        val accountLast4: String?,
        val transactionType: TransactionType? = null,
        val toAccount: String? = null
    )

    private data class AccountKey(val bankName: String, val accountLast4: String)

    /**
     * Books an alert's effect on the account it belongs to and says which account
     * that was, or null when nothing was booked. For a card linked to an account
     * that is the linked account, which can differ from the account the saved
     * transaction names; the caller files the transaction under it too.
     */
    suspend fun process(
        parsedTransaction: ParsedTransaction,
        entity: TransactionEntity,
        rowId: Long
    ): BookedAccount? {
        // The saved transaction carries the account it was attributed to, which can
        // differ from the parser's raw values: a partial number expanded to the
        // account it belongs to, or a bank name matched to the account the user set
        // up for those digits. The balance has to land on that same account, or the
        // transaction is listed under one account while another one moves.
        val bankName = entity.bankName ?: parsedTransaction.bankName

        paymentRecipientOf(parsedTransaction, entity)?.let { payee ->
            return bookPayment(parsedTransaction, entity, rowId, bankName, payee)
        }

        val parsedAccountLast4 = parsedTransaction.accountLast4?.takeIf { it.isNotBlank() }
        val resolvedAccountLast4 = entity.accountNumber?.takeIf { it.isNotBlank() }
        val fallbackAccountLast4 = resolvedAccountLast4 ?: parsedAccountLast4
        if (fallbackAccountLast4 == null) return null

        val isFromCard = parsedTransaction.isFromCard
        val isCreditAlert = parsedTransaction.type.toEntityType() == TransactionType.CREDIT
        var card: CardEntity? = null

        // Where the money moved. A linked card books to the account it is linked
        // to, which may be filed under a different bank name than the alert's.
        val target: AccountKey = if (isFromCard) {
            // The card used. Some banks name the account the card drew on as well
            // ("Account XXXX0002 / Card XXXX1234"); the card is then looked up by
            // its own number, not by the account's.
            val cardNumber = parsedTransaction.cardLast4?.takeIf { it.isNotBlank() } ?: fallbackAccountLast4
            val drawnAccount = fallbackAccountLast4.takeIf {
                parsedTransaction.cardLast4 != null && it != cardNumber
            }
            val accounts = accountBalanceRepository.getAllLatestBalances().first()

            // How the user set this number up outranks how the card was first
            // detected: card spending arrives as EXPENSE, so the transaction type
            // alone files a credit card as a debit card.
            val ownCreditAccount = creditCardAccountFor(accounts, bankName, cardNumber)

            val existingCard = findCard(bankName, parsedTransaction.bankName, cardNumber)
            val usedCard: CardEntity = when {
                existingCard == null -> cardRepository.findOrCreateCard(
                    cardLast4 = cardNumber,
                    bankName = bankName,
                    isCredit = isCreditAlert || ownCreditAccount != null
                )
                existingCard.cardType != CardType.CREDIT && ownCreditAccount != null -> {
                    // A credit card filed as a debit card could be linked to a bank
                    // account, and from then on every alert for it - and its reported
                    // balance - landed on that account instead of the card. Re-file it
                    // as the credit card the user says it is, with no link.
                    val refiled = existingCard.copy(cardType = CardType.CREDIT, accountLast4 = null)
                    cardRepository.updateCard(refiled)
                    refiled
                }
                else -> existingCard
            }
            card = usedCard

            cardRepository.updateCardBalance(
                cardId = usedCard.id,
                balance = parsedTransaction.balance,
                source = sanitizeSmsSource(parsedTransaction).take(200),
                date = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(parsedTransaction.timestamp),
                    ZoneId.systemDefault()
                )
            )

            // A card linked to a bank account draws on it; one linked to a credit
            // card account is charged to it. An unlinked card books to the account
            // the alert says it drew on, or else to its own number, the account the
            // user sees for that card.
            val linked = linkedAccountOf(usedCard, accounts)
            val debitLink = usedCard.accountLast4.takeIf { usedCard.cardType == CardType.DEBIT }
            when {
                linked != null -> AccountKey(linked.bankName, linked.accountLast4)
                // Linked to an account that does not exist (yet): book under the
                // link, as before.
                debitLink != null -> AccountKey(bankName, debitLink)
                ownCreditAccount != null ->
                    AccountKey(ownCreditAccount.bankName, ownCreditAccount.accountLast4)
                drawnAccount != null && !isCreditAlert ->
                    accountForNumber(accounts, bankName, parsedTransaction.bankName, drawnAccount)
                        ?: AccountKey(bankName, drawnAccount)
                else -> AccountKey(bankName, cardNumber)
            }
        } else {
            AccountKey(bankName, fallbackAccountLast4)
        }

        val targetAccount = accountBalanceRepository.getLatestBalance(target.bankName, target.accountLast4)
        val isCreditCard = targetAccount?.isCreditCard
            ?: (isCreditAlert || card?.cardType == CardType.CREDIT)

        // A transaction can arrive in a different currency from the account it
        // belongs to (a card used abroad, for instance). Convert into the
        // account's own currency, otherwise the raw figure is subtracted as
        // though 30 USD and 30 AED were the same amount of money.
        val accountCurrency = targetAccount?.currency ?: parsedTransaction.currency
        val amountInAccountCurrency = currencyConversionService.convertAmount(
            amount = parsedTransaction.amount,
            fromCurrency = parsedTransaction.currency,
            toCurrency = accountCurrency
        )

        accountBalanceRepository.insertTransactionBalance(
            bankName = target.bankName,
            accountLast4 = target.accountLast4,
            amount = amountInAccountCurrency,
            // The saved type, after rules: a rule that relabels an alert has
            // to change how it moves the balance, or the account and the
            // transaction list disagree.
            transactionType = entity.transactionType,
            // The bank states this balance in the account's own currency, so
            // it is used as-is and never converted.
            explicitBalance = parsedTransaction.balance,
            timestamp = entity.dateTime,
            transactionId = if (rowId != -1L) rowId else null,
            creditLimit = parsedTransaction.creditLimit,
            isCreditCard = isCreditCard,
            smsSource = sanitizeSmsSource(parsedTransaction),
            currency = accountCurrency
        )

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Saved balance update from SMS transaction")
        }
        return BookedAccount(target.bankName, target.accountLast4)
    }

    /**
     * The last four digits of the account an alert paid money to, when it is a
     * payment out that names one: "Your payment instructions of AED 100.00 to
     * ***1234", or a transfer alert sent for the account the money left. Card
     * alerts keep going through the card, so a linked card still books where it
     * is linked.
     */
    private fun paymentRecipientOf(parsed: ParsedTransaction, entity: TransactionEntity): String? {
        if (parsed.isFromCard) return null
        val payee = parsed.toAccount?.takeIf { it.length == 4 && it.all(Char::isDigit) } ?: return null
        return when (entity.transactionType) {
            TransactionType.EXPENSE -> payee
            // Only an alert about the account the money left. A transfer alert
            // that does not say which account sent it is left as it was.
            TransactionType.TRANSFER -> payee.takeIf {
                parsed.fromAccount != null &&
                    (parsed.accountLast4 == null || parsed.fromAccount == parsed.accountLast4)
            }
            else -> null
        }
    }

    /**
     * Books a payment out of one account to the account or card it paid.
     *
     * The money leaves the paying account. When the payee is one of the user's own
     * accounts - typically a credit card being paid off - it arrives there too, so
     * the card's outstanding amount comes down by what was paid, and the
     * transaction is a transfer between the two. Paying anyone else is an expense
     * from the paying account.
     *
     * Before this, a card payment alert named no account of its own, so it was
     * saved without moving any balance at all: the bank account kept the money
     * and the card kept the debt.
     */
    private suspend fun bookPayment(
        parsed: ParsedTransaction,
        entity: TransactionEntity,
        rowId: Long,
        bankName: String,
        payee: String
    ): BookedAccount? {
        val accounts = accountBalanceRepository.getAllLatestBalances().first()
        val namedSource = entity.accountNumber?.takeIf { it.isNotBlank() }
        val source = if (namedSource != null) {
            AccountKey(bankName, namedSource)
        } else {
            // The alert does not say which account paid. It came from the bank, so
            // it is the user's account there; with only one account it is that one.
            inferPayingAccount(accounts, bankName, parsed.sender, payee)
        }
        val destination = destinationOf(accounts, payee, source)

        val transactionId = if (rowId != -1L) rowId else null
        val smsSource = sanitizeSmsSource(parsed)

        if (source != null) {
            val sourceAccount = accounts.firstOrNull {
                it.bankName == source.bankName && it.accountLast4 == source.accountLast4
            }
            val currency = sourceAccount?.currency ?: parsed.currency
            accountBalanceRepository.insertTransactionBalance(
                bankName = source.bankName,
                accountLast4 = source.accountLast4,
                amount = convert(parsed, currency),
                transactionType = TransactionType.EXPENSE,
                // A balance in the alert is the paying account's.
                explicitBalance = parsed.balance,
                timestamp = entity.dateTime,
                transactionId = transactionId,
                creditLimit = null,
                isCreditCard = sourceAccount?.isCreditCard ?: false,
                smsSource = smsSource,
                currency = currency
            )
        }

        if (destination != null) {
            accountBalanceRepository.insertTransactionBalance(
                bankName = destination.bankName,
                accountLast4 = destination.accountLast4,
                amount = convert(parsed, destination.currency),
                // Money arriving: on a credit card this lowers what is owed.
                transactionType = TransactionType.INCOME,
                explicitBalance = null,
                timestamp = entity.dateTime,
                transactionId = transactionId,
                creditLimit = null,
                isCreditCard = destination.isCreditCard,
                smsSource = smsSource,
                currency = destination.currency
            )
        }

        if (source == null && destination == null) return null
        return BookedAccount(
            bankName = source?.bankName ?: bankName,
            accountLast4 = source?.accountLast4,
            transactionType = if (destination != null) TransactionType.TRANSFER else entity.transactionType,
            toAccount = destination?.accountLast4 ?: entity.toAccount
        )
    }

    /**
     * The user's account at the bank that sent a payment alert.
     *
     * Accounts are keyed by bank name and number, and an account the user set up
     * by hand is often named differently from the bank name on its alerts, so the
     * same account can also appear under the alert's name (created by an earlier
     * alert). Those count as one account, and the user's own naming is used.
     *
     * In order: the account under the alert's bank name or named the way the bank
     * signs its messages ("FAB" for "First Abu Dhabi Bank"); the user's main
     * account; the user's only bank account. Without the middle step, a user with
     * a second account and their own name for the bank had the card credited and
     * the paying account left untouched.
     */
    private fun inferPayingAccount(
        accounts: List<AccountBalanceEntity>,
        bankName: String,
        sender: String,
        payee: String
    ): AccountKey? {
        val spendable = accounts.filter { !it.isCreditCard && !it.isWallet && it.accountLast4 != payee }
        val oneRowPerAccount = spendable.groupBy { it.accountLast4 }.values.map { sameNumber ->
            sameNumber.firstOrNull { !it.bankName.equals(bankName, ignoreCase = true) } ?: sameNumber.first()
        }
        val atThisBank = spendable
            .filter { it.bankName.equals(bankName, ignoreCase = true) }
            .map { it.accountLast4 }
            .toSet()
        val namedLikeThisBank = oneRowPerAccount.filter {
            it.accountLast4 in atThisBank || namesSameBank(it.bankName, bankName, sender)
        }
        val mainKey = accountBalanceRepository.mainAccountKey()
        val main = oneRowPerAccount.firstOrNull { "${it.bankName}_${it.accountLast4}" == mainKey }

        val pick = namedLikeThisBank.singleOrNull()
            ?: main?.takeIf { namedLikeThisBank.isEmpty() || it in namedLikeThisBank }
            ?: namedLikeThisBank.maxByOrNull { it.timestamp }
            ?: oneRowPerAccount.singleOrNull()
        return pick?.let { AccountKey(it.bankName, it.accountLast4) }
    }

    /**
     * Whether an account the user named [accountBank] is at the bank whose alerts
     * say [alertBank] and come from [sender]: the same name, the sender's name
     * ("FAB"), or the bank's initials.
     */
    private fun namesSameBank(accountBank: String, alertBank: String, sender: String): Boolean {
        val name = accountBank.filter { it.isLetterOrDigit() }.lowercase()
        if (name.length < 2) return false
        val alert = alertBank.filter { it.isLetterOrDigit() }.lowercase()
        val from = sender.substringAfter('-').filter { it.isLetterOrDigit() }.lowercase()
        val initials = alertBank.split(' ', '-', '.').filter { it.isNotBlank() }
            .joinToString("") { it.take(1) }.lowercase()
        return name == alert ||
            alert.startsWith(name) ||
            (from.length >= 2 && (name == from || name.startsWith(from) ||
                (name.length >= 3 && from.startsWith(name)))) ||
            name == initials
    }

    /**
     * The user's own account a payment went to, or null when it went to someone
     * else. A credit card with those digits comes first, since paying a card off
     * is what these payments usually are; a card number linked to an account
     * resolves to that account.
     */
    private suspend fun destinationOf(
        accounts: List<AccountBalanceEntity>,
        payee: String,
        source: AccountKey?
    ): AccountBalanceEntity? {
        fun isSource(account: AccountBalanceEntity) =
            source != null && account.bankName == source.bankName && account.accountLast4 == source.accountLast4

        val sameDigits = accounts.filter { it.accountLast4 == payee && !isSource(it) }
        sameDigits.firstOrNull { it.isCreditCard }?.let { return it }
        sameDigits.distinctBy { it.bankName }.singleOrNull()?.let { return it }

        val card = cardRepository.findCard(payee) ?: return null
        return linkedAccountOf(card, accounts)?.takeUnless { isSource(it) }
    }

    /** A card by its number, under the account's bank name or the alert's own. */
    private suspend fun findCard(bankName: String, alertBankName: String, cardNumber: String): CardEntity? =
        cardRepository.getCard(bankName, cardNumber)
            ?: cardRepository.getCard(alertBankName, cardNumber)

    /** A credit card account the user has for [cardNumber]: under this bank's name, or the only one anywhere. */
    private fun creditCardAccountFor(
        accounts: List<AccountBalanceEntity>,
        bankName: String,
        cardNumber: String
    ): AccountBalanceEntity? {
        val cards = accounts.filter { it.isCreditCard && it.accountLast4 == cardNumber }
        return cards.firstOrNull { it.bankName == bankName } ?: cards.distinctBy { it.bankName }.singleOrNull()
    }

    /**
     * The account an alert's account number belongs to: one a card with that
     * number was linked to (an earlier alert filed the account's number as a card
     * and the user linked it), the account under this bank's name, or the only
     * bank account with those digits under any name.
     */
    private suspend fun accountForNumber(
        accounts: List<AccountBalanceEntity>,
        bankName: String,
        alertBankName: String,
        number: String
    ): AccountKey? {
        findCard(bankName, alertBankName, number)
            ?.let { linkedAccountOf(it, accounts) }
            ?.let { return AccountKey(it.bankName, it.accountLast4) }
        accounts.firstOrNull { it.bankName == bankName && it.accountLast4 == number }
            ?.let { return AccountKey(it.bankName, it.accountLast4) }
        return accounts
            .filter { !it.isCreditCard && it.accountLast4 == number }
            .distinctBy { it.bankName }
            .singleOrNull()
            ?.let { AccountKey(it.bankName, it.accountLast4) }
    }

    private suspend fun convert(parsed: ParsedTransaction, toCurrency: String): BigDecimal =
        currencyConversionService.convertAmount(
            amount = parsed.amount,
            fromCurrency = parsed.currency,
            toCurrency = toCurrency
        )

    private fun sanitizeSmsSource(parsedTransaction: ParsedTransaction): String {
        return PiiRedactor.redact(parsedTransaction.smsBody).take(500)
    }
}
