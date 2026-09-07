package space.bitos.app.ui.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.bitos.app.data.media.BlossomUploader
import space.bitos.app.data.media.DefaultBlossomServer
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.PowCard
import space.bitos.app.ui.components.PowOutcome
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSRadius
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.app.ui.theme.SolarFeedIcon
import space.bitos.app.ui.theme.SolarFeedIconImage
import space.bitos.core.model.Stories
import space.bitos.core.publish.ComposerRules
import space.bitos.core.publish.NoteComposer

/** A completed story mining session — the `dTag` is part of the template,
 *  so publish must commit exactly this (dTag, nonce, target, createdAt). */
data class StoryPowCommit(
    val dTag: String,
    val nonce: Long,
    val targetDifficulty: Int,
    val createdAtSeconds: Long,
)

/**
 * APP-006 story composer (web `StoryComposer` parity): 9:16 preview
 * (gradient text slide, image carousel with caption scrim, or a video
 * slide with duration chip), ≤280-char text, six IG-style gradient
 * backgrounds, ≤6 gallery images uploaded via Blossom BEFORE anything
 * references them, one video per slide (measured duration rides the
 * NIP-92 imeta `duration`; video replaces the carousel — web parity),
 * GIF picks (already-public URLs ride the same imeta carousel), emoji
 * inserts, optional NIP-13 PoW mined over the exact kind-30315 template,
 * alt text + sensitive flag, and a kind-30315 publish through the receipt
 * machine. Stories double as a 24h status note with no media attached.
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun StoryComposerSheet(
    identityViewModel: IdentityViewModel,
    onPublish: (
        text: String,
        imageUrls: List<String>,
        background: String?,
        altText: String,
        sensitive: Boolean,
        videoUrl: String?,
        videoMime: String,
        videoDurationMs: Long?,
        videoPoster: String?,
        pow: StoryPowCommit?,
    ) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploader = remember { BlossomUploader() }
    val identity by identityViewModel.state.collectAsStateWithLifecycle()

    var text by remember { mutableStateOf("") }
    var altText by remember { mutableStateOf("") }
    var sensitive by remember { mutableStateOf(false) }
    var bgIndex by remember { mutableStateOf(0) }
    var uploading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val images = remember { mutableStateListOf<String>() }
    var previewIndex by remember { mutableStateOf(0) }
    var showGif by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var showPow by remember { mutableStateOf(false) }
    var powTarget by remember { mutableStateOf(0) }
    var powOutcome by remember { mutableStateOf<PowOutcome?>(null) }
    // One video per slide (web parity: the first video imeta replaces the
    // carousel in the viewer) — picking either clears the other.
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var videoMime by remember { mutableStateOf("video/mp4") }
    var videoDurationMs by remember { mutableStateOf<Long?>(null) }
    // Poster frame (NIP-92 imeta `thumb`): instant rail/viewer preview
    // while the video streams; a failed extraction just omits it.
    var videoPoster by remember { mutableStateOf<String?>(null) }

    // Fixed for the whole session: the dTag is part of the PoW mining
    // template, so it cannot be regenerated at publish time.
    val storyDTag = remember {
        Stories.storyDTag(System.currentTimeMillis() / 1000, (1..Int.MAX_VALUE).random())
    }

    val hasVideo = videoUrl != null
    val canPost = (text.isNotBlank() || images.isNotEmpty() || hasVideo) && !uploading
    val full = images.size >= Stories.MAX_STORY_IMAGES

    // Web backgrounds — published as CSS tokens both clients parse.
    val backgrounds = listOf(
        listOf(Color(0xFF2F95F6), Color(0xFF55D69A)),
        listOf(Color(0xFFFF755F), Color(0xFFFFB86B)),
        listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)),
        listOf(Color(0xFF0F172A), Color(0xFF334155)),
        listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
        listOf(Color(0xFF10B981), Color(0xFF06B6D4)),
    )
    fun backgroundCss(): String? {
        if (images.isNotEmpty() || hasVideo) return null
        val pair = backgrounds[bgIndex]
        return "linear-gradient(135deg, ${pair[0].toRgbHex()}, ${pair[1].toRgbHex()})"
    }

    /** One bounded video upload (media duration + poster frame read
     *  off-thread; the poster upload is best-effort — it never blocks the
     *  story itself). */
    suspend fun uploadVideo(uri: android.net.Uri) {
        uploading = true
        failure = null
        try {
            val signer = identityViewModel.createSigner()
                ?: throw BlossomUploader.UploadFailure("Posting needs an identity (Profile tab).")
            val mime = context.contentResolver.getType(uri) ?: "video/mp4"
            var measuredMs: Long? = null
            var posterBytes: ByteArray? = null
            withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        measuredMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        // Poster frame near the 1 s mark (web `thumb` parity).
                        retriever.getFrameAtTime(1_000_000)?.let { frame ->
                            val out = java.io.ByteArrayOutputStream()
                            frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                            frame.recycle()
                            posterBytes = out.toByteArray().takeIf { it.isNotEmpty() }
                        }
                    } finally {
                        retriever.release()
                    }
                }
            }
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: throw BlossomUploader.UploadFailure("could not read the picked file")
            // Upload BEFORE the URL is referenced anywhere (PUB-002).
            val media = uploader.upload(bytes, mime, signer, DefaultBlossomServer.url)
            val poster = posterBytes?.let { jpg ->
                runCatching { uploader.upload(jpg, "image/jpeg", signer, DefaultBlossomServer.url).url }.getOrNull()
            }
            images.clear()
            videoMime = mime
            videoDurationMs = measuredMs
            videoPoster = poster
            videoUrl = media.url
        } catch (error: Exception) {
            failure = error.message ?: "Upload failed."
        } finally {
            uploading = false
        }
    }

    val galleryPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let { picked ->
            // Nothing references media that was never uploaded (PUB-002):
            // upload BEFORE the URL joins the carousel.
            scope.launch {
                uploading = true
                failure = null
                try {
                    val signer = identityViewModel.createSigner()
                        ?: throw BlossomUploader.UploadFailure("Posting needs an identity (Profile tab).")
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(picked)?.use { it.readBytes() }
                    } ?: throw BlossomUploader.UploadFailure("could not read the picked file")
                    val media = uploader.upload(
                        bytes,
                        context.contentResolver.getType(picked) ?: "image/png",
                        signer,
                        DefaultBlossomServer.url,
                    )
                    if (images.size < Stories.MAX_STORY_IMAGES) {
                        // Images and the video are mutually exclusive slides.
                        videoUrl = null
                        videoDurationMs = null
                        videoPoster = null
                        images += media.url
                        previewIndex = images.lastIndex
                    }
                } catch (error: Exception) {
                    failure = error.message ?: "Upload failed."
                } finally {
                    uploading = false
                }
            }
        }
    }

    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { picked -> scope.launch { uploadVideo(picked) } }
    }

    Surface(color = BitOSColors.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = BitOSSpacing.lg)
                .padding(bottom = BitOSSpacing.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "New story",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W700,
                    color = BitOSColors.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Cancel") }
            }

            // 9:16 preview (web parity).
            Box(
                Modifier
                    .padding(vertical = BitOSSpacing.md)
                    .align(Alignment.CenterHorizontally)
                    .width(190.dp)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(BitOSRadius.lg))
                    .then(
                        if (images.isNotEmpty() || hasVideo) Modifier.background(Color.Black)
                        else Modifier.background(Brush.linearGradient(backgrounds[bgIndex]))
                    ),
            ) {
                // Prototype `#/story-compose` parity: expiry chip on the preview.
                Text(
                    "24 h · kind-30315",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.W700),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color(0x80000000), RoundedCornerShape(BitOSRadius.pill))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (hasVideo) {
                    // No decode in the preview: poster frame + play glyph +
                    // duration chip — the viewer plays the real stream.
                    videoPoster?.let { poster ->
                        AsyncImage(
                            model = poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .background(Color(0x99000000), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                space.bitos.app.ui.theme.AppIcons.Play,
                                contentDescription = "Video story",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                    videoDurationMs?.let { ms ->
                        Text(
                            formatDuration(ms),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.W700),
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                                .background(Color(0x8C000000), RoundedCornerShape(BitOSRadius.pill))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                } else if (images.isNotEmpty()) {
                    AsyncImage(
                        model = images[previewIndex.coerceIn(0, images.lastIndex)],
                        contentDescription = "Story preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClickLabel = "Next preview image") {
                                if (images.isNotEmpty()) previewIndex = (previewIndex + 1) % images.size
                            },
                    )
                    if (images.size > 1) {
                        Text(
                            "${previewIndex + 1}/${images.size}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.W700),
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .background(Color(0x8C000000), RoundedCornerShape(BitOSRadius.pill))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (text.isNotBlank()) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                                .padding(8.dp),
                        ) {
                            Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 3)
                        }
                    }
                } else {
                    Text(
                        text.ifBlank { "Type a note…" },
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.W800),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(BitOSSpacing.md)
                            .align(Alignment.Center),
                    )
                }
            }

            // Caption + live counter (brand field, legacy Flutter
            // InputDecoration parity).
            space.bitos.app.ui.components.BitosTextField(
                value = text,
                onValueChange = { if (it.length <= 280) text = it },
                placeholder = if (images.isNotEmpty()) "Add a caption…" else "What's on your mind?",
                supportingText = { Text("${text.length}/280", style = MaterialTheme.typography.labelSmall) },
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            // Gradient swatches (text-only slides).
            if (images.isEmpty() && !hasVideo) {
                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = BitOSSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                ) {
                    backgrounds.forEachIndexed { i, pair ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(pair))
                                .border(
                                    width = if (bgIndex == i) 2.dp else 1.dp,
                                    color = if (bgIndex == i) BitOSColors.primary else Color.White.copy(alpha = 0.3f),
                                    shape = CircleShape,
                                )
                                .clickable(onClickLabel = "Use background ${i + 1}") { bgIndex = i },
                        )
                    }
                }
                Text(
                    "No image? Stories double as a 24h status note.",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            // Video tile (replaces the carousel — web parity): play glyph,
            // measured duration, removable.
            if (hasVideo) {
                Row(
                    Modifier.padding(vertical = BitOSSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(BitOSRadius.md))
                                .background(Color(0xFF10151C)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                space.bitos.app.ui.theme.AppIcons.Play,
                                contentDescription = "Video story",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        videoDurationMs?.let { ms ->
                            Text(
                                formatDuration(ms),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.W700),
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 4.dp)
                                    .background(Color(0x8C000000), RoundedCornerShape(BitOSRadius.pill))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                videoUrl = null
                                videoDurationMs = null
                                videoPoster = null
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .background(BitOSColors.surface, CircleShape),
                        ) {
                            Icon(space.bitos.app.ui.theme.AppIcons.Close, contentDescription = "Remove video", modifier = Modifier.size(12.dp))
                        }
                    }
                    Text(
                        "Video plays instead of a photo carousel",
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textSecondary,
                    )
                }
            }

            // Image strip + add tile.
            if (images.isNotEmpty()) {
                LazyRow(
                    Modifier.padding(vertical = BitOSSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                ) {
                    items(images) { url ->
                        Box {
                            AsyncImage(
                                model = url,
                                contentDescription = "Attached image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(BitOSRadius.md))
                                    .clickable(onClickLabel = "Preview image") { previewIndex = images.indexOf(url) },
                            )
                            IconButton(
                                onClick = {
                                    images.remove(url)
                                    previewIndex = previewIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .background(BitOSColors.surface, CircleShape),
                            ) {
                                Icon(space.bitos.app.ui.theme.AppIcons.Close, contentDescription = "Remove image", modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                    if (!full) {
                        item {
                            Box(
                                Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(BitOSRadius.md))
                                    .border(1.dp, BitOSColors.border, RoundedCornerShape(BitOSRadius.md))
                                    .clickable(
                                        onClickLabel = "Add image",
                                    ) { galleryPicker.launch("image/*") },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (uploading) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    SolarFeedIconImage(
                                        icon = SolarFeedIcon.Gallery,
                                        contentDescription = null,
                                        tint = BitOSColors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Alt text + sensitive gate any attached media.
            if (images.isNotEmpty() || hasVideo) {
                space.bitos.app.ui.components.BitosTextField(
                    value = altText,
                    onValueChange = { if (it.length <= 280) altText = it },
                    placeholder = "Describe the media for screen readers (alt text)…",
                    singleLine = false,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Same card chrome as the media-details content-warning gate.
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BitOSColors.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier
                            .clickable(onClickLabel = "Mark as sensitive") { sensitive = !sensitive }
                            .padding(BitOSSpacing.base),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .border(
                                    1.dp,
                                    if (sensitive) Color(0xFFFF755F) else BitOSColors.border,
                                    CircleShape,
                                )
                                .background(if (sensitive) Color(0x26FF755F) else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (sensitive) Text("✓", color = Color(0xFFFF755F), fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(BitOSSpacing.sm))
                        Text(
                            "Mark as sensitive — blur until tapped",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.W600,
                            color = if (sensitive) Color(0xFFFF755F) else BitOSColors.textSecondary,
                        )
                    }
                }
            }

            // Quick actions (note-composer toolbar parity): gallery · video ·
            // GIF · emoji · PoW. A video replaces the photo carousel (web
            // parity), so the photo/GIF entries wait while one is attached.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = BitOSSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StoryActionIcon(SolarFeedIcon.Gallery, "Add image", enabled = !full && !uploading && !hasVideo, active = images.isNotEmpty()) {
                    galleryPicker.launch("image/*")
                }
                StoryActionIcon(SolarFeedIcon.VideoCamera, "Add video", enabled = !uploading, active = hasVideo) {
                    videoPicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly,
                        ),
                    )
                }
                StoryActionIcon(SolarFeedIcon.Film, "Add GIF", enabled = !full && !hasVideo) { showGif = true }
                StoryActionIcon(SolarFeedIcon.Emoji, "Insert emoji") { showEmoji = true }
                StoryActionIcon(
                    SolarFeedIcon.ShieldCheck, "Proof of Work",
                    active = showPow || powOutcome != null,
                    badge = powOutcome?.targetDifficulty?.toString(),
                ) { showPow = !showPow }
            }

            // NIP-13 PoW over the exact kind-30315 template (the nonce tag
            // is appended by the miner; any edit invalidates the session).
            if (showPow) {
                val pubkeyHex = identity.account?.pubkeyHex ?: ""
                val templateKey = remember(text, images, bgIndex, altText, sensitive, storyDTag, videoUrl, videoMime, videoDurationMs, videoPoster) {
                    listOf(
                        text, images.joinToString("|"), bgIndex.toString(), altText, sensitive.toString(), storyDTag,
                        videoUrl.orEmpty(), videoMime, videoDurationMs?.toString().orEmpty(), videoPoster.orEmpty(),
                    ).joinToString("§")
                }
                PowCard(
                    target = powTarget,
                    onTargetChange = { powTarget = it },
                    content = "",
                    pubkeyHex = pubkeyHex,
                    template = { createdAt ->
                        NoteComposer(clock = { createdAt }).composeStory(
                            pubkeyHex, text, images.toList(), backgroundCss(), altText, sensitive, storyDTag,
                            videoUrl, videoMime, videoDurationMs, videoPoster,
                        )
                    },
                    templateKey = templateKey,
                    onMined = { powOutcome = it },
                )
            }

            failure?.let {
                Text(it, color = Color(0xFFE5484D), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(BitOSSpacing.md))
            Button(
                onClick = {
                    if (!canPost) return@Button
                    onPublish(
                        text.trim(),
                        images.toList(),
                        backgroundCss(),
                        altText.trim(),
                        sensitive,
                        videoUrl,
                        videoMime,
                        videoDurationMs,
                        videoPoster,
                        powOutcome?.let { StoryPowCommit(storyDTag, it.nonce, it.targetDifficulty, it.createdAtSeconds) },
                    )
                    onClose()
                },
                enabled = canPost,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
            ) {
                Text(if (uploading) "Uploading…" else "Post · 24h", fontWeight = FontWeight.W700)
            }
            Text(
                "Disappears in 24 hours",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textSecondary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = BitOSSpacing.sm),
            )
        }

        // GIF picker (Giphy search + trending, note-composer parity): the
        // picked URL is already public — it joins the carousel as-is.
        if (showGif) {
            space.bitos.app.ui.create.GifPickerSheet(
                onPick = { gif ->
                    if (images.size < Stories.MAX_STORY_IMAGES) {
                        images += gif.url
                        previewIndex = images.lastIndex
                    }
                    showGif = false
                },
                onDismiss = { showGif = false },
            )
        }

        // Emoji sheet (note-composer parity): appends to the caption.
        if (showEmoji) {
            ModalBottomSheet(onDismissRequest = { showEmoji = false }) {
                Column(Modifier.padding(bottom = BitOSSpacing.xl)) {
                    Text(
                        "Insert emoji",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = BitOSSpacing.screen),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(8),
                        modifier = Modifier
                            .padding(horizontal = BitOSSpacing.base)
                            .height(220.dp),
                    ) {
                        gridItems(ComposerRules.COMPOSER_EMOJIS) { emoji ->
                            IconButton(onClick = {
                                if (text.length + emoji.length <= 280) text += emoji
                                showEmoji = false
                            }) {
                                Text(emoji, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One quick-action icon button (active tint marks a toggled tool). */
@Composable
private fun StoryActionIcon(
    icon: SolarFeedIcon,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> BitOSColors.textTertiary.copy(alpha = 0.4f)
        active -> BitOSColors.primary
        else -> BitOSColors.textSecondary
    }
    Box(
        Modifier
            .size(44.dp)
            .clickable(enabled = enabled, onClickLabel = label) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(Modifier.size(36.dp).background(BitOSColors.primary.copy(alpha = 0.15f), CircleShape))
        }
        SolarFeedIconImage(icon = icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        if (badge != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = BitOSColors.primary,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = Color(0xFF0A0A0F),
                    modifier = Modifier.padding(horizontal = 3.dp),
                )
            }
        }
    }
}

/** `#RRGGBB` for the background CSS token (both clients parse hex pairs). */
private fun Color.toRgbHex(): String = "%06X".format(toArgb() and 0xFFFFFF)

/** `m:ss` for the video duration chips. */
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "${totalSeconds / 60}:${"%02d".format(totalSeconds % 60)}"
}
