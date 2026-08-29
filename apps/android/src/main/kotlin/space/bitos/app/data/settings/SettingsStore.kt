package space.bitos.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import space.bitos.core.settings.SettingsCodec
import space.bitos.core.settings.SettingsContract
import space.bitos.core.settings.SettingsRules
import space.bitos.core.settings.SettingsSnapshot

/**
 * APP-018 settings adapter: SharedPreferences-backed key-value store
 * executing the shared `space.bitos.core.settings` contract directly
 * (Android links business-core as a Gradle module). All rules — defaults,
 * validation, canonical wire values, clear-cache protection, cache
 * formatting — live in business-core; this adapter only persists and
 * publishes state.
 *
 * [prefs] is injectable so the adapter contract is JVM-testable against an
 * in-memory store (AGENTS: shared business changes need adapter tests).
 */
class SettingsStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(SettingsContractKeys.PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val _snapshot = MutableStateFlow(decode())
    val snapshot: StateFlow<SettingsSnapshot> = _snapshot

    /** Reload the typed snapshot from persisted wire values. */
    fun reload() {
        _snapshot.value = decode()
    }

    /**
     * Persist one raw wire value through shared validation; returns false
     * (and persists nothing) when the shared rules reject it.
     */
    fun setRaw(key: String, rawValue: String): Boolean {
        val canonical = SettingsRules.normalize(key, rawValue) ?: return false
        prefs.edit().putString(key, canonical).apply()
        reload()
        return true
    }

    /** Clear cache: remove every settings key except protected device globals. */
    fun clearCache() {
        val editor = prefs.edit()
        for (key in SettingsRules.clearCacheRemovableKeys(prefs.all.keys)) {
            editor.remove(key)
        }
        editor.apply()
        reload()
    }

    /** Sum of persisted settings bytes (legacy cache-size parity). */
    fun cacheSizeLabel(): String {
        val bytes = SettingsContractKeys.ALL
            .mapNotNull { prefs.all[it] as? String }
            .sumOf { it.toByteArray().size.toLong() }
        return SettingsRules.formatCacheSize(bytes)
    }

    /** Short npub for display (shared rule, legacy `_shortNpub` parity). */
    fun shortNpub(npub: String): String = SettingsRules.shortNpub(npub)

    private fun decode(): SettingsSnapshot = SettingsCodec.decode(persistedKv())

    private fun persistedKv(): Map<String, String> =
        SettingsContractKeys.ALL.mapNotNull { key ->
            (prefs.all[key] as? String)?.let { key to it }
        }.toMap()
}

/** Canonical storage keys mirrored for the adapter contract test. */
object SettingsContractKeys {
    val ALL: List<String> = listOf(
        SettingsContract.KEY_THEME_MODE,
        SettingsContract.KEY_ACCENT_COLOR,
        SettingsContract.KEY_FONT_SIZE,
        SettingsContract.KEY_LANGUAGE,
        SettingsContract.KEY_NOTIFICATIONS_ENABLED,
        SettingsContract.KEY_SOUND_ENABLED,
        SettingsContract.KEY_HAPTIC_ENABLED,
        SettingsContract.KEY_COMPACT_MODE,
        SettingsContract.KEY_FEED_TIMELINE,
        SettingsContract.KEY_FEED_MEDIA_PREVIEW,
        SettingsContract.KEY_FEED_SHOW_REACTIONS,
        SettingsContract.KEY_FEED_SHOW_PROTOCOL_NOTES,
        SettingsContract.KEY_MEDIA_AUTO_PLAY,
        SettingsContract.KEY_VIDEO_QUALITY,
        SettingsContract.KEY_VIDEO_PLAYBACK_RATE,
        SettingsContract.KEY_DEFAULT_ZAP_AMOUNT,
        SettingsContract.KEY_TIME_ZONE,
        SettingsContract.KEY_DATE_FORMAT,
        SettingsContract.KEY_SENSITIVE_MEDIA,
        SettingsContract.KEY_BITZ_MODE,
        SettingsContract.KEY_VIDEO_MUTED,
    )

    const val PREFS_NAME = "bitos_settings"
}
