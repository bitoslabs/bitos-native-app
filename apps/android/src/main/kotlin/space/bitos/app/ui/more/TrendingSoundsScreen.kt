package space.bitos.app.ui.more

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.feed.FeedNote
import space.bitos.core.studio.MemeSoundTrending

/**
 * Trending sounds (APP-021 bootstrap / "use this sound" Wave D, plan
 * docs/product/use-this-sound-plan.md §5): counting `sound` tags over the
 * LIVE feed window IS the rank — no marketplace. Rows are deterministic
 * (shared `MemeSoundTrending.rank`, 3-day half-life); "Use in Studio"
 * re-attaches by URL through the Wave C editor path (hash-verified, no
 * re-upload).
 */
@Composable
fun TrendingSoundsScreen(
    notes: List<FeedNote>,
    onClose: () -> Unit,
    onUseSound: (MemeSoundTrending.Row) -> Unit,
) {
    // ── Row preview (§3.21): stream the ranked artifact and play it —
    // one row at a time; toggling another row stops the current one.
    var playingUrl by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    /** Waveform peaks per URL (§3.21) — fetched lazily on first preview,
     * session-scoped; a failed fetch simply renders no bars. */
    val waveforms = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateMapOf<String, List<Float>>()
    }
    val previewScope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val previewPlayer = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<MediaPlayer?>(null)
    }
    fun stopPreview() {
        previewPlayer.value?.release()
        previewPlayer.value = null
        playingUrl = null
    }
    fun togglePreview(url: String) {
        if (playingUrl == url) {
            stopPreview()
            return
        }
        stopPreview()
        if (waveforms[url] == null) {
            previewScope.launch {
                val peaks = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val connection = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
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
                                    check(out.size() <= space.bitos.core.model.Blossom.MAX_FILE_BYTES) { "too large" }
                                }
                                out.toByteArray()
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }.getOrNull()
                        ?.let { bytes -> space.bitos.app.ui.create.meme.MemeVideoSound.decodePcm(context, bytes) }
                        ?.let { (pcm, _) ->
                            space.bitos.core.studio.MemeSoundWaveform.peaks(pcm).toList()
                        }
                }
                if (peaks != null && peaks.isNotEmpty()) waveforms[url] = peaks
            }
        }
        val player = MediaPlayer()
        previewPlayer.value = player
        player.setOnCompletionListener { stopPreview() }
        player.setOnErrorListener { _, _, _ ->
            stopPreview()
            true
        }
        runCatching {
            player.setDataSource(url)
            player.prepareAsync()
            player.setOnPreparedListener {
                playingUrl = url
                it.start()
            }
        }.onFailure { stopPreview() }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { stopPreview() }
    }
    val rows = androidx.compose.runtime.remember(notes) {
        val now = System.currentTimeMillis()
        val usages = notes.mapNotNull { note ->
            note.soundOf?.let { sound ->
                MemeSoundTrending.Usage(
                    eventId = note.id,
                    createdAtMs = note.createdAt,
                    url = sound.url,
                    sha256 = sound.sha256,
                    sourceEventId = sound.eventId,
                    authorPubkey = sound.pubkey,
                )
            }
        }
        MemeSoundTrending.rank(usages, nowMs = now)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .verticalScroll(rememberScrollState())
            .padding(BitOSSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Trending sounds",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) {
                Text("Done", color = BitOSColors.primary, fontWeight = FontWeight.W600)
            }
        }
        Text(
            "Most-borrowed ♪ in your feed — ranked with a 3-day half-life.",
            style = MaterialTheme.typography.bodySmall,
            color = BitOSColors.textSecondary,
        )
        if (rows.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                    Text("No borrowed sounds yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                    Text(
                        "Open a video Bitz → Sound to borrow its audio, or publish a meme with a picked soundtrack — every borrow counts here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                    )
                }
            }
        }
        rows.forEach { row ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(BitOSSpacing.base),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BitOSColors.primaryContainer,
                    ) {
                        Icon(
                            AppIcons.MusicNote,
                            contentDescription = null,
                            tint = BitOSColors.primary,
                            modifier = Modifier.padding(10.dp).size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(BitOSSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text("Original sound", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W600)
                        Text(
                            "${row.uses} borrow${if (row.uses == 1) "" else "s"} · trending",
                            style = MaterialTheme.typography.labelSmall,
                            color = BitOSColors.textSecondary,
                        )
                        waveforms[row.url]?.let { peaks ->
                            WaveformBars(
                                peaks = peaks,
                                active = playingUrl == row.url,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = { togglePreview(row.url) },
                    ) {
                        Icon(
                            if (playingUrl == row.url) AppIcons.Pause else AppIcons.Play,
                            contentDescription = if (playingUrl == row.url) "Pause preview" else "Preview sound",
                            tint = BitOSColors.textSecondary,
                        )
                    }
                    TextButton(
                        onClick = { onUseSound(row) },
                    ) {
                        Text("Use in Studio", color = BitOSColors.primary, fontWeight = FontWeight.W600)
                    }
                }
            }
        }
    }
}

/** §3.21 waveform bars: normalized peaks as a compact bar row (active
 *  tint while this row is the one previewing). */
@androidx.compose.runtime.Composable
private fun WaveformBars(
    peaks: List<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    // Theme reads must happen in the composable context, not the draw lambda.
    val activeTint = BitOSColors.primary
    val idleTint = BitOSColors.textTertiary
    androidx.compose.foundation.Canvas(
        modifier = modifier.height(18.dp),
    ) {
        if (peaks.isEmpty()) return@Canvas
        val barWidth = size.width / peaks.size
        val tint = if (active) activeTint else idleTint
        peaks.forEachIndexed { index, peak ->
            val barHeight = (size.height * peak.coerceIn(0f, 1f)).coerceAtLeast(1.5f)
            drawRect(
                color = tint.copy(alpha = 0.8f),
                topLeft = androidx.compose.ui.geometry.Offset(index * barWidth + barWidth * 0.2f, (size.height - barHeight) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth * 0.6f, barHeight),
            )
        }
    }
}
