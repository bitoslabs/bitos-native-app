package space.bitos.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BitOSColors = darkColorScheme(
    primary = Color(0xFFFFCC00),
    secondary = Color(0xFF9EEA6A),
    background = Color(0xFF09090B),
    surface = Color(0xFF18181B),
)

@Composable
fun BitOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BitOSColors, content = content)
}
