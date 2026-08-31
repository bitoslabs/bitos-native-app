package space.bitos.core.identity

import space.bitos.core.crypto.SchnorrSigning
import space.bitos.core.nostr.Sha256EventHasher

/**
 * Shared nsec-import field rules (ID-004): one deterministic classifier so
 * the Compose and SwiftUI login fields render identical live feedback and
 * identical rejection copy. Pure — verdicts and copy only, no side effects.
 */
enum class KeyImportVerdict {
    EMPTY,
    READY,
    WRONG_KEY_TYPE,
    TOO_SHORT,
    TOO_LONG,
    BAD_PREFIX,
    BAD_CHARACTER,
    INVALID,
}

class KeyImportCheck(
    val verdict: KeyImportVerdict,
    val message: String? = null,
    /** hex64 secret; non-null iff [verdict] is READY. */
    val secretHex: String? = null,
    /** Derived x-only pubkey hex; non-null iff [verdict] is READY. */
    val pubkeyHex: String? = null,
    /** Derived npub of [pubkeyHex]; non-null iff [verdict] is READY. */
    val npub: String? = null,
)

object KeyImportForm {

    private const val NSEC_LENGTH = 63
    private const val HEX_SECRET_LENGTH = 64

    const val NSEC_PREFIX = "nsec1"
    const val NPUB_PREFIX = "npub1"

    fun check(raw: String): KeyImportCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return KeyImportCheck(KeyImportVerdict.EMPTY)
        if (trimmed.any { it.isWhitespace() }) {
            return KeyImportCheck(
                KeyImportVerdict.BAD_CHARACTER,
                "Remove spaces and line breaks — a key is one unbroken string.",
            )
        }
        val lowered = trimmed.lowercase()
        if (lowered.startsWith(NPUB_PREFIX)) {
            return KeyImportCheck(
                KeyImportVerdict.WRONG_KEY_TYPE,
                "That is a public key (npub); import needs the secret (nsec).",
            )
        }
        if (lowered.startsWith(NSEC_PREFIX)) {
            val secret = NostrKeyCodec.parseNsec(trimmed)
            if (secret != null) return ready(secret, "Valid nsec key.")
            val tooShort = trimmed.length < NSEC_LENGTH
            val tooLong = trimmed.length > NSEC_LENGTH
            return KeyImportCheck(
                when {
                    tooShort -> KeyImportVerdict.TOO_SHORT
                    tooLong -> KeyImportVerdict.TOO_LONG
                    else -> KeyImportVerdict.INVALID
                },
                when {
                    tooShort -> "Too short — an nsec key is $NSEC_LENGTH characters."
                    tooLong -> "Too long — an nsec key is $NSEC_LENGTH characters."
                    else -> "Not a valid nsec key — check it against the source you copied from."
                },
            )
        }
        if (trimmed.length == HEX_SECRET_LENGTH && trimmed.all { it.isHexDigit() }) {
            return ready(lowered, "Valid hex secret key.")
        }
        return KeyImportCheck(
            KeyImportVerdict.BAD_PREFIX,
            "Secret keys start with nsec1 (or are $HEX_SECRET_LENGTH hex characters).",
        )
    }

    /**
     * READY check with the derived identity attached so import surfaces can
     * preview "the account this key controls" live, before submit (KF-6).
     * Derivation is pure schnorr x-only; no state, no storage.
     */
    private fun ready(secretHex: String, message: String): KeyImportCheck {
        val pubkey = SchnorrSigning.publicKey(hexBytes(secretHex), Sha256EventHasher)
            ?: return KeyImportCheck(KeyImportVerdict.INVALID, "Key rejected by the signer.")
        val pubkeyHex = bytesToHex(pubkey)
        return KeyImportCheck(
            KeyImportVerdict.READY,
            message,
            secretHex,
            pubkeyHex,
            NostrKeyCodec.npub(pubkeyHex),
        )
    }

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun bytesToHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(digits[value shr 4]).append(digits[value and 0x0f])
        }
        return out.toString()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
