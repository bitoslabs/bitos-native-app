package space.bitos.core.studio

import kotlin.math.max
import kotlin.math.min

/**
 * True-GIF export planning (plan MST-022; port of the web
 * `gif-export.ts` slice the V1 studio needs — base frames cadence, no
 * animated layers or cue tracks yet). Pure math: WHICH source moments
 * the encoder paints and how long each encoded frame holds, with the
 * 20 ms centisecond floor and the 360-frame guard.
 */
object GifExportPlan {

    /** GIF centisecond floor (sub-2cs delays can't be expressed). */
    const val MIN_STEP_SEC = 0.02

    /** Lightness guard (web MAX_GIF_EXPORT_FRAMES). */
    const val MAX_FRAMES = 360

    /** Uniform fallback cadence when nothing frames the source (12 fps). */
    const val FALLBACK_FPS = 12

    data class FrameTiming(
        /** Frame start in source-local seconds. */
        val atSec: Double,
        /** Hold time in seconds. */
        val durationSec: Double,
    )

    data class Step(
        /** Source moment to paint (seconds from loop start). */
        val atSec: Double,
        /** Encoded hold (ms, ≥ 20 — the GIF centisecond floor). */
        val delayMs: Int,
    )

    data class Plan(
        val steps: List<Step>,
        /** Total loop length in seconds (sum of holds). */
        val durationSec: Double,
        /** True when the 360-frame guard trimmed the loop (callers warn). */
        val capped: Boolean,
    )

    fun trackDuration(frames: List<FrameTiming>): Double =
        max(0.0, frames.fold(0.0) { sum, frame -> sum + frame.durationSec })

    /**
     * Plans the export loop over decoded base frames. A pinned length
     * only TRIMS — a longer pick can't extend the material (the
     * NETSCAPE loop tag handles repetition). Static/empty sources export
     * as one 100 ms frame (web parity).
     */
    fun plan(
        baseFrames: List<FrameTiming>,
        pinnedSec: Double? = null,
    ): Plan {
        val baseDur = trackDuration(baseFrames)
        val natural = baseDur
        val pinned = pinnedSec?.takeIf { it.isFinite() }?.let { max(0.0, it) }
        val duration = min(pinned ?: Double.POSITIVE_INFINITY, natural)

        if (duration <= 0.0) {
            return Plan(listOf(Step(0.0, 100)), 0.1, capped = false)
        }

        // Cadence: the base's own frames (exact original timing), tiled
        // when a pinned length runs longer than one pass.
        val times = mutableListOf<Double>()
        val passDur = trackDuration(baseFrames)
        var pass = 0
        while (pass * passDur < duration) {
            baseFrames.forEach { frame ->
                val t = pass * passDur + frame.atSec
                if (t < duration) times += t
            }
            pass += 1
        }
        times.sort()

        // Collapse clusters closer than the centisecond floor; holds span
        // to the NEXT KEPT boundary (or the loop end).
        val kept = mutableListOf<Double>()
        for (t in times) {
            if (kept.isNotEmpty() && t - kept.last() < MIN_STEP_SEC - 1e-9) continue
            kept += t
        }
        val steps = kept.mapIndexed { index, t ->
            Step(
                atSec = t,
                delayMs = max(
                    MIN_STEP_SEC,
                    min((kept.getOrNull(index + 1) ?: duration) - t, duration - t),
                ).let { hold -> (hold * 1000).toInt() },
            )
        }.ifEmpty { listOf(Step(0.0, (duration * 1000).toInt())) }

        val capped = steps.size > MAX_FRAMES
        val bounded = if (capped) steps.take(MAX_FRAMES) else steps
        return Plan(
            steps = bounded,
            durationSec = bounded.fold(0.0) { sum, step -> sum + step.delayMs / 1000.0 },
            capped = capped,
        )
    }

    /**
     * Size guard ladder (web MST-022 rule): an oversize encode (> [cap]
     * bytes) halves the long edge and re-encodes, deterministically, up
     * to [maxSteps] times. Returns the first (largest) acceptable size.
     */
    object SizeLadder {

        const val CAP_BYTES = 8L * 1024 * 1024
        const val MAX_STEPS = 3

        /** The canvas for step [step] (0 = original), or null = give up. */
        fun canvasFor(originalWidth: Int, originalHeight: Int, step: Int): Pair<Int, Int>? {
            if (step < 0 || step > MAX_STEPS) return null
            var width = originalWidth
            var height = originalHeight
            repeat(step) {
                width = (width + 1) / 2
                height = (height + 1) / 2
            }
            // Keep GIF-legal (≥1 px) and even-ish dims like the raster path.
            width = max(2, width - (width % 2))
            height = max(2, height - (height % 2))
            return width to height
        }

        fun shouldStep(encodedBytes: Int, step: Int): Boolean =
            encodedBytes > CAP_BYTES && step < MAX_STEPS
    }
}
