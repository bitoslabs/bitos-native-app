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
    onOpenLightning: () -> Unit,
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
    var importInput by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var importError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

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
                    PubkeyAvatar(pubkey = account.pubkeyHex, size = 48)
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
            MoreTile(icon = Icons.Outlined.Bolt, label = "Lightning & Zaps", caption = "Default zap amount", onClick = onOpenLightning)
        }

        GroupLabel("Account")
        HubCard {
            MoreTile(icon = Icons.Outlined.Person, label = "Profile", caption = "Your identity", onClick = onOpenProfile)
            MoreTile(icon = Icons.Outlined.Settings, label = "Settings", caption = "Preferences & relays", onClick = onOpenSettings)
        }

        GroupLabel("Coming soon")
        HubCard {
            MoreTile(
                icon = Icons.Outlined.Bookmark,
                label = "Saved",
                caption = "Bookmarks page (APP-015 remainder)",
                onClick = {},
                enabled = false,
            )
            MoreTile(
                icon = Icons.Outlined.Bolt,
                label = "Zap ledger",
                caption = "Wallet page (APP-014)",
                onClick = {},
                enabled = false,
            )
        }

        GroupLabel("About")
        HubCard {
            MetaRow(label = "About BitOS", onClick = onOpenSettings)
            MetaRow(label = "Privacy", onClick = onOpenSettings)
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
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isActive && !identity.busy) {
                                    identityViewModel.switchTo(acct.pubkeyHex)
                                    showSwitcher = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PubkeyAvatar(pubkey = acct.pubkeyHex, size = 40)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    acct.displayName ?: "Account",
                                    fontSize = 15.sp,
                                    fontWeight = if (isActive) FontWeight.W700 else FontWeight.W500,
                                    color = BitOSColors.textPrimary,
                                )
                                Text(
                                    settingsStore.shortNpub(acct.npub),
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textSecondary,
                                )
                            }
                            if (isActive) {
                                Text("Active", fontSize = 11.sp, fontWeight = FontWeight.W700, color = BitOSColors.primary)
                            }
                        }
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

    // ── Add account (import nsec / create) ────────────────────────────
    if (showAddAccount) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAddAccount = false; importError = null },
            title = { Text("Add account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Import an nsec or create a fresh key. Every account already on this device stays sealed.",
                        fontSize = 12.sp, color = BitOSColors.textSecondary,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = importInput,
                        onValueChange = { importInput = it; importError = null },
                        singleLine = true,
                        isError = importError != null,
                        placeholder = { Text("nsec1\u2026", fontSize = 13.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    )
                    importError?.let { Text(it, fontSize = 12.sp, color = BitOSColors.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                identityViewModel.importNsecPreview(importInput)
                                importError = identityViewModel.state.value.importError
                            },
                            enabled = importInput.isNotBlank(),
                        ) { Text("Review key", color = BitOSColors.primary, fontWeight = FontWeight.W600) }
                        TextButton(onClick = { identityViewModel.createKeyPreview() }) {
                            Text("Create new key", color = BitOSColors.primary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAddAccount = false }) { Text("Cancel") } },
        )
    }

    identity.preview?.let { preview ->
        space.bitos.app.ui.components.ConfirmIdentityDialog(
            npub = preview.npub,
            replacesExisting = preview.replacesExisting,
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
