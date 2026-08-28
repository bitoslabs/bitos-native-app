package space.bitos.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.ConfirmIdentityDialog
import space.bitos.app.ui.components.PubkeyAvatar
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.res.painterResource
import space.bitos.app.R
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing

/**
 * Profile surface with the account flow (ID-004): browse-first by default —
 * identity creation/import is always explicit and visibly confirms the
 * derived npub before anything is stored or replaced.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    identityViewModel: IdentityViewModel,
    settingsStore: space.bitos.app.data.settings.SettingsStore,
    feedRepository: space.bitos.app.data.feed.FeedRepository,
    relayManager: space.bitos.app.data.relay.RelayManager,
    notePublisher: space.bitos.app.data.publish.NotePublisher,
    notifications: space.bitos.app.data.feed.NotificationRepository,
    algorithmStore: space.bitos.app.data.feed.AlgorithmStore,
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel,
    privacyPrefs: space.bitos.app.data.settings.PrivacyPrefsStore,
) {
    var showSettings by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showSettings) {
        space.bitos.app.ui.settings.SettingsScreen(
            identityViewModel,
            store = settingsStore,
            feedRepository = feedRepository,
            relayManager = relayManager,
            notePublisher = notePublisher,
            notifications = notifications,
            algorithmStore = algorithmStore,
            homeViewModel = homeViewModel,
            privacyPrefs = privacyPrefs,
            onBack = { showSettings = false },
        )
        return
    }
    val state by identityViewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    var showEdit by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showQr by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val profileEditState by identityViewModel.profileEditState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .verticalScroll(rememberScrollState())
            .padding(BitOSSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("You", style = MaterialTheme.typography.headlineMedium)
            androidx.compose.material3.IconButton(onClick = { showSettings = true }) {
                androidx.compose.material3.Icon(
                    painterResource(R.drawable.solar_settings_linear),
                    contentDescription = "Settings",
                    tint = BitOSColors.textSecondary,
                )
            }
        }

        val account = state.account
        if (account != null) {
            // ── Own profile page (legacy Flutter profile_view parity) ──
            val feedState by homeViewModel.state.collectAsStateWithLifecycle()
            val profile = feedState.profiles[account.pubkeyHex]
            var tab by remember { mutableStateOf(0) }

            // Hero: gradient cover + overlapping hex avatar (web 160/104 parity).
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(BitOSColors.primary.copy(alpha = 0.35f), BitOSColors.accent.copy(alpha = 0.25f)),
                            ),
                        ),
                ) {}
                PubkeyAvatar(
                    pubkey = account.pubkeyHex,
                    size = 84,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .offset(y = 54.dp),
                )
            }
            Spacer(Modifier.height(46.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    profile?.bestDisplayName?.ifEmpty { "Your account" } ?: "Your account",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.W800,
                    color = BitOSColors.textPrimary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(account.npub)) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                    ) {
                        Text(
                            settingsStore.shortNpub(account.npub),
                            fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = BitOSColors.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            painterResource(R.drawable.solar_pen_linear),
                            contentDescription = "Copy npub", tint = BitOSColors.primary,
                            modifier = Modifier.width(14.dp),
                        )
                    }
                    TextButton(
                        onClick = { showQr = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                    ) {
                        Text("QR", fontSize = 12.sp, fontWeight = FontWeight.W700, color = BitOSColors.primary)
                    }
                }
                profile?.nip05?.takeIf { it.isNotEmpty() }?.let {
                    Text("✓ $it", fontSize = 12.sp, color = BitOSColors.success)
                }
                profile?.about?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = 13.sp, color = BitOSColors.textSecondary, maxLines = 4)
                }
            }

            // Stats (legacy `_FollowStats` parity, live window data).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val ownNotes = feedState.notes.filter { it.pubkey == account.pubkeyHex }
                StatPill("Following", "${feedState.following.size}")
                StatPill("Notes", "${ownNotes.count { it.replyTo == null }}")
                StatPill("Bitz", "${ownNotes.count { it.video != null }}")
            }

            // Actions (own: Edit + Settings).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.material3.OutlinedButton(onClick = { showEdit = true }, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.solar_pen_linear), contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Edit profile")
                }
                androidx.compose.material3.OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.solar_settings_linear), contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Settings")
                }
            }

            // Tabs (legacy ProfileTab parity): own window content.
            val tabs = listOf("Notes", "Replies", "Bitz", "Reposts")
            val own = feedState.notes.filter { it.pubkey == account.pubkeyHex || it.repostedBy == account.pubkeyHex }
            val tabNotes = own.filter { it.replyTo == null && it.repostedBy == null }
            val tabReplies = own.filter { it.replyTo != null && it.repostedBy == null }
            val tabBitz = own.filter { it.video != null }
            val tabReposts = own.filter { it.repostedBy == account.pubkeyHex }
            val content = listOf(tabNotes, tabReplies, tabBitz, tabReposts)[tab]
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEachIndexed { index, label ->
                    val selected = tab == index
                    Text(
                        label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.W700 else FontWeight.W500,
                        color = if (selected) BitOSColors.primary else BitOSColors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (selected) BitOSColors.primaryContainer else BitOSColors.surfaceOverlay.copy(alpha = 0.5f))
                            .clickable { tab = index }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            if (content.isEmpty()) {
                Text(
                    "Nothing here yet — this tab shows your notes currently in the live feed window.",
                    fontSize = 12.sp, color = BitOSColors.textTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                content.take(20).forEach { note ->
                    OwnNoteRow(note = note, shortNpub = settingsStore::shortNpub)
                }
            }
        } else {
            BrowseOnlyPanel(
                onCreate = identityViewModel::createKeyPreview,
                onImport = { /* import field revealed below */ },
            )
            ImportPanel(onSubmit = identityViewModel::importNsecPreview, error = state.importError)
        }
    }

    if (showQr && state.account != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text("Your identity QR") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    space.bitos.app.ui.components.BrandQrCode(value = state.account!!.npub, sizeDp = 224)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Scan with any Nostr app to follow " + settingsStore.shortNpub(state.account!!.npub),
                        fontSize = 12.sp, color = BitOSColors.textSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(state.account!!.npub)); showQr = false }) {
                    Text("Copy npub", color = BitOSColors.primary)
                }
            },
            dismissButton = { TextButton(onClick = { showQr = false }) { Text("Close") } },
        )
    }

    if (showEdit) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showEdit = false }) {
            ProfileEditContent(
                initialNip05 = "",
                initialLud16 = "",
                error = profileEditState.error,
                busy = profileEditState.busy,
                onPublish = { name, displayName, about, nip05, lud16 ->
                    identityViewModel.publishProfile(name, displayName, about, nip05, lud16)
                },
                onClose = { showEdit = false; identityViewModel.clearProfileEditError() },
            )
        }
    }

    state.preview?.let { preview ->
        ConfirmIdentityDialog(
            npub = preview.npub,
            replacesExisting = preview.replacesExisting,
            busy = state.busy,
            onConfirm = identityViewModel::confirmPreview,
            onDismiss = identityViewModel::cancelPreview,
        )
    }
}

@Composable
private fun AccountPanel(npub: String, onCopy: () -> Unit, onRemove: () -> Unit) {
    var confirmRemove by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = BitOSColors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PubkeyAvatar(pubkey = npub, size = 48)
                Spacer(Modifier.width(BitOSSpacing.md))
                Column {
                    Text("Local identity active", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                    Text("Secret sealed in Android Keystore", style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
                }
            }
            Text(
                npub,
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
                maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                OutlinedButton(onClick = onCopy) { Text("Copy npub") }
                OutlinedButton(onClick = { confirmRemove = true }) { Text("Remove") }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove identity?") },
            text = { Text("The sealed secret is deleted from this device. Without a backup you lose the account. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Remove", color = BitOSColors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun BrowseOnlyPanel(onCreate: () -> Unit, onImport: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = BitOSColors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            Text("Browsing without an identity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
            Text(
                "You can watch and explore anonymously. Actions that need a signature offer key creation or import below — nothing is created silently.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                Button(onClick = onCreate) {
                    Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Create identity")
                }
                OutlinedButton(onClick = onImport) {
                    Icon(Icons.Outlined.PersonAddAlt, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Import nsec")
                }
            }
        }
    }
}

@Composable
private fun ImportPanel(onSubmit: (String) -> Unit, error: String?) {
    var input by remember { mutableStateOf("") }
    Surface(shape = RoundedCornerShape(16.dp), color = BitOSColors.surfaceElevated, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            Text("Import a secret key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("nsec1…") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it, color = BitOSColors.error) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { onSubmit(input) }, enabled = input.isNotBlank()) {
                Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("Review key")
            }
            Text(
                "The key stays on this device, sealed in the Android Keystore. Never share an nsec.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textTertiary,
            )
        }
    }
}


// ── Own-profile helpers (legacy parity) ────────────────────────────────

@Composable
private fun StatPill(label: String, value: String) {
    Column {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.W800, fontFamily = FontFamily.Monospace, color = BitOSColors.textPrimary)
        Text(label, fontSize = 11.sp, color = BitOSColors.textTertiary)
    }
}

@Composable
private fun OwnNoteRow(note: space.bitos.core.feed.FeedNote, shortNpub: (String) -> String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (note.video != null) BitOSColors.accent else BitOSColors.primary),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                note.content.take(120).ifEmpty { "(media)" },
                fontSize = 13.sp, color = BitOSColors.textPrimary, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                shortNpub("npub1" + note.pubkey.take(10)),
                fontSize = 10.sp, color = BitOSColors.textTertiary,
            )
        }
    }
}
