package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-018 relays-manager contract (NIP-65 parity): bounded managed set,
 * versioned wire codec and the `r`-tag projection.
 */
class RelayListTest {

    private fun entry(url: String, read: Boolean = true, write: Boolean = true): RelayEntry? {
        val parsed = RelayUrl.parse(url) ?: return null
        return RelayEntry(parsed, read, write)
    }

    private fun relay(url: String): RelayUrl = RelayUrl.parse(url)!!

    @Test
    fun normalizeDedupesDropsRolelessAndCaps() {
        val a = RelayEntry(relay("wss://a.relay"), read = true, write = true)
        val dupA = RelayEntry(relay("wss://a.relay"), read = false, write = true)
        val roleless = RelayEntry(relay("wss://b.relay"), read = false, write = false)
        val filler = (1..20).mapNotNull { entry("wss://r$it.relay") }
        val normalized = RelayListContract.normalize(listOf(a, dupA, roleless) + filler)
        // Deduped, role-less dropped, capped at 16, insertion order kept.
        assertEquals("wss://a.relay", normalized.first().url.value)
        assertEquals(true, normalized.first().read) // first occurrence wins
        assertEquals(RelayListContract.MAX_RELAYS, normalized.size)
        assertTrue(normalized.none { it.url.value == "wss://b.relay" })
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val entries = listOf(
            RelayEntry(relay("wss://relay.damus.io"), read = true, write = true),
            RelayEntry(relay("wss://nos.lol"), read = true, write = true),
            RelayEntry(relay("wss://relay.nostr.band"), read = true, write = false),
        )
        val wire = RelayListContract.encode(entries)
        assertTrue(wire.contains("\"v\":${RelayListContract.SCHEMA_VERSION}"))
        assertEquals(entries, RelayListContract.decode(wire))
    }

    @Test
    fun corruptOrOversizedWireDecodesToEmpty() {
        assertEquals(emptyList(), RelayListContract.decode("not json at all"))
        assertEquals(emptyList(), RelayListContract.decode("{\"v\":1,\"relays\":[{\"u\":123}]}"))
        assertEquals(emptyList(), RelayListContract.decode("{\"v\":1}"))
        // Invalid urls inside the wire are dropped silently.
        assertEquals(
            listOf(RelayEntry(relay("wss://ok.relay"), true, true)),
            RelayListContract.decode(
                "{\"v\":1,\"relays\":[{\"u\":\"http://bad.relay\",\"r\":true,\"w\":true},{\"u\":\"wss://ok.relay\",\"r\":true,\"w\":true}]}",
            ),
        )
        assertEquals(emptyList(), RelayListContract.decode("x".repeat(RelayListContract.MAX_WIRE_LENGTH + 1)))
    }

    @Test
    fun nip65TagShapesFollowTheSpec() {
        val tags = RelayListContract.nip65Tags(
            listOf(
                RelayEntry(relay("wss://both.relay"), read = true, write = true),
                RelayEntry(relay("wss://ro.relay"), read = true, write = false),
                RelayEntry(relay("wss://wo.relay"), read = false, write = true),
                RelayEntry(relay("wss://none.relay"), read = false, write = false),
            ),
        )
        assertEquals(
            listOf(
                listOf("r", "wss://both.relay"),
                listOf("r", "wss://ro.relay", "read"),
                listOf("r", "wss://wo.relay", "write"),
            ),
            tags,
        )
    }
}

/**
 * NIP-65 kind-10002 composition through the shared composer (PUB parity
 * with the follow-list/bookmark-list tests).
 */
class RelayListComposerTest {

    @Test
    fun composeRelayListBuildsNip65Event() {
        val author = "a".repeat(64)
        val composer = space.bitos.core.publish.NoteComposer(clock = { 1_700_000_000 })
        val note = composer.composeRelayList(
            author,
            listOf(
                RelayEntry(RelayUrl.parse("wss://relay.damus.io")!!, read = true, write = true),
                RelayEntry(RelayUrl.parse("wss://relay.nostr.band")!!, read = true, write = false),
            ),
        )
        requireNotNull(note)
        assertEquals(RelayListContract.KIND, note.kind)
        assertEquals("", note.content)
        assertEquals(
            listOf(
                listOf("r", "wss://relay.damus.io"),
                listOf("r", "wss://relay.nostr.band", "read"),
            ),
            note.tags,
        )
        // Canonical id: recomputing through the codec must agree.
        assertEquals(
            note.idHex,
            space.bitos.core.nostr.NostrEventCodec.computeId(
                hasher = space.bitos.core.nostr.Sha256EventHasher,
                pubkey = author,
                createdAt = note.createdAtSeconds,
                kind = note.kind,
                tags = note.tags,
                content = note.content,
            ),
        )
    }

    @Test
    fun composeRelayListRejectsInvalidInput() {
        val composer = space.bitos.core.publish.NoteComposer(clock = { 0 })
        assertNull(composer.composeRelayList("zz", emptyList())) // bad pubkey
        assertNull(composer.composeRelayList("a".repeat(64), emptyList())) // empty set
        assertNull(
            composer.composeRelayList(
                "a".repeat(64),
                listOf(RelayEntry(RelayUrl.parse("wss://x.relay")!!, read = false, write = false)),
            ),
        ) // role-less only
    }
}
