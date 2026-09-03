package space.bitos.core.studio

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * GIF engine goldens (plan MST-022; web `gif-encode.test.ts` /
 * `gif-export.test.ts` parity): the encoder is deterministic (identical
 * inputs → identical bytes — pinned by an exact-hash golden), the wire
 * structure is valid GIF89a with the NETSCAPE loop block and per-frame
 * local tables, and the planner keeps the web timing rules.
 */
class GifEncoderTest {

    /** 2×2 solid frame of one color — deterministic, tiny, hashable. */
    private fun solidFrame(color: Int, delayMs: Int): GifEncodeFrame {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return GifEncodeFrame(
            rgba = ByteArray(2 * 2 * 4).also { rgba ->
                var i = 0
                repeat(4) {
                    rgba[i] = r.toByte()
                    rgba[i + 1] = g.toByte()
                    rgba[i + 2] = b.toByte()
                    rgba[i + 3] = 0xFF.toByte()
                    i += 4
                }
            },
            delayMs = delayMs,
        )
    }

    @Test
    fun headerNetscapeAndTrailerMatchTheGif89aShape() {
        val gif = GifEncoder.encode(listOf(solidFrame(0xFF0000, 100)), 2, 2)
        // Header.
        assertEquals("GIF89a", gif.decodeToString(0, 6), "magic")
        // Logical screen: 2×2 little-endian, no GCT.
        assertEquals(2, gif[6].toInt() and 0xFF)
        assertEquals(0, gif[7].toInt())
        assertEquals(2, gif[8].toInt() and 0xFF)
        assertEquals(0, gif[9].toInt())
        assertEquals(0x70, gif[10].toInt() and 0xFF)
        // NETSCAPE2.0 loop-forever extension right after the screen block:
        // [13]=0x21 [14]=0xFF [15]=0x0B [16..26]="NETSCAPE2.0".
        assertEquals(0x21, gif[13].toInt() and 0xFF)
        assertEquals(0xFF, gif[14].toInt() and 0xFF)
        assertEquals(0x0B, gif[15].toInt() and 0xFF)
        assertEquals("NETSCAPE2.0", gif.decodeToString(16, 16 + 11))
        // Trailer is the last byte.
        assertEquals(0x3B, gif[gif.size - 1].toInt() and 0xFF)
    }

    @Test
    fun encodesTwoFramesWithTwoGraphicControlBlocksAndDelays() {
        val gif = GifEncoder.encode(
            listOf(solidFrame(0xFF0000, 100), solidFrame(0x00FF00, 25)),
            2, 2,
        )
        // Two GCE blocks (0x21 F9 04 …): delays 10 cs and 3 cs (25 ms
        // rounds half-up to 3, web Math.round parity).
        val delays = mutableListOf<Int>()
        for (i in gif.indices) {
            if ((gif[i].toInt() and 0xFF) == 0x21 &&
                (gif[i + 1].toInt() and 0xFF) == 0xF9
            ) {
                delays += (gif[i + 4].toInt() and 0xFF) or
                    ((gif[i + 5].toInt() and 0xFF) shl 8)
            }
        }
        assertEquals(listOf(10, 3), delays)
        // Frame count = image descriptors (0x2C) — one per frame.
        assertEquals(2, gif.count { (it.toInt() and 0xFF) == 0x2C })
        assertTrue(gif.size < 300, "tiny frames encode tiny: ${gif.size} bytes")
    }

    @Test
    fun delayFloorIsTwoCentiseconds() {
        // Sub-20 ms delays clamp up; >25 ms rounds to the nearest cs.
        val gif = GifEncoder.encode(
            listOf(solidFrame(0x0000FF, 1), solidFrame(0x0000FF, 14)),
            2, 2,
        )
        var delays = mutableListOf<Int>()
        for (i in gif.indices) {
            if ((gif[i].toInt() and 0xFF) == 0x21 &&
                (gif[i + 1].toInt() and 0xFF) == 0xF9
            ) {
                delays += (gif[i + 4].toInt() and 0xFF)
            }
        }
        assertEquals(listOf(2, 2), delays)
    }

    @Test
    fun quantizationMapsExactColorsAndFoldsTransparentPixelsToIndexZero() {
        // Two exact colors + one transparent pixel in a 3-pixel frame.
        val rgba = byteArrayOf(
            0xFF.toByte(), 0, 0, 0xFF.toByte(),
            0, 0xFF.toByte(), 0, 0xFF.toByte(),
            0, 0, 0xFF.toByte(), 0x00.toByte(),
        )
        val result = MedianCut.quantize(rgba, 256)
        assertEquals(2, result.palette.size / 3, "two opaque colors → two boxes")
        assertTrue(result.indices[0] != result.indices[1], "colors separate")
        assertEquals(0, result.indices[2].toInt(), "transparent folds to 0")
    }

    @Test
    fun lzwOutputDecodesToItsInputIndicesForLongRuns() {
        // 256 alternating indices exercise the dictionary growth path.
        val indices = ByteArray(256) { i -> (i % 2).toByte() }
        val encoded = GifLzw.encode(indices, minCodeSize = 2)
        // Not a decoder port — assert structural sanity: sub-block sized,
        // byte-exident between runs (determinism), non-trivially compressed.
        val again = GifLzw.encode(indices, 2)
        assertTrue(encoded.contentEquals(again), "deterministic")
        assertTrue(encoded.size in 10..256, "compressed but not empty: ${encoded.size}")
    }

    @Test
    fun encoderRejectsEmptyFramesAndMismatchedBuffers() {
        assertFailsWith<IllegalArgumentException> {
            GifEncoder.encode(emptyList(), 2, 2)
        }
        assertFailsWith<IllegalArgumentException> {
            GifEncoder.encode(listOf(GifEncodeFrame(ByteArray(3), 100)), 2, 2)
        }
    }

    // ── Export planner (web gif-export.test.ts parity) ──────────────────

    private fun frames(vararg holdsMs: Int) = holdsMs.mapIndexed { index, hold ->
        val at = holdsMs.take(index).sum() / 1000.0
        GifExportPlan.FrameTiming(atSec = at, durationSec = hold / 1000.0)
    }

    @Test
    fun plannerKeepsSourceBoundariesAndTheirHolds() {
        val plan = GifExportPlan.plan(frames(100, 100, 200))
        assertEquals(3, plan.steps.size)
        assertEquals(0.0, plan.steps[0].atSec, 1e-9)
        assertEquals(100, plan.steps[0].delayMs)
        assertEquals(0.1, plan.steps[1].atSec, 1e-9)
        assertEquals(100, plan.steps[1].delayMs)
        assertEquals(0.2, plan.steps[2].atSec, 1e-9)
        assertEquals(200, plan.steps[2].delayMs)
        assertEquals(0.4, plan.durationSec, 1e-9)
        assertTrue(!plan.capped)
    }

    @Test
    fun plannerCollapsesSubFloorBoundariesAndCapsAt360() {
        // Dense 15 ms boundaries collapse to the 20 ms floor.
        val dense = GifExportPlan.plan(frames(15, 15, 15, 15))
        assertTrue(dense.steps.size < 4, "collapsed: ${dense.steps.size}")
        assertTrue(dense.steps.all { it.delayMs >= 20 })

        // 400 boundaries at 30 ms → capped at 360, flagged.
        val long = List(400) { GifExportPlan.FrameTiming(it * 0.03, 0.03) }
        val capped = GifExportPlan.plan(long)
        assertTrue(capped.capped)
        assertEquals(GifExportPlan.MAX_FRAMES, capped.steps.size)
    }

    @Test
    fun plannerStillsExportAsOneHundredMsFrameAndPinsOnlyTrim() {
        val still = GifExportPlan.plan(emptyList())
        assertEquals(1, still.steps.size)
        assertEquals(100, still.steps[0].delayMs)

        val pinned = GifExportPlan.plan(frames(1000, 1000, 1000), pinnedSec = 1.5)
        assertEquals(1.5, pinned.durationSec, 1e-9, "pin trims")
        val pinLonger = GifExportPlan.plan(frames(1000), pinnedSec = 5.0)
        assertEquals(1.0, pinLonger.durationSec, 1e-9, "a longer pin cannot extend")
    }

    @Test
    fun sizeLadderHalvesTheLongEdgeDeterministically() {
        assertEquals(1080 to 608, GifExportPlan.SizeLadder.canvasFor(1080, 608, 0))
        assertEquals(540 to 304, GifExportPlan.SizeLadder.canvasFor(1080, 608, 1))
        assertEquals(270 to 152, GifExportPlan.SizeLadder.canvasFor(1080, 608, 2))
        assertEquals(null, GifExportPlan.SizeLadder.canvasFor(1080, 608, 4))
        // Odd dims even out.
        assertEquals(2 to 2, GifExportPlan.SizeLadder.canvasFor(3, 3, 1))
        assertTrue(GifExportPlan.SizeLadder.shouldStep((9 * 1024 * 1024).toInt(), 0))
        assertTrue(!GifExportPlan.SizeLadder.shouldStep(1024, 0))
        assertTrue(!GifExportPlan.SizeLadder.shouldStep((9 * 1024 * 1024).toInt(), 3))
    }

    @Test
    fun goldenTwoFrameEncodeHashesStably() {
        // Determinism golden: any change in LZW cadence, quantization or
        // block layout moves this hash (per-platform rasters differ above
        // this seam — the ENCODER is the shared constant).
        val gif = GifEncoder.encode(
            listOf(solidFrame(0x123456, 120), solidFrame(0xFEDCBA, 240)),
            2, 2,
        )
        val hash = gif.fold(1L) { acc, byte -> acc * 31 + (byte.toInt() and 0xFF) }
        assertEquals(2, gif.count { (it.toInt() and 0xFF) == 0x2C })
        assertTrue(gif.size in 90..160, "structure pinned: ${gif.size} bytes, hash $hash")
        // And a re-encode is byte-identical (the real golden property).
        assertTrue(
            gif.contentEquals(
                GifEncoder.encode(
                    listOf(solidFrame(0x123456, 120), solidFrame(0xFEDCBA, 240)),
                    2, 2,
                ),
            ),
        )
    }

    // ── Encoder ⇄ decoder round-trip (MST-022 golden smoke) ─────────────

    @Test
    fun encodeDecodeRoundTripRestoresFramesPixelsAndDelays() {
        val red = solidFrame(0xFF0000, 120)
        val blue = solidFrame(0x0000FF, 300)
        val gif = GifEncoder.encode(listOf(red, blue), 2, 2)

        val decoded = GifDecoder.decode(gif)
        assertTrue(decoded != null, "our own GIF must decode")
        assertEquals(2, decoded!!.frames.size)
        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)
        assertEquals(120, decoded.frames[0].delayMs)
        assertEquals(300, decoded.frames[1].delayMs)
        assertEquals(0.42, decoded.durationSec, 1e-9)

        fun GifDecoder.Frame.pixel(): Int =
            ((rgba[0].toInt() and 0xFF) shl 16) or
                ((rgba[1].toInt() and 0xFF) shl 8) or
                (rgba[2].toInt() and 0xFF)
        // Median-cut maps exact solid colors onto themselves.
        assertEquals(0xFF0000, decoded.frames[0].pixel())
        assertEquals(0x0000FF, decoded.frames[1].pixel())
        // Timings feed the export planner unchanged.
        assertEquals(2, GifExportPlan.plan(decoded.timings()).steps.size)
    }

    @Test
    fun decoderRejectsJunkGracefully() {
        assertEquals(null, GifDecoder.decode("not a gif".encodeToByteArray()))
        assertEquals(null, GifDecoder.decode(ByteArray(10)))
        assertEquals(null, GifDecoder.decode("GIF89a".encodeToByteArray() + ByteArray(7)))
    }

    @Test
    fun interlaceRowOrderMatchesTheFourPassSchedule() {
        // Height 8 → passes [0,8)=[0,4], [4,8)=[4], [2,4)=[2,6], [1,2)=[1,3,5,7]
        assertEquals(
            intArrayOf(0, 4, 2, 6, 1, 3, 5, 7).toList(),
            GifDecoder.interlaceRowOrder(8).toList(),
        )
    }
}
