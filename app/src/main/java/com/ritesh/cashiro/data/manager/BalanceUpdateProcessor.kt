package com.ritesh.cashiro.data.manager

import android.util.Log
import com.ritesh.cashiro.BuildConfig
import com.ritesh.parser.core.ParsedTransaction
import com.ritesh.cashiro.data.database.entity.CardType
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import com.ritesh.cashiro.data.mapper.toEntityType
import com.ritesh.cashiro.data.currency.CurrencyConverter
import com.ritesh.cashiro.data.repository.AccountBalanceRepository
import com.ritesh.cashiro.data.repository.CardRepository
import com.ritesh.cashiro.data.repository.linkedAccountOf
import com.ritesh.cashiro.utils.PiiRedactor
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

    /** The account an alert was booked to. */
    data class BookedAccount(val bankName: String, val accountLast4: String)

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
        val parsedAccountLast4 = parsedTransaction.accountLast4?.takeIf { it.isNotBlank() }
        val resolvedAccountLast4 = entity.accountNumber?.takeIf { it.isNotBlank() }
        val fallbackAccountLast4 = resolvedAccountLast4 ?: parsedAccountLast4
        if (fallbackAccountLast4 == null) return null

        val isFromCard = parsedTransaction.isFromCard

        // Where the money moved. A linked card books to the account it is linked
        // to, which may be filed under a different bank name than the alert's.
        var targetBankName = bankName
        val targetAccountLast4: String? = if (isFromCard) {
            var card = cardRepository.getCard(bankName, fallbackAccountLast4)
            // How the user set this number up outranks how the card was first
            // detected: card spending arrives as EXPENSE, so the transaction type
            // alone files a credit card as a debit card.
            val ownAccountIsCreditCard = accountBalanceRepository
                .getLatestBalance(bankName, fallbackAccountLast4)
                ?.isCreditCard == true

            if (card == null) {
                val isCredit = (parsedTransaction.type.toEntityType() == TransactionType.CREDIT) ||
                        ownAccountIsCreditCard
                cardRepository.findOrCreateCard(
                    cardLast4 = fallbackAccountLast4,
                    bankName = bankName,
                    isCredit = isCredit
                )
                card = cardRepository.getCard(bankName, fallbackAccountLast4)
            } else if (card.cardType != CardType.CREDIT && ownAccountIsCreditCard) {
                // A credit card filed as a debit card could be linked to a bank
                // account, and from then on every alert for it - and its reported
                // balance - landed on that account instead of the card. Re-file it as
                // the credit card the user says it is, with no link.
                cardRepository.updateCard(card.copy(cardType = CardType.CREDIT, accountLast4 = null))
                card = cardRepository.getCard(bankName, fallbackAccountLast4)
            }

            if (card == null) {
                Log.w(TAG, "Could not create/find card for $bankName")
                null
            } else {
                cardRepository.updateCardBalance(
                    cardId = card.id,
                    balance = parsedTransaction.balance,
                    source = sanitizeSmsSource(parsedTransaction).take(200),
                    date = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(parsedTransaction.timestamp),
                        ZoneId.systemDefault()
                    )
                )

                // A card linked to a bank account draws on it; one linked to a credit
                // card account is charged to it. An unlinked card (or one whose link
                // points nowhere) books to its own number, the account the user sees
                // for that card. Returning null here once dropped the update entirely.
                val linked = linkedAccountOf(card, accountBalanceRepository.getAllLatestBalances().first())
                when {
                    linked != null -> {
                        targetBankName = linked.bankName
                        linked.accountLast4
                    }
                    // Linked to an account that does not exist (yet): book under
                    // the link, as before.
                    card.cardType == CardType.DEBIT && card.accountLast4 != null -> card.accountLast4
                    else -> fallbackAccountLast4
                }
            }
        } else {
            fallbackAccountLast4
        }

        if (targetAccountLast4 == null) return null

        run {
            val targetAccount = accountBalanceRepository.getLatestBalance(targetBankName, targetAccountLast4)
            val isCreditCard = targetAccount?.isCreditCard
                ?: ((parsedTransaction.type.toEntityType() == TransactionType.CREDIT) ||
                    cardRepository.getCard(bankName, fallbackAccountLast4)?.cardType == CardType.CREDIT)

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
                bankName = targetBankName,
                accountLast4 = targetAccountLast4,
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
        }
        return BookedAccount(targetBankName, targetAccountLast4)
    }

    private fun sanitizeSmsSource(parsedTransaction: ParsedTransaction): String {
        return PiiRedactor.redact(parsedTransaction.smsBody).take(500)
    }
}
