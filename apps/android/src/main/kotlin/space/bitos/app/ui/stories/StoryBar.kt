package space.bitos.app.ui.stories

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import space.bitos.app.ui.components.formatCount
import space.bitos.app.ui.components.HexShape
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSRadius
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.model.StoryAuthor
import space.bitos.core.model.StorySlide

/** Seen-set persistence (SharedPreferences, device-local). */
object StorySeenPrefs {
    private const val PREFS = "bitos_stories"
    private const val KEY_SEEN = "seen_ids"

    fun seenIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SEEN, emptySet()) ?: emptySet()

    fun markSeen(context: Context, slideId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_SEEN, seenIds(context) + slideId).apply()
    }
}

/**
 * APP-006 Stories bar + viewer (web `StoriesBar`/`StoryViewer` parity):
 * create card first, own stories next, followed authors; gradient hex
 * ring = unseen, muted ring = seen. The ring clip always matches the hex
 * avatar (web `story-ring-frame hex-clip`).
 */

/** Signature story-ring gradient: unseen stories / own story. */
@Composable
private fun unseenRingBrush(): Brush = Brush.linearGradient(
    listOf(BitOSColors.primary, Color(0xFFFF6B9D), BitOSColors.primary),
)

/** Display name (web `nameFor` parity): profile name over the raw key. */
private fun storyDisplayName(
    pubkey: String,
    profileFor: (String) -> space.bitos.core.model.ProfileMetadata?,
): String =
    profileFor(pubkey)?.bestDisplayName?.takeIf { it.isNotBlank() } ?: shortPubkey(pubkey)

/** Layered hex story ring (web `story-ring-frame hex-clip` parity): a
 * 3 dp gradient (unseen) or muted (seen) hex ring, a 2 dp inner gap, then
 * the hex avatar — the ring shape always matches the avatar clip. */
@Composable
private fun StoryRingHexAvatar(
    pubkey: String,
    avatarSize: Int,
    ring: Brush,
    inner: Color = BitOSColors.surface,
    pictureUrl: String? = null,
    hasLightning: Boolean = false,
) {
    Box(
        Modifier
            .size((avatarSize + 10).dp)
            .clip(HexShape())
            .background(ring),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .fillMaxSize()
                .clip(HexShape())
                .background(inner),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.padding(2.dp)) {
                PubkeyAvatar(pubkey = pubkey, size = avatarSize, pictureUrl = pictureUrl, hasLightning = hasLightning)
            }
        }
    }
}

@Composable
fun StoriesBar(
    authors: List<StoryAuthor>,
    publicAuthors: List<StoryAuthor>,
    seenIds: Set<String>,
    onOpenViewer: (StoryAuthor) -> Unit,
    onCreateStory: () -> Unit,
    onOpenPublicStories: () -> Unit,
    /** Kind-0 metadata lookup for display names + avatar pictures. */
    profileFor: (String) -> space.bitos.core.model.ProfileMetadata? = { null },
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = BitOSSpacing.screen),
    ) {
        item(key = "create-story") { CreateStoryCard(onClick = onCreateStory) }
        items(authors, key = { it.pubkey }) { author ->
            StoryCard(author, seenIds, profileFor) { onOpenViewer(author) }
        }
        item(key = "public-stories") { PublicStoriesButton(onClick = onOpenPublicStories) }
        items(publicAuthors, key = { "public-${it.pubkey}" }) { author ->
            StoryCard(author, seenIds, profileFor) { onOpenViewer(author) }
        }
    }
}

@Composable
private fun CreateStoryCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(108.dp)
            .clip(RoundedCornerShape(BitOSRadius.md))
            .background(BitOSColors.surface)
            .clickable(onClickLabel = "Create story", onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(118.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF39485A), Color(0xFF17202B)))),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(BitOSColors.primary, CircleShape)
                    .padding(3.dp)
                    .background(BitOSColors.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(space.bitos.app.ui.theme.AppIcons.Add, contentDescription = null, tint = BitOSColors.primary)
            }
        }
        Text(
            "Create story",
            modifier = Modifier.padding(horizontal = BitOSSpacing.sm, vertical = BitOSSpacing.base),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.W700,
            color = BitOSColors.textPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun StoryCard(
    author: StoryAuthor,
    seenIds: Set<String>,
    profileFor: (String) -> space.bitos.core.model.ProfileMetadata?,
    onClick: () -> Unit,
) {
    val hasUnseen = author.slides.any { it.id !in seenIds }
    val latest = author.slides.first()
    val gradient = latest.gradient
    val storyBackground = if (gradient != null) {
        parseGradient(gradient)
    } else {
        Brush.verticalGradient(listOf(BitOSColors.surfaceElevated, BitOSColors.background))
    }
    Box(
        modifier = Modifier
            .width(108.dp)
            .height(154.dp)
            .clip(RoundedCornerShape(BitOSRadius.md))
            .background(BitOSColors.surface)
            .clickable(onClickLabel = "View ${shortPubkey(author.pubkey)}'s story", onClick = onClick),
    ) {
        // Web parity: video tiles preview the poster, image tiles the first
        // image; a play badge marks video slides.
        val previewUrl = latest.videoPoster ?: latest.imageUrls.firstOrNull() ?: latest.imageUrl
        if (previewUrl != null) {
            AsyncImage(
                model = previewUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(storyBackground),
                contentAlignment = Alignment.Center,
            ) {
                // Web parity: gradient tiles preview the note text.
                Text(
                    author.slides.firstOrNull { it.content.isNotBlank() }?.content
                        ?: shortPubkey(author.pubkey),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W700,
                        shadow = Shadow(color = Color.Black.copy(alpha = 0.45f), blurRadius = 2f, offset = Offset(0f, 1f)),
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    modifier = Modifier.padding(
                        start = BitOSSpacing.md, end = BitOSSpacing.md,
                        top = BitOSSpacing.md, bottom = 32.dp,
                    ),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                .padding(horizontal = BitOSSpacing.sm, vertical = BitOSSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(storyDisplayName(author.pubkey, profileFor), style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
                if (!profileFor(author.pubkey)?.nip05.isNullOrBlank()) {
                    Icon(
                        space.bitos.app.ui.theme.AppIcons.CheckCircle,
                        contentDescription = "NIP-05 identity claim",
                        tint = Color.White,
                        modifier = Modifier.padding(start = 3.dp).size(12.dp),
                    )
                }
            }
        }
        // Hex ring (web `hex-clip` parity): gradient = unseen, muted = seen.
        Box(Modifier.padding(BitOSSpacing.sm)) {
            StoryRingHexAvatar(
                pubkey = author.pubkey,
                avatarSize = 34,
                ring = if (hasUnseen) {
                    unseenRingBrush()
                } else {
                    Brush.linearGradient(listOf(BitOSColors.border, BitOSColors.border))
                },
                pictureUrl = profileFor(author.pubkey)?.picture,
                hasLightning = !profileFor(author.pubkey)?.lud16.isNullOrBlank(),
            )
        }
        if (author.isPublicDiscovery || latest.videoUrl != null) {
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(BitOSSpacing.sm),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (latest.videoUrl != null) {
                    Box(
                        Modifier.size(24.dp).background(Color(0x8C000000), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            space.bitos.app.ui.theme.AppIcons.Play,
                            contentDescription = "Video story",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                if (author.isPublicDiscovery) {
                    Text(
                        "Public",
                        modifier = Modifier
                            .background(Color(0xB33B2D12), RoundedCornerShape(BitOSRadius.pill))
                            .padding(horizontal = BitOSSpacing.xs, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.W700,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun PublicStoriesButton(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .height(154.dp)
            .clickable(onClickLabel = "Browse public stories", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(30.dp).background(Color(0x1F24DFA0), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(space.bitos.app.ui.theme.AppIcons.Compass, contentDescription = null, tint = Color(0xFF24DFA0), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(BitOSSpacing.sm))
        Text(
            "Public stories",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            maxLines = 2,
        )
    }
}

/**
 * APP-006 Story viewer (web `StoryViewer` parity): full-screen 9:16 canvas,
 * white progress bars, header with hex-ringed avatar + relative time +
 * pause/close, image slides full-bleed with a caption scrim (animated GIFs
 * play inline), carousels with dots + per-image timers, video slides via a
 * controls-free ExoPlayer, sensitive media blurred until revealed, text
 * slides centered on their gradient, left-third / right-two-thirds tap
 * zones (double-tap = like burst), reply/DM input row with like/zap/
 * activity actions and a counts row, slide counter pill.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StoryViewer(
    author: StoryAuthor,
    initialIndex: Int = 0,
    /** Engagement lookup for the CURRENT slide (viewer-side, per-slide ids). */
    interactionFor: (String) -> space.bitos.app.data.stories.StoryInteractionUi? = { null },
    /** Kind-0 metadata lookup for display names + avatar pictures. */
    profileFor: (String) -> space.bitos.core.model.ProfileMetadata? = { null },
    /** True for the signed-in account's own slides (delete + view count). */
    isMine: Boolean = false,
    /** Signed-in state gates the reply input (web "Sign in to reply"). */
    hasIdentity: Boolean = false,
    onLike: (space.bitos.core.model.StorySlide) -> Unit = {},
    /** Unlike publishes a kind-5 delete of MY like event id. */
    onUnlike: (String) -> Unit = {},
    onReply: (space.bitos.core.model.StorySlide, String) -> Unit = { _, _ -> },
    onDm: (String) -> Unit = {},
    /** Zap request carrying the CURRENT slide (its id is the zap target). */
    onZap: (space.bitos.core.model.StorySlide) -> Unit = {},
    onDelete: (space.bitos.core.model.StorySlide) -> Unit = {},
    onSeen: (String) -> Unit,
    onClose: () -> Unit,
) {
    var index by remember { mutableStateOf(initialIndex.coerceIn(0, author.slides.size - 1)) }
    var imageIndex by remember { mutableStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var measuredVideoMs by remember { mutableStateOf(0L) }
    // Engagement UI state (web parity): reply/DM modes, activity sheet,
    // delete confirm, double-tap heart burst.
    var replyMode by remember { mutableStateOf("reply") }
    var replyText by remember { mutableStateOf("") }
    var activityOpen by remember { mutableStateOf(false) }
    var confirmDeleteOpen by remember { mutableStateOf(false) }
    var burstAt by remember { mutableStateOf<Offset?>(null) }
    val burstScale = remember { androidx.compose.animation.core.Animatable(0.6f) }
    val slide = author.slides.getOrNull(index)

    if (slide == null) {
        onClose()
        return
    }

    val interaction = interactionFor(slide.id)

    var imageFailed by remember(slide.id) { mutableStateOf(false) }
    val images = slide.imageUrls
    // StorySlide is supplied by BusinessCore. Snapshot the nullable public
    // property once so Kotlin does not need to smart-cast across the module
    // boundary (and the slide used for the predicate is the one we play).
    val videoUrl = slide.videoUrl

    // Video slides replace the image carousel entirely (web parity).
    val isVideo = videoUrl != null && images.isEmpty()
    // Sensitive image slides stay blurred until the viewer taps to reveal.
    val hidden = slide.sensitive && images.isNotEmpty() && !revealed

    // Carousel-first navigation (web `advance`/`back` parity).
    fun advance() {
        if (imageIndex < images.size - 1) {
            imageIndex++
        } else if (index < author.slides.size - 1) {
            index++
        } else {
            onClose()
        }
    }

    fun back() {
        if (imageIndex > 0) {
            imageIndex--
        } else if (index > 0) {
            index--
        }
    }

    /** Like the current slide (double-tap + heart button path). */
    fun likeCurrent() {
        if (!hasIdentity) return
        val eventId = interaction?.myLikeEventId
        if (interaction?.likedByMe == true && eventId != null) {
            onUnlike(eventId)
        } else {
            onLike(slide)
        }
    }

    // Auto-advance + progress. Progress resets per carousel frame so every
    // image gets a full segment. Web parity: images 5 s per frame, text-only
    // 7 s, video its measured (or imeta-declared) duration capped at 60 s
    // with a 15 s fallback — re-read each tick so a late measurement counts.
    var progress by remember(slide.id, imageIndex) { mutableStateOf(0f) }
    LaunchedEffect(slide.id, imageIndex) {
        imageFailed = false
        onSeen(slide.id)
        val declaredMs = slide.videoDurationMs ?: 0L
        while (progress < 1f) {
            delay(50)
            if (paused) continue
            val seconds = if (isVideo) {
                val ms = when {
                    measuredVideoMs > 0 -> measuredVideoMs
                    declaredMs > 0 -> declaredMs
                    else -> 15_000L
                }
                minOf(ms, 60_000L) / 1000f
            } else if (images.isNotEmpty()) 5f else 7f
            progress += 0.05f / seconds
        }
        advance()
    }
    // New slide: restart the carousel and re-hide sensitive media.
    LaunchedEffect(index) {
        imageIndex = 0
        revealed = false
        measuredVideoMs = 0L
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Phone-portrait fills the screen; wider screens (tablet/landscape)
        // letterbox a 9:16 canvas (web `aspect-[9/16]` parity).
        BoxWithConstraints {
            val canvas = if (maxWidth / maxHeight > 9f / 16f) {
                Modifier.align(Alignment.Center).aspectRatio(9f / 16f)
            } else {
                Modifier.fillMaxSize()
            }
            Box(canvas) {
                // Slide backdrop + caption.
                if (isVideo) {
                    // `isVideo` is derived from this same immutable snapshot.
                    val url = requireNotNull(videoUrl)
                    Box(Modifier.fillMaxSize()) {
                        slide.videoPoster?.let { poster ->
                            AsyncImage(
                                model = poster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        StoryVideoSurface(
                            url = url,
                            paused = paused,
                            modifier = Modifier.fillMaxSize(),
                            onEnded = { advance() },
                            onDurationMeasured = { measuredVideoMs = it },
                        )
                    }
                } else if (images.isNotEmpty() && images.indices.contains(imageIndex) && !imageFailed) {
                    Box(Modifier.fillMaxSize()) {
                        // Animated GIFs play inline via the shared Coil loader.
                        AsyncImage(
                            model = images[imageIndex],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            onError = { imageFailed = true },
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(if (hidden) 30.dp else 0.dp),
                        )
                        if (hidden) Box(Modifier.fillMaxSize().background(Color(0x80000000)))
                        if (!hidden && slide.content.isNotBlank()) {
                            CaptionScrim(slide.content, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                } else {
                    Box(
                        Modifier.fillMaxSize().background(
                            slide.gradient?.let { parseGradient(it) }
                                ?: Brush.verticalGradient(listOf(BitOSColors.background, BitOSColors.background)),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Text-only slide; a broken image keeps its caption
                        // legible (web "Image unavailable" fallback).
                        val text = slide.content.ifBlank { if (imageFailed) "Image unavailable" else "" }
                        if (text.isNotEmpty()) {
                            Text(
                                text,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.W800,
                                ),
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = BitOSSpacing.xl),
                            )
                        }
                    }
                }

                // Tap zones (web: left third = previous, right two thirds =
                // next; double-tap = like burst), under the reveal gate +
                // header chrome so their taps win.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { offset ->
                                    if (offset.x < size.width / 3) back() else advance()
                                },
                                onDoubleTap = { offset ->
                                    burstAt = offset
                                    likeCurrent()
                                },
                            )
                        },
                )

                // Sensitive media gate (web parity): blur + tap-to-reveal.
                if (hidden) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0x4D000000))
                            .clickable(onClickLabel = "Sensitive content. Tap to view") { revealed = true },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        space.bitos.app.ui.theme.SolarFeedIconImage(
                            icon = space.bitos.app.ui.theme.SolarFeedIcon.EyeClosed,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color(0x99000000), CircleShape)
                                .padding(14.dp),
                        )
                        Spacer(Modifier.height(BitOSSpacing.base))
                        Text(
                            "Sensitive content · tap to view",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.W700,
                            color = Color.White,
                        )
                    }
                }

                // Chrome: progress bars + dots + header, clear of the status bar.
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    // White-on-white/30 bars with an animated fill (web parity).
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BitOSSpacing.md, vertical = BitOSSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        author.slides.forEachIndexed { i, _ ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.3f)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(
                                            when {
                                                i < index -> 1f
                                                i == index -> progress.coerceIn(0f, 1f)
                                                else -> 0f
                                            },
                                        )
                                        .clip(CircleShape)
                                        .background(Color.White),
                                )
                            }
                        }
                    }
                    // Carousel dots for multi-image slides (web parity).
                    if (images.size > 1) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            images.indices.forEach { i ->
                                Box(
                                    Modifier
                                        .padding(horizontal = 3.dp)
                                        .size(width = if (i == imageIndex) 16.dp else 6.dp, height = 6.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(if (i == imageIndex) 1f else 0.4f))
                                        .clickable(
                                            onClickLabel = "Image ${i + 1} of ${images.size}",
                                        ) { imageIndex = i },
                                )
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BitOSSpacing.md, vertical = BitOSSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StoryRingHexAvatar(
                            pubkey = author.pubkey,
                            avatarSize = 32,
                            ring = unseenRingBrush(),
                            inner = Color(0x73000000), // black/45 scrim
                            pictureUrl = profileFor(author.pubkey)?.picture,
                            hasLightning = !profileFor(author.pubkey)?.lud16.isNullOrBlank(),
                        )
                        Spacer(Modifier.width(BitOSSpacing.sm))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    storyDisplayName(author.pubkey, profileFor),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.W700,
                                    ),
                                    color = Color.White,
                                    maxLines = 1,
                                )
                                if (!profileFor(author.pubkey)?.nip05.isNullOrBlank()) {
                                    Icon(
                                        space.bitos.app.ui.theme.AppIcons.CheckCircle,
                                        contentDescription = "NIP-05 identity claim",
                                        tint = BitOSColors.primary,
                                        modifier = Modifier.padding(start = 3.dp).size(13.dp),
                                    )
                                }
                            }
                            Text(
                                formatTimeAgo(slide.createdAt, System.currentTimeMillis() / 1000),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (isMine) {
                            IconButton(onClick = { confirmDeleteOpen = true }, modifier = Modifier.size(32.dp)) {
                                space.bitos.app.ui.theme.SolarFeedIconImage(
                                    icon = space.bitos.app.ui.theme.SolarFeedIcon.Trash,
                                    contentDescription = "Delete story",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        IconButton(onClick = { paused = !paused }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (paused) space.bitos.app.ui.theme.AppIcons.Play else space.bitos.app.ui.theme.AppIcons.Pause,
                                contentDescription = if (paused) "Play" else "Pause",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                            Icon(
                                space.bitos.app.ui.theme.AppIcons.Close,
                                contentDescription = "Close story",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // Double-tap heart burst (web like-burst, simplified).
                burstAt?.let { position ->
                    LaunchedEffect(position) {
                        burstScale.snapTo(0.55f)
                        burstScale.animateTo(1.15f, androidx.compose.animation.core.tween(160))
                        burstScale.animateTo(1f, androidx.compose.animation.core.tween(90))
                        delay(430)
                        burstAt = null
                    }
                    space.bitos.app.ui.theme.SolarFeedIconImage(
                        icon = space.bitos.app.ui.theme.SolarFeedIcon.HeartFilled,
                        contentDescription = null,
                        tint = Color(0xFFFF4D67),
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (position.x - 44.dp.toPx()).roundToInt(),
                                    (position.y - 44.dp.toPx()).roundToInt(),
                                )
                            }
                            .size(88.dp)
                            .scale(burstScale.value),
                    )
                }

                // Interactions (web parity): slide counter, reply/DM modes,
                // input + like/zap/activity row, counts.
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .background(
                            Brush.verticalGradient(listOf(Color(0xC0000000), Color(0x40000000), Color.Transparent))
                        )
                        .padding(horizontal = BitOSSpacing.md)
                        .padding(top = BitOSSpacing.lg, bottom = BitOSSpacing.sm),
                ) {
                    if (author.slides.size > 1) {
                        val carousel = if (images.size > 1) " · ${imageIndex + 1}/${images.size}" else ""
                        Text(
                            "${index + 1} / ${author.slides.size}$carousel",
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(bottom = 6.dp)
                                .background(Color(0x66000000), RoundedCornerShape(BitOSRadius.pill))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.W600,
                        ),
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    }

                    // Mode segmented control + privacy hint (web parity).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier
                                .background(Color(0x1AFFFFFF), RoundedCornerShape(BitOSRadius.pill))
                                .padding(1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StoryModeChip("Reply", replyMode == "reply") { replyMode = "reply"; paused = true }
                            StoryModeChip("DM", replyMode == "dm") { replyMode = "dm"; paused = true }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (replyMode == "reply") "Visible in story activity" else "Only sent privately",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.W600,
                            ),
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                    Spacer(Modifier.height(BitOSSpacing.sm))
                    // Input + like/zap/activity actions.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(BitOSRadius.pill))
                                .background(Color(0x1AFFFFFF))
                                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(BitOSRadius.pill)),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BasicTextField(
                                value = replyText,
                                onValueChange = { replyText = it },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp,
                                    color = Color.White,
                                ),
                                singleLine = true,
                                cursorBrush = SolidColor(Color.White),
                                decorationBox = { inner ->
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = BitOSSpacing.base)
                                    ) {
                                        if (replyText.isEmpty()) {
                                            Text(
                                                if (!hasIdentity) "Sign in to reply"
                                                else if (replyMode == "reply") "Reply to ${storyDisplayName(author.pubkey, profileFor)}…"
                                                else "Message ${storyDisplayName(author.pubkey, profileFor)} privately…",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                                color = Color(0x99FFFFFF),
                                                maxLines = 1,
                                            )
                                        }
                                        inner()
                                    }
                                },
                                enabled = hasIdentity,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { paused = it.isFocused },
                            )
                        }
                        IconButton(
                            onClick = {
                                val text = replyText.trim()
                                if (text.isEmpty()) return@IconButton
                                if (replyMode == "dm") onDm(text) else onReply(slide, text)
                                replyText = ""
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            space.bitos.app.ui.theme.SolarFeedIconImage(
                                icon = if (replyMode == "reply") space.bitos.app.ui.theme.SolarFeedIcon.Comment else space.bitos.app.ui.theme.SolarFeedIcon.Send,
                                contentDescription = if (replyMode == "reply") "Reply to story" else "Message privately",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(onClick = { likeCurrent() }, modifier = Modifier.size(40.dp)) {
                            space.bitos.app.ui.theme.SolarFeedIconImage(
                                icon = if (interaction?.likedByMe == true) space.bitos.app.ui.theme.SolarFeedIcon.HeartFilled else space.bitos.app.ui.theme.SolarFeedIcon.Heart,
                                contentDescription = if (interaction?.likedByMe == true) "Unlike story" else "Like story",
                                tint = if (interaction?.likedByMe == true) Color(0xFFFF4D67) else Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(onClick = { onZap(slide) }, modifier = Modifier.size(40.dp)) {
                            space.bitos.app.ui.theme.SolarFeedIconImage(
                                icon = space.bitos.app.ui.theme.SolarFeedIcon.Zap,
                                contentDescription = "Zap sats to this story",
                                tint = Color(0xFFFFC24B),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(onClick = { activityOpen = true }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                space.bitos.app.ui.theme.AppIcons.ArrowUp,
                                contentDescription = "View activity",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Counts row (own slides add the view count, web parity).
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StoryCountChip(
                            icon = { tint ->
                                space.bitos.app.ui.theme.SolarFeedIconImage(
                                    space.bitos.app.ui.theme.SolarFeedIcon.Heart, null, tint, Modifier.size(14.dp),
                                )
                            },
                            label = "${interaction?.likeCount ?: 0}",
                            onClick = { activityOpen = true },
                        )
                        Spacer(Modifier.width(BitOSSpacing.md))
                        if ((interaction?.zapSats ?: 0L) > 0L || (interaction?.zapCount ?: 0) > 0) {
                            StoryCountChip(
                                icon = { tint ->
                                    space.bitos.app.ui.theme.SolarFeedIconImage(
                                        space.bitos.app.ui.theme.SolarFeedIcon.Zap, null, tint, Modifier.size(14.dp),
                                    )
                                },
                                label = if ((interaction?.zapSats ?: 0L) > 0L) {
                                    "${formatCount(interaction?.zapSats ?: 0L)} sats"
                                } else {
                                    "${interaction?.zapCount ?: 0}"
                                },
                                tint = Color(0xFFFFC24B),
                                onClick = { onZap(slide) },
                            )
                            Spacer(Modifier.width(BitOSSpacing.md))
                        }
                        if (isMine) {
                            StoryCountChip(
                                icon = { tint ->
                                    Icon(
                                        space.bitos.app.ui.theme.AppIcons.Visibility,
                                        contentDescription = null,
                                        tint = tint,
                                        modifier = Modifier.size(14.dp),
                                    )
                                },
                                label = "${interaction?.viewCount ?: 0}",
                                onClick = { activityOpen = true },
                            )
                            Spacer(Modifier.width(BitOSSpacing.md))
                        }
                        StoryCountChip(
                            icon = { tint ->
                                space.bitos.app.ui.theme.SolarFeedIconImage(
                                    space.bitos.app.ui.theme.SolarFeedIcon.Comment, null, tint, Modifier.size(14.dp),
                                )
                            },
                            label = "${interaction?.replyCount ?: 0}",
                            onClick = { activityOpen = true },
                        )
                    }
                }
            }
        }

        // Activity sheet (web StoryActivity parity): likes + replies.
        if (activityOpen) {
            androidx.compose.material3.ModalBottomSheet(onDismissRequest = { activityOpen = false }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BitOSSpacing.lg)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        "Story activity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.W700,
                        color = BitOSColors.textPrimary,
                    )
                    Spacer(Modifier.height(BitOSSpacing.md))
                    val likes = interaction?.likes.orEmpty()
                    val replies = interaction?.replies.orEmpty()
                    if (likes.isEmpty() && replies.isEmpty()) {
                        Text(
                            "No activity yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BitOSColors.textSecondary,
                        )
                    }
                    likes.forEach { like ->
                        Row(
                            Modifier.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PubkeyAvatar(
                                pubkey = like.pubkey, size = 28,
                                pictureUrl = profileFor(like.pubkey)?.picture,
                                hasLightning = !profileFor(like.pubkey)?.lud16.isNullOrBlank(),
                            )
                            Spacer(Modifier.width(BitOSSpacing.sm))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    storyDisplayName(like.pubkey, profileFor),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BitOSColors.textPrimary,
                                )
                                if (!profileFor(like.pubkey)?.nip05.isNullOrBlank()) {
                                    Icon(
                                        space.bitos.app.ui.theme.AppIcons.CheckCircle,
                                        contentDescription = "NIP-05 identity claim",
                                        tint = BitOSColors.primary,
                                        modifier = Modifier.padding(start = 4.dp).size(13.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(like.emoji, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (likes.isNotEmpty() && replies.isNotEmpty()) {
                        Spacer(Modifier.height(BitOSSpacing.sm))
                        androidx.compose.material3.HorizontalDivider()
                        Spacer(Modifier.height(BitOSSpacing.sm))
                    }
                    replies.forEach { reply ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PubkeyAvatar(
                                    pubkey = reply.pubkey, size = 28,
                                    pictureUrl = profileFor(reply.pubkey)?.picture,
                                    hasLightning = !profileFor(reply.pubkey)?.lud16.isNullOrBlank(),
                                )
                                Spacer(Modifier.width(BitOSSpacing.sm))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        storyDisplayName(reply.pubkey, profileFor),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BitOSColors.textPrimary,
                                    )
                                    if (!profileFor(reply.pubkey)?.nip05.isNullOrBlank()) {
                                        Icon(
                                            space.bitos.app.ui.theme.AppIcons.CheckCircle,
                                            contentDescription = "NIP-05 identity claim",
                                            tint = BitOSColors.primary,
                                            modifier = Modifier.padding(start = 4.dp).size(13.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    formatTimeAgo(reply.at, System.currentTimeMillis() / 1000),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BitOSColors.textSecondary,
                                )
                            }
                            Text(
                                reply.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = BitOSColors.textPrimary,
                                modifier = Modifier.padding(start = 36.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        // Own-story delete confirm (web `deleteSlide` dialog parity).
        if (confirmDeleteOpen) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmDeleteOpen = false },
                title = { Text("Delete story", fontWeight = FontWeight.W700) },
                text = {
                    Text(
                        "Delete this story from your profile? BitOS will publish a delete event to your relays and remove this story from your device."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            confirmDeleteOpen = false
                            onDelete(slide)
                        },
                    ) { Text("Delete", color = Color(0xFFE5484D), fontWeight = FontWeight.W700) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmDeleteOpen = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

/** Reply/DM segmented chip (web pill parity). */
@Composable
private fun StoryModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.W600),
        color = if (selected) Color.Black else Color.White.copy(alpha = 0.85f),
        modifier = Modifier
            .clip(RoundedCornerShape(BitOSRadius.pill))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** One count chip in the viewer's counts row (web parity). The icon slot
 *  takes the chip tint so Solar painters and Material vectors match. */
@Composable
private fun StoryCountChip(
    icon: @Composable (Color) -> Unit,
    label: String,
    tint: Color = Color.White.copy(alpha = 0.85f),
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(BitOSRadius.pill))
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { icon(tint) }
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.W600),
            color = tint,
        )
    }
}

/** Caption over a bottom scrim (web parity). */
@Composable
private fun CaptionScrim(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            .padding(horizontal = BitOSSpacing.lg, vertical = 20.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.W600,
            ),
            color = Color.White,
        )
    }
}

/** Controls-free, muted, aspect-fill video surface for story slides
 *  (web `<video>` parity). Reports measured duration + playback end. */
@Composable
private fun StoryVideoSurface(
    url: String,
    paused: Boolean,
    modifier: Modifier = Modifier,
    onEnded: () -> Unit,
    onDurationMeasured: (Long) -> Unit,
) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(url) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded()
                if (playbackState == Player.STATE_READY) {
                    val duration = player.duration
                    if (duration != C.TIME_UNSET && duration > 0) {
                        onDurationMeasured(duration)
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(paused) { player.playWhenReady = !paused }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = player
            }
        },
        modifier = modifier,
    )
}


/** `#hex>to>#hex` → vertical gradient. */
@Composable
internal fun parseGradient(token: String): Brush {
    val match = Regex("#([0-9a-fA-F]{6})>to>#([0-9a-fA-F]{6})").find(token) ?: return BitOSColors.background.let { Brush.verticalGradient(listOf(it, it)) }
    val from = Color(android.graphics.Color.parseColor("#${match.groupValues[1]}"))
    val to = Color(android.graphics.Color.parseColor("#${match.groupValues[2]}"))
    return Brush.verticalGradient(listOf(from, to))
}
