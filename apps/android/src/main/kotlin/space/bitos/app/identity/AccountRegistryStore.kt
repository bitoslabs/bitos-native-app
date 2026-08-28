package space.bitos.app.identity

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.bitos.core.identity.AccountRegistry
import space.bitos.core.identity.RegisteredAccount

/**
 * Multi-account registry adapter (APP-018a row 1): persists the versioned
 * shared [AccountRegistry] wire + the active-pubkey marker. Secrets never
 * pass through here — they live in [SecureKeyStore] slots keyed by pubkey.
 */
class AccountRegistryStore internal constructor(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<List<RegisteredAccount>> = _accounts.asStateFlow()

    private val _activePubkey = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activePubkey: StateFlow<String?> = _activePubkey.asStateFlow()

    fun reload() {
        _accounts.value = load()
        _activePubkey.value = prefs.getString(KEY_ACTIVE, null)
    }

    /** Registers (or refreshes) an account and optionally marks it active. */
    fun register(account: RegisteredAccount, makeActive: Boolean) {
        val next = AccountRegistry.normalize(_accounts.value.filter { it.pubkeyHex != account.pubkeyHex } + account)
        persist(next)
        if (makeActive) setActive(account.pubkeyHex)
    }

    /** Marks the active account; null clears the pointer (sign-out keeps slots). */
    fun setActive(pubkeyHex: String?) {
        if (pubkeyHex != null && _accounts.value.none { it.pubkeyHex == pubkeyHex }) return
        prefs.edit().putString(KEY_ACTIVE, pubkeyHex).apply()
        _activePubkey.value = pubkeyHex
    }

    /** Removes one registry row (caller wipes the matching secret slot). */
    fun remove(pubkeyHex: String) {
        persist(_accounts.value.filterNot { it.pubkeyHex == pubkeyHex })
        if (_activePubkey.value == pubkeyHex) setActive(null)
    }

    private fun persist(accounts: List<RegisteredAccount>) {
        prefs.edit().putString(KEY_WIRE, AccountRegistry.encode(accounts)).apply()
        _accounts.value = accounts
    }

    private fun load(): List<RegisteredAccount> =
        prefs.getString(KEY_WIRE, null)?.let(AccountRegistry::decode) ?: emptyList()

    companion object {
        const val PREFS_NAME = "bitos_accounts"
        const val KEY_WIRE = "account_registry_v1"
        const val KEY_ACTIVE = "active_pubkey"
    }
}
