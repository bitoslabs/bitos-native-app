package space.bitos.core.model

import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookmarkListTest {

    private val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val a = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
    private val b = "c52c5fdb44ec230447a503a33f60bb729077fd8b62208456c1752d2c3dcaec1a"
    private val composer = NoteComposer(clock = { 1_710_000_000 })

    private fun event(tags: List<List<String>>, createdAt: Long = 1_000, kind: Int = BookmarkList.KIND) = NostrEvent(
        id = EventId.parse("33".repeat(32))!!,
        pubkey = Pubkey.parse("44".repeat(32))!!,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = "",
        signature = null,
        receivedFromRelay = null,
    )

    @Test
    fun composesAddressableBookmarkList() {
        val list = composer.composeBookmarkList(author, listOf(a, b, a, "invalid"))!!
        assertEquals(BookmarkList.KIND, list.kind)
        assertEquals("", list.content)
        // d coordinate first, then deduped valid ids in order.
        assertEquals(listOf(listOf("d", ""), listOf("e", a), listOf("e", b)), list.tags)

        val expected = NostrEventCodec.computeId(
            Sha256EventHasher, author, 1_710_000_000, BookmarkList.KIND, list.tags, "",
        )
        assertEquals(expected, list.idHex)
    }

    @Test
    fun roundTripsThroughVerifiedCodecAndProjection() {
        val list = composer.composeBookmarkList(author, listOf(a, b))!!
        val frame = composer.publishMessage(list, "77".repeat(64))!!
        val decoded = NostrEventCodec.decodeClientEventFrame(
            Sha256EventHasher, frame, RelayUrl.parse("wss://relay.test"),
        )
        assertEquals(BookmarkList.KIND, decoded.kind)
        assertEquals(list.tags, decoded.tags)
        // Write path and read path agree on the saved set.
        assertEquals(listOf(a, b), BookmarkList.bookmarkedIds(decoded))
    }

    @Test
    fun boundsAndRejects() {
        val many = (0 until BookmarkList.MAX_BOOKMARKS + 10).map { it.toString(16).padStart(64, '0') }
        val list = composer.composeBookmarkList(author, many)!!
        // d tag + MAX_BOOKMARKS e tags.
        assertEquals(BookmarkList.MAX_BOOKMARKS, list.tags.size - 1)

        assertNull(composer.composeBookmarkList("zz", listOf(a)))
        assertTrue(BookmarkList.bookmarkedIds(event(emptyList(), kind = NostrKinds.SHORT_TEXT_NOTE)).isEmpty())
    }

    @Test
    fun newestHeadWins() {
        val older = event(listOf(listOf("d", ""), listOf("e", a)), createdAt = 1_000)
        val newer = event(listOf(listOf("d", ""), listOf("e", b)), createdAt = 2_000)
        assertEquals(2_000, BookmarkList.newest(listOf(older, newer))?.createdAt)
    }
}
