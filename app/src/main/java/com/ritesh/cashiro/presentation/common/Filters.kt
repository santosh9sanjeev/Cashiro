package com.ritesh.cashiro.presentation.common

import androidx.annotation.StringRes
import com.ritesh.cashiro.R
import com.ritesh.cashiro.data.database.entity.TransactionType
import java.time.LocalDate
import java.time.YearMonth

enum class TimePeriod(@StringRes val labelRes: Int) {
    THIS_MONTH(R.string.time_period_this_month),
    LAST_MONTH(R.string.time_period_last_month),
    CURRENT_FY(R.string.time_period_current_fy),
    ALL(R.string.time_period_all_time),
    CUSTOM(R.string.time_period_custom_range)
}

enum class TransactionTypeFilter(@StringRes val labelRes: Int) {
    ALL(R.string.type_all),
    INCOME(R.string.type_income),
    EXPENSE(R.string.type_expense),
    CREDIT(R.string.type_credit),
    TRANSFER(R.string.type_transfer),
    INVESTMENT(R.string.type_investment),
    LENT(R.string.type_lent),
    BORROWED(R.string.type_borrowed)
}

/**
 * The types a transaction can be given. Credit card spending is an expense and
 * paying the card off is a transfer, so total spending counts each purchase
 * once; lending and borrowing are kept in the people ledger. Credit, Lent and
 * Borrowed are therefore not offered. Transactions already saved with one of
 * them keep it.
 */
val selectableTransactionTypes: List<TransactionType> = listOf(
    TransactionType.EXPENSE,
    TransactionType.INCOME,
    TransactionType.TRANSFER,
    TransactionType.INVESTMENT
)

/** The type filters offered, matching [selectableTransactionTypes]. */
val selectableTypeFilters: List<TransactionTypeFilter> = TransactionTypeFilter.entries.filter {
    it != TransactionTypeFilter.CREDIT &&
        it != TransactionTypeFilter.LENT &&
        it != TransactionTypeFilter.BORROWED
}

fun getDateRangeForPeriod(period: TimePeriod): Pair<LocalDate, LocalDate>? {
    val today = LocalDate.now()
    return when (period) {
        TimePeriod.THIS_MONTH -> {
            val start = YearMonth.now().atDay(1)
            start to today
        }
        TimePeriod.LAST_MONTH -> {
            val lastMonth = YearMonth.now().minusMonths(1)
            val start = lastMonth.atDay(1)
            val end = lastMonth.atEndOfMonth()
            start to end
        }
        TimePeriod.CURRENT_FY -> {
            // Indian Financial Year: April 1 to March 31
            val currentYear = today.year
            val currentMonth = today.monthValue
            val fyStart = if (currentMonth >= 4) {
                LocalDate.of(currentYear, 4, 1)  // Apr 1 of current year
            } else {
                LocalDate.of(currentYear - 1, 4, 1)  // Apr 1 of previous year
            }
            fyStart to today
        }
        TimePeriod.ALL -> {
            // Use a reasonable date range for "All Time" - 10 years back to today
            val start = today.minusYears(10)
            start to today
        }
        TimePeriod.CUSTOM -> {
            // Custom range is handled separately in ViewModel
            null
        }
    }
}