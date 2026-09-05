package space.bitos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import space.bitos.app.ui.theme.BitOSColors

/**
 * Shared BitOS single-value slider.
 *
 * The prototype's range control is a 5dp neutral rail, coral fill and a 14dp
 * cursor. The visible control stays compact while its touch target is 40dp.
 */
@Composable
fun BitosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    // Pointer-input coroutines outlive recomposition while the finger stays
    // down. Keep their callbacks current so changing Contrast cannot submit
    // an older Brightness/Saturation triple captured by a previous render.
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished = rememberUpdatedState(onValueChangeFinished)
    val current = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((current - valueRange.start) / span).coerceIn(0f, 1f)
    val railColor = if (enabled) BitOSColors.surfaceElevated else BitOSColors.textTertiary.copy(alpha = 0.35f)
    val activeColor = if (enabled) BitOSColors.primary else BitOSColors.textTertiary.copy(alpha = 0.5f)
    fun valueAt(x: Float, width: Int): Float =
        valueRange.start + span * (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .semantics { contentDescription = "Slider" }
            .pointerInput(valueRange, enabled) {
                if (enabled) {
                    // One recognizer owns this pointer from press through
                    // release. Separate tap + drag recognizers can both
                    // react to the same contact in a scrollable sheet.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        latestOnValueChange.value(valueAt(down.position.x, size.width))
                        var change = down
                        while (change.pressed) {
                            val event = awaitPointerEvent()
                            change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.positionChanged()) {
                                latestOnValueChange.value(valueAt(change.position.x, size.width))
                                change.consume()
                            }
                        }
                        latestOnValueChangeFinished.value?.invoke()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The rail is visually 40dp high. Do not fill the sheet's available
        // height here: a slider lives inside rows and must never expand them.
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val railHeight = 5.dp.toPx()
            val thumbRadius = 7.dp.toPx()
            val centerY = size.height / 2f
            val thumbX = size.width * fraction
            val railTop = centerY - railHeight / 2f
            drawRoundRect(
                color = railColor,
                topLeft = Offset(0f, railTop),
                size = Size(size.width, railHeight),
                cornerRadius = CornerRadius(railHeight / 2f),
            )
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(0f, railTop),
                size = Size(thumbX, railHeight),
                cornerRadius = CornerRadius(railHeight / 2f),
            )
            drawCircle(activeColor, radius = thumbRadius, center = Offset(thumbX, centerY))
        }
    }
}

/**
 * Compact adjustment row used for values such as size, opacity and rotation.
 * Drag for coarse changes or use the two nudge buttons for a precise change.
 */
@Composable
fun BitosAdjustmentSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    step: Float,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    val current = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val canDecrease = current > valueRange.start
    val canIncrease = current < valueRange.endInclusive
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(BitOSColors.surfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(leadingIcon, contentDescription = null, tint = BitOSColors.textSecondary)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = BitOSColors.textSecondary,
            fontWeight = FontWeight.W600,
            modifier = Modifier.width(84.dp),
        )
        BitosSlider(
            value = current,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
        )
        Text(
            valueText,
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            fontWeight = FontWeight.W600,
            modifier = Modifier.width(38.dp),
        )
        SliderNudge(
            symbol = "−",
            label = "Decrease $label",
            enabled = canDecrease,
        ) { onValueChange((current - step).coerceAtLeast(valueRange.start)) }
        SliderNudge(
            symbol = "+",
            label = "Increase $label",
            enabled = canIncrease,
        ) { onValueChange((current + step).coerceAtMost(valueRange.endInclusive)) }
    }
}

@Composable
private fun SliderNudge(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        symbol,
        style = MaterialTheme.typography.titleSmall,
        color = if (enabled) BitOSColors.textSecondary else BitOSColors.textTertiary,
        fontWeight = FontWeight.W600,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
    )
}
