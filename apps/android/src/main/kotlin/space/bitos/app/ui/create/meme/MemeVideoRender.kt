package space.bitos.app.ui.create.meme

import android.content.Context
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns Transformer on its application looper until completion or cancellation. */
internal object MemeVideoRender {
    suspend fun render(context: Context, composition: Composition, path: String, timeoutMs: Long) {
        withContext(Dispatchers.Main) {
            var transformer: Transformer? = null
            try {
                withTimeout(timeoutMs) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        transformer = Transformer.Builder(context)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }

                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (continuation.isActive) continuation.resumeWithException(exportException)
                                }
                            }).build()
                        transformer!!.start(composition, path)
                    }
                }
            } finally {
                // Runs on Main before the caller removes temporary input/output files.
                transformer?.cancel()
            }
        }
    }
}
