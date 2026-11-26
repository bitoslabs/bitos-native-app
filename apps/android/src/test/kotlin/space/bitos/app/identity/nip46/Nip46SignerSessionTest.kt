package space.bitos.app.identity.nip46

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.bitos.app.data.relay.RelayConnectionState
import space.bitos.app.data.relay.RelayFrame
import space.bitos.app.data.relay.RelayTransport
import space.bitos.core.crypto.SchnorrSigning
import space.bitos.core.identity.Nip46AccountMetadata
import space.bitos.core.identity.SignEventRequest
import space.bitos.core.identity.SignEventResult
import space.bitos.core.identity.nip46.Nip46Crypto
import space.bitos.core.identity.nip46.Nip46Wire
import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ID-006a adapter-contract tests: the NIP-46 session drives a dedicated
 * socket lane against a fixture bunker — approval, denial, tampering,
 * cancellation, timeout, unreachable relays and account isolation — and
 * nothing is ever sent toward the relays after a refusal.
 */
class Nip46SignerSessionTest {

    private val hasher = Sha256EventHasher
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // Client transport keypair (fixture; pre-seeded so the bunker knows it).
    private val clientSecret = "d2ad3c3c9e7b0f4f6a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718"
    private val clientPubkey = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"

    // Bunker transport keypair (the signer's own identity).
    private val bunkerSecret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val bunkerPubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    // The USER account the bunker holds.
    private val userSecret = "4b1aa1a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d"
    private val userPubkey = SchnorrSigning.publicKey(hexBytes(userSecret), hasher)!!.toHex()

    private val relayUrl = RelayUrl.parse("wss://bunker-relay.example.com")!!
    private lateinit var transport: ScriptedTransport
    private lateinit var bunker: FakeBunker

    private fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }

    private fun session(
        clockMs: () -> Long = { 1_714_079_000_000L },
        signTimeoutMs: Long = 120_000,
        setupTimeoutMs: Long = 30_000,
        connectTimeoutMs: Long = 2_000,
    ) = Nip46SignerSession(
        scope = scope,
        metadata = Nip46AccountMetadata(bunkerPubkeyHex = bunkerPubkey, relays = listOf(relayUrl.value), label = "Fixture"),
        accountPubkeyHex = userPubkey,
        secretStore = object : Nip46TransportSecretStore {
            override fun loadOrCreateTransportSecret(accountPubkeyHex: String): String = clientSecret
            override fun removeTransportSecret(accountPubkeyHex: String) {}
        },
        transportFactory = { _, _ -> transport },
        clockMs = clockMs,
        connectTimeoutMs = connectTimeoutMs,
        connectAttempts = 2,
        signTimeoutMs = signTimeoutMs,
        setupTimeoutMs = setupTimeoutMs,
    )

    /** Fake relay socket: records sends, lets the fixture inject frames. */
    private class ScriptedTransport(private val relay: RelayUrl) : RelayTransport {
        override val state = MutableStateFlow(RelayConnectionState.DISCONNECTED)
        override val frames = MutableSharedFlow<RelayFrame>(
            replay = 64, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val sent = mutableListOf<String>()
        var connectCalls = 0
        var autoConnect = true
        var onSend: (String) -> Unit = {}

        override fun connect() {
            connectCalls++
            if (autoConnect) state.value = RelayConnectionState.CONNECTED
        }

        override fun send(message: String): Boolean {
            sent += message
            onSend(message)
            return true
        }

        override fun close(code: Int, reason: String) {
            state.value = RelayConnectionState.DISCONNECTED
        }

        fun deliver(message: String) {
            state.value = RelayConnectionState.CONNECTED
            frames.tryEmit(RelayFrame(relay, message))
        }
    }

    /** The signer side of the fixture: decrypts requests, replies as events. */
    private inner class FakeBunker {
        var denyAll = false
        var tamperSignedEvent = false
        var silent = false
        var sawSecret: String? = null

        fun onClientFrame(frame: String) {
            if (!frame.startsWith("""["EVENT""")) return
            val event = try {
                Json.parseToJsonElement(frame).jsonArray[1].jsonObject
            } catch (failure: Exception) {
                return
            }
            val content = event["content"]!!.jsonPrimitive.content
            val plaintext = Nip46Crypto.decrypt(content, bunkerSecret, clientPubkey, hasher) ?: run {
                return
            }
            val request = Nip46Wire.decodeRequest(plaintext) ?: run {
                return
            }
            when (request.method) {
                "connect" -> {
                    sawSecret = request.params.getOrNull(1)
                    respond(request.id, result = "ack")
                }
                "get_public_key" -> respond(request.id, result = userPubkey)
                "sign_event" -> {
                    if (denyAll) {
                        respond(request.id, error = "user denied the request")
                    } else {
                        respond(request.id, result = signedUserEventJson(request.params.first(), tamperSignedEvent))
                    }
                }
            }
        }

        private fun respond(requestId: String, result: String? = null, error: String? = null) {
            if (silent) return
            val envelope = Nip46Wire.encodeResponse(requestId, result, error) ?: return
            val encrypted = Nip46Crypto.encrypt(envelope, bunkerSecret, clientPubkey, { hexBytes("33".repeat(32)) }, hasher) ?: return
            val createdAt = 1_714_079_000L
            val id = NostrEventCodec.computeId(
                hasher, bunkerPubkey, createdAt, Nip46Wire.KIND, listOf(listOf("p", clientPubkey)), encrypted,
            )
            val sig = SchnorrSigning.sign(hexBytes(id), hexBytes(bunkerSecret), ByteArray(32), hasher)!!.toHex()
            transport.deliver(
                """["EVENT","sub-1",{"id":"$id","pubkey":"$bunkerPubkey","created_at":$createdAt,""" +
                    """"kind":${Nip46Wire.KIND},"tags":[["p","$clientPubkey"]],"content":"$encrypted","sig":"$sig"}]""",
            )
        }

        /** Signs the request's unsigned event with the USER key (optionally tampered). */
        private fun signedUserEventJson(unsignedJson: String, tamper: Boolean): String {
            val root = Json.parseToJsonElement(unsignedJson).jsonObject
            val kind = root["kind"]!!.jsonPrimitive.content.toInt()
            val content = root["content"]!!.jsonPrimitive.content + if (tamper) " tampered" else ""
            val createdAt = root["created_at"]!!.jsonPrimitive.content.toLong()
            val tags = root["tags"]!!.jsonArray.toString()
            val id = NostrEventCodec.computeId(hasher, userPubkey, createdAt, kind, emptyList(), content)
            val sig = SchnorrSigning.sign(hexBytes(id), hexBytes(userSecret), ByteArray(32), hasher)!!.toHex()
            return """{"id":"$id","pubkey":"$userPubkey","created_at":$createdAt,"kind":$kind,"tags":$tags,""" +
                """"content":"${content.replace("\\", "\\\\").replace("\"", "\\\"")}","sig":"$sig"}"""
        }
    }

    @BeforeTest
    fun setUp() {
        transport = ScriptedTransport(relayUrl)
        bunker = FakeBunker()
        transport.onSend = { bunker.onClientFrame(it) }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun signRequest(): SignEventRequest = SignEventRequest(
        requestId = "sign-1",
        accountPubkeyHex = userPubkey,
        createdAtSeconds = 1_714_078_911L,
        kind = 1,
        tags = emptyList(),
        content = "Hello, I'm signing remotely",
    )

    @Test
    fun setupConnectsAndReturnsBunkerAccountKey() = runBlocking {
        val result = session().connectAndGetPublicKey(pairingSecret = "abc")
        assertTrue(result is Nip46SetupResult.Connected)
        assertEquals(userPubkey, (result as Nip46SetupResult.Connected).accountPubkeyHex)
        assertEquals("abc", bunker.sawSecret)
        // The response lane REQ and CLOSE both flowed through the dedicated socket.
        assertTrue(transport.sent.any { it.startsWith("""["REQ",""") && it.contains("24133") })
        assertTrue(transport.sent.any { it.startsWith("""["CLOSE",""") })
    }

    @Test
    fun approvesSignEventWithVerifiedSignature() = runBlocking {
        val result = session().approveEvent(signRequest())
        assertTrue(result is SignEventResult.Signed)
        val signed = (result as SignEventResult.Signed).event
        assertEquals(userPubkey, signed.pubkeyHex)
        assertTrue(
            space.bitos.core.crypto.SchnorrVerification.verify(
                hasher, hexBytes(signed.pubkeyHex), hexBytes(signed.idHex), hexBytes(signed.signatureHex),
            ),
        )
    }

    @Test
    fun denialSendsNothingFurtherAndReportsDenied() = runBlocking {
        bunker.denyAll = true
        val result = session().approveEvent(signRequest())
        assertTrue(result is SignEventResult.Denied)
        assertEquals("denied", (result as SignEventResult.Denied).outcomeCode)
        // Only the original request EVENT ever hit the wire — no publish
        // frame followed the refusal.
        assertEquals(1, transport.sent.count { it.startsWith("""["EVENT""") })
    }

    @Test
    fun tamperedReturnedEventIsRejected() = runBlocking {
        bunker.tamperSignedEvent = true
        val result = session().approveEvent(signRequest())
        assertTrue(result is SignEventResult.Rejected)
        assertEquals("payload_mismatch.content", (result as SignEventResult.Rejected).reasonCode)
    }

    @Test
    fun cancelWaitIsNotAnErrorAndTearsDown() = runBlocking {
        bunker.silent = true
        val session = session(signTimeoutMs = 60_000)
        val approval = CompletableDeferred<SignEventResult>()
        scope.launch { approval.complete(session.approveEvent(signRequest())) }
        withTimeout(5_000) {
            while (transport.sent.none { it.startsWith("""["EVENT""") }) delay(10)
        }
        session.cancelWait()
        val result = withTimeout(5_000) { approval.await() }
        assertTrue(result is SignEventResult.Cancelled)
        assertTrue(transport.sent.any { it.startsWith("""["CLOSE",""") })
        assertEquals(RelayConnectionState.DISCONNECTED, transport.state.value)
    }

    @Test
    fun deadlineTimesOutAndTearsDown() = runBlocking {
        bunker.silent = true
        val result = withTimeout(10_000) { session(signTimeoutMs = 200).approveEvent(signRequest()) }
        assertTrue(result is SignEventResult.TimedOut)
        assertEquals(RelayConnectionState.DISCONNECTED, transport.state.value)
    }

    @Test
    fun unreachableRelaysReportUnavailableWithCappedAttempts() = runBlocking {
        transport.autoConnect = false
        val result = withTimeout(30_000) { session(connectTimeoutMs = 50).approveEvent(signRequest()) }
        assertTrue(result is SignEventResult.Unavailable)
        assertEquals("relay_unreachable", (result as SignEventResult.Unavailable).reasonCode)
        assertTrue(transport.connectCalls <= 2)
    }

    @Test
    fun foreignAccountIsRejectedBeforeAnySocketActivity() = runBlocking {
        val foreign = signRequest().copy(accountPubkeyHex = "ab".repeat(32))
        val result = session().approveEvent(foreign)
        assertTrue(result is SignEventResult.Rejected)
        assertEquals(0, transport.connectCalls)
        assertEquals(0, transport.sent.size)
    }
}

private fun hexBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

private fun ByteArray.toHex(): String =
    joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }
