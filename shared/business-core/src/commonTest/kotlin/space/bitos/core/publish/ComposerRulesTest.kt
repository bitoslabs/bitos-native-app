package space.bitos.core.publish

import space.bitos.core.model.ProfileMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-008 composer rules (legacy Flutter/web Composer parity): counter
 * targets, toolbar inserts, mention autocomplete + rewrite, content join
 * and tag derivation.
 */
class ComposerRulesTest {

    // ── Counter ─────────────────────────────────────────────────────

    @Test
    fun counterTargetsFourThousandUnderTheSoftLimit() {
        val state = ComposerRules.counterState(120)
        assertEquals("120 / 4,000", state.label)
        assertEquals(0.03f, state.ratio)
        assertFalse(state.near)
        assertFalse(state.over)
    }

    @Test
    fun counterSwitchesToSixteenThousandPastTheSoftLimit() {
        val past = ComposerRules.counterState(4_500)
        assertEquals("4,500 / 16,000", past.label)
        assertTrue(past.remaining < 0 || past.remaining == 11_500)
        val near = ComposerRules.counterState(3_900)
        assertTrue(near.near)
        assertFalse(near.over)
        val over = ComposerRules.counterState(16_001)
        assertTrue(over.over)
        assertTrue(over.ratio == 1f)
    }

    // ── Toolbar inserts ────────────────────────────────────────────

    @Test
    fun hashtagInsertRespectsTheLeadingSpaceRule() {
        assertEquals("gm #" to 4, ComposerRules.insertHashtag("gm ", 3))
        assertEquals("gm #" to 4, ComposerRules.insertHashtag("gm", 2))
        assertEquals("#" to 1, ComposerRules.insertHashtag("", 0))
        // Cursor-preserving: insertion in the middle.
        assertEquals("a #b" to 3, ComposerRules.insertHashtag("ab", 1))
    }

    @Test
    fun emojiInsertAddsASpaceUnlessAtStartOrNewline() {
        assertEquals("gm ⚡" to 4, ComposerRules.insertEmoji("gm", 2, "⚡"))
        assertEquals("⚡" to 1, ComposerRules.insertEmoji("", 0, "⚡"))
        assertEquals("a\n⚡" to 3, ComposerRules.insertEmoji("a\n", 2, "⚡"))
    }

    // ── Mention autocomplete ───────────────────────────────────────

    @Test
    fun mentionQueryDetectsTrailingAtWord() {
        assertEquals("sat", ComposerRules.mentionQueryAt("hello @sat", 10))
        assertEquals("", ComposerRules.mentionQueryAt("hello @", 7))
        assertNull(ComposerRules.mentionQueryAt("hello bob", 9))
        assertNull(ComposerRules.mentionQueryAt("no match here", 5))
    }

    private fun profile(name: String, pubkey: String, displayName: String? = null) = ProfileMetadata(
        pubkey = space.bitos.core.model.Pubkey.parse(pubkey)!!,
        name = name,
        displayName = displayName,
        about = null, picture = null, nip05 = null, lud16 = null,
    )

    @Test
    fun mentionSuggestionsFilterByNameAndCap() {
        val profiles = listOf(
            profile("satoshi", "aa".repeat(32), displayName = "Satoshi Nakamoto"),
            profile("alice", "bb".repeat(32)),
            profile("sandra", "cc".repeat(32)),
        ) + (1..6).map { index -> profile("sat$index", index.toString(16).padStart(63, '0') + "a") }

        val suggestions = ComposerRules.mentionSuggestions("sat", profiles)
        assertTrue(suggestions.size <= ComposerRules.MENTION_SUGGESTION_CAP)
        assertTrue(suggestions.all { it.name.lowercase().contains("sat") })
        // Display name wins over username when present.
        assertEquals("Satoshi Nakamoto", suggestions.first().name)
        assertTrue(suggestions.none { it.name.isBlank() })
        // Empty query lists everyone (capped).
        assertTrue(ComposerRules.mentionSuggestions("", profiles).isNotEmpty())
    }

    @Test
    fun rewriteMentionsConvertsTrackedNamesToNostrEntities() {
        val npub = space.bitos.core.identity.NostrKeyCodec.npub("aa".repeat(32))!!
        val rewritten = ComposerRules.rewriteMentions(
            "gm @Satoshi Nakamoto and @Satoshi Nakamoto again",
            listOf("Satoshi Nakamoto" to npub),
        )
        assertEquals("gm nostr:$npub and nostr:$npub again", rewritten)
        // Untouched names stay as written.
        assertEquals("hi @bob", ComposerRules.rewriteMentions("hi @bob", listOf("alice" to npub)))
    }

    // ── Content assembly + tags ────────────────────────────────────

    @Test
    fun composeContentJoinsMediaUrlsPerLine() {
        assertEquals(
            "gm\nhttps://a.example/1.png\nhttps://a.example/2.jpg",
            ComposerRules.composeContent("  gm  ", listOf("https://a.example/1.png", "https://a.example/2.jpg")),
        )
        assertEquals("https://only.example/x.png", ComposerRules.composeContent("", listOf("https://only.example/x.png")))
    }

    @Test
    fun deriveTagsProducesDistinctHashtagsEntitiesAndContentWarning() {
        val pubkeyHex = "aa".repeat(32)
        val npub = space.bitos.core.identity.NostrKeyCodec.npub(pubkeyHex)!!
        val content = "gm #Bitcoin #bitcoin nostr:$npub"
        val tags = ComposerRules.deriveTags(content, "nudity")
        assertEquals(listOf("t", "bitcoin"), tags[0]) // distinct, lowercased
        val pTag = tags.first { it.first() == "p" }
        assertEquals(pubkeyHex, pTag[1])
        assertEquals(listOf("content-warning", "nudity"), tags.last())
        // Without a warning the tag is absent.
        assertTrue(ComposerRules.deriveTags("plain").none { it.first() == "content-warning" })
        assertTrue(ComposerRules.deriveTags("plain").isEmpty())
    }
}
