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
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing

/**
 * Recorded-take preview with trim (CAP-003/004): playback + start/end trim
 * sliders + export via Media3 Transformer. "Use this" exports the trimmed
 * clip (or the original when untrimmed) and hands the bytes to the publish
 * pipeline unchanged. Playback always uses a cache-file URI: encoding a
 * camera-original clip as a Base64 data URI duplicates it in the app heap.
 */
@Composable
fun VideoPreviewScreen(
    bytes: ByteArray,
    mimeType: String,
    onUse: (ByteArray, String) -> Unit,
    onRetake: () -> Unit,
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
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

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
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(BitOSSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
                ) {
                    OutlinedButton(onClick = onRetake) { Text("Retake") }
                    Button(
                        onClick = {
                            if (!hasTrimmed) {
                                // No trim: hand the original bytes unchanged.
                                onUse(bytes, mimeType)
                            } else {
                                exporting = true
                                exportError = null
                                // Export the trimmed clip via Transformer.
                                val output = java.io.File(context.cacheDir, "bitos-trim-${System.currentTimeMillis()}.mp4")
                                val mediaItem = MediaItem.Builder()
                                    .setUri(android.net.Uri.fromFile(sourceFile))
                                    .setClippingConfiguration(
                                        MediaItem.ClippingConfiguration.Builder()
                                            .setStartPositionMs(trimStartMs)
                                            .setEndPositionMs(trimEndMs)
                                            .build()
                                    )
                                    .build()
                                val editedItem = EditedMediaItem.Builder(mediaItem).build()
                                val transformer = Transformer.Builder(context).build()
                                transformer.start(editedItem, output.absolutePath)
                                // Poll for completion (Transformer.Listener on newer API; poll for compat).
                                Thread {
                                    try {
                                        while (output.length() == 0L) {
                                            Thread.sleep(200)
                                        }
                                        val trimmed = output.readBytes()
                                        output.delete()
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            exporting = false
                                            if (trimmed.isNotEmpty() && trimmed.size <= bytes.size) {
                                                onUse(trimmed, "video/mp4")
                                            } else {
                                                exportError = "Trim export failed; publishing original."
                                                onUse(bytes, mimeType)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            exporting = false
                                            exportError = "Trim export failed; publishing original."
                                            onUse(bytes, mimeType)
                                        }
                                    }
                                }.start()
                            }
                        },
                    ) { Text(if (hasTrimmed) "Trim & use" else "Use this") }
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
