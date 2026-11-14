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
import java.math.RoundingMode
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Editing a transaction has to move its effect on every account it touches.
 *
 * Each test starts from one current account holding 10000 and one credit card
 * with 500 outstanding on a 5000 limit, then edits a transaction the way the
 * transaction screen saves it: the edited row is stored first, then balances are
 * brought in line.
 */
class TransactionEditRebalanceTest {

    private val bank = "Test Bank"
    private val account = "1111"
    private val card = "2222"

    private val opened = LocalDateTime.of(2026, 1, 1, 9, 0)
    private val earlier = LocalDateTime.of(2026, 1, 3, 9, 0)
    private val paidAt = LocalDateTime.of(2026, 1, 5, 12, 0)
    private val later = LocalDateTime.of(2026, 1, 7, 18, 0)

    private val balanceDao = FakeAccountBalanceDao()
    private val transactionDao = FakeTransactionDao()
    private val converter = TestRates()
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
    fun `turning an expense into a transfer to the card pays the card`() = runTest {
        val expense = recordExpense("100", paidAt)
        assertBalance("9900", account, "account after the expense")

        edit(expense) {
            it.copy(transactionType = TransactionType.TRANSFER, toAccount = card, category = "Self Transfer")
        }

        assertBalance("9900", account, "the money still left the account")
        assertBalance("400", card, "the card outstanding drops by the payment")
    }

    @Test
    fun `a transfer saved before the fix gets its card payment on the next save`() = runTest {
        // What the old edit code left behind: the account was handed the money
        // back and the card never saw the payment.
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
                transactionHash = "legacy-transfer"
            )
        )
        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = bank,
                accountLast4 = account,
                balance = BigDecimal("10000"),
                timestamp = paidAt,
                transactionId = id,
                sourceType = "TRANSACTION_CALCULATED"
            )
        )
        val transfer = transactionDao.byId(id)!!

        edit(transfer) { it }

        assertBalance("9900", account, "the account pays out")
        assertBalance("400", card, "the card is paid")
    }

    @Test
    fun `changing a transfer's amount moves both accounts`() = runTest {
        val transfer = recordTransfer("100", paidAt)
        assertBalance("9900", account, "account after the transfer")
        assertBalance("400", card, "card after the transfer")

        edit(transfer) { it.copy(amount = BigDecimal("150")) }

        assertBalance("9850", account, "account after the edit")
        assertBalance("350", card, "card after the edit")
    }

    @Test
    fun `changing a transfer's destination moves the payment to the new card`() = runTest {
        val otherCard = "3333"
        balanceDao.seedCreditCard(bank, otherCard, outstanding = "1000", limit = "8000", timestamp = opened)
        val transfer = recordTransfer("100", paidAt)

        edit(transfer) { it.copy(toAccount = otherCard) }

        assertBalance("500", card, "the first card gets its outstanding back")
        assertBalance("900", otherCard, "the new card is paid instead")
        assertBalance("9900", account, "the account is unaffected")
    }

    @Test
    fun `moving an expense from the account to the card moves its effect too`() = runTest {
        val expense = recordExpense("100", paidAt)

        edit(expense) { it.copy(accountNumber = card) }

        assertBalance("10000", account, "the account gets the money back")
        assertBalance("600", card, "the card outstanding rises by the spend")
    }

    @Test
    fun `changing only the category leaves every balance alone`() = runTest {
        val expense = recordExpense("100", paidAt)
        val accountRows = balanceDao.rowsFor(bank, account).toList()
        val cardRows = balanceDao.rowsFor(bank, card).toList()

        edit(expense) { it.copy(category = "Groceries") }

        assertEquals(accountRows, balanceDao.rowsFor(bank, account).toList())
        assertEquals(cardRows, balanceDao.rowsFor(bank, card).toList())
    }

    @Test
    fun `a balance the bank reported is kept when the expense becomes a transfer`() = runTest {
        // The bank's own figure includes a fee the app never saw.
        val expense = recordExpense("100", paidAt, reportedBalance = "9850")

        edit(expense) { it.copy(transactionType = TransactionType.TRANSFER, toAccount = card) }

        assertBalance("9850", account, "the account stays at what the bank reported")
        assertBalance("400", card, "the card is still paid")
    }

    @Test
    fun `an older alert arriving later does not turn a card payment into spending`() = runTest {
        recordTransfer("100", paidAt)

        // Alerts dated before the payment, processed after it.
        recordExpense("50", earlier, on = card)
        recordExpense("30", earlier)

        assertBalance("450", card, "500 + 50 spent - 100 paid")
        assertBalance("9870", account, "10000 - 30 spent - 100 paid out")
    }

    @Test
    fun `an older alert arriving later keeps a foreign purchase at its converted amount`() = runTest {
        val aedCard = "4444"
        balanceDao.seedCreditCard(
            bank,
            aedCard,
            outstanding = "0",
            limit = "5000",
            timestamp = opened,
            currency = "AED"
        )
        recordExpense("30", paidAt, on = aedCard, currency = "USD")
        assertBalance("110.10", aedCard, "30 USD at 3.67")

        recordExpense("50", earlier, on = aedCard, currency = "AED")

        assertBalance("160.10", aedCard, "50 AED plus the USD purchase still at 110.10")
    }

    @Test
    fun `moving a transfer to another day keeps both accounts right`() = runTest {
        val transfer = recordTransfer("100", paidAt)

        edit(transfer) { it.copy(dateTime = later) }

        assertBalance("9900", account, "the account still paid out once")
        assertBalance("400", card, "the card is still paid once")
        val legs = (balanceDao.rowsFor(bank, account) + balanceDao.rowsFor(bank, card))
            .filter { it.transactionId == transfer.id }
        assertEquals(2, legs.size)
        assertTrue(legs.all { it.timestamp == later }, "both rows moved to the new day")
    }

    @Test
    fun `two transactions at the same moment on one account both keep their rows`() = runTest {
        recordExpense("100", paidAt)
        recordExpense("200", paidAt)

        assertBalance("9700", account, "both expenses count")
        assertEquals(2, balanceDao.rowsFor(bank, account).count { it.transactionId != null })
    }

    @Test
    fun `editing a linked debit card's spend keeps it on the account it draws from`() = runTest {
        val spend = recordLinkedDebitCardSpend("100")

        edit(spend) { it.copy(amount = BigDecimal("150")) }

        assertBalance("9850", account, "the linked account carries the corrected amount")
        assertNull(accounts.getLatestBalance(bank, debitCard), "no account is invented for the card")
    }

    @Test
    fun `deleting a linked debit card's spend gives the account its money back`() = runTest {
        val spend = recordLinkedDebitCardSpend("100")

        repository.deleteTransaction(spend)

        assertBalance("10000", account, "the spend is undone on the account it came out of")
    }

    // ---- helpers ----

    private val debitCard = "5555"

    /** An alert-style expense: the transaction plus the one balance row the SMS path writes. */
    private suspend fun recordExpense(
        amount: String,
        at: LocalDateTime,
        on: String = account,
        currency: String = "INR",
        reportedBalance: String? = null
    ): TransactionEntity {
        val id = transactionDao.insertTransaction(
            TransactionEntity(
                amount = BigDecimal(amount),
                merchantName = "Shop",
                category = "Shopping",
                transactionType = TransactionType.EXPENSE,
                dateTime = at,
                bankName = bank,
                accountNumber = on,
                balanceAfter = reportedBalance?.let { BigDecimal(it) },
                transactionHash = "alert-$on-$amount-$at",
                currency = currency
            )
        )
        val target = accounts.getLatestBalance(bank, on)!!
        accounts.insertTransactionBalance(
            bankName = bank,
            accountLast4 = on,
            amount = converter.convertAmount(BigDecimal(amount), currency, target.currency),
            transactionType = TransactionType.EXPENSE,
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

    /** A debit card linked to the account: the alert names the card, the account moves. */
    private suspend fun recordLinkedDebitCardSpend(amount: String): TransactionEntity {
        val id = transactionDao.insertTransaction(
            TransactionEntity(
                amount = BigDecimal(amount),
                merchantName = "Shop",
                category = "Shopping",
                transactionType = TransactionType.EXPENSE,
                dateTime = paidAt,
                bankName = bank,
                accountNumber = debitCard,
                transactionHash = "debit-card-spend"
            )
        )
        accounts.insertTransactionBalance(
            bankName = bank,
            accountLast4 = account,
            amount = BigDecimal(amount),
            transactionType = TransactionType.EXPENSE,
            explicitBalance = null,
            timestamp = paidAt,
            transactionId = id,
            creditLimit = null,
            isCreditCard = false,
            smsSource = null,
            currency = "INR"
        )
        return transactionDao.byId(id)!!
    }

    /** A transfer entered in the app: both legs, through the real add path. */
    private suspend fun recordTransfer(amount: String, at: LocalDateTime, to: String = card): TransactionEntity {
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
            targetAccountLast4 = to
        )
        return transactionDao.byId(id)!!
    }

    /** What saving on the transaction screen does: store the edit, then rebalance. */
    private suspend fun edit(
        original: TransactionEntity,
        change: (TransactionEntity) -> TransactionEntity
    ): TransactionEntity {
        val edited = change(original)
        transactionDao.updateTransaction(edited)
        repository.rebalanceAfterEdit(original, edited)
        return edited
    }

    private suspend fun assertBalance(expected: String, last4: String, what: String) {
        val actual = accounts.getLatestBalance(bank, last4)?.balance
        assertNotNull(actual, what)
        assertTrue(
            BigDecimal(expected).compareTo(actual) == 0,
            "$what: expected $expected, was ${actual.toPlainString()}"
        )
    }

    /** Identity within a currency; USD to AED at a fixed 3.67. */
    private class TestRates : CurrencyConverter {
        override suspend fun convertAmount(
            amount: BigDecimal,
            fromCurrency: String,
            toCurrency: String,
            forceRefresh: Boolean
        ): BigDecimal = when {
            fromCurrency.equals(toCurrency, ignoreCase = true) -> amount
            fromCurrency == "USD" && toCurrency == "AED" ->
                amount.multiply(BigDecimal("3.67")).setScale(2, RoundingMode.HALF_UP)
            else -> amount
        }
    }
}
