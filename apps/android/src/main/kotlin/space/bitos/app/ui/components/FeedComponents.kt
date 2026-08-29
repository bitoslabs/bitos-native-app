package space.bitos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Deterministic hex identicon avatar (APP-022 motif) derived from the
 * pubkey. Remote profile pictures load through the media pipeline later;
 * the identicon is the stable fallback so feed rows never shift layout.
 * Geometry and colors live in [HexIdentity].
 */
@Composable
fun PubkeyAvatar(
    pubkey: String,
    modifier: Modifier = Modifier,
    size: Int = 40,
    pictureUrl: String? = null,
    label: String? = null,
    hasLightning: Boolean = false,
) {
    HexAvatar(
        pubkey = pubkey,
        modifier = modifier,
        size = size,
        imageUrl = pictureUrl,
        label = label,
        hasLightning = hasLightning,
    )
}

/** Compact social counter: 1_234 -> "1.2K". */
fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000f)
    count >= 1_000 -> "%.1fK".format(count / 1_000f)
    else -> count.toString()
}

/** Compact relative time: "12s", "4m", "2h", "6d", "3mo". */
fun formatTimeAgo(createdAtSeconds: Long, nowSeconds: Long): String {
    val age = (nowSeconds - createdAtSeconds).coerceAtLeast(0)
    return when {
        age < 60 -> "${age}s"
        age < 3_600 -> "${age / 60}m"
        age < 86_400 -> "${age / 3_600}h"
        age < 30 * 86_400 -> "${age / 86_400}d"
        else -> "${age / (30 * 86_400)}mo"
    }
}

/** Short author label for unprofiled pubkeys: "abc123…9f". */
fun shortPubkey(pubkey: String): String =
    if (pubkey.length > 16) pubkey.take(8) + "…" + pubkey.takeLast(4) else pubkey
