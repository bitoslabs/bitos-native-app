package space.bitos.core.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-012 bridge contract: the JSON shapes crossing to Swift are stable and
 * the pure notification rules match the common suites.
 */
class NotificationBridgeJsonTest {

    private val bridge = BusinessCoreBridge()

    private fun itemsJson(vararg items: String) = "[${items.joinToString(",")}]"

    @Test
    fun groupingJsonShapeIsStable() {
        val json = bridge.groupNotificationsJson(
            itemsJson(
                """{"id":"a","authorPubkey":"pk1","kind":2,"targetEventId":"note1","summary":"like","createdAt":300}""",
                """{"id":"b","authorPubkey":"pk2","kind":2,"targetEventId":"note1","summary":"like","createdAt":200}""",
                """{"id":"c","authorPubkey":"pk3","kind":5,"targetEventId":"","summary":"","createdAt":100}""",
            ),
            nowSeconds = 300,
        )!!
        assertTrue(json.contains("\"sections\":[{\"day\":0,\"groups\":["), json)
        // Reaction group aggregates (actorCount 2, oldest id = b), follow stays solo,
        // groups carry the summed zap msat (0 for non-zaps).
        assertTrue(
            json.contains(
                "\"id\":\"b\",\"kind\":2,\"actors\":[\"pk1\",\"pk2\"],\"actorCount\":2," +
                    "\"target\":\"note1\",\"summary\":\"like\",\"newest\":300,\"msat\":0,\"items\":[\"a\",\"b\"]",
            ),
            json,
        )
        assertTrue(json.contains("\"kind\":5,\"actors\":[\"pk3\"],\"actorCount\":1,\"target\":\"\""), json)
        assertTrue(json.endsWith("]}"), json)
    }

    @Test
    fun groupingJsonRejectsMalformedInput() {
        assertNull(bridge.groupNotificationsJson("not json", nowSeconds = 0))
        assertNull(bridge.groupNotificationsJson("""[{"id":"a"}]""", nowSeconds = 0))
    }

    @Test
    fun tabAndActivityPredicatesFollowSharedOrdinals() {
        assertTrue(bridge.notificationTabMatches(1, 0, isRead = true)) // mention, ALL
        assertTrue(bridge.notificationTabMatches(0, 1, isRead = false)) // reply, UNREAD
        assertEquals(false, bridge.notificationTabMatches(0, 1, isRead = true))
        assertEquals(false, bridge.notificationTabMatches(2, 3, isRead = false)) // reaction not REPLIES
        assertTrue(bridge.notificationActivityMatches(4, 1)) // zap, ZAPS
        assertEquals(false, bridge.notificationActivityMatches(0, 1))
        assertTrue(bridge.notificationActivityMatches(0, 0)) // NONE passes
        // Out-of-range inputs fall back safely.
        assertEquals(false, bridge.notificationTabMatches(99, 0, isRead = false))
        assertTrue(bridge.notificationTabMatches(0, 99, isRead = true)) // unknown tab → ALL
    }

    @Test
    fun notificationsRequestCoversVideoComments() {
        val account = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        val request = bridge.notificationsRequest("inbox", account)
        // Kind-1111 comments ride the #p participant filter…
        assertTrue(request.contains(""""kinds":[1,7,6,16,1111,3],"#p":["$account"]"""), request)
        // …and the uppercase #P root-author filter for strict NIP-22 clients.
        assertTrue(request.contains(""""kinds":[1111],"#P":["$account"]"""), request)
        // Zap receipts keep their dedicated filter (web parity).
        assertTrue(request.contains(""""kinds":[9735],"#p":["$account"]"""), request)
    }

    @Test
    fun eventsByIdsRequestIsBoundedAndValidated() {
        val request = bridge.eventsByIdsRequest("sub", listOf("a".repeat(64), "zz-not-hex"))!!
        assertTrue(request.contains(""""ids":["${"a".repeat(64)}"]}"""))
        assertNull(bridge.eventsByIdsRequest("sub", listOf("zz-not-hex")))
        assertNull(bridge.eventsByIdsRequest("sub", emptyList()))
        val tooMany = List(120) { index -> index.toString(16).padStart(64, '0') }
        val capped = bridge.eventsByIdsRequest("sub", tooMany)!!
        assertEquals(100, Regex("[0-9a-f]{64}").findAll(capped).count())
    }

    @Test
    fun originNoteFromFrameVerifiesAndFiltersById() {
        val note = bridge.originNoteFromFrame(
            VALID_TEXT_NOTE_MESSAGE,
            "wss://relay.damus.io",
            listOf("10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"),
        )!!
        assertEquals("10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a", note["id"])
        assertEquals("gm from BitOS", note["excerpt"])
        assertEquals(1, note["kind"])
        assertEquals("gm from BitOS", note["content"])

        // Not in the wanted set → null.
        assertNull(
            bridge.originNoteFromFrame(VALID_TEXT_NOTE_MESSAGE, "wss://relay.damus.io", listOf("b".repeat(64))),
        )
        // Tampered signature → null.
        assertNull(
            bridge.originNoteFromFrame(
                VALID_ID_WRONG_SIGNATURE_MESSAGE,
                "wss://relay.damus.io",
                listOf("2cbc3c8affa0828e03b11f975337317f8e415397933fc87069265e17f719e95b"),
            ),
        )
    }

    private companion object {
        const val VALID_TEXT_NOTE_MESSAGE =
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]"""
        const val VALID_ID_WRONG_SIGNATURE_MESSAGE =
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"tampered but re-identified","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"2cbc3c8affa0828e03b11f975337317f8e415397933fc87069265e17f719e95b","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]"""
    }
}
