package space.bitos.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.bitos.app.R

/**
 * APP-005 / spec §2.4 animated like: 300 ms elasticOut scale-bounce +
 * haptic on the like tap (unlike stays quiet). Backed by the reviewed
 * Solar heart assets (Linear inactive / Bold active — icon-system.md).
 */
@Composable
fun AnimatedLikeIcon(
    liked: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    onClick: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(liked) {
        if (liked) {
            scale.snapTo(0.6f)
            scale.animateTo(
                1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }
    val label = if (liked) "Unlike" else "Like"
    Icon(
        painter = painterResource(
            if (liked) R.drawable.solar_heart_bold else R.drawable.solar_heart_linear
        ),
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(iconSize)
            .scale(scale.value)
            .clickable(onClickLabel = label) {
                if (!liked) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics { contentDescription = label },
    )
}
