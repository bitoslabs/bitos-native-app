package space.bitos.core.model

/**
 * Blossom (BUD-01/02/11) shared rules. HTTP is platform-side; auth-event
 * composition, challenge parsing, the upload-endpoint URL and the BUD-11
 * header encoding live here so both apps produce byte-identical protocol
 * behavior.
 */
object Blossom {

    /** Kind 24242: Blossom server authorization event. */
    const val AUTH_KIND = 24_242

    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** BUD-02: the blob upload endpoint is `PUT {server}/upload`. */
    fun uploadUrl(serverUrl: String): String {
        if (serverUrl.length > 2048) return serverUrl
        val trimmed = serverUrl.trimEnd('/')
        if (trimmed.endsWith("/upload")) return trimmed
        return "$trimmed/upload"
    }

    /**
     * BUD-11: `Authorization: Nostr <base64url(signed event JSON)>`.
     * Padding is included — verified against production servers that reject
     * the unpadded form.
     */
    fun authorizationHeaderValue(signedEventJson: String): String? {
        if (!signedEventJson.startsWith("{") || !signedEventJson.endsWith("}")) return null
        if (signedEventJson.length > 65_536) return null
        return "Nostr " + encodeBase64(signedEventJson.encodeToByteArray())
    }

    class UploadAuth(
        val serverUrl: String,
        val fileHashHex: String,
        val sizeBytes: Long,
        val expirationSeconds: Long,
        val nowSeconds: Long,
    )

    /**
     * Parses a `WWW-Authenticate: Nostr <base64url-json>` challenge header
     * (BUD-11 tokens; the legacy percent-encoded form is also accepted).
     * Returns the expiration it carries, or null when absent/malformed —
     * the caller then falls back to now + 10 minutes.
     */
    fun challengeExpiration(headerValue: String): Long? {
        val prefix = "Nostr "
        if (!headerValue.startsWith(prefix)) return null
        val payload = headerValue.removePrefix(prefix)
        val decoded = decodeBase64(payload)?.decodeToString()
            ?: decodeQueryComponent(payload)
            ?: return null
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

    /**
     * Base64 (URL-safe alphabet, padded) of the BUD-11 token bytes. Padding
     * is mandatory: production servers reject unpadded payloads.
     */
    fun encodeBase64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var index = 0
        while (index < bytes.size) {
            val b0: Int = bytes[index].toInt() and 0xff
            val b1: Int = if (index + 1 < bytes.size) (bytes[index + 1].toInt() and 0xff) else -1
            val b2: Int = if (index + 2 < bytes.size) (bytes[index + 2].toInt() and 0xff) else -1
            out.append(alphabet[b0 ushr 2])
            out.append(alphabet[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 ushr 4 else 0)])
            if (b1 < 0) {
                out.append("==")
                break
            }
            out.append(alphabet[((b1 and 0x0f) shl 2) or (if (b2 >= 0) b2 ushr 6 else 0)])
            if (b2 < 0) {
                out.append('=')
                break
            }
            out.append(alphabet[b2 and 0x3f])
            index += 3
        }
        return out.toString()
    }

    /**
     * Tolerant Base64 decode (URL-safe or standard alphabet, padded or raw).
     * Returns null for any malformed payload — callers fall back to the
     * legacy percent-encoded form.
     */
    fun decodeBase64(value: String): ByteArray? {
        val out = ArrayList<Byte>(value.length * 3 / 4 + 4)
        var buffer = 0
        var bits = 0
        var padding = false
        for (c in value) {
            if (c == '=') {
                padding = true
                continue
            }
            if (padding) return null
            val v = when {
                c in 'A'..'Z' -> c - 'A'
                c in 'a'..'z' -> c - 'a' + 26
                c in '0'..'9' -> c - '0' + 52
                c == '-' || c == '+' -> 62
                c == '_' || c == '/' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer ushr bits) and 0xff).toByte())
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        // 6 leftover bits means a dangling single character.
        if (bits == 6) return null
        return out.toByteArray()
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
    /**
     * Poster/cover frame URL (plan §3.4, MST-032): a JPEG uploaded
     * separately and referenced as the imeta `thumb` so clients show the
     * creator-selected cover before decoding the clip. Same URL policy
     * as [url]; null = no cover.
     */
    val thumbUrl: String? = null,
    /** Verified byte-identical media mirrors, tried after [url] by players. */
    val fallbackUrls: List<String> = emptyList(),
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
        thumbUrl?.let { thumb ->
            require(
                thumb.startsWith("https://") ||
                    thumb.startsWith("http://127.0.0.1:") || thumb.startsWith("http://localhost:")
            ) { "Cover thumbs require HTTPS" }
            require(thumb.length <= 2048)
        }
        require(fallbackUrls.size <= 2) { "At most two media fallbacks" }
        fallbackUrls.forEach { fallback ->
            require(
                fallback.startsWith("https://") ||
                    fallback.startsWith("http://127.0.0.1:") || fallback.startsWith("http://localhost:")
            ) { "Media fallbacks require HTTPS" }
            require(fallback.length <= 2048)
            require(fallback != url) { "Fallback must differ from primary URL" }
        }
    }

    /**
     * NIP-92 imeta field list for this descriptor (web `buildKind22` parity:
     * url, m, size, dim, thumb, x, duration, bitrate). Duration keeps its
     * millisecond precision as `s.mmm` (web `toFixed(3)`); bitrate is the
     * rounded bits/second of the uploaded bytes over that duration, emitted
     * only for clips longer than [MIN_BITRATE_DURATION_MS] (web gates at
     * 0.2 s so a poster-frame still never divides by ~0).
     */
    fun imetaFields(): List<String> = buildList {
        add("url $url")
        fallbackUrls.distinct().forEach { add("fallback $it") }
        add("m $mimeType")
        add("size $sizeBytes")
        if (width != null && height != null) add("dim ${width}x${height}")
        thumbUrl?.let { add("thumb $it") }
        add("x $sha256Hex")
        durationMs?.let { ms ->
            add("duration ${formatDurationSeconds(ms)}")
            if (ms > MIN_BITRATE_DURATION_MS) {
                add("bitrate ${(sizeBytes * 8_000L + ms / 2) / ms}")
            }
        }
    }

    private fun formatDurationSeconds(ms: Long): String =
        "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"

    private companion object {
        /** Web parity: no bitrate under 0.2 s of media. */
        const val MIN_BITRATE_DURATION_MS = 200L
    }
}
