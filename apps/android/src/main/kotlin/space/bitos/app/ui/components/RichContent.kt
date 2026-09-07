package space.bitos.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import java.net.URL

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import space.bitos.core.nostr.RichToken

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
    maxLines: Int = Int.MAX_VALUE,
    /** Body color override (video captions render white). */
    color: Color = BitOSColors.textPrimary,
    /** Media link URLs already rendered as tiles — hidden from the body. */
    hiddenMediaUrls: Set<String> = emptySet(),
    /** Mention display-name resolver (profile entities show @name). */
    resolveMentionName: ((String) -> String?)? = null,
    /** Non-profile entity tap (note1/nevent1/naddr1) → open thread. */
    onOpenNoteRef: ((String) -> Unit)? = null,
    /** APP-005 Show more/less: reports whether the body overflowed the
     * [maxLines] clamp (only meaningful with a finite limit). */
    onOverflow: ((Boolean) -> Unit)? = null,
    onOpenProfile: ((String) -> Unit)? = null,
    onOpenHashtag: ((String) -> Unit)? = null,
    /** External-link tap interception (confirm sheet); null = open directly. */
    onOpenExternalLink: ((String) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = BitOSColors.primary, fontWeight = FontWeight.W500),
    )
    val annotated = buildAnnotatedString {
        for (token in tokens) {
            when (token) {
                is RichToken.Text -> append(token.value)
                is RichToken.Link -> {
                    // Bare media links render as tiles below — the raw URL
                    // disappears from the body (web parity).
                    if (token.url in hiddenMediaUrls) continue
                    withLink(
                        androidx.compose.ui.text.LinkAnnotation.Url(token.url, linkStyles) {
                            val handler = onOpenExternalLink
                            if (handler != null) {
                                handler(token.url)
                            } else {
                                // External links open the system browser.
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(token.url),
                                        ),
                                    )
                                }
                            }
                        },
                    ) { append(token.label ?: token.url) }
                }
                is RichToken.Hashtag -> withLink(
                    androidx.compose.ui.text.LinkAnnotation.Clickable(
                        tag = "hashtag:${token.tag}",
                        styles = linkStyles,
                        linkInteractionListener = { onOpenHashtag?.invoke(token.tag) },
                    ),
                ) { append("#${token.tag}") }
                is RichToken.Nostr -> {
                    // Web parity: profile mentions resolve to @display-name
                    // once metadata lands; note refs stay shortened ids.
                    val display = if (token.entity == RichToken.Entity.PROFILE) {
                        token.hex?.let { hex ->
                            resolveMentionName?.invoke(hex)?.let { "@$it" }
                        } ?: if (token.raw.length > 14) token.raw.take(10) + "…" else token.raw
                    } else {
                        if (token.raw.length > 14) token.raw.take(10) + "…" else token.raw
                    }
                    withLink(
                        androidx.compose.ui.text.LinkAnnotation.Clickable(
                            tag = "nostr:${token.raw}",
                            styles = linkStyles,
                            linkInteractionListener = {
                                if (token.entity == RichToken.Entity.PROFILE) {
                                    token.hex?.let { hex -> onOpenProfile?.invoke(hex) }
                                } else {
                                    // note1/nevent1/naddr1 → thread (web parity).
                                    onOpenNoteRef?.invoke(token.raw)
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
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(color = color),
        modifier = modifier,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        onTextLayout = { result -> onOverflow?.invoke(result.hasVisualOverflow) },
    )
}

/** Media tiles (≤9): images render inline; bare video links get a
 *  play-glyph tile that opens a fullscreen player. */
@Composable
fun MediaRow(urls: List<String>, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val media = remember(urls) { urls.take(9) }
    if (media.isEmpty()) return
    var playUrl by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        media.chunked(3).forEach { rowUrls ->
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
                            .clickable(onClickLabel = if (isVideoMediaUrl(url)) "Play video" else "Open media") {
                                if (isVideoMediaUrl(url)) playUrl = url else onOpen(url)
                            },
                    ) {
                        if (isVideoMediaUrl(url)) {
                            // Bare video link: gradient tile + play glyph.
                            Box(Modifier.fillMaxSize().background(BitOSColors.surfaceElevated), contentAlignment = Alignment.Center) {
                                Icon(
                                    AppIcons.Play,
                                    contentDescription = null,
                                    tint = BitOSColors.textSecondary,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        } else {
                            RemoteBitmapImage(url = url)
                        }
                    }
                }
                repeat(3 - rowUrls.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
    playUrl?.let { url ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { playUrl = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            FullscreenVideoPlayer(url = url, onDismiss = { playUrl = null })
        }
    }
}

// Canonical video-link test lives in AttachmentPreviewRow.kt (same
// package); RichContent's former private copy was removed to keep one
// helper.

/**
 * imeta video preview tile (comment-sheet origin cards): poster art (or an
 * elevated tile) with a play glyph; tap opens the fullscreen player. The
 * URL lives on `FeedNote.video`, not in content links, so [MediaRow] never
 * sees it — without this tile a Bitz origin card renders an empty body.
 */
@Composable
fun VideoPreviewTile(
    url: String,
    posterUrl: String?,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 9f / 16f,
) {
    var play by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(12.dp))
            .background(BitOSColors.surfaceElevated)
            .clickable(onClickLabel = "Play video") { play = true },
        contentAlignment = Alignment.Center,
    ) {
        posterUrl?.let { poster ->
            coil.compose.AsyncImage(
                model = poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .size(52.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Play, contentDescription = "Play video", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
    if (play) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { play = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            FullscreenVideoPlayer(url = url, onDismiss = { play = false })
        }
    }
}

/** Single-file fullscreen player for bare video links (released on close). */
@Composable
fun FullscreenVideoPlayer(url: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = androidx.compose.runtime.remember(url) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    androidx.compose.runtime.DisposableEffect(url) {
        onDispose { player.release() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    useController = true
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(AppIcons.Close, contentDescription = "Close video", tint = Color.White)
        }
    }
}

/**
 * Remote image tile rendered through the shared Coil loader — the app-wide
 * `ImageLoader` registers the platform GIF decoder, so animated `.gif`
 * attachments play inline (comment sheets, feed tiles, lightbox) instead
 * of decoding a static first frame. The gradient shows until the load
 * lands.
 */
@Composable
fun RemoteBitmapImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(
        modifier
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(BitOSColors.surface, BitOSColors.surfaceElevated),
                ),
            ),
    ) {
        coil.compose.AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching { BitmapFactory.decodeStream(URL(url).openStream()) }.getOrNull()
}

/**
 * Local content-Uri thumbnail (composer picks): bounds-first decode with
 * sampling so a 48 MP photo never lands in memory at full resolution —
 * tile-sized is all the composer grid ever shows.
 */
@Composable
fun LocalUriImage(uri: android.net.Uri, resolver: android.content.ContentResolver, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = loadUriThumbnail(resolver, uri)
    }
    Box(
        modifier.background(
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

suspend fun loadUriThumbnail(resolver: android.content.ContentResolver, uri: android.net.Uri, maxDim: Int = 512): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull()
    }

/** Fullscreen zoomable media viewer (lightbox).
 *
 * The original image is always fitted inside the viewport; it is never
 * cropped. Multi-image notes retain their order so the reader can move
 * between attachments without dismissing and reopening the viewer.
 */
@Composable
fun MediaLightbox(
    urls: List<String>,
    initialUrl: String,
    onDismiss: () -> Unit,
) {
    val media = remember(urls) { urls.distinct() }
    var currentIndex by remember(media, initialUrl) {
        mutableStateOf(media.indexOf(initialUrl).coerceAtLeast(0))
    }
    val currentUrl = media.getOrElse(currentIndex) { initialUrl }
    var scale by remember { mutableStateOf(1f) }
    androidx.compose.runtime.LaunchedEffect(currentUrl) { scale = 1f }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .pointerInput(currentUrl) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale == 1f && zoom == 1f && pan.x > 72f && currentIndex > 0) {
                        currentIndex -= 1
                    } else if (scale == 1f && zoom == 1f && pan.x < -72f && currentIndex < media.lastIndex) {
                        currentIndex += 1
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        RemoteBitmapImage(
            url = currentUrl,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    transformOrigin = TransformOrigin.Center,
                ),
            contentScale = ContentScale.Fit,
        )
        if (media.size > 1) {
            IconButton(
                onClick = { currentIndex -= 1 },
                enabled = currentIndex > 0,
                modifier = Modifier.align(Alignment.CenterStart).padding(8.dp).size(48.dp),
            ) {
                Icon(AppIcons.ChevronLeft, contentDescription = "Previous image", tint = Color.White)
            }
            IconButton(
                onClick = { currentIndex += 1 },
                enabled = currentIndex < media.lastIndex,
                modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp).size(48.dp),
            ) {
                Icon(AppIcons.ChevronRight, contentDescription = "Next image", tint = Color.White)
            }
            Text(
                text = "${currentIndex + 1} of ${media.size}",
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x80000000))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
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

/** Compatibility entry point for single-image surfaces. */
@Composable
fun MediaLightbox(url: String, onDismiss: () -> Unit) =
    MediaLightbox(urls = listOf(url), initialUrl = url, onDismiss = onDismiss)

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
