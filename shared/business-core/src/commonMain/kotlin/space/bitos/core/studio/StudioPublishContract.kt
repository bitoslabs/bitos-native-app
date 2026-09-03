package space.bitos.core.studio

/**
 * APP-019 studio publish machine (plan MST-003): the pure state sequence
 * render → hash-verified upload → sign(+PoW) → publish. The safety rule is
 * structural: **the signed step is reachable only after [UploadVerified]**,
 * so no native caller can sign media that was never uploaded and verified
 * ("never sign before media is uploaded and hash-verified"). Illegal events
 * are no-ops (same state returned); failures move to FAILED from anywhere
 * and [StudioPublishEvent.Reset] re-arms a retry.
 */
enum class StudioPublishStep {
    DRAFT,
    RENDERING,
    UPLOADING,
    READY_TO_SIGN,
    SIGNING,
    PUBLISHED,
    FAILED,
}

data class StudioPublishState(
    val step: StudioPublishStep = StudioPublishStep.DRAFT,
    /** Upload progress 0..100 (UPLOADING only). */
    val uploadPercent: Int = 0,
    val hashVerified: Boolean = false,
    val failureReason: String? = null,
    /** Published note id (PUBLISHED only). */
    val noteId: String? = null,
)

sealed interface StudioPublishEvent {
    data object BeginRendering : StudioPublishEvent
    data object RenderingDone : StudioPublishEvent
    data class UploadProgress(val percent: Int) : StudioPublishEvent
    data object UploadVerified : StudioPublishEvent
    data object BeginSigning : StudioPublishEvent
    data class Signed(val noteId: String) : StudioPublishEvent
    data class Fail(val reason: String) : StudioPublishEvent
    data object Reset : StudioPublishEvent
}

object StudioPublishContract {

    fun initial(): StudioPublishState = StudioPublishState()

    fun transition(
        state: StudioPublishState,
        event: StudioPublishEvent,
    ): StudioPublishState = when (event) {
        is StudioPublishEvent.BeginRendering ->
            if (state.step == StudioPublishStep.DRAFT) {
                state.copy(step = StudioPublishStep.RENDERING, failureReason = null)
            } else {
                state
            }

        is StudioPublishEvent.RenderingDone ->
            if (state.step == StudioPublishStep.RENDERING) {
                state.copy(step = StudioPublishStep.UPLOADING, uploadPercent = 0)
            } else {
                state
            }

        is StudioPublishEvent.UploadProgress ->
            if (state.step == StudioPublishStep.UPLOADING) {
                state.copy(uploadPercent = event.percent.coerceIn(0, 100))
            } else {
                state
            }

        is StudioPublishEvent.UploadVerified ->
            if (state.step == StudioPublishStep.UPLOADING) {
                state.copy(step = StudioPublishStep.READY_TO_SIGN, hashVerified = true)
            } else {
                state
            }

        is StudioPublishEvent.BeginSigning ->
            if (state.step == StudioPublishStep.READY_TO_SIGN && state.hashVerified) {
                state.copy(step = StudioPublishStep.SIGNING)
            } else {
                state
            }

        is StudioPublishEvent.Signed ->
            if (state.step == StudioPublishStep.SIGNING && state.hashVerified &&
                event.noteId.isNotBlank()
            ) {
                state.copy(step = StudioPublishStep.PUBLISHED, noteId = event.noteId)
            } else {
                state
            }

        is StudioPublishEvent.Fail ->
            if (state.step == StudioPublishStep.PUBLISHED) {
                state
            } else {
                state.copy(step = StudioPublishStep.FAILED, failureReason = event.reason.take(200))
            }

        is StudioPublishEvent.Reset ->
            if (state.step == StudioPublishStep.FAILED) initial() else state
    }
}
