package space.bitos.core.studio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Meme wire document `com.bitos.bitz.meme` v1 (plan §3.1, task MST-019; web
 * `src/lib/meme/schema.ts` ported verbatim — when they disagree, the web
 * wins). This is the INTEROP contract for remix `meme` tag payloads, shared
 * templates and cross-device project exchange; the editing working wire
 * stays `MemeProject` (§3.2), converted through [MemeWireConvert].
 *
 * Parse rule (web parity): coerce/clamp/drop, never throw. Foreign schema
 * ids and `version > 1` are rejected; junk overlay rows drop; `updatedAt`
 * is always re-stamped on load. Native is strictly SAFER than web in one
 * spot: unknown fields are preserved verbatim in `passthrough` maps so a
 * native round-trip never destroys future-web data (web drops them).
 */
object MemeWire {

    const val SCHEMA = "com.bitos.bitz.meme"
    const val VERSION = 1

    /** Hostile-input bound (largest legal doc is a few KB). */
    const val MAX_WIRE_LENGTH = 65_536

    const val MAX_OVERLAYS = 12
    const val MAX_OVERLAY_CHARS = 300
    const val MIN_OVERLAY_SIZE = 0.03f
    const val MAX_OVERLAY_SIZE = 0.22f
    const val DEFAULT_OVERLAY_SIZE = 0.09f

    /** Canvas reference for wire fraction ⇄ local px (plan §3.2: 0.09 → 97 px). */
    const val CANVAS_REFERENCE = 1080f

    const val MAX_CAPTION = 1000
    const val MAX_SFX_CUES = 16

    val FONTS = listOf("impact", "sans", "serif", "mono")

    /** Fixed 7-color web palette (schema.ts MEME_COLORS), index-ordered. */
    val COLORS = listOf(
        "#ffffff", "#000000", "#fde047", "#f97316",
        "#22d3ee", "#a3e635", "#f472b6",
    )

    val FX_IDS = listOf("none", "pop", "fade", "shake", "spin")

    val LOOK_IDS = listOf(
        "none", "mono", "noir", "sepia", "vhs", "deepfry", "dream", "invert",
    )

    /** 31 synth recipe ids (schema.ts MEME_SFX_IDS; recipes render in M4). */
    val SFX_IDS = listOf(
        // funny
        "boom", "bruh", "laugh", "crowd-laugh", "gasp", "sad-trombone", "awkward-silence",
        // impact
        "bass-hit", "whoosh", "slam", "explosion", "punch", "anime-slash",
        // system
        "error", "success", "notification", "loading", "game-over",
        // money
        "coin", "cash", "jackpot", "lightning-zap",
        // transitions
        "pop", "boing", "drumroll", "ding", "swipe", "click", "snap",
        "record-scratch", "reverse-whoosh",
    )

    /** Sentinel cue key for user-imported sounds (library id in soundId). */
    const val CUSTOM_SOUND_KEY = "custom"

    internal val colorRegex = Regex("^#[0-9a-f]{3,8}$", RegexOption.IGNORE_CASE)
    internal val lenientJson = Json { ignoreUnknownKeys = true }
}

/** One wire overlay row (web `MemeTextOverlay`) + preserved unknown fields. */
data class MemeWireOverlay(
    val id: String,
    val text: String,
    /** Center, normalized 0–1. */
    val x: Float,
    val y: Float,
    /** Fraction of stage height, 0.03–0.22. */
    val size: Float,
    /** Web hex color (3–8 hex digits with `#`, case preserved). */
    val color: String,
    /** One of [MemeWire.FONTS]. */
    val font: String,
    val caps: Boolean,
    /** Classic outline around the glyphs. */
    val stroke: Boolean,
    /** Contrast pill behind each line. */
    val bar: Boolean,
    /** Visibility window in media ms, half-open [start, end); null = always. */
    val startMs: Long?,
    val endMs: Long?,
    /** One of [MemeWire.FX_IDS] minus "none"; null = none. */
    val fx: String?,
    /** Unknown fields, verbatim (future-web data safety). */
    val passthrough: Map<String, JsonElement> = emptyMap(),
)

/** One SFX cue (web `MemeSfxCue`). Recipes synthesize from M4. */
data class MemeWireCue(
    val id: String,
    /** A [MemeWire.SFX_IDS] entry or [MemeWire.CUSTOM_SOUND_KEY]. */
    val sfx: String,
    /** Cue point in media ms (integer, ≥ 0). */
    val atMs: Long,
    /** Master gain 0–1 (default 1). */
    val gain: Float,
    /** Mixer lane 0–3; null = default. */
    val lane: Int?,
    /** Required when sfx == custom (library id, ≤64 chars). */
    val soundId: String?,
    val passthrough: Map<String, JsonElement> = emptyMap(),
)

/** The full wire document (web `MemeProject`). */
data class MemeWireDocument(
    val overlays: List<MemeWireOverlay>,
    val sfxCues: List<MemeWireCue> = emptyList(),
    val caption: String? = null,
    /** "image" | "video"; null = unspecified. */
    val mediaKind: String? = null,
    /** One of [MemeWire.LOOK_IDS] minus "none"; null = none. */
    val lookId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val passthrough: Map<String, JsonElement> = emptyMap(),
) {
    /** Is the overlay on screen at media time [atMs]? Windows are [start, end). */
    fun visibleAt(overlay: MemeWireOverlay, atMs: Long): Boolean {
        overlay.startMs?.let { if (atMs < it) return false }
        overlay.endMs?.let { if (atMs >= it) return false }
        return true
    }
}

/**
 * Tolerant wire codec: `decode` parses foreign/hostile JSON into a
 * normalized document (or null when the schema id/version is foreign);
 * `encode` writes the exact web shape with passthrough fields re-attached.
 */
object MemeWireCodec {

    fun decode(json: String, nowMs: Long): MemeWireDocument? {
        if (json.length > MemeWire.MAX_WIRE_LENGTH) return null
        return try {
            val root = MemeWire.lenientJson.parseToJsonElement(json).jsonObject
            if ((root["schema"] as? JsonPrimitive)?.content != MemeWire.SCHEMA) return null
            val version = (root["version"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                ?: MemeWire.VERSION.toDouble()
            if (version < 1 || version > MemeWire.VERSION) return null

            var generated = 0
            val seen = mutableSetOf<String>()
            val overlays = (root["overlays"] as? JsonArray)
                ?.mapNotNull { element -> decodeOverlay(element, seen) { overlayId(generated++, seen) } }
                ?.take(MemeWire.MAX_OVERLAYS)
                ?: emptyList()
            val cues = (root["sfxCues"] as? JsonArray)
                ?.mapNotNull { element -> decodeCue(element, seen) { cueId(generated++, seen) } }
                ?.take(MemeWire.MAX_SFX_CUES)
                ?: emptyList()

            MemeWireDocument(
                overlays = overlays,
                sfxCues = cues,
                caption = (root["caption"] as? JsonPrimitive)?.content?.let { it.take(MemeWire.MAX_CAPTION) },
                mediaKind = (root["mediaKind"] as? JsonPrimitive)?.content
                    ?.takeIf { it == "image" || it == "video" },
                lookId = (root["lookId"] as? JsonPrimitive)?.content
                    ?.takeIf { it in MemeWire.LOOK_IDS && it != "none" },
                createdAt = longNum(root["createdAt"]) ?: nowMs,
                updatedAt = nowMs,
                passthrough = passthroughOf(
                    root,
                    known = setOf(
                        "schema", "version", "overlays", "sfxCues", "caption",
                        "mediaKind", "lookId", "createdAt", "updatedAt",
                    ),
                ),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun encode(document: MemeWireDocument): String = buildJsonObject {
        put("schema", MemeWire.SCHEMA)
        put("version", MemeWire.VERSION)
        put("overlays", buildJsonArray {
            document.overlays.take(MemeWire.MAX_OVERLAYS).forEach { overlay ->
                add(buildJsonObject {
                    put("id", overlay.id.take(64))
                    put("text", overlay.text.take(MemeWire.MAX_OVERLAY_CHARS))
                    put("x", MemeWireConvert.sanitizeFraction(overlay.x, 0.5f))
                    put("y", MemeWireConvert.sanitizeFraction(overlay.y, 0.5f))
                    put("size", overlay.size.coerceIn(MemeWire.MIN_OVERLAY_SIZE, MemeWire.MAX_OVERLAY_SIZE))
                    put("color", cleanColor(overlay.color))
                    put("font", if (overlay.font in MemeWire.FONTS) overlay.font else "impact")
                    put("caps", overlay.caps)
                    put("stroke", overlay.stroke)
                    put("bar", overlay.bar)
                    overlay.startMs?.let { put("startMs", it) }
                    overlay.endMs?.let { put("endMs", it) }
                    overlay.fx?.let { fx -> if (fx in MemeWire.FX_IDS && fx != "none") put("fx", fx) }
                    overlay.passthrough.forEach { (key, value) -> put(key, value) }
                })
            }
        })
        if (document.sfxCues.isNotEmpty()) {
            put("sfxCues", buildJsonArray {
                document.sfxCues.take(MemeWire.MAX_SFX_CUES).forEach { cue ->
                    add(buildJsonObject {
                        put("id", cue.id.take(64))
                        put("sfx", cue.sfx)
                        put("atMs", cue.atMs)
                        put("gain", cue.gain)
                        cue.lane?.let { put("lane", it) }
                        cue.soundId?.let { put("soundId", it) }
                        cue.passthrough.forEach { (key, value) -> put(key, value) }
                    })
                }
            })
        }
        document.caption?.let { put("caption", it.take(MemeWire.MAX_CAPTION)) }
        document.mediaKind?.let { put("mediaKind", it) }
        document.lookId?.let { put("lookId", it) }
        put("createdAt", document.createdAt)
        put("updatedAt", document.updatedAt)
        document.passthrough.forEach { (key, value) -> put(key, value) }
    }.toString()

    /** `decode` → `encode`: hostile wire in, canonical web-shaped wire out. */
    fun normalize(json: String, nowMs: Long): String? =
        decode(json, nowMs)?.let(::encode)

    // ── Overlay parsing (web normalizeOverlay, branch for branch) ───────

    private fun decodeOverlay(
        raw: JsonElement,
        seen: MutableSet<String>,
        fallbackId: () -> String,
    ): MemeWireOverlay? {
        if (raw !is JsonObject) return null
        val text = (raw["text"] as? JsonPrimitive)?.content
            ?.replace("\r", "")?.take(MemeWire.MAX_OVERLAY_CHARS)
        if (text.isNullOrEmpty() || text.isBlank()) return null
        val explicitId = (raw["id"] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }?.take(64)
        val id = uniqueId(explicitId, seen) ?: fallbackId()
        seen += id
        val startMs = optionalMs(raw["startMs"])
        val endMs = optionalMs(raw["endMs"])
        val windowed = if (startMs != null && endMs != null && endMs <= startMs) {
            null to null
        } else {
            startMs to endMs
        }
        val fx = (raw["fx"] as? JsonPrimitive)?.content
            ?.takeIf { it in MemeWire.FX_IDS && it != "none" }
        return MemeWireOverlay(
            id = id,
            text = text,
            x = fractionOf(raw["x"], 0.5f),
            y = fractionOf(raw["y"], 0.5f),
            size = fractionOf(raw["size"], MemeWire.DEFAULT_OVERLAY_SIZE)
                .coerceIn(MemeWire.MIN_OVERLAY_SIZE, MemeWire.MAX_OVERLAY_SIZE),
            color = cleanColor((raw["color"] as? JsonPrimitive)?.content),
            font = (raw["font"] as? JsonPrimitive)?.content?.takeIf { it in MemeWire.FONTS }
                ?: "impact",
            caps = truthy(raw["caps"], default = true),
            stroke = truthy(raw["stroke"], default = true),
            bar = truthy(raw["bar"], default = false),
            startMs = windowed.first,
            endMs = windowed.second,
            fx = fx,
            passthrough = passthroughOf(
                raw,
                known = setOf(
                    "id", "text", "x", "y", "size", "color", "font", "caps",
                    "stroke", "bar", "startMs", "endMs", "fx",
                ),
            ),
        )
    }

    // ── Cue parsing (web normalizeSfxCue) ───────────────────────────────

    private fun decodeCue(
        raw: JsonElement,
        seen: MutableSet<String>,
        fallbackId: () -> String,
    ): MemeWireCue? {
        if (raw !is JsonObject) return null
        val soundId = (raw["soundId"] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }?.take(64)
        val sfx = (raw["sfx"] as? JsonPrimitive)?.content
        val resolved = when {
            sfx == MemeWire.CUSTOM_SOUND_KEY -> if (soundId != null) sfx else return null
            sfx != null && sfx in MemeWire.SFX_IDS -> sfx
            else -> return null
        }
        val atMs = (raw["atMs"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0 }?.roundToLong() ?: 0L
        val gain = (raw["gain"] as? JsonPrimitive)?.content?.toFloatOrNull()
            ?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val lane = (raw["lane"] as? JsonPrimitive)?.content?.toFloatOrNull()
            ?.takeIf { it.isFinite() && it > 0 }
            ?.let { floor(it).toInt().coerceAtMost(3) }
        val explicitId = (raw["id"] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }?.take(64)
        val id = uniqueId(explicitId, seen) ?: fallbackId()
        seen += id
        return MemeWireCue(
            id = id,
            sfx = resolved,
            atMs = atMs,
            gain = gain,
            lane = lane,
            soundId = if (sfx == MemeWire.CUSTOM_SOUND_KEY) soundId else null,
            passthrough = passthroughOf(
                raw,
                known = setOf("id", "sfx", "atMs", "gain", "lane", "soundId"),
            ),
        )
    }

    // ── Shared tolerant-field readers (web `num`/`optionalMs`/truthiness) ──

    /** Explicit ids win unless they collide in-document; generated ids
     * (`o-1`, `c-1`, …) are deterministic and collision-free. */
    private fun uniqueId(explicit: String?, seen: MutableSet<String>): String? {
        if (explicit == null) return null
        if (explicit !in seen) return explicit
        var bump = 1
        while ("$explicit-$bump" in seen) bump += 1
        return "$explicit-$bump"
    }

    private fun overlayId(generated: Int, seen: MutableSet<String>): String {
        var candidate = "o-${generated + 1}"
        var bump = 1
        while (candidate in seen) {
            bump += 1
            candidate = "o-${generated + 1}-$bump"
        }
        return candidate
    }

    private fun cueId(generated: Int, seen: MutableSet<String>): String {
        var candidate = "c-${generated + 1}"
        var bump = 1
        while (candidate in seen) {
            bump += 1
            candidate = "c-${generated + 1}-$bump"
        }
        return candidate
    }

    private fun fractionOf(raw: JsonElement?, fallback: Float): Float =
        (raw as? JsonPrimitive)?.content?.toFloatOrNull()
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0f, 1f)
            ?: fallback

    private fun longNum(raw: JsonElement?): Long? =
        (raw as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }?.toLong()

    /** web `optionalMs`: null/""/negative/non-finite → undefined, else round. */
    private fun optionalMs(raw: JsonElement?): Long? {
        if (raw is JsonNull) return null
        val content = (raw as? JsonPrimitive)?.content ?: return null
        if (content.isEmpty()) return null
        return content.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0 }?.roundToLong()
    }

    /** web `!!value` truthiness; absent key → default, JSON null → false. */
    private fun truthy(raw: JsonElement?, default: Boolean): Boolean = when (raw) {
        null -> default // key absent (JSON undefined)
        is JsonNull -> false
        is JsonArray, is JsonObject -> true
        else -> {
            val primitive = raw.jsonPrimitive
            when (primitive.isString) {
                true -> primitive.content.isNotEmpty()
                else -> primitive.content != "0" && primitive.content != "false" && primitive.content.isNotEmpty()
            }
        }
    }

    private fun cleanColor(raw: String?, fallback: String = "#ffffff"): String {
        val trimmed = raw?.trim()
        return if (trimmed != null && MemeWire.colorRegex.matches(trimmed)) trimmed else fallback
    }

    private fun passthroughOf(obj: JsonObject, known: Set<String>): Map<String, JsonElement> =
        obj.filterKeys { it !in known }
}

/**
 * Local ⇄ wire converters (plan §3.2). Pure, deterministic, common-tested.
 *
 *  • size: wire fraction × [MemeWire.CANVAS_REFERENCE] → local px; local px
 *    ÷ reference → fraction. Local MIN 12 px sits below the web MIN 32 px —
 *    exports clamp to the tighter bound.
 *  • color: nearest-match between the web 7-hex set and the 16-color local
 *    palette (§8 open decision — converging on the web set as indices 0–6
 *    later touches only [nearestPaletteIndex]/[nearestWireHex]).
 *  • stroke ⇄ outline: web `stroke=true` ⇔ local `outline > 0` (width is a
 *    local extra; platforms default 2 px).
 *  • local-only styling (scale, rotation, shadow) rides the overlay
 *    passthrough so a NATIVE wire round-trip is lossless; web parsers
 *    ignore those fields, so web stays compatible either way.
 */
object MemeWireConvert {

    fun wireToLocal(document: MemeWireDocument): MemeProject {
        val mode = if (document.mediaKind == "video") MemeMode.VIDEO else MemeMode.IMAGE
        return MemeProject(
            mode = mode,
            assets = emptyList(), // wire docs carry no binary assets (M4 templates bind them)
            overlays = document.overlays.map { overlay ->
                val capsOff = !overlay.caps
                val strokeOff = !overlay.stroke
                val stickerLike = strokeOff && capsOff && StickerCatalog.isEmojiOnly(overlay.text)
                MemeOverlay(
                    id = overlay.id,
                    kind = if (stickerLike) MemeOverlayKind.STICKER else MemeOverlayKind.TEXT,
                    text = overlay.text,
                    font = MemeFontSlot.entries.firstOrNull {
                        it.name.equals(overlay.font, ignoreCase = true)
                    } ?: MemeFontSlot.IMPACT,
                    size = wireSizeToLocalPx(overlay.size),
                    colorIndex = nearestPaletteIndex(overlay.color),
                    outline = if (overlay.stroke) LOCAL_STROKE_OUTLINE_PX else 0,
                    shadow = localBool(overlay.passthrough["shadow"]) ?: false,
                    x = MemeRules.clampCoordinate(overlay.x),
                    y = MemeRules.clampCoordinate(overlay.y),
                    scale = MemeRules.clampScale(localFloat(overlay.passthrough["scale"]) ?: 1f),
                    rotationDeg = MemeRules.clampRotation(localFloat(overlay.passthrough["rot"]) ?: 0f),
                    caps = if (overlay.caps) null else false,
                    bar = if (overlay.bar) true else null,
                    startMs = overlay.startMs,
                    endMs = overlay.endMs,
                    fx = overlay.fx?.let { fx ->
                        MemeOverlayFx.entries.firstOrNull { it.name.equals(fx, ignoreCase = true) }
                    },
                )
            },
            contentWarningReason = null,
            altText = document.caption ?: "",
            tags = emptyList(),
        )
    }

    /**
     * Local project → wire document. Blank-text overlays drop (web
     * `buildProject` parity — the wire parser rejects them anyway); IMAGE
     * layers drop too (web `image-overlay.ts`: layers ride the editor
     * store, never the interop wire — they persist via the local project
     * wire and burn into exported media); only the first
     * [MemeWire.MAX_OVERLAYS] survive.
     */
    fun localToWire(project: MemeProject, nowMs: Long): MemeWireDocument {
        return MemeWireDocument(
            overlays = project.overlays
                .filter { it.kind != MemeOverlayKind.IMAGE && it.text.isNotBlank() }
                .take(MemeWire.MAX_OVERLAYS)
                .map { overlay ->
                    MemeWireOverlay(
                        id = overlay.id,
                        text = overlay.text,
                        x = MemeRules.clampCoordinate(overlay.x),
                        y = MemeRules.clampCoordinate(overlay.y),
                        size = localSizeToWireFraction(overlay.size),
                        color = nearestWireHex(overlay.colorIndex),
                        font = overlay.font.name.lowercase(),
                        caps = overlay.caps ?: true,
                        stroke = overlay.outline > 0,
                        bar = overlay.bar ?: false,
                        startMs = overlay.startMs,
                        endMs = overlay.endMs,
                        fx = overlay.fx?.name?.lowercase(),
                        passthrough = buildMap {
                            // Local-only styling, preserved for native round-trips.
                            if (overlay.scale != 1f) put("scale", JsonPrimitive(overlay.scale))
                            if (overlay.rotationDeg != 0f) put("rot", JsonPrimitive(overlay.rotationDeg))
                            if (overlay.shadow) put("shadow", JsonPrimitive(true))
                            if (overlay.kind == MemeOverlayKind.STICKER) put("sticker", JsonPrimitive(true))
                        },
                    )
                },
            caption = project.altText.takeIf { it.isNotBlank() }?.take(MemeWire.MAX_CAPTION),
            mediaKind = if (project.mode == MemeMode.VIDEO) "video" else "image",
            createdAt = nowMs,
            updatedAt = nowMs,
        )
    }

    // ── Size ⇄ fraction ─────────────────────────────────────────────────

    fun wireSizeToLocalPx(fraction: Float): Int =
        MemeRules.clampSize((fraction * MemeWire.CANVAS_REFERENCE).roundToInt())

    fun localSizeToWireFraction(sizePx: Int): Float =
        MemeRules.clampSize(sizePx)
            .div(MemeWire.CANVAS_REFERENCE)
            .coerceIn(MemeWire.MIN_OVERLAY_SIZE, MemeWire.MAX_OVERLAY_SIZE)

    // ── Nearest-match color mapping (§8: nearest-match until decided) ───

    /** Local palette index whose RGB is nearest the web hex (ties → lowest). */
    fun nearestPaletteIndex(hex: String): Int {
        val rgb = parseHexRgb(hex) ?: return 0
        return nearestByRgbDistance(rgb, MemeRules.PALETTE.map { it.toInt() and 0xFFFFFF })
    }

    /** Web hex nearest the local palette index's RGB (ties → lowest). */
    fun nearestWireHex(paletteIndex: Int): String {
        val clamped = paletteIndex.coerceIn(0, MemeRules.PALETTE.lastIndex)
        val rgb = MemeRules.PALETTE[clamped].toInt() and 0xFFFFFF
        val candidateRgb = MemeWire.COLORS.mapNotNull(::parseHexRgb)
        val nearest = nearestByRgbDistance(rgb, candidateRgb)
        return MemeWire.COLORS[nearest]
    }

    private fun nearestByRgbDistance(target: Int, candidates: List<Int>): Int {
        val r = (target shr 16) and 0xFF
        val g = (target shr 8) and 0xFF
        val b = target and 0xFF
        var best = 0
        var bestDistance = Long.MAX_VALUE
        candidates.forEachIndexed { index, candidate ->
            val dr = r - ((candidate shr 16) and 0xFF)
            val dg = g - ((candidate shr 8) and 0xFF)
            val db = b - (candidate and 0xFF)
            val distance = dr.toLong() * dr + dg.toLong() * dg + db.toLong() * db
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /** `#rgb`/`#rrggbb`/`#rrggbbaa` → 24-bit RGB; null on garbage. */
    private fun parseHexRgb(hex: String): Int? {
        val body = hex.removePrefix("#").lowercase()
        val expanded = when (body.length) {
            3 -> body.map { "$it$it" }.joinToString("")
            6, 8 -> body.take(6)
            else -> return null
        }
        return expanded.toIntOrNull(16)
    }

    private fun localFloat(raw: JsonElement?): Float? =
        (raw as? JsonPrimitive)?.content?.toFloatOrNull()?.takeIf { it.isFinite() }

    private fun localBool(raw: JsonElement?): Boolean? =
        (raw as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    fun sanitizeFraction(value: Float, fallback: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else fallback

    private const val LOCAL_STROKE_OUTLINE_PX = 2
}
