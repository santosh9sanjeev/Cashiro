package com.ritesh.parser.core.bank

import com.ritesh.parser.core.TransactionType
import com.ritesh.parser.core.test.ExpectedTransaction
import com.ritesh.parser.core.test.ParserTestCase
import com.ritesh.parser.core.test.ParserTestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

/**
 * How FAB alerts name accounts, cards and payees.
 *
 * Card alerts name both the account and the card, and payment instructions name
 * the payee. Those are what tell a card purchase from a bank account debit, and a
 * credit card payment from spending.
 */
class FABAlertLayoutTest {

    private val parser = FABParser()

    private val creditCardPurchase = """
        Credit Card Purchase
        Debit
        Account XXXX0002
        Card XXXX1234
        AED 8.00
        TR              DUBAI           ARE
        23/09/25 16:17
        Available Balance AED 4530.16
    """.trimIndent()

    @TestFactory
    fun `fab alerts name the right account, card and payee`(): List<DynamicTest> {
        ParserTestUtils.printTestHeader(
            parserName = "First Abu Dhabi Bank",
            bankName = parser.getBankName(),
            currency = parser.getCurrency()
        )

        val testCases = listOf(
            ParserTestCase(
                name = "Payment instructions to a card",
                message = "Dear Customer, Your payment instructions of AED 150.00 to ***4321 has been processed on 01/11/2025 10:15",
                sender = "FAB",
                expected = ExpectedTransaction(
                    amount = BigDecimal("150.00"),
                    currency = "AED",
                    type = TransactionType.EXPENSE,
                    toAccount = "4321"
                )
            ),
            ParserTestCase(
                name = "Credit card purchase is filed under the card",
                message = creditCardPurchase,
                sender = "FAB",
                expected = ExpectedTransaction(
                    amount = BigDecimal("8.00"),
                    currency = "AED",
                    type = TransactionType.CREDIT,
                    accountLast4 = "1234",
                    creditLimit = BigDecimal("4530.16"),
                    isFromCard = true,
                    cardLast4 = "1234"
                )
            ),
            ParserTestCase(
                name = "Debit card purchase keeps the account and names the card",
                message = """
                    Debit Card Purchase
                    Debit
                    Account XXXX9876
                    Card XXXX2865
                    THB 1500.50
                    WWW.GRAB.COM          BANGKOK         TH
                    26/07/25 17:55
                    Available Balance AED 8500.25
                """.trimIndent(),
                sender = "FAB",
                expected = ExpectedTransaction(
                    amount = BigDecimal("1500.50"),
                    currency = "THB",
                    type = TransactionType.EXPENSE,
                    accountLast4 = "9876",
                    balance = BigDecimal("8500.25"),
                    isFromCard = true,
                    cardLast4 = "2865"
                )
            ),
            ParserTestCase(
                name = "Card alert naming only the card",
                message = "Debit Card Purchase Card No XXXX1234 AED 25.00 SHOP NAME 01/11/25 10:15 Available Balance AED 900.00",
                sender = "FAB",
                expected = ExpectedTransaction(
                    amount = BigDecimal("25.00"),
                    currency = "AED",
                    type = TransactionType.EXPENSE,
                    accountLast4 = "1234",
                    isFromCard = true,
                    cardLast4 = "1234"
                )
            )
        )

        return ParserTestUtils.runTestSuite(
            parser = parser,
            testCases = testCases,
            suiteName = "FAB alert layout"
        )
    }

    @Test
    fun `available credit on a credit card alert is not a balance`() {
        val parsed = parser.parse(creditCardPurchase, "FAB", 0L)
        assertNotNull(parsed)
        parsed!!
        assertNull(parsed.balance, "available credit must not become the card's outstanding amount")
        assertEquals(BigDecimal("4530.16"), parsed.creditLimit)
    }

    @Test
    fun `a payee masked down to fewer digits is not guessed`() {
        val parsed = parser.parse(
            "Dear Customer, Your payment instructions of AED 250.00 to 5xxx**1xxx has been processed on 10/09/2025 15:28",
            "FAB",
            0L
        )
        assertNotNull(parsed)
        assertNull(parsed!!.toAccount)
        assertEquals(TransactionType.EXPENSE, parsed.type)
    }
}
