package space.bitos.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Import-field rule vectors (ID-004). Keys are the public test fixtures from
 * `NostrKeyCodecTest`/`SchnorrSigningTest`; copy defects model the mistakes
 * users actually make when pasting a key.
 */
class KeyImportFormTest {

    private val secretHex = "d2ad3c3c9e7b0f4f6a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718"
    private val nsec = "nsec162knc0y70v8576su95ly75rpw2peffdkclvwnu9pktpafe0kquvqh3ydrs"
    private val npub = "npub194667yy2sqh4h4vlwssg7g5smhmqx4x9hgtfdjun8e46l30kxqqselzc9y"

    @Test
    fun acceptsValidNsecAndResolvesSecret() {
        val check = KeyImportForm.check(nsec)
        assertEquals(KeyImportVerdict.READY, check.verdict)
        assertEquals(secretHex, check.secretHex)
        assertEquals("Valid nsec key.", check.message)
    }

    @Test
    fun readyChecksCarryTheDerivedIdentity() {
        // KF-6: a READY check previews the account the key controls — the
        // npub derives from the same secret, no storage involved.
        listOf(nsec, secretHex, secretHex.uppercase(), "  $nsec\n").forEach { input ->
            val check = KeyImportForm.check(input)
            assertEquals(KeyImportVerdict.READY, check.verdict)
            assertEquals(64, check.pubkeyHex?.length)
            assertTrue(check.pubkeyHex!!.all { it in '0'..'f' })
            assertTrue(check.npub!!.startsWith("npub1"))
            assertEquals(check.pubkeyHex, NostrKeyCodec.parseNpub(check.npub!!))
        }
    }

    @Test
    fun nonReadyVerdictsNeverCarryIdentity() {
        listOf("", npub, nsec.dropLast(4), nsec + "q", "note182…", nsec.dropLast(2) + " q").forEach { input ->
            val check = KeyImportForm.check(input)
            if (check.verdict != KeyImportVerdict.READY) {
                assertNull(check.pubkeyHex)
                assertNull(check.npub)
            }
        }
    }

    @Test
    fun trimsSurroundingWhitespaceAndNewlines() {
        val check = KeyImportForm.check("  $nsec\n")
        assertEquals(KeyImportVerdict.READY, check.verdict)
        assertEquals(secretHex, check.secretHex)
    }

    @Test
    fun acceptsUppercaseNsec() {
        val check = KeyImportForm.check(nsec.uppercase())
        assertEquals(KeyImportVerdict.READY, check.verdict)
        assertEquals(secretHex, check.secretHex)
    }

    @Test
    fun acceptsHex64SecretLowercased() {
        val check = KeyImportForm.check(secretHex.uppercase())
        assertEquals(KeyImportVerdict.READY, check.verdict)
        assertEquals(secretHex, check.secretHex)
        assertEquals("Valid hex secret key.", check.message)
    }

    @Test
    fun emptyInputIsEmptyVerdict() {
        assertEquals(KeyImportVerdict.EMPTY, KeyImportForm.check("").verdict)
        assertEquals(KeyImportVerdict.EMPTY, KeyImportForm.check("   \n ").verdict)
        assertNull(KeyImportForm.check("").message)
        assertNull(KeyImportForm.check("").secretHex)
    }

    @Test
    fun npubIsRejectedWithKeyTypeCopy() {
        val check = KeyImportForm.check(npub)
        assertEquals(KeyImportVerdict.WRONG_KEY_TYPE, check.verdict)
        assertEquals("That is a public key (npub); import needs the secret (nsec).", check.message)
        assertNull(check.secretHex)
    }

    @Test
    fun truncatedNsecReportsTooShort() {
        val check = KeyImportForm.check(nsec.dropLast(4))
        assertEquals(KeyImportVerdict.TOO_SHORT, check.verdict)
        assertEquals("Too short — an nsec key is 63 characters.", check.message)
    }

    @Test
    fun paddedNsecReportsTooLong() {
        val check = KeyImportForm.check(nsec + "q")
        assertEquals(KeyImportVerdict.TOO_LONG, check.verdict)
        assertEquals("Too long — an nsec key is 63 characters.", check.message)
    }

    @Test
    fun corruptedChecksumAtRightLengthIsInvalid() {
        val corrupted = npub.replaceRange(npub.length - 1, npub.length, "z")
            .replace("npub1", "nsec1", ignoreCase = false)
        // Same length (63), decodable alphabet, fails the bech32 checksum.
        assertEquals(63, corrupted.length)
        val check = KeyImportForm.check(corrupted)
        assertEquals(KeyImportVerdict.INVALID, check.verdict)
        assertEquals("Not a valid nsec key — check it against the source you copied from.", check.message)
    }

    @Test
    fun internalSpaceIsBadCharacter() {
        val check = KeyImportForm.check(nsec.dropLast(2) + " q")
        assertEquals(KeyImportVerdict.BAD_CHARACTER, check.verdict)
    }

    @Test
    fun wrongEntityPrefixIsBadPrefix() {
        assertEquals(KeyImportVerdict.BAD_PREFIX, KeyImportForm.check("note182…").verdict)
        assertEquals(KeyImportVerdict.BAD_PREFIX, KeyImportForm.check("nprofile1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq").verdict)
        // 63 hex characters are neither an nsec nor a hex secret.
        assertEquals(KeyImportVerdict.BAD_PREFIX, KeyImportForm.check("f".repeat(63)).verdict)
    }
}
