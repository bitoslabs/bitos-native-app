package space.bitos.app.identity

import android.content.SharedPreferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import space.bitos.core.identity.AccountRegistry
import space.bitos.core.identity.RegisteredAccount

/**
 * Adapter-contract test (AGENTS): the registry store executes the shared
 * AccountRegistry contract — bounded registration, active-pointer
 * lifecycle, per-account removal — with no secret ever passing through.
 */
class AccountRegistryStoreTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: MemoryPrefs

    @BeforeTest
    fun setUp() {
        prefs = MemoryPrefs()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun account(pk: String) = RegisteredAccount(
        pubkeyHex = pk,
        npub = "npub1" + "x".repeat(58),
        addedAtSeconds = 42,
    )

    @Test
    fun registerActivateRemoveLifecycle() = runBlocking {
        val store = AccountRegistryStore(prefs)
        val a = account("a".repeat(64))
        val b = account("b".repeat(64))
        store.register(a, makeActive = true)
        store.register(b, makeActive = false)
        assertEquals(listOf(a, b), store.accounts.first())
        assertEquals(a.pubkeyHex, store.activePubkey.first())

        store.setActive(b.pubkeyHex)
        assertEquals(b.pubkeyHex, store.activePubkey.first())

        // Sign-out semantics: pointer clears, rows survive.
        store.setActive(null)
        assertNull(store.activePubkey.first())
        assertEquals(2, store.accounts.first().size)

        // Removal of the active row also clears the pointer.
        store.setActive(a.pubkeyHex)
        store.remove(a.pubkeyHex)
        assertNull(store.activePubkey.first())
        assertEquals(listOf(b), store.accounts.first())
    }

    @Test
    fun corruptWireBootsEmptyAndRegistrationCaps() = runBlocking {
        prefs.map[AccountRegistryStore.KEY_WIRE] = "{\"v\":1,\"accounts\":[{"
        val store = AccountRegistryStore(prefs)
        assertTrue(store.accounts.first().isEmpty())
        // Cap enforced through the shared normalize.
        val many = (1..12).map { account(it.toString(16).padStart(64, '0')) }
        many.forEach { store.register(it, makeActive = false) }
        assertEquals(AccountRegistry.MAX_ACCOUNTS, store.accounts.first().size)
    }

    /** Minimal in-memory SharedPreferences (contract surface only). */
    private class MemoryPrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, Any?> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = apply { if (value == null || key == null) Unit else map[key] = value }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) map[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) map[key] = value }
            override fun remove(key: String?) = apply { if (key != null) map.remove(key) }
            override fun clear() = apply { map.clear() }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun commit() = true
            override fun apply() {}
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
    }
}
