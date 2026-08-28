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
 * APP-008 composer draft (spec §3.8 "Draft persistence + discard
 * confirm"): the versioned, size-bounded wire for the in-progress note so
 * a page close or app kill never loses it. Persisted by native adapters
 * (SharedPreferences / UserDefaults); the schema and its bounds live here.
 *
 *  • v1 wire: `{"v":1,"text":…,"urls":[…],"cw":…,"mentions":[{n,u}…],"pow":n}`
 *  • text ≤ [ComposerRules.HARD_LIMIT], urls ≤ [ComposerRules.MAX_IMAGES]
 *    (local picks are NOT persisted — their URIs are ephemeral),
 *    cw reason ≤ 120, mentions ≤ 8 pairs, pow target 0…30
 *  • decode is lenient-but-bounded: corrupt or oversized wire → null (an
 *    empty draft), over-long fields clamp — a broken store must never
 *    block composing.
 */
data class ComposerDraft(
    val text: String,
    val remoteUrls: List<String> = emptyList(),
    val contentWarningReason: String? = null,
    /** Tracked mention picks (display name → npub) for the publish rewrite. */
    val trackedMentions: List<Pair<String, String>> = emptyList(),
    val powTarget: Int = 0,
) {
    /** True when any content exists worth confirming a discard for. */
    val isEmpty: Boolean
        get() = text.isBlank() && remoteUrls.isEmpty() && contentWarningReason.isNullOrBlank()
}

object ComposerDraftContract {

    const val SCHEMA_VERSION = 1

    /** 16k text + 4×~512 URLs + mentions headroom — hard wire bound. */
    const val MAX_WIRE_LENGTH = 24_576

    private const val MAX_CW_REASON = 120
    private const val MAX_MENTIONS = 8

    private val lenientJson = Json { ignoreUnknownKeys = true }

    fun encode(draft: ComposerDraft): String = buildJsonObject {
        put("v", SCHEMA_VERSION)
        put("text", draft.text.take(ComposerRules.HARD_LIMIT))
        put("urls", buildJsonArray {
            draft.remoteUrls.take(ComposerRules.MAX_IMAGES).forEach { url ->
                if (url.length <= 2_048) add(JsonPrimitive(url))
            }
        })
        put("cw", draft.contentWarningReason?.take(MAX_CW_REASON) ?: "")
        put("mentions", buildJsonArray {
            draft.trackedMentions.take(MAX_MENTIONS).forEach { (name, npub) ->
                add(buildJsonObject {
                    put("n", name.take(120))
                    put("u", npub.take(80))
                })
            }
        })
        put("pow", draft.powTarget.coerceIn(0, 30))
    }.toString()

    fun decode(json: String): ComposerDraft? {
        if (json.length > MAX_WIRE_LENGTH) return null
        return try {
            val root = lenientJson.parseToJsonElement(json).jsonObject
            if ((root["v"] as? JsonPrimitive)?.content?.toIntOrNull() != SCHEMA_VERSION) return null
            val text = (root["text"] as? JsonPrimitive)?.content?.take(ComposerRules.HARD_LIMIT) ?: ""
            val urls = root["urls"]?.jsonArray
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { url -> url.length <= 2_048 } }
                ?.take(ComposerRules.MAX_IMAGES)
                ?: emptyList()
            val cwRaw = (root["cw"] as? JsonPrimitive)?.content.orEmpty()
            val mentions = root["mentions"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val name = (obj["n"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val npub = (obj["u"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                if (name.isBlank() || npub.isBlank()) return@mapNotNull null
                name to npub
            }?.take(MAX_MENTIONS) ?: emptyList()
            val pow = (root["pow"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(0, 30) ?: 0
            ComposerDraft(
                text = text,
                remoteUrls = urls,
                contentWarningReason = cwRaw.ifBlank { null },
                trackedMentions = mentions,
                powTarget = pow,
            )
        } catch (_: Exception) {
            null
        }
    }
}
