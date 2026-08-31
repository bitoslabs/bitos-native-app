package space.bitos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.core.identity.KeyImportCheck
import space.bitos.core.identity.KeyImportForm
import space.bitos.core.identity.KeyImportVerdict

/**
 * Secret-key login field (ID-004), shared by the onboarding import step, the
 * You-tab import panel and the More-hub add-account sheet: masked by default
 * with reveal + one-tap paste, keyboard flags that never mangle a pasted
 * key, and live feedback from the shared `KeyImportForm` rule rendered as an
 * icon+text state banner. The caller owns the text; gate the submit action
 * on `KeyImportForm.check(value).verdict == READY`.
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

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text("nsec1…", fontFamily = FontFamily.Monospace, color = BitOSColors.textTertiary)
            },
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            textStyle = textStyle.copy(fontFamily = FontFamily.Monospace),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (ready) BitOSColors.success else BitOSColors.primary,
                unfocusedBorderColor = if (feedback != null && !ready) BitOSColors.error else BitOSColors.border,
                focusedContainerColor = BitOSColors.surfaceElevated,
                unfocusedContainerColor = BitOSColors.surfaceElevated,
                cursorColor = BitOSColors.primary,
            ),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (value.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = BitOSColors.surfaceOverlay,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.border),
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clickable(onClickLabel = "Paste key from clipboard") {
                                    clipboard.getText()?.text?.let(onValueChange)
                                },
                        ) {
                            Text(
                                "Paste",
                                color = BitOSColors.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.W600,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
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
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Secret key" },
        )
        feedback?.let { message ->
            StateBanner(
                tone = if (ready) StateBannerTone.OK else StateBannerTone.ERROR,
                text = message,
            )
        }
    }
}

/**
 * Live derived-identity preview (KF-6): once the shared rule resolves the
 * input to a usable secret, this shows the account the key controls — hex
 * avatar over the derived pubkey, monospace npub and a copy chip — before
 * anything is stored.
 */
@Composable
fun DerivedIdentityCard(
    check: KeyImportCheck,
    modifier: Modifier = Modifier,
) {
    val pubkey = check.pubkeyHex ?: return
    val npub = check.npub ?: return
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BitOSColors.surfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.border),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            HexAvatar(pubkey = pubkey, size = 40)
            Column(Modifier.weight(1f)) {
                Text(
                    "Derived identity",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W700,
                    color = BitOSColors.textPrimary,
                )
                Text(
                    middleEllipsize(npub),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = BitOSColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = BitOSColors.primary.copy(alpha = 0.14f),
                border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.primary.copy(alpha = 0.4f)),
                modifier = Modifier.clickable(onClickLabel = "Copy npub") {
                    clipboard.setText(AnnotatedString(npub))
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        tint = BitOSColors.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        "Copy npub",
                        color = BitOSColors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.W600,
                    )
                }
            }
        }
    }
}

/** True when the current field text resolves to a usable secret key. */
fun secretKeyReady(value: String): Boolean = KeyImportForm.check(value).verdict == KeyImportVerdict.READY

/** npub/nsec display truncation (CB-1): keep both bech32 ends visible. */
fun middleEllipsize(value: String, head: Int = 10, tail: Int = 8): String =
    if (value.length <= head + tail + 1) value else value.take(head) + "…" + value.takeLast(tail)
