package com.ritesh.cashiro.data.currency

import java.math.BigDecimal

/**
 * Converts an amount between currencies.
 *
 * The balance code needs nothing more than this, and depending on the narrow
 * interface rather than [CurrencyConversionService] keeps that code testable:
 * the full service needs a Context, a DAO and a network provider, none of which
 * belong in a unit test about arithmetic.
 */
interface CurrencyConverter {
    suspend fun convertAmount(
        amount: BigDecimal,
        fromCurrency: String,
        toCurrency: String,
        forceRefresh: Boolean = false
    ): BigDecimal
}
