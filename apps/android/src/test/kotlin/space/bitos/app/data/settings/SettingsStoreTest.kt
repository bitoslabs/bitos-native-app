package space.bitos.app.data.settings

import android.content.SharedPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import space.bitos.core.settings.SettingsContract
import space.bitos.core.settings.FeedTimelineSetting
import space.bitos.core.settings.ThemeModeSetting

/**
 * Adapter contract (AGENTS: shared business changes require native
 * adapter-contract tests): [SettingsStore] must persist canonical wire
 * values through the shared rules and republish a typed snapshot.
 */
class SettingsStoreTest {

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

    @Test
    fun storeDecodesDefaultsFromEmptyPrefs() {
        val store = SettingsStore(MemoryPrefs())
        val snapshot = store.snapshot.value
        assertEquals(ThemeModeSetting.DARK, snapshot.themeMode)
        assertTrue(snapshot.notificationsEnabled)
        assertEquals(21, snapshot.defaultZapAmount)
    }

    @Test
    fun writesGoThroughSharedValidationAndCanonicalize() {
        val prefs = MemoryPrefs()
        val store = SettingsStore(prefs)
        // Legacy boolean spelling canonicalizes to "1".
        assertTrue(store.setRaw(SettingsContract.KEY_NOTIFICATIONS_ENABLED, "true"))
        assertEquals("1", prefs.map[SettingsContract.KEY_NOTIFICATIONS_ENABLED])
        assertFalse(store.snapshot.value.notificationsEnabled.not()) // still on
        // Rejected values persist nothing.
        assertFalse(store.setRaw(SettingsContract.KEY_DEFAULT_ZAP_AMOUNT, "0"))
        assertNull(prefs.map[SettingsContract.KEY_DEFAULT_ZAP_AMOUNT])
        assertFalse(store.setRaw("bitos_unknown", "1"))
        // Enum write reflects in the published snapshot.
        assertTrue(store.setRaw(SettingsContract.KEY_FEED_TIMELINE, "trending"))
        assertEquals(FeedTimelineSetting.TRENDING, store.snapshot.value.feedTimeline)
    }

    @Test
    fun clearCacheKeepsProtectedDeviceGlobals() {
        val prefs = MemoryPrefs()
        val store = SettingsStore(prefs)
        store.setRaw(SettingsContract.KEY_THEME_MODE, "system")
        store.setRaw(SettingsContract.KEY_FEED_TIMELINE, "trending")
        store.setRaw(SettingsContract.KEY_DEFAULT_ZAP_AMOUNT, "100")
        store.clearCache()
        assertEquals("system", prefs.map[SettingsContract.KEY_THEME_MODE]) // protected
        assertNull(prefs.map[SettingsContract.KEY_FEED_TIMELINE])          // reset
        assertNull(prefs.map[SettingsContract.KEY_DEFAULT_ZAP_AMOUNT])     // reset
        assertEquals(FeedTimelineSetting.LATEST, store.snapshot.value.feedTimeline)
    }

    @Test
    fun cacheSizeLabelFormatsThroughSharedRule() {
        val store = SettingsStore(MemoryPrefs())
        store.setRaw(SettingsContract.KEY_THEME_MODE, "dark") // "dark" = 4 bytes
        assertEquals("4 B", store.cacheSizeLabel())
    }

    @Test
    fun accentColorAndPlaybackRateRoundTripThroughTheAdapter() {
        val prefs = MemoryPrefs()
        val store = SettingsStore(prefs)
        // Accent canonicalizes to uppercase and decodes back into the snapshot.
        assertTrue(store.setRaw(SettingsContract.KEY_ACCENT_COLOR, "#06b6d4"))
        assertEquals("#06B6D4", prefs.map[SettingsContract.KEY_ACCENT_COLOR])
        assertEquals("#06B6D4", store.snapshot.value.accentColorHex)
        // Playback rate accepts only the discrete legacy steps; an unknown
        // step self-heals to the default (shared enum rule) instead of rejecting.
        assertTrue(store.setRaw(SettingsContract.KEY_VIDEO_PLAYBACK_RATE, "1.5"))
        assertEquals(space.bitos.core.settings.VideoPlaybackRateSetting.X_1_5, store.snapshot.value.videoPlaybackRate)
        assertTrue(store.setRaw(SettingsContract.KEY_VIDEO_PLAYBACK_RATE, "1.1"))
        assertEquals("1", prefs.map[SettingsContract.KEY_VIDEO_PLAYBACK_RATE])
        assertEquals(space.bitos.core.settings.VideoPlaybackRateSetting.X_1, store.snapshot.value.videoPlaybackRate)
        // Accent survives clear cache (protected device global), rate resets.
        store.clearCache()
        assertEquals("#06B6D4", prefs.map[SettingsContract.KEY_ACCENT_COLOR])
        assertNull(prefs.map[SettingsContract.KEY_VIDEO_PLAYBACK_RATE])
        assertEquals(space.bitos.core.settings.VideoPlaybackRateSetting.X_1, store.snapshot.value.videoPlaybackRate)
    }
}
