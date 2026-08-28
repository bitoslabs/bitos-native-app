package space.bitos.core.crypto

import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SBC-006 acceptance suite: every fixture vector from
 * `contracts/nostr/fixtures/verification-vectors.json` — generated and
 * reference-verified by nostr-tools/@noble (BIP-340) — must agree with this
 * implementation on the JVM host and the native Apple lane.
 */
class SchnorrVerificationTest {

    private val hasher = Sha256EventHasher
    private val relay = RelayUrl.parse("wss://relay.damus.io")!!

    private class Vector(val name: String, val valid: Boolean, val message: String)

    private fun run(vector: Vector): Boolean {
        val event = NostrEventCodec.decodeRelayEvent(hasher, vector.message, relay)
        return NostrEventCodec.verifySignature(hasher, event)
    }

    @Test
    fun acceptsAllValidVectors() {
        for (vector in vectors.filter { it.valid }) {
            assertTrue(run(vector), "${vector.name} must verify")
        }
    }

    @Test
    fun rejectsAllInvalidVectors() {
        for (vector in vectors.filter { !it.valid }) {
            assertEquals(false, run(vector), "${vector.name} must be rejected")
        }
    }

    @Test
    fun verificationCompletesWithinLooseBudget() {
        // Loose regression guard only: catches pathological regressions, not
        // micro-benchmarks. Optimization lane (windowed mul, Montgomery) sits
        // behind the same vectors.
        val vector = vectors.first { it.name == "valid-text-note" }
        repeat(3) { assertTrue(run(vector)) } // warmup
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        val iterations = 5
        repeat(iterations) { assertTrue(run(vector)) }
        val perVerification = start.elapsedNow() / iterations
        assertTrue(
            perVerification < kotlin.time.Duration.parse("500ms"),
            "one verification took $perVerification",
        )
    }

    private val vectors = listOf(
        Vector(
            "valid-text-note", true,
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]""",
        ),
        Vector(
            "valid-profile-metadata", true,
            """["EVENT","sub1",{"kind":0,"created_at":1710000100,"tags":[],"content":"{\"name\":\"satoshi\",\"display_name\":\"Satoshi ₿\",\"about\":\"test vector\"}","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"1bdf4e4f9f406ce12418060a4f6296fb1f983d5cd75d6cd583263e09ae78d2a5","sig":"f21868a6a8be0823603064a38832404508746fd6ce3c92a627eabc55b0562bd6477901518273b2c483c628c7b1be74cb444a33fbbe26d174fc211cca234c11a6"}]""",
        ),
        Vector(
            "valid-escaped-content", true,
            """["EVENT","sub1",{"kind":1,"created_at":1710000200,"tags":[["e","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"],["p","cccccccccccccccccccccccccccccccc"]],"content":"line1\nline2 \"quoted\" ₿\u0007end","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"6bbba7020543b6d2fbd740a5a387cd92054716342d2b6389692fec5257f5e7fd","sig":"6678f8524132e35027dd2403993fe012a3728b100cfce94a656a9a5da39f37802185d8e6d7120518436c773e64d8e19758a6ba914fd98a0fd86339caa6adf61b"}]""",
        ),
        Vector(
            "valid-id-wrong-signature", false,
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"tampered but re-identified","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"2cbc3c8affa0828e03b11f975337317f8e415397933fc87069265e17f719e95b","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]""",
        ),
        Vector(
            "valid-second-key", true,
            """["EVENT","sub1",{"kind":1,"created_at":1710000300,"tags":[],"content":"second author note","pubkey":"e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301","id":"c52c5fdb44ec230447a503a33f60bb729077fd8b62208456c1752d2c3dcaec1a","sig":"7d560e1a017cea1aa15271a5d0d0e3e6e8ada4f44261b770e27cadb51bf884398360b5464e39f739eb1860163b37ccedf9bf81b87e4b7026abe6d539a39bddcc"}]""",
        ),
        Vector(
            "tampered-signature-byte", false,
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921f05fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]""",
        ),
        Vector(
            "signature-s-equals-n", false,
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"}]""",
        ),
        Vector(
            "signature-r-zero", false,
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","sig":"0000000000000000000000000000000000000000000000000000000000000000c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]""",
        ),
        Vector(
            "pubkey-not-on-curve", false,
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"0000000000000000000000000000000000000000000000000000000000000005","id":"70a6598eca9574dd35c0be77852bbafed36a3910742e6a466e73c113b42d0cca","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]""",
        ),
    )
}
