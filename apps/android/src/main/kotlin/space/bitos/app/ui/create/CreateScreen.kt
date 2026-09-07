package space.bitos.app.ui.create

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import space.bitos.app.ui.create.meme.MemeRaster
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.studio.MassBatchDocument
import space.bitos.core.studio.MassBatchRules
import space.bitos.core.studio.MassPublishState
import space.bitos.core.studio.MassRow
import space.bitos.core.studio.MassSlotType
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

private data class CreateAction(
    val icon: @Composable (Modifier) -> Unit,
    val title: String,
    val subtitle: String,
    val description: String,
    /** False rows render dimmed with a "Soon" chip — never a dead tap. */
    val enabled: Boolean,
)

/** Studio import-media gate (CAP/EDT): a picked library video seeds the
 *  meme editor — the ONE publish path from the Create hub. Pure rule so
 *  the rejection copy (name the reason AND the fix, keep the hub
 *  untouched — ux-ui-flows §3) is unit-pinned. */
internal object ImportedVideoRules {
    /** Null = seed the editor; else the named rejection for the dialog. */
    fun rejection(byteCount: Long?, maxBytes: Long = space.bitos.app.ui.create.meme.MemeVideoExport.MAX_SOURCE_BYTES): String? = when {
        byteCount == null || byteCount <= 0L -> "This video could not be read. Try another file."
        byteCount > maxBytes ->
            "This video is larger than ${maxBytes / (1024 * 1024)} MB. Trim it first, then try again."
        else -> null
    }
}

private val quickActions = listOf(
    CreateAction(
        icon = { modifier ->
            space.bitos.app.ui.theme.SolarFeedIconImage(
                space.bitos.app.ui.theme.SolarFeedIcon.Gallery,
                contentDescription = null,
                tint = BitOSColors.primary,
                modifier = modifier,
            )
        },
        title = "Import media",
        subtitle = "From your library",
        description = "Pick a video and polish it in the studio editor",
        enabled = true,
    ),
    CreateAction(
        icon = { modifier ->
            space.bitos.app.ui.theme.SolarStudioIconImage(
                space.bitos.app.ui.theme.SolarStudioIcon.MusicNote,
                contentDescription = null,
                tint = BitOSColors.primary,
                modifier = modifier,
            )
        },
        title = "Use a sound",
        subtitle = "Sound library",
        description = "Start a project from a licensed sound",
        enabled = false,
    ),
    CreateAction(
        icon = { modifier ->
            space.bitos.app.ui.theme.SolarStudioIconImage(
                space.bitos.app.ui.theme.SolarStudioIcon.MagicWand,
                contentDescription = null,
                tint = BitOSColors.primary,
                modifier = modifier,
            )
        },
        title = "Remix",
        subtitle = "Provenance-aware",
        description = "Build on a template with attribution",
        enabled = false,
    ),
)

/** "Start something new" tiles (mockup app-15 scr-home): Bitz · Meme · Batch. */
private data class StartTile(
    val icon: space.bitos.app.ui.theme.SolarStudioIcon,
    val title: String,
    val subtitle: String,
)

private val startTiles = listOf(
    StartTile(space.bitos.app.ui.theme.SolarStudioIcon.VideoCamera, "Bitz", "camera"),
    StartTile(space.bitos.app.ui.theme.SolarStudioIcon.Gallery, "Meme", "quick edit"),
    StartTile(space.bitos.app.ui.theme.SolarStudioIcon.Settings, "Batch", "mass produce"),
)

/** scr-home template cover gradients (honeycomb.css `grad-*`) — the same
 *  stable ladder iOS paints, picked by rail position. */
private val StudioCoverGradients = listOf(
    listOf(androidx.compose.ui.graphics.Color(0xFF0B1E3A), androidx.compose.ui.graphics.Color(0xFF2858C8), androidx.compose.ui.graphics.Color(0xFF06102A)), // lightning
    listOf(androidx.compose.ui.graphics.Color(0xFF3A1505), androidx.compose.ui.graphics.Color(0xFFC2570F), androidx.compose.ui.graphics.Color(0xFF200B02)), // mine
    listOf(androidx.compose.ui.graphics.Color(0xFF2B1802), androidx.compose.ui.graphics.Color(0xFF7A4A05), androidx.compose.ui.graphics.Color(0xFF1A0F01)), // node
    listOf(androidx.compose.ui.graphics.Color(0xFF1E1033), androidx.compose.ui.graphics.Color(0xFF6D28D9), androidx.compose.ui.graphics.Color(0xFF150B26)), // story
)

/** Diagonal cover wash (top-leading → bottom-trailing), matching the iOS
 *  `StudioCoverGradient` ladder and the hex-avatar gradient axis. */
private fun studioCoverBrush(index: Int) = androidx.compose.ui.graphics.Brush.linearGradient(
    StudioCoverGradients[((index % StudioCoverGradients.size) + StudioCoverGradients.size) % StudioCoverGradients.size],
)

/** `hex-plate` (honeycomb.css): flat-top hexagon tile matching the iOS
 *  design system's HexShape — a *regular* flat-top hexagon with the 6.7%
 *  vertical inset (web `.hex-clip` parity), the same clip the batch/settings
 *  icon plates use, here carrying the gradient template cover. */
private val HexPlateShape = object : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.25f, h * 0.067f)
            lineTo(w * 0.75f, h * 0.067f)
            lineTo(w, h * 0.5f)
            lineTo(w * 0.75f, h * 0.933f)
            lineTo(w * 0.25f, h * 0.933f)
            lineTo(0f, h * 0.5f)
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

/**
 * Create surface: fast paths into capture and the Studio (mockup app-15
 * scr-home). The "Start something new" tiles open the camera, the Quick MEM
 * editor and — APP-019 M4 wave 5 / MST-048 — mass production, which flows
 * inside this screen: pick master → typed rows → review contact sheet →
 * per-event publish machine. Camera, import and editors stay native-only
 * features (CAP/EDT epics); this chooser is the stable entry contract.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(
    mediaPublishViewModel: space.bitos.app.ui.feed.MediaPublishViewModel,
    onClose: () -> Unit = {},
) {
    var showCamera by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    /** Studio import (CAP/EDT): reading a picked library video into the
     *  editor seed — the hub stays alive with a named progress state. */
    var importing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var importError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var showMeme by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var templateSeed by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<space.bitos.core.studio.MemeTemplate?>(null)
    }
    /** CAP→MEM handoff (M5): camera takes (strip order) seed video mode —
     * each take becomes its own timeline clip, unmerged. */
    var memeSeeds by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<ByteArray>?>(null)
    }

    var resumeSlotId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    // MST-018 continuation slots: filesDir/studio, ≤6 LRU (EDT-001/002).
    val context = androidx.compose.ui.platform.LocalContext.current
    val slotStore = androidx.compose.runtime.remember {
        space.bitos.app.ui.create.meme.MemeProjectStore(java.io.File(context.filesDir, "studio"))
    }
    var slotsRevision by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val slots = androidx.compose.runtime.remember(slotsRevision) {
        slotStore.listSlots()
    }

    var sharedSeed by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)
    }
    val templateStore = androidx.compose.runtime.remember(context) {
        space.bitos.app.data.feed.SharedTemplateStore(
            (context.applicationContext as space.bitos.app.BitOsApplication).applicationScope,
            (context.applicationContext as space.bitos.app.BitOsApplication).memeTemplatePool,
        )
    }
    val sharedRows by templateStore.rows.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { templateStore.subscribe() }

    // Mass production (MST-048) lives in this screen: a non-null controller
    // replaces the hub with the batch flow, exactly like the editor paths.
    val scope = rememberCoroutineScope()
    var mass by remember { mutableStateOf<MassBatchUi?>(null) }
    val massFiles = remember { MassBatchFiles(File(context.filesDir, "studio/mass")) }
    // Recomputed whenever a batch session closes (mass flips back to null).
    val batchSummary = remember(mass) { massSummary(massFiles) }

    // The editor's resume handle must be identity-stable across
    // recompositions: loading it inline handed MemeEditorScreen a fresh
    // SavedSlot every time (each editor autosave bumps slotsRevision here,
    // and the new document's updatedAtMs breaks SavedSlot equality), so the
    // screen's remember(resume) seed block re-ran and appended every
    // timeline clip a second time — the "auto split on resume" bug — plus
    // main-thread disk reads per frame. Keyed by the slot id: loaded once
    // per editor session.
    val resumeSlot = remember(resumeSlotId) {
        resumeSlotId?.let { slotStore.loadSlot(it) }
    }

    // ── Import media → editor (ONE studio pipeline) ───────────────────
    // The Create hub has exactly one publish path: the meme editor's Post
    // details → render → upload → sign machine. Picked library videos
    // seed video mode as a timeline clip, exactly like a camera take
    // (docs/product/ux-ui-flows.md §6) — the standalone "New video"
    // publish sheet is gone (it conflicted with the studio meme flow).
    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                space.bitos.app.ui.create.meme.readAssetBytes(context, uri)
            }
            importError = ImportedVideoRules.rejection(bytes?.size?.toLong())
            importing = false
            if (importError == null && bytes != null) {
                memeSeeds = listOf(bytes)
                showMeme = true
            }
        }
    }

    if (showMeme) {
        space.bitos.app.ui.create.meme.MemeEditorScreen(
            onClose = {
                showMeme = false; resumeSlotId = null; templateSeed = null; sharedSeed = null; memeSeeds = null
            },
            sharedTagsJson = sharedSeed?.first,
            sharedContent = sharedSeed?.second,
            mediaPublishViewModel = mediaPublishViewModel,
            store = slotStore,
            resume = resumeSlot,
            template = templateSeed,
            videoSeeds = memeSeeds,
            onSlotsChanged = { slotsRevision += 1 },
            onMakeVariations = { projectJson, posterBytes ->
                showMeme = false
                mass = MassBatchUi(massFiles, scope).also { batch ->
                    if (batch.createFromDesign(projectJson, posterBytes) == null) {
                        batch.message = "Image designs with at least one caption — GIF and video stay on the renderer roadmap."
                    }
                }
            },
        )
        return
    }

    if (showCamera) {
        CameraScreen(
            onCaptured = { bytes, _ ->
                // Record → editor: the used take seeds video mode as its
                // own timeline clip; caption/publish flow through the
                // editor's Post details pipeline (the ONE studio path).
                showCamera = false
                memeSeeds = listOf(bytes)
                showMeme = true
            },
            onImport = {
                // Library path from the record screen: same picker → same
                // editor pipeline as the hub's "Import media" action.
                showCamera = false
                importPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            },
            onOpenMeme = { payload ->
                // Quick MEM (M5): the takes seed video mode as individual
                // timeline clips; the editor's slot autosave keeps the
                // first as a studio draft from here on.
                showCamera = false
                memeSeeds = payload?.map { it.first }
                showMeme = true
            },
            onCancel = { showCamera = false },
        )
        return
    }

    mass?.let { batch ->
        MassProductionArea(
            batch = batch,
            mediaPublishViewModel = mediaPublishViewModel,
            onExit = { mass = null },
        )
        return
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
            Text("Create", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = onClose) {
                Text("Done", color = BitOSColors.primary, fontWeight = FontWeight.W600)
            }
        }

        // Start something new (scr-home tiles): camera · quick MEM · batch —
        // tiles sit directly under their header (iOS hub order parity).
        Text("Start something new", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            startTiles.forEachIndexed { index, tile ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BitOSColors.surface,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClickLabel = tile.title) {
                            when (index) {
                                0 -> showCamera = true
                                1 -> showMeme = true
                                2 -> mass = MassBatchUi(massFiles, scope)
                            }
                        }
                        .semantics { contentDescription = "${tile.title}, ${tile.subtitle}" },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(BitOSSpacing.base),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        space.bitos.app.ui.theme.SolarStudioIconImage(
                            tile.icon,
                            contentDescription = null,
                            tint = BitOSColors.primary,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.height(BitOSSpacing.xs))
                        Text(tile.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.W700)
                        Text(tile.subtitle, style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
                    }
                }
            }
        }

        // Templates rail (MST-040): the seeded built-in pack.
        Text("Templates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
        Row(
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            space.bitos.core.studio.MemeTemplates.PACK.forEachIndexed { index, template ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BitOSColors.surface,
                    modifier = Modifier
                        .width(96.dp)
                        .clickable(onClickLabel = template.label) {
                            templateSeed = template
                            showMeme = true
                        },
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Hex-clipped gradient plate (settings-icon geometry).
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .clip(HexPlateShape)
                                    .background(studioCoverBrush(index)),
                            )
                            Text(template.emoji, style = MaterialTheme.typography.headlineMedium)
                        }
                        Text(
                            template.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.W600,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = BitOSSpacing.xs, vertical = BitOSSpacing.xs),
                        )
                    }
                }
            }
        }

        // Shared templates (MST-045): relay-fetched kind-30078 pack.
        if (sharedRows.isNotEmpty()) {
            Text("Shared templates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
            Row(
                horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                sharedRows.forEachIndexed { index, row ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BitOSColors.surface,
                        modifier = Modifier
                            .width(96.dp)
                            .clickable(onClickLabel = row.label) {
                                sharedSeed = row.tagsJson to row.content
                                showMeme = true
                            },
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                // Hex-clipped gradient plate (settings-icon geometry).
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                        .clip(HexPlateShape)
                                        .background(studioCoverBrush(index)),
                                )
                                Text(row.emoji, style = MaterialTheme.typography.headlineMedium)
                                if (row.priceSats > 0) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        space.bitos.app.ui.theme.SolarStudioIconImage(
                                            space.bitos.app.ui.theme.SolarStudioIcon.Bolt,
                                            contentDescription = null,
                                            tint = Color(0xFFFFB000),
                                            modifier = Modifier.size(9.dp),
                                        )
                                        Text(
                                            "${row.priceSats}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFFFB000),
                                            fontWeight = FontWeight.W700,
                                        )
                                    }
                                }
                            }
                            Text(
                                row.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.W600,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = BitOSSpacing.xs, vertical = BitOSSpacing.xs),
                            )
                        }
                    }
                }
            }
        }

        // Batch-queue bar (scr-home mockup): live progress of the newest
        // batch — variants · published · awaiting approval.
        batchSummary?.let { summary ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Open batch") {
                        mass = MassBatchUi(massFiles, scope)
                    },
            ) {
                Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(HexPlateShape)
                                .background(BitOSColors.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            space.bitos.app.ui.theme.SolarStudioIconImage(
                                space.bitos.app.ui.theme.SolarStudioIcon.Settings,
                                contentDescription = null,
                                tint = BitOSColors.primary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Spacer(Modifier.width(BitOSSpacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(summary.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.W700)
                            Text(
                                "${summary.rows} variants · ${summary.published} published · ${summary.awaiting} awaiting approval",
                                style = MaterialTheme.typography.labelSmall,
                                color = BitOSColors.textSecondary,
                            )
                        }
                        Text("Open", color = BitOSColors.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W700)
                    }
                    val total = summary.rows.coerceAtLeast(1)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = BitOSColors.surfaceElevated,
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = BitOSColors.primary,
                            modifier = Modifier.fillMaxWidth(summary.published.toFloat() / total),
                        ) {}
                    }
                }
            }
        }

        // Continue creating (MST-018): one-tap resume into the exact state.
        if (slots.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Continue creating",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W600,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "≤ ${space.bitos.core.studio.MemeSlots.MAX_SLOTS} WIP",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textSecondary,
                )
            }
            slots.forEach { slot ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BitOSColors.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(BitOSSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val poster = slotStore.posterFile(slot.slotId)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BitOSColors.surfaceElevated,
                            modifier = Modifier.size(width = 40.dp, height = 52.dp),
                        ) {
                            if (poster != null) {
                                coil.compose.AsyncImage(
                                    model = poster,
                                    contentDescription = null,
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
                        Spacer(Modifier.width(BitOSSpacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                slot.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.W600,
                                maxLines = 1,
                            )
                            Text(
                                space.bitos.core.studio.MemeSlotRules.relativeTime(
                                    slot.updatedAtMs,
                                    System.currentTimeMillis(),
                                ) + " · quick editor",
                                style = MaterialTheme.typography.labelSmall,
                                color = BitOSColors.textSecondary,
                            )
                        }
                        androidx.compose.material3.TextButton(onClick = {
                            resumeSlotId = slot.slotId
                            showMeme = true
                        }) {
                            Text("Resume", color = BitOSColors.primary, fontWeight = FontWeight.W600)
                        }
                        IconButton(onClick = {
                            slotStore.deleteSlot(slot.slotId)
                            slotsRevision += 1
                        }) {
                            Icon(
                                AppIcons.Close,
                                contentDescription = "Delete draft",
                                tint = BitOSColors.textSecondary,
                            )
                        }
                    }
                }
            }
        }

        quickActions.forEach { action ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (action.enabled) 1f else 0.55f)
                    .then(
                        if (action.enabled && action.title == "Import media") {
                            Modifier.clickable(onClickLabel = action.title) {
                                importPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                                )
                            }
                        } else {
                            Modifier
                        },
                    )
                    .semantics { contentDescription = "${action.title}, ${action.subtitle}" },
            ) {
                Row(
                    modifier = Modifier.padding(BitOSSpacing.base),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(12.dp), color = BitOSColors.primaryContainer) {
                        Box(Modifier.padding(10.dp)) {
                            action.icon(Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.width(BitOSSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(action.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                        Text(action.description, style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
                    }
                    if (!action.enabled) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = BitOSColors.surfaceOverlay,
                        ) {
                            Text(
                                "Soon",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.W700,
                                color = BitOSColors.textSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = BitOSColors.surfaceElevated,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                Text("Project library", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                Text(
                    "Drafts live on-device by default. The library lists projects, storage usage and recovery state once the editor phase lands.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textSecondary,
                )
            }
        }
    }

    // Import progress (named stage — the hub never looks dead while a
    // large library video streams into memory).
    if (importing) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = BitOSColors.surface,
            ) {
                Column(
                    modifier = Modifier.padding(BitOSSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
                ) {
                    CircularProgressIndicator(color = BitOSColors.primary)
                    Text("Preparing your video…", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }

    // Named, non-destructive rejections (UX §3): the hub state stays
    // untouched; the user learns exactly what to do next.
    importError?.let { error ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Couldn't open that video") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("OK") }
            },
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// Mass production (APP-019 M4 wave 5 / MST-048) — integrated into the
// Create hub per docs/ui/app-15 scr-batch/scr-review/scr-pub. Pure rules
// live in shared/business-core (MassBatch/MassBatchRules, common-tested);
// everything below is the native file store + this screen's flow.
// ══════════════════════════════════════════════════════════════════════

/** One durable batch on disk: `<root>/<batchId>/` with batch.json, master
 *  image, row assets and rendered posters. Every read is lenient — a
 *  corrupt store degrades to empty and never blocks creating. */
private class MassBatchFiles(private val root: File) {

    init {
        runCatching { root.mkdirs() }
    }

    fun batchDir(batchId: String): File = File(root, batchId)

    /** MUX-06: a batch FROM an editor design — the frozen `{t:<id>}`
     *  placeholder project rides the recipe (image designs with ≥1 caption
     *  only; null otherwise). */
    fun createFromDesign(projectJson: String, posterBytes: ByteArray, nowMs: Long): MassBatchDocument? {
        if (!isImage(posterBytes)) return null
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return null
        val recipe = space.bitos.core.studio.MassBatch.designRecipe(project) ?: return null
        val document = MassBatchDocument(
            batchId = "mb-" + nowMs.toString(36),
            name = "Variations",
            recipe = recipe,
            rows = emptyList(),
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
        return if (!save(document, nowMs)) null else load(document.batchId)
    }

    /** New batch from a master image; null when the bytes are unreadable. */
    fun create(name: String, masterBytes: ByteArray, nowMs: Long): MassBatchDocument? {
        if (!isImage(masterBytes)) return null
        val batchId = "mb-" + nowMs.toString(36)
        val dir = batchDir(batchId).apply { runCatching { mkdirs() } }
        val master = File(dir, MASTER_FILE)
        runCatching { master.writeBytes(masterBytes) }
        if (!master.exists() || master.length() == 0L) return null
        val document = MassBatchDocument(
            batchId = batchId,
            name = name.ifBlank { "Untitled batch" }.take(80),
            recipe = space.bitos.core.studio.MassBatch.defaultRecipe(
                space.bitos.core.studio.MassBatch.starterProject(),
            ),
            rows = emptyList(),
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
        return if (save(document, nowMs)) document else null
    }

    /** Atomic-ish write (temp + rename); the index upserts most-recent-first. */
    fun save(document: MassBatchDocument, nowMs: Long): Boolean {
        val stamped = document.copy(updatedAtMs = nowMs)
        val wire = space.bitos.core.studio.MassBatchCodec.encode(stamped)
        val dir = batchDir(stamped.batchId).apply { runCatching { mkdirs() } }
        val target = File(dir, BATCH_FILE)
        val temp = File(dir, "$BATCH_FILE.tmp")
        val wrote = runCatching {
            temp.writeText(wire)
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }.getOrDefault(false)
        if (!wrote) return false
        val entry = space.bitos.core.studio.MassBatchCodec.IndexEntry(
            stamped.batchId, stamped.name, nowMs,
        )
        val index = (listOf(entry) + listBatches().filterNot { it.batchId == stamped.batchId })
            .take(space.bitos.core.studio.MassBatch.MAX_BATCHES)
        runCatching {
            File(root, INDEX_FILE).writeText(space.bitos.core.studio.MassBatchCodec.encodeIndex(index))
        }
        return true
    }

    /** Load + crash recovery: orphaned PUBLISHING states reset to WAITING. */
    fun load(batchId: String): MassBatchDocument? {
        val wire = runCatching { File(batchDir(batchId), BATCH_FILE).readText() }.getOrNull()
            ?: return null
        val document = space.bitos.core.studio.MassBatchCodec.decode(wire) ?: return null
        val orphans = document.states.filterValues { it.publish == MassPublishState.PUBLISHING }
        if (orphans.isEmpty()) return document
        var states = document.states
        orphans.keys.forEach { rowId ->
            states = MassBatchRules.withPublishState(states, rowId, MassPublishState.WAITING)
        }
        val recovered = document.copy(states = states)
        save(recovered, recovered.updatedAtMs)
        return recovered
    }

    fun listBatches(): List<space.bitos.core.studio.MassBatchCodec.IndexEntry> = runCatching {
        val file = File(root, INDEX_FILE)
        if (!file.exists()) emptyList() else space.bitos.core.studio.MassBatchCodec.decodeIndex(file.readText())
    }.getOrDefault(emptyList())

    fun delete(batchId: String) {
        runCatching { batchDir(batchId).deleteRecursively() }
        runCatching {
            File(root, INDEX_FILE).writeText(
                space.bitos.core.studio.MassBatchCodec.encodeIndex(
                    listBatches().filterNot { it.batchId == batchId },
                ),
            )
        }
    }

    fun masterFile(batchId: String): File? =
        File(batchDir(batchId), MASTER_FILE).takeIf { it.exists() && it.length() > 0 }

    fun assetFile(batchId: String, fileName: String): File? =
        fileName.takeIf { it.isNotBlank() && !it.startsWith("/") && !it.contains("..") }
            ?.let { File(File(batchDir(batchId), ASSETS_DIR), it) }
            ?.takeIf { it.exists() && it.length() > 0 }

    /** Idempotent row-image copy-in (CAP-005: picker URIs are transient). */
    fun copyRowAsset(batchId: String, rowId: String, slotId: String, bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_ASSET_BYTES || !isImage(bytes)) return null
        val name = "${rowId.take(32)}-${slotId.take(32)}.img"
        val target = File(File(batchDir(batchId), ASSETS_DIR), name)
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            name
        }.getOrNull()
    }

    fun posterNameFor(rowId: String): String = "poster-${rowId.take(32)}.jpg"

    fun writePoster(batchId: String, rowId: String, jpeg: ByteArray): Boolean = runCatching {
        File(batchDir(batchId), posterNameFor(rowId)).writeBytes(jpeg)
        true
    }.getOrDefault(false)

    fun posterFile(batchId: String, rowId: String): File? =
        File(batchDir(batchId), posterNameFor(rowId)).takeIf { it.exists() }

    fun decode(bytes: ByteArray): Bitmap? = runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun isImage(bytes: ByteArray): Boolean = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.outWidth > 0 && options.outHeight > 0
    }.getOrDefault(false)

    companion object {
        const val BATCH_FILE = "batch.json"
        const val INDEX_FILE = "index.json"
        const val MASTER_FILE = "asset-master.png"
        const val ASSETS_DIR = "assets"
        const val MAX_ASSET_BYTES = 8 * 1024 * 1024
    }
}

/** Screen-owned batch state: every mutation persists through
 *  [MassBatchFiles] immediately (kill/relaunch loses nothing, EDT-002);
 *  previews and the publish queue run sequentially off the main thread
 *  and persist around each per-event step (crash-safe advance). */
private class MassBatchUi(
    private val files: MassBatchFiles,
    private val scope: CoroutineScope,
) {
    enum class Phase { PICK, SETUP, REVIEW, PUBLISH }

    var document by mutableStateOf<MassBatchDocument?>(null)
        private set
    var phase by mutableStateOf(Phase.PICK)
    var revision by mutableIntStateOf(0)
        private set
    var generating by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0 to 0)
        private set
    var publishing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    /** MUX-09: per-row export results ("" = saved, else error); successes
     *  are never re-exported, Retry re-runs failures only. */
    var exportResults by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var exportingBatch by mutableStateOf(false)
        private set

    fun exportSelected(rowIds: Set<String>, validations: Map<String, MassBatchRules.RowValidation>, context: android.content.Context) {
        if (generating || exportingBatch) return
        val start = document ?: return
        val targets = start.rows.withIndex().filter { (index, row) ->
            row.id in rowIds && validations[row.id]?.queueable == true
        }
        if (targets.isEmpty()) return
        exportingBatch = true
        scope.launch(Dispatchers.IO) {
            var doc = start
            targets.forEachIndexed { position, (index, row) ->
                if (exportResults[row.id].isNullOrEmpty().not() && exportResults[row.id] == "") {
                    // already saved — never duplicate
                } else {
                    val variant = MassBatchRules.resolveVariant(doc.recipe, row, index + 1)
                    val source = variantSource(doc, variant.assetOverrides)
                    val bitmap = source?.let { MemeRaster.render(it, variant.project) }
                    if (bitmap == null) {
                        exportResults = exportResults + (row.id to "source unreadable")
                    } else {
                        val result = runCatching {
                            MemeRaster.savePng(context, bitmap, variant.name)
                        }
                        exportResults = exportResults + (row.id to (result.exceptionOrNull()?.message ?: ""))
                    }
                }
                progress = position + 1 to targets.size
            }
            exportingBatch = false
        }
    }

    val exportFailedIds: List<String>
        get() = exportResults.filterValues { it.isNotEmpty() }.keys.toList()

    /** MUX-08: full-preview target row (null = closed). */
    var previewingRowId by mutableStateOf<String?>(null)

    fun open(document: MassBatchDocument) {
        this.document = document
        this.phase = Phase.SETUP
        revision += 1
    }

    /** Back to the batch picker (the open batch stays persisted on disk). */
    fun close() {
        document = null
        phase = Phase.PICK
        revision += 1
    }

    // Pick-phase operations share the same on-disk store as the open batch.
    fun createBatch(masterBytes: ByteArray): MassBatchDocument? =
        files.create("New batch", masterBytes, System.currentTimeMillis())

    /** MUX-06 "Make variations": freeze the editor design into a batch. */
    fun createFromDesign(projectJson: String, posterBytes: ByteArray): MassBatchDocument? =
        files.createFromDesign(projectJson, posterBytes, System.currentTimeMillis())

    fun listBatches() = files.listBatches()

    fun loadBatch(batchId: String): MassBatchDocument? = files.load(batchId)

    fun deleteBatch(batchId: String) = files.delete(batchId)

    fun posterFile(rowId: String): File? = document?.let { files.posterFile(it.batchId, rowId) }

    private fun mutate(transform: (MassBatchDocument) -> MassBatchDocument) {
        val current = document ?: return
        val next = transform(current)
        if (files.save(next, System.currentTimeMillis())) {
            document = next
            revision += 1
        }
    }

    private fun assetReadable(fileName: String): Boolean =
        document?.let { files.assetFile(it.batchId, fileName) != null } ?: false

    fun validations(): Map<String, MassBatchRules.RowValidation> {
        val d = document ?: return emptyMap()
        return MassBatchRules.validateAll(d.recipe, d.rows, ::assetReadable)
    }

    fun counts(validations: Map<String, MassBatchRules.RowValidation>): Triple<Int, Int, Int> {
        var ok = 0; var warn = 0; var blocked = 0
        validations.values.forEach {
            when (it.severity) {
                MassBatchRules.Severity.OK -> ok++
                MassBatchRules.Severity.WARN -> warn++
                MassBatchRules.Severity.BLOCKER -> blocked++
            }
        }
        return Triple(ok, warn, blocked)
    }

    /** Approval validity recomputed from current content (edits invalidate). */
    fun approvedNow(rowId: String): Boolean {
        val d = document ?: return false
        val index = d.rows.indexOfFirst { it.id == rowId }
        if (index < 0) return false
        val row = d.rows[index]
        val variant = MassBatchRules.resolveVariant(d.recipe, row, index + 1)
        val hash = MassBatchRules.contentHash(d.recipe.version, row, variant.project)
        val state = d.states[rowId] ?: return false
        return MassBatchRules.approvalValid(state, hash, state.posterHash)
    }

    private fun nextRowId(): String {
        val max = document?.rows?.maxOfOrNull { row ->
            row.id.removePrefix("r").takeWhile(Char::isDigit).toIntOrNull() ?: 0
        } ?: 0
        return "r${max + 1}"
    }

    fun addRow() = mutate { d -> d.copy(rows = d.rows + MassRow(nextRowId())) }

    fun updateRowValue(rowId: String, slotId: String, value: String) = mutate { d ->
        d.copy(
            rows = d.rows.map { row ->
                if (row.id == rowId) row.copy(values = row.values + (slotId to value.take(300))) else row
            },
        )
    }

    fun setRowAsset(rowId: String, slotId: String, bytes: ByteArray?) {
        val d = document ?: return
        val name = files.copyRowAsset(d.batchId, rowId, slotId, bytes)
        mutate { doc ->
            doc.copy(
                rows = doc.rows.map { row ->
                    if (row.id != rowId) {
                        row
                    } else if (name != null) {
                        row.copy(assetFiles = row.assetFiles + (slotId to name))
                    } else {
                        row.copy(assetFiles = row.assetFiles - slotId)
                    }
                },
            )
        }
    }

    fun removeRow(rowId: String) = mutate { d -> d.copy(rows = d.rows.filterNot { it.id == rowId }) }

    /** Recipe-content edits FORK once rows exist (product doc §3). */
    fun editRecipe(naming: String? = null, caption: String? = null) {
        mutate { d ->
            var recipe = d.recipe
            naming?.let { recipe = recipe.copy(naming = it.take(64).ifBlank { "memes_{i}" }) }
            caption?.let { recipe = recipe.copy(caption = it.take(1000)) }
            if (d.rows.isEmpty()) d.copy(recipe = recipe) else MassBatchRules.forkRecipe(d, recipe)
        }
    }

    fun importCsv(text: String, onNotes: (List<String>) -> Unit) {
        val d = document ?: return
        val imported = MassBatchRules.importCsv(text, d.recipe)
        if (imported.rows.isEmpty()) {
            onNotes(imported.notes + "No importable rows found.")
            return
        }
        var counter = d.rows.maxOfOrNull { row ->
            row.id.removePrefix("r").takeWhile(Char::isDigit).toIntOrNull() ?: 0
        } ?: 0
        val rows = imported.rows.map { row -> counter += 1; row.copy(id = "r$counter") }
        mutate { doc ->
            if (doc.rows.isEmpty()) {
                MassBatchRules.forkRecipe(doc, doc.recipe).copy(rows = rows)
            } else {
                doc.copy(rows = doc.rows + rows)
            }
        }
        onNotes(imported.notes)
    }

    fun setApproval(rowId: String, approve: Boolean) {
        val d = document ?: return
        val index = d.rows.indexOfFirst { it.id == rowId }
        if (index < 0) return
        val row = d.rows[index]
        val variant = MassBatchRules.resolveVariant(d.recipe, row, index + 1)
        val hash = if (approve) {
            MassBatchRules.contentHash(d.recipe.version, row, variant.project)
        } else {
            null
        }
        mutate { doc ->
            doc.copy(
                states = MassBatchRules.withApproval(
                    doc.states, rowId, hash, doc.states[rowId]?.posterHash, System.currentTimeMillis(),
                ),
            )
        }
    }

    fun approveAllQueueable(validations: Map<String, MassBatchRules.RowValidation>) {
        val d = document ?: return
        var states = d.states
        val now = System.currentTimeMillis()
        d.rows.withIndex().forEach { (index, row) ->
            if (validations[row.id]?.queueable == true && !approvedNow(row.id)) {
                val variant = MassBatchRules.resolveVariant(d.recipe, row, index + 1)
                val hash = MassBatchRules.contentHash(d.recipe.version, row, variant.project)
                states = MassBatchRules.withApproval(states, row.id, hash, states[row.id]?.posterHash, now)
            }
        }
        mutate { doc -> doc.copy(states = states) }
    }

    /** Sequential poster generation; persists after each variant. */
    fun generatePreviews(validations: Map<String, MassBatchRules.RowValidation>) {
        generating = true
        scope.launch(Dispatchers.IO) {
            val start = document ?: return@launch
            val targets = start.rows.withIndex().filter { (_, row) -> validations[row.id]?.queueable == true }
            progress = 0 to targets.size
            var doc = start
            targets.forEachIndexed { position, (index, row) ->
                val variant = MassBatchRules.resolveVariant(doc.recipe, row, index + 1)
                val source = variantSource(doc, variant.assetOverrides)
                val poster = source?.let { MemeRaster.render(it, variant.project).toPosterJpeg() }
                if (source == null || poster == null) {
                    message = "Row ${position + 1}: image unreadable — preview skipped"
                } else if (files.writePoster(doc.batchId, row.id, poster)) {
                    doc = doc.copy(
                        states = MassBatchRules.withRender(
                            doc.states, row.id, files.posterNameFor(row.id), poster.sha256Hex(),
                        ),
                    )
                    files.save(doc, System.currentTimeMillis())
                    document = doc
                    revision += 1
                }
                progress = position + 1 to targets.size
            }
            generating = false
            phase = Phase.REVIEW
        }
    }

    /** Per-event publish machine: one variant at a time, each renders →
     *  hash-verified upload → kind-20 sign; state persists around every
     *  step so a kill mid-queue resumes without double-publishing. */
    fun runPublishQueue(viewModel: space.bitos.app.ui.feed.MediaPublishViewModel) {
        scope.launch(Dispatchers.IO) {
            publishing = true
            message = null
            try {
                while (true) {
                    val doc = document ?: break
                    val entry = MassBatchRules.queue(doc).firstOrNull() ?: break
                    mutate { d ->
                        d.copy(
                            states = MassBatchRules.withPublishState(
                                d.states, entry.rowId, MassPublishState.PUBLISHING,
                            ),
                        )
                    }
                    val ok = publishOne(viewModel, entry.index, entry.variant)
                    mutate { d ->
                        d.copy(
                            states = MassBatchRules.withPublishState(
                                d.states,
                                entry.rowId,
                                if (ok) MassPublishState.PUBLISHED else MassPublishState.FAILED,
                                failure = if (ok) null else "upload or publish failed — retry available",
                            ),
                        )
                    }
                    if (!ok) message = "Variant ${entry.index} failed — remaining variants stay queued."
                }
            } finally {
                publishing = false
            }
        }
    }

    private suspend fun publishOne(
        viewModel: space.bitos.app.ui.feed.MediaPublishViewModel,
        index: Int,
        variant: MassBatchRules.ResolvedVariant,
    ): Boolean {
        val doc = document ?: return false
        val source = variantSource(doc, variant.assetOverrides) ?: run {
            message = "Variant $index: image unreadable"
            return false
        }
        val rendered = MemeRaster.render(source, variant.project)
        val bytes = ByteArrayOutputStream().also {
            rendered.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        return suspendCancellableCoroutine { continuation ->
            viewModel.publishMemePicture(
                bytes = bytes,
                width = rendered.width,
                height = rendered.height,
                caption = variant.caption,
                altText = variant.project.altText,
                contentWarningReason = doc.recipe.contentWarningReason,
            ) { ok, error ->
                if (continuation.isActive) continuation.resume(ok)
                if (!ok) message = "Variant $index: ${error ?: "publish failed"}"
            }
        }
    }

    private fun variantSource(doc: MassBatchDocument, overrides: Map<String, String>): Bitmap? {
        val overrideBytes = overrides.values.firstNotNullOfOrNull { file ->
            files.assetFile(doc.batchId, file)?.let { runCatching { it.readBytes() }.getOrNull() }
        }
        val bytes = overrideBytes
            ?: files.masterFile(doc.batchId)?.let { runCatching { it.readBytes() }.getOrNull() }
            ?: return null
        return files.decode(bytes)
    }

    private fun Bitmap.toPosterJpeg(maxEdge: Int = 256): ByteArray? = runCatching {
        val scale = (maxEdge.toFloat() / maxOf(width, height)).coerceAtMost(1f)
        val poster = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true,
            )
        } else {
            this
        }
        val out = ByteArrayOutputStream()
        poster.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (poster !== this) poster.recycle()
        out.toByteArray()
    }.getOrNull()

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}

// ── Mass flow UI: pick → setup (scr-batch) → review (scr-review) → publish (scr-pub) ──

@Composable
private fun MassProductionArea(
    batch: MassBatchUi,
    mediaPublishViewModel: space.bitos.app.ui.feed.MediaPublishViewModel,
    onExit: () -> Unit,
) {
    when (batch.document) {
        null -> MassPickBatch(batch, onExit)
        else -> when (batch.phase) {
            MassBatchUi.Phase.PICK -> MassPickBatch(batch, onExit)
            MassBatchUi.Phase.SETUP -> MassSetup(batch)
            MassBatchUi.Phase.REVIEW -> MassReview(batch, mediaPublishViewModel)
            MassBatchUi.Phase.PUBLISH -> MassPublish(batch, mediaPublishViewModel)
        }
    }
}

/** Batch picker: existing batches + New batch (master image pick). */
@Composable
private fun MassPickBatch(batch: MassBatchUi, onExit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var failure by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val batches = remember(revision) { batch.listBatches() }
    val masterPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bytes = readBounded(uri, context)
            val document = bytes?.let(batch::createBatch)
            if (document != null) {
                failure = null
                batch.open(document)
            } else {
                failure = "That image could not be read — pick a PNG or JPEG."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .padding(BitOSSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mass production", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onExit) { Text("Done", color = BitOSColors.primary, fontWeight = FontWeight.W600) }
        }
        Button(
            onClick = { masterPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("New batch — pick a master image") }
        failure?.let { Text(it, color = BitOSColors.error, style = MaterialTheme.typography.bodySmall) }
        Text(
            "One recipe becomes many reviewable outputs. Pick a master image, add typed rows (or import a CSV), review every variant, publish only what you approve.",
            style = MaterialTheme.typography.bodySmall,
            color = BitOSColors.textSecondary,
        )
        if (batches.isEmpty()) {
            Text(
                "No batches yet — pick a master image above to start your first run.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
        }
        var deleteTarget by remember { mutableStateOf<String?>(null) }
        batches.forEach { entry ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Open batch ${entry.name}") {
                        batch.loadBatch(entry.batchId)?.let(batch::open)
                    },
            ) {
                Row(Modifier.padding(BitOSSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.W600)
                        Text(
                            space.bitos.core.studio.MemeSlotRules.relativeTime(
                                entry.updatedAtMs, System.currentTimeMillis(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = BitOSColors.textSecondary,
                        )
                    }
                    Text("Open", color = BitOSColors.primary, fontWeight = FontWeight.W700, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(BitOSSpacing.sm))
                    IconButton(onClick = { deleteTarget = entry.batchId }) {
                        Icon(AppIcons.Close, contentDescription = "Delete batch ${entry.name}", tint = BitOSColors.textSecondary)
                    }
                }
            }
        }
        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Delete batch?") },
                text = { Text("The recipe, rows and rendered posters are deleted permanently.") },
                confirmButton = {
                    TextButton(onClick = {
                        batch.deleteBatch(target)
                        deleteTarget = null
                        revision += 1
                    }) { Text("Delete", color = BitOSColors.error, fontWeight = FontWeight.W600) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun MassSetup(batch: MassBatchUi) {
    val document = batch.document ?: return
    // Recipe drafts commit on Done / Generate — NOT per keystroke: recipe
    // edits fork the version once rows exist (product doc §3), so live
    // commits would fork on every key.
    var namingDraft by remember(document.recipe.naming) { mutableStateOf(document.recipe.naming) }
    var captionDraft by remember(document.recipe.caption) { mutableStateOf(document.recipe.caption) }
    val commitRecipe = {
        batch.editRecipe(naming = namingDraft.takeIf { it != document.recipe.naming })
        batch.editRecipe(caption = captionDraft.takeIf { it != document.recipe.caption })
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val validations = remember(batch.revision) { batch.validations() }
    var csvNotes by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingAsset by remember { mutableStateOf<Pair<String, String>?>(null) }

    // MUX-07: dry-run analysis first — nothing imports until confirmed.
    var csvPending by remember { mutableStateOf<Pair<String, space.bitos.core.studio.MassBatchRules.CsvPreview>?>(null) }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val doc = batch.document
            if (text != null && doc != null) {
                csvPending = text to MassBatchRules.csvPreview(text, doc.recipe)
            } else {
                csvNotes = listOf("The file could not be read.")
            }
        }
    }
    csvPending?.let { (text, preview) ->
        AlertDialog(
            onDismissRequest = { csvPending = null },
            title = {
                Text(if (preview.overCap) "Too many rows" else "Import ${preview.dataRows} rows?")
            },
            text = {
                Text(
                    buildList {
                        if (preview.overCap) {
                            add("${preview.dataRows} rows found — the cap is ${preview.cap} per import. Split the CSV and import in parts.")
                        }
                        if (preview.missingRequired.isNotEmpty()) {
                            add("Missing required columns: ${preview.missingRequired.joinToString()} — those rows stay blocked until fixed.")
                        }
                        if (preview.unknownColumns.isNotEmpty()) {
                            add("Ignored (no matching field): ${preview.unknownColumns.joinToString()}.")
                        }
                        if (!preview.overCap && preview.missingRequired.isEmpty() && preview.unknownColumns.isEmpty()) {
                            add("Every column maps to a field. Nothing changes until you confirm.")
                        }
                    }.joinToString("\n"),
                )
            },
            confirmButton = {
                if (!preview.overCap) {
                    TextButton(onClick = {
                        batch.importCsv(text) { notes -> csvNotes = notes }
                        csvPending = null
                    }) { Text("Import ${preview.dataRows}", color = BitOSColors.primary, fontWeight = FontWeight.W600) }
                } else {
                    TextButton(onClick = { csvPending = null }) { Text("OK") }
                }
            },
            dismissButton = {
                if (!preview.overCap) {
                    TextButton(onClick = { csvPending = null }) { Text("Cancel") }
                }
            },
        )
    }
    val rowImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = pendingAsset
        pendingAsset = null
        if (uri != null && target != null) {
            batch.setRowAsset(target.first, target.second, readBounded(uri, context))
        }
    }

    val counts = batch.counts(validations)
    val queueable = counts.first + counts.second

    Column(modifier = Modifier.fillMaxSize().background(BitOSColors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { batch.close() }) {
                Icon(AppIcons.Close, contentDescription = "Back to batches")
            }
            Column(Modifier.weight(1f)) {
                Text(document.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W700)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "recipe v${document.recipe.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textSecondary,
                    )
                    if (document.rows.isNotEmpty()) {
                        Icon(
                            AppIcons.Lock,
                            contentDescription = null,
                            tint = BitOSColors.textSecondary,
                            modifier = Modifier.size(10.dp),
                        )
                        Text(
                            "edits fork v${document.recipe.version + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = BitOSColors.textSecondary,
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = BitOSSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = BitOSColors.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                        OutlinedTextField(
                            value = namingDraft,
                            onValueChange = { namingDraft = it },
                            label = { Text("File naming") },
                            placeholder = { Text("e.g. meme-{i}.png") },
                            supportingText = {
                                Text("{i} row order · {name} · {sats} — applies on Done / Generate")
                            },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onDone = { commitRecipe() },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = captionDraft,
                            onValueChange = { captionDraft = it },
                            label = { Text("Caption template") },
                            placeholder = { Text("e.g. gm {name} — {sats} sats") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onDone = { commitRecipe() },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            items(document.rows, key = { it.id }) { row ->
                val validation = validations[row.id]
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (validation?.severity == MassBatchRules.Severity.BLOCKER) {
                        BitOSColors.error.copy(alpha = 0.08f)
                    } else {
                        BitOSColors.surface
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "#${document.rows.indexOf(row) + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.W700,
                                color = BitOSColors.textSecondary,
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { batch.removeRow(row.id) }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    AppIcons.Close,
                                    contentDescription = "Remove row",
                                    modifier = Modifier.size(16.dp),
                                    tint = BitOSColors.textSecondary,
                                )
                            }
                        }
                        document.recipe.slots.forEach { slot ->
                            if (
                                slot.type == MassSlotType.IMAGE_ASSET ||
                                slot.type == MassSlotType.VIDEO_ASSET ||
                                slot.type == MassSlotType.AUDIO_ASSET
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        slot.name + if (slot.required) "" else " (optional)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BitOSColors.textSecondary,
                                        modifier = Modifier.width(96.dp),
                                    )
                                    TextButton(onClick = {
                                        pendingAsset = row.id to slot.id
                                        rowImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }) {
                                        Text(if (row.assetFiles[slot.id] != null) "replace image" else "pick image")
                                    }
                                    if (row.assetFiles[slot.id] != null) {
                                        Icon(
                                            AppIcons.Check,
                                            contentDescription = null,
                                            tint = BitOSColors.success,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = row.values[slot.id] ?: "",
                                    onValueChange = { batch.updateRowValue(row.id, slot.id, it) },
                                    label = {
                                        Text(
                                            slot.name + (if (slot.required) "" else " (optional)") + " · " +
                                                when (slot.type) {
                                                    MassSlotType.NUMBER -> "number (1k works)"
                                                    MassSlotType.COLOR -> "#rrggbb"
                                                    MassSlotType.TIMESTAMP -> "timestamp"
                                                    MassSlotType.ENUM -> slot.enumValues.joinToString("/")
                                                    else -> "text"
                                                },
                                        )
                                    },
                                    singleLine = true,
                                    isError = validation?.notes?.any { note ->
                                        note.slotId == slot.id &&
                                            (note.kind == MassBatchRules.Note.Kind.INVALID || note.kind == MassBatchRules.Note.Kind.MISSING)
                                    } == true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        validation?.notes?.forEach { note ->
                            Text(
                                "· ${note.message}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (validation.severity == MassBatchRules.Severity.BLOCKER) {
                                    BitOSColors.error
                                } else {
                                    BitOSColors.warningText
                                },
                            )
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                    OutlinedButton(onClick = { batch.addRow() }, modifier = Modifier.weight(1f)) {
                        Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add row")
                    }
                    OutlinedButton(
                        onClick = { csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Import CSV") }
                }
            }
            item {
                csvNotes.forEach { note ->
                    Text("· $note", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
                }
            }
        }

        // Validity chips + generate (scr-batch: "96 valid · 3 warnings · 1 blocked").
        Column(Modifier.padding(BitOSSpacing.screen), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                SeverityChip("${counts.first} valid", BitOSColors.success)
                SeverityChip("${counts.second} warnings", BitOSColors.warning)
                if (counts.third > 0) SeverityChip("${counts.third} blocked — excluded", BitOSColors.error)
            }
            if (batch.generating && batch.progress.second > 0) {
                // Determinate render progress — posters are real work.
                val fraction = batch.progress.first.toFloat() / batch.progress.second
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                ) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.weight(1f),
                        color = BitOSColors.primary,
                    )
                    Text(
                        "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.W700,
                        color = BitOSColors.primary,
                    )
                }
                Text(
                    "Rendering posters ${batch.progress.first}/${batch.progress.second} — each variant is hash-pinned as it renders",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textSecondary,
                )
            }
            Button(
                onClick = {
                    commitRecipe()
                    batch.generatePreviews(validations)
                },
                enabled = queueable > 0 && !batch.generating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (batch.generating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = BitOSColors.background)
                    Spacer(Modifier.width(8.dp))
                    Text("Rendering ${batch.progress.first}/${batch.progress.second}")
                } else {
                    Text("Generate previews ($queueable)")
                }
            }
        }
    }
}

/** Review contact sheet (scr-review): stable order, per-tile approval. */
@Composable
private fun MassReview(batch: MassBatchUi, mediaPublishViewModel: space.bitos.app.ui.feed.MediaPublishViewModel) {
    val document = batch.document ?: return
    val validations = remember(batch.revision) { batch.validations() }
    // MUX-09: export selection lives above the grid so tiles can toggle it.
    var exportSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(BitOSColors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { batch.phase = MassBatchUi.Phase.SETUP }) { Text("Setup") }
            Text(
                "Review · ${document.rows.size} variants",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
                modifier = Modifier.weight(1f),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = BitOSSpacing.screen),
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            gridItems(document.rows, key = { it.id }) { row ->
                val index = document.rows.indexOf(row) + 1
                val validation = validations[row.id]
                val blocked = validation?.queueable == false
                val state = document.states[row.id]
                val approved = batch.approvedNow(row.id)
                val poster = batch.posterFile(row.id)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BitOSColors.surface,
                    // MUX-08: inspecting never approves — the body opens the
                    // full preview; approval is the explicit footer control.
                    onClick = { batch.previewingRowId = row.id },
                    modifier = Modifier.alpha(if (blocked) 0.5f else 1f),
                ) {
                    Column {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(0.75f).background(BitOSColors.surfaceElevated),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (poster != null) {
                                AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Text("queued", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
                            }
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.55f),
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                            ) {
                                Text(
                                    "#$index",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            if (validation?.severity == MassBatchRules.Severity.WARN) {
                                Surface(
                                    shape = CircleShape,
                                    color = BitOSColors.warning,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                                ) {
                                    space.bitos.app.ui.theme.SolarStudioIconImage(
                                        space.bitos.app.ui.theme.SolarStudioIcon.DangerTriangle,
                                        contentDescription = "Warning",
                                        tint = Color.Black,
                                        modifier = Modifier.padding(4.dp).size(10.dp),
                                    )
                                }
                            }
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                                    .clickable(enabled = validations[row.id]?.queueable == true) {
                                        exportSelection = if (row.id in exportSelection) exportSelection - row.id else exportSelection + row.id
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (row.id in exportSelection) AppIcons.CheckCircle else AppIcons.Check,
                                    contentDescription = if (row.id in exportSelection) "Remove variant $index from export" else "Add variant $index to export",
                                    tint = if (row.id in exportSelection) BitOSColors.primary else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            if (state?.publish == MassPublishState.PUBLISHED) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = BitOSColors.success.copy(alpha = 0.9f),
                                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                                ) {
                                    Text(
                                        "live",
                                        color = Color.Black,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.W800,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            approved -> BitOSColors.success
                                            blocked -> BitOSColors.error.copy(alpha = 0.3f)
                                            else -> BitOSColors.surfaceElevated
                                        },
                                    )
                                    .clickable(enabled = !blocked) { batch.setApproval(row.id, !approved) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = if (approved) "Unapprove variant $index" else "Approve variant $index",
                                    tint = if (approved) Color.Black else BitOSColors.textTertiary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (blocked) {
                                    validation.notes.firstOrNull()?.message ?: "blocked"
                                } else {
                                    row.values[document.recipe.slots.firstOrNull()?.id] ?: row.id
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        val approvedCount = document.rows.count {
            batch.approvedNow(it.id) && validations[it.id]?.queueable == true &&
                document.states[it.id]?.publish != MassPublishState.PUBLISHED
        }
        val publishedCount = document.rows.count { document.states[it.id]?.publish == MassPublishState.PUBLISHED }
        val readyIds = document.rows.filter { validations[it.id]?.queueable == true }.map { it.id }.toSet()
        Column(Modifier.padding(BitOSSpacing.screen), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
            Text(
                "$approvedCount approved · $publishedCount published · ${batch.counts(validations).third} excluded (blocked)",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textSecondary,
            )
            // MUX-09: export selection is DISTINCT from publish approval.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        exportSelection = if (exportSelection == readyIds) emptySet() else readyIds
                    },
                    enabled = readyIds.isNotEmpty(),
                ) {
                    Text(if (exportSelection == readyIds) "Deselect all" else "Select all ready", color = BitOSColors.primary)
                }
                Text(
                    "· ${document.rows.size - readyIds.size} blocked excluded",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textSecondary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${exportSelection.size} chosen",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.W700,
                    color = BitOSColors.primary,
                )
            }
            var showApproveAll by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                OutlinedButton(
                    onClick = { showApproveAll = true },
                    modifier = Modifier.weight(1f),
                ) { Text("Approve all valid") }
                Button(
                    onClick = { batch.phase = MassBatchUi.Phase.PUBLISH },
                    enabled = approvedCount > 0 && !batch.publishing,
                    modifier = Modifier.weight(1f),
                ) { Text("Sign & publish $approvedCount") }
            Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                Button(
                    onClick = { batch.exportSelected(exportSelection, validations, context) },
                    enabled = exportSelection.isNotEmpty() && !batch.exportingBatch,
                    colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.primary, contentColor = androidx.compose.ui.graphics.Color.White),
                    modifier = Modifier.weight(1f),
                ) {
                    if (batch.exportingBatch) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = androidx.compose.ui.graphics.Color.White)
                        Spacer(Modifier.width(BitOSSpacing.xs))
                    }
                    Text("Export selected (${exportSelection.size})", fontWeight = FontWeight.W600)
                }
                if (batch.exportFailedIds.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { batch.exportSelected(batch.exportFailedIds.toSet(), validations, context) },
                        enabled = !batch.exportingBatch,
                        modifier = Modifier.weight(1f),
                    ) { Text("Retry failed (${batch.exportFailedIds.size})") }
                }
            }
            if (batch.exportResults.isNotEmpty()) {
                val saved = batch.exportResults.values.count { it.isEmpty() }
                Text(
                    if (saved == batch.exportResults.size) "$saved saved to Photos ✓"
                    else "$saved saved · ${batch.exportResults.size - saved} need attention",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (saved == batch.exportResults.size) BitOSColors.success else BitOSColors.warning,
                )
            }
            }
            Text(
                "Nothing signs until each upload hash-verifies. Approvals bind content hashes — edits invalidate.",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textSecondary,
            )

        val validCount = document.rows.count { validations[it.id]?.queueable == true }
        val warnCount = document.rows.count { validations[it.id]?.severity == MassBatchRules.Severity.WARN }
        val blockedCount = document.rows.count { validations[it.id]?.queueable == false }
        if (showApproveAll) {
            AlertDialog(
                onDismissRequest = { showApproveAll = false },
                title = { Text("Approve $validCount valid variants?") },
                text = {
                    Text(
                        "$warnCount with warnings included · $blockedCount excluded (blocked) · changed content goes back to unapproved.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        batch.approveAllQueueable(validations)
                        showApproveAll = false
                    }) { Text("Approve $validCount", color = BitOSColors.primary, fontWeight = FontWeight.W600) }
                },
                dismissButton = { TextButton(onClick = { showApproveAll = false }) { Text("Cancel") } },
            )
        }
        batch.previewingRowId?.let { rowId ->
            val row = document.rows.firstOrNull { it.id == rowId } ?: return@let
            val index = document.rows.indexOf(row) + 1
            val poster = batch.posterFile(row.id)
            val validation = validations[row.id]
            val blocked = validation?.queueable == false
            val approved = batch.approvedNow(row.id)
            AlertDialog(
                onDismissRequest = { batch.previewingRowId = null },
                title = { Text("Variant #$index") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                        if (poster != null) {
                            AsyncImage(
                                model = poster,
                                contentDescription = "Variant $index preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                            )
                        } else {
                            Text(
                                if (blocked) validation?.notes?.firstOrNull()?.message ?: "blocked — fix the row in Setup"
                                else "queued — generate previews first",
                                style = MaterialTheme.typography.bodySmall,
                                color = BitOSColors.textSecondary,
                            )
                        }
                        validation?.notes?.forEach { Text("· " + it.message, style = MaterialTheme.typography.labelSmall, color = if (blocked) BitOSColors.error else BitOSColors.warning) }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                        TextButton(
                            onClick = {
                                val i = document.rows.indexOf(row)
                                if (i > 0) batch.previewingRowId = document.rows[i - 1].id
                            },
                        ) { Text("‹ Prev") }
                        TextButton(
                            onClick = { if (!blocked) batch.setApproval(row.id, !approved) },
                            enabled = !blocked,
                        ) { Text(if (approved) "Unapprove" else "Approve", color = if (approved) BitOSColors.error else BitOSColors.primary, fontWeight = FontWeight.W600) }
                        TextButton(
                            onClick = {
                                batch.previewingRowId = null
                                batch.phase = MassBatchUi.Phase.SETUP
                            },
                        ) { Text("Edit") }
                        TextButton(
                            onClick = {
                                val i = document.rows.indexOf(row)
                                if (i + 1 < document.rows.size) batch.previewingRowId = document.rows[i + 1].id
                            },
                        ) { Text("Next ›") }
                    }
                },
                dismissButton = { TextButton(onClick = { batch.previewingRowId = null }) { Text("Close") } },
            )
        }
        }
    }
}

/** Publish machine (scr-pub): per-event signing, crash-safe advance. */
@Composable
private fun MassPublish(batch: MassBatchUi, mediaPublishViewModel: space.bitos.app.ui.feed.MediaPublishViewModel) {
    val document = batch.document ?: return
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(batch.phase) {
        if (!started && !batch.publishing) {
            started = true
            batch.runPublishQueue(mediaPublishViewModel)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BitOSColors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                batch.phase = MassBatchUi.Phase.REVIEW
                started = false
            }) { Text("Review") }
            Text(
                if (batch.publishing) "Publishing…" else "Publish queue",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
                modifier = Modifier.weight(1f),
            )
        }
        // Overall queue progress: published + failed advance the machine;
        // the bar shows how far through the queue the run is.
        val totalRows = document.rows.size.coerceAtLeast(1)
        val settled = document.rows.count { row ->
            val p = document.states[row.id]?.publish
            p == MassPublishState.PUBLISHED || p == MassPublishState.FAILED
        }
        val fraction = settled.toFloat() / totalRows
        Column(
            Modifier.padding(horizontal = BitOSSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
        ) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = BitOSColors.primary,
            )
            Text(
                "$settled of ${document.rows.size} processed · ${(fraction * 100).toInt()}%" +
                    " — signing is per event, never batched blindly",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textSecondary,
            )
        }
        batch.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.warningText,
                modifier = Modifier.padding(horizontal = BitOSSpacing.screen),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
        ) {
            items(document.rows, key = { it.id }) { row ->
                val index = document.rows.indexOf(row) + 1
                val state = document.states[row.id]
                Surface(shape = RoundedCornerShape(12.dp), color = BitOSColors.surface, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(BitOSSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "#$index",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.W700,
                            color = BitOSColors.textSecondary,
                            modifier = Modifier.width(34.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.values[document.recipe.slots.firstOrNull()?.id] ?: row.id,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.W600,
                                maxLines = 1,
                            )
                            Text(
                                when (state?.publish) {
                                    MassPublishState.PUBLISHED -> "published"
                                    MassPublishState.PUBLISHING -> "render → upload → sign…"
                                    MassPublishState.FAILED -> state.failure ?: "failed — will retry on next run"
                                    else -> "waiting"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (state?.publish) {
                                    MassPublishState.PUBLISHED -> BitOSColors.success
                                    MassPublishState.FAILED -> BitOSColors.error
                                    MassPublishState.PUBLISHING -> BitOSColors.primary
                                    else -> BitOSColors.textSecondary
                                },
                            )
                        }
                        when (state?.publish) {
                            MassPublishState.PUBLISHED ->
                                Icon(AppIcons.CheckCircle, contentDescription = null, tint = BitOSColors.success)
                            MassPublishState.PUBLISHING ->
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else -> Unit
                        }
                    }
                }
            }
        }
        Text(
            "Signing is per event — each variant uploads and hash-verifies before its own kind-20 signs.",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            modifier = Modifier.padding(BitOSSpacing.screen),
        )
    }
}

@Composable
private fun SeverityChip(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.W700,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Bounded read of a content URI (≤8 MB — row/master images). */
private fun readBounded(uri: Uri, context: android.content.Context): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > 8 * 1024 * 1024) return null
            buffer.write(chunk, 0, read)
        }
        buffer.toByteArray()
    }
}.getOrNull()

/** Hub batch-queue bar data (scr-home mockup): newest batch progress. */
private data class MassSummary(val name: String, val rows: Int, val published: Int, val awaiting: Int)

private fun massSummary(files: MassBatchFiles): MassSummary? {
    val newest = files.listBatches().firstOrNull() ?: return null
    val document = files.load(newest.batchId) ?: return null
    val published = document.states.values.count {
        it.publish == space.bitos.core.studio.MassPublishState.PUBLISHED
    }
    return MassSummary(
        name = newest.name,
        rows = document.rows.size,
        published = published,
        awaiting = (document.rows.size - published).coerceAtLeast(0),
    )
}
