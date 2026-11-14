package com.ritesh.cashiro.utils

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Credit card maths, kept in one place so every screen agrees on it.
 *
 * The balance of a credit card account is the **outstanding** amount: spending
 * increases it, paying the bill decreases it, and a fully paid card sits at zero.
 * The credit limit is the total the card can hold, not the amount still available.
 */
object CreditCardUtils {

    /**
     * Outstanding as a percentage of the total credit limit, clamped to 0..100.
     *
     * Returns 0 when there is nothing outstanding or no usable limit on record.
     */
    fun utilizationPercent(outstanding: BigDecimal?, creditLimit: BigDecimal?): Int {
        if (creditLimit == null || creditLimit <= BigDecimal.ZERO) return 0
        val owed = outstanding ?: BigDecimal.ZERO
        if (owed <= BigDecimal.ZERO) return 0
        return owed.multiply(BigDecimal(100))
            .divide(creditLimit, 0, RoundingMode.HALF_UP)
            .toInt()
            .coerceIn(0, 100)
    }

    /**
     * Credit still available to spend: limit minus outstanding.
     *
     * Deliberately unclamped. An overpaid card carries a credit balance and has
     * more than its limit available; a card pushed over its limit has less than
     * nothing. Both are real states and hiding them misreports the card.
     */
    fun availableCredit(outstanding: BigDecimal?, creditLimit: BigDecimal?): BigDecimal {
        val limit = creditLimit ?: BigDecimal.ZERO
        return limit.subtract(outstanding ?: BigDecimal.ZERO)
    }
}
