package space.bitos.core.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Design-token contract tests (APP-022/023). Every appearance mode must
 * ship a *complete* palette whose text roles actually meet the §2.6
 * accessibility floors — this is what lets native layers resolve tokens
 * blindly instead of re-deriving contrast per screen.
 */
class DesignTokensTest {

    private val modes = DesignTokens.Mode.entries.toList()

    // ── Contrast floors (WCAG 2.1) ──────────────────────────────────────

    @Test
    fun primaryAndSecondaryTextMeetAAOnBackgroundAndSurface() {
        for (mode in modes) {
            val p = DesignTokens.resolve(mode)
            for (surface in listOf(p.background, p.surface)) {
                assertTrue(
                    DesignTokens.meetsAA(p.textPrimary, surface),
                    "textPrimary fails AA on ${surface.toString(16)} in $mode",
                )
                assertTrue(
                    DesignTokens.meetsAA(p.textSecondary, surface),
                    "textSecondary fails AA on ${surface.toString(16)} in $mode",
                )
            }
        }
    }

    @Test
    fun tertiaryTextMeetsTheLargeTextFloor() {
        for (mode in modes) {
            val p = DesignTokens.resolve(mode)
            assertTrue(
                DesignTokens.meetsAA(p.textTertiary, p.surface, largeText = true),
                "textTertiary fails 3:1 on surface in $mode",
            )
        }
    }

    @Test
    fun linkAndSemanticTextRolesMeetAAOnSurface() {
        for (mode in modes) {
            val p = DesignTokens.resolve(mode)
            for (role in listOf(p.textLink, p.errorText, p.successText, p.warningText, p.infoText, p.accentText)) {
                assertTrue(
                    DesignTokens.meetsAA(role, p.surface),
                    "text role ${role.toString(16)} fails AA on surface in $mode",
                )
                assertTrue(
                    DesignTokens.meetsAA(role, p.background),
                    "text role ${role.toString(16)} fails AA on background in $mode",
                )
            }
        }
    }

    @Test
    fun primaryButtonsKeepReadableLabelsInBothModes() {
        for (mode in modes) {
            val p = DesignTokens.resolve(mode)
            assertTrue(
                DesignTokens.meetsAA(p.onPrimary, p.primary),
                "onPrimary label fails AA on the brand fill in $mode",
            )
        }
    }

    @Test
    fun socialActionColorsStayVisibleOnSurfaceAsIcons() {
        // Non-text UI needs 3:1 (WCAG 1.4.11): action icons/counts over cards.
        for (mode in modes) {
            val p = DesignTokens.resolve(mode)
            for (role in listOf(p.like, p.repost, p.zap, p.reply, p.bookmark)) {
                assertTrue(
                    DesignTokens.contrastRatio(role, p.surface) >= 3.0,
                    "action color ${role.toString(16)} drops below 3:1 on surface in $mode",
                )
            }
        }
    }

    @Test
    fun secondaryTextStaysReadableOnElevatedSurfaces() {
        for (mode in modes) {
            val p = DesignTokens.resolve(mode)
            assertTrue(DesignTokens.meetsAA(p.textSecondary, p.surfaceElevated), "elevated in $mode")
            assertTrue(DesignTokens.meetsAA(p.textSecondary, p.surfaceOverlay), "overlay in $mode")
        }
    }

    // ── Engine sanity ───────────────────────────────────────────────────

    @Test
    fun contrastEngineMatchesWcagReferenceValues() {
        // Black on white = 21:1; a color against itself = 1:1.
        assertEquals(21.0, DesignTokens.contrastRatio(0xFFFFFFFF, 0xFF000000), 0.01)
        assertEquals(1.0, DesignTokens.contrastRatio(0xFFABCDEF, 0xFFABCDEF), 0.0001)
        // Brand orange label on its near-black fill (checked by hand): ~8.6:1.
        assertTrue(DesignTokens.contrastRatio(0xFFF7931A, 0xFF0A0A0F) > 8.0)
    }

    // ── Completeness / parity ───────────────────────────────────────────

    @Test
    fun bothModesResolveDistinctCompletePalettes() {
        val dark = DesignTokens.resolve(DesignTokens.Mode.DARK)
        val light = DesignTokens.resolve(DesignTokens.Mode.LIGHT)
        // The brand fill is mode-independent; surfaces invert.
        assertEquals(dark.primary, light.primary, "brand primary must not change per mode")
        assertTrue(dark.background != light.background)
        // Every token of both modes must be a fully-opaque ARGB value, so
        // native Color(value) adapters never render accidental transparency.
        for (palette in listOf(dark, light)) {
            for (value in palette.values()) {
                assertEquals(
                    0xFF000000L,
                    value and 0xFF000000L,
                    "token ${value.toString(16)} is not opaque ARGB",
                )
            }
        }
    }

    @Test
    fun darkModeIsTheProductDefaultAndMatchesLegacySurfaces() {
        // Ported 1:1 from the Flutter/web tokens — a regression here changes
        // every shipped dark screen, so pin the exact values.
        val dark = DesignTokens.DARK
        assertEquals(0xFF0A0A0F, dark.background)
        assertEquals(0xFF12121A, dark.surface)
        assertEquals(0xFF1A1A26, dark.surfaceElevated)
        assertEquals(0xFF22222E, dark.surfaceOverlay)
        assertEquals(0xFFF7931A, dark.primary)
        assertEquals(0xFFF8F8FF, dark.textPrimary)
        assertEquals(0xFF9CA3AF, dark.textSecondary)
        assertEquals(0xFF2A2A3A, dark.border)
        assertEquals(0xFF1F1F2E, dark.divider)
        assertEquals(0xFFEC4899, dark.like)
        assertEquals(0xFF10B981, dark.repost)
        assertEquals(0xFFF59E0B, dark.zap)
        assertEquals(0xFF3B82F6, dark.reply)
    }

    @Test
    fun typeScaleStaysOnTheContractedGrid() {
        // §2.2 invariants: display is the largest, mono carries tracking,
        // body line-height is 1.5, labels never exceed body size.
        val t = DesignTokens.Type
        assertTrue(t.displayLarge.size > t.headlineLarge.size)
        assertTrue(t.headlineLarge.size > t.headlineMedium.size)
        assertTrue(t.headlineMedium.size > t.headlineSmall.size)
        assertEquals(1.5, t.bodyLarge.lineHeight)
        assertEquals(1.5, t.bodyMedium.lineHeight)
        assertTrue(t.mono.letterSpacing > 0)
        assertTrue(t.labelLarge.size <= t.bodyLarge.size)
        assertTrue(t.labelSmall.size < t.labelMedium.size)
        // Weights stay in the valid OpenType range.
        for (role in listOf(t.displayLarge, t.headlineLarge, t.headlineMedium, t.headlineSmall,
                t.bodyLarge, t.bodyMedium, t.bodySmall, t.labelLarge, t.labelMedium,
                t.labelSmall, t.menuItem, t.mono)) {
            assertTrue(role.weight in 100..900, "weight ${role.weight} out of range")
            assertTrue(role.lineHeight >= 1.0)
        }
    }

    @Test
    fun spacingStaysOnTheFourPointGrid() {
        val s = DesignTokens.Spacing
        for (v in listOf(s.XS, s.SM, s.MD, s.BASE, s.LG, s.XL, s.XXL, s.XXXL,
                s.SCREEN, s.CARD, s.SECTION_GAP, s.LIST_ITEM, s.ICON_TEXT, s.AVATAR_GAP)) {
            assertEquals(0, v % 4, "spacing $v is off the 4-pt grid")
        }
    }

    @Test
    fun hexGeometryMatchesTheWebClipPolygon() {
        // The 6.7% inset is what makes it a *regular* flat-top hexagon.
        assertEquals(0.25, DesignTokens.HEX_VERTEX_X_INSET, 1e-9)
        assertEquals(0.067, DesignTokens.HEX_VERTEX_Y_INSET, 1e-9)
    }

    @Test
    fun touchTargetsAndAvatarLadderAreContracted() {
        assertEquals(44, DesignTokens.MIN_TOUCH_TARGET_PT)
        assertEquals(48, DesignTokens.MIN_TOUCH_TARGET_DP)
        val a = DesignTokens.AvatarSize
        assertTrue(a.XS < a.SM && a.SM < a.MD && a.MD < a.LG && a.LG < a.XL && a.XL < a.XXL)
        // Motion ladder from §2.4.
        val d = DesignTokens.Duration
        assertTrue(d.INSTANT < d.FAST && d.FAST < d.NORMAL && d.NORMAL < d.SLOW && d.SLOW < d.EMPHASIS)
    }
}
