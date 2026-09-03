package space.bitos.app.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import space.bitos.app.R

/**
 * Reviewed, bundled Solar assets used by the camera capture surface
 * (CAP-002, spec §3.19 record screen). Counterpart of [SolarFeedIcon]:
 * capture chrome resolves through real Solar vectors because its controls
 * (torch, self-timer, flip, duration cap) have no Material equivalent that
 * matches the reference UX (`docs/ui/app-app-04-create-camera-editor.html`).
 * Semantic tokens mirror the iOS `AppIcons` capture section.
 */
enum class SolarCaptureIcon(@DrawableRes val drawableRes: Int) {
    Torch(R.drawable.solar_flashlight_on_linear),
    Timer(R.drawable.solar_stopwatch_linear),
    ClockCircle(R.drawable.solar_clock_circle_linear),
    CameraRotate(R.drawable.solar_camera_rotate_linear),
    Camera(R.drawable.solar_camera_linear),
    Gallery(R.drawable.solar_gallery_linear),
    Pen(R.drawable.solar_pen_linear),
}

@Composable
fun SolarCaptureIconImage(
    icon: SolarCaptureIcon,
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
