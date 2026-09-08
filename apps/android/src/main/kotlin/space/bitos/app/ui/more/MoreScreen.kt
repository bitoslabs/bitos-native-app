package space.bitos.app.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.data.relay.RelayManager
import space.bitos.app.data.settings.SettingsStore
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.HexShape
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.feed.HomeViewModel
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors

/**
 * APP-017 More hub (legacy Flutter `MoreView` parity — the web NavRail
 * destination list collapsed into one page, opened from the feed apps-grid
 * action): profile hero + account switch row + stat tiles + tile groups +
 * meta rows. Tiles whose surfaces are later waves are omitted — never dead
 * links.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    identityViewModel: IdentityViewModel,
    homeViewModel: HomeViewModel,
    settingsStore: SettingsStore,
    relayManager: RelayManager,
    onOpenProfile: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStaticAbout: () -> Unit = onOpenSettings,
    onOpenStaticPrivacy: () -> Unit = onOpenSettings,
    onOpenStaticTerms: () -> Unit = onOpenSettings,
    onOpenLightning: () -> Unit,
    /** APP-015: opens the Saved (bookmarks) page. */
    onOpenSaved: () -> Unit = {},
    /** "Use this sound" Wave D: trending-sounds rail (APP-021 bootstrap). */
    onOpenSounds: () -> Unit = {},
    /** APP-014: opens the zap wallet (sent ledger + received receipts). */
    onOpenZaps: () -> Unit = {},
    onClose: () -> Unit,
) {
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    val registered by identityViewModel.registeredAccounts.collectAsStateWithLifecycle()
    val activePubkey by identityViewModel.activeRegistryPubkey.collectAsStateWithLifecycle()
    val feedState by homeViewModel.state.collectAsStateWithLifecycle()
    val relays by relayManager.entries.collectAsStateWithLifecycle()
    val connections by relayManager.connectionStates.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val account = identity.account
    var showSwitcher by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showAddAccount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showQr by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    /** Branded switch overlay target (legacy overlay parity). */
    var switchTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<space.bitos.core.identity.RegisteredAccount?>(null)
    }
    var importInput by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("More", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.W700)
            TextButton(onClick = onClose) { Text("Done", color = BitOSColors.primary, fontWeight = FontWeight.W600) }
        }

        if (account == null) {
            // Guest card (legacy `_GuestCard` parity).
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BitOSColors.surface.copy(alpha = 0.6f))
                    .border(1.dp, BitOSColors.border.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(20.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Browse freely", fontSize = 16.sp, fontWeight = FontWeight.W700, color = BitOSColors.textPrimary)
                    Text(
                        "Create or import a key on the You tab to publish, zap and follow.",
                        fontSize = 13.sp, color = BitOSColors.textSecondary,
                    )
                }
            }
        } else {
            // ── Profile hero (legacy `_ProfileHero` parity) ───────────
            val profile = feedState.profiles[account.pubkeyHex]
            HubCard {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PubkeyAvatar(
                        pubkey = account.pubkeyHex,
                        size = 48,
                        pictureUrl = profile?.picture,
                        label = profile?.bestDisplayName,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile?.bestDisplayName ?: "Your account",
                            fontSize = 16.sp, fontWeight = FontWeight.W700, color = BitOSColors.textPrimary,
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(account.npub))
                        }) {
                            Text(
                                settingsStore.shortNpub(account.npub),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.primary,
                            )
                        }
                    }
                    TextButton(onClick = { showQr = true }) {
                        Text("QR", color = BitOSColors.primary, fontWeight = FontWeight.W600)
                    }
                    TextButton(onClick = onOpenProfile) {
                        Text("Profile", color = BitOSColors.primary, fontWeight = FontWeight.W600)
                    }
                }
            }

            // ── Account switch row (legacy `_AccountSwitchRow` parity) ──
            HubCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showSwitcher = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Switch account", fontSize = 12.sp, color = BitOSColors.textTertiary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${registered.size} on this device",
                        fontSize = 12.sp, fontWeight = FontWeight.W700, color = BitOSColors.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = BitOSColors.textTertiary)
                }
            }

            // ── Stat tiles (legacy `_StatTile` parity) ────────────────
            val connected = connections.values.count { it == space.bitos.app.data.relay.RelayConnectionState.CONNECTED }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(label = "Following", value = "${feedState.following.size}", modifier = Modifier.weight(1f))
                StatTile(label = "Relays", value = "$connected/${connections.size}", modifier = Modifier.weight(1f))
            }
        }

        // ── Tile groups (only live surfaces) ─────────────────────────
        GroupLabel("Explore")
        HubCard {
            MoreTile(icon = Icons.Outlined.Search, label = "Discover", caption = "Search Nostr", onClick = onOpenDiscover)
            MoreTile(icon = AppIcons.MusicNote, label = "Trending sounds", caption = "Most-borrowed ♪ in your feed", onClick = onOpenSounds)
            MoreTile(icon = Icons.Outlined.Bolt, label = "Lightning & Zaps", caption = "Default zap amount", onClick = onOpenLightning)
        }

        GroupLabel("Account")
        HubCard {
            MoreTile(icon = Icons.Outlined.Person, label = "Profile", caption = "Your identity", onClick = onOpenProfile)
            // APP-014: same wallet surface the You page opens (menu item +
            // "Sats zapped" pill) — one entry point per identity, no fork.
            MoreTile(icon = Icons.Outlined.Bolt, label = "Zap wallet", caption = "Sent & received sats", onClick = onOpenZaps)
            MoreTile(icon = Icons.Outlined.Settings, label = "Settings", caption = "Preferences & relays", onClick = onOpenSettings)
        }

        GroupLabel("Library")
        HubCard {
            MoreTile(icon = Icons.Outlined.Bookmark, label = "Saved", caption = "Your bookmarked notes", onClick = onOpenSaved)
        }

        GroupLabel("About")
        HubCard {
            MetaRow(label = "About BitOS", onClick = onOpenStaticAbout)
            MetaRow(label = "Privacy", onClick = onOpenStaticPrivacy)
        }
        Spacer(Modifier.height(16.dp))
    }

    // ── Account switcher sheet (legacy AccountSwitcherSheet parity) ──
    if (showSwitcher) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showSwitcher = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Switch account", fontSize = 20.sp, fontWeight = FontWeight.W800, color = BitOSColors.textPrimary)
                Text(
                    "${registered.size} saved on this device",
                    fontSize = 11.sp, color = BitOSColors.textTertiary,
                )
                Spacer(Modifier.height(10.dp))
                if (registered.isEmpty()) {
                    Text("No saved accounts yet.", fontSize = 13.sp, color = BitOSColors.textSecondary)
                } else {
                    for (acct in registered) {
                        val isActive = acct.pubkeyHex == (activePubkey ?: account?.pubkeyHex)
                        // Legacy `_AccountRow` parity: rounded card row —
                        // active tint + border; badges from the account's
                        // kind-0 when the feed has seen it.
                        val profile = feedState.profiles[acct.pubkeyHex]
                        val name = acct.displayName
                            ?: profile?.bestDisplayName?.takeIf { it.isNotBlank() }
                            ?: "Account"
                        val nip05 = profile?.nip05?.takeIf { it.isNotEmpty() }
                        val hasLightning = profile?.lud16.orEmpty().isNotEmpty()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isActive) BitOSColors.primary.copy(alpha = 0.06f)
                                    else BitOSColors.surface.copy(alpha = 0.4f),
                                )
                                .border(
                                    1.dp,
                                    if (isActive) BitOSColors.primary.copy(alpha = 0.25f)
                                    else BitOSColors.border.copy(alpha = 0.35f),
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable(enabled = !isActive && !identity.busy) {
                                    showSwitcher = false // sheet closes; the full-page overlay takes over
                                    switchTarget = acct
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box {
                                PubkeyAvatar(
                                    pubkey = acct.pubkeyHex,
                                    size = 40,
                                    pictureUrl = profile?.picture,
                                    label = name,
                                )
                                if (hasLightning) {
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomEnd)
                                            .size(14.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(BitOSColors.zap)
                                            .border(1.dp, BitOSColors.background, androidx.compose.foundation.shape.CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("⚡", fontSize = 7.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.W700,
                                        color = BitOSColors.textPrimary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (nip05 != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Outlined.Check,
                                            contentDescription = null,
                                            tint = BitOSColors.success,
                                            modifier = Modifier.size(11.dp),
                                        )
                                    }
                                }
                                Text(
                                    settingsStore.shortNpub(acct.npub),
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textSecondary,
                                )
                                if (nip05 != null) {
                                    Text(nip05, fontSize = 10.sp, color = BitOSColors.success, maxLines = 1)
                                }
                            }
                            when {
                                identity.busy && !isActive -> androidx.compose.material3.CircularProgressIndicator(
                                    strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = BitOSColors.primary,
                                )
                                isActive -> Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = "Active account",
                                    tint = BitOSColors.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                else -> Icon(
                                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = BitOSColors.textTertiary,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = BitOSColors.border.copy(alpha = 0.4f))
                TextButton(onClick = { showSwitcher = false; showAddAccount = true }) {
                    Text("+ Add account", color = BitOSColors.primary, fontWeight = FontWeight.W600)
                }
                TextButton(onClick = { showSwitcher = false; onOpenProfile() }) {
                    Text("Manage accounts (You tab)", color = BitOSColors.textSecondary)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // ── Add account (import nsec / create): bottom sheet, shared v2 copy,
    // live derived-identity preview + collapsible nsec help (KF-6/7) ────
    if (showAddAccount) {
        space.bitos.app.ui.components.AddAccountSheet(
            importInput = importInput,
            onImportChange = {
                importInput = it
                identityViewModel.clearImportError()
            },
            error = identity.importError,
            onReview = { identityViewModel.importNsecPreview(importInput) },
            onCreateKey = { identityViewModel.createKeyPreview() },
            onDismiss = { showAddAccount = false; importInput = "" },
        )
    }

    if (showQr && account != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text("Your identity QR") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    space.bitos.app.ui.components.BrandQrCode(value = account.npub, sizeDp = 224)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Scan with any Nostr app to follow " + settingsStore.shortNpub(account.npub),
                        fontSize = 12.sp, color = BitOSColors.textSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(account.npub)); showQr = false }) {
                    Text("Copy npub", color = BitOSColors.primary)
                }
            },
            dismissButton = { TextButton(onClick = { showQr = false }) { Text("Close") } },
        )
    }

    switchTarget?.let { target ->
        val fromPubkey = (activePubkey ?: account?.pubkeyHex)
        space.bitos.app.ui.components.AccountSwitchOverlay(
            fromPubkey = fromPubkey,
            toPubkey = target.pubkeyHex,
            toName = target.displayName ?: target.npub.take(10) + "…",
            hapticsEnabled = { settingsStore.snapshot.value.hapticEnabled },
            switchAction = { identityViewModel.switchTo(target.pubkeyHex) },
            onFinished = { switchTarget = null },
        )
    }

    identity.preview?.let { preview ->
        space.bitos.app.ui.components.ConfirmIdentityDialog(
            npub = preview.npub,
            replacesExisting = preview.replacesExisting,
            isNewKey = preview.isNewKey,
            secretNsec = if (preview.isNewKey) identityViewModel.previewNsec() else null,
            busy = identity.busy,
            onConfirm = { identityViewModel.confirmPreview(); showAddAccount = false },
            onDismiss = { identityViewModel.cancelPreview(); showAddAccount = false },
        )
    }
}

@Composable
private fun HubCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BitOSColors.surface.copy(alpha = 0.6f))
            .border(1.dp, BitOSColors.border.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
    ) { content() }
}

@Composable
private fun GroupLabel(label: String) {
    Text(
        label.uppercase(),
        fontSize = 11.sp, fontWeight = FontWeight.W700, color = BitOSColors.textTertiary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(BitOSColors.surface.copy(alpha = 0.6f))
            .border(1.dp, BitOSColors.border.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp, horizontal = 16.dp),
    ) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.W800, fontFamily = FontFamily.Monospace, color = BitOSColors.textPrimary)
        Text(label, fontSize = 11.sp, color = BitOSColors.textTertiary)
    }
}

@Composable
private fun MoreTile(
    icon: ImageVector,
    label: String,
    caption: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(HexShape())
                .background((if (enabled) BitOSColors.primary else BitOSColors.textTertiary).copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) BitOSColors.primary else BitOSColors.textTertiary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.W500, color = if (enabled) BitOSColors.textPrimary else BitOSColors.textSecondary)
            Text(caption, fontSize = 12.sp, color = BitOSColors.textTertiary)
        }
        if (enabled) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = BitOSColors.textTertiary)
        }
    }
}

@Composable
private fun MetaRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = BitOSColors.textPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = BitOSColors.textTertiary)
    }
}
