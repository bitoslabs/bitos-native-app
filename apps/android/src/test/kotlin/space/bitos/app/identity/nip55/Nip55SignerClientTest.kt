package space.bitos.app.identity.nip55

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.bitos.core.crypto.SchnorrSigning
import space.bitos.core.identity.LocalKeyEventSigner
import space.bitos.core.identity.SignEventRequest
import space.bitos.core.identity.SignEventResult
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ID-007a/b adapter-contract tests: the NIP-55 client pins its package,
 * treats refusal/failure/malformed/wrong-package results as distinct
 * recoverable states, and only a validator-passing signed event ever
 * completes the approval.
 */
class Nip55SignerClientTest {

    private val hasher = Sha256EventHasher
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val userSecret = "4b1aa1a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d"
    private val userPubkey = run {
        val bytes = ByteArray(32) { index -> userSecret.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        SchnorrSigning.publicKey(bytes, hasher)!!.toHex()
    }
    private val pinnedPackage = "com.example.signer"

    private fun request(): SignEventRequest = SignEventRequest(
        requestId = "req-55",
        accountPubkeyHex = userPubkey,
        createdAtSeconds = 1_714_078_911L,
        kind = 1,
        tags = emptyList(),
        content = "signed by the system signer",
    )

    /** A genuinely signed event JSON as a conforming signer app would return. */
    private fun signedResultJson(request: SignEventRequest): String {
        val signer = LocalKeyEventSigner(userSecret, { hexBytes("00".repeat(32)) }, hasher)
        val signed = runBlocking { signer.approveEvent(request) } as SignEventResult.Signed
        return """{"id":"${signed.event.idHex}","pubkey":"${signed.event.pubkeyHex}",""" +
            """"created_at":${signed.event.createdAtSeconds},"kind":${signed.event.kind},""" +
            """"tags":[],"content":"${signed.event.content}","sig":"${signed.event.signatureHex}"}"""
    }

    private fun client(timeoutMs: Long = 60_000) =
        Nip55SignerClient(accountPubkeyHex = userPubkey, pinnedPackage = pinnedPackage, timeoutMs = timeoutMs)

    @BeforeTest
    fun setUp() {}
    @AfterTest
    fun tearDown() { scope.cancel() }

    /** Launches approval on the test scope; returns the deferred result. */
    private fun launchApproval(client: Nip55SignerClient, request: SignEventRequest = request()): CompletableDeferred<SignEventResult> {
        val deferred = CompletableDeferred<SignEventResult>()
        scope.launch { deferred.complete(client.approveEvent(request)) }
        return deferred
    }

    @Test
    fun buildsPinnedSignEventSpec() {
        val spec = Nip55Contracts.signEventSpec(request(), pinnedPackage)!!
        assertEquals(pinnedPackage, spec.signerPackage)
        assertEquals("sign_event", spec.type)
        assertEquals(userPubkey, spec.currentUserPubkeyHex)
        assertTrue(spec.payload.startsWith("""{"kind":1,"content":"""))
        assertNull(Nip55Contracts.signEventSpec(request().copy(kind = 70_000), pinnedPackage))
    }

    @Test
    fun approvesVerifiedSignedEvent() = runBlocking {
        val client = client()
        val approval = launchApproval(client)
        withTimeout(5_000) {
            while (client.activeRequest.value == null) kotlinx.coroutines.delay(5)
        }
        client.deliverResult(
            mapOf(
                Nip55Contracts.EXTRA_EVENT to signedResultJson(request()),
                Nip55Contracts.EXTRA_SIGNATURE to "ab".repeat(64),
                Nip55Contracts.EXTRA_ID to "req-55",
            ),
            resultCodeOk = true,
            sourcePackage = pinnedPackage,
        )
        val result = withTimeout(5_000) { approval.await() }
        assertTrue(result is SignEventResult.Signed)
        assertEquals(userPubkey, (result as SignEventResult.Signed).event.pubkeyHex)
        assertNull(client.activeRequest.value)
    }

    @Test
    fun refusalIsDenialNotAnError() = runBlocking {
        val client = client()
        val approval = launchApproval(client)
        withTimeout(5_000) { while (client.activeRequest.value == null) kotlinx.coroutines.delay(5) }
        client.deliverResult(
            mapOf(Nip55Contracts.EXTRA_REJECTED to "true"),
            resultCodeOk = true,
            sourcePackage = pinnedPackage,
        )
        assertTrue(withTimeout(5_000) { approval.await() } is SignEventResult.Denied)
    }

    @Test
    fun signerFailureVersusMalformedVersusWrongPackage() = runBlocking {
        // Non-OK result code = signer failure.
        val failed = client()
        val failedApproval = launchApproval(failed)
        withTimeout(5_000) { while (failed.activeRequest.value == null) kotlinx.coroutines.delay(5) }
        failed.deliverResult(emptyMap(), resultCodeOk = false, sourcePackage = pinnedPackage)
        assertTrue(withTimeout(5_000) { failedApproval.await() } is SignEventResult.Unavailable)

        // RESULT_OK but no event extra = malformed response.
        val malformed = client()
        val malformedApproval = launchApproval(malformed)
        withTimeout(5_000) { while (malformed.activeRequest.value == null) kotlinx.coroutines.delay(5) }
        malformed.deliverResult(mapOf(Nip55Contracts.EXTRA_ID to "req-55"), resultCodeOk = true, sourcePackage = pinnedPackage)
        assertEquals(
            "rejected",
            (withTimeout(5_000) { malformedApproval.await() } as SignEventResult.Rejected).outcomeCode,
        )

        // A different app answering the pinned request is rejected outright.
        val stranger = client()
        val strangerApproval = launchApproval(stranger)
        withTimeout(5_000) { while (stranger.activeRequest.value == null) kotlinx.coroutines.delay(5) }
        stranger.deliverResult(
            mapOf(Nip55Contracts.EXTRA_EVENT to signedResultJson(request())),
            resultCodeOk = true,
            sourcePackage = "com.evil.app",
        )
        assertEquals(
            "wrong_package",
            (withTimeout(5_000) { strangerApproval.await() } as SignEventResult.Rejected).reasonCode,
        )
    }

    @Test
    fun tamperedEventIsRejected() = runBlocking {
        val client = client()
        val approval = launchApproval(client)
        withTimeout(5_000) { while (client.activeRequest.value == null) kotlinx.coroutines.delay(5) }
        val tampered = signedResultJson(request()).replace("system signer", "something else")
        client.deliverResult(
            mapOf(Nip55Contracts.EXTRA_EVENT to tampered),
            resultCodeOk = true,
            sourcePackage = pinnedPackage,
        )
        assertEquals(
            "payload_mismatch.content",
            (withTimeout(5_000) { approval.await() } as SignEventResult.Rejected).reasonCode,
        )
    }

    @Test
    fun cancelWaitIsNotAnErrorAndTimeoutIsTyped() = runBlocking {
        val cancelClient = client()
        val cancelApproval = launchApproval(cancelClient)
        withTimeout(5_000) { while (cancelClient.activeRequest.value == null) kotlinx.coroutines.delay(5) }
        cancelClient.cancelWait()
        assertTrue(withTimeout(5_000) { cancelApproval.await() } is SignEventResult.Cancelled)

        val timeoutClient = client(timeoutMs = 100)
        assertTrue(withTimeout(5_000) { timeoutClient.approveEvent(request()) } is SignEventResult.TimedOut)
        assertNull(timeoutClient.activeRequest.value)
    }

    @Test
    fun foreignAccountRejectedBeforeLaunching() = runBlocking {
        val result = client().approveEvent(request().copy(accountPubkeyHex = "ab".repeat(32)))
        assertTrue(result is SignEventResult.Rejected)
    }

    @Test
    fun setupPublicKeyValidation() {
        val ok = Nip55Contracts.validateSetupResult(
            Nip55Contracts.RawResult.PublicKey(userPubkey.uppercase(), pinnedPackage),
            pinnedPackage,
        )
        assertEquals(userPubkey, ok)
        // npub/hex-mismatch and wrong package both fail the gate.
        assertNull(
            Nip55Contracts.validateSetupResult(
                Nip55Contracts.RawResult.PublicKey("npub1zz", pinnedPackage), pinnedPackage,
            ),
        )
        assertNull(
            Nip55Contracts.validateSetupResult(
                Nip55Contracts.RawResult.PublicKey(userPubkey, "com.other.app"), pinnedPackage,
            ),
        )
        assertNull(
            Nip55Contracts.validateSetupResult(Nip55Contracts.RawResult.Empty, pinnedPackage),
        )
    }
}

private fun hexBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

private fun ByteArray.toHex(): String =
    joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }
