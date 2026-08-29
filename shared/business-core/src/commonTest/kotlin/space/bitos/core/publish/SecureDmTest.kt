package space.bitos.core.publish

import space.bitos.core.crypto.SchnorrSigning
import space.bitos.core.crypto.Nip44
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NIP-17 Secure DMs: full wrap→unwrap round-trip, recipient-only decrypt,
 * wrong-key rejection, structural checks (kinds 14/13/1059, throwaway
 * wrap key, randomized timestamps).
 */
class SecureDmTest {

    private val hasher = Sha256EventHasher
    private val senderPriv = "c".repeat(64)
    private val recipientPriv = "d".repeat(64)
    private val senderPub = Nip44.bytesToHex(SchnorrSigning.publicKey(Nip44.hexToBytes(senderPriv)!!, hasher)!!)
    private val recipientPub = Nip44.bytesToHex(SchnorrSigning.publicKey(Nip44.hexToBytes(recipientPriv)!!, hasher)!!)
    private val now = 1_700_000_000L
    private val pinnedRandom = Random(42) // deterministic tests

    init {
        SecureDmClock.now = { now }
    }

    @Test
    fun wrapAndUnwrapRoundTrips() {
        val wrapped = SecureDmComposer.wrapMessage(
            senderPrivateKeyHex = senderPriv,
            recipientPubkey = recipientPub,
            content = "Hello from the shared core!",
            hasher = hasher,
            random = pinnedRandom,
        )
        assertNotNull(wrapped)

        // Structural checks.
        assertEquals(SecureDmComposer.KIND_PRIVATE_DM, wrapped.rumor.kind)
        assertEquals(SecureDmComposer.KIND_SEAL, wrapped.seal.kind)
        assertEquals(SecureDmComposer.KIND_GIFT_WRAP, wrapped.wrap.kind)
        assertEquals(senderPub, wrapped.rumor.pubkey.value)
        // Gift wrap is signed by a throwaway key, never the sender.
        assertTrue(wrapped.wrap.pubkey.value != senderPub)
        // Wrap tags the recipient.
        assertTrue(wrapped.wrap.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == recipientPub })

        // Recipient unwraps → gets the original message.
        val rumor = SecureDmComposer.unwrap(wrapped.wrap, recipientPriv, hasher)
        assertNotNull(rumor)
        assertEquals("Hello from the shared core!", rumor.content)
        assertEquals(senderPub, rumor.pubkey.value)
        assertEquals(SecureDmComposer.KIND_PRIVATE_DM, rumor.kind)
    }

    @Test
    fun wrongKeyCannotUnwrap() {
        val wrapped = SecureDmComposer.wrapMessage(
            senderPrivateKeyHex = senderPriv,
            recipientPubkey = recipientPub,
            content = "secret",
            hasher = hasher,
            random = pinnedRandom,
        )!!
        // A third party with their own key gets null (silent failure).
        val thirdParty = "e".repeat(64)
        assertNull(SecureDmComposer.unwrap(wrapped.wrap, thirdParty, hasher))
    }

    @Test
    fun wrapTimestampsAreRandomizedWithinTwoDays() {
        val wrapped = SecureDmComposer.wrapMessage(
            senderPrivateKeyHex = senderPriv,
            recipientPubkey = recipientPub,
            content = "timing",
            hasher = hasher,
            random = pinnedRandom,
        )!!
        val twoDays = 2 * 24 * 60 * 60L
        assertTrue(wrapped.wrap.createdAt <= now && wrapped.wrap.createdAt > now - twoDays,
            "wrap timestamp within the last 48h: ${wrapped.wrap.createdAt}")
        assertTrue(wrapped.seal.createdAt <= now && wrapped.seal.createdAt > now - twoDays,
            "seal timestamp within the last 48h: ${wrapped.seal.createdAt}")
    }

    @Test
    fun unicodeContentSurvivesTheRoundTrip() {
        val message = "ສະບາຍດີ ⚡ emoji 🚀 test"
        val wrapped = SecureDmComposer.wrapMessage(
            senderPrivateKeyHex = senderPriv,
            recipientPubkey = recipientPub,
            content = message,
            hasher = hasher,
            random = pinnedRandom,
        )!!
        assertEquals(message, SecureDmComposer.unwrap(wrapped.wrap, recipientPriv, hasher)!!.content)
    }

    @Test
    fun invalidPrivateKeyYieldsNull() {
        assertNull(SecureDmComposer.wrapMessage("not-hex", recipientPub, "x", hasher, pinnedRandom))
        assertNull(SecureDmComposer.wrapMessage("", recipientPub, "x", hasher, pinnedRandom))
    }
}
