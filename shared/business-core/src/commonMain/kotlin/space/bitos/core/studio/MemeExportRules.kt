package space.bitos.core.studio

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Export rules for the Quick MEM raster (plan MST-016; web `render.ts`
 * parity — when they disagree the web wins). Pure and deterministic: the
 * common tests pin exact pixel values, and both platform rasterizers
 * (android.graphics / UIGraphicsImageRenderer) consume the same
 * [MemeExportItem] plan so preview ⇄ export stay WYSIWYG within a
 * platform and identical in geometry across platforms.
 *
 * Reference frame: overlay `size` (local px) lives on the 1080-long-edge
 * reference canvas; the draw size on any target is `size × height/1080`
 * (web `paintOverlay`: `px = max(10, size × referenceHeight)`).
 */
object MemeExportRules {

    /** Canvas long-edge cap (px) — web `targetSize` default. */
    const val LONG_EDGE = 1080

    /** Web `paintOverlay` floors the font at 10 px regardless of size. */
    const val MIN_FONT_PX = 10

    /**
     * Local outline px are glyph-edge padding; the painted stroke is this
     * multiple (matches the stage rasterizers for WYSIWYG).
     */
    const val OUTLINE_STROKE_SCALE = 2f

    /** Line spacing multiplier for multiline captions. */
    const val LINE_HEIGHT = 1.2f

    /**
     * Output canvas for a source (web `targetSize` port): never upscales,
     * rounds, then forces even dimensions (≥ 2) for hardware encoders.
     * Degenerate sources fall back to the 1080×1920 portrait default.
     * [longEdgeCap] lowers the cap for manual export presets
     * ([MemeExportPresets]); the default is the MST-016 web parity cap.
     */
    fun outputSize(
        sourceWidth: Int,
        sourceHeight: Int,
        longEdgeCap: Int = LONG_EDGE,
    ): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return longEdgeCap to (longEdgeCap * 16 / 9)
        var width = sourceWidth
        var height = sourceHeight
        val longest = max(width, height)
        if (longest > longEdgeCap) {
            val scale = longEdgeCap.toFloat() / longest
            width = (width * scale).roundToInt()
            height = (height * scale).roundToInt()
        }
        width = max(2, width - (width % 2))
        height = max(2, height - (height % 2))
        return width to height
    }

    /**
     * One draw command for the platform rasterizers: everything already in
     * target-canvas pixels — a rasterizer only paints.
     */
    data class MemeExportItem(
        val id: String,
        /** Display text: the caps transform is applied here (web `displayText`). */
        val text: String,
        val font: MemeFontSlot,
        /** ARGB color. */
        val color: Long,
        /** Center on the target canvas (px). */
        val centerX: Float,
        val centerY: Float,
        /**
         * Font size (px, ≥ [MIN_FONT_PX]). For image layers this is the
         * layer's target HEIGHT in px — the rasterizer resolves the asset
         * by id and keeps its aspect for the width.
         */
        val fontSizePx: Float,
        /** Outline stroke width (px, 0 = none — stickers/images). */
        val outlinePx: Float,
        val shadow: Boolean,
        val rotationDeg: Float,
        val sticker: Boolean,
        /** Multiline rows, pre-split (paint top-down, centered). */
        val lines: List<String>,
        /** Image layer: paint [assetId]'s bitmap instead of text. */
        val image: Boolean = false,
        /** Image layers only: the project asset id to resolve. */
        val assetId: String? = null,
    )

    /**
     * The draw plan for [project] on a [canvasWidth]×[canvasHeight] target.
     * Deterministic: same project + canvas → byte-identical plan (goldens).
     * FX transforms stay out until M4 (`MemeFxRules`) — fx ids ride the
     * project but render static in V1 exports, exactly like the V1 preview.
     */
    fun exportPlan(project: MemeProject, canvasWidth: Int, canvasHeight: Int): List<MemeExportItem> {
        if (canvasWidth <= 0 || canvasHeight <= 0) return emptyList()
        val heightScale = canvasHeight / MemeWire.CANVAS_REFERENCE
        return project.overlays.mapNotNull { overlay ->
            if (overlay.kind == MemeOverlayKind.IMAGE) {
                // Image layers never blank-drop: the painted content is the
                // asset, not text. fontSizePx carries the target height.
                val assetId = overlay.assetId ?: return@mapNotNull null
                val scale = MemeRules.clampScale(overlay.scale)
                return@mapNotNull MemeExportItem(
                    id = overlay.id,
                    text = "",
                    font = overlay.font,
                    color = MemeRules.PALETTE[0],
                    centerX = MemeRules.clampCoordinate(overlay.x) * canvasWidth,
                    centerY = MemeRules.clampCoordinate(overlay.y) * canvasHeight,
                    fontSizePx = overlay.size * scale * heightScale,
                    outlinePx = 0f,
                    shadow = overlay.shadow,
                    rotationDeg = overlay.rotationDeg,
                    sticker = false,
                    lines = emptyList(),
                    image = true,
                    assetId = assetId,
                )
            }
            val display = displayText(overlay)
            if (display.isBlank()) return@mapNotNull null
            // Local scale participates so exports match the edited stage
            // (the wire has no scale — native round-trips carry it in
            // passthrough, rendered output honors it always).
            val scale = MemeRules.clampScale(overlay.scale)
            val fontSize = max(MIN_FONT_PX.toFloat(), overlay.size * scale * heightScale)
            MemeExportItem(
                id = overlay.id,
                text = display,
                font = overlay.font,
                color = MemeRules.PALETTE[
                    overlay.colorIndex.coerceIn(0, MemeRules.PALETTE.lastIndex),
                ],
                centerX = MemeRules.clampCoordinate(overlay.x) * canvasWidth,
                centerY = MemeRules.clampCoordinate(overlay.y) * canvasHeight,
                fontSizePx = fontSize,
                outlinePx = if (overlay.kind == MemeOverlayKind.STICKER) {
                    0f
                } else {
                    overlay.outline * OUTLINE_STROKE_SCALE * scale * heightScale
                },
                shadow = overlay.shadow,
                rotationDeg = overlay.rotationDeg,
                sticker = overlay.kind == MemeOverlayKind.STICKER,
                lines = display.split("\n"),
            )
        }
    }

    /** web `displayText`: uppercase when caps (default true), source untouched. */
    fun displayText(overlay: MemeOverlay): String {
        val caps = overlay.caps ?: true
        return if (caps) overlay.text.uppercase() else overlay.text
    }

    /**
     * One per-frame paint command for the TIMED exporters (video burn-in):
     * the static [MemeExportItem] geometry plus the frame's fx transform.
     */
    data class MemeTimedPaint(
        val item: MemeExportItem,
        val fx: MemeFxRules.FxTransform,
    )

    /**
     * One pen-stroke paint command: everything in target-canvas px — the
     * rasterizer only strokes the polyline (round caps/joins, stage parity).
     */
    data class MemeStrokePaint(
        val color: Long,
        val widthPx: Float,
        val pointsPx: List<Float>,
    )

    /**
     * Pen strokes on the target canvas (painted UNDER the overlays — the
     * media grades never touch ink). Deterministic with the export plan.
     */
    fun drawingPlan(project: MemeProject, canvasWidth: Int, canvasHeight: Int): List<MemeStrokePaint> {
        if (canvasWidth <= 0 || canvasHeight <= 0) return emptyList()
        return project.drawStrokes.map { stroke ->
            MemeStrokePaint(
                color = MemeRules.PALETTE[
                    stroke.colorIndex.coerceIn(0, MemeRules.PALETTE.lastIndex),
                ],
                widthPx = MemeProjectContract.clampStrokeWidth(stroke.widthNorm) * canvasHeight,
                pointsPx = stroke.points.mapIndexed { index, point ->
                    val clamped = point.coerceIn(0f, 1f)
                    // Even indices are x (× width), odd are y (× height).
                    if (index % 2 == 0) clamped * canvasWidth else clamped * canvasHeight
                },
            )
        }
    }

    /**
     * The paint plan at MEDIA time [atMs]: overlays outside their
     * half-open visibility window drop; fx transforms ride every item
     * ([MemeFxRules.IDENTITY] when none). Video exporters call this per
     * frame with the OUTPUT time shifted back into media time (output 0
     * = the trim window start), so burned-in timing matches the stage
     * preview exactly. atMs = null means "poster" — everything paints,
     * untransformed.
     */
    fun paintPlanAt(project: MemeProject, canvasWidth: Int, canvasHeight: Int, atMs: Long?): List<MemeTimedPaint> {
        if (canvasWidth <= 0 || canvasHeight <= 0) return emptyList()
        val plan = exportPlan(project, canvasWidth, canvasHeight)
        if (atMs == null) return plan.map { MemeTimedPaint(it, MemeFxRules.IDENTITY) }
        val overlaysById = project.overlays.associateBy { it.id }
        return plan.mapNotNull { item ->
            val overlay = overlaysById[item.id] ?: return@mapNotNull null
            if (!MemeFxRules.visibleAt(overlay, atMs)) return@mapNotNull null
            MemeTimedPaint(item, MemeFxRules.transformAt(overlay, atMs))
        }
    }

    /**
     * The export envelope for the Swift bridge consumer: the evened output
     * canvas (web `targetSize` of the SOURCE dims) plus the paint rows —
     * one call, so the size math stays single-sourced on the shared side.
     */
    fun exportEnvelope(project: MemeProject, sourceWidth: Int, sourceHeight: Int): String {
        val (width, height) = outputSize(sourceWidth, sourceHeight)
        return buildJsonObject {
            put("width", width)
            put("height", height)
            put("items", buildJsonArray {
                exportPlan(project, width, height).forEach { item ->
                    add(buildJsonObject {
                        put("id", item.id)
                        put("text", item.text)
                        put("font", item.font.name.lowercase())
                        put("color", item.color)
                        put("x", item.centerX)
                        put("y", item.centerY)
                        put("fontSize", item.fontSizePx)
                        put("outline", item.outlinePx)
                        put("shadow", item.shadow)
                        put("rot", item.rotationDeg)
                        put("sticker", item.sticker)
                        put("lines", buildJsonArray { item.lines.forEach { add(it) } })
                        if (item.image) {
                            put("image", true)
                            put("asset", item.assetId ?: "")
                        }
                    }
                    )
                }
            })
            // Pen strokes paint UNDER the overlay items.
            val strokes = drawingPlan(project, width, height)
            if (strokes.isNotEmpty()) {
                put("strokes", buildJsonArray {
                    strokes.forEach { stroke ->
                        add(buildJsonObject {
                            put("c", stroke.color)
                            put("w", stroke.widthPx)
                            put("p", buildJsonArray {
                                stroke.pointsPx.forEach { point -> add(point) }
                            })
                        })
                    }
                })
            }
        }.toString()
    }
}
