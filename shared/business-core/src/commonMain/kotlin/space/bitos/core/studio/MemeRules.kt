package space.bitos.core.studio

/**
 * One reversible editor mutation (EDT-004). Commands are pure data: the
 * native editor keeps the history stack and coalescing window; the rules
 * here only apply and merge them.
 */
sealed interface MemeCommand {
    data class AddOverlay(val overlay: MemeOverlay) : MemeCommand
    data class RemoveOverlay(val id: String) : MemeCommand

    /**
     * Stack position edit (Layers sheet): moves one overlay to an absolute
     * paint index. Paint order = list order, so index 0 paints at the back
     * and the last index paints in front; the target clamps into range.
     */
    data class ReorderOverlay(val id: String, val toIndex: Int) : MemeCommand
    data class UpdateOverlay(
        val id: String,
        val x: Float? = null,
        val y: Float? = null,
        val scale: Float? = null,
        val rotationDeg: Float? = null,
        val text: String? = null,
        val font: MemeFontSlot? = null,
        /** Canvas px at the 1080 reference (clamped). */
        val size: Int? = null,
        val colorIndex: Int? = null,
        val outline: Int? = null,
        val shadow: Boolean? = null,
        /** Motion fx (MST-044); null keeps, "none"-style null clears via wire. */
        val fx: MemeOverlayFx? = null,
        /** Clear-the-fx flag (fx null means "leave unchanged"). */
        val clearFx: Boolean = false,
        /** Visibility window in media ms (MST-044). */
        val startMs: Long? = null,
        val endMs: Long? = null,
        /** Clear-the-end flag (null endMs otherwise keeps the stored one). */
        val clearEndMs: Boolean = false,
    ) : MemeCommand

    data class SetTrim(val startMs: Long, val endMs: Long) : MemeCommand

    /** Whole-clip playback rate (V2 suite Speed chip; clamped 0.5–2). */
    data class SetSpeed(val rate: Float) : MemeCommand
    data class SetFrameDelay(val delayMs: Int) : MemeCommand

    /** Source-media color grade (MST-043); `"none"` clears the look. */
    data class SetLook(val lookId: String) : MemeCommand

    /** Manual fine-tune over the look (prototype FX sliders); default clears. */
    data class SetAdjust(val adjust: MemeAdjust) : MemeCommand

    /** Synth SFX cue at media time (MST-041); cap 16, junk ids no-op. */
    data class AddSfxCue(val cue: MemeSfxCue) : MemeCommand
    data class RemoveSfxCue(val id: String) : MemeCommand

    /** Pen strokes (V2 Draw chip); the project-wide budget caps adds. */
    data class AddStroke(val stroke: MemeStroke) : MemeCommand
    data class RemoveStroke(val id: String) : MemeCommand
    data class ClearDrawing(val ignored: Boolean = true) : MemeCommand
}

/**
 * APP-019 meme editor rules (plan MST-002): pure, deterministic helpers for
 * overlays, the undo command model (EDT-004) and hit-testing. No platform
 * or rendering dependency — text metrics are estimates only; real metrics
 * live in the native rasterizers (per-platform export goldens).
 */
object MemeRules {

    /** Web-parity 16-color caption palette (ARGB). Index 0 is white. */
    val PALETTE: List<Long> = listOf(
        0xFFFFFFFF, 0xFF0A0A0F, 0xFFF9A84B, 0xFFD4790F,
        0xFFE84C4C, 0xFFB83232, 0xFF66D17E, 0xFF2E9E5B,
        0xFF5BC8F5, 0xFF2D9CDB, 0xFFB085F5, 0xFF7B4FD0,
        0xFFF5C15B, 0xFFE09B2D, 0xFFF2F2F7, 0xFF8E8E93,
    )

    const val DEFAULT_TEXT_SIZE = 48
    const val DEFAULT_STICKER_SIZE = 96

    /** Image layers default to ~18% of stage height (web sticker parity). */
    const val DEFAULT_IMAGE_SIZE = 192
    const val MIN_SIZE = 12
    const val MAX_SIZE = 240
    const val MIN_OUTLINE = 0
    const val MAX_OUTLINE = 12
    const val MIN_SCALE = 0.1f
    const val MAX_SCALE = 5f

    fun clampSize(size: Int): Int = size.coerceIn(MIN_SIZE, MAX_SIZE)
    fun clampOutline(outline: Int): Int = outline.coerceIn(MIN_OUTLINE, MAX_OUTLINE)
    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)
    fun clampRotation(deg: Float): Float = ((deg + 180f) % 360f).let {
        (if (it < 0) it + 360f else it) - 180f
    }
    fun clampCoordinate(value: Float): Float =
        if (value.isNaN()) 0.5f else value.coerceIn(0f, 1f)

    /** Deterministic overlay bounds estimate on the normalized canvas. */
    fun estimateBounds(overlay: MemeOverlay): Pair<Float, Float> {
        val effectiveSize = overlay.size * overlay.scale
        return if (overlay.kind == MemeOverlayKind.TEXT) {
            val width = ((overlay.text.length.coerceAtLeast(1) * effectiveSize * 0.6f) / 1080f)
            val height = (effectiveSize * 1.4f) / 1080f
            width.coerceIn(0.02f, 1f) to height.coerceIn(0.02f, 1f)
        } else {
            // Stickers and image layers: square hit box. Image aspect is a
            // platform raster concern; the shared estimate stays square so
            // hit-test geometry is deterministic cross-platform.
            val side = (effectiveSize * 1.2f) / 1080f
            side.coerceIn(0.02f, 1f) to side.coerceIn(0.02f, 1f)
        }
    }

    /**
     * Top-most overlay whose (rotation-ignored) bounds contain the point,
     * or null. Paint order = list order, so the scan runs back to front.
     */
    fun hitTest(project: MemeProject, x: Float, y: Float): MemeOverlay? {
        for (overlay in project.overlays.reversed()) {
            val (width, height) = estimateBounds(overlay)
            if (x in (overlay.x - width / 2)..(overlay.x + width / 2) &&
                y in (overlay.y - height / 2)..(overlay.y + height / 2)
            ) {
                return overlay
            }
        }
        return null
    }

    /** Staggered default placement so stacked adds never fully overlap. */
    fun defaultOverlay(project: MemeProject, kind: MemeOverlayKind, text: String): MemeOverlay {
        val index = project.overlays.size
        val textish = kind == MemeOverlayKind.TEXT
        return MemeOverlay(
            id = stableId(project, "o${index + 1}"),
            kind = kind,
            text = text.take(MemeProjectContract.MAX_TEXT_LENGTH),
            font = if (textish) MemeFontSlot.IMPACT else MemeFontSlot.SANS,
            size = when (kind) {
                MemeOverlayKind.TEXT -> DEFAULT_TEXT_SIZE
                MemeOverlayKind.STICKER -> DEFAULT_STICKER_SIZE
                MemeOverlayKind.IMAGE -> DEFAULT_IMAGE_SIZE
            },
            colorIndex = if (textish) 0 else 0,
            outline = if (textish) 2 else 0,
            shadow = false,
            x = 0.5f + ((index % 3) - 1) * 0.08f,
            y = 0.35f + (index % 5) * 0.08f,
            scale = 1f,
            rotationDeg = 0f,
        )
    }

    /** Deterministic uniqueness within the project (no clock dependency). */
    private fun stableId(project: MemeProject, candidate: String): String {
        var id = candidate
        var bump = 0
        while (project.overlays.any { it.id == id }) {
            bump += 1
            id = "$candidate-$bump"
        }
        return id
    }

    // ── Undo command model (EDT-004) ────────────────────────────────────

    /** Applies one command; unknown ids and out-of-cap adds are no-ops. */
    fun apply(project: MemeProject, command: MemeCommand): MemeProject = when (command) {
        is MemeCommand.AddOverlay ->
            if (project.overlays.size >= MemeProjectContract.MAX_OVERLAYS) project
            else project.copy(overlays = project.overlays + command.overlay)

        is MemeCommand.RemoveOverlay ->
            project.copy(overlays = project.overlays.filterNot { it.id == command.id })

        is MemeCommand.ReorderOverlay -> {
            val from = project.overlays.indexOfFirst { it.id == command.id }
            if (from == -1) {
                project
            } else {
                val to = command.toIndex.coerceIn(0, project.overlays.lastIndex)
                if (to == from) {
                    project
                } else {
                    val reordered = project.overlays.toMutableList()
                    reordered.add(to, reordered.removeAt(from))
                    project.copy(overlays = reordered)
                }
            }
        }

        is MemeCommand.UpdateOverlay ->
            // A nonsensical window (end ≤ start) means "always visible" —
            // the wire parser's rule, enforced on edits too.
            if (command.startMs != null && command.endMs != null && command.endMs <= command.startMs) {
                updateOverlays(project, command.copy(endMs = null, clearEndMs = true))
            } else {
                updateOverlays(project, command)
            }

        is MemeCommand.AddSfxCue ->
            if (project.sfxCues.size >= SfxSynth.MAX_CUES ||
                (command.cue.sfx != "custom" && SfxSynth.RECIPES[command.cue.sfx] == null)
            ) {
                project
            } else {
                project.copy(
                    sfxCues = project.sfxCues + command.cue.copy(
                        gain = command.cue.gain.coerceIn(0f, 1f),
                        atMs = command.cue.atMs.coerceAtLeast(0),
                    ),
                )
            }

        is MemeCommand.RemoveSfxCue ->
            project.copy(sfxCues = project.sfxCues.filterNot { it.id == command.id })


        is MemeCommand.SetTrim -> {
            val max = MemeProjectContract.MAX_DURATION_MS
            val start = command.startMs.coerceIn(0, max)
            project.copy(
                trimStartMs = start,
                trimEndMs = command.endMs.coerceIn(start, max),
            )
        }

        is MemeCommand.SetSpeed -> project.copy(
            speed = MemeProjectContract.clampSpeed(command.rate),
        )

        is MemeCommand.AddStroke -> {
            val stroke = command.stroke.let { raw ->
                raw.copy(
                    colorIndex = raw.colorIndex.coerceIn(0, PALETTE.lastIndex),
                    widthNorm = MemeProjectContract.clampStrokeWidth(raw.widthNorm),
                    points = raw.points.take(MemeProjectContract.MAX_POINTS_PER_STROKE)
                        .map { clampCoordinate(it) },
                )
            }
            if (stroke.points.size < 4) {
                project
            } else {
                // Project-wide point budget: over-budget adds drop the
                // OLDEST strokes first (recent ink wins, drawing.ts parity).
                val kept = ArrayList<MemeStroke>()
                var acc = stroke.points.size
                for (existing in project.drawStrokes.asReversed()) {
                    if (acc + existing.points.size > MemeProjectContract.MAX_DRAWING_POINTS) break
                    acc += existing.points.size
                    kept.add(0, existing)
                }
                project.copy(drawStrokes = (kept + stroke).take(MemeProjectContract.MAX_STROKES))
            }
        }

        is MemeCommand.RemoveStroke ->
            project.copy(drawStrokes = project.drawStrokes.filterNot { it.id == command.id })

        is MemeCommand.ClearDrawing -> project.copy(drawStrokes = emptyList())

        is MemeCommand.SetFrameDelay -> project.copy(
            frameDelayMs = command.delayMs.coerceIn(
                MemeProjectContract.MIN_FRAME_DELAY_MS,
                MemeProjectContract.MAX_FRAME_DELAY_MS,
            ),
        )

        is MemeCommand.SetLook -> project.copy(lookId = MemeLooks.normalize(command.lookId))

        is MemeCommand.SetAdjust -> {
            // Clamped again here (commands are hostile input); default = null
            // so the wire stays minimal and the matrix fast-path holds.
            val clamped = MemeAdjust.clamp(
                command.adjust.brightness,
                command.adjust.contrast,
                command.adjust.saturation,
            )
            project.copy(adjust = if (clamped.isDefault) null else clamped)
        }
    }

    /**
     * Drag/slider coalescing: two updates to the same overlay merge into
     * one (later fields win, nulls keep the earlier values) — a 300 ms
     * gesture becomes a single undo step. Adjust slider bursts merge the
     * same way. Null when not coalescable.
     */
    fun coalesce(earlier: MemeCommand, later: MemeCommand): MemeCommand? {
        if (earlier is MemeCommand.UpdateOverlay && later is MemeCommand.UpdateOverlay) {
            if (earlier.id != later.id) return null
            return MemeCommand.UpdateOverlay(
                id = later.id,
                x = later.x ?: earlier.x,
                y = later.y ?: earlier.y,
                scale = later.scale ?: earlier.scale,
                rotationDeg = later.rotationDeg ?: earlier.rotationDeg,
                text = later.text ?: earlier.text,
                font = later.font ?: earlier.font,
                size = later.size ?: earlier.size,
                colorIndex = later.colorIndex ?: earlier.colorIndex,
                outline = later.outline ?: earlier.outline,
                shadow = later.shadow ?: earlier.shadow,
                fx = later.fx ?: earlier.fx,
                clearFx = later.clearFx || earlier.clearFx,
                startMs = later.startMs ?: earlier.startMs,
                endMs = later.endMs ?: earlier.endMs,
                clearEndMs = later.clearEndMs || earlier.clearEndMs,
            )
        }
        // Adjust sliders fire continuously — one drag, one undo step. The
        // command carries the full triple, so the later state wins as-is.
        if (earlier is MemeCommand.SetAdjust && later is MemeCommand.SetAdjust) {
            return later
        }
        return null
    }
    private fun updateOverlays(project: MemeProject, command: MemeCommand.UpdateOverlay): MemeProject = project.copy(
            overlays = project.overlays.map { overlay ->
                if (overlay.id != command.id) {
                    overlay
                } else {
                    overlay.copy(
                        x = command.x?.let(::clampCoordinate) ?: overlay.x,
                        y = command.y?.let(::clampCoordinate) ?: overlay.y,
                        scale = command.scale?.let(::clampScale) ?: overlay.scale,
                        rotationDeg = command.rotationDeg?.let(::clampRotation)
                            ?: overlay.rotationDeg,
                        text = command.text?.take(MemeProjectContract.MAX_TEXT_LENGTH)
                            ?: overlay.text,
                        font = command.font ?: overlay.font,
                        size = command.size?.let(::clampSize) ?: overlay.size,
                        colorIndex = command.colorIndex?.coerceIn(0, PALETTE.lastIndex)
                            ?: overlay.colorIndex,
                        outline = command.outline?.let(::clampOutline) ?: overlay.outline,
                        shadow = command.shadow ?: overlay.shadow,
                        fx = if (command.clearFx) null else command.fx ?: overlay.fx,
                        startMs = command.startMs ?: overlay.startMs,
                        endMs = if (command.clearEndMs) null else command.endMs ?: overlay.endMs,
                    )
                }
            },
        )
}
