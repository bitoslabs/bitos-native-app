package space.bitos.core.studio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * APP-019 Quick MEM editor project (spec §3.19; plan
 * `docs/native/meme-studio-plan.md` MST-001): the versioned, size-bounded
 * wire for an in-progress meme so a page close or app kill never loses
 * committed edits (EDT-002). Persisted by native adapters (project files
 * under the app's studio dir); the schema and its bounds live here.
 *
 *  • v1 wire:
 *    `{"v":1,"mode":"image","assets":[{"id":…,"kind":"image","delay":ms}],
 *      "overlays":[{"id":…,"kind":"text","text":…,"font":"impact","size":n,
 *                   "color":i,"outline":n,"shadow":b,"x":…,"y":…,"scale":…,"rot":…}],
 *      "trim":[inMs,outMs],"delay":ms,"cw":"","alt":"","tags":[…]}`
 *  • Canvas coordinates are normalized 0..1 (x/y = overlay center), so a
 *    project file loads identically on both platforms; only rasterized text
 *    metrics differ (per-platform export goldens).
 *  • Asset caps are mode-dependent: image ≤ 9 (ComposerRules.MAX_IMAGES
 *    parity), GIF frames ≤ 60 (delay ≥ 20 ms), video exactly 1.
 *  • Decode is lenient-but-bounded: corrupt or oversized wire → null (an
 *    empty project); over-long fields clamp — a broken store must never
 *    block creating.
 */
enum class MemeMode { IMAGE, GIF, VIDEO }

enum class MemeOverlayKind { TEXT, STICKER, IMAGE }

/** Semantic font slots — platforms map them to their licensed faces. */
enum class MemeFontSlot { IMPACT, SANS, SERIF, MONO }

/**
 * Burned-in motion effect ids (web `fx.ts`; plan §3.1). `none` is stored
 * as null — missing fx means none, forever. Transforms ship with M4
 * (`MemeFxRules`); M1 carries the ids losslessly through the wire.
 */
enum class MemeOverlayFx { POP, FADE, SHAKE, SPIN }

data class MemeAsset(
    val id: String,
    val kind: MemeMode,
    /** GIF frame delay in ms (GIF assets/frames only). */
    val delayMs: Int = 0,
)

/**
 * One M5 timeline clip (VIDEO mode): the project asset [id] (a video
 * source) plus its window in SOURCE media time. List order = timeline
 * order; output duration per clip is (end − start) ÷ the project speed.
 * A v1 wire (no `clips` array) decodes as a single clip built from the
 * legacy whole-project trim window.
 */
data class MemeClip(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    /** Per-clip audio volume (0 = muted, 1 = source; bounded 0..2). */
    val volume: Float = 1f,
    /** Per-clip color grade ([MemeLooks] id; null = the project grade). */
    val lookId: String? = null,
    /** Per-clip playback rate. Legacy projects use the project rate as fallback. */
    val speed: Float = 1f,
)

data class MemeOverlay(
    val id: String,
    val kind: MemeOverlayKind,
    val text: String,
    val font: MemeFontSlot,
    /** Font size in canvas px at the 1080-wide reference canvas. */
    val size: Int,
    /** Index into [MemeRules.PALETTE]. */
    val colorIndex: Int,
    /** Outline width in canvas px (0 = none). */
    val outline: Int,
    val shadow: Boolean,
    val x: Float,
    val y: Float,
    val scale: Float,
    /** Degrees, −180..180. */
    val rotationDeg: Float,
    /**
     * Web-parity flags (plan §3.2, MST-019): null = the web default
     * (caps true / bar false) so a round-trip never flips a default on.
     */
    val caps: Boolean? = null,
    val bar: Boolean? = null,
    /** Visibility window in media ms, half-open [start, end); null pairs
     *  mean "always visible". Nonsense windows never parse. */
    val startMs: Long? = null,
    val endMs: Long? = null,
    /** Motion effect; null = none. Transforms render from M4. */
    val fx: MemeOverlayFx? = null,
    /**
     * IMAGE layers only: the project asset id this overlay paints (web
     * `image-overlay.ts` parity — layers reference imported sources and
     * ride the local store, never the interop wire).
     */
    val assetId: String? = null,
)

/**
 * One pen stroke (V2 suite Draw chip; web `drawing.ts` bounds): a
 * normalized polyline painted UNDER the overlays. Points are x,y pairs
 * in 0..1 canvas space; width is a fraction of canvas height.
 */
data class MemeStroke(
    val id: String,
    /** Index into [MemeRules.PALETTE]. */
    val colorIndex: Int,
    /** Stroke width as a fraction of canvas height (clamped). */
    val widthNorm: Float,
    /** Flattened x0,y0,x1,y1… normalized 0..1. */
    val points: List<Float>,
)

data class MemeProject(
    val mode: MemeMode,
    val assets: List<MemeAsset> = emptyList(),
    val overlays: List<MemeOverlay> = emptyList(),
    /** Pen strokes, painted under the overlays (V2 Draw chip). */
    val drawStrokes: List<MemeStroke> = emptyList(),
    /** M5 ordered timeline clips (VIDEO mode; empty elsewhere). */
    val clips: List<MemeClip> = emptyList(),
    /** Imported-audio soundtrack ("use this sound"; VIDEO mode only;
     * null = original clip audio). Additive wire row `"sound"`. */
    val soundtrack: MemeSoundtrack? = null,
    /** Video trim window in ms (VIDEO mode only; legacy v1 field — the
     * first clip's window mirrors it for old-reader compatibility). */
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    /** Video playback rate (VIDEO mode only; 1 = source speed). */
    val speed: Float = 1f,
    /** GIF per-frame delay in ms (GIF mode only). */
    val frameDelayMs: Int = 0,
    val contentWarningReason: String? = null,
    val altText: String = "",
    val tags: List<String> = emptyList(),
    /** Source-media color grade (MST-043; [MemeLooks] id; null = none). */
    val lookId: String? = null,
    /** Manual fine-tune over the look (prototype FX sliders; null = default). */
    val adjust: MemeAdjust? = null,
    /** Synth SFX cues in media time (MST-041; ≤ [SfxSynth.MAX_CUES]). */
    val sfxCues: List<MemeSfxCue> = emptyList(),
    /** Canvas ratio preset ([MemeCanvas.RATIOS] id; null/`source` = media frame). */
    val canvasRatio: String? = null,
    /** Canvas background `#rrggbb` (null = platform default). */
    val canvasBg: String? = null,
) {
    /** True when nothing worth confirming a discard for exists. */
    val isEmpty: Boolean
        get() = assets.isEmpty() && overlays.isEmpty()
}

object MemeProjectContract {

    const val SCHEMA_VERSION = 1

    /** 48 overlays × ~200 chars + 60 assets + caption headroom. */
    const val MAX_WIRE_LENGTH = 65_536

    const val MAX_OVERLAYS = 48

    /** Web parity (schema.ts MAX_OVERLAY_CHARS = 300, plan MST-019). */
    const val MAX_TEXT_LENGTH = 300
    const val MAX_TAGS = 24
    const val MAX_TAG_LENGTH = 40
    const val MAX_ALT_LENGTH = 1_000
    const val MAX_CW_LENGTH = 120
    const val MAX_ASSET_ID_LENGTH = 64
    const val MIN_FRAME_DELAY_MS = 20
    const val MAX_FRAME_DELAY_MS = 1_000
    const val MAX_DURATION_MS = 14_400_000L

    /** Whole-clip playback rate bounds (web `speed-track.ts` rate range). */
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2f

    // ── Pen drawing (V2 Draw chip; web `drawing.ts` bounds) ───────────
    const val MAX_STROKES = 1_600
    const val MAX_POINTS_PER_STROKE = 1_500
    const val MAX_DRAWING_POINTS = 12_000
    const val MIN_STROKE_WIDTH = 0.002f
    const val MAX_STROKE_WIDTH = 0.05f
    const val DEFAULT_STROKE_WIDTH = 0.008f

    fun clampStrokeWidth(width: Float): Float =
        if (width.isNaN()) DEFAULT_STROKE_WIDTH else width.coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)

    /** Clamps a playback rate into [MIN_SPEED, MAX_SPEED]; junk → 1. */
    fun clampSpeed(speed: Float): Float =
        if (speed.isNaN() || speed <= 0f) 1f else speed.coerceIn(MIN_SPEED, MAX_SPEED)

    /**
     * Image layers a video project may stack on the clip (web
     * `image-overlay.ts` parity ≤ 6). GIF/image inserts land as a layer
     * (GIFs paint their first frame — V1 semantics).
     */
    const val MAX_IMAGE_LAYERS = 6

    /**
     * Assets allowed per mode. VIDEO = up to [MAX_VIDEO_CLIPS] clips +
     * [MAX_IMAGE_LAYERS] imported image sources.
     */
    const val MAX_VIDEO_CLIPS = 8

    /** Per-clip audio gain bound (0 = mute). */
    const val MAX_CLIP_VOLUME = 2f

    fun maxAssets(mode: MemeMode): Int = when (mode) {
        MemeMode.IMAGE -> 9
        MemeMode.GIF -> 60
        MemeMode.VIDEO -> MAX_VIDEO_CLIPS + MAX_IMAGE_LAYERS
    }

    private val lenientJson = Json { ignoreUnknownKeys = true }

    fun encode(project: MemeProject): String = buildJsonObject {
        put("v", SCHEMA_VERSION)
        put("mode", project.mode.name.lowercase())
        put("assets", buildJsonArray {
            project.assets.take(maxAssets(project.mode)).forEach { asset ->
                add(buildJsonObject {
                    put("id", asset.id.take(MAX_ASSET_ID_LENGTH))
                    put("kind", asset.kind.name.lowercase())
                    if (asset.delayMs > 0) put("delay", asset.delayMs)
                })
            }
        })
        put("overlays", buildJsonArray {
            project.overlays.take(MAX_OVERLAYS).forEach { overlay ->
                add(overlayJson(overlay))
            }
        })
        if (project.mode == MemeMode.VIDEO) {
            put("trim", buildJsonArray {
                add(JsonPrimitive(project.trimStartMs.coerceIn(0, MAX_DURATION_MS)))
                add(JsonPrimitive(project.trimEndMs.coerceIn(0, MAX_DURATION_MS)))
            })
            // "Use this sound" soundtrack: additive wire key (old readers
            // ignore it); written only when set and normalizable.
            MemeSoundRules.normalize(project.soundtrack)?.let { sound ->
                put("sound", buildJsonObject {
                    if (sound.url.isNotBlank()) put("url", sound.url)
                    put("sha256", sound.sha256)
                    put("ms", sound.durationMs)
                    if (sound.startMs > 0) put("start", sound.startMs)
                    if (sound.volume != 1f) put("vol", sound.volume)
                    if (sound.offsetMs > 0) put("offset", sound.offsetMs)
                    sound.sourceNoteId?.let { put("src", it) }
                    sound.sourceAuthorPubkey?.let { put("author", it) }
                    if (sound.label.isNotBlank()) put("label", sound.label)
                })
            }
            // M5 timeline clips: additive v1 wire key (old readers ignore it
            // and degrade to the first clip via `trim`); bounded + clamped.
            if (project.clips.isNotEmpty()) {
                put("clips", buildJsonArray {
                    project.clips.take(MAX_VIDEO_CLIPS).forEach { clip ->
                        add(buildJsonObject {
                            put("id", clip.id.take(MAX_ASSET_ID_LENGTH))
                            put("start", clip.startMs.coerceIn(0, MAX_DURATION_MS))
                            put("end", clip.endMs.coerceIn(0, MAX_DURATION_MS))
                            if (clip.volume != 1f) put("vol", clip.volume.coerceIn(0f, MAX_CLIP_VOLUME))
                            MemeLooks.normalize(clip.lookId)?.let { put("look", it) }
                            val clipSpeed = clampSpeed(clip.speed)
                            if (clipSpeed != 1f) put("rate", clipSpeed)
                        })
                    }
                })
            }
            // Speed rides only when set (1 = source speed stays implicit),
            // so older wires stay byte-identical.
            val speed = clampSpeed(project.speed)
            if (speed != 1f) put("speed", speed)
        }
        if (project.mode == MemeMode.GIF) {
            put("delay", project.frameDelayMs.coerceIn(MIN_FRAME_DELAY_MS, MAX_FRAME_DELAY_MS))
        }
        put("cw", project.contentWarningReason?.take(MAX_CW_LENGTH) ?: "")
        put("alt", project.altText.take(MAX_ALT_LENGTH))
        // Optional grade (MST-043): written only when set, so older wires
        // (and `none`) stay byte-identical.
        MemeLooks.normalize(project.lookId)?.let { put("look", it) }
        // Manual adjust (prototype FX sliders): same additive-key rule.
        val adjust = project.adjust
        if (adjust != null && !adjust.isDefault) {
            put("adjust", buildJsonObject {
                put("bri", adjust.brightness)
                put("con", adjust.contrast)
                put("sat", adjust.saturation)
            })
        }
        if (project.sfxCues.isNotEmpty()) {
            put("sfx", buildJsonArray {
                project.sfxCues.take(SfxSynth.MAX_CUES).forEach { cue ->
                    add(buildJsonObject {
                        put("id", cue.id.take(64))
                        put("sfx", cue.sfx.take(32))
                        put("at", cue.atMs.coerceAtLeast(0))
                        put("g", cue.gain.coerceIn(0f, 1f))
                    })
                }
            })
        }
        if (project.drawStrokes.isNotEmpty()) {
            put("draw", buildJsonArray {
                project.drawStrokes
                    .take(MAX_STROKES)
                    .map { stroke -> stroke.copy(widthNorm = clampStrokeWidth(stroke.widthNorm)) }
                    .forEach { stroke ->
                        add(buildJsonObject {
                            put("id", stroke.id.take(MAX_ASSET_ID_LENGTH))
                            put("c", stroke.colorIndex.coerceIn(0, MemeRules.PALETTE.lastIndex))
                            put("w", stroke.widthNorm)
                            put("p", buildJsonArray {
                                stroke.points.take(MAX_POINTS_PER_STROKE).forEach { point ->
                                    add(JsonPrimitive(point.coerceIn(0f, 1f)))
                                }
                            })
                        })
                    }
            })
        }
        put("tags", buildJsonArray {
            project.tags.take(MAX_TAGS).forEach { tag ->
                add(JsonPrimitive(tag.take(MAX_TAG_LENGTH)))
            }
        })
        // Canvas (image/GIF): additive v1 wire key — written only when set
        // so older wires stay byte-identical; unknown ids drop on read.
        val ratio = project.canvasRatio?.takeIf { it != MemeCanvas.RATIO_SOURCE }
        val bg = project.canvasBg?.takeIf { MemeCanvas.isValidBackground(it) }
        if (ratio != null || bg != null) {
            put("canvas", buildJsonObject {
                ratio?.take(12)?.let { put("ratio", it) }
                bg?.let { put("bg", it) }
            })
        }
    }.toString()

    internal fun overlayJson(overlay: MemeOverlay) = buildJsonObject {
        put("id", overlay.id.take(MAX_ASSET_ID_LENGTH))
        put("kind", overlay.kind.name.lowercase())
        put("text", overlay.text.take(MAX_TEXT_LENGTH))
        put("font", overlay.font.name.lowercase())
        put("size", MemeRules.clampSize(overlay.size))
        put("color", overlay.colorIndex.coerceIn(0, MemeRules.PALETTE.lastIndex))
        put("outline", MemeRules.clampOutline(overlay.outline))
        put("shadow", overlay.shadow)
        put("x", MemeRules.clampCoordinate(overlay.x))
        put("y", MemeRules.clampCoordinate(overlay.y))
        put("scale", MemeRules.clampScale(overlay.scale))
        put("rot", MemeRules.clampRotation(overlay.rotationDeg))
        // Web-parity optional fields (MST-019): written only when set so
        // v1 wires from M0 keep decoding and defaults stay implicit.
        overlay.caps?.let { put("caps", it) }
        overlay.bar?.let { put("bar", it) }
        overlay.startMs?.let { put("startMs", it) }
        overlay.endMs?.let { put("endMs", it) }
        overlay.fx?.let { put("fx", it.name.lowercase()) }
        overlay.assetId?.let { put("asset", it.take(MAX_ASSET_ID_LENGTH)) }
    }

    fun decode(json: String): MemeProject? {
        if (json.length > MAX_WIRE_LENGTH) return null
        return try {
            val root = lenientJson.parseToJsonElement(json).jsonObject
            if ((root["v"] as? JsonPrimitive)?.content?.toIntOrNull() != SCHEMA_VERSION) return null
            val mode = (root["mode"] as? JsonPrimitive)?.content?.let { parseMode(it) } ?: return null
            val assets = root["assets"]?.jsonArray
                ?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val kind = (obj["kind"] as? JsonPrimitive)?.content?.let { parseMode(it) }
                        ?: MemeMode.IMAGE
                    val delay = (obj["delay"] as? JsonPrimitive)?.content?.toIntOrNull()
                        ?.coerceIn(0, MAX_FRAME_DELAY_MS) ?: 0
                    MemeAsset(id.take(MAX_ASSET_ID_LENGTH), kind, delay)
                }
                ?.take(maxAssets(mode))
                ?: emptyList()
            val overlays = root["overlays"]?.jsonArray
                ?.mapNotNull { element -> decodeOverlayJson(element) }
                ?.take(MAX_OVERLAYS)
                ?: emptyList()
            val trim = root["trim"]?.jsonArray
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
            val legacyTrimStart = trim?.getOrNull(0)?.coerceIn(0, MAX_DURATION_MS) ?: 0
            val legacyTrimEnd = trim?.getOrNull(1)?.coerceIn(0, MAX_DURATION_MS) ?: 0
            // M5 clips: v2 read; a clip needs end > start. Absent/empty →
            // v1 migration (the legacy whole-project trim becomes clip 1).
            val clips = decodeClips(root["clips"])
            // Soundtrack: junk degrades to null (MemeSoundRules.normalize),
            // never a failed project decode.
            val soundtrack = (root["sound"] as? JsonObject)?.let { sound ->
                MemeSoundRules.normalize(
                    MemeSoundtrack(
                        url = (sound["url"] as? JsonPrimitive)?.content ?: "",
                        sha256 = (sound["sha256"] as? JsonPrimitive)?.content ?: "",
                        durationMs = (sound["ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                        startMs = (sound["start"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                        volume = (sound["vol"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 1f,
                        offsetMs = (sound["offset"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                        sourceNoteId = (sound["src"] as? JsonPrimitive)?.content,
                        sourceAuthorPubkey = (sound["author"] as? JsonPrimitive)?.content,
                        label = (sound["label"] as? JsonPrimitive)?.content ?: "",
                    ),
                )
            }
            val migratedClips = when {
                mode != MemeMode.VIDEO -> emptyList()
                clips.isNotEmpty() -> clips
                else -> listOf(MemeClip("v1", legacyTrimStart, legacyTrimEnd))
            }
            val frameDelay = (root["delay"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?.coerceIn(MIN_FRAME_DELAY_MS, MAX_FRAME_DELAY_MS) ?: 0
            val cwRaw = (root["cw"] as? JsonPrimitive)?.content.orEmpty()
            MemeProject(
                mode = mode,
                assets = assets,
                overlays = overlays,
                clips = migratedClips,
                soundtrack = if (mode == MemeMode.VIDEO) soundtrack else null,
                trimStartMs = legacyTrimStart,
                trimEndMs = legacyTrimEnd,
                speed = clampSpeed(
                    (root["speed"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 1f,
                ),
                frameDelayMs = frameDelay,
                contentWarningReason = cwRaw.take(MAX_CW_LENGTH).ifBlank { null },
                altText = ((root["alt"] as? JsonPrimitive)?.content ?: "").take(MAX_ALT_LENGTH),
                lookId = MemeLooks.normalize((root["look"] as? JsonPrimitive)?.content),
                adjust = decodeAdjust(root["adjust"]),
                sfxCues = (root["sfx"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { element ->
                        val obj = element.jsonObject
                        val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val sfx = (obj["sfx"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        if (sfx != "custom" && SfxSynth.RECIPES[sfx] == null) return@mapNotNull null
                        MemeSfxCue(
                            id = id.take(64),
                            sfx = sfx.take(32),
                            atMs = (obj["at"] as? JsonPrimitive)?.content?.toLongOrNull()
                                ?.coerceAtLeast(0) ?: 0,
                            gain = ((obj["g"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 1f)
                                .coerceIn(0f, 1f),
                        )
                    }
                    ?.take(SfxSynth.MAX_CUES)
                    ?: emptyList(),
                drawStrokes = decodeStrokes(root["draw"]),
                tags = root["tags"]?.jsonArray
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                    ?.map { it.take(MAX_TAG_LENGTH) }
                    ?.take(MAX_TAGS)
                    ?: emptyList(),
                canvasRatio = (root["canvas"] as? kotlinx.serialization.json.JsonObject)
                    ?.get("ratio")?.let { (it as? JsonPrimitive)?.content }
                    ?.takeIf { it.length <= 12 && MemeCanvas.isValidRatio(it) },
                canvasBg = (root["canvas"] as? kotlinx.serialization.json.JsonObject)
                    ?.get("bg")?.let { (it as? JsonPrimitive)?.content }
                    ?.takeIf { it.length == 7 && MemeCanvas.isValidBackground(it) },
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * M5 clips-array decoder (shared by the wire read and the bridge
     * timeline seams): bounded ≤ [MAX_VIDEO_CLIPS], windows clamped,
     * degenerate rows (missing id / end ≤ start) drop.
     */
    fun decodeClips(element: kotlinx.serialization.json.JsonElement?): List<MemeClip> {
        val rows = (element as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        return rows.mapNotNull { row ->
            val obj = row.jsonObject
            val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val start = (obj["start"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?.coerceIn(0, MAX_DURATION_MS - 1) ?: 0
            val rawEnd = (obj["end"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: return@mapNotNull null
            if (rawEnd <= start) return@mapNotNull null
            MemeClip(
                id = id.take(MAX_ASSET_ID_LENGTH),
                startMs = start,
                endMs = rawEnd.coerceAtMost(MAX_DURATION_MS),
                volume = ((obj["vol"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 1f)
                    .coerceIn(0f, MAX_CLIP_VOLUME),
                lookId = MemeLooks.normalize((obj["look"] as? JsonPrimitive)?.content),
                speed = clampSpeed((obj["rate"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 1f),
            )
        }.take(MAX_VIDEO_CLIPS)
    }

    /** Clips-array decode from a raw JSON string (bridge seam input). */
    fun decodeClipsJson(json: String): List<MemeClip>? = try {
        decodeClips(lenientJson.parseToJsonElement(json))
    } catch (_: Exception) {
        null
    }

    /**
     * Adjust triple decode (prototype FX sliders): lenient + clamped; a
     * default/all-junk row decodes to null (missing = untouched).
     */
    internal fun decodeAdjust(element: JsonElement?): MemeAdjust? {
        val obj = (element as? kotlinx.serialization.json.JsonObject) ?: return null
        fun valueOf(key: String): Float? =
            (obj[key] as? JsonPrimitive)?.content?.toFloatOrNull()
        val adjust = MemeAdjust.clamp(
            brightness = valueOf("bri") ?: 1f,
            contrast = valueOf("con") ?: 1f,
            saturation = valueOf("sat") ?: 1f,
        )
        return if (adjust.isDefault) null else adjust
    }

    /** Unknown kinds/fonts/colors degrade to safe defaults; junk rows drop. */
    internal fun decodeOverlayJson(element: kotlinx.serialization.json.JsonElement): MemeOverlay? {
        val obj = element.jsonObject
        val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        val kind = (obj["kind"] as? JsonPrimitive)?.content?.let { kind ->
            MemeOverlayKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }
        } ?: MemeOverlayKind.TEXT
        val font = (obj["font"] as? JsonPrimitive)?.content?.let { slot ->
            MemeFontSlot.entries.firstOrNull { it.name.equals(slot, ignoreCase = true) }
        } ?: MemeFontSlot.IMPACT
        return MemeOverlay(
            id = id.take(MAX_ASSET_ID_LENGTH),
            kind = kind,
            text = ((obj["text"] as? JsonPrimitive)?.content ?: "").take(MAX_TEXT_LENGTH),
            font = font,
            size = MemeRules.clampSize(
                (obj["size"] as? JsonPrimitive)?.content?.toIntOrNull() ?: MemeRules.DEFAULT_TEXT_SIZE,
            ),
            colorIndex = ((obj["color"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0)
                .coerceIn(0, MemeRules.PALETTE.lastIndex),
            outline = MemeRules.clampOutline(
                (obj["outline"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            ),
            shadow = (obj["shadow"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
            x = MemeRules.clampCoordinate(
                (obj["x"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0.5f,
            ),
            y = MemeRules.clampCoordinate(
                (obj["y"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0.5f,
            ),
            scale = MemeRules.clampScale(
                (obj["scale"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 1f,
            ),
            rotationDeg = MemeRules.clampRotation(
                (obj["rot"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f,
            ),
            caps = boolOf(obj, "caps"),
            bar = boolOf(obj, "bar"),
            startMs = msOf(obj, "startMs"),
            endMs = msOf(obj, "endMs"),
            fx = (obj["fx"] as? JsonPrimitive)?.content?.let { fx ->
                MemeOverlayFx.entries.firstOrNull { it.name.equals(fx, ignoreCase = true) }
            },
            assetId = (obj["asset"] as? JsonPrimitive)?.content
                ?.take(MAX_ASSET_ID_LENGTH)?.takeIf { it.isNotBlank() },
        ).normalizedWindow()
    }

    /** Nonsense windows (end ≤ start) mean "always visible" (web parity). */
    internal fun MemeOverlay.normalizedWindow(): MemeOverlay {
        val start = startMs
        val end = endMs
        return if (start != null && end != null && end <= start) {
            copy(startMs = null, endMs = null)
        } else {
            this
        }
    }

    private fun boolOf(obj: kotlinx.serialization.json.JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun msOf(obj: kotlinx.serialization.json.JsonObject, key: String): Long? =
        (obj[key] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it >= 0 }

    private fun parseMode(value: String): MemeMode? =
        MemeMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }

    /**
     * Pen strokes: lenient-but-budgeted — junk rows drop, a stroke needs
     * ≥ 2 points, and the project-wide point budget caps the total
     * (drawing.ts parity).
     */
    private fun decodeStrokes(element: kotlinx.serialization.json.JsonElement?): List<MemeStroke> {
        val rows = (element as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        var budget = MAX_DRAWING_POINTS
        val strokes = ArrayList<MemeStroke>()
        for (row in rows) {
            if (strokes.size >= MAX_STROKES || budget < 4) break
            val obj = try {
                row.jsonObject
            } catch (_: Exception) {
                continue
            }
            val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: "d${strokes.size + 1}"
            val points = (obj["p"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.toFloatOrNull() }
                ?.take(minOf(MAX_POINTS_PER_STROKE, budget))
                ?: continue
            if (points.size < 4) continue
            budget -= points.size
            strokes.add(
                MemeStroke(
                    id = id.take(MAX_ASSET_ID_LENGTH),
                    colorIndex = ((obj["c"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0)
                        .coerceIn(0, MemeRules.PALETTE.lastIndex),
                    widthNorm = clampStrokeWidth(
                        (obj["w"] as? JsonPrimitive)?.content?.toFloatOrNull()
                            ?: DEFAULT_STROKE_WIDTH,
                    ),
                    points = points.map { it.coerceIn(0f, 1f) },
                ),
            )
        }
        return strokes
    }
}
