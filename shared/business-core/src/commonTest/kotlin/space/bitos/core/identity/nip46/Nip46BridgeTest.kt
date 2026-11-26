package space.bitos.core.identity.nip46

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.bitos.core.bridge.BusinessCoreBridge
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bridge contract lock for the NIP-46 seam (SBC-008b): the String-only
 * surface the iOS session consumes must round-trip the SAME tested
 * reducer — state tokens, command JSON and outcome codes stay stable.
 */
class Nip46BridgeTest {

    private val hasher = Sha256EventHasher
    private val bridge = BusinessCoreBridge()

    private val clientSecret = "d2ad3c3c9e7b0f4f6a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718"
    private val clientPubkey = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val bunkerSecret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val bunkerPubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    @Test
    fun uriParseProducesStableJson() {
        val json = bridge.nip46ParseUri(
            "bunker://$bunkerPubkey?relay=wss%3A%2F%2Frelay1.example.com&secret=abc",
        )!!
        assertTrue(json.contains(""""pubkey":"$bunkerPubkey""""))
        assertTrue(json.contains(""""relays":["wss://relay1.example.com"]"""))
        assertTrue(json.contains(""""secret":"abc""""))
        assertNull(bridge.nip46ParseUri("bunker://zz?relay=wss://r.example.com"))
    }

    @Test
    fun pairingUriBuildsThroughBridge() {
        val uri = bridge.nip46BuildPairingUri(
            clientPubkey, """["wss://relay1.example.com"]""", "s3cret", "BitOS",
        )!!
        assertTrue(uri.startsWith("nostrconnect://$clientPubkey?relay="))
        assertNull(bridge.nip46BuildPairingUri(clientPubkey, "not-json", "s", null))
    }

    @Test
    fun transportEncryptDecryptRoundTripsThroughBridge() {
        val envelope = bridge.nip46EncodeRequest("req-1", "get_public_key", "[]")!!
        val nonceHex = "11".repeat(32)
        val payload = bridge.nip46Encrypt(envelope, clientSecret, bunkerPubkey, nonceHex)!!
        assertEquals(envelope, bridge.nip46Decrypt(payload, clientSecret, bunkerPubkey))
        assertNull(bridge.nip46Encrypt(envelope, clientSecret, bunkerPubkey, "short"))
        assertNull(bridge.nip46EncodeRequest("req-1", "nip04_encrypt", "[]"))
    }

    @Test
    fun beginAndResponseStepsRoundTripTheReducer() {
        val envelope = bridge.nip46EncodeRequest("gpk-1", "get_public_key", "[]")!!
        val payload = bridge.nip46Encrypt(envelope, clientSecret, bunkerPubkey, "22".repeat(32))!!
        val event = bridge.nip46BuildRequestEvent(clientPubkey, bunkerPubkey, payload, 1_714_078_911L)!!

        // Sign the transport event like the native session would.
        val signature = bridge.signDetached(event["id"]!!, clientSecret, "00".repeat(32))!!
        val frame = bridge.nip46EventFrame(event["json"]!!, event["id"]!!, signature)!!
        assertTrue(frame.startsWith("""["EVENT",{"id":""""))

        // Reducer begin through the bridge.
        val state0 = bridge.nip46StateInit()
        val step = assertNotNull(
            bridge.nip46Begin(
                stateJson = state0,
                requestId = "gpk-1",
                method = "get_public_key",
                deadlineMs = 60_000L,
                signerPubkeyHex = bunkerPubkey,
                originJson = "",
                envelopePlainJson = envelope,
                encryptedContent = payload,
                createdAtSeconds = 1_714_078_911L,
                clientPubkeyHex = clientPubkey,
                subscriptionId = "lane-1",
            ),
        )
        assertTrue(step.contains(""""type":"open_lane""""))
        assertTrue(step.contains(""""type":"send_request""""))

        // Response arrives → outcome public_key.
        val responsePlain = """{"id":"gpk-1","result":"$bunkerPubkey"}"""
        val responseStep = bridge.nip46OnResponse(extractState(step), bunkerPubkey, responsePlain)
        assertTrue(responseStep.contains(""""type":"public_key""""))
        assertTrue(responseStep.contains(""""pubkey":"$bunkerPubkey""""))

        // Mismatched id is ignored (no outcome).
        val noiseStep = bridge.nip46OnResponse(extractState(step), bunkerPubkey, """{"id":"other","result":"x"}""")
        assertTrue(!noiseStep.contains(""""outcome""""))
    }

    @Test
    fun validateSignedEventJsonGate() {
        val originJson = bridge.signEventRequestJson(
            requestId = "sign-1",
            accountPubkeyHex = bunkerPubkey,
            createdAtSeconds = 1_714_078_911L,
            kind = 1,
            tagsJson = "[]",
            content = "hi",
        )!!
        // Build a genuinely signed event with the bunker key.
        val signature = bridge.signDetached(
            space.bitos.core.identity.SignEventRequest(
                "sign-1", bunkerPubkey, 1_714_078_911L, 1, emptyList(), "hi",
            ).eventIdHex(hasher),
            bunkerSecret,
            "00".repeat(32),
        )!!
        val signedJson = """{"id":"${space.bitos.core.identity.SignEventRequest(
            "sign-1", bunkerPubkey, 1_714_078_911L, 1, emptyList(), "hi",
        ).eventIdHex(hasher)}",""" +
            """"pubkey":"$bunkerPubkey","created_at":1714078911,"kind":1,"tags":[],""" +
            """"content":"hi","sig":"$signature"}"""
        val accepted = bridge.validateSignedEventJson(originJson, signedJson)
        assertEquals("signed", accepted!!["code"])
        assertEquals(signature, accepted["sig"])

        val tampered = bridge.validateSignedEventJson(originJson, signedJson.replace("\"hi\"", "\"no\""))
        assertEquals("payload_mismatch.content", tampered!!["code"])
    }

    private fun extractState(stepJson: String): String {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(stepJson).jsonObject
        return root["state"]!!.jsonPrimitive.content
    }
}
