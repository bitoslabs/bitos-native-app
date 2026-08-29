package space.bitos.app.ui.zap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.data.feed.NotificationRepository
import space.bitos.app.data.zap.SentZapsStore
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.feed.HomeViewModel
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.model.NotificationKind
import space.bitos.core.model.SentZapLedger
import space.bitos.core.model.ZapFormat

/**
 * APP-014 zap wallet (legacy Flutter `ZapsView` / web /zaps parity): stat
 * tiles (Received · Sent · Avg · Net) above an All/Received/Sent ledger.
 * Sent = the local device ledger (verified payments); received = verified
 * kind-9735 receipts from the notification stream. Rules (merge, totals)
 * live in shared `SentZapLedger`.
 */
@Composable
fun ZapsScreen(
    identityViewModel: IdentityViewModel,
    homeViewModel: HomeViewModel,
    notifications: NotificationRepository,
    sentZaps: SentZapsStore,
    onClose: () -> Unit,
) {
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    val feedState by homeViewModel.state.collectAsStateWithLifecycle()
    val notificationsState by notifications.state.collectAsStateWithLifecycle()
    val sent = remember { sentZaps.load() }
    var tab by remember { mutableIntStateOf(0) }

    val received = notificationsState.items.filter { it.kind == NotificationKind.ZAP }
    val entries = remember(notificationsState.items, sent) {
        SentZapLedger.ledger(
            sent = sent,
            receivedSats = received.map { (it.amountMsat ?: 0) / 1000 },
            receivedFrom = received.map { it.authorPubkey },
            receivedAt = received.map { it.createdAt },
            receivedNote = received.map { it.targetEventId },
        )
    }
    val totals = remember(entries) { SentZapLedger.totals(entries) }
    val filtered = remember(entries, tab) {
        when (tab) {
            1 -> entries.filter { it.direction == SentZapLedger.Direction.RECEIVED }
            2 -> entries.filter { it.direction == SentZapLedger.Direction.SENT }
            else -> entries
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .padding(horizontal = BitOSSpacing.screen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Zap wallet", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            space.bitos.app.ui.components.SheetCloseIcon(onClose = onClose)
        }
        if (identity.account == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Add an identity (You tab) to see your zap history.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BitOSColors.textSecondary,
                )
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm), modifier = Modifier.padding(vertical = BitOSSpacing.sm)) {
            StatTile("Received", ZapFormat.sats(totals.receivedSats), emphasized = true, modifier = Modifier.weight(1f))
            StatTile("Sent", ZapFormat.sats(totals.sentSats), modifier = Modifier.weight(1f))
            StatTile("Avg", ZapFormat.sats(totals.averageSats), modifier = Modifier.weight(1f))
            StatTile("Net", ZapFormat.sats(totals.netSats), modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.md)) {
            listOf("All", "Received", "Sent").forEachIndexed { index, label ->
                TextButton(onClick = { tab = index }) {
                    Text(
                        label,
                        fontWeight = if (tab == index) FontWeight.W800 else FontWeight.W600,
                        color = if (tab == index) BitOSColors.primary else BitOSColors.textSecondary,
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚡", fontSize = 34.sp, color = BitOSColors.zap)
                    Spacer(Modifier.height(BitOSSpacing.md))
                    Text("No zaps yet", style = MaterialTheme.typography.bodyMedium, color = BitOSColors.textSecondary)
                }
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = BitOSSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
            ) {
                items(filtered, key = { "${it.direction}-${it.createdAt}-${it.peerPubkey}-${it.sats}" }) { entry ->
                    ZapRow(entry, feedState.profiles[entry.peerPubkey])
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, emphasized: Boolean = false, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (emphasized) BitOSColors.zap.copy(alpha = 0.10f) else BitOSColors.surfaceElevated,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = BitOSColors.textTertiary)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W800,
                color = if (emphasized) BitOSColors.zap else BitOSColors.textPrimary,
            )
        }
    }
}

@Composable
private fun ZapRow(entry: SentZapLedger.LedgerEntry, profile: space.bitos.core.model.ProfileMetadata?) {
    val received = entry.direction == SentZapLedger.Direction.RECEIVED
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BitOSColors.surface,
    ) {
        Row(Modifier.padding(BitOSSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(
                        if (received) BitOSColors.zap.copy(alpha = 0.12f) else BitOSColors.surfaceElevated,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("⚡", fontSize = 15.sp, color = if (received) BitOSColors.zap else BitOSColors.textTertiary)
            }
            Spacer(Modifier.width(BitOSSpacing.md))
            PubkeyAvatar(pubkey = entry.peerPubkey, size = 34)
            Spacer(Modifier.width(BitOSSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    profile?.bestDisplayName ?: shortPubkey(entry.peerPubkey),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.W700,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        if (received) "Received" else "Sent",
                        entry.memo,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${if (received) "+" else "−"}${ZapFormat.sats(entry.sats)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.W800,
                    color = if (received) BitOSColors.zap else BitOSColors.textSecondary,
                )
                Text(
                    formatTimeAgo(entry.createdAt, System.currentTimeMillis() / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                )
            }
        }
    }
}
