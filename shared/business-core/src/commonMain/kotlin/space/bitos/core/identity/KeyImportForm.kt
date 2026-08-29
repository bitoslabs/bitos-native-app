package space.bitos.core.identity

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
            if (secret != null) return KeyImportCheck(KeyImportVerdict.READY, "Valid nsec key.", secret)
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
            return KeyImportCheck(KeyImportVerdict.READY, "Valid hex secret key.", lowered)
        }
        return KeyImportCheck(
            KeyImportVerdict.BAD_PREFIX,
            "Secret keys start with nsec1 (or are $HEX_SECRET_LENGTH hex characters).",
        )
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
