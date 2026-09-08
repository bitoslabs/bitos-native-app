package space.bitos.app.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Effects
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Recorded-take preview with trim (CAP-003/004): playback + start/end trim
 * sliders plus an optional left-to-right mirror. "Use this" exports the
 * requested trim/mirror combination, so the visible choice is the clip that
 * reaches the publishing pipeline. Playback always uses a cache-file URI: encoding a
 * camera-original clip as a Base64 data URI duplicates it in the app heap.
 */
@Composable
fun VideoPreviewScreen(
    bytes: ByteArray,
    mimeType: String,
    initialMirrored: Boolean = false,
    onUse: (ByteArray, String) -> Unit,
    onRetake: () -> Unit,
    /** Return to the session strip without deleting this take. */
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // Media3 reads the source from disk. A data: URI needs a Base64 string
    // (~4/3 the source size) in addition to the already-held byte array and
    // crashes on normal camera clips under Android's heap limit.
    val sourceFile = remember(bytes) {
        java.io.File(context.cacheDir, "bitos-preview-${System.nanoTime()}.mp4").apply {
            writeBytes(bytes)
        }
    }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(sourceFile)))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }

    var durationMs by remember { mutableLongStateOf(0L) }
    var trimStartMs by remember { mutableLongStateOf(0L) }
    var trimEndMs by remember { mutableLongStateOf(0L) }
    var hasTrimmed by remember { mutableStateOf(false) }
    var mirrored by remember(initialMirrored) { mutableStateOf(initialMirrored) }
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Read duration once the player is prepared.
    DisposableEffect(player, sourceFile) {
        onDispose {
            player.release()
            sourceFile.delete()
        }
    }
    androidx.compose.runtime.LaunchedEffect(player) {
        // Wait for the duration to become available.
        kotlinx.coroutines.withTimeoutOrNull(5_000) {
            while (player.duration <= 0) kotlinx.coroutines.delay(100)
        }
        durationMs = player.duration.coerceAtLeast(0)
        trimStartMs = 0
        trimEndMs = durationMs
    }

    if (exporting) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Trimming…", style = MaterialTheme.typography.titleMedium, color = Color.White)
                androidx.compose.material3.CircularProgressIndicator(
                    color = BitOSColors.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(top = BitOSSpacing.md).fillMaxWidth(0.2f),
                )
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize()) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = {
                    it.player = player
                    it.scaleX = if (mirrored) -1f else 1f
                },
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(BitOSSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (onBack != null) {
                    OutlinedButton(onClick = onBack) { Text("Back to takes") }
                }
                if (durationMs > 0) {
                    // Trim sliders
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "Trim: ${trimStartMs / 1000}s – ${trimEndMs / 1000}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xB3F8F8FF),
                        )
                        Text("Start", style = MaterialTheme.typography.labelSmall, color = Color(0x80F8F8FF))
                        Slider(
                            value = trimStartMs.toFloat(),
                            onValueChange = { value ->
                                trimStartMs = value.toLong().coerceIn(0, trimEndMs - 500)
                                hasTrimmed = true
                                player.seekTo(trimStartMs)
                            },
                            valueRange = 0f..durationMs.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("End", style = MaterialTheme.typography.labelSmall, color = Color(0x80F8F8FF))
                        Slider(
                            value = trimEndMs.toFloat(),
                            onValueChange = { value ->
                                trimEndMs = value.toLong().coerceIn(trimStartMs + 500, durationMs)
                                hasTrimmed = true
                                player.seekTo((trimEndMs - 500).coerceAtLeast(trimStartMs))
                            },
                            valueRange = 0f..durationMs.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                exportError?.let { error ->
                    Text(error, style = MaterialTheme.typography.labelSmall, color = BitOSColors.error)
                }

                OutlinedButton(onClick = {
                    mirrored = !mirrored
                    // Rewind after changing the review transform so the user
                    // can immediately verify text, handedness, and framing.
                    player.seekTo(trimStartMs)
                }) {
                    Text(if (mirrored) "Mirrored" else "Mirror")
                }
                Text(
                    if (mirrored) "Video will be flipped left to right" else "Mirror reverses the final video left to right",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xB3F8F8FF),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
                ) {
                    OutlinedButton(onClick = onRetake) { Text("Retake") }
                    Button(
                        onClick = {
                            if (!hasTrimmed && !mirrored) {
                                // No trim: hand the original bytes unchanged.
                                onUse(bytes, mimeType)
                            } else {
                                exporting = true
                                exportError = null
                                scope.launch {
                                    val edited = exportReviewVideo(
                                        context, sourceFile, trimStartMs, trimEndMs, mirrored,
                                    )
                                    exporting = false
                                    if (edited != null) onUse(edited, "video/mp4")
                                    else exportError = "Couldn’t apply edits. Try again or use the original take."
                                }
                            }
                        },
                    ) { Text(if (hasTrimmed || mirrored) "Use edited video" else "Use this") }
                }
                Text(
                    "${bytes.size / (1024 * 1024)}MB" + if (hasTrimmed) " → ~${(((trimEndMs - trimStartMs).toDouble() / durationMs * bytes.size).toInt() / (1024 * 1024))}MB trimmed" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xB3F8F8FF),
                )
            }
        }
    }
}

/** Applies review-only edits in one bounded render pass; source bytes remain untouched. */
private suspend fun exportReviewVideo(
    context: android.content.Context,
    source: java.io.File,
    startMs: Long,
    endMs: Long,
    mirrored: Boolean,
): ByteArray? = withContext(Dispatchers.IO) {
    val output = java.io.File(context.cacheDir, "bitos-review-${System.nanoTime()}.mp4")
    try {
        val mediaItem = MediaItem.Builder()
            .setUri(android.net.Uri.fromFile(source))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs).setEndPositionMs(endMs).build())
            .build()
        val effects = if (mirrored) listOf(
            ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build(),
        ) else emptyList()
        val item = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effects))
            .build()
        val transformer = Transformer.Builder(context).build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build()).build()
        suspendCancellableCoroutine { continuation ->
            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onError(composition: Composition, result: ExportResult, error: ExportException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            })
            transformer.start(composition, output.absolutePath)
            continuation.invokeOnCancellation { transformer.cancel() }
        }
        output.takeIf { it.length() in 1..space.bitos.core.model.Blossom.MAX_FILE_BYTES }?.readBytes()
    } catch (_: Exception) {
        null
    } finally {
        output.delete()
    }
}
