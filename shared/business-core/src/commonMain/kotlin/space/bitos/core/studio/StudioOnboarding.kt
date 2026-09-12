package space.bitos.core.studio

/**
 * Meme Studio onboarding + empty-state copy (plan
 * `meme-studio-ux-redesign-plan.md`, MSU-030..033).
 *
 * Why shared: the shipped editor gave guidance only on *blank* sessions
 * (`StageHintChip`), so the common "I picked a photo" path had none, and
 * each platform wrote its own hint strings. The copy, the coach steps and
 * the eligibility rules live here so Compose and SwiftUI render the same
 * guidance at the same moments — and so the rules are testable without a
 * UI.
 *
 * Pure: no UI, no platform types, no I/O. The device-local "seen" flag is
 * persisted by the native adapter under [COACH_KEY].
 */
object StudioOnboarding {

    // v1.
    const val SCHEMA_VERSION = 1

    /**
     * Device-local persistence key for "the coach has been shown". Stored
     * per platform (SharedPreferences / UserDefaults) — the STRING is
     * shared so the two apps describe the same fact.
     */
    const val COACH_KEY = "studio_coach_seen"

    // ── Coach steps ─────────────────────────────────────────────────────

    /**
     * One first-run coach step. [anchor] tells the platform what to point
     * at (`stage`, `tools`, `next`); [iconToken] is a semantic icon name
     * the native facades resolve.
     */
    data class CoachStep(
        val id: String,
        val anchor: String,
        val iconToken: String,
        val title: String,
        val body: String,
    )

    /**
     * The first-run walkthrough: what can I touch → what can I do → how do
     * I finish. Deliberately three steps — a longer tour is skipped, not
     * read.
     */
    val COACH_STEPS: List<CoachStep> = listOf(
        CoachStep(
            id = "stage",
            anchor = "stage",
            iconToken = "move",
            title = "This is your canvas",
            body = "Drag to move, pinch to scale and twist to rotate. Tap an item to select it.",
        ),
        CoachStep(
            id = "tools",
            anchor = "tools",
            iconToken = "look",
            title = "Five tools, every mode",
            body = "Media · Text · Sticker · Sound · Look. Everything else lives under More.",
        ),
        CoachStep(
            id = "next",
            anchor = "next",
            iconToken = "send",
            title = "Next publishes",
            body = "Next posts to Nostr. Export saves a rendered file to this device.",
        ),
    )

    const val SKIP_LABEL = "Skip"
    const val DONE_LABEL = "Got it"

    /**
     * Session context that decides whether the coach runs.
     *
     * The coach is for a creator who arrived with nothing and has never
     * seen it. It must NEVER appear on a resumed draft (they already know
     * the screen) or on any handoff (remix / sound seed / camera takes),
     * where the creator is mid-task and a tour would be in the way.
     */
    data class Context(
        val hasSeenCoach: Boolean,
        val isResume: Boolean = false,
        val isRemix: Boolean = false,
        val isSoundSeed: Boolean = false,
        val isTemplateSeed: Boolean = false,
        val isCameraHandoff: Boolean = false,
    )

    /**
     * Whether the first-run coach should run. Any handoff or a previous
     * run suppresses it; only a genuinely fresh, self-started session on a
     * device that has not seen it qualifies.
     */
    fun shouldRunCoach(context: Context): Boolean {
        if (context.hasSeenCoach) return false
        return !(context.isResume || context.isRemix || context.isSoundSeed ||
            context.isTemplateSeed || context.isCameraHandoff)
    }

    // ── Empty states ────────────────────────────────────────────────────

    /**
     * Guiding empty state for a mode with no media yet. [primaryLabel] is
     * the main action; the optional secondary/tertiary are the
     * alternatives the shipped CTA already offered (blank canvas, GIF
     * browse).
     */
    data class EmptyState(
        val title: String,
        val body: String,
        val iconToken: String,
        val primaryLabel: String,
        val secondaryLabel: String? = null,
        val tertiaryLabel: String? = null,
    )

    /**
     * Empty-state copy for [mode]. An unknown mode resolves to the IMAGE
     * copy — the same lenient fallback the rest of the studio contract
     * uses, so a corrupt wire can never blank the screen.
     */
    fun emptyStateFor(mode: MemeMode?): EmptyState = when (mode ?: MemeMode.IMAGE) {
        MemeMode.IMAGE -> EmptyState(
            title = "Start with an image",
            body = "Tap Media to pick a photo, or start from a blank canvas.",
            iconToken = "media",
            primaryLabel = "Pick an image",
            secondaryLabel = "Start blank canvas",
        )
        MemeMode.VIDEO -> EmptyState(
            title = "Start with a clip",
            body = "Tap Media to pick a video, or create a blank canvas with sound and text.",
            iconToken = "media",
            primaryLabel = "Pick a clip",
            secondaryLabel = "Start blank video",
        )
        MemeMode.GIF -> EmptyState(
            title = "Add frames",
            body = "Pick frames or a GIF — or start a blank loop and animate your own text.",
            iconToken = "gifs",
            primaryLabel = "Pick frames",
            secondaryLabel = "Start blank GIF",
            tertiaryLabel = "Browse GIFs",
        )
    }

    /** The line shown once media exists but nothing is on it yet. */
    const val ADD_SOMETHING_HINT = "Add text, a sticker or a look — everything autosaves."

    /**
     * Undo/redo discoverability (MSU-033): shown as a one-time notice the
     * first time an edit is undone, naming redo so the pair is learnt
     * together.
     */
    const val UNDO_HINT = "Undone — redo is beside undo in the top bar."

    /** Shown when undo is tapped with an empty history (never a dead tap). */
    const val NOTHING_TO_UNDO = "Nothing to undo"

    /** Shown when redo is tapped with an empty redo stack. */
    const val NOTHING_TO_REDO = "Nothing to redo"

    // ── Publish vs Export (MSU-050..052) ────────────────────────────────

    /**
     * The one-line mental model. The shipped editor showed `Next` and
     * `Export` with equal weight and no explanation, so the two "I'm done"
     * verbs were indistinguishable. [PUBLISH_EXPLAINER] is shown once, the
     * first time the creator reaches the publish entry.
     */
    const val PUBLISH_EXPLAINER =
        "Publish posts to Nostr · Export saves a file to this device."

    /** The single primary action label (publish flow entry). */
    const val PRIMARY_ACTION = "Next"

    /** The demoted secondary action (save a rendered file). */
    const val EXPORT_ACTION = "Save a copy"

    /** Review-screen step titles, in order (MSU-051). */
    data class ReviewStep(val id: String, val title: String)

    /**
     * The review order shown to the creator. Mirrors the actual screen
     * order so the copy can never disagree with the layout.
     */
    val REVIEW_STEPS: List<ReviewStep> = listOf(
        ReviewStep("preview", "Preview"),
        ReviewStep("caption", "Caption"),
        ReviewStep("tags", "Tags"),
        ReviewStep("safety", "Safety"),
        ReviewStep("publish", "Publish"),
    )

    /**
     * Post-publish result card (MSU-052): the success state offers follow-on
     * actions instead of a bare "Done".
     */
    const val POSTED_LABEL = "Posted"
    const val VIEW_LABEL = "View"
    const val SHARE_LABEL = "Share"
    const val MAKE_ANOTHER_LABEL = "Make another"

    /** Reassurance line: the protocol order is not negotiable. */
    const val VERIFY_BEFORE_SIGN =
        "Media uploads and hash-verifies before anything is signed."
}
