package space.bitos.app.ui.create.meme

import space.bitos.core.studio.MemeFontSlot
import space.bitos.core.studio.MemeOverlayKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Editing-core contract for the Quick MEM editor (plan MST-010..015,
 * EDT-004 undo semantics): gestures become ONE undo step, style bursts
 * coalesce, undo restores snapshots, and the shared rules stay the single
 * source of clamping, caps and hit-testing.
 */
class MemeEditorStateTest {

    private class Clock {
        var now = 0L
        fun state(): MemeEditorState = MemeEditorState(clockMs = { now })
    }

    @Test
    fun assetsCapAtTheImageModeBoundAndDedup() {
        val state = MemeEditorState()
        val added = state.addAssets((1..12).map { "a$it" })
        assertEquals(9, added.size, "ComposerRules.MAX_IMAGES parity cap")
        assertEquals(0, state.addAssets(listOf("a1", "a10")).size, "dedup + full cap")
        assertEquals(9, state.project.assets.size)
        // Blank ids never enter the project wire.
        assertEquals(0, MemeEditorState().addAssets(listOf("", " ")).size)
    }

    @Test
    fun addOverlaySelectsItAndUndoRestores() {
        val state = MemeEditorState()
        val id = state.addOverlay(MemeOverlayKind.TEXT, "gm")
        assertTrue(id != null)
        assertEquals(id, state.selectedOverlayId)
        assertEquals(1, state.project.overlays.size)
        assertTrue(state.undo())
        assertEquals(0, state.project.overlays.size)
        assertNull(state.selectedOverlayId, "undo clears a selection it removed")
        assertFalse(state.canUndo)
    }

    @Test
    fun aDragStreamIsASingleUndoStep() {
        val state = MemeEditorState()
        val id = state.addOverlay(MemeOverlayKind.TEXT, "drag me")
        state.clearSelection()

        // Tap selects via the shared hit-test at the placed point.
        val placed = state.project.overlays.single()
        assertTrue(state.selectAt(placed.x, placed.y))
        assertEquals(id, state.selectedOverlayId)

        state.beginGesture()
        repeat(20) { frame -> state.gestureUpdate(x = 0.1f + frame * 0.01f, y = 0.5f) }
        assertTrue(state.gestureActive)
        state.endGesture()
        assertFalse(state.gestureActive)
        assertEquals(0.29f, state.project.overlays.single().x, 1e-4f)

        // ONE undo returns to the pre-drag geometry.
        assertTrue(state.undo())
        assertEquals(1, state.project.overlays.size)
        assertNotEquals(0.29f, state.project.overlays.single().x)
        assertEquals(0.42f, state.project.overlays.single().x, 1e-3f, "overlay #1 staggers to 0.5 − 0.08")

        // A gesture with no movement (a tap) never pushes an undo entry.
        val depthAfterDrag = state.undoDepth
        state.beginGesture()
        state.endGesture()
        assertEquals(depthAfterDrag, state.undoDepth)
    }

    @Test
    fun styleBurstsCoalesceWithinTheWindow() {
        val clock = Clock()
        val state = clock.state()
        val id = state.addOverlay(MemeOverlayKind.TEXT, "gm")
        clock.now = 1_000L

        state.updateStyle(id!!, font = MemeFontSlot.SERIF)
        state.updateStyle(id, colorIndex = 3)
        state.updateStyle(id, shadow = true)
        assertEquals(2, state.undoDepth, "add + ONE merged style step")

        // The merged step restores the whole burst at once (pre-burst style).
        assertTrue(state.undo())
        val merged = state.project.overlays.single()
        assertEquals(MemeFontSlot.IMPACT, merged.font)
        assertEquals(0, merged.colorIndex)
        assertFalse(merged.shadow)
        assertTrue(state.canUndo, "the add step remains after undoing the burst")
    }

    @Test
    fun coalescingWindowExpiresAndGesturesNeverCoalesce() {
        val clock = Clock()
        val state = clock.state()
        val id = state.addOverlay(MemeOverlayKind.TEXT, "gm")
        clock.now = 1_000L
        state.updateStyle(id!!, font = MemeFontSlot.MONO)
        clock.now = 2_000L // outside the 300 ms window
        state.updateStyle(id, colorIndex = 5)
        assertEquals(3, state.undoDepth, "expired window = separate steps")

        // Gesture entries do not merge with adjacent style steps.
        state.beginGesture()
        state.gestureUpdate(x = 0.8f)
        state.endGesture()
        clock.now += 1L
        state.updateStyle(id, shadow = true)
        assertTrue(state.undo())
        assertFalse(state.project.overlays.single().shadow)
        assertEquals(0.8f, state.project.overlays.single().x, 1e-4f, "undo stopped at the gesture step")
    }

    @Test
    fun cancelGestureRestoresThePreDragProject() {
        val state = MemeEditorState()
        val id = state.addOverlay(MemeOverlayKind.TEXT, "gm")!!
        state.beginGesture()
        state.gestureUpdate(x = 0.95f, scale = 4f)
        state.cancelGesture()
        assertEquals(0.42f, state.project.overlays.single().x, 1e-3f, "pre-gesture placement restored")
        assertEquals(1f, state.project.overlays.single().scale)
        assertEquals(1, state.undoDepth, "a cancelled gesture adds no history")
        assertEquals(id, state.selectedOverlayId, "selection survives the cancelled gesture")
        assertTrue(state.undo())
        assertEquals(0, state.project.overlays.size, "prior history still intact")
    }

    @Test
    fun removeOverlayUsesSharedCapAndUndo() {
        val state = MemeEditorState()
        repeat(48) { state.addOverlay(MemeOverlayKind.STICKER, "⚡") }
        assertNull(state.addOverlay(MemeOverlayKind.TEXT, "over"), "48-overlay cap (MemeProjectContract)")
        val first = state.project.overlays.first().id
        state.removeOverlay(first)
        assertFalse(state.project.overlays.any { it.id == first })
        assertTrue(state.undo())
        assertTrue(state.project.overlays.any { it.id == first })
        // Removing a ghost id is a no-op (no new history).
        val depthBeforeGhost = state.undoDepth
        state.removeOverlay("ghost")
        assertEquals(depthBeforeGhost, state.undoDepth)
    }

    @Test
    fun undoStackIsBounded() {
        val state = MemeEditorState()
        val id = state.addOverlay(MemeOverlayKind.TEXT, "gm")!!
        repeat(MemeEditorState.MAX_UNDO_ENTRIES + 10) {
            state.updateStyle(id, shadow = it % 2 == 0, colorIndex = it % 16)
        }
        var undos = 0
        while (state.undo()) undos += 1
        assertTrue(undos <= MemeEditorState.MAX_UNDO_ENTRIES + 1, "bounded at $undos entries + the add")
    }

    @Test
    fun redoReappliesUndoneStatesAndAnyEditClearsTheBranch() {
        val state = MemeEditorState()
        val id = state.addOverlay(MemeOverlayKind.TEXT, "gm")!!
        state.updateStyle(id, size = 200, colorIndex = 3)
        val styled = state.project.overlays.single()

        assertFalse(state.canRedo, "nothing to redo before an undo")
        assertTrue(state.undo())
        assertTrue(state.canRedo)
        assertEquals(48, state.project.overlays.single().size, "undo landed on the add step (default placement)")
        assertEquals(0.35f, state.project.overlays.single().y, 1e-3f)
        assertTrue(state.redo())
        assertEquals(styled, state.project.overlays.single(), "redo restored the post-edit state exactly")
        assertFalse(state.canRedo, "redo branch consumed")

        // Undo again, then a fresh edit clears the redo branch.
        assertTrue(state.undo())
        state.updateStyle(id, shadow = true)
        assertFalse(state.canRedo, "a new edit clears the redo branch")
        assertTrue(state.project.overlays.single().shadow)
    }

    @Test
    fun imageLayersAreOrdinaryOverlaysBoundToAnAssetId() {
        val state = MemeEditorState()
        state.switchMode(space.bitos.core.studio.MemeMode.VIDEO)
        val added = state.addAssets(listOf("v1"))
        assertEquals(listOf("v1"), added)
        // The real insert flow: register the source asset, then bind a layer.
        assertEquals(listOf("a1"), state.addAssets(listOf("a1"), kind = space.bitos.core.studio.MemeMode.IMAGE))
        val layerId = state.addImageOverlay("a1")!!
        val layer = state.project.overlays.single()
        assertEquals(space.bitos.core.studio.MemeOverlayKind.IMAGE, layer.kind)
        assertEquals("a1", layer.assetId)
        assertEquals(space.bitos.core.studio.MemeRules.DEFAULT_IMAGE_SIZE, layer.size)
        assertEquals(layerId, state.selectedOverlayId, "the new layer is selected")
        assertTrue(state.undo())
        assertEquals(0, state.project.overlays.size, "image layers undo like any overlay")
        // Video mode holds ≤8 clips + ≤6 image sources (a1 already lives
        // here as the layer's asset — it dedupes).
        val layered = state.addAssets((1..16).map { "a$it" })
        assertEquals(
            space.bitos.core.studio.MemeProjectContract
                .maxAssets(space.bitos.core.studio.MemeMode.VIDEO) - 2,
            layered.size,
        )
    }
}
