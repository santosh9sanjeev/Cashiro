package com.ritesh.cashiro.data.repository

import com.ritesh.cashiro.data.database.dao.SOURCE_SMS_BALANCE
import com.ritesh.cashiro.data.database.dao.SOURCE_TRANSACTION_SMS_BALANCE
import com.ritesh.cashiro.data.database.dao.balanceDelta
import android.util.Log
import com.ritesh.cashiro.data.currency.CurrencyConverter
import com.ritesh.cashiro.data.database.dao.TransactionDao
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A balance row whose transaction was deleted: it carries the balance from before it. */
private const val SOURCE_DELETE_REVERSAL = "DELETE_REVERSAL"

@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val currencyConversionService: CurrencyConverter
) {
    fun getAllTransactions(): Flow<List<TransactionEntity>> = transactionDao.getAllTransactions()
        .map { list -> list.filter { it.transactionType != TransactionType.BALANCE_UPDATE } }

    fun getTransactionCount(): Flow<Int> = transactionDao.getTransactionCount()

    suspend fun getTransactionById(id: Long): TransactionEntity? =
            transactionDao.getTransactionById(id)

    suspend fun getTransactionsUpdatedBetween(
            updatedAfter: LocalDateTime,
            updatedBefore: LocalDateTime,
            currency: String
    ): List<TransactionEntity> =
            transactionDao.getTransactionsUpdatedBetween(updatedAfter, updatedBefore, currency)

    suspend fun getTransactionsBetweenDatesByCurrency(
            startDate: LocalDateTime,
            endDate: LocalDateTime,
            currency: String
    ): List<TransactionEntity> =
            transactionDao.getTransactionsBetweenDatesByCurrency(startDate, endDate, currency)

    fun getTransactionsBetweenDates(
            startDate: LocalDateTime,
            endDate: LocalDateTime
    ): Flow<List<TransactionEntity>> =
            transactionDao.getTransactionsBetweenDates(startDate, endDate)

    fun getTransactionsBetweenDates(
            startDate: LocalDate,
            endDate: LocalDate
    ): Flow<List<TransactionEntity>> =
            transactionDao.getTransactionsBetweenDates(
                    startDate.atStartOfDay(),
                    endDate.atTime(23, 59, 59)
            )

    /**
     * Gets transactions filtered at the database level for better performance. Combines date range,
     * currency, and transaction type filters to reduce memory usage.
     *
     * @param startDate Start of the date range (inclusive)
     * @param endDate End of the date range (inclusive)
     * @param currency Currency code to filter by (e.g., "INR", "USD")
     * @param transactionType Optional transaction type filter (null means all types)
     * @return Flow of filtered transactions
     */
    fun getTransactionsFiltered(
            startDate: LocalDate,
            endDate: LocalDate,
            currency: String,
            transactionType: TransactionType? = null
    ): Flow<List<TransactionEntity>> =
            transactionDao.getTransactionsFiltered(
                    startDate.atStartOfDay(),
                    endDate.atTime(23, 59, 59),
                    currency,
                    transactionType
            )

    fun getTransactionsByType(type: TransactionType): Flow<List<TransactionEntity>> =
            transactionDao.getTransactionsByType(type)

    fun getTransactionsByCategory(category: String): Flow<List<TransactionEntity>> =
            transactionDao.getTransactionsByCategory(category)

    fun searchTransactions(query: String): Flow<List<TransactionEntity>> =
            transactionDao.searchTransactions(query)

    fun getAllCurrencies(): Flow<List<String>> = transactionDao.getAllCurrencies()

    fun getCurrenciesForPeriod(
            startDate: LocalDateTime,
            endDate: LocalDateTime
    ): Flow<List<String>> = transactionDao.getCurrenciesForPeriod(startDate, endDate)

    fun getAllCategories(): Flow<List<String>> = transactionDao.getAllCategories()

    /**
     * Gets the top N categories by usage count (number of transactions). Useful for showing user's
     * most frequently used categories in notifications.
     *
     * @param limit Maximum number of categories to return (default: 3)
     * @return List of category names ordered by usage count (most used first)
     */
    suspend fun getTopCategoriesByUsage(limit: Int = 3): List<String> =
            transactionDao.getTopCategoriesByUsage(limit)

    fun getAllMerchants(): Flow<List<String>> = transactionDao.getAllMerchants()

    suspend fun getTotalAmountByTypeAndPeriod(
            type: TransactionType,
            startDate: LocalDateTime,
            endDate: LocalDateTime
    ): Double? = transactionDao.getTotalAmountByTypeAndPeriod(type, startDate, endDate)

    suspend fun insertTransaction(transaction: TransactionEntity): Long =
            transactionDao.insertTransaction(transaction)

    suspend fun insertTransactions(transactions: List<TransactionEntity>) =
            transactionDao.insertTransactions(transactions)

    suspend fun updateTransaction(transaction: TransactionEntity) =
            transactionDao.updateTransaction(transaction)

    suspend fun deleteTransaction(transaction: TransactionEntity, hardDelete: Boolean = false) {
        // Deleting something already deleted must not give its money back twice.
        val alreadyDeleted = transactionDao.getTransactionById(transaction.id)?.isDeleted == true
        if (hardDelete) {
            transactionDao.deleteTransaction(transaction)
        } else {
            transactionDao.softDeleteTransaction(transaction.id)
        }
        if (!alreadyDeleted) takeOutOfBalances(transaction)
    }

    suspend fun deleteTransactionById(id: Long, hardDelete: Boolean = false) {
        val transaction = transactionDao.getTransactionById(id) ?: return
        if (hardDelete) {
            transactionDao.deleteTransactionById(id)
        } else {
            transactionDao.softDeleteTransaction(id)
        }
        if (!transaction.isDeleted) takeOutOfBalances(transaction)
    }

    suspend fun deleteAllTransactions() = transactionDao.deleteAllTransactions()

    suspend fun deleteSampleTransactions() = transactionDao.deleteSampleTransactions()

    // Helper method to check if transaction exists by hash
    suspend fun getTransactionByHash(transactionHash: String): TransactionEntity? =
            transactionDao.getTransactionByHash(transactionHash)

    suspend fun getTransactionsByReferenceAndAmount(
        reference: String,
        amount: BigDecimal,
        accountLast4: String?,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): List<TransactionEntity> =
        transactionDao.getTransactionsByReferenceAndAmount(reference, amount, accountLast4, startDate, endDate)

    suspend fun undoDeleteTransaction(transaction: TransactionEntity) {
        // Restoring something that is not deleted must not apply it a second time.
        val stored = transactionDao.getTransactionById(transaction.id)
        if (stored != null && !stored.isDeleted) return
        transactionDao.updateTransaction(transaction.copy(isDeleted = false))
        putBackIntoBalances(transaction)
    }

    suspend fun deleteTransactions(transactions: List<TransactionEntity>, hardDelete: Boolean = false) {
        val live = transactions.filter { transactionDao.getTransactionById(it.id)?.isDeleted != true }
        val transactionIds = transactions.map { it.id }
        if (hardDelete) {
            transactionDao.deleteTransactionsByIds(transactionIds)
        } else {
            transactionDao.softDeleteTransactions(transactionIds)
        }
        live.forEach { takeOutOfBalances(it) }
    }

    suspend fun undoDeleteTransactions(transactions: List<TransactionEntity>) {
        transactions.forEach { undoDeleteTransaction(it) }
    }

    /**
     * Balance rows a recent deletion neutralised, kept so that undoing it puts
     * every account back exactly as it was. Undo is offered for a few seconds
     * right after a delete; if the app is restarted in between, the effect is
     * re-applied as of now instead.
     */
    private val deletedRowsByTransaction = ConcurrentHashMap<Long, List<NeutralisedRow>>()

    private data class NeutralisedRow(
        val rowId: Long,
        val original: AccountBalanceEntity,
        /** What the transaction added to the balance before it, for a computed row. */
        val effect: BigDecimal,
        /** Whether the row held a balance the bank reported, which comes back as reported. */
        val reported: Boolean
    )

    /**
     * Takes a deleted transaction's effect out of every account it moved.
     *
     * Each balance row the transaction wrote is turned into a plain snapshot that
     * carries the balance from before it, and every row after it is recalculated.
     * The account's history then reads as though the transaction never happened:
     * deleting an expense gives the money back, deleting income takes it away,
     * deleting a card charge lowers what is owed, and a transfer is undone on both
     * accounts. The rows stay in place, so an account whose history this
     * transaction started is not removed with it.
     *
     * This used to post a correction dated now on the account the transaction
     * named. That got a linked debit card's spending (booked to the linked
     * account) and a transfer whose destination had never been credited wrong, and
     * left the history showing the transaction until the correction.
     */
    private suspend fun takeOutOfBalances(transaction: TransactionEntity) {
        val rows = accountBalanceRepository.getBalancesByTransactionId(transaction.id)
        if (rows.isEmpty()) {
            // Nothing records where this transaction landed (it predates that, or
            // came from an import that did not keep the link). Undo its effect as
            // of now, which is all that can be done. A balance update never moved
            // money, so there is nothing to undo.
            if (transaction.transactionType != TransactionType.BALANCE_UPDATE) {
                applyTransactionToBalances(transaction, reverse = true, sourceType = "DELETE_REVERSAL")
            }
            return
        }

        val neutralised = mutableListOf<NeutralisedRow>()
        for (row in rows) {
            // The latest version: an earlier row of the same transaction (a transfer
            // can write two on one account) may have been recalculated already.
            val current = accountBalanceRepository.getBalanceById(row.id) ?: continue
            val before = accountBalanceRepository.getLatestBalanceOnOrBefore(
                current.bankName, current.accountLast4, current.timestamp.minusNanos(1)
            )
            val base = before?.balance
                ?: (current.balance - effectOn(current, rowLegType(current, transaction), transaction))

            neutralised += NeutralisedRow(
                rowId = current.id,
                original = current,
                effect = current.balance - base,
                reported = current.sourceType == SOURCE_TRANSACTION_SMS_BALANCE ||
                    current.sourceType == SOURCE_SMS_BALANCE
            )
            accountBalanceRepository.updateBalance(
                current.copy(
                    balance = base,
                    transactionId = null,
                    sourceType = SOURCE_DELETE_REVERSAL
                )
            )
            accountBalanceRepository.recalculateBalancesAfter(
                current.bankName,
                current.accountLast4,
                current.timestamp,
                base,
                previousOldBalance = current.balance
            )
        }
        deletedRowsByTransaction[transaction.id] = neutralised
    }

    /** Reverses [takeOutOfBalances] for a transaction that is being restored. */
    private suspend fun putBackIntoBalances(transaction: TransactionEntity) {
        val neutralised = deletedRowsByTransaction.remove(transaction.id)
        if (neutralised == null) {
            // Either it never had balance rows (it was undone as of now, so redo it
            // as of now) or the record of its rows was lost with the app's process.
            if (transaction.transactionType != TransactionType.BALANCE_UPDATE) {
                applyBalanceForTransaction(transaction)
            }
            return
        }

        for (entry in neutralised) {
            val current = accountBalanceRepository.getBalanceById(entry.rowId) ?: continue
            val before = accountBalanceRepository.getLatestBalanceOnOrBefore(
                current.bankName, current.accountLast4, current.timestamp.minusNanos(1)
            )
            val base = before?.balance ?: current.balance
            val restored = if (entry.reported) entry.original.balance else base + entry.effect
            accountBalanceRepository.updateBalance(
                current.copy(
                    balance = restored,
                    transactionId = entry.original.transactionId,
                    sourceType = entry.original.sourceType
                )
            )
            accountBalanceRepository.recalculateBalancesAfter(
                current.bankName,
                current.accountLast4,
                current.timestamp,
                restored,
                previousOldBalance = current.balance
            )
        }
    }

    /** The rule a transaction's row was applied with: a transfer leaves its own account and enters the other. */
    private fun rowLegType(row: AccountBalanceEntity, transaction: TransactionEntity): TransactionType = when {
        transaction.transactionType != TransactionType.TRANSFER -> transaction.transactionType
        row.bankName == transaction.bankName && row.accountLast4 == transaction.accountNumber -> TransactionType.EXPENSE
        else -> TransactionType.INCOME
    }

    /**
     * Applies (or undoes) a transaction's effect on the accounts it touches.
     *
     * Handles both legs of a transfer, and mirrors the sign on credit cards where
     * the stored balance is an outstanding amount rather than available money.
     */
    private suspend fun applyTransactionToBalances(
        transaction: TransactionEntity,
        reverse: Boolean,
        sourceType: String
    ) {
        val bankName = transaction.bankName
        val accountLast4 = transaction.accountNumber

        if (transaction.transactionType != TransactionType.TRANSFER) {
            // A linked debit card's transactions name the card but move the account
            // it draws from, so that is the account to undo or redo them on.
            val home = homeAccountOf(
                transaction,
                accountBalanceRepository.getBalancesByTransactionId(transaction.id)
            ) ?: return
            adjustBalance(
                home.bankName, home.accountLast4, transaction.amount, transaction.transactionType,
                reverse, sourceType, transaction.currency
            )
            return
        }

        // A transfer is written as an EXPENSE leg on the source and an INCOME
        // leg on the target (see AddTransactionUseCase), so it is undone with
        // exactly those rules: a card source owes more, a card target owes less.
        if (bankName != null && accountLast4 != null) {
            adjustBalance(
                bankName, accountLast4, transaction.amount, TransactionType.EXPENSE,
                reverse, sourceType, transaction.currency
            )
        }
        // Exclude the source so a transfer can never resolve back onto the
        // account it left, which would cancel itself out.
        val target = transaction.toAccount?.let {
            findAccountByLast4(it, excludeBank = bankName, excludeLast4 = accountLast4)
        }
        if (target != null) {
            adjustBalance(
                target.bankName, target.accountLast4, transaction.amount, TransactionType.INCOME,
                reverse, sourceType, transaction.currency
            )
        }
    }

    /**
     * The account a non-transfer transaction actually moves.
     *
     * Normally the account it names. A debit card linked to a bank account is the
     * exception: its alerts name the card, but the money comes out of the linked
     * account and that is where its balance row was written. When the named
     * account does not exist as an account, the row the transaction wrote says
     * where its effect lives.
     */
    private suspend fun homeAccountOf(
        transaction: TransactionEntity,
        linkedRows: List<AccountBalanceEntity>
    ): AccountKey? {
        val bankName = transaction.bankName ?: return null
        val accountLast4 = transaction.accountNumber ?: return null
        if (accountBalanceRepository.getLatestBalance(bankName, accountLast4) != null) {
            return AccountKey(bankName, accountLast4)
        }
        val routed = linkedRows.firstOrNull {
            it.bankName != bankName || it.accountLast4 != accountLast4
        } ?: return null
        return AccountKey(routed.bankName, routed.accountLast4)
    }

    /**
     * Brings account balances in line with an edited transaction.
     *
     * The edited transaction has one leg (the account it names) or, for a
     * transfer, two: money out of the source and into the destination. Each
     * balance row the transaction wrote is checked against those legs. A row on an
     * account the transaction no longer touches is removed, a row whose effect is
     * no longer right is rewritten, and a leg with no row yet is added. Every
     * change is made at the transaction's own time and carried through the rows
     * after it, so the account's history stays consistent instead of having a
     * correction stacked on top of it.
     *
     * Before this, only the source account was ever adjusted, and only when the
     * transaction was already a transfer. Turning an expense into a transfer to a
     * credit card handed the money back to the bank account and never touched the
     * card, so its outstanding amount stayed where it was.
     *
     * A balance the bank reported is kept as reported: the bank's figure already
     * reflects the transaction, whatever it is relabelled as.
     */
    suspend fun rebalanceAfterEdit(old: TransactionEntity, new: TransactionEntity) {
        // A balance update records what an account held; it never moved money.
        if (old.transactionType == TransactionType.BALANCE_UPDATE ||
            new.transactionType == TransactionType.BALANCE_UPDATE
        ) return

        val linkedRows = accountBalanceRepository.getBalancesByTransactionId(new.id)
        val oldLegs = legsOf(old, linkedRows)
        val newLegs = legsOf(new, linkedRows)
        val movedInTime = old.dateTime != new.dateTime
        val settled = mutableSetOf<AccountKey>()

        for (row in linkedRows) {
            val account = AccountKey(row.bankName, row.accountLast4)
            val leg = newLegs.firstOrNull { it.account == account }
            val oldLeg = oldLegs.firstOrNull { it.account == account }
            when {
                // The transaction no longer touches this account, or this is a
                // stray second row for an account already dealt with.
                leg == null || account in settled -> removeRow(row, oldLeg, old)
                movedInTime -> {
                    moveRow(row, oldLeg, old, leg, new)
                    settled += account
                }
                else -> {
                    rewriteRow(row, oldLeg, old, leg, new)
                    settled += account
                }
            }
        }

        // Legs with no row yet: a transfer's destination that was never credited,
        // or the account a transaction has just been moved to.
        for (leg in newLegs) {
            if (leg.account !in settled) insertLeg(new, leg, explicitBalance = null)
        }
    }

    private data class AccountKey(val bankName: String, val accountLast4: String)

    private data class Leg(val account: AccountKey, val type: TransactionType)

    /** The accounts a transaction moves money on, with the rule each one follows. */
    private suspend fun legsOf(
        transaction: TransactionEntity,
        linkedRows: List<AccountBalanceEntity>
    ): List<Leg> {
        if (transaction.isDeleted) return emptyList()

        if (transaction.transactionType != TransactionType.TRANSFER) {
            val home = homeAccountOf(transaction, linkedRows) ?: return emptyList()
            return listOf(Leg(home, transaction.transactionType))
        }

        val bankName = transaction.bankName
        val accountLast4 = transaction.accountNumber
        val source = if (bankName != null && accountLast4 != null) {
            Leg(AccountKey(bankName, accountLast4), TransactionType.EXPENSE)
        } else {
            null
        }
        val target = transaction.toAccount
            ?.let { findAccountByLast4(it, excludeBank = bankName, excludeLast4 = accountLast4) }
            ?.let { Leg(AccountKey(it.bankName, it.accountLast4), TransactionType.INCOME) }
        return listOfNotNull(source, target)
    }

    /** Re-derives a row in place from the row before it, then carries the change forward. */
    private suspend fun rewriteRow(
        row: AccountBalanceEntity,
        oldLeg: Leg?,
        old: TransactionEntity,
        leg: Leg,
        new: TransactionEntity
    ) {
        if (isBankReported(row, new)) {
            restoreReportedBalance(row, new)
            return
        }

        // A foreign-currency row was converted at the rate of the day it was
        // applied. If nothing about it changed, leave it at that rate rather
        // than re-converting it every time the transaction is opened and saved.
        val unchanged = oldLeg?.type == leg.type && sameMoney(old, new)
        if (unchanged && !new.currency.equals(row.currency, ignoreCase = true)) return

        val before = accountBalanceRepository.getLatestBalanceOnOrBefore(
            row.bankName, row.accountLast4, row.timestamp.minusNanos(1)
        )
        val base = before?.balance
            ?: (row.balance - effectOn(row, oldLeg?.type ?: legTypeWithoutLeg(old), old))
        val updated = base + effectOn(row, leg.type, new)
        if (updated.compareTo(row.balance) == 0) return

        accountBalanceRepository.updateBalance(row.copy(balance = updated))
        accountBalanceRepository.recalculateBalancesAfter(
            row.bankName,
            row.accountLast4,
            row.timestamp,
            updated,
            previousOldBalance = row.balance
        )
    }

    /**
     * Moves a leg that stays on the same account to the transaction's new time.
     * The new row goes in before the old one comes out, so an account whose only
     * row this is never disappears in between.
     */
    private suspend fun moveRow(
        row: AccountBalanceEntity,
        oldLeg: Leg?,
        old: TransactionEntity,
        leg: Leg,
        new: TransactionEntity
    ) {
        val reported = if (isBankReported(row, old)) row.balance else null
        insertLeg(new, leg, explicitBalance = reported)
        removeRow(row, oldLeg, old)
    }

    /** Takes a row out and recalculates the account forward from the row before it. */
    private suspend fun removeRow(row: AccountBalanceEntity, oldLeg: Leg?, old: TransactionEntity) {
        // The latest version of the row: an earlier step of the same edit may have
        // recalculated it.
        val current = accountBalanceRepository.getBalanceById(row.id) ?: return
        val before = accountBalanceRepository.getLatestBalanceOnOrBefore(
            current.bankName, current.accountLast4, current.timestamp.minusNanos(1)
        )
        val base = before?.balance
            ?: (current.balance - effectOn(current, oldLeg?.type ?: legTypeWithoutLeg(old), old))
        accountBalanceRepository.deleteBalanceById(current.id)
        accountBalanceRepository.recalculateBalancesAfter(
            current.bankName,
            current.accountLast4,
            current.timestamp,
            base,
            previousOldBalance = current.balance
        )
    }

    /** Applies one leg at the transaction's time, exactly as a new transaction would be. */
    private suspend fun insertLeg(transaction: TransactionEntity, leg: Leg, explicitBalance: BigDecimal?) {
        // Only onto an account that exists: an edit never conjures one up.
        val account = accountBalanceRepository
            .getLatestBalance(leg.account.bankName, leg.account.accountLast4) ?: return
        val amount = currencyConversionService.convertAmount(
            amount = transaction.amount,
            fromCurrency = transaction.currency,
            toCurrency = account.currency
        )
        accountBalanceRepository.insertTransactionBalance(
            bankName = leg.account.bankName,
            accountLast4 = leg.account.accountLast4,
            amount = amount,
            transactionType = leg.type,
            explicitBalance = explicitBalance,
            timestamp = transaction.dateTime,
            transactionId = transaction.id,
            creditLimit = null,
            isCreditCard = account.isCreditCard,
            smsSource = null,
            currency = account.currency
        )
    }

    /**
     * A bank-reported balance stays as the bank reported it. The edit code this
     * replaces could shift one in place; put it back and carry that forward.
     */
    private suspend fun restoreReportedBalance(row: AccountBalanceEntity, transaction: TransactionEntity) {
        val reported = transaction.balanceAfter ?: return
        if (row.bankName != transaction.bankName || row.accountLast4 != transaction.accountNumber) return
        if (!transaction.currency.equals(row.currency, ignoreCase = true)) return
        if (reported.compareTo(row.balance) == 0) return

        accountBalanceRepository.updateBalance(row.copy(balance = reported))
        accountBalanceRepository.recalculateBalancesAfter(
            row.bankName,
            row.accountLast4,
            row.timestamp,
            reported,
            previousOldBalance = row.balance
        )
    }

    private fun isBankReported(row: AccountBalanceEntity, transaction: TransactionEntity): Boolean =
        row.sourceType == SOURCE_TRANSACTION_SMS_BALANCE ||
            row.sourceType == SOURCE_SMS_BALANCE ||
            (transaction.balanceAfter != null &&
                row.bankName == transaction.bankName &&
                row.accountLast4 == transaction.accountNumber)

    /** The signed change [transaction] makes to [row]'s account under [type]. */
    private suspend fun effectOn(
        row: AccountBalanceEntity,
        type: TransactionType,
        transaction: TransactionEntity
    ): BigDecimal {
        val converted = currencyConversionService.convertAmount(
            amount = transaction.amount,
            fromCurrency = transaction.currency,
            toCurrency = row.currency
        )
        return balanceDelta(converted, type, row.isCreditCard)
    }

    /** For a row on an account that was not one of the transaction's legs. */
    private fun legTypeWithoutLeg(transaction: TransactionEntity): TransactionType =
        if (transaction.transactionType == TransactionType.TRANSFER) TransactionType.EXPENSE
        else transaction.transactionType

    private fun sameMoney(old: TransactionEntity, new: TransactionEntity): Boolean =
        old.amount.compareTo(new.amount) == 0 && old.currency.equals(new.currency, ignoreCase = true)

    private suspend fun adjustBalance(
        bankName: String,
        accountLast4: String,
        amount: BigDecimal,
        effectiveType: TransactionType,
        reverse: Boolean,
        sourceType: String,
        currency: String
    ) {
        val latestBalance = accountBalanceRepository.getLatestBalance(bankName, accountLast4) ?: return

        // The transaction may be denominated differently from the account it hits,
        // so convert before any arithmetic.
        val converted = currencyConversionService.convertAmount(
            amount = amount,
            fromCurrency = currency,
            toCurrency = latestBalance.currency
        )

        // Exactly the rules used when the transaction was inserted, so undoing
        // it and redoing it are exact inverses.
        val applied = balanceDelta(converted, effectiveType, latestBalance.isCreditCard)
        if (applied.signum() == 0) return
        val delta = if (reverse) applied.negate() else applied
        // Unclamped, matching calculateTransactionBalance: an overdrawn account
        // and an overpaid card are both real states.
        val newBalance = latestBalance.balance + delta

        accountBalanceRepository.insertBalance(
            AccountBalanceEntity(
                bankName = bankName,
                accountLast4 = accountLast4,
                balance = newBalance,
                timestamp = LocalDateTime.now(),
                transactionId = null,
                creditLimit = latestBalance.creditLimit,
                isCreditCard = latestBalance.isCreditCard,
                iconResId = latestBalance.iconResId,
                iconName = latestBalance.iconName,
                isWallet = latestBalance.isWallet,
                color = latestBalance.color,
                currency = latestBalance.currency,
                sourceType = sourceType
            )
        )
    }

    /**
     * Resolves a transfer's destination from the last four digits.
     *
     * Transactions only store the destination's last four digits, with no bank,
     * so two accounts ending in the same digits are genuinely ambiguous. The
     * source account is excluded so a transfer never lands back on itself, and
     * a remaining ambiguity is logged rather than silently resolved.
     */
    private suspend fun findAccountByLast4(
        last4: String,
        excludeBank: String? = null,
        excludeLast4: String? = null
    ): AccountBalanceEntity? {
        val candidates = accountBalanceRepository.getAllLatestBalances().first()
            .filter { it.accountLast4 == last4 }
            .filterNot { it.bankName == excludeBank && it.accountLast4 == excludeLast4 }
        if (candidates.size > 1) {
            Log.w(
                "TransactionRepository",
                "Ambiguous transfer target: ${candidates.size} accounts end in $last4"
            )
        }
        return candidates.firstOrNull()
    }

    private suspend fun applyBalanceForTransaction(transaction: TransactionEntity) {
        applyTransactionToBalances(transaction, reverse = false, sourceType = "UNDO_REVERSAL")
    }

    suspend fun updateCategoryForMerchant(merchantName: String, newCategory: String) {
        transactionDao.updateCategoryForMerchant(merchantName, newCategory)
    }

    suspend fun updateCategoryAndSubcategoryForMerchantContains(merchantName: String, newCategory: String, newSubcategory: String?) {
        transactionDao.updateCategoryAndSubcategoryForMerchantContains(merchantName, newCategory, newSubcategory)
    }

    suspend fun getOtherTransactionCountForMerchant(merchantName: String, excludeId: Long): Int {
        return transactionDao.getTransactionCountForMerchant(merchantName, excludeId)
    }

    /** Returns non-deleted transactions whose merchant name contains the given keyword, excluding one id. */
    suspend fun getTransactionsByMerchantContains(
        merchantName: String,
        excludeId: Long
    ): List<TransactionEntity> {
        return transactionDao.getTransactionsByMerchantContains(merchantName, excludeId)
    }

    suspend fun searchTransactionsList(searchQuery: String): List<TransactionEntity> {
        return transactionDao.searchTransactionsList(searchQuery)
    }

    // Additional methods for Home screen
    data class MonthlyBreakdown(
            val total: BigDecimal,
            val income: BigDecimal,
            val expenses: BigDecimal
    )

    fun getCurrentMonthBreakdown(): Flow<MonthlyBreakdown> {
        val now = LocalDate.now()
        val startDate = now.withDayOfMonth(1).atStartOfDay()
        val endDate = now.atTime(23, 59, 59)

        return transactionDao.getTransactionsBetweenDates(startDate, endDate).map { transactions ->
            val income =
                    transactions.filter { it.transactionType == TransactionType.INCOME || it.transactionType == TransactionType.BORROWED }.fold(
                                    BigDecimal.ZERO
                            ) { acc, transaction -> acc + transaction.amount }
            val expenses =
                    transactions.filter { it.transactionType == TransactionType.EXPENSE || it.transactionType == TransactionType.LENT }.fold(
                                    BigDecimal.ZERO
                            ) { acc, transaction -> acc + transaction.amount }
            MonthlyBreakdown(total = income - expenses, income = income, expenses = expenses)
        }
    }

    fun getCurrentMonthTotal(): Flow<BigDecimal> {
        return getCurrentMonthBreakdown().map { it.total }
    }

    fun getLastMonthBreakdown(): Flow<MonthlyBreakdown> {
        val now = LocalDate.now()
        val dayOfMonth = now.dayOfMonth
        val lastMonth = now.minusMonths(1)

        // Compare same period: if today is 10th, compare 1st-10th of last month
        val startDate = lastMonth.withDayOfMonth(1).atStartOfDay()
        val lastMonthMaxDay = min(dayOfMonth, lastMonth.lengthOfMonth())
        val endDate = lastMonth.withDayOfMonth(lastMonthMaxDay).atTime(23, 59, 59)

        return transactionDao.getTransactionsBetweenDates(startDate, endDate).map { transactions ->
            val income =
                    transactions.filter { it.transactionType == TransactionType.INCOME || it.transactionType == TransactionType.BORROWED }.fold(
                                    BigDecimal.ZERO
                            ) { acc, transaction -> acc + transaction.amount }
            val expenses =
                    transactions.filter { it.transactionType == TransactionType.EXPENSE || it.transactionType == TransactionType.LENT }.fold(
                                    BigDecimal.ZERO
                            ) { acc, transaction -> acc + transaction.amount }
            MonthlyBreakdown(total = income - expenses, income = income, expenses = expenses)
        }
    }

    fun getLastMonthTotal(): Flow<BigDecimal> {
        return getLastMonthBreakdown().map { it.total }
    }

    // Currency-grouped breakdown methods
    fun getCurrentMonthBreakdownByCurrency(): Flow<Map<String, MonthlyBreakdown>> {
        val now = LocalDate.now()
        val startDate = now.withDayOfMonth(1).atStartOfDay()
        val endDate = now.atTime(23, 59, 59)

        return transactionDao.getTransactionsBetweenDates(startDate, endDate).map { transactions ->
            transactions.groupBy { it.currency }.mapValues { (_, currencyTransactions) ->
                val income =
                        currencyTransactions
                                .filter { it.transactionType == TransactionType.INCOME || it.transactionType == TransactionType.BORROWED }
                                .fold(BigDecimal.ZERO) { acc, transaction ->
                                    acc + transaction.amount
                                }
                val expenses =
                        currencyTransactions
                                .filter { it.transactionType == TransactionType.EXPENSE || it.transactionType == TransactionType.LENT }
                                .fold(BigDecimal.ZERO) { acc, transaction ->
                                    acc + transaction.amount
                                }
                MonthlyBreakdown(total = income - expenses, income = income, expenses = expenses)
            }
        }
    }

    fun getLastMonthBreakdownByCurrency(): Flow<Map<String, MonthlyBreakdown>> {
        val now = LocalDate.now()
        val dayOfMonth = now.dayOfMonth
        val lastMonth = now.minusMonths(1)

        // Compare same period: if today is 10th, compare 1st-10th of last month
        val startDate = lastMonth.withDayOfMonth(1).atStartOfDay()
        val lastMonthMaxDay = min(dayOfMonth, lastMonth.lengthOfMonth())
        val endDate = lastMonth.withDayOfMonth(lastMonthMaxDay).atTime(23, 59, 59)

        return transactionDao.getTransactionsBetweenDates(startDate, endDate).map { transactions ->
            transactions.groupBy { it.currency }.mapValues { (_, currencyTransactions) ->
                val income =
                        currencyTransactions
                                .filter { it.transactionType == TransactionType.INCOME || it.transactionType == TransactionType.BORROWED }
                                .fold(BigDecimal.ZERO) { acc, transaction ->
                                    acc + transaction.amount
                                }
                val expenses =
                        currencyTransactions
                                .filter { it.transactionType == TransactionType.EXPENSE || it.transactionType == TransactionType.LENT }
                                .fold(BigDecimal.ZERO) { acc, transaction ->
                                    acc + transaction.amount
                                }
                MonthlyBreakdown(total = income - expenses, income = income, expenses = expenses)
            }
        }
    }

    fun getCurrentYearBreakdownByCurrency(): Flow<Map<String, MonthlyBreakdown>> {
        val now = LocalDate.now()
        val startDate = now.withDayOfYear(1).atStartOfDay()
        val endDate = now.atTime(23, 59, 59)

        return transactionDao.getTransactionsBetweenDates(startDate, endDate).map { transactions ->
            transactions.groupBy { it.currency }.mapValues { (_, currencyTransactions) ->
                val income =
                    currencyTransactions
                        .filter { it.transactionType == TransactionType.INCOME || it.transactionType == TransactionType.BORROWED }
                        .fold(BigDecimal.ZERO) { acc, transaction ->
                            acc + transaction.amount
                        }
                val expenses =
                    currencyTransactions
                        .filter { it.transactionType == TransactionType.EXPENSE || it.transactionType == TransactionType.LENT }
                        .fold(BigDecimal.ZERO) { acc, transaction ->
                            acc + transaction.amount
                        }
                MonthlyBreakdown(total = income - expenses, income = income, expenses = expenses)
            }
        }
    }

    fun getRecentTransactions(limit: Int = 5): Flow<List<TransactionEntity>> {
        return transactionDao.getAllTransactions().map { transactions -> transactions
            .filter { it.transactionType != TransactionType.BALANCE_UPDATE }
            .take(limit)
        }
    }

    fun getTransactionsByAccount(
            bankName: String,
            accountLast4: String
    ): Flow<List<TransactionEntity>> {
        return transactionDao.getTransactionsByAccount(bankName, accountLast4)
    }

    fun getTransactionsByAccountAndDateRange(
            bankName: String,
            accountLast4: String,
            startDate: LocalDateTime,
            endDate: LocalDateTime
    ): Flow<List<TransactionEntity>> {
        return transactionDao.getTransactionsByAccountAndDateRange(
                bankName,
                accountLast4,
                startDate,
                endDate
        )
    }

    // Methods for batch rule application
    suspend fun getAllTransactionsList(): List<TransactionEntity> {
        // Get all non-deleted transactions as a list (not Flow) for batch processing
        // Use a large date range to get all transactions
        val startDate = LocalDateTime.of(2000, 1, 1, 0, 0)
        val endDate = LocalDateTime.now().plusYears(10)
        return transactionDao.getTransactionsBetweenDatesList(startDate, endDate)
    }

    suspend fun getUncategorizedTransactions(): List<TransactionEntity> {
        // Get all transactions without a category or with "Others" category
        return getAllTransactionsList().filter { transaction ->
            transaction.category.isNullOrBlank() || transaction.category == "Others"
        }
    }

    suspend fun updateAccountForTransactions(
            oldBankName: String,
            oldAccountNumber: String,
            newBankName: String,
            newAccountNumber: String
    ) {
        transactionDao.updateAccountForTransactions(
                oldBankName,
                oldAccountNumber,
                newBankName,
                newAccountNumber
        )
    }

    suspend fun updateTransactionsCategory(oldCategory: String, newCategory: String, newSubcategory: String?) {
        transactionDao.updateTransactionsCategory(oldCategory, newCategory, newSubcategory)
    }

    suspend fun getTransactionCountByCategory(category: String): Int {
        return transactionDao.getTransactionCountByCategory(category)
    }

    suspend fun findPotentialDuplicates(
        amount: BigDecimal,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): List<TransactionEntity> {
        return transactionDao.findPotentialDuplicates(startDate, endDate)
            .filter { it.amount.compareTo(amount) == 0 }
    }
}
