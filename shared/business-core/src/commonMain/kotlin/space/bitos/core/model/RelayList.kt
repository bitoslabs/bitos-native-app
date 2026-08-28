package space.bitos.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One managed relay with its NIP-65 roles. An entry must carry at least one
 * role — a relay that is neither read nor write is not part of a relay list.
 */
data class RelayEntry(val url: RelayUrl, val read: Boolean, val write: Boolean)

/**
 * Versioned relay-list contract (APP-018 `relays` section, NIP-65 parity):
 * the persisted managed set and the kind-10002 wire rules live here once and
 * both platforms execute them through adapters.
 *
 *  • bounded managed set (≤ [MAX_RELAYS], url-unique, ≥1 role per entry)
 *  • versioned JSON wire format for device persistence
 *    (`{"v":1,"relays":[{"u":"wss://…","r":true,"w":false}, …]}`)
 *  • NIP-65 `r`-tag projection (marker omitted = read+write)
 *
 * Corrupt or oversized persisted values decode to an empty list — the
 * adapter then falls back to the platform default set; a broken store must
 * never crash the relays manager.
 */
object RelayListContract {
    /** NIP-65 relay-list event kind. */
    const val KIND = 10_002

    /** Persisted wire schema version. */
    const val SCHEMA_VERSION = 1

    /** Managed-set bound (legacy web manager parity; size-bounded stores). */
    const val MAX_RELAYS = 16

    /** Hard bound on the persisted wire string (16 × ~512-char url + overhead). */
    const val MAX_WIRE_LENGTH = 16_384

    private val lenientJson = Json { ignoreUnknownKeys = true }

    /** Parses, dedupes by url, drops role-less entries and caps the set. */
    fun normalize(entries: List<RelayEntry>): List<RelayEntry> {
        val seen = HashSet<RelayUrl>(entries.size)
        return entries.filter { it.read || it.write }
            .filter { entry -> seen.add(entry.url) }
            .take(MAX_RELAYS)
    }

    /** Encodes the normalized set as the versioned wire JSON. */
    fun encode(entries: List<RelayEntry>): String {
        val array = buildJsonArray {
            normalize(entries).forEach { entry ->
                add(
                    buildJsonObject {
                        put("u", entry.url.value)
                        put("r", entry.read)
                        put("w", entry.write)
                    },
                )
            }
        }
        return buildJsonObject {
            put("v", SCHEMA_VERSION)
            put("relays", array)
        }.toString()
    }

    /** Lenient decode: any corruption or oversize yields an empty list. */
    fun decode(json: String): List<RelayEntry> {
        if (json.length > MAX_WIRE_LENGTH) return emptyList()
        return try {
            val root = lenientJson.parseToJsonElement(json).jsonObject
            val relays = root["relays"]?.jsonArray ?: return emptyList()
            relays.mapNotNull { element ->
                val obj = element.jsonObject
                val url = RelayUrl.parse(obj["u"]?.jsonPrimitive?.content ?: return@mapNotNull null)
                    ?: return@mapNotNull null
                val read = obj["r"]?.jsonPrimitive?.booleanOrNull ?: false
                val write = obj["w"]?.jsonPrimitive?.booleanOrNull ?: false
                RelayEntry(url, read, write)
            }.let(::normalize)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * NIP-65 `r` tags for the set: the marker is omitted for read+write,
     * `"read"`/`"write"` otherwise. Order follows the managed set.
     */
    fun nip65Tags(entries: List<RelayEntry>): List<List<String>> =
        normalize(entries).map { entry ->
            when {
                entry.read && entry.write -> listOf("r", entry.url.value)
                entry.write -> listOf("r", entry.url.value, "write")
                else -> listOf("r", entry.url.value, "read")
            }
        }
}
