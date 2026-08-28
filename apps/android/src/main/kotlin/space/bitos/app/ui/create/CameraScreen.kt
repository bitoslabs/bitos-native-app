package space.bitos.app.ui.create

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing

/**
 * Camera capture surface (CAP-002): CameraX preview + bounded recording.
 * The camera layer is deliberately thin — a finished take is handed to the
 * caller as (bytes, mime) and flows into the fully tested publish pipeline;
 * no media logic lives here.
 */
@Composable
fun CameraScreen(
    onCaptured: (ByteArray, String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var previewBytes by remember { mutableStateOf<ByteArray?>(null) }
    var previewMime by remember { mutableStateOf("video/mp4") }
    val previewView = remember { PreviewView(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> hasPermission = grants[Manifest.permission.CAMERA] == true }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    DisposableEffect(Unit) {
        onDispose { recording?.stop() }
    }

    previewBytes?.let { bytes ->
        VideoPreviewScreen(
            bytes = bytes,
            mimeType = previewMime,
            onUse = { usedBytes, usedMime ->
                onCaptured(usedBytes, usedMime)
                previewBytes = null
            },
            onRetake = { previewBytes = null },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasPermission) {
            Column(
                Modifier.fillMaxSize().padding(BitOSSpacing.screen),
                verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
            ) {
                Text("Camera access needed", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(
                    "Recording requires the camera permission. You can still import videos from your library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xB3F8F8FF),
                )
                Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) {
                    Text("Grant camera access")
                }
                Button(onClick = onCancel) { Text("Back") }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )
                // Bind the camera when the preview enters composition.
                LaunchedEffect(previewView) {
                    bindCamera(context, lifecycleOwner, previewView) { activeRecording -> recording = activeRecording }
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(BitOSSpacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RecordButton(
                        isRecording = recording != null,
                        onToggle = { toggle ->
                            val active = recording
                            if (active != null) {
                                active.stop()
                                recording = null
                            } else {
                                startRecording(context, lifecycleOwner, previewView, onCaptured, { bytes, mime ->
                                    previewBytes = bytes
                                    previewMime = mime
                                }) { activeRecording ->
                                    recording = activeRecording
                                }
                            }
                            toggle
                        },
                    )
                    Text(
                        if (recording != null) "Tap to stop" else "Tap to record",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xB3F8F8FF),
                    )
                    Button(onClick = onCancel, modifier = Modifier.padding(top = BitOSSpacing.sm)) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, onToggle: (Unit) -> Unit) {
    androidx.compose.material3.Surface(
        onClick = { onToggle(Unit) },
        shape = CircleShape,
        color = if (isRecording) BitOSColors.error else BitOSColors.primary,
        modifier = Modifier.size(72.dp),
    ) {}
}

/** Binds preview + video capture once; the recorder lives in a holder. */
private var boundRecorder: Recorder? = null

private fun bindCamera(
    context: android.content.Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    onRecording: (Recording?) -> Unit,
) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
        val provider = providerFuture.get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        boundRecorder = recorder
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                VideoCapture.withOutput(recorder),
            )
        } catch (_: Exception) {
            // Camera unavailable (emulator without camera, device busy): the
            // import path remains available; surface nothing here.
            onRecording(null)
        }
    }, ContextCompat.getMainExecutor(context))
}

private fun startRecording(
    context: android.content.Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    onCaptured: (ByteArray, String) -> Unit,
    onPreviewReady: (ByteArray, String) -> Unit,
    onRecording: (Recording) -> Unit,
) {
    val recorder = boundRecorder ?: return
    val name = "bitos_${System.currentTimeMillis()}.mp4"
    val contentValues = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
        put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
    }
    val output = MediaStoreOutputOptions.Builder(
        context.contentResolver,
        android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
    ).setContentValues(contentValues).build()

    val pending = recorder.prepareRecording(context, output)
    val active = try {
        pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize && event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                val uri = event.outputResults.outputUri
                readTake(context, uri, onCaptured, onPreviewReady)
            }
        }
    } catch (_: Exception) {
        null
    }
    active?.let(onRecording)
}

private fun readTake(
    context: android.content.Context,
    uri: android.net.Uri,
    onCaptured: (ByteArray, String) -> Unit,
    onPreviewReady: (ByteArray, String) -> Unit,
) {
    kotlinx.coroutines.runBlocking {
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        total += read
                        if (total > space.bitos.core.model.Blossom.MAX_FILE_BYTES) return@withContext null
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
            }.getOrNull()
        }
        if (bytes != null) {
            onPreviewReady(bytes, "video/mp4")
        }
    }
}
