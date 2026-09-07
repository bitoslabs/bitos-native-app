package space.bitos.core.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bridge contract tests driven by `contracts/nostr/fixtures/verification-vectors.json`,
 * generated with the independent nostr-tools/@noble implementation (see
 * `scripts/generate-verification-vectors.mjs`). The key and vectors are
 * public test fixtures.
 */
class BusinessCoreBridgeTest {

    private val bridge = BusinessCoreBridge()

    @Test
    fun publicStoriesRequestIsBoundedAndDoesNotExposeAnAuthorScope() {
        val request = bridge.publicStoriesRequest("bitos-public-stories")
        assertTrue(request.contains("\"bitos-public-stories\""), request)
        assertTrue(request.contains("\"kinds\":[30315]"), request)
        assertTrue(request.contains("\"limit\":20"), request)
        assertFalse(request.contains("\"authors\""), request)
    }

    @Test
    fun decodesIdVerifiedSignedEvents() {
        val event = bridge.decodeEvent(VALID_TEXT_NOTE_MESSAGE, "wss://relay.damus.io")
        assertNotNull(event)
        assertEquals("10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a", event.id)
        assertEquals(1, event.kind)
        assertEquals(1_710_000_000L, event.createdAt)
        assertEquals("gm from BitOS", event.content)
        assertEquals("2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001", event.pubkey)
        assertEquals("wss://relay.damus.io", event.relayUrl)
        assertTrue(event.signature.isNotEmpty())
    }

    @Test
    fun rejectsUnsignedAndMalformedFrames() {
        val unsigned = """["EVENT","sub1",{"id":"${"0".repeat(64)}","pubkey":"${"aa".repeat(32)}","created_at":1,"kind":1,"tags":[],"content":"x"}]"""
        assertNull(bridge.decodeEvent(unsigned, "wss://relay.damus.io"))
        assertNull(bridge.decodeEvent("not json", "wss://relay.damus.io"))
        assertNull(bridge.decodeEvent("""["EVENT","sub1",{}]""", "wss://relay.damus.io"))
        assertNull(bridge.decodeEvent("""["NOTICE","x"]""", "wss://relay.damus.io"))
    }

    @Test
    fun eventJsonEmitsTheCanonicalSignedObject() {
        val event = bridge.decodeEvent(VALID_TEXT_NOTE_MESSAGE, "wss://relay.damus.io")
        assertNotNull(event)
        val json = bridge.eventJson(event)
        assertTrue(json.startsWith("{\"id\":\"${event.id}\",\"pubkey\":\"${event.pubkey}\""), json)
        assertTrue(json.contains("\"created_at\":1710000000,\"kind\":1,"), json)
        assertTrue(json.contains("\"tags\":[[\"t\",\"bitcoin\"]],\"content\":\"gm from BitOS\""), json)
        assertTrue(json.endsWith(",\"sig\":\"${event.signature}\"}"), json)
    }

    @Test
    fun repostInnerEventExposesTheEmbeddedOriginal() = kotlinx.coroutines.runBlocking {
        val composer = space.bitos.core.publish.NoteComposer(clock = { 1_710_000_000 })
        val signer = space.bitos.core.identity.DeterministicTestSigner(
            "4b1aa1a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d",
        )
        val author = signer.publicKeyHex()
        val original = composer.composeTextNote(author, "original content here")!!
        val originalFrame = composer.publishMessage(original, signer.sign(original.messageBytes())!!)!!
        val embedded = originalFrame.removePrefix("""["EVENT",""").removeSuffix("]")

        val tags = listOf(listOf("e", original.idHex), listOf("p", author))
        val repostId = space.bitos.core.nostr.NostrEventCodec.computeId(
            space.bitos.core.nostr.Sha256EventHasher, author, 1_710_000_100, 6, tags, embedded,
        )
        val unsigned = space.bitos.core.publish.UnsignedNote(repostId, author, 1_710_000_100, 6, tags, embedded)
        // publishMessage emits ["EVENT", {...}]; the relay gate wants the
        // full 3-element frame, so add a subscription id.
        val frame = composer.publishMessage(unsigned, signer.sign(unsigned.messageBytes())!!)!!
        val message = """["EVENT","sub1",""" + frame.removePrefix("""["EVENT",""")
        val repost = bridge.decodeEvent(message, "wss://relay.damus.io")
        assertNotNull(repost)
        val inner = bridge.repostInnerEvent(repost)
        assertNotNull(inner)
        assertEquals(original.idHex, inner.id)
        assertEquals("original content here", inner.content)
        // Non-repost frames never resolve.
        val note = bridge.decodeEvent(VALID_TEXT_NOTE_MESSAGE, "wss://relay.damus.io")
        assertNotNull(note)
        assertNull(bridge.repostInnerEvent(note))
    }

    // ── APP-011 DM presentation bridge contract ──────────────────

    private val me = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val peer = "aa".repeat(32)

    private fun dmMessage(id: String, author: String, at: Long, content: String = "hi"): Map<String, Any> =
        mapOf(
            "id" to id,
            "authorPubkey" to author,
            "peerPubkey" to peer,
            "content" to content,
            "createdAt" to at,
        )

    @Test
    fun dmUnreadCountCountsPeerMessagesPastTheCursor() {
        val messages = listOf(
            dmMessage("01", peer, 100),
            dmMessage("02", me, 150),
            dmMessage("03", peer, 300),
            dmMessage("04", peer, 400),
        )
        assertEquals(0, bridge.dmUnreadCount(messages, me, 500))
        assertEquals(1, bridge.dmUnreadCount(messages, me, 350))
        assertEquals(2, bridge.dmUnreadCount(messages, me, 200))
        assertEquals(3, bridge.dmUnreadCount(messages, me, 0))
        // My own messages are never unread, and empty input maps to 0.
        assertEquals(0, bridge.dmUnreadCount(emptyList(), me, 0))
    }

    @Test
    fun dmPreviewLineStaysGenericUntilOpened() {
        val messages = listOf(
            dmMessage("01", peer, 100),
            dmMessage("02", me, 150),
            dmMessage("03", peer, 300),
        )
        // Unread peer message → generic "New message", never plaintext.
        assertEquals("New message", bridge.dmPreviewLine(messages, me, 200))
        // Read everything → settled generic line.
        assertEquals("Encrypted · decrypt in app", bridge.dmPreviewLine(messages, me, 300))
        // Empty conversation → explicit placeholder.
        assertEquals("No messages", bridge.dmPreviewLine(emptyList(), me, 0))
    }

    @Test
    fun dmIsAcceptedAppliesDeclineWinsOverEverSentTo() {
        // Declined wins over everything (explicit user intent).
        assertFalse(bridge.dmIsAccepted(peer, everSentTo = listOf(peer), explicitlyAccepted = listOf(peer), explicitlyDeclined = listOf(peer)))
        // Explicit accept.
        assertTrue(bridge.dmIsAccepted(peer, everSentTo = emptyList(), explicitlyAccepted = listOf(peer), explicitlyDeclined = emptyList()))
        // I replied once → auto-accepted.
        assertTrue(bridge.dmIsAccepted(peer, everSentTo = listOf(peer), explicitlyAccepted = emptyList(), explicitlyDeclined = emptyList()))
        // Fresh stranger → request bucket.
        assertFalse(bridge.dmIsAccepted(peer, everSentTo = emptyList(), explicitlyAccepted = emptyList(), explicitlyDeclined = emptyList()))
    }

    @Test
    fun dmNextCursorNeverRewinds() {
        val messages = listOf(
            dmMessage("01", peer, 100),
            dmMessage("02", me, 500),
        )
        assertEquals(500, bridge.dmNextCursor(messages, currentCursor = 0))
        // An already-newer cursor must not move backward.
        assertEquals(900, bridge.dmNextCursor(messages, currentCursor = 900))
        // Empty conversation keeps the cursor untouched.
        assertEquals(42, bridge.dmNextCursor(emptyList(), currentCursor = 42))
    }

    @Test
    fun authorRequestPagesBackwardAndStaysBounded() {
        val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        // First page: separate profile (limit 1), deep media and shallow
        // text filters (web loadReels parity — Nostr limit is per relay per
        // filter, so splitting maximizes the renderable yield per request).
        val first = bridge.authorRequest("bitos-author", author)
        assertTrue(first.contains("\"kinds\":[0],\"authors\":[\"$author\"],\"limit\":1"), first)
        assertTrue(first.contains("\"kinds\":[20,21,22,34235,34236],\"authors\":[\"$author\"],\"limit\":60"), first)
        assertTrue(first.contains("\"kinds\":[1],\"authors\":[\"$author\"],\"limit\":150"), first)
        assertFalse(first.contains("\"until\""), first)
        // Follow-up page: notes only, `until` cursor on both filters.
        val page = bridge.authorRequest(
            "bitos-author-2", author,
            mediaLimit = 60, textLimit = 150, untilSeconds = 1_710_000_000,
        )
        assertFalse(page.contains("\"kinds\":[0]"), page)
        assertTrue(page.contains("\"kinds\":[20,21,22,34235,34236]"), page)
        assertTrue(page.contains("\"kinds\":[1]"), page)
        assertTrue(page.contains("\"until\":1710000000"), page)
        // Both limits coerce into the 1..500 window either way.
        assertTrue(
            bridge.authorRequest("s3", author, mediaLimit = 9_999, textLimit = 9_999)
                .contains("\"limit\":500"),
        )
        assertTrue(
            bridge.authorRequest("s4", author, mediaLimit = 0, textLimit = -5)
                .contains("\"limit\":1"),
        )
    }

    @Test
    fun deletionComposerBuildsBoundedKindFive() {
        val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        val target = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        assertNotNull(bridge.composeDeletionEventId(listOf(target), author, "Deleted from BitOS", nowSeconds = 1_710_000_000))
        // The wire frame carries the EVENT verb; content and tags come from
        // the composer (asserted below).
        val frame = bridge.deletionPublishMessage(listOf(target), author, "Deleted from BitOS", 1_710_000_000, "ab".repeat(64))
        assertNotNull(frame)
        assertTrue(frame.startsWith("""["EVENT","""), frame)
        assertTrue(frame.contains("\"kind\":5"), frame)
        assertTrue(frame.contains("""["e","$target"]"""), frame)
        // Composer contract: dedupe + hex validation + default copy.
        val composer = space.bitos.core.publish.NoteComposer(clock = { 1_710_000_000 })
        val unsigned = composer.composeDeletion(listOf(target, target, "not-hex"), author)!!
        assertEquals(5, unsigned.kind)
        assertEquals(listOf(listOf("e", target)), unsigned.tags)
        assertEquals("Deleted from BitOS", unsigned.content)
        // Empty/invalid target sets refuse to compose.
        assertNull(composer.composeDeletion(emptyList(), author))
        assertNull(composer.composeDeletion(listOf("zz"), author))
    }

    @Test
    fun rejectsInvalidRelayUrl() {
        assertNull(bridge.decodeEvent(VALID_TEXT_NOTE_MESSAGE, "https://not-websocket.example"))
        assertNull(bridge.decodeEvent(VALID_TEXT_NOTE_MESSAGE, null))
    }

    @Test
    fun normalizesFeedNotes() {
        val event = bridge.decodeEvent(VALID_ESCAPED_CONTENT_MESSAGE, "wss://nos.lol")!!
        val note = bridge.feedNote(event)
        assertEquals("b".repeat(32), note.replyTo)
        assertEquals(listOf("gm from BitOS").map { true }, listOf(note.content.isNotEmpty()))
        assertFalse(note.protocolPayload)
        assertTrue(bridge.isFeedKind(note.kind))
        assertFalse(bridge.isProfileKind(note.kind))
    }

    @Test
    fun parsesProfiles() {
        val event = bridge.decodeEvent(VALID_PROFILE_METADATA_MESSAGE, "wss://nos.lol")!!
        val profile = bridge.profile(event)!!
        assertEquals("satoshi", profile.name)
        assertEquals("Satoshi ₿", profile.displayName)
        assertEquals("test vector", profile.about)
        assertTrue(bridge.isProfileKind(event.kind))
        assertNull(bridge.profile(bridge.decodeEvent(VALID_TEXT_NOTE_MESSAGE, "wss://nos.lol")!!))
    }

    @Test
    fun encodesSubscriptionMessages() {
        val request = bridge.feedRequest("feed1")
        assertTrue(request.startsWith("""["REQ","feed1","""), request)
        // Bitz discovery/query standard: the head queries the standard
        // NIP-68/NIP-71 media kinds deep; Home's kind-1 window rides along.
        assertTrue(request.contains(""""kinds":[20,21,22,34235,34236],"limit":80"""), request)
        assertTrue(request.contains(""""kinds":[1],"limit":48"""), request)
        val gapRequest = bridge.feedRequestSince("feed-gap", 600)
        assertTrue(gapRequest.contains(""""kinds":[1],"limit":48,"since":600"""), gapRequest)
        // Bitz NIP-50 search: media kinds only — never kind-1.
        val bitzSearch = bridge.bitzSearchRequest("bs1", "lightning", 50)!!
        assertTrue(bitzSearch.contains(""""kinds":[20,21,22,34235,34236],"search":"lightning","limit":50"""), bitzSearch)
        assertTrue(!bitzSearch.contains(""""kinds":[1]"""), bitzSearch)
        assertEquals("""["CLOSE","feed1"]""", bridge.close("feed1"))
        val profileRequest = bridge.profileRequest("p1", listOf("aa".repeat(32)))
        assertTrue(profileRequest.startsWith("""["REQ","p1","""), profileRequest)
        assertTrue(profileRequest.contains(""""authors":[""" + "\"" + "a".repeat(64)), profileRequest)
        assertTrue(profileRequest.contains(""""limit":1"""), profileRequest)
        // NIP-01 hashtag recall: `#tag` queries classify into a bounded `#t`
        // filter (NIP-50 leaves `#` undefined in search strings).
        val tagRequest = bridge.searchTagRequest("tag1", "#LaoStr", listOf(1, 21, 22), 50)!!
        assertEquals("""["REQ","tag1",{"kinds":[1,21,22],"#t":["laostr"],"limit":50}]""", tagRequest)
        assertEquals(null, bridge.searchTagRequest("tag2", "two words", listOf(1), 50))
    }

    @Test
    fun windowDeduplicatesAndBounds() {
        val window = bridge.makeWindow(3)
        val event = bridge.decodeEvent(VALID_TEXT_NOTE_MESSAGE, "wss://nos.lol")!!
        val note = bridge.feedNote(event)
        assertTrue(window.insert(note))
        assertFalse(window.insert(note))
        assertEquals(1, window.size())

        val fresh = (0 until 10).map { index ->
            BusinessCoreBridge.Note(
                id = "id$index", pubkey = note.pubkey, content = "c$index",
                createdAt = 1_710_003_000L + index, kind = 1, replyTo = null,
                hashtags = emptyList(), mentions = emptyList(), mediaUrls = emptyList(),
                protocolPayload = false,
            )
        }
        fresh.forEach { window.insert(it) }
        assertEquals(3, window.size())
        // Newest survive; dedupe by id is already covered above.
        assertEquals(listOf("id9", "id8", "id7"), window.snapshot().map { it.id })
    }

    @Test
    fun mediaPickHonorsVideoQualityPreference() {
        // APP-018 `bitos_video_quality` (UX U9): AUTO = tallest fitting the
        // display; HIGH = tallest rung; LOW = data saver (shortest ≥360p).
        val specs = listOf(
            "https://cdn.example/v-2160.mp4|2160|8000000",
            "https://cdn.example/v-1080.mp4|1080|4000000",
            "https://cdn.example/v-480.mp4|480|1500000",
        )
        val primary = "https://cdn.example/v.mp4"
        assertEquals("https://cdn.example/v-2160.mp4", bridge.mediaPickRenditionUrl(specs, primary, 1080, "high"))
        assertEquals("https://cdn.example/v-480.mp4", bridge.mediaPickRenditionUrl(specs, primary, 1080, "low"))
        assertEquals("https://cdn.example/v-1080.mp4", bridge.mediaPickRenditionUrl(specs, primary, 1080, "auto"))
        // No ladder → primary regardless of preference.
        assertEquals(primary, bridge.mediaPickRenditionUrl(emptyList(), primary, 1080, "low"))
    }

    @Test
    fun windowRoundTripsAllPresentationFields() {
        // The iOS feed store projects notes through insert → snapshot. Every
        // presentation field — thread anchors, poll, remix, license, warning
        // and the FED-004 media ladder — must survive that round trip
        // (performance-audit R11 regression lock).
        val window = bridge.makeWindow(4)
        val full = BusinessCoreBridge.Note(
            id = "full1",
            pubkey = "aa".repeat(32),
            content = "poll + remix + video",
            createdAt = 1_710_005_000L,
            kind = 1,
            replyTo = "bb".repeat(32),
            hashtags = listOf("bitcoin"),
            mentions = listOf("satoshi"),
            mediaUrls = listOf("https://cdn.io/a.png"),
            protocolPayload = false,
            repostedBy = "cc".repeat(32),
            videoUrl = "https://cdn.io/a.mp4",
            videoMime = "video/mp4",
            posterUrl = "https://cdn.io/a.jpg",
            videoWidth = 1080,
            videoHeight = 1920,
            durationSeconds = 42L,
            contentWarning = true,
            threadRootId = "dd".repeat(32),
            threadParentId = "ee".repeat(32),
            pollOptions = listOf("yes", "no"),
            remixOfEventId = "ff".repeat(32),
            remixOfPubkey = "ab".repeat(32),
            license = "CC-BY-4.0",
            fallbackUrls = listOf("https://mirror.io/a.mp4"),
            renditionSpecs = listOf("https://cdn.io/a-720.mp4|720|2500000"),
        )
        assertTrue(window.insert(full))
        val roundTripped = window.snapshot().single()

        assertEquals(full.id, roundTripped.id)
        assertEquals(full.replyTo, roundTripped.replyTo)
        assertEquals(full.threadRootId, roundTripped.threadRootId)
        assertEquals(full.threadParentId, roundTripped.threadParentId)
        assertEquals(full.contentWarning, roundTripped.contentWarning)
        assertEquals(full.pollOptions, roundTripped.pollOptions)
        assertEquals(full.remixOfEventId, roundTripped.remixOfEventId)
        assertEquals(full.remixOfPubkey, roundTripped.remixOfPubkey)
        assertEquals(full.license, roundTripped.license)
        assertEquals(full.repostedBy, roundTripped.repostedBy)
        assertEquals(full.durationSeconds, roundTripped.durationSeconds)
        assertEquals(full.fallbackUrls, roundTripped.fallbackUrls)
        assertEquals(full.renditionSpecs, roundTripped.renditionSpecs)
        assertEquals(full.videoUrl, roundTripped.videoUrl)
        assertEquals(full.posterUrl, roundTripped.posterUrl)
    }

    @Test
    fun rejectsSignatureUnverifiedEvents() {
        // SBC-006: a well-formed event whose ID was recomputed for tampered
        // content, carrying another event's signature, must NOT reach the
        // display path. The bridge now runs both trust stages (ID + BIP-340).
        assertNull(bridge.decodeEvent(VALID_ID_WRONG_SIGNATURE_MESSAGE, "wss://relay.damus.io"))
    }

    @Test
    fun composerMentionQueryDistinguishesComposingFromNone() {
        // `composerMentionQuery` collapses "none" and the bare-`@` empty
        // query to ""; `composerIsComposingMention` keeps them apart so the
        // empty query still surfaces the unfiltered candidate list.
        assertEquals("sat", bridge.composerMentionQuery("hello @sat", 10))
        assertTrue(bridge.composerIsComposingMention("hello @sat", 10))
        assertEquals("", bridge.composerMentionQuery("hello @", 7))
        assertTrue(bridge.composerIsComposingMention("hello @", 7))
        assertEquals("", bridge.composerMentionQuery("hello bob", 9))
        assertFalse(bridge.composerIsComposingMention("hello bob", 9))
        // Cursor counts UTF-16 code units (Kotlin string indices), not
        // grapheme clusters: the emoji is two units.
        assertEquals("sat", bridge.composerMentionQuery("👍 @sat", 7))
        assertTrue(bridge.composerIsComposingMention("👍 @sat", 7))
        assertFalse(bridge.composerIsComposingMention("👍 @sat", 2))
    }

    @Test
    fun replyTagsJsonCarriesMarkersAndParticipants() {
        val author = "aa".repeat(32)
        val root = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        val parent = "bb".repeat(32)
        val participant = "cc".repeat(32)
        val json = bridge.replyTagsJson(
            rootEventId = root, targetEventId = parent, targetPubkey = author,
            targetPTagsJson = """["$participant","not-hex"]""", content = "gm #bitcoin",
        )!!
        assertTrue(json.contains(""""e","$root","","root""""), json)
        assertTrue(json.contains(""""e","$parent","","reply""""), json)
        assertTrue(json.contains(""""p","$author""""))
        assertTrue(json.contains(""""p","$participant""""))
        assertTrue(json.contains(""""t","bitcoin""""))
        assertNull(bridge.replyTagsJson("nope", parent, author, "[]", "x"))
    }

    @Test
    fun gifPickerSurfaceMatchesStableJsonShapes() {
        // Request building (trending when blank, encoded search otherwise).
        assertTrue(bridge.gifPickerUrl("  ", 0).endsWith("&limit=30&offset=0&rating=pg"))
        assertTrue(bridge.gifPickerUrl("gm nostr", 30).contains("q=gm%20nostr"))
        // Response parse → item array JSON.
        val items = bridge.gifPickerParse(GIPHY_TWO_ITEM_RESPONSE)
        assertTrue(items.startsWith("[{\"id\":\"a\""), items)
        assertTrue(items.contains("\"preview\":\"https://media.giphy.com/media/a/100.gif\""))
        assertEquals("[]", bridge.gifPickerParse("not json"))
        // Pagination map.
        val page = bridge.gifPickerPagination(GIPHY_TWO_ITEM_RESPONSE, fetchedCount = 2, requestedOffset = 0)
        assertEquals(2, page["nextOffset"])
        assertEquals(true, page["hasMore"])
        // Recent merge: newest first, deduped.
        val first = """{"id":"a","url":"https://a.example/a.gif","preview":"https://a.example/as.gif","w":100,"h":100}"""
        val merged = bridge.gifPickerMergeRecent("[$first]", first)
        assertEquals("[$first]", merged)
        // Cache encode/decode round-trip through the envelope JSON.
        val wire = bridge.gifCacheEncode("[$first]", "[$first]", savedAtMs = 1_000)
        val decoded = bridge.gifCacheDecode(wire)!!
        assertTrue(decoded.contains("\"savedAt\":1000"), decoded)
        assertTrue(decoded.contains("\"recent\":[{\"id\":\"a\""), decoded)
        assertNull(bridge.gifCacheDecode("{corrupt"))
        assertTrue(bridge.gifCacheFresh(1_000, 1_000 + 86_399_999))
        assertFalse(bridge.gifCacheFresh(1_000, 1_000 + 86_400_001))
    }

    @Test
    fun keyImportCheckWireMatchesSharedVerdicts() {
        // READY carries the resolved secret; every other verdict carries copy only.
        val ready = bridge.keyImportCheck("nsec162knc0y70v8576su95ly75rpw2peffdkclvwnu9pktpafe0kquvqh3ydrs")
        assertEquals("READY", ready.verdict)
        assertEquals("d2ad3c3c9e7b0f4f6a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718", ready.secretHex)
        assertEquals("Valid nsec key.", ready.message)
        val npubInput = bridge.keyImportCheck("npub194667yy2sqh4h4vlwssg7g5smhmqx4x9hgtfdjun8e46l30kxqqselzc9y")
        assertEquals("WRONG_KEY_TYPE", npubInput.verdict)
        assertEquals("That is a public key (npub); import needs the secret (nsec).", npubInput.message)
        assertNull(npubInput.secretHex)
        assertEquals("EMPTY", bridge.keyImportCheck("  ").verdict)
    }

    @Test
    fun nip22CommentTagsMatchWebShape() {
        val target = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        val kind22 = 22L
        // Top-level: parent repeats the target; k carries the TARGET kind.
        val top = bridge.commentTagsJson(target, target, kind22, null, null, "gm #bitcoin")!!
        assertTrue(top.startsWith("["), top)
        assertTrue(top.contains("""["E","$target"]"""), top)
        assertTrue(top.contains("""["K","22"]"""), top)
        assertTrue(top.contains("""["P","$target"]"""), top)
        assertTrue(top.contains("""["e","$target"]"""), top)
        assertTrue(top.contains("""["k","22"]"""), top)
        assertTrue(top.contains("""["t","bitcoin"]"""), top)
        // Nested: parent points at the comment being answered; k = 1111.
        val parentComment = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        val nested = bridge.commentTagsJson(target, target, kind22, parentComment, target, "re")!!
        assertTrue(nested.contains("""["e","$parentComment"]"""), nested)
        assertTrue(nested.contains("""["k","1111"]"""), nested)
        // Kind-1 targets must use NIP-10 (web's intentional guard).
        assertNull(bridge.commentTagsJson(target, target, 1L, null, null, "gm"))
        // The compose/publish pair round-trips as a kind-1111 event frame.
        val eventId = bridge.composeCommentWithTagsEventId("gm", target, 1_710_000_000, top)
        assertNotNull(eventId)
        val frame = bridge.commentWithTagsPublishMessage("gm", target, 1_710_000_000, "ab".repeat(64), top)!!
        assertTrue(frame.startsWith("""["EVENT","""), frame)
        assertTrue(frame.contains("\"kind\":1111"), frame)
        // Thread REQs: the plain #e filter carries 1111; the #E variant
        // catches uppercase-rooted comments.
        val plain = bridge.commentsRequest("s", target)
        assertTrue(plain.contains("\"kinds\":[1,1111,7,6,9735]"), plain)
        val root = bridge.commentsRootRequest("s2", target)
        assertTrue(root.contains("\"kinds\":[1111]"), root)
        assertTrue(root.contains("\"#E\":[\"$target\"]"), root)
    }

    @Test
    fun memeTimelineSeamsSyncClipsAndMeasureDuration() {
        val project = bridge.memeProjectNormalize(
            """{"v":1,"mode":"video","assets":[{"id":"v1","kind":"video"}],"overlays":[]}""",
        )
        assertTrue(project.isNotEmpty())

        // Sync: two clips land in order, bounded + clamped, the first
        // window mirrors into the legacy trim fields.
        val synced = bridge.memeTimelineSyncClips(
            project,
            """[{"id":"v1","start":0,"end":10000},
                {"id":"v2","start":500,"end":9000,"vol":0,"look":"sepia"}]""",
        )
        assertTrue(synced.contains("\"clips\""), synced)
        val roundTrip = bridge.memeProjectNormalize(synced)
        assertEquals(synced, roundTrip, "clips survive a normalize round-trip")
        assertTrue(synced.contains("\"trim\":[0,10000]"), synced)
        assertEquals(18_500L, bridge.memeTimelineDurationMs(synced))

        // Speed divides the whole timeline: 2× halves the output duration.
        val fast = bridge.memeApplyCommand(synced, """{"op":"speed","rate":2}""")
        assertEquals(9_250L, bridge.memeTimelineDurationMs(fast))

        // Hostile clip rows drop; degenerate windows never sync.
        val hostile = bridge.memeTimelineSyncClips(
            project,
            """[{"start":0,"end":10},{"id":"v1","start":900,"end":900},
                {"id":"v2","start":100,"end":200}]""",
        )
        assertTrue(hostile.contains("\"clips\":[{\"id\":\"v2\""), hostile)
        assertFalse(hostile.contains("\"id\":\"v1\",\"start\""), hostile)

        // Guards: non-video project, corrupt clips, corrupt project.
        val image = bridge.memeProjectNormalize("""{"v":1,"mode":"image","assets":[],"overlays":[]}""")
        assertEquals("", bridge.memeTimelineSyncClips(image, """[{"id":"v1","start":0,"end":10}]"""))
        assertEquals("", bridge.memeTimelineSyncClips(project, "not json"))
        assertEquals(-1L, bridge.memeTimelineDurationMs("not json"))
        assertEquals(0L, bridge.memeTimelineDurationMs(image))
    }

    @Test
    fun memeSeamsNormalizeApplyAndHitTestOnTheProjectWire() {
        // Normalize: hostile values clamp through the shared contract.
        val normalized = bridge.memeProjectNormalize(
            """{"v":1,"mode":"image","assets":[],"overlays":[
               {"id":"o1","kind":"text","text":"gm","font":"impact","size":48,
                "color":0,"outline":2,"shadow":false,"x":0.5,"y":0.5,
                "scale":1,"rot":0}]}""",
        )
        assertTrue(normalized.contains("\"mode\":\"image\""), normalized)

        // Corrupt wires normalize to "" (an empty project on the caller side).
        assertEquals("", bridge.memeProjectNormalize("not json"))
        assertEquals("", bridge.memeApplyCommand("not json", """{"op":"remove","id":"o1"}"""))

        // Apply: update lands; unknown op / unknown overlay are no-ops
        // (the normalized project comes back unchanged).
        val updated = bridge.memeApplyCommand(
            normalized,
            """{"op":"update","id":"o1","x":0.9,"y":0.1}""",
        )
        assertTrue(updated.contains("\"x\":0.9"), updated)
        assertEquals(normalized, bridge.memeApplyCommand(normalized, """{"op":"nope"}"""))
        assertEquals(
            normalized,
            bridge.memeApplyCommand(normalized, """{"op":"update","id":"ghost","x":0.1}"""),
        )

        // Hit test: inside the overlay hits it, outside misses.
        assertEquals("o1", bridge.memeHitTest(normalized, 0.5f, 0.5f))
        assertEquals("", bridge.memeHitTest(normalized, 0.99f, 0.99f))
    }

    @Test
    fun memePaletteDefaultOverlayAndStickerPacksSeams() {
        // Palette: 16 rows, 6-hex, index-ordered (index 0 = web white).
        val palette = bridge.memePalette()
        assertEquals(16, palette.size)
        assertEquals("FFFFFF", palette.first())
        palette.forEach { row -> assertEquals(6, row.length, row) }

        // Default overlay: deterministic placement + unique id, add-ready.
        val project = """{"v":1,"mode":"image","assets":[{"id":"a1","kind":"image"}],"overlays":[]}"""
        val overlayJson = bridge.memeDefaultOverlay(project, "text", "gm")
        assertTrue(overlayJson.contains("\"id\":\"o1\""), overlayJson)
        assertTrue(overlayJson.contains("\"kind\":\"text\""), overlayJson)
        // First overlay staggers to 0.5 − 0.08 (deterministic placement).
        assertTrue(overlayJson.contains("\"x\":0.42"), overlayJson)
        // The produced overlay round-trips through an add command.
        val added = bridge.memeApplyCommand(
            project,
            """{"op":"add","overlay":$overlayJson}""",
        )
        assertTrue(added.contains("\"text\":\"gm\""), added)
        // Bounds feed the selection chrome: "width|height" on the canvas.
        assertTrue(bridge.memeBounds(added, "o1").matches(Regex("0\\.\\d+\\|0\\.\\d+")), bridge.memeBounds(added, "o1"))
        assertEquals("", bridge.memeBounds(added, "ghost"))
        // Unknown kind / corrupt project → "" (never a half-built overlay).
        assertEquals("", bridge.memeDefaultOverlay(project, "nope", "gm"))
        assertEquals("", bridge.memeDefaultOverlay("junk", "text", "gm"))

        // Sticker packs ride to Swift verbatim (web stickers.ts port).
        val packs = bridge.memeStickerPacks()
        assertTrue(packs.contains("\"id\":\"crypto\""), packs)
        assertTrue(packs.contains("₿"), packs)
    }

    @Test
    fun memeEncoderPlanAndEstimateSeamsServeMST036Presets() {
        // Encoder plan: AUTO tiers on a 9:16 portrait source — long edge
        // caps at 1080 → 608×1080 at 6 Mbps + 128 k audio (shared math).
        val plan = bridge.memeEncoderPlan("P1080", "HIGH", 1080, 1920)
        assertTrue(plan.contains("\"bitrate\":6000000"), plan)
        assertTrue(plan.contains("\"audioBitrate\":128000"), plan)
        assertTrue(plan.contains("\"width\":608"), plan)
        assertTrue(plan.contains("\"height\":1080"), plan)
        // Case-insensitive tiers.
        assertEquals(plan, bridge.memeEncoderPlan("p1080", "high", 1080, 1920))
        // Estimate + gate: 90 s at AUTO ~ 71 MB → blocked; 720p/Medium fits.
        val blocked = bridge.memeExportEstimate("P1080", "HIGH", 90_000)
        assertTrue(blocked.contains("\"publishFits\":false"), blocked)
        assertTrue(blocked.contains("\"bytes\":71008200"), blocked)
        val fits = bridge.memeExportEstimate("P720", "MEDIUM", 90_000)
        assertTrue(fits.contains("\"publishFits\":true"), fits)
        assertTrue(fits.contains("\"label\":\"≈ 29.0 MB\""), fits)
        // Unknown tiers normalize to "" — never throws.
        assertEquals("", bridge.memeEncoderPlan("P2160", "HIGH", 1080, 1920))
        assertEquals("", bridge.memeExportEstimate("P720", "ULTRA", 90_000))
    }

    @Test
    fun memePowSeamsMineAndReproduceTheMinedId() {
        val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        val minedAt = 1_710_000_000L
        // Video (kind 22): mine one window, then the pow compose seam must
        // reproduce the mined id — the byte-match publish contract.
        val raw = bridge.mineMemeVideoPow(
            author, "gm #nostr", "", null, portrait = true,
            "https://cdn.example/m.mp4", "a".repeat(64), "video/mp4",
            1_048_576, 608, 1080, 30_000, minedAt,
            targetDifficulty = 8, startNonce = 0, maxAttempts = 500_000,
        )
        assertNotNull(raw)
        val (nonce, id) = raw!!.split(":")
        assertEquals(
            id,
            bridge.powMemeVideoEventId(
                author, "gm #nostr", "", null, true,
                "https://cdn.example/m.mp4", "a".repeat(64), "video/mp4",
                1_048_576, 608, 1080, 30_000, minedAt, nonce.toLong(), 8,
            ),
        )
        assertTrue(space.bitos.core.nostr.Pow.difficulty(id) >= 8)
        // Picture (kind 20) twin.
        val rawPicture = bridge.mineMemePicturePow(
            author, "pic", "", null,
            "https://cdn.example/p.png", "b".repeat(64), "image/png",
            2048, 608, 1080, minedAt,
            targetDifficulty = 8, startNonce = 0, maxAttempts = 500_000,
        )
        assertNotNull(rawPicture)
        val (noncePicture, idPicture) = rawPicture!!.split(":")
        assertEquals(
            idPicture,
            bridge.powMemePictureEventId(
                author, "pic", "", null,
                "https://cdn.example/p.png", "b".repeat(64), "image/png",
                2048, 608, 1080, minedAt, noncePicture.toLong(), 8,
            ),
        )
        // Junk pubkey → no template, no mining (never throws).
        assertNull(
            bridge.mineMemeVideoPow(
                "NOPE", "gm", "", null, true,
                "https://cdn.example/m.mp4", "a".repeat(64), "video/mp4",
                1_048_576, 608, 1080, 30_000, minedAt,
                targetDifficulty = 8, startNonce = 0, maxAttempts = 1_000,
            ),
        )
        // Regression (paste bug fixed with MST post-details PoW): the
        // text/comment pow seams once emitted literal `${it.nonce}` junk —
        // pin that every "nonce:id" payload stays parseable.
        val textRaw = bridge.mineTextNotePowWithTags("gm", author, minedAt, 8, 0, 500_000, "[]")
        assertNotNull(textRaw)
        textRaw!!.split(":").first().toLongOrNull().also { assertTrue(it != null, textRaw) }
    }

    @Test
    fun memeFxTransformAndCueTrackSeamsFeedTimedExports() {
        var project = """{"v":1,"mode":"video","assets":[],"overlays":[]}"""
        project = bridge.memeApplyCommand(
            project,
            """{"op":"add","overlay":{"id":"fx1","kind":"text","text":"pop","font":"impact",
                 "size":48,"color":0,"outline":2,"shadow":false,"x":0.5,"y":0.5,
                 "scale":1,"rot":0,"startMs":1000,"endMs":4000,"fx":"pop"}}""",
        )
        project = bridge.memeApplyCommand(
            project,
            """{"op":"cue-add","cue":{"id":"c1","sfx":"lightning-zap","at":500,"g":1}}""",
        )

        // Outside the window: the window folds into alpha 0.
        val before = bridge.memeFxTransformAt(project, "fx1", 0)
        assertTrue(before.endsWith("|0.0"), before)
        // Mid-entry (200 ms in): pop overshoots past 1 (easeOutBack), visible.
        val mid = bridge.memeFxTransformAt(project, "fx1", 1200)
        val midParts = mid.split("|")
        assertTrue(kotlin.math.abs(midParts[0].toFloat() - 1f) > 0.001f, mid)
        assertEquals(1f, midParts[4].toFloat())
        // Past the 380 ms entry: identity, still visible.
        val settled = bridge.memeFxTransformAt(project, "fx1", 2000).split("|")
        assertEquals(1f, settled[0].toFloat(), 0.001f)
        assertEquals(1f, settled[4].toFloat())
        // Poster (negative time): identity, untransformed.
        assertEquals("1.0|0.0|0.0|0.0|1.0", bridge.memeFxTransformAt(project, "fx1", -1))
        // Unknown id / corrupt project → "".
        assertEquals("", bridge.memeFxTransformAt(project, "ghost", 0))
        assertEquals("", bridge.memeFxTransformAt("junk", "fx1", 0))

        // Cue track: audible in-window → base64 WAV with a RIFF header;
        // empty when no cue lands inside the window or the wire is junk.
        val wavB64 = bridge.memeSfxTrackWavBase64(project, 2000)
        assertTrue(wavB64.isNotEmpty())
        val wav = kotlin.io.encoding.Base64.Default.decode(wavB64)
        val header = wav.copyOf(4).decodeToString()
        assertEquals("RIFF", header)
        assertEquals("", bridge.memeSfxTrackWavBase64(project, 0), "the 500 ms cue is outside a 0 ms window")
        assertEquals("", bridge.memeSfxTrackWavBase64("junk", 2000))
    }

    @Test
    fun memeWireSeamsRoundTripTheInteropDocument() {
        val wire = """{"schema":"com.bitos.bitz.meme","version":1,
               "overlays":[{"id":"t1","text":"wen moon","x":0.2,"y":0.8,
                 "size":0.09,"color":"#fde047","font":"impact",
                 "startMs":250,"endMs":9000,"fx":"pop","futureField":7}]}"""
        val nowMs = 1_700_000_000_000L

        // Normalize: hostile wire in, canonical web-shaped wire out,
        // passthrough (futureField) intact, updatedAt re-stamped.
        val normalized = bridge.memeWireNormalize(wire, nowMs)
        assertTrue(normalized.contains("\"schema\":\"com.bitos.bitz.meme\""), normalized)
        assertTrue(normalized.contains("\"futureField\":7"), normalized)
        assertTrue(normalized.contains("\"updatedAt\":$nowMs"), normalized)
        assertEquals("", bridge.memeWireNormalize("""{"schema":"com.other.meme"}""", nowMs))

        // Wire → local: fraction size lands in px, window + fx survive.
        val local = bridge.memeWireToLocal(wire)
        assertTrue(local.contains("\"v\":1"), local)
        assertTrue(local.contains("\"size\":97"), local)
        assertTrue(local.contains("\"startMs\":250"), local)
        assertTrue(local.contains("\"fx\":\"pop\""), local)
        assertEquals("", bridge.memeWireToLocal("junk"))

        // Local → wire: the same project exports back into the web shape
        // (size re-quantizes through the 97 px reference — 0.0898…).
        val exported = bridge.localToMemeWire(local, nowMs)
        assertTrue(exported.contains("\"text\":\"wen moon\""), exported)
        assertTrue(exported.contains("\"size\":0.0898"), exported)
        assertEquals("", bridge.localToMemeWire("junk", nowMs))
    }

    @Test
    fun memeExportPlanSeamFeedsTheRasterizers() {
        val project = """{"v":1,"mode":"image","assets":[{"id":"a1","kind":"image"}],"overlays":[
               {"id":"o1","kind":"text","text":"gm","font":"impact","size":108,
                "color":0,"outline":2,"shadow":false,"x":0.5,"y":0.5,
                "scale":1,"rot":0}]}"""
        // Portrait 1080×1920 source → envelope canvas evens to 608×1080
        // (web targetSize parity) and the plan rides inside.
        val plan = bridge.memeExportPlan(project, sourceWidth = 1080, sourceHeight = 1920)
        assertTrue(plan.contains("\"width\":608"), plan)
        assertTrue(plan.contains("\"height\":1080"), plan)
        assertTrue(plan.contains("\"text\":\"GM\""), plan)
        assertTrue(
            plan.contains("\"fontSize\":108"),
            "$plan — size × 1080/1080 height reference",
        )
        assertTrue(
            plan.contains("\"outline\":4"),
            "$plan — 2 px outline paints at stroke scale 2",
        )
        assertEquals("", bridge.memeExportPlan("junk", 1080, 1080))
    }

    @Test
    fun memePictureSeamsComposeAndFrameTheKind20Event() {
        val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        val hash = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"

        val eventId = bridge.composeMemePictureEventId(
            authorPubkey = author,
            caption = "wen moon #bitcoin",
            altText = "",
            contentWarningReason = null,
            url = "https://cdn.example/meme.png",
            sha256Hex = hash,
            mimeType = "image/png",
            sizeBytes = 424_242,
            width = 608,
            height = 1080,
            nowSeconds = 1_710_000_000,
        )
        assertTrue(eventId != null)

        // The signed frame matches the same composition (id + kind 20).
        val frame = bridge.memePicturePublishMessage(
            authorPubkey = author,
            caption = "wen moon #bitcoin",
            altText = "",
            contentWarningReason = null,
            url = "https://cdn.example/meme.png",
            sha256Hex = hash,
            mimeType = "image/png",
            sizeBytes = 424_242,
            width = 608,
            height = 1080,
            createdAtSeconds = 1_710_000_000,
            signatureHex = "ab".repeat(64),
        )
        assertTrue(frame!!.contains("\"kind\":20"), frame)
        assertTrue(frame.contains("\"imeta\""), frame)
        assertTrue(frame.contains("m image/png"), frame)

        // Hostile media (non-https) → null, never a half-built event.
        assertEquals(
            "",
            bridge.composeMemePictureEventId(
                authorPubkey = author, caption = "gm", altText = "", contentWarningReason = null,
                url = "http://insecure/meme.png", sha256Hex = hash, mimeType = "image/png",
                sizeBytes = 10, width = 10, height = 10, nowSeconds = 0,
            ) ?: "",
        )
    }


    @Test
    fun memeRemixTagsSeamBuildsTheLineageTags() {
        val project = """{"v":1,"mode":"image","assets":[],"overlays":[
               {"id":"o1","kind":"text","text":"wen moon #bitcoin","font":"impact",
                "size":97,"color":0,"outline":2,"shadow":false,"x":0.31,"y":0.77,
                "scale":1,"rot":0}]}"""
        val tags = bridge.memeRemixTagsFor(
            projectJson = project,
            sourceEventId = "1".repeat(64),
            sourcePubkey = "2".repeat(64),
            relaysJson = """["wss://a.one","wss://b.two","wss://c.three","wss://d.four"]""",
            license = "CC-BY-4.0",
            attribution = "the OG memelord",
        )
        assertTrue(tags.contains("[\"remix\",\"${"1".repeat(64)}\""), tags)
        assertTrue(tags.contains("wss://a.one"), tags)
        assertTrue(!tags.contains("wss://d.four"), "relay hints cap at 3")
        assertTrue(tags.contains("\"meme\",\""), tags)
        assertTrue(tags.contains("[\"p\",\"${"2".repeat(64)}\"]"), tags)
        assertTrue(tags.contains("[\"license\",\"CC-BY-4.0\"]"), tags)
        assertTrue(tags.contains("remix of the OG memelord"), tags)
        // Corrupt wire → "".
        assertEquals("", bridge.memeRemixTagsFor("junk", "1".repeat(64), "2".repeat(64), "[]", "", ""))
    }

    @Test
    fun memeSoundTagsSeamStampsProvenanceOnlyWhenUploaded() {
        val sha = "a".repeat(64)
        fun wire(soundtrack: space.bitos.core.studio.MemeSoundtrack?) =
            space.bitos.core.studio.MemeProjectContract.encode(
                space.bitos.core.studio.MemeProject(
                    mode = space.bitos.core.studio.MemeMode.VIDEO,
                    soundtrack = soundtrack,
                ),
            )
        val sound = space.bitos.core.studio.MemeSoundtrack(
            url = "https://blossom.example/b/audio.mp4",
            sha256 = sha,
            durationMs = 12_000,
            sourceNoteId = "1".repeat(64),
            sourceAuthorPubkey = "2".repeat(64),
            label = "the OG memelord",
        )
        val tags = bridge.memeSoundTagsFor(wire(sound))
        assertTrue(tags.contains("[\"sound\",\"https://blossom.example/b/audio.mp4\",\"$sha\",\"${"1".repeat(64)}\"]"), tags)
        assertTrue(tags.contains("[\"p\",\"${"2".repeat(64)}\"]"), tags)
        assertTrue(tags.contains("sound of the OG memelord"), tags)
        // Attach-time (pre-upload): nothing stampable — never sign first.
        assertEquals("", bridge.memeSoundTagsFor(wire(sound.copy(url = ""))))
        // No soundtrack / corrupt wire → "".
        assertEquals("", bridge.memeSoundTagsFor(wire(null)))
        assertEquals("", bridge.memeSoundTagsFor("junk"))
    }

    @Test
    fun remixRelayHintsSeamMergesSourceAndWriteRelays() {
        val merged = bridge.remixRelayHintsJson(
            sourceRelaysJson = """["wss://src.one","wss://src.two"]""",
            writeRelaysJson = """["wss://src.two","wss://w.one","wss://w.two","wss://w.three"]""",
        )
        // Source-first dedupe, then write relays fill, cap 3.
        assertEquals("""["wss://src.one","wss://src.two","wss://w.one"]""", merged)
        // Corrupt JSON inputs degrade to empty lists, never throw.
        assertEquals("[]", bridge.remixRelayHintsJson("not json", "also not json"))
    }

    @Test
    fun memeApplyRemixSeamClonesLayoutOntoTheProject() {
        val project = """{"v":1,"mode":"image","assets":[],"overlays":[],"tags":[]}"""
        val payload = """{"v":1,"o":[{"t":"gm","x":0.4,"y":0.2,"s":0.09}],"c":[],"l":"vhs"}"""
        val seeded = bridge.memeApplyRemix(project, payload)
        assertTrue(seeded.contains("\"text\":\"gm\""), seeded)
        assertTrue(seeded.contains("\"look\":\"vhs\""), seeded)
        // Fresh ids — never the compact decode's literal "o" row id.
        assertTrue(!seeded.contains("\"id\":\"o\""), seeded)
        // Undecodable payload / missing tag → project unchanged (the seam
        // re-encodes canonically, so compare the decoded shape).
        fun overlaysOf(json: String) =
            space.bitos.core.studio.MemeProjectContract.decode(json)?.overlays
        assertEquals(emptyList(), overlaysOf(bridge.memeApplyRemix(project, null)))
        assertEquals(emptyList(), overlaysOf(bridge.memeApplyRemix(project, """{"v":1,"o":[],"c":[]}""")))
        // Corrupt project wire → "".
        assertEquals("", bridge.memeApplyRemix("junk", payload))
    }

    @Test
    fun csvPreviewMapsColumnsAndCapsRowsBeforeImport() {
        // MUX-07: dry-run analysis + the operational cap (UX-14) — imports
        // over 100 rows refuse with the split instruction, never truncate.
        val fresh = bridge.massBatchNew("Zap batch", nowMs = 1_700_000_000_000)
        val header = "name,sats,unmapped column"
        val rows = (1..101).joinToString("\n") { "user$it,${it}k,ignored" }
        val previewJson = bridge.massBatchCsvPreview(fresh, header + "\n" + rows)
        assertTrue(previewJson.contains("\"unknownColumns\":[\"unmapped column\"]"), previewJson)
        assertTrue(previewJson.contains("\"overCap\":true"), previewJson)
        assertTrue(previewJson.contains("\"dataRows\":101"), previewJson)
        assertTrue(previewJson.contains("\"missingRequired\":[\"sats\"]").not() && previewJson.contains("\"missingRequired\":["), previewJson)

        // Import over the cap refuses with the actionable note, imports nothing.
        val refused = bridge.massBatchImportCsv(fresh, header + "\n" + rows)
        assertTrue(refused.contains("Split the CSV"), refused)

        // ≤100 rows import unchanged; quoted commas + Unicode survive.
        val small = "name,sats\n\"Satoshi, Jr.\",1k\nヴィタリク,2k"
        val imported = bridge.massBatchImportCsv(fresh, small)
        assertTrue(imported.contains("Satoshi, Jr."), imported)
        assertTrue(imported.contains("ヴィタリク"), imported)

        // Schema decode keeps 200-row documents readable (no truncation).
        val big = (1..200).joinToString("\n") { "user$it,${'$'}{it}k" }
        val imported2 = bridge.massBatchImportCsv(fresh, "name,sats\n$big")
        // 200 > 100 → refused now; decode compatibility is exercised via a
        // 200-row wire decoded by MassBatchCodec directly:
        val doc = space.bitos.core.studio.MassBatchCodec.decode(fresh)!!
        val bigRows = (1..200).map { space.bitos.core.studio.MassRow(id = "r$it", values = mapOf("name" to "u$it")) }
        val bigDoc = doc.copy(rows = bigRows)
        val encoded = space.bitos.core.studio.MassBatchCodec.encode(bigDoc)
        assertEquals(200, space.bitos.core.studio.MassBatchCodec.decode(encoded)!!.rows.size)
        assertTrue(imported2.contains("Split the CSV"))
    }

    @Test
    fun massBatchFromDesignFreezesAnEditorDesign() {
        // MUX-06: the editor's current image design becomes a batch recipe —
        // one LONG_TEXT slot per caption; blank row values drop the caption.
        val design = """{"v":1,"mode":"image","assets":[{"id":"a1"}],"overlays":[
               {"id":"o1","kind":"text","text":"wen moon #bitcoin","font":"impact",
                "size":97,"color":0,"outline":2,"shadow":false,"x":0.31,"y":0.77,
                "scale":1,"rot":0}]}"""
        val doc = bridge.massBatchFromDesign(design, "mb-design1", "My variations", 1_700_000_000_000)
        assertTrue(doc.contains("\"id\":\"mb-design1\""), doc)
        assertTrue(doc.contains("t:o1"), doc)
        val decoded = space.bitos.core.studio.MassBatchCodec.decode(doc)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.recipe.slots.size)
        assertEquals(space.bitos.core.studio.MassSlotType.LONG_TEXT, decoded.recipe.slots.first().type)
        // Slot value substitutes into the variant; blank drops the caption.
        var working = bridge.massBatchOp(doc, """{"op":"addRow"}""")
        working = bridge.massBatchOp(working, """{"op":"setValue","row":"r1","slot":"t:o1","value":"wen lambo"}""")
        val filled = space.bitos.core.studio.MassBatchRules.resolveVariant(
            decoded.recipe,
            space.bitos.core.studio.MassBatchCodec.decode(working)!!.rows.first(),
            1,
        )
        assertEquals("wen lambo", filled.project.overlays.first().text)
        val emptyRow = space.bitos.core.studio.MassRow(id = "r1")
        val dropped = space.bitos.core.studio.MassBatchRules.resolveVariant(decoded.recipe, emptyRow, 1)
        assertEquals("", dropped.project.overlays.first().text)

        // Non-image designs and corrupt wires get honest errors, not batches.
        assertTrue(bridge.massBatchFromDesign(design.replace("\"mode\":\"image\"", "\"mode\":\"gif\""), "x", "n", 1).contains("error"))
        assertTrue(bridge.massBatchFromDesign("junk", "x", "n", 1).contains("error"))
        // A caption-less design has nothing to vary.
        val noCaptions = """{"v":1,"mode":"image","assets":[{"id":"a1"}],"overlays":[]}"""
        assertTrue(bridge.massBatchFromDesign(noCaptions, "x", "n", 1).contains("error"))
    }

    @Test
    fun massBatchSeamsDriveTheCreateHubFlow() {
        // New → starter recipe with the canonical placeholder pair.
        val fresh = bridge.massBatchNew("Zap batch", nowMs = 1_700_000_000_000)
        assertTrue(fresh.contains("\"name\":\"sats\""), fresh)
        assertTrue(fresh.contains("zap {name}"), fresh)

        // addRow + setValue + setAsset → plan reports severity/approval.
        var doc = bridge.massBatchOp(fresh, """{"op":"addRow"}""")
        doc = bridge.massBatchOp(doc, """{"op":"setValue","row":"r1","slot":"name","value":"satoshi_v"}""")
        doc = bridge.massBatchOp(doc, """{"op":"setValue","row":"r1","slot":"sats","value":"1k"}""")
        doc = bridge.massBatchOp(doc, """{"op":"setAsset","row":"r1","slot":"img","file":"r1-img.img"}""")
        val plan = bridge.massBatchPlan(doc, """["r1-img.img"]""")
        assertTrue(plan.contains("\"severity\":\"warn\""), plan) // 1k coerced → warn
        assertTrue(plan.contains("\"blocked\":0"), plan)
        assertTrue(plan.contains("\"queueCount\":0"), plan) // not approved yet

        // withRender + approve → queueCount 1; unknown op is a no-op.
        doc = bridge.massBatchOp(doc, """{"op":"withRender","row":"r1","posterName":"poster-r1.jpg","posterHash":"ph"}""")
        doc = bridge.massBatchOp(doc, """{"op":"approve","row":"r1","approve":true,"nowMs":10}""")
        val approvedPlan = bridge.massBatchPlan(doc, """["r1-img.img"]""")
        assertTrue(approvedPlan.contains("\"queueCount\":1"), approvedPlan)
        assertTrue(approvedPlan.contains("\"approved\":true"), approvedPlan)
        assertEquals(doc, bridge.massBatchOp(doc, """{"op":"nonsense"}"""))

        // Recipe edit with rows → fork (version bumps, approvals reset).
        val forked = bridge.massBatchOp(doc, """{"op":"editRecipe","naming":"v2_{i}"}""")
        assertTrue(forked.contains("\"version\":2"), forked)
        assertTrue(bridge.massBatchPlan(forked, "[]").contains("\"queueCount\":0"))

        // withPublish settles the per-event state machine.
        val published = bridge.massBatchOp(
            bridge.massBatchOp(forked, """{"op":"addRow"}""")
                .let { bridge.massBatchOp(it, """{"op":"setValue","row":"r2","slot":"name","value":"x"}""") }
                .let { bridge.massBatchOp(it, """{"op":"setValue","row":"r2","slot":"sats","value":"2"}""") },
            """{"op":"withPublish","row":"r2","state":"published"}""",
        )
        assertTrue(published.contains("\"publish\":\"published\""), published)

        // CSV import returns the mutated doc + bounded notes.
        val csv = bridge.massBatchImportCsv(fresh, "Name,Sats\nsatoshi_v,21\nllady,100\n")
        assertTrue(csv.contains("\"notes\":[") && csv.contains("\"doc\":"), csv)
        assertTrue(bridge.massBatchImportCsv(fresh, "junk,header\n\n").contains("\"doc\":"))

        // Corrupt wire → "".
        assertEquals("", bridge.massBatchPlan("junk", "[]"))
        assertEquals("", bridge.massBatchOp("junk", """{"op":"addRow"}"""))
    }

    @Test
    fun memePictureSeamAcceptsRemixExtraTags() {
        val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        val remixTags = """[["remix","${"b".repeat(64)}"],["p","${"c".repeat(64)}"]]"""
        val plain = bridge.composeMemePictureEventId(
            authorPubkey = author, caption = "remix test", altText = "",
            contentWarningReason = null, url = "https://cdn.example/m.png",
            sha256Hex = "a".repeat(64), mimeType = "image/png",
            sizeBytes = 1000L, width = 1080L, height = 1080L, nowSeconds = 100,
        )
        val remixed = bridge.composeMemePictureEventId(
            authorPubkey = author, caption = "remix test", altText = "",
            contentWarningReason = null, url = "https://cdn.example/m.png",
            sha256Hex = "a".repeat(64), mimeType = "image/png",
            sizeBytes = 1000L, width = 1080L, height = 1080L, nowSeconds = 100,
            extraTagsJson = remixTags,
        )
        assertNotNull(plain)
        assertNotNull(remixed)
        assertNotEquals(plain, remixed, "lineage tags change the event id")
        val frame = bridge.memePicturePublishMessage(
            authorPubkey = author, caption = "remix test", altText = "",
            contentWarningReason = null, url = "https://cdn.example/m.png",
            sha256Hex = "a".repeat(64), mimeType = "image/png",
            sizeBytes = 1000L, width = 1080L, height = 1080L,
            createdAtSeconds = 100, signatureHex = "d".repeat(128),
            extraTagsJson = """[["remix","${"b".repeat(64)}"]]""",
        )
        assertTrue(frame!!.contains("remix"), frame)
    }

    @Test
    fun memeVideoSeamAcceptsExtraTagsForTagsAndLicense() {
        val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        val extra = """[["t","plebchain"],["license","CC0-1.0"]]"""
        val base = { extraTags: String ->
            bridge.composeMemeVideoEventId(
                authorPubkey = author, caption = "clip #bitcoin", altText = "",
                contentWarningReason = null, portrait = true,
                url = "https://cdn.example/m.mp4", sha256Hex = "a".repeat(64),
                mimeType = "video/mp4", sizeBytes = 1000L, width = 1080L, height = 1920L,
                durationMs = 15_000L, nowSeconds = 100,
                extraTagsJson = extraTags,
            )
        }
        val plain = base("")
        val tagged = base(extra)
        assertNotNull(plain)
        assertNotNull(tagged)
        assertNotEquals(plain, tagged, "post-details tags + license change the event id")

        // The signed frame carries them, and the default stays byte-identical
        // to the pre-extraTags composition (old callers unchanged).
        val frame = bridge.memeVideoPublishMessage(
            authorPubkey = author, caption = "clip #bitcoin", altText = "",
            contentWarningReason = null, portrait = true,
            url = "https://cdn.example/m.mp4", sha256Hex = "a".repeat(64),
            mimeType = "video/mp4", sizeBytes = 1000L, width = 1080L, height = 1920L,
            durationMs = 15_000L, createdAtSeconds = 100, signatureHex = "d".repeat(128),
            extraTagsJson = extra,
        )
        assertTrue(frame!!.contains("[\"t\",\"plebchain\"]"), frame)
        assertTrue(frame.contains("[\"license\",\"CC0-1.0\"]"), frame)
        assertTrue(frame.contains("\"kind\":22"), frame)
    }
    @Test
    fun memeLookSeamsExposeCatalogAndMatrix() {
        val catalog = bridge.memeLooks()
        assertTrue(catalog.contains("\"id\":\"none\""), catalog)
        assertTrue(catalog.contains("\"id\":\"deepfry\""), catalog)
        assertTrue(catalog.contains("\"css\":\"grayscale(1) contrast(1.35) brightness(0.92)\""), catalog)

        // Composed matrix as a flat 20-float array; none/unknown → identity.
        val matrixJson = bridge.memeLookMatrix("noir")
        assertTrue(matrixJson.contains("\"matrix\":"), matrixJson)
        assertTrue(matrixJson.contains("\"blur\":0"), matrixJson)
        val identity = bridge.memeLookMatrix("none")
        assertTrue(identity.count { it == '1' } >= 4, identity)
        val dream = bridge.memeLookMatrix("dream")
        assertTrue(dream.contains("\"blur\":1.2"), dream)
        assertTrue(bridge.memeLookMatrix("hologram") == identity)

        // SetLook rides the command seam end-to-end.
        val empty = bridge.memeProjectNormalize("""{"v":1,"mode":"image"}""")
        val graded = bridge.memeApplyCommand(empty, """{"op":"look","look":"vhs"}""")
        assertTrue(graded.contains("\"look\":\"vhs\""), graded)
        assertTrue(bridge.memeApplyCommand(graded, """{"op":"look","look":"none"}""").contains("\"look\"").not())
    }

    @Test
    fun memeGifPlanAndLadderSeamsDriveTheIosEncoder() {
        val planJson = bridge.memeGifPlan("[100,100,200]", pinnedSec = 0.0)
        assertTrue(planJson.contains("\"capped\":false"), planJson)
        assertTrue(planJson.contains("\"delayMs\":200"), planJson)
        assertTrue(planJson.contains("\"durationSec\":0.4"), planJson)

        // Clamps hostile holds (20..1000 ms) and caps at 360 steps.
        val hostile = bridge.memeGifPlan("[5,9999]", 0.0)
        assertTrue(hostile.contains("\"delayMs\":20"), hostile)
        assertTrue(hostile.contains("\"delayMs\":1000"), hostile)
        val many = (1..400).joinToString(prefix = "[", postfix = "]") { "30" }
        val capped = bridge.memeGifPlan(many, 0.0)
        assertTrue(capped.contains("\"capped\":true"), capped)

        assertEquals("1080|608", bridge.memeGifLadderCanvas(1080, 608, 0))
        assertEquals("540|304", bridge.memeGifLadderCanvas(1080, 608, 1))
        assertEquals("", bridge.memeGifLadderCanvas(1080, 608, 4))
    }

    // ── Event-based extractor seams (decode-once stage, audit Phase 2) ──

    /** Fabricated events suffice here: these seams trust the gate, so the
     *  id need not match a signature — only hex shape and projections. */
    private fun bridgeEvent(
        kind: Int,
        tags: List<List<String>>,
        content: String = "",
        createdAt: Long = 1_710_000_000,
        pubkey: String = VALID_AUTHOR,
    ) = BusinessCoreBridge.Event(
        id = "aa".repeat(32),
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        relayUrl = "wss://relay.damus.io",
        signature = "",
    )

    @Test
    fun storyFromEventProjectsVerifiedStory() {
        val now = 1_710_050_000L
        val slide = bridge.storyFromEvent(
            bridgeEvent(
                kind = 30_315,
                tags = listOf(listOf("d", "slide-1"), listOf("expiration", "${now + 3_600}")),
                content = "https://blossom.example/story.jpg",
                createdAt = now - 60,
            ),
            now,
        )
        assertNotNull(slide)
        assertEquals("aa".repeat(32), slide["id"])
        assertEquals("slide-1", slide["d"])
        // Non-story kinds and expired slides drop.
        assertNull(bridge.storyFromEvent(bridgeEvent(kind = 1, tags = emptyList(), content = "x"), now))
        assertNull(
            bridge.storyFromEvent(
                bridgeEvent(
                    kind = 30_315,
                    tags = listOf(listOf("expiration", "$now")),
                    content = "https://blossom.example/story.jpg",
                    createdAt = now - 60,
                ),
                now,
            ),
        )
    }

    @Test
    fun interestSetFromEventReturnsHashtagsAndCreatedAtTogether() {
        val account = VALID_AUTHOR
        val head = bridge.interestSetFromEvent(
            bridgeEvent(
                kind = 30_015,
                tags = listOf(listOf("d", "interest"), listOf("t", "Bitcoin"), listOf("t", "nostr")),
            ),
            account,
        )
        assertNotNull(head)
        assertEquals(listOf("bitcoin", "nostr"), head["hashtags"])
        assertEquals(1_710_000_000L, (head["createdAt"] as Long))
        // Another author's head must not match.
        assertNull(
            bridge.interestSetFromEvent(
                bridgeEvent(kind = 30_015, tags = listOf(listOf("d", "interest")), pubkey = "bb".repeat(32)),
                account,
            ),
        )
    }

    @Test
    fun blockListFromEventProjectsBlockedSet() {
        val blocked = bridge.blockListFromEvent(
            bridgeEvent(
                kind = 10_004,
                tags = listOf(listOf("p", "cc".repeat(32)), listOf("p", "not-hex")),
            ),
            VALID_AUTHOR,
        )
        assertNotNull(blocked)
        assertEquals(listOf("cc".repeat(32)), blocked["pubkeys"])
        // A non-list event has no blocked set.
        assertNull(bridge.blockListFromEvent(bridgeEvent(kind = 1, tags = emptyList()), VALID_AUTHOR))
    }

    @Test
    fun extractNotificationFromEventMatchesFrameSeamShape() {
        // Another author replies mentioning the account — own events
        // never count (extractor rule).
        val reply = bridge.extractNotificationFromEvent(
            bridgeEvent(
                kind = 1,
                tags = listOf(listOf("p", VALID_AUTHOR), listOf("e", "cc".repeat(32))),
                content = "replying to you",
                pubkey = "bb".repeat(32),
            ),
            VALID_AUTHOR,
        )
        assertNotNull(reply)
        assertEquals("replying to you", reply["summary"])
        assertEquals(-1L, reply["amountMsat"])
    }


    private companion object {
        // Verbatim relay frames from contracts/nostr/fixtures/verification-vectors.json.
        const val VALID_AUTHOR = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        const val VALID_TEXT_NOTE_MESSAGE =
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]"""
        const val VALID_PROFILE_METADATA_MESSAGE =
            """["EVENT","sub1",{"kind":0,"created_at":1710000100,"tags":[],"content":"{\"name\":\"satoshi\",\"display_name\":\"Satoshi ₿\",\"about\":\"test vector\"}","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"1bdf4e4f9f406ce12418060a4f6296fb1f983d5cd75d6cd583263e09ae78d2a5","sig":"f21868a6a8be0823603064a38832404508746fd6ce3c92a627eabc55b0562bd6477901518273b2c483c628c7b1be74cb444a33fbbe26d174fc211cca234c11a6"}]"""
        const val VALID_ID_WRONG_SIGNATURE_MESSAGE =
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"tampered but re-identified","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"2cbc3c8affa0828e03b11f975337317f8e415397933fc87069265e17f719e95b","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]"""
        const val VALID_ESCAPED_CONTENT_MESSAGE =
            """["EVENT","sub1",{"kind":1,"created_at":1710000200,"tags":[["e","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"],["p","cccccccccccccccccccccccccccccccc"]],"content":"line1\nline2 \"quoted\" ₿end","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"6bbba7020543b6d2fbd740a5a387cd92054716342d2b6389692fec5257f5e7fd","sig":"6678f8524132e35027dd2403993fe012a3728b100cfce94a656a9a5da39f37802185d8e6d7120518436c773e64d8e19758a6ba914fd98a0fd86339caa6adf61b"}]"""
        const val GIPHY_TWO_ITEM_RESPONSE =
            "{\"data\":[{\"id\":\"a\",\"images\":{\"original\":{\"url\":\"https://media.giphy.com/media/a/giphy.gif\",\"width\":\"480\",\"height\":\"480\"},\"fixed_height_small\":{\"url\":\"https://media.giphy.com/media/a/100.gif\",\"width\":\"100\",\"height\":\"100\"}}},{\"id\":\"b\",\"images\":{\"original\":{\"url\":\"https://media.giphy.com/media/b/giphy.gif\",\"width\":\"480\",\"height\":\"480\"},\"fixed_height_small\":{\"url\":\"https://media.giphy.com/media/b/100.gif\",\"width\":\"100\",\"height\":\"100\"}}}],\"pagination\":{\"offset\":0,\"count\":2,\"total_count\":95}}"
    }
}
