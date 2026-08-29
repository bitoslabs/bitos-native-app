package space.bitos.app.data.relay

import android.content.SharedPreferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import space.bitos.core.model.RelayListContract
import space.bitos.core.model.RelayUrl

/**
 * Adapter-contract test (AGENTS: shared business changes require native
 * adapter tests): [RelayManager] must execute the shared relay-list
 * contract — validation, dedupe, bounds, non-empty invariant — and apply
 * edits to the live pool (connects added relays, closes removed ones).
 */
class RelayManagerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var transports: MutableMap<RelayUrl, RecordingTransport>
    private lateinit var pool: RelayPool
    private lateinit var prefs: MemoryPrefs

    @BeforeTest
    fun setUp() {
        transports = mutableMapOf()
        prefs = MemoryPrefs()
        pool = RelayPool(scope, emptyList()) { url, _ ->
            RecordingTransport(url).also { transports[url] = it }
        }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun manager() = RelayManager(pool = pool, prefs = prefs)

    @Test
    fun emptyStoreBootsPlatformDefaults() {
        val manager = manager()
        assertEquals(RelayManager.defaultEntries().map { it.url }, manager.entries.value.map { it.url })
        assertEquals("wss://nostr-01.yakihonne.com", manager.entries.value.first().url.value)
        assertTrue(manager.entries.value.first().primary)
        // The default set seeds the pool (url-keyed transports installed).
        assertEquals(transports.keys.map { it.value }.toSet(), manager.entries.value.map { it.url.value }.toSet())
    }

    @Test
    fun addValidatesPersistsAndConnects() {
        val manager = manager()
        pool.start()
        assertTrue(manager.add("wss://added.relay"))
        assertEquals("wss://added.relay", manager.entries.value.last().url.value)
        // Read+write by default and the wire round-trips through the shared codec.
        val persisted = prefs.map[RelayManager.KEY_WIRE] as String
        assertEquals(manager.entries.value, RelayListContract.decode(persisted))
        assertTrue(manager.entries.value.last().write)
        // Connected immediately when the pool is running.
        assertEquals(RelayConnectionState.CONNECTED, transports[RelayUrl.parse("wss://added.relay")!!]?.state?.value)
        // Invalid / duplicate / over-cap adds change nothing.
        assertFalse(manager.add("http://nope.relay"))
        assertFalse(manager.add("wss://added.relay"))
    }

    @Test
    fun removeClosesTransportAndNeverEmptiesTheSet() {
        val manager = manager()
        assertTrue(manager.add("wss://extra.relay"))
        val removed = RelayUrl.parse("wss://extra.relay")!!
        val transport = transports[removed]!!
        manager.remove(removed)
        assertFalse(manager.entries.value.any { it.url == removed })
        assertTrue(transport.closed, "removed relay's transport must close")
        // The set never becomes empty: defaults survive repeated removals.
        repeat(10) { manager.remove(manager.entries.value.first().url) }
        assertTrue(manager.entries.value.isNotEmpty())
    }

    @Test
    fun setRolesGuardsBothFalseAndPersists() {
        val manager = manager()
        val target = manager.entries.value.first()
        manager.setRoles(target.url, read = false, write = false) // no-op
        assertEquals(target, manager.entries.value.first())
        manager.setRoles(target.url, read = true, write = false) // read-only
        val updated = manager.entries.value.first()
        assertTrue(updated.read)
        assertFalse(updated.write)
        assertEquals(manager.entries.value, RelayListContract.decode(prefs.map[RelayManager.KEY_WIRE] as String))
    }

    @Test
    fun corruptWireFallsBackToDefaults() {
        prefs.map[RelayManager.KEY_WIRE] = "{\"v\":1,\"relays\":[{"
        val manager = manager()
        assertEquals(RelayManager.defaultEntries().map { it.url }, manager.entries.value.map { it.url })
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

    /** Deterministic transport double recording lifecycle. */
    private class RecordingTransport(private val relay: RelayUrl) : RelayTransport {
        private val mutableState = MutableStateFlow(RelayConnectionState.DISCONNECTED)
        private val mutableFrames = MutableSharedFlow<RelayFrame>(
            replay = 8, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val state: StateFlow<RelayConnectionState> = mutableState
        override val frames: SharedFlow<RelayFrame> = mutableFrames
        var closed = false

        override fun connect() {
            mutableState.value = RelayConnectionState.CONNECTED
        }

        override fun send(message: String): Boolean = true

        override fun close(code: Int, reason: String) {
            closed = true
            mutableState.value = RelayConnectionState.DISCONNECTED
        }
    }
}
