package com.ritesh.cashiro.testing

import com.ritesh.cashiro.data.database.dao.AccountBalanceDao
import com.ritesh.cashiro.data.database.dao.AccountBalanceTransactionInfo
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory stand-in for [AccountBalanceDao].
 *
 * Only the query methods are faked. The balance arithmetic that matters -
 * insertTransactionBalance and the formula it calls - is concrete on the DAO,
 * so tests built on this exercise the real logic rather than a reimplementation
 * of it. Shared between tests deliberately: a second copy of this fake is how
 * the two balanceEffect implementations drifted apart in the first place.
 */
internal class FakeAccountBalanceDao : AccountBalanceDao() {

    private val balances = mutableMapOf<Pair<String, String>, MutableList<AccountBalanceEntity>>()
    private var nextId = 1L

    fun seedBalance(entity: AccountBalanceEntity) {
        val key = Pair(entity.bankName, entity.accountLast4)
        balances.getOrPut(key) { mutableListOf() }.add(entity.copy(id = nextId++))
    }

    /** Seeds a credit card the user set up by hand, before any SMS arrived. */
    fun seedCreditCard(
        bankName: String,
        accountLast4: String,
        outstanding: String,
        limit: String,
        timestamp: LocalDateTime = LocalDateTime.of(2025, 1, 1, 0, 0)
    ) {
        seedBalance(
            AccountBalanceEntity(
                bankName = bankName,
                accountLast4 = accountLast4,
                balance = BigDecimal(outstanding),
                creditLimit = BigDecimal(limit),
                isCreditCard = true,
                timestamp = timestamp,
                sourceType = "MANUAL"
            )
        )
    }

    /** The most recent row for an account, or null when it has none. */
    suspend fun latest(bankName: String, accountLast4: String): AccountBalanceEntity? =
        getLatestBalance(bankName, accountLast4)

    fun rowsFor(bankName: String, accountLast4: String): List<AccountBalanceEntity> =
        balances[Pair(bankName, accountLast4)].orEmpty()

    override suspend fun insertBalance(balance: AccountBalanceEntity): Long {
        val key = Pair(balance.bankName, balance.accountLast4)
        val id = nextId++
        balances.getOrPut(key) { mutableListOf() }.add(balance.copy(id = id))
        return id
    }

    override suspend fun getLatestBalance(
        bankName: String,
        accountLast4: String
    ): AccountBalanceEntity? = balances[Pair(bankName, accountLast4)]?.maxByOrNull { it.timestamp }

    override suspend fun getLatestBalanceOnOrBefore(
        bankName: String,
        accountLast4: String,
        timestamp: LocalDateTime
    ): AccountBalanceEntity? = balances[Pair(bankName, accountLast4)]
        ?.filter { !it.timestamp.isAfter(timestamp) }
        ?.maxByOrNull { it.timestamp }

    override suspend fun getEarliestBalance(
        bankName: String,
        accountLast4: String
    ): AccountBalanceEntity? = balances[Pair(bankName, accountLast4)]?.minByOrNull { it.timestamp }

    override suspend fun updateBalance(balance: AccountBalanceEntity) {
        for (list in balances.values) {
            val index = list.indexOfFirst { it.id == balance.id }
            if (index != -1) {
                list[index] = balance
                return
            }
        }
    }

    override suspend fun updateBalanceById(id: Long, newBalance: BigDecimal) {
        for (list in balances.values) {
            val index = list.indexOfFirst { it.id == id }
            if (index != -1) {
                list[index] = list[index].copy(balance = newBalance)
                return
            }
        }
    }

    override suspend fun getBalanceById(id: Long): AccountBalanceEntity? {
        for (list in balances.values) {
            list.find { it.id == id }?.let { return it }
        }
        return null
    }

    override suspend fun getBalanceByTransactionId(transactionId: Long): AccountBalanceEntity? {
        for (list in balances.values) {
            list.find { it.transactionId == transactionId }?.let { return it }
        }
        return null
    }

    override suspend fun deleteBalanceById(id: Long) {
        for (list in balances.values) {
            if (list.removeAll { it.id == id }) return
        }
    }

    override suspend fun deleteAccount(bankName: String, accountLast4: String): Int {
        val key = Pair(bankName, accountLast4)
        val size = balances[key]?.size ?: 0
        balances.remove(key)
        return size
    }

    override suspend fun getBalanceCountForAccount(bankName: String, accountLast4: String): Int =
        balances[Pair(bankName, accountLast4)]?.size ?: 0

    override suspend fun updateAccountLast4(
        bankName: String,
        oldAccountLast4: String,
        newAccountLast4: String
    ): Int {
        val rows = balances.remove(Pair(bankName, oldAccountLast4)) ?: return 0
        val moved = rows.map { it.copy(accountLast4 = newAccountLast4) }.toMutableList()
        balances.getOrPut(Pair(bankName, newAccountLast4)) { mutableListOf() }.addAll(moved)
        return moved.size
    }

    override suspend fun setAccountCreditCardFlag(
        bankName: String,
        accountLast4: String,
        isCreditCard: Boolean
    ): Int {
        val rows = balances[Pair(bankName, accountLast4)] ?: return 0
        for (i in rows.indices) rows[i] = rows[i].copy(isCreditCard = isCreditCard)
        return rows.size
    }

    // ---- unused by these tests ----

    override suspend fun getAccountLast4sEndingWith(bankName: String, suffix: String): List<String> =
        emptyList()

    override suspend fun getBalancesAfterWithTransactions(
        bankName: String,
        accountLast4: String,
        timestamp: LocalDateTime
    ): List<AccountBalanceTransactionInfo> = emptyList()

    override fun getLatestBalanceFlow(
        bankName: String,
        accountLast4: String
    ): Flow<AccountBalanceEntity?> = flowOf(null)

    override fun getAllLatestBalances(): Flow<List<AccountBalanceEntity>> = flowOf(emptyList())

    override fun getAllBalances(): Flow<List<AccountBalanceEntity>> = flowOf(emptyList())

    override suspend fun deleteAllBalances() = Unit

    override suspend fun deleteSampleBalances() = Unit

    override fun getCurrentMonthLatestBalances(): Flow<List<AccountBalanceEntity>> =
        flowOf(emptyList())

    override fun getTotalBalance(): Flow<BigDecimal?> = flowOf(BigDecimal.ZERO)

    override fun getBalanceHistory(
        bankName: String,
        accountLast4: String,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): Flow<List<AccountBalanceEntity>> = flowOf(emptyList())

    override fun getAccountCount(): Flow<Int> = flowOf(0)

    override suspend fun deleteOldBalances(beforeDate: LocalDateTime): Int = 0

    override suspend fun deleteBalance(balance: AccountBalanceEntity) = Unit

    override suspend fun getBalanceHistoryForAccount(
        bankName: String,
        accountLast4: String
    ): List<AccountBalanceEntity> = emptyList()

    override suspend fun updateAccountBankName(
        oldBankName: String,
        accountLast4: String,
        newBankName: String
    ): Int = 0

    override suspend fun recalculateBalancesAfter(
        bankName: String,
        accountLast4: String,
        timestamp: LocalDateTime,
        startingBalance: BigDecimal
    ) = Unit

    override suspend fun getAccountByLast4(accountLast4: String): AccountBalanceEntity? = null
}
