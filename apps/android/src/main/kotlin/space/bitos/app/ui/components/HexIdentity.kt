package space.bitos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Hex identity system (unified feature spec §2.5/§4, APP-022): the BitOS
 * sovereign-identity motif. `HexShape` is the flat-top hexagon clip shared
 * by avatars, badges and brand tiles (web `.hex-clip` / Flutter `HexShape`
 * / iOS `HexShape` parity); `HexAvatar` is the deterministic pubkey avatar
 * used by every surface until remote profile pictures load.
 */

/** Pure geometry + identity-color derivation (JVM-testable, no UI). */
object HexIdentity {

    /** Flat-top hexagon vertices inscribed in the box
     * (CSS `polygon(25% 6.7%, 75% 6.7%, 100% 50%, 75% 93.3%, 25% 93.3%, 0% 50%)`
     * parity with the web `.hex-clip` / Flutter `hexPoints`: a *regular*
     * flat-top hexagon touching the left/right edges with a 6.7% vertical
     * inset — not one stretched to fill the square). */
    fun vertices(width: Float, height: Float): List<Offset> = listOf(
        Offset(width * 0.25f, height * 0.067f),
        Offset(width * 0.75f, height * 0.067f),
        Offset(width, height * 0.5f),
        Offset(width * 0.75f, height * 0.933f),
        Offset(width * 0.25f, height * 0.933f),
        Offset(0f, height * 0.5f),
    )

    data class IdentityColors(val start: Color, val end: Color)

    /** Deterministic pubkey → avatar gradient. Stable for the same pubkey
     * across launches, distinct between keys. */
    fun identityColors(pubkey: String): IdentityColors {
        val seed = pubkey.take(8).toLongOrNull(16) ?: 0L
        val hue = (seed % 360) / 360f
        val hueEnd = (hue + 40f / 360f) % 1f
        return IdentityColors(hsv(hue, 0.65f, 0.85f), hsv(hueEnd, 0.7f, 0.55f))
    }

    /** HSV (0..1 ranges) to Compose color. */
    fun hsv(h: Float, s: Float, v: Float): Color {
        val i = (h * 6).toInt()
        val f = h * 6 - i
        val p = v * (1 - s)
        val q = v * (1 - f * s)
        val t = v * (1 - (1 - f) * s)
        return when (i % 6) {
            0 -> Color(v, t, p)
            1 -> Color(q, v, p)
            2 -> Color(p, v, t)
            3 -> Color(p, q, v)
            4 -> Color(t, p, v)
            else -> Color(v, p, q)
        }
    }
}

/** Flat-top hexagon clip (BitOS identity motif). */
class HexShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val v = HexIdentity.vertices(size.width, size.height)
        val path = Path().apply {
            moveTo(v[0].x, v[0].y)
            for (i in 1 until v.size) lineTo(v[i].x, v[i].y)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Hexagonal identicon avatar: deterministic gradient + initials. Remote
 * profile pictures load through the media pipeline later; the identicon is
 * the stable fallback so feed rows never shift layout. */
@Composable
fun HexAvatar(
    pubkey: String,
    modifier: Modifier = Modifier,
    size: Int = 40,
    label: String? = null,
    hasLightning: Boolean = false,
) {
    val colors = HexIdentity.identityColors(pubkey)
    val initials = avatarInitials(label ?: pubkey)
    Box(
        modifier = modifier
            .size(size.dp)
            .semantics { contentDescription = "Avatar $initials" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(HexShape())
                .background(Brush.linearGradient(listOf(colors.start, colors.end))),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, color = Color.White, fontSize = (size / 3).sp, fontWeight = FontWeight.W700)
        }
        if (hasLightning) {
            Icon(
                Icons.Rounded.Bolt,
                contentDescription = "Lightning enabled",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size * 0.36f).coerceIn(10f, 18f).dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFFFB51B), Color(0xFFF7931A))))
                    .border(1.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
                    .padding(2.dp),
            )
        }
    }
}

private fun avatarInitials(label: String): String {
    val words = label.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return when (words.size) {
        0 -> "?"
        1 -> words.first().take(2).uppercase()
        else -> "${words.first().first()}${words.last().first()}".uppercase()
    }
}
