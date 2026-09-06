package space.bitos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.bitos.app.ui.theme.BitOSColors

/** Video extensions the preview row renders with a play affordance. */
fun isVideoMediaUrl(url: String): Boolean {
    return space.bitos.core.feed.InlineMediaUrls.isVideoUrl(url)
}

private fun isGifUrl(url: String): Boolean = url.substringBeforeLast('#').substringBefore('?').endsWith(".gif")

/**
 * Pending-attachment preview row (legacy Flutter `AttachmentPreviewRow` /
 * web ReplyComposer parity, spec §4): 64 dp rounded tiles with a hairline
 * border — images/GIFs render a cover thumbnail, videos a play affordance
 * over a dark surface, GIFs carry a bottom-left badge; every tile keeps an
 * always-visible ✕ (touch has no hover). Used by the thread reply bar and
 * later the bitz comments composer.
 */
@Composable
fun AttachmentPreviewRow(
    urls: List<String>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (urls.isEmpty()) return
    LazyRow(
        modifier = modifier.height(68.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        itemsIndexed(urls) { index, url ->
            AttachmentTile(url = url, onRemove = { onRemove(index) })
        }
    }
}

@Composable
private fun AttachmentTile(url: String, onRemove: () -> Unit) {
    val video = isVideoMediaUrl(url)
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(0.5.dp, BitOSColors.border.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .background(BitOSColors.surfaceElevated),
    ) {
        if (video) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                    contentDescription = "Video attachment",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        } else {
            RemoteBitmapImage(url = url, modifier = Modifier.fillMaxSize())
        }
        if (isGifUrl(url) && !video) {
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = Color(0xA6000000),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(3.dp),
            ) {
                Text(
                    "GIF",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 0.5.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = Color(0xA6000000),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clickable(onClickLabel = "Remove attachment") { onRemove() },
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.padding(4.dp))
        }
    }
}
