package space.bitos.app.ui.create.meme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import space.bitos.core.studio.MemeExportRules
import space.bitos.core.studio.MemeExportPresets
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
     * is applied. Delegates to the shared cross-platform cap
     * (`MemeVideoCutRules.MAX_SOURCE_BYTES`) — the Create hub's import
     * gate reads the same bound on iOS.
     */
    const val MAX_SOURCE_BYTES = space.bitos.core.studio.MemeVideoCutRules.MAX_SOURCE_BYTES

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

    /**
     * Animated GIF layer reel (web `DecodedGif`/`gifLayerPainter` parity,
     * MST-053): composited frames + encoded holds. The active frame at
     * media time comes from the SHARED looping rule — a GIF layer keeps
     * moving for the whole export instead of freezing after its first
     * pass. Still image layers have no reel.
     */
    class GifLayerReel(val frames: List<Bitmap>, val delaysMs: List<Int>) {
        fun frameAt(atMs: Long): Bitmap =
            frames[space.bitos.core.studio.GifLayerRules.frameIndexAt(delaysMs, atMs)]
    }

    /**
     * Decodes an image-layer source into an animated reel when the bytes
     * are an animated GIF (≥2 decoded frames, bounded input); null = still
     * image — the caller keeps the static path. Off-thread by contract.
     */
    suspend fun decodeGifLayerReel(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): GifLayerReel? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            if (bytes.size > space.bitos.core.studio.GifDecoder.MAX_INPUT_BYTES) return@runCatching null
            val decoded = space.bitos.core.studio.GifDecoder.decode(bytes) ?: return@runCatching null
            if (!space.bitos.core.studio.GifLayerRules.isAnimated(decoded.frames.size)) {
                return@runCatching null
            }
            val frames = decoded.frames.map { frame ->
                Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also {
                    it.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(frame.rgba))
                }
            }
            GifLayerReel(frames, decoded.frames.map { it.delayMs })
        }.getOrNull()
    }

    /**
     * Export result (MST-036): the artifact bytes plus the ACTUAL output
     * dims — publish imeta must carry these, never the probe dims (the
     * preset canvas can differ from the source).
     */
    data class Exported(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

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
        /** Per-clip playback speed. */
        val speed: Float = 1f,
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
    suspend fun exportClips(
        context: Context,
        clips: List<ClipInput>,
        project: MemeProject,
        sfxPcm16: ByteArray? = null,
        imageAssets: Map<String, Uri> = emptyMap(),
        preset: MemeExportPresets.Preset = MemeExportPresets.AUTO,
        gifReels: Map<String, GifLayerReel> = emptyMap(),
        onProgress: ((Float) -> Unit)? = null,
    ): Exported {
        require(clips.isNotEmpty()) { "no clips" }
        // One uniform output canvas for the whole timeline (a sequence muxes
        // into a single track): the shared target of the LARGEST clip, then
        // every item scales to it — smaller sources upscale, larger ones
        // downscale, uniformity wins for mixed-resolution timelines.
        val largestClip = clips.maxBy { it.probe.uprightWidth.toLong() * it.probe.uprightHeight }
        val plan = MemeExportPresets.encoderPlan(
            preset, largestClip.probe.uprightWidth, largestClip.probe.uprightHeight,
        )
        // Still image layers decode ONCE per export (the old per-frame
        // decode re-read the source every onDraw); animated GIF layers
        // resolve per frame through the shared looping rule.
        val staticImages = HashMap<String, Bitmap>()
        fun imageForAt(atMs: Long): (String) -> Bitmap? = { id ->
            val frame = gifReels[id]?.frameAt(atMs)
            if (frame != null) {
                frame
            } else {
                staticImages[id]
                    ?: imageAssets[id]?.let { MemeRaster.decodeForExport(context.contentResolver, it) }
                        ?.also { decoded -> staticImages[id] = decoded }
            }
        }

        val files = mutableListOf<File>()
        try {
            val editedItems = clips.map { clip ->
                val rate = space.bitos.core.studio.MemeProjectContract.clampSpeed(clip.speed)
                val probe = clip.probe
                // Paint plans live on the UNIFORM timeline canvas (post-scale,
                // pre-encode) so text rasterizes at its final size.
                val frameWidth = plan.width
                val frameHeight = plan.height
                // Per-item normalization onto that canvas (down- or upscale —
                // mixed-resolution timelines must land on one track size).
                val itemFrame = MemeExportPresets.evenedFrame(probe.uprightWidth, probe.uprightHeight)
                val itemScaleX = plan.width.toFloat() / itemFrame.first
                val itemScaleY = plan.height.toFloat() / itemFrame.second
                val itemScaleNeeded = abs(itemScaleX - 1f) > 0.001f || abs(itemScaleY - 1f) > 0.001f
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
                        val imageFor = imageForAt(timelineMs)
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
                    .setEffects(Effects(if (clip.volume > 0f && clip.volume != 1f) listOf(MemeVideoAudio.gain(clip.volume)) else emptyList(), buildList {
                        // Preset scale FIRST (MST-036): the canvas, grade and
                        // overlay all work at the final output size.
                        if (itemScaleNeeded) {
                            add(ScaleAndRotateTransformation.Builder().setScale(itemScaleX, itemScaleY).build())
                        }
                        // Color grade per clip: the clip's own look, else the
                        // project grade — the manual adjust composes over
                        // either into the shared WYSIWYG matrix.
                        val effectiveLook = clip.lookId ?: project.lookId
                        if (effectiveLook != null || project.adjust != null) {
                            val matrix = space.bitos.core.studio.MemeLooks.adjustedMatrixFor(
                                effectiveLook,
                                project.adjust,
                            )
                            add(androidx.media3.effect.RgbMatrix { _, _ -> MemeVideoColor.glMatrix(matrix) })
                        }
                        add(OverlayEffect(listOf(timedOverlay)))
                    }))
                if (clip.volume <= 0f) {
                    // Remove muted audio; fractional gain uses the audio processor above.
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
            MemeVideoRender.render(
                context, composition, output.absolutePath,
                120_000L * clips.size.coerceAtMost(4),
                plan.videoBitrateBps, plan.audioBitrateBps,
                onProgress = onProgress,
            )
            if (output.length() == 0L) throw ExportFailure("Video export produced an empty file")
            return Exported(output.readBytes(), plan.width, plan.height)
        } finally {
            files.forEach { runCatching { it.delete() } }
        }
    }


    /** Probes a picked clip. Timeline profile owns duration; export caps dims. */
    fun probe(resolver: android.content.ContentResolver, uri: Uri): Probe? = try {
        // Photo Picker returns content:// URIs. Passing that URI as a plain
        // string makes MediaMetadataRetriever treat it as a filesystem path
        // on several devices, causing valid gallery clips to be reported as
        // unreadable. Give it the resolver-backed file descriptor instead.
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(descriptor.fileDescriptor)
                probeRetriever(retriever, extractorDurationMs(descriptor.fileDescriptor))
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Probes a LOCAL slot file by direct path — the draft-resume path.
     * Plain files (unlike content:// picker sources) probe reliably by
     * path, including right after a cold start where resolver-FD probing
     * has proven flaky (dropped-resume bug).
     */
    fun probeFile(file: File): Probe? = try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            probeRetriever(retriever, extractorDurationMs(file.absolutePath))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Duration fallback (user-reported blank-canvas bug): some devices'
     * retrievers return NO duration metadata for small/no-audio files
     * (the blank-canvas synth among them) — the old `?: 0L` collapsed
     * every window that trusted the probe into a 0-second timeline. The
     * container's video-track header (MediaMuxer always writes it) is the
     * honest bound; 0 only when even that is absent.
     */
    private fun extractorDurationMs(source: Any): Long = try {
        val extractor = android.media.MediaExtractor()
        try {
            when (source) {
                is String -> extractor.setDataSource(source)
                is java.io.FileDescriptor -> extractor.setDataSource(source)
                else -> return 0L
            }
            (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true && format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION) / 1000L
                } else {
                    null
                }
            } ?: 0L
        } finally {
            runCatching { extractor.release() }
        }
    } catch (_: Exception) {
        0L
    }

    private fun probeRetriever(retriever: MediaMetadataRetriever, durationFallbackMs: Long = 0L): Probe? {
        val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: return null
        val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: return null
        // A missing/zero duration read is garbage, not a bound — prefer the
        // container's track duration before ever reporting 0.
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.takeIf { it > 0 }
            ?: durationFallbackMs.takeIf { it > 0 }
            ?: 0L
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        return Probe(rawW, rawH, duration, rotation)
    }

    /**
     * MST-032: captures the frame at [positionMs] as JPEG bytes (the
     * creator-selected cover; uploaded separately and referenced as the
     * imeta `thumb`).
     */
    fun captureCoverJpeg(
        context: Context,
        clipBytes: ByteArray,
        positionMs: Long,
        timelineMs: Long,
        project: MemeProject,
        imageAssets: Map<String, Uri> = emptyMap(),
        gifReels: Map<String, GifLayerReel> = emptyMap(),
        effectiveLookId: String? = project.lookId,
    ): ByteArray? = try {
        val temp = java.io.File.createTempFile("meme-cover", ".mp4")
        try {
            temp.writeBytes(clipBytes)
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(temp.absolutePath)
                val frame = retriever.getFrameAtTime(
                    positionMs.coerceIn(0L, space.bitos.core.studio.MemeProjectContract.MAX_DURATION_MS) * 1000, // µs
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: return null
                val staticImages = HashMap<String, Bitmap>()
                val imageFor: (String) -> Bitmap? = { id ->
                    gifReels[id]?.frameAt(timelineMs)
                        ?: staticImages[id]
                        ?: imageAssets[id]?.let { MemeRaster.decodeForExport(context.contentResolver, it) }
                            ?.also { staticImages[id] = it }
                }
                val rendered = MemeRaster.renderVideoCoverFrame(
                    source = frame,
                    project = project.copy(lookId = effectiveLookId),
                    timelineMs = timelineMs,
                    imageFor = imageFor,
                )
                val stream = java.io.ByteArrayOutputStream()
                rendered.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
                if (rendered !== frame) rendered.recycle()
                frame.recycle()
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
    suspend fun export(
        context: Context,
        clipBytes: ByteArray,
        probe: Probe,
        project: MemeProject,
        sfxPcm16: ByteArray? = null,
        imageAssets: Map<String, Uri> = emptyMap(),
        preset: MemeExportPresets.Preset = MemeExportPresets.AUTO,
        gifReels: Map<String, GifLayerReel> = emptyMap(),
    ): Exported {
        val plan = MemeExportPresets.encoderPlan(preset, probe.uprightWidth, probe.uprightHeight)
        // Paint plans live on the output canvas (post-scale) — text burns
        // at its final raster size.
        val frameWidth = plan.width
        val frameHeight = plan.height

        // The project trim window is the export contract (MST-030
        // revision): over-long clips are CUT here, not rejected.
        val clipStart = project.trimStartMs.coerceIn(0, probe.durationMs)
        val clipEnd = (if (project.trimEndMs > 0) project.trimEndMs else probe.durationMs)
            .coerceIn(clipStart, probe.durationMs)

        // Still image layers decode ONCE per export (the old per-frame
        // decode re-read the source every onDraw); animated GIF layers
        // resolve per frame through the shared looping rule.
        val staticImages = HashMap<String, Bitmap>()
        fun imageForAt(atMs: Long): (String) -> Bitmap? = { id ->
            val frame = gifReels[id]?.frameAt(atMs)
            if (frame != null) {
                frame
            } else {
                staticImages[id]
                    ?: imageAssets[id]?.let { MemeRaster.decodeForExport(context.contentResolver, it) }
                        ?.also { decoded -> staticImages[id] = decoded }
            }
        }

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
                val imageFor = imageForAt(mediaMs)
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
                .setEffects(
                    Effects(
                        emptyList(),
                        buildList {
                            // Preset scale FIRST (MST-036).
                            if (plan.scaleNeeded) {
                                add(ScaleAndRotateTransformation.Builder().setScale(plan.scaleX, plan.scaleY).build())
                            }
                            add(effect)
                        },
                    ),
                )
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
            val sfxComposition = sfxFile?.takeIf { it.exists() }?.let { wavFile ->
                val sfxItem = EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(Uri.fromFile(wavFile)).build(),
                ).build()
                androidx.media3.transformer.Composition.Builder(
                    androidx.media3.transformer.EditedMediaItemSequence.Builder(edited).build(),
                    androidx.media3.transformer.EditedMediaItemSequence.Builder(sfxItem).build(),
                ).build()
            }
            val composition = sfxComposition ?: androidx.media3.transformer.Composition.Builder(
                androidx.media3.transformer.EditedMediaItemSequence.Builder(edited).build(),
            ).build()
            try {
                MemeVideoRender.render(
                    context, composition, output.absolutePath, 120_000L,
                    plan.videoBitrateBps, plan.audioBitrateBps,
                )
                if (output.length() == 0L) throw ExportFailure("Video export produced an empty file")
                return Exported(output.readBytes(), plan.width, plan.height)
            } finally {
                sfxFile?.delete()
            }
        } finally {
            runCatching { input.delete() }
            runCatching { output.delete() }
        }
    }
}
