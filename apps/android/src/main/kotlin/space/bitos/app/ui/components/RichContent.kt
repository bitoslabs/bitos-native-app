package space.bitos.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.core.nostr.RichToken
import java.net.URL

/**
 * APP-005 rich content (unified feature spec §3.5): NIP-27 rich body,
 * media tiles with a fullscreen zoomable viewer, and the NIP-36 sensitive
 * cover. Tokens come from the shared `Nip27` tokenizer (single source of
 * truth on both platforms).
 */

/** NIP-27 rich body: entities, links and hashtags tappable. */
@Composable
fun RichText(
    tokens: List<RichToken>,
    modifier: Modifier = Modifier,
    onOpenProfile: ((String) -> Unit)? = null,
    onOpenHashtag: ((String) -> Unit)? = null,
) {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = BitOSColors.primary, fontWeight = FontWeight.W500),
    )
    val annotated = buildAnnotatedString {
        for (token in tokens) {
            when (token) {
                is RichToken.Text -> append(token.value)
                is RichToken.Link -> withLink(
                    androidx.compose.ui.text.LinkAnnotation.Url(token.url, linkStyles) {
                        // External links: styled only for now; in-app routing
                        // lands with thread deep links (APP-009).
                    },
                ) { append(token.url) }
                is RichToken.Hashtag -> withLink(
                    androidx.compose.ui.text.LinkAnnotation.Clickable(
                        tag = "hashtag:${token.tag}",
                        styles = linkStyles,
                        linkInteractionListener = { onOpenHashtag?.invoke(token.tag) },
                    ),
                ) { append("#${token.tag}") }
                is RichToken.Nostr -> {
                    val display = if (token.raw.length > 14) token.raw.take(10) + "…" else token.raw
                    withLink(
                        androidx.compose.ui.text.LinkAnnotation.Clickable(
                            tag = "nostr:${token.raw}",
                            styles = linkStyles,
                            linkInteractionListener = {
                                // Only profile entities open in V1 (author sheet);
                                // note/address entities land with threads (APP-009).
                                if (token.entity == RichToken.Entity.PROFILE) {
                                    token.hex?.let { hex -> onOpenProfile?.invoke(hex) }
                                }
                            },
                        ),
                    ) { append(display) }
                }
            }
        }
    }
    Text(
        annotated,
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(color = BitOSColors.textPrimary),
        modifier = modifier,
    )
}

/** Image tiles (≤9) with the video pager owning video notes. */
@Composable
fun MediaRow(urls: List<String>, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val images = remember(urls) {
        urls.filter {
            val ext = it.substringAfterLast('.', "").lowercase()
            ext in setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "apng")
        }.take(9)
    }
    if (images.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        images.chunked(3).forEach { rowUrls ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowUrls.forEach { url ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClickLabel = "Open media") { onOpen(url) },
                    ) {
                        RemoteBitmapImage(url = url)
                    }
                }
                repeat(3 - rowUrls.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun RemoteBitmapImage(url: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, url) {
        value = loadBitmap(url)
    }
    Box(
        modifier
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(BitOSColors.surface, BitOSColors.surfaceElevated),
                ),
            ),
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching { BitmapFactory.decodeStream(URL(url).openStream()) }.getOrNull()
}

/** Fullscreen zoomable media viewer (lightbox). */
@Composable
fun MediaLightbox(url: String, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .pointerInput(url) {
                detectTransformGestures { _, _, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                }
            }
            .clickable(onClickLabel = "Close media") { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        RemoteBitmapImage(
            url = url,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    transformOrigin = TransformOrigin.Center,
                ),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0x80000000))
                .clickable(onClickLabel = "Close media") { onDismiss() }
                .padding(10.dp)
                .semantics { contentDescription = "Close media" },
        ) {
            Icon(AppIcons.Close, contentDescription = null, tint = Color.White, modifier = Modifier.height(16.dp))
        }
    }
}

/** NIP-36 sensitive cover with per-session reveal. */
@Composable
fun SensitiveCover(onReveal: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BitOSColors.surfaceElevated)
            .semantics { contentDescription = "Sensitive content hidden. Show button." },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = BitOSColors.textTertiary, modifier = Modifier.height(28.dp))
        Text(
            "Sensitive content",
            fontSize = 14.sp,
            fontWeight = FontWeight.W600,
            color = BitOSColors.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedButton(onClick = onReveal, modifier = Modifier.padding(top = 8.dp)) { Text("Show") }
    }
}
