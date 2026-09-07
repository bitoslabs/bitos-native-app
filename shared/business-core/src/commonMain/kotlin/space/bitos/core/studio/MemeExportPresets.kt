package space.bitos.core.studio

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Export quality/size presets (MST-036): the single cross-platform source
 * for resolution × quality → target video bitrate, the pre-export MB
 * estimate, and the publish estimate gate. Pure and deterministic — the
 * Android (Media3) and iOS (AVFoundation) adapters consume the same
 * table so estimate, gate and encoder targets never drift.
 *
 *  • [AUTO] is the default and equals the pre-MST-036 behavior: 1080p
 *    long-edge cap at the highest tier, publish fit owned by the cut
 *    ladder ([MemeVideoCutRules]) — never by this gate.
 *  • Manual presets are publish-gated BY ESTIMATE with headroom for ABR
 *    overshoot (a target bitrate is a target, not a cap) and manual
 *    means manual: an over-bound pick is surfaced to the creator to
 *    re-pick, never silently degraded.
 *  • Size is only ever an OUTPUT: the MB preview derives from the
 *    preset + trimmed duration; there is no "target MB" input.
 */
object MemeExportPresets {

    /** Output resolution tiers — long-edge caps, never upscaled. */
    enum class Resolution(val longEdge: Int) {
        P1080(1080),
        P720(720),
        P480(480),
    }

    enum class Quality { HIGH, MEDIUM, LOW }

    /**
     * One manual pick. [videoBitrateBps] is the ENCODER TARGET the native
     * exporters must configure (Media3 `VideoEncoderSettings`, iOS the
     * writer's `averageBitRate`) — defaults are not acceptable.
     */
    data class Preset(
        val resolution: Resolution,
        val quality: Quality,
    ) {
        val videoBitrateBps: Int = bitrate(resolution, quality)
        val longEdge: Int = resolution.longEdge
    }

    /** Default pick: exactly the pre-MST-036 export (1080p / 6 Mbps tier). */
    val AUTO = Preset(Resolution.P1080, Quality.HIGH)

    /** Audio track target (AAC, both exporters configure it explicitly). */
    const val AUDIO_BITRATE_BPS = 128_000

    /** MP4 container overhead applied on top of the stream estimate. */
    const val CONTAINER_OVERHEAD_PERCENT = 3L

    /**
     * Manual publish gate: the ESTIMATE must fit the Blossom bound with
     * ~10% headroom for ABR overshoot. An estimate above this blocks
     * Publish until the creator picks a lower preset; the cut ladder
     * never touches manual picks.
     */
    val PUBLISH_GATE_BYTES: Long = space.bitos.core.model.Blossom.MAX_FILE_BYTES * 9 / 10

    /** The preset table (bps). Pins live in the common tests — bumps are
     *  deliberate, never drift. */
    fun bitrate(resolution: Resolution, quality: Quality): Int = when (resolution) {
        Resolution.P1080 -> when (quality) {
            Quality.HIGH -> 6_000_000
            Quality.MEDIUM -> 4_000_000
            Quality.LOW -> 2_500_000
        }
        Resolution.P720 -> when (quality) {
            Quality.HIGH -> 4_000_000
            Quality.MEDIUM -> 2_500_000
            Quality.LOW -> 1_500_000
        }
        Resolution.P480 -> when (quality) {
            Quality.HIGH -> 2_000_000
            Quality.MEDIUM -> 1_200_000
            Quality.LOW -> 800_000
        }
    }

    /**
     * Pre-export file estimate: `(video + audio target) × duration`
     * plus container overhead. Integer math only — the same inputs must
     * yield byte-identical estimates on both platforms.
     */
    fun estimateBytes(preset: Preset, durationMs: Long): Long {
        if (durationMs <= 0) return 0L
        val streams = (preset.videoBitrateBps + AUDIO_BITRATE_BPS).toLong() * durationMs / 8_000L
        return streams * (100L + CONTAINER_OVERHEAD_PERCENT) / 100L
    }

    /** True when the estimate clears [PUBLISH_GATE_BYTES] (manual picks). */
    fun publishFits(preset: Preset, durationMs: Long): Boolean =
        estimateBytes(preset, durationMs) <= PUBLISH_GATE_BYTES

    /**
     * Deterministic creator-facing size label: "≈ 45.1 MB" where MB =
     * 1024² (matches the Blossom bound units). Both platforms render
     * this exact string — no locale machinery.
     */
    fun sizeLabel(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        val text = if (mb >= 100) {
            mb.roundToInt().toString()
        } else {
            val tenths = (mb * 10).roundToInt()
            "${tenths / 10}.${tenths % 10}"
        }
        return "≈ $text MB"
    }

    /**
     * Output canvas for a preset: the shared never-upscale/even-dims
     * math with the preset's long-edge cap (1080 default = [AUTO] and
     * all pre-MST-036 callers).
     */
    fun outputSize(preset: Preset, sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> =
        MemeExportRules.outputSize(sourceWidth, sourceHeight, preset.longEdge)

    /** Evened upright frame dims for a source (hardware encoders want even). */
    fun evenedFrame(uprightWidth: Int, uprightHeight: Int): Pair<Int, Int> =
        max(2, uprightWidth - (uprightWidth % 2)) to max(2, uprightHeight - (uprightHeight % 2))

    /**
     * The native adapter contract (MST-036): the encoder targets + output
     * canvas + frame→target scale for one source at one preset, consumed
     * IDENTICALLY by Android (Media3) and iOS (AVFoundation) so the MB
     * estimate never drifts from what the encoders are told.
     */
    data class EncoderPlan(
        val videoBitrateBps: Int,
        val audioBitrateBps: Int,
        val width: Int,
        val height: Int,
        val scaleX: Float,
        val scaleY: Float,
    ) {
        /** False at/below the cap — never upscale, no scale effect needed. */
        val scaleNeeded: Boolean get() = scaleX < 0.999f || scaleY < 0.999f
    }

    /**
     * The encoder plan for evened upright source dims at [preset]: the
     * shared `outputSize` target plus the frame → target scale the
     * effects chain (Android) / layer transform + render size (iOS)
     * must apply before the overlay burns.
     */
    fun encoderPlan(
        preset: Preset,
        uprightWidth: Int,
        uprightHeight: Int,
    ): EncoderPlan {
        val (frameWidth, frameHeight) = evenedFrame(uprightWidth, uprightHeight)
        val (width, height) = outputSize(preset, frameWidth, frameHeight)
        return EncoderPlan(
            videoBitrateBps = preset.videoBitrateBps,
            audioBitrateBps = AUDIO_BITRATE_BPS,
            width = width,
            height = height,
            scaleX = width.toFloat() / frameWidth,
            scaleY = height.toFloat() / frameHeight,
        )
    }
}
