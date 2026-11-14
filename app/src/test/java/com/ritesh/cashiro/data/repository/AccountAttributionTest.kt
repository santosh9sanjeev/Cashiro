package com.ritesh.cashiro.data.repository

import android.content.ContextWrapper
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import com.ritesh.cashiro.testing.FakeAccountBalanceDao
import com.ritesh.parser.core.ParsedTransaction
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.ritesh.parser.core.TransactionType as ParserTransactionType

/**
 * Which account an alert lands on when the bank name it carries is not the name
 * the user gave the account.
 *
 * Accounts are keyed by bank name and last four digits. A card created by hand
 * under a different spelling of the bank never matched its own alerts, so they
 * went to a second account under the parser's name and the card stayed at zero.
 */
class AccountAttributionTest {

    private val balanceDao = FakeAccountBalanceDao()
    private val accounts = AccountBalanceRepository(balanceDao, ContextWrapper(null))

    @Test
    fun `a card alert reaches the card set up under another bank name`() = runTest {
        balanceDao.seedCreditCard("My Card", "1234", outstanding = "0", limit = "5000")

        val resolved = accounts.resolveEntityAccountNumber(alert("Parsed Bank"), parsed("Parsed Bank", fromCard = true))

        assertEquals("My Card", resolved.bankName)
        assertEquals("1234", resolved.accountNumber)
    }

    @Test
    fun `an account under the parsed name is used as it is`() = runTest {
        balanceDao.seedCreditCard("My Card", "1234", outstanding = "0", limit = "5000")
        balanceDao.seedCreditCard("Parsed Bank", "1234", outstanding = "0", limit = "5000")

        val resolved = accounts.resolveEntityAccountNumber(alert("Parsed Bank"), parsed("Parsed Bank", fromCard = true))

        assertEquals("Parsed Bank", resolved.bankName)
    }

    @Test
    fun `two cards ending in the same digits are not guessed between`() = runTest {
        balanceDao.seedCreditCard("Card A", "1234", outstanding = "0", limit = "5000")
        balanceDao.seedCreditCard("Card B", "1234", outstanding = "0", limit = "5000")

        val resolved = accounts.resolveEntityAccountNumber(alert("Parsed Bank"), parsed("Parsed Bank", fromCard = true))

        assertEquals("Parsed Bank", resolved.bankName)
    }

    @Test
    fun `an account alert is never pinned on a credit card with the same digits`() = runTest {
        balanceDao.seedCreditCard("My Card", "1234", outstanding = "0", limit = "5000")

        val resolved = accounts.resolveEntityAccountNumber(alert("Parsed Bank"), parsed("Parsed Bank", fromCard = false))

        assertEquals("Parsed Bank", resolved.bankName)
    }

    private fun alert(bankName: String) = TransactionEntity(
        amount = BigDecimal("100"),
        merchantName = "Shop",
        category = "Shopping",
        transactionType = TransactionType.EXPENSE,
        dateTime = LocalDateTime.of(2026, 1, 1, 12, 0),
        bankName = bankName,
        accountNumber = "1234",
        transactionHash = "attribution-$bankName"
    )

    private fun parsed(bankName: String, fromCard: Boolean) = ParsedTransaction(
        amount = BigDecimal("100"),
        type = ParserTransactionType.EXPENSE,
        merchant = "Shop",
        reference = null,
        accountLast4 = "1234",
        balance = null,
        smsBody = "spend notification",
        sender = "BANKSMS",
        timestamp = 1_700_000_000_000L,
        bankName = bankName,
        isFromCard = fromCard,
        currency = "INR"
    )
}
