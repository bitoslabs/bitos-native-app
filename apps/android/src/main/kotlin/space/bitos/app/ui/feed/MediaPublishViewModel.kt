package space.bitos.app.ui.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import space.bitos.app.data.media.BlossomUploader
import space.bitos.app.data.media.DefaultBlossomServer
import space.bitos.app.data.media.MemeVideoUploader
import space.bitos.app.data.publish.NotePublisher
import space.bitos.core.model.Blossom

/**
 * Activity-scoped meme publish machine shared by the studio editor and the
 * Create mass-batch flow: rendered bytes → hash-verified Blossom upload →
 * kind-20/22 through the receipt machine. All heavy work runs off the main
 * thread; failures surface in state, never crash.
 */
class MediaPublishViewModel(
    application: Application,
    private val publisher: NotePublisher,
    private val identity: space.bitos.app.identity.IdentityViewModel,
) : AndroidViewModel(application) {

    private val uploader = BlossomUploader()
    private val videoUploader = MemeVideoUploader(blossom = uploader)
    private val jobLedger = MemePublishJobStore(application)

    private val mutableMemeState = MutableStateFlow(MemePublishUiState())
    val memeState: StateFlow<MemePublishUiState> = mutableMemeState.asStateFlow()

    private var memeJobCounter = 0

    /**
     * Prototype `#/publishing`: the editor marks the render stage as it
     * encodes (the encode runs in the editor, not here) and mints the
     * per-attempt job id.
     */
    fun markMemeRenderStarted() {
        memeJobCounter = (1000..9999).random()
        mutableMemeState.value = MemePublishUiState(
            phase = MemePublishPhase.UPLOADING,
            stage = MemePublishStage.RENDER,
            jobId = memeJobCounter,
        )
    }

    /** The ViewModel outlives an editor; a terminal attempt must not. */
    fun resetCompletedMemePublish() {
        if (!memePublishBusy()) {
            mutableMemeState.value = MemePublishUiState()
        }
    }

    /**
     * Render-stage fraction from the editor's encoder poll (Transformer's
     * REAL progress API). Ignored outside the render step so a late frame
     * can't bleed into the hash/upload rows.
     */
    fun updateMemeRenderProgress(fraction: Float) {
        val state = mutableMemeState.value
        if (state.phase == MemePublishPhase.UPLOADING && state.stage == MemePublishStage.RENDER) {
            mutableMemeState.value = state.copy(stageProgress = fraction.coerceIn(0f, 1f))
        }
    }

    /** Stage checkpoint: advances the machine and drops any stale fraction. */
    private fun onUploadStage(mapped: MemePublishStage, ledgerId: Int) {
        mutableMemeState.value = mutableMemeState.value.copy(
            stage = mapped,
            stageProgress = null,
            stageProgressDetail = null,
        )
        jobLedger.update(ledgerId, stage = mapped.ordinal)
    }

    /**
     * Upload-stage byte truth (socket bytes, never estimated) → fraction +
     * MB detail. Dropped once the stage moved on (verify/read-back).
     */
    private fun onUploadBytes(written: Long, total: Long) {
        val state = mutableMemeState.value
        if (state.phase == MemePublishPhase.UPLOADING && state.stage == MemePublishStage.UPLOAD) {
            mutableMemeState.value = state.copy(
                stageProgress = (written.toDouble() / total.coerceAtLeast(1).toDouble())
                    .toFloat().coerceIn(0f, 1f),
                stageProgressDetail = mbDetail(written, total),
            )
        }
    }

    private fun mbDetail(written: Long, total: Long): String =
        String.format(
            java.util.Locale.US, "%.1f / %.1f MB",
            written / 1_000_000.0, total / 1_000_000.0,
        )

    private fun freshJobId(): Int {
        memeJobCounter = (1000..9999).random()
        return memeJobCounter
    }

    /**
     * Completes the machine stepper when the receipt machine resolves:
     * CONFIRM + the relay hosts that accepted the event.
     */
    private fun watchMemeResult(
        jobId: Int,
        onSettled: ((Boolean, String?) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val resolved = withTimeoutOrNull(15_000) {
                publisher.state.first { it.result != null }
            }
            if (mutableMemeState.value.jobId != jobId) return@launch
            val accepted = resolved?.receipts
                ?.filter { it.accepted == true }
                ?.map { it.relay.value.removePrefix("wss://").removePrefix("ws://").substringBefore('/') }
                .orEmpty()
            if (resolved?.result == space.bitos.app.data.publish.PublishResult.PUBLISHED) {
                jobLedger.finish(jobId)
                mutableMemeState.value = mutableMemeState.value.copy(
                    phase = MemePublishPhase.DONE,
                    stage = MemePublishStage.CONFIRM,
                    terminal = true,
                    confirmedRelayHosts = accepted,
                )
                onSettled?.invoke(true, null)
            } else {
                val failure = "No relay confirmed the event — it may still land; verify before retrying."
                jobLedger.update(
                    jobId, status = "failed",
                    error = failure,
                )
                mutableMemeState.value = mutableMemeState.value.copy(
                    phase = MemePublishPhase.IDLE,
                    stage = MemePublishStage.CONFIRM,
                    terminal = true,
                    confirmedRelayHosts = accepted,
                    failure = failure,
                )
                onSettled?.invoke(false, failure)
            }
        }
    }

    /** Queue retry (prototype #/queue): re-runs upload+publish from the
     *  persisted media — the job's bytes ARE the render. Refused while an
     *  event id exists (that note may already be live). */
    fun retryMemeJob(jobId: Int) {
        if (memePublishBusy()) return
        val job = jobLedger.job(jobId) ?: return
        if (!job.retryAllowed) return
        val bytes = jobLedger.loadBytes(job) ?: return
        mutableMemeState.value = MemePublishUiState(
            phase = MemePublishPhase.UPLOADING, stage = MemePublishStage.HASH, jobId = job.id,
        )
        jobLedger.update(job.id, status = "active")
        viewModelScope.launch {
            try {
                val signer = identity.createSigner()
                    ?: throw BlossomUploader.UploadFailure("Importing needs an identity (Profile tab).")
                val media = withContext(Dispatchers.IO) {
                    if (job.mode == "video") videoUploader.upload(
                        bytes, job.mime, signer, DefaultBlossomServer.url,
                        onStage = { stage ->
                            val mapped = when (stage) {
                                MemeVideoUploader.UploadStage.HASHING -> MemePublishStage.HASH
                                MemeVideoUploader.UploadStage.UPLOADING_BITOS,
                                MemeVideoUploader.UploadStage.UPLOADING_BLOSSOM -> MemePublishStage.UPLOAD
                                MemeVideoUploader.UploadStage.VERIFYING_BITOS,
                                MemeVideoUploader.UploadStage.VERIFYING_BLOSSOM -> MemePublishStage.VERIFY
                            }
                            onUploadStage(mapped, job.id)
                        },
                        onProgress = ::onUploadBytes,
                    ) else uploader.upload(
                        bytes, job.mime, signer, DefaultBlossomServer.url,
                        onStage = { stage ->
                            val mapped = when (stage) {
                                BlossomUploader.UploadStage.HASHING -> MemePublishStage.HASH
                                BlossomUploader.UploadStage.UPLOADING -> MemePublishStage.UPLOAD
                                BlossomUploader.UploadStage.VERIFYING -> MemePublishStage.VERIFY
                            }
                            onUploadStage(mapped, job.id)
                        },
                        onProgress = ::onUploadBytes,
                    )
                }
                mutableMemeState.value = mutableMemeState.value.copy(
                    phase = MemePublishPhase.PUBLISHING, stage = MemePublishStage.BUILD,
                )
                jobLedger.update(job.id, stage = MemePublishStage.BUILD.ordinal, mediaUrl = media.url, sha256 = media.sha256Hex)
                val extraTags = parseTags(job.extraTagsJson)
                if (job.mode == "video") {
                    publisher.publishMemeVideoNote(
                        job.caption, job.altText, job.contentWarningReason,
                        // NIP-71 kind is a short-vs-normal viewing intent,
                        // not an orientation heuristic. Retries derive it
                        // from the immutable rendered duration.
                        portrait = space.bitos.core.studio.MemeVideoCutRules.isShortFormDuration(job.durationMs),
                        media = media, signerProvider = { signer },
                        writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
                        extraTags = extraTags,
                        onStage = { s, id -> onMemeNoteStage(s, id, job.id) },
                    )
                } else {
                    publisher.publishMemePictureNote(
                        job.caption, job.altText, job.contentWarningReason,
                        media, { signer }, space.bitos.app.data.feed.DefaultRelays.writeUrls,
                        extraTags = extraTags,
                        onStage = { s, id -> onMemeNoteStage(s, id, job.id) },
                    )
                }
                watchMemeResult(job.id)
            } catch (failure: Exception) {
                mutableMemeState.value = mutableMemeState.value.copy(
                    phase = MemePublishPhase.IDLE, failure = failure.message ?: "Retry failed.",
                )
                jobLedger.update(job.id, status = "failed", error = failure.message)
            }
        }
    }

    /** Queue surface: durable recoverable jobs. */
    fun memeJobs(): List<MemePublishJob> = jobLedger.recoverable()

    fun discardMemeJob(jobId: Int) = jobLedger.discard(jobId)

    fun verifyMemeJobIntegrity(jobId: Int): Boolean? = jobLedger.job(jobId)?.let { jobLedger.integrityOK(it) }

    /**
     * MST-034 meme video publish: exported MP4 bytes → hash-verified
     * Blossom upload → kind 22 (portrait) / 21 (landscape) through the
     * receipt machine. Nothing signs before the upload verifies.
     */
    fun publishMemeVideo(
        bytes: ByteArray,
        width: Int,
        height: Int,
        durationMs: Long,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        thumbUrl: String? = null,
        remixTagsJson: String = "",
        powBits: Int = 0,
        /** "Use this sound" (MST-050 Wave B): the extracted m4a + the
         *  project wire carrying its soundtrack row. The audio uploads
         *  hash-verified BEFORE the note signs; sound/p/attribution tags
         *  then stamp the real URL (never a pre-upload stamp). */
        soundtrackBytes: ByteArray? = null,
        soundtrackProjectJson: String = "",
    ) {
        if (memePublishBusy()) return
        if (bytes.isEmpty() || bytes.size.toLong() > space.bitos.core.studio.MemeUploadRouting.BITOS_API_MAX_BYTES) {
            mutableMemeState.value = MemePublishUiState(
                failure = "Rendered meme exceeds the ${space.bitos.core.studio.MemeUploadRouting.BITOS_API_MAX_BYTES / (1024 * 1024)}MB BitOS limit.",
            )
            return
        }
        // A finished attempt leaves `result` set on the SHARED publisher —
        // its guard would silently drop the next publish and the machine
        // would sit on the old state forever ("done, cannot submit").
        // Reset it, exactly like the iOS flow's beginPublish does.
        publisher.dismiss()
        val jobId = mutableMemeState.value.jobId.takeIf { it > 0 } ?: freshJobId()
        mutableMemeState.value = MemePublishUiState(
            phase = MemePublishPhase.UPLOADING,
            stage = MemePublishStage.HASH,
            jobId = jobId,
        )
        val ledgerId = jobLedger.begin(
            "video", caption, altText, contentWarningReason, remixTagsJson, "", "",
            bytes, "video/mp4", width, height, durationMs, thumbUrl, System.currentTimeMillis(),
        )
        // Once rendered bytes are durable, their ledger id is the canonical
        // attempt id used by stage callbacks and receipt completion.
        mutableMemeState.value = mutableMemeState.value.copy(jobId = ledgerId)
        viewModelScope.launch {
            try {
                val signer = identity.createSigner()
                    ?: throw BlossomUploader.UploadFailure("Importing needs an identity (Profile tab).")
                val media = withContext(Dispatchers.IO) {
                    videoUploader.upload(
                        bytes, "video/mp4", signer, DefaultBlossomServer.url,
                        onStage = { stage ->
                            val mapped = when (stage) {
                                MemeVideoUploader.UploadStage.HASHING -> MemePublishStage.HASH
                                MemeVideoUploader.UploadStage.UPLOADING_BITOS,
                                MemeVideoUploader.UploadStage.UPLOADING_BLOSSOM -> MemePublishStage.UPLOAD
                                MemeVideoUploader.UploadStage.VERIFYING_BITOS,
                                MemeVideoUploader.UploadStage.VERIFYING_BLOSSOM -> MemePublishStage.VERIFY
                            }
                            onUploadStage(mapped, ledgerId)
                        },
                        onProgress = ::onUploadBytes,
                    )
                }
                val sized = space.bitos.core.model.UploadedMedia(
                    url = media.url,
                    sha256Hex = media.sha256Hex,
                    mimeType = "video/mp4",
                    sizeBytes = bytes.size.toLong(),
                    width = width,
                    height = height,
                    durationMs = durationMs,
                    thumbUrl = thumbUrl,
                    fallbackUrls = media.fallbackUrls,
                )
                // Soundtrack upload + tag stamping (Wave B): nothing stamps
                // until the audio upload verifies — the same order the
                // safety rule demands for the video itself.
                var extra = parseTags(remixTagsJson)
                if (soundtrackBytes != null && soundtrackBytes.isNotEmpty() && soundtrackProjectJson.isNotBlank()) {
                    val project = space.bitos.core.studio.MemeProjectContract.decode(soundtrackProjectJson)
                    val sound = project?.soundtrack
                    if (project == null || sound == null) {
                        throw BlossomUploader.UploadFailure("The soundtrack row was missing at publish time")
                    }
                    if (sound.url.isNotBlank()) {
                        // Wave D re-attach: the artifact is ALREADY uploaded
                        // (trending rail) — stamp the existing URL directly.
                        val soundTags = space.bitos.core.bridge.BusinessCoreBridge()
                            .memeSoundTagsFor(soundtrackProjectJson)
                        extra = extra + parseTags(soundTags)
                    } else {
                        val audio = withContext(Dispatchers.IO) {
                            uploader.upload(soundtrackBytes, "audio/mp4", signer, DefaultBlossomServer.url)
                        }
                        if (!audio.sha256Hex.equals(sound.sha256, ignoreCase = true)) {
                            throw BlossomUploader.UploadFailure("Soundtrack hash mismatch — nothing was signed")
                        }
                        val stamped = space.bitos.core.studio.MemeProjectContract.encode(
                            project.copy(soundtrack = sound.copy(url = audio.url)),
                        )
                        val soundTags = space.bitos.core.bridge.BusinessCoreBridge()
                            .memeSoundTagsFor(stamped)
                        extra = extra + parseTags(soundTags)
                    }
                }
                mutableMemeState.value = mutableMemeState.value.copy(
                    phase = MemePublishPhase.PUBLISHING,
                    stage = MemePublishStage.BUILD,
                )
                jobLedger.update(ledgerId, stage = MemePublishStage.BUILD.ordinal, mediaUrl = media.url, sha256 = media.sha256Hex)
                publisher.publishMemeVideoNote(
                    caption, altText, contentWarningReason,
                    portrait = space.bitos.core.studio.MemeVideoCutRules.isShortFormDuration(durationMs),
                    media = sized, signerProvider = { signer },
                    writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
                    extraTags = extra,
                    powBits = powBits,
                    onStage = { stage, eventId -> onMemeNoteStage(stage, eventId, ledgerId) },
                )
                watchMemeResult(ledgerId)
            } catch (failure: Exception) {
                mutableMemeState.value = mutableMemeState.value.copy(
                    phase = MemePublishPhase.IDLE,
                    failure = failure.message ?: "Meme publish failed.",
                )
                jobLedger.update(ledgerId, status = "failed", error = failure.message)
            }
        }
    }

    /** A publish call is busy unless the only activity is the render marker. */
    private fun memePublishBusy(): Boolean {
        val state = mutableMemeState.value
        return state.phase == MemePublishPhase.PUBLISHING ||
            (state.phase == MemePublishPhase.UPLOADING && state.stage != MemePublishStage.RENDER)
    }

    private fun onMemeNoteStage(stage: space.bitos.app.data.publish.MemeNoteStage, eventId: String, jobId: Int) {
        if (mutableMemeState.value.jobId != jobId) return
        val mapped = when (stage) {
            space.bitos.app.data.publish.MemeNoteStage.BUILT -> MemePublishStage.SIGN
            space.bitos.app.data.publish.MemeNoteStage.SIGNED -> MemePublishStage.RELAY
            space.bitos.app.data.publish.MemeNoteStage.RELAYED -> MemePublishStage.CONFIRM
        }
        mutableMemeState.value = mutableMemeState.value.copy(
            stage = mapped,
            eventId = eventId.ifBlank { mutableMemeState.value.eventId },
        )
        jobLedger.update(jobId, stage = mapped.ordinal, eventId = eventId.ifBlank { null })
    }

    /**
     * MST-032: uploads the captured cover JPEG separately and hands back
     * its URL (the imeta `thumb`); null on failure — the editor surfaces
     * it, publish proceeds without a cover.
     */
    fun uploadMemeCover(jpegBytes: ByteArray, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val url = try {
                val signer = identity.createSigner() ?: throw IllegalStateException(
                    "Importing needs an identity (Profile tab).",
                )
                withContext(Dispatchers.IO) {
                    uploader.upload(jpegBytes, "image/jpeg", signer, DefaultBlossomServer.url)
                }.url
            } catch (_: Exception) {
                null
            }
            onResult(url)
        }
    }

    /** TagsCodec `[[name,…],…]` JSON → tag rows; junk → empty. */
    private fun parseTags(json: String): List<List<String>> =
        space.bitos.core.store.TagsCodec.decode(json) ?: emptyList()

    /**
     * MST-017 meme publish: rendered PNG bytes → hash-verified Blossom
     * upload → kind-20 picture meme through the same receipt machine. The
     * signer is created BEFORE upload (Blossom auth needs it); nothing is
     * signed until the upload hash verifies (uploader contract).
     */
    fun publishMemePicture(
        bytes: ByteArray,
        width: Int,
        height: Int,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        mimeType: String = "image/png",
        remixTagsJson: String = "",
        powBits: Int = 0,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        if (memePublishBusy()) return
        if (bytes.isEmpty() || bytes.size > Blossom.MAX_FILE_BYTES) {
            mutableMemeState.value = MemePublishUiState(
                failure = "Rendered meme exceeds the ${Blossom.MAX_FILE_BYTES / (1024 * 1024)}MB limit.",
            )
            return
        }
        // A finished attempt leaves `result` set on the SHARED publisher —
        // its guard would silently drop the next publish and the machine
        // would sit on the old state forever ("done, cannot submit").
        // Reset it, exactly like the iOS flow's beginPublish does.
        publisher.dismiss()
        val jobId = mutableMemeState.value.jobId.takeIf { it > 0 } ?: freshJobId()
        mutableMemeState.value = MemePublishUiState(
            phase = MemePublishPhase.UPLOADING,
            stage = MemePublishStage.HASH,
            jobId = jobId,
        )
        val ledgerId = jobLedger.begin(
            if (mimeType == "image/gif") "gif" else "image",
            caption, altText, contentWarningReason, remixTagsJson, "", "",
            bytes, mimeType, width, height, 0L, null, System.currentTimeMillis(),
        )
        mutableMemeState.value = mutableMemeState.value.copy(jobId = ledgerId)
        viewModelScope.launch {
            try {
                val signer = identity.createSigner()
                    ?: throw BlossomUploader.UploadFailure("Importing needs an identity (Profile tab).")
                val media = withContext(Dispatchers.IO) {
                    uploader.upload(
                        bytes, mimeType, signer, DefaultBlossomServer.url,
                        onStage = { stage ->
                            val mapped = when (stage) {
                                BlossomUploader.UploadStage.HASHING -> MemePublishStage.HASH
                                BlossomUploader.UploadStage.UPLOADING -> MemePublishStage.UPLOAD
                                BlossomUploader.UploadStage.VERIFYING -> MemePublishStage.VERIFY
                            }
                            onUploadStage(mapped, ledgerId)
                        },
                        onProgress = ::onUploadBytes,
                    )
                }
                val sized = space.bitos.core.model.UploadedMedia(
                    url = media.url,
                    sha256Hex = media.sha256Hex,
                    mimeType = mimeType,
                    sizeBytes = bytes.size.toLong(),
                    width = width,
                    height = height,
                )
                mutableMemeState.value = mutableMemeState.value.copy(
                    phase = MemePublishPhase.PUBLISHING,
                    stage = MemePublishStage.BUILD,
                )
                jobLedger.update(ledgerId, stage = MemePublishStage.BUILD.ordinal, mediaUrl = media.url, sha256 = media.sha256Hex)
                publisher.publishMemePictureNote(
                    caption, altText, contentWarningReason, sized, { signer },
                    space.bitos.app.data.feed.DefaultRelays.writeUrls,
                    extraTags = parseTags(remixTagsJson),
                    powBits = powBits,
                    onStage = { stage, eventId -> onMemeNoteStage(stage, eventId, ledgerId) },
                )
                watchMemeResult(ledgerId, onResult)
            } catch (failure: Exception) {
                mutableMemeState.value = mutableMemeState.value.copy(
                    phase = MemePublishPhase.IDLE,
                    failure = failure.message ?: "Meme publish failed.",
                )
                jobLedger.update(ledgerId, status = "failed", error = failure.message)
                onResult?.invoke(false, failure.message ?: "Meme publish failed.")
            }
        }
    }

    companion object {
        fun factory(
            app: Application,
            publisher: NotePublisher,
            identity: space.bitos.app.identity.IdentityViewModel,
        ): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    MediaPublishViewModel(app, publisher, identity) as T
            }
    }
}
