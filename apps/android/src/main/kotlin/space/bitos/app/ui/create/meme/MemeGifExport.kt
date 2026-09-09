package space.bitos.app.ui.create.meme

import android.graphics.Bitmap
import space.bitos.core.studio.GifEncodeFrame
import space.bitos.core.studio.GifEncoder
import space.bitos.core.studio.GifExportPlan
import space.bitos.core.studio.MemeProject

/**
 * GIF export glue (plan MST-022): frames + project → looping GIF89a bytes.
 * The shared planner picks WHICH moments hold and for how long (source
 * cadence, 20 ms floor, ≤360 cap), the raster paints each frame with the
 * overlays burned in, the shared encoder emits bytes, and the size ladder
 * halves the long edge (≤3 steps) when the encode busts the 8 MB cap.
 * Deterministic: same inputs → same ladder decision.
 */
object MemeGifExport {

    data class Result(
        val gifBytes: ByteArray,
        val canvasWidth: Int,
        val canvasHeight: Int,
        /** Ladder steps taken (0 = first canvas fit). */
        val ladderStep: Int,
        /** The 360-frame guard trimmed the loop (callers warn). */
        val capped: Boolean,
    )

    /**
     * @param frames decoded source frames (reordered already; the plan's
     *   steps index into this list via `atSec` against the frame timeline).
     * @param frameDelaysMs per-frame holds AFTER any uniform-delay override;
     *   the planner re-applies the 20 ms floor and the 360 cap.
     */
    fun export(
        frames: List<Bitmap>,
        frameDelaysMs: List<Int>,
        project: MemeProject,
        imageFor: ((String) -> Bitmap?)? = null,
    ): Result? {
        if (frames.isEmpty()) return null
        val delays = frameDelaysMs.take(frames.size).map { delay ->
            delay.coerceIn(
                space.bitos.core.studio.MemeProjectContract.MIN_FRAME_DELAY_MS,
                space.bitos.core.studio.MemeProjectContract.MAX_FRAME_DELAY_MS,
            )
        }
        val timings = buildTimings(delays)
        val plan = GifExportPlan.plan(timings)

        val source = frames.first()
        val original = GifExportPlan.SizeLadder.canvasFor(source.width, source.height, 0) ?: return null
        var step = 0
        while (true) {
            val canvas = GifExportPlan.SizeLadder.canvasFor(original.first, original.second, step)
                ?: return null
            val encoded = encodeAt(frames, delays, plan, project, canvas.first, canvas.second, imageFor)
            if (encoded != null && !GifExportPlan.SizeLadder.shouldStep(encoded.size, step)) {
                return Result(encoded, canvas.first, canvas.second, step, plan.capped)
            }
            if (step >= GifExportPlan.SizeLadder.MAX_STEPS) {
                // Accept the last encode even oversize — the caller surfaces
                // the size honestly rather than failing the export.
                return encoded?.let {
                    Result(it, canvas.first, canvas.second, step, plan.capped)
                }
            }
            step += 1
        }
    }

    private fun encodeAt(
        frames: List<Bitmap>,
        delays: List<Int>,
        plan: GifExportPlan.Plan,
        project: MemeProject,
        width: Int,
        height: Int,
        imageFor: ((String) -> Bitmap?)? = null,
    ): ByteArray? {
        val encodeFrames = plan.steps.map { step ->
            val frame = frameActiveAt(frames, delays, step.atSec)
            GifEncodeFrame(
                rgba = MemeRaster.renderFrameRgba(
                    frame, project, width, height, imageFor,
                    // Timed plan clock (MST-077): each step paints at its
                    // own moment — fx windows + transforms go kinetic.
                    (step.atSec * 1000).toLong(),
                ),
                delayMs = step.delayMs,
            )
        }
        return try {
            GifEncoder.encode(encodeFrames, width, height)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The source frame active at composition time (cover of the timeline). */
    private fun frameActiveAt(frames: List<Bitmap>, delays: List<Int>, atSec: Double): Bitmap {
        var acc = 0.0
        frames.forEachIndexed { index, frame ->
            acc += delays[index] / 1000.0
            if (atSec < acc) return frame
        }
        return frames.last()
    }

    private fun buildTimings(delays: List<Int>): List<GifExportPlan.FrameTiming> {
        var at = 0.0
        return delays.map { delay ->
            val row = GifExportPlan.FrameTiming(atSec = at, durationSec = delay / 1000.0)
            at += delay / 1000.0
            row
        }
    }
}
