package space.bitos.app.ui.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.feed.FeedNote
import space.bitos.core.studio.RemixPreflight
import space.bitos.core.studio.RemixSourceTargets
import space.bitos.core.studio.RemixTargetRejection

/**
 * "Remix a Bitz" source picker (MEM-005a, plan
 * `create-hub-sound-remix-plan.md` §Remix source picker): paste/scan a
 * Bitz link or pick one of the account's own recent verified Bitz, then a
 * short preflight (creator, short id, media type, license/attribution,
 * ask-required warning) before the editor opens. A valid event with NO
 * loadable media offers the seeded credited post — never a blank studio
 * project pretending to be a remix. Cancel preserves the hub; an
 * in-flight fetch is cancelled (the lookup is cancellable by contract).
 */
@Composable
fun RemixSourcePickerScreen(
    lookup: space.bitos.app.data.feed.RemixSourceLookup,
    recent: space.bitos.app.data.feed.RecentBitzStore,
    accountPubkey: String?,
    writeRelays: List<String>,
    remixLineageCycles: suspend (FeedNote) -> Boolean,
    onOpenRemix: (space.bitos.app.ui.create.meme.MemeRemixSeed) -> Unit,
    onOpenCreditedPost: (List<List<String>>) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var paste by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var preflightNote by remember { mutableStateOf<FeedNote?>(null) }
    var cycleBlocked by remember { mutableStateOf(false) }

    // The account-scoped recent window opens with the picker and closes
    // with it (a switched account re-opens a fresh window).
    androidx.compose.runtime.LaunchedEffect(accountPubkey) {
        accountPubkey?.let { recent.open(it) }
    }
    DisposableEffect(Unit) {
        onDispose {
            lookup.cancel()
            recent.close()
        }
    }

    fun shortKey(pubkey: String): String =
        if (pubkey.length >= 16) "${pubkey.take(8)}…${pubkey.takeLast(4)}" else pubkey

    /** Resolve one raw input (paste or decoded QR) through the lookup. */
    fun resolve(raw: String) {
        val rejection = RemixSourceTargets.rejectionOf(raw)
        if (rejection != null) {
            pasteError = rejection.message
            return
        }
        pasteError = null
        timedOut = false
        looking = true
        preflightNote = null
        scope.launch {
            val note = lookup.resolve(raw)
            looking = false
            if (note == null) {
                timedOut = lookup.state.value is
                    space.bitos.app.data.feed.RemixSourceLookup.State.Timeout
            } else {
                preflightNote = note
            }
        }
    }

    // ── Scan action: decode a QR from a picked photo (zxing) ──────────
    // Live-camera scanning rides the EDT-013 device pass; the decoded
    // string flows through the SAME shared parser as a paste.
    val scanPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                            ?: return@use null
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val source = com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                        val reader = com.google.zxing.MultiFormatReader().apply {
                            setHints(
                                mapOf(
                                    com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to
                                        listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                                ),
                            )
                        }
                        reader.decodeWithState(
                            com.google.zxing.BinaryBitmap(
                                com.google.zxing.common.HybridBinarizer(source),
                            ),
                        ).text
                    }
                }.getOrNull()
            }
            if (text == null) {
                pasteError = "No Bitz link found in that image"
            } else {
                paste = text
                resolve(text)
            }
        }
    }

    /** Open the editor (or the cycle block, or the credited-post fallback). */
    fun openRemix(note: FeedNote) {
        scope.launch {
            if (remixLineageCycles(note)) {
                cycleBlocked = true
                return@launch
            }
            val label = shortKey(note.pubkey)
            val seed = MemeRemixHandoff.editorSeed(
                note = note,
                creatorLabel = label,
                writeRelays = writeRelays,
            )
            if (seed != null) {
                lookup.cancel()
                onOpenRemix(seed)
            } else {
                lookup.cancel()
                onOpenCreditedPost(MemeRemixHandoff.creditedPostTags(note, label))
            }
        }
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
                "Remix a Bitz",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { lookup.cancel(); onClose() },
                modifier = Modifier.semantics { contentDescription = "Close remix picker" },
            ) {
                Text("Cancel", color = BitOSColors.primary, fontWeight = FontWeight.W600)
            }
        }
        Text(
            "Start from a Bitz; the original creator is credited automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = BitOSColors.textSecondary,
        )

        // ── 1. Paste or scan a Bitz link (primary) ────────────────────
        OutlinedTextField(
            value = paste,
            onValueChange = {
                paste = it.take(RemixSourceTargets.MAX_INPUT_LENGTH)
                pasteError = null
            },
            label = { Text("Paste a Bitz link") },
            placeholder = { Text("nostr:nevent1… or a BitOS note link") },
            isError = pasteError != null,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Paste a Bitz link" },
        )
        pasteError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = BitOSColors.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            Button(
                onClick = { resolve(paste) },
                enabled = !looking && paste.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Check this Bitz" },
            ) {
                Text("Check this Bitz")
            }
            OutlinedButton(
                onClick = {
                    scanPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.semantics { contentDescription = "Scan a Bitz link" },
            ) {
                Icon(AppIcons.Photo, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(BitOSSpacing.xs))
                Text("Scan")
            }
        }

        // ── Named lookup stage ────────────────────────────────────────
        if (looking) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(BitOSSpacing.base),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Checking this Bitz on relays…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BitOSColors.textSecondary,
                    )
                }
            }
        }
        if (timedOut) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                    Text(
                        "Relays did not answer in time",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.W600,
                    )
                    Text(
                        "The Bitz may live on relays you are not connected to. Retry, or paste a different link.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                    )
                    TextButton(onClick = { timedOut = false; resolve(paste) }) {
                        Text("Retry", color = BitOSColors.primary, fontWeight = FontWeight.W600)
                    }
                }
            }
        }

        // ── Preflight card ────────────────────────────────────────────
        preflightNote?.let { note ->
            val mediaUrl = RemixPreflight.mediaUrlOf(note)
            val askRequired = RemixPreflight.requiresAsk(note)
            val license = RemixPreflight.licenseOf(note)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.md)) {
                        val poster = note.video?.let { video ->
                            video.posterUrl
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BitOSColors.surfaceElevated,
                            modifier = Modifier.size(width = 64.dp, height = 80.dp),
                        ) {
                            if (poster != null) {
                                AsyncImage(
                                    model = poster,
                                    contentDescription = "Source poster",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        AppIcons.Photo,
                                        contentDescription = null,
                                        tint = BitOSColors.textTertiary,
                                    )
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Creator ${shortKey(note.pubkey)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.W600,
                            )
                            Text(
                                "Event ${note.id.take(8)}… · kind ${note.kind}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BitOSColors.textSecondary,
                            )
                            RemixPreflight.mediaKindOf(note)?.let { kind ->
                                Text(
                                    "Media: $kind",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BitOSColors.textSecondary,
                                )
                            }
                            Text(
                                "License: ${license ?: "none set (permissive)"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BitOSColors.textSecondary,
                            )
                        }
                    }
                    Text(
                        "Credit: ${RemixPreflight.attributionPreview(shortKey(note.pubkey))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                    )
                    if (askRequired) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BitOSColors.surfaceOverlay,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "This Bitz carries a restrictive license ($license). Attribution is automatic; remixing may still need the creator's permission — it is not granted by opening the editor.",
                                style = MaterialTheme.typography.labelSmall,
                                color = BitOSColors.textPrimary,
                                modifier = Modifier.padding(BitOSSpacing.sm),
                            )
                        }
                    }
                    if (mediaUrl == null) {
                        Text(
                            "This Bitz has no loadable media, so it cannot open as a studio remix.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BitOSColors.textSecondary,
                        )
                        Button(
                            onClick = { openRemix(note) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Create credited post" },
                        ) {
                            Text("Create credited post")
                        }
                    } else {
                        Button(
                            onClick = { openRemix(note) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Open remix" },
                        ) {
                            Text("Open remix")
                        }
                    }
                    TextButton(onClick = { preflightNote = null }) {
                        Text("Cancel", color = BitOSColors.textSecondary)
                    }
                }
            }
        }

        // ── 2. Your recent Bitz (account-scoped, verified) ───────────
        val recentState by recent.state.collectAsStateWithLifecycle()
        Text("Your recent Bitz", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
        if (recentState.isLoading) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                modifier = Modifier.padding(vertical = BitOSSpacing.xs),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    "Loading your recent Bitz…",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textSecondary,
                )
            }
        }
        if (recentState.timedOut) {
            Text(
                "Your relays did not answer — recent Bitz stay unavailable until retry.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textTertiary,
            )
            TextButton(onClick = { accountPubkey?.let { recent.open(it) } }) { Text("Retry") }
        }
        if (!recentState.isLoading && recentState.rows.isEmpty() && !recentState.timedOut) {
            Text(
                "None of your Bitz yet — publish one, or paste a link above.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
        }
        recentState.rows.forEach { note ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Remix your Bitz ${note.id.take(8)}" },
                onClick = { preflightNote = note },
            ) {
                Row(
                    modifier = Modifier.padding(BitOSSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BitOSColors.surfaceElevated,
                        modifier = Modifier.size(width = 44.dp, height = 56.dp),
                    ) {
                        val poster = note.video?.let {
                            it.posterUrl
                        }
                        if (poster != null) {
                            AsyncImage(
                                model = poster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    AppIcons.PlayCircle,
                                    contentDescription = null,
                                    tint = BitOSColors.textTertiary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(BitOSSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Your Bitz · ${RemixPreflight.mediaKindOf(note) ?: "note"}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.W600,
                        )
                        Text(
                            "${note.id.take(8)}… · ${space.bitos.core.studio.MemeSlotRules.relativeTime(note.createdAt, System.currentTimeMillis())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = BitOSColors.textSecondary,
                        )
                    }
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = BitOSColors.textTertiary,
                    )
                }
            }
        }
    }

    if (cycleBlocked) {
        AlertDialog(
            onDismissRequest = { cycleBlocked = false },
            title = { Text("This lineage loops") },
            text = {
                Text("Remixing this Bitz would create a remix cycle. Nothing was created — pick a different source.")
            },
            confirmButton = {
                TextButton(onClick = { cycleBlocked = false }) { Text("OK") }
            },
        )
    }
}
