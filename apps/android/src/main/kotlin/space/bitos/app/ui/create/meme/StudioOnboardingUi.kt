package space.bitos.app.ui.create.meme

import android.content.Context
import org.json.JSONObject
import space.bitos.core.bridge.BusinessCoreBridge

/**
 * Studio onboarding adapter (MSU-030..033): the device-local "coach seen"
 * flag plus tolerant decoders for the shared coach/empty-state copy.
 *
 * Kept out of `MemeEditorScreen` (SRP): this file owns persistence and
 * decoding, the screen owns presentation. The copy and the eligibility
 * RULES live in the shared core (`StudioOnboarding`) — this only carries
 * them across the platform boundary.
 */
object StudioCoachPrefs {
    private const val PREFS = "bitos_studio"
    private const val KEY_COACH_DONE = "studio_coach_seen"

    fun hasSeenCoach(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_COACH_DONE, false)

    fun markCoachSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COACH_DONE, true).apply()
    }
}

/** One decoded coach step (shared `StudioOnboarding.CoachStep`). */
data class StudioCoachStep(
    val id: String,
    val anchor: String,
    val icon: String,
    val title: String,
    val body: String,
)

/** Decoded coach plan: whether to run plus the shared steps + labels. */
data class StudioCoachPlan(
    val run: Boolean,
    val skipLabel: String,
    val doneLabel: String,
    val steps: List<StudioCoachStep>,
) {
    companion object {
        private val NONE = StudioCoachPlan(false, "Skip", "Got it", emptyList())

        /**
         * Decodes the bridge seam. Junk JSON degrades to "do not run" —
         * guidance must never crash or block the editor.
         */
        fun decode(json: String): StudioCoachPlan = runCatching {
            val root = JSONObject(json)
            val steps = root.optJSONArray("steps")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val row = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = row.optString("id").ifBlank { return@mapNotNull null }
                    StudioCoachStep(
                        id = id,
                        anchor = row.optString("anchor"),
                        icon = row.optString("icon"),
                        title = row.optString("title"),
                        body = row.optString("body"),
                    )
                }
            } ?: emptyList()
            StudioCoachPlan(
                run = root.optBoolean("run"),
                skipLabel = root.optString("skipLabel", "Skip"),
                doneLabel = root.optString("doneLabel", "Got it"),
                steps = steps,
            )
        }.getOrDefault(NONE)

        /** Runs the shared eligibility rules through the bridge. */
        fun query(
            hasSeenCoach: Boolean,
            isResume: Boolean,
            isRemix: Boolean,
            isSoundSeed: Boolean,
            isTemplateSeed: Boolean,
            isCameraHandoff: Boolean,
        ): StudioCoachPlan = runCatching {
            decode(
                BusinessCoreBridge().memeCoachPlan(
                    hasSeenCoach = hasSeenCoach,
                    isResume = isResume,
                    isRemix = isRemix,
                    isSoundSeed = isSoundSeed,
                    isTemplateSeed = isTemplateSeed,
                    isCameraHandoff = isCameraHandoff,
                ),
            )
        }.getOrDefault(NONE)
    }
}

/** Decoded guiding empty-state copy for the current mode. */
data class StudioEmptyState(    val title: String,
    val body: String,
    val icon: String,
    val primary: String,
    val secondary: String?,
    val tertiary: String?,
    val hint: String,
    val undoHint: String,
    val nothingToUndo: String,
    val nothingToRedo: String,
) {
    companion object {
        /**
         * Decodes the bridge seam for [mode] (`image|gif|video`). Junk
         * falls back to hard-coded image copy so the screen is never blank.
         */
        fun decode(mode: String): StudioEmptyState {
            val fallback = StudioEmptyState(
                title = "Start with an image",
                body = "Tap Media to pick a photo, or start from a blank canvas.",
                icon = "media",
                primary = "Pick an image",
                secondary = "Start blank canvas",
                tertiary = null,
                hint = "Add text, a sticker or a look — everything autosaves.",
                undoHint = "Undone — redo is beside undo in the top bar.",
                nothingToUndo = "Nothing to undo",
                nothingToRedo = "Nothing to redo",
            )
            return runCatching {
                val root = JSONObject(BusinessCoreBridge().memeEmptyState(mode))
                fun text(key: String, default: String) =
                    root.optString(key, default).ifBlank { default }
                fallback.copy(
                    title = text("title", fallback.title),
                    body = text("body", fallback.body),
                    icon = text("icon", fallback.icon),
                    primary = text("primary", fallback.primary),
                    secondary = root.optString("secondary").takeIf { it.isNotBlank() },
                    tertiary = root.optString("tertiary").takeIf { it.isNotBlank() },
                    hint = text("hint", fallback.hint),
                    undoHint = text("undoHint", fallback.undoHint),
                    nothingToUndo = text("nothingToUndo", fallback.nothingToUndo),
                    nothingToRedo = text("nothingToRedo", fallback.nothingToRedo),
                )
            }.getOrDefault(fallback)
        }
    }
}

/**
 * MSU-050..052: publish-vs-export copy (explainer, verbs, review order,
 * result-card labels). Decoded from the bridge; junk falls back to working
 * copy so the flow is never blank.
 */
data class StudioPublishCopy(
    val publishExplainer: String,
    val primaryAction: String,
    val exportAction: String,
    val posted: String,
    val view: String,
    val share: String,
    val makeAnother: String,
    val verifyBeforeSign: String,
    val reviewSteps: List<Pair<String, String>>,
) {
    companion object {
        private val FALLBACK = StudioPublishCopy(
            publishExplainer = "Publish posts to Nostr · Export saves a file to this device.",
            primaryAction = "Next",
            exportAction = "Save a copy",
            posted = "Posted",
            view = "View",
            share = "Share",
            makeAnother = "Make another",
            verifyBeforeSign = "Media uploads and hash-verifies before anything is signed.",
            reviewSteps = listOf(
                "preview" to "Preview",
                "caption" to "Caption",
                "tags" to "Tags",
                "safety" to "Safety",
                "publish" to "Publish",
            ),
        )

        fun decode(): StudioPublishCopy = runCatching {
            val root = JSONObject(BusinessCoreBridge().memePublishCopy())
            fun text(key: String, default: String) =
                root.optString(key, default).ifBlank { default }
            val steps = root.optJSONArray("reviewSteps")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val row = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = row.optString("id").ifBlank { return@mapNotNull null }
                    id to row.optString("title")
                }
            }.orEmpty()
            FALLBACK.copy(
                publishExplainer = text("publishExplainer", FALLBACK.publishExplainer),
                primaryAction = text("primaryAction", FALLBACK.primaryAction),
                exportAction = text("exportAction", FALLBACK.exportAction),
                posted = text("posted", FALLBACK.posted),
                view = text("view", FALLBACK.view),
                share = text("share", FALLBACK.share),
                makeAnother = text("makeAnother", FALLBACK.makeAnother),
                verifyBeforeSign = text("verifyBeforeSign", FALLBACK.verifyBeforeSign),
                reviewSteps = steps.ifEmpty { FALLBACK.reviewSteps },
            )
        }.getOrDefault(FALLBACK)
    }
}

/**
 * MSU-060..063: mass-production + operator copy (batch base, batch status
 * strip, template-first batch, controls reference). Decoded from the
 * bridge; junk falls back to working copy so the flow is never blank.
 *
 * Revised for touch-first phones: the reference is now [controls] — each
 * row names its on-screen affordance first and its optional hardware key
 * second — rather than a keyboard-only list a phone could never use.
 */
data class StudioProductionCopy(
    val batchBaseAction: String,
    val batchBaseExplainer: String,
    val batchSeeded: String,
    val batchQueueLink: String,
    val templateBatchCount: Int,
    val templateBatchExplainer: String,
    val controlsTitle: String,
    val touchSectionTitle: String,
    val keyboardSectionTitle: String,
    val keyboardAbsentHint: String,
    val shortcutsHint: String,
    val controls: List<StudioControl>,
) {
    /** `keys` is a platform-neutral token string (`mod+shift+z`). */
    fun shortcutParts(keys: String): List<String> = keys.split("+")

    /** Human list for the template-rail action ("Make 3 variants"). */
    fun templateBatchAction(count: Int): String {
        val bounded = count.coerceIn(1, 12)
        return "Make $bounded variant${if (bounded == 1) "" else "s"}"
    }

    /** Rows that have a bound hardware key (the keyboard section). */
    val keyboardRows: List<StudioControl> get() = controls.filter { it.keys.isNotBlank() }

    companion object {
        private val FALLBACK = StudioProductionCopy(
            batchBaseAction = "Use as batch base",
            batchBaseExplainer = "Freezes this design as a reusable batch base — every variant varies the caption, not the layout.",
            batchSeeded = "Batch seeded from this design — caption slots are ready in the queue.",
            batchQueueLink = "View queue",
            templateBatchCount = 3,
            templateBatchExplainer = "Seeds a batch from this template — one caption slot per variant, editable in the queue.",
            controlsTitle = "Controls",
            touchSectionTitle = "On screen",
            keyboardSectionTitle = "Keyboard (optional)",
            keyboardAbsentHint = "Connect a keyboard for faster editing — every control here is also on screen.",
            shortcutsHint = "Works with a hardware or desktop-class keyboard; touch equivalents stay on screen.",
            controls = listOf(
                StudioControl("undo", "Undo", "Top-bar ↶", "mod+z"),
                StudioControl("redo", "Redo", "Top-bar ↷", "mod+shift+z"),
                StudioControl("play-pause", "Play / pause", "Timeline ▷", "space"),
                StudioControl("prev", "Select previous", "Tap a lane or layer", "alt+arrowup"),
                StudioControl("next", "Select next", "Tap a lane or layer", "alt+arrowdown"),
                StudioControl("export", "Save a copy", "Header ⤓", "mod+e"),
                StudioControl("publish", "Review & publish", "Header Next", "mod+enter"),
                StudioControl("timeline", "Timeline workspace", "More ▸ Timeline", "mod+t"),
                StudioControl("delete", "Delete selection", "Select, then Delete", "backspace"),
            ),
        )

        fun decode(): StudioProductionCopy = runCatching {
            val root = JSONObject(BusinessCoreBridge().memeProductionCopy())
            fun text(key: String, default: String) =
                root.optString(key, default).ifBlank { default }
            val controls = root.optJSONArray("controls")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val row = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = row.optString("id").ifBlank { return@mapNotNull null }
                    StudioControl(
                        id = id,
                        label = row.optString("label"),
                        touch = row.optString("touch"),
                        keys = row.optString("keys"),
                    )
                }
            }.orEmpty()
            FALLBACK.copy(
                batchBaseAction = text("batchBaseAction", FALLBACK.batchBaseAction),
                batchBaseExplainer = text("batchBaseExplainer", FALLBACK.batchBaseExplainer),
                batchSeeded = text("batchSeeded", FALLBACK.batchSeeded),
                batchQueueLink = text("batchQueueLink", FALLBACK.batchQueueLink),
                templateBatchCount = root.optInt("templateBatchCount", FALLBACK.templateBatchCount),
                templateBatchExplainer = text("templateBatchExplainer", FALLBACK.templateBatchExplainer),
                controlsTitle = text("controlsTitle", FALLBACK.controlsTitle),
                touchSectionTitle = text("touchSectionTitle", FALLBACK.touchSectionTitle),
                keyboardSectionTitle = text("keyboardSectionTitle", FALLBACK.keyboardSectionTitle),
                keyboardAbsentHint = text("keyboardAbsentHint", FALLBACK.keyboardAbsentHint),
                shortcutsHint = text("shortcutsHint", FALLBACK.shortcutsHint),
                controls = controls.ifEmpty { FALLBACK.controls },
            )
        }.getOrDefault(FALLBACK)
    }
}

/** One documented editor control (shared `StudioProduction.Control`). */
data class StudioControl(
    val id: String,
    val label: String,
    /** The always-available on-screen affordance (touch). */
    val touch: String,
    /** Optional hardware accelerator; blank when none is bound. */
    val keys: String,
)

/** One keyboard-only row (shared `StudioProduction.Shortcut`). */
data class StudioShortcut(
    val id: String,
    val keys: String,
    val label: String,
)

/**
 * MSU-042..043: feedback-closure copy — the busy-surface titles/bodies and
 * the confirm-vs-undo classification for every destructive surface.
 * Decoded from the bridge; junk falls back to working copy.
 */
data class StudioFeedbackCopy(
    val progress: List<StudioProgressSurface>,
    val confirms: List<StudioConfirmRule>,
) {
    /** Resolve a surface by id; unknown ids return the export render. */
    fun surfaceFor(id: String?): StudioProgressSurface =
        progress.firstOrNull { it.id == id } ?: progress.firstOrNull() ?: StudioProgressSurface(
            id = "export-render",
            title = "Rendering your meme…",
            body = "This can take a moment for long clips — keep the screen open.",
            determinate = false,
        )

    /** True when the surface must show a confirm dialog (MSU-043). */
    fun requiresDialog(id: String?): Boolean =
        confirms.firstOrNull { it.id == id }?.mode == "confirm"

    /**
     * The determinate fraction for a surface, clamped to `0..1`; null when
     * the surface is not determinate (never a simulated bar).
     */
    fun fraction(surface: StudioProgressSurface, done: Int, total: Int): Float? {
        if (!surface.determinate) return null
        if (total <= 0) return 0f
        return (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        private val FALLBACK = StudioFeedbackCopy(
            progress = listOf(
                StudioProgressSurface(
                    "import-clips", "Preparing clips…",
                    "Probing and staging your source clips.", true,
                ),
                StudioProgressSurface(
                    "export-render", "Rendering your meme…",
                    "This can take a moment for long clips — keep the screen open.", false,
                ),
                StudioProgressSurface(
                    "gif-ladder", "Fitting the GIF…",
                    "Re-sampling frames to fit the size limit.", true,
                ),
                StudioProgressSurface(
                    "batch-render", "Rendering variants…",
                    "Every approved variant renders before it publishes.", true,
                ),
                StudioProgressSurface(
                    "publish", "Publishing…",
                    "Media uploads and hash-verifies before anything is signed.", true,
                ),
            ),
            confirms = listOf(
                StudioConfirmRule("delete-overlay", "undo", "Delete overlay", ""),
                StudioConfirmRule("delete-clip", "undo", "Delete clip", ""),
                StudioConfirmRule("delete-layer", "undo", "Delete layer", ""),
                StudioConfirmRule(
                    "mode-switch", "confirm", "Start a new project?",
                    "Switching clears the current media (overlays stay). Continue?",
                ),
                StudioConfirmRule(
                    "discard-draft", "confirm", "Could not save the draft",
                    "Your edits are still open. Retry the save to keep them, or delete the draft deliberately.",
                ),
                StudioConfirmRule(
                    "discard-takes", "confirm", "Discard takes?",
                    "Recorded takes are removed when you leave.",
                ),
            ),
        )

        fun decode(): StudioFeedbackCopy = runCatching {
            val root = JSONObject(BusinessCoreBridge().memeFeedbackCopy())
            val progress = root.optJSONArray("progress")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val row = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = row.optString("id").ifBlank { return@mapNotNull null }
                    StudioProgressSurface(
                        id = id,
                        title = row.optString("title"),
                        body = row.optString("body"),
                        determinate = row.optBoolean("determinate"),
                    )
                }
            }.orEmpty()
            val confirms = root.optJSONArray("confirms")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val row = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = row.optString("id").ifBlank { return@mapNotNull null }
                    StudioConfirmRule(
                        id = id,
                        mode = row.optString("mode", "confirm"),
                        title = row.optString("title"),
                        body = row.optString("body"),
                    )
                }
            }.orEmpty()
            FALLBACK.copy(
                progress = progress.ifEmpty { FALLBACK.progress },
                confirms = confirms.ifEmpty { FALLBACK.confirms },
            )
        }.getOrDefault(FALLBACK)
    }
}

/** One busy surface (shared `StudioProgress.ProgressSurface`). */
data class StudioProgressSurface(
    val id: String,
    val title: String,
    val body: String,
    val determinate: Boolean,
)

/** One audited destructive surface (shared `StudioProgress.ConfirmRule`). */
data class StudioConfirmRule(
    val id: String,
    /** `"confirm"` (irreversible ⇒ dialog) or `"undo"` (reversible ⇒ Undo notice). */
    val mode: String,
    val title: String,
    val body: String,
)
