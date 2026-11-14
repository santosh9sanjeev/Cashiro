package com.ritesh.cashiro.data.database.dao

import com.ritesh.cashiro.data.database.entity.TransactionType
import com.ritesh.cashiro.utils.CreditCardUtils
import java.math.BigDecimal
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Covers the arithmetic behind account balances.
 *
 * These exist because the balance formula compiled cleanly through four CI runs
 * while silently discarding every credit card transaction: a `.max(ZERO)` clamp
 * turned `0 - 500` back into `0`, so spending never registered. A green build
 * proves nothing about this code, so the behaviour is pinned here instead.
 */
class BalanceMathTest {

    private fun bd(v: String) = BigDecimal(v)

    private fun card(balance: String, amount: String, type: TransactionType) =
        calculateTransactionBalance(bd(balance), bd(amount), type, isCreditCard = true)

    private fun account(balance: String, amount: String, type: TransactionType) =
        calculateTransactionBalance(bd(balance), bd(amount), type, isCreditCard = false)

    // ---- credit cards: balance is an outstanding amount ----

    @Test
    fun `spending on a card increases the outstanding amount`() {
        assertEquals(bd("500"), card("0", "500", TransactionType.EXPENSE))
        assertEquals(bd("750"), card("500", "250", TransactionType.EXPENSE))
    }

    @Test
    fun `paying a card reduces the outstanding amount`() {
        assertEquals(bd("400"), card("500", "100", TransactionType.INCOME))
    }

    @Test
    fun `paying a card that owes nothing leaves a credit balance`() {
        // Previously clamped to zero, which made the payment disappear.
        assertEquals(bd("-100"), card("0", "100", TransactionType.INCOME))
    }

    @Test
    fun `overpaying a card leaves a credit balance`() {
        assertEquals(bd("-50"), card("100", "150", TransactionType.INCOME))
    }

    @Test
    fun `paying the exact outstanding amount clears the card`() {
        assertEquals(bd("0"), card("500", "500", TransactionType.INCOME))
    }

    // ---- bank accounts ----

    @Test
    fun `spending from an account reduces the balance`() {
        assertEquals(bd("400"), account("500", "100", TransactionType.EXPENSE))
    }

    @Test
    fun `spending more than the balance goes negative rather than vanishing`() {
        // The clamp here is what made expenses silently disappear at zero.
        assertEquals(bd("-500"), account("0", "500", TransactionType.EXPENSE))
    }

    @Test
    fun `income increases an account balance`() {
        assertEquals(bd("600"), account("500", "100", TransactionType.INCOME))
    }

    @Test
    fun `borrowing adds to an account balance`() {
        assertEquals(bd("600"), account("500", "100", TransactionType.BORROWED))
    }

    // ---- derived credit card figures ----

    @Test
    fun `utilisation is zero when nothing is owed`() {
        assertEquals(0, CreditCardUtils.utilizationPercent(bd("0"), bd("5000")))
    }

    @Test
    fun `utilisation rises with the outstanding amount`() {
        assertEquals(8, CreditCardUtils.utilizationPercent(bd("400"), bd("5000")))
        assertEquals(50, CreditCardUtils.utilizationPercent(bd("2500"), bd("5000")))
        assertEquals(100, CreditCardUtils.utilizationPercent(bd("5000"), bd("5000")))
    }

    @Test
    fun `utilisation stays zero for a card in credit`() {
        assertEquals(0, CreditCardUtils.utilizationPercent(bd("-100"), bd("5000")))
    }

    @Test
    fun `utilisation is zero when no limit is known`() {
        assertEquals(0, CreditCardUtils.utilizationPercent(bd("400"), null))
        assertEquals(0, CreditCardUtils.utilizationPercent(bd("400"), bd("0")))
    }

    @Test
    fun `available credit is the limit minus what is owed`() {
        assertEquals(bd("4600"), CreditCardUtils.availableCredit(bd("400"), bd("5000")))
        assertEquals(bd("5000"), CreditCardUtils.availableCredit(bd("0"), bd("5000")))
    }

    @Test
    fun `a card in credit has more than its limit available`() {
        assertEquals(bd("5100"), CreditCardUtils.availableCredit(bd("-100"), bd("5000")))
    }

    @Test
    fun `an over limit card reports negative available credit`() {
        assertEquals(bd("-200"), CreditCardUtils.availableCredit(bd("5200"), bd("5000")))
    }

    // ---- the exact sequence a user walks through ----

    @Test
    fun `spend then pay returns a card to zero and updates derived figures`() {
        val limit = bd("5000")
        var outstanding = bd("0")

        outstanding = calculateTransactionBalance(outstanding, bd("500"), TransactionType.EXPENSE, true)
        assertEquals(bd("500"), outstanding)
        assertEquals(10, CreditCardUtils.utilizationPercent(outstanding, limit))
        assertEquals(bd("4500"), CreditCardUtils.availableCredit(outstanding, limit))

        outstanding = calculateTransactionBalance(outstanding, bd("100"), TransactionType.INCOME, true)
        assertEquals(bd("400"), outstanding)
        assertEquals(8, CreditCardUtils.utilizationPercent(outstanding, limit))
        assertEquals(bd("4600"), CreditCardUtils.availableCredit(outstanding, limit))

        outstanding = calculateTransactionBalance(outstanding, bd("400"), TransactionType.INCOME, true)
        assertEquals(bd("0"), outstanding)
        assertEquals(0, CreditCardUtils.utilizationPercent(outstanding, limit))
        assertEquals(limit, CreditCardUtils.availableCredit(outstanding, limit))
    }
}
