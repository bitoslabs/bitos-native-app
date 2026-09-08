package space.bitos.app.ui.create.meme

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * "Use this sound" Wave B extraction (plan docs/product/
 * use-this-sound-plan.md §5): pull a picked video's audio track out as a
 * bounded m4a — PASSTHROUGH (no transcode: extractor → muxer sample copy,
 * fast and lossless) — then decode that m4a to mono float PCM for the
 * shared bed math (`MemeSoundMix`). The m4a IS the publish artifact
 * (sha256 over these bytes; uploaded before any signing); the PCM is the
 * preview/export bed. All codec work runs off the main thread (callers
 * wrap in Dispatchers.IO).
 */
internal object MemeVideoSound {

    /** One extracted soundtrack: the publishable bytes + bed metadata. */
    data class Extracted(
        val m4aBytes: ByteArray,
        val durationMs: Long,
        val nativeRate: Int,
        val sha256Hex: String,
    )

    /**
     * Passthrough audio extraction, capped at [space.bitos.core.studio.MemeSoundRules.MAX_SOUND_DURATION_MS].
     * Null when the source has no readable audio track.
     */
    fun extract(context: Context, uri: Uri): Extracted? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var output: File? = null
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
            } ?: return null
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            output = File.createTempFile("meme-sound", ".m4a", context.cacheDir)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()
            val maxUs = space.bitos.core.studio.MemeSoundRules.MAX_SOUND_DURATION_MS * 1_000L
            val buffer = java.nio.ByteBuffer.allocateDirect(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var lastUs = 0L
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME
                if (extractor.sampleTime > maxUs) break
                muxer.writeSampleData(outTrack, buffer, info)
                lastUs = extractor.sampleTime
                extractor.advance()
            }
            muxer.stop()
            val bytes = output.readBytes()
            if (bytes.isEmpty() || lastUs <= 0L) return null
            return Extracted(
                m4aBytes = bytes,
                durationMs = lastUs / 1_000L,
                nativeRate = rate,
                sha256Hex = sha256Hex(bytes),
            )
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            output?.let { file -> runCatching { file.delete() } }
        }
    }

    /**
     * Decodes the extracted m4a to MONO float PCM at its native rate
     * (channels averaged; `MemeSoundMix.resample` owns rate conversion so
     * both platforms mix identically). Null when the codec refuses.
     */
    fun decodePcm(context: Context, m4aBytes: ByteArray): Pair<FloatArray, Int>? {
        val input = File.createTempFile("meme-sound-in", ".m4a", context.cacheDir)
        return try {
            input.writeBytes(m4aBytes)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(input.absolutePath)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return null
                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
                val codec = MediaCodec.createDecoderByType(mime)
                val pcm = ArrayList<Float>(1 shl 18)
                val info = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                codec.configure(format, null, null, 0)
                codec.start()
                try {
                    while (!sawOutputEos) {
                        if (!sawInputEos) {
                            val inIndex = codec.dequeueInputBuffer(10_000)
                            if (inIndex >= 0) {
                                val inBuffer = codec.getInputBuffer(inIndex)!!
                                val size = extractor.readSampleData(inBuffer, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEos = true
                                } else {
                                    codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            else -> if (outIndex >= 0) {
                                val outBuffer = codec.getOutputBuffer(outIndex)!!
                                val chunk = java.nio.ByteBuffer.allocate(info.size)
                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    .put(outBuffer)
                                chunk.flip()
                                // Whole frames only (channels averaged to mono);
                                // a trailing partial frame is dropped.
                                val frames = chunk.remaining() / 2 / channels
                                repeat(frames) {
                                    var acc = 0
                                    repeat(channels) { acc += chunk.short.toInt() }
                                    pcm.add((acc.toFloat() / channels) / 32768f)
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    sawOutputEos = true
                                }
                            }
                        }
                    }
                } finally {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
                if (pcm.isEmpty()) return null
                FloatArray(pcm.size) { pcm[it] } to rate
            } finally {
                runCatching { extractor.release() }
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { input.delete() }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
