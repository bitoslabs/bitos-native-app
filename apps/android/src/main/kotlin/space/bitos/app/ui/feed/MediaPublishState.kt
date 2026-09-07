package space.bitos.app.ui.feed

/** MST-017 meme lane: render → upload → kind-20 phases (no pick phase). */
enum class MemePublishPhase { IDLE, UPLOADING, PUBLISHING, DONE }

/**
 * Prototype `#/publishing` machine stages — each maps to a REAL pipeline
 * checkpoint (render → hash → upload → verify → build → sign → relay →
 * confirm), never a timer.
 */
enum class MemePublishStage { IDLE, RENDER, HASH, UPLOAD, VERIFY, BUILD, SIGN, RELAY, CONFIRM }

data class MemePublishUiState(
    val phase: MemePublishPhase = MemePublishPhase.IDLE,
    val failure: String? = null,
    val stage: MemePublishStage = MemePublishStage.IDLE,
    /** Stable per-attempt job number (prototype "job NNNN" chip). */
    val jobId: Int = 0,
    /** Canonical event id once composed (null until BUILD completes). */
    val eventId: String? = null,
    /** Relay hosts that returned an accepted OK for the event. */
    val confirmedRelayHosts: List<String> = emptyList(),
    /** True when the receipt machine reached a terminal result. */
    val terminal: Boolean = false,
)
