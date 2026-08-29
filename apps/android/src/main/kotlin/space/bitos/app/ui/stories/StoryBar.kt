package space.bitos.app.ui.stories

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.theme.BitOSColors
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
 * APP-006 Stories bar (legacy Flutter `stories_bar` parity): create card
 * first, own stories next, followed authors; gradient hex ring = unseen,
 * muted ring = seen.
 */
@Composable
fun StoriesBar(
    authors: List<StoryAuthor>,
    seenIds: Set<String>,
    onOpenViewer: (StoryAuthor) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = BitOSSpacing.screen),
    ) {
        items(authors, key = { it.pubkey }) { author ->
            StoryAvatar(author, seenIds) { onOpenViewer(author) }
        }
    }
}

@Composable
private fun StoryAvatar(author: StoryAuthor, seenIds: Set<String>, onClick: () -> Unit) {
    val hasUnseen = author.slides.any { it.id !in seenIds }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Ring: gradient hex = unseen, muted = seen.
        Box(
            Modifier
                .size(64.dp)
                .background(
                    if (hasUnseen) {
                        Brush.sweepGradient(listOf(BitOSColors.primary, Color(0xFFFF6B9D), BitOSColors.primary))
                    } else {
                        Brush.sweepGradient(listOf(BitOSColors.border, BitOSColors.border))
                    },
                    CircleShape,
                )
                .clickable(onClickLabel = "View ${shortPubkey(author.pubkey)}'s story") { onClick() },
        ) {
            Box(Modifier.padding(3.dp).fillMaxSize().background(BitOSColors.background, CircleShape), contentAlignment = Alignment.Center) {
                PubkeyAvatar(pubkey = author.pubkey, size = 54)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            shortPubkey(author.pubkey),
            style = MaterialTheme.typography.labelSmall,
            color = if (hasUnseen) BitOSColors.textPrimary else BitOSColors.textTertiary,
            maxLines = 1,
        )
    }
}

/**
 * APP-006 Story viewer (legacy parity): full-screen, tap right/left =
 * next/prev slide, progress bar per slide, auto-advance 5 s.
 */
@Composable
fun StoryViewer(
    author: StoryAuthor,
    initialIndex: Int = 0,
    onSeen: (String) -> Unit,
    onClose: () -> Unit,
) {
    var index by remember { mutableStateOf(initialIndex.coerceIn(0, author.slides.size - 1)) }
    val slide = author.slides.getOrNull(index)

    if (slide == null) {
        onClose()
        return
    }

    // Auto-advance + progress.
    var progress by remember(slide.id) { mutableStateOf(0f) }
    LaunchedEffect(slide.id) {
        onSeen(slide.id)
        while (progress < 1f) {
            delay(50)
            progress += 0.05f / 5.0f // 5 s per slide
        }
        if (index < author.slides.size - 1) index++ else onClose()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                slide.gradient?.let { parseGradient(it) }
                    ?: Brush.verticalGradient(listOf(BitOSColors.background, BitOSColors.background)),
            ),
    ) {
        // Progress bars.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.md, vertical = BitOSSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            author.slides.forEachIndexed { i, s ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .background(
                            if (i < index) BitOSColors.primary
                            else if (i == index) BitOSTransparent
                            else BitOSColors.surfaceOverlay,
                            CircleShape,
                        ),
                )
            }
        }
        // Header.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.base, vertical = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PubkeyAvatar(pubkey = author.pubkey, size = 32)
            Spacer(Modifier.width(BitOSSpacing.sm))
            Text(
                shortPubkey(author.pubkey),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.W700,
                color = Color.White,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("✕", color = Color.White) }
        }
        // Content.
        Text(
            slide.content,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = BitOSSpacing.xl),
        )
        // Tap zones: left = prev, right = next.
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable {
                        if (index > 0) {
                            index--
                            progress = 0f
                        }
                    },
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable {
                        if (index < author.slides.size - 1) {
                            index++
                            progress = 0f
                        } else onClose()
                    },
            )
        }
    }
}

private val BitOSTransparent = Color(0x33FFFFFF)

/** `#hex>to>#hex` → vertical gradient. */
internal fun parseGradient(token: String): Brush {
    val match = Regex("#([0-9a-fA-F]{6})>to>#([0-9a-fA-F]{6})").find(token) ?: return BitOSColors.background.let { Brush.verticalGradient(listOf(it, it)) }
    val from = Color(android.graphics.Color.parseColor("#${match.groupValues[1]}"))
    val to = Color(android.graphics.Color.parseColor("#${match.groupValues[2]}"))
    return Brush.verticalGradient(listOf(from, to))
}
