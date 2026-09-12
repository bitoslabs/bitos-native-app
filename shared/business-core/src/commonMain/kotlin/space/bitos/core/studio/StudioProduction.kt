package space.bitos.core.studio

/**
 * Meme Studio mass-production + operator copy (plan
 * `meme-studio-ux-redesign-plan.md`, MSU-060..063).
 *
 * Why shared: mass production was bolted onto the studio — the batch entry
 * was a row inside the Export sheet, the queue lived only on the Create
 * hub, and each platform wrote its own operator strings (or none). This
 * object is the single source for the batch-base copy, the in-editor batch
 * status strip wording, the template-first batch action, and the operator
 * keyboard reference both platforms render under More → Shortcuts.
 *
 * Pure and UI-free: [SHORTCUTS] names platform-neutral key tokens
 * (`mod`, `shift`, `alt`, `space`, arrows, …); the native layer renders the
 * platform glyphs (`⌘` vs `Ctrl`) and binds the events. The RULE lives here
 * so a shortcut can never be documented on one platform and missing on the
 * other.
 */
object StudioProduction {

    // v1.
    const val SCHEMA_VERSION = 1

    const val MAX_TEMPLATE_BATCH = 12

    // ── MSU-060: use as batch base ──────────────────────────────────────

    /** The action label on the More → Batch row. */
    const val BATCH_BASE_ACTION = "Use as batch base"

    /**
     * One-line explainer shown with the action so a creator knows what a
     * batch base is before tapping: the LAYOUT is frozen, the caption is
     * what varies.
     */
    const val BATCH_BASE_EXPLAINER =
        "Freezes this design as a reusable batch base — every variant varies the caption, not the layout."

    /** Toast after the design is handed to mass production. */
    const val BATCH_SEEDED_MESSAGE =
        "Batch seeded from this design — caption slots are ready in the queue."

    /** The queue affordance (tap opens the batch queue in one step). */
    const val BATCH_QUEUE_LINK = "View queue"

    // ── MSU-061: in-editor batch status strip ───────────────────────────

    /**
     * The status strip shown inside the editor while a batch references
     * this project, e.g. `"3 of 8 rendered · View queue"`.
     *
     * @return the strip text, or `null` when no strip should show (no
     *         batch, or a batch with no variants). Never throws on a
     *         hostile count — [rendered] is clamped into `0..total`.
     */
    fun batchStrip(rendered: Int, total: Int): String? {
        if (total <= 0) return null
        val done = rendered.coerceIn(0, total)
        return "$done of $total rendered · $BATCH_QUEUE_LINK"
    }

    // ── MSU-062: template-first batch ───────────────────────────────────

    /** The action label on the template rail ("Make 3 variants"). */
    fun templateBatchAction(count: Int): String {
        val bounded = count.coerceIn(1, MAX_TEMPLATE_BATCH)
        return "Make $bounded variant${if (bounded == 1) "" else "s"}"
    }

    /** Explainer under the template-rail action. */
    const val TEMPLATE_BATCH_EXPLAINER =
        "Seeds a batch from this template — one caption slot per variant, editable in the queue."

    /** The default variant count a template batch seeds. */
    const val TEMPLATE_BATCH_COUNT = 3

    // ── MSU-063: operator controls reference ────────────────────────────
    //
    // Revised for touch-first phones: the shipped sheet listed keyboard
    // keys only, which a phone with no attached keyboard could read but
    // never use. Every action now names its **on-screen (touch)**
    // affordance FIRST and the optional hardware key SECOND, so the
    // reference is actionable on every device and the keyboard is an
    // accelerator, never a requirement.

    /**
     * One documented editor control.
     *
     * [touch] is the always-available on-screen affordance (the honest
     * answer to "how do I do this on my phone?"). [keys] is the optional
     * hardware accelerator as a platform-neutral token list joined by `+`
     * (`mod+shift+z`, `alt+arrowup`, `space`); `null` when no key is bound.
     */
    data class Control(
        val id: String,
        val label: String,
        val touch: String,
        val keys: String?,
    )

    /**
     * The operator reference rendered under More → Controls and bound by
     * the native key handlers. `mod` = ⌘ on iOS, Ctrl on Android/desktop.
     * Touch affordances are the source of truth; keys mirror them.
     */
    val CONTROLS: List<Control> = listOf(
        Control("undo", "Undo", "Top-bar ↶", "mod+z"),
        Control("redo", "Redo", "Top-bar ↷", "mod+shift+z"),
        Control("play-pause", "Play / pause", "Timeline ▷", "space"),
        Control("prev", "Select previous", "Tap a lane or layer", "alt+arrowup"),
        Control("next", "Select next", "Tap a lane or layer", "alt+arrowdown"),
        Control("export", "Save a copy", "Header ⤓", "mod+e"),
        Control("publish", "Review & publish", "Header Next", "mod+enter"),
        Control("timeline", "Timeline workspace", "More ▸ Timeline", "mod+t"),
        Control("delete", "Delete selection", "Select, then Delete", "backspace"),
    )

    /**
     * Back-compat view of [CONTROLS] for the keyboard-only code paths and
     * tests. Only rows with a bound key appear.
     */
    data class Shortcut(val id: String, val keys: String, val label: String)

    val SHORTCUTS: List<Shortcut> = CONTROLS.mapNotNull { control ->
        control.keys?.let { Shortcut(control.id, it, control.label) }
    }

    /** Human title for the controls reference sheet. */
    const val SHORTCUTS_TITLE = "Controls"

    /**
     * Section header for the touch rows — always shown, on every device.
     * The touch affordance is the primary answer.
     */
    const val TOUCH_SECTION_TITLE = "On screen"

    /**
     * Section header for the keyboard rows. Hidden entirely when no
     * hardware keyboard is attached, so a bare phone never sees keys it
     * cannot press.
     */
    const val KEYBOARD_SECTION_TITLE = "Keyboard (optional)"

    /**
     * Shown in place of the [KEYBOARD_SECTION_TITLE] section when no
     * hardware keyboard is attached — an inviting hint, not a dead list.
     */
    const val KEYBOARD_ABSENT_HINT =
        "Connect a keyboard for faster editing — every control here is also on screen."

    /**
     * Retained for the keyboard section's footnote when a keyboard IS
     * present.
     */
    const val SHORTCUTS_HINT =
        "Works with a hardware or desktop-class keyboard; touch equivalents stay on screen."
}
