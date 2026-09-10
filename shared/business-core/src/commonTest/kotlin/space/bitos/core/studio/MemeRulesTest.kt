package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Meme editor rules (plan MST-002): overlay CRUD, hit-test order, default
 * placement and the undo command model with drag coalescing (EDT-004).
 */
class MemeRulesTest {

    private val empty = MemeProject(mode = MemeMode.IMAGE)

    private fun textOverlay(id: String, x: Float = 0.5f, y: Float = 0.5f) = MemeOverlay(
        id = id, kind = MemeOverlayKind.TEXT, text = "gm",
        font = MemeFontSlot.IMPACT, size = 48, colorIndex = 0,
        outline = 2, shadow = false, x = x, y = y,
        scale = 1f, rotationDeg = 0f,
    )

    @Test
    fun paletteIsBoundedAndIndexZeroIsWhite() {
        assertEquals(16, MemeRules.PALETTE.size)
        assertEquals(0xFFFFFFFF, MemeRules.PALETTE[0])
    }

    @Test
    fun defaultOverlaysStaggerAndIdsStayUnique() {
        var project = empty
        repeat(5) {
            project = MemeRules.apply(project, MemeCommand.AddOverlay(MemeRules.defaultOverlay(project, MemeOverlayKind.TEXT, "gm")))
        }
        assertEquals(5, project.overlays.size)
        assertEquals(project.overlays.map { it.id }.distinct(), project.overlays.map { it.id })
        assertNotEquals(project.overlays[0].y, project.overlays[4].y)
    }

    @Test
    fun imageLayersDefaultToTheirOwnSizeAndHitSquare() {
        val base = MemeRules.defaultOverlay(empty, MemeOverlayKind.IMAGE, "")
        assertEquals(MemeRules.DEFAULT_IMAGE_SIZE, base.size)
        assertEquals(0, base.outline, "image layers carry no text outline")
        val layer = base.copy(assetId = "a1")
        val (width, height) = MemeRules.estimateBounds(layer)
        assertEquals(width, height, 0.0001f, "square hit box like stickers")
        val project = MemeRules.apply(empty, MemeCommand.AddOverlay(layer))
        assertEquals(layer, project.overlays.single(), "image overlays are ordinary overlays")
        assertEquals(layer, MemeRules.hitTest(project, layer.x, layer.y))
    }

    @Test
    fun addCapsAtMaxOverlays() {
        var project = empty
        repeat(MemeProjectContract.MAX_OVERLAYS + 5) { index ->
            project = MemeRules.apply(project, MemeCommand.AddOverlay(textOverlay("o$index")))
        }
        assertEquals(MemeProjectContract.MAX_OVERLAYS, project.overlays.size)
    }

    @Test
    fun updateAppliesOnlyProvidedFieldsAndClamps() {
        val project = MemeRules.apply(empty, MemeCommand.AddOverlay(textOverlay("o1")))
        val moved = MemeRules.apply(
            project,
            MemeCommand.UpdateOverlay(id = "o1", x = 9f, rotationDeg = 999f, colorIndex = -4),
        )
        val overlay = moved.overlays.single()
        assertEquals(1f, overlay.x)
        assertEquals(-81f, overlay.rotationDeg)
        assertEquals(0, overlay.colorIndex)
        assertEquals("gm", overlay.text)
    }

    @Test
    fun updateClampsStyleSizeBothWays() {
        val project = MemeRules.apply(empty, MemeCommand.AddOverlay(textOverlay("o1")))
        val sized = MemeRules.apply(
            project,
            MemeCommand.UpdateOverlay(id = "o1", size = 9_999),
        )
        assertEquals(MemeRules.MAX_SIZE, sized.overlays.single().size)
        val shrunk = MemeRules.apply(sized, MemeCommand.UpdateOverlay(id = "o1", size = 1))
        assertEquals(MemeRules.MIN_SIZE, shrunk.overlays.single().size)
        // The wire clamps identically (hostile sheet input can't escape).
        val json = MemeCommandCodec.encode(MemeCommand.UpdateOverlay(id = "o1", size = 500))
        val decoded = MemeCommandCodec.decode(json)
        assertTrue(decoded is MemeCommand.UpdateOverlay && decoded.size == 500)
    }

    @Test
    fun updateBarAppliesAndRoundTripsTheCodec() {
        val project = MemeRules.apply(empty, MemeCommand.AddOverlay(textOverlay("o1")))
        val on = MemeRules.apply(project, MemeCommand.UpdateOverlay(id = "o1", bar = true))
        assertEquals(true, on.overlays.single().bar)
        val decoded = MemeCommandCodec.decode(
            MemeCommandCodec.encode(MemeCommand.UpdateOverlay(id = "o1", bar = false)),
        )
        assertTrue(decoded is MemeCommand.UpdateOverlay && decoded.bar == false)
        val off = MemeRules.apply(on, decoded as MemeCommand.UpdateOverlay)
        assertEquals(false, off.overlays.single().bar)
    }

    @Test
    fun coalesceMergesBarWithEarlierStyleFields() {
        val earlier = MemeCommand.UpdateOverlay(id = "o1", shadow = true)
        val later = MemeCommand.UpdateOverlay(id = "o1", bar = true)
        val merged = MemeRules.coalesce(earlier, later) as MemeCommand.UpdateOverlay
        assertEquals(true, merged.shadow)
        assertEquals(true, merged.bar)
    }

    @Test
    fun nativeTextOverlaysDefaultCapsOffForWysiwyg() {
        val overlay = MemeRules.defaultOverlay(empty, MemeOverlayKind.TEXT, "gm")
        assertEquals(false, overlay.caps, "previews paint text as typed — export must match")
        assertEquals(false, overlay.bar, "the background bar starts off")
    }

    @Test
    fun removeDropsOnlyTheTarget() {
        val seeded = MemeRules.apply(
            MemeRules.apply(empty, MemeCommand.AddOverlay(textOverlay("o1"))),
            MemeCommand.AddOverlay(textOverlay("o2", y = 0.2f)),
        )
        val removed = MemeRules.apply(seeded, MemeCommand.RemoveOverlay("o1"))
        assertEquals(listOf("o2"), removed.overlays.map { it.id })
    }

    @Test
    fun reorderMovesOverlayThroughThePaintStack() {
        var project = empty
        listOf("o1", "o2", "o3").forEach { id ->
            project = MemeRules.apply(project, MemeCommand.AddOverlay(textOverlay(id)))
        }
        // Bottom layer to the front: paint order o2, o3, o1.
        val front = MemeRules.apply(project, MemeCommand.ReorderOverlay("o1", 2))
        assertEquals(listOf("o2", "o3", "o1"), front.overlays.map { it.id })
        assertEquals("o1", MemeRules.hitTest(front, 0.5f, 0.5f)?.id, "front layer wins hits")
        // Front layer to the back again.
        val back = MemeRules.apply(front, MemeCommand.ReorderOverlay("o1", 0))
        assertEquals(listOf("o1", "o2", "o3"), back.overlays.map { it.id })
        // Out-of-range targets clamp; a same-position or unknown-id move is a no-op.
        assertEquals(listOf("o1", "o3", "o2"), MemeRules.apply(back, MemeCommand.ReorderOverlay("o2", 99)).overlays.map { it.id })
        assertEquals(back, MemeRules.apply(back, MemeCommand.ReorderOverlay("o2", 1)))
        assertEquals(back, MemeRules.apply(back, MemeCommand.ReorderOverlay("nope", 0)))
        assertEquals(empty, MemeRules.apply(empty, MemeCommand.ReorderOverlay("o1", 0)), "empty project stays empty")
        // The command round-trips through the wire codec.
        val encoded = MemeCommandCodec.encode(MemeCommand.ReorderOverlay("o2", 0))
        assertEquals(MemeCommand.ReorderOverlay("o2", 0), MemeCommandCodec.decode(encoded))
    }

    @Test
    fun hitTestPicksTopMost() {
        val project = MemeRules.apply(
            MemeRules.apply(empty, MemeCommand.AddOverlay(textOverlay("back", y = 0.5f))),
            MemeCommand.AddOverlay(textOverlay("front", y = 0.5f)),
        )
        assertEquals("front", MemeRules.hitTest(project, 0.5f, 0.5f)?.id)
        assertNull(MemeRules.hitTest(project, 0.01f, 0.99f))
    }

    @Test
    fun coalesceMergesSameOverlayUpdatesOnly() {
        val first = MemeCommand.UpdateOverlay(id = "o1", x = 0.2f)
        val second = MemeCommand.UpdateOverlay(id = "o1", y = 0.3f)
        assertEquals(
            MemeCommand.UpdateOverlay(id = "o1", x = 0.2f, y = 0.3f),
            MemeRules.coalesce(first, second),
        )
        assertNull(MemeRules.coalesce(first, MemeCommand.RemoveOverlay("o1")))
        assertNull(
            MemeRules.coalesce(
                first,
                MemeCommand.UpdateOverlay(id = "o2", x = 0.4f),
            ),
        )
    }

    @Test
    fun coalescedSequenceBehavesLikeSequentialApplication() {
        val add = MemeCommand.AddOverlay(textOverlay("o1"))
        val drag1 = MemeCommand.UpdateOverlay(id = "o1", x = 0.2f)
        val drag2 = MemeCommand.UpdateOverlay(id = "o1", y = 0.3f)

        val sequential = MemeRules.apply(MemeRules.apply(MemeRules.apply(empty, add), drag1), drag2)
        val merged = MemeRules.apply(
            MemeRules.apply(empty, add),
            MemeRules.coalesce(drag1, drag2)!!,
        )
        assertEquals(sequential, merged)
    }

    @Test
    fun trimCommandKeepsEndAfterStart() {
        val project = MemeRules.apply(empty, MemeCommand.SetTrim(1_000, 500))
        assertEquals(1_000, project.trimStartMs)
        assertEquals(1_000, project.trimEndMs, "end never precedes start")
    }

    @Test
    fun speedCommandClampsIntoTheWebRange() {
        val video = empty.copy(mode = MemeMode.VIDEO)
        assertEquals(2f, MemeRules.apply(video, MemeCommand.SetSpeed(9f)).speed)
        assertEquals(0.5f, MemeRules.apply(video, MemeCommand.SetSpeed(0.1f)).speed)
        assertEquals(1.5f, MemeRules.apply(video, MemeCommand.SetSpeed(1.5f)).speed)
        // The command round-trips through the codec.
        val encoded = space.bitos.core.studio.MemeCommandCodec.encode(MemeCommand.SetSpeed(1.25f))
        val decoded = space.bitos.core.studio.MemeCommandCodec.decode(encoded)
        assertEquals(MemeCommand.SetSpeed(1.25f), decoded)
    }

    @Test
    fun strokesObeyThePointBudgetAndCommandsRoundTrip() {
        val stroke = { id: String, pts: Int ->
            MemeStroke(
                id = id, colorIndex = 2, widthNorm = 0.01f,
                points = List(pts) { 0.5f },
            )
        }
        var project = MemeRules.apply(empty, MemeCommand.AddStroke(stroke("s1", 6)))
        assertEquals(1, project.drawStrokes.size)
        // A degenerate stroke (one point) never lands.
        project = MemeRules.apply(project, MemeCommand.AddStroke(stroke("s2", 2)))
        assertEquals(1, project.drawStrokes.size)
        // Per-stroke cap + project budget: fill strokes evict the OLDEST
        // ink first once the 12,000-point budget bites.
        repeat(8) { index ->
            project = MemeRules.apply(
                project,
                MemeCommand.AddStroke(stroke("f$index", MemeProjectContract.MAX_POINTS_PER_STROKE)),
            )
        }
        assertEquals(8, project.drawStrokes.size)
        assertFalse(project.drawStrokes.any { it.id == "s1" }, "the tiny first stroke dropped when the budget bit")
        project = MemeRules.apply(
            project,
            MemeCommand.AddStroke(stroke("new", MemeProjectContract.MAX_POINTS_PER_STROKE)),
        )
        assertEquals(8, project.drawStrokes.size)
        assertFalse(project.drawStrokes.any { it.id == "f0" }, "the oldest fill stroke dropped")
        assertTrue(project.drawStrokes.any { it.id == "new" })
        // Remove + clear.
        project = MemeRules.apply(project, MemeCommand.AddStroke(stroke("s4", 6)))
        project = MemeRules.apply(project, MemeCommand.RemoveStroke("s4"))
        assertFalse(project.drawStrokes.any { it.id == "s4" })
        project = MemeRules.apply(project, MemeCommand.ClearDrawing())
        assertEquals(0, project.drawStrokes.size)
        // The add command round-trips through the codec.
        val encoded = MemeCommandCodec.encode(MemeCommand.AddStroke(stroke("s9", 8)))
        assertEquals(MemeCommand.AddStroke(stroke("s9", 8)), MemeCommandCodec.decode(encoded))
    }

    @Test
    fun templatePackAppliesAsFreshIdClone() {
        assertTrue(space.bitos.core.studio.MemeTemplates.PACK.size in 1..space.bitos.core.studio.MemeTemplates.MAX_PACK_SIZE)
        val base = MemeProject(
            mode = MemeMode.IMAGE,
            assets = listOf(MemeAsset("a1", MemeMode.IMAGE)),
            overlays = listOf(
                MemeOverlay("old", MemeOverlayKind.TEXT, "old", MemeFontSlot.SANS, 48, 0, 0, false, .5f, .5f, 1f, 0f),
            ),
            lookId = "noir",
        )
        val applied = space.bitos.core.studio.MemeTemplates.apply(base, "classic")
        assertEquals(2, applied.overlays.size)
        assertTrue(applied.overlays.none { it.id == "old" }, "template replaces overlays")
        assertTrue(applied.overlays.all { it.id.startsWith("t1-classic") || it.id.startsWith("t2-classic") })
        // Assets/grade survive; unknown id is a no-op.
        assertEquals(base.assets, applied.assets)
        assertEquals("noir", applied.lookId)
        assertEquals(base, space.bitos.core.studio.MemeTemplates.apply(base, "nope"))
    }
}
