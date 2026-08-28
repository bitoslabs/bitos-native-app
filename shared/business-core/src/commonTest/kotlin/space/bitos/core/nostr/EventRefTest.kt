package space.bitos.core.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-009 root-resolution refs: note1/nevent1/naddr1 (± `nostr:` prefix)
 * parse into id / NIP-33 coordinate pointers with author + relay hints;
 * invalid inputs yield null.
 */
class EventRefTest {

    private val eventId = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    private val author = "1111111111111111111111111111111111111111111111111111111111111111"

    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun nevent1(id: String, authorHex: String?, relays: List<String>): String {
        var payload = EventRefs.encodeTlvEntry(0, bytes(id))
        authorHex?.let { payload += EventRefs.encodeTlvEntry(1, bytes(it)) }
        relays.forEach { payload += EventRefs.encodeTlvEntry(2, it.encodeToByteArray()) }
        return Nip27.encodeEntity("nevent", payload)
    }

    private fun naddr1(kind: Int, pubkey: String, d: String, relays: List<String>): String {
        val special = "$kind:$pubkey:$d"
        var payload = EventRefs.encodeTlvEntry(0, special.encodeToByteArray())
        payload += EventRefs.encodeTlvEntry(1, bytes(pubkey))
        relays.forEach { payload += EventRefs.encodeTlvEntry(2, it.encodeToByteArray()) }
        return Nip27.encodeEntity("naddr", payload)
    }

    @Test
    fun note1ParsesToAnIdPointer() {
        val note1 = Nip27.encodeEntity("note", bytes(eventId))
        val ref = EventRefs.parse(note1)
        assertEquals(EventRef.ById(eventId, null, emptyList()), ref)
        // nostr: prefix tolerated.
        assertEquals(ref, EventRefs.parse("nostr:$note1"))
    }

    @Test
    fun nevent1CarriesAuthorAndBoundedRelayHints() {
        val relays = (1..6).map { "wss://r$it.example" }
        val ref = EventRefs.parse(nevent1(eventId, author, relays)) as EventRef.ById
        assertEquals(eventId, ref.id)
        assertEquals(author, ref.authorPubkey)
        assertEquals(relays.take(EventRefs.MAX_RELAY_HINTS), ref.relayHints)
    }

    @Test
    fun naddr1ParsesToACoordinatePointer() {
        val ref = EventRefs.parse(naddr1(30023, author, "my-list", listOf("wss://relay.example"))) as EventRef.ByCoordinate
        assertEquals(30023, ref.kind)
        assertEquals(author, ref.pubkey)
        assertEquals("my-list", ref.d)
        assertEquals("wss://relay.example", ref.relayHints.single())
    }

    @Test
    fun invalidRefsYieldNull() {
        assertNull(EventRefs.parse("npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsx5ep8"))
        assertNull(EventRefs.parse("note1invalidchecksum"))
        assertNull(EventRefs.parse("https://example.com/note1whatever"))
        assertNull(EventRefs.parse(""))
        // nevent without a type-0 id is not addressable.
        assertNull(EventRefs.parse(Nip27.encodeEntity("nevent", EventRefs.encodeTlvEntry(2, "wss://x".encodeToByteArray()))))
        // naddr with a malformed coordinate is not addressable.
        assertNull(EventRefs.parse(Nip27.encodeEntity("naddr", EventRefs.encodeTlvEntry(0, "no-colons".encodeToByteArray()))))
    }

    @Test
    fun requestFilterTargetsIdOrNewestCoordinateVersion() {
        val byId = EventRefs.requestFilter(EventRef.ById(eventId, null, emptyList()))
        assertTrue(byId.contains("\"ids\""), byId)
        val byCoord = EventRefs.requestFilter(EventRef.ByCoordinate(30023, author, "my-list", author, emptyList()))
        assertTrue(byCoord.contains("\"#d\"") && byCoord.contains("\"kinds\":[30023]"), byCoord)
    }
}
