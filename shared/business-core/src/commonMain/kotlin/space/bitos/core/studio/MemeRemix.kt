package space.bitos.core.studio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Compact `meme` tag payload for remix publishes (plan §3.3, task
 * MST-042; web `remix.ts` parity). The full wire document (MST-019) is
 * the editor/project format; THIS is the ≤700-char relay-friendly tag
 * form: keys `v/o/c/l` (+ verbatim passthrough of future keys like
 * `g/z/f/s` so a native remix never drops an author's tracks), defaults
 * omitted (impact/white/caps/stroke), coordinates rounded to 2 dp.
 *
 * Degradation ladder (plan: "never truncate silently"): the full
 * layout+cues payload, then layout only, then media-only (no meme tag at
 * all — the remix/p attribution still ride). Web slices at 700 today;
 * the ladder is the plan's rule and produces valid JSON at every step.
 */
object MemeRemix {

    /** Max serialized remix payload (web MAX_MEME_TAG_CHARS). */
    const val MAX_TAG_CHARS = 700

    /** Compact decode cap (plan §3.3: overlay decode cap 12). */
    const val MAX_DECODE_OVERLAYS = 12

    data class Degraded(
        /** The encoded `meme` tag payload, or null at the media-only step. */
        val payload: String?,
        /** media-only = the meme tag was dropped entirely (remix/p still ride). */
        val mediaOnly: Boolean,
    )

    /** Compact-encodes a wire document and applies the 700-char ladder. */
    fun encodeDegraded(document: MemeWireDocument): Degraded {
        val full = encode(document, dropCues = false)
        if (full.length <= MAX_TAG_CHARS) return Degraded(full, mediaOnly = false)
        val layoutOnly = encode(document, dropCues = true)
        if (layoutOnly.length <= MAX_TAG_CHARS) return Degraded(layoutOnly, mediaOnly = false)
        return Degraded(null, mediaOnly = true)
    }

    /** Compact encode (web `encodeRemixPayload`, V1 keys + passthrough). */
    fun encode(document: MemeWireDocument, dropCues: Boolean = false): String = buildJsonObject {
        put("v", MemeWire.VERSION)
        put("o", buildJsonArray {
            document.overlays.take(MemeWire.MAX_OVERLAYS).forEach { overlay ->
                add(buildJsonObject {
                    put("t", overlay.text)
                    put("x", round2(overlay.x))
                    put("y", round2(overlay.y))
                    put("s", round2(overlay.size))
                    if (overlay.color.lowercase() != "#ffffff") put("c", overlay.color)
                    if (overlay.font != "impact") put("f", overlay.font)
                    if (!overlay.caps) put("k", false)
                    if (!overlay.stroke) put("o", false)
                    if (overlay.bar) put("b", true)
                    if (overlay.startMs != null && overlay.endMs != null) {
                        put("w", buildJsonArray {
                            add(JsonPrimitive(overlay.startMs))
                            add(JsonPrimitive(overlay.endMs))
                        })
                    }
                })
            }
        })
        if (!dropCues && document.sfxCues.isNotEmpty()) {
            put("c", buildJsonArray {
                document.sfxCues.take(MemeWire.MAX_SFX_CUES).forEach { cue ->
                    add(buildJsonObject {
                        put("s", cue.sfx)
                        cue.soundId?.let { put("i", it) }
                        put("a", cue.atMs)
                        put("g", cue.gain)
                        cue.lane?.let { put("l", it) }
                    })
                }
            })
        }
        document.lookId?.let { put("l", it) }
        // Unknown/future keys ride verbatim (native must not drop tracks).
        document.passthrough.forEach { (key, value) -> put(key, value) }
    }.toString()

    /**
     * Tolerant decode (web `decodeRemixPayload`): junk → null; overlays
     * normalize through the MST-019 rules and cap at 12; unknown top-level
     * keys are preserved in the passthrough.
     */
    fun decode(raw: String?): MemeWireDocument? {
        if (raw.isNullOrEmpty() || raw.length > MAX_TAG_CHARS) return null
        return try {
            val root = Json.parseToJsonElement(raw).jsonObject
            val rows = root["o"] as? JsonArray ?: return null
            val overlays = rows.mapNotNull { element ->
                val obj = element.jsonObject
                val window = (obj["w"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
                MemeWireOverlay(
                    id = "o",
                    text = (obj["t"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                    x = (fraction(obj["x"]) ?: 0.5f).coerceIn(0f, 1f),
                    y = (fraction(obj["y"]) ?: 0.5f).coerceIn(0f, 1f),
                    size = (fraction(obj["s"]) ?: 0.09f)
                        .coerceIn(MemeWire.MIN_OVERLAY_SIZE, MemeWire.MAX_OVERLAY_SIZE),
                    color = cleanColor((obj["c"] as? JsonPrimitive)?.content),
                    font = (obj["f"] as? JsonPrimitive)?.content?.takeIf { it in MemeWire.FONTS }
                        ?: "impact",
                    caps = (obj["k"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    stroke = (obj["o"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    bar = (obj["b"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    startMs = window?.getOrNull(0),
                    endMs = window?.getOrNull(1),
                    fx = (obj["fx"] as? JsonPrimitive)?.content
                        ?.takeIf { it in MemeWire.FX_IDS && it != "none" },
                    passthrough = emptyMap(),
                )
            }.filter { it.text.isNotBlank() }
                .take(MAX_DECODE_OVERLAYS)
            if (overlays.isEmpty()) return null

            val cues = (root["c"] as? JsonArray)?.mapNotNull { element ->
                val obj = element.jsonObject
                val sfx = (obj["s"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                MemeWireCue(
                    id = "c",
                    sfx = when {
                        sfx == MemeWire.CUSTOM_SOUND_KEY -> {
                            (obj["i"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                            sfx
                        }

                        sfx in MemeWire.SFX_IDS -> sfx
                        else -> return@mapNotNull null
                    },
                    atMs = (obj["a"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                    gain = (fraction(obj["g"]) ?: 1f).coerceIn(0f, 1f),
                    lane = (obj["l"] as? JsonPrimitive)?.content?.toIntOrNull()
                        ?.takeIf { it in 0..3 },
                    soundId = (obj["i"] as? JsonPrimitive)?.content,
                )
            }?.take(MemeWire.MAX_SFX_CUES) ?: emptyList()

            val lookId = (root["l"] as? JsonPrimitive)?.content
                ?.takeIf { it in MemeWire.LOOK_IDS && it != "none" }

            val passthrough: Map<String, JsonElement> = root.entries
                .filter { it.key !in KNOWN_KEYS && (it.value is JsonArray || it.value is JsonObject) }
                .associate { it.key to it.value }

            MemeWireDocument(
                overlays = overlays,
                sfxCues = cues,
                lookId = lookId,
                passthrough = passthrough,
                createdAt = 0L,
                updatedAt = 0L,
            )
        } catch (_: Exception) {
            null
        }
    }

    private val KNOWN_KEYS = setOf("v", "o", "c", "l")

    /**
     * Web `applyRemixPayload` parity: clone the compact payload's overlays,
     * cues and look onto [project] with FRESH ids so each remix is an
     * independently editable copy (mirrors memeTemplates.apply semantics —
     * ids strip first, normalization hands out new ones). REPLACE, not
     * append: a remix session starts from the source layout, it does not
     * stack on whatever the project already held.
     *
     * Custom-sound cues drop (the local project cue has no library id slot;
     * a cue without its sound is noise). Tracks the native editor cannot
     * model or re-emit (`g`/`z`/`f`/`s` passthrough) drop too — carrying
     * them would claim effects the export never burns in.
     */
    fun applyTo(project: MemeProject, payload: String?): MemeProject {
        val document = decode(payload) ?: return project
        val seeded = MemeWireConvert.wireToLocal(document)
        val taken = HashSet<String>()
        project.overlays.forEach { taken.add(it.id) }
        project.sfxCues.forEach { taken.add(it.id) }

        fun freshId(prefix: String): String {
            var n = 0
            while (true) {
                n += 1
                val candidate = "$prefix$n"
                if (taken.add(candidate)) return candidate
            }
        }

        return project.copy(
            overlays = seeded.overlays.map { it.copy(id = freshId("r")) },
            sfxCues = document.sfxCues
                .filter { it.sfx != MemeWire.CUSTOM_SOUND_KEY }
                .map { cue -> MemeSfxCue(id = freshId("s"), sfx = cue.sfx, atMs = cue.atMs, gain = cue.gain) },
            lookId = document.lookId ?: project.lookId,
        )
    }


    /** Web `cleanColor` parity: valid #hex survives, junk → white. */
    private fun cleanColor(raw: String?): String {
        val trimmed = raw?.trim() ?: return "#ffffff"
        return if (Regex("^#[0-9a-f]{3,8}$", RegexOption.IGNORE_CASE).matches(trimmed)) {
            trimmed
        } else {
            "#ffffff"
        }
    }

    private fun fraction(element: JsonElement?): Float? =
        (element as? JsonPrimitive)?.content?.toFloatOrNull()?.takeIf { it.isFinite() }

    private fun round2(value: Float): Double =
        kotlin.math.round(value * 100) / 100.0
}
