package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feedback closure (plan MSU-042..043): progress surfaces are determinate
 * only where the pipeline reports real counts, and every destructive
 * surface is classified into confirm-dialog vs Undo-notice.
 */
class StudioProgressTest {

    private val progress = StudioProgress

    @Test
    fun everySurfaceHasDistinctCopy() {
        val ids = progress.ALL.map { it.id }
        val titles = progress.ALL.map { it.title }
        assertEquals(ids.size, ids.toSet().size, "duplicate surface id: $ids")
        assertEquals(titles.size, titles.toSet().size, "duplicate surface title: $titles")
        assertTrue(progress.ALL.all { it.title.isNotBlank() && it.body.isNotBlank() })
    }

    @Test
    fun unknownSurfaceDegradesToExport() {
        assertEquals(progress.EXPORT_RENDER, progress.surfaceFor("nope"))
        assertEquals(progress.EXPORT_RENDER, progress.surfaceFor(null))
        assertEquals(progress.GIF_LADDER, progress.surfaceFor("gif-ladder"))
    }

    @Test
    fun indeterminateSurfacesNeverReportAFraction() {
        // A simulated bar is a defect: the render surface has no real
        // fraction, so it must return null even with counts.
        assertNull(progress.fraction(progress.EXPORT_RENDER, done = 3, total = 9))
        assertNull(progress.fraction(progress.EXPORT_RENDER, done = 0, total = 0))
    }

    @Test
    fun determinateFractionsClamp() {
        assertEquals(0.5f, progress.fraction(progress.IMPORT_CLIPS, done = 1, total = 2))
        // Hostile counts clamp into 0..1 and never divide by zero.
        assertEquals(1f, progress.fraction(progress.IMPORT_CLIPS, done = 9, total = 2))
        assertEquals(0f, progress.fraction(progress.IMPORT_CLIPS, done = -3, total = 2))
        assertEquals(0f, progress.fraction(progress.IMPORT_CLIPS, done = 0, total = 0))
    }

    @Test
    fun confirmsAreClassifiedAndUnique() {
        val ids = progress.CONFIRMS.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate confirm id: $ids")
        assertTrue(progress.CONFIRMS.all { it.title.isNotBlank() })
    }

    @Test
    fun reversibleActionsUseUndoNotADialog() {
        // MSU-043: deletes are reversible — dialog would be friction.
        listOf("delete-overlay", "delete-clip", "delete-layer").forEach { id ->
            assertTrue(progress.requiresUndoNotice(id), "$id must use the Undo notice")
            assertFalse(progress.requiresDialog(id), "$id must NOT show a dialog")
        }
    }

    @Test
    fun irreversibleActionsMustConfirm() {
        // Media is not recoverable from the project wire — these confirm.
        listOf("mode-switch", "discard-draft", "discard-takes").forEach { id ->
            assertTrue(progress.requiresDialog(id), "$id must confirm")
            assertFalse(progress.requiresUndoNotice(id), "$id must NOT rely on undo")
        }
    }

    @Test
    fun unknownConfirmDefaultsToTheSaferDialog() {
        // Fail safe: an unaudited surface must not silently become a
        // no-dialog action.
        assertEquals(
            StudioProgress.ConfirmMode.MODE,
            progress.confirmFor("brand-new").mode,
        )
        assertTrue(progress.requiresDialog("brand-new"))
    }
}
