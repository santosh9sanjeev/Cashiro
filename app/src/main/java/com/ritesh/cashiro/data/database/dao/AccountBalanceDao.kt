package com.ritesh.cashiro.data.database.dao

import androidx.room.*
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.LocalDateTime

private const val SOURCE_TRANSACTION_CALCULATED = "TRANSACTION_CALCULATED"
internal const val SOURCE_TRANSACTION_SMS_BALANCE = "TRANSACTION_SMS_BALANCE"
private const val SOURCE_MANUAL = "MANUAL"
private const val SOURCE_MANUAL_EDIT = "MANUAL_EDIT"
internal const val SOURCE_SMS_BALANCE = "SMS_BALANCE"

@Dao
abstract class AccountBalanceDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertBalance(balance: AccountBalanceEntity): Long
    
    @Query("""
        SELECT * FROM account_balances 
        WHERE bank_name = :bankName AND account_last4 = :accountLast4
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    abstract suspend fun getLatestBalance(bankName: String, accountLast4: String): AccountBalanceEntity?

    @Query("""
        SELECT DISTINCT account_last4 FROM account_balances
        WHERE bank_name = :bankName
        AND LENGTH(account_last4) >= 4
        AND account_last4 LIKE '%' || :suffix
    """)
    abstract suspend fun getAccountLast4sEndingWith(bankName: String, suffix: String): List<String>

    @Query("""
        SELECT * FROM account_balances
        WHERE bank_name = :bankName AND account_last4 = :accountLast4
        AND timestamp <= :timestamp
        ORDER BY timestamp DESC, id DESC
        LIMIT 1
    """)
    abstract suspend fun getLatestBalanceOnOrBefore(
        bankName: String,
        accountLast4: String,
        timestamp: LocalDateTime
    ): AccountBalanceEntity?

    @Query("""
        SELECT
            ab.id AS id,
            ab.balance AS balance,
            ab.source_type AS sourceType,
            ab.is_credit_card AS isCreditCard,
            ab.transaction_id AS transactionId,
            t.amount AS transactionAmount,
            t.transaction_type AS transactionType,
            t.balance_after AS transactionBalanceAfter,
            t.is_deleted AS isDeleted,
            ab.currency AS accountCurrency,
            t.currency AS transactionCurrency,
            t.bank_name AS transactionBankName,
            t.account_number AS transactionAccountNumber
        FROM account_balances ab
        LEFT JOIN transactions t ON t.id = ab.transaction_id
        WHERE ab.bank_name = :bankName AND ab.account_last4 = :accountLast4
        AND ab.timestamp > :timestamp
        ORDER BY ab.timestamp ASC, ab.id ASC
    """)
    abstract suspend fun getBalancesAfterWithTransactions(
        bankName: String,
        accountLast4: String,
        timestamp: LocalDateTime
    ): List<AccountBalanceTransactionInfo>

    /**
     * Inserts a balance entry linked to a transaction, and sequentially recalculates succeeding balances.
     *
     * @param bankName The name of the bank.
     * @param accountLast4 The last 4 digits of the account number.
     * @param amount The transaction amount.
     * @param transactionType The transaction type.
     * @param explicitBalance The bank-reported explicit balance (if any).
     * @param timestamp The transaction timestamp.
     * @param transactionId The associated transaction ID.
     * @param creditLimit Optionally, a custom credit limit parsed from SMS.
     * @param isCreditCard Whether this account is a credit card.
     * @param smsSource Sanitized SMS snippet source.
     * @param currency The transaction currency.
     */
    @Transaction
    open suspend fun insertTransactionBalance(
        bankName: String,
        accountLast4: String,
        amount: BigDecimal,
        transactionType: TransactionType,
        explicitBalance: BigDecimal?,
        timestamp: LocalDateTime,
        transactionId: Long?,
        creditLimit: BigDecimal?,
        isCreditCard: Boolean,
        smsSource: String?,
        currency: String
    ): Long {
        // The table holds one row per account per instant (a unique index) and
        // inserts replace on conflict. Two transactions stamped with the same time
        // on one account - a bulk date change, two alerts in the same second -
        // would silently overwrite each other's row and lose one of them. Step
        // past any instant that is already taken.
        var slot = timestamp
        while (getLatestBalanceOnOrBefore(bankName, accountLast4, slot)?.timestamp == slot) {
            slot = slot.plusNanos(1_000_000)
        }

        val latest = getLatestBalance(bankName, accountLast4)
        val previous = getLatestBalanceOnOrBefore(bankName, accountLast4, slot)

        // Fix for manually-created accounts: when the account was set up today (MANUAL entry),
        // backdated transactions have no prior entry. Fall back to the earliest MANUAL balance
        // so the calculation is based on the user's initial balance, not zero.
        val previousForBalance = previous ?: run {
            val earliest = getEarliestBalance(bankName, accountLast4)
            if (earliest?.sourceType == SOURCE_MANUAL) earliest else null
        }

        val accountIsCreditCard = isCreditCard || (previousForBalance?.isCreditCard ?: false)
        val knownCreditLimit = previousForBalance?.creditLimit ?: latest?.creditLimit
        val newBalance = explicitBalance ?: calculateTransactionBalance(
            currentBalance = previousForBalance?.balance ?: BigDecimal.ZERO,
            amount = amount,
            transactionType = transactionType,
            isCreditCard = accountIsCreditCard
        )

        val balanceId = insertBalance(
            AccountBalanceEntity(
                bankName = bankName,
                accountLast4 = accountLast4,
                balance = newBalance,
                timestamp = slot,
                transactionId = transactionId,
                creditLimit = if (accountIsCreditCard) {
                    // Parsers report the *available* limit, so the total limit is
                    // available + outstanding. Only derive it when nothing is on
                    // record yet: re-deriving on every SMS makes the stored limit
                    // drift upward with the outstanding balance and silently
                    // overwrites a limit the user set by hand.
                    knownCreditLimit ?: creditLimit?.add(newBalance)
                } else {
                    knownCreditLimit
                },
                isCreditCard = accountIsCreditCard,
                smsSource = smsSource?.take(500),
                sourceType = if (explicitBalance != null) {
                    SOURCE_TRANSACTION_SMS_BALANCE
                } else {
                    SOURCE_TRANSACTION_CALCULATED
                },
                // Never let a transaction relabel the account. A foreign-currency
                // transaction is converted into the account's currency before it
                // gets here, so the account's own currency is the correct one.
                currency = previousForBalance?.currency ?: latest?.currency ?: currency,
                iconResId = previousForBalance?.iconResId ?: latest?.iconResId ?: 0,
                iconName = previousForBalance?.iconName ?: latest?.iconName ?: "",
                isWallet = previousForBalance?.isWallet ?: latest?.isWallet ?: false,
                color = previousForBalance?.color ?: latest?.color ?: "#33B5E5"
            )
        )

        // `previous` is what preceded the first later row before this insert, so
        // rows whose effect has to be carried over can still be measured.
        recalculateBalancesAfter(
            bankName,
            accountLast4,
            slot,
            newBalance,
            previousOldBalance = previous?.balance
        )
        return balanceId
    }

    /**
     * Recalculates every row after [timestamp], starting from [startingBalance].
     *
     * [previousOldBalance] is the balance the row just before them held before the
     * change that triggered this pass. Some rows cannot be recalculated from their
     * transaction alone (see [keepsAppliedEffect]); their original effect is the
     * step from that balance to their own, and it is carried over unchanged.
     * Without it they fall back to being recalculated.
     */
    open suspend fun recalculateBalancesAfter(
        bankName: String,
        accountLast4: String,
        timestamp: LocalDateTime,
        startingBalance: BigDecimal,
        previousOldBalance: BigDecimal? = null
    ) {
        recalculateBalancesAfterInternal(
            bankName,
            accountLast4,
            timestamp,
            startingBalance,
            previousOldBalance
        )
    }

    private suspend fun recalculateBalancesAfterInternal(
        bankName: String,
        accountLast4: String,
        timestamp: LocalDateTime,
        startingBalance: BigDecimal,
        previousOldBalance: BigDecimal?
    ) {
        var runningBalance = startingBalance
        var previousOld = previousOldBalance
        for (row in getBalancesAfterWithTransactions(bankName, accountLast4, timestamp)) {
            val sourceType = row.sourceType
            val originalBalance = row.balance
            val balanceBeforeRow = previousOld
            previousOld = originalBalance

            // MANUAL entries (user-created account setup) are NOT hard stops.
            // When a backdated transaction is added before a MANUAL entry, the cascade
            // must continue past it so that subsequent TRANSACTION_CALCULATED entries
            // (e.g., today's expenses) are correctly updated to reflect the backdated change.
            // The MANUAL entry itself is updated to carry the accumulated delta.
            if (sourceType == SOURCE_MANUAL) {
                if (runningBalance != row.balance) {
                    updateAndInvalidate(row.id, runningBalance)
                }
                continue
            }

            // Bank-reported explicit balances (SMS) are authoritative anchors — stop here.
            // A transaction's reported balance belongs to the account it was reported
            // on. The other leg of a transfer shares the transaction but not the
            // balance, and treating it as an anchor stopped the destination account
            // from ever being recalculated past it.
            val reportedForThisAccount = row.transactionBalanceAfter != null &&
                    (row.transactionBankName == null ||
                        isTransactionsOwnAccount(row, bankName, accountLast4))
            val isExplicitBalance = reportedForThisAccount ||
                    sourceType == SOURCE_TRANSACTION_SMS_BALANCE ||
                    sourceType == SOURCE_SMS_BALANCE

            if (isExplicitBalance) {
                break
            }

            if (row.transactionId == null) {
                val isCalculatedSnapshot = sourceType == SOURCE_MANUAL_EDIT ||
                        sourceType == "DELETE_REVERSAL" ||
                        sourceType == "UNDO_REVERSAL"
                if (!isCalculatedSnapshot) {
                    break
                }
                if (runningBalance != row.balance) {
                    updateAndInvalidate(row.id, runningBalance)
                }
                continue
            }

            val amount = row.transactionAmount
            val transactionType = row.transactionType?.let { runCatching { TransactionType.valueOf(it) }.getOrNull() }
            val isDeleted = row.isDeleted == true

            val recalculated = when {
                amount == null || transactionType == null || isDeleted -> runningBalance
                balanceBeforeRow != null && keepsAppliedEffect(row, transactionType) ->
                    runningBalance + (originalBalance - balanceBeforeRow)
                else -> calculateTransactionBalance(
                    currentBalance = runningBalance,
                    amount = amount,
                    transactionType = legType(row, transactionType, bankName, accountLast4),
                    isCreditCard = row.isCreditCard
                )
            }

            if (recalculated != row.balance) {
                updateAndInvalidate(row.id, recalculated)
            }
            runningBalance = recalculated
        }
    }

    /**
     * Updates a balance entry by fetching the full entity first, then using @Update
     * so that Room properly invalidates Flow observers and the UI refreshes.
     */
    private suspend fun updateAndInvalidate(id: Long, newBalance: BigDecimal) {
        val entity = getBalanceById(id) ?: return
        updateBalance(entity.copy(balance = newBalance))
    }

    @Query("SELECT * FROM account_balances WHERE id = :id LIMIT 1")
    abstract suspend fun getBalanceById(id: Long): AccountBalanceEntity?
    
    @Query("""
        SELECT * FROM account_balances 
        WHERE bank_name = :bankName AND account_last4 = :accountLast4
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    abstract fun getLatestBalanceFlow(bankName: String, accountLast4: String): Flow<AccountBalanceEntity?>
    
    @Query("""
        SELECT DISTINCT 
            ab1.id,
            ab1.bank_name,
            ab1.account_last4,
            ab1.balance,
            ab1.timestamp,
            ab1.transaction_id,
            ab1.created_at,
            ab1.credit_limit,
            ab1.is_credit_card,
            ab1.sms_source,
            ab1.source_type,
            ab1.currency,
            ab1.icon_res_id,
            ab1.icon_name,
            ab1.is_wallet,
            ab1.color,
            ab1.is_sample
        FROM account_balances ab1
        INNER JOIN (
            SELECT bank_name, account_last4, MAX(timestamp) as max_timestamp
            FROM account_balances
            GROUP BY bank_name, account_last4
        ) ab2 
        ON ab1.bank_name = ab2.bank_name 
        AND ab1.account_last4 = ab2.account_last4 
        AND ab1.timestamp = ab2.max_timestamp
        ORDER BY ab1.balance DESC
    """)
    abstract fun getAllLatestBalances(): Flow<List<AccountBalanceEntity>>
    
    @Query("SELECT * FROM account_balances ORDER BY timestamp DESC")
    abstract fun getAllBalances(): Flow<List<AccountBalanceEntity>>
    
    @Query("DELETE FROM account_balances")
    abstract suspend fun deleteAllBalances()
    
    @Query("DELETE FROM account_balances WHERE is_sample = 1")
    abstract suspend fun deleteSampleBalances()
    
    @Query("""
        SELECT DISTINCT 
            ab1.id,
            ab1.bank_name,
            ab1.account_last4,
            ab1.balance,
            ab1.timestamp,
            ab1.transaction_id,
            ab1.created_at,
            ab1.credit_limit,
            ab1.is_credit_card,
            ab1.sms_source,
            ab1.source_type,
            ab1.currency,
            ab1.icon_res_id,
            ab1.icon_name,
            ab1.is_wallet,
            ab1.color,
            ab1.is_sample
        FROM account_balances ab1
        INNER JOIN (
            SELECT bank_name, account_last4, MAX(timestamp) as max_timestamp
            FROM account_balances
            WHERE strftime('%Y-%m', timestamp/1000, 'unixepoch') = strftime('%Y-%m', 'now')
            GROUP BY bank_name, account_last4
        ) ab2 
        ON ab1.bank_name = ab2.bank_name 
        AND ab1.account_last4 = ab2.account_last4 
        AND ab1.timestamp = ab2.max_timestamp
        ORDER BY ab1.balance DESC
    """)
    abstract fun getCurrentMonthLatestBalances(): Flow<List<AccountBalanceEntity>>
    
    @Query("""
        SELECT SUM(balance) as total FROM (
            SELECT DISTINCT 
                ab1.balance
            FROM account_balances ab1
            INNER JOIN (
                SELECT bank_name, account_last4, MAX(timestamp) as max_timestamp
                FROM account_balances
                GROUP BY bank_name, account_last4
            ) ab2 
            ON ab1.bank_name = ab2.bank_name 
            AND ab1.account_last4 = ab2.account_last4 
            AND ab1.timestamp = ab2.max_timestamp
        )
    """)
    abstract fun getTotalBalance(): Flow<BigDecimal?>
    
    @Query("""
        SELECT * FROM account_balances
        WHERE bank_name = :bankName AND account_last4 = :accountLast4
        AND timestamp >= :startDate AND timestamp <= :endDate
        ORDER BY timestamp DESC
    """)
    abstract fun getBalanceHistory(
        bankName: String,
        accountLast4: String,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): Flow<List<AccountBalanceEntity>>
    
    @Query("""
        SELECT COUNT(DISTINCT bank_name || account_last4) FROM account_balances
    """)
    abstract fun getAccountCount(): Flow<Int>
    
    @Query("DELETE FROM account_balances WHERE timestamp < :beforeDate")
    abstract suspend fun deleteOldBalances(beforeDate: LocalDateTime): Int
    
    @Update
    abstract suspend fun updateBalance(balance: AccountBalanceEntity)
    
    @Delete
    abstract suspend fun deleteBalance(balance: AccountBalanceEntity)
    
    @Query("""SELECT * FROM account_balances 
        WHERE bank_name = :bankName AND account_last4 = :accountLast4
        ORDER BY timestamp DESC""")
    abstract suspend fun getBalanceHistoryForAccount(bankName: String, accountLast4: String): List<AccountBalanceEntity>
    
    @Query("DELETE FROM account_balances WHERE id = :id")
    abstract suspend fun deleteBalanceById(id: Long)
    
    @Query("UPDATE account_balances SET balance = :newBalance WHERE id = :id")
    abstract suspend fun updateBalanceById(id: Long, newBalance: BigDecimal)
    
    @Query("""SELECT COUNT(*) FROM account_balances
        WHERE bank_name = :bankName AND account_last4 = :accountLast4""")
    abstract suspend fun getBalanceCountForAccount(bankName: String, accountLast4: String): Int
 
    @Query("DELETE FROM account_balances WHERE bank_name = :bankName AND account_last4 = :accountLast4")
    abstract suspend fun deleteAccount(bankName: String, accountLast4: String): Int
 
    @Query("UPDATE account_balances SET bank_name = :newBankName WHERE bank_name = :oldBankName AND account_last4 = :accountLast4")
    abstract suspend fun updateAccountBankName(oldBankName: String, accountLast4: String, newBankName: String): Int

    @Query("UPDATE account_balances SET account_last4 = :newAccountLast4 WHERE bank_name = :bankName AND account_last4 = :oldAccountLast4")
    abstract suspend fun updateAccountLast4(bankName: String, oldAccountLast4: String, newAccountLast4: String): Int

    /**
     * Repairs an account whose rows were never flagged as a credit card.
     *
     * The flag used to be dropped whenever a balance was edited, which left the
     * account being treated as a bank account: spending was subtracted from the
     * outstanding amount instead of added to it, and at a zero balance it was
     * discarded entirely. Marking the account as credit has to fix its history
     * too, not just rows written from then on.
     */
    @Query("UPDATE account_balances SET is_credit_card = :isCreditCard WHERE bank_name = :bankName AND account_last4 = :accountLast4")
    abstract suspend fun setAccountCreditCardFlag(bankName: String, accountLast4: String, isCreditCard: Boolean): Int
 
    @Query("SELECT * FROM account_balances WHERE transaction_id = :transactionId LIMIT 1")
    abstract suspend fun getBalanceByTransactionId(transactionId: Long): AccountBalanceEntity?

    /** Every row a transaction wrote: one per account it touched, so two for a transfer. */
    @Query("""
        SELECT * FROM account_balances
        WHERE transaction_id = :transactionId
        ORDER BY timestamp ASC, id ASC
    """)
    abstract suspend fun getBalancesByTransactionId(transactionId: Long): List<AccountBalanceEntity>

    /** Finds the latest account record for a given last-4 digits, regardless of bank name. */
    @Query("""
        SELECT * FROM account_balances
        WHERE account_last4 = :accountLast4
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    abstract suspend fun getAccountByLast4(accountLast4: String): AccountBalanceEntity?

    /** Returns the oldest balance entry for an account, used as a fallback when no prior entry
     *  exists for a backdated transaction (e.g. for manually-created accounts). */
    @Query("""
        SELECT * FROM account_balances
        WHERE bank_name = :bankName AND account_last4 = :accountLast4
        ORDER BY timestamp ASC, id ASC
        LIMIT 1
    """)
    abstract suspend fun getEarliestBalance(bankName: String, accountLast4: String): AccountBalanceEntity?
}


data class AccountBalanceTransactionInfo(
    val id: Long,
    val balance: BigDecimal,
    val sourceType: String?,
    val isCreditCard: Boolean,
    val transactionId: Long?,
    val transactionAmount: BigDecimal?,
    val transactionType: String?,
    val transactionBalanceAfter: BigDecimal?,
    val isDeleted: Boolean?,
    val accountCurrency: String? = null,
    val transactionCurrency: String? = null,
    val transactionBankName: String? = null,
    val transactionAccountNumber: String? = null
)

/** Whether [row] sits on the account its transaction names, rather than the other leg of a transfer. */
private fun isTransactionsOwnAccount(
    row: AccountBalanceTransactionInfo,
    bankName: String,
    accountLast4: String
): Boolean = row.transactionBankName == bankName && row.transactionAccountNumber == accountLast4

/**
 * Rows whose effect cannot be worked out again from their transaction alone.
 *
 * A transfer row may be the money leaving the source, the money arriving at the
 * destination, or a bank alert that was recorded as it stood; the transaction
 * does not say which, and recalculating one as plain spending is what turned a
 * card payment into a charge. A foreign-currency row was converted when it was
 * applied, and recalculating it from the raw amount treats 30 USD as 30 of the
 * account's own currency. Both keep the effect they were given.
 */
private fun keepsAppliedEffect(row: AccountBalanceTransactionInfo, type: TransactionType): Boolean {
    if (type == TransactionType.TRANSFER) return true
    val transactionCurrency = row.transactionCurrency ?: return false
    val accountCurrency = row.accountCurrency ?: return false
    return !transactionCurrency.equals(accountCurrency, ignoreCase = true)
}

/**
 * The type to recalculate a row with when its original effect is not known: a
 * transfer takes money out of the account it names and puts it into the other.
 */
private fun legType(
    row: AccountBalanceTransactionInfo,
    type: TransactionType,
    bankName: String,
    accountLast4: String
): TransactionType = when {
    type != TransactionType.TRANSFER -> type
    isTransactionsOwnAccount(row, bankName, accountLast4) -> TransactionType.EXPENSE
    else -> TransactionType.INCOME
}

/**
 * The signed change a transaction makes to an account's balance.
 *
 * Derived from [calculateTransactionBalance] rather than restated, so every
 * caller that needs a delta - deletion, undo, edits, imports - applies exactly
 * the rules the insert path uses. Restating them by hand is what let them
 * drift: mirroring signs for credit cards treated a CREDIT card charge as money
 * coming in, so deleting a charge raised the outstanding amount instead of
 * lowering it.
 */
internal fun balanceDelta(
    amount: BigDecimal,
    transactionType: TransactionType,
    isCreditCard: Boolean
): BigDecimal = calculateTransactionBalance(BigDecimal.ZERO, amount, transactionType, isCreditCard)

internal fun calculateTransactionBalance(
    currentBalance: BigDecimal,
    amount: BigDecimal,
    transactionType: TransactionType,
    isCreditCard: Boolean
): BigDecimal {
    return when {
        // Paying a card reduces what is owed. Not clamped at zero either: paying
        // more than the outstanding amount leaves a credit balance on the card,
        // and clamping made that payment disappear entirely.
        isCreditCard && transactionType == TransactionType.INCOME ->
            currentBalance - amount
        // Anything else on a card is spending, which adds to the outstanding amount.
        isCreditCard -> currentBalance + amount
        transactionType == TransactionType.INCOME || transactionType == TransactionType.CREDIT || transactionType == TransactionType.BORROWED -> currentBalance + amount
        // Deliberately not clamped at zero. Clamping made an expense against a
        // zero balance vanish silently: 0 - 500 became 0, so the spend never
        // registered at all. A balance that should go negative must show negative.
        transactionType == TransactionType.EXPENSE || transactionType == TransactionType.INVESTMENT || transactionType == TransactionType.LENT ->
            currentBalance - amount
        else -> currentBalance
    }
}
