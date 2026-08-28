package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-012 blocked-author filtering: kind-10004 `p`-tag parsing with hex
 * validation and a bounded set.
 */
class BlockListTest {

    private fun event(tags: List<List<String>>, kind: Int = BlockList.KIND, createdAt: Long = 1_000) = NostrEvent(
        id = EventId.parse("11".repeat(32))!!,
        pubkey = Pubkey.parse("2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001")!!,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = "",
        signature = null,
        receivedFromRelay = null,
    )

    @Test
    fun parsesHexPTagsIntoTheBlockedSet() {
        val a = "aa".repeat(32)
        val b = "bb".repeat(32)
        val blocked = BlockList.blockedPubkeys(event(listOf(listOf("p", a), listOf("e", "ignored"), listOf("p", b), listOf("p", a))))
        assertNotNull(blocked)
        assertEquals(setOf(a, b), blocked)
    }

    @Test
    fun dropsNonHexOrShortPTags() {
        val good = "cc".repeat(32)
        val blocked = BlockList.blockedPubkeys(
            event(listOf(listOf("p", "not-hex"), listOf("p", "abcd"), listOf("p", good), listOf("p", good.uppercase()))),
        )
        assertNotNull(blocked)
        // Lowercase hex survives; uppercase-hex tags are dropped (canonical form).
        assertEquals(setOf(good), blocked)
    }

    @Test
    fun otherKindsReturnNullSoThePreviousHeadSurvives() {
        assertNull(BlockList.blockedPubkeys(event(listOf(listOf("p", "aa".repeat(32))), kind = 1)))
    }

    @Test
    fun setIsBounded() {
        val tags = (1..600).map { index -> listOf("p", index.toString(16).padStart(64, '0')) }
        val blocked = BlockList.blockedPubkeys(event(tags))
        assertNotNull(blocked)
        assertEquals(BlockList.MAX_BLOCKS, blocked.size)
    }

    private fun eventWithId(idHex: String, createdAt: Long) = NostrEvent(
        id = EventId.parse(idHex)!!,
        pubkey = Pubkey.parse("2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001")!!,
        createdAt = createdAt,
        kind = BlockList.KIND,
        tags = listOf(listOf("p", "b".repeat(64))),
        content = "",
        signature = null,
        receivedFromRelay = null,
    )

    @Test
    fun newestSelectsTheLatestHeadWithDeterministicTies() {
        val older = eventWithId("11".repeat(32), createdAt = 100)
        val newer = eventWithId("22".repeat(32), createdAt = 200)
        assertEquals(newer.id.value, BlockList.newest(listOf(older, newer))!!.id.value)
        // Same createdAt → higher event id wins deterministically.
        val a = eventWithId("aa".repeat(32), createdAt = 100)
        val b = eventWithId("bb".repeat(32), createdAt = 100)
        assertEquals(b.id.value, BlockList.newest(listOf(a, b))!!.id.value)
        assertNull(BlockList.newest(emptyList()))
    }

    @Test
    fun blockListRequestValidatesAuthorAndEncodes() {
        val codec = space.bitos.core.nostr.NostrEventCodec
        val req = codec.encodeBlockListRequest("bitos-blocks", "a".repeat(64))
        kotlin.test.assertTrue(req!!.contains("\"kinds\":[10004]"))
        kotlin.test.assertTrue(req.contains("\"limit\":1"))
        kotlin.test.assertEquals(null, codec.encodeBlockListRequest("bitos-blocks", "zzz"))
        kotlin.test.assertEquals(null, codec.encodeBlockListRequest("bitos-blocks", "a".repeat(63)))
    }

    @Test
    fun composeBlockListBoundedAndValidated() {
        val composer = space.bitos.core.publish.NoteComposer(clock = { 1 })
        val author = "a".repeat(64)
        val note = composer.composeBlockList(author, listOf("b".repeat(64), "b".repeat(64), "nothex"))
        kotlin.test.assertEquals(space.bitos.core.model.BlockList.KIND, note!!.kind)
        kotlin.test.assertEquals(listOf(listOf("p", "b".repeat(64))), note.tags)
        kotlin.test.assertEquals(null, composer.composeBlockList(author, emptyList()))
        kotlin.test.assertEquals(null, composer.composeBlockList("zz", listOf("b".repeat(64))))
        // Bounded: >MAX_BLOCKS input truncates.
        val many = (1..600).map { it.toString(16).padStart(64, '0') }
        val capped = composer.composeBlockList(author, many)
        kotlin.test.assertEquals(space.bitos.core.model.BlockList.MAX_BLOCKS, capped!!.tags.size)
    }

}
