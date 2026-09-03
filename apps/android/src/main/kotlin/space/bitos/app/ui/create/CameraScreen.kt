package space.bitos.app.ui.create

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.SolarCaptureIcon
import space.bitos.app.ui.theme.SolarCaptureIconImage
import space.bitos.core.model.Blossom

/**
 * Camera capture surface (CAP-002, spec §3.19): CameraX preview + bounded
 * recording behind the reference record-screen chrome
 * (`docs/ui/app-app-04-create-camera-editor.html` — grid guides, REC badge,
 * lens zoom rail, torch/self-timer, session takes strip, morphing record
 * button, permission-declined state).
 *
 * The camera layer stays deliberately thin: takes live only in this screen's
 * state; the one a publisher chooses is handed to the caller as
 * (bytes, mime) and flows into the fully tested publish pipeline. No media
 * logic lives here.
 */
private const val RECORD_CAP_MS = 3 * 60 * 1000L
private const val MAX_PENDING_TAKES = 5
private const val MAX_PENDING_BYTES = 96L * 1024 * 1024
private val SCRIM_BLACK = Color(0xD9000000)
private val CONTROL_BG = Color(0x66000000)
private val CONTROL_STROKE = Color(0x40FFFFFF)

/** One finished take waiting in the session strip (CAP-002). */
private class PendingTake(
    val id: Long,
    val bytes: ByteArray,
    val mime: String,
    val durationMs: Long,
    val thumbnail: ImageBitmap?,
)

private enum class TimerMode(val label: String, val seconds: Int) {
    OFF("Off", 0),
    S3("3s", 3),
    S10("10s", 10),
}

@Composable
fun CameraScreen(
    onCaptured: (ByteArray, String) -> Unit,
    onImport: () -> Unit,
    /** Quick MEM: the latest take (bytes, mime), or null to open the editor empty. */
    onOpenMeme: (List<Pair<ByteArray, String>>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var requestPending by remember { mutableStateOf(!hasPermission) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        requestPending = false
    }
    LaunchedEffect(Unit) {
        // Only ever asked from the record screen — never at launch (spec).
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ── Capture state ────────────────────────────────────────────────
    val engine = remember { CameraEngine() }
    val previewView = remember { PreviewView(context) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var torchOn by remember { mutableStateOf(false) }
    var flashAvailable by remember { mutableStateOf(false) }
    var gridOn by remember { mutableStateOf(true) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var timerMode by remember { mutableStateOf(TimerMode.OFF) }
    var countdown by remember { mutableIntStateOf(-1) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var finalizing by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var takes by remember { mutableStateOf(listOf<PendingTake>()) }
    var previewId by remember { mutableStateOf<Long?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    /** M5 take-native handoff: EVERY recorded take enters the studio as
     * its own timeline clip (strip order) — no merge re-encode, per-take
     * trim/effects stay possible. */
    fun openMemeWithAllTakes() {
        if (takes.isEmpty()) return
        onOpenMeme(takes.map { it.bytes to it.mime })
    }

    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            engine.release()
        }
    }

    fun showHint(message: String) {
        hint = message
    }

    fun beginRecording() {
        if (finalizing) {
            showHint("Finishing previous take…")
            return
        }
        if (takes.size >= MAX_PENDING_TAKES) {
            showHint("Take limit reached — review or delete before recording another")
            return
        }
        val active = engine.startTake(
            context = context,
            onFinalize = { uri, durationMs ->
                engine.readTakeAsync(context, uri, durationMs) { take ->
                    finalizing = false
                    if (take != null) {
                        val total = takes.sumOf { it.bytes.size } + take.bytes.size
                        if (total > MAX_PENDING_BYTES) {
                            showHint("Take exceeds the session buffer — use or delete earlier takes")
                        } else {
                            takes = takes + take
                        }
                    } else {
                        showHint("Take failed or exceeded the ${Blossom.MAX_FILE_BYTES / (1024 * 1024)}MB publish cap")
                    }
                }
            },
        )
        if (active != null) {
            finalizing = true
            recording = active
            elapsedMs = 0
        } else {
            showHint("Camera is not ready yet — try again in a moment")
        }
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
    }

    fun pressRecord() {
        val active = recording
        if (active != null) {
            stopRecording()
        } else if (timerMode != TimerMode.OFF) {
            countdown = timerMode.seconds
        } else {
            beginRecording()
        }
    }

    // Bind (and rebind on flip); settings reapply inside the engine.
    LaunchedEffect(lensFacing, previewView) {
        engine.bind(context, lifecycleOwner, previewView, lensFacing)
        flashAvailable = engine.hasFlashUnit()
        engine.applyTorch(torchOn)
        engine.applyZoom(zoomRatio)
    }
    LaunchedEffect(torchOn) { engine.applyTorch(torchOn) }
    LaunchedEffect(zoomRatio) { engine.applyZoom(zoomRatio) }

    // Elapsed ticker + hard duration cap.
    LaunchedEffect(recording) {
        if (recording == null) return@LaunchedEffect
        val start = android.os.SystemClock.elapsedRealtime()
        while (true) {
            elapsedMs = android.os.SystemClock.elapsedRealtime() - start
            if (elapsedMs >= RECORD_CAP_MS) {
                stopRecording()
                break
            }
            delay(200)
        }
    }

    // Self-timer countdown → auto-start.
    LaunchedEffect(countdown) {
        if (countdown <= 0) return@LaunchedEffect
        delay(1_000)
        countdown -= 1
        if (countdown == 0) {
            countdown = -1
            beginRecording()
        }
    }

    // Transient hint auto-clear.
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(2_500)
            hint = null
        }
    }

    // (M5: the CAP→MEM TakeMerger concat is gone — takes pass through
    // unmerged and become individual timeline clips in the studio.)

    val previewing = takes.firstOrNull { it.id == previewId }
    if (previewing != null) {
        VideoPreviewScreen(
            bytes = previewing.bytes,
            mimeType = previewing.mime,
            onUse = { bytes, mime -> onCaptured(bytes, mime) },
            onRetake = {
                takes = takes.filterNot { it.id == previewing.id }
                previewId = null
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasPermission && !requestPending) {
            PermissionDeclinedContent(
                onOpenSettings = { openSystemSettings(context) },
                onImport = onImport,
                onRetry = {
                    requestPending = true
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
            )
        } else if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (gridOn) {
                Box(Modifier.fillMaxSize().gridGuides())
            }

            // Top controls: close · torch · grid · self-timer.
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleControl(
                    contentDescription = "Close camera",
                    onClick = { if (takes.isEmpty()) onCancel() else confirmDiscard = true },
                ) {
                    Icon(imageVector = AppIcons.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (flashAvailable) {
                        CircleControl(
                            contentDescription = if (torchOn) "Torch on" else "Torch off",
                            onClick = { torchOn = !torchOn },
                        ) {
                            SolarCaptureIconImage(
                                icon = SolarCaptureIcon.Torch,
                                contentDescription = null,
                                tint = if (torchOn) BitOSColors.warning else Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    CircleControl(
                        contentDescription = if (gridOn) "Grid guides on" else "Grid guides off",
                        onClick = { gridOn = !gridOn },
                    ) {
                        Icon(
                            imageVector = AppIcons.AppsGrid,
                            contentDescription = null,
                            tint = if (gridOn) BitOSColors.primary else Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    TimerChip(mode = timerMode) { timerMode = it }
                }
            }

            if (recording != null) {
                RecBadge(elapsedMs = elapsedMs, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))
            }

            ZoomRail(
                active = zoomRatio,
                onSelect = { zoomRatio = it },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            )

            if (countdown > 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        countdown.toString(),
                        color = Color.White,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, SCRIM_BLACK)))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
            ) {
                hint?.let { message ->
                    Text(
                        message,
                        color = BitOSColors.warningText,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                TakesStrip(
                    takes = takes,
                    onTapTake = { previewId = it.id },
                    onDeleteTake = { id -> takes = takes.filterNot { it.id == id } },
                    onAddTake = { if (recording == null) pressRecord() },
                )
                Spacer(Modifier.height(16.dp))
                CaptureControls(
                    isRecording = recording != null,
                    onRecordToggle = { pressRecord() },
                    onImport = onImport,
                    onOpenMeme = ::openMemeWithAllTakes,
                )
                Spacer(Modifier.height(16.dp))
                CameraBottomActions(
                    canEditTakes = takes.isNotEmpty(),
                    onFlip = {
                        if (recording != null) stopRecording()
                        torchOn = false
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                    onEditTakes = { takes.lastOrNull()?.let { previewId = it.id } },
                )
            }
        }

        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text("Discard takes?") },
                text = { Text("${takes.size} recorded take(s) in this session would be lost.") },
                confirmButton = {
                    TextButton(onClick = onCancel) { Text("Discard", color = BitOSColors.error) }
                },
                dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
            )
        }
    }
}

// ── Chrome pieces ──────────────────────────────────────────────────────

/** Translucent 48 dp circular control over the preview (reference top bar). */
@Composable
private fun CircleControl(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(CONTROL_BG)
            .border(1.dp, CONTROL_STROKE, CircleShape)
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Self-timer chip: cycles off → 3 s → 10 s (reference timer toast). */
@Composable
private fun TimerChip(mode: TimerMode, onCycle: (TimerMode) -> Unit) {
    val next = when (mode) { TimerMode.OFF -> TimerMode.S3; TimerMode.S3 -> TimerMode.S10; TimerMode.S10 -> TimerMode.OFF }
    Row(
        Modifier
            .clip(RoundedCornerShape(BitOSRadiusPill))
            .background(CONTROL_BG)
            .border(1.dp, CONTROL_STROKE, RoundedCornerShape(BitOSRadiusPill))
            .clickable(onClickLabel = "Self-timer ${mode.label}") { onCycle(next) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SolarCaptureIconImage(
            icon = SolarCaptureIcon.Timer,
            contentDescription = null,
            tint = if (mode != TimerMode.OFF) BitOSColors.primary else Color.White,
            modifier = Modifier.size(16.dp),
        )
        if (mode != TimerMode.OFF) {
            Text(mode.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** REC badge: blinking dot + monospace elapsed clock (reference recBadge). */
@Composable
private fun RecBadge(elapsedMs: Long, modifier: Modifier = Modifier) {
    val blink = rememberInfiniteTransition(label = "rec-blink")
    val dotAlpha by blink.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(550, easing = LinearEasing), RepeatMode.Reverse),
        label = "rec-dot",
    )
    Row(
        modifier
            .clip(RoundedCornerShape(BitOSRadiusPill))
            .background(Color(0x80000000))
            .border(1.dp, CONTROL_STROKE, RoundedCornerShape(BitOSRadiusPill))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(BitOSColors.error),
        )
        Text(
            formatClock(elapsedMs),
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

/** Lens zoom rail on the right edge: .5× / 1× / 2× (reference lens switch). */
@Composable
private fun ZoomRail(active: Float, onSelect: (Float) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(0.5f, 1f, 2f).forEach { ratio ->
            val label = if (ratio == 0.5f) ".5×" else if (ratio == 1f) "1×" else "2×"
            val isActive = active == ratio
            Box(
                Modifier
                    .clip(RoundedCornerShape(BitOSRadiusPill))
                    .background(if (isActive) BitOSColors.primary else CONTROL_BG)
                    .border(1.dp, if (isActive) BitOSColors.primary else CONTROL_STROKE, RoundedCornerShape(BitOSRadiusPill))
                    .clickable(onClickLabel = "Zoom $label") { onSelect(ratio) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    label,
                    color = if (isActive) Color(0xFF1A1000) else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Session takes strip: thumbnails with delete, dashed "+" and summary. */
@Composable
private fun TakesStrip(
    takes: List<PendingTake>,
    onTapTake: (PendingTake) -> Unit,
    onDeleteTake: (Long) -> Unit,
    onAddTake: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        takes.forEachIndexed { index, take ->
            Box(
                Modifier
                    .size(width = 56.dp, height = 44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x332858C8))
                    .clickable(onClickLabel = "Review take ${index + 1}") { onTapTake(take) },
            ) {
                take.thumbnail?.let { thumb ->
                    Image(
                        bitmap = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    (index + 1).toString(),
                    color = if (take.thumbnail != null) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
                Text(
                    formatClock(take.durationMs),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .background(Color(0x88000000), RoundedCornerShape(2.dp)),
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xB3000000))
                        .clickable(onClickLabel = "Delete take ${index + 1}") { onDeleteTake(take.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = AppIcons.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                }
            }
        }
        Box(
            Modifier
                .size(width = 40.dp, height = 44.dp)
                .dashedBorder(Color(0x66FFFFFF))
                .clickable(onClickLabel = "Record the next take", onClick = onAddTake),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp)
        }
        if (takes.isNotEmpty()) {
            Text(
                "${takes.size} take${if (takes.size == 1) "" else "s"} · ${formatClock(takes.sumOf { it.durationMs })}",
                color = Color.White.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
        }
    }
}

/** Import · record · MEM row (reference capture controls). */
@Composable
private fun CaptureControls(
    isRecording: Boolean,
    onRecordToggle: () -> Unit,
    onImport: () -> Unit,
    onOpenMeme: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2858C8), Color(0xFF06102A))))
                .border(1.dp, CONTROL_STROKE, RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Import from library", onClick = onImport),
            contentAlignment = Alignment.Center,
        ) {
            SolarCaptureIconImage(
                icon = SolarCaptureIcon.Gallery,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        RecordButton(isRecording = isRecording, onToggle = onRecordToggle)
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CONTROL_BG)
                .border(1.dp, CONTROL_STROKE, RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Open the MEM expert editor with the latest take", onClick = onOpenMeme),
            contentAlignment = Alignment.Center,
        ) {
            Text("MEM", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 76 dp capture button: white ring with a red core that morphs into a
 * rounded stop-square while recording (reference recBtn).
 */
@Composable
private fun RecordButton(isRecording: Boolean, onToggle: () -> Unit) {
    val innerSize by animateDpAsState(if (isRecording) 26.dp else 58.dp, tween(200), label = "rec-inner-size")
    val innerCorner by animateDpAsState(if (isRecording) 6.dp else 29.dp, tween(200), label = "rec-inner-corner")
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
            .clickable(onClickLabel = if (isRecording) "Stop recording" else "Start recording", onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(innerCorner))
                .background(BitOSColors.error),
        )
    }
}

/** Flip · Edit takes · duration cap row (reference bottom actions). */
@Composable
private fun CameraBottomActions(
    canEditTakes: Boolean,
    onFlip: () -> Unit,
    onEditTakes: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
    ) {
        BottomAction(
            label = "Flip",
            onClickLabel = "Flip lens",
            onClick = onFlip,
        ) {
            SolarCaptureIconImage(
                icon = SolarCaptureIcon.CameraRotate,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(17.dp),
            )
        }
        BottomAction(
            label = "Edit takes",
            onClickLabel = "Edit takes",
            onClick = onEditTakes,
            enabled = canEditTakes,
        ) {
            SolarCaptureIconImage(
                icon = SolarCaptureIcon.Pen,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (canEditTakes) 0.7f else 0.3f),
                modifier = Modifier.size(17.dp),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SolarCaptureIconImage(
                icon = SolarCaptureIcon.ClockCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(17.dp),
            )
            Text("3:00 cap", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BottomAction(
    label: String,
    onClickLabel: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        icon()
        Text(
            label,
            color = Color.White.copy(alpha = if (enabled) 0.7f else 0.3f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Permission-declined state (reference scr-perm). */
@Composable
private fun PermissionDeclinedContent(
    onOpenSettings: () -> Unit,
    onImport: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(BitOSColors.error.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            SolarCaptureIconImage(
                icon = SolarCaptureIcon.Camera,
                contentDescription = null,
                tint = BitOSColors.error,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("Camera permission declined", style = MaterialTheme.typography.headlineSmall, color = BitOSColors.textPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "Recording needs the camera, and we only ever ask from the record screen — never at launch. " +
                "You can still create with media you already have.",
            style = MaterialTheme.typography.bodySmall,
            color = BitOSColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(BitOSRadiusPill))
                .background(BitOSColors.primary)
                .clickable(onClickLabel = "Open system settings", onClick = onOpenSettings)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("Open system settings", color = Color(0xFF1A1000), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(BitOSRadiusPill))
                .border(1.dp, BitOSColors.border, RoundedCornerShape(BitOSRadiusPill))
                .clickable(onClickLabel = "Import from library instead", onClick = onImport)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("Import from library instead", color = BitOSColors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Try the permission request again",
            color = BitOSColors.textTertiary,
            fontSize = 12.sp,
            modifier = Modifier
                .clickable(onClickLabel = "Try the permission request again", onClick = onRetry),
        )
    }
}

// ── Helpers ────────────────────────────────────────────────────────────

/** mm:ss clock for badges, take durations and summaries. */
private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private val BitOSRadiusPill = 999.dp

private fun openSystemSettings(context: Context) {
    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    runCatching { context.startActivity(intent) }
}

/** Rule-of-thirds guides (reference grid overlay). */
private fun Modifier.gridGuides(): Modifier = drawBehind {
    val guide = Color.White.copy(alpha = 0.07f)
    val width = 1.dp.toPx()
    for (i in 1..2) {
        val x = size.width * i / 3
        drawLine(color = guide, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = width)
        val y = size.height * i / 3
        drawLine(color = guide, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = width)
    }
}

/** Dashed border for the "+" next-take slot (reference dashed thumb). */
private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    val radius = 6.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))),
    )
}

/**
 * Owns the CameraX camera/recorder pair for the screen's lifetime — no
 * global mutable state, and finalize work continues off the main thread
 * even while the composable leaves composition.
 */
private class CameraEngine {
    var camera: Camera? = null
        private set
    var recorder: Recorder? = null
        private set
    private var desiredTorch = false
    private var desiredZoom = 1f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun hasFlashUnit(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true

    fun bind(
        context: Context,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        previewView: PreviewView,
        facing: Int,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val newRecorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            recorder = newRecorder
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.Builder().requireLensFacing(facing).build(),
                    preview,
                    VideoCapture.withOutput(newRecorder),
                )
                applyTorch(desiredTorch)
                applyZoom(desiredZoom)
            } catch (_: Exception) {
                // Camera unavailable (emulator without camera, device busy):
                // the import path remains available; surface nothing here.
                camera = null
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun applyTorch(on: Boolean) {
        desiredTorch = on
        camera?.cameraControl?.enableTorch(on)
    }

    fun applyZoom(ratio: Float) {
        desiredZoom = ratio
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    /** Starts a bounded take; the MediaStore row is removed after reading. */
    fun startTake(
        context: Context,
        onFinalize: (Uri, Long) -> Unit,
    ): Recording? {
        val activeRecorder = recorder ?: return null
        val contentValues = android.content.ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "bitos_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        val output = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        ).setContentValues(contentValues).build()

        val pending = activeRecorder.prepareRecording(context, output)
        return try {
            pending.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize && event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                    val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                    onFinalize(event.outputResults.outputUri, durationMs)
                } else if (event is VideoRecordEvent.Finalize) {
                    // Errored finalize: surface as a failed read so the
                    // caller's finalizing flag clears with an error hint.
                    onFinalize(Uri.EMPTY, -1)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads a finished take on IO (bounded by the publish cap), grabs a
     * small poster frame, deletes the MediaStore row and hands a
     * [PendingTake] back on the main thread.
     */
    fun readTakeAsync(
        context: Context,
        uri: Uri,
        durationMs: Long,
        onDone: (PendingTake?) -> Unit,
    ) {
        scope.launch {
            val result = runCatching {
                if (uri == Uri.EMPTY) return@runCatching null
                val resolver = context.contentResolver
                val thumbnail = extractThumbnail(resolver, uri)
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        total += read
                        if (total > Blossom.MAX_FILE_BYTES) return@runCatching null
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                } ?: return@runCatching null
                if (bytes.isEmpty()) return@runCatching null
                resolver.delete(uri, null, null)
                PendingTake(
                    id = System.nanoTime(),
                    bytes = bytes,
                    mime = "video/mp4",
                    durationMs = durationMs.coerceAtLeast(0),
                    thumbnail = thumbnail,
                )
            }.getOrNull()
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    private fun extractThumbnail(resolver: android.content.ContentResolver, uri: Uri): ImageBitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            resolver.openFileDescriptor(uri, "r")?.use { fd -> retriever.setDataSource(fd.fileDescriptor) }
                ?: return@runCatching null
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@runCatching null
            val height = 96
            val width = (frame.width * height / frame.height.coerceAtLeast(1)).coerceAtLeast(1)
            android.graphics.Bitmap.createScaledBitmap(frame, width, height, true).asImageBitmap()
        } finally {
            retriever.release()
        }
    }.getOrNull()

    fun release() {
        scope.cancel()
    }
}
