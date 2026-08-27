package space.bitos.core.publish

import space.bitos.core.model.EventId
import space.bitos.core.model.MediaHash

enum class PublishStage {
    DRAFT,
    VALIDATING,
    RENDERING,
    HASHING,
    UPLOADING,
    VERIFYING_REMOTE,
    AWAITING_SIGNATURE,
    PUBLISHING,
    RECONCILING,
    DONE,
    CANCELLED,
    BLOCKED,
    FAILED,
}

data class MediaDescriptor(
    val url: String,
    val hash: MediaHash,
    val mimeType: String,
    val bytes: Long,
) {
    init {
        require(url.startsWith("https://")) { "Published media requires HTTPS" }
        require(mimeType.startsWith("video/") || mimeType.startsWith("image/"))
        require(bytes > 0)
    }
}

data class PublishState(
    val stage: PublishStage = PublishStage.DRAFT,
    val revision: Long = 0,
    val projectHash: MediaHash? = null,
    val media: MediaDescriptor? = null,
    val eventId: EventId? = null,
    val relayAcks: Set<String> = emptySet(),
    val errorCode: String? = null,
)

sealed interface PublishAction {
    data object Validate : PublishAction
    data class ValidationPassed(val projectHash: MediaHash) : PublishAction
    data object RenderCompleted : PublishAction
    data class HashCompleted(val outputHash: MediaHash) : PublishAction
    data class UploadCompleted(val descriptor: MediaDescriptor) : PublishAction
    data object RemoteVerified : PublishAction
    data object SignatureCompleted : PublishAction
    data class RelayAcknowledged(val relay: String, val eventId: EventId) : PublishAction
    data object ReconcileCompleted : PublishAction
    data class Fail(val code: String) : PublishAction
    data object Cancel : PublishAction
}

sealed interface PublishEffect {
    data object ValidateProject : PublishEffect
    data object RenderProject : PublishEffect
    data object HashOutput : PublishEffect
    data object UploadMedia : PublishEffect
    data class VerifyRemote(val descriptor: MediaDescriptor) : PublishEffect
    data class RequestSignature(val descriptor: MediaDescriptor) : PublishEffect
    data object PublishToRelays : PublishEffect
    data object ReconcileRelayEcho : PublishEffect
}

data class Reduction(
    val state: PublishState,
    val effects: List<PublishEffect> = emptyList(),
)

object PublishReducer {
    fun reduce(state: PublishState, action: PublishAction): Reduction = when {
        action is PublishAction.Cancel && !state.stage.isTerminal ->
            Reduction(state.next(PublishStage.CANCELLED))

        action is PublishAction.Fail && !state.stage.isTerminal ->
            Reduction(state.next(PublishStage.FAILED).copy(errorCode = action.code.take(80)))

        state.stage == PublishStage.DRAFT && action is PublishAction.Validate ->
            Reduction(state.next(PublishStage.VALIDATING), listOf(PublishEffect.ValidateProject))

        state.stage == PublishStage.VALIDATING && action is PublishAction.ValidationPassed ->
            Reduction(
                state.next(PublishStage.RENDERING).copy(projectHash = action.projectHash),
                listOf(PublishEffect.RenderProject),
            )

        state.stage == PublishStage.RENDERING && action is PublishAction.RenderCompleted ->
            Reduction(state.next(PublishStage.HASHING), listOf(PublishEffect.HashOutput))

        state.stage == PublishStage.HASHING && action is PublishAction.HashCompleted ->
            Reduction(state.next(PublishStage.UPLOADING), listOf(PublishEffect.UploadMedia))

        state.stage == PublishStage.UPLOADING && action is PublishAction.UploadCompleted ->
            Reduction(
                state.next(PublishStage.VERIFYING_REMOTE).copy(media = action.descriptor),
                listOf(PublishEffect.VerifyRemote(action.descriptor)),
            )

        state.stage == PublishStage.VERIFYING_REMOTE && action is PublishAction.RemoteVerified -> {
            val descriptor = requireNotNull(state.media)
            Reduction(
                state.next(PublishStage.AWAITING_SIGNATURE),
                listOf(PublishEffect.RequestSignature(descriptor)),
            )
        }

        state.stage == PublishStage.AWAITING_SIGNATURE && action is PublishAction.SignatureCompleted ->
            Reduction(state.next(PublishStage.PUBLISHING), listOf(PublishEffect.PublishToRelays))

        state.stage == PublishStage.PUBLISHING && action is PublishAction.RelayAcknowledged ->
            Reduction(
                state.next(PublishStage.RECONCILING).copy(
                    eventId = action.eventId,
                    relayAcks = state.relayAcks + action.relay,
                ),
                listOf(PublishEffect.ReconcileRelayEcho),
            )

        state.stage == PublishStage.RECONCILING && action is PublishAction.RelayAcknowledged ->
            Reduction(state.copy(relayAcks = state.relayAcks + action.relay))

        state.stage == PublishStage.RECONCILING && action is PublishAction.ReconcileCompleted ->
            Reduction(state.next(PublishStage.DONE))

        else -> Reduction(state)
    }
}

private fun PublishState.next(stage: PublishStage) = copy(stage = stage, revision = revision + 1)

private val PublishStage.isTerminal: Boolean
    get() = this == PublishStage.DONE ||
        this == PublishStage.CANCELLED ||
        this == PublishStage.BLOCKED ||
        this == PublishStage.FAILED
