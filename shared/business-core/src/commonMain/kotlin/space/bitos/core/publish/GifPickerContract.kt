package space.bitos.core.publish

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One GIF from the picker: the full-resolution URL is what gets embedded in
 * the note (exactly how Giphy links appear in real Nostr notes); the preview
 * and its dimensions render the grid tile.
 */
data class GifChoice(
    val id: String,
    val url: String,
    val preview: String,
    val width: Int,
    val height: Int,
)

/** Restored picker cache: recents, the last trending page and its stamp. */
data class CachedGifs(
    val recent: List<GifChoice>,
    val trending: List<GifChoice>,
    val savedAtMs: Long,
)

/**
 * APP-008 GIF picker rules (legacy Flutter `GifPickerSheet` / web
 * `GifPicker.svelte` parity): the deterministic product rules behind the
 * composer's GIF attachment — Giphy request building, the lenient response
 * parse with its preview-fallback chain, pagination math, the Recent list
 * merge (dedup, newest first, cap) and the versioned, size-bounded cache
 * wire. Natives own HTTP, image loading and persistence only.
 */
object GifPickerContract {

    const val PAGE_SIZE = 30
    const val RECENT_LIMIT = 12

    /** Trending cache lifetime (24 h). */
    const val CACHE_TTL_MS: Long = 24 * 60 * 60 * 1_000L

    /** Search-as-you-type debounce. */
    const val SEARCH_DEBOUNCE_MS: Long = 350L

    /**
     * Giphy's public beta key — rate-limited, docs/demo use (web + legacy
     * Flutter parity). Products with real traffic swap their own key at the
     * adapter boundary.
     */
    const val DEFAULT_API_KEY = "Gc7131jiJuvI7IdN0HZ1D7nh0ow5BU6g"

    /** 42 items × ~500-char records — hard wire bound. */
    const val MAX_WIRE_LENGTH = 65_536

    /** Cached trending keeps one page. */
    const val MAX_CACHED_TRENDING = 30

    private const val MAX_PARSED = 2 * PAGE_SIZE
    private const val MAX_ID = 64
    private const val MAX_URL = 2_048
    private const val RATING = "pg"

    private val lenientJson = Json { ignoreUnknownKeys = true }

    // ── Requests ─────────────────────────────────────────────────────

    /** Trending when the query is blank, search otherwise; offset pages. */
    fun buildUrl(apiKey: String, query: String, offset: Int): String {
        val kind = if (query.isBlank()) "trending" else "search"
        val q = encodeQueryComponent(query.trim())
        val search = if (kind == "search") "&q=$q" else ""
        val page = offset.coerceAtLeast(0)
        return "https://api.giphy.com/v1/gifs/$kind?api_key=$apiKey$search&limit=$PAGE_SIZE&offset=$page&rating=$RATING"
    }

    /** RFC 3986 percent-encoding for one query component. */
    internal fun encodeQueryComponent(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c in '0'.code..'9'.code || c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            ) {
                append(c.toChar())
            } else {
                append('%')
                append(((c shr 4) and 0xF).toString(16).uppercase())
                append((c and 0xF).toString(16).uppercase())
            }
        }
    }

    // ── Response parse ───────────────────────────────────────────────

    /**
     * Giphy `data` parse: preview prefers `fixed_height_small`, then
     * `fixed_height`, then `original`; the full URL prefers `original`.
     * Malformed entries are dropped, never thrown.
     */
    fun parseChoices(responseJson: String): List<GifChoice> = try {
        val data = lenientJson.parseToJsonElement(responseJson).jsonObject["data"]?.jsonArray ?: return emptyList()
        data.mapNotNull { element -> (element as? kotlinx.serialization.json.JsonObject)?.let(::choiceFrom) }.take(MAX_PARSED)
    } catch (_: Exception) {
        emptyList()
    }

    private fun choiceFrom(raw: kotlinx.serialization.json.JsonObject): GifChoice? {
        fun url(key: String): String {
            val image = raw["images"]?.jsonObject?.get(key)?.jsonObject ?: return ""
            val value = (image["url"] as? JsonPrimitive)?.content ?: ""
            return if (value.startsWith("http") && value.length <= MAX_URL) value else ""
        }
        val preview = url("fixed_height_small").ifEmpty { url("fixed_height") }.ifEmpty { url("original") }
        val full = url("original").ifEmpty { preview }
        if (preview.isEmpty() || full.isEmpty()) return null
        fun dim(key: String, fallback: Int): Int {
            val image = raw["images"]?.jsonObject?.get(key)?.jsonObject ?: return fallback
            return (image["width"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(1, 4_096) ?: fallback
        }
        val previewKey = if (url("fixed_height_small").isNotEmpty()) "fixed_height_small" else "fixed_height"
        return GifChoice(
            id = (raw["id"] as? JsonPrimitive)?.content.orEmpty().take(MAX_ID),
            url = full,
            preview = preview,
            width = dim(previewKey, 120),
            height = run {
                val image = raw["images"]?.jsonObject?.get(previewKey)?.jsonObject
                (image?.get("height") as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(1, 4_096) ?: 120
            },
        )
    }

    data class Pagination(val nextOffset: Int, val hasMore: Boolean)

    /** `offset + count` advances; `total_count` decides hasMore (fetched
     *  page size is the fallback when Giphy omits pagination). */
    fun pagination(responseJson: String, fetchedCount: Int, requestedOffset: Int): Pagination {
        val pagination = try {
            lenientJson.parseToJsonElement(responseJson).jsonObject["pagination"]?.jsonObject
        } catch (_: Exception) {
            null
        }
        val returnedOffset = (pagination?.get("offset") as? JsonPrimitive)?.content?.toIntOrNull() ?: requestedOffset
        val returnedCount = (pagination?.get("count") as? JsonPrimitive)?.content?.toIntOrNull() ?: fetchedCount
        val next = (returnedOffset + returnedCount).coerceAtLeast(0)
        val total = (pagination?.get("total_count") as? JsonPrimitive)?.content?.toIntOrNull()
        return Pagination(nextOffset = next, hasMore = if (total != null) next < total else fetchedCount >= PAGE_SIZE)
    }

    // ── Recent list ──────────────────────────────────────────────────

    /** Newest first, deduped by id, capped at [RECENT_LIMIT]. */
    fun mergeRecent(existing: List<GifChoice>, pick: GifChoice): List<GifChoice> =
        (listOf(pick) + existing.filter { it.id != pick.id || it.id.isEmpty() }).take(RECENT_LIMIT)

    // ── Cache wire (versioned, size-bounded) ─────────────────────────

    fun cacheEncode(recent: List<GifChoice>, trending: List<GifChoice>, savedAtMs: Long): String = buildJsonObject {
        put("v", 1)
        put("recent", itemsWire(recent.take(RECENT_LIMIT)))
        put("trending", buildJsonObject {
            put("savedAt", savedAtMs.coerceAtLeast(0))
            put("items", itemsWire(trending.take(MAX_CACHED_TRENDING)))
        })
    }.toString()

    /** Lenient decode: corrupt or oversized wire → null (an empty cache). */
    fun cacheDecode(json: String): CachedGifs? {
        if (json.length > MAX_WIRE_LENGTH) return null
        return try {
            val root = lenientJson.parseToJsonElement(json).jsonObject
            if ((root["v"] as? JsonPrimitive)?.content?.toIntOrNull() != 1) return null
            val recent = choicesFrom(root["recent"]?.jsonArray, RECENT_LIMIT)
            val trendingNode = root["trending"]?.jsonObject
            val trending = choicesFrom(trendingNode?.get("items")?.jsonArray, MAX_CACHED_TRENDING)
            val savedAt = (trendingNode?.get("savedAt") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            CachedGifs(recent = recent, trending = trending, savedAtMs = savedAt)
        } catch (_: Exception) {
            null
        }
    }

    fun isCacheFresh(savedAtMs: Long, nowMs: Long): Boolean =
        nowMs in savedAtMs..(savedAtMs + CACHE_TTL_MS)

    // ── Item JSON (bridge + cache share one shape) ───────────────────

    /** `[{"id":…,"url":…,"preview":…,"w":…,"h":…}, …]`. */
    fun choicesToJson(choices: List<GifChoice>): String = itemsWire(choices).toString()

    fun choicesFromJson(json: String): List<GifChoice> = try {
        choicesFrom(Json.parseToJsonElement(json).jsonArray, MAX_PARSED)
    } catch (_: Exception) {
        emptyList()
    }

    private fun itemsWire(choices: List<GifChoice>) = buildJsonArray {
        choices.forEach { gif ->
            add(buildJsonObject {
                put("id", gif.id.take(MAX_ID))
                put("url", gif.url.take(MAX_URL))
                put("preview", gif.preview.take(MAX_URL))
                put("w", gif.width)
                put("h", gif.height)
            })
        }
    }

    private fun choicesFrom(array: kotlinx.serialization.json.JsonArray?, cap: Int): List<GifChoice> =
        array?.mapNotNull { element -> (element as? kotlinx.serialization.json.JsonObject)?.let(::choiceFromWire) }?.take(cap) ?: emptyList()

    private fun choiceFromWire(obj: kotlinx.serialization.json.JsonObject): GifChoice? {
        val url = (obj["url"] as? JsonPrimitive)?.content ?: return null
        val preview = (obj["preview"] as? JsonPrimitive)?.content ?: return null
        if (!url.startsWith("http") || !preview.startsWith("http")) return null
        return GifChoice(
            id = (obj["id"] as? JsonPrimitive)?.content.orEmpty().take(MAX_ID),
            url = url.take(MAX_URL),
            preview = preview.take(MAX_URL),
            width = (obj["w"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(1, 4_096) ?: 120,
            height = (obj["h"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(1, 4_096) ?: 120,
        )
    }
}
