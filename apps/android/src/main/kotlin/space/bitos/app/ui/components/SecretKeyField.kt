package space.bitos.app.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import space.bitos.core.identity.KeyImportForm
import space.bitos.core.identity.KeyImportVerdict
import space.bitos.app.ui.theme.BitOSColors

/**
 * Secret-key login field (ID-004), shared by the You-tab import panel and
 * the More-hub add-account sheet: masked by default with reveal + one-tap
 * paste, keyboard flags that never mangle a pasted key, and live feedback
 * from the shared `KeyImportForm` rule. The caller owns the text; gate the
 * submit action on `KeyImportForm.check(value).verdict == READY`.
 */
@Composable
fun SecretKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
    textStyle: TextStyle = TextStyle.Default,
) {
    var revealed by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val check = remember(value) { KeyImportForm.check(value) }
    val ready = check.verdict == KeyImportVerdict.READY
    // A submit error outranks the live hint; both clear on the next edit.
    val feedback = error ?: check.message

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("nsec1…") },
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        textStyle = textStyle,
        isError = feedback != null && !ready,
        supportingText = feedback?.let { message ->
            {
                Text(
                    message,
                    color = when {
                        ready -> BitOSColors.success
                        else -> BitOSColors.error
                    },
                )
            }
        },
        trailingIcon = {
            Row {
                if (value.isEmpty()) {
                    IconButton(onClick = { clipboard.getText()?.text?.let(onValueChange) }) {
                        Icon(
                            Icons.Outlined.ContentPaste,
                            contentDescription = "Paste key from clipboard",
                            tint = BitOSColors.textSecondary,
                        )
                    }
                }
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (revealed) "Hide key" else "Show key",
                        tint = BitOSColors.textSecondary,
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrect = false,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (ready) onSubmit() }),
        modifier = modifier,
    )
}

/** True when the current field text resolves to a usable secret key. */
fun secretKeyReady(value: String): Boolean = KeyImportForm.check(value).verdict == KeyImportVerdict.READY
