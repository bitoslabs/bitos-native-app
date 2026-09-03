package space.bitos.app.ui.create.meme

import android.graphics.Bitmap

/**
 * GIF frame source for the Quick MEM editor (plan MST-020): decodes an
 * animated GIF through the SHARED pure decoder (byte-identical timing
 * extraction on both platforms — no platform API has frame-accurate GIF
 * decode) and converts the composited RGBA frames to Bitmaps, bounded by
 * the project contract (≤ maxFrames, delays ≥ 20 ms — the decoder already
 * applies the sub-2cs → 100 ms heuristic). Static images decode as one
 * 100 ms still (web planner parity).
 */
object GifFrameSource {

    const val STILL_DELAY_MS = 100

    class Frames(
        val bitmaps: List<Bitmap>,
        val delaysMs: List<Int>,
    ) {
        val frameCount: Int get() = bitmaps.size
        val isAnimated: Boolean get() = bitmaps.size > 1
    }

    /** Decodes GIF bytes (already read + size-bounded by the caller). */
    fun decode(gifBytes: ByteArray, maxFrames: Int): Frames? {
        val decoded = space.bitos.core.studio.GifDecoder.decode(gifBytes) ?: return null
        val pixels = IntArray(decoded.width * decoded.height)
        val bitmaps = mutableListOf<Bitmap>()
        val delays = mutableListOf<Int>()
        decoded.frames.take(maxFrames).forEach { frame ->
            var source = 0
            var target = 0
            while (source + 3 < frame.rgba.size && target < pixels.size) {
                val alpha = frame.rgba[source + 3].toInt() and 0xFF
                pixels[target] = (alpha shl 24) or
                    ((frame.rgba[source].toInt() and 0xFF) shl 16) or
                    ((frame.rgba[source + 1].toInt() and 0xFF) shl 8) or
                    (frame.rgba[source + 2].toInt() and 0xFF)
                source += 4
                target += 1
            }
            bitmaps += Bitmap.createBitmap(pixels, decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            delays += frame.delayMs
        }
        if (bitmaps.isEmpty()) return null
        return Frames(bitmaps, delays)
    }
}
