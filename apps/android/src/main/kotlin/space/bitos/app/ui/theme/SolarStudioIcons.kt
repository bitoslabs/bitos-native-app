package space.bitos.app.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import space.bitos.app.R

/**
 * Reviewed, bundled Solar assets for the studio surfaces (Create hub,
 * meme editor, mass production — spec §3.17/§3.19, mockup app-15).
 * Mirrors the iOS AppIcons studio tokens one-for-one so both platforms
 * paint the same Solar glyph per semantic action.
 */
enum class SolarStudioIcon(@DrawableRes val drawableRes: Int) {
    Settings(R.drawable.solar_settings_linear),
    Gallery(R.drawable.solar_gallery_linear),
    VideoCamera(R.drawable.solar_video_linear),
    Bolt(R.drawable.solar_bolt_linear),
    Text(R.drawable.solar_text_linear),
    Sticker(R.drawable.solar_emoji_linear),
    Palette(R.drawable.solar_palette_linear),
    Soundwave(R.drawable.solar_soundwave_linear),
    UndoLeft(R.drawable.solar_undo_left_round_linear),
    DangerTriangle(R.drawable.solar_danger_triangle_linear),
    MusicNote(R.drawable.solar_music_note_linear),
    MagicWand(R.drawable.solar_magic_wand_linear),
    Pen(R.drawable.solar_pen_linear),
}

@Composable
fun SolarStudioIconImage(
    icon: SolarStudioIcon,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(icon.drawableRes),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier,
    )
}
