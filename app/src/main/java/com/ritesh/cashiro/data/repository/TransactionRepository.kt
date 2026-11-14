package com.ritesh.cashiro.data.repository

import android.util.Log
import com.ritesh.cashiro.data.currency.CurrencyConverter
import com.ritesh.cashiro.data.database.dao.TransactionDao
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val currencyConversionService: CurrencyConverter
) {
    fun getAllTransactions(): Flow<List<TransactionEntity>> = transactionDao.getAllTransactions()
        .map { list -> list.filter { it.transactionType != TransactionType.BALANCE_UPDATE } }

    fun getTransactionCount(): Flow<Int> = transactionDao.getTransactionCount()

    suspend fun getTransactionById(id: Long): TransactionEntity? =
            transactionDao.getTransactionById(id)

    suspend fun getTransactionsUpdatedBetween(
            updatedAfter: LocalDateTime,
            updatedBefore: LocalDateTime,
            currency: String
    ): List<TransactionEntity> =
            transactionDao.getTransactionsUpdatedBetween(updatedAfter, updatedBefore, currency)

    suspend fun getTransactionsBetweenDatesByCurrency(
            startDate: LocalDateTime,
            endDate: LocalDateTime,
            currency: String
    ): List<TransactionEntity> =
            transactionDao.getTransactionsBetweenDatesByCurrency(startDate, endDate, currency)

    fun getTransactionsBetweenDates(
            startDate: LocalDateTime,
            endDate: LocalDateTime
    ): Flow<List<TransactionEntity>> =
            transactionDao.getTransactionsBetweenDates(startDate, endDate)

    fun getTransactionsBetweenDates(
            startDate: LocalDate,
            endDate: LocalDate
    ): Flow<List<TransactionEntity>> =
            transactionDao.getTransactionsBetweenDates(
                    startDate.atStartOfDay(),
                    endDate.atTime(23, 59, 59)
            )

    /**
     * Gets transactions filtered at the database level for better performance. Combines date range,
     * currency, and transaction type filters to reduce memory usage.
     *
     * @param startDate Start of the date range (inclusive)
     * @param endDate End of the date range (inclusive)
     * @param currency Currency code to filter by (e.g., "INR", "USD")
     * @param transactionType Optional transaction type filter (null means all types)
     * @return Flow of filtered transactions
     */
    fun getTransactionsFiltered(
            startDate: LocalDate,
            endDate: LocalDate,
            currency: String,
            transactionType: TransactionType? = null
    ): Flow<List<TransactionEntity>> =
            transactionDao.getTransactionsFiltered(
                    startDate.atStartOfDay(),
                    endDate.atTime(23, 59, 59),
                    currency,
                    transactionType
            )

    fun getTransactionsByType(type: TransactionType): Flow<List<TransactionEntity>> =
            transactionDao.getTransactionsByType(type)

    fun getTransactionsByCategory(category: String): Flow<List<TransactionEntity>> =
            transactionDao.getTransactionsByCategory(category)

    fun searchTransactions(query: String): Flow<List<TransactionEntity>> =
            transactionDao.searchTransactions(query)

    fun getAllCurrencies(): Flow<List<String>> = transactionDao.getAllCurrencies()

    fun getCurrenciesForPeriod(
            startDate: LocalDateTime,
            endDate: LocalDateTime
    ): Flow<List<String>> = transactionDao.getCurrenciesForPeriod(startDate, endDate)

    fun getAllCategories(): Flow<List<String>> = transactionDao.getAllCategories()

    /**
     * Gets the top N categories by usage count (number of transactions). Useful for showing user's
     * most frequently used categories in notifications.
     *
     * @param limit Maximum number of categories to return (default: 3)
     * @return List of category names ordered by usage count (most used first)
     */
    suspend fun getTopCategoriesByUsage(limit: Int = 3): List<String> =
            transactionDao.getTopCategoriesByUsage(limit)

    fun getAllMerchants(): Flow<List<String>> = transactionDao.getAllMerchants()

    suspend fun getTotalAmountByTypeAndPeriod(
            type: TransactionType,
            startDate: LocalDateTime,
            endDate: LocalDateTime
    ): Double? = transactionDao.getTotalAmountByTypeAndPeriod(type, startDate, endDate)

    suspend fun insertTransaction(transaction: TransactionEntity): Long =
            transactionDao.insertTransaction(transaction)

    suspend fun insertTransactions(transactions: List<TransactionEntity>) =
            transactionDao.insertTransactions(transactions)

    suspend fun updateTransaction(transaction: TransactionEntity) =
            transactionDao.updateTransaction(transaction)

    suspend fun deleteTransaction(transaction: TransactionEntity, hardDelete: Boolean = false) {
        if (hardDelete) {
            transactionDao.deleteTransaction(transaction)
        } else {
            transactionDao.softDeleteTransaction(transaction.id)
        }
        reverseBalanceForDeletion(transaction)
    }

    suspend fun deleteTransactionById(id: Long, hardDelete: Boolean = false) {
        val transaction = transactionDao.getTransactionById(id) ?: return
        if (hardDelete) {
            transactionDao.deleteTransactionById(id)
        } else {
            transactionDao.softDeleteTransaction(id)
        }
        reverseBalanceForDeletion(transaction)
    }

    suspend fun deleteAllTransactions() = transactionDao.deleteAllTransactions()

    suspend fun deleteSampleTransactions() = transactionDao.deleteSampleTransactions()

    // Helper method to check if transaction exists by hash
    suspend fun getTransactionByHash(transactionHash: String): TransactionEntity? =
            transactionDao.getTransactionByHash(transactionHash)

    suspend fun getTransactionsByReferenceAndAmount(
        reference: String,
        amount: BigDecimal,
        accountLast4: String?,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): List<TransactionEntity> =
        transactionDao.getTransactionsByReferenceAndAmount(reference, amount, accountLast4, startDate, endDate)

    suspend fun undoDeleteTransaction(transaction: TransactionEntity) {
        transactionDao.updateTransaction(transaction.copy(isDeleted = false))
        applyBalanceForTransaction(transaction)
    }

    suspend fun deleteTransactions(transactions: List<TransactionEntity>, hardDelete: Boolean = false) {
        val transactionIds = transactions.map { it.id }
        if (hardDelete) {
            transactionDao.deleteTransactionsByIds(transactionIds)
        } else {
            transactionDao.softDeleteTransactions(transactionIds)
        }
        transactions.forEach { reverseBalanceForDeletion(it) }
    }

    suspend fun undoDeleteTransactions(transactions: List<TransactionEntity>) {
        transactions.forEach { transaction ->
            transactionDao.updateTransaction(transaction.copy(isDeleted = false))
            applyBalanceForTransaction(transaction)
        }
    }

    private suspend fun reverseBalanceForDeletion(transaction: TransactionEntity) {
        applyTransactionToBalances(transaction, reverse = true, sourceType = "DELETE_REVERSAL")
    }

    /**
     * Applies (or undoes) a transaction's effect on the accounts it touches.
     *
     * Handles both legs of a transfer, and mirrors the sign on credit cards where
     * the stored balance is an outstanding amount rather than available money.
     */
    private suspend fun applyTransactionToBalances(
        transaction: TransactionEntity,
        reverse: Boolean,
        sourceType: String
    ) {
        val bankName = transaction.bankName
        val accountLast4 = transaction.accountNumber

        if (transaction.transactionType == TransactionType.TRANSFER) {
            // Source loses the money, target receives it.
            if (bankName != null && accountLast4 != null) {
                adjustBalance(bankName, accountLast4, transaction.amount.negate(), reverse, sourceType, transaction.currency)
            }
            // Exclude the source so a transfer can never resolve back onto the
            // account it left, which would cancel itself out.
            val target = transaction.toAccount?.let {
                findAccountByLast4(it, excludeBank = bankName, excludeLast4 = accountLast4)
            }
            if (target != null) {
                adjustBalance(target.bankName, target.accountLast4, transaction.amount, reverse, sourceType, transaction.currency)
            }
            return
        }

        if (bankName == null || accountLast4 == null) return

        val effect = when (transaction.transactionType) {
            TransactionType.CREDIT, TransactionType.INCOME, TransactionType.BORROWED -> transaction.amount
            TransactionType.EXPENSE, TransactionType.INVESTMENT, TransactionType.LENT -> transaction.amount.negate()
            else -> return
        }
        adjustBalance(bankName, accountLast4, effect, reverse, sourceType, transaction.currency)
    }

    private suspend fun adjustBalance(
        bankName: String,
        accountLast4: String,
        effect: BigDecimal,
        reverse: Boolean,
        sourceType: String,
        currency: String
    ) {
        val latestBalance = accountBalanceRepository.getLatestBalance(bankName, accountLast4) ?: return

        // The transaction may be denominated differently from the account it hits,
        // so convert before any arithmetic.
        val converted = currencyConversionService.convertAmount(
            amount = effect.abs(),
            fromCurrency = currency,
            toCurrency = latestBalance.currency
        ).let { if (effect.signum() < 0) it.negate() else it }

        // On a credit card the balance counts what is owed, so spending has to add
        // to it and a payment has to subtract — the opposite of a bank account.
        val signed = if (latestBalance.isCreditCard) converted.negate() else converted
        val delta = if (reverse) signed.negate() else signed
        // Unclamped, matching calculateTransactionBalance: an overdrawn account
        // and an overpaid card are both real states, and clamping made reversals
        // silently lose money.
        val newBalance = latestBalance.balance + delta

        accountBalanceRepository.insertBalance(
            AccountBalanceEntity(
                bankName = bankName,
                accountLast4 = accountLast4,
                balance = newBalance,
                timestamp = LocalDateTime.now(),
                transactionId = null,
                creditLimit = latestBalance.creditLimit,
                isCreditCard = latestBalance.isCreditCard,
                iconResId = latestBalance.iconResId,
                iconName = latestBalance.iconName,
                isWallet = latestBalance.isWallet,
                color = latestBalance.color,
                currency = latestBalance.currency,
                sourceType = sourceType
            )
        )
    }

    /**
     * Resolves a transfer's destination from the last four digits.
     *
     * Transactions only store the destination's last four digits, with no bank,
     * so two accounts ending in the same digits are genuinely ambiguous. The
     * source account is excluded so a transfer never lands back on itself, and
     * a remaining ambiguity is logged rather than silently resolved.
     */
    private suspend fun findAccountByLast4(
        last4: String,
        excludeBank: String? = null,
        excludeLast4: String? = null
    ): AccountBalanceEntity? {
        val candidates = accountBalanceRepository.getAllLatestBalances().first()
            .filter { it.accountLast4 == last4 }
            .filterNot { it.bankName == excludeBank && it.accountLast4 == excludeLast4 }
        if (candidates.size > 1) {
            Log.w(
                "TransactionRepository",
                "Ambiguous transfer target: ${candidates.size} accounts end in $last4"
            )
        }
        return candidates.firstOrNull()
    }

    private suspend fun applyBalanceForTransaction(transaction: TransactionEntity) {
        applyTransactionToBalances(transaction, reverse = false, sourceType = "UNDO_REVERSAL")
    }

    suspend fun updateCategoryForMerchant(merchantName: String, newCategory: String) {
        transactionDao.updateCategoryForMerchant(merchantName, newCategory)
    }

    suspend fun updateCategoryAndSubcategoryForMerchantContains(merchantName: String, newCategory: String, newSubcategory: String?) {
        transactionDao.updateCategoryAndSubcategoryForMerchantContains(merchantName, newCategory, newSubcategory)
    }

    suspend fun getOtherTransactionCountForMerchant(merchantName: String, excludeId: Long): Int {
        return transactionDao.getTransactionCountForMerchant(merchantName, excludeId)
    }

    /** Returns non-deleted transactions whose merchant name contains the given keyword, excluding one id. */
    suspend fun getTransactionsByMerchantContains(
        merchantName: String,
        excludeId: Long
    ): List<TransactionEntity> {
        return transactionDao.getTransactionsByMerchantContains(merchantName, excludeId)
    }

    suspend fun searchTransactionsList(searchQuery: String): List<TransactionEntity> {
        return transactionDao.searchTransactionsList(searchQuery)
    }

    // Additional methods for Home screen
    data class MonthlyBreakdown(
            val total: BigDecimal,
            val income: BigDecimal,
            val expenses: BigDecimal
    )

    fun getCurrentMonthBreakdown(): Flow<MonthlyBreakdown> {
        val now = LocalDate.now()
        val startDate = now.withDayOfMonth(1).atStartOfDay()
        val endDate = now.atTime(23, 59, 59)

        return transactionDao.getTransactionsBetweenDates(startDate, endDate).map { transactions ->
            val income =
                    transactions.filter { it.transactionType == TransactionType.INCOME || it.transactionType == TransactionType.BORROWED }.fold(
                                    BigDecimal.ZERO
                            ) { acc, transaction -> acc + transaction.amount }
            val expenses =
                    transactions.filter { it.transactionType == TransactionType.EXPENSE || it.transactionType == TransactionType.LENT }.fold(
                                    BigDecimal.ZERO
                            ) { acc, transaction -> acc + transaction.amount }
            MonthlyBreakdown(total = income - expenses, income = income, expenses = expenses)
        }
    }

    fun getCurrentMonthTotal(): Flow<BigDecimal> {
        return getCurrentMonthBreakdown().map { it.total }
    }

    fun getLastMonthBreakdown(): Flow<MonthlyBreakdown> {
        val now = LocalDate.now()
        val dayOfMonth = now.dayOfMonth
        val lastMonth = now.minusMonths(1)

        // Compare same period: if today is 10th, compare 1st-10th of last month
        val startDate = lastMonth.withDayOfMonth(1).atStartOfDay()
        val lastMonthMaxDay = min(dayOfMonth, lastMonth.lengthOfMonth())
        val endDate = lastMonth.withDayOfMonth(lastMonthMaxDay).atTime(23, 59, 59)

        return transactionDao.getTransactionsBetweenDates(startDate, endDate).map { transactions ->
            val income =
                    transactions.filter { it.transactionType == TransactionType.INCOME || it.transactionType == TransactionType.BORROWED }.fold(
                                    BigDecimal.ZERO
                            ) { acc, transaction -> acc + transaction.amount }
            val expenses =
                    transactions.filter { it.transactionType == TransactionType.EXPENSE || it.transactionType == TransactionType.LENT }.fold(
                                    BigDecimal.ZERO
                            ) { acc, transaction -> acc + transaction.amount }
            MonthlyBreakdown(total = income - expenses, income = income, expenses = expenses)
        }
    }

    fun getLastMonthTotal(): Flow<BigDecimal> {
        return getLastMonthBreakdown().map { it.total }
    }

    // Currency-grouped breakdown methods
    fun getCurrentMonthBreakdownByCurrency(): Flow<Map<String, MonthlyBreakdown>> {
        val now = LocalDate.now()
        val startDate = now.withDayOfMonth(1).atStartOfDay()
        val endDate = now.atTime(23, 59, 59)

        return transactionDao.getTransactionsBetweenDates(startDate, endDate).map { transactions ->
            transactions.groupBy { it.currency }.mapValues { (_, currencyTransactions) ->
                val income =
                        currencyTransactions
                                .filter { it.transactionType == TransactionType.INCOME || it.transactionType == TransactionType.BORROWED }
                                .fold(BigDecimal.ZERO) { acc, transaction ->
                                    acc + transaction.amount
                                }
                val expenses =
                        currencyTransactions
                                .filter { it.transactionType == TransactionType.EXPENSE || it.transactionType == TransactionType.LENT }
                                .fold(BigDecimal.ZERO) { acc, transaction ->
                                    acc + transaction.amount
                                }
                MonthlyBreakdown(total = income - expenses, income = income, expenses = expenses)
            }
        }
    }

    fun getLastMonthBreakdownByCurrency(): Flow<Map<String, MonthlyBreakdown>> {
        val now = LocalDate.now()
        val dayOfMonth = now.dayOfMonth
        val lastMonth = now.minusMonths(1)

        // Compare same period: if today is 10th, compare 1st-10th of last month
        val startDate = lastMonth.withDayOfMonth(1).atStartOfDay()
        val lastMonthMaxDay = min(dayOfMonth, lastMonth.lengthOfMonth())
        val endDate = lastMonth.withDayOfMonth(lastMonthMaxDay).atTime(23, 59, 59)

        return transactionDao.getTransactionsBetweenDates(startDate, endDate).map { transactions ->
            transactions.groupBy { it.currency }.mapValues { (_, currencyTransactions) ->
                val income =
                        currencyTransactions
                                .filter { it.transactionType == TransactionType.INCOME || it.transactionType == TransactionType.BORROWED }
                                .fold(BigDecimal.ZERO) { acc, transaction ->
                                    acc + transaction.amount
                                }
                val expenses =
                        currencyTransactions
                                .filter { it.transactionType == TransactionType.EXPENSE || it.transactionType == TransactionType.LENT }
                                .fold(BigDecimal.ZERO) { acc, transaction ->
                                    acc + transaction.amount
                                }
                MonthlyBreakdown(total = income - expenses, income = income, expenses = expenses)
            }
        }
    }

    fun getCurrentYearBreakdownByCurrency(): Flow<Map<String, MonthlyBreakdown>> {
        val now = LocalDate.now()
        val startDate = now.withDayOfYear(1).atStartOfDay()
        val endDate = now.atTime(23, 59, 59)

        return transactionDao.getTransactionsBetweenDates(startDate, endDate).map { transactions ->
            transactions.groupBy { it.currency }.mapValues { (_, currencyTransactions) ->
                val income =
                    currencyTransactions
                        .filter { it.transactionType == TransactionType.INCOME || it.transactionType == TransactionType.BORROWED }
                        .fold(BigDecimal.ZERO) { acc, transaction ->
                            acc + transaction.amount
                        }
                val expenses =
                    currencyTransactions
                        .filter { it.transactionType == TransactionType.EXPENSE || it.transactionType == TransactionType.LENT }
                        .fold(BigDecimal.ZERO) { acc, transaction ->
                            acc + transaction.amount
                        }
                MonthlyBreakdown(total = income - expenses, income = income, expenses = expenses)
            }
        }
    }

    fun getRecentTransactions(limit: Int = 5): Flow<List<TransactionEntity>> {
        return transactionDao.getAllTransactions().map { transactions -> transactions
            .filter { it.transactionType != TransactionType.BALANCE_UPDATE }
            .take(limit)
        }
    }

    fun getTransactionsByAccount(
            bankName: String,
            accountLast4: String
    ): Flow<List<TransactionEntity>> {
        return transactionDao.getTransactionsByAccount(bankName, accountLast4)
    }

    fun getTransactionsByAccountAndDateRange(
            bankName: String,
            accountLast4: String,
            startDate: LocalDateTime,
            endDate: LocalDateTime
    ): Flow<List<TransactionEntity>> {
        return transactionDao.getTransactionsByAccountAndDateRange(
                bankName,
                accountLast4,
                startDate,
                endDate
        )
    }

    // Methods for batch rule application
    suspend fun getAllTransactionsList(): List<TransactionEntity> {
        // Get all non-deleted transactions as a list (not Flow) for batch processing
        // Use a large date range to get all transactions
        val startDate = LocalDateTime.of(2000, 1, 1, 0, 0)
        val endDate = LocalDateTime.now().plusYears(10)
        return transactionDao.getTransactionsBetweenDatesList(startDate, endDate)
    }

    suspend fun getUncategorizedTransactions(): List<TransactionEntity> {
        // Get all transactions without a category or with "Others" category
        return getAllTransactionsList().filter { transaction ->
            transaction.category.isNullOrBlank() || transaction.category == "Others"
        }
    }

    suspend fun updateAccountForTransactions(
            oldBankName: String,
            oldAccountNumber: String,
            newBankName: String,
            newAccountNumber: String
    ) {
        transactionDao.updateAccountForTransactions(
                oldBankName,
                oldAccountNumber,
                newBankName,
                newAccountNumber
        )
    }

    suspend fun updateTransactionsCategory(oldCategory: String, newCategory: String, newSubcategory: String?) {
        transactionDao.updateTransactionsCategory(oldCategory, newCategory, newSubcategory)
    }

    suspend fun getTransactionCountByCategory(category: String): Int {
        return transactionDao.getTransactionCountByCategory(category)
    }

    suspend fun findPotentialDuplicates(
        amount: BigDecimal,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): List<TransactionEntity> {
        return transactionDao.findPotentialDuplicates(startDate, endDate)
            .filter { it.amount.compareTo(amount) == 0 }
    }
}
