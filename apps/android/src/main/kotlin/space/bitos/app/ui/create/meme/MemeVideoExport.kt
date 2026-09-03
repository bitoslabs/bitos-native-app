package space.bitos.app.ui.create.meme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.math.max
import space.bitos.core.studio.MemeExportRules
import space.bitos.core.studio.MemeProject

/**
 * Video meme export (plan MST-033; Android split per plan §4.2): burns the
 * overlay composition into the clip with Media3 Transformer. MST-044
 * close-out: a [androidx.media3.effect.CanvasOverlay] repaints the shared
 * [MemeExportRules.paintPlanAt] per frame, so visibility windows and fx
 * motion burn into the export exactly as the stage previews them (the
 * frame's presentation time is output time, shifted back into media time
 * by the trim start). One Transformer pass at the source's resolution/fps.
 */
object MemeVideoExport {

    /**
     * Importing is local editing work, not a Blossom upload. Keep a bounded
     * (but substantially larger) source allowance so a creator can trim and
     * re-encode a camera-original clip before the final 64 MiB upload policy
     * is applied.
     */
    const val MAX_SOURCE_BYTES = 256L * 1024 * 1024

    data class Probe(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val rotationDeg: Int,
    ) {
        /** Upright dims (rotation applied) — the orientation the event imeta
         * and the portrait kind decision use. */
        val uprightWidth: Int get() = if (rotationDeg == 90 || rotationDeg == 270) height else width
        val uprightHeight: Int get() = if (rotationDeg == 90 || rotationDeg == 270) width else height
    }

    class ExportFailure(message: String) : Exception(message)

    /** One M5 timeline clip handed to [exportClips]. */
    data class ClipInput(
        val bytes: ByteArray,
        val probe: Probe,
        val startMs: Long,
        val endMs: Long,
        /** Timeline offset of this clip's first output frame. */
        val offsetMs: Long,
        /** Per-clip audio gain (0 = mute). */
        val volume: Float = 1f,
        /** Per-clip grade; null = the project grade. */
        val lookId: String? = null,
    )

    /**
     * M5 multi-clip export: every clip becomes one EditedMediaItem in a
     * SINGLE sequence (Transformer plays a sequence's items back-to-back),
     * each with its own clipping window, speed and a timed overlay that
     * maps item-local output time + the clip's timeline offset back to
     * timeline time — so overlay windows, fx and pen ink burn exactly as
     * the stage previews them across the concatenated timeline. The SFX
     * cue mix rides as the usual second sequence.
     */
    fun exportClips(
        context: Context,
        clips: List<ClipInput>,
        project: MemeProject,
        sfxPcm16: ByteArray? = null,
        imageAssets: Map<String, Uri> = emptyMap(),
    ): ByteArray {
        require(clips.isNotEmpty()) { "no clips" }
        val rate = space.bitos.core.studio.MemeProjectContract.clampSpeed(project.speed)
        val imageFor: ((String) -> Bitmap?) = imageAssets.takeIf { it.isNotEmpty() }?.let { assets ->
            { id -> assets[id]?.let { MemeRaster.decodeForExport(context.contentResolver, it) } }
        } ?: { null }

        val files = mutableListOf<File>()
        try {
            val editedItems = clips.map { clip ->
                val probe = clip.probe
                val frameWidth = max(2, probe.uprightWidth - (probe.uprightWidth % 2))
                val frameHeight = max(2, probe.uprightHeight - (probe.uprightHeight % 2))
                val input = File(context.cacheDir, "meme-clip-in-${clip.offsetMs}-${System.nanoTime()}.mp4")
                files += input
                input.writeBytes(clip.bytes)
                // Timeline time = clip offset + item-local OUTPUT time; media
                // time (for stroke plans keyed to source time) = window start
                // + local output time × rate.
                val timedOverlay = object : androidx.media3.effect.CanvasOverlay(true) {
                    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
                        val timelineMs = clip.offsetMs + presentationTimeUs / 1000L
                        MemeRaster.drawStrokes(
                            canvas,
                            space.bitos.core.studio.MemeExportRules.drawingPlan(project, frameWidth, frameHeight),
                        )
                        space.bitos.core.studio.MemeExportRules
                            .paintPlanAt(project, frameWidth, frameHeight, timelineMs)
                            .forEach { timed ->
                                MemeRaster.drawExportItem(canvas, timed.item, imageFor, timed.fx)
                            }
                    }
                }
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(input))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.startMs)
                            .setEndPositionMs(clip.endMs.coerceAtLeast(clip.startMs + 1))
                            .build(),
                    )
                    .build()
                val builder = EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(emptyList(), buildList {
                        add(OverlayEffect(listOf(timedOverlay)))
                        // Color grade per clip: the clip's own look, else the
                        // project grade — 4×5 matrix is the shared WYSIWYG one.
                        space.bitos.core.studio.MemeLooks
                            .normalize(clip.lookId ?: project.lookId)
                            ?.let { lookId ->
                                val matrix = space.bitos.core.studio.MemeLooks.matrixFor(lookId)
                                add(androidx.media3.effect.RgbMatrix { _, _ -> matrix })
                            }
                    }))
                if (clip.volume <= 0f) {
                    // Mute is exact in export (audio track removed). Fractional
                    // gain is preview-only until media3 exposes a public audio
                    // gain hook — never silently half-applied.
                    builder.setRemoveAudio(true)
                }
                if (rate != 1f) {
                    builder.setSpeed(
                        object : androidx.media3.common.audio.SpeedProvider {
                            override fun getSpeed(timestampUs: Long) = rate
                            override fun getNextSpeedChangeTimeUs(timestampUs: Long) =
                                androidx.media3.common.C.TIME_UNSET
                        },
                    )
                }
                builder.build()
            }

            val output = File(context.cacheDir, "meme-video-out-${System.currentTimeMillis()}.mp4")
            files += output
            val transformer = Transformer.Builder(context).build()
            val sfxFile = sfxPcm16?.let { pcm ->
                File(context.cacheDir, "meme-sfx-${System.currentTimeMillis()}.wav").also { wav ->
                    runCatching { wav.writeBytes(space.bitos.core.studio.SfxSynth.wav(pcm)) }
                    files += wav
                }.takeIf { it.exists() }
            }
            val videoSequence = androidx.media3.transformer.EditedMediaItemSequence.Builder(editedItems).build()
            val composition = if (sfxFile != null) {
                val sfxItem = EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(Uri.fromFile(sfxFile)).build(),
                ).build()
                androidx.media3.transformer.Composition.Builder(
                    videoSequence,
                    androidx.media3.transformer.EditedMediaItemSequence.Builder(sfxItem).build(),
                ).build()
            } else {
                androidx.media3.transformer.Composition.Builder(videoSequence).build()
            }
            transformer.start(composition, output.absolutePath)
            val deadline = System.currentTimeMillis() + 120_000 * clips.size.coerceAtMost(4)
            while (output.length() == 0L && System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
            }
            var stableBytes = output.length()
            Thread.sleep(600)
            if (output.length() == stableBytes && stableBytes > 0) {
                return output.readBytes()
            }
            throw ExportFailure("Video export did not finish in time")
        } finally {
            files.forEach { runCatching { it.delete() } }
        }
    }


    /** Probes a picked clip (bounded ≤60 s; dims capped at 1080 by export). */
    fun probe(resolver: android.content.ContentResolver, uri: Uri): Probe? = try {
        // Photo Picker returns content:// URIs. Passing that URI as a plain
        // string makes MediaMetadataRetriever treat it as a filesystem path
        // on several devices, causing valid gallery clips to be reported as
        // unreadable. Give it the resolver-backed file descriptor instead.
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(descriptor.fileDescriptor)
                val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: return null
                val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: return null
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                Probe(rawW, rawH, duration, rotation)
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * MST-032: captures the frame at [positionMs] as JPEG bytes (the
     * creator-selected cover; uploaded separately and referenced as the
     * imeta `thumb`).
     */
    fun captureCoverJpeg(clipBytes: ByteArray, positionMs: Long): ByteArray? = try {
        val temp = java.io.File.createTempFile("meme-cover", ".mp4")
        try {
            temp.writeBytes(clipBytes)
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(temp.absolutePath)
                val frame = retriever.getFrameAtTime(
                    positionMs.coerceIn(0L, 60_000) * 1000, // µs
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: return null
                val stream = java.io.ByteArrayOutputStream()
                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
                stream.toByteArray()
            }
        } finally {
            runCatching { temp.delete() }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Burns the overlay layer into the clip. The overlay bitmap covers the
     * full upright frame, so the default centered OverlaySettings is exact.
     * [imageAssets] resolves IMAGE-layer overlay ids to content/file URIs
     * (video-mode source inserts) — decoded off-thread, bounded by the
     * raster's 1080-long-edge sampler.
     */
    fun export(
        context: Context,
        clipBytes: ByteArray,
        probe: Probe,
        project: MemeProject,
        sfxPcm16: ByteArray? = null,
        imageAssets: Map<String, Uri> = emptyMap(),
    ): ByteArray {
        val frameWidth = max(2, probe.uprightWidth - (probe.uprightWidth % 2))
        val frameHeight = max(2, probe.uprightHeight - (probe.uprightHeight % 2))

        // The project trim window is the export contract (MST-030
        // revision): over-long clips are CUT here, not rejected.
        val clipStart = project.trimStartMs.coerceIn(0, probe.durationMs)
        val clipEnd = (if (project.trimEndMs > 0) project.trimEndMs else probe.durationMs)
            .coerceIn(clipStart, probe.durationMs)

        val imageFor: ((String) -> Bitmap?) = imageAssets.takeIf { it.isNotEmpty() }?.let { assets ->
            { id -> assets[id]?.let { MemeRaster.decodeForExport(context.contentResolver, it) } }
        } ?: { null }

        // Timed burn-in (MST-044 close-out): windows + fx render per frame
        // from the shared plan — the export finally matches the stage.
        // Transformer applies ClippingConfiguration + speed BEFORE effects,
        // so the frame's presentation time is OUTPUT time; map it back into
        // media time (÷ rate, + trim start).
        val rate = space.bitos.core.studio.MemeProjectContract.clampSpeed(project.speed)
        val timedOverlay = object : androidx.media3.effect.CanvasOverlay(true) {
            override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
                val mediaMs = (presentationTimeUs / 1000.0 * rate).toLong() + clipStart
                // Pen ink sits under the overlays on every frame.
                MemeRaster.drawStrokes(
                    canvas,
                    space.bitos.core.studio.MemeExportRules.drawingPlan(project, frameWidth, frameHeight),
                )
                space.bitos.core.studio.MemeExportRules
                    .paintPlanAt(project, frameWidth, frameHeight, mediaMs)
                    .forEach { timed ->
                        MemeRaster.drawExportItem(canvas, timed.item, imageFor, timed.fx)
                    }
            }
        }

        val input = File(context.cacheDir, "meme-video-in-${System.currentTimeMillis()}.mp4")
        val output = File(context.cacheDir, "meme-video-out-${System.currentTimeMillis()}.mp4")
        try {
            input.writeBytes(clipBytes)
            val effect = OverlayEffect(listOf(timedOverlay))
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(input))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clipStart)
                        .setEndPositionMs(clipEnd)
                        .build(),
                )
                .build()
            val editedBuilder = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), listOf(effect)))
            if (rate != 1f) {
                // V2 suite Speed chip: whole-clip rate (audio + video — the
                // Sonic processor resamples both; pitch shifts, V1 accepted).
                editedBuilder.setSpeed(
                    object : androidx.media3.common.audio.SpeedProvider {
                        override fun getSpeed(timestampUs: Long) = rate
                        override fun getNextSpeedChangeTimeUs(timestampUs: Long) =
                            androidx.media3.common.C.TIME_UNSET
                    },
                )
            }
            val edited = editedBuilder.build()
            // SFX burn-in (MST-041): the shared cue mix rides as a second
            // sequence — Transformer mixes sequences into one output.
            val sfxFile = sfxPcm16?.let { pcm ->
                File(context.cacheDir, "meme-sfx-${System.currentTimeMillis()}.wav").apply {
                    runCatching { writeBytes(space.bitos.core.studio.SfxSynth.wav(pcm)) }
                }
            }
            val transformer = Transformer.Builder(context).build()
            val sfxComposition = sfxFile?.takeIf { it.exists() }?.let { wavFile ->
                val sfxItem = EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(Uri.fromFile(wavFile)).build(),
                ).build()
                androidx.media3.transformer.Composition.Builder(
                    androidx.media3.transformer.EditedMediaItemSequence.Builder(edited).build(),
                    androidx.media3.transformer.EditedMediaItemSequence.Builder(sfxItem).build(),
                ).build()
            }
            if (sfxComposition != null) {
                transformer.start(sfxComposition, output.absolutePath)
            } else {
                transformer.start(edited, output.absolutePath)
            }
            // Completion poll (the repo's established Transformer pattern
            // from VideoPreviewScreen; Transformer listeners vary by version).
            val deadline = System.currentTimeMillis() + 120_000
            while (output.length() == 0L && System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
            }
            // Give the muxer a beat to close the file, then drain-check.
            var stableBytes = output.length()
            Thread.sleep(600)
            if (output.length() == stableBytes && stableBytes > 0) {
                return output.readBytes()
            }
            throw ExportFailure("Video export did not finish in time")
        } finally {
            runCatching { input.delete() }
            runCatching { output.delete() }
        }
    }
}
