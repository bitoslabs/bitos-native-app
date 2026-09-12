package space.bitos.core.studio

/**
 * Meme Studio feedback closure (plan `meme-studio-ux-redesign-plan.md`,
 * MSU-042..043).
 *
 * Two shipped defects this closes:
 *
 * 1. **Progress was scattered.** Clip import, export render, the GIF
 *    ladder, batch rendering and publish each owned their own overlay with
 *    their own copy, so an operator saw five different "busy" treatments
 *    and could not tell which were determinate. [ProgressSurface] names
 *    every one, with a single title/body and an honest
 *    determinate/indeterminate flag; both platforms render ONE overlay
 *    from it.
 *
 * 2. **Confirms were unaudited.** The shipped editor mixed reversible
 *    actions (delete overlay) with irreversible ones (switch mode, discard
 *    draft) and asked for confirmation inconsistently. [ConfirmRule]
 *    classifies every destructive surface: irreversible actions MUST
 *    confirm, reversible ones MUST post the Undo notice instead (never a
 *    dialog). `EditorConfirmsTest` pins the classification so a future
 *    surface cannot silently ship the wrong treatment.
 *
 * Pure and UI-free. Copy is bounded; unknown ids degrade to the neutral
 * surface/rule — a corrupt wire can never blank a busy screen.
 */
object StudioProgress {

    // v1.
    const val SCHEMA_VERSION = 1

    /**
     * One busy surface in the editor. [determinate] is true only where the
     * pipeline reports real counts or a real fraction (never a simulated
     * bar); [cancelable] is true only where cancelling leaves the project
     * intact.
     */
    data class ProgressSurface(
        val id: String,
        val title: String,
        val body: String,
        val determinate: Boolean,
    )

    /** Clip probing / staging during import (camera takes, picker inserts). */
    val IMPORT_CLIPS = ProgressSurface(
        id = "import-clips",
        title = "Preparing clips…",
        body = "Probing and staging your source clips.",
        determinate = true,
    )

    /** The export render (PNG / MP4 / GIF) before the device save. */
    val EXPORT_RENDER = ProgressSurface(
        id = "export-render",
        title = "Rendering your meme…",
        body = "This can take a moment for long clips — keep the screen open.",
        determinate = false,
    )

    /** The GIF size ladder re-sampling a large export down to fit. */
    val GIF_LADDER = ProgressSurface(
        id = "gif-ladder",
        title = "Fitting the GIF…",
        body = "Re-sampling frames to fit the size limit.",
        determinate = true,
    )

    /** Batch variant rendering on the Create hub. */
    val BATCH_RENDER = ProgressSurface(
        id = "batch-render",
        title = "Rendering variants…",
        body = "Every approved variant renders before it publishes.",
        determinate = true,
    )

    /** The publish machine's render/upload/sign/relay stages. */
    val PUBLISH = ProgressSurface(
        id = "publish",
        title = "Publishing…",
        body = "Media uploads and hash-verifies before anything is signed.",
        determinate = true,
    )

    /** All surfaces, in a stable order. */
    val ALL: List<ProgressSurface> =
        listOf(IMPORT_CLIPS, EXPORT_RENDER, GIF_LADDER, BATCH_RENDER, PUBLISH)

    /** Resolve a surface by id; unknown ids return the export surface. */
    fun surfaceFor(id: String?): ProgressSurface =
        ALL.firstOrNull { it.id == id } ?: EXPORT_RENDER

    /**
     * The determinate fraction for a surface, clamped to `0..1`. A surface
     * that is not [ProgressSurface.determinate] always returns `null` so a
     * caller can never render a simulated bar.
     */
    fun fraction(surface: ProgressSurface, done: Int, total: Int): Float? {
        if (!surface.determinate) return null
        if (total <= 0) return 0f
        return (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    // ── MSU-043: confirm classification ─────────────────────────────────

    /**
     * How a destructive surface must be treated:
     *  • [MODE] — irreversible: MUST show a confirm dialog.
     *  • [REVERSIBLE] — MUST post the Undo notice (never a dialog).
     */
    enum class ConfirmMode { MODE, REVERSIBLE }

    /** One audited destructive surface. */
    data class ConfirmRule(
        val id: String,
        val mode: ConfirmMode,
        val title: String,
        val body: String,
    )

    /**
     * The audit. Every destructive action the editor exposes is listed:
     *  • Reversible edits (overlay / clip / layer delete, drawing undo)
     *    rely on the notice host's Undo — a dialog would be friction.
     *  • Irreversible clears (mode switch drops media, draft delete,
     *    camera takes discard) MUST confirm first.
     */
    val CONFIRMS: List<ConfirmRule> = listOf(
        ConfirmRule(
            id = "delete-overlay",
            mode = ConfirmMode.REVERSIBLE,
            title = "Delete overlay",
            body = "Removed — undo is in the notice.",
        ),
        ConfirmRule(
            id = "delete-clip",
            mode = ConfirmMode.REVERSIBLE,
            title = "Delete clip",
            body = "Removed — undo is in the notice.",
        ),
        ConfirmRule(
            id = "delete-layer",
            mode = ConfirmMode.REVERSIBLE,
            title = "Delete layer",
            body = "Removed — undo is in the notice.",
        ),
        ConfirmRule(
            id = "mode-switch",
            mode = ConfirmMode.MODE,
            title = "Start a new project?",
            body = "Switching clears the current media (overlays stay). Continue?",
        ),
        ConfirmRule(
            id = "discard-draft",
            mode = ConfirmMode.MODE,
            title = "Could not save the draft",
            body = "Your edits are still open. Retry the save to keep them, or delete the draft deliberately.",
        ),
        ConfirmRule(
            id = "discard-takes",
            mode = ConfirmMode.MODE,
            title = "Discard takes?",
            body = "Recorded takes are removed when you leave.",
        ),
    )

    /** Resolve a rule by id; unknown ids default to the safer MODE. */
    fun confirmFor(id: String?): ConfirmRule =
        CONFIRMS.firstOrNull { it.id == id }
            ?: ConfirmRule("unknown", ConfirmMode.MODE, "", "")

    /** True when the surface must show a confirm dialog. */
    fun requiresDialog(id: String?): Boolean = confirmFor(id).mode == ConfirmMode.MODE

    /** True when the surface must use the Undo notice instead of a dialog. */
    fun requiresUndoNotice(id: String?): Boolean = confirmFor(id).mode == ConfirmMode.REVERSIBLE
}
