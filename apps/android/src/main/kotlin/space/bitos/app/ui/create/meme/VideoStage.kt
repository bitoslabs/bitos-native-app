package space.bitos.app.ui.create.meme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import kotlin.math.max
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.app.ui.components.BitosSlider
import space.bitos.core.studio.MemeOverlay
import space.bitos.core.studio.MemeProject

/**
 * External transport for the V2 suite dock (mockup app-15 `scr-suite`):
 * the stage registers its player controls here so the timeline dock can
 * play/pause and seek without owning the player instance.
 */
class VideoTransport {
    var playPause: () -> Unit = {}
    var seekTo: (Long) -> Unit = {}
    var isPlaying: () -> Boolean = { false }
}

/**
 * Video stage (plan MST-031; M5 multi-clip): an ExoPlayer surface whose
 * playlist is the timeline — one MediaItem per clip, each clipped to its
 * window. Overlays compose ON TOP keyed to TIMELINE time (the same clock
 * the dock, the visibility windows and the export burn use). The player
 * reads cache-file URIs, avoiding the enormous Base64 heap copy a data URI
 * would create. [imageAssets] resolves IMAGE layer overlay ids to their
 * picker URI + aspect (source inserts).
 */
@Composable
internal fun VideoStage(
    clips: List<SessionClip>,
    project: MemeProject,
    rate: Float,
    stageWidthPx: Int,
    stageHeightPx: Int,
    onStageSized: (androidx.compose.ui.unit.IntSize) -> Unit,
    overlays: List<MemeOverlay>,
    selectedId: String?,
    state: MemeEditorState,
    coverSet: Boolean = false,
    onSetCover: (timelineMs: Long) -> Unit = {},
    /** SFX cues (MST-041) — markers on the scrub row (timeline time). */
    cues: List<space.bitos.core.studio.MemeSfxCue> = emptyList(),
    /** Playhead tracking (timeline time) for cue placement. */
    onPositionChange: (Long) -> Unit = {},
    /** IMAGE layer sources: overlay asset id → (uri, aspect). */
    imageAssets: Map<String, Pair<android.net.Uri, Float>> = emptyMap(),
    /** Suite mode hides the scrub row — the timeline dock owns transport. */
    showScrub: Boolean = true,
    /** Player control surface for the suite dock (registered, not owned). */
    transport: VideoTransport? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (clips.isEmpty()) return
    // Per-clip cache files + output-timeline offsets (window ÷ rate).
    val sourceFiles = remember(clips) {
        clips.map { clip ->
            File(context.cacheDir, "meme-stage-${clip.id}-${System.nanoTime()}.mp4").apply {
                writeBytes(clip.bytes)
            }
        }
    }
    fun clipRate(clip: SessionClip): Float =
        space.bitos.core.studio.MemeProjectContract.clampSpeed(clip.speed)
    fun clipOutputMs(clip: SessionClip): Long =
        (((clip.endMs - clip.startMs).coerceAtLeast(0L)) / clipRate(clip)).toLong()
    val offsets = remember(clips, rate) {
        var acc = 0L
        clips.map { clip ->
            val at = acc
            acc += clipOutputMs(clip)
            at
        }
    }
    val timelineDurationMs = offsets.lastOrNull()?.let { last ->
        clips.lastOrNull()?.let { last + clipOutputMs(it) }
    } ?: 0L

    val colorMatrix = remember {
        java.util.concurrent.atomic.AtomicReference(
            MemeVideoColor.glMatrix(space.bitos.core.studio.MemeLooks.adjustedMatrixFor(null, null)),
        )
    }
    val player = remember(sourceFiles) {
        ExoPlayer.Builder(context).build().apply {
            // This standard SurfaceView is supported by Media3's effect path.
            // The matrix only targets decoded video; Compose overlays stay crisp.
            // Register one stable GPU effect before prepare. Updating its
            // matrix is safe on devices that reject effect-pipeline swaps.
            setVideoEffects(listOf(androidx.media3.effect.RgbMatrix { _, _ -> colorMatrix.get() }))
            clips.forEachIndexed { index, clip ->
                addMediaItem(
                    index,
                    MediaItem.Builder()
                        .setUri(android.net.Uri.fromFile(sourceFiles[index]))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clip.startMs)
                                .setEndPositionMs(clip.endMs.coerceAtLeast(clip.startMs + 1))
                                .build(),
                        )
                        .build(),
                )
            }
            // A TIMELINE repeats end-to-end; REPEAT_MODE_ONE would loop the
            // current clip forever and never advance to the next one.
            repeatMode = if (clips.size > 1) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_ONE
            setPlaybackSpeed(clipRate(clips.first()))
            prepare()
            playWhenReady = true
        }
    }
    var previewError by remember(player) { mutableStateOf<String?>(null) }
    DisposableEffect(player, project.lookId, project.adjust, clips) {
        fun updateLook(redrawPausedFrame: Boolean) {
            val active = clips.getOrNull(player.currentMediaItemIndex)
            colorMatrix.set(
                MemeVideoColor.glMatrix(
                    space.bitos.core.studio.MemeLooks.adjustedMatrixFor(
                        active?.lookId ?: project.lookId,
                        project.adjust,
                    ),
                ),
            )
            // A playing player consumes the matrix on its next decoded frame.
            // A paused player needs one same-position seek to refresh the held frame.
            if (redrawPausedFrame && !player.isPlaying && player.playbackState == Player.STATE_READY) {
                player.seekTo(player.currentMediaItemIndex, player.currentPosition)
            }
        }
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateLook(false)
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                previewError = "Video preview unavailable. Tap retry."
            }
        }
        updateLook(true)
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, clips) {
        player.setPlaybackSpeed(clipRate(clips.getOrElse(player.currentMediaItemIndex) { clips.first() }))
    }
    /** Seeks the timeline clock to [timelineMs] (maps to item + position).
     *  Clipped items address positions RELATIVE to their window start. */
    fun seekTimelineTo(timelineMs: Long) {
        var remaining = timelineMs.coerceIn(0L, max(1L, timelineDurationMs))
        for (index in clips.indices) {
            val out = clipOutputMs(clips[index])
            if (remaining < out || index == clips.lastIndex) {
                player.seekTo(index, (remaining * clipRate(clips[index])).toLong())
                return
            }
            remaining -= out
        }
    }

    DisposableEffect(player, sourceFiles) {
        transport?.apply {
            playPause = { if (player.isPlaying) player.pause() else player.play() }
            seekTo = { ms -> seekTimelineTo(ms) }
            isPlaying = { player.isPlaying }
        }
        onDispose {
            transport?.apply {
                playPause = {}
                seekTo = { }
                isPlaying = { false }
            }
            player.release()
            sourceFiles.forEach { runCatching { it.delete() } }
        }
    }

    var timelineMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    // Re-keyed when the timeline shape changes so the offsets/duration the
    // clock reads can never go stale after a trim/split/reorder.
    LaunchedEffect(player, clips) {
        while (true) {
            val index = player.currentMediaItemIndex.coerceIn(0, clips.lastIndex.coerceAtLeast(0))
            // ClippingConfiguration remaps item positions to the WINDOW
            // (0 = window start) — no source-start subtraction here, or the
            // playhead drifts out of sync with what's on screen.
            val localPos = player.currentPosition.coerceAtLeast(0L)
            val clip = clips.getOrElse(index) { clips.first() }
            player.setPlaybackSpeed(clipRate(clip))
            timelineMs = (offsets.getOrElse(index) { 0L }) + ((localPos / clipRate(clip)).toLong())
            onPositionChange(timelineMs.coerceIn(0L, max(1L, timelineDurationMs)))
            playing = player.isPlaying
            // Per-clip audio follows the playhead (mute/volume preview).
            player.volume = (clips.getOrNull(index)?.volume ?: 1f).coerceIn(0f, 1f)
            // 30 fps keeps the cursor and timed overlays fluid without
            // spending a frame loop while playback is paused.
            kotlinx.coroutines.delay(if (player.isPlaying) 33 else 100)
        }
    }

    val aspect = clips.first().probe.uprightWidth.toFloat() / clips.first().probe.uprightHeight
    Column {
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val fittedWidth = minOf(maxWidth, maxHeight * aspect)
            val fittedHeight = fittedWidth / aspect
            Box(
                modifier = Modifier
                    .size(fittedWidth, fittedHeight)
                    .onSizeChanged(onStageSized),
            ) {
                AndroidView(
                    factory = { contextView ->
                        // Inflated so the stage uses its explicit, standard
                        // Media3 surface and fit policy on every device.
                        @Suppress("InflateParams") // attached by AndroidView
                        android.view.LayoutInflater.from(contextView)
                            .inflate(space.bitos.app.R.layout.video_stage_player_view, null)
                            as PlayerView
                    },
                    // The player is rebuilt whenever the clip list changes
                    // (a multi-take handoff appends clips across frames, so
                    // the stage composes with partial lists first). The
                    // factory runs ONCE — re-attach here or the view keeps a
                    // released player and renders nothing.
                    update = { view -> view.player = player },
                    modifier = Modifier.fillMaxSize(),
                )
                previewError?.let { message ->
                    androidx.compose.material3.Button(
                        onClick = {
                            previewError = null
                            player.prepare()
                            player.play()
                        },
                        modifier = Modifier.align(Alignment.Center),
                    ) { Text(message) }
                }
                overlays.forEach { overlay ->
                    // MST-044/M5: out-of-window overlays hide; fx transforms
                    // track the playhead on the TIMELINE clock.
                    if (!space.bitos.core.studio.MemeFxRules.visibleAt(overlay, timelineMs)) return@forEach
                    OverlayNode(
                        overlay = overlay,
                        stageWidthPx = stageWidthPx,
                        stageHeightPx = stageHeightPx,
                        selected = overlay.id == selectedId,
                        fx = space.bitos.core.studio.MemeFxRules.transformAt(overlay, timelineMs),
                        imageAssets = imageAssets,
                    )
                }
                overlays.firstOrNull { it.id == selectedId }?.let { selected ->
                    DeleteHandle(
                        overlay = selected,
                        stageWidthPx = stageWidthPx,
                        stageHeightPx = stageHeightPx,
                    )
                }
                // Gesture layer INSIDE the fitted box: tap positions map 1:1
                // onto the overlay/delete-handle math.
                StageGestures(
                    stageWidthPx = stageWidthPx,
                    stageHeightPx = stageHeightPx,
                    state = state,
                )
            }
        }
        if (showScrub) {
            var draggingScrubber by remember { mutableStateOf(false) }
            var pendingScrubMs by remember { mutableStateOf(0f) }
            // Scrub row: play/pause + timeline position slider.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BitOSSpacing.base),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier.semantics { contentDescription = if (playing) "Pause" else "Play" },
                ) {
                    Icon(
                        if (playing) AppIcons.Pause else AppIcons.Play,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(BitOSSpacing.sm))
                Box(Modifier.weight(1f)) {
                    val duration = max(1L, timelineDurationMs)
                    BitosSlider(
                        value = if (draggingScrubber) pendingScrubMs
                            else timelineMs.toFloat().coerceIn(0f, duration.toFloat()),
                        onValueChange = {
                            draggingScrubber = true
                            pendingScrubMs = it
                        },
                        onValueChangeFinished = {
                            seekTimelineTo(pendingScrubMs.toLong())
                            draggingScrubber = false
                        },
                        valueRange = 0f..duration.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Cue markers (accent ticks) at their timeline fraction.
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
                        val trackWidth = maxWidth
                        cues.forEach { cue ->
                            Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = trackWidth * (cue.atMs.toFloat() / duration))
                                    .size(width = 3.dp, height = 14.dp)
                                    .background(BitOSColors.primary, RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                }
                Text(
                    "%02d:%02d".format((timelineMs / 1000) / 60, (timelineMs / 1000) % 60),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(BitOSSpacing.sm))
                androidx.compose.material3.TextButton(
                    onClick = { onSetCover(timelineMs) },
                    modifier = Modifier.semantics { contentDescription = "Set cover at playhead" },
                ) {
                    Text(
                        if (coverSet) "Cover ✓" else "Set cover",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (coverSet) BitOSColors.success else BitOSColors.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.W600,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
