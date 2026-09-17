package space.bitos.core.identity.nip46

import space.bitos.core.identity.LocalKeyEventSigner
import space.bitos.core.identity.NostrKeyCodec
import space.bitos.core.identity.SignEventRequest
import space.bitos.core.identity.SignEventResult
import space.bitos.core.identity.SignedEvent
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SBC-008b protocol fixtures: URI variants (valid, malformed, oversized,
 * relay lists), envelope codec round-trips, NIP-44 transport wrap and the
 * connection reducer over correlated, stale, mismatched, denied, expired,
 * cancelled and relay-switched cases.
 */
class Nip46ProtocolTest {

    private val hasher = Sha256EventHasher

    // Client transport keypair (fixtures; public by construction).
    private val clientSecret = "d2ad3c3c9e7b0f4f6a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718"
    private val clientPubkey = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"

    // Bunker (signer) keypair — also the ACCOUNT key the bunker holds.
    private val bunkerSecret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val bunkerPubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    private fun deterministicAux() = "00".repeat(32).hexToBytes()

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    // ── URI parser ─────────────────────────────────────────────────────

    @Test
    fun parsesBunkerUriWithEncodedRelaysAndSecret() {
        val connection = Nip46Uri.parseBunker(
            "bunker://$bunkerPubkey?relay=wss%3A%2F%2Frelay1.example.com&relay=wss://relay2.example.com&secret=abc123",
        )
        assertTrue(connection != null)
        assertEquals(bunkerPubkey, connection.remotePubkeyHex)
        assertEquals(listOf("wss://relay1.example.com", "wss://relay2.example.com"), connection.relays.map { it.value })
        assertEquals("abc123", connection.secret)
        assertNull(connection.perms)
    }

    @Test
    fun parsesBunkerUriWithNpubKey() {
        val npub = NostrKeyCodec.npub(bunkerPubkey)!!
        val connection = Nip46Uri.parseBunker("bunker://$npub?relay=wss://relay.damus.io")
        assertTrue(connection != null)
        assertEquals(bunkerPubkey, connection.remotePubkeyHex)
    }

    @Test
    fun rejectsMalformedBunkerUris() {
        assertNull(Nip46Uri.parseBunker(""))
        assertNull(Nip46Uri.parseBunker("nostrconnect://$bunkerPubkey?relay=wss://r.example.com"))
        assertNull(Nip46Uri.parseBunker("bunker://zz?relay=wss://r.example.com"))
        assertNull(Nip46Uri.parseBunker("bunker://$bunkerPubkey")) // relays mandatory
        assertNull(Nip46Uri.parseBunker("bunker://$bunkerPubkey?relay=http://r.example.com")) // wss only
        assertNull(Nip46Uri.parseBunker("bunker://$bunkerPubkey?relay=ws://r.example.com")) // wss only, stricter than feed
        assertNull(Nip46Uri.parseBunker("bunker://$bunkerPubkey?relay=" + "wss://r.example.com/".repeat(200)))
    }

    @Test
    fun capsSignerRelaysAtFive() {
        val sixRelays = (1..6).joinToString("&") { "relay=wss://relay$it.example.com" }
        val connection = Nip46Uri.parseBunker("bunker://$bunkerPubkey?$sixRelays")!!
        assertEquals(5, connection.relays.size)
    }

    @Test
    fun parsesNostrconnectUri() {
        val connection = Nip46Uri.parseNostrconnect(
            "nostrconnect://$clientPubkey?relay=wss%3A%2F%2Frelay1.example.com&perms=sign_event%3A1&secret=s3cret",
        )
        assertTrue(connection != null)
        assertEquals(clientPubkey, connection.remotePubkeyHex)
        assertEquals("s3cret", connection.secret)
        assertEquals("sign_event:1", connection.perms)
    }

    @Test
    fun buildsRoundTrippablePairingUri() {
        val relays = listOf("wss://relay1.example.com", "wss://relay2.example.com")
            .mapNotNull { space.bitos.core.model.RelayUrl.parse(it) }
        val uri = Nip46Uri.buildNostrconnect(clientPubkey, relays, secret = "pair-secret", appName = "BitOS")!!
        assertTrue(uri.startsWith("nostrconnect://$clientPubkey?relay=wss%3A%2F%2Frelay1.example.com"))
        val parsed = Nip46Uri.parseNostrconnect(uri)!!
        assertEquals(clientPubkey, parsed.remotePubkeyHex)
        assertEquals(2, parsed.relays.size)
        assertEquals("pair-secret", parsed.secret)
        assertNull(parsed.perms) // name/metadata params are not persisted policy
    }

    @Test
    fun buildNostrconnectRejectsBadInputs() {
        val relays = listOfNotNull(space.bitos.core.model.RelayUrl.parse("wss://relay.example.com"))
        assertNull(Nip46Uri.buildNostrconnect("zz", relays, "s"))
        assertNull(Nip46Uri.buildNostrconnect(clientPubkey, emptyList(), "s"))
        assertNull(Nip46Uri.buildNostrconnect(clientPubkey, relays, ""))
        assertNull(Nip46Uri.buildNostrconnect(clientPubkey, relays, "s".repeat(257)))
    }

    // ── Envelope codec ────────────────────────────────────────────────

    @Test
    fun requestEnvelopeRoundTrips() {
        val json = Nip46Wire.encodeRequest("req-42", "sign_event", listOf("""{"kind":1,"content":"hi"}"""))!!
        assertEquals("""{"id":"req-42","method":"sign_event","params":["{\"kind\":1,\"content\":\"hi\"}"]}""", json)
        val decoded = Nip46Wire.decodeRequest(json)!!
        assertEquals("req-42", decoded.id)
        assertEquals("sign_event", decoded.method)
        assertEquals(listOf("""{"kind":1,"content":"hi"}"""), decoded.params)
    }

    @Test
    fun responseEnvelopeRoundTrips() {
        val json = Nip46Wire.encodeResponse("req-42", "ack")!!
        assertEquals("""{"id":"req-42","result":"ack"}""", json)
        val decoded = Nip46Wire.decodeResponse(json)!!
        assertEquals("req-42", decoded.id)
        assertEquals("ack", decoded.result)
        assertNull(decoded.error)

        val errorJson = Nip46Wire.encodeResponse("req-42", null, "denied: user refused")!!
        val error = Nip46Wire.decodeResponse(errorJson)!!
        assertTrue(error.isError)
    }

    @Test
    fun envelopeCodecRejectsDisallowedMethodsAndBounds() {
        assertNull(Nip46Wire.encodeRequest("id", "nip04_encrypt", listOf("x"))) // not in the active slice
        assertNull(Nip46Wire.encodeRequest("", "connect", emptyList()))
        assertNull(Nip46Wire.encodeRequest("id", "connect", List(9) { "x" }))
        assertNull(Nip46Wire.decodeRequest("{not json"))
        assertNull(Nip46Wire.decodeRequest("""{"method":"connect"}""")) // no id
        assertNull(Nip46Wire.decodeResponse("""{"id":"x"}""")) // neither result nor error
    }

    @Test
    fun buildsUnsignedKind24133RequestEvent() {
        val envelope = Nip46Wire.encodeRequest("req-7", "get_public_key", emptyList())!!
        val encrypted = Nip46Crypto.encrypt(envelope, clientSecret, bunkerPubkey, ::deterministicAux, hasher)!!
        val event = Nip46Wire.buildRequestEvent(clientPubkey, bunkerPubkey, encrypted, 1_714_078_911L, hasher)!!
        assertTrue(event.json.contains("\"kind\":24133"))
        assertTrue(event.json.contains("\"pubkey\":\"$clientPubkey\""))
        assertTrue(event.json.contains("\"tags\":[[\"p\",\"$bunkerPubkey\"]]"))
        // Canonical id over exactly the serialized fields.
        val expected = hasher.sha256(
            """[0,"$clientPubkey",1714078911,24133,[["p","$bunkerPubkey"]],"$encrypted"]""".encodeToByteArray(),
        ).toHexString()
        assertEquals(expected, event.idHex)
        val parsed = Nip46Wire.parseTransportEvent(event.json)!!
        assertEquals(clientPubkey, parsed.pubkeyHex)
        assertEquals(bunkerPubkey, parsed.pTagHex)
        assertEquals(encrypted, parsed.content)
    }

    @Test
    fun responseFilterPinsAuthorWhenKnown() {
        val pinned = Nip46Wire.responseFilter(clientPubkey, bunkerPubkey)!!
        assertEquals("""{"kinds":[24133],"#p":["$clientPubkey"],"authors":["$bunkerPubkey"],"limit":1}""", pinned)
        val pairing = Nip46Wire.responseFilter(clientPubkey, null)!!
        assertEquals("""{"kinds":[24133],"#p":["$clientPubkey"],"limit":1}""", pairing)
        assertNull(Nip46Wire.responseFilter("zz", null))
    }

    // ── Transport crypto ──────────────────────────────────────────────

    @Test
    fun nip44WrapRoundTripsAndRejectsWrongKey() {
        val envelope = Nip46Wire.encodeRequest("req-9", "get_public_key", emptyList())!!
        val payload = Nip46Crypto.encrypt(envelope, clientSecret, bunkerPubkey, ::deterministicAux, hasher)!!
        val decrypted = Nip46Crypto.decrypt(payload, clientSecret, bunkerPubkey, hasher)!!
        assertEquals(envelope, decrypted)
        // An unrelated third key shares no secret and must fail.
        val strangerSecret = "0000000000000000000000000000000000000000000000000000000000000002"
        assertNull(Nip46Crypto.decrypt(payload, strangerSecret, bunkerPubkey, hasher))
        assertNull(Nip46Crypto.decrypt("not-a-nip44-payload", clientSecret, bunkerPubkey, hasher))
    }

    // ── Reducer: request correlation lifecycle ────────────────────────

    private fun signEventFixture(accountPubkey: String, secret: String): Pair<SignEventRequest, String> {
        val request = SignEventRequest(
            requestId = "sign-1",
            accountPubkeyHex = accountPubkey,
            createdAtSeconds = 1_714_078_911L,
            kind = 1,
            tags = emptyList(),
            content = "Hello, I'm signing remotely",
        )
        val signer = LocalKeyEventSigner(secret, ::deterministicAux, hasher)
        val signed = kotlinx.coroutines.runBlocking { signer.approveEvent(request) } as SignEventResult.Signed
        val json = signedJson(signed.event)
        return request to json
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte ->
            ((byte.toInt() and 0xf0) ushr 4).toString(16) + (byte.toInt() and 0x0f).toString(16)
        }

    private fun signedJson(event: SignedEvent): String =
        """{"id":"${event.idHex}","pubkey":"${event.pubkeyHex}","created_at":${event.createdAtSeconds},""" +
            """"kind":${event.kind},"tags":[],"content":"${event.content.replace("\"", "\\\"")}","sig":"${event.signatureHex}"}"""

    private fun begunSignEventRpc(nowMs: Long = 1_000L): Nip46SessionState {
        val (request, _) = signEventFixture(bunkerPubkey, bunkerSecret)
        val envelope = Nip46Wire.encodeRequest("sign-1", "sign_event", Nip46Wire.signEventParams(request))!!
        val encrypted = Nip46Crypto.encrypt(envelope, clientSecret, bunkerPubkey, ::deterministicAux, hasher)!!
        val unsigned = Nip46Wire.buildRequestEvent(clientPubkey, bunkerPubkey, encrypted, 1_714_078_911L, hasher)!!
        val (state, commands) = Nip46SessionReducer.begin(
            Nip46SessionState(),
            Nip46Rpc(
                requestId = "sign-1",
                method = Nip46Methods.SIGN_EVENT,
                deadlineMs = nowMs + 60_000,
                expectedSignerPubkeyHex = bunkerPubkey,
                originating = request,
            ),
            unsigned,
            subscriptionId = "nip46-lane-1",
            clientTransportPubkeyHex = clientPubkey,
        )!!
        assertEquals(2, commands.size) // open lane + send
        assertTrue(commands[0] is Nip46Command.OpenResponseLane)
        assertTrue(commands[1] is Nip46Command.SendRequest)
        return state
    }

    @Test
    fun reducerApprovesVerifiedSignEventResponse() {
        val state = begunSignEventRpc()
        val (_, signedJson) = signEventFixture(bunkerPubkey, bunkerSecret)
        val responseEnvelope = Nip46Wire.encodeResponse("sign-1", signedJson)!!
        val (next, commands) = Nip46SessionReducer.onResponse(state, bunkerPubkey, Nip46Wire.decodeResponse(responseEnvelope)!!)
        val outcome = next.finished
        assertTrue(outcome is Nip46Outcome.EventApproved)
        assertEquals(bunkerPubkey, outcome.event.pubkeyHex)
        assertTrue(commands.isEmpty())
        assertFalse(next.busy)
    }

    @Test
    fun reducerIgnoresStaleMismatchedAndForeignResponses() {
        val state = begunSignEventRpc()
        // Wrong correlation id.
        val wrongId = Nip46Wire.decodeResponse(Nip46Wire.encodeResponse("other-id", "ack")!!)!!
        assertEquals(state, Nip46SessionReducer.onResponse(state, bunkerPubkey, wrongId).first)
        // Foreign source.
        val (request, signedJson) = signEventFixture(bunkerPubkey, bunkerSecret)
        val good = Nip46Wire.decodeResponse(Nip46Wire.encodeResponse("sign-1", signedJson)!!)!!
        assertEquals(state, Nip46SessionReducer.onResponse(state, clientPubkey, good).first)
        assertTrue(request.requestId == "sign-1")
        // No in-flight RPC at all.
        val idle = Nip46SessionState(subscriptionId = "nip46-lane-1")
        assertEquals(idle, Nip46SessionReducer.onResponse(idle, bunkerPubkey, good).first)
    }

    @Test
    fun reducerRejectsTamperedReturnedEvent() {
        val state = begunSignEventRpc()
        val (_, signedJson) = signEventFixture(bunkerPubkey, bunkerSecret)
        val tampered = signedJson.replace("Hello", "Goodbye")
        val envelope = Nip46Wire.decodeResponse(Nip46Wire.encodeResponse("sign-1", tampered)!!)!!
        val (next, _) = Nip46SessionReducer.onResponse(state, bunkerPubkey, envelope)
        val outcome = next.finished
        assertTrue(outcome is Nip46Outcome.Failed)
        assertEquals("rejected", (outcome as Nip46Outcome.Failed).result.outcomeCode)
    }

    @Test
    fun reducerMapsSignerDenialAndErrors() {
        val state = begunSignEventRpc()
        val denied = Nip46Wire.decodeResponse(Nip46Wire.encodeResponse("sign-1", null, "user denied")!!)!!
        val outcome = Nip46SessionReducer.onResponse(state, bunkerPubkey, denied).first.finished
        assertTrue(outcome is Nip46Outcome.Failed)
        assertEquals(
            SignEventResult.Denied.CODE,
            (outcome as Nip46Outcome.Failed).result.outcomeCode,
        )

        val rateLimited = Nip46Wire.decodeResponse(Nip46Wire.encodeResponse("sign-1", null, "rate limited")!!)!!
        val outcome2 = Nip46SessionReducer.onResponse(begunSignEventRpc(), bunkerPubkey, rateLimited).first.finished
        assertEquals(
            SignEventResult.Unavailable.CODE,
            (outcome2 as Nip46Outcome.Failed).result.outcomeCode,
        )
    }

    @Test
    fun reducerTimesOutAtDeadlineOnly() {
        val state = begunSignEventRpc(nowMs = 1_000)
        // Before the deadline: nothing happens.
        assertEquals(state, Nip46SessionReducer.onExpired(state, 2_000).first)
        val (expired, _) = Nip46SessionReducer.onExpired(state, 61_001)
        val outcome = expired.finished
        assertTrue(outcome is Nip46Outcome.Failed)
        assertEquals(SignEventResult.TimedOut.CODE, (outcome as Nip46Outcome.Failed).result.outcomeCode)
    }

    @Test
    fun reducerCancelIsNotAnErrorAndTearsDown() {
        val state = begunSignEventRpc()
        val (next, commands) = Nip46SessionReducer.onCancelled(state)
        assertTrue((next.finished as Nip46Outcome.Failed).result is SignEventResult.Cancelled)
        assertTrue(commands.any { it is Nip46Command.CloseResponseLane })
        assertTrue(commands.any { it is Nip46Command.TeardownSockets })
    }

    @Test
    fun reducerResendsInFlightRequestOnRelaySwitch() {
        val state = begunSignEventRpc()
        val envelope = Nip46Wire.encodeRequest("sign-1", "sign_event", listOf("{}"))!!
        val encrypted = Nip46Crypto.encrypt(envelope, clientSecret, bunkerPubkey, ::deterministicAux, hasher)!!
        val unsigned = Nip46Wire.buildRequestEvent(clientPubkey, bunkerPubkey, encrypted, 1_714_078_911L, hasher)!!
        val (next, commands) = Nip46SessionReducer.onRelaysSwitched(state, unsigned, "lane-2", clientPubkey)
        assertEquals("lane-2", next.subscriptionId)
        assertTrue(commands.any { it is Nip46Command.CloseResponseLane })
        assertTrue(commands.any { it is Nip46Command.OpenResponseLane })
        assertTrue(commands.any { it is Nip46Command.SendRequest })
        assertTrue(next.busy) // same request continues, same id
    }

    @Test
    fun reducerRefusesSecondRpcWhileBusy() {
        val state = begunSignEventRpc()
        val envelope = Nip46Wire.encodeRequest("req-2", "get_public_key", emptyList())!!
        val encrypted = Nip46Crypto.encrypt(envelope, clientSecret, bunkerPubkey, ::deterministicAux, hasher)!!
        val unsigned = Nip46Wire.buildRequestEvent(clientPubkey, bunkerPubkey, encrypted, 1_714_078_911L, hasher)!!
        assertNull(
            Nip46SessionReducer.begin(
                state,
                Nip46Rpc("req-2", Nip46Methods.GET_PUBLIC_KEY, 999_999L, bunkerPubkey),
                unsigned,
                "lane-x",
                clientPubkey,
            ),
        )
    }

    @Test
    fun reducerHandlesGetPublicKeyAndConnectResults() {
        val envelope = Nip46Wire.encodeRequest("gpk-1", "get_public_key", emptyList())!!
        val encrypted = Nip46Crypto.encrypt(envelope, clientSecret, bunkerPubkey, ::deterministicAux, hasher)!!
        val unsigned = Nip46Wire.buildRequestEvent(clientPubkey, bunkerPubkey, encrypted, 1_714_078_911L, hasher)!!
        val (state, _) = Nip46SessionReducer.begin(
            Nip46SessionState(),
            Nip46Rpc("gpk-1", Nip46Methods.GET_PUBLIC_KEY, 60_000L, bunkerPubkey),
            unsigned,
            "lane-1",
            clientPubkey,
        )!!
        val ok = Nip46Wire.decodeResponse(Nip46Wire.encodeResponse("gpk-1", bunkerPubkey)!!)!!
        val outcome = Nip46SessionReducer.onResponse(state, bunkerPubkey, ok).first.finished
        assertEquals(bunkerPubkey, (outcome as Nip46Outcome.PublicKeyObtained).accountPubkeyHex)

        val badKey = Nip46Wire.decodeResponse(Nip46Wire.encodeResponse("gpk-1", "npub1zz")!!)!!
        val badOutcome = Nip46SessionReducer.onResponse(state, bunkerPubkey, badKey).first.finished
        assertTrue(badOutcome is Nip46Outcome.Failed)

        val connectState = Nip46SessionReducer.begin(
            Nip46SessionState(),
            Nip46Rpc("c-1", Nip46Methods.CONNECT, 60_000L, bunkerPubkey),
            unsigned,
            "lane-1",
            clientPubkey,
        )!!.first
        val ack = Nip46Wire.decodeResponse(Nip46Wire.encodeResponse("c-1", "ack")!!)!!
        val connectOutcome = Nip46SessionReducer.onResponse(connectState, bunkerPubkey, ack).first.finished
        assertNull((connectOutcome as Nip46Outcome.ConnectAcknowledged).echoedSecret)
    }

    @Test
    fun reducerKeepsWaitingThroughAuthUrlChallenge() {
        val state = begunSignEventRpc()
        val challenge = Nip46Wire.decodeResponse(Nip46Wire.encodeResponse("sign-1", "auth_url")!!)!!
        val challenged = Nip46SessionReducer.onAuthUrlChallenge(state, challenge)
        assertTrue(challenged.awaitingAuthUrl)
        assertTrue(challenged.busy)
        // A real answer with the same id still completes the RPC.
        val (_, signedJson) = signEventFixture(bunkerPubkey, bunkerSecret)
        val answer = Nip46Wire.decodeResponse(Nip46Wire.encodeResponse("sign-1", signedJson)!!)!!
        val (next, _) = Nip46SessionReducer.onResponse(challenged, bunkerPubkey, answer)
        assertTrue(next.finished is Nip46Outcome.EventApproved)
    }

    @Test
    fun pairingConnectRequestIsAckedAndLearnsSigner() {
        val state = Nip46SessionState(subscriptionId = "pair-lane")
        val connect = Nip46Wire.decodeRequest(
            Nip46Wire.encodeRequest("intro-1", "connect", listOf(clientPubkey, "pair-secret"))!!,
        )!!
        val (next, commands) = Nip46SessionReducer.onSignerConnectRequest(state, bunkerPubkey, connect, clientPubkey)
        val outcome = next.finished
        assertTrue(outcome is Nip46Outcome.ConnectAcknowledged)
        assertEquals(bunkerPubkey, (outcome as Nip46Outcome.ConnectAcknowledged).signerPubkeyHex)
        assertEquals(1, commands.size)
        val ack = (commands.single() as Nip46Command.SendPlainResponse).envelopeJson
        assertTrue(ack.contains(""""id":"intro-1"""") && ack.contains(""""result":"ack""""))
        // A connect naming a DIFFERENT client is ignored.
        val stranger = Nip46Wire.decodeRequest(
            Nip46Wire.encodeRequest("intro-2", "connect", listOf("ab".repeat(32)))!!,
        )!!
        assertEquals(state, Nip46SessionReducer.onSignerConnectRequest(state, bunkerPubkey, stranger, clientPubkey).first)
    }
}
