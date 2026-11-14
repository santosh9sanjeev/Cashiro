package com.ritesh.cashiro.data.manager

import android.content.ContextWrapper
import com.ritesh.cashiro.data.currency.CurrencyConverter
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.dao.CardDao
import com.ritesh.cashiro.data.database.entity.CardEntity
import com.ritesh.cashiro.data.database.entity.CardType
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.repository.AccountBalanceRepository
import com.ritesh.cashiro.data.repository.CardRepository
import com.ritesh.cashiro.testing.FakeAccountBalanceDao
import com.ritesh.cashiro.testing.FakeCardDao
import com.ritesh.parser.core.ParsedTransaction
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import com.ritesh.cashiro.data.database.entity.TransactionType as EntityTransactionType
import com.ritesh.parser.core.TransactionType as ParserTransactionType

/**
 * Covers how a card SMS is attributed to an account.
 *
 * An unlinked card used to fall through to null, which skipped the balance
 * write entirely: the transaction was saved and listed against the account
 * while the account itself stayed at zero. Card spending arrives as EXPENSE
 * rather than CREDIT, so cards were created as unlinked debit cards and nearly
 * every card message took that path.
 */
class BalanceUpdateProcessorTest {

    private val bank = "HDFC"
    private val cardLast4 = "1234"

    @Test
    fun `an unlinked card still produces a balance row`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        val processor = processorWith(balanceDao, FakeCardDao())

        processor.process(parsedCardSpend("500"), entity(), rowId = 1L)

        val written = balanceDao.latest(bank, cardLast4)
        assertNotNull(written, "an unlinked card must still update its account")
        assertEquals(cardLast4, written.accountLast4)
    }

    @Test
    fun `spending is attributed to the credit card account with those last four digits`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        // The user created this credit card by hand before any SMS arrived.
        balanceDao.seedCreditCard(bank, cardLast4, outstanding = "0", limit = "5000")
        val processor = processorWith(balanceDao, FakeCardDao())

        processor.process(parsedCardSpend("500"), entity(), rowId = 1L)

        val written = balanceDao.latest(bank, cardLast4)
        assertNotNull(written)
        // Spending raises the outstanding amount rather than leaving it at zero.
        assertEquals(BigDecimal("500"), written.balance)
        assertEquals(true, written.isCreditCard)
    }

    @Test
    fun `a linked debit card updates its account rather than the card number`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        val cardDao = FakeCardDao()
        cardDao.cards += CardEntity(
            id = 1L,
            cardLast4 = cardLast4,
            cardType = CardType.DEBIT,
            bankName = bank,
            accountLast4 = "9999"
        )
        val processor = processorWith(balanceDao, cardDao)

        processor.process(parsedCardSpend("500"), entity(), rowId = 1L)

        assertNotNull(balanceDao.latest(bank, "9999"))
    }

    @Test
    fun `a credit card filed as a linked debit card is charged on the card`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        // The user set the card up as a credit card...
        balanceDao.seedCreditCard(bank, cardLast4, outstanding = "0", limit = "5000")
        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = bank,
                accountLast4 = "9999",
                balance = BigDecimal("10000"),
                timestamp = LocalDateTime.of(2025, 1, 1, 0, 0),
                sourceType = "MANUAL"
            )
        )
        // ...but its first alert filed it as a debit card, since linked to the
        // bank account.
        val cardDao = FakeCardDao()
        cardDao.cards += CardEntity(
            id = 1L,
            cardLast4 = cardLast4,
            cardType = CardType.DEBIT,
            bankName = bank,
            accountLast4 = "9999"
        )
        val processor = processorWith(balanceDao, cardDao)

        processor.process(parsedCardSpend("500"), entity(), rowId = 1L)

        assertEquals(BigDecimal("500"), balanceDao.latest(bank, cardLast4)?.balance)
        assertEquals(BigDecimal("10000"), balanceDao.latest(bank, "9999")?.balance)
        val refiled = cardDao.cards.single()
        assertEquals(CardType.CREDIT, refiled.cardType)
        assertEquals(null, refiled.accountLast4)
    }

    @Test
    fun `a card linked to an account under another bank name books there and says so`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        // The user set the account up by hand under their own name for the bank.
        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = "My Bank",
                accountLast4 = "9999",
                balance = BigDecimal("10000"),
                timestamp = LocalDateTime.of(2025, 1, 1, 0, 0),
                sourceType = "MANUAL"
            )
        )
        val cardDao = FakeCardDao()
        cardDao.cards += CardEntity(
            id = 1L,
            cardLast4 = cardLast4,
            cardType = CardType.DEBIT,
            bankName = bank,
            accountLast4 = "9999"
        )
        val processor = processorWith(balanceDao, cardDao)

        val booked = processor.process(parsedCardSpend("500"), entity(), rowId = 1L)

        assertEquals(BalanceUpdateProcessor.BookedAccount("My Bank", "9999"), booked)
        assertEquals(BigDecimal("9500"), balanceDao.latest("My Bank", "9999")?.balance)
        assertEquals(null, balanceDao.latest(bank, "9999"), "no second account under the alert's bank name")
        assertEquals(null, balanceDao.latest(bank, cardLast4), "nothing booked to the card's own number")
    }

    @Test
    fun `a card linked to a credit card account is charged on that credit card`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        balanceDao.seedCreditCard(bank, "7777", outstanding = "100", limit = "5000")
        val cardDao = FakeCardDao()
        cardDao.cards += CardEntity(
            id = 1L,
            cardLast4 = cardLast4,
            cardType = CardType.CREDIT,
            bankName = bank,
            accountLast4 = "7777"
        )
        val processor = processorWith(balanceDao, cardDao)

        val booked = processor.process(parsedCardSpend("500"), entity(), rowId = 1L)

        assertEquals(BalanceUpdateProcessor.BookedAccount(bank, "7777"), booked)
        assertEquals(BigDecimal("600"), balanceDao.latest(bank, "7777")?.balance)
    }

    @Test
    fun `a credit card linked to a bank account still owes on itself`() = runTest {
        val balanceDao = FakeAccountBalanceDao()
        balanceDao.seedCreditCard(bank, cardLast4, outstanding = "0", limit = "5000")
        balanceDao.seedBalance(
            AccountBalanceEntity(
                bankName = bank,
                accountLast4 = "9999",
                balance = BigDecimal("10000"),
                timestamp = LocalDateTime.of(2025, 1, 1, 0, 0),
                sourceType = "MANUAL"
            )
        )
        val cardDao = FakeCardDao()
        cardDao.cards += CardEntity(
            id = 1L,
            cardLast4 = cardLast4,
            cardType = CardType.CREDIT,
            bankName = bank,
            accountLast4 = "9999"
        )
        val processor = processorWith(balanceDao, cardDao)

        val booked = processor.process(parsedCardSpend("500"), entity(), rowId = 1L)

        assertEquals(BalanceUpdateProcessor.BookedAccount(bank, cardLast4), booked)
        assertEquals(BigDecimal("500"), balanceDao.latest(bank, cardLast4)?.balance)
        assertEquals(BigDecimal("10000"), balanceDao.latest(bank, "9999")?.balance)
    }

    private fun processorWith(balanceDao: FakeAccountBalanceDao, cardDao: FakeCardDao) =
        BalanceUpdateProcessor(
            cardRepository = CardRepository(cardDao),
            accountBalanceRepository = AccountBalanceRepository(balanceDao, ContextWrapper(null)),
            currencyConversionService = IdentityConverter()
        )

    private fun parsedCardSpend(amount: String) = ParsedTransaction(
        amount = BigDecimal(amount),
        type = ParserTransactionType.EXPENSE,
        merchant = "Shop",
        reference = null,
        accountLast4 = cardLast4,
        balance = null,
        smsBody = "card spend notification",
        sender = "HDFCBK",
        timestamp = 1_700_000_000_000L,
        bankName = bank,
        isFromCard = true,
        currency = "INR"
    )

    private fun entity() = TransactionEntity(
        amount = BigDecimal("500"),
        merchantName = "Shop",
        category = "Shopping",
        transactionType = EntityTransactionType.EXPENSE,
        dateTime = LocalDateTime.of(2026, 1, 1, 12, 0),
        bankName = bank,
        accountNumber = cardLast4,
        // Required: the column has a database default but no Kotlin default.
        transactionHash = "test-card-spend",
        currency = "INR"
    )

    private class IdentityConverter : CurrencyConverter {
        override suspend fun convertAmount(
            amount: BigDecimal,
            fromCurrency: String,
            toCurrency: String,
            forceRefresh: Boolean
        ): BigDecimal = amount
    }
}
