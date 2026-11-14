package com.ritesh.cashiro.data.manager

import android.content.ContextWrapper
import com.ritesh.cashiro.data.currency.CurrencyConverter
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.entity.CardEntity
import com.ritesh.cashiro.data.database.entity.CardType
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import com.ritesh.cashiro.data.mapper.toEntity
import com.ritesh.cashiro.data.repository.AccountBalanceRepository
import com.ritesh.cashiro.data.repository.CardRepository
import com.ritesh.cashiro.data.repository.SubscriptionRepository
import com.ritesh.cashiro.data.repository.TransactionRepository
import com.ritesh.cashiro.domain.usecase.AddTransactionUseCase
import com.ritesh.cashiro.testing.FakeAccountBalanceDao
import com.ritesh.cashiro.testing.FakeCardDao
import com.ritesh.cashiro.testing.FakeSubscriptionDao
import com.ritesh.cashiro.testing.FakeTransactionDao
import com.ritesh.parser.core.bank.FABParser
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * First Abu Dhabi Bank alerts, from the SMS text to the balances they move.
 *
 * Each alert goes through the real parser, the real attribution and the real
 * booking, the way a received SMS does. The user set their accounts up by hand
 * under their own name for the bank ("FAB"), not the name on the alerts.
 */
class FabAlertBookingTest {

    private val userBank = "FAB"
    private val alertBank = "First Abu Dhabi Bank"
    private val account = "0002"
    private val cardBank = "FAB Credit"
    private val card = "4321"

    private val opened = LocalDateTime.of(2026, 11, 1, 9, 0)

    private val balanceDao = FakeAccountBalanceDao()
    private val transactionDao = FakeTransactionDao()
    private val cardDao = FakeCardDao()
    private val converter = SameCurrency()
    private val accounts = AccountBalanceRepository(balanceDao, ContextWrapper(null))
    private val repository = TransactionRepository(transactionDao, accounts, converter)
    private val processor = BalanceUpdateProcessor(CardRepository(cardDao), accounts, converter)

    init {
        balanceDao.transactionLookup = { transactionDao.byId(it) }
    }

    @Test
    fun `paying the user's own card takes it from the account and off the card`() = runTest {
        openAccount(account, "10000")
        openCreditCard(outstanding = "500")

        val payment = receive(
            "Dear Customer, Your payment instructions of AED 150.00 to ***$card has been processed on 14/11/2026 21:37",
            at = LocalDateTime.of(2026, 11, 14, 21, 37)
        )

        assertBalance("9850", userBank, account, "the account paid the card")
        assertBalance("350", cardBank, card, "the payment comes off what the card owes")
        assertEquals(TransactionType.TRANSFER, payment.transactionType)
        assertEquals(userBank, payment.bankName)
        assertEquals(account, payment.accountNumber)
        assertEquals(account, payment.fromAccount)
        assertEquals(card, payment.toAccount)
        assertNull(balanceDao.latest(alertBank, card), "no second card under the alert's bank name")
    }

    @Test
    fun `deleting a card payment puts both the account and the card back`() = runTest {
        openAccount(account, "10000")
        openCreditCard(outstanding = "500")
        val payment = receive(
            "Dear Customer, Your payment instructions of AED 150.00 to ***$card has been processed on 14/11/2026 21:37",
            at = LocalDateTime.of(2026, 11, 14, 21, 37)
        )

        repository.deleteTransaction(payment)

        assertBalance("10000", userBank, account, "the money is back in the account")
        assertBalance("500", cardBank, card, "the card owes what it did before")
    }

    @Test
    fun `paying someone else is spent from the account`() = runTest {
        openAccount(account, "10000")
        openCreditCard(outstanding = "500")

        val payment = receive(
            "Dear Customer, Your payment instructions of AED 150.00 to ***9999 has been processed on 14/11/2026 21:37",
            at = LocalDateTime.of(2026, 11, 14, 21, 37)
        )

        assertBalance("9850", userBank, account, "the account paid")
        assertBalance("500", cardBank, card, "the card is not involved")
        assertEquals(TransactionType.EXPENSE, payment.transactionType)
        assertEquals(account, payment.accountNumber)
    }

    @Test
    fun `a debit card purchase comes out of the user's account`() = runTest {
        openAccount(account, "10000")

        val purchase = receive(
            debitCardPurchase(amount = "25.00", balanceAfter = "9975.00"),
            at = LocalDateTime.of(2026, 11, 14, 10, 15)
        )

        assertBalance("9975", userBank, account, "the bank's balance after the purchase")
        assertEquals(userBank, purchase.bankName)
        assertEquals(account, purchase.accountNumber)
        assertNull(balanceDao.latest(alertBank, account), "no second account under the alert's bank name")
        assertNull(balanceDao.latest(userBank, "2865"), "nothing booked to the card's own number")
    }

    @Test
    fun `a debit card linked to an account is taken from that account`() = runTest {
        // The user's account is filed under a number of their own; they linked
        // the card to it.
        openAccount("7777", "10000")
        cardDao.cards += CardEntity(
            id = 1L,
            cardLast4 = "2865",
            cardType = CardType.DEBIT,
            bankName = alertBank,
            accountLast4 = "7777"
        )

        val first = receive(
            debitCardPurchase(amount = "25.00", balanceAfter = "9975.00"),
            at = LocalDateTime.of(2026, 11, 14, 10, 15)
        )
        receive(
            debitCardPurchase(amount = "40.00", balanceAfter = "9935.00", time = "14/11/26 11:30"),
            at = LocalDateTime.of(2026, 11, 14, 11, 30)
        )

        assertBalance("9935", userBank, "7777", "both purchases came out of the linked account")
        assertEquals("7777", first.accountNumber)
        assertNull(balanceDao.latest(alertBank, account), "no account made up from the alert's number")
        assertNull(balanceDao.latest(alertBank, "2865"), "nothing booked to the card's own number")
    }

    @Test
    fun `a credit card purchase is owed on the card, not taken from the account`() = runTest {
        openAccount(account, "10000")
        openCreditCard(outstanding = "500")

        val purchase = receive(
            """
            Credit Card Purchase
            Debit
            Account XXXX$account
            Card XXXX$card
            AED 80.00
            SHOP NAME          DUBAI           ARE
            14/11/26 12:00
            Available Balance AED 4420.00
            """.trimIndent(),
            at = LocalDateTime.of(2026, 11, 14, 12, 0)
        )

        assertBalance("580", cardBank, card, "the purchase adds to what the card owes")
        assertBalance("10000", userBank, account, "the bank account is untouched")
        assertEquals(card, purchase.accountNumber)
        val limit = assertNotNull(balanceDao.latest(cardBank, card)?.creditLimit, "the card keeps a limit")
        assertTrue(
            BigDecimal("5000").compareTo(limit) == 0,
            "the card's limit is not replaced by the available credit: was ${limit.toPlainString()}"
        )
    }

    @Test
    fun `a payment synced after the user set the balance still comes out of the account`() = runTest {
        openAccount(account, "5000")
        openCreditCard(outstanding = "500")
        // Late in the evening the user sets the account to 1000, then syncs the
        // messages, including a card payment made earlier that evening.
        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = userBank,
                accountLast4 = account,
                balance = BigDecimal("1000"),
                timestamp = LocalDateTime.of(2026, 11, 14, 23, 30),
                sourceType = "MANUAL",
                currency = "AED"
            )
        )

        receive(
            "Dear Customer, Your payment instructions of AED 100.00 to ***$card has been processed on 14/11/2026 21:37",
            at = LocalDateTime.of(2026, 11, 14, 21, 37)
        )

        assertBalance("900", userBank, account, "the payment comes out of the balance the user set")
        assertBalance("400", cardBank, card, "and off what the card owes")
    }

    @Test
    fun `with two bank accounts the one named like the bank pays`() = runTest {
        openAccount(account, "10000")
        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = "Other Bank",
                accountLast4 = "3333",
                balance = BigDecimal("2000"),
                timestamp = opened,
                sourceType = "MANUAL",
                currency = "AED"
            )
        )
        openCreditCard(outstanding = "500")

        receive(
            "Dear Customer, Your payment instructions of AED 150.00 to ***$card has been processed on 14/11/2026 21:37",
            at = LocalDateTime.of(2026, 11, 14, 21, 37)
        )

        assertBalance("9850", userBank, account, "the FAB account paid")
        assertBalance("2000", "Other Bank", "3333", "the other bank's account is untouched")
        assertBalance("350", cardBank, card, "the card was paid")
    }

    @Test
    fun `deleting an older transaction moves a balance the user set by its amount`() = runTest {
        openAccount(account, "10000")
        val addTransaction = AddTransactionUseCase(
            repository,
            SubscriptionRepository(FakeSubscriptionDao()),
            accounts,
            converter
        )
        val olderId = addTransaction.execute(
            amount = BigDecimal("100"),
            merchant = "Shop",
            category = "Shopping",
            type = TransactionType.EXPENSE,
            date = LocalDateTime.of(2026, 11, 3, 12, 0),
            bankName = userBank,
            accountLast4 = account,
            currency = "AED"
        )
        assertBalance("9900", userBank, account, "after the older expense")

        // The user checks the bank and sets the balance to what it really is.
        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = userBank,
                accountLast4 = account,
                balance = BigDecimal("9000"),
                timestamp = LocalDateTime.of(2026, 11, 10, 9, 0),
                sourceType = "MANUAL",
                currency = "AED"
            )
        )
        addTransaction.execute(
            amount = BigDecimal("50"),
            merchant = "Shop",
            category = "Shopping",
            type = TransactionType.EXPENSE,
            date = LocalDateTime.of(2026, 11, 12, 12, 0),
            bankName = userBank,
            accountLast4 = account,
            currency = "AED"
        )
        assertBalance("8950", userBank, account, "a new expense applies on top of the correction")

        repository.deleteTransaction(transactionDao.byId(olderId)!!)

        assertBalance("9050", userBank, account, "the deleted expense's 100 comes back, on top of the user's figure")
    }

    // ---- helpers ----

    /** What the app does with a received SMS: parse, attribute, save, book, refile. */
    private suspend fun receive(sms: String, at: LocalDateTime): TransactionEntity {
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val parsed = assertNotNull(FABParser().parse(sms, "FAB", millis), "the alert parses")
        val entity = accounts.resolveEntityAccountNumber(parsed.toEntity(), parsed)
        val id = transactionDao.insertTransaction(entity)
        val saved = entity.copy(id = id)
        val booked = processor.process(parsed, saved, id)
        val filed = if (booked != null) refileAsBooked(saved, booked) else saved
        if (filed != saved) transactionDao.updateTransaction(filed)
        return filed
    }

    private fun debitCardPurchase(amount: String, balanceAfter: String, time: String = "14/11/26 10:15") =
        """
        Debit Card Purchase
        Debit
        Account XXXX$account
        Card XXXX2865
        AED $amount
        SHOP NAME          DUBAI           ARE
        $time
        Available Balance AED $balanceAfter
        """.trimIndent()

    private fun openAccount(last4: String, balance: String) {
        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = userBank,
                accountLast4 = last4,
                balance = BigDecimal(balance),
                timestamp = opened,
                sourceType = "MANUAL",
                currency = "AED"
            )
        )
    }

    private fun openCreditCard(outstanding: String) {
        balanceDao.seedCreditCard(
            cardBank,
            card,
            outstanding = outstanding,
            limit = "5000",
            timestamp = opened,
            currency = "AED"
        )
    }

    private suspend fun assertBalance(expected: String, bankName: String, last4: String, what: String) {
        val actual = balanceDao.latest(bankName, last4)?.balance
        assertNotNull(actual, "$what: no balance for $bankName $last4")
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
