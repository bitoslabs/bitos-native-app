package space.bitos.app.ui.settings

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import space.bitos.app.R
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.bitos.app.data.feed.DefaultRelays
import space.bitos.app.data.feed.FeedRepository
import space.bitos.app.data.publish.NotePublisher
import space.bitos.app.data.relay.RelayConnectionState
import space.bitos.app.data.relay.RelayManager
import space.bitos.app.data.settings.SettingsStore
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.HexShape
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.theme.BitOSColors
import androidx.activity.compose.BackHandler

/**
 * APP-018 Settings hub (unified feature spec §3.18, legacy Flutter
 * `SettingsView`/`settings_section_page` parity): account hero + the shared
 * section catalog from business-core (web mobile index grouping), hex icon
 * tiles, per-section detail screens executing the shared settings contract
 * through [SettingsStore]. Sections whose features are later waves show
 * honest pending notes, never fake controls.
 */
@Composable
fun SettingsScreen(
    identityViewModel: IdentityViewModel,
    store: SettingsStore,
    feedRepository: FeedRepository,
    relayManager: RelayManager,
    notePublisher: NotePublisher,
    notifications: space.bitos.app.data.feed.NotificationRepository,
    algorithmStore: space.bitos.app.data.feed.AlgorithmStore,
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel,
    privacyPrefs: space.bitos.app.data.settings.PrivacyPrefsStore,
    onBack: () -> Unit = {},
) {
    var openSection by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = openSection != null) { openSection = null }

    if (openSection != null) {
        SettingsDetail(
            sectionKey = openSection!!,
            identityViewModel = identityViewModel,
            store = store,
            feedRepository = feedRepository,
            relayManager = relayManager,
            notePublisher = notePublisher,
            notifications = notifications,
            algorithmStore = algorithmStore,
            homeViewModel = homeViewModel,
            privacyPrefs = privacyPrefs,
        )
        return
    }

    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    val account = identity.account
    val clipboard = LocalClipboardManager.current
    var npubCopied by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.W700)
            TextButton(onClick = onBack) { Text("Done", color = BitOSColors.primary, fontWeight = FontWeight.W600) }
        }

        // ── Account hero ─────────────────────────────────────────────
        SectionCard {
            if (account != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { openSection = "account" }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PubkeyAvatar(pubkey = account.pubkeyHex, size = 48)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Your account",
                            fontSize = 16.sp, fontWeight = FontWeight.W700, color = BitOSColors.textPrimary,
                        )
                        Text(
                            store.shortNpub(account.npub),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textSecondary,
                        )
                    }
                    Chevron()
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No account", fontSize = 16.sp, fontWeight = FontWeight.W700, color = BitOSColors.textPrimary)
                    Text(
                        "Create or import a key from the You tab to start publishing.",
                        fontSize = 13.sp, color = BitOSColors.textSecondary,
                    )
                }
            }
        }

        SectionGroupLabel("Preferences")
        SectionCard {
            for (spec in sectionSpecs.filter { it.group == "PREFERENCES" }) {
                SectionRow(spec) { openSection = spec.key }
            }
        }

        SectionGroupLabel("Content")
        SectionCard {
            for (spec in sectionSpecs.filter { it.group == "CONTENT" }) {
                SectionRow(spec) { openSection = spec.key }
            }
        }

        SectionGroupLabel("Support")
        SectionCard {
            for (spec in sectionSpecs.filter { it.group == "SUPPORT" }) {
                SectionRow(spec) { openSection = spec.key }
            }
        }

        if (account != null) {
            if (confirmSignOut) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { identityViewModel.signOut(); confirmSignOut = false }) {
                        Text("Confirm sign out", color = BitOSColors.error, fontWeight = FontWeight.W600)
                    }
                    TextButton(onClick = { confirmSignOut = false }) {
                        Text("Cancel", color = BitOSColors.textSecondary)
                    }
                }
            } else {
                TextButton(onClick = { confirmSignOut = true }) {
                    Text("Sign out", color = BitOSColors.error, fontWeight = FontWeight.W600)
                }
            }
        }

        Text(
            "BitOS 1.0 · settings shared-contract parity",
            fontSize = 11.sp, color = BitOSColors.textTertiary,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 16.dp),
        )
    }
}

// ── Section catalog presentation (labels/icons/tints stay native) ─────

private data class SectionSpec(
    val key: String,
    val label: String,
    @DrawableRes val iconRes: Int,
    val tint: Color,
    val group: String,
)

private val sectionSpecs = listOf(
    SectionSpec("account", "Account", R.drawable.solar_settings_account, Color(0xFF2F95F6), "HERO"),
    SectionSpec("lightning", "Lightning & Zaps", R.drawable.solar_settings_lightning, Color(0xFFFF9500), "PREFERENCES"),
    SectionSpec("privacy", "Privacy", R.drawable.solar_settings_privacy, Color(0xFF5856D6), "PREFERENCES"),
    SectionSpec("notifications", "Notifications", R.drawable.solar_settings_notifications, Color(0xFFFF3B30), "PREFERENCES"),
    SectionSpec("appearance", "Appearance", R.drawable.solar_settings_appearance, Color(0xFFFF2D92), "PREFERENCES"),
    SectionSpec("algorithm", "Algorithm & Feed", R.drawable.solar_settings_algorithm, Color(0xFFBF5AF2), "PREFERENCES"),
    SectionSpec("security", "Security", R.drawable.solar_settings_security, Color(0xFFFF9500), "CONTENT"),
    SectionSpec("media", "Media", R.drawable.solar_settings_media, Color(0xFF34C759), "CONTENT"),
    SectionSpec("language", "Language & Region", R.drawable.solar_settings_language, Color(0xFF5AC8FA), "CONTENT"),
    SectionSpec("relays", "Relays", R.drawable.solar_settings_relays, Color(0xFF5AC8FA), "CONTENT"),
    SectionSpec("help", "Help & FAQ", R.drawable.solar_settings_help, Color(0xFF32ADE6), "SUPPORT"),
    SectionSpec("about", "About", R.drawable.solar_settings_about, Color(0xFF8E8E93), "SUPPORT"),
)

// ── Shared building blocks ─────────────────────────────────────────────

@Composable
private fun SectionGroupLabel(label: String) {
    Text(
        label.uppercase(),
        fontSize = 11.sp, fontWeight = FontWeight.W700,
        color = BitOSColors.textTertiary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BitOSColors.surface.copy(alpha = 0.6f))
            .border(1.dp, BitOSColors.border.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
    ) { content() }
}

@Composable
private fun SectionRow(spec: SectionSpec, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HexIconTile(iconRes = spec.iconRes, tint = spec.tint, size = 38)
        Spacer(Modifier.width(12.dp))
        Text(
            spec.label,
            fontSize = 15.sp, fontWeight = FontWeight.W500, color = BitOSColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Chevron()
    }
}

/** Hex tile matching the iOS `HexIcon` / legacy Flutter `HexIconTile`
 *  (12% tint background + 1.2dp ring inside the brand hexagon). */
@Composable
internal fun HexIconTile(@DrawableRes iconRes: Int, tint: Color, size: Int = 38) {
    Box(
        Modifier
            .size(size.dp)
            .clip(HexShape())
            .background(tint.copy(alpha = 0.12f))
            .border(1.2.dp, tint.copy(alpha = 0.35f), HexShape()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.48f).dp))
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = BitOSColors.textTertiary,
    )
}

// ── Detail screens ─────────────────────────────────────────────────────

@Composable
private fun SettingsDetail(
    sectionKey: String,
    identityViewModel: IdentityViewModel,
    store: SettingsStore,
    feedRepository: FeedRepository,
    relayManager: RelayManager,
    notePublisher: NotePublisher,
    notifications: space.bitos.app.data.feed.NotificationRepository,
    algorithmStore: space.bitos.app.data.feed.AlgorithmStore,
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel,
    privacyPrefs: space.bitos.app.data.settings.PrivacyPrefsStore,
) {
    val snapshot by store.snapshot.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            detailTitle(sectionKey),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.W700,
        )
        when (sectionKey) {
            "notifications" -> NotificationsDetail(snapshot, store, notifications)
            "appearance" -> AppearanceDetail(snapshot, store)
            "algorithm" -> AlgorithmDetail(snapshot, store, algorithmStore)
            "media" -> MediaDetail(snapshot, store)
            "language" -> LanguageDetail(snapshot, store)
            "account" -> AccountDetail(identityViewModel, store, feedRepository)
            "about" -> AboutDetail(store)
            "security" -> SecurityDetail(identityViewModel)
            "lightning" -> LightningDetail(snapshot, store)
            "privacy" -> PrivacyDetail(snapshot, store, homeViewModel, identityViewModel, notePublisher, relayManager, privacyPrefs)
            "relays" -> RelaysDetail(feedRepository, relayManager, identityViewModel, notePublisher)
            "help" -> HelpDetail()
            else -> PendingDetail(sectionKey)
        }
    }
}

private fun detailTitle(key: String): String =
    sectionSpecs.firstOrNull { it.key == key }?.label ?: key.replaceFirstChar { it.uppercase() }

@Composable
private fun NotificationsDetail(
    snapshot: space.bitos.core.settings.SettingsSnapshot,
    store: SettingsStore,
    notifications: space.bitos.app.data.feed.NotificationRepository,
) {
    val notifState by notifications.state.collectAsStateWithLifecycle()
    val muted = notifState.mutedKinds
    DetailCard("Notifications") {
        PrefRow("Notifications", snapshot.notificationsEnabled) {
            store.setRaw(space.bitos.core.settings.SettingsContract.KEY_NOTIFICATIONS_ENABLED, if (it) "1" else "0")
        }
        PrefRow("Sound", snapshot.soundEnabled, enabled = snapshot.notificationsEnabled) {
            store.setRaw(space.bitos.core.settings.SettingsContract.KEY_SOUND_ENABLED, if (it) "1" else "0")
        }
        PrefRow("Haptics", snapshot.hapticEnabled) {
            store.setRaw(space.bitos.core.settings.SettingsContract.KEY_HAPTIC_ENABLED, if (it) "1" else "0")
        }
    }
    Footnote("Push delivery arrives with the notification service (APP-012).")
    DetailCard("Notify me about") {
        for (kind in space.bitos.core.model.NotificationKind.entries) {
            PrefRow(notificationTypeLabel(kind), kind !in muted) { enabled ->
                val next = if (enabled) muted - kind else muted + kind
                notifications.setMutedKinds(next)
            }
        }
    }
    Footnote("Muted types drop from the inbox, unread counts and the Activity badge (same mutes as the inbox header menu).")
}

private fun notificationTypeLabel(kind: space.bitos.core.model.NotificationKind): String = when (kind) {
    space.bitos.core.model.NotificationKind.REPLY -> "Replies"
    space.bitos.core.model.NotificationKind.MENTION -> "Mentions"
    space.bitos.core.model.NotificationKind.REACTION -> "Likes"
    space.bitos.core.model.NotificationKind.REPOST -> "Reposts"
    space.bitos.core.model.NotificationKind.ZAP -> "Zaps"
    space.bitos.core.model.NotificationKind.FOLLOW -> "New follows"
}

@Composable
private fun AppearanceDetail(
    snapshot: space.bitos.core.settings.SettingsSnapshot,
    store: SettingsStore,
) {
    DetailCard("Theme") {
        OptionRow(
            title = "Theme",
            options = listOf("light" to "Light", "dark" to "Dark", "system" to "System"),
            selected = snapshot.themeMode.wire,
        ) { store.setRaw(space.bitos.core.settings.SettingsContract.KEY_THEME_MODE, it) }
    }
    Footnote("Light surfaces land with the theming wave (APP-023); the preference is already persisted.")
    DetailCard("Text Size") {
        OptionRow(
            title = "Text size",
            options = listOf("small" to "Small", "default" to "Default", "large" to "Large", "extra_large" to "Extra Large"),
            selected = snapshot.fontSize.wire,
        ) { store.setRaw(space.bitos.core.settings.SettingsContract.KEY_FONT_SIZE, it) }
    }
    Footnote("Text scaling applies app-wide with the theming wave (APP-023).")
    DetailCard("Layout") {
        PrefRow("Compact mode", snapshot.compactMode) {
            store.setRaw(space.bitos.core.settings.SettingsContract.KEY_COMPACT_MODE, if (it) "1" else "0")
        }
    }
    DetailCard("Accent Color") {
        AccentPaletteRow(
            selectedHex = snapshot.accentColorHex,
            onPick = { store.setRaw(space.bitos.core.settings.SettingsContract.KEY_ACCENT_COLOR, it) },
        )
    }
    Footnote("Light surfaces and app-wide accent/font scaling apply with the theming wave (APP-023); the choices are already persisted.")
}

@Composable
private fun AlgorithmDetail(
    snapshot: space.bitos.core.settings.SettingsSnapshot,
    store: SettingsStore,
    algorithmStore: space.bitos.app.data.feed.AlgorithmStore,
) {
    val C = space.bitos.core.settings.SettingsContract
    val algo by algorithmStore.snapshot.collectAsStateWithLifecycle()
    var surface by remember { mutableStateOf<space.bitos.core.feed.AlgorithmSurface>(space.bitos.core.feed.AlgorithmSurface.FEED) }
    val setting = algo.surfaces[surface] ?: space.bitos.core.feed.AlgorithmContract.preset(
        surface, space.bitos.core.feed.AlgorithmPresetId.BALANCED,
    )
    val preset = algorithmStore.detectPreset(surface)

    DetailCard("Feed") {
        OptionRow("Timeline", listOf("latest" to "Latest", "trending" to "Trending"), snapshot.feedTimeline.wire) {
            store.setRaw(C.KEY_FEED_TIMELINE, it)
        }
        PrefRow("Media previews", snapshot.mediaPreview) { store.setRaw(C.KEY_FEED_MEDIA_PREVIEW, if (it) "1" else "0") }
        PrefRow("Show reactions", snapshot.showReactions) { store.setRaw(C.KEY_FEED_SHOW_REACTIONS, if (it) "1" else "0") }
        PrefRow("Protocol notes", snapshot.showProtocolNotes) { store.setRaw(C.KEY_FEED_SHOW_PROTOCOL_NOTES, if (it) "1" else "0") }
    }
    Footnote("Protocol notes show raw kind events (reposts, reactions) in the timeline — web feedPreferences parity.")

    // ── Ranking (origin AlgorithmSettings parity) ─────────────────────
    DetailCard("Freshness") {
        OptionRow(
            "Recency half-life",
            listOf(
                1 to "Live · 1h",
                6 to "Balanced · 6h",
                24 to "Relaxed · 24h",
                72 to "Chill · 3d",
            ).map { (h, label) -> h.toString() to label },
            selected = space.bitos.core.feed.AlgorithmContract.FRESHNESS_STEPS_HOURS
                .firstOrNull { it == algo.freshnessHours }?.toString()
                ?: space.bitos.core.feed.AlgorithmContract.FRESHNESS_DEFAULT_HOURS.toString(),
        ) { algorithmStore.setFreshness(it.toInt()) }
    }
    Footnote("Freshness retunes the recency signal everywhere — older notes survive longer at higher steps.")

    DetailCard("Surfaces") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in space.bitos.core.feed.AlgorithmSurface.entries) {
                    val selected = s == surface
                    Text(
                        s.name.lowercase().replaceFirstChar { it.uppercase() },
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.W700 else FontWeight.W500,
                        color = if (selected) BitOSColors.primary else BitOSColors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (selected) BitOSColors.primaryContainer else BitOSColors.surfaceOverlay.copy(alpha = 0.5f))
                            .clickable { surface = s }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            PrefRow(
                "Ranked ${surface.name.lowercase()}",
                setting.enabled,
            ) { algorithmStore.setEnabled(surface, it) }
            if (!setting.enabled) {
                Footnote("Off = strict reverse-chronological — never hidden.")
            }
        }
    }

    if (setting.enabled) {
        DetailCard("Preset") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, label) in listOf(
                        space.bitos.core.feed.AlgorithmPresetId.LATEST to "Latest",
                        space.bitos.core.feed.AlgorithmPresetId.BALANCED to "Balanced",
                        space.bitos.core.feed.AlgorithmPresetId.TRENDING to "Trending",
                        space.bitos.core.feed.AlgorithmPresetId.TRUSTED to "Trusted",
                    )) {
                        val selected = id == preset
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.W700 else FontWeight.W500,
                            color = if (selected) BitOSColors.primary else BitOSColors.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(if (selected) BitOSColors.primaryContainer else BitOSColors.surfaceOverlay.copy(alpha = 0.5f))
                                .clickable { algorithmStore.setPreset(surface, id) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    if (preset == space.bitos.core.feed.AlgorithmPresetId.CUSTOM) {
                        Text(
                            "Custom",
                            fontSize = 13.sp, fontWeight = FontWeight.W700,
                            color = BitOSColors.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(BitOSColors.accent.copy(alpha = 0.15f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        DetailCard("Signals") {
            MixBar(setting)
            for (signal in space.bitos.core.feed.AlgorithmSignal.entries) {
                SignalRow(
                    signal = signal,
                    config = setting.signals[signal] ?: space.bitos.core.feed.SignalSetting(false, 0.0),
                    totalWeight = setting.signals.values.filter { it.enabled && it.weight > 0 }.sumOf { it.weight },
                    onChange = { enabled, weight -> algorithmStore.setSignal(surface, signal, enabled, weight) },
                )
            }
        }
        Footnote("Weights re-balance live — turning a signal off instantly re-normalizes the mix. Topics & Web-of-trust contribute once their data feeds land (W2).")
    }
}

@Composable
private fun MixBar(setting: space.bitos.core.feed.SurfaceSetting) {
    val active = setting.signals.entries.filter { it.value.enabled && it.value.weight > 0 }
    if (active.isEmpty()) return
    val total = active.sumOf { it.value.weight }
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .padding(horizontal = 12.dp, vertical = 0.dp)
            .clip(RoundedCornerShape(99.dp)),
    ) {
        for ((signal, config) in active) {
            Box(
                Modifier
                    .weight((config.weight / total).toFloat())
                    .fillMaxHeight()
                    .background(signalColor(signal)),
            )
        }
    }
}

private fun signalColor(signal: space.bitos.core.feed.AlgorithmSignal): Color = when (signal) {
    space.bitos.core.feed.AlgorithmSignal.RECENCY -> BitOSColors.primary
    space.bitos.core.feed.AlgorithmSignal.ENGAGEMENT -> BitOSColors.like
    space.bitos.core.feed.AlgorithmSignal.ZAPS -> BitOSColors.zap
    space.bitos.core.feed.AlgorithmSignal.AFFINITY -> BitOSColors.reply
    space.bitos.core.feed.AlgorithmSignal.TOPICS -> BitOSColors.accent
    space.bitos.core.feed.AlgorithmSignal.WOT -> BitOSColors.success
}

@Composable
private fun SignalRow(
    signal: space.bitos.core.feed.AlgorithmSignal,
    config: space.bitos.core.feed.SignalSetting,
    totalWeight: Double,
    onChange: (Boolean, Double) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(signalColor(signal)))
            Spacer(Modifier.width(8.dp))
            Text(
                signal.name.lowercase().replaceFirstChar { it.uppercase() },
                fontSize = 14.sp, color = if (config.enabled) BitOSColors.textPrimary else BitOSColors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (config.enabled && totalWeight > 0) "${(config.weight / totalWeight * 100).toInt()}%" else "—",
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textSecondary,
            )
            Switch(
                checked = config.enabled,
                onCheckedChange = { onChange(it, config.weight) },
                colors = SwitchDefaults.colors(checkedTrackColor = BitOSColors.primary),
            )
        }
        if (config.enabled) {
            androidx.compose.material3.Slider(
                value = config.weight.toFloat(),
                onValueChange = { onChange(true, it.toDouble()) },
                valueRange = 0.05f..1f,
                steps = 18,
            )
        }
    }
}

@Composable
private fun MediaDetail(
    snapshot: space.bitos.core.settings.SettingsSnapshot,
    store: SettingsStore,
) {
    val C = space.bitos.core.settings.SettingsContract
    DetailCard("Playback") {
        OptionRow("Autoplay", listOf("always" to "Always", "wifi" to "Wi-Fi Only", "never" to "Never"), snapshot.mediaAutoPlay.wire) {
            store.setRaw(C.KEY_MEDIA_AUTO_PLAY, it)
        }
        OptionRow("Video quality", listOf("auto" to "Auto", "high" to "High", "low" to "Low"), snapshot.videoQuality.wire) {
            store.setRaw(C.KEY_VIDEO_QUALITY, it)
        }
        OptionRow(
            "Playback rate",
            listOf("0.5" to "0.5×", "0.75" to "0.75×", "1" to "1×", "1.25" to "1.25×", "1.5" to "1.5×", "2" to "2×"),
            snapshot.videoPlaybackRate.wire,
        ) {
            store.setRaw(C.KEY_VIDEO_PLAYBACK_RATE, it)
        }
    }
    Footnote("Autoplay honors the network policy above; downloads stay hash-verified (Blossom).")
    DetailCard("Uploads") {
        InfoLine("Provider", "Blossom (default)")
    }
    Footnote("Hash-verified Blossom uploads before signing; S3/Cloudinary fallbacks arrive with the media wave.")
}

@Composable
private fun LanguageDetail(
    snapshot: space.bitos.core.settings.SettingsSnapshot,
    store: SettingsStore,
) {
    val C = space.bitos.core.settings.SettingsContract
    DetailCard("Language") {
        OptionRow("Language", listOf("en" to "English", "lo" to "ລາວ / Lao"), snapshot.language.wire) {
            store.setRaw(C.KEY_LANGUAGE, it)
        }
    }
    Footnote("Lao translation strings arrive with APP-024.")
    DetailCard("Region") {
        OptionRow("Date format", listOf("MDY" to "Month · Day · Year", "DMY" to "Day · Month · Year", "YMD" to "Year · Month · Day"), snapshot.dateFormat.wire) {
            store.setRaw(C.KEY_DATE_FORMAT, it)
        }
        InfoLine("Time zone", if (snapshot.timeZone == "auto") "Automatic" else snapshot.timeZone)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AccountDetail(
    identityViewModel: IdentityViewModel,
    store: SettingsStore,
    feedRepository: FeedRepository,
) {
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    val profileEditState by identityViewModel.profileEditState.collectAsStateWithLifecycle()
    val registered by identityViewModel.registeredAccounts.collectAsStateWithLifecycle()
    val activePubkey by identityViewModel.activeRegistryPubkey.collectAsStateWithLifecycle()
    val account = identity.account
    val clipboard = LocalClipboardManager.current
    var npubCopied by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    if (account == null) {
        Footnote("No account — create or import a key on the You tab.")
        return
    }
    if (showEdit) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showEdit = false }) {
            space.bitos.app.ui.profile.ProfileEditContent(
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
    DetailCard("Identity") {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PubkeyAvatar(pubkey = account.pubkeyHex, size = 48)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Your account", fontSize = 15.sp, fontWeight = FontWeight.W600, color = BitOSColors.textPrimary)
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(account.npub)); npubCopied = true
                }) {
                    Text(
                        if (npubCopied) "copied ✓" else store.shortNpub(account.npub),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.primary,
                    )
                }
            }
        }
        TextButton(onClick = { showEdit = true }, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text("Edit profile", color = BitOSColors.primary, fontWeight = FontWeight.W600)
        }
    }

    DetailCard("Accounts on this device") {
        if (registered.isEmpty()) {
            Text(
                "No saved accounts — create or import a key on the You tab.",
                fontSize = 13.sp, color = BitOSColors.textSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        } else {
            for (acct in registered) {
                val isActive = acct.pubkeyHex == (activePubkey ?: account?.pubkeyHex)
                var displayName by remember(acct.pubkeyHex) { mutableStateOf(acct.displayName ?: "") }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isActive) { identityViewModel.switchTo(acct.pubkeyHex) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PubkeyAvatar(pubkey = acct.pubkeyHex, size = 36)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            displayName.ifEmpty { "Account" },
                            fontSize = 14.sp, fontWeight = if (isActive) FontWeight.W700 else FontWeight.W500,
                            color = BitOSColors.textPrimary,
                        )
                        Text(
                            store.shortNpub(acct.npub),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textSecondary,
                        )
                    }
                    if (isActive) {
                        Icon(
                            androidx.compose.ui.res.painterResource(space.bitos.app.R.drawable.solar_bolt_linear),
                            contentDescription = "Active account",
                            tint = BitOSColors.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (confirmRemove == acct.pubkeyHex) {
                        TextButton(onClick = {
                            identityViewModel.removeRegisteredAccount(acct.pubkeyHex)
                            confirmRemove = null
                        }) { Text("Remove", color = BitOSColors.error, fontWeight = FontWeight.W600) }
                    } else {
                        TextButton(onClick = { confirmRemove = acct.pubkeyHex }) {
                            Text("Remove", color = BitOSColors.textSecondary)
                        }
                    }
                }
            }
            Footnote("Switching keeps every account sealed on this device — one tap back. Remove wipes that account's key (back it up first).")
        }
    }

    DetailCard("Storage") {
        InfoLine("Settings cache", store.cacheSizeLabel())
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        TextButton(onClick = {
            store.clearCache()
            scope.launch { feedRepository.clearEventCache() }
        }) {
            Text("Clear cache (keeps theme & language)", color = BitOSColors.error, fontWeight = FontWeight.W600)
        }
    }
}

@Composable
private fun AboutDetail(store: SettingsStore) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }
    val facts = space.bitos.core.settings.AppFacts

    // Brand card (legacy About hero parity).
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BitOSColors.surface.copy(alpha = 0.6f))
            .border(1.dp, BitOSColors.border.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HexIconTile(iconRes = space.bitos.app.R.drawable.solar_bolt_linear, tint = BitOSColors.primary, size = 56)
        Spacer(Modifier.height(10.dp))
        Text(facts.APP_NAME, fontSize = 20.sp, fontWeight = FontWeight.W800, color = BitOSColors.textPrimary)
        Text(
            "version $version · built on ${facts.BUILT_ON} · ${facts.LICENSE}",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textTertiary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            facts.TAGLINE,
            fontSize = 13.sp, color = BitOSColors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }

    DetailCard("Supported NIPs") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            for (row in facts.SUPPORTED_NIPS.chunked(5)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (nip in row) {
                        Text(
                            "NIP-${if (nip < 10) "0$nip" else "$nip"}",
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            color = BitOSColors.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(BitOSColors.surfaceOverlay.copy(alpha = 0.5f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
    Footnote("Only NIPs live in this client are listed — the list grows with each shipped wave.")

    DetailCard("Version") {
        InfoLine("Settings schema", "v${space.bitos.core.settings.SettingsContract.SCHEMA_VERSION}")
        InfoLine("App facts schema", "v${space.bitos.core.settings.AppFacts.SCHEMA_VERSION}")
        InfoLine("Contract keys", "${space.bitos.core.settings.SettingsContract.SECTIONS.size} sections")
    }
    Footnote("Notes are canonical signed events; this app is a projection of them.")
}

@Composable
private fun PendingDetail(sectionKey: String) {
    Footnote("Coming in a later wave.")
}

// ── Security (legacy SecurityPage parity: keys + danger zone) ─────────

@Composable
private fun SecurityDetail(identityViewModel: IdentityViewModel) {
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    val account = identity.account ?: run {
        Footnote("No account — create or import a key on the You tab.")
        return
    }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copiedToken by remember { mutableStateOf<String?>(null) }
    var nsec by remember { mutableStateOf<String?>(null) }
    var confirmReveal by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    DetailCard("Keys") {
        KeyRow("npub (public)", account.npub, "npub", copiedToken) {
            clipboard.setText(AnnotatedString(account.npub))
            copiedToken = "npub"
        }
        val secret = nsec
        if (secret != null) {
            KeyRow("nsec (secret)", secret, "nsec", copiedToken) {
                clipboard.setText(AnnotatedString(secret))
                copiedToken = "nsec"
            }
        } else if (confirmReveal) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "Anyone with your nsec controls your identity. Never share or screenshot it.",
                    fontSize = 12.sp, color = BitOSColors.textSecondary,
                )
                Row {
                    TextButton(onClick = {
                        confirmReveal = false
                        scope.launch { nsec = identityViewModel.revealNsec() }
                    }) { Text("Reveal", color = BitOSColors.error, fontWeight = FontWeight.W600) }
                    TextButton(onClick = { confirmReveal = false }) {
                        Text("Cancel", color = BitOSColors.textSecondary)
                    }
                }
            }
        } else {
            TextButton(onClick = { confirmReveal = true }, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text("Reveal secret key (nsec)", color = BitOSColors.error, fontWeight = FontWeight.W600)
            }
        }
    }
    Footnote("Back up your nsec somewhere safe — it is the only way to recover this account.")

    DetailCard("Danger zone") {
        if (confirmRemove) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    identityViewModel.removeAccount()
                    confirmRemove = false
                }) { Text("Remove key", color = BitOSColors.error, fontWeight = FontWeight.W600) }
                TextButton(onClick = { confirmRemove = false }) {
                    Text("Cancel", color = BitOSColors.textSecondary)
                }
            }
        } else {
            TextButton(onClick = { confirmRemove = true }, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text("Remove key from this device", color = BitOSColors.error, fontWeight = FontWeight.W600)
            }
        }
    }
    Footnote("Keeps every saved theme and feed preference — only the active key is removed.")
}

@Composable
private fun KeyRow(label: String, value: String, token: String, copiedToken: String?, onCopy: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.W600, color = BitOSColors.textPrimary)
        Text(
            value,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textSecondary,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (copiedToken == token) {
            Text("copied ✓", fontSize = 11.sp, color = BitOSColors.success)
        }
    }
}

// ── Lightning (legacy parity: default zap amount) ─────────────────────

@Composable
private fun LightningDetail(
    snapshot: space.bitos.core.settings.SettingsSnapshot,
    store: SettingsStore,
) {
    val C = space.bitos.core.settings.SettingsContract
    DetailCard("Zaps") {
        ZapAmountRow(snapshot.defaultZapAmount) { store.setRaw(C.KEY_DEFAULT_ZAP_AMOUNT, it.toString()) }
    }
    Footnote("Wallet pairing (LNURL/NWC) and the sats ledger arrive with the wallet wave (APP-014).")
}

// ── Privacy (live rows + honest pending gates) ────────────────────────

@Composable
private fun PrivacyDetail(
    snapshot: space.bitos.core.settings.SettingsSnapshot,
    store: SettingsStore,
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel,
    identityViewModel: IdentityViewModel,
    notePublisher: NotePublisher,
    relayManager: RelayManager,
    privacyPrefs: space.bitos.app.data.settings.PrivacyPrefsStore,
) {
    val privacy by privacyPrefs.state.collectAsStateWithLifecycle()
    val C = space.bitos.core.settings.SettingsContract
    val feedState by homeViewModel.state.collectAsStateWithLifecycle()
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    val publishState by notePublisher.state.collectAsStateWithLifecycle()
    val blocked = feedState.blocked

    DetailCard("Content") {
        OptionRow("Media auto-load", listOf("always" to "Always", "wifi" to "Wi-Fi Only", "never" to "Never"), snapshot.mediaAutoPlay.wire) {
            store.setRaw(C.KEY_MEDIA_AUTO_PLAY, it)
        }
        PrefRow("Protocol notes in feed", snapshot.showProtocolNotes) {
            store.setRaw(C.KEY_FEED_SHOW_PROTOCOL_NOTES, if (it) "1" else "0")
        }
        OptionRow(
            "Sensitive media",
            listOf("cover" to "Cover by default", "show" to "Show directly"),
            snapshot.sensitiveMedia.wire,
        ) { store.setRaw(C.KEY_SENSITIVE_MEDIA, it) }
    }
    Footnote("Cover keeps NIP-36 flagged notes behind a tap-to-reveal; Show renders them directly. Device-local choice.")

    DetailCard("Account privacy") {
        PrefRow("Private account", privacy.privateAccount) { enabled ->
            privacyPrefs.update { it.copy(privateAccount = enabled) }
        }
        PrefRow("Include client tag", privacy.includeClientTag) { enabled ->
            privacyPrefs.update { it.copy(includeClientTag = enabled) }
        }
        PrefRow("Activity visible to others", privacy.activityVisible) { enabled ->
            privacyPrefs.update { it.copy(activityVisible = enabled) }
        }
        PrefRow("Read receipts", privacy.readReceipts) { enabled ->
            privacyPrefs.update { it.copy(readReceipts = enabled) }
        }
        PrefRow("Show sensitive-content reason", privacy.sensitiveReason) { enabled ->
            privacyPrefs.update { it.copy(sensitiveReason = enabled) }
        }
        PrefRow("Allow story sharing", privacy.storyShare) { enabled ->
            privacyPrefs.update { it.copy(storyShare = enabled) }
        }
    }
    Footnote("Read receipts and story sharing take effect when DMs and stories ship (W2); the choices persist now — legacy parity.")

    DetailCard("Interactions") {
        OptionRow(
            "Who can message me",
            listOf("everyone" to "Everyone", "followers" to "Followers", "none" to "No one"),
            privacy.messagePermission.wire,
        ) { privacyPrefs.setMessagePermission(space.bitos.core.settings.MessagePermission.parse(it)) }
        OptionRow(
            "Who can comment",
            listOf("everyone" to "Everyone", "followers" to "Followers", "friends" to "Friends"),
            privacy.commentPermission.wire,
        ) { privacyPrefs.setCommentPermission(space.bitos.core.settings.CommentPermission.parse(it)) }
    }
    Footnote("Gates apply to incoming interactions (W2 DMs/comments enforcement; persisted now — legacy parity).")

    DetailCard("Blocked users") {
        if (blocked.isEmpty()) {
            Text(
                "No blocked authors on this account's kind-10004 list.",
                fontSize = 13.sp, color = BitOSColors.textSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        } else {
            for (pubkey in blocked.take(50)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PubkeyAvatar(pubkey = pubkey, size = 28)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        space.bitos.core.identity.NostrKeyCodec.npub(pubkey)
                            ?.let(store::shortNpub) ?: pubkey,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = BitOSColors.textSecondary, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        notePublisher.dismiss()
                        notePublisher.publishBlockList(
                            blocked = blocked.filterNot { it == pubkey },
                            signerProvider = { identityViewModel.createSigner() },
                            writeRelays = relayManager.writeRelays(),
                        )
                    }) { Text("Unblock", color = BitOSColors.primary, fontWeight = FontWeight.W600) }
                }
            }
            if (blocked.size > 50) {
                Footnote("Showing first 50 of ${blocked.size} blocked authors.")
            }
            PublishStatusLine(publishState)
            Footnote("Unblock publishes a new kind-10004 head to your write relays.")
        }
    }
    Footnote("Blocked authors are filtered from feeds and the inbox (NIP-51). DM/mention gates and read receipts arrive with the DM wave.")
}

// ── Relays manager (APP-018 §3.18: CRUD, roles, status dots, NIP-65) ──

@Composable
private fun RelaysDetail(
    feedRepository: FeedRepository,
    relayManager: RelayManager,
    identityViewModel: IdentityViewModel,
    notePublisher: NotePublisher,
) {
    val feedState by feedRepository.state.collectAsStateWithLifecycle()
    val health = feedState.relayHealth
    val entries by relayManager.entries.collectAsStateWithLifecycle()
    val connections by relayManager.connectionStates.collectAsStateWithLifecycle()
    val publishState by notePublisher.state.collectAsStateWithLifecycle()
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    var addInput by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    DetailCard("Status") {
        InfoLine("Connected", "${health.connected}/${health.total}")
    }
    Footnote("Edits connect and disconnect sockets immediately and persist on this device.")

    DetailCard("Configured relays") {
        for (entry in entries) {
            RelayRow(
                entry = entry,
                state = connections[entry.url],
                onRemove = { relayManager.remove(entry.url) },
                onToggleRead = { relayManager.setRoles(entry.url, !entry.read, entry.write) },
                onToggleWrite = { relayManager.setRoles(entry.url, entry.read, !entry.write) },
            )
        }
    }
    Footnote("Roles follow NIP-65: read relays serve your feeds; write relays receive your events. A relay needs at least one role.")

    DetailCard("Add relay") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = addInput,
                onValueChange = { addInput = it; addError = null },
                singleLine = true,
                isError = addError != null,
                placeholder = { Text("wss://relay.example.com", fontSize = 13.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            if (addError != null) {
                Text(addError!!, fontSize = 12.sp, color = BitOSColors.error)
            }
            Row {
                TextButton(
                    onClick = {
                        if (!relayManager.add(addInput)) {
                            addError = when {
                                entries.any { it.url.value == addInput.trim() } -> "Already in your relay set."
                                entries.size >= space.bitos.core.model.RelayListContract.MAX_RELAYS ->
                                    "Relay set is full (${space.bitos.core.model.RelayListContract.MAX_RELAYS})."
                                else -> "Enter a valid wss:// relay URL."
                            }
                        } else {
                            addInput = ""
                        }
                    },
                    enabled = addInput.isNotBlank(),
                ) {
                    Icon(
                        androidx.compose.ui.res.painterResource(space.bitos.app.R.drawable.solar_add_circle_linear),
                        contentDescription = null,
                        tint = BitOSColors.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Add relay", color = BitOSColors.primary, fontWeight = FontWeight.W600)
                }
            }
        }
    }

    DetailCard("Publish") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (identity.account == null) {
                Footnote("Publishing the relay list (kind 10002) needs an account — create or import a key on the You tab.")
            } else {
                TextButton(onClick = {
                    notePublisher.dismiss()
                    notePublisher.publishRelayList(
                        relayListJson = relayManager.encode(),
                        signerProvider = { identityViewModel.createSigner() },
                        writeRelays = relayManager.writeRelays(),
                    )
                }) {
                    Icon(
                        androidx.compose.ui.res.painterResource(space.bitos.app.R.drawable.solar_bolt_linear),
                        contentDescription = null,
                        tint = BitOSColors.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Publish relay list (NIP-65)", color = BitOSColors.primary, fontWeight = FontWeight.W600)
                }
                PublishStatusLine(publishState)
            }
        }
    }
    Footnote("The published kind-10002 event advertises your relays to other clients (NIP-65).")
}

@Composable
private fun RelayRow(
    entry: space.bitos.core.model.RelayEntry,
    state: RelayConnectionState?,
    onRemove: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleWrite: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(relayStateColor(state)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.url.value,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRemove) {
                Icon(
                    androidx.compose.ui.res.painterResource(space.bitos.app.R.drawable.solar_trash_linear),
                    contentDescription = "Remove ${entry.url.host()}",
                    tint = BitOSColors.error,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoleChip("read", entry.read, onToggleRead)
            RoleChip("write", entry.write, onToggleWrite)
        }
    }
}

@Composable
private fun relayStateColor(state: RelayConnectionState?): Color = when (state) {
    RelayConnectionState.CONNECTED -> BitOSColors.success
    RelayConnectionState.CONNECTING, RelayConnectionState.RETRYING -> BitOSColors.warning
    else -> BitOSColors.textTertiary
}

@Composable
private fun RoleChip(label: String, active: Boolean, onClick: () -> Unit) {
    val color = if (active) BitOSColors.primary else BitOSColors.textTertiary
    Text(
        label,
        fontSize = 10.sp, fontWeight = FontWeight.W600,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(color.copy(alpha = 0.15f))
            .clickable(onClickLabel = "Toggle $label role") { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun PublishStatusLine(publishState: space.bitos.app.data.publish.PublishUiState) {
    val text = when {
        publishState.result == null && publishState.inFlightId != null -> "Publishing…"
        publishState.result == space.bitos.app.data.publish.PublishResult.PUBLISHED -> "Published ✓"
        publishState.result == space.bitos.app.data.publish.PublishResult.REJECTED ->
            "Rejected: ${publishState.receipts.firstOrNull { it.accepted == false }?.message ?: "relay declined"}"
        publishState.result == space.bitos.app.data.publish.PublishResult.TIMEOUT -> "No relay receipt before timeout."
        publishState.result == space.bitos.app.data.publish.PublishResult.SIGNING_REFUSED -> "No account key available."
        publishState.result == space.bitos.app.data.publish.PublishResult.INVALID -> "Relay list invalid — nothing sent."
        else -> null
    } ?: return
    Text(
        text,
        fontSize = 12.sp,
        color = if (publishState.result == space.bitos.app.data.publish.PublishResult.PUBLISHED) BitOSColors.success else BitOSColors.textSecondary,
    )
}

// ── Help (legacy SupportSettings parity: FAQ + contribute + donate) ────

private val helpFaq: List<Pair<String, String>> =
    space.bitos.core.settings.AppFacts.FAQ.map { it.question to it.answer }

@Composable
private fun HelpDetail() {
    var expanded by remember { mutableStateOf<String?>(null) }

    DetailCard("Support the project") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                space.bitos.core.settings.AppFacts.CONTRIBUTE_NOTE,
                fontSize = 13.sp, color = BitOSColors.textSecondary,
            )
            if (space.bitos.core.settings.AppFacts.SUPPORT_LUD16.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Zap the team · " + space.bitos.core.settings.AppFacts.SUPPORT_LUD16,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textTertiary,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (tier in space.bitos.core.settings.AppFacts.SUPPORT_TIERS_SATS) {
                        Text(
                            "$tier ⚡",
                            fontSize = 13.sp, fontWeight = FontWeight.W600, color = BitOSColors.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(BitOSColors.primaryContainer)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
    Footnote("Support tiers activate when the project lightning address is configured.")

    DetailCard("FAQ") {
        for ((question, answer) in helpFaq) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = if (expanded == question) null else question }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(question, fontSize = 15.sp, fontWeight = FontWeight.W500, color = BitOSColors.textPrimary, modifier = Modifier.weight(1f))
                    Text(
                        if (expanded == question) "−" else "+",
                        fontSize = 16.sp, fontWeight = FontWeight.W700, color = BitOSColors.textTertiary,
                    )
                }
                if (expanded == question) {
                    Spacer(Modifier.height(6.dp))
                    Text(answer, fontSize = 13.sp, color = BitOSColors.textSecondary)
                }
            }
        }
    }

    DetailCard("Links") {
        LinkRow("Nostr Improvement Possibilities", space.bitos.core.settings.AppFacts.LINK_NIPS)
        LinkRow("What is Nostr?", space.bitos.core.settings.AppFacts.LINK_NOSTR)
        if (space.bitos.core.settings.AppFacts.LINK_SOURCE.isNotEmpty()) {
            LinkRow("Source code", space.bitos.core.settings.AppFacts.LINK_SOURCE)
        }
    }
}

@Composable
private fun LinkRow(label: String, url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                    )
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            androidx.compose.ui.res.painterResource(space.bitos.app.R.drawable.solar_widget_linear),
            contentDescription = null,
            tint = BitOSColors.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 15.sp, color = BitOSColors.textPrimary, modifier = Modifier.weight(1f))
        Text(
            url.removePrefix("https://").removePrefix("www."),
            fontSize = 11.sp, color = BitOSColors.textTertiary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

// ── Controls ───────────────────────────────────────────────────────────

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.W700,
        color = BitOSColors.textTertiary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    SectionCard { content() }
}

@Composable
private fun PrefRow(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = if (enabled) BitOSColors.textPrimary else BitOSColors.textTertiary)
        Switch(
            checked = value,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = BitOSColors.primary),
        )
    }
}

@Composable
private fun OptionRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(title, fontSize = 13.sp, color = BitOSColors.textSecondary)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((wire, label) in options) {
                val isSelected = wire == selected
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.W700 else FontWeight.W500,
                    color = if (isSelected) BitOSColors.primary else BitOSColors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(
                            if (isSelected) BitOSColors.primaryContainer
                            else BitOSColors.surfaceOverlay.copy(alpha = 0.5f),
                        )
                        .clickable { onPick(wire) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ZapAmountRow(amount: Int, onChange: (Int) -> Unit) {
    val presets = listOf(1, 5, 21, 100, 500, 1000)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Default zap: $amount sats", fontSize = 15.sp, color = BitOSColors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in presets) {
                val isSelected = preset == amount
                Text(
                    "$preset ⚡",
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.W700 else FontWeight.W500,
                    color = if (isSelected) BitOSColors.primary else BitOSColors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(
                            if (isSelected) BitOSColors.primaryContainer
                            else BitOSColors.surfaceOverlay.copy(alpha = 0.5f),
                        )
                        .clickable { onChange(preset) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // Fine stepper (legacy slider parity; shared rules hard-validate 1..100_000).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                onClick = { onChange((amount - 1).coerceAtLeast(space.bitos.core.settings.SettingsContract.ZAP_AMOUNT_MIN)) },
                enabled = amount > space.bitos.core.settings.SettingsContract.ZAP_AMOUNT_MIN,
            ) { Text("−", fontSize = 16.sp, fontWeight = FontWeight.W700) }
            Text("$amount sats", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textSecondary)
            TextButton(
                onClick = { onChange((amount + 1).coerceAtMost(space.bitos.core.settings.SettingsContract.ZAP_AMOUNT_MAX)) },
                enabled = amount < space.bitos.core.settings.SettingsContract.ZAP_AMOUNT_MAX,
            ) { Text("+", fontSize = 16.sp, fontWeight = FontWeight.W700) }
        }
    }
}

/** Legacy `accentColorOptions` palette (web/Flutter parity — same 15 colors as iOS). */
@Composable
private fun AccentPaletteRow(selectedHex: String, onPick: (String) -> Unit) {
    val palette = listOf(
        "#F7931A", "#8B5CF6", "#7C3AED", "#6366F1", "#3B82F6",
        "#0EA5E9", "#06B6D4", "#14B8A6", "#10B981", "#84CC16",
        "#F59E0B", "#EF4444", "#E11D48", "#EC4899", "#D946EF",
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        for (row in palette.chunked(5)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (hex in row) {
                    val color = Color(hex.removePrefix("#").toLong(16).toInt() or 0xFF000000.toInt())
                    val isSelected = hex == selectedHex
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                2.dp,
                                if (isSelected) BitOSColors.textPrimary else Color.Transparent,
                                CircleShape,
                            )
                            .clickable { onPick(hex) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Text("✓", fontSize = 13.sp, fontWeight = FontWeight.W700, color = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = BitOSColors.textPrimary)
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textSecondary)
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        fontSize = 12.sp, color = BitOSColors.textTertiary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}
