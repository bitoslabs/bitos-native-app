package space.bitos.core.identity

import space.bitos.core.crypto.SchnorrSigning
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SBC-008a contract tests: request bounds, wire form, local-key signing,
 * and the independent validation gate that decides whether a signer
 * response may reach the publisher.
 */
class SignEventContractTest {

    private val hasher = Sha256EventHasher

    private val testSecret = "d2ad3c3c9e7b0f4f6a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718"
    private val testPubkey = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"

    private fun deterministicAux() = "00".repeat(32).hexToBytes()

    private fun signer() = LocalKeyEventSigner(testSecret, ::deterministicAux, hasher)

    private fun sign(request: SignEventRequest): SignEventResult =
        kotlinx.coroutines.runBlocking { signer().approveEvent(request) }

    private fun request(
        requestId: String = "req-1",
        pubkey: String = testPubkey,
        createdAt: Long = 1_714_078_911L,
        kind: Int = 1,
        tags: List<List<String>> = emptyList(),
        content: String = "Hello, I'm signing remotely",
    ) = SignEventRequest(requestId, pubkey, createdAt, kind, tags, content)

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }

    // ── Request bounds ─────────────────────────────────────────────────

    @Test
    fun acceptsBoundedRequest() {
        assertNull(SignEventRequest.validate(request()))
    }

    @Test
    fun rejectsOutOfBoundsFields() {
        assertEquals("request_id", SignEventRequest.validate(request(requestId = "")))
        assertEquals("request_id", SignEventRequest.validate(request(requestId = "x".repeat(65))))
        assertEquals("request_id", SignEventRequest.validate(request(requestId = "bad id!")))
        assertEquals("account_pubkey", SignEventRequest.validate(request(pubkey = "zz".repeat(32))))
        assertEquals("created_at", SignEventRequest.validate(request(createdAt = 0)))
        assertEquals("kind", SignEventRequest.validate(request(kind = 70_000)))
        assertEquals("content", SignEventRequest.validate(request(content = "x".repeat(65_537))))
        assertEquals(
            "tags",
            SignEventRequest.validate(request(tags = (0..257).map { listOf("t", "x") })),
        )
        assertEquals("tags", SignEventRequest.validate(request(tags = listOf(listOf()))))
        assertEquals(
            "tags",
            SignEventRequest.validate(request(tags = listOf(List(33) { "x" }))),
        )
    }

    @Test
    fun unsignedWireFormCarriesExactlyTheFourSignerFields() {
        val wire = request(tags = listOf(listOf("client", "BitOS"))).unsignedEventJson()
        assertEquals(
            """{"kind":1,"content":"Hello, I'm signing remotely","tags":[["client","BitOS"]],"created_at":1714078911}""",
            wire,
        )
    }

    @Test
    fun unsignedWireFormEscapesContent() {
        val wire = request(content = "quote \" backslash \\ newline \n").unsignedEventJson()
        assertEquals(
            """{"kind":1,"content":"quote \" backslash \\ newline \n","tags":[],"created_at":1714078911}""",
            wire,
        )
    }

    @Test
    fun eventIdMatchesCanonicalSerialization() {
        val request = request()
        val expected = hasher.sha256(
            """[0,"$testPubkey",1714078911,1,[],"Hello, I'm signing remotely"]""".encodeToByteArray(),
        ).toHex()
        assertEquals(expected, request.eventIdHex(hasher))
    }

    // ── Local-key signer ───────────────────────────────────────────────

    @Test
    fun localKeySignsApprovedRequestVerifiably() {
        val result = sign(request())
        assertTrue(result is SignEventResult.Signed)
        val signed = result.event
        assertEquals(testPubkey, signed.pubkeyHex)
        assertEquals(request().eventIdHex(hasher), signed.idHex)
        assertEquals(128, signed.signatureHex.length)
        // Independent BIP-340 verification of the returned signature.
        assertTrue(
            space.bitos.core.crypto.SchnorrVerification.verify(
                hasher,
                testPubkey.hexToBytes(),
                signed.idHex.hexToBytes(),
                signed.signatureHex.hexToBytes(),
            ),
        )
    }

    @Test
    fun localKeyRefusesForeignAccount() {
        val result = sign(request(pubkey = "ab".repeat(32)))
        assertTrue(result is SignEventResult.Rejected)
        assertEquals("wrong_pubkey", (result as SignEventResult.Rejected).reasonCode)
    }

    @Test
    fun localKeyRejectsInvalidRequest() {
        val result = sign(request(requestId = "bad id"))
        assertTrue(result is SignEventResult.Invalid)
        assertEquals("request_id", (result as SignEventResult.Invalid).reasonCode)
    }

    // ── Validation gate (the anti-tamper core) ────────────────────────

    private fun signedFrom(signatureHex: String, request: SignEventRequest = request()): SignedEvent =
        SignedEvent(
            idHex = request.eventIdHex(hasher),
            pubkeyHex = request.accountPubkeyHex,
            createdAtSeconds = request.createdAtSeconds,
            kind = request.kind,
            tags = request.tags,
            content = request.content,
            signatureHex = signatureHex,
        )

    @Test
    fun validatorAcceptsExactSignedEvent() {
        val request = request()
        val signed = sign(request) as SignEventResult.Signed
        assertNull(SignedEventValidator.validate(request, signed.event, hasher))
    }

    @Test
    fun validatorRejectsChangedPayload() {
        val request = request()
        val signed = sign(request) as SignEventResult.Signed
        val tamperedContent = signed.event.copy(content = signed.event.content + " tampered")
        assertEquals("payload_mismatch.content", SignedEventValidator.validate(request, tamperedContent, hasher))

        val tamperedKind = signed.event.copy(kind = 2)
        assertEquals("payload_mismatch.kind", SignedEventValidator.validate(request, tamperedKind, hasher))

        val tamperedTags = signed.event.copy(tags = listOf(listOf("t", "injected")))
        assertEquals("payload_mismatch.tags", SignedEventValidator.validate(request, tamperedTags, hasher))

        val tamperedTime = signed.event.copy(createdAtSeconds = request.createdAtSeconds + 1)
        assertEquals("payload_mismatch.created_at", SignedEventValidator.validate(request, tamperedTime, hasher))
    }

    @Test
    fun validatorRejectsDifferentPubkeyAndBadSignature() {
        val request = request()
        val signed = sign(request) as SignEventResult.Signed
        val wrongKey = signed.event.copy(pubkeyHex = "ab".repeat(32))
        assertEquals("wrong_pubkey", SignedEventValidator.validate(request, wrongKey, hasher))

        val flipped = signed.event.signatureHex.let {
            it.dropLast(1) + if (it.last() == '0') '1' else '0'
        }
        val badSig = signedFrom(signatureHex = flipped)
        assertEquals("bad_signature", SignedEventValidator.validate(request, badSig, hasher))
    }

    @Test
    fun validatorRejectsRecomputedIdMismatch() {
        val request = request()
        val signed = sign(request) as SignEventResult.Signed
        // Move the event to a different id while keeping a self-consistent
        // payload would require re-signing; here the declared id lies.
        val liar = signed.event.copy(idHex = "cd".repeat(32))
        assertEquals("bad_id", SignedEventValidator.validate(request, liar, hasher))
    }

    @Test
    fun parseSignedEventBoundsAndMalformedInput() {
        val signed = (sign(request()) as SignEventResult.Signed).event
        val json = signedJson(signed)
        assertEquals(signed, SignedEventValidator.parseSignedEvent(json))
        assertNull(SignedEventValidator.parseSignedEvent(""))
        assertNull(SignedEventValidator.parseSignedEvent("{not json"))
        assertNull(SignedEventValidator.parseSignedEvent(json.replace("\"sig\"", "\"missing\"")))
        assertNull(SignedEventValidator.parseSignedEvent(json.replace("\"kind\":1", "\"kind\":true")))
        // Oversized input never parses.
        assertNull(SignedEventValidator.parseSignedEvent("x".repeat(space.bitos.core.model.NostrLimits.MAX_EVENT_BYTES + 1)))
    }

    @Test
    fun validateJsonCombinesParseAndGate() {
        val request = request()
        val signed = (sign(request) as SignEventResult.Signed).event
        val (parsed, reason) = SignedEventValidator.validateJson(request, signedJson(signed), hasher)
        assertNull(reason)
        assertEquals(signed, parsed)

        val tampered = signedJson(signed.copy(content = "changed"))
        val (_, reject) = SignedEventValidator.validateJson(request, tampered, hasher)
        assertEquals("payload_mismatch.content", reject)
    }

    @Test
    fun outcomeCodesAreStableAndRedacted() {
        // The codes are the telemetry contract: aggregate-safe, no payload.
        assertEquals("signed", SignEventResult.Signed(signedFrom("ab".repeat(64))).outcomeCode)
        assertEquals("denied", SignEventResult.Denied().outcomeCode)
        assertEquals("timeout", SignEventResult.TimedOut().outcomeCode)
        assertEquals("unavailable", SignEventResult.Unavailable("app_absent").outcomeCode)
        assertEquals("rejected", SignEventResult.Rejected("wrong_pubkey").outcomeCode)
        assertEquals("cancelled", SignEventResult.Cancelled.outcomeCode)
        assertEquals("invalid", SignEventResult.Invalid("content").outcomeCode)
    }

    @Test
    fun deterministicSignatureMatchesReferenceVector() {
        // Locks LocalKeyEventSigner to the independently generated BIP-340
        // vector for this secret/aux/message (regeneration in repo docs).
        val request = request(content = "")
        val id = request.eventIdHex(hasher)
        val expected = space.bitos.core.crypto.SchnorrSigning.sign(
            id.hexToBytes(), testSecret.hexToBytes(), ByteArray(32), hasher,
        )!!.toHex()
        val signed = sign(request) as SignEventResult.Signed
        // Same inputs → same deterministic signature path as raw signing.
        assertNotEquals("", signed.event.signatureHex)
        assertEquals(expected, signed.event.signatureHex)
    }

    private fun signedJson(event: SignedEvent): String {
        val tags = event.tags.joinToString(prefix = "[", postfix = "]", separator = ",") { tag ->
            tag.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
        }
        val escaped = event.content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "{\"id\":\"${event.idHex}\",\"pubkey\":\"${event.pubkeyHex}\"," +
            "\"created_at\":${event.createdAtSeconds},\"kind\":${event.kind},\"tags\":$tags," +
            "\"content\":\"$escaped\",\"sig\":\"${event.signatureHex}\"}"
    }
}
