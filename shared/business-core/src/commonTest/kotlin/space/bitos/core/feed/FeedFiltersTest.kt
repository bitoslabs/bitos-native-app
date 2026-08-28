package space.bitos.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * APP-004 content-filter contract (unified feature spec §3.4): the six
 * legacy feed windows as pure predicates over verified FeedNotes.
 */
class FeedFiltersTest {

    private val me = "aa".repeat(32)
    private val other = "bb".repeat(32)
    private val liked = setOf("11".repeat(8), "22".repeat(8))

    private fun note(
        id: String = "0".repeat(64),
        pubkey: String = other,
        replyTo: String? = null,
        mediaUrls: List<String> = emptyList(),
        video: space.bitos.core.model.MediaMetadata? = null,
        protocol: Boolean = false,
    ) = FeedNote(
        id = id,
        pubkey = pubkey,
        content = "content",
        createdAt = 1_700_000_000L,
        kind = 1,
        replyTo = replyTo,
        hashtags = emptyList(),
        mentions = emptyList(),
        mediaUrls = mediaUrls,
        isProtocolPayload = protocol,
        video = video,
    )

    @Test
    fun allHidesProtocolPayloads() {
        assertTrue(FeedFilters.passes(note(), FeedFilter.ALL, null))
        assertFalse(FeedFilters.passes(note(protocol = true), FeedFilter.ALL, null))
    }

    @Test
    fun originalsExcludesReplies() {
        assertTrue(FeedFilters.passes(note(), FeedFilter.ORIGINALS, null))
        assertFalse(FeedFilters.passes(note(replyTo = "e".repeat(64)), FeedFilter.ORIGINALS, null))
        assertFalse(FeedFilters.passes(note(protocol = true), FeedFilter.ORIGINALS, null))
    }

    @Test
    fun repliesMatchesOnlyReplyNotes() {
        val reply = note(replyTo = "e".repeat(64))
        assertTrue(FeedFilters.passes(reply, FeedFilter.REPLIES, null))
        assertFalse(FeedFilters.passes(note(), FeedFilter.REPLIES, null))
    }

    @Test
    fun mediaMatchesVideoOrImageUrls() {
        assertTrue(FeedFilters.passes(note(mediaUrls = listOf("https://x.example/a.png")), FeedFilter.MEDIA, null))
        assertTrue(
            FeedFilters.passes(
                note(video = space.bitos.core.model.MediaMetadata("https://x.example/v.mp4", "video/mp4", null, null, null)),
                FeedFilter.MEDIA, null,
            ),
        )
        assertFalse(FeedFilters.passes(note(), FeedFilter.MEDIA, null))
        assertFalse(FeedFilters.passes(note(protocol = true, mediaUrls = listOf("https://x.example/a.png")), FeedFilter.MEDIA, null))
    }

    @Test
    fun likedMatchesLocalLikeSet() {
        val likedNote = note(id = "11".repeat(8))
        assertTrue(FeedFilters.passes(likedNote, FeedFilter.LIKED, null, liked))
        assertFalse(FeedFilters.passes(note(), FeedFilter.LIKED, null, liked))
    }

    @Test
    fun mineRequiresMatchingAccount() {
        assertTrue(FeedFilters.passes(note(pubkey = me), FeedFilter.MINE, me))
        assertFalse(FeedFilters.passes(note(pubkey = other), FeedFilter.MINE, me))
        // Signed out: MINE matches nothing.
        assertFalse(FeedFilters.passes(note(pubkey = me), FeedFilter.MINE, null))
    }

    @Test
    fun labelsAreStableForMenus() {
        assertEquals(listOf("All", "Original", "Replies", "Media", "Liked", "Mine"), FeedFilter.entries.map { it.label })
    }
}
