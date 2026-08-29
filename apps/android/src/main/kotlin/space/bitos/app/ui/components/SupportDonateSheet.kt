package space.bitos.app.ui.components

import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.asImageBitmap

import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.data.feed.ProfileLookupStore
import space.bitos.app.data.zap.LnurlPayClient
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.core.identity.NostrKeyCodec

/**
 * Support/donate sheet (legacy Flutter `SupportWidget` parity): the official
 * support npub's kind-0 → lud16 → LNURL-pay invoice for the chosen tier
 * (coffee / expert★ / production / premium + custom), with QR + copy +
 * open-wallet. No custodian: sats go straight to the project's address.
 */
@Composable
fun SupportDonateSheet(
    supportNpub: String,
    tiers: List<Pair<Int, Boolean>>, // sats to recommended
    profileLookup: ProfileLookupStore,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val pubkey = remember(supportNpub) { NostrKeyCodec.parseNpub(supportNpub) }
    val profiles by profileLookup.profiles.collectAsStateWithLifecycle()
    val loadingProfile by profileLookup.loading.collectAsStateWithLifecycle()

    var selectedSats by remember { mutableStateOf(tiers.firstOrNull { it.second }?.first ?: tiers.first().first) }
    var customSats by remember { mutableStateOf("") }
    var invoice by remember { mutableStateOf<String?>(null) }
    var invoiceBusy by remember { mutableStateOf(false) }
    var invoiceError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(supportNpub) {
        pubkey?.let { profileLookup.lookup(listOf(it)) }
    }

    val profile = pubkey?.let { profiles[it] }
    val lud16 = profile?.lud16?.takeIf { it.isNotEmpty() }
    val amount = customSats.toIntOrNull()?.takeIf { it > 0 } ?: selectedSats

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        Text("Support BitOS", fontSize = 20.sp, fontWeight = FontWeight.W800, color = BitOSColors.textPrimary)
        when {
            loadingProfile && lud16 == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = BitOSColors.primary)
                Spacer(Modifier.width(10.dp))
                Text("Resolving the project Lightning address…", fontSize = 13.sp, color = BitOSColors.textSecondary)
            }
            lud16 == null -> Text(
                "Couldn't resolve a Lightning address for the support account. Try again later.",
                fontSize = 13.sp, color = BitOSColors.error,
            )
            else -> {
                Text("⚡ $lud16", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textTertiary)
                if (invoice == null) {
                    // Tier tiles (legacy _TierTile parity).
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        for ((sats, recommended) in tiers) {
                            val selected = customSats.isBlank() && selectedSats == sats
                            TierTile(sats = sats, recommended = recommended, selected = selected) {
                                selectedSats = sats
                                customSats = ""
                            }
                        }
                    }
                    OutlinedTextField(
                        value = customSats,
                        onValueChange = { customSats = it.filter { c -> c.isDigit() }.take(7) },
                        singleLine = true,
                        label = { Text("Custom amount (sats)", fontSize = 13.sp) },
                        textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    invoiceError?.let { Text(it, fontSize = 12.sp, color = BitOSColors.error) }
                    TextButton(
                        onClick = {
                            invoiceBusy = true
                            invoiceError = null
                            scope.launch {
                                try {
                                    val payRequest = LnurlPayClient().fetchPayRequest(lud16)
                                    invoice = LnurlPayClient().fetchInvoice(payRequest, amount * 1000L, null, lud16).paymentRequest
                                } catch (e: LnurlPayClient.ZapFailure) {
                                    invoiceError = e.message
                                } catch (_: Exception) {
                                    invoiceError = "LNURL server unreachable."
                                } finally {
                                    invoiceBusy = false
                                }
                            }
                        },
                        enabled = !invoiceBusy,
                    ) {
                        if (invoiceBusy) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = BitOSColors.primary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Support with $amount sats", color = BitOSColors.primary, fontWeight = FontWeight.W700)
                    }
                } else {
                    // Invoice panel (QR + copy + open wallet).
                    val qr = remember(invoice) { invoiceQrBitmap(invoice!!) }
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Payment QR code",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(210.dp)
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                        )
                    }
                    Text("Pay $amount sats in any Lightning wallet.", fontSize = 12.sp, color = BitOSColors.textSecondary)
                    Row {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(invoice!!))
                            copied = true
                        }) {
                            if (copied) Icon(Icons.Outlined.Check, null, tint = BitOSColors.success, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (copied) "Copied" else "Copy invoice", color = BitOSColors.primary)
                        }
                        val context = androidx.compose.ui.platform.LocalContext.current
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$invoice")))
                            }
                        }) { Text("Open wallet", color = BitOSColors.primary) }
                    }
                }
            }
        }
        SheetCloseIcon(onClose = onDismiss)
    }
}

@Composable
private fun TierTile(sats: Int, recommended: Boolean, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    selected -> BitOSColors.primary.copy(alpha = 0.14f)
                    recommended -> BitOSColors.primary.copy(alpha = 0.07f)
                    else -> BitOSColors.surfaceOverlay.copy(alpha = 0.5f)
                },
            )
            .then(
                Modifier.padding(vertical = 10.dp),
            )
            .clickable { onClick() },
    ) {
        if (recommended) {
            Text("★", fontSize = 10.sp, color = BitOSColors.warning)
        } else {
            Spacer(Modifier.height(10.dp))
        }
        Text("$sats", fontSize = 15.sp, fontWeight = FontWeight.W800, fontFamily = FontFamily.Monospace, color = if (selected) BitOSColors.primary else BitOSColors.textPrimary)
        Text("sats", fontSize = 10.sp, color = BitOSColors.textTertiary)
    }
}

// ── Local helpers (keep imports light) ─────────────────────────────────

/** zxing QR bitmap for LNURL invoices (long payloads). */
internal fun invoiceQrBitmap(invoice: String): Bitmap? = runCatching {
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
