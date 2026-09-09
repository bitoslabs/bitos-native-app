package space.bitos.app.ui.create.meme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import space.bitos.core.studio.MemeProject

/**
 * Video→GIF sampler (plan `meme-blank-canvas-crossmode-plan.md` D4 /
 * MST-081): renders the composed timeline into GIF source frames by
 * decoding the RAW clip windows (MediaMetadataRetriever, closest-frame)
 * — the GIF encoder then paints the timed overlay plan per frame, so
 * overlays, fx kinetics, layers, looks and letterboxing arrive exactly
 * like the MP4 burn, with no double-paint (the composed MP4 is never
 * re-decoded) and no extra encode pass.
 *
 * Bounds: 10 fps, the FIRST 10 s of the timeline, 480 px long-edge
 * frames — the GIF ladder (≤3 halvings, 8 MB) owns the final size.
 */
object VideoGifSampler {

    private val SAMPLE_FPS = space.bitos.core.studio.MemeCanvas.VIDEO_GIF_FPS
    private val MAX_SPAN_MS = space.bitos.core.studio.MemeCanvas.VIDEO_GIF_MAX_SPAN_MS
    private const val LONG_EDGE = 480

    data class Sampled(
        val frames: List<Bitmap>,
        val delaysMs: List<Int>,
        /** Timeline longer than the sampled span — say so honestly. */
        val trimmed: Boolean,
    )

    /** Returns null when no clip decodes (corrupt sources). */
    fun sample(
        context: Context,
        clips: List<MemeVideoExport.ClipInput>,
        project: MemeProject,
    ): Sampled? {
        if (clips.isEmpty()) return null
        val outputs = clips.map { clip ->
            (((clip.endMs - clip.startMs).coerceAtLeast(0L)) / clip.speed).toLong()
        }
        val totalMs = outputs.sum()
        if (totalMs <= 0L) return null
        val spanMs = minOf(totalMs, MAX_SPAN_MS)
        val frameCount = space.bitos.core.studio.MemeCanvas.videoGifFrameCount(totalMs)
        val delayMs = space.bitos.core.studio.MemeCanvas.videoGifDelayMs()

        val temps = mutableListOf<File>()
        val retrievers = mutableListOf<MediaMetadataRetriever>()
        try {
            clips.forEach { clip ->
                val temp = File.createTempFile("gif-sample", ".mp4", context.cacheDir)
                temp.writeBytes(clip.bytes)
                temps += temp
                retrievers += MediaMetadataRetriever().apply { setDataSource(temp.absolutePath) }
            }

            // The FIRST frame defines the GIF canvas (same convention as
            // picked-GIF exports); even dims keep the encoder happy.
            fun decodeAt(timelineMs: Long): Bitmap? {
                var offset = 0L
                clips.forEachIndexed { index, clip ->
                    val outMs = outputs[index]
                    if (timelineMs < offset + outMs || index == clips.lastIndex) {
                        val mediaMs = clip.startMs +
                            (((timelineMs - offset).coerceAtLeast(0L)) * clip.speed).toLong()
                        val retriever = retrievers[index]
                        return retriever.getFrameAtTime(
                            mediaMs.coerceIn(clip.startMs, (clip.endMs - 1).coerceAtLeast(clip.startMs)) * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                        )
                    }
                    offset += outMs
                }
                return null
            }

            val first = decodeAt(0L) ?: return null
            var baseW = LONG_EDGE
            var baseH = ((LONG_EDGE.toLong() * first.height) / first.width).toInt()
            if (baseH > LONG_EDGE) {
                baseH = LONG_EDGE
                baseW = ((LONG_EDGE.toLong() * first.width) / first.height).toInt()
            }
            baseW -= baseW % 2
            baseH -= baseH % 2
            if (baseW <= 0 || baseH <= 0) return null

            val bg = project.canvasBg?.let { hex ->
                runCatching { Color.parseColor(hex) }.getOrNull()
            } ?: Color.BLACK
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            val frames = (0 until frameCount).mapNotNull { index ->
                val source = if (index == 0) first else decodeAt(index.toLong() * delayMs)
                val out = Bitmap.createBitmap(baseW, baseH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.drawColor(bg)
                if (source != null) {
                    // Letterbox onto the base canvas (mixed-clip timelines).
                    val scale = minOf(
                        baseW.toFloat() / source.width,
                        baseH.toFloat() / source.height,
                    )
                    val w = source.width * scale
                    val h = source.height * scale
                    canvas.drawBitmap(
                        source,
                        null,
                        RectF((baseW - w) / 2f, (baseH - h) / 2f, (baseW + w) / 2f, (baseH + h) / 2f),
                        paint,
                    )
                }
                if (source != null && source !== first) source.recycle()
                out
            }
            if (frames.isEmpty()) return null
            return Sampled(frames, List(frames.size) { delayMs }, space.bitos.core.studio.MemeCanvas.videoGifSpanTrimmed(totalMs))
        } catch (_: Exception) {
            return null
        } finally {
            retrievers.forEach { runCatching { it.release() } }
            temps.forEach { runCatching { it.delete() } }
        }
    }
}
