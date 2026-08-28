package space.bitos.app.data.feed

import android.content.SharedPreferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import space.bitos.core.feed.AlgorithmContract
import space.bitos.core.feed.AlgorithmPresetId
import space.bitos.core.feed.AlgorithmSignal
import space.bitos.core.feed.AlgorithmSurface

/**
 * Adapter-contract test (AGENTS): [AlgorithmStore] persists the versioned
 * wire, executes shared rules (presets, steps, freshness) and republishes
 * a normalized snapshot.
 */
class AlgorithmStoreTest {

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

    @Test
    fun emptyStoreBootsDefaultsAndPersistedWireRoundTrips() {
        val store = AlgorithmStore(prefs)
        // Defaults: balanced presets, valid freshness.
        assertEquals(
            AlgorithmContract.preset(AlgorithmSurface.FEED, AlgorithmPresetId.BALANCED),
            store.snapshot.value.surfaces[AlgorithmSurface.FEED],
        )
        assertEquals(AlgorithmContract.FRESHNESS_DEFAULT_HOURS, store.snapshot.value.freshnessHours)
        // Edits persist and a fresh store reads them back.
        store.setFreshness(72)
        store.setPreset(AlgorithmSurface.FEED, AlgorithmPresetId.TRENDING)
        store.setEnabled(AlgorithmSurface.REELS, false)
        val reloaded = AlgorithmStore(prefs)
        assertEquals(72, reloaded.snapshot.value.freshnessHours)
        assertEquals(
            AlgorithmPresetId.TRENDING,
            reloaded.detectPreset(AlgorithmSurface.FEED),
        )
        assertEquals(false, reloaded.snapshot.value.surfaces[AlgorithmSurface.REELS]!!.enabled)
    }

    @Test
    fun signalEditsStrayToCustomAndSnapBackToPresets() {
        val store = AlgorithmStore(prefs)
        store.setSignal(AlgorithmSurface.FEED, AlgorithmSignal.RECENCY, enabled = true, weight = 0.9)
        assertEquals(AlgorithmPresetId.CUSTOM, store.detectPreset(AlgorithmSurface.FEED))
        // Weight quantizes to the 5% grid (0.92 → 0.90).
        assertEquals(0.90, store.snapshot.value.surfaces[AlgorithmSurface.FEED]!!.signals[AlgorithmSignal.RECENCY]!!.weight)
        store.setPreset(AlgorithmSurface.FEED, AlgorithmPresetId.BALANCED)
        assertEquals(AlgorithmPresetId.BALANCED, store.detectPreset(AlgorithmSurface.FEED))
        assertTrue(store.snapshot.value.surfaces[AlgorithmSurface.FEED]!!.enabled)
    }

    @Test
    fun corruptWireFallsBackToDefaults() {
        prefs.map[AlgorithmStore.KEY_WIRE] = "{\"v\":1,\"s\":{"
        val store = AlgorithmStore(prefs)
        assertEquals(AlgorithmContract.preset(AlgorithmSurface.FEED, AlgorithmPresetId.BALANCED),
            store.snapshot.value.surfaces[AlgorithmSurface.FEED])
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
