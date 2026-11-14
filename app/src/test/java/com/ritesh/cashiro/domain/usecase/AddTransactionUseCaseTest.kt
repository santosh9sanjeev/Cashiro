package com.ritesh.cashiro.domain.usecase

import android.content.ContextWrapper
import com.ritesh.cashiro.data.currency.CurrencyConverter
import com.ritesh.cashiro.data.database.dao.AccountBalanceDao
import com.ritesh.cashiro.data.database.dao.AccountBalanceTransactionInfo
import com.ritesh.cashiro.data.database.dao.SubscriptionDao
import com.ritesh.cashiro.data.database.dao.TransactionDao
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import com.ritesh.cashiro.data.repository.AccountBalanceRepository
import com.ritesh.cashiro.data.repository.SubscriptionRepository
import com.ritesh.cashiro.data.repository.TransactionRepository
import com.ritesh.cashiro.testing.FakeAccountBalanceDao
import com.ritesh.cashiro.testing.FakeSubscriptionDao
import com.ritesh.cashiro.testing.FakeTransactionDao
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddTransactionUseCaseTest {

    private val testBank = "Test Bank"
    private val testLast4 = "1234"
    private val testCurrency = "INR"
    private val baseTime = LocalDateTime.of(2025, 1, 15, 10, 30)
    private var transactionDao: FakeTransactionDao? = null

    @Test
    fun `CREDIT transaction adds to existing outstanding balance`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        val useCase = createUseCase(balanceDao)

        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = testBank,
                accountLast4 = testLast4,
                balance = BigDecimal("2000"),
                timestamp = baseTime.minusDays(1),
                isCreditCard = true,
                creditLimit = BigDecimal("50000")
            )
        )

        useCase.execute(
            amount = BigDecimal("500"),
            merchant = "Ice Cream Shop",
            category = "Food",
            type = TransactionType.CREDIT,
            date = baseTime,
            bankName = testBank,
            accountLast4 = testLast4,
            currency = testCurrency
        )

        val latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertNotNull(latest)
        assertEquals(BigDecimal("2500"), latest!!.balance)
        assertEquals(true, latest.isCreditCard)
        assertEquals(BigDecimal("50000"), latest.creditLimit)
    }

    @Test
    fun `CREDIT transaction creates first balance entry`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        val useCase = createUseCase(balanceDao)

        useCase.execute(
            amount = BigDecimal("750"),
            merchant = "Coffee Shop",
            category = "Food",
            type = TransactionType.CREDIT,
            date = baseTime,
            bankName = testBank,
            accountLast4 = testLast4,
            currency = testCurrency
        )

        val latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertNotNull(latest)
        assertEquals(BigDecimal("750"), latest!!.balance)
    }

    @Test
    fun `INCOME transaction adds to balance`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        val useCase = createUseCase(balanceDao)

        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = testBank,
                accountLast4 = testLast4,
                balance = BigDecimal("10000"),
                timestamp = baseTime.minusDays(1)
            )
        )

        useCase.execute(
            amount = BigDecimal("5000"),
            merchant = "Salary",
            category = "Income",
            type = TransactionType.INCOME,
            date = baseTime,
            bankName = testBank,
            accountLast4 = testLast4,
            currency = testCurrency
        )

        val latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertNotNull(latest)
        assertEquals(BigDecimal("15000"), latest!!.balance)
    }

    @Test
    fun `EXPENSE transaction subtracts from balance`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        val useCase = createUseCase(balanceDao)

        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = testBank,
                accountLast4 = testLast4,
                balance = BigDecimal("10000"),
                timestamp = baseTime.minusDays(1)
            )
        )

        useCase.execute(
            amount = BigDecimal("3000"),
            merchant = "Rent",
            category = "Housing",
            type = TransactionType.EXPENSE,
            date = baseTime,
            bankName = testBank,
            accountLast4 = testLast4,
            currency = testCurrency
        )

        val latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertNotNull(latest)
        assertEquals(BigDecimal("7000"), latest!!.balance)
    }

    @Test
    fun `INVESTMENT transaction subtracts from balance`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        val useCase = createUseCase(balanceDao)

        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = testBank,
                accountLast4 = testLast4,
                balance = BigDecimal("50000"),
                timestamp = baseTime.minusDays(1)
            )
        )

        useCase.execute(
            amount = BigDecimal("10000"),
            merchant = "Mutual Fund SIP",
            category = "Investment",
            type = TransactionType.INVESTMENT,
            date = baseTime,
            bankName = testBank,
            accountLast4 = testLast4,
            currency = testCurrency
        )

        val latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertNotNull(latest)
        assertEquals(BigDecimal("40000"), latest!!.balance)
    }

    @Test
    fun `CREDIT transaction preserves isCreditCard and creditLimit`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        val useCase = createUseCase(balanceDao)

        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = testBank,
                accountLast4 = testLast4,
                balance = BigDecimal("5000"),
                timestamp = baseTime.minusDays(1),
                isCreditCard = true,
                creditLimit = BigDecimal("100000")
            )
        )

        useCase.execute(
            amount = BigDecimal("1500"),
            merchant = "Electronics",
            category = "Shopping",
            type = TransactionType.CREDIT,
            date = baseTime,
            bankName = testBank,
            accountLast4 = testLast4,
            currency = testCurrency
        )

        val latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertEquals(BigDecimal("6500"), latest!!.balance)
        assertEquals(true, latest.isCreditCard)
        assertEquals(BigDecimal("100000"), latest.creditLimit)
    }

    @Test
    fun `CREDIT multiple transactions accumulate correctly`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        val useCase = createUseCase(balanceDao)

        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = testBank,
                accountLast4 = testLast4,
                balance = BigDecimal.ZERO,
                timestamp = baseTime.minusDays(2),
                isCreditCard = true,
                creditLimit = BigDecimal("50000")
            )
        )

        useCase.execute(
            amount = BigDecimal("200"),
            merchant = "Lunch",
            category = "Food",
            type = TransactionType.CREDIT,
            date = baseTime.minusDays(1),
            bankName = testBank,
            accountLast4 = testLast4,
            currency = testCurrency
        )

        useCase.execute(
            amount = BigDecimal("350"),
            merchant = "Dinner",
            category = "Food",
            type = TransactionType.CREDIT,
            date = baseTime,
            bankName = testBank,
            accountLast4 = testLast4,
            currency = testCurrency
        )

        val latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertEquals(BigDecimal("550"), latest!!.balance)
    }

    @Test
    fun `deleting CREDIT transaction reverses the balance`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        val (useCase, transactionRepo) = createUseCaseWithRepo(balanceDao)

        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = testBank,
                accountLast4 = testLast4,
                balance = BigDecimal("25000"),
                timestamp = baseTime.minusDays(1),
                isCreditCard = true,
                creditLimit = BigDecimal("50000")
            )
        )

        useCase.execute(
            amount = BigDecimal("5000"),
            merchant = "Ice Cream",
            category = "Food",
            type = TransactionType.CREDIT,
            date = baseTime,
            bankName = testBank,
            accountLast4 = testLast4,
            currency = testCurrency
        )

        var latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertEquals(BigDecimal("30000"), latest!!.balance)

        val transaction = transactionDao!!.insertedTransactions.last()
        transactionRepo.deleteTransaction(transaction)

        latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertEquals(BigDecimal("25000"), latest!!.balance)
    }

    @Test
    fun `deleting EXPENSE transaction reverses the balance`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        val (useCase, transactionRepo) = createUseCaseWithRepo(balanceDao)

        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = testBank,
                accountLast4 = testLast4,
                balance = BigDecimal("25000"),
                timestamp = baseTime.minusDays(1)
            )
        )

        useCase.execute(
            amount = BigDecimal("5000"),
            merchant = "Ice Cream",
            category = "Food",
            type = TransactionType.EXPENSE,
            date = baseTime,
            bankName = testBank,
            accountLast4 = testLast4,
            currency = testCurrency
        )

        var latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertEquals(BigDecimal("20000"), latest!!.balance)

        val transaction = transactionDao!!.insertedTransactions.last()
        transactionRepo.deleteTransaction(transaction)

        latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertEquals(BigDecimal("25000"), latest!!.balance)
    }

    @Test
    fun `undoing CREDIT delete re-applies the balance`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        val (useCase, transactionRepo) = createUseCaseWithRepo(balanceDao)

        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = testBank,
                accountLast4 = testLast4,
                balance = BigDecimal("25000"),
                timestamp = baseTime.minusDays(1),
                isCreditCard = true,
                creditLimit = BigDecimal("50000")
            )
        )

        useCase.execute(
            amount = BigDecimal("5000"),
            merchant = "Ice Cream",
            category = "Food",
            type = TransactionType.CREDIT,
            date = baseTime,
            bankName = testBank,
            accountLast4 = testLast4,
            currency = testCurrency
        )

        val transaction = transactionDao!!.insertedTransactions.last()
        transactionRepo.deleteTransaction(transaction)

        var latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertEquals(BigDecimal("25000"), latest!!.balance)

        transactionRepo.undoDeleteTransaction(
            transaction.copy(isDeleted = true)
        )

        latest = balanceDao.getLatestBalance(testBank, testLast4)
        assertEquals(BigDecimal("30000"), latest!!.balance)
    }

    private fun createUseCase(balanceDao: FakeAccountBalanceDao): AddTransactionUseCase {
        return createUseCaseWithRepo(balanceDao).first
    }

    private fun createUseCaseWithRepo(
        balanceDao: FakeAccountBalanceDao
    ): Pair<AddTransactionUseCase, TransactionRepository> {
        val context = ContextWrapper(null)
        val accountBalanceRepo = AccountBalanceRepository(balanceDao, context)
        val dao = FakeTransactionDao()
        transactionDao = dao
        // Recalculation reads each row's transaction, as the real query joins it.
        balanceDao.transactionLookup = { dao.byId(it) }
        val converter = FakeCurrencyConverter()
        val transactionRepo = TransactionRepository(dao, accountBalanceRepo, converter)
        val subscriptionDao = FakeSubscriptionDao()
        val subscriptionRepo = SubscriptionRepository(subscriptionDao)
        return Pair(
            AddTransactionUseCase(transactionRepo, subscriptionRepo, accountBalanceRepo, converter),
            transactionRepo
        )
    }

    /** Single-currency test data, so conversion is the identity. */
    private class FakeCurrencyConverter : CurrencyConverter {
        override suspend fun convertAmount(
            amount: BigDecimal,
            fromCurrency: String,
            toCurrency: String,
            forceRefresh: Boolean
        ): BigDecimal = amount
    }
}
