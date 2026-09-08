package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Use this sound" contract (TikTok-style sound sourcing, plan
 * docs/product/use-this-sound-plan.md Wave A): a project may carry ONE
 * imported-audio soundtrack extracted from another video (a bitz or a
 * library pick). The wire row is additive + bounded; publish tags carry
 * provenance (`sound` + `p` + `attribution`) and are stamped ONLY when
 * the audio is uploaded + hash-verified — never before.
 */
class MemeSoundRulesTest {

    private val sha = "a".repeat(64)

    private fun soundtrack(
        url: String = "https://blossom.example/b/audio.mp4",
        sourceNoteId: String? = "source-event-id",
        sourceAuthorPubkey: String? = "author-pubkey",
        label: String = "Original sound · author",
    ) = MemeSoundtrack(
        url = url,
        sha256 = sha,
        durationMs = 12_000,
        startMs = 1_000,
        volume = 0.8f,
        offsetMs = 500,
        sourceNoteId = sourceNoteId,
        sourceAuthorPubkey = sourceAuthorPubkey,
        label = label,
    )

    // ── Wire codec (additive, bounded, junk-tolerant) ─────────────────

    @Test
    fun soundtrackRoundTripsThroughTheProjectWire() {
        val project = MemeProject(mode = MemeMode.VIDEO, soundtrack = soundtrack())
        val decoded = MemeProjectContract.decode(MemeProjectContract.encode(project))!!
        val sound = decoded.soundtrack!!
        assertEquals("https://blossom.example/b/audio.mp4", sound.url)
        assertEquals(sha, sound.sha256)
        assertEquals(12_000, sound.durationMs)
        assertEquals(1_000, sound.startMs)
        assertEquals(0.8f, sound.volume)
        assertEquals(500, sound.offsetMs)
        assertEquals("source-event-id", sound.sourceNoteId)
        assertEquals("author-pubkey", sound.sourceAuthorPubkey)
        assertEquals("Original sound · author", sound.label)
    }

    @Test
    fun oldWiresDecodeWithNoSoundtrackAndSoundNeverBreaksThem() {
        val legacy = MemeProjectContract.encode(MemeProject(mode = MemeMode.VIDEO))
        assertTrue("sound" !in legacy)
        assertNull(MemeProjectContract.decode(legacy)!!.soundtrack)
    }

    @Test
    fun hostileSoundRowDegradesToNoSoundtrackNotAFailedDecode() {
        val legacy = MemeProjectContract.encode(MemeProject(mode = MemeMode.VIDEO))
        val injected = legacy.dropLast(1) + // strip closing '}'
            ",\"sound\":{\"url\":\"x\",\"sha256\":\"zz\",\"durationMs\":-4}}"
        assertNull(MemeProjectContract.decode(injected)!!.soundtrack)
    }

    // ── Bounds (normalize) ────────────────────────────────────────────

    @Test
    fun normalizeRefusesUnusableDurationsAndHashes() {
        assertNull(MemeSoundRules.normalize(soundtrack().copy(durationMs = 0)))
        assertNull(MemeSoundRules.normalize(soundtrack().copy(durationMs = MemeSoundRules.MAX_SOUND_DURATION_MS + 1)))
        assertNull(MemeSoundRules.normalize(soundtrack().copy(sha256 = "not-hex")))
    }

    @Test
    fun normalizeClampsTimingVolumeAndText() {
        val clamped = MemeSoundRules.normalize(
            soundtrack().copy(
                startMs = 99_000,
                volume = 9f,
                offsetMs = -5,
                label = "x".repeat(500),
                url = "https://blossom.example/b/" + "a".repeat(4_000),
            ),
        )!!
        assertEquals(11_999, clamped.startMs, "in-point stays inside the audio")
        assertEquals(MemeProjectContract.MAX_CLIP_VOLUME, clamped.volume)
        assertEquals(0, clamped.offsetMs)
        assertEquals(MemeSoundRules.MAX_LABEL_LENGTH, clamped.label.length)
        assertEquals(MemeSoundRules.MAX_URL_LENGTH, clamped.url.length)
    }

    // ── Publish tags (provenance; only after upload+verify) ───────────

    @Test
    fun tagsForStampsSoundPAndAttribution() {
        val tags = MemeSoundRules.tagsFor(soundtrack())
        val sound = tags.first()
        assertEquals("sound", sound[0])
        assertEquals("https://blossom.example/b/audio.mp4", sound[1])
        assertEquals(sha, sound[2])
        assertEquals("source-event-id", sound[3], "source event id rides the sound tag")
        val p = tags.first { it[0] == "p" }
        assertEquals("author-pubkey", p[1])
        val attribution = tags.first { it[0] == "attribution" }
        assertEquals("sound of Original sound · author", attribution[1])
    }

    @Test
    fun tagsForStampsNothingUntilTheAudioIsUploaded() {
        // No URL yet (attach-time, pre-upload): nothing is stampable —
        // nothing is signed before the upload exists (safety rule).
        assertTrue(MemeSoundRules.tagsFor(soundtrack().copy(url = "")).isEmpty())
        // Library picks carry no source: no p tag, no 4th marker.
        val local = MemeSoundRules.tagsFor(soundtrack().copy(sourceNoteId = null, sourceAuthorPubkey = null, label = ""))
        assertEquals(1, local.size, "sound tag only — no p, no attribution")
    }

    @Test
    fun attributionStaysWithinTheLegacyBound() {
        // The label cap (80) already bounds the attribution (≤ 89 chars);
        // the 140 take in tagsFor stays as defense-in-depth if the label
        // cap ever grows.
        val tags = MemeSoundRules.tagsFor(soundtrack().copy(label = "y".repeat(400)))
        val attribution = tags.first { it[0] == "attribution" }[1]
        assertTrue(attribution.length <= space.bitos.core.feed.RemixRules.ATTRIBUTION_MAX, attribution)
        assertEquals("sound of ${"y".repeat(MemeSoundRules.MAX_LABEL_LENGTH)}", attribution)
    }

    // ── Read side (feed chip + trending rank reuse) ───────────────────

    @Test
    fun sourceOfReadsTheProvenanceBack() {
        val source = MemeSoundRules.sourceOf(MemeSoundRules.tagsFor(soundtrack()))!!
        assertEquals("source-event-id", source.eventId)
        assertEquals("author-pubkey", source.pubkey)
        assertEquals(sha, source.sha256, "the audio hash rides the read seam (trending dedup)")
        assertNull(MemeSoundRules.sourceOf(emptyList()))
    }

    // ── Command seam (iOS store applies wire commands) ───────────────

    @Test
    fun setSoundtrackCommandRoundTripsAndApplies() {
        val command = MemeCommand.SetSoundtrack(soundtrack())
        val json = MemeCommandCodec.encode(command)
        val decoded = MemeCommandCodec.decode(json)!! as MemeCommand.SetSoundtrack
        assertEquals(soundtrack(), decoded.soundtrack)
        val project = MemeRules.apply(
            MemeProject(mode = MemeMode.VIDEO),
            decoded,
        )
        assertEquals(soundtrack(), project.soundtrack)
        // sound-del removes; a junk row normalizes to the same removal.
        val removed = MemeRules.apply(
            project,
            MemeCommandCodec.decode("{\"op\":\"sound-del\"}") as MemeCommand.SetSoundtrack,
        )
        assertNull(removed.soundtrack)
        val junk = MemeRules.apply(
            MemeProject(mode = MemeMode.VIDEO),
            MemeCommand.SetSoundtrack(soundtrack().copy(durationMs = 0)),
        )
        assertNull(junk.soundtrack, "an unusable row never lands on the wire")
    }

    // ── Interop fixture (repo rule: protocol changes ship fixtures) ───

    @Test
    fun contractsFixtureDecodesWithSoundtrack() {
        // Verbatim from contracts/meme/project-soundtrack-v1.json — pins
        // the additive `"sound"` row for cross-platform interop.
        val fixture = """{"v":1,"mode":"video","assets":[],"overlays":[],
            "clips":[{"id":"v1","start":0,"end":12000}],
            "sound":{"url":"https://blossom.example/b/9f2c/audio.mp4",
             "sha256":"9f2c4a1b7e5d3a6f8c0b2d4e6f8a0c2b4d6e8f0a2c4b6d8e0f2a4c6b8d0e2f4a",
             "ms":12000,"start":1000,"vol":0.8,"offset":500,
             "src":"aa11bb22cc33dd44ee55ff66778899aabbccddeeff00112233445566778899aa",
             "author":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
             "label":"Original sound · satoshi"},
            "cw":"","alt":"clip over a borrowed sound","tags":[]}"""
        val project = MemeProjectContract.decode(fixture)!!
        val sound = project.soundtrack!!
        assertEquals(12_000, sound.durationMs)
        assertEquals(1_000, sound.startMs)
        assertEquals(0.8f, sound.volume)
        assertEquals(500, sound.offsetMs)
        assertEquals("Original sound · satoshi", sound.label)
        val tags = MemeSoundRules.tagsFor(sound)
        assertEquals("aa11bb22cc33dd44ee55ff66778899aabbccddeeff00112233445566778899aa", tags[0][3])
        // The published tags round-trip through the read seam.
        assertEquals(
            "aa11bb22cc33dd44ee55ff66778899aabbccddeeff00112233445566778899aa",
            MemeSoundRules.sourceOf(tags)!!.eventId,
        )
    }
}
