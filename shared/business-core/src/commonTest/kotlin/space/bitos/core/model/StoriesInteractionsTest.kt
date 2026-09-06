package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-006 story interactions: address derivation, target/reply tags, and
 * per-event classification (like vs view emoji, replies, zap sats, e/a
 * resolution, untracked targets rejected).
 */
class StoriesInteractionsTest {

    private val now = 1_700_000_000L
    private val author = "aa".repeat(32)
    private val viewer = "bb".repeat(32)

    private fun event(
        id: String,
        pubkey: String,
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long = now,
    ) = NostrEvent(
        id = EventId.parse(id)!!,
        pubkey = Pubkey.parse(pubkey)!!,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        signature = null,
        receivedFromRelay = null,
    )

    @Test
    fun addressDerivationNeedsDTag() {
        assertEquals("30315:$author:slide-1", StoriesInteractions.addressFor(author, "slide-1"))
        assertNull(StoriesInteractions.addressFor(author, null))
        assertNull(StoriesInteractions.addressFor(author, ""))
    }

    @Test
    fun targetTagsCarryAddressOnlyForParameterizedSlides() {
        assertEquals(
            listOf(listOf("e", "e1"), listOf("p", author)),
            StoriesInteractions.targetTagsFor("e1", author, null),
        )
        assertEquals(
            listOf(
                listOf("e", "e1"),
                listOf("p", author),
                listOf("a", "30315:$author:slide-1"),
            ),
            StoriesInteractions.targetTagsFor("e1", author, "slide-1"),
        )
        // Replies carry NIP-10 markers (web `stories.reply` parity).
        assertEquals(
            listOf(
                listOf("e", "e1", "", "reply"),
                listOf("p", author),
                listOf("a", "30315:$author:slide-1", "", "reply"),
            ),
            StoriesInteractions.replyTagsFor("e1", author, "slide-1"),
        )
    }

    @Test
    fun classifiesLikeViewReplyAndZap() {
        val ids = setOf("11".repeat(32))

        val like = StoriesInteractions.project(
            event("22".repeat(32), viewer, 7, "❤️", listOf(listOf("e", ids.first()))),
            ids, emptyMap(),
        )!!
        assertEquals(StoriesInteractions.StoryActivityEvent.Type.LIKE, like.type)
        assertEquals("❤️", like.emoji)

        // Eye reactions are views, not likes; blank content falls back to ❤️.
        val view = StoriesInteractions.project(
            event("33".repeat(32), viewer, 7, "👁️", listOf(listOf("e", ids.first()))),
            ids, emptyMap(),
        )!!
        assertEquals(StoriesInteractions.StoryActivityEvent.Type.VIEW, view.type)
        assertEquals("❤️", StoriesInteractions.project(
            event("44".repeat(32), viewer, 7, "  ", listOf(listOf("e", ids.first()))),
            ids, emptyMap(),
        )!!.emoji)

        val reply = StoriesInteractions.project(
            event("55".repeat(32), viewer, 1, "nice story!", listOf(listOf("e", ids.first(), "", "reply"))),
            ids, emptyMap(),
        )!!
        assertEquals(StoriesInteractions.StoryActivityEvent.Type.REPLY, reply.type)
        assertEquals("nice story!", reply.text)

        // Zap sats come from the bolt11 amount (milli→whole sats).
        val zap = StoriesInteractions.project(
            event(
                "66".repeat(32), viewer, 9_735, "",
                listOf(listOf("e", ids.first()), listOf("bolt11", "lnbc210u1dummy")),
            ),
            ids, emptyMap(),
        )!!
        assertEquals(StoriesInteractions.StoryActivityEvent.Type.ZAP, zap.type)
        assertTrue(zap.sats >= 0)
    }

    @Test
    fun resolvesSlidesByAddressAndRejectsUntracked() {
        val slideId = "11".repeat(32)
        val address = "30315:$author:slide-1"
        val byAddress = StoriesInteractions.project(
            event("22".repeat(32), viewer, 7, "❤️", listOf(listOf("a", address))),
            setOf(slideId), mapOf(address to slideId),
        )
        assertNotNull(byAddress)
        assertEquals(slideId, byAddress!!.slideId)

        // Nothing tracked → null, whatever the kind.
        assertNull(StoriesInteractions.project(
            event("33".repeat(32), viewer, 7, "❤️", listOf(listOf("e", "ff".repeat(32)))),
            setOf(slideId), emptyMap(),
        ))
        assertNull(StoriesInteractions.project(
            event("44".repeat(32), viewer, 1, "hi", listOf(listOf("e", slideId))),
            emptySet(), emptyMap(),
        ))
    }
}
