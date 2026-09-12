package space.bitos.core.studio

import space.bitos.core.studio.EditorSurfaces.Kind
import space.bitos.core.studio.EditorSurfaces.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Surface state machine (plan MSU-003). The contract these tests pin is the
 * repo's documented rule: **Back dismisses the sheet, not the editor** —
 * and the editor exits only when nothing was open.
 */
class EditorSurfacesTest {

    private val surfaces = EditorSurfaces

    @Test
    fun backFromNothingExitsTheEditor() {
        assertNull(surfaces.back(State.Empty))
        assertNull(surfaces.back(State()))
    }

    @Test
    fun backClosesASheetBeforeExiting() {
        val state = surfaces.openSheet(State.Empty, "look")
        val next = surfaces.back(state)
        assertNotNull(next)
        assertEquals(Kind.NONE, next.top.kind)
        // A second back now exits.
        assertNull(surfaces.back(next))
    }

    @Test
    fun backUnwindsSheetThenOverlayThenExits() {
        // Compose (overlay) with a sheet stacked above it.
        val composing = surfaces.openCompose(State.Empty, "o1")
        val stacked = surfaces.openSheet(composing, "export")
        assertEquals(Kind.SHEET, stacked.top.kind)

        val afterSheet = surfaces.back(stacked)
        assertNotNull(afterSheet)
        // The overlay (compose) is restored intact — the sheet did not
        // replace it, and the overlay id survives.
        assertEquals(Kind.COMPOSE, afterSheet.top.kind)
        assertEquals("o1", afterSheet.top.overlayId)

        val afterOverlay = surfaces.back(afterSheet)
        assertNotNull(afterOverlay)
        assertEquals(Kind.NONE, afterOverlay.top.kind)

        assertNull(surfaces.back(afterOverlay))
    }

    @Test
    fun openingASheetDoesNotDiscardTheComposeOverlay() {
        val composing = surfaces.openCompose(State.Empty, "o7")
        val withSheet = surfaces.openSheet(composing, "sound")
        assertEquals("o7", withSheet.overlay?.overlayId)
        assertEquals("sound", withSheet.sheet?.id)
    }

    @Test
    fun enteringComposeDismissesAnyOpenSheet() {
        val withSheet = surfaces.openSheet(State.Empty, "media")
        val composing = surfaces.openCompose(withSheet, "o2")
        assertNull(composing.sheet)
        assertEquals(Kind.COMPOSE, composing.top.kind)
    }

    @Test
    fun timelineAndReviewAreReachableAndExitable() {
        val timeline = surfaces.openTimeline(State.Empty)
        assertEquals("timeline", surfaces.tokenOf(timeline))
        assertEquals(Kind.NONE, surfaces.back(timeline)?.top?.kind)

        val review = surfaces.openReview(State.Empty)
        assertEquals("review", surfaces.tokenOf(review))
        assertNull(surfaces.back(surfaces.back(review)!!))
    }

    @Test
    fun tokensAreStableForBackHandlerKeys() {
        assertEquals("none", surfaces.tokenOf(State.Empty))
        assertEquals("sheet:look", surfaces.tokenOf(surfaces.openSheet(State.Empty, "look")))
        assertEquals("panel:pen", surfaces.tokenOf(surfaces.openInlinePanel(State.Empty, "pen")))
        assertEquals("compose:o3", surfaces.tokenOf(surfaces.openCompose(State.Empty, "o3")))
        assertEquals("timeline", surfaces.tokenOf(surfaces.openTimeline(State.Empty)))
        assertEquals("review", surfaces.tokenOf(surfaces.openReview(State.Empty)))
    }

    @Test
    fun openSurfaceReportingDrivesTheExitDecision() {
        assertFalse(surfaces.hasOpenSurface(State.Empty))
        assertTrue(surfaces.hasOpenSurface(surfaces.openSheet(State.Empty, "export")))
        assertTrue(surfaces.hasOpenSurface(surfaces.openCompose(State.Empty, "o1")))
    }

    @Test
    fun inlinePanelDoesNotStackUnderASheetAsTheTopSurface() {
        val panel = surfaces.openInlinePanel(State.Empty, "pen")
        val withSheet = surfaces.openSheet(panel, "look")
        // The sheet is on top; back lands on the inline panel.
        assertEquals(Kind.SHEET, withSheet.top.kind)
        assertEquals(Kind.INLINE_PANEL, surfaces.back(withSheet)?.top?.kind)
    }
}
