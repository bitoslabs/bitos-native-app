package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compact `meme` tag payload battery (plan MST-042, §3.3; web `remix.ts`
 * parity): compact round-trips with defaults omitted, the 700-char ladder
 * degrades layout+cues → layout → media-only (never a raw truncate), the
 * decode caps at 12 overlays and keeps unknown future keys verbatim.
 */
class MemeRemixTest {

    private fun document(
        overlays: List<MemeWireOverlay>,
        cues: List<MemeWireCue> = emptyList(),
        passthrough: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ) = MemeWireDocument(
        overlays = overlays, sfxCues = cues, passthrough = passthrough,
        createdAt = 0, updatedAt = 0,
    )

    private fun overlay(
        text: String = "gm",
        color: String = "#ffffff",
        font: String = "impact",
        caps: Boolean = true,
        stroke: Boolean = true,
        bar: Boolean = false,
        startMs: Long? = null,
        endMs: Long? = null,
    ) = MemeWireOverlay(
        id = "o1", text = text, x = 0.31f, y = 0.77f, size = 0.11f,
        color = color, font = font, caps = caps, stroke = stroke, bar = bar,
        startMs = startMs, endMs = endMs, fx = null,
    )

    @Test
    fun compactEncodeOmitsDefaultsAndRoundsTo2dp() {
        val encoded = MemeRemix.encode(
            document(listOf(overlay(startMs = 250L, endMs = 9000L))),
        )
        // Defaults (impact/white/caps/stroke/no-bar) never ride.
        assertFalse(encoded.contains("\"c\""), encoded)
        assertFalse(encoded.contains("\"f\""), encoded)
        assertFalse(encoded.contains("\"k\""), encoded)
        assertFalse(encoded.contains("\"o\":false"), encoded)
        assertFalse(encoded.contains("\"b\""), encoded)
        // Rounding to 2 dp + the window pair.
        assertTrue(encoded.contains("\"x\":0.31"), encoded)
        assertTrue(encoded.contains("\"s\":0.11"), encoded)
        assertTrue(encoded.contains("\"w\":[250,9000]"), encoded)
        // Non-defaults DO ride.
        val custom = MemeRemix.encode(
            document(
                listOf(overlay(color = "#fde047", font = "mono", caps = false, stroke = false, bar = true)),
            ),
        )
        assertTrue(custom.contains("\"c\":\"#fde047\""), custom)
        assertTrue(custom.contains("\"f\":\"mono\""), custom)
        assertTrue(custom.contains("\"k\":false"), custom)
        assertTrue(custom.contains("\"o\":false"), custom)
        assertTrue(custom.contains("\"b\":true"), custom)
    }

    @Test
    fun compactRoundTripSurvivesWithCuesAndLook() {
        val source = document(
            listOf(overlay(text = "wen moon", color = "#22d3ee", font = "mono", caps = false)),
            cues = listOf(
                MemeWireCue(id = "c1", sfx = "coin", atMs = 1500, gain = 0.5f, lane = 2, soundId = null),
            ),
        ).copy(lookId = "noir")
        val decoded = MemeRemix.decode(MemeRemix.encode(source))!!
        val overlayOut = decoded.overlays.single()
        assertEquals("wen moon", overlayOut.text)
        assertEquals("#22d3ee", overlayOut.color)
        assertEquals("mono", overlayOut.font)
        assertFalse(overlayOut.caps)
        assertTrue(overlayOut.stroke)
        assertEquals(0.31f, overlayOut.x, 0.001f)
        val cueOut = decoded.sfxCues.single()
        assertEquals("coin", cueOut.sfx)
        assertEquals(1500L, cueOut.atMs)
        assertEquals(0.5f, cueOut.gain, 0.001f)
        assertEquals(2, cueOut.lane)
        assertEquals("noir", decoded.lookId)
    }

    @Test
    fun ladderDegradesLayoutThenMediaOnlyNeverTruncates() {
        // Tuned: 12 compact rows (≤700 alone) + a 16-cue sheet (>700 with
        // it) — the FULL payload busts the budget, layout-only fits.
        val big = document(
            List(12) { overlay(text = "padded caption $it") },
            cues = List(16) {
                MemeWireCue(id = "c$it", sfx = "coin", atMs = it * 100L, gain = 1f, lane = null, soundId = null)
            },
        )
        val step1 = MemeRemix.encodeDegraded(big)
        assertFalse(step1.mediaOnly)
        assertTrue(step1.payload!!.length <= MemeRemix.MAX_TAG_CHARS, step1.payload.length.toString())
        assertFalse(step1.payload.contains("\"c\":"), "cues dropped at step 2")

        // Even layout-only can bust 700 with hostile text → media-only.
        val huge = document(
            List(12) { overlay(text = "x".repeat(120)) },
        )
        val step2 = MemeRemix.encodeDegraded(huge)
        assertTrue(step2.mediaOnly, "media-only when even layout busts the budget")
        assertNull(step2.payload)

        // And a small payload never degrades.
        val small = MemeRemix.encodeDegraded(document(listOf(overlay())))
        assertFalse(small.mediaOnly)
        assertTrue(small.payload!!.contains("\"c\"") == false) // no cues anyway
    }

    @Test
    fun decodeIsTolerantAndCapsAtTwelve() {
        assertNull(MemeRemix.decode(null))
        assertNull(MemeRemix.decode("junk"))
        assertNull(MemeRemix.decode("""{"v":1,"c":[]}"""), "no overlays → unusable")
        // 20 rows decode to 12 (the §3.3 cap).
        val rows = (1..20).joinToString(",") { """{"t":"m$it"}""" }
        val decoded = MemeRemix.decode("""{"v":1,"o":[$rows]}""")!!
        assertEquals(MemeRemix.MAX_DECODE_OVERLAYS, decoded.overlays.size)
        // Hostile junk fields fall to defaults, never throw.
        val hostile = MemeRemix.decode("""{"v":1,"o":[{"t":"gm","x":-4,"s":9,"c":"nope","f":"comic"}]}""")!!
        assertEquals(0f, hostile.overlays.single().x, 1e-4f)
        assertEquals(0.22f, hostile.overlays.single().size)
        assertEquals("#ffffff", hostile.overlays.single().color)
        assertEquals("impact", hostile.overlays.single().font)
    }

    @Test
    fun futureKeysRideVerbatimThroughANativeRoundTrip() {
        // A web-published payload with zoom/fx/speed tracks (V2 studio):
        // native decodes → re-encodes WITHOUT dropping them.
        val webPayload = """
            {"v":1,"o":[{"t":"gm","x":0.5,"y":0.2,"s":0.09}],
             "z":[[0,1000,1.5,0.5,0.5]],"f":[["flash",0,400,0.7]]}
        """.trimIndent()
        val decoded = MemeRemix.decode(webPayload)!!
        val reEncoded = MemeRemix.encode(decoded)
        assertTrue(reEncoded.contains("\"z\":[[0,1000,1.5,0.5,0.5]]"), reEncoded)
        assertTrue(reEncoded.contains("\"f\":"), reEncoded)
    }
}
