package space.bitos.app.ui.create.meme

import android.content.Context
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.container.Mp4OrientationData
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns Transformer on its application looper until completion or cancellation.
 *
 * Studio output is a new public artifact, never a container copy of the picked
 * clip. The muxer deliberately drops source metadata (location, device and
 * creation fields) rather than relying on an exporter default that may change.
 *
 * Encoder targets (MST-036) come from the shared `MemeExportPresets` table —
 * never encoder defaults, so the pre-export MB estimate stays honest.
 */
internal object MemeVideoRender {
    /**
     * [onProgress] receives the encoder's REAL completion fraction (0..1)
     * from Transformer's pollable progress API while the export runs —
     * the render stage's percent, never a timer.
     */
    suspend fun render(
        context: Context,
        composition: Composition,
        path: String,
        timeoutMs: Long,
        videoBitrateBps: Int,
        audioBitrateBps: Int,
        onProgress: ((Float) -> Unit)? = null,
    ) {
        withContext(Dispatchers.Main) {
            var transformer: Transformer? = null
            val progressHolder = androidx.media3.transformer.ProgressHolder()
            // Same looper as the Transformer (getProgress contract); the
            // suspension below frees Main between polls.
            val poller = launch {
                while (isActive) {
                    delay(200)
                    val current = transformer ?: continue
                    if (current.getProgress(progressHolder) ==
                        Transformer.PROGRESS_STATE_AVAILABLE
                    ) {
                        onProgress?.invoke(progressHolder.progress.coerceIn(0, 100) / 100f)
                    }
                }
            }
            try {
                withTimeout(timeoutMs) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val encoderFactory = androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                androidx.media3.transformer.VideoEncoderSettings.Builder()
                                    .setBitrate(videoBitrateBps)
                                    .build(),
                            )
                            .setRequestedAudioEncoderSettings(
                                androidx.media3.transformer.AudioEncoderSettings.Builder()
                                    .setBitrate(audioBitrateBps)
                                    .build(),
                            )
                            .build()
                        transformer = Transformer.Builder(context)
                            .setMuxerFactory(PrivacySafeMuxerFactory())
                            .setEncoderFactory(encoderFactory)
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
                onProgress?.invoke(1f)
            } finally {
                // Runs on Main before the caller removes temporary input/output files.
                poller.cancel()
                transformer?.cancel()
            }
        }
    }

    /** Allows media samples through while refusing every input metadata entry. */
    private class PrivacySafeMuxerFactory : Muxer.Factory {
        private val delegate = DefaultMuxer.Factory()

        override fun create(path: String): Muxer = PrivacySafeMuxer(delegate.create(path))

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
            delegate.getSupportedSampleMimeTypes(trackType)

        override fun supportsWritingNegativeTimestampsInEditList(): Boolean =
            delegate.supportsWritingNegativeTimestampsInEditList()
    }

    private class PrivacySafeMuxer(private val delegate: Muxer) : Muxer {
        override fun addTrack(format: Format): Int = delegate.addTrack(format)

        override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) {
            delegate.writeSampleData(trackId, byteBuffer, bufferInfo)
        }

        override fun addMetadataEntry(metadataEntry: Metadata.Entry) {
            // Orientation is needed for correct playback. Everything else may
            // identify the source device, location or its creation history.
            if (metadataEntry is Mp4OrientationData) delegate.addMetadataEntry(metadataEntry)
        }

        override fun close() = delegate.close()
    }
}
