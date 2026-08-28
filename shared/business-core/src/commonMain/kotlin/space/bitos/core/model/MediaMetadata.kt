package space.bitos.core.model

/**
 * Display-oriented media attachment for feed notes (FED foundation).
 *
 * Parses kind-22 attachments (NIP-92 `imeta` tags with url/m/dim fields and
 * a poster image attachment) and legacy kind-1 video URLs in content. All
 * inputs are untrusted relay data: URLs are scheme/length bounded and
 * dimensions capped, so a hostile event cannot inflate memory or smuggle a
 * non-media locator.
 */
data class MediaMetadata(
    val url: String,
    val mimeType: String?,
    val posterUrl: String?,
    val width: Int?,
    val height: Int?,
) {
    companion object {
        private const val MAX_URL_LENGTH = 2048
        private const val MAX_DIMENSION = 100_000
        private val httpUrl = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
        private val videoMime = Regex("^video/[\\w.+-]+$", RegexOption.IGNORE_CASE)
        private val imageMime = Regex("^image/[\\w.+-]+$", RegexOption.IGNORE_CASE)
        private val videoExt = Regex("\\.(mp4|webm|mov|m4v)(?:[?#]\\S*)?$", RegexOption.IGNORE_CASE)
        private val imageExt = Regex("\\.(jpe?g|png|webp|avif)(?:[?#]\\S*)?$", RegexOption.IGNORE_CASE)
        private val videoInContent = Regex("https?://\\S+\\.(?:mp4|webm|mov|m4v)(?:[?#]\\S*)?", RegexOption.IGNORE_CASE)

        /** First video attachment with its poster, or null when the event carries none. */
        fun fromEvent(event: NostrEvent): MediaMetadata? {
            var videoUrl: String? = null
            var videoMime: String? = null
            var posterUrl: String? = null
            var width: Int? = null
            var height: Int? = null

            for (tag in event.tags) {
                when (tag.firstOrNull()) {
                    "imeta" -> {
                        val fields = parseImetaFields(tag)
                        val url = fields["url"]?.takeIf(::isHttpUrl) ?: continue
                        val mime = fields["m"]?.takeIf { it.length <= 128 }
                        when {
                            isVideo(url, mime) && videoUrl == null -> {
                                videoUrl = url
                                videoMime = mime
                                val dim = fields["dim"]?.let(::parseDim)
                                width = dim?.first
                                height = dim?.second
                            }
                            isImage(url, mime) && posterUrl == null -> posterUrl = url
                        }
                    }
                    "url" -> {
                        val url = tag.getOrNull(1)?.takeIf(::isHttpUrl) ?: continue
                        // The m tag pairs positionally with the url tag it follows.
                    val mime = nextMTagAfter(event.tags, tag)?.getOrNull(1)?.takeIf { it.length <= 128 }
                        if (videoUrl == null && isVideo(url, mime)) {
                            videoUrl = url
                            videoMime = mime
                        } else if (posterUrl == null && isImage(url, mime)) {
                            posterUrl = url
                        }
                    }
                }
            }

            // Legacy kind-1: a video URL inside the note content.
            if (videoUrl == null) {
                videoUrl = videoInContent.find(event.content)?.value?.takeIf(::isHttpUrl)
                videoMime = null
            }
            if (videoUrl == null) return null
            return MediaMetadata(videoUrl, videoMime, posterUrl, width, height)
        }

        private fun parseImetaFields(tag: List<String>): Map<String, String> {
            val fields = mutableMapOf<String, String>()
            for (index in 1 until tag.size) {
                val entry = tag[index]
                val separator = entry.indexOf(' ')
                if (separator <= 0 || separator == entry.length - 1) continue
                val key = entry.substring(0, separator)
                val value = entry.substring(separator + 1)
                if (key.length <= 32 && value.length <= MAX_URL_LENGTH) fields[key] = value
            }
            return fields
        }

        private fun nextMTagAfter(tags: List<List<String>>, urlTag: List<String>): List<String>? {
            var seenUrlTag = false
            for (tag in tags) {
                when {
                    tag === urlTag -> seenUrlTag = true
                    seenUrlTag && tag.firstOrNull() == "m" -> return tag
                    seenUrlTag && tag.firstOrNull() == "url" -> return null // next url pairs with its own m
                }
            }
            return null
        }

        private fun isHttpUrl(url: String): Boolean =
            url.length <= MAX_URL_LENGTH && httpUrl.matches(url)

        private fun isVideo(url: String, mime: String?): Boolean =
            mime?.let { videoMime.matches(it) } ?: videoExt.containsMatchIn(url)

        private fun isImage(url: String, mime: String?): Boolean =
            mime?.let { imageMime.matches(it) } ?: imageExt.containsMatchIn(url)

        private fun parseDim(raw: String): Pair<Int, Int>? {
            val parts = raw.split("x")
            if (parts.size != 2) return null
            val width = parts[0].toIntOrNull() ?: return null
            val height = parts[1].toIntOrNull() ?: return null
            if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) return null
            return width to height
        }
    }
}
