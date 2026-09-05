package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Meme project wire contract (plan MST-001): versioned round-trip, hostile
 * clamps and lenient-but-bounded decode — a broken store must never block
 * creating.
 */
class MemeProjectContractTest {

    private val project = MemeProject(
        mode = MemeMode.GIF,
        assets = listOf(MemeAsset("a1", MemeMode.GIF, delayMs = 100)),
        overlays = listOf(
            MemeOverlay(
                id = "o1", kind = MemeOverlayKind.TEXT, text = "gm",
                font = MemeFontSlot.IMPACT, size = 48, colorIndex = 0,
                outline = 2, shadow = true, x = 0.5f, y = 0.6f,
                scale = 1.2f, rotationDeg = -7f,
            ),
        ),
        frameDelayMs = 120,
        tags = listOf("memes"),
    )

    @Test
    fun roundTripsTheProject() {
        val decoded = MemeProjectContract.decode(MemeProjectContract.encode(project))
        assertEquals(project, decoded)
    }

    @Test
    fun rejectsUnknownVersionsCorruptJsonAndOversizedWire() {
        val wrongVersion = MemeProjectContract.encode(project).replace("\"v\":1", "\"v\":99")
        assertNull(MemeProjectContract.decode(wrongVersion))
        assertNull(MemeProjectContract.decode("not json at all"))
        assertNull(MemeProjectContract.decode("x".repeat(MemeProjectContract.MAX_WIRE_LENGTH + 1)))
    }

    @Test
    fun decodesMissingModeToNullButDegradesUnknownOverlayFields() {
        val noMode = """{"v":1,"assets":[],"overlays":[]}"""
        assertNull(MemeProjectContract.decode(noMode))

        val degraded = MemeProjectContract.decode(
            """{"v":1,"mode":"IMAGE","assets":[],"overlays":[
               {"id":"o1","kind":"sticker","text":"hello","font":"weird",
                "size":9999,"color":999,"outline":999,"x":9,"y":-3,"scale":99,"rot":999}]}""",
        )
        val overlay = degraded?.overlays?.single()
        assertEquals(MemeOverlayKind.STICKER, overlay?.kind)
        assertEquals(MemeFontSlot.IMPACT, overlay?.font)
        assertEquals(MemeRules.MAX_SIZE, overlay?.size)
        assertEquals(MemeRules.PALETTE.lastIndex, overlay?.colorIndex)
        assertEquals(MemeRules.MAX_OUTLINE, overlay?.outline)
        assertEquals(1f, overlay?.x)
        assertEquals(0f, overlay?.y)
        assertEquals(MemeRules.MAX_SCALE, overlay?.scale)
        // Modular wrap: 999° folds into the −180..180 window.
        assertEquals(-81f, overlay?.rotationDeg)
    }

    @Test
    fun clampsAssetCapsPerMode() {
        val asset = MemeAsset("a", MemeMode.IMAGE)
        val imageProject = project.copy(
            mode = MemeMode.IMAGE,
            assets = List(20) { asset },
        )
        val decodedImage = MemeProjectContract.decode(MemeProjectContract.encode(imageProject))
        assertEquals(9, decodedImage?.assets?.size)

        val gifProject = project.copy(
            assets = List(70) { MemeAsset("f$it", MemeMode.GIF) },
        )
        val decodedGif = MemeProjectContract.decode(MemeProjectContract.encode(gifProject))
        assertEquals(60, decodedGif?.assets?.size)

        // VIDEO = ≤ MAX_VIDEO_CLIPS clips + ≤ MAX_IMAGE_LAYERS image sources.
        val videoProject = project.copy(
            mode = MemeMode.VIDEO,
            assets = listOf(MemeAsset("v1", MemeMode.VIDEO)) +
                List(20) { MemeAsset("a$it", MemeMode.IMAGE) },
        )
        val decodedVideo = MemeProjectContract.decode(MemeProjectContract.encode(videoProject))
        assertEquals(MemeProjectContract.MAX_VIDEO_CLIPS + MemeProjectContract.MAX_IMAGE_LAYERS, decodedVideo?.assets?.size)
    }

    @Test
    fun videoImageLayersRoundTripWithTheirAssetRefs() {
        val layered = project.copy(
            mode = MemeMode.VIDEO,
            assets = listOf(MemeAsset("v1", MemeMode.VIDEO)) +
                List(MemeProjectContract.MAX_IMAGE_LAYERS) { MemeAsset("a$it", MemeMode.IMAGE) },
            overlays = listOf(
                MemeOverlay(
                    id = "L1", kind = MemeOverlayKind.IMAGE, text = "",
                    font = MemeFontSlot.SANS, size = MemeRules.DEFAULT_IMAGE_SIZE,
                    colorIndex = 0, outline = 0, shadow = false,
                    x = 0.4f, y = 0.6f, scale = 1.5f, rotationDeg = -20f,
                    assetId = "a1",
                ),
            ),
        )
        val decoded = MemeProjectContract.decode(MemeProjectContract.encode(layered))
        assertEquals(1 + MemeProjectContract.MAX_IMAGE_LAYERS, decoded?.assets?.size)
        val layer = decoded?.overlays?.single()
        assertEquals(MemeOverlayKind.IMAGE, layer?.kind)
        assertEquals("a1", layer?.assetId, "the asset reference survives the local wire")
        assertEquals(1.5f, layer?.scale)
    }

    @Test
    fun clampsOverlayCountTextAndTags() {
        val flood = project.copy(
            overlays = List(60) {
                MemeOverlay(
                    id = "o$it", kind = MemeOverlayKind.TEXT, text = "t".repeat(500),
                    font = MemeFontSlot.SANS, size = 48, colorIndex = 0,
                    outline = 0, shadow = false, x = 0.5f, y = 0.5f,
                    scale = 1f, rotationDeg = 0f,
                )
            },
            tags = List(40) { "tag$it" },
        )
        val decoded = MemeProjectContract.decode(MemeProjectContract.encode(flood))
        assertEquals(MemeProjectContract.MAX_OVERLAYS, decoded?.overlays?.size)
        assertTrue(decoded?.overlays?.all { it.text.length == MemeProjectContract.MAX_TEXT_LENGTH } == true)
        assertEquals(MemeProjectContract.MAX_TAGS, decoded?.tags?.size)
    }

    @Test
    fun videoOnlyFieldsStayModeBounded() {
        val gifWire = MemeProjectContract.encode(project.copy(trimStartMs = 5, trimEndMs = 9))
        assertFalse(gifWire.contains("trim"))
        assertFalse(gifWire.contains("speed"), "speed is a video-only field")

        val video = project.copy(mode = MemeMode.VIDEO, trimStartMs = -5, trimEndMs = 99_000_000)
        val decodedVideo = MemeProjectContract.decode(MemeProjectContract.encode(video))
        assertEquals(0, decodedVideo?.trimStartMs)
        assertEquals(MemeProjectContract.MAX_DURATION_MS, decodedVideo?.trimEndMs)

        // Speed: written only when ≠ 1, clamped both ways, junk → source.
        assertFalse(
            MemeProjectContract.encode(project.copy(mode = MemeMode.VIDEO)).contains("speed"),
            "1× stays implicit",
        )
        val fast = project.copy(mode = MemeMode.VIDEO, speed = 9f)
        assertEquals(2f, MemeProjectContract.decode(MemeProjectContract.encode(fast))?.speed)
        val slow = project.copy(mode = MemeMode.VIDEO, speed = 0.1f)
        assertEquals(0.5f, MemeProjectContract.decode(MemeProjectContract.encode(slow))?.speed)
        val junk = """{"v":1,"mode":"video","assets":[],"overlays":[],"speed":0}"""
        assertEquals(1f, MemeProjectContract.decode(junk)?.speed)

        val gifDelay = MemeProjectContract.decode(
            MemeProjectContract.encode(project.copy(frameDelayMs = 5_000)),
        )
        assertEquals(MemeProjectContract.MAX_FRAME_DELAY_MS, gifDelay?.frameDelayMs)
    }

    @Test
    fun emptyProjectIsEmptyAndEncodedLosslessly() {
        val empty = MemeProject(mode = MemeMode.IMAGE)
        assertTrue(empty.isEmpty)
        assertEquals(empty, MemeProjectContract.decode(MemeProjectContract.encode(empty)))
        assertFalse(
            MemeProjectContract.decode(
                MemeProjectContract.encode(project),
            )!!.isEmpty,
        )
        assertNotEquals(MemeProjectContract.encode(empty), MemeProjectContract.encode(project))
    }

    @Test
    fun strokesRoundTripAndTheBudgetCapsDecodes() {
        val inked = project.copy(
            drawStrokes = listOf(
                MemeStroke(id = "d1", colorIndex = 3, widthNorm = 0.01f, points = listOf(0.1f, 0.2f, 0.3f, 0.4f)),
                // Width clamps both ways; junk decode rows drop.
                MemeStroke(id = "d2", colorIndex = 0, widthNorm = 9f, points = listOf(0.5f, 0.5f, 0.6f, 0.6f)),
            ),
        )
        val wire = MemeProjectContract.encode(inked)
        assertTrue(wire.contains("\"draw\""), wire)
        val decoded = MemeProjectContract.decode(wire)
        assertEquals(2, decoded?.drawStrokes?.size)
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f), decoded?.drawStrokes?.first()?.points)
        assertEquals(MemeProjectContract.MAX_STROKE_WIDTH, decoded?.drawStrokes?.get(1)?.widthNorm)

        // Hostile wire: budget caps the decoded point total, degenerate
        // strokes drop, coordinates clamp.
        val flood = buildString {
            append("""{"v":1,"mode":"image","assets":[],"overlays":[],"draw":[""")
            append(List(50) { index ->
                """{"id":"d$index","c":1,"w":0.01,"p":[${List(400) { "0.5" }.joinToString(",")}]""" + "}"
            }.joinToString(","))
            append("]}")
        }
        val decodedFlood = MemeProjectContract.decode(flood)
        val total = decodedFlood?.drawStrokes?.sumOf { it.points.size } ?: 0
        assertTrue(total <= MemeProjectContract.MAX_DRAWING_POINTS, "decoded $total points")
    }

    // ── M5 timeline clips (wire v2 additive key) ─────────────────────────

    @Test
    fun timelineClipsRoundTripInOrder() {
        val video = project.copy(
            mode = MemeMode.VIDEO,
            clips = listOf(
                MemeClip("v1", 0, 12_000, lookId = "sepia", speed = 0.75f),
                MemeClip("v2", 3_500, 9_000, volume = 0f),
                MemeClip("v3", 1_000, 60_000, volume = 1.5f, lookId = "junk-grade"),
            ),
        )
        val decoded = MemeProjectContract.decode(MemeProjectContract.encode(video))
        assertEquals(listOf("v1", "v2", "v3"), decoded?.clips?.map { it.id })
        assertEquals(3_500, decoded?.clips?.get(1)?.startMs)
        assertEquals(9_000, decoded?.clips?.get(1)?.endMs)
        // Volume: written only when ≠ 1; 0 = mute; junk/clamps bounded.
        assertEquals(0f, decoded?.clips?.get(1)?.volume)
        assertEquals(1.5f, decoded?.clips?.get(2)?.volume)
        assertEquals(1f, decoded?.clips?.get(0)?.volume)
        assertEquals(0.75f, decoded?.clips?.get(0)?.speed)
        // Per-clip look: normalized; unknown ids degrade to none (inherit
        // the project grade).
        assertEquals("sepia", decoded?.clips?.get(0)?.lookId)
        assertEquals(null, decoded?.clips?.get(2)?.lookId)
        val hostileVol = """{"v":1,"mode":"video","assets":[],"overlays":[],"clips":[{"id":"v1","start":0,"end":10,"vol":9}]}"""
        assertEquals(MemeProjectContract.MAX_CLIP_VOLUME, MemeProjectContract.decode(hostileVol)?.clips?.first()?.volume)
        val hostileRate = """{"v":1,"mode":"video","assets":[],"overlays":[],"clips":[{"id":"v1","start":0,"end":10,"rate":9}]}"""
        assertEquals(2f, MemeProjectContract.decode(hostileRate)?.clips?.first()?.speed)
    }

    @Test
    fun v1WireMigratesItsTrimWindowIntoASingleClip() {
        val v1Wire = """{"v":1,"mode":"video","assets":[{"id":"v1","kind":"video"}],"overlays":[],"trim":[2000,8000]}"""
        val decoded = MemeProjectContract.decode(v1Wire)
        assertEquals(listOf(MemeClip("v1", 2_000, 8_000)), decoded?.clips)
        // The legacy trim fields survive untouched for old readers.
        assertEquals(2_000, decoded?.trimStartMs)
        assertEquals(8_000, decoded?.trimEndMs)
    }

    @Test
    fun clipsAreVideoOnlyAndCapped() {
        // GIF/image projects never carry a clips array, and a decode of a
        // clips array outside video mode ignores it.
        val gif = MemeProjectContract.encode(project.copy(clips = listOf(MemeClip("v1", 0, 5))))
        assertFalse(gif.contains("clips"))
        assertFalse(MemeProjectContract.decode(gif)?.clips!!.isNotEmpty())

        val flood = buildString {
            append("""{"v":1,"mode":"video","assets":[],"overlays":[],"clips":[""")
            append(List(30) { index -> """{"id":"v$index","start":0,"end":1000}""" }.joinToString(","))
            append("]}")
        }
        assertEquals(MemeProjectContract.MAX_VIDEO_CLIPS, MemeProjectContract.decode(flood)?.clips?.size)
    }

    @Test
    fun hostileClipRowsDropAndDegenerateWindowsNeverDecode() {
        // Missing id or end ≤ start → the row drops (never a broken clip).
        val hostile = """{"v":1,"mode":"video","assets":[],"overlays":[],"clips":[
            {"start":0,"end":100},
            {"id":"v1","start":500,"end":500},
            {"id":"v2","start":100,"end":200}
        ]}"""
        assertEquals(listOf(MemeClip("v2", 100, 200)), MemeProjectContract.decode(hostile)?.clips)

        // Out-of-range windows clamp; video asset cap lifts for 8 clips.
        assertEquals(
            MemeProjectContract.MAX_VIDEO_CLIPS + MemeProjectContract.MAX_IMAGE_LAYERS,
            MemeProjectContract.maxAssets(MemeMode.VIDEO),
        )
    }
}
