package space.bitos.core.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import space.bitos.core.nostr.Pow

/**
 * NIP-13 proof-of-work contract (unified feature spec §3.8 PowCard path):
 * difficulty counting, committed nonce tags, and chunked mining whose
 * serialization is byte-identical to the canonical codec — locked by
 * recomputing every mined ID through [NostrEventCodec.computeId].
 */
class PowTest {

    private val hasher = Sha256EventHasher
    private val pubkey = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"

    // -----------------------------------------------------------------
    // Difficulty counting
    // -----------------------------------------------------------------

    @Test
    fun difficultyCountsLeadingZeroBits() {
        assertEquals(256, Pow.difficulty("0".repeat(64)))
        assertEquals(28, Pow.difficulty("0".repeat(7) + "f" + "a".repeat(56)))
        assertEquals(31, Pow.difficulty("00000001" + "f".repeat(56)))
        assertEquals(30, Pow.difficulty("00000002" + "f".repeat(56)))
        assertEquals(29, Pow.difficulty("00000004" + "f".repeat(56)))
        assertEquals(28, Pow.difficulty("00000008" + "f".repeat(56)))
        assertEquals(0, Pow.difficulty("f".repeat(64)))
        assertEquals(4, Pow.difficulty("0" + "f".repeat(63)))
    }

    @Test
    fun difficultyRejectsInvalidHex() {
        assertEquals(-1, Pow.difficulty(""))
        assertEquals(-1, Pow.difficulty("g".repeat(64)))
        assertEquals(-1, Pow.difficulty("0".repeat(63) + "G"))
        assertEquals(-1, Pow.difficulty("0".repeat(63) + " "))
    }

    @Test
    fun nonceTagCarriesTarget() {
        assertEquals(listOf("nonce", "166", "20"), Pow.nonceTag(166, 20))
        assertEquals(listOf("nonce", "0", "0"), Pow.nonceTag(0, 0))
    }

    // -----------------------------------------------------------------
    // Mining
    // -----------------------------------------------------------------

    @Test
    fun zeroTargetSucceedsImmediately() {
        val result = Pow.mineChunk(hasher, pubkey, 1_700_000_000L, 1, emptyList(), "hello", 0, 42L, 10)
        assertNotNull(result)
        assertEquals(42L, result.nonce)
        assertEquals(1L, result.attempts)
    }

    @Test
    fun minedIdMeetsTargetAndMatchesCanonicalSerialization() {
        val target = 12
        val result = Pow.mineChunk(hasher, pubkey, 1_700_000_000L, 1, emptyList(), "pow note", target, 0, 500_000)
        assertNotNull(result)
        assertTrue(result.nonce >= 0)
        assertTrue(Pow.difficulty(result.idHex) >= target)

        // Byte-parity lock: recomputing through the canonical codec with the
        // committed nonce tag must reproduce the mined ID exactly.
        val canonical = NostrEventCodec.computeId(
            hasher, pubkey, 1_700_000_000L, 1,
            listOf(Pow.nonceTag(result.nonce, target)), "pow note",
        )
        assertEquals(canonical, result.idHex)
    }

    @Test
    fun miningIsDeterministic() {
        val a = Pow.mineChunk(hasher, pubkey, 1_700_000_000L, 1, emptyList(), "same", 10, 0, 500_000)
        val b = Pow.mineChunk(hasher, pubkey, 1_700_000_000L, 1, emptyList(), "same", 10, 0, 500_000)
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a, b)
    }

    @Test
    fun chunkExhaustionReturnsNullAndResumeSucceeds() {
        // Target 16 needs on average ~65k attempts; a 10k window must miss,
        // and continuing from where it stopped must find a valid nonce.
        val first = Pow.mineChunk(hasher, pubkey, 1_700_000_000L, 1, emptyList(), "chunks", 16, 0, 10_000)
        assertNull(first)

        var start = 0L
        var totalAttempts = 0L
        var found: Pow.MinedAttempt? = null
        repeat(64) {
            if (found == null) {
                val chunk = Pow.mineChunk(hasher, pubkey, 1_700_000_000L, 1, emptyList(), "chunks", 16, start, 10_000)
                totalAttempts += 10_000
                if (chunk != null) found = chunk else start += 10_000
            }
        }
        val result = assertNotNull(found)
        assertTrue(Pow.difficulty(result.idHex) >= 16)
        assertTrue(totalAttempts <= 640_000)
    }

    @Test
    fun miningHonororsExistingTagsAndNoncePosition() {
        val tags = listOf(listOf("t", "pow"), listOf("p", pubkey))
        val result = Pow.mineChunk(hasher, pubkey, 1_700_000_000L, 1, tags, "tagged", 8, 0, 500_000)
        assertNotNull(result)
        val canonical = NostrEventCodec.computeId(
            hasher, pubkey, 1_700_000_000L, 1, tags + listOf(Pow.nonceTag(result.nonce, 8)), "tagged",
        )
        assertEquals(canonical, result.idHex)
    }

    @Test
    fun rejectsOutOfBoundsAndDuplicateNonceTags() {
        assertFailsWith<IllegalArgumentException> {
            Pow.mineChunk(hasher, pubkey, 1L, 1, emptyList(), "x", 33, 0, 10)
        }
        assertFailsWith<IllegalArgumentException> {
            Pow.mineChunk(hasher, pubkey, 1L, 1, emptyList(), "x", 8, 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            Pow.mineChunk(hasher, pubkey, 1L, 1, listOf(listOf("nonce", "1", "8")), "x", 8, 0, 10)
        }
    }
}
