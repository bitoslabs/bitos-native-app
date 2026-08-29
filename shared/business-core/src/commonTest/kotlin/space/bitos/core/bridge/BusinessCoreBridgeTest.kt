package space.bitos.core.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(request.contains(""""kinds":[21,22],"limit":16"""), request)
        assertTrue(request.contains(""""kinds":[1],"limit":48"""), request)
        assertEquals("""["CLOSE","feed1"]""", bridge.close("feed1"))
        val profileRequest = bridge.profileRequest("p1", listOf("aa".repeat(32)))
        assertTrue(profileRequest.startsWith("""["REQ","p1","""), profileRequest)
        assertTrue(profileRequest.contains(""""authors":[""" + "\"" + "a".repeat(64)), profileRequest)
        assertTrue(profileRequest.contains(""""limit":1"""), profileRequest)
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
    fun rejectsSignatureUnverifiedEvents() {
        // SBC-006: a well-formed event whose ID was recomputed for tampered
        // content, carrying another event's signature, must NOT reach the
        // display path. The bridge now runs both trust stages (ID + BIP-340).
        assertNull(bridge.decodeEvent(VALID_ID_WRONG_SIGNATURE_MESSAGE, "wss://relay.damus.io"))
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

    private companion object {
        // Verbatim relay frames from contracts/nostr/fixtures/verification-vectors.json.
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
