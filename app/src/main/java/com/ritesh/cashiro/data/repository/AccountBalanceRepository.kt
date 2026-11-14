package com.ritesh.cashiro.data.repository

import android.content.Context
import com.ritesh.cashiro.data.database.dao.AccountBalanceDao
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.time.LocalDateTime
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.parser.core.ParsedTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ritesh.cashiro.utils.IconResolutionUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountBalanceRepository @Inject constructor(
    private val accountBalanceDao: AccountBalanceDao,
    @ApplicationContext private val context: Context
) {
    suspend fun insertBalance(balance: AccountBalanceEntity): Long {
        val balanceWithIconName = if (balance.iconName.isEmpty() && balance.iconResId != 0) {
            balance.copy(iconName = IconResolutionUtils.resIdToName(context, balance.iconResId))
        } else {
            balance
        }
        return accountBalanceDao.insertBalance(balanceWithIconName)
    }
    
    suspend fun getLatestBalance(bankName: String, accountLast4: String): AccountBalanceEntity? {
        return accountBalanceDao.getLatestBalance(bankName, accountLast4)
    }

    suspend fun getLatestBalanceOnOrBefore(
        bankName: String,
        accountLast4: String,
        timestamp: LocalDateTime
    ): AccountBalanceEntity? {
        return accountBalanceDao.getLatestBalanceOnOrBefore(bankName, accountLast4, timestamp)
    }

    suspend fun resolveAccountLast4(bankName: String, accountLast4: String): String {
        if (accountLast4.isBlank()) {
            return accountLast4
        }

        if (!accountLast4.all { it.isDigit() }) {
            return accountLast4
        }

        if (accountLast4.length >= 4) {
            return accountLast4.takeLast(4)
        }

        val matches = accountBalanceDao.getAccountLast4sEndingWith(bankName, accountLast4)
        return if (matches.size == 1) matches.first() else accountLast4
    }

    suspend fun resolveEntityAccountNumber(
        entity: TransactionEntity,
        parsedTransaction: ParsedTransaction
    ): TransactionEntity {
        val withAccount =
            if (!parsedTransaction.isFromCard && entity.bankName != null && entity.accountNumber != null) {
                entity.copy(
                    accountNumber = resolveAccountLast4(entity.bankName, entity.accountNumber)
                )
            } else {
                entity
            }
        return resolveBankByLast4(withAccount, namesCreditCard(parsedTransaction))
    }

    /**
     * Whether the number an alert is filed under is a credit card's.
     *
     * A card alert usually names only the card, so its number is the card's. Some
     * banks also name the account a debit card drew on ("Account XXXX0002 / Card
     * XXXX1234"); the alert is then filed under that bank account, and treating it
     * as a card's number kept it from ever matching the user's account.
     */
    private fun namesCreditCard(parsed: ParsedTransaction): Boolean {
        if (parsed.type == com.ritesh.parser.core.TransactionType.CREDIT) return true
        if (!parsed.isFromCard) return false
        val card = parsed.cardLast4 ?: return true
        return card == parsed.accountLast4
    }

    /**
     * Attributes an alert to the account the user set up for its last four digits
     * when the bank name the parser reports differs from the name that account was
     * given.
     *
     * Accounts are keyed by bank name and last four digits, so a card created by
     * hand under any other spelling of the bank never matched its own alerts: they
     * went to a second account the app created under the parser's name, and the
     * card the user set up stayed at zero. Only an unambiguous match is used - a
     * single account of the same kind (a card alert to a credit card, anything else
     * to a non-card account) - so an unrelated account that happens to share the
     * digits is never picked.
     */
    private suspend fun resolveBankByLast4(
        entity: TransactionEntity,
        isCreditCardNumber: Boolean
    ): TransactionEntity {
        val bankName = entity.bankName ?: return entity
        val last4 = entity.accountNumber ?: return entity
        if (last4.length != 4 || !last4.all { it.isDigit() }) return entity
        if (accountBalanceDao.getLatestBalance(bankName, last4) != null) return entity

        val candidates = accountBalanceDao.getAllLatestBalances().first()
            .filter { it.accountLast4 == last4 && it.bankName != bankName }
            .filter { it.isCreditCard == isCreditCardNumber }
            .distinctBy { it.bankName }
        return if (candidates.size == 1) entity.copy(bankName = candidates.single().bankName) else entity
    }

    /**
     * The account the user marked as their main one, as "bankName_last4", or
     * null when none is set (or settings cannot be read, as in tests).
     */
    fun mainAccountKey(): String? = runCatching {
        context.getSharedPreferences("account_prefs", Context.MODE_PRIVATE)
            .getString("main_account", null)
    }.getOrNull()

    fun getLatestBalanceFlow(bankName: String, accountLast4: String): Flow<AccountBalanceEntity?> {
        return accountBalanceDao.getLatestBalanceFlow(bankName, accountLast4)
    }
    
    fun getAllLatestBalances(): Flow<List<AccountBalanceEntity>> {
        return accountBalanceDao.getAllLatestBalances()
    }
    
    fun getTotalBalance(): Flow<BigDecimal?> {
        return accountBalanceDao.getTotalBalance()
    }
    
    fun getBalanceHistory(
        bankName: String,
        accountLast4: String,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): Flow<List<AccountBalanceEntity>> {
        return accountBalanceDao.getBalanceHistory(bankName, accountLast4, startDate, endDate)
    }
    
    fun getAccountCount(): Flow<Int> {
        return accountBalanceDao.getAccountCount()
    }
    
    suspend fun deleteOldBalances(beforeDate: LocalDateTime): Int {
        return accountBalanceDao.deleteOldBalances(beforeDate)
    }
    
    suspend fun updateBalance(balance: AccountBalanceEntity) {
        accountBalanceDao.updateBalance(balance)
    }
    
    fun getAllBalances(): Flow<List<AccountBalanceEntity>> {
        return accountBalanceDao.getAllBalances()
    }

    suspend fun deleteBalance(balance: AccountBalanceEntity) {
        accountBalanceDao.deleteBalance(balance)
    }

    suspend fun insertBalanceFromTransaction(
        bankName: String?,
        accountLast4: String?,
        balance: BigDecimal?,
        creditLimit: BigDecimal? = null,
        timestamp: LocalDateTime,
        transactionId: Long?,
        isCreditCard: Boolean = false
    ) {
        if (bankName != null && accountLast4 != null && (balance != null || creditLimit != null)) {
            val latest = getLatestBalance(bankName, accountLast4)
            val balanceEntity = AccountBalanceEntity(
                bankName = bankName,
                accountLast4 = accountLast4,
                balance = balance ?: BigDecimal.ZERO,
                timestamp = timestamp,
                transactionId = transactionId,
                creditLimit = creditLimit ?: latest?.creditLimit,
                isCreditCard = isCreditCard || (latest?.isCreditCard ?: false),
                iconResId = latest?.iconResId ?: 0,
                iconName = latest?.iconName ?: "",
                isWallet = latest?.isWallet ?: false,
                color = latest?.color ?: "#33B5E5"
            )
            insertBalance(balanceEntity)
        }
    }

    /**
     * Inserts a balance entry linked to a transaction, and sequentially recalculates succeeding balances.
     *
     * @param bankName The name of the bank.
     * @param accountLast4 The last 4 digits of the account number.
     * @param amount The transaction amount.
     * @param transactionType The transaction type.
     * @param explicitBalance The bank-reported explicit balance (if any).
     * @param timestamp The transaction timestamp.
     * @param transactionId The associated transaction ID.
     * @param creditLimit Optionally, a custom credit limit parsed from SMS.
     * @param isCreditCard Whether this account is a credit card.
     * @param smsSource Sanitized SMS snippet source.
     * @param currency The transaction currency.
     * @return The ID of the inserted balance record.
     */
    suspend fun insertTransactionBalance(
        bankName: String,
        accountLast4: String,
        amount: BigDecimal,
        transactionType: TransactionType,
        explicitBalance: BigDecimal?,
        timestamp: LocalDateTime,
        transactionId: Long?,
        creditLimit: BigDecimal?,
        isCreditCard: Boolean,
        smsSource: String?,
        currency: String
    ): Long {
        return accountBalanceDao.insertTransactionBalance(
            bankName = bankName,
            accountLast4 = accountLast4,
            amount = amount,
            transactionType = transactionType,
            explicitBalance = explicitBalance,
            timestamp = timestamp,
            transactionId = transactionId,
            creditLimit = creditLimit,
            isCreditCard = isCreditCard,
            smsSource = smsSource,
            currency = currency
        )
    }


    suspend fun insertBalanceUpdate(
        bankName: String,
        accountLast4: String,
        balance: BigDecimal,
        timestamp: LocalDateTime,
        smsSource: String? = null,
        sourceType: String? = null,
        currency: String = "INR"
    ): Long {
        val latest = getLatestBalance(bankName, accountLast4)
        val balanceEntity = AccountBalanceEntity(
            bankName = bankName,
            accountLast4 = accountLast4,
            balance = balance,
            timestamp = timestamp,
            transactionId = null,
            smsSource = smsSource?.take(500),  // Limit to 500 chars
            sourceType = sourceType,
            // An existing account keeps its currency. This used to stamp the
            // caller's currency - INR by default - on the account's newest row,
            // and every later transaction inherits the newest row's currency, so
            // linking a card turned an AED account into an INR one.
            currency = latest?.currency ?: currency,
            iconResId = latest?.iconResId ?: 0,
            iconName = latest?.iconName ?: "",
            isWallet = latest?.isWallet ?: false,
            isCreditCard = latest?.isCreditCard ?: false,
            creditLimit = latest?.creditLimit,
            color = latest?.color ?: "#33B5E5"
        )
        return insertBalance(balanceEntity)
    }
    
    suspend fun getBalanceHistoryForAccount(bankName: String, accountLast4: String): List<AccountBalanceEntity> {
        return accountBalanceDao.getBalanceHistoryForAccount(bankName, accountLast4)
    }
    
    suspend fun deleteBalanceById(id: Long) {
        accountBalanceDao.deleteBalanceById(id)
    }
    
    suspend fun updateBalanceById(id: Long, newBalance: BigDecimal) {
        accountBalanceDao.updateBalanceById(id, newBalance)
    }
    
    suspend fun getBalanceCountForAccount(bankName: String, accountLast4: String): Int {
        return accountBalanceDao.getBalanceCountForAccount(bankName, accountLast4)
    }

    suspend fun deleteAccount(bankName: String, accountLast4: String): Int {
        return accountBalanceDao.deleteAccount(bankName, accountLast4)
    }

    suspend fun updateAccountLast4(bankName: String, oldAccountLast4: String, newAccountLast4: String): Int {
        return accountBalanceDao.updateAccountLast4(bankName, oldAccountLast4, newAccountLast4)
    }

    suspend fun setAccountCreditCardFlag(bankName: String, accountLast4: String, isCreditCard: Boolean): Int {
        return accountBalanceDao.setAccountCreditCardFlag(bankName, accountLast4, isCreditCard)
    }

    suspend fun updateAccountBankName(oldBankName: String, accountLast4: String, newBankName: String): Int {
        return accountBalanceDao.updateAccountBankName(oldBankName, accountLast4, newBankName)
    }

    suspend fun deleteAllBalances() {
        accountBalanceDao.deleteAllBalances()
    }

    suspend fun deleteSampleBalances() {
        accountBalanceDao.deleteSampleBalances()
    }

    suspend fun getAccountByLast4(accountLast4: String): AccountBalanceEntity? {
        return accountBalanceDao.getAccountByLast4(accountLast4)
    }

    suspend fun getBalanceByTransactionId(transactionId: Long): AccountBalanceEntity? {
        return accountBalanceDao.getBalanceByTransactionId(transactionId)
    }

    suspend fun getBalancesByTransactionId(transactionId: Long): List<AccountBalanceEntity> {
        return accountBalanceDao.getBalancesByTransactionId(transactionId)
    }

    suspend fun getBalanceById(id: Long): AccountBalanceEntity? {
        return accountBalanceDao.getBalanceById(id)
    }

    suspend fun recalculateBalancesAfter(
        bankName: String,
        accountLast4: String,
        timestamp: LocalDateTime,
        startingBalance: BigDecimal,
        previousOldBalance: BigDecimal? = null,
        correctionShift: BigDecimal? = null
    ) {
        accountBalanceDao.recalculateBalancesAfter(
            bankName,
            accountLast4,
            timestamp,
            startingBalance,
            previousOldBalance,
            correctionShift
        )
    }
}
