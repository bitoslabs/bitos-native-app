package space.bitos.app.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.bitos.app.ui.theme.BitOSColors

/**
 * BitOS text field (legacy Flutter `InputDecorationTheme` parity —
 * app_theme.dart): filled surfaceElevated, 12 dp radius, 1 dp border
 * (accent 2 dp focused), tertiary 14/w400 hint, lg+md content padding.
 * One component so every surface inherits the brand styling; call sites
 * drop their local `colors = OutlinedTextFieldDefaults.colors(...)` soup.
 */
@Composable
fun BitosTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    /** Compact: tighter h12/v8 padding (~48 dp) for dense forms. */
    compact: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = if (compact) modifier.height(48.dp) else modifier,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        textStyle = textStyle,
        placeholder = {
            HintText(placeholder)
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = BitOSColors.surfaceElevated,
            unfocusedContainerColor = BitOSColors.surfaceElevated,
            focusedTextColor = BitOSColors.textPrimary,
            unfocusedTextColor = BitOSColors.textPrimary,
            focusedBorderColor = BitOSColors.primary,
            unfocusedBorderColor = BitOSColors.border,
            disabledBorderColor = BitOSColors.border.copy(alpha = 0.5f),
            disabledContainerColor = BitOSColors.surfaceElevated.copy(alpha = 0.5f),
            cursorColor = BitOSColors.primary,
        ),
    )
}

/** Borderless plain variant for inline composer bars (reply rows). */
@Composable
fun BitosPlainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = textStyle,
        placeholder = {
            HintText(placeholder)
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = BitOSColors.primary,
            focusedTextColor = BitOSColors.textPrimary,
            unfocusedTextColor = BitOSColors.textPrimary,
        ),
    )
}

/**
 * Selection-aware variant for composer bodies: keeps the real cursor so
 * @-mention detection, toolbar inserts and mention picks all operate at
 * the caret, not at end-of-text.
 */
@Composable
fun BitosPlainTextField(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = textStyle,
        placeholder = {
            HintText(placeholder)
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = BitOSColors.primary,
            focusedTextColor = BitOSColors.textPrimary,
            unfocusedTextColor = BitOSColors.textPrimary,
        ),
    )
}

/** Legacy hintStyle parity: tertiary, 14 sp, w400. */
@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = BitOSColors.textTertiary,
            fontSize = 14.sp,
            fontWeight = FontWeight.W400,
        ),
        maxLines = 1,
    )
}
