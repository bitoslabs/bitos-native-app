package space.bitos.app.ui.create.meme

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.app.ui.theme.SolarStudioIcon
import space.bitos.app.ui.theme.SolarStudioIconImage
import space.bitos.core.studio.MemeFontSlot
import space.bitos.core.studio.MemeMode
import space.bitos.core.studio.MemeExportRules
import space.bitos.core.studio.MemeOverlay
import space.bitos.core.studio.MemeOverlayKind
import space.bitos.core.studio.MemeProject
import space.bitos.core.studio.MemeProjectContract
import space.bitos.core.studio.MemeRules
import space.bitos.core.studio.StickerCatalog

/** A session asset: project id + platform image ref + decoded aspect. */
private data class EditorAsset(
    val id: String,
    val uri: Uri,
    /** width / height of the decoded image. */
    val aspect: Float,
)

/**
 * One M5 timeline clip (session-scoped V1): a source clip + its window in
 * source media time. Timeline order = list order; output time per clip is
 * window ÷ the project speed.
 */
internal data class SessionClip(
    val id: String,
    val bytes: ByteArray,
    val probe: MemeVideoExport.Probe,
    val startMs: Long,
    val endMs: Long,
    /** Per-clip audio gain (0 = mute; preview + export). */
    val volume: Float = 1f,
    /** Per-clip grade; null = the project grade. */
    val lookId: String? = null,
)

/** Timeline clip cap (M5 plan §F1). */
private const val MAX_TIMELINE_CLIPS = 8

/**
 * Quick MEM image editor (plan MST-010..015; wave 1 of the M1 execution
 * note in `docs/native/meme-studio-plan.md`). Layout per the app-15
 * `scr-quick` mockup: chrome (✕ · mode chips · undo) → stage (media +
 * overlays, drag/pinch/twist) → media tray → text/sticker tools.
 *
 * All edits flow through [MemeEditorState] → shared `MemeRules`; this file
 * only maps gestures → commands and commands → pixels. Export (MST-016)
 * and publish (MST-017) arrive in later waves — the editor is
 * session-only until MST-018 lands the project store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemeEditorScreen(
    onClose: () -> Unit,
    mediaPublishViewModel: space.bitos.app.ui.feed.MediaPublishViewModel? = null,
    store: MemeProjectStore? = null,
    resume: MemeProjectStore.SavedSlot? = null,
    template: space.bitos.core.studio.MemeTemplate? = null,
    sharedTagsJson: String? = null,
    sharedContent: String? = null,
    /** CAP handoff (M5): camera record-screen takes that seed video mode. */
    videoSeeds: List<ByteArray>? = null,
    onSlotsChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    /** Full-screen export experience (render progress → result → Done). */
    var showExport by remember { mutableStateOf(false) }
    val state = remember(resume) {
        MemeEditorState().apply {
            resume?.let { saved -> restore(saved.document.project) }
            template?.let { tpl -> restore(space.bitos.core.studio.MemeTemplates.apply(project, tpl.id)) }
            if (sharedTagsJson != null && sharedContent != null) {
                space.bitos.core.bridge.BusinessCoreBridge()
                    .memeApplySharedTemplate(
                        space.bitos.core.studio.MemeProjectContract.encode(project),
                        sharedTagsJson, sharedContent,
                    )
                    .takeIf { it.isNotEmpty() }
                    ?.let { space.bitos.core.studio.MemeProjectContract.decode(it) }
                    ?.let { restore(it) }
            }
        }
    }
    // M2 GIF mode session frames (in-memory; PNG bytes feed slot autosave).
    val gifFrames = remember { mutableStateListOf<android.graphics.Bitmap>() }
    val gifDelays = remember { mutableStateListOf<Int>() }
    /** PNG bytes per frame id — slot autosave source (mem: scheme). */
    val gifFrameBytes = remember { androidx.compose.runtime.mutableStateMapOf<String, ByteArray>() }
    // M5 multi-clip timeline: video mode owns an ordered clip list (each
    // camera take / picker import = one clip). V1 is session-scoped; the
    // first clip still rides the v1 slot wire for autosave.
    val videoClips = remember { mutableStateListOf<SessionClip>() }
    var selectedClipIndex by remember { mutableIntStateOf(0) }
    var videoPositionMs by remember { mutableStateOf(0L) }
    // Recomputed each composition — the clip list read here is what
    // subscribes the editor to timeline changes.
    val hasVideo = videoClips.isNotEmpty()
    val videoBytes: ByteArray? = videoClips.firstOrNull()?.bytes
    val videoProbe: MemeVideoExport.Probe? = videoClips.firstOrNull()?.probe
    val videoRate: Float =
        space.bitos.core.studio.MemeProjectContract.clampSpeed(state.project.speed)

    fun clipOutputMs(clip: SessionClip): Long =
        (((clip.endMs - clip.startMs).coerceAtLeast(0L)) / videoRate).toLong()

    fun clipTimelineOffsetMs(index: Int): Long =
        videoClips.subList(0, index.coerceIn(0, videoClips.size)).sumOf { clipOutputMs(it) }

    val timelineDurationMs: Long = videoClips.sumOf { clipOutputMs(it) }

    /** Maps a timeline position to (clip, media-time) — cover capture,
     * overlay windows and the burn-in all speak timeline time. */
    fun timelineToMedia(timelineMs: Long): Pair<SessionClip, Long>? {
        videoClips.forEachIndexed { index, clip ->
            val start = clipTimelineOffsetMs(index)
            val end = start + clipOutputMs(clip)
            if (timelineMs < end || index == videoClips.lastIndex) {
                return clip to (clip.startMs + ((timelineMs - start) * videoRate).toLong())
            }
        }
        return null
    }

    /** Mirrors the session clip list into the project wire (M5 persistence). */
    fun syncWireClips() {
        state.syncClips(
            videoClips.map {
                space.bitos.core.studio.MemeClip(it.id, it.startMs, it.endMs, it.volume, it.lookId)
            },
        )
    }

    /**
     * Fresh unique clip ids survive splits/removals (never reuse a wire id).
     * Declared before its callers among the local helpers below.
     */
    fun maxClipCounter(): Int = videoClips.maxOfOrNull {
        it.id.removePrefix("v").takeWhile(Char::isDigit).toIntOrNull() ?: 0
    } ?: 0

    /** Appends a probed source as a new timeline clip (cut rules applied). */
    fun appendClip(bytes: ByteArray, probe: MemeVideoExport.Probe) {
        if (videoClips.size >= MAX_TIMELINE_CLIPS) {
            exportStatus = "Clip limit reached ($MAX_TIMELINE_CLIPS)"
            return
        }
        if (videoClips.sumOf { it.bytes.size.toLong() } + bytes.size > MemeVideoExport.MAX_SOURCE_BYTES) {
            exportStatus = "This clip would push the timeline over the 256 MB source cap"
            return
        }
        val cut = space.bitos.core.studio.MemeVideoCutRules.cutForDuration(probe.durationMs)
        if (cut.cut) exportStatus = cut.message
        val id = "v${maxClipCounter() + 1}"
        state.addAssets(listOf(id), kind = MemeMode.VIDEO)
        videoClips += SessionClip(id, bytes, probe, cut.startMs, cut.endMs)
        selectedClipIndex = videoClips.lastIndex
        syncWireClips()
    }

    fun removeClip(index: Int) {
        if (index !in videoClips.indices) return
        videoClips.removeAt(index)
        selectedClipIndex = selectedClipIndex.coerceIn(0, (videoClips.size - 1).coerceAtLeast(0))
        syncWireClips()
    }

    fun moveClip(index: Int, delta: Int) {
        val target = index + delta
        if (index !in videoClips.indices || target !in videoClips.indices) return
        val clip = videoClips.removeAt(index)
        videoClips.add(target, clip)
        selectedClipIndex = target
        syncWireClips()
    }

    /**
     * M5 split: the clip under the timeline playhead becomes two clips over
     * the same source (≥200 ms each side, like the trim rule); volume/look
     * carry to both halves. No-op when the playhead sits too close to an
     * edge or outside any clip.
     */
    fun splitAtPlayhead() {
        if (videoClips.size >= MAX_TIMELINE_CLIPS) {
            exportStatus = "Clip limit reached ($MAX_TIMELINE_CLIPS)"
            return
        }
        val timelineMs = videoPositionMs
        for (index in videoClips.indices) {
            val clip = videoClips[index]
            val offset = clipTimelineOffsetMs(index)
            val outMs = clipOutputMs(clip)
            if (timelineMs < offset || timelineMs >= offset + outMs) continue
            val intoMediaMs = ((timelineMs - offset) * videoRate).toLong()
            val splitAt = clip.startMs + intoMediaMs
            if (splitAt - clip.startMs < 200 || clip.endMs - splitAt < 200) {
                exportStatus = "Too close to a clip edge to split"
                return
            }
            val second = clip.copy(
                id = "v${maxClipCounter() + 1}",
                startMs = splitAt,
            )
            videoClips[index] = clip.copy(endMs = splitAt)
            videoClips.add(index + 1, second)
            selectedClipIndex = index
            syncWireClips()
            return
        }
    }

    var videoPickPending by remember { mutableStateOf(false) }
    var videoTrimBytes by remember { mutableStateOf<ByteArray?>(null) }
    /** MST-032: separately-uploaded cover URL (session-only in V1). */
    var coverThumbUrl by remember { mutableStateOf<String?>(null) }
    val videoMode = state.project.mode == MemeMode.VIDEO
    var gifPreviewIndex by remember { mutableIntStateOf(0) }
    var gifUniformDelayMs by remember { mutableStateOf(0) } // 0 = keep source delays
    var confirmModeSwitch by remember { mutableStateOf(false) }
    var pendingModeSwitch by remember { mutableStateOf(MemeMode.IMAGE) }

    val assets = remember(resume) {
        mutableStateListOf<EditorAsset>().apply {
            resume?.let { saved ->
                if (saved.document.project.mode == MemeMode.VIDEO) {
                    // M5 resume: the wire's clip list + slot asset files
                    // rebuild the full timeline (windows from the wire; a
                    // v1 slot migrates into a single clip server-side).
                    saved.document.project.clips.forEach { wireClip ->
                        val file = saved.assetFiles[wireClip.id] ?: return@forEach
                        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                        val probe = MemeVideoExport.probe(
                            context.contentResolver,
                            android.net.Uri.fromFile(file),
                        )
                        if (probe != null) {
                            videoClips += SessionClip(
                                wireClip.id, bytes, probe,
                                wireClip.startMs.coerceIn(0, probe.durationMs),
                                wireClip.endMs.coerceAtMost(probe.durationMs),
                                wireClip.volume,
                                wireClip.lookId,
                            )
                        }
                    }
                    selectedClipIndex = 0
                    // IMAGE layers resume with the slot (their assets ride
                    // the same assetFiles map the image mode uses).
                    saved.document.project.assets
                        .filter { it.id != "v1" && it.kind == MemeMode.IMAGE }
                        .forEach { asset ->
                            saved.assetFiles[asset.id]?.let { file ->
                                decodeAspect(context.contentResolver, android.net.Uri.fromFile(file))
                                    ?.let { aspect ->
                                        add(EditorAsset(asset.id, android.net.Uri.fromFile(file), aspect))
                                    }
                            }
                        }
                } else if (saved.document.project.mode == MemeMode.GIF) {
                    // GIF resume: slot files decode back into the frame tray
                    // (holds collapse to a uniform 100 ms — source delays are
                    // not part of the slot wire in V1).
                    saved.document.assets.sortedBy { it.id }.forEach { asset ->
                        saved.assetFiles[asset.id]?.let { file ->
                            android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                                gifFrames += bitmap
                                gifDelays += GifFrameSource.STILL_DELAY_MS
                            }
                        }
                    }
                } else {
                    saved.document.assets.forEach { asset ->
                        saved.assetFiles[asset.id]?.let { file ->
                            add(EditorAsset(asset.id, android.net.Uri.fromFile(file), asset.aspect))
                        }
                    }
                }
            }
        }
    }
    val slotId = remember(resume) {
        resume?.document?.slotId ?: "s-" + java.util.UUID.randomUUID().toString().take(13)
    }
    // Clip-import progress (camera handoff / picker insert): "1/3" style
    // loading state so the studio never looks dead while sources probe.
    var seedingProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // CAP handoff (M5): camera takes enter video mode AS CLIPS — no merge.
    // Each take is probed and cut to the allowed window, then joins the
    // timeline. All probes run off-thread FIRST and the appends land in one
    // go, so the stage never composes against a partial clip list (and the
    // player/PlayerView pair is built exactly once).
    androidx.compose.runtime.LaunchedEffect(videoSeeds) {
        val seeds = videoSeeds ?: return@LaunchedEffect
        if (resume == null && state.project.assets.isEmpty() && videoClips.isEmpty() && seeds.isNotEmpty()) {
            seedingProgress = 0 to seeds.size
            val probed = withContext(Dispatchers.IO) {
                seeds.mapIndexed { index, seed ->
                    val probe = runCatching {
                        val temp = java.io.File.createTempFile("meme-seed", ".mp4")
                        try {
                            temp.writeBytes(seed)
                            MemeVideoExport.probe(context.contentResolver, android.net.Uri.fromFile(temp))
                        } finally {
                            runCatching { temp.delete() }
                        }
                    }.getOrNull()
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        seedingProgress = index + 1 to seeds.size
                    }
                    seed to probe
                }
            }
            state.switchMode(MemeMode.VIDEO)
            var failed = false
            for ((seed, probe) in probed) {
                if (probe != null) {
                    appendClip(seed, probe)
                } else {
                    failed = true
                }
            }
            seedingProgress = null
            if (failed) exportStatus = "A camera take could not be read"
        }
    }
    var activeAssetId by remember { mutableStateOf(resume?.document?.assets?.firstOrNull()?.id) }
    // Compose observation: one read subscribes the whole editor scope.
    val dataRevision = state.revision
    var stagePx by remember { mutableStateOf(IntSize.Zero) }
    var showDiscard by remember { mutableStateOf(false) }
    var editingOverlayId by remember { mutableStateOf<String?>(null) }
    var showStickers by remember { mutableStateOf(false) }
    var showLooks by remember { mutableStateOf(false) }
    var showSfx by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showTrim by remember { mutableStateOf(false) }
    /** M5 per-clip management sheet (trim · reorder · remove · duplicate). */
    var showClipSheet by remember { mutableStateOf(false) }
    /** M5 per-clip audio (volume · mute) — replaces the old "Sound · soon". */
    var showVolume by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    /** V2 Draw mode: pen strokes captured on the stage (all modes). */
    var drawMode by remember { mutableStateOf(false) }
    var penColorIndex by remember { mutableIntStateOf(2) }
    var penWidthNorm by remember { mutableStateOf(space.bitos.core.studio.MemeProjectContract.DEFAULT_STROKE_WIDTH) }
    /** In-progress stroke points (normalized), painted live. */
    val liveStrokePoints = remember { mutableStateListOf<Float>() }
    /** V2 suite expert dock (mockup app-15 scr-suite) — video mode only. */
    var suiteMode by remember { mutableStateOf(false) }
    val videoTransport = remember { VideoTransport() }
    val recentStickers = remember { mutableStateListOf<String>() }
    var showPublish by remember { mutableStateOf(false) }
    val gifMode = state.project.mode == MemeMode.GIF
    val memePublishState = mediaPublishViewModel?.memeState?.collectAsStateWithLifecycle()?.value

    val activeAsset = assets.firstOrNull { it.id == activeAssetId }

    // Multi-select import (Photo Picker, ≤9 ComposerRules.MAX_IMAGES
    // parity): each pick gets a session id and its aspect is decoded for
    // the stage box. The whole batch lands in one pass.
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = MemeProjectContract.maxAssets(MemeMode.IMAGE),
        ),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> decodeAspect(context.contentResolver, uri)?.let { uri to it } }
            }
            val existing = assets.map { it.id }.toSet()
            val candidates = decoded.mapIndexedNotNull { index, (uri, aspect) ->
                val id = "a${assets.size + index + 1}"
                if (id in existing) null else EditorAsset(id, uri, aspect)
            }
            val accepted = state.addAssets(candidates.map { it.id })
            accepted.forEach { id ->
                candidates.firstOrNull { it.id == id }?.let { assets += it }
            }
            if (activeAssetId == null) activeAssetId = accepted.firstOrNull()
        }
    }

    /**
     * MST-018 autosave (EDT-002; M5 multi-clip): debounced 500 ms after
     * every committed edit or asset change, the whole session — including
     * every timeline clip source — persists to the slot store (assets
     * copy-in + wire + poster + LRU index). Kill/relaunch loses nothing
     * the user saw committed.
     */
    LaunchedEffect(store, slotId, dataRevision, assets.size, activeAssetId, gifFrames.size, videoClips.size) {
        val slotStore = store ?: return@LaunchedEffect
        if (state.isEmpty && assets.isEmpty() && gifFrames.isEmpty() && videoClips.isEmpty()) {
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(space.bitos.core.studio.MemeSlots.AUTOSAVE_DEBOUNCE_MS)
        withContext(Dispatchers.IO) {
            val frameBytes = gifFrameBytes.toMap()
            val clipSources = videoClips.associate { clip -> "mem:${clip.id}" to clip.bytes }
            val opener = MemeProjectStore.AssetOpener { key ->
                if (key.startsWith("mem:v")) {
                    clipSources[key]
                } else if (key.startsWith("mem:")) {
                    val mem: ByteArray? = frameBytes[key.removePrefix("mem:")]
                    mem
                } else {
                    readAssetBytes(context, android.net.Uri.parse(key))
                }
            }
            val refs = assets.map { MemeProjectStore.AssetRef(it.id, it.uri.toString()) } +
                (1..gifFrames.size).map { MemeProjectStore.AssetRef("f$it", "mem:f$it") } +
                videoClips.map { MemeProjectStore.AssetRef(it.id, "mem:${it.id}") }
            slotStore.save(
                slotId = slotId,
                projectWire = space.bitos.core.studio.MemeProjectContract.encode(state.project),
                assets = refs,
                opener = opener,
                nowMs = System.currentTimeMillis(),
            )
        }
        onSlotsChanged()
    }

    fun clearSlot() {
        val slotStore = store ?: return
        scope.launch {
            withContext(Dispatchers.IO) { slotStore.deleteSlot(slotId) }
            onSlotsChanged()
        }
    }

    // A published WIP has served its purpose — its slot goes away.
    LaunchedEffect(memePublishState?.phase) {
        if (memePublishState?.phase == space.bitos.app.ui.feed.MemePublishPhase.DONE) {
            clearSlot()
        }
    }

    // MST-021: the stage loops the frame reel at each frame's hold time
    // (uniform override > source delays); overlays persist across frames.
    LaunchedEffect(gifMode, gifFrames.size, gifPreviewIndex, gifUniformDelayMs) {
        if (!gifMode || gifFrames.size < 2) return@LaunchedEffect
        val hold = (if (gifUniformDelayMs > 0) {
            gifUniformDelayMs
        } else {
            gifDelays.getOrElse(gifPreviewIndex) { GifFrameSource.STILL_DELAY_MS }
        }).coerceAtLeast(MemeProjectContract.MIN_FRAME_DELAY_MS)
        kotlinx.coroutines.delay(hold.toLong())
        gifPreviewIndex = (gifPreviewIndex + 1) % gifFrames.size
    }

    /** GIF mode: decode picks into bounded frames (animated or stills). */
    val gifPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val before = gifFrames.size
            withContext(Dispatchers.IO) {
                val bytes = readAssetBytes(context, uri) ?: return@withContext
                val cap = MemeProjectContract.maxAssets(MemeMode.GIF) - before
                val decoded = GifFrameSource.decode(bytes, cap)
                fun capture(frame: android.graphics.Bitmap) {
                    val stream = java.io.ByteArrayOutputStream()
                    frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                    gifFrameBytes["f${gifFrames.size + 1}"] = stream.toByteArray()
                }
                if (decoded != null && decoded.frameCount > 0) {
                    decoded.bitmaps.forEach {
                        capture(it)
                        gifFrames += it
                    }
                    decoded.delaysMs.forEach { gifDelays += it }
                } else {
                    // Static image picked in GIF mode: one 100 ms still frame.
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                        if (gifFrames.size < MemeProjectContract.maxAssets(MemeMode.GIF)) {
                            capture(it)
                            gifFrames += it
                            gifDelays += GifFrameSource.STILL_DELAY_MS
                        }
                    }
                }
            }
            // Keep the project wire's frame ids in step with the tray.
            state.addAssets((before + 1..gifFrames.size).map { "f$it" })
            gifPreviewIndex = 0
        }
    }

    /** M3: pick a bounded source clip, hand it through the existing trim screen. */
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                readAssetBytes(context, uri)
            }
            if (picked == null) {
                exportStatus = "Could not read this video. Try another file or grant Photos access."
                return@launch
            }
            if (picked.size.toLong() > MemeVideoExport.MAX_SOURCE_BYTES) {
                exportStatus = "This source video is larger than 256 MB. Trim it first, then try again."
                return@launch
            }
            val probe = withContext(Dispatchers.IO) {
                MemeVideoExport.probe(context.contentResolver, uri)
            }
            if (probe == null) {
                exportStatus = "Not a readable video"
                return@launch
            }
            // Long clips are NOT rejected — the system cuts them to the
            // allowed window and says why (MemeVideoCutRules, MST-030 rev).
            videoPickPending = true
            videoTrimBytes = picked
        }
    }

    /**
     * V2 source insert: an image/GIF picked in video mode becomes an IMAGE
     * layer overlay bound to a fresh asset (GIFs paint their first frame —
     * V1 semantics, noted in the Layers sheet).
     */
    val layerPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val aspect = withContext(Dispatchers.IO) {
                decodeAspect(context.contentResolver, uri)
            } ?: run {
                exportStatus = "Could not read this image"
                return@launch
            }
            val id = "a${assets.size + 1}"
            val accepted = state.addAssets(listOf(id), kind = MemeMode.IMAGE)
            if (accepted.isEmpty()) {
                exportStatus = "Layer limit reached (clip + ${MemeProjectContract.MAX_IMAGE_LAYERS})"
                return@launch
            }
            assets += EditorAsset(id, uri, aspect)
            state.addImageOverlay(id)
        }
    }

    /** IMAGE layer sources for the stage/export: overlay asset id → (uri, aspect). */
    val imageAssetMap = remember(assets.toList(), dataRevision) {
        assets.associate { it.id to (it.uri to it.aspect) }
    }
    /** IMAGE layer sources for the exporter: id → uri only. */
    val imageAssetUris = remember(assets.toList(), dataRevision) {
        assets.associate { it.id to it.uri }
    }

    fun launchPicker() {
        if (videoMode) {
            videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        } else if (gifMode) {
            gifPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        } else {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    /** MST-016/022: render (+ GIF-encode) → MediaStore, off the UI. */
    fun saveToDevice() {
        if (exporting) return
        if (gifMode) {
            if (gifFrames.isEmpty()) {
                exportStatus = "Pick frames first"
                return
            }
            exporting = true
            showExport = true
            exportStatus = null
            scope.launch {
                var gifExportInfo: MemeGifExport.Result? = null
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val delays = if (gifUniformDelayMs > 0) {
                            List(gifFrames.size) { gifUniformDelayMs }
                        } else {
                            gifDelays.toList()
                        }
                        val exported = MemeGifExport.export(gifFrames.toList(), delays, state.project)
                            ?: error("GIF export failed")
                        gifExportInfo = exported
                        MemeRaster.saveMediaFile(
                            context,
                            exported.gifBytes,
                            "image/gif",
                            "bitos-meme-${System.currentTimeMillis()}",
                        )
                    }
                }
                exporting = false
                exportStatus = result.fold(
                    onSuccess = {
                        val exported = gifExportInfo
                        if (exported != null && (exported.ladderStep > 0 || exported.capped)) {
                            "Saved (downscaled ×${exported.ladderStep})"
                        } else {
                            "Saved to Photos ✓"
                        }
                    },
                    onFailure = { "Save failed: ${it.message}" },
                )
            }
            return
        }
        if (videoMode) {
            if (!hasVideo) { exportStatus = "Pick a clip first"; return }
            val timelineMs = timelineDurationMs
            exporting = true
            showExport = true
            exportStatus = null
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val exported = MemeVideoExport.exportClips(
                            context, videoClips.toList().toClipInputs(videoRate), state.project,
                            sfxMixTimeline(state.project, timelineMs),
                            imageAssets = imageAssetUris,
                        )
                        MemeRaster.saveVideoFile(
                            context, exported, "bitos-meme-${System.currentTimeMillis()}",
                        )
                    }
                }
                exporting = false
                exportStatus = result.fold(
                    onSuccess = { "Saved to Movies ✓" },
                    onFailure = { "Save failed: ${it.message}" },
                )
            }
            return
        }
        val asset = activeAsset
        if (asset == null) {
            exportStatus = "Pick an image first"
            return
        }
        exporting = true
        exportStatus = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val source = MemeRaster.decodeForExport(context.contentResolver, asset.uri)
                        ?: error("Image could not be read")
                    MemeRaster.savePng(
                        context,
                        MemeRaster.render(source, state.project),
                        "bitos-meme-${System.currentTimeMillis()}",
                    )
                }
            }
            exporting = false
            exportStatus = result.fold(
                onSuccess = { "Saved to Photos ✓" },
                onFailure = { "Save failed: ${it.message}" },
            )
        }
    }

    fun requestClose() {
        if (state.isEmpty) {
            clearSlot()
            onClose()
        } else {
            showDiscard = true
        }
    }

    // Back follows the ✕ contract; a mid-air gesture is cancelled first.
    BackHandler(enabled = true) {
        when {
            showExport -> {
                showExport = false
                exportStatus = null
            }
            state.gestureActive -> state.cancelGesture()
            editingOverlayId != null -> editingOverlayId = null
            else -> requestClose()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .statusBarsPadding(),
    ) {
        // ── Top chrome: exit · mode chips · undo ─────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.xs, vertical = BitOSSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::requestClose) {
                Icon(AppIcons.Close, contentDescription = "Close editor", tint = BitOSColors.textPrimary)
            }
            Spacer(Modifier.width(BitOSSpacing.sm))
            ModeChips(
                modifier = Modifier.weight(1f),
                activeMode = state.project.mode,
                onPickMode = { mode ->
                    if (mode == state.project.mode) return@ModeChips
                    if (state.isEmpty && gifFrames.isEmpty()) {
                        state.switchMode(mode)
                    } else {
                        confirmModeSwitch = true
                        pendingModeSwitch = mode
                    }
                },
            )
            if (mediaPublishViewModel != null && (activeAssetId != null || gifFrames.isNotEmpty() || videoBytes != null)) {
                TextButton(
                    onClick = { showPublish = true },
                    enabled = memePublishState?.phase.let {
                        it != space.bitos.app.ui.feed.MemePublishPhase.UPLOADING &&
                            it != space.bitos.app.ui.feed.MemePublishPhase.PUBLISHING
                    },
                ) {
                    Text("Post", color = BitOSColors.primary, fontWeight = FontWeight.W600)
                }
            }
            IconButton(
                onClick = { state.undo() },
                enabled = state.canUndo,
                modifier = Modifier.semantics { contentDescription = "Undo" },
            ) {
                SolarStudioIconImage(
                    SolarStudioIcon.UndoLeft,
                    contentDescription = null,
                    tint = BitOSColors.textPrimary,
                )
            }
        }

        // ── Stage: media + overlays, single gesture target ───────────────
        val stageWidth = stagePx.width.coerceAtLeast(1)
        val stageHeight = stagePx.height.coerceAtLeast(1)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.base, vertical = BitOSSpacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            val current = if (gifMode || videoMode) null else activeAsset
            if (videoMode && hasVideo) {
                // WYSIWYG grade preview: the whole-project look rides the
                // stage as a hardware layer color filter (the same shared
                // matrix the exporter burns; per-clip overrides stay
                // export-exact). Overlays sit above, un-graded, like the
                // export composition.
                val lookFilter = space.bitos.core.studio.MemeLooks.normalize(state.project.lookId)?.let { lookId ->
                    androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                        androidx.compose.ui.graphics.ColorMatrix(
                            space.bitos.core.studio.MemeLooks.matrixFor(lookId),
                        ),
                    )
                }
                Box(
                    modifier = if (lookFilter != null) {
                        Modifier.fillMaxSize().graphicsLayer { colorFilter = lookFilter }
                    } else {
                        Modifier.fillMaxSize()
                    },
                ) {
                VideoStage(
                    clips = videoClips.toList(),
                    project = state.project,
                    rate = videoRate,
                    stageWidthPx = stageWidth,
                    stageHeightPx = stageHeight,
                    onStageSized = { stagePx = it },
                    overlays = state.project.overlays,
                    selectedId = state.selectedOverlayId,
                    state = state,
                    coverSet = coverThumbUrl != null,
                    cues = state.project.sfxCues,
                    onPositionChange = { videoPositionMs = it },
                    imageAssets = imageAssetMap,
                    showScrub = !suiteMode,
                    transport = videoTransport,
                    onSetCover = { timelineMs ->
                        val mapped = timelineToMedia(timelineMs) ?: return@VideoStage
                        scope.launch {
                            val jpeg = withContext(Dispatchers.IO) {
                                MemeVideoExport.captureCoverJpeg(mapped.first.bytes, mapped.second)
                            }
                            if (jpeg == null) {
                                exportStatus = "Cover capture failed"
                            } else {
                                mediaPublishViewModel?.uploadMemeCover(jpeg) { url ->
                                    coverThumbUrl = url
                                    if (url == null) exportStatus = "Cover upload failed"
                                }
                            }
                        }
                    },
                )
                }
            } else if (current == null && !gifMode && !videoMode) {
                EmptyCanvasCta(onPick = ::launchPicker)
            } else if (videoMode) {
                EmptyCanvasCta(onPick = ::launchPicker)
            } else if (gifMode && gifFrames.isEmpty()) {
                EmptyCanvasCta(onPick = ::launchPicker)
            } else if (gifMode) {
                val frame = gifFrames[gifPreviewIndex.coerceIn(0, gifFrames.size - 1)]
                val frameAspect = frame.width.toFloat() / frame.height
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(frameAspect, matchHeightConstraintsFirst = frameAspect < 1f)
                        .onSizeChanged { stagePx = it },
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                    state.project.overlays.forEach { overlay ->
                        OverlayNode(
                            overlay = overlay,
                            stageWidthPx = stageWidth,
                            stageHeightPx = stageHeight,
                            selected = overlay.id == state.selectedOverlayId,
                        )
                    }
                    val selected = state.project.overlays.firstOrNull { it.id == state.selectedOverlayId }
                    if (selected != null) {
                        DeleteHandle(
                            overlay = selected,
                            stageWidthPx = stageWidth,
                            stageHeightPx = stageHeight,
                        )
                    }
                    StageHintChip(hasSelection = state.selectedOverlayId != null)
                }
                StageGestures(
                    stageWidthPx = stageWidth,
                    stageHeightPx = stageHeight,
                    state = state,
                )
            } else if (current != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(current.aspect, matchHeightConstraintsFirst = current.aspect < 1f)
                        .onSizeChanged { stagePx = it },
                ) {
                    AsyncImage(
                        model = current.uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                        // WYSIWYG: the stage shows the same composed grade
                        // the rasterizer burns into exports (MST-043).
                        colorFilter = state.project.lookId?.let { lookId ->
                            androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                                androidx.compose.ui.graphics.ColorMatrix(
                                    space.bitos.core.studio.MemeLooks.matrixFor(lookId),
                                ),
                            )
                        },
                    )
                    state.project.overlays.forEach { overlay ->
                        OverlayNode(
                            overlay = overlay,
                            stageWidthPx = stageWidth,
                            stageHeightPx = stageHeight,
                            selected = overlay.id == state.selectedOverlayId,
                        )
                    }
                    val selected = state.project.overlays.firstOrNull { it.id == state.selectedOverlayId }
                    if (selected != null) {
                        DeleteHandle(
                            overlay = selected,
                            stageWidthPx = stageWidth,
                            stageHeightPx = stageHeight,
                        )
                    }
                    StageHintChip(hasSelection = state.selectedOverlayId != null)
                }
                StageGestures(
                    stageWidthPx = stageWidth,
                    stageHeightPx = stageHeight,
                    state = state,
                )
            }
            // Draw mode captures pen strokes ON the media rect (above all
            // stage content — it owns touches while active).
            if (drawMode) {
                DrawCaptureLayer(
                    project = state.project,
                    stageWidthPx = stageWidth,
                    stageHeightPx = stageHeight,
                    livePoints = liveStrokePoints.toList(),
                    colorIndex = penColorIndex,
                    widthNorm = penWidthNorm,
                    onStroke = { points -> state.addStroke(penColorIndex, penWidthNorm, points) },
                )
            }
        }

        // V2 Draw mode: pen controls sit between the stage and the tray.
        if (drawMode) {
            PenControlsRow(
                colorIndex = penColorIndex,
                onPickColor = { penColorIndex = it },
                widthNorm = penWidthNorm,
                onPickWidth = { penWidthNorm = it },
                canUndoStroke = state.project.drawStrokes.isNotEmpty(),
                onUndoStroke = { state.removeLastStroke() },
                onClear = { state.clearDrawing() },
                onDone = {
                    liveStrokePoints.clear()
                    drawMode = false
                },
            )
        }

        // ── Media tray / V2 suite dock (mockup app-15 scr-suite) ────────
        if (videoMode && suiteMode && hasVideo) {
            SuiteDock(
                project = state.project,
                clips = videoClips.toList(),
                rate = videoRate,
                selectedClipIndex = selectedClipIndex,
                onSelectClip = { selectedClipIndex = it },
                positionMs = videoPositionMs,
                transport = videoTransport,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onUndo = { state.undo() },
                onRedo = { state.redo() },
                onOpenLayers = { showLayers = true },
                looksEnabled = (videoMode && hasVideo) || activeAsset != null,
                onOpenLooks = { showLooks = true },
                onOpenSfx = { showSfx = true },
                onOpenDraw = { drawMode = true },
                onOpenTrim = { showTrim = true },
                onOpenClip = { showClipSheet = true },
                onOpenVolume = { showVolume = true },
                onSplit = ::splitAtPlayhead,
                onOpenSpeed = { showSpeed = true },
                onExport = ::saveToDevice,
                exporting = exporting,
                onClose = { suiteMode = false },
            )
        } else {
        if (gifMode) {
            GifFrameTray(
                frames = gifFrames,
                activeIndex = gifPreviewIndex.coerceIn(0, (gifFrames.size - 1).coerceAtLeast(0)),
                uniformDelayMs = gifUniformDelayMs,
                onPickFrame = { gifPreviewIndex = it },
                onReorder = { from, to ->
                    if (from != to && from in gifFrames.indices && to in gifFrames.indices) {
                        val frame = gifFrames.removeAt(from)
                        val delay = gifDelays.removeAt(from)
                        gifFrames.add(to.coerceIn(0, gifFrames.size), frame)
                        gifDelays.add(to.coerceIn(0, gifDelays.size), delay)
                        gifPreviewIndex = to
                    }
                },
                onDelayChange = { gifUniformDelayMs = it },
                onAddFrames = ::launchPicker,
            )
        } else if (videoMode) {
            // M5 source tray: the timeline's clips + inserted image/GIF layers.
            VideoSourceTray(
                clips = videoClips.toList(),
                selectedClipIndex = selectedClipIndex,
                assets = assets,
                onSelectClip = { selectedClipIndex = it },
                onAddClip = {
                    videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
                onPickLayer = { layerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
        } else {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = BitOSSpacing.base),
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            rowItems(assets, key = { it.id }) { asset ->
                TrayTile(
                    asset = asset,
                    active = asset.id == activeAssetId,
                    onClick = { activeAssetId = asset.id },
                )
            }
            if (assets.size < MemeProjectContract.maxAssets(MemeMode.IMAGE)) {
                item(key = "add") {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, BitOSColors.border),
                        onClick = ::launchPicker,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                AppIcons.Add,
                                contentDescription = "Add image",
                                tint = BitOSColors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
        }

        // ── Status line: a full-width row of its own so long messages
        // (size-limit cuts, save results) never wrap into a letter column.
        when {
                exporting -> Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BitOSSpacing.base),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                    color = BitOSColors.primary,
                )
                Spacer(Modifier.width(BitOSSpacing.xs))
                Text(
                    "Rendering…",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textSecondary,
                )
            }

                exportStatus != null -> Text(
                exportStatus!!,
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BitOSSpacing.base),
            )

                state.selectedOverlayId != null -> Text(
                "drag · scale · rotate",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // ── Bottom tools: text · sticker · save (scrollable — 48dp targets
        // never compress or push each other off-screen) ────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = BitOSSpacing.base, vertical = BitOSSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            ToolButton(
                icon = SolarStudioIcon.Text,
                description = "Add text",
                enabled = state.canAddOverlay,
                onClick = {
                    val id = state.addOverlay(MemeOverlayKind.TEXT, "")
                    if (id != null) editingOverlayId = id
                },
            )
            ToolButton(
                icon = SolarStudioIcon.Sticker,
                description = "Add sticker",
                enabled = state.canAddOverlay,
                onClick = { showStickers = true },
            )
            ToolButton(
                icon = SolarStudioIcon.Palette,
                description = "Color look",
                enabled = activeAsset != null,
                onClick = { showLooks = true },
            )
            ToolButton(
                icon = SolarStudioIcon.Soundwave,
                description = "Sound effects",
                enabled = videoMode && videoBytes != null,
                onClick = { showSfx = true },
            )
            ToolButton(
                icon = SolarStudioIcon.Pen,
                description = "Draw",
                enabled = true,
                onClick = { drawMode = !drawMode },
            )
            ToolIconButton(
                icon = AppIcons.Download,
                description = "Save to Photos",
                enabled = (activeAsset != null || gifFrames.isNotEmpty() || videoBytes != null) && !exporting,
                onClick = ::saveToDevice,
            )
            if (videoMode && videoProbe != null) {
                ToolIconButton(
                    icon = AppIcons.AppsGrid,
                    description = "Expert suite",
                    enabled = videoBytes != null,
                    onClick = { suiteMode = true },
                )
            }
        }
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard meme?") },
            text = {
                Text(
                    if (store != null && !state.isEmpty) {
                        "Your edits live in this session. Discard and delete the saved draft?"
                    } else {
                        "Your edits live only in this session. Discard and close the editor?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clearSlot()
                    onClose()
                }) { Text("Discard", color = BitOSColors.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) { Text("Keep editing") }
            },
        )
    }

    if (confirmModeSwitch) {
        AlertDialog(
            onDismissRequest = { confirmModeSwitch = false },
            title = { Text("Start a ${if (pendingModeSwitch == MemeMode.GIF) "GIF" else "image"} project?") },
            text = { Text("Switching clears the current media (overlays stay). Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmModeSwitch = false
                    state.switchMode(pendingModeSwitch)
                    when (pendingModeSwitch) {
                        MemeMode.GIF -> {
                            assets.clear()
                            activeAssetId = null
                            videoClips.clear()
                        }
                        MemeMode.VIDEO -> {
                            assets.clear()
                            activeAssetId = null
                            gifFrames.clear()
                            gifDelays.clear()
                        }
                        else -> {
                            gifFrames.clear()
                            gifDelays.clear()
                            videoClips.clear()
                        }
                    }
                }) { Text("Switch", color = BitOSColors.primary) }
            },
            dismissButton = {
                TextButton(onClick = { confirmModeSwitch = false }) { Text("Cancel") }
            },
        )
    }

    editingOverlayId?.let { id ->
        val overlay = state.project.overlays.firstOrNull { it.id == id }
        if (overlay == null) {
            editingOverlayId = null
        } else {
            TextSheet(
                overlay = overlay,
                onText = { state.updateStyle(id, text = it) },
                onFont = { state.updateStyle(id, font = it) },
                onSize = { state.updateStyle(id, size = it) },
                onColor = { state.updateStyle(id, colorIndex = it) },
                onOutline = { state.updateStyle(id, outline = it) },
                onShadow = { state.updateStyle(id, shadow = it) },
                onFx = { fx ->
                    state.updateStyle(id, fx = fx, clearFx = fx == null)
                },
                onWindow = { startMs, endMs ->
                    state.updateStyle(
                        id,
                        startMs = startMs,
                        endMs = endMs,
                        clearEndMs = (endMs ?: 0L) <= 0L,
                    )
                },
                showTiming = videoMode,
                onDelete = {
                    state.removeOverlay(id)
                    editingOverlayId = null
                },
                onDismiss = {
                    if (overlay.text.isBlank()) state.removeOverlay(id)
                    editingOverlayId = null
                },
            )
        }
    }

    if (videoPickPending && videoTrimBytes != null) {
        // MST-030/M5: a picked clip flows through the EXISTING trim screen;
        // its "use" output APPENDS to the timeline as a new clip.
        space.bitos.app.ui.create.VideoPreviewScreen(
            bytes = videoTrimBytes!!,
            mimeType = "video/mp4",
            onUse = { trimmed, _ ->
                videoPickPending = false
                videoTrimBytes = null
                // Fresh picks never probed (only slot-restore did) — probe
                // now; appendClip applies the duration cut rules.
                seedingProgress = 0 to 1
                scope.launch(Dispatchers.IO) {
                    val probed = runCatching {
                        val temp = java.io.File.createTempFile("meme-probe", ".mp4")
                        try {
                            temp.writeBytes(trimmed)
                            MemeVideoExport.probe(
                                context.contentResolver,
                                android.net.Uri.fromFile(temp),
                            )
                        } finally {
                            runCatching { temp.delete() }
                        }
                    }.getOrNull()
                    if (probed != null) {
                        appendClip(trimmed, probed)
                    } else {
                        exportStatus = "Not a readable video"
                    }
                    seedingProgress = null
                }
            },
            onRetake = {
                videoPickPending = false
                videoTrimBytes = null
            },
        )
        return
    }

    if (showStickers) {
        ModalBottomSheet(onDismissRequest = { showStickers = false }) {
            StickerSheetContent(
                recents = recentStickers.toList(),
                onPick = { emoji ->
                    val id = state.addOverlay(MemeOverlayKind.STICKER, emoji)
                    if (id != null) {
                        recentStickers.removeAll { it == emoji }
                        recentStickers.add(0, emoji)
                        if (recentStickers.size > StickerCatalog.MAX_RECENT_STICKERS) {
                            recentStickers.removeAt(recentStickers.lastIndex)
                        }
                    }
                },
            )
        }
    }

    if (showLooks) {
        ModalBottomSheet(onDismissRequest = { showLooks = false }) {
            if (videoMode && hasVideo) {
                // M5: the grade applies to the SELECTED clip; "none" clears
                // the override (the project grade shows through again).
                val clip = videoClips.getOrNull(selectedClipIndex) ?: videoClips.first()
                LooksSheetContent(
                    active = clip.lookId ?: state.project.lookId
                        ?: space.bitos.core.studio.MemeLooks.NONE,
                    onPick = { id ->
                        val index = videoClips.indexOf(clip)
                        if (index >= 0) {
                            videoClips[index] = clip.copy(
                                lookId = space.bitos.core.studio.MemeLooks.normalize(id),
                            )
                            syncWireClips()
                        }
                        showLooks = false
                    },
                )
            } else {
                LooksSheetContent(
                    active = state.project.lookId ?: space.bitos.core.studio.MemeLooks.NONE,
                    onPick = { id ->
                        state.setLook(id)
                        showLooks = false
                    },
                )
            }
        }
    }

    if (showSfx) {
        ModalBottomSheet(onDismissRequest = { showSfx = false; SfxPreview.stop() }) {
            SfxSheetContent(
                cues = state.project.sfxCues,
                positionMs = videoPositionMs,
                onAdd = { state.addSfxCue(it, videoPositionMs) },
                onRemove = { state.removeSfxCue(it) },
            )
        }
    }

    if (showLayers) {
        ModalBottomSheet(onDismissRequest = { showLayers = false }) {
            LayersSheetContent(
                project = state.project,
                assets = assets.toList(),
                selectedId = state.selectedOverlayId,
                onSelect = { id ->
                    state.select(id)
                    showLayers = false
                },
                onDelete = { state.removeOverlay(it) },
                onInsert = {
                    showLayers = false
                    layerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        }
    }

    if (showTrim && hasVideo) {
        val clip = videoClips.getOrNull(selectedClipIndex) ?: videoClips.first()
        ModalBottomSheet(onDismissRequest = { showTrim = false }) {
            TrimSheetContent(
                startMs = clip.startMs,
                endMs = clip.endMs,
                durationMs = clip.probe.durationMs,
                speed = state.project.speed,
                onApply = { start, end ->
                    val index = videoClips.indexOf(clip)
                    if (index >= 0) {
                        videoClips[index] = clip.copy(startMs = start, endMs = end)
                        syncWireClips()
                    }
                    showTrim = false
                },
            )
        }
    }

    if (showVolume && hasVideo) {
        val clip = videoClips.getOrNull(selectedClipIndex) ?: videoClips.first()
        ModalBottomSheet(onDismissRequest = { showVolume = false }) {
            VolumeSheetContent(
                clipLabel = "vdo ${videoClips.indexOf(clip) + 1}",
                volume = clip.volume,
                onApply = { volume ->
                    val index = videoClips.indexOf(clip)
                    if (index >= 0) {
                        videoClips[index] = clip.copy(volume = volume)
                        syncWireClips()
                    }
                    showVolume = false
                },
            )
        }
    }

    if (showClipSheet && hasVideo) {
        ModalBottomSheet(onDismissRequest = { showClipSheet = false }) {
            ClipSheetContent(
                clips = videoClips.toList(),
                rate = videoRate,
                selectedClipIndex = selectedClipIndex,
                onSelect = { selectedClipIndex = it },
                onMove = ::moveClip,
                onRemove = { index ->
                    removeClip(index)
                    if (videoClips.isEmpty()) showClipSheet = false
                },
                onAdd = {
                    showClipSheet = false
                    videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
            )
        }
    }

    if (showSpeed) {
        ModalBottomSheet(onDismissRequest = { showSpeed = false }) {
            SpeedSheetContent(
                speed = state.project.speed,
                mediaDurationMs = videoProbe?.durationMs ?: state.project.trimEndMs,
                onPick = {
                    state.setSpeed(it)
                    showSpeed = false
                },
            )
        }
    }

    // Clip-import progress: full-screen scrim so the studio never looks
    // dead while camera takes / picked clips are probed and staged.
    seedingProgress?.let { (done, total) ->
        ClipImportOverlay(done = done, total = total)
    }

    if (showExport) {
        ExportFullScreen(
            exporting = exporting,
            status = exportStatus,
            onDone = {
                showExport = false
                exportStatus = null
            },
        )
    }

    if (showPublish && mediaPublishViewModel != null) {
        MemePublishScreen(
            state = state,
            asset = activeAsset,
            canPublish = activeAsset != null || gifFrames.isNotEmpty() || hasVideo,
            publishState = memePublishState,
            onPublish = { caption, altText, cwReason, remixOf, remixAuthor ->
                val project = state.project
                // Video/GIF modes have no `activeAsset` (their media lives in
                // session bytes) — the mode itself gates readiness here.
                val mediaReady = activeAsset != null ||
                    (project.mode == MemeMode.VIDEO && hasVideo) ||
                    (project.mode == MemeMode.GIF && gifFrames.isNotEmpty())
                if (!mediaReady) return@MemePublishScreen
                scope.launch {
                    val rendered = withContext(Dispatchers.IO) {
                        if (project.mode == MemeMode.VIDEO && hasVideo) {
                            // MST-034: video memes publish as kind 22/21 by
                            // orientation with duration+dim imeta. Over-size
                            // exports are CUT (duration ladder), not failed —
                            // M5: the ladder trims the LAST clip's window.
                            val probe = videoClips.first().probe
                            fun exportNow(): ByteArray = MemeVideoExport.exportClips(
                                context, videoClips.toList().toClipInputs(videoRate), state.project,
                                sfxMixTimeline(state.project, timelineDurationMs),
                                imageAssets = imageAssetUris,
                            )
                            var exported = exportNow()
                            var durationMs = timelineDurationMs
                            var cuts = 0
                            while (exported.size > space.bitos.core.model.Blossom.MAX_FILE_BYTES &&
                                cuts < space.bitos.core.studio.MemeVideoCutRules.MAX_CUT_ATTEMPTS
                            ) {
                                val cut = space.bitos.core.studio.MemeVideoCutRules.nextCutForSize(
                                    durationMs, exported.size.toLong(),
                                    space.bitos.core.model.Blossom.MAX_FILE_BYTES,
                                ) ?: break
                                // Shrink the tail clip's window to fit the
                                // allowed output duration.
                                val allowedTimelineMs = cut.endMs
                                var remaining = allowedTimelineMs
                                val trimmed = videoClips.mapIndexed { index, clip ->
                                    if (index < videoClips.lastIndex) {
                                        remaining -= clipOutputMs(clip)
                                        clip
                                    } else {
                                        val window = (remaining * videoRate).toLong()
                                            .coerceIn(200L, clip.probe.durationMs)
                                        clip.copy(endMs = (clip.startMs + window).coerceAtMost(clip.probe.durationMs))
                                    }
                                }
                                videoClips.clear()
                                videoClips.addAll(trimmed)
                                syncWireClips()
                                exportStatus = cut.message
                                durationMs = timelineDurationMs
                                cuts += 1
                                exported = exportNow()
                            }
                            Triple(exported, probe.uprightWidth, probe.uprightHeight) to "video/mp4"
                        } else if (project.mode == MemeMode.GIF && gifFrames.isNotEmpty()) {
                            // MST-023: GIF memes publish as kind-20 with
                            // imeta `m image/gif` (the same verify-before-sign
                            // machine as PNG — only the bytes differ).
                            val delays = if (gifUniformDelayMs > 0) {
                                List(gifFrames.size) { gifUniformDelayMs }
                            } else {
                                gifDelays.toList()
                            }
                            val exported = MemeGifExport.export(gifFrames.toList(), delays, project)
                                ?: error("GIF export failed")
                            Triple(
                                exported.gifBytes,
                                exported.canvasWidth,
                                exported.canvasHeight,
                            ) to "image/gif"
                        } else {
                            val asset = activeAsset ?: error("Pick an image first")
                            val source = MemeRaster.decodeForExport(context.contentResolver, asset.uri)
                                ?: error("Image could not be read")
                            val (width, height) = MemeExportRules.outputSize(source.width, source.height)
                            val bitmap = MemeRaster.render(source, project)
                            val bytes = java.io.ByteArrayOutputStream().also { stream ->
                                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
                            }.toByteArray()
                            Triple(bytes, width, height) to "image/png"
                        }
                    }
                    if (project.mode == MemeMode.VIDEO) {
                        // The exported timeline (windows ÷ speed) is the
                        // truth — what actually hit the wire.
                        val effectiveDurationMs = timelineDurationMs
                        mediaPublishViewModel.publishMemeVideo(
                            bytes = rendered.first.first,
                            width = rendered.first.second,
                            height = rendered.first.third,
                            durationMs = effectiveDurationMs,
                            caption = caption,
                            altText = altText,
                            contentWarningReason = cwReason,
                            thumbUrl = coverThumbUrl,
                            remixTagsJson = remixTagsFor(state.project, remixOf, remixAuthor),
                        )
                    } else {
                        mediaPublishViewModel.publishMemePicture(
                            bytes = rendered.first.first,
                            width = rendered.first.second,
                            height = rendered.first.third,
                            caption = caption,
                            altText = altText,
                            contentWarningReason = cwReason,
                            mimeType = rendered.second,
                            remixTagsJson = remixTagsFor(state.project, remixOf, remixAuthor),
                        )
                    }
                }
            },
            onDismiss = { showPublish = false },
        )
    }
}

/** Decode-only aspect pass (no bitmap allocation); null for unreadable files. */
private fun decodeAspect(resolver: android.content.ContentResolver, uri: Uri): Float? = try {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
    if (options.outWidth <= 0 || options.outHeight <= 0) {
        null
    } else {
        options.outWidth.toFloat() / options.outHeight
    }
} catch (_: Exception) {
    null
}

/** Asset bytes for slot persistence: content picks via resolver, restored
 * slot files (file://) straight off disk. */
private fun readAssetBytes(context: android.content.Context, uri: Uri): ByteArray? = runCatching {
    when (uri.scheme?.lowercase()) {
        "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        "file" -> uri.path?.let { path -> java.io.File(path).takeIf(File::exists)?.readBytes() }
        else -> null
    }
}.getOrNull()

@Composable
private fun ModeChips(
    modifier: Modifier = Modifier,
    activeMode: MemeMode = MemeMode.IMAGE,
    onPickMode: (MemeMode) -> Unit = {},
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
        ModeChip(label = "Image", active = activeMode == MemeMode.IMAGE, enabled = true) {
            onPickMode(MemeMode.IMAGE)
        }
        ModeChip(label = "GIF", active = activeMode == MemeMode.GIF, enabled = true) {
            onPickMode(MemeMode.GIF)
        }
        ModeChip(label = "Video", active = activeMode == MemeMode.VIDEO, enabled = true) {
            onPickMode(MemeMode.VIDEO)
        }
    }
}

@Composable
private fun ModeChip(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = active,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
    )
}

@Composable
private fun EmptyCanvasCta(onPick: () -> Unit) {
    val borderColor = BitOSColors.border
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        onClick = onPick,
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .aspectRatio(1f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRoundRect(
                        color = borderColor,
                        cornerRadius = CornerRadius(16.dp.toPx()),
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                        ),
                    )
                }
                .padding(BitOSSpacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    AppIcons.Photo,
                    contentDescription = null,
                    tint = BitOSColors.primary,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(BitOSSpacing.sm))
                Text("Pick an image", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(BitOSSpacing.xs))
                Text(
                    "Up to ${MemeProjectContract.maxAssets(MemeMode.IMAGE)} images",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textSecondary,
                )
            }
        }
    }
}


/**
 * GIF frame tray (plan MST-020): ordered frame tiles — tap to inspect,
 * long-press + drag to reorder (swaps with the passed neighbor) — plus
 * the uniform frame-delay control (20–1000 ms; 0 keeps source delays).
 */
@Composable
private fun GifFrameTray(
    frames: List<android.graphics.Bitmap>,
    activeIndex: Int,
    uniformDelayMs: Int,
    onPickFrame: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onDelayChange: (Int) -> Unit,
    onAddFrames: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = BitOSSpacing.base),
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            rowItems(frames.indices.toList(), key = { it }) { index ->
                val frame = frames[index]
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        if (index == activeIndex) 2.dp else 1.dp,
                        if (index == activeIndex) BitOSColors.primary else BitOSColors.border,
                    ),
                    modifier = Modifier
                        .size(56.dp)
                        .pointerInput(frames.size) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                var totalDrag = 0f
                                var moved = false
                                var swappedFrom = index
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pan = event.calculatePan().x
                                    if (down.uptimeMillis + 350 > event.changes.first().uptimeMillis &&
                                        !moved && kotlin.math.abs(totalDrag) > 8.dp.toPx()
                                    ) {
                                        // Started moving before the long-press held: a scroll, not a reorder.
                                    }
                                    if (event.changes.first().uptimeMillis - down.uptimeMillis > 350 ||
                                        moved
                                    ) {
                                        totalDrag += pan
                                        if (kotlin.math.abs(totalDrag) > 48.dp.toPx()) {
                                            val direction = if (totalDrag > 0) 1 else -1
                                            val target = (swappedFrom + direction).coerceIn(0, frames.size - 1)
                                            if (target != swappedFrom) {
                                                onReorder(swappedFrom, target)
                                                swappedFrom = target
                                                totalDrag = 0f
                                            }
                                        }
                                        moved = true
                                        event.changes.forEach { it.consume() }
                                    }
                                    if (!event.changes.any { it.pressed }) break
                                }
                            }
                        },
                    onClick = { onPickFrame(index) },
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Frame ${index + 1} — long-press and drag to reorder",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            if (frames.size < MemeProjectContract.maxAssets(MemeMode.GIF)) {
                item(key = "add-frame") {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, BitOSColors.border),
                        onClick = onAddFrames,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                AppIcons.Add,
                                contentDescription = "Add frames",
                                tint = BitOSColors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
        LabeledSlider(
            label = "Frame delay",
            value = (if (uniformDelayMs > 0) uniformDelayMs else 100).toFloat(),
            range = MemeProjectContract.MIN_FRAME_DELAY_MS..MemeProjectContract.MAX_FRAME_DELAY_MS,
        ) { value ->
            onDelayChange(value.roundToInt())
        }
        Text(
            if (uniformDelayMs > 0) {
                "Every frame holds ${uniformDelayMs} ms"
            } else {
                "Source frame delays (${frames.size} frames)"
            },
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textTertiary,
            modifier = Modifier.padding(horizontal = BitOSSpacing.base),
        )
    }
}

@Composable
private fun TrayTile(asset: EditorAsset, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = BitOSColors.surfaceElevated,
        border = if (active) BorderStroke(2.dp, BitOSColors.primary) else BorderStroke(1.dp, BitOSColors.border),
        onClick = onClick,
        modifier = Modifier.size(56.dp),
    ) {
        AsyncImage(
            model = asset.uri,
            contentDescription = "Asset ${asset.id}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ToolButton(icon: SolarStudioIcon, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = BitOSColors.surfaceElevated,
        border = BorderStroke(1.dp, BitOSColors.border),
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            SolarStudioIconImage(
                icon,
                contentDescription = null,
                tint = BitOSColors.textPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ToolIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = BitOSColors.surfaceElevated,
        border = BorderStroke(1.dp, BitOSColors.border),
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = BitOSColors.textPrimary)
        }
    }
}

/**
 * One overlay: content centered on its normalized position, scale/rotate
 * around that center (the zero-size layout trick keeps the transform
 * origin at the normalized point).
 */
@Composable
internal fun OverlayNode(
    overlay: MemeOverlay,
    stageWidthPx: Int,
    stageHeightPx: Int,
    selected: Boolean,
    fx: space.bitos.core.studio.MemeFxRules.FxTransform = space.bitos.core.studio.MemeFxRules.IDENTITY,
    /** IMAGE layers: overlay asset id → (picker uri, aspect). */
    imageAssets: Map<String, Pair<android.net.Uri, Float>> = emptyMap(),
) {
    val density = LocalDensity.current
    // Font/outline px live on the 1080-HIGH reference (web `paintOverlay`:
    // px = size × canvas height / 1080) — the export rasterizer shares it,
    // so stage ⇄ export stay WYSIWYG.
    val scalePx = stageHeightPx / 1080f
    val fontSize = with(density) { (overlay.size * scalePx * overlay.scale).toSp() }
    val outlinePx = overlay.outline * scalePx * overlay.scale
    val color = Color(MemeRules.PALETTE[overlay.colorIndex.coerceIn(0, MemeRules.PALETTE.lastIndex)])
    val accent = BitOSColors.primary
    val isSticker = overlay.kind == MemeOverlayKind.STICKER
    val imageAsset = if (overlay.kind == MemeOverlayKind.IMAGE) {
        overlay.assetId?.let { imageAssets[it] }
    } else {
        null
    }
    val imageHeight = with(density) { (overlay.size * scalePx * overlay.scale).toDp() }

    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(0, 0) {
                    placeable.placeRelative(-placeable.width / 2, -placeable.height / 2)
                }
            }
            .graphicsLayer {
                translationX = overlay.x * stageWidthPx + fx.dx * stageWidthPx
                translationY = overlay.y * stageHeightPx + fx.dy * stageHeightPx
                scaleX = overlay.scale * fx.scale
                scaleY = overlay.scale * fx.scale
                rotationZ = overlay.rotationDeg +
                    (fx.rotateRad * 180f / kotlin.math.PI.toFloat())
                alpha = fx.alpha
            },
    ) {
        Box(
            modifier = Modifier
                .drawBehind {
                    if (selected) {
                        drawRoundRect(
                            color = accent,
                            cornerRadius = CornerRadius(10.dp.toPx()),
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                            ),
                        )
                        // Mockup scr-quick: orange corner dots with a dark
                        // rim anchor the dashed bounds visually.
                        val radius = 4.dp.toPx()
                        val rim = 1.5.dp.toPx()
                        listOf(
                            Offset(radius + rim, radius + rim),
                            Offset(size.width - radius - rim, size.height - radius - rim),
                        ).forEach { center ->
                            drawCircle(Color.Black, radius + rim, center)
                            drawCircle(accent, radius, center)
                        }
                    }
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (imageAsset != null) {
                // Source-insert layer: the imported image/GIF still, aspect
                // kept, height on the same 1080 reference as text (WYSIWYG
                // with the export rasterizer's drawImageItem).
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(imageAsset.first)
                        .crossfade(false)
                        .build(),
                    contentDescription = "Image layer",
                    contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                    modifier = Modifier
                        .height(imageHeight)
                        .aspectRatio(imageAsset.second.coerceIn(0.2f, 5f)),
                )
            } else if (!isSticker && outlinePx > 0f) {
                // Classic meme outline: stroke copy behind the fill copy.
                Box {
                    Text(
                        text = overlay.text,
                        color = Color.Black,
                        fontSize = fontSize,
                        fontFamily = fontSlotFamily(overlay.font),
                        fontWeight = fontSlotWeight(overlay.font),
                        textAlign = TextAlign.Center,
                        style = TextStyle.Default.copy(
                            drawStyle = Stroke(width = outlinePx * 2f, miter = 4f),
                        ),
                    )
                    Text(
                        text = overlay.text,
                        color = color,
                        fontSize = fontSize,
                        fontFamily = fontSlotFamily(overlay.font),
                        fontWeight = fontSlotWeight(overlay.font),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    text = overlay.text,
                    color = color,
                    fontSize = fontSize,
                    fontFamily = fontSlotFamily(overlay.font),
                    fontWeight = fontSlotWeight(overlay.font),
                    textAlign = TextAlign.Center,
                    style = if (overlay.shadow) {
                        TextStyle.Default.copy(
                            shadow = Shadow(Color.Black, Offset(3f, 3f), blurRadius = 6f),
                        )
                    } else {
                        TextStyle.Default
                    },
                )
            }
        }
    }
}

/** Mockup scr-quick: the top-left "drag · scale · rotate" hint chip. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.StageHintChip(hasSelection: Boolean) {
    if (!hasSelection) return
    Surface(
        shape = RoundedCornerShape(50),
        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp),
    ) {
        Text(
            "drag · scale · rotate",
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Dashed selection bounds + delete handle chip (rotation-ignored, like hit-test). */
@Composable
internal fun DeleteHandle(overlay: MemeOverlay, stageWidthPx: Int, stageHeightPx: Int) {
    val (boundsW, boundsH) = MemeRules.estimateBounds(overlay)
    val chipCenterX = overlay.x * stageWidthPx + boundsW * stageWidthPx / 2f
    val chipCenterY = overlay.y * stageHeightPx - boundsH * stageHeightPx / 2f
    // The handle is drawn but taps are handled by [stageGestures] (the
    // gesture layer owns the stage), so this node is pointer-transparent.
    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(0, 0) { placeable.placeRelative(0, 0) }
            }
            .graphicsLayer {
                translationX = chipCenterX
                translationY = chipCenterY
            },
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .drawBehind {
                    drawCircle(color = Color.White)
                    drawCircle(
                        color = Color(0xFF0A0A0F),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Close,
                contentDescription = null,
                tint = Color(0xFF0A0A0F),
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * The stage's single gesture target: down → live transform of the
 * selection; up → tap (no movement) hit-tests deletion/selection.
 */
private fun Modifier.stageGestures(
    state: MemeEditorState,
    stageWidthPx: () -> Int,
    stageHeightPx: () -> Int,
    deleteTapRadiusPx: () -> Float,
): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val downPos = down.position
        var moved = false
        var tappedDeleteId: String? = null
        state.beginGesture()
        while (true) {
            val event = awaitPointerEvent()
            val zoom = event.calculateZoom()
            val rotation = event.calculateRotation()
            val pan = event.calculatePan()
            if (zoom != 1f || rotation != 0f || pan != Offset.Zero) moved = true
            if (moved) {
                state.selectedOverlayId?.let { id ->
                    state.project.overlays.firstOrNull { it.id == id }?.let { current ->
                        state.gestureUpdate(
                            x = current.x + pan.x / stageWidthPx(),
                            y = current.y + pan.y / stageHeightPx(),
                            scale = current.scale * zoom,
                            rotationDeg = current.rotationDeg + rotation,
                        )
                    }
                }
            }
            event.changes.forEach { change ->
                if (change.positionChanged()) change.consume()
            }
            if (!event.changes.any { it.pressed }) {
                if (!moved) {
                    // Tap: delete handle first, then hit-test select/deselect.
                    val selected = state.project.overlays
                        .firstOrNull { it.id == state.selectedOverlayId }
                    if (selected != null) {
                        val (bw, bh) = MemeRules.estimateBounds(selected)
                        val cx = selected.x * stageWidthPx() + bw * stageWidthPx() / 2f
                        val cy = selected.y * stageHeightPx() - bh * stageHeightPx() / 2f
                        val dx = downPos.x - cx
                        val dy = downPos.y - cy
                        if (dx * dx + dy * dy <= deleteTapRadiusPx() * deleteTapRadiusPx()) {
                            tappedDeleteId = selected.id
                        }
                    }
                    if (tappedDeleteId == null) {
                        val hit = state.selectAt(
                            downPos.x / stageWidthPx(),
                            downPos.y / stageHeightPx(),
                        )
                        if (!hit) state.clearSelection()
                    }
                }
                break
            }
        }
        state.endGesture()
        tappedDeleteId?.let { state.removeOverlay(it) }
    }
}

@Composable
internal fun StageGestures(
    stageWidthPx: Int,
    stageHeightPx: Int,
    state: MemeEditorState,
) {
    val density = LocalDensity.current
    val tapRadius = with(density) { 22.dp.toPx() }
    val w = rememberUpdatedState(stageWidthPx)
    val h = rememberUpdatedState(stageHeightPx)
    val r = rememberUpdatedState(tapRadius)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .stageGestures(
                state = state,
                stageWidthPx = { w.value },
                stageHeightPx = { h.value },
                deleteTapRadiusPx = { r.value },
            ),
    )
}

private fun fontSlotFamily(slot: MemeFontSlot): FontFamily = when (slot) {
    MemeFontSlot.IMPACT -> FontFamily.SansSerif
    MemeFontSlot.SANS -> FontFamily.Default
    MemeFontSlot.SERIF -> FontFamily.Serif
    MemeFontSlot.MONO -> FontFamily.Monospace
}

private fun fontSlotWeight(slot: MemeFontSlot): FontWeight = when (slot) {
    MemeFontSlot.IMPACT -> FontWeight.Black
    MemeFontSlot.SANS -> FontWeight.Bold
    MemeFontSlot.SERIF -> FontWeight.Bold
    MemeFontSlot.MONO -> FontWeight.Medium
}

/** Text sheet (mockup "Text style" card): field, font slots, palette,
 * size + outline sliders, shadow toggle — all live via update commands
 * that coalesce, so the burst is one undo step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSheet(
    overlay: MemeOverlay,
    onText: (String) -> Unit,
    onFont: (MemeFontSlot) -> Unit,
    onSize: (Int) -> Unit,
    onColor: (Int) -> Unit,
    onOutline: (Int) -> Unit,
    onShadow: (Boolean) -> Unit,
    onFx: (space.bitos.core.studio.MemeOverlayFx?) -> Unit,
    onWindow: (startMs: Long?, endMs: Long?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    showTiming: Boolean = false,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Focus the field on open — a new text overlay starts empty, and the
        // keyboard showing is the cue that typing is expected.
        val textFocus = androidx.compose.ui.focus.FocusRequester()
        LaunchedEffect(Unit) { runCatching { textFocus.requestFocus() } }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BitOSSpacing.base)
                .padding(bottom = BitOSSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            OutlinedTextField(
                value = overlay.text,
                onValueChange = onText,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(textFocus),
                label = { Text("Text") },
                singleLine = true,
            )
            // Font slots (semantic — platforms map to licensed faces).
            Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                MemeFontSlot.entries.forEach { slot ->
                    FilterChip(
                        selected = overlay.font == slot,
                        onClick = { onFont(slot) },
                        label = { Text(slot.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
            // Palette (shared MemeRules.PALETTE, index order).
            LazyRow(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                rowItems(MemeRules.PALETTE) { argb ->
                    val selected = overlay.colorIndex == MemeRules.PALETTE.indexOf(argb)
                    Surface(
                        shape = CircleShape,
                        color = Color(argb),
                        border = BorderStroke(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) BitOSColors.primary else BitOSColors.border,
                        ),
                        onClick = { onColor(MemeRules.PALETTE.indexOf(argb)) },
                        modifier = Modifier.size(32.dp),
                    ) {}
                }
            }
            LabeledSlider("Size", overlay.size.toFloat(), MemeRules.MIN_SIZE..MemeRules.MAX_SIZE) {
                onSize(it.roundToInt())
            }
            LabeledSlider("Outline", overlay.outline.toFloat(), MemeRules.MIN_OUTLINE..MemeRules.MAX_OUTLINE) {
                onOutline(it.roundToInt())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Shadow", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = overlay.shadow, onCheckedChange = onShadow)
            }
            // Motion fx (MST-044, web fx options) — own scrollable row.
            Row(
                horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                space.bitos.core.studio.MemeOverlayFx.entries.forEach { option ->
                    FilterChip(
                        selected = overlay.fx == option,
                        onClick = { onFx(option) },
                        label = {
                            Text(
                                option.name.lowercase().replaceFirstChar(Char::uppercase),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
                if (overlay.fx != null) {
                    FilterChip(
                        selected = false,
                        onClick = { onFx(null) },
                        label = { Text("None", style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
            if (showTiming) {
                // Visibility window: label + the two second fields side by
                // side on their OWN row (they previously shared a row with
                // the fx chips and the actions and collapsed).
                Text("Visible between", style = MaterialTheme.typography.labelMedium, color = BitOSColors.textSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                ) {
                    OutlinedTextField(
                        value = ((overlay.startMs ?: 0L) / 1000L).toString(),
                        onValueChange = { raw ->
                            onWindow((raw.toFloatOrNull()?.times(1000)?.toLong()) ?: 0L, overlay.endMs)
                        },
                        label = { Text("Start (s)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = ((overlay.endMs ?: 0L) / 1000L).toString(),
                        onValueChange = { raw ->
                            onWindow(overlay.startMs, (raw.toFloatOrNull()?.times(1000)?.toLong()) ?: 0L)
                        },
                        label = { Text("End (s)") },
                        supportingText = { Text("0 = always", color = BitOSColors.textTertiary) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // Actions: one clear row — Delete secondary, Done the primary
            // submit (a filled button so it is always visible).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BitOSSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
            ) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text("Delete", color = BitOSColors.error)
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text("Done", fontWeight = FontWeight.W600)
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: IntRange,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                value.roundToInt().toString(),
                style = MaterialTheme.typography.labelMedium,
                color = BitOSColors.textSecondary,
            )
        }
        Slider(
            value = value.coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = onValueChange,
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/** Sticker sheet: shared [StickerCatalog] packs (web port), recents row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StickerSheetContent(recents: List<String>, onPick: (String) -> Unit) {
    var packId by remember { mutableStateOf(StickerCatalog.PACKS.first().id) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.base)
            .padding(bottom = BitOSSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        Text("Stickers", style = MaterialTheme.typography.titleMedium)
        if (recents.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.labelMedium, color = BitOSColors.textSecondary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                rowItems(recents) { emoji ->
                    StickerCell(emoji = emoji, onClick = { onPick(emoji) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
            StickerCatalog.PACKS.forEach { pack ->
                FilterChip(
                    selected = packId == pack.id,
                    onClick = { packId = pack.id },
                    label = { Text(pack.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        StickerCatalog.PACKS
            .firstOrNull { it.id == packId }
            ?.let { pack ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.height(220.dp),
                    verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
                ) {
                    gridItems(pack.stickers) { emoji ->
                        StickerCell(emoji = emoji, onClick = { onPick(emoji) })
                    }
                }
            }
    }
}

@Composable
private fun StickerCell(emoji: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BitOSColors.surfaceElevated,
        onClick = onClick,
        modifier = Modifier
            .height(52.dp)
            .semantics { contentDescription = "Add $emoji sticker" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 26.sp)
        }
    }
}

/** Human label for a semantic font slot (sheet pill copy). */
private val MemeFontSlot.label: String
    get() = when (this) {
        MemeFontSlot.IMPACT -> "Impact"
        MemeFontSlot.SANS -> "Sans"
        MemeFontSlot.SERIF -> "Serif"
        MemeFontSlot.MONO -> "Mono"
    }

/**
 * Publish page (plan MST-017; mockups app-04 "Post details" + app-15
 * scr-pub "Publish machine" — ONE full-screen form, nothing behind it):
 * caption + counter (meme caps: soft 300 / hard 1000), derived hashtag
 * chips, CW toggle + reason, alt text (defaults to the caption in the
 * event when blank), remix lineage and the preflight checklist.
 * Publishing renders → hash-verified upload → kind-20 (phases in
 * [space.bitos.app.ui.feed.MemePublishPhase]).
 */
@Composable
private fun MemePublishScreen(
    state: MemeEditorState,
    asset: EditorAsset?,
    canPublish: Boolean = false,
    publishState: space.bitos.app.ui.feed.MemePublishUiState?,
    onPublish: (
        caption: String,
        altText: String,
        cwReason: String?,
        remixEventId: String,
        remixAuthor: String,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    var remixOf by remember { mutableStateOf("") }
    var remixAuthor by remember { mutableStateOf("") }
    var altText by remember { mutableStateOf("") }
    var contentWarningOn by remember { mutableStateOf(false) }
    var contentWarningReason by remember { mutableStateOf("Sensitive content") }
    val derivedTags = remember(caption) {
        space.bitos.core.publish.ComposerRules.deriveTags(caption)
            .filter { it.firstOrNull() == "t" }
            .mapNotNull { it.getOrNull(1) }
    }
    val phase = publishState?.phase ?: space.bitos.app.ui.feed.MemePublishPhase.IDLE
    val busy = phase == space.bitos.app.ui.feed.MemePublishPhase.UPLOADING ||
        phase == space.bitos.app.ui.feed.MemePublishPhase.PUBLISHING
    val published = phase == space.bitos.app.ui.feed.MemePublishPhase.DONE

    BackHandler { onDismiss() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── Header: back · title (scr-pub topnav) ────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.xs, vertical = BitOSSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Back to editor" }) {
                Icon(AppIcons.Back, contentDescription = null, tint = BitOSColors.textPrimary)
            }
            Text(
                "Publish",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
                modifier = Modifier.weight(1f),
            )
            if (published) {
                TextButton(onClick = onDismiss) { Text("Done", color = BitOSColors.primary, fontWeight = FontWeight.W600) }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BitOSSpacing.base),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            OutlinedTextField(
                value = caption,
                onValueChange = { if (it.length <= 1_000) caption = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Caption") },
                supportingText = {
                    Text(
                        "${caption.length} / 300" + if (caption.length > 300) " · hard cap 1000" else "",
                        color = if (caption.length > 300) BitOSColors.warning else BitOSColors.textTertiary,
                    )
                },
                minLines = 2,
            )

            if (derivedTags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                    derivedTags.take(6).forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = BitOSColors.primaryContainer,
                        ) {
                            Text(
                                "#$tag",
                                style = MaterialTheme.typography.labelMedium,
                                color = BitOSColors.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = altText,
                onValueChange = { if (it.length <= 200) altText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Alt text") },
                supportingText = {
                    Text("Screen readers read this; defaults to the caption", color = BitOSColors.textTertiary)
                },
                singleLine = true,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Content warning", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Gate the meme behind a visible warning",
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                    )
                }
                Switch(checked = contentWarningOn, onCheckedChange = { contentWarningOn = it })
            }
            if (contentWarningOn) {
                OutlinedTextField(
                    value = contentWarningReason,
                    onValueChange = { if (it.length <= 120) contentWarningReason = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Warning reason") },
                    singleLine = true,
                )
            }

            when {
                publishState?.failure != null -> Text(
                    publishState.failure!!,
                    color = BitOSColors.error,
                    style = MaterialTheme.typography.bodySmall,
                )

                published -> Text(
                    "Published ✓ — nothing was signed before the upload verified",
                    color = BitOSColors.success,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Remix lineage (MST-042): optional source event → tags.
            OutlinedTextField(
                value = remixOf,
                onValueChange = { if (it.length <= 256) remixOf = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Remix of (optional)") },
                supportingText = { Text("Paste the source note1/nevent1/event id") },
                singleLine = true,
            )
            if (remixOf.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
                ) {
                    Surface(shape = RoundedCornerShape(50), color = BitOSColors.primaryContainer) {
                        Text(
                            "source · ${remixOf.take(10)}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = BitOSColors.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Text("→", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
                    Surface(shape = RoundedCornerShape(50), color = BitOSColors.primary) {
                        Text(
                            "you",
                            style = MaterialTheme.typography.labelSmall,
                            color = androidx.compose.ui.graphics.Color.Black,
                            fontWeight = FontWeight.W700,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = remixAuthor,
                    onValueChange = { if (it.length <= 64) remixAuthor = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Source author (optional)") },
                    supportingText = { Text("npub/hex — becomes the p-tag") },
                    singleLine = true,
                )
            }

            // Preflight (mockup app-04 scr-review): what will publish.
            val kindLabel = when (state.project.mode) {
                MemeMode.IMAGE -> "kind 20 · picture"
                MemeMode.GIF -> "kind 20 · image/gif"
                MemeMode.VIDEO -> "kind 22/21 · by orientation"
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BitOSColors.surfaceElevated,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                    PreflightRow(done = caption.isNotBlank(), label = "Caption & tags", meta = kindLabel)
                    PreflightRow(
                        done = altText.isNotBlank() || caption.isNotBlank(),
                        label = "Alt text",
                        meta = if (altText.isBlank()) "defaults to caption" else "✓",
                    )
                    PreflightRow(
                        done = !contentWarningOn || contentWarningReason.isNotBlank(),
                        label = "Content warning",
                        meta = if (contentWarningOn) "gated: $contentWarningReason" else "off",
                    )
                }
            }

            Text(
                "Nothing is signed until the rendered meme is uploaded and hash-verified (Blossom).",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textTertiary,
            )
        }

        // ── Single primary action, pinned below the form (scr-pub) ───────
        Button(
            onClick = {
                onPublish(
                    caption, altText,
                    if (contentWarningOn) contentWarningReason else null,
                    remixOf, remixAuthor,
                )
            },
            enabled = canPublish && !busy && !published,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.base, vertical = BitOSSpacing.sm),
            shape = RoundedCornerShape(50),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                    color = BitOSColors.onPrimary,
                )
                Spacer(Modifier.width(BitOSSpacing.sm))
                Text(
                    when (phase) {
                        space.bitos.app.ui.feed.MemePublishPhase.UPLOADING -> "Uploading…"
                        else -> "Publishing…"
                    },
                )
            } else {
                Text(if (published) "Published ✓" else "Sign & publish")
            }
        }
    }
}

/** One publish-preflight row (mockup app-04 scr-review checklist). */
@Composable
private fun PreflightRow(done: Boolean, label: String, meta: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) AppIcons.Check else AppIcons.Close,
            contentDescription = null,
            tint = if (done) BitOSColors.success else BitOSColors.warning,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(BitOSSpacing.sm))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(meta, style = MaterialTheme.typography.labelSmall, color = BitOSColors.textTertiary)
    }
}

/** Color-grade picker (MST-043): the 8 web presets, media-only, undoable. */
@Composable
private fun LooksSheetContent(active: String, onPick: (String) -> Unit) {
    Column(Modifier.padding(BitOSSpacing.base)) {
        Text("Color look", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W700)
        Text(
            "Applies to the media only — captions stay crisp. Undo works.",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = BitOSSpacing.sm),
        )
        space.bitos.core.studio.MemeLooks.ALL.chunked(4).forEach { rowLooks ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = BitOSSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
            ) {
                rowLooks.forEach { look ->
                    val selected = look.id == active
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) BitOSColors.primary else BitOSColors.surfaceElevated,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClickLabel = look.label) { onPick(look.id) },
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = BitOSSpacing.sm),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                look.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.W700,
                                color = if (selected) {
                                    androidx.compose.ui.graphics.Color.Black
                                } else {
                                    BitOSColors.textPrimary
                                },
                            )
                        }
                    }
                }
                // Keep rows even when the chunk is short (8 = 2×4, future-proof).
                repeat(4 - rowLooks.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One-shot synth preview (MST-041): shared-rendered PCM → AudioTrack. */
private object SfxPreview {
    private var track: android.media.AudioTrack? = null

    fun play(recipe: space.bitos.core.studio.SfxSynth.Recipe, gain: Float = 1f) {
        stop()
        val pcm16 = space.bitos.core.studio.SfxSynth.pcm16Le(
            space.bitos.core.studio.SfxSynth.renderPcm(recipe, gain.toDouble()),
        )
        val minBuffer = android.media.AudioTrack.getMinBufferSize(
            space.bitos.core.studio.SfxSynth.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        val player = android.media.AudioTrack(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            android.media.AudioFormat.Builder()
                .setSampleRate(space.bitos.core.studio.SfxSynth.SAMPLE_RATE)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            maxOf(minBuffer, pcm16.size),
            android.media.AudioTrack.MODE_STATIC,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        player.write(pcm16, 0, pcm16.size)
        player.play()
        track = player
    }

    fun stop() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }
}

/** MST-042: lineage tags via the shared seam; "" when no source given. */
private fun remixTagsFor(
    project: space.bitos.core.studio.MemeProject,
    remixOf: String,
    remixAuthor: String,
): String {
    if (remixOf.isBlank()) return ""
    return space.bitos.core.bridge.BusinessCoreBridge().memeRemixTagsFor(
        space.bitos.core.studio.MemeProjectContract.encode(project),
        remixOf, remixAuthor, "[]", "", "",
    )
}

/** Mixed cue PCM for the current export window (null = silent). */
/** Timeline clips as export inputs (window + cumulative offset). */
private fun List<SessionClip>.toClipInputs(rate: Float): List<MemeVideoExport.ClipInput> {
    var acc = 0L
    return map { clip ->
        val input = MemeVideoExport.ClipInput(
            bytes = clip.bytes,
            probe = clip.probe,
            startMs = clip.startMs,
            endMs = clip.endMs,
            offsetMs = acc,
            volume = clip.volume,
            lookId = clip.lookId,
        )
        acc += (((clip.endMs - clip.startMs).coerceAtLeast(0L)) / rate).toLong()
        input
    }
}

/**
 * M5 cue mix: cues live on the TIMELINE clock already (added at the
 * timeline playhead), so the mix renders straight against the timeline
 * duration — no per-source mapping.
 */
private fun sfxMixTimeline(project: space.bitos.core.studio.MemeProject, timelineDurationMs: Long): ByteArray? {
    if (!space.bitos.core.studio.SfxSynth.hasAudibleCues(project.sfxCues, timelineDurationMs)) return null
    return space.bitos.core.studio.SfxSynth.pcm16Le(
        space.bitos.core.studio.SfxSynth.renderCueTrack(project.sfxCues, timelineDurationMs),
    )
}

/** SFX sheet (MST-041): 5 buckets × 31 synth sounds, tap = preview,
 *  ＋ schedules the cue at the tracked playhead; cue list removes. */
@Composable
private fun SfxSheetContent(
    cues: List<space.bitos.core.studio.MemeSfxCue>,
    positionMs: Long,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var bucketId by remember { mutableStateOf(space.bitos.core.studio.SfxSynth.BUCKETS.first().id) }
    val bucket = space.bitos.core.studio.SfxSynth.BUCKETS.first { it.id == bucketId }
    Column(Modifier.padding(BitOSSpacing.base)) {
        Text("Sound effects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W700)
        Text(
            "Tap to preview · Add cue schedules it at ${(positionMs / 1000.0).let { "%.1f".format(it) }}s (≤16, fully synthesized — zero audio assets)",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = BitOSSpacing.sm),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
            space.bitos.core.studio.SfxSynth.BUCKETS.forEach { b ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (b.id == bucketId) BitOSColors.primary else BitOSColors.surfaceElevated,
                    modifier = Modifier.clickable(onClickLabel = b.label) { bucketId = b.id },
                ) {
                    Text(
                        b.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.W600,
                        color = if (b.id == bucketId) androidx.compose.ui.graphics.Color.Black else BitOSColors.textPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(BitOSSpacing.sm))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
            rowItems(bucket.sfx) { id ->
                val recipe = space.bitos.core.studio.SfxSynth.recipeOf(id) ?: return@rowItems
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BitOSColors.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.border),
                ) {
                    Column(
                        Modifier
                            .padding(horizontal = BitOSSpacing.sm, vertical = BitOSSpacing.xs)
                            .clickable(onClickLabel = id) { SfxPreview.play(recipe) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(id, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W600)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(
                                AppIcons.Play,
                                contentDescription = null,
                                tint = BitOSColors.textSecondary,
                                modifier = Modifier.size(10.dp),
                            )
                            Text(
                                "${"%.1f".format(recipe.duration)}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = BitOSColors.textSecondary,
                            )
                        }
                        TextButton(onClick = { onAdd(id); }) {
                            Icon(
                                AppIcons.Add,
                                contentDescription = null,
                                tint = BitOSColors.primary,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Text("cue", color = BitOSColors.primary, fontWeight = FontWeight.W700)
                        }
                    }
                }
            }
        }
        if (cues.isNotEmpty()) {
            Spacer(Modifier.height(BitOSSpacing.sm))
            Text("Cues (${cues.size}/${space.bitos.core.studio.SfxSynth.MAX_CUES})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W700)
            cues.sortedBy { it.atMs }.forEach { cue ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${cue.sfx} @ ${(cue.atMs / 1000.0).let { s -> "%.1f".format(s) }}s",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            space.bitos.core.studio.SfxSynth.recipeOf(cue.sfx)?.let { SfxPreview.play(it, cue.gain) }
                        },
                    ) {
                        Icon(
                            AppIcons.Play,
                            contentDescription = "Preview cue",
                            tint = BitOSColors.primary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    IconButton(onClick = { onRemove(cue.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(AppIcons.Close, contentDescription = "Remove cue", modifier = Modifier.size(14.dp), tint = BitOSColors.textSecondary)
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// V2 suite (mockup app-15 `scr-suite`) + video-mode source insert
// ══════════════════════════════════════════════════════════════════════

/**
 * M5 clip sheet: the timeline's clip list — select, reorder (← →), remove,
 * and add another source. Windows shown in source time; output length
 * shown at the project speed.
 */
@Composable
private fun ClipSheetContent(
    clips: List<SessionClip>,
    rate: Float,
    selectedClipIndex: Int,
    onSelect: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
        Text("Clips", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.W700)
        Text(
            "${clips.size} clip(s) play back-to-back · ${suiteClock(clips.sumOf { (((it.endMs - it.startMs).coerceAtLeast(0L)) / rate).toLong() })} total",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            modifier = Modifier.padding(bottom = BitOSSpacing.xs),
        )
        clips.forEachIndexed { index, clip ->
            val selected = index == selectedClipIndex
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (selected) BitOSColors.primaryContainer else BitOSColors.surfaceElevated,
                border = if (selected) BorderStroke(1.dp, BitOSColors.primary) else null,
                onClick = { onSelect(index) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Clip ${index + 1}" },
            ) {
                Row(
                    Modifier.padding(horizontal = BitOSSpacing.sm, vertical = BitOSSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "vdo ${index + 1} · ${clip.id}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.W600,
                        )
                        Text(
                            "${suiteClock(clip.startMs)} – ${suiteClock(clip.endMs)} (source) → " +
                                suiteClock((((clip.endMs - clip.startMs).coerceAtLeast(0L)) / rate).toLong()) +
                                " at ${rate}×",
                            style = MaterialTheme.typography.labelSmall,
                            color = BitOSColors.textSecondary,
                        )
                    }
                    IconButton(
                        onClick = { onMove(index, -1) },
                        enabled = index > 0,
                        modifier = Modifier.semantics { contentDescription = "Move clip earlier" },
                    ) {
                        Icon(AppIcons.Back, contentDescription = null, tint = BitOSColors.textSecondary, modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = { onMove(index, 1) },
                        enabled = index < clips.lastIndex,
                        modifier = Modifier.semantics { contentDescription = "Move clip later" },
                    ) {
                        Icon(
                            AppIcons.Back,
                            contentDescription = null,
                            tint = BitOSColors.textSecondary,
                            modifier = Modifier
                                .size(16.dp)
                                .graphicsLayer { scaleX = -1f },
                        )
                    }
                    IconButton(
                        onClick = { onRemove(index) },
                        modifier = Modifier.semantics { contentDescription = "Remove clip" },
                    ) {
                        Icon(AppIcons.Close, contentDescription = null, tint = BitOSColors.error, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        androidx.compose.material3.OutlinedButton(onClick = onAdd, modifier = Modifier.padding(top = BitOSSpacing.xs)) {
            Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add clip…")
        }
    }
}

/**
 * M5 per-clip audio sheet: volume 0..2× (0 = mute) for the selected clip.
 * Applies live to the stage preview (the player follows the playhead).
 */
@Composable
private fun VolumeSheetContent(
    clipLabel: String,
    volume: Float,
    onApply: (Float) -> Unit,
) {
    var value by remember(volume) { mutableStateOf(volume) }
    Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
        Text("Volume", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.W700)
        Text(
            "$clipLabel · mute is exact; fractional gain previews on stage " +
                "(exact fractional export lands with the sound wave)",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            Text("Mute", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = value <= 0f, onCheckedChange = { mute -> value = if (mute) 0f else 1f })
            Spacer(Modifier.weight(1f))
            Text(
                if (value <= 0f) "muted" else "${"%.2f".format(value)}×",
                style = MaterialTheme.typography.labelMedium,
                color = BitOSColors.textSecondary,
                maxLines = 1,
            )
        }
        Slider(
            value = value.coerceIn(0f, 2f),
            onValueChange = { value = it },
            valueRange = 0f..2f,
        )
        Button(
            onClick = { onApply(value.coerceIn(0f, 2f)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
        ) {
            Text("Apply")
        }
    }
}

/**
 * Video-mode source tray: the timeline's clips (tap = select; the film tile
 * opens replace via the trim handoff) plus inserted image/GIF layers.
 */
@Composable
private fun VideoSourceTray(
    clips: List<SessionClip>,
    selectedClipIndex: Int,
    assets: List<EditorAsset>,
    onSelectClip: (Int) -> Unit,
    onAddClip: () -> Unit,
    onPickLayer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = BitOSSpacing.base),
        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        clips.forEachIndexed { index, clip ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = BitOSColors.surfaceElevated,
                border = BorderStroke(
                    1.dp,
                    if (index == selectedClipIndex) BitOSColors.primary else BitOSColors.border,
                ),
                onClick = { onSelectClip(index) },
                modifier = Modifier
                    .size(56.dp)
                    .semantics { contentDescription = "Select clip ${index + 1}" },
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    space.bitos.app.ui.theme.SolarFeedIconImage(
                        space.bitos.app.ui.theme.SolarFeedIcon.Film,
                        contentDescription = null,
                        tint = if (index == selectedClipIndex) BitOSColors.primary else BitOSColors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "vdo ${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textSecondary,
                    )
                }
            }
        }
        assets.forEach { asset ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = BitOSColors.surfaceElevated,
                border = BorderStroke(1.dp, BitOSColors.border),
                modifier = Modifier.size(56.dp),
            ) {
                coil.compose.AsyncImage(
                    model = asset.uri,
                    contentDescription = "Layer ${asset.id}",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (clips.size < MAX_TIMELINE_CLIPS) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, BitOSColors.border),
                onClick = onAddClip,
                modifier = Modifier
                    .size(56.dp)
                    .semantics { contentDescription = "Add video clip" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        AppIcons.Add,
                        contentDescription = null,
                        tint = BitOSColors.textSecondary,
                    )
                }
            }
        }
        if (assets.size < MemeProjectContract.MAX_IMAGE_LAYERS) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, BitOSColors.border),
                onClick = onPickLayer,
                modifier = Modifier
                    .size(56.dp)
                    .semantics { contentDescription = "Insert image layer" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        AppIcons.Photo,
                        contentDescription = null,
                        tint = BitOSColors.textSecondary,
                    )
                }
            }
        }
    }
}

/** One mono-labeled track row with a fixed-height lane (scr-suite dock).
 * Tappable when the lane selects something (M5 clip lanes). */
@Composable
private fun TrackLane(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlight: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = "Select $label") { onClick() }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = if (highlight) BitOSColors.primary else BitOSColors.textTertiary,
            modifier = Modifier.width(52.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(20.dp)
                .background(BitOSColors.surfaceElevated, RoundedCornerShape(6.dp))
                .border(BorderStroke(1.dp, BitOSColors.border), RoundedCornerShape(6.dp))
                .padding(2.dp),
            content = content,
        )
    }
}

/** scr-suite segment colors (honeycomb rgba tracks). */
private val SuiteOverlayColors = listOf(
    Color(0x80EC4899), // pink
    Color(0x8006B6D4), // cyan
)

/**
 * The expert dock (mockup `scr-suite`, M5 multi-track): LAYERED tracks —
 * one lane per timeline clip (`vdo 1`, `vdo 2`, … staggered by their
 * cumulative offsets), one lane per IMAGE layer (`image 1…`) positioned by
 * its visibility window, plus overlays · audio · sfx lanes — with a
 * scrubbable playhead over the whole stack, the contextual tool chips and
 * the undo/redo · Preview · Export action rows. Real data only.
 */
@Composable
private fun SuiteDock(
    project: MemeProject,
    clips: List<SessionClip>,
    rate: Float,
    selectedClipIndex: Int,
    onSelectClip: (Int) -> Unit,
    positionMs: Long,
    transport: VideoTransport,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenLayers: () -> Unit,
    looksEnabled: Boolean,
    onOpenLooks: () -> Unit,
    onOpenSfx: () -> Unit,
    onOpenDraw: () -> Unit,
    onOpenTrim: () -> Unit,
    onOpenClip: () -> Unit,
    onOpenVolume: () -> Unit,
    onSplit: () -> Unit,
    onOpenSpeed: () -> Unit,
    onExport: () -> Unit,
    exporting: Boolean,
    onClose: () -> Unit,
) {
    fun outMs(clip: SessionClip): Long =
        (((clip.endMs - clip.startMs).coerceAtLeast(0L)) / rate).toLong()

    var acc = 0L
    val clipRows = clips.map { clip ->
        val at = acc
        acc += outMs(clip)
        clip to at
    }
    val durationMs = acc.coerceAtLeast(1L)
    val imageLayers = project.overlays.filter { it.kind == MemeOverlayKind.IMAGE }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(transport) {
        while (true) {
            playing = transport.isPlaying()
            kotlinx.coroutines.delay(200)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = BitOSSpacing.base, vertical = BitOSSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── Layered tracks + playhead (tap anywhere to seek) ────────────
        androidx.compose.foundation.layout.BoxWithConstraints {
            // THE ruler is the lane box, which starts after the 52dp label
            // column (+ its spacer). Measuring fractions against the full
            // dock width made segments/playhead/ticks drift off the lanes'
            // true zero point — the timeline now starts where the lanes do.
            val laneStart = 52.dp + BitOSSpacing.sm
            val trackWidth = maxWidth - laneStart
            val density = LocalDensity.current
            val laneStartPx = with(density) { laneStart.toPx() }
            val trackWidthPx = with(density) { trackWidth.toPx() }
            val laneCount = clipRows.size + imageLayers.size + 3
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // Video clips — each on its own lane, staggered by its
                // timeline offset (the mockup scr-suite "tracks" stack).
                clipRows.forEachIndexed { index, (clip, offsetMs) ->
                    val left = (offsetMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    val width = (outMs(clip).toFloat() / durationMs).coerceIn(0f, 1f - left)
                    val selected = index == selectedClipIndex
                    TrackLane(
                        "vdo ${index + 1}",
                        onClick = { onSelectClip(index) },
                        highlight = selected,
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .padding(start = trackWidth * left)
                                .fillMaxWidth(width)
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        listOf(Color(0xFF3A1505), Color(0xFFC2570F)),
                                    ),
                                    RoundedCornerShape(4.dp),
                                )
                                .border(
                                    BorderStroke(
                                        if (selected) 1.5.dp else 0.dp,
                                        if (selected) BitOSColors.primary else Color.Transparent,
                                    ),
                                    RoundedCornerShape(4.dp),
                                ),
                        )
                    }
                }
                // IMAGE layers — one lane each, segment = visibility window.
                imageLayers.forEachIndexed { index, layer ->
                    val start = layer.startMs ?: 0L
                    val end = layer.endMs?.takeIf { it > 0 } ?: durationMs
                    val left = (start.toFloat() / durationMs).coerceIn(0f, 1f)
                    val width = ((end - start).toFloat() / durationMs).coerceIn(0f, 1f - left)
                    TrackLane("image ${index + 1}") {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .padding(start = trackWidth * left)
                                .fillMaxWidth(width)
                                .background(Color(0x808B5CF6), RoundedCornerShape(4.dp)), // violet
                        )
                    }
                }
                TrackLane("overlays") {
                    project.overlays.forEachIndexed { index, overlay ->
                        val start = overlay.startMs ?: 0L
                        val end = overlay.endMs?.takeIf { it > 0 } ?: durationMs
                        val left = (start.toFloat() / durationMs).coerceIn(0f, 1f)
                        val width = ((end - start).toFloat() / durationMs).coerceIn(0f, 1f - left)
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .padding(start = trackWidth * left)
                                .fillMaxWidth(width)
                                .background(
                                    SuiteOverlayColors[index % SuiteOverlayColors.size],
                                    RoundedCornerShape(4.dp),
                                ),
                        )
                    }
                }
                TrackLane("audio") {
                    // Per-clip audio segments — muted clips show an empty
                    // gap (their track is removed in the export too).
                    clipRows.forEachIndexed { index, (clip, offsetMs) ->
                        val left = (offsetMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        val width = (outMs(clip).toFloat() / durationMs).coerceIn(0f, 1f - left)
                        if (clip.volume > 0f) {
                            Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxHeight()
                                    .padding(start = trackWidth * left)
                                    .fillMaxWidth(width)
                                    .background(Color(0x6650E3A0), RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                }
                TrackLane("sfx") {
                    project.sfxCues.forEach { cue ->
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = trackWidth * (cue.atMs.toFloat() / durationMs))
                                .size(width = 3.dp, height = 14.dp)
                                .background(Color(0xFFFFB000), RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
            // Playhead line over the whole lane stack — positioned from the
            // LANE's zero point (after the label column), like the segments.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = laneStart + trackWidth * (positionMs.toFloat() / durationMs))
                    .width(2.dp)
                    .height(((laneCount * 20) + ((laneCount - 1) * 3)).dp)
                    .background(BitOSColors.primary, RoundedCornerShape(1.dp)),
            )
            // Tap-to-seek anywhere on the lanes (same ruler: x is measured
            // from the lane start, not the dock edge).
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(durationMs, trackWidthPx, laneStartPx) {
                        detectTapGestures { offset ->
                            val fraction = ((offset.x - laneStartPx) / trackWidthPx).coerceIn(0f, 1f)
                            transport.seekTo((fraction * durationMs).toLong())
                        }
                    },
            )
        }
        // ── Time readout (mono, like the mockup footer) — 00:00 aligns
        // with the lanes' zero point, 00:end with the lane's right edge.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 52.dp + BitOSSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(suiteClock(positionMs), style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = BitOSColors.textTertiary)
            Text(
                if (project.sfxCues.isEmpty()) {
                    "cue 0/${space.bitos.core.studio.SfxSynth.MAX_CUES}"
                } else {
                    val last = project.sfxCues.maxBy { it.atMs }
                    "cue ${project.sfxCues.size}/${space.bitos.core.studio.SfxSynth.MAX_CUES} · ${last.sfx}"
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = BitOSColors.textTertiary,
            )
            Text(suiteClock(durationMs), style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = BitOSColors.textTertiary)
        }
        // ── Contextual tool chips (mockup: Draw/Layers/Looks/Trim/SFX/…) ─
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
        ) {
            SuiteToolChip(label = "Clips", icon = AppIcons.AppsGrid, enabled = true, onClick = onOpenClip)
            SuiteToolChip(label = "Draw", icon = AppIcons.Pen, enabled = true, onClick = onOpenDraw)
            SuiteToolChip(label = "Layers", icon = AppIcons.AppsGrid, enabled = true, onClick = onOpenLayers)
            SuiteToolChip(label = "Looks", solarIcon = space.bitos.app.ui.theme.SolarStudioIcon.Palette, enabled = looksEnabled, onClick = onOpenLooks)
            SuiteToolChip(label = "Trim", icon = AppIcons.Scissors, enabled = true, onClick = onOpenTrim)
            SuiteToolChip(label = "Split", icon = AppIcons.Scissors, enabled = true, onClick = onSplit)
            SuiteToolChip(label = "SFX ≤${space.bitos.core.studio.SfxSynth.MAX_CUES}", solarIcon = space.bitos.app.ui.theme.SolarStudioIcon.Soundwave, enabled = true, onClick = onOpenSfx)
            SuiteToolChip(label = "Volume", icon = AppIcons.MusicNote, enabled = true, onClick = onOpenVolume)
            SuiteToolChip(label = "Speed", icon = AppIcons.Speed, enabled = true, onClick = onOpenSpeed)
        }
        // ── Action rows: undo/redo + autosave on one line, the primary
        // Preview/Export actions on their own — a single squeezed row made
        // "autosave on" stack one letter per line on narrow screens. ──────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
        ) {
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.semantics { contentDescription = "Undo" }) {
                space.bitos.app.ui.theme.SolarStudioIconImage(
                    space.bitos.app.ui.theme.SolarStudioIcon.UndoLeft,
                    contentDescription = null,
                    tint = if (canUndo) BitOSColors.textPrimary else BitOSColors.textTertiary,
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.semantics { contentDescription = "Redo" }) {
                Icon(AppIcons.Redo, contentDescription = null, tint = if (canRedo) BitOSColors.textPrimary else BitOSColors.textTertiary)
            }
            Text(
                "autosave on",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = BitOSColors.textTertiary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close suite" }) {
                Icon(AppIcons.Close, contentDescription = null, tint = BitOSColors.textSecondary)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            androidx.compose.material3.OutlinedButton(
                onClick = { transport.playPause() },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = if (playing) "Pause preview" else "Play preview" },
            ) {
                Icon(if (playing) AppIcons.Pause else AppIcons.Play, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Preview", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            androidx.compose.material3.Button(
                onClick = onExport,
                enabled = !exporting,
                modifier = Modifier.weight(1f),
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                        color = Color.Black,
                    )
                } else {
                    Text("Export", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Clip-import progress (M5 UX): spinner + "clip X of Y" + a determinate
 * bar — probing/staging sources is real work the user should see.
 */
@Composable
private fun ClipImportOverlay(done: Int, total: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.base),
            modifier = Modifier.padding(horizontal = BitOSSpacing.screen),
        ) {
            CircularProgressIndicator(
                strokeWidth = 4.dp,
                modifier = Modifier.size(52.dp),
                color = BitOSColors.primary,
            )
            Text(
                "Preparing clips…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W600,
                color = Color.White,
            )
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = BitOSColors.primary,
                trackColor = Color(0x33FFFFFF),
            )
            Text(
                "$done of $total",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xB3FFFFFF),
            )
        }
    }
}

/**
 * Full-screen export experience: rendering progress with an honest status,
 * then the result (saved ✓ / failure detail) with a single Done action.
 * Rendering continues behind this screen; Done only dismisses.
 */
@Composable
private fun ExportFullScreen(
    exporting: Boolean,
    status: String?,
    onDone: () -> Unit,
) {
    val succeeded = status?.startsWith("Saved") == true
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        IconButton(
            onClick = onDone,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(BitOSSpacing.xs)
                .semantics { contentDescription = "Close export screen" },
        ) {
            Icon(AppIcons.Close, contentDescription = null, tint = BitOSColors.textPrimary)
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.base),
        ) {
            if (exporting) {
                CircularProgressIndicator(
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(56.dp),
                    color = BitOSColors.primary,
                )
                Text(
                    "Rendering your meme…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W600,
                )
                Text(
                    "This can take a moment for long clips — keep the screen open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = if (succeeded) BitOSColors.success.copy(alpha = 0.15f) else BitOSColors.error.copy(alpha = 0.15f),
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        if (succeeded) AppIcons.CheckCircle else AppIcons.Close,
                        contentDescription = null,
                        tint = if (succeeded) BitOSColors.success else BitOSColors.error,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                Text(
                    if (succeeded) "Saved!" else "Export finished",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W600,
                )
                Text(
                    status ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = BitOSSpacing.sm),
                ) {
                    Text("Done")
                }
            }
        }
    }
}

/** scr-suite mm:ss clock. */
private fun suiteClock(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** One contextual tool chip; disabled chips carry the honest "Soon" tag. */
@Composable
private fun SuiteToolChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    solarIcon: space.bitos.app.ui.theme.SolarStudioIcon? = null,
    enabled: Boolean,
    onClick: () -> Unit = {},
) {
    val tint = if (enabled) BitOSColors.textPrimary else BitOSColors.textTertiary
    Surface(
        shape = RoundedCornerShape(50),
        color = if (enabled) BitOSColors.surfaceElevated else BitOSColors.surface,
        border = BorderStroke(1.dp, if (enabled) BitOSColors.border else BitOSColors.border.copy(alpha = 0.5f)),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "$label${if (enabled) "" else " (soon)"}" },
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
            } else if (solarIcon != null) {
                space.bitos.app.ui.theme.SolarStudioIconImage(
                    solarIcon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                if (enabled) label else "$label · soon",
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
    }
}

/**
 * Layers sheet (source insert management): every IMAGE overlay with its
 * thumbnail, select and delete; "Insert image…" opens the picker. GIF
 * inserts paint their first frame (V1 semantics, said out loud).
 */
@Composable
private fun LayersSheetContent(
    project: MemeProject,
    assets: List<EditorAsset>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onInsert: () -> Unit,
) {
    val layers = project.overlays.filter { it.kind == MemeOverlayKind.IMAGE }
    Column(Modifier.padding(BitOSSpacing.base)) {
        Text("Layers", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.W700)
        Text(
            "Insert image or GIF sources over the clip (≤${MemeProjectContract.MAX_IMAGE_LAYERS}). GIFs paint their first frame.",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = BitOSSpacing.sm),
        )
        if (layers.isEmpty()) {
            Text(
                "No layers yet — insert a source to stack it over the clip.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
                modifier = Modifier.padding(bottom = BitOSSpacing.sm),
            )
        }
        layers.forEach { layer ->
            val asset = layer.assetId?.let { id -> assets.firstOrNull { it.id == id } }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = BitOSColors.surfaceElevated,
                    border = if (layer.id == selectedId) BorderStroke(1.dp, BitOSColors.primary) else null,
                    modifier = Modifier.size(44.dp),
                ) {
                    coil.compose.AsyncImage(
                        model = asset?.uri,
                        contentDescription = "Layer ${layer.id}",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Layer ${layer.assetId ?: "?"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.W600,
                    )
                    Text(
                        if (layer.startMs != null || layer.endMs != null) {
                            "${suiteClock(layer.startMs ?: 0)} – ${suiteClock(layer.endMs ?: 0)}"
                        } else {
                            "always visible"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textSecondary,
                    )
                }
                androidx.compose.material3.TextButton(onClick = { onSelect(layer.id) }) {
                    Text("Select", color = BitOSColors.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.W600)
                }
                IconButton(
                    onClick = { onDelete(layer.id) },
                    modifier = Modifier.semantics { contentDescription = "Delete layer" },
                ) {
                    Icon(AppIcons.Close, contentDescription = null, tint = BitOSColors.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
        androidx.compose.material3.OutlinedButton(onClick = onInsert, modifier = Modifier.padding(top = BitOSSpacing.xs)) {
            Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Insert image…")
        }
    }
}

/**
 * Trim sheet (V2 suite): the export window over the source clip, with the
 * effective output duration (window ÷ speed) said out loud. Applies the
 * same undoable SetTrim command the pick flow uses.
 */
@Composable
private fun TrimSheetContent(
    startMs: Long,
    endMs: Long,
    durationMs: Long,
    speed: Float,
    onApply: (startMs: Long, endMs: Long) -> Unit,
) {
    var start by remember(startMs) { mutableStateOf(startMs.toFloat()) }
    var end by remember(endMs) { mutableStateOf(endMs.toFloat()) }
    val rate = space.bitos.core.studio.MemeProjectContract.clampSpeed(speed)
    val effectiveMs = ((end - start).coerceAtLeast(0f) / rate).toLong()
    Column(Modifier.padding(BitOSSpacing.base)) {
        Text("Trim", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.W700)
        Text(
            "The export window over the source clip. ${suiteClock(start.toLong())} – ${suiteClock(end.toLong())} → ${suiteClock(effectiveMs)} at ${rate}×",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = BitOSSpacing.sm),
        )
        Text("Start ${suiteClock(start.toLong())}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = start,
            onValueChange = {
                start = it.coerceIn(0f, end - 200f)
            },
            valueRange = 0f..durationMs.toFloat(),
        )
        Text("End ${suiteClock(end.toLong())}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = end,
            onValueChange = {
                end = it.coerceIn(start + 200f, durationMs.toFloat())
            },
            valueRange = 0f..durationMs.toFloat(),
        )
        androidx.compose.material3.Button(
            onClick = { onApply(start.toLong(), end.toLong()) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BitOSSpacing.sm),
        ) {
            Text("Apply trim")
        }
    }
}

/**
 * Speed sheet (V2 suite): whole-clip playback rate (web speed-track
 * bounds 0.5–2×). Pitch shifts with the clip in V1 — said out loud.
 */
@Composable
private fun SpeedSheetContent(
    speed: Float,
    mediaDurationMs: Long,
    onPick: (Float) -> Unit,
) {
    val rate = space.bitos.core.studio.MemeProjectContract.clampSpeed(speed)
    Column(Modifier.padding(BitOSSpacing.base)) {
        Text("Speed", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.W700)
        Text(
            "Whole-clip rate (0.5–2×). Audio pitch follows the clip in V1.",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = BitOSSpacing.sm),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { option ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (option == rate) BitOSColors.primary else BitOSColors.surfaceElevated,
                    border = BorderStroke(1.dp, if (option == rate) BitOSColors.primary else BitOSColors.border),
                    onClick = { onPick(option) },
                    modifier = Modifier.semantics { contentDescription = "${option}× speed" },
                ) {
                    Text(
                        "${option}×",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
                        color = if (option == rate) androidx.compose.ui.graphics.Color.Black else BitOSColors.textPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (mediaDurationMs > 0) {
            Text(
                "Output length ${suiteClock((mediaDurationMs / rate).toLong())} (from ${suiteClock(mediaDurationMs)})",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textSecondary,
                modifier = Modifier.padding(top = BitOSSpacing.sm),
            )
        }
    }
}

/**
 * Draw mode (V2 Draw chip): pen capture + ink rendering over the media
 * rect. The layer centers on the stage's measured media box so strokes
 * normalize exactly like the export canvas (WYSIWYG); it owns touches
 * while active, ending one stroke per drag (commit → undoable command).
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.DrawCaptureLayer(
    project: MemeProject,
    stageWidthPx: Int,
    stageHeightPx: Int,
    livePoints: List<Float>,
    colorIndex: Int,
    widthNorm: Float,
    onStroke: (List<Float>) -> Unit,
) {
    if (stageWidthPx <= 0 || stageHeightPx <= 0) return
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.matchParentSize(),
    ) {
        val density = LocalDensity.current
        with(density) {
            val stageW = stageWidthPx.toDp()
            val stageH = stageHeightPx.toDp()
            val offsetX = ((maxWidth - stageW) / 2).coerceAtLeast(0.dp)
            val offsetY = ((maxHeight - stageH) / 2).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier
                    .offset(offsetX, offsetY)
                    .size(stageW, stageH),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    MemeRaster.drawStrokes(
                        drawContext.canvas.nativeCanvas,
                        MemeExportRules.drawingPlan(project, stageWidthPx, stageHeightPx),
                    )
                    if (livePoints.size >= 4) {
                        MemeRaster.drawStrokes(
                            drawContext.canvas.nativeCanvas,
                            listOf(
                                MemeExportRules.MemeStrokePaint(
                                    color = MemeRules.PALETTE[colorIndex.coerceIn(0, MemeRules.PALETTE.lastIndex)],
                                    widthPx = widthNorm * stageHeightPx,
                                    pointsPx = livePoints.mapIndexed { index, point ->
                                        point * if (index % 2 == 0) stageWidthPx else stageHeightPx
                                    },
                                ),
                            ),
                        )
                    }
                }
                // Capture: one normalized polyline per drag.
                val points = remember { mutableStateListOf<Float>() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(stageWidthPx, stageHeightPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                points.clear()
                                points.add(down.position.x / size.width)
                                points.add(down.position.y / size.height)
                                var dragging = true
                                while (dragging) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (change.pressed) {
                                        points.add(change.position.x / size.width)
                                        points.add(change.position.y / size.height)
                                        if (points.size > MemeProjectContract.MAX_POINTS_PER_STROKE * 2) break
                                        change.consume()
                                    } else {
                                        dragging = false
                                    }
                                }
                                if (points.size >= 4) onStroke(points.toList())
                                points.clear()
                            }
                        },
                )
            }
        }
    }
}

/**
 * Pen controls (shown while draw mode is active): palette dots, three
 * widths, undo-stroke, clear, done. Ink is budgeted by the shared rule.
 */
@Composable
private fun PenControlsRow(
    colorIndex: Int,
    onPickColor: (Int) -> Unit,
    widthNorm: Float,
    onPickWidth: (Float) -> Unit,
    canUndoStroke: Boolean,
    onUndoStroke: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = BitOSSpacing.base, vertical = BitOSSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        // Palette subset: white/black/orange/red/green/blue/purple/yellow.
        listOf(0, 1, 2, 4, 6, 8, 10, 12).forEach { index ->
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 2.dp)
                    .size(if (index == colorIndex) 24.dp else 20.dp)
                    .background(
                        Color(MemeRules.PALETTE[index]),
                        androidx.compose.foundation.shape.CircleShape,
                    )
                    .border(
                        BorderStroke(
                            if (index == colorIndex) 2.dp else 1.dp,
                            if (index == colorIndex) BitOSColors.primary else BitOSColors.border,
                        ),
                        androidx.compose.foundation.shape.CircleShape,
                    )
                    .clickable(onClickLabel = "Pen color") { onPickColor(index) },
            )
        }
        Spacer(Modifier.width(BitOSSpacing.xs))
        listOf(
            0.004f to "Thin",
            space.bitos.core.studio.MemeProjectContract.DEFAULT_STROKE_WIDTH to "Medium",
            0.016f to "Thick",
        ).forEach { (width, label) ->
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 2.dp)
                    .size(if (width == widthNorm) 24.dp else 20.dp)
                    .border(
                        BorderStroke(
                            if (width == widthNorm) 2.dp else 1.dp,
                            if (width == widthNorm) BitOSColors.primary else BitOSColors.border,
                        ),
                        androidx.compose.foundation.shape.CircleShape,
                    )
                    .clickable(onClickLabel = label) { onPickWidth(width) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(BitOSColors.textPrimary, androidx.compose.foundation.shape.CircleShape),
                )
            }
        }
        IconButton(onClick = onUndoStroke, enabled = canUndoStroke, modifier = Modifier.semantics { contentDescription = "Undo stroke" }) {
            SolarStudioIconImage(SolarStudioIcon.UndoLeft, contentDescription = null, tint = BitOSColors.textPrimary)
        }
        IconButton(onClick = onClear, modifier = Modifier.semantics { contentDescription = "Clear drawing" }) {
            Icon(AppIcons.Delete, contentDescription = null, tint = BitOSColors.textSecondary)
        }
        androidx.compose.material3.TextButton(onClick = onDone) {
            Text("Done", color = BitOSColors.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.W700)
        }
    }
}
