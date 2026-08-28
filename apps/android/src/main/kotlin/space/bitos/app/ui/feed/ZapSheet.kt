package space.bitos.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.feed.FeedNote

private val AMOUNT_PRESETS_SATS = listOf(21L, 100L, 1_000L, 10_000L)

/** Zap sheet UI state driven by the caller's flow (viewmodel/state holder). */
data class ZapUiState(
    val phase: ZapPhase = ZapPhase.PICK_AMOUNT,
    val amountSats: Long = 21,
    val invoice: String? = null,
    val failure: String? = null,
    val busy: Boolean = false,
)

enum class ZapPhase { PICK_AMOUNT, FETCHING, INVOICE, FAILED }

/**
 * Zap sheet (SOC-008 client path): amount presets → signed kind-9734 zap
 * request → LNURL invoice for payment in any external wallet. No wallet
 * connection lives here (NWC is a separate safety-gated slice).
 */
@Composable
fun ZapContent(
    note: FeedNote,
    lud16: String?,
    state: ZapUiState,
    onAmountSelected: (Long) -> Unit,
    onZap: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BitOSSpacing.screen)
            .padding(bottom = BitOSSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Zap ⚡", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(BitOSSpacing.sm))
            Text(
                "${state.amountSats} sats",
                style = MaterialTheme.typography.labelMedium,
                color = BitOSColors.zap,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Close") }
        }

        when {
            lud16 == null -> Surface(shape = RoundedCornerShape(14.dp), color = BitOSColors.surface) {
                Text(
                    "This author has no Lightning address in their profile, so zaps cannot be sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textSecondary,
                    modifier = Modifier.padding(BitOSSpacing.base),
                )
            }
            state.phase == ZapPhase.FAILED || state.failure != null -> Surface(
                shape = RoundedCornerShape(14.dp),
                color = BitOSColors.surface,
            ) {
                Text(
                    state.failure ?: "Zap failed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.error,
                    modifier = Modifier.padding(BitOSSpacing.base),
                )
            }
            state.phase == ZapPhase.INVOICE && state.invoice != null -> {
                Text(
                    "Invoice ready — pay from any Lightning wallet:",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textSecondary,
                )
                Surface(shape = RoundedCornerShape(12.dp), color = BitOSColors.surfaceElevated) {
                    Text(
                        state.invoice!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.zap,
                        modifier = Modifier.padding(BitOSSpacing.base),
                        maxLines = 4,
                    )
                }
                Button(onClick = { clipboard.setText(AnnotatedString(state.invoice!!)) }) {
                    Text("Copy invoice")
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                    AMOUNT_PRESETS_SATS.forEach { sats ->
                        FilterChip(
                            selected = state.amountSats == sats,
                            onClick = { onAmountSelected(sats) },
                            label = { Text("${sats}s") },
                        )
                    }
                }
                Button(
                    onClick = { onZap(state.amountSats) },
                    enabled = !state.busy,
                ) { Text(if (state.busy) "Fetching invoice…" else "Zap ${state.amountSats} sats") }
                Text(
                    "The zap request is signed with your key and sent to the recipient's Lightning server; payment happens in your wallet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textTertiary,
                )
            }
        }
    }
}
