package space.bitos.app.ui.feed

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val mutableState = MutableStateFlow(MediaPublishUiState())
    val state: StateFlow<MediaPublishUiState> = mutableState.asStateFlow()

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

    /** Accepts freshly captured bytes from the camera flow. */
    fun mediaCaptured(bytes: ByteArray, mimeType: String) {
        mutableState.value = if (bytes.size <= Blossom.MAX_FILE_BYTES && bytes.isNotEmpty()) {
            MediaPublishUiState(picked = PickedMedia(bytes, mimeType))
        } else {
            MediaPublishUiState(failure = "Recording exceeds the ${Blossom.MAX_FILE_BYTES / (1024 * 1024)}MB limit.")
        }
    }

    fun cancel() {
        mutableState.value = MediaPublishUiState()
    }

    /** hash → upload (hash-verified) → kind-22 → sign → relay fan-out. */
    fun publish(caption: String) {
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
                publisher.publishMediaNote(caption, media, { signer }, space.bitos.app.data.feed.DefaultRelays.writeUrls)
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
