package space.bitos.core.studio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Local shared-sound library index (plan MST-047 W4 / §3.5): ONE
 * versioned, size-bounded JSON the natives persist next to the audio
 * bytes (Android `filesDir/studio/sounds/`, iOS Application Support —
 * same layout contract both platforms). ≤ 30 entries, LRU eviction by
 * `savedAtMs`, dedup by id; the audio bytes themselves stay native —
 * this object owns only the index model + bounds (pure, common-tested).
 *
 * Every read is hostile-tolerant: junk rows drop, junk fields clamp,
 * over-cap lists truncate — a corrupt index degrades to fewer sounds,
 * never a failed app.
 */
object SharedSoundLibrary {

    const val WIRE_VERSION = 1

    /** One saved sound: enough to re-attach offline (sha-verified local
     *  bytes) and to credit the source at publish. [url] may be blank
     *  for a never-uploaded local pick. */
    data class Entry(
        val id: String,
        val label: String,
        val url: String,
        /** 64-hex canonical hash of the saved bytes. */
        val sha256: String,
        val license: String,
        val durationMs: Long,
        /** LRU clock (wall ms at save time). */
        val savedAtMs: Long,
        val sourceEventId: String = "",
        val authorPubkey: String = "",
    )

    data class Library(val entries: List<Entry>) {
        val size: Int get() = entries.size
        operator fun get(id: String): Entry? = entries.firstOrNull { it.id == id }
    }

    val EMPTY = Library(emptyList())

    /** Tolerant decode: junk rows drop, fields clamp, list caps at 30. */
    fun decode(json: String): Library {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return EMPTY
        if ((root["v"] as? JsonPrimitive)?.content?.toIntOrNull() != WIRE_VERSION) return EMPTY
        val rows = (root["sounds"] as? JsonArray) ?: return EMPTY
        val entries = rows.mapNotNull { row -> entryOf(row as? JsonObject ?: return@mapNotNull null) }
            .distinctBy { it.id }
            .take(SharedSoundContract.MAX_LOCAL_SOUNDS)
        return Library(entries)
    }

    /** Canonical encode (the natives write exactly this). */
    fun encode(library: Library): String = buildJsonObject {
        put("v", WIRE_VERSION)
        put("sounds", buildJsonArray {
            library.entries.take(SharedSoundContract.MAX_LOCAL_SOUNDS).forEach { entry ->
                add(buildJsonObject {
                    put("id", entry.id)
                    put("label", entry.label)
                    put("url", entry.url)
                    put("sha256", entry.sha256)
                    put("license", entry.license)
                    put("durationMs", entry.durationMs)
                    put("savedAtMs", entry.savedAtMs)
                    put("src", entry.sourceEventId)
                    put("author", entry.authorPubkey)
                })
            }
        })
    }.toString()

    /**
     * Add/replace by id, then LRU-evict down to the cap: when over
     * [SharedSoundContract.MAX_LOCAL_SOUNDS], the OLDEST `savedAtMs`
     * entries drop (the new entry never evicts itself).
     */
    fun add(library: Library, entry: Entry): Library {
        val bounded = normalizeEntry(entry) ?: return library
        val kept = library.entries.filterNot { it.id == bounded.id } + bounded
        val evicted = kept.sortedByDescending { it.savedAtMs }
            .take(SharedSoundContract.MAX_LOCAL_SOUNDS)
        return Library(evicted)
    }

    fun remove(library: Library, id: String): Library =
        Library(library.entries.filterNot { it.id == id })

    /** Bounded/clamped entry; null when unusable (no id / bad sha / no
     *  positive duration within the 15 s cap — junk never reaches disk). */
    private fun normalizeEntry(raw: Entry): Entry? {
        val id = raw.id.trim().take(SharedSoundContract.MAX_ID_LENGTH).takeIf(String::isNotBlank) ?: return null
        val sha = raw.sha256.lowercase()
        if (!Regex("^[0-9a-f]{64}$").matches(sha)) return null
        if (raw.durationMs !in 1..SharedSoundContract.MAX_DURATION_MS) return null
        return Entry(
            id = id,
            label = raw.label.take(SharedSoundContract.MAX_LABEL).ifBlank { id },
            url = raw.url.trim().take(SharedSoundContract.MAX_URL_LENGTH),
            sha256 = sha,
            license = raw.license.take(SharedSoundContract.MAX_LICENSE_LENGTH),
            durationMs = raw.durationMs,
            savedAtMs = raw.savedAtMs.coerceAtLeast(0),
            sourceEventId = raw.sourceEventId.take(MemeSoundRules.MAX_SOURCE_ID_LENGTH),
            authorPubkey = raw.authorPubkey.take(MemeSoundRules.MAX_AUTHOR_LENGTH),
        )
    }

    private fun entryOf(row: JsonObject?): Entry? {
        row ?: return null
        fun str(key: String) = (row[key] as? JsonPrimitive)?.content ?: ""
        return normalizeEntry(
            Entry(
                id = str("id"),
                label = str("label"),
                url = str("url"),
                sha256 = str("sha256"),
                license = str("license"),
                durationMs = str("durationMs").toLongOrNull() ?: return null,
                savedAtMs = str("savedAtMs").toLongOrNull() ?: 0L,
                sourceEventId = str("src"),
                authorPubkey = str("author"),
            ),
        )
    }
}
