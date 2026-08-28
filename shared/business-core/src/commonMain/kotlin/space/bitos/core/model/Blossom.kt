package space.bitos.core.model

/**
 * Blossom (BUD-01/02) shared rules. HTTP is platform-side; auth-event
 * composition, challenge parsing and descriptor building live here so both
 * apps produce byte-identical protocol behavior.
 */
object Blossom {

    /** Kind 24242: Blossom server authorization event. */
    const val AUTH_KIND = 24_242

    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    class UploadAuth(
        val serverUrl: String,
        val fileHashHex: String,
        val sizeBytes: Long,
        val expirationSeconds: Long,
        val nowSeconds: Long,
    )

    /**
     * Parses a `WWW-Authenticate: Nostr <urlencoded-json>` challenge header.
     * Returns the expiration it carries, or null when absent/malformed —
     * the client then falls back to now + 10 minutes.
     */
    fun challengeExpiration(headerValue: String): Long? {
        val prefix = "Nostr "
        if (!headerValue.startsWith(prefix)) return null
        val decoded = decodeQueryComponent(headerValue.removePrefix(prefix)) ?: return null
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(decoded)
        }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null
        // The template event carries expiration in its tags.
        val tags = root["tags"] as? kotlinx.serialization.json.JsonArray ?: return null
        for (tag in tags) {
            val array = tag as? kotlinx.serialization.json.JsonArray ?: continue
            if (array.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == "expiration") {
                return (array.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            }
        }
        return null
    }

    /** Percent-decodes a query component (both %XX and '+' as space). */
    fun decodeQueryComponent(value: String): String? {
        val out = StringBuilder()
        var index = 0
        while (index < value.length) {
            val c = value[index]
            when {
                c == '%' -> {
                    if (index + 2 >= value.length + 1) return null
                    if (index + 2 > value.length - 1) return null
                    val hi = hexDigit(value[index + 1]) ?: return null
                    val lo = hexDigit(value[index + 2]) ?: return null
                    out.append(((hi shl 4) or lo).toChar())
                    index += 3
                }
                c == '+' -> {
                    out.append(' ')
                    index += 1
                }
                else -> {
                    out.append(c)
                    index += 1
                }
            }
        }
        return out.toString()
    }

    /** Percent-encodes for query components (unreserved set kept literal). */
    fun encodeQueryComponent(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xff
            when {
                c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code -> append(c.toChar())
                else -> {
                    append('%')
                    append("0123456789ABCDEF"[c ushr 4])
                    append("0123456789ABCDEF"[c and 0x0f])
                }
            }
        }
    }

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}

/** Uploaded media descriptor feeding the kind-22 `imeta` tag. */
data class UploadedMedia(
    val url: String,
    val sha256Hex: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
) {
    init {
        // HTTPS in production; loopback HTTP is allowed for the local dev stack.
        require(
            url.startsWith("https://") ||
                url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")
        ) { "Published media requires HTTPS" }
        require(url.length <= 2048)
        require(sha256Hex.length == 64)
        require(mimeType.matches(Regex("^(video|image)/[\\w.+-]+$"))) { "unsupported media type" }
        require(sizeBytes in 1..Blossom.MAX_FILE_BYTES)
        width?.let { require(it in 1..100_000) }
        height?.let { require(it in 1..100_000) }
        durationMs?.let { require(it in 1..24L * 60 * 60 * 1000) }
    }

    /** NIP-92 imeta field list for this descriptor. */
    fun imetaFields(): List<String> = buildList {
        add("url $url")
        add("m $mimeType")
        add("x $sha256Hex")
        add("size $sizeBytes")
        if (width != null && height != null) add("dim ${width}x${height}")
        if (durationMs != null) add("duration ${durationMs / 1000}")
    }
}
