package space.bitos.app.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.identity.IdentityViewModel
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlinx.coroutines.withContext
import space.bitos.app.ui.theme.AppIcons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Settings
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
    /** APP-014: opens the zap wallet (local sent ledger + received). */
    onOpenZaps: () -> Unit = {},
    profileLookup: space.bitos.app.data.feed.ProfileLookupStore,
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
            profileLookup = profileLookup,
            onBack = { showSettings = false },
        )
        return
    }
    val state by identityViewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var showEdit by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showQr by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var npubCopied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val profileEditState by identityViewModel.profileEditState.collectAsStateWithLifecycle()
    val account = state.account

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .verticalScroll(rememberScrollState()),
    ) {
        if (account != null) {
            // ── Own profile page (legacy Flutter profile_view parity) ──
            val feedState by homeViewModel.state.collectAsStateWithLifecycle()
            val profile = feedState.profiles[account.pubkeyHex]
            var tab by remember { mutableStateOf(0) }

            // ── Full-bleed cover + floating glass controls ─────────────
            Box(Modifier.fillMaxWidth()) {
                // Banner (or brand gradient fallback) + scrims.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                ) {
                    val bannerUrl = profile?.banner.orEmpty()
                    val bannerBitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, bannerUrl) {
                        if (bannerUrl.isNotEmpty()) {
                            value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { android.graphics.BitmapFactory.decodeStream(java.net.URL(bannerUrl).openStream()) }.getOrNull()
                            }
                        }
                    }
                    if (bannerBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bannerBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(
                                        listOf(Color(0xFFF57A1A), Color(0xFFA12E0A)),
                                    ),
                                ),
                        ) { DefaultCoverHexPattern() }
                    }
                    // Scrim (keeps glass controls readable).
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f),
                                    0.5f to androidx.compose.ui.graphics.Color.Transparent,
                                    1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f),
                                ),
                            ),
                    )
                }
                // Glass controls row.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    GlassPill(icon = "camera", label = "Edit cover", onClick = { showEdit = true })
                    GlassIconButton(icon = AppIcons.Share, label = "Share profile") {
                        clipboard.setText(AnnotatedString("nostr:" + account.npub))
                    }
                    GlassIconButton(icon = Icons.Outlined.Settings, label = "Settings") { showSettings = true }
                }
            }

            // ── Avatar hero band (lifted onto the cover edge) ───────────
            Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.TopCenter) {
                ProfileHeroAvatar(
                    pubkey = account.pubkeyHex,
                    pictureUrl = profile?.picture,
                    label = profile?.bestDisplayName,
                    hasLightning = !profile?.lud16.isNullOrBlank(),
                    modifier = Modifier
                        .offset(y = (-46).dp)
                        .shadow(8.dp, RoundedCornerShape(16.dp)),
                )
            }

            // ── Identity block (centered, legacy _ProfileInfo) ──────────
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile?.bestDisplayName?.takeIf { it.isNotBlank() } ?: "Your account",
                        fontSize = 24.sp, fontWeight = FontWeight.W800, color = BitOSColors.textPrimary,
                    )
                    if (!profile?.nip05.isNullOrBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.Check, contentDescription = "Verified", tint = BitOSColors.success, modifier = Modifier.size(18.dp))
                    }
                }
                profile?.name?.takeIf { it.isNotBlank() }?.let {
                    Text("@$it", fontSize = 15.sp, color = BitOSColors.primary)
                }
                // npub chip (copy → check).
                TextButton(onClick = { clipboard.setText(AnnotatedString(account.npub)); npubCopied = true }) {
                    Text(
                        settingsStore.shortNpub(account.npub),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textSecondary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (npubCopied) "✓" else "⧉", fontSize = 11.sp, color = if (npubCopied) BitOSColors.success else BitOSColors.textTertiary)
                }
                // Chips: ⚡ Lightning (when lud16).
                if (!profile?.lud16.isNullOrBlank()) {
                    ProfileChip(text = "⚡ Lightning", fg = BitOSColors.zap, bg = BitOSColors.zap.copy(alpha = 0.10f))
                }
            }

            // Own-profile actions are live: metadata editor + canonical QR.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { showEdit = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Edit profile")
                }
                OutlinedButton(onClick = { showQr = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.QrCode2, contentDescription = "Show your profile QR code")
                }
            }

            OutlinedButton(
                onClick = onOpenZaps,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text("⚡ Zap wallet")
                Spacer(Modifier.weight(1f))
                Text("Ledger", fontSize = 11.sp, color = BitOSColors.textTertiary)
            }

            ProfileCompletionCard(
                missing = profileCompletionFields(profile),
                onFinish = { showEdit = true },
            )

            // ── Stats row (evenly spaced) ───────────────────────────────
            val own = feedState.notes.filter { it.pubkey == account.pubkeyHex || it.repostedBy == account.pubkeyHex }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatPill("Posts", "${own.count { it.replyTo == null && it.repostedBy == null }}")
                StatPill("Following", "${feedState.following.size}")
                StatPill("Bitz", "${own.count { it.video != null }}")
            }

            // ── About (collapsible) ─────────────────────────────────────
            profile?.about?.takeIf { it.isNotBlank() }?.let { bio ->
                var aboutExpanded by remember { mutableStateOf(bio.length <= 120) }
                Text(
                    bio,
                    fontSize = 13.sp, color = BitOSColors.textSecondary,
                    maxLines = if (aboutExpanded) Int.MAX_VALUE else 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (bio.length > 120) {
                    TextButton(onClick = { aboutExpanded = !aboutExpanded }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(if (aboutExpanded) "Show less" else "Show more", color = BitOSColors.primary, fontSize = 12.sp)
                    }
                }
            }
            val nip05 = profile?.nip05?.trim().orEmpty()
            val website = profile?.website?.trim().orEmpty()
            if (nip05.isNotEmpty() || website.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (nip05.isNotEmpty()) {
                        Text("✓ $nip05", fontSize = 12.sp, color = BitOSColors.textSecondary)
                    }
                    val uri = website.takeIf {
                        Uri.parse(it).scheme?.lowercase() in setOf("http", "https")
                    }?.let(Uri::parse)
                    if (uri != null) {
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) {
                            Text("↗ $website", fontSize = 12.sp, color = BitOSColors.primary, maxLines = 1)
                        }
                    }
                }
            }

            // ── Tab bar (Notes · Replies · Bitz · Reposts) ──────────────
            val tabs = listOf("Notes", "Replies", "Bitz", "Reposts")
            val tabNotes = own.filter { it.replyTo == null && it.repostedBy == null }
            val tabReplies = own.filter { it.replyTo != null && it.repostedBy == null }
            val tabBitz = own.filter { it.video != null }
            val tabReposts = own.filter { it.repostedBy == account.pubkeyHex }
            val content = listOf(tabNotes, tabReplies, tabBitz, tabReposts)[tab]
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BitOSColors.surface.copy(alpha = 0.5f)),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                tabs.forEachIndexed { index, label ->
                    val selected = tab == index
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.W800 else FontWeight.W500,
                        color = if (selected) BitOSColors.primary else BitOSColors.textSecondary,
                        modifier = Modifier
                            .clickable { tab = index }
                            .padding(vertical = 10.dp, horizontal = 16.dp),
                    )
                }
            }
            if (content.isEmpty()) {
                Text(
                    "Nothing here yet — this tab shows your notes currently in the live feed window.",
                    fontSize = 12.sp, color = BitOSColors.textTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                content.take(20).forEach { note ->
                    OwnNoteRow(note = note, shortNpub = settingsStore::shortNpub)
                }
            }
        } else {
            Column(
                Modifier.padding(BitOSSpacing.screen),
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
                BrowseOnlyPanel(
                    onCreate = identityViewModel::createKeyPreview,
                    onImport = { /* import field revealed below */ },
                )
                ImportPanel(onSubmit = identityViewModel::importNsecPreview, error = state.importError)
            }
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

    if (showEdit && state.account != null) {
        val editProfile = homeViewModel.state.value.profiles[state.account!!.pubkeyHex]
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showEdit = false }) {
            ProfileEditContent(
                initialNip05 = editProfile?.nip05.orEmpty(),
                initialLud16 = editProfile?.lud16.orEmpty(),
                initialName = editProfile?.name.orEmpty(),
                initialDisplayName = editProfile?.displayName.orEmpty(),
                initialAbout = editProfile?.about.orEmpty(),
                initialPicture = editProfile?.picture.orEmpty(),
                initialWebsite = editProfile?.website.orEmpty(),
                initialBanner = editProfile?.banner.orEmpty(),
                error = profileEditState.error,
                busy = profileEditState.busy,
                onUploadImage = { target, bytes ->
                    val spec = space.bitos.core.model.ProfileMediaSpec
                    val (w, h) = if (target == "avatar") spec.AVATAR_SIZE to spec.AVATAR_SIZE else spec.BANNER_WIDTH to spec.BANNER_HEIGHT
                    val prepped = space.bitos.app.ui.profile.ProfileImagePrep.cropScale(bytes, w, h)
                        ?: return@ProfileEditContent null
                    val signer = identityViewModel.createSigner() ?: return@ProfileEditContent null
                    runCatching {
                        space.bitos.app.data.media.BlossomUploader().upload(
                            prepped, "image/jpeg", signer, space.bitos.app.data.media.DefaultBlossomServer.url,
                        ).url
                    }.getOrNull()
                },
                onPublish = { name, displayName, about, nip05, lud16, picture, banner, website ->
                    identityViewModel.publishProfile(name, displayName, about, nip05, lud16, picture, banner, website)
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

// ── Legacy glass controls (`_GlassIconButton`/`_GlassPillButton` parity) ──

@Composable
private fun GlassIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.30f))
            .clickable(onClickLabel = label) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun GlassPill(icon: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.30f))
            .clickable(onClickLabel = label) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            when (icon) {
                "camera" -> "📷"
                else -> icon
            },
            fontSize = 13.sp,
        )
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.W600, color = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
private fun ProfileChip(text: String, fg: Color, bg: Color) {
    Text(
        text,
        fontSize = 11.sp, fontWeight = FontWeight.W700, color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Decorative native hex tile, matching the old app fallback cover. */
@Composable
private fun DefaultCoverHexPattern() {
    Canvas(Modifier.fillMaxSize()) {
        val tile = 120.dp.toPx()
        val hexHeight = 104.dp.toPx()
        var x = -tile / 2
        while (x < size.width + tile) {
            var y = -hexHeight / 2
            while (y < size.height + hexHeight) {
                val path = Path().apply {
                    moveTo(x + tile * .25f, y)
                    lineTo(x + tile * .75f, y)
                    lineTo(x + tile, y + hexHeight * .5f)
                    lineTo(x + tile * .75f, y + hexHeight)
                    lineTo(x + tile * .25f, y + hexHeight)
                    lineTo(x, y + hexHeight * .5f)
                    close()
                }
                drawPath(path, Color.White.copy(alpha = .08f))
                y += hexHeight
            }
            x += tile
        }
    }
}

/** Published profile picture in the same flat-top hex frame as the fallback. */
@Composable
private fun ProfileHeroAvatar(
    pubkey: String,
    pictureUrl: String?,
    label: String?,
    hasLightning: Boolean,
    modifier: Modifier = Modifier,
) {
    PubkeyAvatar(
        pubkey = pubkey,
        modifier = modifier,
        size = 92,
        pictureUrl = pictureUrl,
        label = label,
        hasLightning = hasLightning,
    )
}

private fun profileCompletionFields(profile: space.bitos.core.model.ProfileMetadata?): List<String> = buildList {
    if (profile?.displayName.isNullOrBlank() && profile?.name.isNullOrBlank()) add("Display name")
    if (profile?.about.isNullOrBlank()) add("Bio")
    if (profile?.picture.isNullOrBlank()) add("Profile picture")
    if (profile?.banner.isNullOrBlank()) add("Cover photo")
    if (profile?.nip05.isNullOrBlank()) add("Verified NIP-05")
    if (profile?.lud16.isNullOrBlank()) add("Lightning address")
    if (profile?.website.isNullOrBlank()) add("Website")
}

@Composable
private fun ProfileCompletionCard(missing: List<String>, onFinish: () -> Unit) {
    if (missing.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BitOSColors.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(BitOSSpacing.md), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✦", color = BitOSColors.primary, modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Complete your profile", fontSize = 13.sp, fontWeight = FontWeight.W800)
                    Text("${7 - missing.size} of 7 complete · ${missing.size} steps to go", fontSize = 10.sp, color = BitOSColors.textTertiary)
                }
                Button(onClick = onFinish) { Text("Finish", fontSize = 12.sp) }
            }
            val rows = missing.chunked(2)
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { field ->
                        Text("○ $field", fontSize = 10.sp, color = BitOSColors.textSecondary, modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
