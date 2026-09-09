package space.bitos.app.ui.create.meme

import android.content.Context
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import java.io.File

/**
 * Blank-video source (plan `meme-blank-canvas-crossmode-plan.md` D1 /
 * MST-070): synthesizes a solid-color, silent H.264 MP4 — the "canvas is
 * the media" trick. The generated file is a perfectly ordinary clip: it
 * is probed and appended through `appendClip` like a camera take, so the
 * WHOLE M5 machinery (timeline, trim/split/speed/volume/look, SFX mix,
 * layers, export, publish, slots) works on it unchanged.
 *
 * Duration is exact because frames carry explicit presentation times
 * (EGL `eglPresentationTimeANDROID`), not wall-clock post times —
 * synthesizing a 10 s canvas takes milliseconds, not 10 seconds.
 * Deterministic given (ratio, bg, duration): solid color compresses to a
 * few dozen KiB.
 */
object BlankClipSource {

    private const val FPS = 30
    private const val LONG_EDGE = 720
    private const val BIT_RATE = 1_000_000

    /** Presentation times start at 1 s, never 0 — some surface encoders
     *  mishandle a zero timestamp (dropped first frame / wrong duration);
     *  an arbitrary monotonic base is the standard practice. Duration is
     *  unaffected (last − first + one frame). */
    private const val PTS_BASE_NS = 1_000_000_000L

    /**
     * @param ratioId `w:h` preset id from [space.bitos.core.studio.MemeCanvas]
     * @param bgHex `#rrggbb` canvas background
     * @param durationMs exact source length (>= 1 frame; multiple frames
     *   are snapped to the 30 fps grid)
     */
    fun create(context: Context, ratioId: String, bgHex: String, durationMs: Long): File? {
        val terms = space.bitos.core.studio.MemeCanvas.ratioTerms(ratioId) ?: return null
        if (!space.bitos.core.studio.MemeCanvas.isValidBackground(bgHex)) return null
        val durationMs = durationMs.coerceIn(1_000L, 60_000L)

        // Self-check + retry (user-reported "set 10 s → 0 s"): some
        // encoders mis-handle a non-zero pts base (or produce a broken
        // stream); the honest extractor-backed probe catches a short file
        // and one zero-base retry fixes the base-sensitive devices. A
        // second failure refuses loudly instead of shipping a 0 s canvas.
        val first = write(context, terms, bgHex, durationMs, PTS_BASE_NS)
        if (first != null && honestDurationMs(first) >= durationMs - 400L) return first
        runCatching { first?.delete() }
        val second = write(context, terms, bgHex, durationMs, 0L)
        if (second != null && honestDurationMs(second) >= durationMs - 400L) return second
        runCatching { second?.delete() }
        return null
    }

    /** Duration via the (extractor-backed) probe — the honest truth about
     *  what the device's encoder actually produced. */
    private fun honestDurationMs(file: File): Long =
        MemeVideoExport.probeFile(file)?.durationMs ?: 0L

    private fun write(
        context: Context,
        terms: Pair<Int, Int>,
        bgHex: String,
        durationMs: Long,
        ptsBaseNs: Long,
    ): File? {
        val color = runCatching { Color.parseColor(bgHex) }.getOrNull() ?: return null
        var width = LONG_EDGE
        var height = ((LONG_EDGE.toLong() * terms.second) / terms.first).toInt()
        if (height > LONG_EDGE) {
            height = LONG_EDGE
            width = ((LONG_EDGE.toLong() * terms.first) / terms.second).toInt()
        }
        width -= width % 2
        height -= height % 2
        if (width <= 0 || height <= 0) return null
        val totalFrames = ((durationMs * FPS) / 1000L).toInt().coerceAtLeast(1)

        val output = File.createTempFile("blank-canvas", ".mp4", context.cacheDir)
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var gl: GlSession? = null
        try {
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val inputSurface = encoder.createInputSurface()
            encoder.start()
            gl = GlSession(inputSurface)
            val info = MediaCodec.BufferInfo()
            var trackIndex = -1
            var muxerStarted = false

            for (frame in 0 until totalFrames) {
                gl.drawColor(color, frameNs = ptsBaseNs + frame * 1_000_000_000L / FPS)
                // Interleaved drain — the encoder stalls once its output
                // buffers fill (usually ~10), long before 30 solid frames.
                drain(encoder, muxer, info, endOfStream = false, trackIndex = trackIndex) { track, started ->
                    trackIndex = track; muxerStarted = started
                }
            }
            encoder.signalEndOfInputStream()
            drain(encoder, muxer, info, endOfStream = true, trackIndex = trackIndex) { track, started ->
                trackIndex = track; muxerStarted = started
            }
            if (!muxerStarted || trackIndex < 0) return null
            return output
        } catch (_: Exception) {
            runCatching { output.delete() }
            return null
        } finally {
            runCatching { gl?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /** Non-blocking drain; at EOS drains to the end-of-stream flag. */
    private fun drain(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        trackIndex: Int,
        onState: (track: Int, started: Boolean) -> Unit,
    ) {
        // `INFO_OUTPUT_FORMAT_CHANGED` occurs only once. The track must
        // survive every interleaved drain below or all later encoded frames
        // are dropped because their local track would be -1.
        var track = trackIndex
        var started = track >= 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER ->
                    if (!endOfStream) break else continue

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                    onState(track, started)
                }

                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) {
                        muxer.writeSampleData(track, buffer, info)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    /**
     * Minimal EGL session over the encoder input surface. Only solid
     * clears are ever drawn, so there are no shaders or textures — the
     * session exists purely to own the presentation timestamps.
     */
    private class GlSession(surface: Surface) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val eglSurface: EGLSurface
        private var width = 1
        private var height = 1

        init {
            require(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val version = IntArray(2)
            require(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val matched = IntArray(1)
            require(
                EGL14.eglChooseConfig(display, attribs, 0, configs, 0, configs.size, matched, 0) &&
                    matched[0] > 0,
            ) { "no EGL config" }
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            require(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            require(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
            require(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
        }

        fun drawColor(color: Int, frameNs: Long) {
            GLES20.glClearColor(
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f,
                1f,
            )
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, frameNs)
            require(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed" }
        }

        fun release() {
            runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            runCatching { EGL14.eglDestroySurface(display, eglSurface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }
    }
}
