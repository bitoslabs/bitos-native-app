package space.bitos.app.ui.create

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Multi-take merge for the CAP→MEM handoff: concatenates the session's
 * recorded takes (strip order) into one clip through the same Media3
 * Transformer pipeline the trim preview exports with. Bytes in → merged
 * bytes out; null on failure so the caller can fall back to the newest
 * take. A merged result beyond the editor's source cap is refused (the
 * editor would reject it anyway).
 */
object TakeMerger {

    /** Concatenates the takes; a single take passes through unchanged. */
    suspend fun merge(context: Context, takes: List<ByteArray>): ByteArray? =
        withContext(Dispatchers.IO) {
            if (takes.isEmpty()) return@withContext null
            if (takes.size == 1) return@withContext takes.first()

            val cacheDir = File(context.cacheDir, "takes").apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            val sources = takes.mapIndexedNotNull { index, bytes ->
                runCatching {
                    File(cacheDir, "merge-$stamp-$index.mp4").apply { writeBytes(bytes) }
                }.getOrNull()
            }
            if (sources.size != takes.size) {
                sources.forEach { it.delete() }
                return@withContext null
            }
            val output = File(cacheDir, "merged-$stamp.mp4")
            try {
                val items = sources.map {
                    EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(it))).build()
                }
                val composition = Composition.Builder(
                    EditedMediaItemSequence.Builder(items).build(),
                ).build()
                val transformer = Transformer.Builder(context).build()
                awaitTransformation(transformer, composition, output.absolutePath)
                val merged = output.readBytes()
                if (merged.isEmpty() ||
                    merged.size.toLong() > space.bitos.app.ui.create.meme.MemeVideoExport.MAX_SOURCE_BYTES
                ) {
                    null
                } else {
                    merged
                }
            } catch (_: Exception) {
                null
            } finally {
                sources.forEach { runCatching { it.delete() } }
                runCatching { output.delete() }
            }
        }

    private suspend fun awaitTransformation(
        transformer: Transformer,
        composition: Composition,
        outputPath: String,
    ) = suspendCancellableCoroutine { cont ->
        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                if (cont.isActive) cont.resumeWithException(exportException)
            }
        })
        transformer.start(composition, outputPath)
        cont.invokeOnCancellation { transformer.cancel() }
    }
}
