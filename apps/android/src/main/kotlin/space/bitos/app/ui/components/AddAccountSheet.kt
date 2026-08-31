package space.bitos.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.core.identity.KeyImportForm
import space.bitos.core.identity.KeyImportVerdict
import space.bitos.core.settings.IdentityOnboardingContent

/**
 * More-hub add-account sheet (ID-004 surface, v2 shared copy): import an
 * nsec with the live derived-identity preview (KF-6) and collapsible nsec
 * help, or create a fresh key. A bottom sheet — not a dialog — because it
 * hosts a keyboard form opened from the switcher sheet, matching every
 * other form surface; the shared copy keeps verbatim parity with the
 * SwiftUI AddAccountSheet. The caller owns the input; the review action
 * stays gated on the shared READY rule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountSheet(
    importInput: String,
    onImportChange: (String) -> Unit,
    error: String?,
    onReview: () -> Unit,
    onCreateKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    val content = IdentityOnboardingContent
    val check = remember(importInput) { KeyImportForm.check(importInput) }
    val ready = check.verdict == KeyImportVerdict.READY
    var nsecHelpOpen by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                content.ADD_ACCOUNT_TITLE,
                fontSize = 20.sp, fontWeight = FontWeight.W800, color = BitOSColors.textPrimary,
            )
            Text(
                content.ADD_ACCOUNT_SUBTITLE,
                fontSize = 12.sp, color = BitOSColors.textSecondary,
            )
            SecretKeyField(
                value = importInput,
                onValueChange = onImportChange,
                error = error,
                onSubmit = onReview,
                textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            )
            if (ready) {
                DerivedIdentityCard(check)
            }
            NsecHelpSection(
                title = content.NSEC_HELP_TITLE,
                items = content.NSEC_HELP_ITEMS,
                open = nsecHelpOpen,
                onToggle = { nsecHelpOpen = !nsecHelpOpen },
            )
            Button(
                onClick = onReview,
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(content.ADD_ACCOUNT_REVIEW_LABEL, fontWeight = FontWeight.W600)
            }
            OutlinedButton(
                onClick = onCreateKey,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(content.ADD_ACCOUNT_CREATE_LABEL, fontWeight = FontWeight.W600, color = BitOSColors.primary)
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(content.ADD_ACCOUNT_CANCEL_LABEL, color = BitOSColors.textSecondary)
            }
        }
    }
}

/**
 * Collapsible nsec help (shared v2 copy): what the key is, where to export
 * it from an existing Nostr app, npub-vs-nsec, and the sealed-on-device
 * promise. Collapsed by default so the import path stays one glance wide.
 */
@Composable
private fun NsecHelpSection(
    title: String,
    items: List<String>,
    open: Boolean,
    onToggle: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClickLabel = title) { onToggle() }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.HelpOutline,
                contentDescription = null,
                tint = BitOSColors.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.W600, color = BitOSColors.primary)
            Spacer(Modifier.weight(1f))
            Icon(
                if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (open) "Collapse" else "Expand",
                tint = BitOSColors.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(visible = open) {
            Surface(shape = RoundedCornerShape(12.dp), color = BitOSColors.surfaceElevated) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (item in items) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", fontSize = 12.sp, color = BitOSColors.primary)
                            Text(item, fontSize = 12.sp, color = BitOSColors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}
