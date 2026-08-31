package space.bitos.core.nostr

import space.bitos.core.model.EventId
import space.bitos.core.model.Pubkey
import space.bitos.core.model.RelayUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NostrEventCodecTest {

    private val hasher = Sha256EventHasher
    private val pubkey = "aa".repeat(32)

    @Test
    fun canonicalSerializationMatchesNip01Vectors() {
        val ser1 = NostrEventCodec.serializeForId(
            pubkey, 1_710_000_000, 1,
            listOf(listOf("e", "bb".repeat(32)), listOf("p", "cc".repeat(32))),
            "Hello BitOS \"quote\"\nline2\ttab",
        )
        assertEquals(
            "[0,\"$pubkey\",1710000000,1," +
                "[[\"e\",\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"],[\"p\",\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\"]]," +
                "\"Hello BitOS \\\"quote\\\"\\nline2\\ttab\"]",
            ser1,
        )

        val ser2 = NostrEventCodec.serializeForId(pubkey, 1_710_000_100, 0, emptyList(), "{\"name\":\"satoshi\",\"about\":\"₿ctrl\"}")
        assertTrue(ser2.endsWith("\"{\\\"name\\\":\\\"satoshi\\\",\\\"about\\\":\\\"₿\\u0007ctrl\\\"}\"]"))

        assertEquals("[0,\"$pubkey\",1710000200,1,[],\"\"]", NostrEventCodec.serializeForId(pubkey, 1_710_000_200, 1, emptyList(), ""))
    }

    @Test
    fun computedIdsMatchReferenceVectors() {
        assertEquals(
            "af7c7803bebd90ba8be8b97b228abbeeaf595914b20715c36beedca467f1517e",
            NostrEventCodec.computeId(hasher, pubkey, 1_710_000_000, 1, listOf(listOf("e", "bb".repeat(32)), listOf("p", "cc".repeat(32))), "Hello BitOS \"quote\"\nline2\ttab"),
        )
        assertEquals("396e4c431224ef316768c023ea3d48581996db202a57a094d8624f3fcb903267", NostrEventCodec.computeId(hasher, pubkey, 1_710_000_200, 1, emptyList(), ""))
    }

    @Test
    fun computedIdWithControlCharacterMatchesVector() {
        // Content contains a real U+0007 control character between the bitcoin
        // sign and "ctrl"; the serialized form escapes it as \u0007.
        assertEquals(
            "a6c57506d3d1caf24e0db7338cfcde55b0fa50517f25b943a3a235bd7403d4db",
            NostrEventCodec.computeId(hasher, pubkey, 1_710_000_100, 0, emptyList(), "{\"name\":\"satoshi\",\"about\":\"₿\u0007ctrl\"}"),
        )
    }

    @Test
    fun sha256HasherMatchesKnownDigest() {
        // RFC 4231 test case for SHA-256: "abc"
        val digest = hasher.sha256("abc".encodeToByteArray()).toLowercaseHex()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
    }

    private fun eventJson(
        id: String,
        pk: String = pubkey,
        createdAt: Long = 1_710_000_000,
        kind: Int = 1,
        tags: String = "[[\"t\",\"bitcoin\"]]",
        content: String = "gm",
        sig: String? = null,
    ): String {
        val sigPart = sig?.let { ",\"sig\":\"$it\"" } ?: ""
        return """{"id":"$id","pubkey":"$pk","created_at":$createdAt,"kind":$kind,"tags":$tags,"content":${quote(content)}$sigPart}"""
    }

    private fun quote(raw: String): String = buildString {
        append('"')
        for (c in raw) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
        append('"')
    }

    @Test
    fun decodesVerifiedRelayEvent() {
        val id = NostrEventCodec.computeId(hasher, pubkey, 1_710_000_000, 1, listOf(listOf("t", "bitcoin")), "gm")
        val relay = RelayUrl.parse("wss://relay.damus.io")!!
        val event = NostrEventCodec.decodeRelayEvent(
            hasher,
            """["EVENT","sub1",${eventJson(id)}]""",
            relay,
        )
        assertEquals(EventId.parse(id)!!, event.id)
        assertEquals(Pubkey.parse(pubkey)!!, event.pubkey)
        assertEquals(1, event.tags.size)
        assertEquals(relay, event.receivedFromRelay)
        assertTrue(NostrEventCodec.verifyId(hasher, event))
    }

    @Test
    fun rejectsIdHashMismatch() {
        val wrongId = "00".repeat(32)
        assertFailsWith<NostrEventCodec.Rejected> {
            NostrEventCodec.decodeEventObject(hasher, eventJson(wrongId), null)
        }
    }

    @Test
    fun rejectsMalformedStructures() {
        assertFailsWith<NostrEventCodec.Rejected> { NostrEventCodec.decodeRelayEvent(hasher, """{"not":"an array"}""", null) }
        assertFailsWith<NostrEventCodec.Rejected> { NostrEventCodec.decodeRelayEvent(hasher, """["NOTICE","x"]""", null) }
        assertFailsWith<NostrEventCodec.Rejected> { NostrEventCodec.decodeEventObject(hasher, """{"id":"zz","pubkey":"$pubkey"}""", null) }
        assertFailsWith<NostrEventCodec.Rejected> {
            NostrEventCodec.decodeEventObject(hasher, eventJson("0".repeat(64), pk = "not-hex"), null)
        }
        assertFailsWith<NostrEventCodec.Rejected> {
            NostrEventCodec.decodeEventObject(hasher, eventJson("0".repeat(64), createdAt = -5), null)
        }
    }

    @Test
    fun rejectsOversizedPayloads() {
        val hugeContent = "x".repeat(70_000)
        assertFailsWith<NostrEventCodec.Rejected> {
            NostrEventCodec.decodeEventObject(hasher, eventJson("0".repeat(64), content = hugeContent), null)
        }
    }

    @Test
    fun ignoresSignatureThatIsNotHex64() {
        val id = NostrEventCodec.computeId(hasher, pubkey, 1_710_000_000, 1, listOf(listOf("t", "bitcoin")), "gm")
        val event = NostrEventCodec.decodeEventObject(hasher, eventJson(id, sig = "short"), null)
        assertNull(event.signature)
    }

    @Test
    fun encodesRequestAndCloseMessages() {
        assertEquals(
            """["REQ","feed1",{"kinds":[1],"limit":50}]""",
            NostrEventCodec.encodeRequest("feed1", """{"kinds":[1],"limit":50}"""),
        )
        assertEquals(
            """["REQ","bitz",{"kinds":[21,22],"limit":16},{"kinds":[1],"limit":48}]""",
            NostrEventCodec.encodeRequest(
                "bitz",
                listOf("""{"kinds":[21,22],"limit":16}""", """{"kinds":[1],"limit":48}"""),
            ),
        )
        assertEquals("""["CLOSE","feed1"]""", NostrEventCodec.encodeClose("feed1"))
        assertFailsWith<NostrEventCodec.Rejected> { NostrEventCodec.encodeRequest("x".repeat(200), "{}") }
        assertFailsWith<NostrEventCodec.Rejected> { NostrEventCodec.encodeRequest("bitz", emptyList()) }
    }

    @Test
    fun extractsOnlyBoundedRelayEventSubscriptionIds() {
        assertEquals(
            "bitos-older-7",
            NostrEventCodec.relayEventSubscriptionId("""["EVENT","bitos-older-7",{}]"""),
        )
        assertNull(NostrEventCodec.relayEventSubscriptionId("""["NOTICE","bitos-older-7"]"""))
        assertNull(NostrEventCodec.relayEventSubscriptionId("""["EVENT",7,{}]"""))
        assertNull(NostrEventCodec.relayEventSubscriptionId("""["EVENT","${"x".repeat(129)}",{}]"""))
    }

    @Test
    fun parsesOnlyBoundedEoseSubscriptionIds() {
        // Wire values mirror contracts/nostr/fixtures/pagination-v1.json.
        assertEquals("bitos-older-1", NostrEventCodec.relayEoseSubscriptionId("""["EOSE","bitos-older-1"]"""))
        assertNull(NostrEventCodec.relayEoseSubscriptionId("""["EVENT","bitos-older-7",{}]"""))
        assertNull(NostrEventCodec.relayEoseSubscriptionId("""["EOSE",7]"""))
        assertNull(NostrEventCodec.relayEoseSubscriptionId("""["EOSE","${"x".repeat(129)}"]"""))
    }

    @Test
    fun signatureOutcomeCacheIsDeterministicForRepeats() {
        // The same verified frame twice: the repeat hits the outcome cache
        // and must return exactly what recomputation returned.
        val relay = RelayUrl.parse("wss://relay.damus.io")!!
        val event = NostrEventCodec.decodeRelayEvent(hasher, VALID_SIGNED_FRAME, relay)
        assertTrue(NostrEventCodec.verifySignature(hasher, event))
        assertTrue(NostrEventCodec.verifySignature(hasher, event))

        // Negative outcomes cache too — an unrelated garbage signature is
        // false on first sight and on the cached repeat.
        val id = NostrEventCodec.computeId(hasher, pubkey, 1_710_000_000, 1, listOf(listOf("t", "bitcoin")), "gm")
        val unsignedShape = NostrEventCodec.decodeEventObject(hasher, eventJson(id, sig = "11".repeat(64)), null)
        assertFalse(NostrEventCodec.verifySignature(hasher, unsignedShape))
        assertFalse(NostrEventCodec.verifySignature(hasher, unsignedShape))
    }

    @Test
    fun signatureOutcomeCacheKeysOnIdPubkeyAndSignature() {
        // Cache-safety: a forged event reusing a VERIFIED event's id and
        // signature under a different pubkey must NOT inherit the cached
        // true outcome. The cache key commits all three verification inputs.
        val relay = RelayUrl.parse("wss://relay.damus.io")!!
        val verified = NostrEventCodec.decodeRelayEvent(hasher, VALID_SIGNED_FRAME, relay)
        assertTrue(NostrEventCodec.verifySignature(hasher, verified))

        val forged = space.bitos.core.model.NostrEvent(
            id = verified.id,
            pubkey = Pubkey.parse(pubkey)!!,
            createdAt = verified.createdAt,
            kind = verified.kind,
            tags = verified.tags,
            content = verified.content,
            signature = verified.signature,
            receivedFromRelay = null,
        )
        assertFalse(NostrEventCodec.verifySignature(hasher, forged))
    }

    private companion object {
        // Verbatim signed relay frame from the verification-vector fixtures.
        const val VALID_SIGNED_FRAME =
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]"""
    }
}

class RelayUrlTest {

    @Test
    fun parsesValidWebSocketRelayUrls() {
        assertEquals("wss://relay.damus.io", RelayUrl.parse("wss://relay.damus.io")?.value)
        assertEquals("wss://relay.damus.io", RelayUrl.parse("wss://relay.damus.io/")?.value)
        assertEquals("wss://relay.damus.io", RelayUrl.parse(" wss://relay.damus.io ")?.value)
        assertEquals("relay.damus.io", RelayUrl.parse("wss://relay.damus.io")?.host())
        assertEquals("wss://nostr-01.yakihonne.com", RelayUrl.parse("wss://nostr-01.yakihonne.com")?.value)
    }

    @Test
    fun rejectsInvalidUrls() {
        assertNull(RelayUrl.parse("https://relay.damus.io"))
        assertNull(RelayUrl.parse("wss://"))
        assertNull(RelayUrl.parse("not a url"))
        assertNull(RelayUrl.parse("wss://relay.damus.io/query?injection=';drop"))
        assertNull(RelayUrl.parse(""))
    }
}
