package space.bitos.app.ui.create

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.EventId
import space.bitos.core.model.NostrEvent
import space.bitos.core.model.Pubkey

/**
 * MEM-005b/c adapter contract for the ONE handoff mapper both the Bitz
 * card and the Create-hub remix picker build their seeds through. The
 * shared rules (parser, preflight, mismatch, licenses) carry their own
 * common batteries — this pins the ANDROID-side mapping: precedence,
 * relay-hint merge, attribution tags and the no-media fallback.
 */
class MemeRemixHandoffTest {

    private val eventId = "aa".repeat(31) + "11"
    private val pubkey = "bb".repeat(31) + "22"

    private fun note(
        tags: List<List<String>> = emptyList(),
        content: String = "",
    ): FeedNote = FeedNote.from(
        NostrEvent(
            id = EventId.parse(eventId)!!,
            pubkey = Pubkey.parse(pubkey)!!,
            createdAt = 1_000L,
            kind = 22,
            tags = tags,
            content = content,
            signature = null,
            receivedFromRelay = null,
        ),
    )

    @Test
    fun videoNoteSeedsWithImetaUrlAndRelayHints() {
        val n = note(
            tags = listOf(
                listOf("imeta", "url https://blossom.example/b/clip.mp4", "m video/mp4", "dim 720x1280"),
                listOf("remix", "cc".repeat(31) + "33", "wss://source.relay"),
            ),
        )
        val seed = MemeRemixHandoff.editorSeed(
            note = n,
            creatorLabel = "satoshi",
            writeRelays = listOf("wss://write.example", "wss://source.relay", "wss://extra1", "wss://extra2"),
        )!!
        assertEquals(eventId, seed.eventId)
        assertEquals(pubkey, seed.pubkey)
        assertEquals("satoshi", seed.label)
        assertEquals("https://blossom.example/b/clip.mp4", seed.mediaUrl)
        assertTrue(seed.isVideo)
        // Source-tag hints first, then write relays, deduped, ≤3.
        assertEquals(listOf("wss://source.relay", "wss://write.example", "wss://extra1"), seed.relays)
        assertNull(seed.memeTag)
    }

    @Test
    fun memeTagRidesTheSeedVerbatim() {
        val payload = """{"v":1,"o":[{"t":"gm","x":0.5,"y":0.2,"s":0.09}]}"""
        val n = note(tags = listOf(listOf("meme", payload)), content = "https://blossom.example/b/meme.png")
        val seed = MemeRemixHandoff.editorSeed(n, creatorLabel = "anon", writeRelays = emptyList())!!
        assertEquals(payload, seed.memeTag)
        assertTrue(!seed.isVideo)
    }

    @Test
    fun noLoadableMediaMeansNoEditorSeed() {
        val n = note(content = "no media at all")
        assertNull(MemeRemixHandoff.editorSeed(n, "anon", emptyList()))
        // And the credited-post fallback carries remix/p + attribution.
        val tags = MemeRemixHandoff.creditedPostTags(n, "satoshi")
        assertTrue(tags.any { it.first() == "remix" && it[1] == eventId })
        assertTrue(tags.any { it.first() == "p" && it[1] == pubkey })
        assertTrue(tags.any { it.first() == "attribution" && it[1] == "remix of satoshi" })
    }

    @Test
    fun insecureMediaUrlIsNotASeedSource() {
        val n = note(content = "http://insecure.example/b/clip.mp4")
        assertNull(MemeRemixHandoff.editorSeed(n, "anon", emptyList()))
    }
}
