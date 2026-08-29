package space.bitos.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-007 remix attribution rules (legacy web `src/lib/meme/remix.ts`
 * parity): wire parse/compose, advisory license gate, bounded credit and
 * the seed/derived tag merge used by both native composers.
 */
class RemixTest {

    @Test
    fun parsesRemixTagWithRelayHintsAndAttributionPubkey() {
        val source = RemixRules.sourceOf(
            listOf(
                listOf("license", "CC-BY-4.0"),
                listOf("remix", "ab".repeat(32), "wss://a.example", "wss://b.example", "wss://c.example", "wss://d.example"),
                listOf("p", "cd".repeat(32)),
            ),
        )
        assertEquals("ab".repeat(32), source?.eventId)
        assertEquals("cd".repeat(32), source?.pubkey)
        // Relay hints cap at 3 like the web studio.
        assertEquals(listOf("wss://a.example", "wss://b.example", "wss://c.example"), source?.relays)
    }

    @Test
    fun parsesGraphEdgeFormAndRejectsMalformedTags() {
        val graph = RemixRules.sourceOf(
            listOf(listOf("bitz:edge", "remix", "event:" + "11".repeat(32), "1")),
        )
        assertEquals("11".repeat(32), graph?.eventId)
        // Not a remix: original work.
        assertNull(RemixRules.sourceOf(listOf(listOf("p", "aa".repeat(32)))))
        // Malformed marker (missing id) yields nothing.
        assertNull(RemixRules.sourceOf(listOf(listOf("remix"))))
    }

    @Test
    fun composesWireTagsForAPublish() {
        val tags = RemixRules.tagsFor("ab".repeat(32), "cd".repeat(32), listOf("wss://x.example"))
        assertEquals(listOf("remix", "ab".repeat(32), "wss://x.example"), tags[0])
        assertEquals(listOf("p", "cd".repeat(32)), tags[1])
        // Author-less sources still carry the marker.
        assertEquals(1, RemixRules.tagsFor("ab".repeat(32), null).size)
    }

    @Test
    fun attributionCreditIsBounded() {
        val short = RemixRules.attributionTag("satoshi")
        assertEquals("attribution", short[0])
        assertEquals("remix of satoshi", short[1])
        val long = RemixRules.attributionTag("x".repeat(500))
        assertTrue(long[1].length <= RemixRules.ATTRIBUTION_MAX)
    }

    @Test
    fun licensesAreAdvisoryNeverHidden() {
        // Restrictive codes ask; everything else (incl. unknown) remixes.
        assertTrue(RemixRules.requiresAsk("bitz/all-reserved"))
        assertTrue(RemixRules.requiresAsk("bitz/source-permission"))
        assertFalse(RemixRules.requiresAsk("CC0-1.0"))
        assertFalse(RemixRules.requiresAsk("CC-BY-4.0"))
        assertFalse(RemixRules.requiresAsk("some-future-code"))
        assertFalse(RemixRules.requiresAsk(null))
        // licenseOf reads the tag; absent = permissive.
        assertEquals("CC-BY-NC-4.0", RemixRules.licenseOf(listOf(listOf("license", "CC-BY-NC-4.0"))))
        assertNull(RemixRules.licenseOf(emptyList()))
    }

    @Test
    fun mergeDedupesSeedTagsAgainstDerivedTags() {
        val seed = listOf(
            listOf("remix", "ab".repeat(32)),
            listOf("p", "cd".repeat(32)),
        )
        val derived = listOf(
            listOf("p", "cd".repeat(32)), // mention-derived duplicate of the attribution
            listOf("t", "bitcoin"),
        )
        val merged = RemixRules.mergeTags(seed, derived)
        assertEquals(3, merged.size)
        assertEquals(seed, merged.take(2)) // base-first order is stable
    }

    @Test
    fun mergeTagsJsonRoundTripsAndFallsBackOnCorruptInput() {
        val base = """[["remix","${"ab".repeat(32)}"],["p","${"cd".repeat(32)}"]]"""
        val derived = """[["t","bitcoin"]]"""
        val merged = RemixRules.mergeTagsJson(base, derived)
        assertTrue(merged.contains("\"remix\""))
        assertTrue(merged.contains("\"bitcoin\""))
        // Corrupt base falls back to the derived tags alone.
        assertEquals(derived, RemixRules.mergeTagsJson("not json", derived))
    }

    @Test
    fun feedNoteProjectsRemixAndLicenseFields() {
        val event = space.bitos.core.model.NostrEvent(
            id = space.bitos.core.model.EventId.parse("11".repeat(32))!!,
            pubkey = space.bitos.core.model.Pubkey.parse("22".repeat(32))!!,
            createdAt = 1_710_000_000,
            kind = space.bitos.core.model.NostrKinds.VIDEO,
            tags = listOf(
                listOf("remix", "33".repeat(32)),
                listOf("p", "44".repeat(32)),
                listOf("license", "bitz/all-reserved"),
            ),
            content = "remixed clip",
            signature = null,
            receivedFromRelay = null,
        )
        val note = FeedNote.from(event)
        assertEquals("33".repeat(32), note.remixOfEventId)
        assertEquals("44".repeat(32), note.remixOfPubkey)
        assertEquals("bitz/all-reserved", note.license)
        // Originals project nulls.
        val original = FeedNote.from(event.copy(tags = emptyList()))
        assertNull(original.remixOfEventId)
        assertNull(original.license)
    }

    // MARK: - Chain walk (web remixChainOf parity)

    private fun idOf(char: Char) = char.toString().repeat(64)
    private fun tagsRemixOf(char: Char) = listOf(listOf("remix", idOf(char)))

    /** Unique hex64 id per index (first two hex digits vary). */
    private fun hexId(index: Int): String {
        val digits = "0123456789abcdef"
        val head = digits[index % 16].toString() + digits[(index / 16) % 16]
        return head.padEnd(64, 'a')
    }

    @Test
    fun chainWalksTheFullAncestryWithDepths() = kotlinx.coroutines.runBlocking {
        // root -> a -> b -> c (original)
        val tags = mapOf(idOf('a') to tagsRemixOf('b'), idOf('b') to tagsRemixOf('c'), idOf('c') to emptyList())
        val outcome = RemixChain.walk(idOf('r'), RemixRules.Source(idOf('a'), null, emptyList())) { id -> tags[id] }
        val steps = (outcome as RemixChain.Outcome.Completed).steps
        assertEquals(listOf('a', 'b', 'c'), steps.map { it.eventId.first() })
        assertEquals(listOf(0, 1, 2), steps.map { it.depth })
        assertFalse(outcome.truncated)
    }

    @Test
    fun chainMissingParentIsTheNaturalEnd() = kotlinx.coroutines.runBlocking {
        // 'a' exists but its parent was pruned: lookup returns null.
        val outcome = RemixChain.walk(idOf('r'), RemixRules.Source(idOf('a'), null, emptyList())) { id ->
            if (id == idOf('a')) tagsRemixOf('x') else null
        }
        val completed = outcome as RemixChain.Outcome.Completed
        assertEquals(1, completed.steps.size)
        assertFalse(completed.truncated)
    }

    @Test
    fun chainDetectsCyclesIncludingSelfLoops() = kotlinx.coroutines.runBlocking {
        // a -> b -> a loops.
        val tags = mapOf(idOf('a') to tagsRemixOf('b'), idOf('b') to tagsRemixOf('a'))
        val outcome = RemixChain.walk(idOf('5'), RemixRules.Source(idOf('a'), null, emptyList())) { id -> tags[id] }
        assertEquals(RemixChain.Outcome.Cycle, outcome)
        // Source pointing back at the tapped note is a self-loop.
        val self = RemixChain.walk(idOf('5'), RemixRules.Source(idOf('5'), null, emptyList())) { null }
        assertEquals(RemixChain.Outcome.Cycle, self)
    }

    @Test
    fun chainTruncatesAtTheDepthCapAndRejectsNonHexIds() = kotlinx.coroutines.runBlocking {
        // Hostile non-hex id ends the walk before any lookup (never reaches
        // a relay REQ).
        val hostile = RemixChain.walk(idOf('r'), RemixRules.Source("not-hex-at-all", null, emptyList())) { fail("lookup must not run") }
        assertTrue((hostile as RemixChain.Outcome.Completed).steps.isEmpty())
        // A 40-link hex chain truncates at the 32 cap.
        val tags = (0..40).associate { hexId(it) to listOf(listOf("remix", hexId(it + 1))) }
        val capped = RemixChain.walk(idOf('r'), RemixRules.Source(hexId(0), null, emptyList())) { id -> tags[id] }
        val completed = capped as RemixChain.Outcome.Completed
        assertEquals(RemixChain.MAX_DEPTH, completed.steps.size)
        assertTrue(completed.truncated)
    }
}
