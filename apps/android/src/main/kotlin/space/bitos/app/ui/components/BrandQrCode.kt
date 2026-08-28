package space.bitos.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import space.bitos.core.nostr.QrCode

/**
 * Branded QR (APP-022; legacy Flutter `BrandQrCode` parity): white card,
 * gray-900 modules, bitcoin-orange hex cover with the bolt glyph in the
 * center. Matrix comes from the shared pure encoder — deterministic and
 * identical on both platforms.
 */
@Composable
fun BrandQrCode(
    value: String,
    sizeDp: Int = 224,
    contentDescription: String? = null,
) {
    val matrix = remember(value) { QrCode.encode(value) }
    if (matrix == null || matrix.isEmpty()) return
    val moduleColor = Color(0xFF111827)
    val coverColor = Color(0xFFF7931A)

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size((sizeDp - 20).dp)) {
            val n = matrix.size
            val cell = this.size.width / (n + 1f) // + quiet-zone ring
            val origin = Offset(cell / 2, cell / 2)
            for (y in 0 until n) {
                val row = matrix[y]
                for (x in 0 until n) {
                    if (row[x]) {
                        drawRect(
                            color = moduleColor,
                            topLeft = Offset(origin.x + x * cell, origin.y + y * cell),
                            size = Size(cell + 0.5f, cell + 0.5f), // hairline overlap
                        )
                    }
                }
            }
        }
        // Brand cover: orange hex disc with the bolt glyph (scan-safe: a
        // quiet-zone-sized center keeps the code readable).
        Canvas(Modifier.size(((sizeDp - 20) * 0.22f).dp)) {
            val r = size.minDimension / 2f
            // White ring then orange hex.
            drawCircle(Color.White, radius = r * 1.15f)
            val hex = HexPath(center = Offset(r, r), radius = r)
            drawPath(hex, coverColor)
            // Bolt: simple polygon glyph.
            val bolt = androidx.compose.ui.graphics.Path().apply {
                moveTo(r + r * 0.10f, r - r * 0.55f)
                lineTo(r - r * 0.35f, r + r * 0.08f)
                lineTo(r + r * 0.02f, r + r * 0.08f)
                lineTo(r - r * 0.10f, r + r * 0.55f)
                lineTo(r + r * 0.35f, r - r * 0.08f)
                lineTo(r - r * 0.02f, r - r * 0.08f)
                close()
            }
            drawPath(bolt, Color.White)
        }
    }
}

/** Regular hexagon path (flat-top), matching HexShape geometry. */
private fun HexPath(center: Offset, radius: Float) = androidx.compose.ui.graphics.Path().apply {
    for (i in 0 until 6) {
        val angle = Math.PI / 6.0 + i * Math.PI / 3.0
        val x = center.x + (radius * kotlin.math.cos(angle)).toFloat()
        val y = center.y + (radius * kotlin.math.sin(angle)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

