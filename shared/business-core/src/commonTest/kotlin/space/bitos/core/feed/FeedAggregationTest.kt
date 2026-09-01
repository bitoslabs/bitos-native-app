package space.bitos.core.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import space.bitos.core.model.EventId
import space.bitos.core.model.NostrEvent
import space.bitos.core.model.NostrKinds
import space.bitos.core.model.ProfileMetadata
import space.bitos.core.model.Pubkey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedNoteTest {

    private fun note(
        content: String,
        tags: List<List<String>> = emptyList(),
        kind: Int = NostrKinds.SHORT_TEXT_NOTE,
        createdAt: Long = 1_000,
    ) = FeedNote.from(
        NostrEvent(
            id = EventId.parse("11".repeat(32))!!,
            pubkey = Pubkey.parse("22".repeat(32))!!,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            signature = null,
            receivedFromRelay = null,
        ),
    )

    @Test
    fun extractsHashtagsMentionsAndMedia() {
        val feedNote = note(
            "Check #Bitcoin and #Lightning @satoshi plus https://example.com/pic.png and https://cdn.io/clip.mp4",
        )
        assertEquals(listOf("Bitcoin", "Lightning"), feedNote.hashtags)
        assertEquals(listOf("satoshi"), feedNote.mentions)
        assertEquals(
            listOf("https://example.com/pic.png", "https://cdn.io/clip.mp4"),
            feedNote.mediaUrls,
        )
        assertFalse(feedNote.isProtocolPayload)
        assertNull(feedNote.replyTo)
    }

    @Test
    fun resolvesReplyParentFromNip10Marker() {
        val feedNote = note("a reply", tags = listOf(listOf("e", "aa".repeat(32), "", "reply")))
        assertEquals("aa".repeat(32), feedNote.replyTo)
    }

    @Test
    fun resolvesReplyParentFromNip22LowercaseConvention() {
        val feedNote = note("a comment", tags = listOf(listOf("e", "bb".repeat(32))))
        assertEquals("bb".repeat(32), feedNote.replyTo)
    }

    @Test
    fun classifiesProtocolPayloads() {
        // Web content-classification parity: serialized channel rosters are
        // machine traffic; prose (even JSON-ish) stays readable.
        assertTrue(note("channel:__roster\n${"ab".repeat(64)}").isProtocolPayload)
        assertFalse(note("""{"kind":"token","amount":1}""").isProtocolPayload)
        assertFalse(note("just talking about json { is fine").isProtocolPayload)
    }

    @Test
    fun filtersMachineTagsFromHashtags() {
        val feedNote = note("hi #nostr #udal-friend-aede0a98e7fd3ffef77db169c0ccaaa1")
        // Inline extraction keeps both; tag consumers filter (humanTags).
        assertEquals(listOf("nostr", "udal-friend-aede0a98e7fd3ffef77db169c0ccaaa1"), feedNote.hashtags)
        assertEquals(listOf("nostr"), space.bitos.core.nostr.ContentClassification.humanTags(feedNote.hashtags))
    }

    @Test
    fun feedKindsIncludeTextAndBothNip71VideoKinds() {
        assertTrue(FeedNote.isFeedKind(NostrKinds.SHORT_TEXT_NOTE))
        assertTrue(FeedNote.isFeedKind(NostrKinds.NORMAL_VIDEO))
        assertTrue(FeedNote.isFeedKind(NostrKinds.SHORT_VIDEO))
        assertFalse(FeedNote.isFeedKind(NostrKinds.REPOST))
    }
}

class ProfileMetadataTest {

    @Test
    fun parsesBoundedMetadata() {
        val event = NostrEvent(
            id = EventId.parse("33".repeat(32))!!,
            pubkey = Pubkey.parse("44".repeat(32))!!,
            createdAt = 1_000,
            kind = NostrKinds.PROFILE_METADATA,
            tags = emptyList(),
            content = """{"name":"satoshi","display_name":"Satoshi N","about":"₿","picture":"https://x/p.png","nip05":"s@x.com","lud16":"s@wallet.io","banner":"https://x/banner.png","website":"https://bitos.space","unknown":"dropped"}""",
            signature = null,
            receivedFromRelay = null,
        )
        val profile = ProfileMetadata.parse(event)!!
        assertEquals("Satoshi N", profile.bestDisplayName)
        assertEquals("s@x.com", profile.nip05)
        assertEquals("s@wallet.io", profile.lud16)
        assertEquals("https://x/banner.png", profile.banner)
        assertEquals("https://bitos.space", profile.website)
    }

    @Test
    fun fallsBackToPubkeyPrefixWhenUnnamed() {
        val event = NostrEvent(
            id = EventId.parse("33".repeat(32))!!,
            pubkey = Pubkey.parse("44".repeat(32))!!,
            createdAt = 1_000,
            kind = NostrKinds.PROFILE_METADATA,
            tags = emptyList(),
            content = "{}",
            signature = null,
            receivedFromRelay = null,
        )
        assertEquals("44444444", ProfileMetadata.parse(event)!!.bestDisplayName)
    }

    @Test
    fun rejectsInvalidJsonAndWrongKind() {
        val textEvent = NostrEvent(
            id = EventId.parse("33".repeat(32))!!,
            pubkey = Pubkey.parse("44".repeat(32))!!,
            createdAt = 1_000,
            kind = NostrKinds.SHORT_TEXT_NOTE,
            tags = emptyList(),
            content = """{"name":"x"}""",
            signature = null,
            receivedFromRelay = null,
        )
        assertNull(ProfileMetadata.parse(textEvent))
    }
}

class FeedAggregatorTest {

    private fun note(id: String, createdAt: Long) = FeedNote(
        id = id,
        pubkey = "aa".repeat(32),
        content = "note $id",
        createdAt = createdAt,
        kind = 1,
        replyTo = null,
        hashtags = emptyList(),
        mentions = emptyList(),
        mediaUrls = emptyList(),
        isProtocolPayload = false,
    )

    @Test
    fun deduplicatesByEventId() {
        val aggregator = FeedAggregator()
        assertTrue(aggregator.insert(note("a", 100)))
        assertFalse(aggregator.insert(note("a", 100)))
        assertEquals(1, aggregator.size())
    }

    @Test
    fun sortsNewestFirstWithStableTieBreak() {
        val aggregator = FeedAggregator()
        aggregator.insert(note("a", 100))
        aggregator.insert(note("b", 300))
        aggregator.insert(note("c", 200))
        assertEquals(listOf("b", "c", "a"), aggregator.snapshot().map { it.id })
    }

    @Test
    fun boundsWindowSizeByDroppingOldest() {
        val aggregator = FeedAggregator(maxItems = 3)
        for (i in 0 until 10) aggregator.insert(note("id$i", i * 10L))
        assertEquals(3, aggregator.size())
        assertEquals(listOf("id9", "id8", "id7"), aggregator.snapshot().map { it.id })
    }

    @Test
    fun insertOlderExtendsAFullWindowBackward() {
        // The "load more does nothing at a full window" trap: an older page
        // landing on a bounded window must evict at the HEAD (newest), not
        // the tail — tail eviction drops the just-landed older note itself.
        val aggregator = FeedAggregator(maxItems = 3)
        for (i in 0 until 3) aggregator.insert(note("id$i", 100L + i * 10L))
        assertTrue(aggregator.insertOlder(note("old0", 40L)))
        assertEquals(3, aggregator.size())
        // The NEWEST id (id2) is evicted at the head; the window extends
        // downward — the reader is scrolling into older history.
        assertEquals(listOf("id1", "id0", "old0"), aggregator.snapshot().map { it.id })
        // Dedupe parity with insert().
        assertFalse(aggregator.insertOlder(note("old0", 40L)))
    }

    @Test
    fun insertOlderSlidesTheWindowDeepIntoHistory() {
        // A bounded backwards walk keeps the window full while the oldest
        // boundary retreats — Home "load more" at the cap.
        val aggregator = FeedAggregator(maxItems = 4)
        for (i in 0 until 4) aggregator.insert(note("id$i", 1_000L - i * 10L))
        var cursor = 950L
        repeat(6) { step ->
            aggregator.insertOlder(note("old$step", cursor))
            cursor -= 10L
        }
        assertEquals(4, aggregator.size())
        val ids = aggregator.snapshot().map { it.id }
        // Canonical newest-first order: the four retained older notes.
        assertEquals(listOf("old2", "old3", "old4", "old5"), ids)
        // The oldest boundary retreated from 970 to 900 (old5).
        assertEquals(900L, aggregator.snapshot().last().createdAt)
    }

    @Test
    fun insertOlderEvictsAllIdsSharingTheNewestSecond() {
        // Tie behavior mirrors trimIfNeeded from the head end: ids sharing
        // the boundary second are evictable, never a mid-window note.
        val aggregator = FeedAggregator(maxItems = 2)
        aggregator.insert(note("a", 100))
        aggregator.insert(note("b", 100))
        assertTrue(aggregator.insertOlder(note("c", 50)))
        assertEquals(listOf("a", "c"), aggregator.snapshot().map { it.id })
    }

    @Test
    fun prependKeepsVisibleOrderStable() {
        val aggregator = FeedAggregator()
        aggregator.insert(note("old1", 100))
        aggregator.insert(note("old2", 200))
        val result = aggregator.prepend(listOf(note("new1", 500), note("new2", 400)), visibleOrder = listOf("old2", "old1"))
        // Visible ids keep their relative order, fresh ids land below them.
        assertEquals(listOf("old2", "old1", "new1", "new2"), result.map { it.id })
    }

    @Test
    fun snapshotStaysCanonicalAcrossInterleavedInserts() {
        // Incremental ordering (no per-event re-sort): arbitrary arrival
        // order still yields the canonical snapshot, and the cached
        // snapshot agrees with a fresh rebuild after every mutation.
        val aggregator = FeedAggregator(maxItems = 8)
        val stamps = listOf(500L, 100L, 300L, 200L, 600L, 400L)
        stamps.forEachIndexed { index, at -> aggregator.insert(note("n$index", at)) }
        val expected = listOf("n4", "n0", "n5", "n2", "n3", "n1")
        assertEquals(expected, aggregator.snapshot().map { it.id })
        // Snapshot is stable across repeated reads (cache path).
        assertEquals(expected, aggregator.snapshot().map { it.id })
        // A late arrival slots into the maintained order without a re-sort.
        aggregator.insert(note("late", 350L))
        assertEquals(listOf("n4", "n0", "n5", "late", "n2", "n3", "n1"), aggregator.snapshot().map { it.id })
    }

    @Test
    fun equalTimestampsBreakTiesByIdAscending() {
        val aggregator = FeedAggregator()
        aggregator.insert(note("c", 100))
        aggregator.insert(note("a", 100))
        aggregator.insert(note("b", 100))
        assertEquals(listOf("a", "b", "c"), aggregator.snapshot().map { it.id })
    }

    @Test
    fun snapshotsRemainConsistentDuringConcurrentInserts() = runBlocking {
        val aggregator = FeedAggregator(maxItems = 200)
        coroutineScope {
            repeat(4) { writer ->
                launch(Dispatchers.Default) {
                    repeat(100) { index ->
                        aggregator.insert(note("$writer-$index", writer * 1_000L + index))
                    }
                }
            }
            repeat(4) {
                launch(Dispatchers.Default) {
                    repeat(500) {
                        val snapshot = aggregator.snapshot()
                        assertEquals(snapshot.map { it.id }.toSet().size, snapshot.size)
                        assertTrue(snapshot.zipWithNext().all { (a, b) -> a.createdAt >= b.createdAt })
                    }
                }
            }
        }
        assertEquals(200, aggregator.size())
        assertEquals(200, aggregator.snapshot().map { it.id }.toSet().size)
    }
}
