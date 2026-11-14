package com.ritesh.cashiro.testing

import com.ritesh.cashiro.data.database.dao.TransactionDao
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory stand-in for [TransactionDao].
 *
 * Inserting, reading back by id, updating and soft-deleting behave like the
 * real table, because balance recalculation reads the current transaction and
 * edits are applied to the stored row before balances are brought in line.
 * Every query the balance code does not use returns nothing.
 */
internal class FakeTransactionDao : TransactionDao {
    /** Every transaction as it was first inserted, in order. */
    var insertedTransactions = mutableListOf<TransactionEntity>()

    /** The table as it stands now: updates and soft deletes land here. */
    private val stored = mutableListOf<TransactionEntity>()
    private var nextId = 1L

    fun byId(id: Long): TransactionEntity? = stored.firstOrNull { it.id == id }

    override suspend fun insertTransaction(transaction: TransactionEntity): Long {
        val id = nextId++
        val saved = transaction.copy(id = id)
        insertedTransactions.add(saved)
        stored.add(saved)
        return id
    }

    override suspend fun getTransactionByHash(transactionHash: String): TransactionEntity? =
        stored.firstOrNull { it.transactionHash == transactionHash }

    override fun getAllTransactions(): Flow<List<TransactionEntity>> = flowOf(emptyList())

    override suspend fun getAllTransactionsIncludingDeleted(): List<TransactionEntity> = stored.toList()
    override fun getTransactionCount(): Flow<Int> = flowOf(0)
    override suspend fun getTransactionById(transactionId: Long): TransactionEntity? = byId(transactionId)
    override fun getTransactionsBetweenDates(
        startDate: LocalDateTime, endDate: LocalDateTime
    ): Flow<List<TransactionEntity>> = flowOf(emptyList())
    override fun getTransactionsFiltered(
        startDate: LocalDateTime, endDate: LocalDateTime,
        currency: String, transactionType: TransactionType?
    ): Flow<List<TransactionEntity>> = flowOf(emptyList())
    override fun getTransactionsByType(type: TransactionType): Flow<List<TransactionEntity>> = flowOf(emptyList())
    override fun getTransactionsByCategory(category: String): Flow<List<TransactionEntity>> = flowOf(emptyList())
    override fun searchTransactions(searchQuery: String): Flow<List<TransactionEntity>> = flowOf(emptyList())
    override suspend fun searchTransactionsList(searchQuery: String): List<TransactionEntity> = emptyList()
    override fun getAllCategories(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun getTopCategoriesByUsage(limit: Int): List<String> = emptyList()
    override fun getAllMerchants(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun getTotalAmountByTypeAndPeriod(
        type: TransactionType, startDate: LocalDateTime, endDate: LocalDateTime
    ): Double? = null
    override suspend fun insertTransactions(transactions: List<TransactionEntity>) = Unit
    override suspend fun updateTransaction(transaction: TransactionEntity) {
        val index = stored.indexOfFirst { it.id == transaction.id }
        if (index != -1) stored[index] = transaction
    }
    override suspend fun deleteTransaction(transaction: TransactionEntity) = Unit
    override suspend fun deleteTransactionById(transactionId: Long) = Unit
    override suspend fun deleteAllTransactions() = Unit
    override suspend fun deleteSampleTransactions() = Unit
    override suspend fun updateCategoryForMerchant(merchantName: String, newCategory: String) = Unit
    override suspend fun updateCategoryAndSubcategoryForMerchantContains(
        merchantName: String, newCategory: String, newSubcategory: String?
    ) = Unit
    override suspend fun getTransactionCountForMerchant(merchantName: String, excludeId: Long): Int = 0
    override suspend fun getTransactionsByMerchantContains(
        merchantName: String, excludeId: Long
    ): List<TransactionEntity> = emptyList()
    override fun getAllCurrencies(): Flow<List<String>> = flowOf(emptyList())
    override fun getCurrenciesForPeriod(
        startDate: LocalDateTime, endDate: LocalDateTime
    ): Flow<List<String>> = flowOf(emptyList())
    override suspend fun softDeleteTransaction(transactionId: Long) {
        val index = stored.indexOfFirst { it.id == transactionId }
        if (index != -1) stored[index] = stored[index].copy(isDeleted = true)
    }
    override suspend fun softDeleteByHash(transactionHash: String) = Unit
    override suspend fun softDeleteTransactions(transactionIds: List<Long>) {
        transactionIds.forEach { softDeleteTransaction(it) }
    }
    override suspend fun deleteTransactionsByIds(transactionIds: List<Long>) = Unit
    override suspend fun getTransactionsByReferenceAndAmount(
        reference: String,
        amount: java.math.BigDecimal,
        accountLast4: String?,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): List<TransactionEntity> = emptyList()
    override suspend fun getTransactionsBetweenDatesList(
        startDate: LocalDateTime, endDate: LocalDateTime
    ): List<TransactionEntity> = emptyList()
    override fun getTransactionsByAccount(
        bankName: String, accountLast4: String
    ): Flow<List<TransactionEntity>> = flowOf(emptyList())
    override fun getTransactionsByAccountAndDateRange(
        bankName: String, accountLast4: String,
        startDate: LocalDateTime, endDate: LocalDateTime
    ): Flow<List<TransactionEntity>> = flowOf(emptyList())
    override suspend fun updateAccountForTransactions(
        oldBankName: String, oldAccountNumber: String,
        newBankName: String, newAccountNumber: String
    ) = Unit
    override suspend fun updateTransactionsCategory(
        oldCategory: String, newCategory: String, newSubcategory: String?
    ) = Unit
    override suspend fun getTransactionCountByCategory(category: String): Int = 0
    override suspend fun findPotentialDuplicates(
        startDate: LocalDateTime, endDate: LocalDateTime
    ): List<TransactionEntity> = emptyList()
    override suspend fun getTransactionsUpdatedBetween(
        updatedAfter: LocalDateTime, updatedBefore: LocalDateTime, currency: String
    ): List<TransactionEntity> = emptyList()
    override suspend fun getTransactionsBetweenDatesByCurrency(
        startDate: LocalDateTime, endDate: LocalDateTime, currency: String
    ): List<TransactionEntity> = emptyList()
}
