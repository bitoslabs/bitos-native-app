package space.bitos.core.settings

/**
 * Versioned settings contract (APP-018, legacy Flutter `SettingsController` /
 * web `settingsSections` parity). All preference rules live here once and
 * both platforms execute them through adapters:
 *
 *  • storage keys + canonical wire format (legacy `bitos_*` key names)
 *  • typed option sets with safe fallback parsing
 *  • value validation/normalization before anything is persisted
 *  • deterministic section catalog (ordering/grouping parity with the web
 *    mobile index: hero · preferences · content · support)
 *  • cache-size formatting + clear-cache protected keys
 *
 * The schema is versioned: adding a key/option bumps [SCHEMA_VERSION];
 * unknown keys and out-of-range values must never crash an adapter — they
 * fall back to defaults.
 */
object SettingsContract {
    const val SCHEMA_VERSION = 2

    // ── Storage keys (legacy bitos_* names) ────────────────────────────
    const val KEY_THEME_MODE = "bitos_theme_mode"
    const val KEY_ACCENT_COLOR = "bitos_accent_color"
    const val KEY_FONT_SIZE = "bitos_font_size"
    const val KEY_LANGUAGE = "bitos_language"
    const val KEY_NOTIFICATIONS_ENABLED = "bitos_notifications"
    const val KEY_SOUND_ENABLED = "bitos_sound"
    const val KEY_HAPTIC_ENABLED = "bitos_haptic"
    const val KEY_COMPACT_MODE = "bitos_compact_mode"
    const val KEY_FEED_TIMELINE = "bitos_feed_timeline"
    const val KEY_FEED_MEDIA_PREVIEW = "bitos_feed_media_preview"
    const val KEY_FEED_SHOW_REACTIONS = "bitos_feed_show_reactions"
    const val KEY_FEED_SHOW_PROTOCOL_NOTES = "bitos_feed_show_protocol_notes"
    const val KEY_MEDIA_AUTO_PLAY = "bitos_media_auto_play"
    const val KEY_VIDEO_QUALITY = "bitos_video_quality"
    const val KEY_VIDEO_PLAYBACK_RATE = "bitos_video_playback_rate"
    const val KEY_DEFAULT_ZAP_AMOUNT = "bitos_default_zap_amount"
    const val KEY_TIME_ZONE = "bitos_time_zone"
    const val KEY_DATE_FORMAT = "bitos_date_format"

    /** Max stored string length (size-bounded persistence rule). */
    const val MAX_VALUE_LENGTH = 64

    /** Default zap amount bounds (legacy slider range). */
    const val ZAP_AMOUNT_MIN = 1
    const val ZAP_AMOUNT_MAX = 100_000
    const val ZAP_AMOUNT_DEFAULT = 21

    /** Brand accent default (bitcoin orange, `AppColors.primary`). */
    const val ACCENT_COLOR_DEFAULT = "#F7931A"

    /** `#RRGGBB` (case-insensitive) — accent palette values. */
    val ACCENT_COLOR_PATTERN: Regex = Regex("^#[0-9a-fA-F]{6}$")

    /**
     * Keys that survive "Clear cache" (legacy `clearCache` protectedKeys
     * parity): device-global identity/theme prefs stay, feed/content
     * preferences reset.
     */
    val CLEAR_CACHE_PROTECTED_KEYS: Set<String> = setOf(
        KEY_THEME_MODE,
        KEY_ACCENT_COLOR,
        KEY_FONT_SIZE,
        KEY_LANGUAGE,
    )

    /** Deterministic section catalog — same order/groups as the web mobile
     *  settings index and the legacy Flutter `_sections` list. Icons/tints
     *  are presentation and stay native, keyed by [SettingsSection.key]. */
    val SECTIONS: List<SettingsSection> = listOf(
        SettingsSection(key = "account", group = SettingsGroup.HERO),
        SettingsSection(key = "lightning", group = SettingsGroup.PREFERENCES),
        SettingsSection(key = "privacy", group = SettingsGroup.PREFERENCES),
        SettingsSection(key = "notifications", group = SettingsGroup.PREFERENCES),
        SettingsSection(key = "appearance", group = SettingsGroup.PREFERENCES),
        SettingsSection(key = "algorithm", group = SettingsGroup.PREFERENCES),
        SettingsSection(key = "security", group = SettingsGroup.CONTENT),
        SettingsSection(key = "media", group = SettingsGroup.CONTENT),
        SettingsSection(key = "language", group = SettingsGroup.CONTENT),
        SettingsSection(key = "relays", group = SettingsGroup.CONTENT),
        SettingsSection(key = "help", group = SettingsGroup.SUPPORT),
        SettingsSection(key = "about", group = SettingsGroup.SUPPORT),
    )

    fun section(key: String): SettingsSection? = SECTIONS.firstOrNull { it.key == key }
}

enum class SettingsGroup { HERO, PREFERENCES, CONTENT, SUPPORT }

data class SettingsSection(val key: String, val group: SettingsGroup)

/** Theme preference (legacy values 'light' | 'dark' | 'system'). */
enum class ThemeModeSetting(val wire: String) {
    LIGHT("light"), DARK("dark"), SYSTEM("system");

    companion object {
        val DEFAULT = DARK
        fun parse(raw: String?): ThemeModeSetting =
            entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}

/** Font scale (legacy 'small' | 'default' | 'large' | 'extra_large'). */
enum class FontSizeSetting(val wire: String) {
    SMALL("small"), DEFAULT("default"), LARGE("large"), EXTRA_LARGE("extra_large");

    companion object {
        fun parse(raw: String?): FontSizeSetting =
            entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}

/** UI language (APP-024 en/lo). */
enum class LanguageSetting(val wire: String) {
    ENGLISH("en"), LAO("lo");

    companion object {
        val DEFAULT = ENGLISH
        fun parse(raw: String?): LanguageSetting =
            entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}

/** Feed timeline ordering (legacy 'latest' | 'trending'). */
enum class FeedTimelineSetting(val wire: String) {
    LATEST("latest"), TRENDING("trending");

    companion object {
        val DEFAULT = LATEST
        fun parse(raw: String?): FeedTimelineSetting =
            entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}

/** Media autoplay policy (legacy 'always' | 'wifi' | 'never'). */
enum class MediaAutoPlaySetting(val wire: String) {
    ALWAYS("always"), WIFI("wifi"), NEVER("never");

    companion object {
        val DEFAULT = WIFI
        fun parse(raw: String?): MediaAutoPlaySetting =
            entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}

/** Video playback quality (legacy 'auto' | 'high' | 'low'). */
enum class VideoQualitySetting(val wire: String) {
    AUTO("auto"), HIGH("high"), LOW("low");

    companion object {
        val DEFAULT = AUTO
        fun parse(raw: String?): VideoQualitySetting =
            entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}

/** Video playback rate (legacy `bitos_video_playback_rate` steps). */
enum class VideoPlaybackRateSetting(val wire: String, val rate: Double) {
    X_0_5("0.5", 0.5), X_0_75("0.75", 0.75), X_1("1", 1.0),
    X_1_25("1.25", 1.25), X_1_5("1.5", 1.5), X_2("2", 2.0);

    companion object {
        val DEFAULT = X_1
        fun parse(raw: String?): VideoPlaybackRateSetting =
            entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}

/** Date display order (legacy 'MDY' | 'DMY' | 'YMD'). */
enum class DateFormatSetting(val wire: String) {
    MDY("MDY"), DMY("DMY"), YMD("YMD");

    companion object {
        val DEFAULT = MDY
        fun parse(raw: String?): DateFormatSetting =
            entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}

/**
 * Fully decoded settings state. Snapshots are immutable; adapters apply a
 * change by producing the next snapshot and persisting the normalized wire
 * value through [SettingsRules].
 */
data class SettingsSnapshot(
    val themeMode: ThemeModeSetting = ThemeModeSetting.DEFAULT,
    val accentColorHex: String = SettingsContract.ACCENT_COLOR_DEFAULT,
    val fontSize: FontSizeSetting = FontSizeSetting.DEFAULT,
    val language: LanguageSetting = LanguageSetting.DEFAULT,
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
    val compactMode: Boolean = false,
    val feedTimeline: FeedTimelineSetting = FeedTimelineSetting.DEFAULT,
    val mediaPreview: Boolean = true,
    val showReactions: Boolean = true,
    val showProtocolNotes: Boolean = false,
    val mediaAutoPlay: MediaAutoPlaySetting = MediaAutoPlaySetting.DEFAULT,
    val videoQuality: VideoQualitySetting = VideoQualitySetting.DEFAULT,
    val videoPlaybackRate: VideoPlaybackRateSetting = VideoPlaybackRateSetting.DEFAULT,
    val defaultZapAmount: Int = SettingsContract.ZAP_AMOUNT_DEFAULT,
    /** 'auto' or a bounded IANA zone id (≤ [SettingsContract.MAX_VALUE_LENGTH]). */
    val timeZone: String = "auto",
    val dateFormat: DateFormatSetting = DateFormatSetting.DEFAULT,
)

/**
 * Key-value codec: canonical wire strings in ("1"/"0" booleans, decimal
 * ints, enum wire names), typed snapshot out. Unknown keys are ignored;
 * malformed/oversized values fall back to defaults — a corrupt store must
 * never crash the settings hub.
 */
object SettingsCodec {

    fun decode(kv: Map<String, String>): SettingsSnapshot = SettingsSnapshot(
        themeMode = ThemeModeSetting.parse(bounded(kv[SettingsContract.KEY_THEME_MODE])),
        accentColorHex = bounded(kv[SettingsContract.KEY_ACCENT_COLOR])
            ?.takeIf { SettingsContract.ACCENT_COLOR_PATTERN.matches(it) }
            ?: SettingsContract.ACCENT_COLOR_DEFAULT,
        fontSize = FontSizeSetting.parse(bounded(kv[SettingsContract.KEY_FONT_SIZE])),
        language = LanguageSetting.parse(bounded(kv[SettingsContract.KEY_LANGUAGE])),
        notificationsEnabled = bool(kv[SettingsContract.KEY_NOTIFICATIONS_ENABLED], default = true),
        soundEnabled = bool(kv[SettingsContract.KEY_SOUND_ENABLED], default = true),
        hapticEnabled = bool(kv[SettingsContract.KEY_HAPTIC_ENABLED], default = true),
        compactMode = bool(kv[SettingsContract.KEY_COMPACT_MODE], default = false),
        feedTimeline = FeedTimelineSetting.parse(bounded(kv[SettingsContract.KEY_FEED_TIMELINE])),
        mediaPreview = bool(kv[SettingsContract.KEY_FEED_MEDIA_PREVIEW], default = true),
        showReactions = bool(kv[SettingsContract.KEY_FEED_SHOW_REACTIONS], default = true),
        showProtocolNotes = bool(kv[SettingsContract.KEY_FEED_SHOW_PROTOCOL_NOTES], default = false),
        mediaAutoPlay = MediaAutoPlaySetting.parse(bounded(kv[SettingsContract.KEY_MEDIA_AUTO_PLAY])),
        videoQuality = VideoQualitySetting.parse(bounded(kv[SettingsContract.KEY_VIDEO_QUALITY])),
        videoPlaybackRate = VideoPlaybackRateSetting.parse(bounded(kv[SettingsContract.KEY_VIDEO_PLAYBACK_RATE])),
        defaultZapAmount = zap(kv[SettingsContract.KEY_DEFAULT_ZAP_AMOUNT]),
        timeZone = bounded(kv[SettingsContract.KEY_TIME_ZONE])?.takeIf { it.isNotBlank() } ?: "auto",
        dateFormat = DateFormatSetting.parse(bounded(kv[SettingsContract.KEY_DATE_FORMAT])),
    )

    /** Canonical wire value for a typed snapshot field, by storage key. */
    fun encode(key: String, snapshot: SettingsSnapshot): String? = when (key) {
        SettingsContract.KEY_THEME_MODE -> snapshot.themeMode.wire
        SettingsContract.KEY_ACCENT_COLOR -> snapshot.accentColorHex
        SettingsContract.KEY_FONT_SIZE -> snapshot.fontSize.wire
        SettingsContract.KEY_LANGUAGE -> snapshot.language.wire
        SettingsContract.KEY_NOTIFICATIONS_ENABLED -> snapshot.notificationsEnabled.wireBool
        SettingsContract.KEY_SOUND_ENABLED -> snapshot.soundEnabled.wireBool
        SettingsContract.KEY_HAPTIC_ENABLED -> snapshot.hapticEnabled.wireBool
        SettingsContract.KEY_COMPACT_MODE -> snapshot.compactMode.wireBool
        SettingsContract.KEY_FEED_TIMELINE -> snapshot.feedTimeline.wire
        SettingsContract.KEY_FEED_MEDIA_PREVIEW -> snapshot.mediaPreview.wireBool
        SettingsContract.KEY_FEED_SHOW_REACTIONS -> snapshot.showReactions.wireBool
        SettingsContract.KEY_FEED_SHOW_PROTOCOL_NOTES -> snapshot.showProtocolNotes.wireBool
        SettingsContract.KEY_MEDIA_AUTO_PLAY -> snapshot.mediaAutoPlay.wire
        SettingsContract.KEY_VIDEO_QUALITY -> snapshot.videoQuality.wire
        SettingsContract.KEY_VIDEO_PLAYBACK_RATE -> snapshot.videoPlaybackRate.wire
        SettingsContract.KEY_DEFAULT_ZAP_AMOUNT -> snapshot.defaultZapAmount.toString()
        SettingsContract.KEY_TIME_ZONE -> snapshot.timeZone
        SettingsContract.KEY_DATE_FORMAT -> snapshot.dateFormat.wire
        else -> null
    }

    private val Boolean.wireBool: String get() = if (this) "1" else "0"

    private fun bounded(raw: String?): String? =
        raw?.takeIf { it.length <= SettingsContract.MAX_VALUE_LENGTH }

    private fun bool(raw: String?, default: Boolean): Boolean = when (raw) {
        "1", "true" -> true
        "0", "false" -> false
        else -> default
    }

    private fun zap(raw: String?): Int {
        val value = raw?.toIntOrNull() ?: return SettingsContract.ZAP_AMOUNT_DEFAULT
        return value.coerceIn(SettingsContract.ZAP_AMOUNT_MIN, SettingsContract.ZAP_AMOUNT_MAX)
    }
}

/**
 * Pure product rules for settings writes and derived display values.
 */
object SettingsRules {

    /**
     * Validates + normalizes a raw wire value for a known key (adapters call
     * this before persisting anything). Returns the canonical wire value, or
     * null when the key is unknown / the value is rejected.
     */
    fun normalize(key: String, rawValue: String): String? {
        if (rawValue.length > SettingsContract.MAX_VALUE_LENGTH) return null
        return when (key) {
            SettingsContract.KEY_THEME_MODE ->
                ThemeModeSetting.parse(rawValue).wire
            SettingsContract.KEY_ACCENT_COLOR ->
                rawValue.uppercase().takeIf { SettingsContract.ACCENT_COLOR_PATTERN.matches(it) }
            SettingsContract.KEY_FONT_SIZE ->
                FontSizeSetting.parse(rawValue).wire
            SettingsContract.KEY_LANGUAGE ->
                LanguageSetting.parse(rawValue).wire
            SettingsContract.KEY_FEED_TIMELINE ->
                FeedTimelineSetting.parse(rawValue).wire
            SettingsContract.KEY_MEDIA_AUTO_PLAY ->
                MediaAutoPlaySetting.parse(rawValue).wire
            SettingsContract.KEY_VIDEO_QUALITY ->
                VideoQualitySetting.parse(rawValue).wire
            SettingsContract.KEY_VIDEO_PLAYBACK_RATE ->
                VideoPlaybackRateSetting.parse(rawValue).wire
            SettingsContract.KEY_DATE_FORMAT ->
                DateFormatSetting.parse(rawValue).wire
            SettingsContract.KEY_NOTIFICATIONS_ENABLED,
            SettingsContract.KEY_SOUND_ENABLED,
            SettingsContract.KEY_HAPTIC_ENABLED,
            SettingsContract.KEY_COMPACT_MODE,
            SettingsContract.KEY_FEED_MEDIA_PREVIEW,
            SettingsContract.KEY_FEED_SHOW_REACTIONS,
            SettingsContract.KEY_FEED_SHOW_PROTOCOL_NOTES,
            -> when (rawValue) {
                "1", "true" -> "1"
                "0", "false" -> "0"
                else -> null
            }
            SettingsContract.KEY_DEFAULT_ZAP_AMOUNT -> {
                val amount = rawValue.toIntOrNull() ?: return null
                if (amount !in SettingsContract.ZAP_AMOUNT_MIN..SettingsContract.ZAP_AMOUNT_MAX) {
                    null
                } else {
                    amount.toString()
                }
            }
            SettingsContract.KEY_TIME_ZONE ->
                rawValue.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /** Whether a raw value would persist unchanged for [key]. */
    fun isValid(key: String, rawValue: String): Boolean =
        normalize(key, rawValue) == rawValue

    /**
     * Keys to delete on "Clear cache" — every settings key except the
     * protected device-global ones (legacy parity).
     */
    fun clearCacheRemovableKeys(allKeys: Collection<String>): List<String> =
        allKeys.filter { it !in SettingsContract.CLEAR_CACHE_PROTECTED_KEYS }

    /** Cache-size label (legacy `_formatBytes`: `87 B`, `12.3 KB`, `1.4 MB`). */
    fun formatCacheSize(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1_024 -> "$bytes B"
        bytes < 1_024 * 1_024 -> oneDecimal(bytes / 1_024.0) + " KB"
        else -> oneDecimal(bytes / (1_024.0 * 1_024.0)) + " MB"
    }

    /** Locale-free `%.1f` with legacy `toStringAsFixed(1)` rounding. */
    private fun oneDecimal(value: Double): String {
        val tenths = (value * 10.0 + 0.5).toLong().coerceAtLeast(0)
        return "${tenths / 10}.${tenths % 10}"
    }

    /**
     * Short npub for display (legacy `_shortNpub`):
     * `npub1abc123…xyz789` — first 10 + ellipsis + last 6, bounded inputs pass through.
     */
    fun shortNpub(npub: String): String = when {
        npub.length <= 16 -> npub
        else -> npub.take(10) + "…" + npub.takeLast(6)
    }
}
