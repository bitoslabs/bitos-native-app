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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.bitos.app.data.media.BlossomUploader
import space.bitos.app.data.media.DefaultBlossomServer
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSRadius
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.model.Stories

/**
 * APP-006 story composer (web `StoryComposer` parity): 9:16 preview
 * (gradient text slide or image carousel with caption scrim), ≤280-char
 * text, six IG-style gradient backgrounds, ≤6 gallery images uploaded via
 * Blossom BEFORE anything references them, alt text + sensitive flag, and
 * a kind-30315 publish through the receipt machine. Stories double as a
 * 24h status note when no image is attached.
 */
@Composable
fun StoryComposerSheet(
    identityViewModel: IdentityViewModel,
    onPublish: (text: String, imageUrls: List<String>, background: String?, altText: String, sensitive: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploader = remember { BlossomUploader() }

    var text by remember { mutableStateOf("") }
    var altText by remember { mutableStateOf("") }
    var sensitive by remember { mutableStateOf(false) }
    var bgIndex by remember { mutableStateOf(0) }
    var uploading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val images = remember { mutableStateListOf<String>() }
    var previewIndex by remember { mutableStateOf(0) }

    val canPost = (text.isNotBlank() || images.isNotEmpty()) && !uploading
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
        val pair = backgrounds[bgIndex]
        return "linear-gradient(135deg, ${pair[0].toRgbHex()}, ${pair[1].toRgbHex()})"
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

    Surface(color = BitOSColors.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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
                        if (images.isNotEmpty()) Modifier.background(Color.Black)
                        else Modifier.background(Brush.linearGradient(backgrounds[bgIndex]))
                    ),
            ) {
                if (images.isNotEmpty()) {
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

            // Caption + live counter.
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 280) text = it },
                placeholder = { Text(if (images.isNotEmpty()) "Add a caption…" else "What's on your mind?") },
                supportingText = { Text("${text.length}/280", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            // Gradient swatches (text-only slides).
            if (images.isEmpty()) {
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
                                Icon(Icons.Rounded.Close, contentDescription = "Remove image", modifier = Modifier.size(12.dp))
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
                                    Icon(
                                        Icons.Rounded.AddPhotoAlternate,
                                        contentDescription = null,
                                        tint = BitOSColors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = altText,
                    onValueChange = { if (it.length <= 280) altText = it },
                    placeholder = { Text("Describe the images for screen readers (alt text)…") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Mark as sensitive") { sensitive = !sensitive },
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
            } else {
                Row {
                    Button(
                        onClick = { galleryPicker.launch("image/*") },
                        enabled = !uploading,
                    ) {
                        if (uploading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(BitOSSpacing.xs))
                            Text("Uploading…")
                        } else {
                            Text(if (images.isEmpty()) "Image" else "Add")
                        }
                    }
                }
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
                        if (images.isEmpty()) backgroundCss() else null,
                        altText.trim(),
                        sensitive,
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
    }
}

/** `#RRGGBB` for the background CSS token (both clients parse hex pairs). */
private fun Color.toRgbHex(): String = "%06X".format(toArgb() and 0xFFFFFF)
