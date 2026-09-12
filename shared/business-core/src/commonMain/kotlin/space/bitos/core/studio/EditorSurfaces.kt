package space.bitos.core.studio

/**
 * Editor presentation-surface state machine (plan MSU-003).
 *
 * The shipped editors tracked "what is on top of the stage" with a loose
 * cluster of booleans and nullable ids — `showExport`, `activePanel`,
 * `editingOverlayId`, `suiteMode`, `showLayers`, `showTrim`, plus the
 * Android `BackHandler` `when` ladder and the iOS sheet-detent stack. Back
 * behavior therefore had to be re-derived at every call site and drifted
 * between platforms (Android closed overlays then the editor; iOS relied
 * on SwiftUI's sheet dismissal and could drop straight out of the editor).
 *
 * This type makes the surface an explicit, tested state so both platforms
 * answer "what does Back do?" from one place:
 *
 *  - [back] returns the next state, or `null` when Back must leave the
 *    editor (nothing was open). The caller exits only on `null`.
 *  - Surfaces are a strict stack of at most two levels: an optional
 *    [Surface.Sheet] _over_ an optional [Surface.Overlay]. Back unwinds
 *    top-down, which is exactly the CapCut/IG habit users already have.
 *
 * Pure and UI-free: the platform renders whatever [state] names.
 */
object EditorSurfaces {

    /** Which surface currently owns input, if any. */
    enum class Kind {
        /** Nothing on top — the stage and tool bar are live. */
        NONE,

        /** A light inline panel under the tool bar (e.g. pen controls). */
        INLINE_PANEL,

        /** A modal bottom sheet (media, look, sound, layers, export…). */
        SHEET,

        /** On-canvas text compose (keyboard up, typing dock owns the bottom). */
        COMPOSE,

        /** The timeline workspace (expert tier). */
        TIMELINE,

        /** The full-screen publish review flow. */
        REVIEW,
    }

    /**
     * A named surface. [id] is the panel/sheet token
     * (`"media"`, `"look"`, `"sound"`, `"layers"`, `"export"`, `"clip"`,
     * `"sfx"`, `"canvas"`, `"batch"`, …) — an opaque, stable string the
     * platform maps to its own content. [overlayId] is set only for
     * [Kind.COMPOSE].
     */
    data class Surface(
        val kind: Kind,
        val id: String = "",
        val overlayId: String? = null,
    ) {
        companion object {
            val None = Surface(Kind.NONE)

            fun sheet(id: String) = Surface(Kind.SHEET, id)

            fun inlinePanel(id: String) = Surface(Kind.INLINE_PANEL, id)

            fun compose(overlayId: String) = Surface(Kind.COMPOSE, overlayId = overlayId)

            val TimeLine = Surface(Kind.TIMELINE)

            val Review = Surface(Kind.REVIEW)
        }
    }

    /**
     * Editor surface state: an optional [overlay] with an optional [sheet]
     * stacked above it. A sheet never replaces the overlay — closing the
     * sheet restores exactly the prior surface (the repo's documented
     * "back dismisses the sheet, not the editor" rule).
     */
    data class State(
        val overlay: Surface? = null,
        val sheet: Surface? = null,
    ) {
        /** The surface currently receiving input (sheet wins). */
        val top: Surface
            get() = sheet ?: overlay ?: Surface.None

        val isBusyLayerVisible: Boolean
            get() = top.kind != Kind.NONE

        companion object {
            val Empty = State()
        }
    }

    /** Open a sheet above whatever surface is active. */
    fun openSheet(state: State, id: String): State =
        state.copy(sheet = Surface.sheet(id))

    /** Open the timeline workspace (a sheet over the current overlay). */
    fun openTimeline(state: State): State =
        state.copy(sheet = Surface.TimeLine)

    /** Open the publish review flow. */
    fun openReview(state: State): State =
        state.copy(sheet = Surface.Review)

    /** Open an inline panel (does not stack above a sheet). */
    fun openInlinePanel(state: State, id: String): State =
        state.copy(overlay = Surface.inlinePanel(id))

    /** Enter on-canvas compose for [overlayId]. */
    fun openCompose(state: State, overlayId: String): State =
        state.copy(sheet = null, overlay = Surface.compose(overlayId))

    /**
     * Dismiss the topmost surface.
     *
     * @return the next state, or `null` when nothing was open — the caller
     *   must then leave the editor (this is the ONLY place that decision is
     *   made).
     */
    fun back(state: State): State? = when {
        state.sheet != null -> state.copy(sheet = null)
        state.overlay != null -> state.copy(overlay = null)
        else -> null
    }

    /**
     * Whether [state] is in a configuration the platform should treat as
     * "an editor exit must confirm" — i.e. the user is mid-edit and a bare
     * Back/✕ would discard the surface they are looking at. The editor
     * still owns the *content* decision (unsaved work); this only reports
     * that a surface is open.
     */
    fun hasOpenSurface(state: State): Boolean = state.top.kind != Kind.NONE

    /**
     * A stable machine token for the current surface, for logging and for
     * Android's `BackHandler`/`rememberSaveable` keys:
     * `"none"`, `"sheet:look"`, `"compose:<overlayId>"`, `"timeline"`, …
     */
    fun tokenOf(state: State): String {
        val top = state.top
        return when (top.kind) {
            Kind.NONE -> "none"
            Kind.SHEET -> "sheet:${top.id}"
            Kind.INLINE_PANEL -> "panel:${top.id}"
            Kind.COMPOSE -> "compose:${top.overlayId.orEmpty()}"
            Kind.TIMELINE -> "timeline"
            Kind.REVIEW -> "review"
        }
    }
}
