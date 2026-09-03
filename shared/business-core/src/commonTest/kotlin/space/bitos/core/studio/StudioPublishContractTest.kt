package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Studio publish machine contract (plan MST-003): the safety rule is
 * structural — signing/publishing is reachable only after a verified
 * upload; illegal events are no-ops.
 */
class StudioPublishContractTest {

    private val rendering = StudioPublishContract.initial()
        .let { StudioPublishContract.transition(it, StudioPublishEvent.BeginRendering) }

    private val uploading = StudioPublishContract.transition(
        rendering,
        StudioPublishEvent.RenderingDone,
    )

    private val readyToSign = StudioPublishContract.transition(
        uploading,
        StudioPublishEvent.UploadVerified,
    )

    @Test
    fun happyPathReachesPublished() {
        val signing = StudioPublishContract.transition(readyToSign, StudioPublishEvent.BeginSigning)
        val published = StudioPublishContract.transition(
            signing,
            StudioPublishEvent.Signed("note-1"),
        )
        assertEquals(StudioPublishStep.PUBLISHED, published.step)
        assertEquals("note-1", published.noteId)
        assertTrue(published.hashVerified)
    }

    @Test
    fun signingIsUnreachableWithoutVerifiedUpload() {
        // From UPLOADING (hash not yet verified): a no-op, never SIGNING.
        assertEquals(uploading, StudioPublishContract.transition(uploading, StudioPublishEvent.BeginSigning))
        // Skipping the upload phase entirely: a no-op from RENDERING too.
        assertEquals(rendering, StudioPublishContract.transition(rendering, StudioPublishEvent.BeginSigning))
        // A forged Signed event without hash verification: no-op.
        assertEquals(uploading, StudioPublishContract.transition(uploading, StudioPublishEvent.Signed("note-1")))
    }

    @Test
    fun progressOnlyMovesInUploadingAndIsBounded() {
        assertEquals(100, StudioPublishContract.transition(uploading, StudioPublishEvent.UploadProgress(140)).uploadPercent)
        assertEquals(40, StudioPublishContract.transition(uploading, StudioPublishEvent.UploadProgress(40)).uploadPercent)
        assertEquals(0, StudioPublishContract.transition(readyToSign, StudioPublishEvent.UploadProgress(40)).uploadPercent)
    }

    @Test
    fun renderingStartsOnlyFromDraft() {
        assertEquals(StudioPublishStep.RENDERING, StudioPublishContract.transition(StudioPublishContract.initial(), StudioPublishEvent.BeginRendering).step)
        assertEquals(StudioPublishStep.UPLOADING, StudioPublishContract.transition(uploading, StudioPublishEvent.BeginRendering).step)
    }

    @Test
    fun failLandsInFailedFromAnyActiveStepAndResetRearms() {
        val failed = StudioPublishContract.transition(uploading, StudioPublishEvent.Fail("relay timeout"))
        assertEquals(StudioPublishStep.FAILED, failed.step)
        assertEquals("relay timeout", failed.failureReason)
        assertEquals(
            StudioPublishStep.DRAFT,
            StudioPublishContract.transition(failed, StudioPublishEvent.Reset).step,
        )
        // Published is terminal — a late failure cannot un-publish.
        val published = StudioPublishContract.transition(
            StudioPublishContract.transition(readyToSign, StudioPublishEvent.BeginSigning),
            StudioPublishEvent.Signed("note-1"),
        )
        assertEquals(
            StudioPublishStep.PUBLISHED,
            StudioPublishContract.transition(published, StudioPublishEvent.Fail("late")).step,
        )
        assertNull(StudioPublishContract.initial().failureReason)
    }
}
