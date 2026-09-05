package space.bitos.app.ui.create.meme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import space.bitos.core.studio.MemeAsset
import space.bitos.core.studio.MemeCommand
import space.bitos.core.studio.MemeFontSlot
import space.bitos.core.studio.MemeMode
import space.bitos.core.studio.MemeOverlayKind
import space.bitos.core.studio.MemeProject
import space.bitos.core.studio.MemeProjectContract
import space.bitos.core.studio.MemeRules

/**
 * Editing core of the Quick MEM image editor (plan MST-010..015; wave 1 of
 * `docs/native/meme-studio-plan.md` §M1 execution note). Holds the project
 * plus a bounded undo stack and turns editor intents into shared
 * [MemeCommand]s — all clamping, caps and hit-testing stay in
 * `space.bitos.core.studio`, this class only owns history and selection.
 *
 * Gesture semantics (EDT-004): a drag/pinch/twist stream applies live but
 * pushes ONE net `UpdateOverlay` at gesture end; discrete style/slider
 * bursts coalesce through `MemeRules.coalesce` within
 * [DEFAULT_COALESCE_WINDOW_MS]. Undo restores the project snapshot taken
 * before each committed step; redo (V2 suite dock) holds the post-undo
 * states and any new edit clears it (standard history semantics).
 *
 * Platform-free by design: assets are opaque session-local ids (`a1…a9`)
 * mapped to image refs by the screen, so this class is unit-testable.
 */
class MemeEditorState(
    private val clockMs: () -> Long = DEFAULT_CLOCK,
    private val coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS,
) {

    data class UndoEntry(
        val projectBefore: MemeProject,
        val command: MemeCommand,
        val atMs: Long,
        /** Gesture entries never coalesce with neighbours. */
        val fromGesture: Boolean,
        /** Global action sequence — orders undo against clip-list edits. */
        val seq: Long = 0L,
    )

    var project: MemeProject = MemeProject(mode = MemeMode.IMAGE)
        private set
    var selectedOverlayId: String? = null
        private set

    /**
     * Compose observation hook: bumps on every visible mutation (drag
     * frames, adds, undo, selection) — screens read it so the stage
     * redraws live even though the underlying state is plain Kotlin.
     */
    var revision by mutableIntStateOf(0)
        private set

    /** Restores a persisted project (MST-018 resume path). */
    fun restore(document: MemeProject) {
        project = MemeProjectContract.decode(MemeProjectContract.encode(document)) ?: project
        undoStack.clear()
        redoStack.clear()
        clipsStack.clear()
        clipsRedo.clear()
        gesture = null
        clearSelection()
        revision += 1
    }

    /**
     * Mode switch (M2 GIF): allowed when the project is empty — the editor
     * asks for confirmation otherwise (modes own different asset shapes).
     * Overlays survive a switch (mode-agnostic geometry) except IMAGE
     * layers, whose assets are video-session-bound and die with the mode.
     */
    fun switchMode(mode: MemeMode) {
        if (project.mode == mode) return
        project = project.copy(
            mode = mode,
            assets = emptyList(),
            overlays = project.overlays.filter { it.kind != MemeOverlayKind.IMAGE },
        )
        undoStack.clear()
        redoStack.clear()
        clipsStack.clear()
        clipsRedo.clear()
        gesture = null
        revision += 1
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty() || clipsStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty() || clipsRedo.isNotEmpty()
    val canAddOverlay: Boolean get() = project.overlays.size < MemeProjectContract.MAX_OVERLAYS
    val isEmpty: Boolean get() = project.isEmpty

    private val undoStack = ArrayDeque<UndoEntry>()
    private val redoStack = ArrayDeque<RedoEntry>()

    // ── Clip-list edits (split/delete/move/mute/trim/volume) ────────────
    // The session clip data (bytes/probes) lives in the screen; these
    // entries carry the wire state, interleaved with command entries by
    // [actionSeq] so ONE undo affordance steps back the newest action of
    // either kind.

    /** (original seq, projectBefore) per clip-list edit. */
    private val clipsStack = ArrayDeque<Pair<Long, MemeProject>>()
    /** (original seq, projectAfter) for redone clip-list edits. */
    private val clipsRedo = ArrayDeque<Pair<Long, MemeProject>>()
    private var actionSeq = 0L

    private fun nextSeq(): Long = ++actionSeq

    /**
     * Opens an undoable clip-list edit — call AFTER the edit's guards pass
     * and BEFORE the session list mutates (the screen then mirrors the
     * result through [syncClips]). Refused edits never call this, so the
     * stack holds no no-op steps.
     */
    fun beginClipsEdit() {
        clipsStack.addLast(nextSeq() to project)
        while (clipsStack.size > MAX_UNDO_ENTRIES) clipsStack.removeFirst()
        clipsRedo.clear()
        redoStack.clear()
    }

    // ── Assets (session-local ids; the screen owns the image refs) ──────

    /** Appends ids up to the mode cap; returns the ids actually added. */
    fun addAssets(ids: List<String>, kind: MemeMode = project.mode): List<String> {
        val cap = MemeProjectContract.maxAssets(project.mode)
        val room = cap - project.assets.size
        if (room <= 0) return emptyList()
        val existing = project.assets.map { it.id }.toSet()
        val accepted = ids.asSequence()
            .filter { it.isNotBlank() && it !in existing }
            .take(room)
            .toList()
        if (accepted.isNotEmpty()) {
            project = project.copy(assets = project.assets + accepted.map { id ->
                MemeAsset(id = id, kind = kind)
            })
            revision += 1
        }
        return accepted
    }

    // ── Selection ────────────────────────────────────────────────────────

    /** Hit-tests the normalized point; true when an overlay was selected. */
    fun selectAt(x: Float, y: Float): Boolean {
        val hit = MemeRules.hitTest(project, x, y)
        selectedOverlayId = hit?.id
        revision += 1
        return hit != null
    }

    /** Selects one overlay by id (Layers sheet); unknown ids deselect. */
    fun select(id: String) {
        selectedOverlayId = project.overlays.firstOrNull { it.id == id }?.id
        revision += 1
    }

    fun clearSelection() {
        if (selectedOverlayId != null) revision += 1
        selectedOverlayId = null
    }

    // ── Overlay mutations ────────────────────────────────────────────────

    /** Adds a default-placed overlay and selects it; null when at cap. */
    fun addOverlay(kind: MemeOverlayKind, text: String): String? {
        if (!canAddOverlay) return null
        val overlay = MemeRules.defaultOverlay(project, kind, text)
        commit(MemeCommand.AddOverlay(overlay))
        selectedOverlayId = overlay.id
        return overlay.id
    }

    /**
     * Image layer (video-mode source insert): a default-placed IMAGE
     * overlay bound to an asset id the screen just imported. Null at cap.
     */
    fun addImageOverlay(assetId: String): String? {
        if (!canAddOverlay) return null
        val overlay = MemeRules.defaultOverlay(project, MemeOverlayKind.IMAGE, "")
            .copy(assetId = assetId)
        commit(MemeCommand.AddOverlay(overlay))
        selectedOverlayId = overlay.id
        return overlay.id
    }

    fun removeOverlay(id: String) {
        if (project.overlays.none { it.id == id }) return
        commit(MemeCommand.RemoveOverlay(id))
        if (selectedOverlayId == id) clearSelection()
    }

    /** Source-media color grade (MST-043) — one undoable command. */
    fun setLook(lookId: String) {
        commit(MemeCommand.SetLook(lookId))
    }

    /**
     * Manual fine-tune over the look (prototype `create-edit` FX sliders)
     * — one undoable command; slider bursts coalesce via
     * [MemeRules.coalesce] like every other continuous control.
     */
    fun setAdjust(adjust: space.bitos.core.studio.MemeAdjust) {
        if (project.adjust == adjust) return
        commit(MemeCommand.SetAdjust(adjust))
    }

    /**
     * Classic meme captions (prototype "Meme" hot tool): TOP/BOTTOM pair
     * at the canonical positions with the classic outline look — landing
     * as ONE undo step for the pair. Returns the ids added (empty halves
     * are skipped; the overlay cap bounds the pair).
     */
    fun addMemeCaptions(top: String, bottom: String, fontSlot: MemeFontSlot): List<String> {
        val halves = listOf(
            top.trim().takeIf { it.isNotEmpty() }?.uppercase() to 0.16f,
            bottom.trim().takeIf { it.isNotEmpty() }?.uppercase() to 0.84f,
        ).mapNotNull { (text, y) -> text?.let { it to y } }
        if (halves.isEmpty()) return emptyList()
        if (!canAddOverlay) return emptyList()
        val before = project
        val added = ArrayList<String>()
        for ((text, y) in halves) {
            if (project.overlays.size >= MemeProjectContract.MAX_OVERLAYS) break
            val overlay = MemeRules.defaultOverlay(project, MemeOverlayKind.TEXT, text).copy(
                x = 0.5f,
                y = y,
                font = fontSlot,
                size = 64,
                outline = 3,
            )
            project = MemeRules.apply(project, MemeCommand.AddOverlay(overlay))
            added.add(overlay.id)
        }
        if (project != before) {
            selectedOverlayId = added.lastOrNull()
            revision += 1
            redoStack.clear()
            // Composite step: gesture-flavored so it never coalesces, and
            // undo restores `projectBefore` directly (the command field is
            // informational for replay tooling only).
            undoStack.addLast(
                UndoEntry(
                    projectBefore = before,
                    command = MemeCommand.AddOverlay(
                        MemeRules.defaultOverlay(before, MemeOverlayKind.TEXT, ""),
                    ),
                    atMs = clockMs(),
                    fromGesture = true,
                    seq = nextSeq(),
                ),
            )
            trimUndo()
        }
        return added
    }

    /** SFX cues (MST-041): schedule synth sounds at media time. */
    fun addSfxCue(sfx: String, atMs: Long) {
        val id = "c" + (project.sfxCues.size + 1) + "-" + (System.currentTimeMillis() % 1000)
        commit(MemeCommand.AddSfxCue(space.bitos.core.studio.MemeSfxCue(id, sfx, atMs)))
    }

    fun removeSfxCue(id: String) {
        if (project.sfxCues.none { it.id == id }) return
        commit(MemeCommand.RemoveSfxCue(id))
    }

    /** Trim window (video) — the export contract; undoable like any edit. */
    fun setTrim(startMs: Long, endMs: Long) {
        if (project.trimStartMs == startMs && project.trimEndMs == endMs) return
        commit(MemeCommand.SetTrim(startMs, endMs))
    }

    /**
     * M5 timeline sync: mirrors the session clip list into the project wire
     * (bounded + clamped by the contract; the first clip's window mirrors
     * into the legacy trim fields for old readers). Call after the screen
     * mutated the list — undoability comes from [beginClipsEdit] before it.
     */
    fun syncClips(clips: List<space.bitos.core.studio.MemeClip>) {
        if (project.mode != MemeMode.VIDEO) return
        val bounded = clips.take(MemeProjectContract.MAX_VIDEO_CLIPS)
        if (project.clips == bounded && project.trimStartMs == (bounded.firstOrNull()?.startMs ?: 0L) &&
            project.trimEndMs == (bounded.firstOrNull()?.endMs ?: 0L)
        ) {
            return
        }
        project = project.copy(
            clips = bounded,
            trimStartMs = bounded.firstOrNull()?.startMs ?: 0L,
            trimEndMs = bounded.firstOrNull()?.endMs ?: 0L,
        )
        revision += 1
    }

    /** Whole-clip playback rate (V2 suite Speed chip); undoable. */
    fun setSpeed(rate: Float) {
        val clamped = MemeProjectContract.clampSpeed(rate)
        if (project.speed == clamped) return
        commit(MemeCommand.SetSpeed(clamped))
    }

    // ── Pen drawing (V2 Draw chip) ──────────────────────────────────────

    /** Commits a finished stroke (clamped by the shared rule); true when it landed. */
    fun addStroke(colorIndex: Int, widthNorm: Float, points: List<Float>): Boolean {
        val stroke = space.bitos.core.studio.MemeStroke(
            id = "d" + (project.drawStrokes.size + 1) + "-" + (System.currentTimeMillis() % 1000),
            colorIndex = colorIndex,
            widthNorm = widthNorm,
            points = points,
        )
        val before = project
        commit(MemeCommand.AddStroke(stroke))
        return project != before
    }

    /** Removes the newest stroke (pen "undo stroke"). */
    fun removeLastStroke() {
        project.drawStrokes.lastOrNull()?.let { commit(MemeCommand.RemoveStroke(it.id)) }
    }

    fun clearDrawing() {
        if (project.drawStrokes.isEmpty()) return
        commit(MemeCommand.ClearDrawing())
    }

    /**
     * Style/field edit (text sheet). Bursts within the coalesce window
     * merge into a single undo step (slider drags, palette taps).
     */
    /** Accessible manipulation (MUX-03): explicit nudge / resize / rotate
     *  for the selection — one undoable command, no gestures required. */
    fun nudgeOverlay(
        id: String,
        dx: Float = 0f,
        dy: Float = 0f,
        dRot: Float = 0f,
        scaleFactor: Float = 1f,
    ) {
        val overlay = project.overlays.firstOrNull { it.id == id } ?: return
        commit(
            MemeCommand.UpdateOverlay(
                id = id,
                x = MemeRules.clampCoordinate(overlay.x + dx).takeIf { dx != 0f },
                y = MemeRules.clampCoordinate(overlay.y + dy).takeIf { dy != 0f },
                rotationDeg = MemeRules.clampRotation(overlay.rotationDeg + dRot).takeIf { dRot != 0f },
                scale = MemeRules.clampScale(overlay.scale * scaleFactor).takeIf { scaleFactor != 1f },
            ),
        )
    }

    fun updateStyle(
        id: String,
        text: String? = null,
        font: MemeFontSlot? = null,
        size: Int? = null,
        colorIndex: Int? = null,
        outline: Int? = null,
        shadow: Boolean? = null,
        fx: space.bitos.core.studio.MemeOverlayFx? = null,
        clearFx: Boolean = false,
        startMs: Long? = null,
        endMs: Long? = null,
        clearEndMs: Boolean = false,
    ) {
        if (project.overlays.none { it.id == id }) return
        commit(
            MemeCommand.UpdateOverlay(
                id = id,
                text = text,
                font = font,
                size = size,
                colorIndex = colorIndex,
                outline = outline,
                shadow = shadow,
                fx = fx,
                clearFx = clearFx,
                startMs = startMs,
                endMs = endMs,
                clearEndMs = clearEndMs,
            ),
        )
    }

    // ── Gesture stream (drag / pinch / twist on the selection) ──────────

    private var gesture: GestureSnapshot? = null

    private data class GestureSnapshot(
        val id: String,
        val projectBefore: MemeProject,
        val x: Float,
        val y: Float,
        val scale: Float,
        val rotationDeg: Float,
    )

    fun beginGesture() {
        val selected = project.overlays.firstOrNull { it.id == selectedOverlayId } ?: return
        gesture = GestureSnapshot(
            id = selected.id,
            projectBefore = project,
            x = selected.x,
            y = selected.y,
            scale = selected.scale,
            rotationDeg = selected.rotationDeg,
        )
    }

    /** Live geometry update; applied immediately, pushed once at the end. */
    fun gestureUpdate(x: Float? = null, y: Float? = null, scale: Float? = null, rotationDeg: Float? = null) {
        val id = selectedOverlayId ?: return
        project = MemeRules.apply(
            project,
            MemeCommand.UpdateOverlay(id = id, x = x, y = y, scale = scale, rotationDeg = rotationDeg),
        )
        revision += 1
    }

    fun endGesture() {
        val start = gesture ?: return
        gesture = null
        val current = project.overlays.firstOrNull { it.id == start.id } ?: return
        // Only a changed gesture becomes an undo step — a tap that opened
        // and closed the stream must not push a no-op entry.
        val command = MemeCommand.UpdateOverlay(
            id = start.id,
            x = current.x.takeIf { it != start.x },
            y = current.y.takeIf { it != start.y },
            scale = current.scale.takeIf { it != start.scale },
            rotationDeg = current.rotationDeg.takeIf { it != start.rotationDeg },
        )
        val changed = command.x != null || command.y != null ||
            command.scale != null || command.rotationDeg != null
        if (changed) {
            undoStack.addLast(
                UndoEntry(
                    projectBefore = start.projectBefore,
                    command = command,
                    atMs = clockMs(),
                    fromGesture = true,
                    seq = nextSeq(),
                ),
            )
            redoStack.clear()
            clipsRedo.clear()
            trimUndo()
            revision += 1
        }
    }

    /** True when a gesture is open — the screen cancels back-navigation. */
    val gestureActive: Boolean get() = gesture != null

    /** Abandons an open gesture stream (system back during a drag). */
    fun cancelGesture() {
        gesture?.let { start ->
            project = start.projectBefore
            if (project.overlays.none { it.id == selectedOverlayId }) clearSelection()
            revision += 1
        }
        gesture = null
    }

    // ── Undo / redo ──────────────────────────────────────────────────────

    /** Redo branch: the undone entry plus the project state it led to. */
    private data class RedoEntry(val entry: UndoEntry, val projectAfter: MemeProject)

    fun undo(): Boolean {
        // Step back the NEWEST action of either kind.
        val cmdSeq = undoStack.lastOrNull()?.seq ?: -1L
        val clipSeq = clipsStack.lastOrNull()?.first ?: -1L
        if (clipSeq > cmdSeq) {
            val (seq, before) = clipsStack.removeLast()
            clipsRedo.addLast(seq to project)
            trimRedo()
            project = before
            if (project.overlays.none { it.id == selectedOverlayId }) clearSelection()
            revision += 1
            return true
        }
        val entry = undoStack.removeLastOrNull() ?: return false
        // Clip edits live in the session list (syncClips is not a command)
        // — a restored snapshot must not resurrect an older clip list, or
        // the next autosave silently drops splits/deletes while the strip
        // still shows them.
        val liveClips = project.clips
        val liveTrimStart = project.trimStartMs
        val liveTrimEnd = project.trimEndMs
        redoStack.addLast(RedoEntry(entry, project))
        trimRedo()
        project = entry.projectBefore.copy(
            clips = liveClips,
            trimStartMs = liveTrimStart,
            trimEndMs = liveTrimEnd,
        )
        if (project.overlays.none { it.id == selectedOverlayId }) clearSelection()
        revision += 1
        return true
    }

    /** Re-applies the last undone state; any new edit clears the branch. */
    fun redo(): Boolean {
        val cmdSeq = redoStack.lastOrNull()?.entry?.seq ?: -1L
        val clipSeq = clipsRedo.lastOrNull()?.first ?: -1L
        // Redo replays ASCENDING (the oldest undone action first): each
        // stack pops its own subsequence in order, so the next global
        // action is the top with the SMALLER seq.
        if (clipSeq != -1L && (cmdSeq == -1L || clipSeq < cmdSeq)) {
            val (seq, after) = clipsRedo.removeLast()
            clipsStack.addLast(seq to project)
            trimUndo()
            project = after
            if (project.overlays.none { it.id == selectedOverlayId }) clearSelection()
            revision += 1
            return true
        }
        val redoEntry = redoStack.removeLastOrNull() ?: return false
        val liveClips = project.clips
        val liveTrimStart = project.trimStartMs
        val liveTrimEnd = project.trimEndMs
        // The current project equals entry.projectBefore again, so the
        // original undo entry goes back untouched (coalescing survives).
        undoStack.addLast(redoEntry.entry)
        project = redoEntry.projectAfter.copy(
            clips = liveClips,
            trimStartMs = liveTrimStart,
            trimEndMs = liveTrimEnd,
        )
        if (project.overlays.none { it.id == selectedOverlayId }) clearSelection()
        revision += 1
        return true
    }

    private fun commit(command: MemeCommand) {
        val before = project
        project = MemeRules.apply(project, command)
        if (project == before) return
        revision += 1
        redoStack.clear()
        clipsRedo.clear()
        val now = clockMs()
        val last = undoStack.lastOrNull()
        if (last != null && !last.fromGesture && now - last.atMs <= coalesceWindowMs) {
            MemeRules.coalesce(last.command, command)?.let { merged ->
                undoStack.removeLast()
                undoStack.addLast(last.copy(command = merged, atMs = now))
                return
            }
        }
        undoStack.addLast(
            UndoEntry(projectBefore = before, command = command, atMs = now, fromGesture = false, seq = nextSeq()),
        )
        trimUndo()
    }

    private fun trimUndo() {
        while (undoStack.size > MAX_UNDO_ENTRIES) undoStack.removeFirst()
        while (clipsStack.size > MAX_UNDO_ENTRIES) clipsStack.removeFirst()
    }

    private fun trimRedo() {
        while (redoStack.size > MAX_UNDO_ENTRIES) redoStack.removeFirst()
        while (clipsRedo.size > MAX_UNDO_ENTRIES) clipsRedo.removeFirst()
    }

    /** Test seam: committed (non-coalesced) step count. */
    internal val undoDepth: Int get() = undoStack.size

    companion object {
        const val DEFAULT_COALESCE_WINDOW_MS = 300L
        const val MAX_UNDO_ENTRIES = 60
        private val DEFAULT_CLOCK: () -> Long = { System.currentTimeMillis() }
    }
}
