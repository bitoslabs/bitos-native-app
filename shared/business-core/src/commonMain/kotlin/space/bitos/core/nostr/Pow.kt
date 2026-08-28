package space.bitos.core.nostr

/**
 * NIP-13 proof-of-work: difficulty = leading zero bits of the event ID;
 * mining commits a `["nonce", <counter>, <target>]` tag into the event.
 * Pure and chunked — callers drive bounded attempt windows so UI threads
 * stay free and cancellation is cooperative (never mine on the UI thread).
 */
object Pow {

    /** Hard target ceiling for UI-facing mining (sanity bound). */
    const val MAX_TARGET_DIFFICULTY = 32

    /** Per-call attempt ceiling so a buggy loop cannot spin unbounded. */
    const val MAX_ATTEMPTS_PER_CHUNK = 5_000_000L

    data class MinedAttempt(val nonce: Long, val attempts: Long, val idHex: String)

    /** Leading zero bits of a lowercase hex id. Invalid hex → -1. */
    fun difficulty(idHex: String): Int {
        if (idHex.isEmpty()) return -1
        var zeros = 0
        for (char in idHex) {
            val nibble = when (char) {
                in '0'..'9' -> char - '0'
                in 'a'..'f' -> char - 'a' + 10
                else -> return -1
            }
            if (nibble == 0) {
                zeros += 4
                continue
            }
            return zeros + when (nibble) {
                1 -> 3      // 0001
                in 2..3 -> 2 // 001x
                in 4..7 -> 1 // 01xx
                else -> 0    // 1xxx
            }
        }
        return zeros
    }

    /**
     * NIP-13 committed nonce tag. The target element must be included so
     * relays can reject understated difficulty claims.
     */
    fun nonceTag(nonce: Long, targetDifficulty: Int): List<String> =
        listOf("nonce", nonce.toString(), targetDifficulty.toString())

    /**
     * Mines at most [maxAttempts] nonces starting at [startNonce], appending
     * the nonce tag as the LAST tag, returning the first attempt whose
     * canonical NIP-01 id carries ≥ [targetDifficulty] leading zero bits —
     * else null (caller resumes at `startNonce + attempts`).
     *
     * The per-attempt serialization is byte-identical to
     * [NostrEventCodec.serializeForId] over `tags + nonceTag(...)` (locked
     * by tests through [NostrEventCodec.computeId]).
     */
    fun mineChunk(
        hasher: EventHasher,
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        targetDifficulty: Int,
        startNonce: Long,
        maxAttempts: Long,
    ): MinedAttempt? {
        require(targetDifficulty in 0..MAX_TARGET_DIFFICULTY) { "target out of range" }
        require(maxAttempts in 1..MAX_ATTEMPTS_PER_CHUNK) { "attempt window out of range" }
        if (tags.any { it.firstOrNull() == "nonce" }) {
            throw IllegalArgumentException("tags already contain a nonce tag")
        }

        // Canonical serialization split around the decimal nonce digits —
        // only the digits vary per attempt.
        val prefix = StringBuilder().apply {
            append("[0,\"").append(pubkey).append("\",")
                .append(createdAt).append(',').append(kind).append(",[")
            tags.forEachIndexed { tagIndex, tag ->
                if (tagIndex > 0) append(',')
                append('[')
                tag.forEachIndexed { itemIndex, item ->
                    if (itemIndex > 0) append(',')
                    append('"').append(NostrEventCodec.escape(item)).append('"')
                }
                append(']')
            }
            if (tags.isNotEmpty()) append(',')
            append("[\"nonce\",\"")
        }
        val suffix = "\",\"$targetDifficulty\"]],\"" + NostrEventCodec.escape(content) + "\"]"

        var nonce = startNonce
        var attempts = 0L
        while (attempts < maxAttempts) {
            val id = hasher.sha256((prefix.toString() + nonce.toString() + suffix).encodeToByteArray())
                .toLowercaseHex()
            attempts++
            if (difficulty(id) >= targetDifficulty) {
                return MinedAttempt(nonce = nonce, attempts = attempts, idHex = id)
            }
            nonce++
        }
        return null
    }
}
