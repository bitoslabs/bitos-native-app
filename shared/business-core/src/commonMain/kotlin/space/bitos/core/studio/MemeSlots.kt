package space.bitos.core.studio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.max

/**
 * Continue-creating slots (plan MST-018, EDT-001/002; web
 * `meme-slots.svelte.ts` parity where it crosses the wire): a WIP meme
 * persists as ONE file per slot — the project wire plus the local asset
 * refs the platform copied into its studio dir (CAP-005: assets are
 * copied in, never referenced through transient picker URIs). This file
 * owns the SLOT WIRE (what's on disk) and the INDEX RULES (ordering,
 * LRU eviction, bounds); file IO stays native (Android `filesDir/studio/`,
 * iOS Application Support/studio/).
 *
 * Rules (plan §4.2 + web constants §6): ≤ [MAX_SLOTS] slots, no TTL,
 * explicit delete only; index is most-recent-first; saving an existing id
 * bumps it to the front; overflow evicts the least-recent slot (the
 * caller receives the evicted ids and deletes their files).
 */
object MemeSlots {

    const val MAX_SLOTS = 6

    /** Slot file bound: project wire (64 KB) + asset manifest headroom. */
    const val MAX_SLOT_FILE_LENGTH = 96 * 1024

    /** Poster thumbnails: native-side cap (plan: ≤192 KB JPEG). */
    const val MAX_POSTER_BYTES = 192 * 1024

    /** Autosave debounce after each committed command (web draft parity). */
    const val AUTOSAVE_DEBOUNCE_MS = 500L
}

/** One persisted local asset ref inside a slot (a stable studio-dir file). */
data class MemeSlotAsset(
    /** Project-wire asset id (`a1…a9`) — the overlay/asset join key. */
    val id: String,
    /** File name inside the slot dir (never an absolute path on the wire). */
    val fileName: String,
    /** width/height, preserved so the stage box restores without a decode. */
    val aspect: Float,
)

/** The slot document: what a `slot-<id>.json` file contains. */
data class MemeSlotDocument(
    val slotId: String,
    val project: MemeProject,
    val assets: List<MemeSlotAsset>,
    val updatedAtMs: Long,
)

/** One row in the hub's "Continue creating" list (the in-memory index). */
data class MemeSlotEntry(
    val slotId: String,
    val updatedAtMs: Long,
    /** Poster file name inside the slot dir (null = placeholder art). */
    val posterName: String?,
    /** Human label (see [MemeSlots.labelFor]). */
    val label: String,
)

object MemeSlotCodec {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    /**
     * Encodes a slot file: `{"v":1,"id":…,"assets":[{id,file,aspect}],
     * "updatedAt":ms,"project":{…MemeProject wire…}}`.
     */
    fun encode(document: MemeSlotDocument): String = buildJsonObject {
        put("v", 1)
        put("id", document.slotId)
        put("updatedAt", document.updatedAtMs)
        put("assets", buildJsonArray {
            document.assets.forEach { asset ->
                add(buildJsonObject {
                    put("id", asset.id.take(64))
                    put("file", asset.fileName.take(128))
                    put("aspect", asset.aspect.coerceIn(0.05f, 20f))
                })
            }
        })
        put("project", Json.parseToJsonElement(MemeProjectContract.encode(document.project)))
    }.toString()

    /** Lenient decode: corrupt/oversized/foreign files → null, never throw. */
    fun decode(json: String): MemeSlotDocument? {
        if (json.length > MemeSlots.MAX_SLOT_FILE_LENGTH) return null
        return try {
            val root = lenientJson.parseToJsonElement(json).jsonObject
            if ((root["v"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() != 1) {
                return null
            }
            val slotId = (root["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() } ?: return null
            val updatedAt = (root["updatedAt"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?.toLongOrNull() ?: 0L
            val projectWire = (root["project"] as? JsonObject)?.toString()
                ?: return null
            val project = MemeProjectContract.decode(projectWire) ?: return null
            val assets = (root["assets"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val file = (obj["file"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?.takeIf { it.isNotBlank() && !it.startsWith("/") && !it.contains("..") }
                        ?: return@mapNotNull null
                    MemeSlotAsset(
                        id = id.take(64),
                        fileName = file.take(128),
                        aspect = ((obj["aspect"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?.toFloatOrNull() ?: 1f).coerceIn(0.05f, 20f),
                    )
                }
                ?.take(MemeProjectContract.maxAssets(project.mode))
                ?: emptyList()
            MemeSlotDocument(slotId, project, assets, updatedAt)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The hub index file: `{"v":1,"slots":[{"id","updatedAt","poster","label"}]}`
     * most-recent-first. Corrupt → empty (the hub never blocks creating).
     */
    fun encodeIndex(entries: List<MemeSlotEntry>): String = buildJsonObject {
        put("v", 1)
        put("slots", buildJsonArray {
            entries.take(MemeSlots.MAX_SLOTS).forEach { entry ->
                add(buildJsonObject {
                    put("id", entry.slotId.take(64))
                    put("updatedAt", entry.updatedAtMs)
                    entry.posterName?.let { put("poster", it.take(128)) }
                    put("label", entry.label.take(80))
                })
            }
        })
    }.toString()

    fun decodeIndex(json: String): List<MemeSlotEntry> {
        if (json.length > 16 * 1024) return emptyList()
        return try {
            val root = lenientJson.parseToJsonElement(json).jsonObject
            (root["slots"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MemeSlotEntry(
                        slotId = id.take(64),
                        updatedAtMs = (obj["updatedAt"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content?.toLongOrNull() ?: 0L,
                        posterName = (obj["poster"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?.takeIf { it.isNotBlank() && !it.startsWith("/") && !it.contains("..") },
                        label = (obj["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?.takeIf { it.isNotBlank() } ?: "Untitled meme",
                    )
                }
                ?.take(MemeSlots.MAX_SLOTS)
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Pure index rules for the hub list: front-insert on save, id-bump on
 * re-save, LRU eviction past the cap. Returns the new index + the ids
 * whose slot files the caller must delete.
 */
object MemeSlotRules {

    data class Upsert(
        val entries: List<MemeSlotEntry>,
        /** Slot ids evicted by this upsert (LRU overflow). */
        val evicted: List<String>,
    )

    fun upsert(
        entries: List<MemeSlotEntry>,
        slotId: String,
        updatedAtMs: Long,
        posterName: String?,
        label: String,
    ): Upsert {
        val kept = entries.filter { it.slotId != slotId }
        val updated = listOf(
            MemeSlotEntry(
                slotId = slotId.take(64),
                updatedAtMs = updatedAtMs,
                posterName = posterName?.takeIf { it.isNotBlank() },
                label = label.take(80).ifBlank { "Untitled meme" },
            ),
        ) + kept
        val bounded = updated.take(MemeSlots.MAX_SLOTS)
        return Upsert(
            entries = bounded,
            evicted = updated.drop(MemeSlots.MAX_SLOTS).map { it.slotId },
        )
    }

    /** Hub label: first overlay text (caps-applied look, ≤40), else mode. */
    fun labelFor(project: MemeProject): String {
        val first = project.overlays.firstOrNull { it.text.isNotBlank() }?.text?.trim()
        if (first != null) {
            val collapsed = first.replace(Regex("\\s+"), " ")
            return if (collapsed.length <= 40) collapsed else collapsed.take(39) + "…"
        }
        return when (project.mode) {
            MemeMode.IMAGE -> "Image meme"
            MemeMode.GIF -> "GIF meme"
            MemeMode.VIDEO -> "Video meme"
        }
    }

    /** Relative "2 h ago / yesterday / 3 d ago" for the hub rows. */
    fun relativeTime(updatedAtMs: Long, nowMs: Long): String {
        val delta = max(0L, nowMs - updatedAtMs)
        val minutes = delta / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            minutes < 60 * 24 * 2 -> "yesterday"
            else -> "${minutes / (60 * 24)} d ago"
        }
    }
}
