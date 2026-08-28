package space.bitos.app.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import space.bitos.app.R

/** Reviewed, bundled Solar assets used by the high-frequency feed actions. */
enum class SolarFeedIcon(@DrawableRes val drawableRes: Int) {
    Heart(R.drawable.solar_heart_linear),
    HeartFilled(R.drawable.solar_heart_bold),
    Bookmark(R.drawable.solar_bookmark_linear),
    BookmarkFilled(R.drawable.solar_bookmark_bold),
    Comment(R.drawable.solar_chat_round_linear),
    Repost(R.drawable.solar_repeat_linear),
    Zap(R.drawable.solar_bolt_linear),
    More(R.drawable.solar_menu_dots_linear),
}

@Composable
fun SolarFeedIconImage(
    icon: SolarFeedIcon,
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
