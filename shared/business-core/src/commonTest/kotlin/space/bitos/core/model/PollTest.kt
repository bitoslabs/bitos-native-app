package space.bitos.core.model

import space.bitos.core.identity.DeterministicTestSigner
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-008 poll wire contract: kind-1 + `poll_option` tags (legacy Flutter
 * `PollComposer` / web `feed.postPoll` parity) — bounds at compose,
 * tolerant-but-bounded parse, composer round-trip.
 */
class PollTest {

    private fun event(tags: List<List<String>>, content: String = "Best chain?", kind: Int = 1) = NostrEvent(
        id = EventId.parse("11".repeat(32))!!,
        pubkey = Pubkey.parse("aa".repeat(32))!!,
        createdAt = 1_000,
        kind = kind,
        tags = tags,
        content = content,
        signature = null,
        receivedFromRelay = null,
    )

    private fun option(index: Int, label: String) = listOf(PollContract.TAG, index.toString(), label)

    @Test
    fun parsesQuestionAndSortedOptions() {
        val poll = PollContract.poll(
            event(
                listOf(
                    option(1, "Lightning"),
                    option(0, " Base  "),
                    listOf("t", "bitcoin"),
                    option(2, "Liquid"),
                ),
            ),
        )!!
        assertEquals("Best chain?", poll.question)
        assertEquals(listOf(0, 1, 2), poll.options.map { it.index })
        assertEquals(listOf("Base", "Lightning", "Liquid"), poll.options.map { it.label })
    }

    @Test
    fun fewerThanTwoUsableOptionsIsNotAPoll() {
        assertNull(PollContract.poll(event(listOf(option(0, "only")))))
        assertNull(PollContract.poll(event(emptyList(), content = "plain note")))
        assertNull(PollContract.poll(event(listOf(option(0, "a"), option(1, "b")), kind = 22)))
        // Non-numeric index / empty label drop out.
        assertNull(PollContract.poll(event(listOf(listOf(PollContract.TAG, "x", "a"), option(1, "b")))))
    }

    @Test
    fun duplicatesKeepTheFirstIndexAndDisplayIsBounded() {
        val many = (0..20).map { option(it, "o$it") }
        val poll = PollContract.poll(event(many))!!
        assertEquals(PollContract.DISPLAY_MAX, poll.options.size)
        // Same index twice: first label wins.
        val dup = PollContract.poll(event(listOf(option(0, "first"), option(0, "second"), option(1, "x"))))!!
        assertEquals("first", dup.options[0].label)
    }

    @Test
    fun pollTagsValidateTheLegacyBounds() {
        assertNull(PollContract.pollTags("   ", listOf("a", "b"))) // blank question
        assertNull(PollContract.pollTags("q", listOf("only"))) // < 2
        assertNull(PollContract.pollTags("q", (1..7).map { "o$it" })) // > 6
        assertNull(PollContract.pollTags("q", listOf("a", "x".repeat(PollContract.MAX_OPTION + 1)))) // option too long
        assertNull(PollContract.pollTags("x".repeat(PollContract.MAX_QUESTION + 1), listOf("a", "b"))) // question too long
        val tags = PollContract.pollTags("  Best chain? #Bitcoin  ", listOf(" Base ", "", "Lightning"))!!
        assertEquals(
            listOf(
                listOf("poll_option", "0", "Base"),
                listOf("poll_option", "1", "Lightning"),
            ),
            tags,
        )
    }

    @Test
    fun composerRoundTripsThroughTheParser() {
        val signer = DeterministicTestSigner("0".repeat(63) + "1", Sha256EventHasher)
        val composer = NoteComposer(Sha256EventHasher, { 1_700_000_000L })
        val unsigned = composer.composePoll(signer.publicKeyHex(), "Best chain? #bitcoin", listOf("Base", "Lightning"))!!
        val event = NostrEvent(
            id = EventId.parse(unsigned.idHex)!!,
            pubkey = Pubkey.parse(unsigned.pubkeyHex)!!,
            createdAt = unsigned.createdAtSeconds,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            signature = null,
            receivedFromRelay = null,
        )
        val poll = PollContract.poll(event)!!
        assertEquals("Best chain? #bitcoin", poll.question)
        assertEquals(listOf("Base", "Lightning"), poll.options.map { it.label })
        // Hashtag from the question rides along as a t-tag.
        assertTrue(event.tags.any { it == listOf("t", "bitcoin") })
        assertTrue(event.tags.count { it.first() == PollContract.TAG } == 2)
    }
}
