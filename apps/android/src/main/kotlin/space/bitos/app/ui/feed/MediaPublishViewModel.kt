package space.bitos.app.ui.feed

import android.app.Application
import android.net.Uri
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
import space.bitos.app.data.publish.NotePublisher
import space.bitos.core.model.Blossom

/**
 * Activity-scoped media publish flow shared by Create (record/import) and
 * Home (import): picked or captured bytes → hash-verified Blossom upload →
 * kind-22 through the receipt machine. All heavy work runs off the main
 * thread; failures surface in state, never crash.
 */
class MediaPublishViewModel(
    application: Application,
    private val publisher: NotePublisher,
    private val identity: space.bitos.app.identity.IdentityViewModel,
) : AndroidViewModel(application) {

    private val uploader = BlossomUploader()
    private val jobLedger = MemePublishJobStore(application)

    private val mutableState = MutableStateFlow(MediaPublishUiState())
    val state: StateFlow<MediaPublishUiState> = mutableState.asStateFlow()

    /** MST-017 meme lane — separate flow so the import path stays untouched. */
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

    private fun freshJobId(): Int {
        memeJobCounter = (1000..9999).random()
        return memeJobCounter
    }

    /**
     * Completes the machine stepper when the receipt machine resolves:
     * CONFIRM + the relay hosts that accepted the event.
     */
    private fun watchMemeResult(jobId: Int) {
        viewModelScope.launch {
            val resolved = withTimeoutOrNull(15_000) {
                publisher.state.first { it.result != null }
            }
            if (mutableMemeState.value.jobId != jobId) return@launch
            val accepted = resolved?.receipts
                ?.filter { it.accepted == true }
                ?.map { it.relay.value.removePrefix("wss://").removePrefix("ws://").substringBefore('/') }
                .orEmpty()
            mutableMemeState.value = mutableMemeState.value.copy(
                stage = MemePublishStage.CONFIRM,
                terminal = true,
                confirmedRelayHosts = accepted,
            )
            if (resolved?.result == space.bitos.app.data.publish.PublishResult.PUBLISHED) {
                jobLedger.finish(jobId)
            } else {
                jobLedger.update(
                    jobId, status = "failed",
                    error = "No relay confirmed the event — it may still land; verify before retrying.",
                )
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
                    uploader.upload(bytes, job.mime, signer, DefaultBlossomServer.url) { stage ->
                        val mapped = when (stage) {
                            BlossomUploader.UploadStage.HASHING -> MemePublishStage.HASH
                            BlossomUploader.UploadStage.UPLOADING -> MemePublishStage.UPLOAD
                            BlossomUploader.UploadStage.VERIFYING -> MemePublishStage.VERIFY
                        }
                        mutableMemeState.value = mutableMemeState.value.copy(stage = mapped)
                        jobLedger.update(job.id, stage = mapped.ordinal)
                    }
                }
                mutableMemeState.value = mutableMemeState.value.copy(
                    phase = MemePublishPhase.PUBLISHING, stage = MemePublishStage.BUILD,
                )
                jobLedger.update(job.id, stage = MemePublishStage.BUILD.ordinal, mediaUrl = media.url, sha256 = media.sha256Hex)
                val extraTags = parseTags(job.extraTagsJson)
                if (job.mode == "video") {
                    publisher.publishMemeVideoNote(
                        job.caption, job.altText, job.contentWarningReason,
                        portrait = job.height >= job.width,
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
                mutableMemeState.value = mutableMemeState.value.copy(phase = MemePublishPhase.DONE)
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
            "video", caption, altText, contentWarningReason, remixTagsJson, "", "",
            bytes, "video/mp4", width, height, durationMs, thumbUrl, System.currentTimeMillis(),
        )
        viewModelScope.launch {
            try {
                val signer = identity.createSigner()
                    ?: throw BlossomUploader.UploadFailure("Importing needs an identity (Profile tab).")
                val media = withContext(Dispatchers.IO) {
                    uploader.upload(bytes, "video/mp4", signer, DefaultBlossomServer.url) { stage ->
                        val mapped = when (stage) {
                            BlossomUploader.UploadStage.HASHING -> MemePublishStage.HASH
                            BlossomUploader.UploadStage.UPLOADING -> MemePublishStage.UPLOAD
                            BlossomUploader.UploadStage.VERIFYING -> MemePublishStage.VERIFY
                        }
                        mutableMemeState.value = mutableMemeState.value.copy(stage = mapped)
                        jobLedger.update(ledgerId, stage = mapped.ordinal)
                    }
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
                )
                mutableMemeState.value = mutableMemeState.value.copy(
                    phase = MemePublishPhase.PUBLISHING,
                    stage = MemePublishStage.BUILD,
                )
                jobLedger.update(ledgerId, stage = MemePublishStage.BUILD.ordinal, mediaUrl = media.url, sha256 = media.sha256Hex)
                publisher.publishMemeVideoNote(
                    caption, altText, contentWarningReason,
                    portrait = height >= width,
                    media = sized, signerProvider = { signer },
                    writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
                    extraTags = parseTags(remixTagsJson),
                    powBits = powBits,
                    onStage = { stage, eventId -> onMemeNoteStage(stage, eventId, jobId) },
                )
                mutableMemeState.value = mutableMemeState.value.copy(phase = MemePublishPhase.DONE)
                watchMemeResult(jobId)
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

    /** Reads picked gallery content (bounded) into the flow. */
    fun mediaPicked(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { readBounded(uri, resolver) }
            mutableState.value = if (picked != null) {
                MediaPublishUiState(picked = picked)
            } else {
                MediaPublishUiState(failure = "Media exceeds the ${Blossom.MAX_FILE_BYTES / (1024 * 1024)}MB limit or could not be read.")
            }
        }
    }

    fun cancel() {
        mutableState.value = MediaPublishUiState()
        mutableMemeState.value = MemePublishUiState()
    }

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
        viewModelScope.launch {
            try {
                val signer = identity.createSigner()
                    ?: throw BlossomUploader.UploadFailure("Importing needs an identity (Profile tab).")
                val media = withContext(Dispatchers.IO) {
                    uploader.upload(bytes, mimeType, signer, DefaultBlossomServer.url) { stage ->
                        val mapped = when (stage) {
                            BlossomUploader.UploadStage.HASHING -> MemePublishStage.HASH
                            BlossomUploader.UploadStage.UPLOADING -> MemePublishStage.UPLOAD
                            BlossomUploader.UploadStage.VERIFYING -> MemePublishStage.VERIFY
                        }
                        mutableMemeState.value = mutableMemeState.value.copy(stage = mapped)
                        jobLedger.update(ledgerId, stage = mapped.ordinal)
                    }
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
                    onStage = { stage, eventId -> onMemeNoteStage(stage, eventId, jobId) },
                )
                mutableMemeState.value = mutableMemeState.value.copy(phase = MemePublishPhase.DONE)
                watchMemeResult(jobId)
                onResult?.invoke(true, null)
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

    /** hash → upload (hash-verified) → kind-22 → sign → relay fan-out. */
    fun publish(caption: String, altText: String = "", contentWarningReason: String? = null) {
        val picked = mutableState.value.picked ?: return
        mutableState.value = MediaPublishUiState(picked = picked, phase = MediaPublishPhase.UPLOADING)
        viewModelScope.launch {
            try {
                val signer = identity.createSigner()
                    ?: throw BlossomUploader.UploadFailure("Importing needs an identity (Profile tab).")
                val media = withContext(Dispatchers.IO) {
                    uploader.upload(picked.bytes, picked.mimeType, signer, DefaultBlossomServer.url)
                }
                mutableState.value = MediaPublishUiState(picked = picked, phase = MediaPublishPhase.PUBLISHING)
                publisher.publishMediaNote(
                    caption, media, { signer }, space.bitos.app.data.feed.DefaultRelays.writeUrls,
                    altText, contentWarningReason,
                )
                mutableState.value = MediaPublishUiState(phase = MediaPublishPhase.DONE)
            } catch (failure: Exception) {
                mutableState.value = MediaPublishUiState(picked = picked, failure = failure.message ?: "Media publish failed.")
            }
        }
    }

    private fun readBounded(uri: Uri, resolver: android.content.ContentResolver): PickedMedia? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > Blossom.MAX_FILE_BYTES) return null
                buffer.write(chunk, 0, read)
            }
            val mime = resolver.getType(uri) ?: "video/mp4"
            PickedMedia(buffer.toByteArray(), mime)
        }
    }.getOrNull()

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
