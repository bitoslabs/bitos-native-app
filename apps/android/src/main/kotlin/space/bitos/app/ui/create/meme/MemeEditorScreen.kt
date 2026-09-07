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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import space.bitos.app.ui.components.BitosAdjustmentSlider
import space.bitos.app.ui.components.BitosSlider
import space.bitos.core.studio.MemeFontSlot
import space.bitos.core.studio.MemeMode
import space.bitos.core.studio.MemeExportRules
import space.bitos.core.studio.MemeOverlay
import space.bitos.core.studio.MemeOverlayKind
import space.bitos.core.studio.MemeProject
import space.bitos.core.studio.MemeProjectContract
import space.bitos.core.studio.MemeRules
import space.bitos.core.studio.StickerCatalog

/** A session asset: project id + platform image ref + decoded bounds. */
private data class EditorAsset(
    val id: String,
    val uri: Uri,
    /** width / height of the decoded image. */
    val aspect: Float,
    /** Decoded pixel bounds (0 = unknown; the meta chip falls back). */
    val width: Int = 0,
    val height: Int = 0,
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
    /** Per-clip playback rate. Split clips inherit this setting. */
    val speed: Float = 1f,
)

/** Timeline clip cap (M5 plan §F1). */
private const val MAX_TIMELINE_CLIPS = 8

/**
 * M4b remix handoff (web `RemixHandoff` parity): everything the editor
 * needs to open a bitz as an editable remix — the source media to fetch,
 * the `meme` layout payload to clone, and the lineage facts that ride the
 * publish (`remix`/`p` tags, relay hints, attribution label).
 */
data class MemeRemixSeed(
    val eventId: String,
    val pubkey: String,
    /** Author display name for the `attribution` credit ("remix of …"). */
    val label: String,
    /** Relay hints for the remix tag (source-tag relays + write relays, ≤3). */
    val relays: List<String>,
    val mediaUrl: String,
    val isVideo: Boolean,
    val memeTag: String?,
)

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
    /** M4b remix handoff: source bitz media + layout + lineage. */
    remixSeed: MemeRemixSeed? = null,
    onSlotsChanged: () -> Unit = {},
    /** MUX-06: hand the frozen design + rendered poster to mass production. */
    onMakeVariations: ((String, ByteArray) -> Unit)? = null,
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
    /** Legacy project speed remains the fallback for drafts created before clip rates. */
    val videoRate: Float = space.bitos.core.studio.MemeProjectContract.clampSpeed(state.project.speed)
    fun clipRate(clip: SessionClip): Float =
        space.bitos.core.studio.MemeProjectContract.clampSpeed(clip.speed)

    fun clipOutputMs(clip: SessionClip): Long =
        (((clip.endMs - clip.startMs).coerceAtLeast(0L)) / clipRate(clip)).toLong()

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
                return clip to (clip.startMs + ((timelineMs - start) * clipRate(clip)).toLong())
            }
        }
        return null
    }

    /** Mirrors the session clip list into the project wire (M5 persistence). */
    fun syncWireClips() {
        state.syncClips(
            videoClips.map {
                space.bitos.core.studio.MemeClip(
                    id = it.id, startMs = it.startMs, endMs = it.endMs,
                    volume = it.volume, lookId = it.lookId, speed = it.speed,
                )
            },
        )
    }

    /** Undoable-clips support: id → (bytes, probe) so a restored wire clip
     *  list can rebuild the session after undo/redo (bounded; ≤8 ids are
     *  live per session, removed halves linger for their redo step). */
    val clipArchive = remember { mutableMapOf<String, Pair<ByteArray, MemeVideoExport.Probe>>() }
    fun archiveClip(clip: SessionClip) {
        clipArchive[clip.id] = clip.bytes to clip.probe
        while (clipArchive.size > 32) clipArchive.remove(clipArchive.keys.first())
    }

    /**
     * Fresh unique clip ids survive splits/removals (never reuse a wire id).
     * Declared before its callers among the local helpers below.
     */
    fun maxClipCounter(): Int = videoClips.maxOfOrNull {
        it.id.removePrefix("v").takeWhile(Char::isDigit).toIntOrNull() ?: 0
    } ?: 0

    /** Appends a probed source as a new timeline clip (cut rules applied). */
    fun appendClip(bytes: ByteArray, probe: MemeVideoExport.Probe, undoable: Boolean = true) {
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
        if (undoable) state.beginClipsEdit()
        state.addAssets(listOf(id), kind = MemeMode.VIDEO)
        val clip = SessionClip(id, bytes, probe, cut.startMs, cut.endMs, speed = videoRate)
        videoClips += clip
        archiveClip(clip)
        selectedClipIndex = videoClips.lastIndex
        syncWireClips()
    }

    fun removeClip(index: Int) {
        if (index !in videoClips.indices) return
        state.beginClipsEdit()
        videoClips.removeAt(index)
        selectedClipIndex = selectedClipIndex.coerceIn(0, (videoClips.size - 1).coerceAtLeast(0))
        syncWireClips()
    }

    fun moveClip(index: Int, delta: Int) {
        val target = index + delta
        if (index !in videoClips.indices || target !in videoClips.indices) return
        state.beginClipsEdit()
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
            val intoMediaMs = ((timelineMs - offset) * clipRate(clip)).toLong()
            val splitAt = clip.startMs + intoMediaMs
            if (splitAt - clip.startMs < 200 || clip.endMs - splitAt < 200) {
                exportStatus = "Too close to a clip edge to split"
                return
            }
            val second = clip.copy(
                id = "v${maxClipCounter() + 1}",
                startMs = splitAt,
            )
            state.beginClipsEdit()
            videoClips[index] = clip.copy(endMs = splitAt)
            videoClips.add(index + 1, second)
            archiveClip(second)
            selectedClipIndex = index
            syncWireClips()
            return
        }
    }

    var videoPickPending by remember { mutableStateOf(false) }
    var videoTrimBytes by remember { mutableStateOf<ByteArray?>(null) }
    /** MST-032: separately-uploaded cover URL (session-only in V1). */
    var coverThumbUrl by remember { mutableStateOf<String?>(null) }
    /** Immediate local preview while the separately uploaded public cover is in flight. */
    var coverPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var coverUploading by remember { mutableStateOf(false) }
    val videoMode = state.project.mode == MemeMode.VIDEO

    /** Rebuilds the session clip list from the wire after undo/redo. Full
     *  rebuild only — a partial match keeps the session list (sticky
     *  fallback) rather than dropping clips silently. */
    fun reconcileClipsFromWire() {
        if (!videoMode) return
        val wire = state.project.clips
        val rebuilt = wire.mapNotNull { row ->
            clipArchive[row.id]?.let { (bytes, probe) ->
                val start = row.startMs.coerceIn(0, probe.durationMs)
                val end = row.endMs.coerceAtMost(probe.durationMs)
                if (end > start) SessionClip(
                    row.id, bytes, probe, start, end, row.volume, row.lookId, row.speed,
                ) else null
            }
        }
        if (rebuilt.size == wire.size) {
            videoClips.clear()
            videoClips += rebuilt
            rebuilt.forEach(::archiveClip)
            selectedClipIndex = selectedClipIndex.coerceIn(0, (videoClips.size - 1).coerceAtLeast(0))
        }
    }

    /** ONE undo affordance: steps back the newest action of either kind
     *  (command or clip-list edit), then re-hydrates the session clips. */
    fun undoEdit(): Boolean {
        val undone = state.undo()
        if (undone) reconcileClipsFromWire()
        return undone
    }

    fun redoEdit(): Boolean {
        val redone = state.redo()
        if (redone) reconcileClipsFromWire()
        return redone
    }

    var gifPreviewIndex by remember { mutableIntStateOf(0) }
    var gifUniformDelayMs by remember { mutableStateOf(0) } // 0 = keep source delays
    var confirmModeSwitch by remember { mutableStateOf(false) }
    var pendingModeSwitch by remember { mutableStateOf(MemeMode.IMAGE) }

    // Resume integrity: wire clips that rehydration could NOT bring back
    // (missing file / unreadable bytes / probe mismatch). While > 0 the
    // session is an incomplete view of the slot — autosave stands down so
    // the last good copy survives (a partial save would erase the dropped
    // clips' asset rows and the timeline would be unrecoverable).
    var resumeDroppedClips by remember { mutableIntStateOf(0) }

    val assets = remember(resume) {
        mutableStateListOf<EditorAsset>().apply {
            resume?.let { saved ->
                if (saved.document.project.mode == MemeMode.VIDEO) {
                    // M5 resume: the wire's clip list + slot asset files
                    // rebuild the full timeline (windows from the wire; a
                    // v1 slot migrates into a single clip server-side).
                    //
                    // Re-run guard: this block re-executes whenever the
                    // `resume` identity changes (a fresh SavedSlot breaks
                    // SavedSlot equality on its updatedAtMs), while the
                    // unkeyed `videoClips` remember still holds the first
                    // restore — appending again doubled the timeline on
                    // screen ("auto split on resume") and the next
                    // syncWireClips persisted the duplicate. Seed once;
                    // the IMAGE-layer tray below still re-seeds because
                    // the `assets` list itself is rebuilt by this block.
                    if (videoClips.isEmpty()) {
                        var dropped = 0
                        saved.document.project.clips.forEach { wireClip ->
                            val file = saved.assetFiles[wireClip.id]
                            if (file == null) { dropped++; return@forEach }
                            val bytes = runCatching { file.readBytes() }.getOrNull()
                            if (bytes == null) { dropped++; return@forEach }
                            val probe = MemeVideoExport.probe(
                                context.contentResolver,
                                android.net.Uri.fromFile(file),
                            )
                            if (probe == null) { dropped++; return@forEach }
                            val start = wireClip.startMs.coerceIn(0, probe.durationMs)
                            val end = wireClip.endMs.coerceAtMost(probe.durationMs)
                            // A probe that disagrees with the wire enough to
                            // collapse the window would render a zero-length
                            // segment — count it dropped, don't add it.
                            if (end <= start) { dropped++; return@forEach }
                            val restored = SessionClip(
                                wireClip.id, bytes, probe,
                                start,
                                end,
                                wireClip.volume,
                                wireClip.lookId,
                                wireClip.speed,
                            )
                            videoClips += restored
                            archiveClip(restored)
                        }
                        if (dropped > 0) {
                            resumeDroppedClips = dropped
                            exportStatus = "$dropped timeline clip(s) could not be restored — " +
                                "the last saved draft is kept; reopen it to retry"
                        }
                        selectedClipIndex = 0
                    }
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
                    // not part of the slot wire in V1). Same re-run guard as
                    // the video branch: the unkeyed `gifFrames` remember must
                    // not take a second copy when the block re-executes.
                    if (gifFrames.isEmpty()) {
                        saved.document.assets.sortedBy { it.id }.forEach { asset ->
                            saved.assetFiles[asset.id]?.let { file ->
                                android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                                    gifFrames += bitmap
                                    gifDelays += GifFrameSource.STILL_DELAY_MS
                                }
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
                    // Seeding IS the session start, not a user edit step.
                    appendClip(seed, probe, undoable = false)
                } else {
                    failed = true
                }
            }
            seedingProgress = null
            if (failed) exportStatus = "A source clip could not be read"
        }
    }
    var activeAssetId by remember { mutableStateOf(resume?.document?.assets?.firstOrNull()?.id) }

    // M4b remix seeding (web `consumeRemixHandoff` parity): fetch the
    // source bitz media, import it as this session's media (video clip or
    // image background), then clone the source `meme` layout with fresh
    // ids. Runs once against an empty session; the lineage rides the
    // publish sheet (remixSeed → onPublish).
    LaunchedEffect(remixSeed) {
        val seed = remixSeed ?: return@LaunchedEffect
        if (state.project.assets.isNotEmpty() || videoClips.isNotEmpty() ||
            assets.isNotEmpty() || gifFrames.isNotEmpty()
        ) {
            return@LaunchedEffect
        }
        val url = runCatching { java.net.URI(seed.mediaUrl).toURL() }.getOrNull()
        if (url == null || (url.protocol != "https" && url.host != "localhost" && url.host != "127.0.0.1")) {
            exportStatus = "Remix source URL is not loadable"
            return@LaunchedEffect
        }
        seedingProgress = 0 to 1
        val bytes = withContext(Dispatchers.IO) {
            val connection = url.openConnection() as java.net.HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        check(out.size() <= space.bitos.core.model.Blossom.MAX_FILE_BYTES) { "source too large" }
                    }
                    out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        try {
            if (seed.isVideo) {
                val probe = withContext(Dispatchers.IO) {
                    val temp = java.io.File.createTempFile("meme-remix", ".mp4")
                    try {
                        temp.writeBytes(bytes)
                        MemeVideoExport.probe(context.contentResolver, android.net.Uri.fromFile(temp))
                    } finally {
                        runCatching { temp.delete() }
                    }
                }
                checkNotNull(probe) { "clip unreadable" }
                state.switchMode(MemeMode.VIDEO)
                appendClip(bytes, probe, undoable = false)
            } else {
                val bounds = withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                        it.width to it.height
                    }
                }
                checkNotNull(bounds) { "image unreadable" }
                val temp = java.io.File.createTempFile("meme-remix", ".img")
                withContext(Dispatchers.IO) { temp.writeBytes(bytes) }
                val uri = android.net.Uri.fromFile(temp)
                val id = "a1"
                if (id in state.addAssets(listOf(id))) {
                    assets += EditorAsset(
                        id, uri,
                        bounds.first.toFloat() / bounds.second,
                        bounds.first, bounds.second,
                    )
                    activeAssetId = id
                }
            }
            // Clone the source layout (fresh ids); missing/junk payloads
            // leave the project untouched.
            val seeded = space.bitos.core.bridge.BusinessCoreBridge()
                .memeApplyRemix(MemeProjectContract.encode(state.project), seed.memeTag)
            if (seeded.isNotEmpty()) {
                MemeProjectContract.decode(seeded)?.let { state.restore(it) }
            }
        } catch (error: IllegalStateException) {
            exportStatus = "Remix source could not be loaded (${error.message})"
        } finally {
            seedingProgress = null
        }
    }
    // Compose observation: one read subscribes the whole editor scope.
    val dataRevision = state.revision
    var stagePx by remember { mutableStateOf(IntSize.Zero) }
    var showDiscard by remember { mutableStateOf(false) }
    var editingOverlayId by remember { mutableStateOf<String?>(null) }
    var showLooks by remember { mutableStateOf(false) }
    /** Classic meme generator (prototype "Meme" hot tool). */
    var showSfx by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showTrim by remember { mutableStateOf(false) }
    /** M5 per-clip management sheet (trim · reorder · remove · duplicate). */
    var showClipSheet by remember { mutableStateOf(false) }
    /** M5 per-clip audio (volume · mute) — replaces the old "Sound · soon". */
    var showVolume by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showCanvas by remember { mutableStateOf(false) }
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
    /** Inline tool panel (prototype tool panels open under the chips). */
    var activePanel by remember { mutableStateOf<MemeEditorPanel?>(null) }

    /** MUX-01: draft persistence is acknowledged, never assumed. */
    var draftSaveState by remember { mutableStateOf<DraftSaveState>(DraftSaveState.IDLE) }
    /** MUX-02: typed export outcome — display copy can't change state. */
    var exportOutcome by remember { mutableStateOf<ExportOutcome?>(null) }
    /** Exact bytes from the rendered artifact; never inferred from source media. */
    var lastExportSizeBytes by remember { mutableStateOf<Int?>(null) }
    /** MUX-04: output settings before export fires. */
    var showExportSheet by remember { mutableStateOf(false) }
    /** MUX-05: durable export jobs — retry reuses the persisted artifact. */
    val exportJobs = remember { MemeExportJobStore(context) }
    var exportJobsRevision by remember { mutableIntStateOf(0) }

    fun retryExportSave(jobId: Int) {
        if (exporting) return
        val (bytes, format) = exportJobs.loadArtifact(jobId) ?: return
        lastExportSizeBytes = bytes.size
        exporting = true
        exportStatus = null
        exportJobs.update(jobId, phase = "saving")
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    when (format) {
                        "mp4" -> MemeRaster.saveVideoFile(context, bytes, "bitos-meme-${System.currentTimeMillis()}")
                        "gif" -> MemeRaster.saveMediaFile(context, bytes, "image/gif", "bitos-meme-${System.currentTimeMillis()}")
                        else -> MemeRaster.savePng(context, android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size), "bitos-meme-${System.currentTimeMillis()}")
                    }
                }
            }
            exporting = false
            exportJobsRevision += 1
            result.onSuccess { exportJobs.finish(jobId) }
                .onFailure { exportJobs.update(jobId, phase = "failed", error = it.message) }
            exportOutcome = result.fold(
                onSuccess = { ExportOutcome.Success("Saved ✓ (reused the rendered file)") },
                onFailure = { ExportOutcome.Failure("Save failed: ${it.message}") },
            )
            exportStatus = when (val outcome = exportOutcome) {
                is ExportOutcome.Success -> outcome.detail
                is ExportOutcome.SuccessAdjusted -> outcome.detail
                is ExportOutcome.Failure -> outcome.message
                null -> null
            }
        }
    }
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
                uris.mapNotNull { uri ->
                    decodeBounds(context.contentResolver, uri)?.let { (w, h) ->
                        uri to EditorAsset("pending", uri, w.toFloat() / h, w, h)
                    }
                }
            }
            val existing = assets.map { it.id }.toSet()
            val candidates = decoded.mapIndexedNotNull { index, (uri, decoded0) ->
                val id = "a${assets.size + index + 1}"
                if (id in existing) {
                    null
                } else {
                    decoded0.copy(id = id)
                }
            }
            val accepted = state.addAssets(candidates.map { it.id })
            // Image mode STACK semantics: the FIRST pick is the background;
            // every later pick lands as a draggable image layer (the same
            // overlay binding video-mode inserts use).
            val hadBackground = assets.isNotEmpty()
            accepted.forEachIndexed { pickIndex, id ->
                candidates.firstOrNull { it.id == id }?.let { asset ->
                    assets += asset
                    if (!videoMode && (hadBackground || pickIndex > 0)) {
                        state.addImageOverlay(id)
                        exportStatus = "Layer added — drag to place it on the stack"
                    }
                }
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
        // An incompletely restored timeline must never persist: the slot
        // save rewrites the asset list from this session, so a partial one
        // would erase the dropped clips' files from the draft for good.
        if (videoMode && resumeDroppedClips > 0) {
            draftSaveState = DraftSaveState.FAILED
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(space.bitos.core.studio.MemeSlots.AUTOSAVE_DEBOUNCE_MS)
        draftSaveState = DraftSaveState.SAVING
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
            val saved = runCatching {
                slotStore.save(
                    slotId = slotId,
                    projectWire = space.bitos.core.studio.MemeProjectContract.encode(state.project),
                    assets = refs,
                    opener = opener,
                    nowMs = System.currentTimeMillis(),
                )
                true
            }.getOrDefault(false)
            draftSaveState = if (saved) DraftSaveState.SAVED else DraftSaveState.FAILED
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
            val bounds = withContext(Dispatchers.IO) {
                decodeBounds(context.contentResolver, uri)
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
            assets += EditorAsset(
                id, uri, bounds.first.toFloat() / bounds.second, bounds.first, bounds.second,
            )
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
        val exportProject = state.project
        val exportClips = videoClips.toList().toClipInputs(videoRate)
        val exportImages = imageAssetUris.toMap()
        val exportFrames = gifFrames.toList()
        val exportDelays = if (gifUniformDelayMs > 0) List(exportFrames.size) { gifUniformDelayMs } else gifDelays.toList()
        if (gifMode) {
            if (gifFrames.isEmpty()) {
                exportStatus = "Pick frames first"
                return
            }
            exporting = true
            showExport = true
            exportStatus = null
            val exportJob = exportJobs.begin("gif")
            scope.launch {
                var gifExportInfo: MemeGifExport.Result? = null
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val exported = MemeGifExport.export(exportFrames, exportDelays, exportProject)
                            ?: error("GIF export failed")
                        check(exportJobs.artifactReady(exportJob, exported.gifBytes)) {
                            "Could not persist the render"
                        }
                        gifExportInfo = exported
                        exportJobs.update(exportJob, phase = "saving")
                        MemeRaster.saveMediaFile(
                            context,
                            exported.gifBytes,
                            "image/gif",
                            "bitos-meme-${System.currentTimeMillis()}",
                        )
                    }
                }
                exporting = false
                exportJobsRevision += 1
                result.onSuccess {
                    lastExportSizeBytes = exportJobs.list().firstOrNull { it.id == exportJob }?.artifactBytes
                    exportJobs.finish(exportJob)
                }
                    .onFailure { exportJobs.update(exportJob, phase = "failed", error = it.message) }
                exportOutcome = result.fold(
                    onSuccess = {
                        val exported = gifExportInfo
                        if (exported != null && (exported.ladderStep > 0 || exported.capped)) {
                            ExportOutcome.SuccessAdjusted(
                                "Saved at a smaller size (downscaled ×${exported.ladderStep})",
                            )
                        } else {
                            ExportOutcome.Success("Saved to Photos ✓")
                        }
                    },
                    onFailure = { ExportOutcome.Failure("Save failed: ${it.message}") },
                )
                exportStatus = when (val outcome = exportOutcome) {
                    is ExportOutcome.Success -> outcome.detail
                    is ExportOutcome.SuccessAdjusted -> outcome.detail
                    is ExportOutcome.Failure -> outcome.message
                    null -> null
                }
            }
            return
        }
        if (videoMode) {
            if (!hasVideo) { exportStatus = "Pick a clip first"; return }
            val timelineMs = timelineDurationMs
            exporting = true
            showExport = true
            exportStatus = null
            val exportJob = exportJobs.begin("mp4")
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val exported = MemeVideoExport.exportClips(
                            context, exportClips, exportProject,
                            sfxMixTimeline(exportProject, timelineMs),
                            imageAssets = exportImages,
                        )
                        check(exportJobs.artifactReady(exportJob, exported)) {
                            "Could not persist the render"
                        }
                        exportJobs.update(exportJob, phase = "saving")
                        MemeRaster.saveVideoFile(
                            context, exported, "bitos-meme-${System.currentTimeMillis()}",
                        )
                    }
                }
                exporting = false
                exportJobsRevision += 1
                result.onSuccess {
                    lastExportSizeBytes = exportJobs.list().firstOrNull { it.id == exportJob }?.artifactBytes
                    exportJobs.finish(exportJob)
                }
                    .onFailure { exportJobs.update(exportJob, phase = "failed", error = it.message) }
                exportOutcome = result.fold(
                    onSuccess = { ExportOutcome.Success("Saved to Movies ✓") },
                    onFailure = { ExportOutcome.Failure("Save failed: ${it.message}") },
                )
                exportStatus = when (val outcome = exportOutcome) {
                    is ExportOutcome.Success -> outcome.detail
                    is ExportOutcome.SuccessAdjusted -> outcome.detail
                    is ExportOutcome.Failure -> outcome.message
                    null -> null
                }
            }
            return
        }
        val asset = activeAsset
        if (asset == null) {
            exportStatus = "Pick an image first"
            return
        }
        exporting = true
        showExport = true
        exportStatus = null
        val exportJob = exportJobs.begin("png")
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val source = MemeRaster.decodeForExport(context.contentResolver, asset.uri)
                        ?: error("Image could not be read")
                    val rendered = MemeRaster.render(source, exportProject)
                    val pngBytes = java.io.ByteArrayOutputStream().also { stream ->
                        check(rendered.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) {
                            "PNG encode failed"
                        }
                    }.toByteArray()
                    check(exportJobs.artifactReady(exportJob, pngBytes)) {
                        "Could not persist the render"
                    }
                    exportJobs.update(exportJob, phase = "saving")
                    MemeRaster.savePng(
                        context,
                        rendered,
                        "bitos-meme-${System.currentTimeMillis()}",
                    )
                }
            }
            exporting = false
            exportJobsRevision += 1
            result.onSuccess {
                lastExportSizeBytes = exportJobs.list().firstOrNull { it.id == exportJob }?.artifactBytes
                exportJobs.finish(exportJob)
            }
                .onFailure { exportJobs.update(exportJob, phase = "failed", error = it.message) }
            exportOutcome = result.fold(
                onSuccess = { ExportOutcome.Success("Saved to Photos ✓") },
                onFailure = { ExportOutcome.Failure("Save failed: ${it.message}") },
            )
            exportStatus = when (val outcome = exportOutcome) {
                is ExportOutcome.Success -> outcome.detail
                is ExportOutcome.SuccessAdjusted -> outcome.detail
                is ExportOutcome.Failure -> outcome.message
                null -> null
            }
        }
    }

    fun requestClose() {
        if (state.isEmpty) {
            clearSlot()
            onClose()
        } else if (draftSaveState == DraftSaveState.FAILED) {
            showDiscard = true
        } else {
            // MUX-01: leaving keeps the work — the debounced autosave holds
            // the newest committed revision; deleting the draft stays a
            // separate deliberate action.
            onClose()
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
        // ── Top chrome (prototype topbar: close · "Editor" · draft) ─────
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
            Text(
                "Editor",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.W600,
                color = BitOSColors.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            // Publish entry (prototype "Next · post details") lives in the
            // header beside draft save so the bottom stack stays tool-only.
            val headerHasMedia = activeAssetId != null || gifFrames.isNotEmpty() || hasVideo
            val headerPublishBusy = memePublishState?.phase.let {
                it == space.bitos.app.ui.feed.MemePublishPhase.UPLOADING ||
                    it == space.bitos.app.ui.feed.MemePublishPhase.PUBLISHING
            }
            // An incompletely restored timeline must not publish — the post
            // would be an irreversible partial video.
            val headerRestoreIncomplete = videoMode && resumeDroppedClips > 0
            TextButton(
                onClick = {
                    activePanel = null
                    showPublish = true
                },
                enabled = mediaPublishViewModel != null && headerHasMedia &&
                    !headerPublishBusy && !headerRestoreIncomplete,
                colors = ButtonDefaults.textButtonColors(contentColor = BitOSColors.primary),
                modifier = Modifier.semantics { contentDescription = "Next — post details" },
            ) {
                Text("Next", fontWeight = FontWeight.W600)
            }
            IconButton(
                onClick = { exportStatus = when (draftSaveState) {
                    DraftSaveState.SAVING -> "Saving draft…"
                    DraftSaveState.SAVED -> "Draft saved ✓ — resumes from Create hub"
                    DraftSaveState.FAILED -> "Could not save the draft — check storage; edits stay open"
                    DraftSaveState.IDLE -> "Draft autosaves as you edit"
                } },
                modifier = Modifier.semantics { contentDescription = "Save draft" },
            ) {
                when (draftSaveState) {
                    DraftSaveState.SAVING -> CircularProgressIndicator(
                        strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = BitOSColors.primary,
                    )
                    DraftSaveState.FAILED -> Icon(AppIcons.Close, contentDescription = null, tint = BitOSColors.warning)
                    else -> Icon(AppIcons.SaveDraft, contentDescription = null, tint = BitOSColors.textPrimary)
                }
            }
        }

        // ── Stage: media + overlays, single gesture target ───────────────
        // Prototype `create-edit`: the stage renders into a fixed-height
        // card that scrolls with the tools; the expert suite keeps the
        // legacy full-bleed weight(1f) stage.
        val suiteActive = suiteMode && videoMode && hasVideo
        val stageArea: @Composable (Modifier) -> Unit = { stageBoxModifier ->
        val stageWidth = stagePx.width.coerceAtLeast(1)
        val stageHeight = stagePx.height.coerceAtLeast(1)
        Box(
            modifier = stageBoxModifier,
            contentAlignment = Alignment.Center,
        ) {
            val current = if (gifMode || videoMode) null else activeAsset
            if (videoMode && hasVideo) {
                Box(Modifier.fillMaxSize()) {
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
                    coverSet = coverPreview != null || coverThumbUrl != null,
                    cues = state.project.sfxCues,
                    onPositionChange = { videoPositionMs = it },
                    imageAssets = imageAssetMap,
                    // The basic dock owns transport and the single canonical playhead.
                    showScrub = false,
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
                                coverPreview = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                                coverUploading = true
                                mediaPublishViewModel?.uploadMemeCover(jpeg) { url ->
                                    coverUploading = false
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
                val canvasTerms = state.project.canvasRatio
                    ?.let { space.bitos.core.studio.MemeCanvas.ratioTerms(it) }
                val stageAspect = if (canvasTerms != null) {
                    canvasTerms.first.toFloat() / canvasTerms.second
                } else {
                    frameAspect
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(stageAspect, matchHeightConstraintsFirst = stageAspect < 1f)
                        .background(parseCanvasColor(state.project.canvasBg) ?: BitOSColors.surface)
                        .onSizeChanged { stagePx = it },
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                        // WYSIWYG: the frame previews the same composed
                        // look + adjust grade the encoder burns per frame.
                        colorFilter = if (state.project.lookId != null || state.project.adjust != null) {
                            androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                                androidx.compose.ui.graphics.ColorMatrix(
                                    space.bitos.core.studio.MemeLooks.adjustedMatrixFor(
                                        state.project.lookId,
                                        state.project.adjust,
                                    ),
                                ),
                            )
                        } else {
                            null
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
                    // Gesture layer INSIDE the fitted box: tap positions map
                    // 1:1 onto the overlay/delete-handle math. A sibling over
                    // the letterboxed container offsets every hit.
                    StageGestures(
                        stageWidthPx = stageWidth,
                        stageHeightPx = stageHeight,
                        state = state,
                    )
                }
            } else if (current != null) {
                val canvasTerms = state.project.canvasRatio
                    ?.let { space.bitos.core.studio.MemeCanvas.ratioTerms(it) }
                val stageAspect = if (canvasTerms != null) {
                    canvasTerms.first.toFloat() / canvasTerms.second
                } else {
                    current.aspect
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(stageAspect, matchHeightConstraintsFirst = stageAspect < 1f)
                        .background(parseCanvasColor(state.project.canvasBg) ?: BitOSColors.surface)
                        .onSizeChanged { stagePx = it },
                ) {
                    AsyncImage(
                        model = current.uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                        // WYSIWYG: the stage shows the same composed look +
                        // adjust grade the rasterizer burns (MST-043 + FX).
                        colorFilter = if (state.project.lookId != null || state.project.adjust != null) {
                            androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                                androidx.compose.ui.graphics.ColorMatrix(
                                    space.bitos.core.studio.MemeLooks.adjustedMatrixFor(
                                        state.project.lookId,
                                        state.project.adjust,
                                    ),
                                ),
                            )
                        } else {
                            null
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
                    // Gesture layer INSIDE the fitted box (see GIF branch).
                    StageGestures(
                        stageWidthPx = stageWidth,
                        stageHeightPx = stageHeight,
                        state = state,
                    )
                }
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
            // ── Canvas meta chips (prototype `1080×1920 · 9:16` + duration)
            val metaChips = stageMetaChips(
                project = state.project,
                activeAsset = activeAsset,
                gifFrames = gifFrames,
                videoClips = videoClips,
            )
            if (metaChips.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = BitOSSpacing.base + 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    metaChips.forEach { chip ->
                        Text(
                            chip,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        }

        val penControls: @Composable () -> Unit = {

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

        val statusLine: @Composable () -> Unit = {
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
        }

        if (suiteActive) {
            stageArea(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = BitOSSpacing.base, vertical = BitOSSpacing.sm),
            )
            if (drawMode) penControls()
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
                onUndo = ::undoEdit,
                onRedo = ::redoEdit,
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
            // Give spare height to the preview, keeping editing controls next to the action bar.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
            val previewHeight = (maxHeight - 240.dp).coerceAtLeast(180.dp)
            val imagePreviewHeight = (maxHeight - 190.dp).coerceAtLeast(180.dp)
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Bottom,
            ) {
                stageArea(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BitOSSpacing.base)
                        .height(
                            when {
                                gifMode -> 320.dp
                                videoMode -> previewHeight
                                else -> imagePreviewHeight
                            },
                        )
                        .clip(RoundedCornerShape(16.dp)),
                )
                ModePillsRow(
                    activeMode = state.project.mode,
                    canUndo = state.canUndo,
                    onPickMode = { mode ->
                        if (mode == state.project.mode) return@ModePillsRow
                        if (state.isEmpty && gifFrames.isEmpty()) {
                            state.switchMode(mode)
                        } else {
                            confirmModeSwitch = true
                            pendingModeSwitch = mode
                        }
                    },
                    onAddClip = {
                        videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                    onAddImage = {
                        // launchPicker() is video-mode-aware (clips) — the
                        // inline image button always means IMAGES: frames in
                        // GIF mode, background/stack in IMAGE mode, and
                        // LAYERS in VIDEO (the layer picker binds overlays).
                        when {
                            videoMode -> layerPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                            gifMode -> gifPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                            )
                            else -> picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                    },
                    onUndo = ::undoEdit,
                )
                QuickToolsRow(
                    canAddOverlay = state.canAddOverlay,
                    looksEnabled = activeAsset != null || gifFrames.isNotEmpty() || (videoMode && hasVideo),
                    soundEnabled = videoMode && hasVideo,
                    saveEnabled = (activeAssetId != null || gifFrames.isNotEmpty() || hasVideo) && !exporting,
                    activePanel = activePanel,
                    drawActive = drawMode,
                    onPanel = { panel ->
                        drawMode = false
                        activePanel = if (activePanel == panel) null else panel
                    },
                    onDraw = {
                        activePanel = null
                        drawMode = !drawMode
                    },
                    onSave = { showExportSheet = true },
                )
                if (drawMode) penControls()
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
            )
        } else if (videoMode) {
            // The compact prototype uses the timeline itself as the clip
            // source strip. Clip/layer insertion remains in Timeline so the
            // basic editor does not render two competing timelines.
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
            // Adding lives in the mode row beside undo (single source-add
            // affordance) — the tray only selects among what exists.
        }
        }
                if (videoMode && hasVideo) {
                    TimelineStrip(
                        clips = videoClips.toList(),
                        selectedClipIndex = selectedClipIndex,
                        positionMs = videoPositionMs,
                        totalMs = timelineDurationMs,
                        onSelectClip = { selectedClipIndex = it },
                        onSplit = ::splitAtPlayhead,
                        onDelete = {
                            if (videoClips.size > 1) {
                                removeClip(selectedClipIndex)
                                exportStatus = "Clip deleted"
                            } else {
                                exportStatus = "Keep at least one clip"
                            }
                        },
                        onMute = {
                            videoClips.getOrNull(selectedClipIndex)?.let { clip ->
                                state.beginClipsEdit()
                                videoClips[selectedClipIndex] = clip.copy(
                                    volume = if (clip.volume == 0f) 1f else 0f,
                                )
                                syncWireClips()
                                exportStatus = if (clip.volume == 0f) "Clip sound on" else "Clip muted"
                            }
                        },
                        onSpeed = {
                            activePanel = null
                            showSpeed = true
                        },
                        onLayer = { showLayers = true },
                        transport = videoTransport,
                        onSetCover = { timelineMs ->
                            val mapped = timelineToMedia(timelineMs) ?: return@TimelineStrip
                            scope.launch {
                                val jpeg = withContext(Dispatchers.IO) {
                                    MemeVideoExport.captureCoverJpeg(mapped.first.bytes, mapped.second)
                                }
                                if (jpeg == null) {
                                    exportStatus = "Cover capture failed"
                                } else {
                                    coverPreview = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                                    coverUploading = true
                                    mediaPublishViewModel?.uploadMemeCover(jpeg) { url ->
                                        coverUploading = false
                                        coverThumbUrl = url
                                        if (url == null) exportStatus = "Cover upload failed"
                                    }
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(BitOSSpacing.sm))
            }
            }
            state.project.overlays.firstOrNull { it.id == state.selectedOverlayId }?.let { selected ->
                SelectionControlsRow(
                    selectedId = selected.id,
                    isText = selected.kind != space.bitos.core.studio.MemeOverlayKind.STICKER &&
                        selected.kind != space.bitos.core.studio.MemeOverlayKind.IMAGE,
                    onNudge = { dx, dy -> state.nudgeOverlay(selected.id, dx = dx, dy = dy) },
                    onScale = { factor -> state.nudgeOverlay(selected.id, scaleFactor = factor) },
                    onRotate = { degrees -> state.nudgeOverlay(selected.id, dRot = degrees) },
                    onEdit = { editingOverlayId = selected.id },
                    onDelete = { state.removeOverlay(selected.id) },
                )
            }
            PerModeBar(
                videoMode = videoMode,
                gifMode = gifMode,
                onNotice = { exportStatus = it },
                onOpenClips = { showClipSheet = true },
                onOpenTrim = { showTrim = true },
                onOpenFx = {
                    drawMode = false
                    activePanel = MemeEditorPanel.FX
                },
                onOpenText = {
                    drawMode = false
                    activePanel = MemeEditorPanel.TEXT
                },
                onOpenLayers = {
                    activePanel = null
                    showLayers = true
                },
                onOpenSuite = {
                    activePanel = null
                    suiteMode = true
                },
                onCycleGifSpeed = {
                    val next = if (gifUniformDelayMs >= 200) 50 else gifUniformDelayMs + 50
                    gifUniformDelayMs = next
                    exportStatus = "Frame hold $next ms"
                },
                onOpenCanvas = { showCanvas = true },
            )
            statusLine()
        }

        if (showExportSheet) {
            ModalBottomSheet(onDismissRequest = { showExportSheet = false }) {
                ExportSettingsContent(
                    designEligible = !gifMode && !videoMode && activeAsset != null &&
                        state.project.overlays.any {
                            it.kind == space.bitos.core.studio.MemeOverlayKind.TEXT && it.text.isNotBlank()
                        },
                    onMakeVariations = onMakeVariations?.let { callback ->
                        {
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val source = MemeRaster.decodeForExport(context.contentResolver, activeAsset!!.uri)
                                            ?: error("Image could not be read")
                                        MemeRaster.render(source, state.project)
                                    }
                                }
                                result.onSuccess { bitmap ->
                                    val bytes = java.io.ByteArrayOutputStream().also { stream ->
                                        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) { "PNG encode failed" }
                                    }.toByteArray()
                                    showExportSheet = false
                                    callback(space.bitos.core.studio.MemeProjectContract.encode(state.project), bytes)
                                }.onFailure { exportStatus = "Could not render the design — ${it.message}" }
                            }
                        }
                    },
                    recoveredJobs = remember(exportJobsRevision) { exportJobs.recoverable() },
                    onJobRetry = ::retryExportSave,
                    onJobDiscard = { exportJobs.discard(it); exportJobsRevision += 1 },
                    isVideo = videoMode && hasVideo,
                    isGif = gifMode && gifFrames.isNotEmpty(),
                    hasImage = !gifMode && !videoMode && activeAsset != null,
                    dims = when {
                        videoMode && hasVideo ->
                            videoClips.firstOrNull()?.probe?.let { "${it.uprightWidth}×${it.uprightHeight}" } ?: "source size"
                        gifMode && gifFrames.isNotEmpty() -> "${gifFrames.size} frames"
                        else -> activeAsset?.let { "${it.width}×${it.height}" } ?: "—"
                    },
                    durationSeconds = (timelineDurationMs / 1000).toInt(),
                    gifDelayMs = gifUniformDelayMs,
                    renderedSizeBytes = lastExportSizeBytes,
                    exporting = exporting,
                    failure = (exportOutcome as? ExportOutcome.Failure)?.message,
                    onExport = {
                        showExportSheet = false
                        saveToDevice()
                    },
                )
            }
        }

        // Tool panels present as native bottom sheets (canvas stays
        // visible above; drag or scrim dismisses).
        activePanel?.let { panel ->
            ModalBottomSheet(onDismissRequest = { activePanel = null }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BitOSSpacing.base)
                        .padding(bottom = BitOSSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                ) {
                    Text(
                        when (panel) {
                            MemeEditorPanel.MEME -> "Meme generator"
                            MemeEditorPanel.TEXT -> "Text"
                            MemeEditorPanel.STICKERS -> "Stickers"
                            MemeEditorPanel.SOUND -> "Sound"
                            MemeEditorPanel.FX -> "Look"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.W700,
                    )

                        when (panel) {
                            MemeEditorPanel.MEME -> MemeCaptionSheetContent(
                                enabled = state.canAddOverlay,
                                onAdd = { top, bottom, slot ->
                                    state.addMemeCaptions(top, bottom, slot)
                                    exportStatus = "Captions added — drag to fine-tune"
                                },
                            )
                            MemeEditorPanel.TEXT -> TextPanelContent(
                                onAdd = { text, slot ->
                                    val id = state.addOverlay(MemeOverlayKind.TEXT, text)
                                    if (id != null && slot != MemeFontSlot.SANS) {
                                        state.updateStyle(id, font = slot)
                                    }
                                    exportStatus = "Text added — tap it for the style editor"
                                },
                            )
                            MemeEditorPanel.STICKERS -> StickerSheetContent(
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
                            MemeEditorPanel.SOUND -> SoundPanelContent(
                                cueCount = state.project.sfxCues.size,
                                onOpenStudio = {
                                    activePanel = null
                                    showSfx = true
                                },
                            )
                            MemeEditorPanel.FX -> if (videoMode && hasVideo) {
                                // M5: the grade applies to the SELECTED clip;
                                // the adjust sliders stay project-wide.
                                val clip = videoClips.getOrNull(selectedClipIndex) ?: videoClips.first()
                                LooksSheetContent(
                                    active = clip.lookId ?: state.project.lookId
                                        ?: space.bitos.core.studio.MemeLooks.NONE,
                                    onPick = { id ->
                                        val index = videoClips.indexOf(clip)
                                        if (index >= 0) {
                                            state.beginClipsEdit()
                                            videoClips[index] = clip.copy(
                                                lookId = space.bitos.core.studio.MemeLooks.normalize(id),
                                            )
                                            syncWireClips()
                                            exportStatus = "Look applies to vdo ${index + 1}"
                                        }
                                    },
                                    adjust = state.project.adjust,
                                    onAdjust = { state.setAdjust(it) },
                                )
                            } else {
                                LooksSheetContent(
                                    active = state.project.lookId ?: space.bitos.core.studio.MemeLooks.NONE,
                                    onPick = { id -> state.setLook(id) },
                                    adjust = state.project.adjust,
                                    onAdjust = { state.setAdjust(it) },
                                )
                            }
                        }
                    }
            }
        }

        // ── Next: into the publish flow (prototype "Next · post details").
        val hasMedia = activeAssetId != null || gifFrames.isNotEmpty() || hasVideo
        val publishBusy = memePublishState?.phase.let {
            it == space.bitos.app.ui.feed.MemePublishPhase.UPLOADING ||
                it == space.bitos.app.ui.feed.MemePublishPhase.PUBLISHING
        }
        // Bottom keeps a slim disabled-state notice only; the action itself
        // is the header Next button.
        if (!hasMedia || publishBusy) {
            Text(
                if (hasMedia) "publishing…" else "pick a clip, image or frames first",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BitOSSpacing.base, vertical = BitOSSpacing.xs)
                    .navigationBarsPadding(),
            )
        }
    }


    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Could not save the draft") },
            text = {
                Text(
                    "Your edits are still open. Retry the save to keep them, or delete the draft deliberately.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    onClose()
                }) { Text("Keep editing", color = BitOSColors.primary, fontWeight = FontWeight.W600) }
            },
            dismissButton = {
                TextButton(onClick = {
                    clearSlot()
                    onClose()
                }) { Text("Delete draft", color = BitOSColors.error) }
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

    if (showLooks) {
        ModalBottomSheet(onDismissRequest = { showLooks = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BitOSSpacing.base)
                    .padding(bottom = BitOSSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
            ) {
                Text("Color look", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W700)
                if (videoMode && hasVideo) {
                    // M5: the grade applies to the SELECTED clip; "none" clears
                    // the override (the project grade shows through again). The
                    // adjust sliders stay project-wide (prototype semantics).
                    val clip = videoClips.getOrNull(selectedClipIndex) ?: videoClips.first()
                    LooksSheetContent(
                        active = clip.lookId ?: state.project.lookId
                            ?: space.bitos.core.studio.MemeLooks.NONE,
                        onPick = { id ->
                            val index = videoClips.indexOf(clip)
                            if (index >= 0) {
                                state.beginClipsEdit()
                                            videoClips[index] = clip.copy(
                                                lookId = space.bitos.core.studio.MemeLooks.normalize(id),
                                            )
                                            syncWireClips()
                                            exportStatus = "Look applies to vdo ${index + 1}"
                            }
                            showLooks = false
                        },
                        adjust = state.project.adjust,
                        onAdjust = { state.setAdjust(it) },
                    )
                } else {
                    LooksSheetContent(
                        active = state.project.lookId ?: space.bitos.core.studio.MemeLooks.NONE,
                        onPick = { id ->
                            state.setLook(id)
                            showLooks = false
                        },
                        adjust = state.project.adjust,
                        onAdjust = { state.setAdjust(it) },
                    )
                }
            }
        }
    }

    if (showSfx) {
        ModalBottomSheet(onDismissRequest = { showSfx = false; SfxPreview.stop() }) {
            SfxSheetContent(
                cues = state.project.sfxCues,
                positionMs = videoPositionMs,
                onApplyTemplate = { id ->
                    space.bitos.core.studio.SfxTemplates.ALL.firstOrNull { it.id == id }
                        ?.cues?.forEach { cue -> state.addSfxCue(cue.sfx, videoPositionMs + cue.atMs) }
                },
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
                onMove = { id, delta -> state.moveOverlay(id, delta) },
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
                speed = clipRate(clip),
                onApply = { start, end ->
                    val index = videoClips.indexOf(clip)
                    if (index >= 0) {
                        state.beginClipsEdit()
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
                        state.beginClipsEdit()
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

    if (showCanvas && !videoMode) {
        ModalBottomSheet(onDismissRequest = { showCanvas = false }) {
            CanvasSheetContent(
                ratio = state.project.canvasRatio ?: space.bitos.core.studio.MemeCanvas.RATIO_SOURCE,
                bg = state.project.canvasBg,
                onPick = { ratio, bg -> state.setCanvas(ratio, bg) },
            )
        }
    }

    if (showSpeed) {
        val selected = videoClips.getOrNull(selectedClipIndex)
        ModalBottomSheet(onDismissRequest = { showSpeed = false }) {
            SpeedSheetContent(
                speed = selected?.let(::clipRate) ?: videoRate,
                mediaDurationMs = selected?.let { it.endMs - it.startMs }
                    ?: videoProbe?.durationMs ?: state.project.trimEndMs,
                onPick = {
                    if (selected != null) {
                        state.beginClipsEdit()
                        videoClips[selectedClipIndex] = selected.copy(speed = it)
                        syncWireClips()
                        exportStatus = "Speed applies to vdo ${selectedClipIndex + 1}"
                    }
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
        MemePostFlowScreen(
            state = state,
            asset = activeAsset,
            gifFrameCount = gifFrames.size,
            hasVideo = hasVideo,
            timelineSeconds = ((timelineDurationMs + 999) / 1000).toInt(),
            clipCount = videoClips.size,
            coverSet = coverPreview != null || coverThumbUrl != null,
            coverUrl = coverThumbUrl,
            coverPreview = coverPreview,
            coverUploading = coverUploading,
            publishState = memePublishState,
            remixSeed = remixSeed,
            onPublish = { caption, altText, cwReason, tags, license, allowZaps, remixOf, remixAuthor, remixRelays, remixLabel ->
                val project = state.project
                // Video/GIF modes have no `activeAsset` (their media lives in
                // session bytes) — the mode itself gates readiness here.
                val mediaReady = activeAsset != null ||
                    (project.mode == MemeMode.VIDEO && hasVideo) ||
                    (project.mode == MemeMode.GIF && gifFrames.isNotEmpty())
                if (!mediaReady) return@MemePostFlowScreen
                mediaPublishViewModel.markMemeRenderStarted()
                scope.launch {
                    val rendered = withContext(Dispatchers.IO) {
                        if (project.mode == MemeMode.VIDEO && hasVideo) {
                            // MST-034: video memes publish as kind 22/21 by
                            // orientation with duration+dim imeta. Over-size
                            // exports are CUT (duration ladder), not failed —
                            // M5: the ladder trims the LAST clip's window.
                            val probe = videoClips.first().probe
                            suspend fun exportNow(): ByteArray = MemeVideoExport.exportClips(
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
                    // Recently used hashtags feed the "Recent" chip rows.
                    space.bitos.app.data.publish.RecentHashtagsStore.get(context)
                        .record(space.bitos.core.publish.RecentHashtags.hashtagsIn(caption) + tags)
                    // Post-details extras ride the same verified machine:
                    // explicit t-tags merge with the remix lineage, which
                    // supplies the license itself on a remix (web tag
                    // order: remix, meme, p, license, attribution).
                    val lineageJson = remixTagsFor(
                        state.project, remixOf, remixAuthor, remixRelays, license, remixLabel,
                    )
                    val mergedTags = postExtraTagsJson(
                        lineageJson,
                        tags,
                        license,
                        allowZaps,
                    )
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
                            remixTagsJson = mergedTags,
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
                            remixTagsJson = mergedTags,
                        )
                    }
                }
            },
            onDismiss = { showPublish = false },
            onPublished = {
                showPublish = false
                onClose()
            },
            recoverableJobs = mediaPublishViewModel.memeJobs(),
            onJobRetry = { mediaPublishViewModel.retryMemeJob(it) },
            onJobDiscard = { mediaPublishViewModel.discardMemeJob(it) },
            onJobVerify = { mediaPublishViewModel.verifyMemeJobIntegrity(it) },
        )
    }
}

/** Decode-only bounds pass (no bitmap allocation); null for unreadable files. */
private fun decodeBounds(resolver: android.content.ContentResolver, uri: Uri): Pair<Int, Int>? = try {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
    if (options.outWidth <= 0 || options.outHeight <= 0) {
        null
    } else {
        options.outWidth to options.outHeight
    }
} catch (_: Exception) {
    null
}

/** Decode-only aspect pass (no bitmap allocation); null for unreadable files. */
private fun decodeAspect(resolver: android.content.ContentResolver, uri: Uri): Float? =
    decodeBounds(resolver, uri)?.let { it.first.toFloat() / it.second }

/** Asset bytes for slot persistence: content picks via resolver, restored
 * slot files (file://) straight off disk. Shared with the Create hub's
 * import-media → editor seeding. */
internal fun readAssetBytes(context: android.content.Context, uri: Uri): ByteArray? = runCatching {
    when (uri.scheme?.lowercase()) {
        "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        "file" -> uri.path?.let { path -> java.io.File(path).takeIf(File::exists)?.readBytes() }
        else -> null
    }
}.getOrNull()

/** MUX-01 draft persistence states. */
enum class DraftSaveState { IDLE, SAVING, SAVED, FAILED }

/** MUX-02 typed export outcomes (success-with-adjustment is a SUCCESS). */
sealed interface ExportOutcome {
    data class Success(val detail: String) : ExportOutcome
    data class SuccessAdjusted(val detail: String) : ExportOutcome
    data class Failure(val message: String) : ExportOutcome
}

/** Inline editor panels (prototype create-edit tool panels). */
private enum class MemeEditorPanel { MEME, TEXT, STICKERS, SOUND, FX }

/** Prototype mode switcher: uppercase pills + the undo button. */
@Composable
private fun ModePillsRow(
    activeMode: MemeMode,
    canUndo: Boolean,
    onPickMode: (MemeMode) -> Unit,
    onAddClip: () -> Unit,
    onAddImage: () -> Unit,
    onUndo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.base, vertical = BitOSSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(BitOSColors.surface)
                .border(1.dp, BitOSColors.border, RoundedCornerShape(50))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf(
                MemeMode.VIDEO to "VIDEO",
                MemeMode.GIF to "GIF",
                MemeMode.IMAGE to "IMAGE",
            ).forEach { (mode, label) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (activeMode == mode) androidx.compose.ui.graphics.Color.Black else BitOSColors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (activeMode == mode) BitOSColors.primary else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .clickable { onPickMode(mode) }
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // Source add (frame/image/clip per mode) lives here with undo so the
        // pick affordance isn't buried in the tray.
        if (activeMode == MemeMode.VIDEO) {
            IconButton(onClick = onAddClip) {
                SolarStudioIconImage(
                    SolarStudioIcon.VideoCamera,
                    contentDescription = "Add clip",
                    tint = BitOSColors.textPrimary,
                )
            }
        }
        IconButton(onClick = onAddImage) {
            SolarStudioIconImage(
                SolarStudioIcon.Gallery,
                contentDescription = when (activeMode) {
                    MemeMode.GIF -> "Add frames"
                    MemeMode.VIDEO -> "Add image layer"
                    MemeMode.IMAGE -> "Add image"
                },
                tint = BitOSColors.textPrimary,
            )
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            SolarStudioIconImage(
                SolarStudioIcon.UndoLeft,
                contentDescription = "Undo",
                tint = if (canUndo) BitOSColors.textPrimary else BitOSColors.textTertiary,
            )
        }
    }
}

/** Prototype quick-tool chip shell: label pill with the accent rules (the
 *  "hot" Meme tool tints, the open panel gets the accent border). */
@Composable
private fun QuickToolChipShell(
    label: String,
    hot: Boolean,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val tint = if (active || hot) BitOSColors.primary else BitOSColors.textSecondary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(
                when {
                    active -> BitOSColors.primary.copy(alpha = 0.22f)
                    hot -> BitOSColors.primary.copy(alpha = 0.12f)
                    else -> BitOSColors.surface
                },
            )
            .border(
                1.dp,
                if (active || hot) BitOSColors.primary else BitOSColors.border,
                CircleShape,
            )
            .clickable(enabled = enabled) { onClick() }
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = label },
    ) {
        icon()
        Text(
            label,
            fontSize = 9.sp,
            fontWeight = FontWeight.W700,
            color = tint,
            maxLines = 1,
        )
    }
}

/** Quick-tool chip with a Solar studio glyph. */
@Composable
private fun QuickToolChip(
    icon: SolarStudioIcon,
    label: String,
    hot: Boolean = false,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) = QuickToolChipShell(label, hot, active, enabled, onClick) {
    SolarStudioIconImage(icon, contentDescription = null, tint = if (active || hot) BitOSColors.primary else BitOSColors.textSecondary, modifier = Modifier.size(16.dp))
}

/** Quick-tool chip with a vector glyph. */
@Composable
private fun QuickToolChipVector(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) = QuickToolChipShell(label, false, active, enabled, onClick) {
    Icon(icon, contentDescription = null, tint = if (active) BitOSColors.primary else BitOSColors.textSecondary, modifier = Modifier.size(15.dp))
}

/** Quick tool chips (prototype: Meme · Text · Stickers · Sound · Effects;
 *  Draw/Save stay as native extras). */
@Composable
private fun QuickToolsRow(
    canAddOverlay: Boolean,
    looksEnabled: Boolean,
    soundEnabled: Boolean,
    saveEnabled: Boolean,
    activePanel: MemeEditorPanel?,
    drawActive: Boolean,
    onPanel: (MemeEditorPanel) -> Unit,
    onDraw: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = BitOSSpacing.base),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickToolChip(
            icon = SolarStudioIcon.MagicWand,
            label = "Meme",
            hot = true,
            active = activePanel == MemeEditorPanel.MEME,
            enabled = canAddOverlay,
        ) { onPanel(MemeEditorPanel.MEME) }
        QuickToolChip(
            icon = SolarStudioIcon.Text,
            label = "Text",
            active = activePanel == MemeEditorPanel.TEXT,
            enabled = canAddOverlay,
        ) { onPanel(MemeEditorPanel.TEXT) }
        QuickToolChip(
            icon = SolarStudioIcon.Sticker,
            label = "Stickers",
            active = activePanel == MemeEditorPanel.STICKERS,
            enabled = canAddOverlay,
        ) { onPanel(MemeEditorPanel.STICKERS) }
        QuickToolChip(
            icon = SolarStudioIcon.Soundwave,
            label = "Sound",
            active = activePanel == MemeEditorPanel.SOUND,
            enabled = soundEnabled,
        ) { onPanel(MemeEditorPanel.SOUND) }
        QuickToolChip(
            icon = SolarStudioIcon.Palette,
            label = "Look",
            active = activePanel == MemeEditorPanel.FX,
            enabled = looksEnabled,
        ) { onPanel(MemeEditorPanel.FX) }
        QuickToolChip(
            icon = SolarStudioIcon.Pen,
            label = "Draw",
            active = drawActive,
            enabled = true,
        ) { onDraw() }
        QuickToolChipVector(
            icon = AppIcons.Download,
            label = "Export",
            active = false,
            enabled = saveEnabled,
        ) { onSave() }
    }
}


/** Quick-text panel (prototype text panel): type once, pick a font slot. */
@Composable
private fun TextPanelContent(onAdd: (String, MemeFontSlot) -> Unit) {
    var text by remember { mutableStateOf("") }
    var slot by remember { mutableStateOf(MemeFontSlot.SANS) }
    Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
        space.bitos.app.ui.components.BitosTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = "Type something…",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
            listOf(
                MemeFontSlot.SANS to "Modern",
                MemeFontSlot.IMPACT to "Impact",
                MemeFontSlot.SERIF to "Comic",
            ).forEach { (candidate, label) ->
                FilterChip(
                    selected = slot == candidate,
                    onClick = { slot = candidate },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Button(
            onClick = {
                val value = text.trim()
                if (value.isNotEmpty()) {
                    onAdd(value, slot)
                    text = ""
                }
            },
            enabled = text.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = BitOSColors.primary,
                contentColor = androidx.compose.ui.graphics.Color.White,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add text")
        }
    }
}

/** Sound panel (prototype sound row): cue summary + jump to the studio. */
@Composable
private fun SoundPanelContent(cueCount: Int, onOpenStudio: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.base)) {
        Column(Modifier.weight(1f)) {
            Text(
                if (cueCount == 0) "Original clip audio" else "$cueCount synth cues",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.W600,
            )
            Text(
                if (cueCount == 0) "Drop risers, zaps and coin SFX at the playhead"
                else "Cues bake into the export mix",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
        }
        Text(
            "Change",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.W600,
            color = BitOSColors.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onOpenStudio() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/** One clip segment in the compact timeline. */
@Composable
private fun TimelineClipSegment(
    index: Int,
    widthPx: Float,
    outSec: Int,
    speed: Float,
    muted: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .padding(start = if (index == 0) 0.dp else 1.dp, end = 1.dp)
            .width(with(androidx.compose.ui.platform.LocalDensity.current) { widthPx.toDp() })
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) BitOSColors.primary.copy(alpha = 0.85f)
                else BitOSColors.surfaceElevated,
            )
            .border(
                1.5.dp,
                if (selected) BitOSColors.primary else BitOSColors.textTertiary.copy(alpha = 0.45f),
                RoundedCornerShape(6.dp),
            )
            .clickable { onSelect() },
    ) {
        Text(
            "vdo ${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = androidx.compose.ui.graphics.Color.White,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (muted) {
                Text("🔇", fontSize = 9.sp)
            }
            Text(
                "${outSec}s · ${"%.2g".format(java.util.Locale.US, speed)}×",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.W600,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
    }
}

/** Compact clip timeline (prototype edTimeline): ruler, proportional
 *  segments, playhead and the Split/Delete/Mute/Speed/Layer tool row. */
@Composable
private fun TimelineStrip(
    clips: List<SessionClip>,
    selectedClipIndex: Int,
    positionMs: Long,
    totalMs: Long,
    onSelectClip: (Int) -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onMute: () -> Unit,
    onSpeed: () -> Unit,
    onLayer: () -> Unit,
    transport: VideoTransport,
    onSetCover: (Long) -> Unit,
) {
    val safeTotal = maxOf(1L, totalMs)
    var draggingScrubber by remember { mutableStateOf(false) }
    var pendingScrubMs by remember { mutableStateOf(0f) }
    fun mmss(ms: Long): String =
        String.format("%02d:%02d", (ms / 1000) / 60, (ms / 1000) % 60)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.base),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = transport.playPause) {
                Icon(if (transport.isPlaying()) AppIcons.Pause else AppIcons.Play, contentDescription = "Play or pause")
            }
            BitosSlider(
                value = if (draggingScrubber) pendingScrubMs
                    else positionMs.toFloat().coerceIn(0f, safeTotal.toFloat()),
                onValueChange = {
                    draggingScrubber = true
                    pendingScrubMs = it
                },
                onValueChangeFinished = {
                    transport.seekTo(pendingScrubMs.toLong())
                    draggingScrubber = false
                },
                valueRange = 0f..safeTotal.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(
                "${mmss(positionMs.coerceAtMost(safeTotal))} / ${mmss(safeTotal)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = BitOSColors.primary,
                fontWeight = FontWeight.W600,
            )
            TextButton(onClick = { onSetCover(positionMs) }) { Text("Cover") }
        }
        Text(
            "Timeline · clip ${selectedClipIndex + 1} of ${clips.size}",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
        )
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BitOSColors.surfaceElevated.copy(alpha = 0.55f))
                .pointerInput(safeTotal) {
                    detectTapGestures { tap ->
                        val fraction = (tap.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        transport.seekTo((fraction * safeTotal).toLong())
                    }
                },
        ) {
            val trackWidth = constraints.maxWidth.toFloat()
            Row(Modifier.fillMaxHeight()) {
                clips.forEachIndexed { index, clip ->
                    key(clip.id) {
                        TimelineClipSegment(
                            index = index,
                            widthPx = trackWidth * clipOutputMsOf(clip) / safeTotal,
                            outSec = ((clipOutputMsOf(clip) + 999) / 1000).toInt(),
                            speed = space.bitos.core.studio.MemeProjectContract.clampSpeed(clip.speed),
                            muted = clip.volume == 0f,
                            selected = index == selectedClipIndex,
                            onSelect = { onSelectClip(index) },
                        )
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = with(androidx.compose.ui.platform.LocalDensity.current) {
                        (trackWidth * (positionMs.coerceIn(0L, safeTotal).toFloat() / safeTotal) - 1f).toDp()
                    })
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(BitOSColors.primary),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            ClipToolButton(AppIcons.Scissors, "Split", onSplit)
            ClipToolButton(AppIcons.Delete, "Delete", onDelete)
            ClipToolButton(AppIcons.Mute, "Mute", onMute)
            ClipToolButton(AppIcons.Speed, "Speed", onSpeed)
            ClipToolButton(AppIcons.Layer, "Layer", onLayer)
        }
    }
}

/** Output window ms for one clip at the project rate (display math only). */
private fun clipOutputMsOf(clip: SessionClip): Long =
    ((maxOf(0L, clip.endMs - clip.startMs)) /
        space.bitos.core.studio.MemeProjectContract.clampSpeed(clip.speed)).toLong()

/** Icon-over-caption clip tool / per-mode bar button. */
@Composable
private fun ClipToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = label },
    ) {
        Icon(icon, contentDescription = null, tint = BitOSColors.textSecondary, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary, fontWeight = FontWeight.W600)
    }
}

/** Canvas settings (image/GIF): ratio preset + background color (shared
 *  [space.bitos.core.studio.MemeCanvas] rules; the media letterboxes). */
@Composable
private fun CanvasSheetContent(
    ratio: String,
    bg: String?,
    onPick: (ratio: String?, bg: String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.base)
            .padding(bottom = BitOSSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        Text("Canvas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W700)
        Text("Size", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W600)
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
            space.bitos.core.studio.MemeCanvas.RATIOS.forEach { (id, label) ->
                val short = if (id == space.bitos.core.studio.MemeCanvas.RATIO_SOURCE) "Source" else id
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (ratio == id) BitOSColors.primary else BitOSColors.surfaceElevated,
                    modifier = Modifier.clickable(onClickLabel = label) { onPick(id, bg) },
                ) {
                    Text(
                        short,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.W600,
                        color = if (ratio == id) androidx.compose.ui.graphics.Color.Black else BitOSColors.textPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Text("Background", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W600)
        Row(
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("#000000", "#ffffff", "#fde047", "#f97316", "#22d3ee", "#a3e635", "#f472b6")
                .forEach { hex ->
                    val selected = bg == hex
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(parseCanvasColor(hex) ?: androidx.compose.ui.graphics.Color.White)
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) BitOSColors.primary else BitOSColors.border,
                                androidx.compose.foundation.shape.CircleShape,
                            )
                            .clickable(onClickLabel = "Background $hex") { onPick(ratio.takeIf { it != space.bitos.core.studio.MemeCanvas.RATIO_SOURCE }, hex) },
                    )
                }
            if (bg != null) {
                TextButton(onClick = { onPick(ratio.takeIf { it != space.bitos.core.studio.MemeCanvas.RATIO_SOURCE }, null) }) {
                    Text("Clear", color = BitOSColors.textSecondary)
                }
            }
        }
        var hex by remember { mutableStateOf(bg ?: "") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
            space.bitos.app.ui.components.BitosTextField(
                value = hex,
                onValueChange = { value ->
                    hex = value.take(7)
                    // Apply as typed when it completes a valid #rrggbb.
                    if (space.bitos.core.studio.MemeCanvas.isValidBackground(hex)) {
                        onPick(ratio.takeIf { it != space.bitos.core.studio.MemeCanvas.RATIO_SOURCE }, hex)
                    }
                },
                placeholder = "#rrggbb custom",
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(28.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(parseCanvasColor(hex) ?: BitOSColors.surfaceElevated)
                    .border(1.dp, BitOSColors.border, androidx.compose.foundation.shape.CircleShape),
            )
        }
        Text(
            "The media letterboxes onto the canvas; captions and layers keep their positions.",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
        )
    }
}

/** `#rrggbb` → Compose color; null when malformed. */
private fun parseCanvasColor(hex: String?): androidx.compose.ui.graphics.Color? = hex?.let {
    if (it.length == 7 && it.startsWith("#")) {
        it.drop(1).toLongOrNull(16)?.let { value ->
            androidx.compose.ui.graphics.Color(
                red = ((value shr 16) and 0xFF).toInt() / 255f,
                green = ((value shr 8) and 0xFF).toInt() / 255f,
                blue = (value and 0xFF).toInt() / 255f,
                alpha = 1f,
            )
        }
    } else {
        null
    }
}

/** Output settings before export (MUX-04): shows EXACTLY the profile the
 *  pipeline renders — the only tested profile per mode — with automatic
 *  adjustments disclosed up front and the destination named. */
@Composable
private fun ExportSettingsContent(
    designEligible: Boolean = false,
    onMakeVariations: (() -> Unit)? = null,
    recoveredJobs: List<MemeExportJobStore.Job> = emptyList(),
    onJobRetry: (Int) -> Unit = {},
    onJobDiscard: (Int) -> Unit = {},
    isVideo: Boolean,
    isGif: Boolean,
    hasImage: Boolean,
    dims: String,
    durationSeconds: Int,
    gifDelayMs: Int,
    renderedSizeBytes: Int?,
    exporting: Boolean,
    failure: String?,
    onExport: () -> Unit,
) {
    fun sizeText(bytes: Int): String = String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    val format = when {
        isVideo -> "MP4 · $dims · $durationSeconds s"
        isGif -> "GIF · $dims" + if (gifDelayMs > 0) " · $gifDelayMs ms/frame" else " · source timing"
        hasImage -> "PNG · $dims"
        else -> "—"
    }
    val adjustmentNote = when {
        isVideo -> "Save exports the current timeline. Posting checks the 64 MB limit and may offer a shorter version."
        isGif -> "Oversized GIFs automatically downscale — you'll see “Saved at a smaller size” if that happens."
        else -> "Full-quality PNG at the media's resolution."
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.base)
            .padding(bottom = BitOSSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        Text("Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W700)
        Text(format, style = MaterialTheme.typography.titleSmall)
        Text(
            "Destination: " + (if (isVideo) "Movies" else "Photos") + " · " +
                (renderedSizeBytes?.let { "rendered size: ${sizeText(it)}" }
                    ?: "size is shown after rendering; estimates would be guesses."),
            style = MaterialTheme.typography.bodySmall,
            color = BitOSColors.textSecondary,
        )
        Text(
            "Public-safe export: a fresh render removes source location, device and creation metadata.",
            style = MaterialTheme.typography.bodySmall,
            color = BitOSColors.textSecondary,
        )
        Text(
            adjustmentNote,
            style = MaterialTheme.typography.bodySmall,
            color = BitOSColors.textSecondary,
        )
        failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BitOSColors.error) }
        if (recoveredJobs.isNotEmpty()) {
            Text("Recovered exports", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W700)
            recoveredJobs.forEach { job ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BitOSColors.surface)
                        .padding(BitOSSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            job.format.uppercase() + " · " + sizeText(job.artifactBytes),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.W600,
                            modifier = Modifier.weight(1f),
                        )
                        if (job.phase == "needsReview") {
                            Text("check Photos first", style = MaterialTheme.typography.labelSmall, color = BitOSColors.warning)
                        }
                    }
                    job.lastError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = BitOSColors.error) }
                    Row {
                        Button(
                            onClick = { onJobRetry(job.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.primary, contentColor = androidx.compose.ui.graphics.Color.White),
                        ) { Text("Retry save", fontWeight = FontWeight.W600) }
                        Spacer(Modifier.width(BitOSSpacing.sm))
                        OutlinedButton(onClick = { onJobDiscard(job.id) }) { Text("Discard", color = BitOSColors.error) }
                    }
                }
            }
            Text("Retry reuses the rendered file — it never re-renders.", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
        }
        if (designEligible && onMakeVariations != null) {
            OutlinedButton(onClick = onMakeVariations, modifier = Modifier.fillMaxWidth()) {
                Text("Make variations from this design", fontWeight = FontWeight.W600)
            }
            Text(
                "Freezes this design and varies every caption per row.",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textSecondary,
            )
        }
        Button(
            onClick = onExport,
            enabled = !exporting && (isVideo || isGif || hasImage),
            colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.primary, contentColor = androidx.compose.ui.graphics.Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (exporting) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = androidx.compose.ui.graphics.Color.White)
                Spacer(Modifier.width(BitOSSpacing.xs))
            }
            Text(if (exporting) "Rendering…" else "Export", fontWeight = FontWeight.W600)
        }
    }
}

/** Accessible manipulation for the selection (MUX-03): nudge / resize /
 *  rotate / edit / delete without precision gestures — 48dp targets. */
@Composable
private fun SelectionControlsRow(
    selectedId: String,
    isText: Boolean,
    onNudge: (Float, Float) -> Unit,
    onScale: (Float) -> Unit,
    onRotate: (Float) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val borderColor = BitOSColors.border
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = BitOSSpacing.base, vertical = BitOSSpacing.xs),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon = function (Solar alt-arrow / magnifier semantics): the old
        // row mixed a back arrow, a download icon and a bare plus, which
        // read as navigation/save/add instead of nudge/zoom.
        SelectionControlButton("Nudge left", AppIcons.NudgeLeft, onClick = { onNudge(-0.05f, 0f) })
        SelectionControlButton("Nudge right", AppIcons.NudgeRight, onClick = { onNudge(0.05f, 0f) })
        SelectionControlButton("Nudge up", AppIcons.NudgeUp, onClick = { onNudge(0f, -0.05f) })
        SelectionControlButton("Nudge down", AppIcons.NudgeDown, onClick = { onNudge(0f, 0.05f) })
        SelectionControlButton("Shrink", AppIcons.ZoomOut, onClick = { onScale(0.9f) })
        SelectionControlButton("Enlarge", AppIcons.ZoomIn, onClick = { onScale(1.1f) })
        SelectionControlButton("Rotate left", AppIcons.Repost, onClick = { onRotate(-15f) })
        SelectionControlButton("Rotate right", AppIcons.Refresh, onClick = { onRotate(15f) })
        if (isText) {
            SelectionControlButton("Edit text", AppIcons.TextGlyph, onClick = onEdit)
        }
        SelectionControlButton("Delete overlay", AppIcons.Delete, onClick = onDelete, destructive = true)
    }
}

@Composable
private fun SelectionControlButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = label) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) BitOSColors.error else BitOSColors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Per-mode bottom toolbar (prototype edBar). */
@Composable
private fun PerModeBar(
    onOpenClips: () -> Unit,
    onOpenTrim: () -> Unit,
    videoMode: Boolean,
    gifMode: Boolean,
    onNotice: (String) -> Unit,
    onOpenFx: () -> Unit,
    onOpenText: () -> Unit,
    onOpenLayers: () -> Unit,
    onOpenSuite: () -> Unit,
    onCycleGifSpeed: () -> Unit,
    onOpenCanvas: () -> Unit = {},
) {
    val borderColor = BitOSColors.border
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = BitOSSpacing.base, vertical = BitOSSpacing.sm),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        if (videoMode) {
            ClipToolButton(AppIcons.Video, "Clips") { onOpenClips() }
            ClipToolButton(AppIcons.Filter, "Adjust") { onOpenFx() }
            ClipToolButton(AppIcons.Crop, "Trim") { onOpenTrim() }
            ClipToolButton(AppIcons.AppsGrid, "Overlay") { onOpenLayers() }
            ClipToolButton(AppIcons.Sparkles, "Timeline") { onOpenSuite() }
        } else if (gifMode) {
            ClipToolButton(AppIcons.Ratio, "Canvas") { onOpenCanvas() }
            ClipToolButton(AppIcons.Speed, "Speed") { onCycleGifSpeed() }
            ClipToolButton(AppIcons.Loop, "Loop") { onNotice("GIFs loop forever — nothing to set") }
            ClipToolButton(AppIcons.Looks, "Filter") { onOpenFx() }
            ClipToolButton(AppIcons.TextGlyph, "Text") { onOpenText() }
        } else {
            ClipToolButton(AppIcons.Ratio, "Canvas") { onOpenCanvas() }
            ClipToolButton(AppIcons.TextGlyph, "Text") { onOpenText() }
            ClipToolButton(AppIcons.AppsGrid, "Layers") { onOpenLayers() }
            ClipToolButton(AppIcons.Looks, "Filter") { onOpenFx() }
            ClipToolButton(AppIcons.Filter, "Adjust") { onOpenFx() }
        }
    }
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
            // Adding lives in the mode row beside undo (single source-add
            // affordance) — the tray only selects/reorders frames.
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
    val density = LocalDensity.current
    val placeableHalfPx = with(density) { 12.dp.toPx() }
    // The handle is drawn but taps are handled by [stageGestures] (the
    // gesture layer owns the stage), so this node is pointer-transparent.
    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(0, 0) { placeable.placeRelative(0, 0) }
            }
            .graphicsLayer {
                // Center the chip ON the tap-tested point — the gesture
                // layer hit-tests this exact center, so an offset here
                // makes the drawn ✕ miss its own target.
                translationX = chipCenterX - placeableHalfPx
                translationY = chipCenterY - placeableHalfPx
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
            space.bitos.app.ui.components.BitosTextField(
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
                    space.bitos.app.ui.components.BitosTextField(
                        value = ((overlay.startMs ?: 0L) / 1000L).toString(),
                        onValueChange = { raw ->
                            onWindow((raw.toFloatOrNull()?.times(1000)?.toLong()) ?: 0L, overlay.endMs)
                        },
                        label = { Text("Start (s)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    space.bitos.app.ui.components.BitosTextField(
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
    BitosAdjustmentSlider(
        label = label,
        value = value,
        valueRange = range.first.toFloat()..range.last.toFloat(),
        valueText = value.roundToInt().toString(),
        onValueChange = onValueChange,
        step = 1f,
    )
}

/** Sticker sheet: shared [StickerCatalog] packs (web port), recents row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StickerSheetContent(recents: List<String>, onPick: (String) -> Unit) {
    var packId by remember { mutableStateOf(StickerCatalog.PACKS.first().id) }
    // No outer padding/title here — the tool-panel sheet provides both.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
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
 * Publish flow (prototype `#/create-details` → `#/create-review`): the
 * editor's "Next · post details" opens step 1 (details), "Review preflight"
 * opens step 2 (the REAL checklist), Sign & publish runs the existing
 * render → hash-verified upload → sign machine. Splits/PoW/schedule stay
 * documented wave-4 work (meme-studio-plan MST-050s).
 */
@Composable
private fun MemePostFlowScreen(
    state: MemeEditorState,
    asset: EditorAsset?,
    gifFrameCount: Int,
    hasVideo: Boolean,
    timelineSeconds: Int,
    clipCount: Int,
    coverSet: Boolean,
    coverUrl: String?,
    coverPreview: android.graphics.Bitmap?,
    coverUploading: Boolean,
    publishState: space.bitos.app.ui.feed.MemePublishUiState?,
    /** M4b remix lineage carried from the editor handoff, if any. */
    remixSeed: MemeRemixSeed? = null,
    onPublish: (
        caption: String,
        altText: String,
        cwReason: String?,
        tags: List<String>,
        license: String,
        allowZaps: Boolean,
        remixEventId: String,
        remixAuthor: String,
        remixRelays: List<String>,
        remixLabel: String,
    ) -> Unit,
    onDismiss: () -> Unit,
    onPublished: () -> Unit,
    recoverableJobs: List<space.bitos.app.ui.feed.MemePublishJob> = emptyList(),
    onJobRetry: (Int) -> Unit = {},
    onJobDiscard: (Int) -> Unit = {},
    onJobVerify: (Int) -> Boolean? = { null },
) {
    var step by remember { mutableStateOf(0) } // 0 = details, 1 = preflight, 2 = machine, 3 = queue
    var caption by remember { mutableStateOf("") }
    var altText by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(listOf<String>()) }
    var tagInput by remember { mutableStateOf("") }
    var cwOn by remember { mutableStateOf(false) }
    var cwReason by remember { mutableStateOf("Sensitive content") }
    var license by remember { mutableStateOf("CC0-1.0") }
    var allowZaps by remember { mutableStateOf(false) }
    var remixOf by remember(remixSeed) { mutableStateOf(remixSeed?.eventId ?: "") }
    var remixAuthor by remember(remixSeed) { mutableStateOf(remixSeed?.pubkey ?: "") }
    val remixRelays = remember(remixSeed) { remixSeed?.relays ?: emptyList() }
    val remixLabel = remember(remixSeed) { remixSeed?.label ?: "" }
    // M4b: a bitz handoff picks the web studio's remix default license.
    LaunchedEffect(remixSeed) {
        if (remixSeed != null && license == "CC0-1.0") license = "CC-BY-4.0"
    }
    // Allow-remix and the license chips are one `license` tag seen two ways:
    // the switch restores the last remixable code after "Nostr only".
    var lastRemixableLicense by remember { mutableStateOf("CC0-1.0") }
    LaunchedEffect(license) {
        if (license != "bitz/all-reserved") lastRemixableLicense = license
    }

    val phase = publishState?.phase ?: space.bitos.app.ui.feed.MemePublishPhase.IDLE
    val published = phase == space.bitos.app.ui.feed.MemePublishPhase.DONE
    val busy = phase == space.bitos.app.ui.feed.MemePublishPhase.UPLOADING ||
        phase == space.bitos.app.ui.feed.MemePublishPhase.PUBLISHING

    fun addTag(raw: String) {
        val tag = raw.trim().replace("#", "").lowercase()
        if (tag.isNotEmpty() && tag !in tags && tags.size < 8) tags = tags + tag
    }

    fun commitTag() {
        val typed = tagInput
        tagInput = ""
        addTag(typed)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .statusBarsPadding(),
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.xs, vertical = BitOSSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val machineRunning = step == 2 && busy
            IconButton(
                onClick = { if (step > 0) step-- else onDismiss() },
                enabled = !machineRunning,
            ) {
                Icon(
                    AppIcons.Back,
                    contentDescription = if (step == 2) "Back to preflight" else if (step == 1) "Back to details" else "Close",
                    tint = BitOSColors.textPrimary,
                )
            }
            Text(
                when (step) {
                    0 -> "Post details"; 1 -> "Preflight"; 2 -> "Publishing"; else -> "Recovery queue"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
            )
            Spacer(Modifier.weight(1f))
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BitOSSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.base),
        ) {
            if (step == 0) {
                // ── Preview + caption ────────────────────────────────────
                val captionBorder = BitOSColors.border
                Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.base)) {
                    PostPreviewThumb(
                        asset = asset,
                        isVideo = state.project.mode == MemeMode.VIDEO && hasVideo,
                        isGif = state.project.mode == MemeMode.GIF && gifFrameCount > 0,
                        timelineSeconds = timelineSeconds,
                        coverUrl = coverUrl,
                        coverPreview = coverPreview,
                        modifier = Modifier.size(width = 80.dp, height = 112.dp),
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .height(112.dp),
                    ) {
                        space.bitos.app.ui.components.BitosPlainTextField(
                            value = caption,
                            onValueChange = { caption = it.take(300) },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = BitOSColors.textPrimary),
                            singleLine = false,
                            minLines = 3,
                            maxLines = 4,
                            // Flat input (user-directed): no border, no card padding.
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = 0.dp),
                            placeholder = "Write a caption… #tag @mention",
                        )
                        Text(
                            "${caption.length} / 300",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (caption.length > 300) BitOSColors.warning else BitOSColors.textSecondary,
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }

                // ── Tags (nostr t-tags) ──────────────────────────────────
                Column(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(BitOSColors.surface)
                        .border(1.dp, BitOSColors.border, RoundedCornerShape(12.dp))
                        .padding(BitOSSpacing.base),
                    verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                ) {
                    Text("Tags · nostr t-tags", style = MaterialTheme.typography.labelMedium, color = BitOSColors.textSecondary, fontWeight = FontWeight.W600)
                    if (tags.isEmpty()) {
                        Text("No tags yet — type below", style = MaterialTheme.typography.bodySmall, color = BitOSColors.textTertiary)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.take(4).forEach { tag ->
                                AssistChip(
                                    onClick = { tags = tags - tag },
                                    label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                        Icon(AppIcons.Hash, contentDescription = null, tint = BitOSColors.textSecondary, modifier = Modifier.size(14.dp))
                        space.bitos.app.ui.components.BitosPlainTextField(
                            value = tagInput,
                            onValueChange = { tagInput = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = BitOSColors.textPrimary),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commitTag() }),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            placeholder = "Add tag and press space",
                        )
                    }
                    recentHashtagChips(tags, caption, onAddTag = ::addTag)
                }

                // ── Settings rows ────────────────────────────────────────
                Column(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(BitOSColors.surface)
                        .border(1.dp, BitOSColors.border, RoundedCornerShape(12.dp))
                        .padding(BitOSSpacing.sm),
                ) {
                    if (state.project.mode == MemeMode.VIDEO) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = BitOSSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DetailSettingsRow(
                                icon = AppIcons.Photo,
                                title = "Cover image",
                                subtitle = if (coverSet) "custom frame captured" else "first frame (capture on the editor stage)",
                                modifier = Modifier.weight(1f),
                            )
                            // Cover "Edit" drops the flow back onto the
                            // editor stage, where the video scrub row's
                            // "Set cover" lives.
                            TextButton(onClick = onDismiss) {
                                Text(
                                    "Edit",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.W600,
                                    color = BitOSColors.primary,
                                )
                            }
                        }
                        HorizontalDivider(color = BitOSColors.border)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = BitOSSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DetailSettingsRow(
                            icon = AppIcons.Globe,
                            title = "Who can watch",
                            subtitle = "Published publicly on Nostr",
                            trailing = "Everyone",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    HorizontalDivider(color = BitOSColors.border)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = BitOSSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DetailSettingsRow(
                            icon = AppIcons.Zap,
                            iconTint = BitOSColors.warning,
                            title = "Zap settings",
                            subtitle = "viewers can zap this post — off hides the zap action (advisory tag)",
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = allowZaps, onCheckedChange = { allowZaps = it })
                    }
                    HorizontalDivider(color = BitOSColors.border)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = BitOSSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DetailSettingsRow(
                            icon = AppIcons.Loop,
                            title = "Allow remix",
                            subtitle = "others duet / remix with attribution — rides the license tag",
                            modifier = Modifier.weight(1f),
                        )
                        // Off is exactly "Nostr only" (bitz/all-reserved) —
                        // the switch and the license chips stay in sync.
                        Switch(
                            checked = license != "bitz/all-reserved",
                            onCheckedChange = { allow ->
                                if (allow) {
                                    license = lastRemixableLicense
                                } else {
                                    if (license != "bitz/all-reserved") lastRemixableLicense = license
                                    license = "bitz/all-reserved"
                                }
                            },
                        )
                    }
                    HorizontalDivider(color = BitOSColors.border)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = BitOSSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DetailSettingsRow(
                            icon = AppIcons.Lock,
                            title = "Content warning",
                            subtitle = "gate the post behind a visible warning",
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = cwOn, onCheckedChange = { cwOn = it })
                    }
                    if (remixOf.isNotEmpty()) {
                        HorizontalDivider(color = BitOSColors.border)
                        Column(
                            Modifier.padding(vertical = BitOSSpacing.sm),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                "Remix · auto-attributed",
                                style = MaterialTheme.typography.labelMedium,
                                color = BitOSColors.textSecondary,
                                fontWeight = FontWeight.W600,
                            )
                            Text(
                                "source ${shortRef(remixOf)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BitOSColors.textSecondary,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                            if (remixAuthor.isNotEmpty()) {
                                Text(
                                    "author ${shortRef(remixAuthor)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BitOSColors.textSecondary,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                )
                            }
                            Text(
                                "remix + p tags are stamped on publish — keep a remixable license (CC0 / CC-BY) so the chain stays open",
                                style = MaterialTheme.typography.labelSmall,
                                color = BitOSColors.textTertiary,
                            )
                        }
                    }
                    HorizontalDivider(color = BitOSColors.border)
                    space.bitos.app.ui.components.BitosTextField(
                        value = altText,
                        onValueChange = { altText = it },
                        placeholder = "Alt text (defaults to the caption)",
                        singleLine = true,
                        compact = true,
                        textStyle = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth().padding(vertical = BitOSSpacing.sm),
                    )
                }

                // ── License ──────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                    Text("License · imeta license tag", style = MaterialTheme.typography.labelMedium, color = BitOSColors.textSecondary, fontWeight = FontWeight.W600)
                    Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                        listOf(
                            "CC0-1.0" to "CC0 (public)",
                            "CC-BY-4.0" to "CC-BY",
                            "bitz/all-reserved" to "Nostr only",
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = license == value,
                                onClick = { license = value },
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                    Text(
                        when (license) {
                            "CC-BY-4.0" -> "CC-BY — reuse allowed with attribution. The remix chain keeps your npub attached."
                            "bitz/all-reserved" -> "Nostr only — relays may mirror, but the license tag asks apps to block external reuploads."
                            else -> "CC0 — anyone can remix, reuse and commercialize. Maximum spread, maximum remixes."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textSecondary,
                    )
                }

                // Prototype splits/PoW/schedule slot: the tags exist on the
                // wire (NIP-57 zap splits, NIP-13 PoW, NIP-38 schedule) but
                // the pipeline doesn't mine or schedule yet — say so instead
                // of faking controls.
                Text(
                    "Split payments (NIP-57 zap tags), proof-of-work and scheduled publishing ship in wave 4 — this pipeline won't fake them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BitOSColors.surface)
                        .padding(BitOSSpacing.sm),
                )

                Button(
                    onClick = { step = 1 },
                    colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.primary, contentColor = androidx.compose.ui.graphics.Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Review preflight", fontWeight = FontWeight.W600)
                }
                Spacer(Modifier.height(BitOSSpacing.lg))
            } else if (step == 1) {
                // ── Preflight (prototype create-review) ──────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.base)) {
                    PostPreviewThumb(
                        asset = asset,
                        isVideo = state.project.mode == MemeMode.VIDEO && hasVideo,
                        isGif = state.project.mode == MemeMode.GIF && gifFrameCount > 0,
                        timelineSeconds = timelineSeconds,
                        coverUrl = coverUrl,
                        coverPreview = coverPreview,
                        modifier = Modifier.size(width = 64.dp, height = 96.dp),
                    )
                    Column {
                        Text(
                            when {
                                state.project.mode == MemeMode.VIDEO -> "Video · $timelineSeconds s · $clipCount clip${if (clipCount == 1) "" else "s"}"
                                state.project.mode == MemeMode.GIF -> "GIF · $gifFrameCount frames"
                                else -> "Image meme"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.W700,
                        )
                        Text(
                            "imeta dims + sha256 pinned at upload${if (cwOn) " · CW on" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = BitOSColors.textSecondary,
                        )
                        if (caption.isNotEmpty()) {
                            // Real caption preview — the same shared NIP-27
                            // tokenizer the feed cards render with, so
                            // hashtags/mentions highlight exactly as they
                            // will post.
                            space.bitos.app.ui.components.RichText(
                                tokens = remember(caption) {
                                    space.bitos.core.nostr.Nip27.tokenize(caption)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                Column(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(BitOSColors.surface)
                        .border(1.dp, BitOSColors.border, RoundedCornerShape(12.dp))
                        .padding(BitOSSpacing.sm),
                ) {
                    val captionTags = caption.split(' ').count { it.startsWith("#") && it.length > 1 }
                    PostPreflightRow(true, "Media ready", when {
                        state.project.mode == MemeMode.VIDEO -> "MP4 · $timelineSeconds s"
                        state.project.mode == MemeMode.GIF -> "GIF · $gifFrameCount frames"
                        else -> "PNG render"
                    })
                    HorizontalDivider(color = BitOSColors.border)
                    PostPreflightRow(true, "Caption + tags", "${if (caption.isEmpty()) "(none)" else "✓"} · ${captionTags + tags.size} t-tags")
                    HorizontalDivider(color = BitOSColors.border)
                    PostPreflightRow(altText.isNotEmpty() || caption.isNotEmpty(), "Alt text", if (altText.isEmpty()) "defaults to caption" else "✓ set")
                    HorizontalDivider(color = BitOSColors.border)
                    PostPreflightRow(!cwOn || cwReason.isNotEmpty(), "Content warning", if (cwOn) "gated: $cwReason" else "off")
                    HorizontalDivider(color = BitOSColors.border)
                    PostPreflightRow(true, "License", license)
                    HorizontalDivider(color = BitOSColors.border)
                    PostPreflightRow(true, "Zaps", if (allowZaps) "on · viewers can zap" else "off · advisory bitz:zaps tag")
                    HorizontalDivider(color = BitOSColors.border)
                    PostPreflightRow(true, "Audience", "Everyone · public post")
                    HorizontalDivider(color = BitOSColors.border)
                    PostPreflightRow(true, "Relays", "${space.bitos.app.data.feed.DefaultRelays.writeUrls.size} write relays · receipt machine")
                    HorizontalDivider(color = BitOSColors.border)
                    PostPreflightRow(false, "Signer", "publish fails fast with a hint if no identity is imported")
                }

                publishState?.failure?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = BitOSColors.error)
                }
                if (coverUploading) {
                    Text(
                        "Uploading selected cover before public post…",
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textSecondary,
                    )
                }
                when (phase) {
                    space.bitos.app.ui.feed.MemePublishPhase.UPLOADING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = BitOSColors.primary)
                        Text("Uploading + hash-verifying media…", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
                    }
                    space.bitos.app.ui.feed.MemePublishPhase.PUBLISHING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = BitOSColors.primary)
                        Text("Signing + publishing to relays…", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
                    }
                    space.bitos.app.ui.feed.MemePublishPhase.DONE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                        Icon(AppIcons.CheckCircle, contentDescription = null, tint = BitOSColors.success, modifier = Modifier.size(14.dp))
                        Text("Published — nothing was signed before the hash check ✓", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.W600, color = BitOSColors.success)
                    }
                    else -> {}
                }

                Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                    OutlinedButton(
                        onClick = { step = 0 },
                        enabled = !busy && !published,
                        modifier = Modifier.weight(1f),
                    ) { Text("Edit") }
                    if (published) {
                        Button(
                            onClick = onPublished,
                            colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.primary, contentColor = androidx.compose.ui.graphics.Color.White),
                            modifier = Modifier.weight(1f),
                        ) { Text("Done", fontWeight = FontWeight.W600) }
                    } else {
                        Button(
                            onClick = {
                                onPublish(
                                    caption,
                                    altText,
                                    if (cwOn) cwReason else null,
                                    tags,
                                    license,
                                    allowZaps,
                                    remixOf,
                                    remixAuthor,
                                    remixRelays,
                                    remixLabel,
                                )
                                step = 2
                            },
                            enabled = !busy && !coverUploading,
                            colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.primary, contentColor = androidx.compose.ui.graphics.Color.White),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Sign & publish", fontWeight = FontWeight.W600)
                        }
                    }
                }
                Text(
                    "Order is fixed by protocol: media uploads & hash-verifies before anything is signed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textSecondary,
                )
                Spacer(Modifier.height(BitOSSpacing.lg))
            } else if (step == 2) {
                // ── Publishing machine (prototype #/publishing) ──────────
                PublishMachineSection(
                    onOpenQueue = { step = 3 },
                    state = publishState ?: space.bitos.app.ui.feed.MemePublishUiState(),
                    mode = state.project.mode,
                    timelineSeconds = timelineSeconds,
                    clipCount = clipCount,
                    gifFrameCount = gifFrameCount,
                    onRetry = {
                        onPublish(
                            caption,
                            altText,
                            if (cwOn) cwReason else null,
                            tags,
                            license,
                            allowZaps,
                            remixOf,
                            remixAuthor,
                            remixRelays,
                            remixLabel,
                        )
                    },
                    onLater = onDismiss,
                    onPublished = onPublished,
                )
                Spacer(Modifier.height(BitOSSpacing.lg))
            } else {
                // ── Recovery queue (prototype #/queue) ───────────────────
                RecoveryQueueSection(
                    jobs = recoverableJobs,
                    busy = busy,
                    onRetry = onJobRetry,
                    onDiscard = onJobDiscard,
                    onVerify = onJobVerify,
                )
                Spacer(Modifier.height(BitOSSpacing.lg))
            }
        }
    }
}

/** Durable publish-job recovery (prototype `#/queue`): stage, the REAL
 *  integrity check (stored bytes vs the recorded digest), retry-from-media
 *  and confirmed discard. */
@Composable
private fun RecoveryQueueSection(
    jobs: List<space.bitos.app.ui.feed.MemePublishJob>,
    busy: Boolean,
    onRetry: (Int) -> Unit,
    onDiscard: (Int) -> Unit,
    onVerify: (Int) -> Boolean?,
) {
    var verifyNotes by remember { mutableStateOf(mapOf<Int, String>()) }
    var discardTarget by remember { mutableStateOf<Int?>(null) }
    val stageNames = listOf(
        "render & encode", "content hash", "upload to Blossom", "verify hash",
        "build event", "sign", "publish to relays", "relay confirms",
    )

    Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.base)) {
        if (jobs.isEmpty()) {
            Text(
                "Nothing to recover — every publish finished or was discarded. Killing the app mid-publish is safe: the attempt lands here and resumes from the stored media.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BitOSColors.surface)
                    .padding(BitOSSpacing.base),
            )
        }
        jobs.forEach { job ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BitOSColors.surface)
                    .border(1.dp, BitOSColors.border, RoundedCornerShape(12.dp))
                    .padding(BitOSSpacing.base),
                verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                    Text(
                        "job " + job.id,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = BitOSColors.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(BitOSColors.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Text(
                        job.mode.replaceFirstChar { it.uppercase() } + " · " +
                            (job.caption.ifBlank { "untitled" }.take(40)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.W600,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (job.status == "failed") {
                        Text("stalled", style = MaterialTheme.typography.labelSmall, color = BitOSColors.error, fontWeight = FontWeight.W700)
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(BitOSColors.surface),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(job.stage.coerceIn(0, 8) / 8f)
                            .clip(RoundedCornerShape(50))
                            .background(BitOSColors.primary),
                    )
                }
                Text(
                    "stuck at " + stageNames[job.stage.coerceIn(0, 7)] +
                        " · retry re-runs from the stored media · idempotent by content hash",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = BitOSColors.textSecondary,
                )
                job.lastError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = BitOSColors.error)
                }
                verifyNotes[job.id]?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { onRetry(job.id) },
                        enabled = !busy && job.retryAllowed,
                        colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.primary, contentColor = androidx.compose.ui.graphics.Color.White),
                    ) { Text("Retry now", fontWeight = FontWeight.W600) }
                    OutlinedButton(onClick = {
                        verifyNotes = verifyNotes + (job.id to when (onVerify(job.id)) {
                            null -> "Media file missing — retry will fail fast; discard the job."
                            true -> "Hash matches the original render ✓ — safe to resume."
                            false -> "Hash MISMATCH — the stored media changed; discard the job."
                        })
                    }) { Text("Verify integrity") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { discardTarget = job.id }) {
                        Icon(AppIcons.Delete, contentDescription = "Discard job " + job.id, tint = BitOSColors.error)
                    }
                }
                if (!job.retryAllowed) {
                    Text(
                        "An event was already signed — it may be live; verify on your profile before re-sending.",
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.warning,
                    )
                }
            }
        }
        Text(
            "Every background job is durable, idempotent and cancellable. Retries re-run from the stored media — the upload dedupes by content hash, and nothing signs before that hash verifies.",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
        )
    }

    discardTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { discardTarget = null },
            title = { Text("Discard job?") },
            text = { Text("The stored media and job record are deleted permanently (uploaded media stays on Blossom until its GC).") },
            confirmButton = {
                TextButton(onClick = {
                    onDiscard(target)
                    discardTarget = null
                }) { Text("Discard", color = BitOSColors.error, fontWeight = FontWeight.W600) }
            },
            dismissButton = { TextButton(onClick = { discardTarget = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Prototype `#/publishing` machine: 8 REAL pipeline stages as a stepper
 * with a progress bar, the per-attempt job chip, failure recovery
 * (Retry / Later) and the confirmed-event result. Rows track the stage
 * checkpoints the pipeline actually reports — never a timer.
 */
@Composable
private fun PublishMachineSection(
    state: space.bitos.app.ui.feed.MemePublishUiState,
    mode: MemeMode,
    timelineSeconds: Int,
    clipCount: Int,
    gifFrameCount: Int,
    onRetry: () -> Unit,
    onLater: () -> Unit,
    onPublished: () -> Unit,
    onOpenQueue: () -> Unit = {},
) {
    val order = listOf(
        space.bitos.app.ui.feed.MemePublishStage.RENDER,
        space.bitos.app.ui.feed.MemePublishStage.HASH,
        space.bitos.app.ui.feed.MemePublishStage.UPLOAD,
        space.bitos.app.ui.feed.MemePublishStage.VERIFY,
        space.bitos.app.ui.feed.MemePublishStage.BUILD,
        space.bitos.app.ui.feed.MemePublishStage.SIGN,
        space.bitos.app.ui.feed.MemePublishStage.RELAY,
        space.bitos.app.ui.feed.MemePublishStage.CONFIRM,
    )
    val busy = state.phase == space.bitos.app.ui.feed.MemePublishPhase.UPLOADING ||
        state.phase == space.bitos.app.ui.feed.MemePublishPhase.PUBLISHING
    val succeeded = state.terminal && state.confirmedRelayHosts.isNotEmpty()
    val failed = state.failure != null || (state.terminal && !succeeded)
    val currentIndex = order.indexOf(state.stage).let { if (it < 0) 0 else it }
    val doneCount = if (succeeded) 8 else currentIndex

    val renderDetail = when (mode) {
        MemeMode.VIDEO -> "MP4 · $timelineSeconds s · $clipCount clip${if (clipCount == 1) "" else "s"}"
        MemeMode.GIF -> "GIF · $gifFrameCount frames"
        MemeMode.IMAGE -> "PNG render"
    }
    val kindDetail = when (mode) {
        MemeMode.VIDEO -> "kind 22/21 · imeta + tags"
        MemeMode.GIF -> "kind 20 · imeta (image/gif)"
        MemeMode.IMAGE -> "kind 20 · imeta + tags"
    }
    val confirmDetail = if (state.confirmedRelayHosts.isNotEmpty()) {
        "OK from " + state.confirmedRelayHosts.take(3).joinToString(", ")
    } else {
        "${space.bitos.app.data.feed.DefaultRelays.writeUrls.size} write relays · awaiting first OK"
    }
    val rows = listOf(
        "Render & encode" to renderDetail,
        "Content hash" to "SHA-256 over the rendered bytes",
        "Upload to Blossom" to "blossom.primal.net · authed PUT",
        "Verify hash" to "server hash must match the local one",
        "Build event" to kindDetail,
        "Sign" to "key never leaves the device",
        "Publish to relays" to "${space.bitos.app.data.feed.DefaultRelays.writeUrls.size} write relays",
        "Relay confirms" to confirmDetail,
    )

    Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.base)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    succeeded -> "Published"
                    failed -> "Publish stalled"
                    else -> "Publishing…"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
                modifier = Modifier.weight(1f),
            )
            Text(
                "job ${state.jobId}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = BitOSColors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, BitOSColors.border, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        // Progress bar (fraction of completed stages).
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(BitOSColors.surface),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(doneCount / 8f)
                    .clip(RoundedCornerShape(50))
                    .background(if (failed) BitOSColors.error else BitOSColors.primary),
            )
        }

        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(BitOSColors.surface)
                .border(1.dp, BitOSColors.border, RoundedCornerShape(12.dp))
                .padding(BitOSSpacing.sm),
        ) {
            rows.forEachIndexed { index, (label, detail) ->
                val rowDone = succeeded || index < currentIndex
                val rowCurrent = !succeeded && !failed && index == currentIndex
                val rowFailed = failed && index == currentIndex
                Row(
                    Modifier.fillMaxWidth().padding(vertical = BitOSSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(22.dp)
                            .border(
                                1.5.dp,
                                when {
                                    rowCurrent -> BitOSColors.primary
                                    rowFailed -> BitOSColors.error
                                    else -> BitOSColors.border
                                },
                                CircleShape,
                            ),
                    ) {
                        when {
                            rowDone -> Icon(
                                AppIcons.CheckCircle,
                                contentDescription = null,
                                tint = BitOSColors.success,
                                modifier = Modifier.size(14.dp),
                            )
                            rowFailed -> Icon(
                                AppIcons.Close,
                                contentDescription = null,
                                tint = BitOSColors.error,
                                modifier = Modifier.size(11.dp),
                            )
                            else -> Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = if (rowCurrent) FontWeight.W700 else FontWeight.W500,
                                color = if (rowCurrent) BitOSColors.primary else BitOSColors.textTertiary,
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (rowDone || rowCurrent || rowFailed) BitOSColors.textPrimary else BitOSColors.textSecondary,
                        )
                        Text(
                            if (index == 7) confirmDetail else detail,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = BitOSColors.textTertiary,
                        )
                    }
                }
                if (index < rows.lastIndex) {
                    HorizontalDivider(color = BitOSColors.border, modifier = Modifier.padding(start = 30.dp))
                }
            }
        }

        if (failed) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BitOSColors.error.copy(alpha = 0.1f))
                    .padding(BitOSSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
            ) {
                Icon(AppIcons.Close, contentDescription = null, tint = BitOSColors.error, modifier = Modifier.size(13.dp).align(Alignment.Top))
                Text(
                    state.failure ?: "No relay confirmed within the window — the event may still land; retry is safe.",
                    style = MaterialTheme.typography.labelMedium,
                    color = BitOSColors.error,
                )
            }
            Text(
                "The job is recoverable — nothing was signed before the hash check, so retrying never double-publishes media.",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                OutlinedButton(onClick = onLater, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Later")
                }
                Button(
                    onClick = onRetry,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.primary, contentColor = androidx.compose.ui.graphics.Color.White),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Retry now", fontWeight = FontWeight.W600)
                }
            }
            TextButton(onClick = onOpenQueue, modifier = Modifier.fillMaxWidth()) {
                Text("Recovery queue", color = BitOSColors.primary, fontWeight = FontWeight.W600)
            }
        }

        if (succeeded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BitOSColors.success.copy(alpha = 0.1f))
                    .padding(BitOSSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
            ) {
                Icon(AppIcons.CheckCircle, contentDescription = null, tint = BitOSColors.success, modifier = Modifier.size(13.dp).align(Alignment.Top))
                val idLabel = state.eventId?.takeIf { it.length >= 12 }?.let { "event ${it.take(8)}…${it.takeLast(4)} " } ?: ""
                Text(
                    "Published — $idLabel${"confirmed on ${state.confirmedRelayHosts.size} relay"}" +
                        if (state.confirmedRelayHosts.size == 1) "." else "s.",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.W600,
                    color = BitOSColors.success,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                OutlinedButton(onClick = onOpenQueue, modifier = Modifier.weight(1f)) {
                    Text("Recovery queue")
                }
                Button(
                    onClick = onPublished,
                    colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.primary, contentColor = androidx.compose.ui.graphics.Color.White),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("View on Bitz", fontWeight = FontWeight.W600)
                }
            }
        }

        if (busy) {
            Text(
                "Order is fixed by protocol: media uploads & hash-verifies before anything is signed.",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textSecondary,
            )
        }
    }
}

/** TagsCodec `[[name,…],…]` JSON: remix lineage + explicit t-tags + license. */
/** Recently used hashtags — one-tap reuse (shared `RecentHashtags` ledger
 *  recorded on publish). Tags this post already carries drop out. */
@Composable
private fun recentHashtagChips(
    tags: List<String>,
    caption: String,
    onAddTag: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val recent = remember(tags, caption) {
        space.bitos.app.data.publish.RecentHashtagsStore.get(context).suggestions(
            exclude = tags.toSet() + space.bitos.core.publish.RecentHashtags.hashtagsIn(caption).toSet(),
        )
    }
    if (recent.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = BitOSSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        recent.forEach { tag ->
            TextButton(
                onClick = { onAddTag(tag) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(BitOSColors.background)
                    .border(1.dp, BitOSColors.border, RoundedCornerShape(50)),
            ) {
                Text(
                    "#$tag",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.W600,
                    color = BitOSColors.primary,
                )
            }
        }
    }
}

private fun postExtraTagsJson(
    remixJson: String,
    tags: List<String>,
    license: String,
    allowZaps: Boolean = true,
): String {
    val out = org.json.JSONArray()
    var lineageHasLicense = false
    if (remixJson.isNotBlank()) {
        runCatching {
            val parsed = org.json.JSONArray(remixJson)
            for (i in 0 until parsed.length()) {
                val tag = parsed.getJSONArray(i)
                // A remix's lineage supplies the validated license tag
                // (web tag order); the draft's own row would duplicate it.
                if (tag.optString(0) == "license") lineageHasLicense = true
                out.put(tag)
            }
        }
    }
    tags.forEach { out.put(org.json.JSONArray().put("t").put(it)) }
    if (!lineageHasLicense) out.put(org.json.JSONArray().put("license").put(license))
    // Zap settings off → shared advisory marker (cards hide the zap action).
    if (!allowZaps) out.put(org.json.JSONArray().put(space.bitos.core.feed.ZapPolicy.OFF_TAG).put("off"))
    return out.toString()
}

/** Post preview thumbnail per mode. */
@Composable
private fun PostPreviewThumb(
    asset: EditorAsset?,
    isVideo: Boolean,
    isGif: Boolean,
    timelineSeconds: Int,
    coverUrl: String?,
    coverPreview: android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BitOSColors.surface)
            .border(1.dp, BitOSColors.border, RoundedCornerShape(10.dp)),
    ) {
        when {
            isVideo && (coverPreview != null || coverUrl != null) -> AsyncImage(
                model = coverPreview ?: coverUrl,
                contentDescription = "Selected video cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            isVideo -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Play, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f))
            }
            isGif -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("GIF", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W700, color = BitOSColors.textSecondary)
            }
            asset != null -> AsyncImage(
                model = asset.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isVideo) {
            Icon(
                AppIcons.Play,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.Center).size(20.dp),
            )
            Text(
                "${timelineSeconds}s",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}

/** One settings row (prototype list-row): icon, title + subtitle, value. */
@Composable
private fun DetailSettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color = BitOSColors.textSecondary,
    title: String,
    subtitle: String,
    trailing: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.base)) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(BitOSColors.background)
                .padding(6.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W600, color = BitOSColors.primary)
        }
    }
}

/** One preflight checklist line: green check / amber alert + mono meta. */
@Composable
private fun PostPreflightRow(done: Boolean, label: String, meta: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = BitOSSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        Icon(
            if (done) AppIcons.CheckCircle else AppIcons.More,
            contentDescription = null,
            tint = if (done) BitOSColors.success else BitOSColors.warning,
            modifier = Modifier.size(15.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(meta, style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = BitOSColors.textTertiary)
        }
    }
}

/** Color-grade picker (MST-043): the 8 web presets, media-only, undoable. */
@Composable
private fun LooksSheetContent(
    active: String,
    onPick: (String) -> Unit,
    adjust: space.bitos.core.studio.MemeAdjust? = null,
    onAdjust: (space.bitos.core.studio.MemeAdjust) -> Unit = {},
) {
    // No outer padding/title here — the presenting sheet provides both.
    Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
        Text(
            "Applies to the media only — captions stay crisp. Undo works.",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
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
        // ── Adjust (prototype `create-edit` FX sliders): manual fine-tune
        // composed over the preset into the same burn-in matrix.
        HorizontalDivider(
            color = BitOSColors.border,
            modifier = Modifier.padding(vertical = BitOSSpacing.sm),
        )
        Text("Adjust", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W700)
        Text(
            "Fine-tune over the look — burns into the export like the preset.",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = BitOSSpacing.sm),
        )
        val current = adjust ?: space.bitos.core.studio.MemeAdjust()
        AdjustSliderRow(
            label = "Brightness",
            value = current.brightness,
            range = space.bitos.core.studio.MemeAdjust.MIN_BRIGHTNESS..
                space.bitos.core.studio.MemeAdjust.MAX_BRIGHTNESS,
        ) { value ->
            onAdjust(space.bitos.core.studio.MemeAdjust(value, current.contrast, current.saturation))
        }
        AdjustSliderRow(
            label = "Contrast",
            value = current.contrast,
            range = space.bitos.core.studio.MemeAdjust.MIN_CONTRAST..
                space.bitos.core.studio.MemeAdjust.MAX_CONTRAST,
        ) { value ->
            onAdjust(space.bitos.core.studio.MemeAdjust(current.brightness, value, current.saturation))
        }
        AdjustSliderRow(
            label = "Saturation",
            value = current.saturation,
            range = space.bitos.core.studio.MemeAdjust.MIN_SATURATION..
                space.bitos.core.studio.MemeAdjust.MAX_SATURATION,
        ) { value ->
            onAdjust(space.bitos.core.studio.MemeAdjust(current.brightness, current.contrast, value))
        }
        if (!current.isDefault) {
            TextButton(
                onClick = { onAdjust(space.bitos.core.studio.MemeAdjust()) },
                modifier = Modifier.padding(top = BitOSSpacing.xs),
            ) { Text("Reset adjust", color = BitOSColors.primary) }
        }
    }
}

/** One labeled adjust slider with the % readout (prototype FX shape). */
@Composable
private fun AdjustSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    BitosAdjustmentSlider(
        label = label,
        value = value,
        valueRange = range,
        valueText = "${(value * 100).toInt()}%",
        onValueChange = onValueChange,
        step = 0.01f,
        modifier = Modifier.padding(vertical = BitOSSpacing.xs),
    )
}

/**
 * Classic meme generator (prototype `create-edit` "Meme" hot tool):
 * TOP/BOTTOM caption pair + font slot, landing at the canonical positions
 * with the classic heavy-outline look — one undo step for the pair.
 */
@Composable
private fun MemeCaptionSheetContent(
    enabled: Boolean,
    onAdd: (top: String, bottom: String, slot: MemeFontSlot) -> Unit,
) {
    var top by remember { mutableStateOf("") }
    var bottom by remember { mutableStateOf("") }
    var slot by remember { mutableStateOf(MemeFontSlot.IMPACT) }
    val slots = listOf(
        MemeFontSlot.IMPACT to "Impact",
        MemeFontSlot.SERIF to "Comic",
        MemeFontSlot.SANS to "Modern",
    )
    // No outer padding/title here — the tool-panel sheet provides both.
    Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
        Text(
            "Classic top/bottom captions. Drag on the stage to fine-tune.",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
        )
        space.bitos.app.ui.components.BitosTextField(
            value = top,
            onValueChange = { top = it },
            label = { Text("TOP TEXT") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = BitOSSpacing.sm),
        )
        space.bitos.app.ui.components.BitosTextField(
            value = bottom,
            onValueChange = { bottom = it },
            label = { Text("BOTTOM TEXT") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = BitOSSpacing.sm),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
            modifier = Modifier.padding(bottom = BitOSSpacing.sm),
        ) {
            slots.forEach { (candidate, label) ->
                FilterChip(
                    selected = slot == candidate,
                    onClick = { slot = candidate },
                    label = { Text(label) },
                )
            }
        }
        Button(
            onClick = { onAdd(top, bottom, slot) },
            enabled = enabled && (top.isNotBlank() || bottom.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add to canvas") }
    }
}

/**
 * Mode-dependent media facts said out loud on the canvas (prototype
 * `1080×1920 · 9:16` + duration chips).
 */
private fun stageMetaChips(
    project: MemeProject,
    activeAsset: EditorAsset?,
    gifFrames: List<android.graphics.Bitmap>,
    videoClips: List<SessionClip>,
): List<String> {
    if (project.mode == MemeMode.VIDEO) {
        if (videoClips.isEmpty()) return emptyList()
        val rate = maxOf(0.01f, project.speed)
        val totalMs = videoClips.sumOf { maxOf(0L, it.endMs - it.startMs) } / rate
        val seconds = (totalMs / 1000L).toInt()
        val clips = if (videoClips.size == 1) "clip" else "clips"
        return listOf(
            String.format(java.util.Locale.US, "%02d:%02d · %d %s", seconds / 60, seconds % 60, videoClips.size, clips),
        )
    }
    if (project.mode == MemeMode.GIF) {
        if (gifFrames.isEmpty()) return emptyList()
        val delay = if (project.frameDelayMs > 0) "${project.frameDelayMs} ms" else "source delay"
        return listOf("${gifFrames.size} frames · $delay")
    }
    val asset = activeAsset ?: return emptyList()
    val ratio = ratioLabel(asset.aspect)
    return listOf(if (asset.width > 0 && asset.height > 0) "${asset.width}×${asset.height} · $ratio" else ratio)
}

/** Canonical ratio label with tolerance; exotic shapes fall to n:1. */
private fun ratioLabel(aspect: Float): String {
    val canonical = listOf(
        "9:16" to 9f / 16f, "3:4" to 3f / 4f, "1:1" to 1f, "4:5" to 4f / 5f,
        "4:3" to 4f / 3f, "16:9" to 16f / 9f,
    )
    canonical.firstOrNull { kotlin.math.abs(aspect - it.second) < 0.02f }
        ?.let { return it.first }
    return String.format(java.util.Locale.US, "%.2f:1", aspect)
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

/** Relay-friendly short form of an event id / npub for read-only rows. */
private fun shortRef(value: String): String =
    if (value.length > 16) value.take(8) + "…" + value.takeLast(4) else value

/** MST-042: lineage tags via the shared seam; "" when no source given. */
private fun remixTagsFor(
    project: space.bitos.core.studio.MemeProject,
    remixOf: String,
    remixAuthor: String,
    relays: List<String> = emptyList(),
    license: String = "",
    attributionLabel: String = "",
): String {
    if (remixOf.isBlank()) return ""
    val relaysJson = org.json.JSONArray(relays).toString()
    return space.bitos.core.bridge.BusinessCoreBridge().memeRemixTagsFor(
        space.bitos.core.studio.MemeProjectContract.encode(project),
        remixOf, remixAuthor, relaysJson, license, attributionLabel,
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
            speed = space.bitos.core.studio.MemeProjectContract.clampSpeed(clip.speed),
        )
        acc += (((clip.endMs - clip.startMs).coerceAtLeast(0L)) /
            space.bitos.core.studio.MemeProjectContract.clampSpeed(clip.speed)).toLong()
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
    onApplyTemplate: (String) -> Unit = {},
) {
    var bucketId by remember { mutableStateOf(space.bitos.core.studio.SfxSynth.BUCKETS.first().id) }
    var search by remember { mutableStateOf("") }
    val bucket = space.bitos.core.studio.SfxSynth.BUCKETS.first { it.id == bucketId }
    val searching = search.isNotBlank()
    // Case-insensitive label search (web filterEntries parity): a query
    // flattens the buckets; empty keeps the bucket view.
    val entries: List<Pair<String, String>> = if (searching) {
        space.bitos.core.studio.SfxSynth.BUCKETS.flatMap { b -> b.sfx.map { it to space.bitos.core.studio.SfxSynth.labelOf(it) } }
            .filter { it.second.lowercase().contains(search.trim().lowercase()) }
    } else {
        bucket.sfx.map { it to space.bitos.core.studio.SfxSynth.labelOf(it) }
    }
    Column(Modifier.padding(BitOSSpacing.base)) {
        Text("Sound effects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W700)
        Text(
            "Tap to preview · Add cue schedules it at ${(positionMs / 1000.0).let { "%.1f".format(it) }}s (≤16, fully synthesized — zero audio assets)",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = BitOSSpacing.sm),
        )
        space.bitos.app.ui.components.BitosTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = "Search sounds",
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = BitOSSpacing.sm),
        )
        if (!searching) Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
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
            rowItems(entries, key = { it.first }) { (id, label) ->
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
                        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W600)
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
        if (!searching) {
            Spacer(Modifier.height(BitOSSpacing.sm))
            Text("Templates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W700)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                rowItems(space.bitos.core.studio.SfxTemplates.ALL, key = { it.id }) { template ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BitOSColors.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.border),
                        modifier = Modifier.clickable(onClickLabel = "Apply ${template.label}") { onApplyTemplate(template.id) },
                    ) {
                        Column(
                            Modifier.padding(horizontal = BitOSSpacing.sm, vertical = BitOSSpacing.xs),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(template.emoji, fontSize = 18.sp)
                            Text(template.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W600)
                            Text(
                                "${template.cues.size} cues",
                                style = MaterialTheme.typography.labelSmall,
                                color = BitOSColors.textSecondary,
                            )
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
                        "${space.bitos.core.studio.SfxSynth.labelOf(cue.sfx)} @ ${(cue.atMs / 1000.0).let { s -> "%.1f".format(s) }}s",
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
            "$clipLabel · volume applies to preview and export",
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
        BitosSlider(
            value = value.coerceIn(0f, 1f),
            onValueChange = { value = it },
            valueRange = 0f..1f,
        )
        Button(
            onClick = { onApply(value.coerceIn(0f, 1f)) },
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
    outcome: ExportOutcome? = null,
    onDone: () -> Unit,
) {
    val succeeded = outcome?.let { it is ExportOutcome.Success || it is ExportOutcome.SuccessAdjusted }
        ?: (status?.startsWith("Saved") == true)
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
 * Layers sheet (stack management): every overlay top-first — the first row
 * paints in front — with its badge, select, delete and ±1 stack moves
 * (the shared ReorderOverlay command, one undo step each). "Insert
 * image…" opens the picker; new layers land on top, same as video mode.
 * GIF inserts paint their first frame (V1 semantics, said out loud).
 */
@Composable
private fun LayersSheetContent(
    project: MemeProject,
    assets: List<EditorAsset>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (id: String, delta: Int) -> Unit,
    onInsert: () -> Unit,
) {
    // Paint order = list order, so the display runs reversed: row 0 is the
    // front; "up" moves an overlay toward the front (+1 paint slot).
    val stack = project.overlays.asReversed()
    Column(Modifier.padding(BitOSSpacing.base)) {
        Text("Layers", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.W700)
        Text(
            "Top of the list paints in front. New layers land on top — restack with the arrows " +
                "(image inserts ≤${MemeProjectContract.MAX_IMAGE_LAYERS}; GIFs paint their first frame).",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = BitOSSpacing.sm),
        )
        if (stack.isEmpty()) {
            Text(
                "No layers yet — add text, stickers or image sources to stack them.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
                modifier = Modifier.padding(bottom = BitOSSpacing.sm),
            )
        }
        stack.forEachIndexed { position, layer ->
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
                    when {
                        layer.kind == MemeOverlayKind.IMAGE && asset != null -> coil.compose.AsyncImage(
                            model = asset.uri,
                            contentDescription = "Layer ${layer.id}",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        layer.kind == MemeOverlayKind.IMAGE -> Icon(
                            AppIcons.Photo,
                            contentDescription = null,
                            tint = BitOSColors.textTertiary,
                            modifier = Modifier.padding(10.dp).fillMaxSize(),
                        )
                        layer.kind == MemeOverlayKind.STICKER -> SolarStudioIconImage(
                            SolarStudioIcon.Sticker,
                            contentDescription = null,
                            tint = BitOSColors.textTertiary,
                            modifier = Modifier.padding(10.dp).fillMaxSize(),
                        )
                        else -> Icon(
                            AppIcons.TextGlyph,
                            contentDescription = null,
                            tint = BitOSColors.textTertiary,
                            modifier = Modifier.padding(10.dp).fillMaxSize(),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when (layer.kind) {
                            MemeOverlayKind.IMAGE -> "Layer ${layer.assetId ?: "?"}"
                            MemeOverlayKind.STICKER -> "Sticker"
                            MemeOverlayKind.TEXT -> "Text"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.W600,
                    )
                    Text(
                        when {
                            layer.kind == MemeOverlayKind.IMAGE && (layer.startMs != null || layer.endMs != null) ->
                                "${suiteClock(layer.startMs ?: 0)} – ${suiteClock(layer.endMs ?: 0)}"
                            layer.kind == MemeOverlayKind.IMAGE -> "always visible"
                            layer.kind == MemeOverlayKind.TEXT ->
                                layer.text.ifEmpty { "empty caption" }
                            else -> "tap to place"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    AppIcons.NudgeUp,
                    contentDescription = null,
                    tint = if (position > 0) BitOSColors.textPrimary else BitOSColors.textTertiary,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(enabled = position > 0) { onMove(layer.id, 1) }
                        .semantics { contentDescription = "Move to front one slot" },
                )
                Icon(
                    AppIcons.NudgeDown,
                    contentDescription = null,
                    tint = if (position < stack.lastIndex) BitOSColors.textPrimary else BitOSColors.textTertiary,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(enabled = position < stack.lastIndex) { onMove(layer.id, -1) }
                        .semantics { contentDescription = "Move to back one slot" },
                )
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
        BitosSlider(
            value = start,
            onValueChange = {
                start = it.coerceIn(0f, end - 200f)
            },
            valueRange = 0f..durationMs.toFloat(),
        )
        Text("End ${suiteClock(end.toLong())}", style = MaterialTheme.typography.labelMedium)
        BitosSlider(
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
