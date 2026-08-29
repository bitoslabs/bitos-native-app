package space.bitos.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-018 settings contract (legacy Flutter `SettingsController` / web
 * `settingsSections` parity): defaults, wire validation, clear-cache
 * protection, cache formatting and the deterministic section catalog.
 */
class SettingsTest {

    // MARK: - Decode defaults

    @Test
    fun emptyStoreDecodesToDefaults() {
        val snapshot = SettingsCodec.decode(emptyMap())
        assertEquals(ThemeModeSetting.DARK, snapshot.themeMode)
        assertEquals("#F7931A", snapshot.accentColorHex)
        assertEquals(FontSizeSetting.DEFAULT, snapshot.fontSize)
        assertEquals(LanguageSetting.ENGLISH, snapshot.language)
        assertTrue(snapshot.notificationsEnabled)
        assertTrue(snapshot.soundEnabled)
        assertTrue(snapshot.hapticEnabled)
        assertFalse(snapshot.compactMode)
        assertEquals(FeedTimelineSetting.LATEST, snapshot.feedTimeline)
        assertTrue(snapshot.mediaPreview)
        assertTrue(snapshot.showReactions)
        assertFalse(snapshot.showProtocolNotes)
        assertEquals(MediaAutoPlaySetting.WIFI, snapshot.mediaAutoPlay)
        assertEquals(VideoQualitySetting.AUTO, snapshot.videoQuality)
        assertEquals(VideoPlaybackRateSetting.X_1, snapshot.videoPlaybackRate)
        assertEquals(21, snapshot.defaultZapAmount)
        assertEquals("auto", snapshot.timeZone)
        assertEquals(DateFormatSetting.MDY, snapshot.dateFormat)
    }

    @Test
    fun legacyWireValuesDecode() {
        val snapshot = SettingsCodec.decode(
            mapOf(
                SettingsContract.KEY_THEME_MODE to "system",
                SettingsContract.KEY_FONT_SIZE to "extra_large",
                SettingsContract.KEY_LANGUAGE to "lo",
                SettingsContract.KEY_COMPACT_MODE to "1",
                SettingsContract.KEY_FEED_TIMELINE to "trending",
                SettingsContract.KEY_FEED_SHOW_PROTOCOL_NOTES to "1",
                SettingsContract.KEY_MEDIA_AUTO_PLAY to "never",
                SettingsContract.KEY_VIDEO_QUALITY to "high",
                SettingsContract.KEY_VIDEO_PLAYBACK_RATE to "1.5",
                SettingsContract.KEY_ACCENT_COLOR to "#06B6D4",
                SettingsContract.KEY_DEFAULT_ZAP_AMOUNT to "1000",
                SettingsContract.KEY_TIME_ZONE to "Asia/Vientiane",
                SettingsContract.KEY_DATE_FORMAT to "DMY",
            ),
        )
        assertEquals(ThemeModeSetting.SYSTEM, snapshot.themeMode)
        assertEquals(FontSizeSetting.EXTRA_LARGE, snapshot.fontSize)
        assertEquals(LanguageSetting.LAO, snapshot.language)
        assertTrue(snapshot.compactMode)
        assertEquals(FeedTimelineSetting.TRENDING, snapshot.feedTimeline)
        assertTrue(snapshot.showProtocolNotes)
        assertEquals(MediaAutoPlaySetting.NEVER, snapshot.mediaAutoPlay)
        assertEquals(VideoQualitySetting.HIGH, snapshot.videoQuality)
        assertEquals(VideoPlaybackRateSetting.X_1_5, snapshot.videoPlaybackRate)
        assertEquals("#06B6D4", snapshot.accentColorHex)
        assertEquals(1000, snapshot.defaultZapAmount)
        assertEquals("Asia/Vientiane", snapshot.timeZone)
        assertEquals(DateFormatSetting.DMY, snapshot.dateFormat)
    }

    @Test
    fun corruptValuesFallBackInsteadOfCrashing() {
        val snapshot = SettingsCodec.decode(
            mapOf(
                SettingsContract.KEY_THEME_MODE to "neon",
                SettingsContract.KEY_NOTIFICATIONS_ENABLED to "yes",
                SettingsContract.KEY_DEFAULT_ZAP_AMOUNT to "abc",
                SettingsContract.KEY_ACCENT_COLOR to "not-a-color",
                SettingsContract.KEY_VIDEO_PLAYBACK_RATE to "9",
                SettingsContract.KEY_TIME_ZONE to "x".repeat(999), // over the bound
            ),
        )
        assertEquals(ThemeModeSetting.DEFAULT, snapshot.themeMode)
        assertEquals(SettingsContract.ACCENT_COLOR_DEFAULT, snapshot.accentColorHex)
        assertTrue(snapshot.notificationsEnabled) // unknown bool → default
        assertEquals(21, snapshot.defaultZapAmount)
        assertEquals(VideoPlaybackRateSetting.X_1, snapshot.videoPlaybackRate)
        assertEquals("auto", snapshot.timeZone)
    }

    @Test
    fun zapAmountIsClampedIntoBoundsOnDecode() {
        assertEquals(
            SettingsContract.ZAP_AMOUNT_MAX,
            SettingsCodec.decode(mapOf(SettingsContract.KEY_DEFAULT_ZAP_AMOUNT to "999999999"))
                .defaultZapAmount,
        )
    }

    // MARK: - Encode / normalize (write path)

    @Test
    fun encodeRoundTripsEveryKey() {
        val snapshot = SettingsCodec.decode(
            mapOf(
                SettingsContract.KEY_THEME_MODE to "light",
                SettingsContract.KEY_FEED_SHOW_PROTOCOL_NOTES to "1",
                SettingsContract.KEY_DEFAULT_ZAP_AMOUNT to "77",
            ),
        )
        val keys = listOf(
            SettingsContract.KEY_THEME_MODE, SettingsContract.KEY_ACCENT_COLOR,
            SettingsContract.KEY_FONT_SIZE,
            SettingsContract.KEY_LANGUAGE, SettingsContract.KEY_NOTIFICATIONS_ENABLED,
            SettingsContract.KEY_SOUND_ENABLED, SettingsContract.KEY_HAPTIC_ENABLED,
            SettingsContract.KEY_COMPACT_MODE, SettingsContract.KEY_FEED_TIMELINE,
            SettingsContract.KEY_FEED_MEDIA_PREVIEW, SettingsContract.KEY_FEED_SHOW_REACTIONS,
            SettingsContract.KEY_FEED_SHOW_PROTOCOL_NOTES, SettingsContract.KEY_MEDIA_AUTO_PLAY,
            SettingsContract.KEY_VIDEO_QUALITY, SettingsContract.KEY_VIDEO_PLAYBACK_RATE,
            SettingsContract.KEY_DEFAULT_ZAP_AMOUNT,
            SettingsContract.KEY_TIME_ZONE, SettingsContract.KEY_DATE_FORMAT,
        )
        for (key in keys) {
            val wire = SettingsCodec.encode(key, snapshot)
            assertTrue(wire != null, "encode($key) must produce a value")
            val redecoded = SettingsCodec.decode(mapOf(key to wire!!))
            assertEquals(wire, SettingsCodec.encode(key, redecoded), "round trip $key")
        }
    }

    @Test
    fun normalizeValidatesAndCanonicalizes() {
        // Booleans accept legacy "true"/"false" but persist canonical "1"/"0".
        assertEquals("1", SettingsRules.normalize(SettingsContract.KEY_NOTIFICATIONS_ENABLED, "true"))
        assertEquals("0", SettingsRules.normalize(SettingsContract.KEY_SOUND_ENABLED, "false"))
        assertNull(SettingsRules.normalize(SettingsContract.KEY_HAPTIC_ENABLED, "maybe"))
        // Enum values canonicalize; invalid → default (dark), not null, so a
        // hand-edited store self-heals.
        assertEquals("dark", SettingsRules.normalize(SettingsContract.KEY_THEME_MODE, "garbage"))
        assertEquals("wifi", SettingsRules.normalize(SettingsContract.KEY_MEDIA_AUTO_PLAY, "garbage"))
        // Accent color canonicalizes to uppercase #RRGGBB; invalid hex rejected.
        assertEquals("#06B6D4", SettingsRules.normalize(SettingsContract.KEY_ACCENT_COLOR, "#06b6d4"))
        assertNull(SettingsRules.normalize(SettingsContract.KEY_ACCENT_COLOR, "orange"))
        // Playback rate accepts only the discrete legacy steps; unknown
        // values self-heal to the default step (same rule as other enums).
        assertEquals("1.5", SettingsRules.normalize(SettingsContract.KEY_VIDEO_PLAYBACK_RATE, "1.5"))
        assertEquals("1", SettingsRules.normalize(SettingsContract.KEY_VIDEO_PLAYBACK_RATE, "1.1"))
        // Zap amount is hard-validated (never silently clamped on write).
        assertEquals("50", SettingsRules.normalize(SettingsContract.KEY_DEFAULT_ZAP_AMOUNT, "50"))
        assertNull(SettingsRules.normalize(SettingsContract.KEY_DEFAULT_ZAP_AMOUNT, "0"))
        assertNull(SettingsRules.normalize(SettingsContract.KEY_DEFAULT_ZAP_AMOUNT, "100001"))
        // Unknown key or oversized value is rejected.
        assertNull(SettingsRules.normalize("bitos_unknown", "1"))
        assertNull(SettingsRules.normalize(SettingsContract.KEY_TIME_ZONE, "x".repeat(65)))
    }

    @Test
    fun isValidAcceptsCanonicalWireValues() {
        assertTrue(SettingsRules.isValid(SettingsContract.KEY_THEME_MODE, "system"))
        assertTrue(SettingsRules.isValid(SettingsContract.KEY_FEED_TIMELINE, "latest"))
        assertFalse(SettingsRules.isValid(SettingsContract.KEY_THEME_MODE, "SYSTEM"))
        assertFalse(SettingsRules.isValid(SettingsContract.KEY_DEFAULT_ZAP_AMOUNT, "21.0"))
    }

    // MARK: - Clear cache

    @Test
    fun clearCacheKeepsOnlyProtectedDeviceGlobals() {
        val all = setOf(
            SettingsContract.KEY_THEME_MODE,
            SettingsContract.KEY_ACCENT_COLOR,
            SettingsContract.KEY_LANGUAGE,
            SettingsContract.KEY_FEED_TIMELINE,
            SettingsContract.KEY_DEFAULT_ZAP_AMOUNT,
        )
        assertEquals(
            listOf(SettingsContract.KEY_FEED_TIMELINE, SettingsContract.KEY_DEFAULT_ZAP_AMOUNT),
            SettingsRules.clearCacheRemovableKeys(all),
        )
    }

    // MARK: - Cache size formatting (legacy _formatBytes parity)

    @Test
    fun formatsCacheSizeLikeLegacyApp() {
        assertEquals("0 B", SettingsRules.formatCacheSize(0))
        assertEquals("87 B", SettingsRules.formatCacheSize(87))
        assertEquals("1.0 KB", SettingsRules.formatCacheSize(1_024))
        assertEquals("12.3 KB", SettingsRules.formatCacheSize(12_583))
        assertEquals("1.4 MB", SettingsRules.formatCacheSize(1_500_000))
        assertEquals("—", SettingsRules.formatCacheSize(-1))
    }

    // MARK: - npub display (legacy _shortNpub parity)

    @Test
    fun shortNpubMatchesLegacyEllipsizing() {
        val npub = "npub1" + "a".repeat(55)
        assertEquals("npub1aaaaa…aaaaaa", SettingsRules.shortNpub(npub))
        assertEquals("npub1short", SettingsRules.shortNpub("npub1short"))
        assertEquals("", SettingsRules.shortNpub(""))
    }

    // MARK: - Section catalog (web mobile index parity)

    @Test
    fun sectionCatalogOrderMatchesLegacySettingsView() {
        assertEquals(
            listOf(
                "account",
                "lightning", "privacy", "notifications", "appearance", "algorithm",
                "security", "media", "language", "relays",
                "help", "about",
            ),
            SettingsContract.SECTIONS.map { it.key },
        )
        assertEquals(SettingsGroup.HERO, SettingsContract.SECTIONS.first().group)
        assertEquals(SettingsGroup.SUPPORT, SettingsContract.SECTIONS.last().group)
        assertEquals(5, SettingsContract.SECTIONS.count { it.group == SettingsGroup.PREFERENCES })
        assertEquals(4, SettingsContract.SECTIONS.count { it.group == SettingsGroup.CONTENT })
    }

    @Test
    fun schemaIsVersioned() {
        assertEquals(5, SettingsContract.SCHEMA_VERSION)
    }

    // MARK: - Sensitive-media default (v3, APP-018 privacy)

    @Test
    fun sensitiveMediaDecodesCanonicalizesAndClearsWithCache() {
        assertEquals(SensitiveMediaSetting.COVER, SettingsCodec.decode(emptyMap()).sensitiveMedia)
        assertEquals(
            SensitiveMediaSetting.SHOW,
            SettingsCodec.decode(mapOf(SettingsContract.KEY_SENSITIVE_MEDIA to "show")).sensitiveMedia,
        )
        // Corrupt values self-heal to cover.
        assertEquals(
            SensitiveMediaSetting.COVER,
            SettingsCodec.decode(mapOf(SettingsContract.KEY_SENSITIVE_MEDIA to "whatever")).sensitiveMedia,
        )
        assertEquals("show", SettingsRules.normalize(SettingsContract.KEY_SENSITIVE_MEDIA, "show"))
        assertEquals("cover", SettingsRules.normalize(SettingsContract.KEY_SENSITIVE_MEDIA, "garbage"))
        // Content preference: NOT protected from clear-cache.
        assertFalse(SettingsContract.KEY_SENSITIVE_MEDIA in SettingsContract.CLEAR_CACHE_PROTECTED_KEYS)
    }

    // MARK: - Bitz surface keys (v4, APP-007)

    @Test
    fun bitzModeAndVideoMuteDecodeNormalizeAndRoundTrip() {
        // Defaults: player surface, politely muted autoplay (legacy web parity).
        val defaults = SettingsCodec.decode(emptyMap())
        assertEquals(BitzModeSetting.FOR_YOU, defaults.bitzMode)
        assertTrue(defaults.videoMuted)
        // Decode every mode + unmuted wire value.
        assertEquals(
            BitzModeSetting.EXPLORE,
            SettingsCodec.decode(mapOf(SettingsContract.KEY_BITZ_MODE to "explore")).bitzMode,
        )
        assertEquals(
            BitzModeSetting.FOLLOWING,
            SettingsCodec.decode(mapOf(SettingsContract.KEY_BITZ_MODE to "following")).bitzMode,
        )
        // Removed W2 tabs parse back to the default (legacy Flutter parity: 3 tabs).
        assertEquals(
            BitzModeSetting.DEFAULT,
            SettingsCodec.decode(mapOf(SettingsContract.KEY_BITZ_MODE to "trending")).bitzMode,
        )
        assertEquals(
            BitzModeSetting.DEFAULT,
            SettingsCodec.decode(mapOf(SettingsContract.KEY_BITZ_MODE to "zapped")).bitzMode,
        )
        assertFalse(
            SettingsCodec.decode(mapOf(SettingsContract.KEY_VIDEO_MUTED to "0")).videoMuted,
        )
        // Corrupt values self-heal to defaults.
        assertEquals(
            BitzModeSetting.DEFAULT,
            SettingsCodec.decode(mapOf(SettingsContract.KEY_BITZ_MODE to "reels")).bitzMode,
        )
        assertTrue(SettingsCodec.decode(mapOf(SettingsContract.KEY_VIDEO_MUTED to "loud")).videoMuted)
        // Normalize: enums canonicalize (invalid → default), mute is boolean.
        assertEquals("explore", SettingsRules.normalize(SettingsContract.KEY_BITZ_MODE, "explore"))
        assertEquals("for_you", SettingsRules.normalize(SettingsContract.KEY_BITZ_MODE, "trending"))
        assertEquals("for_you", SettingsRules.normalize(SettingsContract.KEY_BITZ_MODE, "zapped"))
        assertEquals("for_you", SettingsRules.normalize(SettingsContract.KEY_BITZ_MODE, "garbage"))
        assertEquals("1", SettingsRules.normalize(SettingsContract.KEY_VIDEO_MUTED, "true"))
        assertEquals("0", SettingsRules.normalize(SettingsContract.KEY_VIDEO_MUTED, "0"))
        assertNull(SettingsRules.normalize(SettingsContract.KEY_VIDEO_MUTED, "maybe"))
        // Both keys round-trip and clear with cache (content prefs, not device globals).
        val snapshot = SettingsCodec.decode(
            mapOf(
                SettingsContract.KEY_BITZ_MODE to "following",
                SettingsContract.KEY_VIDEO_MUTED to "0",
            ),
        )
        for (key in listOf(SettingsContract.KEY_BITZ_MODE, SettingsContract.KEY_VIDEO_MUTED)) {
            val wire = SettingsCodec.encode(key, snapshot)
            assertTrue(wire != null, "encode($key) must produce a value")
            assertEquals(wire, SettingsCodec.encode(key, SettingsCodec.decode(mapOf(key to wire!!))))
        }
        assertFalse(SettingsContract.KEY_BITZ_MODE in SettingsContract.CLEAR_CACHE_PROTECTED_KEYS)
        assertFalse(SettingsContract.KEY_VIDEO_MUTED in SettingsContract.CLEAR_CACHE_PROTECTED_KEYS)
    }
}
