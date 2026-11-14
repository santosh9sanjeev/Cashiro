package com.ritesh.cashiro.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.preferences.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrencyRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) {
    private val sharedPrefs = context.getSharedPreferences("account_prefs", Context.MODE_PRIVATE)

    /**
     * Reactive Flow of the Main Account key.
     */
    private val mainAccountKeyFlow: Flow<String?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "main_account") {
                trySend(prefs.getString(key, null))
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(sharedPrefs.getString("main_account", null))
        awaitClose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /**
     * The currency amounts are shown in unless the user picked one in settings:
     * the main account's currency, or, with no main account, the currency the
     * user's accounts are kept in. Reacts to choosing a new main account and to
     * any account changing currency.
     */
    val baseCurrencyCode: Flow<String> = combine(
        mainAccountKeyFlow,
        accountBalanceRepository.getAllLatestBalances(),
        userPreferencesRepository.baseCurrency
    ) { mainAccountKey, accounts, preferred ->
        resolveBaseCurrency(mainAccountKey, accounts, preferred)
    }.distinctUntilChanged()

    /**
     * Returns the effective base currency, overriding with:
     * 1. Unified currency when enabled (highest priority — overrides all other currency settings for display)
     * 2. Default currency when enabled (used as the relational base for exchange rates)
     * 3. Main account's currency as fallback
     */
    val effectiveBaseCurrencyCode: Flow<String> = combine(
        baseCurrencyCode,
        userPreferencesRepository.unifiedCurrencyEnabled,
        userPreferencesRepository.unifiedCurrencyCode,
        userPreferencesRepository.defaultCurrencyEnabled,
        userPreferencesRepository.defaultCurrencyCode
    ) { baseCode, unifiedEnabled, unifiedCode, defaultEnabled, defaultCode ->
        when {
            unifiedEnabled && !unifiedCode.isNullOrBlank() -> unifiedCode
            defaultEnabled && !defaultCode.isNullOrBlank() -> defaultCode
            else -> baseCode
        }
    }
}

/**
 * Picks the currency to show amounts in when the user has not chosen one.
 *
 * The main account's currency when a main account is set and still exists.
 * Otherwise the currency most of the user's own accounts are in. The stored
 * preference defaults to INR and is only written alongside a main account, so
 * falling back to it showed every income, expense and net figure in rupees for
 * someone whose accounts are all in another currency. It is used only when
 * there is no account to go by, or to settle a tie.
 */
internal fun resolveBaseCurrency(
    mainAccountKey: String?,
    accounts: List<AccountBalanceEntity>,
    preferred: String
): String {
    val main = mainAccountKey?.let { key ->
        accounts.firstOrNull { "${it.bankName}_${it.accountLast4}" == key }
    }
    if (main != null) return main.currency

    // Bank accounts and cards decide; the built-in cash wallet keeps whatever
    // currency it was created with and should not outvote them.
    val pool = accounts.filterNot { it.isWallet }.ifEmpty { accounts }
    if (pool.isEmpty()) return preferred

    val counts = pool.groupingBy { it.currency.uppercase() }.eachCount()
    val most = counts.values.max()
    val leaders = counts.filterValues { it == most }.keys
    if (preferred.uppercase() in leaders) return preferred
    return pool.first { it.currency.uppercase() in leaders }.currency
}
