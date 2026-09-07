package space.bitos.core.studio

/**
 * Animated GIF image-layer rules (web `meme/gif.ts` `gifLayerPainter`
 * parity): a GIF layer paints the frame active at MEDIA TIME and LOOPS
 * for the whole export — the paint clock runs tens of seconds while a
 * sticker clip is ~1–3 s, so clamping past-the-end times would freeze the
 * layer after its first pass (the web "GIF stopped moving in my video"
 * bug — wrap instead).
 *
 * Pure and common-tested: Android and iOS select identical frames at
 * identical times, so the stage preview and both exporters stay WYSIWYG.
 * The wire needs NO change — GIF-ness rides the image-layer asset bytes
 * (≥2 decoded frames = animated; else it stays a still image layer).
 */
object GifLayerRules {

    /** ≥2 frames = animated (a single-frame GIF is a still image layer). */
    fun isAnimated(frameCount: Int): Boolean = frameCount >= 2

    /** Single-pass duration (ms); ≥1 so modulo can never divide by zero. */
    fun totalDurationMs(delaysMs: List<Int>): Long =
        delaysMs.sum().toLong().coerceAtLeast(1L)

    /**
     * Frame index active at [atMs] (media time), LOOPING past the end —
     * `atMs` is the layer's media clock; negative times (scrub guards)
     * wrap via mod, never crash.
     */
    fun frameIndexAt(delaysMs: List<Int>, atMs: Long): Int {
        if (delaysMs.isEmpty()) return 0
        val total = totalDurationMs(delaysMs)
        var loopsIn = atMs.mod(total)
        delaysMs.forEachIndexed { index, delay ->
            val hold = delay.coerceAtLeast(1)
            if (loopsIn < hold) return index
            loopsIn -= hold
        }
        return delaysMs.lastIndex
    }
}
