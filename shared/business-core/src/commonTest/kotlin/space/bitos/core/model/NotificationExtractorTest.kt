package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationExtractorTest {

    private val me = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val other = "e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301"
    private val targetId = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"

    private fun event(kind: Int, tags: List<List<String>>, content: String = "", pubkey: String = other, createdAt: Long = 1_000) = NostrEvent(
        id = EventId.parse("11".repeat(32))!!,
        pubkey = Pubkey.parse(pubkey)!!,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        signature = null,
        receivedFromRelay = null,
    )

    @Test
    fun extractsReplyMentionReactionRepost() {
        // Marker-tagged thread → reply on the reply marker id.
        val reply = NotificationExtractor.extract(
            event(
                1,
                listOf(
                    listOf("e", "20cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a", "", "root"),
                    listOf("e", targetId, "", "reply"),
                    listOf("p", me),
                ),
                "nice post",
            ),
            me,
        )!!
        assertEquals(NotificationKind.REPLY, reply.kind)
        assertEquals(targetId, reply.targetEventId)
        assertEquals("nice post", reply.summary)

        // Plain-e note (no markers) is a standalone mention quoting that note
        // (web `mention` parity: open the quoted origin, not the mention).
        val quoteMention = NotificationExtractor.extract(
            event(1, listOf(listOf("e", targetId), listOf("p", me)), "look at this"), me,
        )!!
        assertEquals(NotificationKind.MENTION, quoteMention.kind)
        assertEquals(targetId, quoteMention.targetEventId)

        val mention = NotificationExtractor.extract(
            event(1, listOf(listOf("p", me)), "hey ${"2d".repeat(8)} check this"), me,
        )!!
        assertEquals(NotificationKind.MENTION, mention.kind)
        assertNull(mention.targetEventId)

        val reaction = NotificationExtractor.extract(
            event(7, listOf(listOf("e", targetId), listOf("p", me)), "+"), me,
        )!!
        assertEquals(NotificationKind.REACTION, reaction.kind)
        assertEquals(targetId, reaction.targetEventId)

        // Negative reactions never notify (web `isPositiveReaction` parity).
        assertNull(NotificationExtractor.extract(
            event(7, listOf(listOf("e", targetId), listOf("p", me)), "-"), me,
        ))

        val repost = NotificationExtractor.extract(
            event(6, listOf(listOf("e", targetId), listOf("p", me)), ""), me,
        )!!
        assertEquals(NotificationKind.REPOST, repost.kind)
        assertEquals(targetId, repost.targetEventId)
    }

    @Test
    fun extractsGenericReposts() {
        // NIP-18 kind 16 with an e tag → repost on that id.
        val tagged = NotificationExtractor.extract(
            event(16, listOf(listOf("e", targetId), listOf("p", me)), "{}"), me,
        )!!
        assertEquals(NotificationKind.REPOST, tagged.kind)
        assertEquals(targetId, tagged.targetEventId)

        // Kind 16 without e tags embeds the target event JSON.
        val embedded = NotificationExtractor.extract(
            event(16, listOf(listOf("p", me)), "{\"id\":\"$targetId\",\"kind\":1,\"pubkey\":\"$other\"}"), me,
        )!!
        assertEquals(NotificationKind.REPOST, embedded.kind)
        assertEquals(targetId, embedded.targetEventId)
    }

    @Test
    fun zapTargetsPreferSecondETag() {
        // Receipts carry the request's own `e` first; the zapped note second.
        val otherTarget = "30cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        val zap = NotificationExtractor.extract(
            event(9735, listOf(listOf("p", me), listOf("e", targetId), listOf("e", otherTarget)), ""), me,
        )!!
        assertEquals(NotificationKind.ZAP, zap.kind)
        assertEquals(otherTarget, zap.targetEventId)

        // Single-e receipts still resolve.
        val single = NotificationExtractor.extract(
            event(9735, listOf(listOf("p", me), listOf("e", targetId)), ""), me,
        )!!
        assertEquals(targetId, single.targetEventId)
    }

    @Test
    fun extractsZapReceiptsTargetingAccount() {
        val zap = NotificationExtractor.extract(
            event(9735, listOf(listOf("p", me), listOf("e", targetId)), ""), me,
        )!!
        assertEquals(NotificationKind.ZAP, zap.kind)

        // Someone else's zap is not our notification.
        assertNull(NotificationExtractor.extract(
            event(9735, listOf(listOf("p", other), listOf("e", targetId)), ""), me,
        ))
    }

    @Test
    fun extractsZapReceiptsWithAmountAndVerifiedSender() = kotlinx.coroutines.runBlocking {
        val signer = space.bitos.core.identity.DeterministicTestSigner("06".repeat(32))
        val payerKey = signer.publicKeyHex()
        val composer = space.bitos.core.publish.NoteComposer(
            hasher = space.bitos.core.nostr.Sha256EventHasher,
            clock = { 1_700_000_000 },
        )
        val unsigned = composer.composeZapRequest(
            recipientPubkey = me,
            amountMillisats = 21_000,
            relays = listOf("wss://relay.damus.io"),
            lnurlHint = "user@wallet.example",
            comment = "nice",
            authorPubkey = payerKey,
        )!!
        val signature = signer.sign(unsigned.messageBytes())!!
        val requestJson = composer.publishMessage(unsigned, signature)!!
            .substringAfter(',').removeSuffix("]")

        val receipt = NotificationExtractor.extract(
            event(
                9735,
                listOf(
                    listOf("p", me),
                    listOf("e", targetId),
                    listOf("bolt11", "lnbc210n1p3xq8z9abcdefghijklmnop"),
                    listOf("description", requestJson),
                ),
            ),
            me,
        )!!
        assertEquals(NotificationKind.ZAP, receipt.kind)
        assertEquals(payerKey, receipt.authorPubkey)
        assertEquals(21_000L, receipt.amountMsat)
        assertEquals("⚡ 21 sats", receipt.summary)

        // No bolt11 → unquantified zap; no description → unattributed.
        val bare = NotificationExtractor.extract(
            event(9735, listOf(listOf("p", me), listOf("e", targetId))), me,
        )!!
        assertNull(bare.amountMsat)
        assertEquals("", bare.authorPubkey)
        assertEquals("⚡ zap received", bare.summary)

        // Someone else's zap is not our notification.
        assertNull(NotificationExtractor.extract(
            event(9735, listOf(listOf("p", other), listOf("e", targetId))), me,
        ))
    }

    @Test
    fun extractsFollowNotificationsFromContactLists() {
        val follow = NotificationExtractor.extract(
            event(3, listOf(listOf("p", me), listOf("p", other))), me,
        )!!
        assertEquals(NotificationKind.FOLLOW, follow.kind)
        assertNull(follow.targetEventId)

        // Republishes pass extraction; the repositories dedupe per author
        // (NotificationFilters.shouldKeep).
        val republish = NotificationExtractor.extract(
            event(3, listOf(listOf("p", me)), createdAt = 2_000), me,
        )!!
        assertEquals(NotificationKind.FOLLOW, republish.kind)
    }

    @Test
    fun ignoresOwnEventsAndUntargeted() {
        assertNull(NotificationExtractor.extract(
            event(1, listOf(listOf("p", me)), "self", pubkey = me), me,
        ))
        assertNull(NotificationExtractor.extract(
            event(1, listOf(listOf("p", other)), "not about me"), me,
        ))
        assertNull(NotificationExtractor.extract(
            event(0, emptyList(), "{}"), me,
        ))
    }

    @Test
    fun summaryIsBounded() {
        val long = "x".repeat(200)
        val mention = NotificationExtractor.extract(
            event(1, listOf(listOf("p", me)), long), me,
        )!!
        assertEquals(120, mention.summary.length)
        assertEquals("…", mention.summary.last().toString())
    }

    @Test
    fun summaryStripsMediaLinksSoRowsNeverShowRawUrls() {
        // Media-only note: the platform renders tiles; the summary is empty.
        val mediaOnly = NotificationExtractor.extract(
            event(1, listOf(listOf("p", me)), "https://xxx.com/img1.gif https://yyy.com/clip.mp4"), me,
        )!!
        assertEquals("", mediaOnly.summary)

        // Mixed content: text survives, the media link is dropped.
        val mixed = NotificationExtractor.extract(
            event(1, listOf(listOf("p", me)), "look at this https://xxx.com/pic.png lol"), me,
        )!!
        assertEquals("look at this lol", mixed.summary)

        // Non-media links are stripped too (same rule as origin excerpts).
        val article = NotificationExtractor.extract(
            event(1, listOf(listOf("p", me)), "read https://example.com/post/42 now"), me,
        )!!
        assertEquals("read now", article.summary)
    }

    @Test
    fun summaryStripsNostrEntitiesAndKeepsHashtags() {
        val npub = space.bitos.core.identity.NostrKeyCodec.npub(me)!!
        val mention = NotificationExtractor.extract(
            event(1, listOf(listOf("p", me)), "hi nostr:$npub nice #nostr post"), me,
        )!!
        assertEquals("hi nice #nostr post", mention.summary)
    }

    @Test
    fun mediaOnlySummaryStillFallsBackForReactions() {
        // Reaction content is an emoji payload; sanitization keeps it intact.
        val reaction = NotificationExtractor.extract(
            event(7, listOf(listOf("e", targetId), listOf("p", me)), "🔥"), me,
        )!!
        assertEquals("🔥", reaction.summary)
    }
}
