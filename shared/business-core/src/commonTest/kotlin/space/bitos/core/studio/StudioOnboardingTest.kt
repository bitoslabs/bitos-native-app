package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Studio onboarding rules (plan MSU-030..033): the coach runs only for a
 * genuinely fresh, self-started session, and every mode has guiding
 * empty-state copy.
 */
class StudioOnboardingTest {

    private val onboarding = StudioOnboarding

    private fun context(
        hasSeenCoach: Boolean = false,
        isResume: Boolean = false,
        isRemix: Boolean = false,
        isSoundSeed: Boolean = false,
        isTemplateSeed: Boolean = false,
        isCameraHandoff: Boolean = false,
    ) = StudioOnboarding.Context(
        hasSeenCoach = hasSeenCoach,
        isResume = isResume,
        isRemix = isRemix,
        isSoundSeed = isSoundSeed,
        isTemplateSeed = isTemplateSeed,
        isCameraHandoff = isCameraHandoff,
    )

    @Test
    fun coachRunsOnlyForAFreshSelfStartedSession() {
        assertTrue(onboarding.shouldRunCoach(context()))
    }

    @Test
    fun coachNeverRunsTwice() {
        assertFalse(onboarding.shouldRunCoach(context(hasSeenCoach = true)))
    }

    @Test
    fun coachIsSuppressedOnEveryHandoff() {
        // Each of these means the creator is already mid-task; a tour would
        // be in the way. The shipped editor had no such gate at all.
        assertFalse(onboarding.shouldRunCoach(context(isResume = true)), "resume")
        assertFalse(onboarding.shouldRunCoach(context(isRemix = true)), "remix")
        assertFalse(onboarding.shouldRunCoach(context(isSoundSeed = true)), "sound seed")
        assertFalse(onboarding.shouldRunCoach(context(isTemplateSeed = true)), "template seed")
        assertFalse(onboarding.shouldRunCoach(context(isCameraHandoff = true)), "camera handoff")
    }

    @Test
    fun coachStepsAreShortOrderedAndAnchored() {
        val steps = onboarding.COACH_STEPS
        // Three steps is the whole tour — longer is skipped, not read.
        assertEquals(3, steps.size, "the coach must stay short enough to finish")
        assertEquals(listOf("stage", "tools", "next"), steps.map { it.id })
        assertEquals(steps.map { it.id }.toSet().size, steps.size, "duplicate step id")
        steps.forEach { step ->
            assertTrue(step.anchor.isNotBlank(), step.id)
            assertTrue(step.iconToken.isNotBlank(), step.id)
            assertTrue(step.title.isNotBlank(), step.id)
            assertTrue(step.body.isNotBlank(), step.id)
            // A coach line is a line, not a paragraph.
            assertTrue(step.body.length <= 140, "${step.id} body too long: ${step.body.length}")
        }
    }

    @Test
    fun everyModeHasGuidingEmptyStateCopy() {
        MemeMode.entries.forEach { mode ->
            val state = onboarding.emptyStateFor(mode)
            assertTrue(state.title.isNotBlank(), "$mode title")
            assertTrue(state.body.isNotBlank(), "$mode body")
            assertTrue(state.iconToken.isNotBlank(), "$mode icon")
            assertTrue(state.primaryLabel.isNotBlank(), "$mode primary")
        }
    }

    @Test
    fun gifOffersBrowseAndVideoDoesNot() {
        // The GIF browser is a real alternate entry; the others are not.
        assertTrue(onboarding.emptyStateFor(MemeMode.GIF).tertiaryLabel != null)
        assertEquals(null, onboarding.emptyStateFor(MemeMode.IMAGE).tertiaryLabel)
        assertEquals(null, onboarding.emptyStateFor(MemeMode.VIDEO).tertiaryLabel)
    }

    @Test
    fun unknownModeFallsBackToTheImageCopyInsteadOfBlank() {
        assertEquals(onboarding.emptyStateFor(MemeMode.IMAGE), onboarding.emptyStateFor(null))
    }

    @Test
    fun emptyStateIconsComeFromTheSharedToolVocabulary() {
        // The empty-state glyphs name tools the catalogue actually has, so
        // the guidance and the bar cannot disagree.
        val toolIcons = MemeTools.ToolId.entries.map { it.iconKey }.toSet()
        MemeMode.entries.forEach { mode ->
            val token = onboarding.emptyStateFor(mode).iconToken
            assertTrue(token in toolIcons, "$mode empty-state icon '$token' is not a tool icon")
        }
    }

    @Test
    fun undoCopyExplainsThePairAndNeverDeadTaps() {
        // MSU-033: the hint names redo so undo/redo are learnt together.
        assertTrue(onboarding.UNDO_HINT.contains("redo", ignoreCase = true))
        assertTrue(onboarding.NOTHING_TO_UNDO.isNotBlank())
        assertTrue(onboarding.NOTHING_TO_REDO.isNotBlank())
        assertTrue(onboarding.COACH_KEY.isNotBlank())
    }

    @Test
    fun publishExplainerSeparatesTheTwoDoneVerbs() {
        // MSU-050: one line must name BOTH destinations so the creator can
        // tell them apart without trial and error.
        val explainer = onboarding.PUBLISH_EXPLAINER
        assertTrue(explainer.contains("Nostr"), explainer)
        assertTrue(explainer.contains("device"), explainer)
        assertTrue(explainer.length <= 100, "the explainer is one line, not a paragraph")
        // Exactly one primary verb; export is the secondary.
        assertEquals("Next", onboarding.PRIMARY_ACTION)
        assertEquals("Save a copy", onboarding.EXPORT_ACTION)
        assertTrue(onboarding.EXPORT_ACTION != onboarding.PRIMARY_ACTION)
    }

    @Test
    fun reviewOrderEndsAtPublishAndIsStable() {
        // MSU-051: the documented review order must be the real order, so
        // copy cannot disagree with the layout.
        val steps = onboarding.REVIEW_STEPS.map { it.id }
        assertEquals(listOf("preview", "caption", "tags", "safety", "publish"), steps)
        assertEquals("Publish", onboarding.REVIEW_STEPS.last().title)
        assertEquals(steps.toSet().size, steps.size, "duplicate review step id")
    }

    @Test
    fun resultCardOffersFollowOnActions() {
        // MSU-052: a bare "Done" gave the creator nothing to do next.
        listOf(
            onboarding.POSTED_LABEL,
            onboarding.VIEW_LABEL,
            onboarding.SHARE_LABEL,
            onboarding.MAKE_ANOTHER_LABEL,
        ).forEach { assertTrue(it.isNotBlank()) }
        assertTrue(onboarding.VERIFY_BEFORE_SIGN.contains("hash", ignoreCase = true))
    }
}
