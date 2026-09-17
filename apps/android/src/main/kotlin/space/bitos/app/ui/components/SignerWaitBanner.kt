package space.bitos.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.identity.nip46.Nip46SessionPhase
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.core.settings.IdentityOnboardingContent

/**
 * Awaiting-signer banner (ID-006b/ID-007b): identifies the selected
 * signer, offers Open signer (NIP-55) and Cancel wait. Cancellation is
 * not an error — the draft/upload/durable job stays exactly as it was.
 */
@Composable
fun SignerWaitBanner(identityViewModel: IdentityViewModel, modifier: Modifier = Modifier) {
    val nip46Session by identityViewModel.activeNip46Session.collectAsState()
    val nip55Client by identityViewModel.activeNip55Client.collectAsState()
    val nip46Ui by (nip46Session?.uiState
        ?: kotlinx.coroutines.flow.MutableStateFlow(space.bitos.app.identity.nip46.Nip46SessionUiState()))
        .collectAsState()
    val nip55Pending by (nip55Client?.activeRequest
        ?: kotlinx.coroutines.flow.MutableStateFlow<space.bitos.app.identity.nip55.Nip55SignerClient.Pending?>(null))
        .collectAsState()

    val waitingNip46 = nip46Ui.phase == Nip46SessionPhase.AWAITING_SIGNER
    if (!waitingNip46 && nip55Pending == null) return

    val content = IdentityOnboardingContent
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Surface(
        color = BitOSColors.surfaceOverlay,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (nip55Pending != null) content.NIP55_AWAITING_TITLE else content.NIP46_AWAITING_TITLE,
                fontSize = 13.sp,
                color = BitOSColors.textPrimary,
            )
            Text(
                content.NIP46_AWAITING_BODY,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = BitOSColors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val pending = nip55Pending
                if (pending != null) {
                    TextButton(onClick = {
                        // Open signer: relaunch the pinned intent so the
                        // user lands straight back in the approval UI.
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:" + pending.spec.payload)).apply {
                                    `package` = pending.spec.signerPackage
                                    putExtra(space.bitos.app.identity.nip55.Nip55Contracts.EXTRA_TYPE, pending.spec.type)
                                    putExtra(space.bitos.app.identity.nip55.Nip55Contracts.EXTRA_ID, pending.spec.requestId)
                                    putExtra(
                                        space.bitos.app.identity.nip55.Nip55Contracts.EXTRA_CURRENT_USER,
                                        pending.spec.currentUserPubkeyHex,
                                    )
                                },
                            )
                        }
                    }) { Text(content.NIP55_OPEN_SIGNER_LABEL, fontSize = 12.sp, color = BitOSColors.primary) }
                }
                TextButton(onClick = {
                    scope.launch {
                        nip46Session?.cancelWait()
                        nip55Client?.cancelWait()
                    }
                }) { Text(content.CANCEL_WAIT_LABEL, fontSize = 12.sp, color = BitOSColors.textSecondary) }
            }
        }
    }
}
