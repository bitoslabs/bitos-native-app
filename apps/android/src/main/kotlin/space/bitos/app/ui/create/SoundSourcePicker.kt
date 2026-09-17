package space.bitos.app.ui.create

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.bitos.app.data.feed.SharedSoundStore
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.studio.MemeSoundTrending
import space.bitos.core.studio.SharedSoundLibrary

/**
 * "Start with a sound" source picker (MEM-003b, plan
 * `create-hub-sound-remix-plan.md` §Sound source picker): a full-screen
 * route over the Create hub with four ordered sections — Shared sounds,
 * Trending on BitOS (a link into the existing trending surface, not a
 * second ranking), My sound library and Pick sound from a video.
 *
 * Selecting a shared, trending or local row dispatches exactly ONE
 * `MemeSoundSeed`/extraction handoff to the hub; the picker dismisses only
 * after the editor accepts the seed (the hub drives that state). Back or
 * Cancel leaves the hub unchanged — nothing here creates a project or
 * draft. The ranking/fetch/attach code all REUSES the existing stores;
 * this screen only presents and dispatches.
 */
@Composable
fun SoundSourcePickerScreen(
    sharedRows: List<SharedSoundStore.Row>,
    trendingRows: List<MemeSoundTrending.Row>,
    libraryEntries: List<SharedSoundLibrary.Entry>,
    libraryBytes: (String) -> ByteArray?,
    onUseShared: (SharedSoundStore.Row) -> Unit,
    onUseTrending: (MemeSoundTrending.Row) -> Unit,
    onUseLibrary: (SharedSoundLibrary.Entry) -> Unit,
    onPickFromVideo: () -> Unit,
    onOpenTrending: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    // ── One preview at a time (plan: "only one preview may play at
    //    once", with an exposed play/pause state per row control) ──────
    var previewKey by remember { mutableStateOf<String?>(null) }
    val previewPlayer = remember { mutableStateOf<MediaPlayer?>(null) }
    val previewScope = rememberCoroutineScope()
    var previewPreparing by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }

    fun stopPreview() {
        previewPlayer.value?.release()
        previewPlayer.value = null
        previewKey = null
        previewPreparing = null
    }

    fun toggleUrlPreview(key: String, url: String) {
        if (previewKey == key || previewPreparing == key) {
            stopPreview()
            return
        }
        stopPreview()
        previewError = null
        previewPreparing = key
        val player = MediaPlayer()
        previewPlayer.value = player
        player.setOnCompletionListener { stopPreview() }
        player.setOnErrorListener { _, _, _ ->
            previewError = "That preview could not play"
            stopPreview()
            true
        }
        runCatching {
            player.setDataSource(url)
            player.prepareAsync()
            player.setOnPreparedListener {
                previewPreparing = null
                previewKey = key
                it.start()
            }
        }.onFailure {
            previewError = "That preview could not play"
            stopPreview()
        }
    }

    /** Library rows preview from their local bytes via a temp file. */
    fun toggleLibraryPreview(entry: SharedSoundLibrary.Entry) {
        val key = "local:${entry.id}"
        if (previewKey == key || previewPreparing == key) {
            stopPreview()
            return
        }
        val bytes = libraryBytes(entry.id)
        if (bytes == null) {
            previewError = "That saved sound's audio is missing"
            return
        }
        stopPreview()
        previewError = null
        previewPreparing = key
        previewScope.launch {
            val temp = withContext(Dispatchers.IO) {
                runCatching {
                    java.io.File.createTempFile("sound-preview", ".bin", context.cacheDir).apply {
                        writeBytes(bytes)
                        deleteOnExit()
                    }
                }.getOrNull()
            }
            if (temp == null) {
                previewError = "That preview could not play"
                previewPreparing = null
                return@launch
            }
            val player = MediaPlayer()
            previewPlayer.value = player
            player.setOnCompletionListener {
                runCatching { temp.delete() }
                stopPreview()
            }
            player.setOnErrorListener { _, _, _ ->
                runCatching { temp.delete() }
                previewError = "That preview could not play"
                stopPreview()
                true
            }
            runCatching {
                player.setDataSource(temp.absolutePath)
                player.prepareAsync()
                player.setOnPreparedListener {
                    previewPreparing = null
                    previewKey = key
                    it.start()
                }
            }.onFailure {
                runCatching { temp.delete() }
                previewError = "That preview could not play"
                stopPreview()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPreview() }
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
                "Start with a sound",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { stopPreview(); onClose() },
                modifier = Modifier.semantics { contentDescription = "Close sound picker" },
            ) {
                Text("Cancel", color = BitOSColors.primary, fontWeight = FontWeight.W600)
            }
        }
        Text(
            "Add your video after you choose a sound.",
            style = MaterialTheme.typography.bodySmall,
            color = BitOSColors.textSecondary,
        )
        previewError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = BitOSColors.error)
        }

        // ── 1. Shared sounds — the existing verified kind-30078 rail ──
        PickerSectionTitle("Shared sounds", sharedRows.isNotEmpty() || previewError != null)
        if (sharedRows.isEmpty()) {
            NetworkRailPlaceholder(label = "Shared sounds are unavailable right now.")
        } else {
            sharedRows.forEach { row ->
                SharedSoundRow(
                    label = row.label,
                    supporting = listOfNotNull(
                        durationLabel(row.durationMs),
                        row.license.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    credit = "by ${shortKey(row.authorPubkey)}",
                    playing = previewKey == row.url || previewPreparing == row.url,
                    preparing = previewPreparing == row.url,
                    onPreview = { toggleUrlPreview(row.url, row.url) },
                    onUse = { stopPreview(); onUseShared(row) },
                )
            }
        }

        // ── 2. Trending on BitOS — a link into the existing surface ───
        PickerSectionTitle("Trending on BitOS", trendingRows.isNotEmpty())
        trendingRows.take(TRENDING_PREVIEW_ROWS).forEach { row ->
            SharedSoundRow(
                label = "Original sound",
                supporting = "${row.uses} borrow${if (row.uses == 1) "" else "s"} · trending",
                credit = null,
                playing = previewKey == row.url || previewPreparing == row.url,
                preparing = previewPreparing == row.url,
                onPreview = { toggleUrlPreview(row.url, row.url) },
                onUse = { stopPreview(); onUseTrending(row) },
            )
        }
        // The whole trending surface opens the existing rail (one entry,
        // not a second ranking implementation).
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = BitOSColors.surface,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Open trending sounds" },
            onClick = { stopPreview(); onOpenTrending() },
        ) {
            Row(
                modifier = Modifier.padding(BitOSSpacing.base),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Open Trending sounds",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.W600,
                    color = BitOSColors.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = BitOSColors.textTertiary,
                )
            }
        }

        // ── 3. My sound library — local, hash-verified, offline-safe ──
        PickerSectionTitle("My sound library", libraryEntries.isNotEmpty())
        if (libraryEntries.isEmpty()) {
            Text(
                "No saved sounds yet — every soundtrack you attach is saved here on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
        } else {
            libraryEntries.forEach { entry ->
                SharedSoundRow(
                    label = entry.label,
                    supporting = listOfNotNull(
                        durationLabel(entry.durationMs),
                        "saved on this device",
                        entry.url.takeIf { it.isBlank() }?.let { "local only" },
                    ).joinToString(" · "),
                    credit = null,
                    playing = previewKey == "local:${entry.id}" || previewPreparing == "local:${entry.id}",
                    preparing = previewPreparing == "local:${entry.id}",
                    onPreview = { toggleLibraryPreview(entry) },
                    onUse = { stopPreview(); onUseLibrary(entry) },
                )
            }
        }

        // ── 4. Pick sound from a video — the editor's extraction path ─
        PickerSectionTitle("Pick sound from a video", true)
        Button(
            onClick = { stopPreview(); onPickFromVideo() },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Pick sound from a video" },
        ) {
            Icon(AppIcons.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(BitOSSpacing.xs))
            Text("Choose a video — its audio becomes the soundtrack")
        }

        // ── Empty state: nothing selectable anywhere ──────────────────
        if (sharedRows.isEmpty() && trendingRows.isEmpty() && libraryEntries.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(BitOSSpacing.base),
                    verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
                ) {
                    Text(
                        "No sounds available yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.W600,
                    )
                    Text(
                        "Pick a sound from a video to start — shared and trending sounds appear as relays deliver them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerSectionTitle(title: String, visible: Boolean) {
    if (!visible) return
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
}

/** Offline/degraded network rail placeholder (labels the state, keeps the
 *  local library fully usable below it). */
@Composable
private fun NetworkRailPlaceholder(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = BitOSColors.textTertiary,
    )
}

@Composable
private fun SharedSoundRow(
    label: String,
    supporting: String,
    credit: String?,
    playing: Boolean,
    preparing: Boolean,
    onPreview: () -> Unit,
    onUse: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BitOSColors.surface,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Use sound $label" },
        onClick = onUse,
    ) {
        Row(
            modifier = Modifier.padding(BitOSSpacing.base),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = BitOSColors.primaryContainer) {
                Box(Modifier.padding(10.dp)) {
                    Icon(
                        AppIcons.MusicNote,
                        contentDescription = null,
                        tint = BitOSColors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(BitOSSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W600, maxLines = 1)
                if (supporting.isNotBlank()) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textSecondary,
                        maxLines = 1,
                    )
                }
                if (credit != null) {
                    Text(
                        credit,
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
            IconButton(
                onClick = onPreview,
                modifier = Modifier.semantics {
                    contentDescription = if (playing) "Pause preview of $label" else "Preview sound $label"
                },
            ) {
                if (preparing && !playing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (playing) AppIcons.Pause else AppIcons.Play,
                        contentDescription = null,
                        tint = BitOSColors.textSecondary,
                    )
                }
            }
            Text(
                "Use",
                color = BitOSColors.primary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.W700,
            )
        }
    }
}

private fun durationLabel(durationMs: Long): String? =
    if (durationMs > 0) "${durationMs / 1000}s" else null

private fun shortKey(pubkey: String): String =
    if (pubkey.length >= 16) "${pubkey.take(8)}…${pubkey.takeLast(4)}" else pubkey.ifBlank { "unknown" }

private const val TRENDING_PREVIEW_ROWS = 3
