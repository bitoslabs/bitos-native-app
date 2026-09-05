package space.bitos.core.studio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Studio mass production (docs/product/studio-mass-production.md; mockup
 * `docs/ui/app-15-studio-mass-production.html`, plan M4 wave 5 / MST-048):
 * one immutable RECIPE (a master meme project + typed input slots + naming
 * and publish defaults) is bound to typed ROWS; each valid row resolves to
 * a deterministic VARIANT that must be explicitly APPROVED (content +
 * poster hashes) before the per-event publish queue may sign it.
 *
 * This file owns the BATCH WIRE (what is persisted on disk) — versioned,
 * size-bounded, lenient-but-bounded decode (corrupt/oversized → null, a
 * broken store never blocks creating). All decisions live in
 * [MassBatchRules]; file IO stays native (Android `filesDir/studio/mass/`,
 * iOS Application Support/studio/mass/).
 *
 * Recipe immutability (product doc §3): a recipe is frozen once a batch
 * has rows; edits FORK to version + 1 and invalidate renders/approvals —
 * reruns stay reproducible, never mutate history.
 */

/** Typed input slots (product doc §4). V1 renders: text-like substitution
 *  into overlay/caption placeholders, image_asset swaps the target asset,
 *  color validates hex (review swatch + placeholder substitution). */
enum class MassSlotType { SHORT_TEXT, LONG_TEXT, NUMBER, COLOR, IMAGE_ASSET, VIDEO_ASSET, AUDIO_ASSET, TIMESTAMP, ENUM }

/** One declared recipe input. */
data class MassSlot(
    val id: String,
    val name: String,
    val type: MassSlotType,
    val required: Boolean = false,
    /** Text cap for SHORT_TEXT (LONG_TEXT uses [MassBatch.MAX_LONG_TEXT]). */
    val maxLen: Int = 40,
    /** NUMBER bounds. */
    val min: Double = 0.0,
    val max: Double = 1_000_000.0,
    /** ENUM choices (≤ [MassBatch.MAX_ENUM_VALUES] × ≤24 chars). */
    val enumValues: List<String> = emptyList(),
    /** IMAGE_ASSET only: project asset id this row image replaces. */
    val assetTargetId: String? = null,
)

/** The frozen master: base project + slots + output/publish defaults. */
data class MassRecipe(
    val version: Int,
    val slots: List<MassSlot>,
    /** Master meme project (the local store wire, embedded as JSON). */
    val project: MemeProject,
    /** Output naming pattern, `{i}` = 1-based row order, `{slot}` allowed. */
    val naming: String,
    val caption: String = "",
    val altText: String = "",
    val contentWarningReason: String? = null,
)

/** One typed input row. Values are raw strings coerced by [MassBatchRules]. */
data class MassRow(
    val id: String,
    val values: Map<String, String> = emptyMap(),
    /** IMAGE_ASSET slot → stored asset file name (inside the batch dir). */
    val assetFiles: Map<String, String> = emptyMap(),
    /** Optional per-variant project overrides (title text swap). */
    val overrideText: Map<String, String> = emptyMap(),
)

/** Per-variant durable state (render / approval / publish). */
data class MassVariantState(
    val rowId: String,
    val render: MassRenderState = MassRenderState.PENDING,
    /** Poster file name inside the batch dir (null until rendered). */
    val posterName: String? = null,
    /** sha256 of the rendered poster — binds approvals to real content. */
    val posterHash: String? = null,
    /** Approved variant hash (content+poster) — null = not approved. */
    val approvedHash: String? = null,
    val approvedAtMs: Long = 0,
    val publish: MassPublishState = MassPublishState.WAITING,
    /** Settled event id once published (nevent/bech32 or hex). */
    val publishedEventId: String? = null,
    val failure: String? = null,
)

enum class MassRenderState { PENDING, RENDERED }
enum class MassPublishState { WAITING, PUBLISHING, PUBLISHED, FAILED }

data class MassBatchDocument(
    val batchId: String,
    val name: String,
    val recipe: MassRecipe,
    val rows: List<MassRow>,
    val states: Map<String, MassVariantState> = emptyMap(),
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0,
)

object MassBatch {

    const val WIRE_VERSION = 1

    /** ≤200 rows × values + embedded project wire; generous but bounded. */
    const val MAX_WIRE_LENGTH = 256 * 1024

    const val MAX_ROWS = 200
    const val MAX_SLOTS = 12
    const val MAX_ENUM_VALUES = 12
    const val MAX_SHORT_TEXT = 60
    const val MAX_LONG_TEXT = 300
    const val MAX_VALUE_LENGTH = 300
    const val MAX_NAME_LENGTH = 80
    const val MAX_NAMING_LENGTH = 64
    const val MAX_CAPTION = 1_000
    const val MAX_CW = 120
    const val MAX_ASSET_FILE_LENGTH = 128
    const val MAX_BATCHES = 6
    /** UX-14: operational cap for NEW imports (schema decode keeps
     *  [MAX_ROWS] = 200 so existing large batches stay readable). */
    const val MAX_NEW_ROWS = 100
    const val MAX_CSV_BYTES = 512 * 1024

    /** Canonical starter project every new batch begins from: master asset
     *  `a1` + the classic placeholder pair the default slots substitute. */
    fun starterProject(): MemeProject = MemeProject(
        mode = MemeMode.IMAGE,
        assets = listOf(MemeAsset("a1", MemeMode.IMAGE)),
        overlays = listOf(
            MemeOverlay(
                id = "o1", kind = MemeOverlayKind.TEXT, text = "zap {name}",
                font = MemeFontSlot.IMPACT, size = 64, colorIndex = 0, outline = 3,
                shadow = false, x = 0.5f, y = 0.14f, scale = 1f, rotationDeg = 0f,
            ),
            MemeOverlay(
                id = "o2", kind = MemeOverlayKind.TEXT, text = "{sats} sats",
                font = MemeFontSlot.IMPACT, size = 64, colorIndex = 0, outline = 3,
                shadow = false, x = 0.5f, y = 0.86f, scale = 1f, rotationDeg = 0f,
            ),
        ),
    )

    /**
     * MUX-06 "Make variations": derive a recipe from an ARBITRARY editor
     * design. One LONG_TEXT slot per non-blank TEXT overlay (id `t:<overlay
     * id>`, friendly name = the current text); a blank row value renders
     * that caption empty in the variant (deliberate drop, not an error).
     * IMAGE-mode designs only — GIF/video stay renderer-gated. Returns
     * null when the project is not an eligible image design.
     */
    fun designRecipe(project: MemeProject): MassRecipe? {
        if (project.mode != MemeMode.IMAGE || project.assets.isEmpty()) return null
        val texts = project.overlays
            .filter { it.kind == MemeOverlayKind.TEXT && it.text.isNotBlank() }
        if (texts.isEmpty()) return null
        val slots = texts.map { overlay ->
            MassSlot(
                id = "t:${overlay.id}",
                name = overlay.text.take(14),
                type = MassSlotType.LONG_TEXT,
                required = false,
            )
        }
        // The frozen design carries `{t:<id>}` placeholders where captions
        // lived — the existing substitution engine then replaces each with
        // the row value (blank row value → caption dropped, not an error).
        val placeholderProject = project.copy(
            overlays = project.overlays.map { overlay ->
                if (overlay.kind == MemeOverlayKind.TEXT && overlay.text.isNotBlank()) {
                    overlay.copy(text = "{t:${overlay.id}}")
                } else {
                    overlay
                }
            },
        )
        return MassRecipe(
            version = 1,
            slots = slots,
            project = placeholderProject,
            naming = "variant-{i}",
            caption = "",
        )
    }

    /** The canonical starter recipe (mockup scr-batch: name/sats/bg/img). */
    fun defaultRecipe(project: MemeProject): MassRecipe = MassRecipe(
        version = 1,
        slots = listOf(
            MassSlot(id = "name", name = "name", type = MassSlotType.SHORT_TEXT, required = true, maxLen = 24),
            MassSlot(id = "sats", name = "sats", type = MassSlotType.NUMBER, required = true),
            MassSlot(id = "bg", name = "bg", type = MassSlotType.COLOR),
            MassSlot(
                id = "img",
                name = "img",
                type = MassSlotType.IMAGE_ASSET,
                assetTargetId = project.assets.firstOrNull()?.id,
            ),
        ),
        project = project,
        naming = "memes_{i}",
    )
}

object MassBatchCodec {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    fun encode(document: MassBatchDocument): String = buildJsonObject {
        put("v", MassBatch.WIRE_VERSION)
        put("id", document.batchId.take(64))
        put("name", document.name.take(MassBatch.MAX_NAME_LENGTH))
        put("createdAt", document.createdAtMs)
        put("updatedAt", document.updatedAtMs)
        put("recipe", buildJsonObject {
            put("version", document.recipe.version.coerceIn(1, 999))
            put("slots", buildJsonArray {
                document.recipe.slots.take(MassBatch.MAX_SLOTS).forEach { slot ->
                    add(buildJsonObject {
                        put("id", slot.id.take(32))
                        put("name", slot.name.take(40))
                        put("type", slot.type.name.lowercase())
                        put("required", slot.required)
                        put("maxLen", slot.maxLen.coerceIn(1, MassBatch.MAX_SHORT_TEXT))
                        put("min", slot.min)
                        put("max", slot.max.coerceAtLeast(slot.min))
                        if (slot.enumValues.isNotEmpty()) {
                            put("enum", buildJsonArray {
                                slot.enumValues.take(MassBatch.MAX_ENUM_VALUES).forEach { add(it.take(24)) }
                            })
                        }
                        slot.assetTargetId?.let { put("asset", it.take(64)) }
                    })
                }
            })
            put("project", Json.parseToJsonElement(MemeProjectContract.encode(document.recipe.project)))
            put("naming", document.recipe.naming.take(MassBatch.MAX_NAMING_LENGTH))
            put("caption", document.recipe.caption.take(MassBatch.MAX_CAPTION))
            put("alt", document.recipe.altText.take(MassBatch.MAX_CAPTION))
            put("cw", document.recipe.contentWarningReason?.take(MassBatch.MAX_CW) ?: "")
        })
        put("rows", buildJsonArray {
            document.rows.take(MassBatch.MAX_ROWS).forEach { row ->
                add(buildJsonObject {
                    put("id", row.id.take(64))
                    put("values", buildJsonObject {
                        row.values.forEach { (key, value) -> put(key.take(32), value.take(MassBatch.MAX_VALUE_LENGTH)) }
                    })
                    if (row.assetFiles.isNotEmpty()) {
                        put("assets", buildJsonObject {
                            row.assetFiles.forEach { (key, file) ->
                                put(key.take(32), file.take(MassBatch.MAX_ASSET_FILE_LENGTH))
                            }
                        })
                    }
                    if (row.overrideText.isNotEmpty()) {
                        put("overrides", buildJsonObject {
                            row.overrideText.forEach { (key, text) ->
                                put(key.take(64), text.take(MassBatch.MAX_LONG_TEXT))
                            }
                        })
                    }
                })
            }
        })
        put("states", buildJsonObject {
            document.states.forEach { (rowId, state) ->
                put(rowId.take(64), buildJsonObject {
                    put("render", state.render.name.lowercase())
                    state.posterName?.let { put("poster", it.take(MassBatch.MAX_ASSET_FILE_LENGTH)) }
                    state.posterHash?.let { put("posterHash", it.take(64)) }
                    state.approvedHash?.let { put("approvedHash", it.take(64)) }
                    if (state.approvedAtMs > 0) put("approvedAt", state.approvedAtMs)
                    put("publish", state.publish.name.lowercase())
                    state.publishedEventId?.let { put("event", it.take(128)) }
                    state.failure?.let { put("failure", it.take(200)) }
                })
            }
        })
    }.toString()

    /** Lenient decode: corrupt/oversized/foreign → null, never throw. */
    fun decode(json: String): MassBatchDocument? {
        if (json.length > MassBatch.MAX_WIRE_LENGTH) return null
        return try {
            val root = lenientJson.parseToJsonElement(json).jsonObject
            if (int(root, "v") != MassBatch.WIRE_VERSION) return null
            val batchId = str(root, "id")?.takeIf { it.isNotBlank() } ?: return null
            val recipeObj = root["recipe"]?.jsonObject ?: return null
            val projectWire = (recipeObj["project"] as? JsonObject)?.toString() ?: return null
            val project = MemeProjectContract.decode(projectWire) ?: return null
            val slots = (recipeObj["slots"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { element -> decodeSlot(element.jsonObject) }
                ?.take(MassBatch.MAX_SLOTS)
                ?: emptyList()
            val recipe = MassRecipe(
                version = (int(recipeObj, "version") ?: 1).coerceIn(1, 999),
                slots = slots,
                project = project,
                naming = (str(recipeObj, "naming") ?: "memes_{i}").take(MassBatch.MAX_NAMING_LENGTH),
                caption = (str(recipeObj, "caption") ?: "").take(MassBatch.MAX_CAPTION),
                altText = (str(recipeObj, "alt") ?: "").take(MassBatch.MAX_CAPTION),
                contentWarningReason = str(recipeObj, "cw")?.take(MassBatch.MAX_CW)?.takeIf { it.isNotBlank() },
            )
            val rows = (root["rows"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { element -> decodeRow(element.jsonObject) }
                ?.take(MassBatch.MAX_ROWS)
                ?: emptyList()
            val states = (root["states"] as? JsonObject)?.entries
                ?.mapNotNull { (rowId, element) ->
                    val obj = element.jsonObject
                    rowId.takeIf { it.isNotBlank() }?.let { id ->
                        id to MassVariantState(
                            rowId = id,
                            render = enumEntry<MassRenderState>(str(obj, "render")) ?: MassRenderState.PENDING,
                            posterName = str(obj, "poster")?.takeIf { it.isNotBlank() && !it.startsWith("/") && !it.contains("..") },
                            posterHash = str(obj, "posterHash")?.take(64),
                            approvedHash = str(obj, "approvedHash")?.take(64),
                            approvedAtMs = long(obj, "approvedAt") ?: 0,
                            publish = enumEntry<MassPublishState>(str(obj, "publish")) ?: MassPublishState.WAITING,
                            publishedEventId = str(obj, "event")?.take(128),
                            failure = str(obj, "failure")?.take(200),
                        )
                    }
                }
                ?.toMap()
                ?: emptyMap()
            MassBatchDocument(
                batchId = batchId,
                name = (str(root, "name") ?: "Untitled batch").take(MassBatch.MAX_NAME_LENGTH),
                recipe = recipe,
                rows = rows,
                states = states,
                createdAtMs = long(root, "createdAt") ?: 0,
                updatedAtMs = long(root, "updatedAt") ?: 0,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSlot(obj: JsonObject): MassSlot? {
        val id = str(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
        val type = enumEntry<MassSlotType>(str(obj, "type")) ?: return null
        return MassSlot(
            id = id.take(32),
            name = (str(obj, "name") ?: id).take(40),
            type = type,
            required = bool(obj, "required"),
            maxLen = (int(obj, "maxLen") ?: 40).coerceIn(1, MassBatch.MAX_SHORT_TEXT),
            min = dbl(obj, "min") ?: 0.0,
            max = (dbl(obj, "max") ?: 1_000_000.0).coerceAtLeast(dbl(obj, "min") ?: 0.0),
            enumValues = (obj["enum"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(24)?.takeIf(String::isNotBlank) }
                ?.take(MassBatch.MAX_ENUM_VALUES)
                ?: emptyList(),
            assetTargetId = str(obj, "asset")?.take(64)?.takeIf { it.isNotBlank() },
        )
    }

    private fun decodeRow(obj: JsonObject): MassRow? {
        val id = str(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
        return MassRow(
            id = id.take(64),
            values = stringMap(obj["values"]),
            assetFiles = stringMap(obj["assets"])
                .filterValues { it.isNotBlank() && !it.startsWith("/") && !it.contains("..") },
            overrideText = stringMap(obj["overrides"]),
        )
    }

    private fun stringMap(element: kotlinx.serialization.json.JsonElement?): Map<String, String> =
        (element as? JsonObject)?.entries
            ?.associate { (key, value) ->
                key.take(64) to ((value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "").take(MassBatch.MAX_VALUE_LENGTH)
            }
            ?.filterKeys { it.isNotBlank() }
            ?: emptyMap()

    private inline fun <reified T : Enum<T>> enumEntry(raw: String?): T? =
        raw?.let { value -> enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } }

    // ── Hub index (most-recent-first, ≤ [MassBatch.MAX_BATCHES]) ────────

    fun encodeIndex(entries: List<IndexEntry>): String = buildJsonObject {
        put("v", 1)
        put("batches", buildJsonArray {
            entries.take(MassBatch.MAX_BATCHES).forEach { entry ->
                add(buildJsonObject {
                    put("id", entry.batchId.take(64))
                    put("name", entry.name.take(MassBatch.MAX_NAME_LENGTH))
                    put("updatedAt", entry.updatedAtMs)
                })
            }
        })
    }.toString()

    fun decodeIndex(json: String): List<IndexEntry> {
        if (json.length > 16 * 1024) return emptyList()
        return try {
            val root = lenientJson.parseToJsonElement(json).jsonObject
            (root["batches"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val id = str(obj, "id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    IndexEntry(
                        batchId = id.take(64),
                        name = (str(obj, "name") ?: "Untitled batch").take(MassBatch.MAX_NAME_LENGTH),
                        updatedAtMs = long(obj, "updatedAt") ?: 0,
                    )
                }
                ?.take(MassBatch.MAX_BATCHES)
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class IndexEntry(val batchId: String, val name: String, val updatedAtMs: Long)

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun int(obj: JsonObject, key: String): Int? = str(obj, key)?.toIntOrNull()
    private fun long(obj: JsonObject, key: String): Long? = str(obj, key)?.toLongOrNull()
    private fun dbl(obj: JsonObject, key: String): Double? = str(obj, key)?.toDoubleOrNull()
    private fun bool(obj: JsonObject, key: String): Boolean = str(obj, key)?.toBooleanStrictOrNull() ?: false
}
