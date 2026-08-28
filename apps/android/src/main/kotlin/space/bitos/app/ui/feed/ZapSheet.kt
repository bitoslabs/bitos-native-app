package space.bitos.app.ui.feed

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.ZapFormat
import kotlin.math.max

/** Zap sheet UI state driven by the caller's flow (viewmodel/state holder). */
data class ZapUiState(
    val phase: ZapPhase = ZapPhase.PICK_AMOUNT,
    val amountSats: Long = 21,
    val invoice: String? = null,
    val failure: String? = null,
    val busy: Boolean = false,
)

enum class ZapPhase { PICK_AMOUNT, FETCHING, INVOICE, FAILED }

private const val AUTO_CLOSE_MS = 2_400L

/**
 * APP-014 zap sheet (legacy Flutter `zap_dialog.dart` / web
 * `NoteZapDialog.svelte` parity): recipient header → amount tiers
 * (⚡💜🔥🚀) + custom sats + comment + anonymous toggle → BOLT-11 invoice
 * (QR, live expiry countdown, open-wallet deep link, copy) → paid success
 * with auto-close. Tiers/formats/expiry come from shared `ZapFormat` /
 * `Bolt11`.
 */
@Composable
fun ZapContent(
    note: FeedNote,
    lud16: String?,
    state: ZapUiState,
    profileName: String?,
    hasIdentity: Boolean,
    zapCount: Int,
    onAmountSelected: (Long) -> Unit,
    onZap: (Long, String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val presets = remember { space.bitos.core.model.ZapFormat.PRESETS.map { it.toLong() } }

    var customAmount by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var anonymous by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var paid by remember { mutableStateOf(false) }
    val zapCountAtInvoice = remember { mutableLongStateOf(-1L) }

    val amount: Long = customAmount.toLongOrNull()?.takeIf { it > 0 } ?: state.amountSats

    // Live expiry countdown while an invoice is showing.
    LaunchedEffect(state.invoice) {
        if (state.invoice != null) {
            zapCountAtInvoice.value = zapCount.toLong()
            while (true) {
                nowSec = System.currentTimeMillis() / 1000
                delay(1_000)
            }
        }
    }
    // Paid: a verified 9735 receipt for this note landed after the invoice
    // (count-based first signal; request-id matching rides the ledger work).
    LaunchedEffect(zapCount, state.invoice) {
        if (!paid && state.invoice != null && zapCountAtInvoice.value >= 0 &&
            zapCount > zapCountAtInvoice.value
        ) {
            paid = true
        }
    }
    LaunchedEffect(paid) {
        if (paid) {
            delay(AUTO_CLOSE_MS)
            onClose()
        }
    }

    val expiry = state.invoice?.let { space.bitos.core.model.Bolt11.expirySeconds(it) } ?: 0L
    val secondsLeft = if (expiry > 0) max(0L, expiry - nowSec) else 0L
    val expired = state.invoice != null && !paid && expiry > 0 && secondsLeft == 0L

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BitOSSpacing.screen)
            .padding(bottom = BitOSSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
    ) {
        ZapHeader(
            title = if (paid) "Zap sent" else "Zap ⚡",
            recipientName = profileName,
            recipientPubkey = note.pubkey,
            lud16 = lud16,
            copiedKind = copied,
            onCopyAddress = {
                clipboard.setText(AnnotatedString(lud16 ?: ""))
                copied = "address"
            },
            onClose = onClose,
        )

        when {
            lud16 == null -> NoAddressCard(profileName)
            paid -> PaidCard(amount, profileName, comment)
            state.phase == ZapPhase.FAILED || state.failure != null -> Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0x1AF59E0B),
            ) {
                Text(
                    state.failure ?: "Zap failed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF5A623),
                    modifier = Modifier.padding(BitOSSpacing.base),
                )
            }
            state.phase == ZapPhase.INVOICE && state.invoice != null -> InvoiceCard(
                invoice = state.invoice!!,
                amount = amount,
                secondsLeft = secondsLeft,
                expired = expired,
                busy = state.busy,
                copiedKind = copied,
                onCopy = {
                    clipboard.setText(AnnotatedString(state.invoice!!))
                    copied = "invoice"
                },
                onOpenWallet = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("lightning:${state.invoice}"))
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        clipboard.setText(AnnotatedString(state.invoice!!))
                        copied = "invoice"
                    }
                },
            )
            else -> AmountStep(
                presets = presets,
                selected = state.amountSats,
                custom = customAmount,
                comment = comment,
                anonymous = anonymous,
                hasIdentity = hasIdentity,
                amount = amount,
                busy = state.busy,
                onSelect = { sats ->
                    customAmount = ""
                    onAmountSelected(sats)
                },
                onCustom = { digits -> customAmount = digits.take(8).filter { it.isDigit() } },
                onComment = { comment = it.take(200) },
                onAnonymous = { anonymous = it },
                onZap = { onZap(amount, comment.trim(), anonymous || !hasIdentity) },
            )
        }
    }
}

// ── Header ──────────────────────────────────────────────────────

@Composable
private fun ZapHeader(
    title: String,
    recipientName: String?,
    recipientPubkey: String,
    lud16: String?,
    copiedKind: String?,
    onCopyAddress: () -> Unit,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.W700)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚡", fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    "zapping ${recipientName ?: recipientPubkey.take(8) + "…"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        PubkeyAvatar(pubkey = recipientPubkey, size = 40)
        Spacer(Modifier.width(BitOSSpacing.sm))
        if (!lud16.isNullOrBlank()) {
            TextButton(onClick = onCopyAddress) {
                Text(
                    if (copiedKind == "address") "✓" else "Copy ⌗",
                    fontSize = 12.sp,
                    color = if (copiedKind == "address") BitOSColors.success else BitOSColors.textSecondary,
                )
            }
        }
        TextButton(onClick = onClose) { Text("Close", color = BitOSColors.textSecondary) }
    }
}

@Composable
private fun NoAddressCard(name: String?) {
    Surface(shape = RoundedCornerShape(14.dp), color = BitOSColors.surfaceElevated) {
        Column(
            Modifier.fillMaxWidth().padding(BitOSSpacing.base),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("⚡", fontSize = 26.sp)
            Spacer(Modifier.height(BitOSSpacing.sm))
            Text("Can't receive zaps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W700)
            Text(
                "${name ?: "This author"} has no Lightning address in their profile.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
        }
    }
}

// ── Amount step ─────────────────────────────────────────────────

@Composable
private fun AmountStep(
    presets: List<Long>,
    selected: Long,
    custom: String,
    comment: String,
    anonymous: Boolean,
    hasIdentity: Boolean,
    amount: Long,
    busy: Boolean,
    onSelect: (Long) -> Unit,
    onCustom: (String) -> Unit,
    onComment: (String) -> Unit,
    onAnonymous: (Boolean) -> Unit,
    onZap: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            presets.forEach { sats ->
                AmountTile(
                    sats = sats,
                    selected = sats == selected && custom.isEmpty(),
                    onClick = { onSelect(sats) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        OutlinedTextField(
            value = custom,
            onValueChange = onCustom,
            placeholder = { Text("Custom amount") },
            suffix = { Text(ZapFormat.emoji(amount), fontSize = 16.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = comment,
            onValueChange = onComment,
            placeholder = { Text("Add a comment… (${comment.length}/200)") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasIdentity) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.border),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = BitOSSpacing.md, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Zap anonymously", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W700)
                        Text(
                            "Hide your key from the zap receipt",
                            style = MaterialTheme.typography.labelSmall,
                            color = BitOSColors.textSecondary,
                        )
                    }
                    Switch(checked = anonymous, onCheckedChange = onAnonymous)
                }
            }
        } else {
            Text(
                "Signed out — this zap will be anonymous.",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textTertiary,
            )
        }
        Button(
            onClick = onZap,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.zap, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = Color.White)
            else Text("${ZapFormat.emoji(amount)} Zap ${ZapFormat.sats(amount)} sats", fontWeight = FontWeight.W800)
        }
        Text(
            "The zap request is signed with your key and sent to the recipient's Lightning server; payment happens in your wallet.",
            style = MaterialTheme.typography.bodySmall,
            color = BitOSColors.textTertiary,
        )
    }
}

@Composable
private fun AmountTile(sats: Long, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) BitOSColors.zap.copy(alpha = 0.12f) else BitOSColors.surfaceElevated,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.4.dp else 1.dp,
            if (selected) BitOSColors.zap.copy(alpha = 0.5f) else BitOSColors.border,
        ),
        modifier = modifier
            .height(74.dp)
            .clickable(onClickLabel = "Zap $sats sats") { onClick() },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(ZapFormat.emoji(sats), fontSize = 17.sp)
            Text(
                ZapFormat.sats(sats),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.W700,
                color = if (selected) BitOSColors.zap else BitOSColors.textPrimary,
            )
            Text("sats", fontSize = 9.sp, color = BitOSColors.textTertiary)
        }
    }
}

// ── Invoice step ────────────────────────────────────────────────

@Composable
private fun InvoiceCard(
    invoice: String,
    amount: Long,
    secondsLeft: Long,
    expired: Boolean,
    busy: Boolean,
    copiedKind: String?,
    onCopy: () -> Unit,
    onOpenWallet: () -> Unit,
) {
    val qr = remember(invoice) { zapQrBitmap(invoice) }
    Surface(shape = RoundedCornerShape(16.dp), color = BitOSColors.surfaceElevated) {
        Column(
            Modifier.fillMaxWidth().padding(BitOSSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (expired) "Invoice expired" else "Invoice ready · ${ZapFormat.sats(amount)} sats",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.W700,
                    modifier = Modifier.weight(1f),
                )
                if (!expired && secondsLeft > 0) {
                    val urgent = secondsLeft < 120
                    Text(
                        "%d:%02d".format(secondsLeft / 60, secondsLeft % 60),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.W700,
                        fontSize = 12.sp,
                        color = if (urgent) Color(0xFFF5A623) else BitOSColors.textSecondary,
                    )
                    Spacer(Modifier.width(BitOSSpacing.sm))
                }
            }
            Spacer(Modifier.height(BitOSSpacing.md))
            if (qr != null) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Payment QR code",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(190.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .let { if (expired) it.alpha(0.35f) else it },
                )
            }
            Spacer(Modifier.height(BitOSSpacing.sm))
            Surface(shape = RoundedCornerShape(999.dp), color = BitOSColors.zap) {
                Row(
                    Modifier.padding(horizontal = BitOSSpacing.md, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⚡", fontSize = 12.sp, color = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${ZapFormat.sats(amount)} sats",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.W800,
                    )
                }
            }
            Spacer(Modifier.height(BitOSSpacing.md))
            if (expired) {
                Button(onClick = onOpenWallet, enabled = !busy) { Text(if (busy) "Creating…" else "New invoice") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                    Button(
                        onClick = onOpenWallet,
                        colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.zap, contentColor = Color.White),
                        modifier = Modifier.weight(2f),
                    ) { Text("Open wallet", fontWeight = FontWeight.W700) }
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                        Text(if (copiedKind == "invoice") "✓" else "Copy", color = BitOSColors.primary)
                    }
                }
                Text(
                    invoice.take(16) + "…" + invoice.takeLast(8),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = BitOSColors.textTertiary,
                )
            }
        }
    }
}

/** zxing QR bitmap (payment-grade), or null on any failure. */
private fun zapQrBitmap(invoice: String): Bitmap? = runCatching {
    val matrix = com.google.zxing.qrcode.QRCodeWriter()
        .encode(invoice.uppercase(), com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
    val scale = 512 / matrix.width
    val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
    for (x in 0 until 512) {
        for (y in 0 until 512) {
            bitmap.setPixel(x, y, if (matrix.get(x / scale, y / scale)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bitmap
}.getOrNull()

// ── Paid step ───────────────────────────────────────────────────

@Composable
private fun PaidCard(amount: Long, name: String?, comment: String) {
    var remaining by remember { mutableStateOf(1f) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            remaining = 1f - (System.currentTimeMillis() - start).toFloat() / AUTO_CLOSE_MS
            if (remaining <= 0f) break
            delay(50)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(56.dp).background(BitOSColors.success.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", fontSize = 28.sp, color = BitOSColors.success, fontWeight = FontWeight.W800)
        }
        Spacer(Modifier.height(BitOSSpacing.md))
        Text(
            "Sent ${ZapFormat.sats(amount)} sats to ${name ?: "the author"}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.W700,
        )
        Text(
            "Your zap is on the relays.",
            style = MaterialTheme.typography.labelMedium,
            color = BitOSColors.textSecondary,
        )
        if (comment.isNotBlank()) {
            Spacer(Modifier.height(BitOSSpacing.sm))
            Text("“$comment”", style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
        }
        Spacer(Modifier.height(BitOSSpacing.md))
        LinearProgressIndicator(
            progress = { remaining.coerceIn(0f, 1f) },
            color = BitOSColors.success,
            trackColor = BitOSColors.success.copy(alpha = 0.15f),
            modifier = Modifier.width(220.dp).height(3.dp),
        )
        Text("Closing…", fontSize = 10.sp, color = BitOSColors.textTertiary)
    }
}
