package space.bitos.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import space.bitos.app.ui.theme.AppIcons
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
    // Same chrome as NoteCardAction (IconButton circle ripple + 48dp touch
    // target); the spec §2.4 bounce/haptic stay on the inner icon.
    IconButton(
        onClick = {
            if (!liked) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier.semantics { contentDescription = label },
    ) {
        Icon(
            imageVector = if (liked) AppIcons.Heart else AppIcons.HeartOutline,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize).scale(scale.value),
        )
    }
}
