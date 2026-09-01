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
    /** NIP-92 imeta `duration` (whole seconds, 1..[MAX_DURATION_SECONDS]); null = unknown. */
    val durationSeconds: Long? = null,
    /**
     * Mirror URLs for the primary [url] (NIP-92 `fallback` imeta fields,
     * order preserved, deduped, bounded by [MAX_FALLBACK_URLS]). Walked by
     * the player only when the primary fails — a failover chain, not an
     * alternative rendition.
     */
    val fallbackUrls: List<String> = emptyList(),
    /**
     * Alternative encodings of the same clip (NIP-92 `fallbackrendition`
     * `variant` entries), sorted tall→short by height, then bitrate. Pure
     * data — selection happens in [selectRendition], bounded to
     * [MAX_RENDITIONS].
     */
    val renditions: List<MediaRendition> = emptyList(),
) {
    /**
     * Picks the rendition whose height best fits [targetHeight] (the
     * display's long edge, see the Bitz performance study §2.6): tallest
     * fitting under `targetHeight × 1.25` (DPR headroom); when everything
     * overshoots, the smallest available (the client downscales). Pure —
     * no platform or network dependency. Falls back to the primary [url]
     * when no rendition carries usable dimensions.
     */
    fun selectRendition(targetHeight: Int): String {
        if (renditions.isEmpty()) return url
        val cap = targetHeight.toLong() * 1_250L / 1_000L
        var best: MediaRendition? = null
        var smallest: MediaRendition? = null
        for (rendition in renditions) {
            val h = rendition.height.toLong()
            if (h in 1..cap) {
                if (best == null || h > best!!.height.toLong()) best = rendition
            }
            if (smallest == null || h < smallest!!.height.toLong()) smallest = rendition
        }
        return (best ?: smallest ?: renditions.first()).url
    }

    /**
     * APP-018 `bitos_video_quality` = high: always the tallest rung —
     * display quality over bandwidth. Deterministic on height ties (the
     * ladder is tall→short, `maxByOrNull` keeps the first = tallest).
     */
    fun selectTallestRendition(): String {
        if (renditions.isEmpty()) return url
        return renditions.maxByOrNull { it.height.toLong() }?.url ?: url
    }

    /**
     * APP-018 `bitos_video_quality` = low (data saver, UX U9): the shortest
     * rung at or above [DATA_SAVER_MIN_HEIGHT] pixels — least data that
     * stays watchable. When every rung is shorter, the shortest available
     * (the preference never returns the primary when a rung exists).
     */
    fun selectDataSaverRendition(): String {
        if (renditions.isEmpty()) return url
        val watchable = renditions.filter { it.height >= DATA_SAVER_MIN_HEIGHT }
        val candidates = watchable.ifEmpty { renditions }
        return candidates.minByOrNull { it.height.toLong() }?.url ?: url
    }

    companion object {
        private const val MAX_URL_LENGTH = 2048
        private const val MAX_DIMENSION = 100_000

        /** Data-saver floor: shortest rung at/above this stays watchable. */
        const val DATA_SAVER_MIN_HEIGHT = 360

        /** Hostile-input bound: mirrors beyond 8 are dropped. */
        const val MAX_FALLBACK_URLS = 8

        /** Hostile-input bound: rendition ladder entries beyond 8 are dropped. */
        const val MAX_RENDITIONS = 8

        /** 4 h — longer imeta durations are hostile data, not real clips. */
        const val MAX_DURATION_SECONDS = 14_400L
        private val httpUrl = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
        private val videoMime = Regex("^video/[\\w.+-]+$", RegexOption.IGNORE_CASE)
        private val imageMime = Regex("^image/[\\w.+-]+$", RegexOption.IGNORE_CASE)
        private val videoExt = Regex("\\.(mp4|webm|mov|m4v)(?:[?#]\\S*)?$", RegexOption.IGNORE_CASE)
        private val imageExt = Regex("\\.(jpe?g|png|webp|avif)(?:[?#]\\S*)?$", RegexOption.IGNORE_CASE)
        private val videoInContent = Regex("https?://\\S+\\.(?:mp4|webm|mov|m4v)(?:[?#]\\S*)?", RegexOption.IGNORE_CASE)

        /** Locale-free duration label: `0:59`, `12:05`, `1:02:03`. */
        fun formatDuration(seconds: Long): String {
            val total = if (seconds < 0) 0 else seconds
            val hours = total / 3_600
            val minutes = (total % 3_600) / 60
            val secs = total % 60
            return if (hours > 0) {
                "$hours:" + two(minutes) + ":" + two(secs)
            } else {
                "$minutes:" + two(secs)
            }
        }

        private fun two(value: Long): String = if (value < 10) "0$value" else value.toString()

        /** First video attachment with its poster, or null when the event carries none. */
        fun fromEvent(event: NostrEvent): MediaMetadata? {
            var videoUrl: String? = null
            var videoMime: String? = null
            var posterUrl: String? = null
            // Explicit cover hints (preview/image) render instantly and cost
            // no video download, so they outrank any derived poster.
            var posterHint: String? = null
            var width: Int? = null
            var height: Int? = null
            var durationSeconds: Long? = null
            val fallbacks = LinkedHashSet<String>()
            val renditions = ArrayList<MediaRendition>(MAX_RENDITIONS)

            for (tag in event.tags) {
                when (tag.firstOrNull()) {
                    "imeta" -> {
                        val fields = parseImetaFields(tag)
                        // A fallbackrendition block carries `variant <url>
                        // <dim> <bitrate>` entries describing renditions of
                        // the clip, without carrying its own url/m fields.
                        if (fields["fallbackrendition"] != null) {
                            collectRendition(fields, renditions)
                        }
                        // Mirrors ride along any imeta block, order preserved.
                        fields["fallback"]?.takeIf(::isHttpUrl)?.let { fallbacks.add(it) }
                        // Flutter parity (media_utils.dart posterUrlFor): an
                        // imeta `preview <url>` / `image <url>` value is a
                        // non-standard but harmless explicit cover hint.
                        if (posterHint == null) {
                            val hinted = fields["preview"] ?: fields["image"]
                            posterHint = hinted?.takeIf(::isHttpUrl)
                        }
                        val url = fields["url"]?.takeIf(::isHttpUrl) ?: continue
                        val mime = fields["m"]?.takeIf { it.length <= 128 }
                        when {
                            isVideo(url, mime) && videoUrl == null -> {
                                videoUrl = url
                                videoMime = mime
                                val dim = fields["dim"]?.let(::parseDim)
                                width = dim?.first
                                height = dim?.second
                                durationSeconds = fields["duration"]?.let(::parseDuration)
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
                    // Flutter parity: top-level `preview <url>` / `image <url>`
                    // tags are the primary publisher cover hint.
                    "preview", "image" -> {
                        if (posterHint == null) {
                            posterHint = tag.getOrNull(1)?.takeIf(::isHttpUrl)
                        }
                    }
                    "fallback" -> {
                        // Legacy/positional mirror tag form.
                        tag.getOrNull(1)?.takeIf(::isHttpUrl)?.let { fallbacks.add(it) }
                    }
                }
            }

            // Legacy kind-1: a video URL inside the note content.
            if (videoUrl == null) {
                videoUrl = videoInContent.find(event.content)?.value?.takeIf(::isHttpUrl)
                videoMime = null
            }
            if (videoUrl == null) return null
            // A hinted poster beats any poster-derived-from-attachment URL.
            val resolvedPoster = posterHint ?: posterUrl
            // Same-URL variants are mirrors, not renditions (codec parity).
            val ladder = renditions
                .filter { it.url != videoUrl }
                .sortedWith(compareByDescending<MediaRendition> { it.height }.thenByDescending { it.bitrate })
                .take(MAX_RENDITIONS)
                .distinctBy { it.url }
            return MediaMetadata(
                videoUrl,
                videoMime,
                resolvedPoster,
                width,
                height,
                durationSeconds,
                fallbackUrls = fallbacks.toList().take(MAX_FALLBACK_URLS),
                renditions = ladder,
            )
        }

        /** `variant <url> <dim> <bitrate>` (bitrate optional) → a ladder entry. */
        private fun collectRendition(fields: Map<String, String>, out: MutableList<MediaRendition>) {
            // The payload rides the `fallbackrendition` field value as
            // `variant <url> <dim> <bitrate>`; a hostile bitrate drops the
            // rung entirely rather than poisoning the ladder with 0.
            val payload = fields["fallbackrendition"] ?: return
            val parts = payload.trim().split(' ')
            if (parts.firstOrNull() != "variant") return
            val url = parts.getOrNull(1)?.takeIf(::isHttpUrl) ?: return
            val dim = parts.getOrNull(2)?.let(::parseDim) ?: return
            val bitrateToken = parts.getOrNull(3)
            val bitrate = if (bitrateToken == null) {
                0L
            } else {
                bitrateToken.toLongOrNull()?.takeIf { it in 1..2_000_000_000L } ?: return
            }
            out.add(MediaRendition(url, minOf(dim.first, dim.second), bitrate))
        }

        private fun parseDuration(raw: String): Long? =
            raw.toLongOrNull()?.takeIf { it in 1..MAX_DURATION_SECONDS }

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

/**
 * One alternative encoding of the same clip (NIP-92 `fallbackrendition`
 * `variant` entry). Renditions are lower/alternate-height encodings, not
 * host mirrors — selection policy lives in `MediaMetadata.selectRendition`.
 */
data class MediaRendition(
    val url: String,
    /** Short-edge height in pixels; 0 = unknown. */
    val height: Int,
    /** Bits per second when published; 0 = unknown. */
    val bitrate: Long,
)
