package space.bitos.core.studio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared template events (plan MST-045 / §3.5): kind-30078 addressable
 * events with `d = "com.bitos.bitz:template:<id>"` whose content is
 * `{schema:"com.bitos.bitz.template", version:2, label, icon, overlays[,
 * timed extras…], price_sats?, category?}`. V1 events (overlays only,
 * no version) parse as FREE templates; timed extras are ignored by this
 * reader (V1 native applies overlays only — the wire fields stay
 * available to later waves via the wire document).
 *
 * Every read is hostile-tolerant (MEM-004: "capability, license, preview
 * and hostile-schema limits"): junk fields clamp, foreign shapes → null,
 * junk prices degrade to free, and a template with no valid overlay is
 * rejected outright.
 */
object MemeTemplateContract {

    const val SCHEMA = "com.bitos.bitz.template"
    const val D_TAG_PREFIX = "com.bitos.bitz:template:"
    const val MAX_LABEL = 40
    /** Rail cap for relay-fetched shared templates (newest-first). */
    const val MAX_SHARED_ROWS = 24
    const val JUNK_PRICE_SATS = 1_000_000L
    val PRICE_TIERS = setOf(0L, 21L, 100L, 500L)

    /** 10 fixed marketplace categories (web `template-marketplace.ts`). */
    val CATEGORIES = listOf(
        "trending", "meme", "lao", "thai", "developer", "bitcoin",
        "gaming", "reaction", "cinematic", "new",
    )

    /** Icon allowlist (8 ids → display emoji; unknown → generic). */
    val ICONS: Map<String, String> = mapOf(
        "zap" to "⚡", "mine" to "⛏", "laugh" to "😂", "fire" to "🔥",
        "rocket" to "🚀", "brain" to "🧠", "ghost" to "👻", "coin" to "🪙",
    )

    data class SharedTemplate(
        val id: String,
        val label: String,
        val emoji: String,
        /** 0 = free; junk/unknown prices degrade here. */
        val priceSats: Long,
        val category: String,
        /** Local-store overlays, ready for the apply-as-clone path. */
        val overlays: List<MemeOverlay>,
    )

    /** True for `d` tags addressing a BitOS template event. */
    fun isTemplateDTag(d: String): Boolean = d.startsWith(D_TAG_PREFIX)

    /**
     * Parses one event (tags + content). Null when the shape is foreign
     * (not our d-tag / wrong schema / no valid overlay). Timed extras in
     * the content are carried through the wire decode losslessly but V1
     * applies overlays only.
     */
    fun parse(tags: List<List<String>>, content: String): SharedTemplate? {
        val dTag = tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)
            ?.takeIf { isTemplateDTag(it) } ?: return null
        val id = dTag.removePrefix(D_TAG_PREFIX).take(48).ifBlank { return null }
        val root = try {
            Json.parseToJsonElement(content).jsonObject
        } catch (_: Exception) {
            return null
        }
        val schema = (root["schema"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (schema != null && schema != SCHEMA) return null
        val version = (root["version"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 1
        if (version < 1 || version > 2) return null

        // Overlays ride the WIRE document shape — reuse its tolerant
        // parser + local converter (single source of clamping truth).
        val overlaysRoot = root["overlays"] as? kotlinx.serialization.json.JsonArray ?: return null
        val wireJson = kotlinx.serialization.json.buildJsonObject {
            put("schema", kotlinx.serialization.json.JsonPrimitive("com.bitos.bitz.meme"))
            put("version", kotlinx.serialization.json.JsonPrimitive(1))
            put("overlays", overlaysRoot)
        }.toString()
        // Hostile-schema tolerance (MEM-004): a converter crash on junk
        // input degrades to "no valid overlay" instead of propagating.
        val document = runCatching { MemeWireCodec.decode(wireJson, nowMs = 0L) }.getOrNull() ?: return null
        val localOverlays = runCatching { MemeWireConvert.wireToLocal(document).overlays }
            .getOrDefault(emptyList())
            .filter { it.text.isNotBlank() }
            .take(MemeProjectContract.MAX_OVERLAYS)
        if (localOverlays.isEmpty()) return null

        return SharedTemplate(
            id = id,
            label = ((root["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: id)
                .take(MAX_LABEL).ifBlank { id },
            emoji = ICONS[(root["icon"] as? kotlinx.serialization.json.JsonPrimitive)?.content] ?: "🖼",
            priceSats = priceOf((root["price_sats"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()),
            category = CATEGORIES.firstOrNull {
                it == (root["category"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: "meme",
            overlays = localOverlays,
        )
    }

    /** Tier prices keep; everything else (incl. junk > 1M) degrades to free. */
    fun priceOf(raw: Long?): Long = when {
        raw == null -> 0L
        raw in PRICE_TIERS -> raw
        else -> 0L
    }

    /** Apply-as-clone onto a project (same semantics as the built-in pack). */
    fun apply(project: MemeProject, template: SharedTemplate): MemeProject {
        var seed = 0
        val cloned = template.overlays.map { overlay ->
            seed += 1
            overlay.copy(id = "s$seed-${template.id.take(12)}")
        }
        return project.copy(overlays = cloned)
    }
}
