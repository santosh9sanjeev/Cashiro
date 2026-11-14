package com.ritesh.cashiro.data.repository

import com.ritesh.cashiro.data.backup.sanitize
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.entity.CardEntity
import com.ritesh.cashiro.data.database.entity.CardType
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Which currency amounts are shown in, which account a linked card books to,
 * and that a restored transaction keeps its payment reference.
 */
class DisplayCurrencyAndCardLinkTest {

    // ---- display currency ----

    @Test
    fun `the main account's currency is used when one is set`() {
        val accounts = listOf(account("Bank", "1111", "AED"), account("Bank", "2222", "USD"))

        assertEquals("USD", resolveBaseCurrency("Bank_2222", accounts, preferred = "INR"))
    }

    @Test
    fun `without a main account the accounts' own currency is used, not INR`() {
        val accounts = listOf(
            account("Bank", "1111", "AED"),
            account("Bank", "2222", "AED", isCreditCard = true),
            account("Cash", "wallet", "INR", isWallet = true)
        )

        assertEquals("AED", resolveBaseCurrency(null, accounts, preferred = "INR"))
    }

    @Test
    fun `a main account that no longer exists falls back to the accounts' currency`() {
        val accounts = listOf(account("Bank", "1111", "AED"))

        assertEquals("AED", resolveBaseCurrency("Old Name_9999", accounts, preferred = "INR"))
    }

    @Test
    fun `with no accounts the stored preference is used`() {
        assertEquals("EUR", resolveBaseCurrency(null, emptyList(), preferred = "EUR"))
    }

    @Test
    fun `a tie between currencies goes to the stored preference`() {
        val accounts = listOf(account("Bank", "1111", "AED"), account("Bank", "2222", "USD"))

        assertEquals("USD", resolveBaseCurrency(null, accounts, preferred = "USD"))
    }

    // ---- card links ----

    @Test
    fun `a linked card books to the account under its own bank name first`() {
        val accounts = listOf(account("Other Bank", "9999", "AED"), account("Bank", "9999", "AED"))

        assertEquals("Bank", linkedAccountOf(card(linkedTo = "9999"), accounts)?.bankName)
    }

    @Test
    fun `a linked card finds its account under a different bank name`() {
        val accounts = listOf(account("My Account", "9999", "AED"))

        assertEquals("My Account", linkedAccountOf(card(linkedTo = "9999"), accounts)?.bankName)
    }

    @Test
    fun `two accounts with the link's digits under other names are not guessed between`() {
        val accounts = listOf(account("First", "9999", "AED"), account("Second", "9999", "AED"))

        assertNull(linkedAccountOf(card(linkedTo = "9999"), accounts))
    }

    @Test
    fun `a credit card is never booked to a bank account`() {
        val accounts = listOf(account("Bank", "9999", "AED"))

        assertNull(linkedAccountOf(card(linkedTo = "9999", type = CardType.CREDIT), accounts))
    }

    @Test
    fun `a card linked to a credit card account books to it`() {
        val accounts = listOf(account("Bank", "7777", "AED", isCreditCard = true))

        assertEquals("7777", linkedAccountOf(card(linkedTo = "7777"), accounts)?.accountLast4)
        assertEquals("7777", linkedAccountOf(card(linkedTo = "7777", type = CardType.CREDIT), accounts)?.accountLast4)
    }

    @Test
    fun `an unlinked card waits for a link unless it is a credit card account itself`() {
        val ownCreditAccount = listOf(account("Bank", "1234", "AED", isCreditCard = true))
        val ownPlainAccount = listOf(account("Bank", "1234", "AED"))

        assertTrue(isAwaitingLink(card(linkedTo = null), emptyList()))
        assertTrue(isAwaitingLink(card(linkedTo = null), ownPlainAccount))
        assertFalse(isAwaitingLink(card(linkedTo = null, type = CardType.CREDIT), ownCreditAccount))
        assertFalse(isAwaitingLink(card(linkedTo = "9999"), emptyList()))
    }

    // ---- backup ----

    @Test
    fun `a restored transaction keeps its payment reference`() {
        val transaction = TransactionEntity(
            amount = BigDecimal("100"),
            merchantName = "Shop",
            category = "Shopping",
            transactionType = TransactionType.EXPENSE,
            dateTime = LocalDateTime.of(2026, 1, 1, 12, 0),
            transactionHash = "hash",
            reference = "REF000111"
        )

        assertEquals("REF000111", transaction.sanitize().reference)
    }

    private fun account(
        bankName: String,
        last4: String,
        currency: String,
        isCreditCard: Boolean = false,
        isWallet: Boolean = false
    ) = AccountBalanceEntity(
        bankName = bankName,
        accountLast4 = last4,
        balance = BigDecimal("100"),
        timestamp = LocalDateTime.of(2026, 1, 1, 0, 0),
        isCreditCard = isCreditCard,
        isWallet = isWallet,
        currency = currency
    )

    private fun card(linkedTo: String?, type: CardType = CardType.DEBIT) = CardEntity(
        id = 1L,
        cardLast4 = "1234",
        cardType = type,
        bankName = "Bank",
        accountLast4 = linkedTo
    )
}
