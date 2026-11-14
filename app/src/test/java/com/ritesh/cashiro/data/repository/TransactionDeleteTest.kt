package com.ritesh.cashiro.data.repository

import android.content.ContextWrapper
import com.ritesh.cashiro.data.currency.CurrencyConverter
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import com.ritesh.cashiro.domain.usecase.AddTransactionUseCase
import com.ritesh.cashiro.testing.FakeAccountBalanceDao
import com.ritesh.cashiro.testing.FakeSubscriptionDao
import com.ritesh.cashiro.testing.FakeTransactionDao
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Deleting a transaction has to take its effect off every account it moved, and
 * undoing the delete has to put it back exactly.
 *
 * Each test starts from one current account holding 10000 and one credit card
 * with 500 outstanding on a 5000 limit.
 */
class TransactionDeleteTest {

    private val bank = "Test Bank"
    private val account = "1111"
    private val card = "2222"

    private val opened = LocalDateTime.of(2026, 1, 1, 9, 0)
    private val earlier = LocalDateTime.of(2026, 1, 3, 9, 0)
    private val paidAt = LocalDateTime.of(2026, 1, 5, 12, 0)
    private val later = LocalDateTime.of(2026, 1, 7, 18, 0)

    private val balanceDao = FakeAccountBalanceDao()
    private val transactionDao = FakeTransactionDao()
    private val converter = SameCurrency()
    private val accounts = AccountBalanceRepository(balanceDao, ContextWrapper(null))
    private val repository = TransactionRepository(transactionDao, accounts, converter)
    private val addTransaction = AddTransactionUseCase(
        repository,
        SubscriptionRepository(FakeSubscriptionDao()),
        accounts,
        converter
    )

    init {
        balanceDao.transactionLookup = { transactionDao.byId(it) }
        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = bank,
                accountLast4 = account,
                balance = BigDecimal("10000"),
                timestamp = opened,
                sourceType = "MANUAL"
            )
        )
        balanceDao.seedCreditCard(bank, card, outstanding = "500", limit = "5000", timestamp = opened)
    }

    @Test
    fun `deleting an expense gives the money back`() = runTest {
        val expense = record(TransactionType.EXPENSE, "100", paidAt)
        assertBalance("9900", account, "after the expense")

        repository.deleteTransaction(expense)

        assertBalance("10000", account, "the expense is undone")
    }

    @Test
    fun `deleting income takes the money away`() = runTest {
        val income = record(TransactionType.INCOME, "250", paidAt)
        assertBalance("10250", account, "after the income")

        repository.deleteTransaction(income)

        assertBalance("10000", account, "the income is undone")
    }

    @Test
    fun `deleting a card charge lowers what is owed`() = runTest {
        val charge = record(TransactionType.EXPENSE, "100", paidAt, on = card)
        assertBalance("600", card, "after the charge")

        repository.deleteTransaction(charge)

        assertBalance("500", card, "the charge is undone")
    }

    @Test
    fun `deleting a transfer undoes it on both accounts`() = runTest {
        val payment = recordTransfer("100", paidAt)
        assertBalance("9900", account, "after paying the card")
        assertBalance("400", card, "after paying the card")

        repository.deleteTransaction(payment)

        assertBalance("10000", account, "the money is back in the account")
        assertBalance("500", card, "the card owes what it did before")
    }

    @Test
    fun `deleting an older expense corrects every balance after it`() = runTest {
        val first = record(TransactionType.EXPENSE, "50", earlier)
        record(TransactionType.EXPENSE, "30", later)
        assertBalance("9920", account, "after both expenses")

        repository.deleteTransaction(first)

        assertBalance("9970", account, "only the later expense remains")
        val history = balanceDao.rowsFor(bank, account).sortedBy { it.timestamp }
        assertTrue(
            history.none { it.balance.compareTo(BigDecimal("9950")) == 0 },
            "no row still shows the deleted expense: ${history.map { it.balance.toPlainString() }}"
        )
    }

    @Test
    fun `undoing a delete puts every account back exactly`() = runTest {
        val payment = recordTransfer("100", paidAt)
        val alert = record(TransactionType.EXPENSE, "40", later, reportedBalance = "9850")
        assertBalance("9850", account, "the bank reported the balance after the alert")

        repository.deleteTransaction(payment)
        repository.deleteTransaction(alert)
        assertBalance("10000", account, "both deleted")
        assertBalance("500", card, "the payment is off the card")

        repository.undoDeleteTransaction(alert)
        repository.undoDeleteTransaction(payment)

        assertBalance("9850", account, "the reported balance is back")
        assertBalance("400", card, "the payment is back on the card")
    }

    @Test
    fun `deleting the same transaction twice gives the money back once`() = runTest {
        val expense = record(TransactionType.EXPENSE, "100", paidAt)

        repository.deleteTransaction(expense)
        repository.deleteTransaction(expense)

        assertBalance("10000", account, "returned once, not twice")
    }

    @Test
    fun `a transfer whose destination was never credited is only undone where it landed`() = runTest {
        // An alert for money sent out: it is recorded on the account it left and
        // never credited anywhere else.
        val id = transactionDao.insertTransaction(
            TransactionEntity(
                amount = BigDecimal("100"),
                merchantName = "Card payment",
                category = "Self Transfer",
                transactionType = TransactionType.TRANSFER,
                dateTime = paidAt,
                bankName = bank,
                accountNumber = account,
                toAccount = card,
                transactionHash = "alert-transfer"
            )
        )
        accounts.insertTransactionBalance(
            bankName = bank,
            accountLast4 = account,
            amount = BigDecimal("100"),
            transactionType = TransactionType.TRANSFER,
            explicitBalance = null,
            timestamp = paidAt,
            transactionId = id,
            creditLimit = null,
            isCreditCard = false,
            smsSource = null,
            currency = "INR"
        )
        val before = balanceDao.latest(bank, account)!!.balance

        repository.deleteTransaction(transactionDao.byId(id)!!)

        assertBalance(before.toPlainString(), account, "the account is as it was before the alert")
        assertBalance("500", card, "the card was never credited, so nothing is taken off it")
    }

    // ---- helpers ----

    /** A transaction plus the balance row the app writes for it. */
    private suspend fun record(
        type: TransactionType,
        amount: String,
        at: LocalDateTime,
        on: String = account,
        reportedBalance: String? = null
    ): TransactionEntity {
        val id = transactionDao.insertTransaction(
            TransactionEntity(
                amount = BigDecimal(amount),
                merchantName = "Shop",
                category = "Shopping",
                transactionType = type,
                dateTime = at,
                bankName = bank,
                accountNumber = on,
                balanceAfter = reportedBalance?.let { BigDecimal(it) },
                transactionHash = "txn-$on-$type-$amount-$at"
            )
        )
        val target = accounts.getLatestBalance(bank, on)!!
        accounts.insertTransactionBalance(
            bankName = bank,
            accountLast4 = on,
            amount = BigDecimal(amount),
            transactionType = type,
            explicitBalance = reportedBalance?.let { BigDecimal(it) },
            timestamp = at,
            transactionId = id,
            creditLimit = null,
            isCreditCard = target.isCreditCard,
            smsSource = null,
            currency = target.currency
        )
        return transactionDao.byId(id)!!
    }

    /** A transfer entered in the app: both legs, through the real add path. */
    private suspend fun recordTransfer(amount: String, at: LocalDateTime): TransactionEntity {
        val id = addTransaction.execute(
            amount = BigDecimal(amount),
            merchant = "Card payment",
            category = "Self Transfer",
            type = TransactionType.TRANSFER,
            date = at,
            bankName = bank,
            accountLast4 = account,
            currency = "INR",
            targetAccountBankName = bank,
            targetAccountLast4 = card
        )
        return transactionDao.byId(id)!!
    }

    private suspend fun assertBalance(expected: String, last4: String, what: String) {
        val actual = accounts.getLatestBalance(bank, last4)?.balance
        assertNotNull(actual, what)
        assertTrue(
            BigDecimal(expected).compareTo(actual) == 0,
            "$what: expected $expected, was ${actual.toPlainString()}"
        )
    }

    private class SameCurrency : CurrencyConverter {
        override suspend fun convertAmount(
            amount: BigDecimal,
            fromCurrency: String,
            toCurrency: String,
            forceRefresh: Boolean
        ): BigDecimal = amount
    }
}
