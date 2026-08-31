package space.bitos.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * APP-023 appearance adapter: the accent derivation that layers the user's
 * persisted `#RRGGBB` choice over the brand palette. Same rules as the
 * shared settings contract — malformed values fall back, never crash.
 */
class ThemeAccentTest {

    @Test
    fun parsesCanonicalHex() {
        assertEquals(0xFFF7931AL, parseAccentArgb("#F7931A"))
        assertEquals(0xFF6366F1L, parseAccentArgb("#6366f1"))
    }

    @Test
    fun rejectsMalformedValues() {
        assertNull(parseAccentArgb(null))
        assertNull(parseAccentArgb(""))
        assertNull(parseAccentArgb("F7931A")) // missing '#'
        assertNull(parseAccentArgb("#F7931")) // too short
        assertNull(parseAccentArgb("#F7931AA")) // too long
        assertNull(parseAccentArgb("#ZZZZZZ")) // not hex
    }

    @Test
    fun mixesThirtyPercentTowardWhiteOrBlack() {
        // 0x80 = 128; 128 ± 38 (30% of 127) → 0xA6 light / 0x5A dark.
        assertEquals(0xFFA6A6A6L, mixToward(0xFF808080L, towardWhite = true))
        assertEquals(0xFF5A5A5AL, mixToward(0xFF808080L, towardWhite = false))
    }

    @Test
    fun accentOverridesBrandPrimaryAndKeepsVariantsOrdered() {
        val themed = applyAccent(BitOSDarkColors, "#6366F1")
        assertEquals(Color(0xFF6366F1), themed.primary)
        // Variants bracket the base: lightened above, darkened below.
        val light = (themed.primaryLight.toArgb() shr 16) and 0xFF
        val mid = 0x63
        val dark = (themed.primaryDark.toArgb() shr 16) and 0xFF
        assertTrue(light > mid, "primaryLight must lighten (got $light vs $mid)")
        assertTrue(dark < mid, "primaryDark must darken (got $dark vs $mid)")
        // Non-brand roles are untouched.
        assertEquals(BitOSDarkColors.background, themed.background)
        assertEquals(BitOSDarkColors.textPrimary, themed.textPrimary)
    }

    @Test
    fun malformedAccentFallsBackToTheBasePalette() {
        val base = BitOSDarkColors
        assertSame(base, applyAccent(base, "not-a-color"))
        assertSame(base, applyAccent(base, null))
    }

    @Test
    fun defaultAccentRoundTripsToTheBrandOrange() {
        // The contract default (#F7931A) equals the shipped brand primary in
        // both palettes, so a fresh install renders unchanged.
        assertEquals(
            Color(0xFFF7931A),
            applyAccent(BitOSDarkColors, "#F7931A").primary,
        )
        assertEquals(
            Color(0xFFF7931A),
            applyAccent(BitOSLightColors, "#F7931A").primary,
        )
    }
}
