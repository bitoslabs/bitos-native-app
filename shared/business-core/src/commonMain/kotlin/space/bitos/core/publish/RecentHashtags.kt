package space.bitos.core.publish

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Recently used hashtags (composer + meme post details): the "Recent" chip
 * row that makes reusing your own tags one tap. Pure deterministic rule —
 * both platforms persist the versioned JSON natively (bounded, junk-tolerant
 * decode) and call [merge] at publish time. Tags normalize exactly like the
 * NIP-22 hashtag shape the feed tokenizer accepts, so anything a caption can
 * highlight is recordable.
 */
object RecentHashtags {

    /** Bounded ledger — newest first, deduped. */
    const val MAX_ENTRIES = 64

    /** Hashtag shape (FeedNote hashtagPattern parity: 2..60 word chars). */
    private val TAG_SHAPE = Regex("^[\\p{L}\\p{N}_-]{2,60}$")

    data class Entry(val tag: String, val usedAtMs: Long)

    /** Lowercase, # stripped; null when the raw text isn't a recordable tag. */
    fun normalize(raw: String): String? {
        val tag = raw.trim().trimStart('#').lowercase()
        return tag.takeIf { it.isNotEmpty() && TAG_SHAPE.matches(it) }
    }

    /**
     * Fold newly used tags into the ledger: every use bumps its entry to the
     * front with the fresh timestamp, unknown shapes drop silently, the list
     * stays newest-first and capped.
     */
    fun merge(entries: List<Entry>, newlyUsed: List<String>, nowMs: Long): List<Entry> {
        val byTag = LinkedHashMap<String, Entry>()
        // Re-insert seed entries newest-first so an existing tag keeps its
        // better timestamp when the caller passes unsorted history.
        for (entry in entries.take(MAX_ENTRIES)) {
            val tag = normalize(entry.tag) ?: continue
            val prev = byTag[tag]
            if (prev == null || entry.usedAtMs > prev.usedAtMs) byTag[tag] = Entry(tag, entry.usedAtMs)
        }
        for (raw in newlyUsed) {
            val tag = normalize(raw) ?: continue
            byTag.remove(tag)
            byTag[tag] = Entry(tag, nowMs)
        }
        return byTag.values
            .sortedByDescending { it.usedAtMs }
            .take(MAX_ENTRIES)
    }

    /** Chip row input: recent tags, minus what the post already carries. */
    fun suggestions(entries: List<Entry>, exclude: Set<String>, limit: Int = 8): List<String> {
        val blocked = exclude.mapNotNull { normalize(it) }.toSet()
        return entries.map { it.tag }.filter { it !in blocked }.take(limit.coerceIn(0, MAX_ENTRIES))
    }

    /** Hashtags typed anywhere in a caption/content (`#word` scan). */
    fun hashtagsIn(content: String): List<String> {
        val found = LinkedHashSet<String>()
        for (word in content.split(Regex("\\s+"))) {
            if (!word.startsWith("#")) continue
            normalize(word)?.let { found.add(it) }
        }
        return found.toList()
    }

    // ── Versioned wire JSON: {"v":1,"entries":[{"t":"meme","u":1694…}]} ──

    fun toJson(entries: List<Entry>): String = kotlinx.serialization.json.buildJsonObject {
        put("v", JsonPrimitive(1))
        put("entries", JsonArray(entries.take(MAX_ENTRIES).map { entry ->
            kotlinx.serialization.json.buildJsonObject {
                put("t", JsonPrimitive(entry.tag))
                put("u", JsonPrimitive(entry.usedAtMs))
            }
        }))
    }.toString()

    /** Lenient decode: wrong version/junk shapes drop, ledger stays capped. */
    fun fromJson(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        if ((root["v"] as? JsonPrimitive)?.content?.toIntOrNull() != 1) return emptyList()
        val array = try {
            root["entries"]?.jsonArray
        } catch (_: Exception) {
            return emptyList()
        } ?: return emptyList()
        val out = mutableListOf<Entry>()
        for (element in array) {
            if (out.size >= MAX_ENTRIES) break
            val obj = try {
                element.jsonObject
            } catch (_: Exception) {
                continue
            }
            val tag = normalize((obj["t"] as? JsonPrimitive)?.content ?: continue) ?: continue
            val usedAt = (obj["u"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
            out += Entry(tag, usedAt)
        }
        // Defensively re-sort + dedupe stored history.
        return merge(out, emptyList(), nowMs = 0L)
    }
}
