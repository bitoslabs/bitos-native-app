package space.bitos.app.ui.onboarding

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.identity.IdentityPreview
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.BrandWordmark
import space.bitos.app.ui.components.DerivedIdentityCard
import space.bitos.app.ui.components.HexAvatar
import space.bitos.app.ui.components.HexShape
import space.bitos.app.ui.components.SecretKeyField
import space.bitos.app.ui.components.StateBanner
import space.bitos.app.ui.components.StateBannerTone
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.core.identity.AccountIdentity
import space.bitos.core.identity.KeyImportForm
import space.bitos.core.identity.KeyImportVerdict
import space.bitos.core.settings.IdentityMethodOption
import space.bitos.core.settings.IdentityOnboardingContent
import space.bitos.core.settings.IdentityValueProp

/** `hasOnboarded` persistence (versioned key, device-local). */
object OnboardingPrefs {
    private const val PREFS = "bitos_onboarding"
    private const val KEY_DONE = "has_onboarded"

    fun hasOnboarded(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markOnboarded(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}

/**
 * Spec §4 launch flow (docs/ui/app-01-onboarding-identity.html): Welcome →
 * Add identity → Import/Backup gate → npub confirmation. Replaces the
 * APP-002 carousel; the identity transaction itself runs through
 * [IdentityViewModel] exactly like the You-tab and More-hub paths.
 */
private enum class OnboardingStep { WELCOME, METHOD, IMPORT, BACKUP, VERIFY, SUCCESS }

@Composable
fun OnboardingScreen(
    identityViewModel: IdentityViewModel,
    onDone: () -> Unit,
) {
    val content = IdentityOnboardingContent
    val state by identityViewModel.state.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    var importInput by rememberSaveable { mutableStateOf("") }
    var comingSoonNotice by remember { mutableStateOf<String?>(null) }

    // Confirmation closes the loop: once the preview is sealed into the
    // keychain the success screen finishes the flow.
    LaunchedEffect(state.preview, state.account) {
        if (state.preview == null && state.account != null &&
            (step == OnboardingStep.BACKUP || step == OnboardingStep.VERIFY)
        ) {
            step = OnboardingStep.SUCCESS
        }
    }
    // Safety: a backup/verify step without a pending preview has nothing to
    // show (process-death restore) — fall back to the method picker. The
    // reverse also advances the import flow: derivation runs off-main, so
    // the verify step reacts when the preview lands.
    LaunchedEffect(state.preview) {
        if (state.preview == null) {
            if (step == OnboardingStep.BACKUP) step = OnboardingStep.METHOD
            if (step == OnboardingStep.VERIFY) step = OnboardingStep.IMPORT
        } else if (step == OnboardingStep.IMPORT) {
            step = OnboardingStep.VERIFY
        }
    }
    LaunchedEffect(comingSoonNotice) {
        if (comingSoonNotice != null) {
            kotlinx.coroutines.delay(3500)
            comingSoonNotice = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BitOSColors.background),
    ) {
        when (step) {
            OnboardingStep.WELCOME -> WelcomeStep(
                onAddIdentity = { step = OnboardingStep.METHOD },
                onBrowse = onDone,
            )
            OnboardingStep.METHOD -> MethodStep(
                onBack = { step = OnboardingStep.WELCOME },
                onCreate = {
                    identityViewModel.createKeyPreview()
                    step = OnboardingStep.BACKUP
                },
                onImport = { step = OnboardingStep.IMPORT },
                onComingSoon = { comingSoonNotice = it },
            )
            OnboardingStep.IMPORT -> ImportStep(
                onBack = { step = OnboardingStep.METHOD },
                input = importInput,
                onInput = {
                    importInput = it
                    identityViewModel.clearImportError()
                },
                error = state.importError,
                onReview = {
                    identityViewModel.importNsecPreview(importInput)
                },
            )
            OnboardingStep.BACKUP -> BackupStep(
                onBack = { identityViewModel.cancelPreview(); step = OnboardingStep.METHOD },
                preview = state.preview,
                secretNsec = identityViewModel.previewNsec(),
                busy = state.busy,
                onConfirm = identityViewModel::confirmPreview,
            )
            OnboardingStep.VERIFY -> VerifyStep(
                onBack = { identityViewModel.cancelPreview(); step = OnboardingStep.IMPORT },
                preview = state.preview,
                busy = state.busy,
                onConfirm = identityViewModel::confirmPreview,
            )
            OnboardingStep.SUCCESS -> SuccessStep(
                account = state.account,
                onDone = onDone,
            )
        }

        comingSoonNotice?.let { notice ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp),
                containerColor = BitOSColors.surfaceOverlay,
                contentColor = BitOSColors.textPrimary,
            ) { Text(notice, fontSize = 12.sp) }
        }
    }
}

// ── 1. Welcome / value proposition ─────────────────────────────────────

@Composable
private fun WelcomeStep(onAddIdentity: () -> Unit, onBrowse: () -> Unit) {
    val content = IdentityOnboardingContent
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            // Brand hero: orange hex ring + ₿ on near-black.
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(HexShape())
                        .background(BitOSColors.primary),
                )
                Box(
                    Modifier
                        .size(90.dp)
                        .clip(HexShape())
                        .background(Color(0xFF14141D)),
                )
                Text("₿", color = BitOSColors.primary, fontSize = 40.sp, fontWeight = FontWeight.W800)
            }
            Spacer(Modifier.height(28.dp))
            // Official wordmark replaces the legacy text title — same brand
            // moment as the shell (theme-aware black/white art).
            BrandWordmark(height = 30.dp, contentDescription = content.APP_NAME)
            Spacer(Modifier.height(12.dp))
            Text(
                content.TAGLINE,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = BitOSColors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))
            content.VALUE_PROPS.forEach { prop ->
                ValuePropRow(prop)
                Spacer(Modifier.height(16.dp))
            }
        }
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp, top = 8.dp)) {
            PrimaryButton(content = "Add identity", onClick = onAddIdentity)
            Spacer(Modifier.height(10.dp))
            GhostButton(content = "Browse now", onClick = onBrowse)
            Spacer(Modifier.height(16.dp))
            Text(
                content.PRIVACY_FOOTNOTE,
                fontSize = 11.sp,
                color = BitOSColors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ValuePropRow(prop: IdentityValueProp) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HexPlate(icon = onboardingIcon(prop.iconToken), size = 44)
        Column {
            Text(prop.title, fontSize = 14.sp, fontWeight = FontWeight.W700, color = BitOSColors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(prop.body, fontSize = 12.sp, lineHeight = 16.sp, color = BitOSColors.textSecondary)
        }
    }
}

// ── 2. Identity method ─────────────────────────────────────────────────

@Composable
private fun MethodStep(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onComingSoon: (String) -> Unit,
) {
    val content = IdentityOnboardingContent
    Column(Modifier.fillMaxSize()) {
        OnboardingTopNav(title = "Add identity", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                content.METHOD_TITLE,
                fontSize = 24.sp,
                fontWeight = FontWeight.W800,
                color = BitOSColors.textPrimary,
            )
            Text(content.METHOD_BODY, fontSize = 14.sp, lineHeight = 20.sp, color = BitOSColors.textSecondary)
            Spacer(Modifier.height(6.dp))
            content.METHODS.forEach { method ->
                MethodCard(method) {
                    when (method.id) {
                        "create" -> onCreate()
                        "import" -> onImport()
                        "nip46" -> onComingSoon(content.NIP46_COMING_SOON)
                        "nip55" -> onComingSoon(content.NIP55_COMING_SOON)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            StateBanner(tone = StateBannerTone.INFO, text = content.METHOD_INFO)
        }
    }
}

@Composable
private fun MethodCard(method: IdentityMethodOption, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BitOSColors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = method.title) { onClick() },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            HexPlate(icon = onboardingIcon(method.iconToken), size = 48, dimmed = !method.available)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        method.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W700,
                        color = if (method.available) BitOSColors.textPrimary else BitOSColors.textSecondary,
                    )
                    when {
                        method.recommended -> AssistChip("Recommended")
                        !method.available -> AssistChip("Soon")
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    method.subtitle,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = BitOSColors.textSecondary,
                )
            }
            if (method.available) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = BitOSColors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ── 3. Import secret key ───────────────────────────────────────────────

@Composable
private fun ImportStep(
    onBack: () -> Unit,
    input: String,
    onInput: (String) -> Unit,
    error: String?,
    onReview: () -> Unit,
) {
    val content = IdentityOnboardingContent
    val check = remember(input) { KeyImportForm.check(input) }
    val ready = check.verdict == KeyImportVerdict.READY
    Column(Modifier.fillMaxSize()) {
        OnboardingTopNav(title = "Import secret key", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FieldLabel(content.IMPORT_FIELD_LABEL)
            SecretKeyField(
                value = input,
                onValueChange = onInput,
                error = error,
                onSubmit = onReview,
            )
            if (ready) {
                DerivedIdentityCard(check)
            }
        }
        Column(Modifier.padding(20.dp)) {
            PrimaryButton(content = "Review key", enabled = ready, onClick = onReview)
            Spacer(Modifier.height(10.dp))
            Text(
                content.IMPORT_REVIEW_NOTE,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = BitOSColors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── 4. Backup gate (freshly generated key) ─────────────────────────────

@Composable
private fun BackupStep(
    onBack: () -> Unit,
    preview: IdentityPreview?,
    secretNsec: String?,
    busy: Boolean,
    onConfirm: () -> Unit,
) {
    val content = IdentityOnboardingContent
    var revealed by remember { mutableStateOf(false) }
    var savedAcknowledged by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        OnboardingTopNav(title = "Back up your key", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StateBanner(tone = StateBannerTone.WARN, text = content.BACKUP_WARNING)
            Column {
                FieldLabel(content.BACKUP_FIELD_LABEL)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BitOSColors.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (!revealed || secretNsec == null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClickLabel = content.BACKUP_REVEAL_PROMPT) { revealed = true }
                                .padding(vertical = 30.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = BitOSColors.textSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                content.BACKUP_REVEAL_PROMPT,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.W600,
                                color = BitOSColors.textSecondary,
                            )
                        }
                    } else {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // CB-4: the secret crosses to the view only inside
                            // this reveal transaction; never logged or persisted.
                            Text(
                                secretNsec,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFFA628),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostPillButton(
                                    label = "Copy key",
                                    onClick = { clipboard.setText(AnnotatedString(secretNsec)) },
                                )
                                GhostPillButton(
                                    label = "Hide",
                                    onClick = { revealed = false },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    content.BACKUP_ADVICE,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = BitOSColors.textTertiary,
                )
            }
            Column {
                FieldLabel(content.BACKUP_NEXT_STEPS_TITLE)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BitOSColors.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        content.BACKUP_NEXT_STEPS.forEachIndexed { index, stepText ->
                            if (index > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp)
                                        .height(1.dp)
                                        .background(BitOSColors.divider),
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(30.dp)
                                        .clip(HexShape())
                                        .background(BitOSColors.primary.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "${index + 1}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.W700,
                                        color = BitOSColors.primary,
                                    )
                                }
                                Text(stepText, fontSize = 12.sp, lineHeight = 16.sp, color = BitOSColors.textSecondary)
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = content.BACKUP_ACK_LABEL) { savedAcknowledged = !savedAcknowledged }
                    .padding(bottom = 12.dp),
            ) {
                Checkbox(
                    checked = savedAcknowledged,
                    onCheckedChange = { savedAcknowledged = it },
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    content.BACKUP_ACK_LABEL,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = BitOSColors.textSecondary,
                )
            }
            PrimaryButton(
                content = content.BACKUP_CONFIRM_LABEL,
                enabled = savedAcknowledged && !busy && preview != null,
                onClick = onConfirm,
            )
        }
    }
}

// ── 5a. Verification (imported key) ────────────────────────────────────

@Composable
private fun VerifyStep(
    onBack: () -> Unit,
    preview: IdentityPreview?,
    busy: Boolean,
    onConfirm: () -> Unit,
) {
    val content = IdentityOnboardingContent
    Column(Modifier.fillMaxSize()) {
        OnboardingTopNav(title = content.VERIFY_TITLE, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(content.VERIFY_BODY, fontSize = 14.sp, lineHeight = 20.sp, color = BitOSColors.textSecondary)
            NpubCard(npub = preview?.npub)
        }
        Column(Modifier.padding(20.dp)) {
            PrimaryButton(
                content = content.VERIFY_CONFIRM_LABEL,
                enabled = !busy && preview != null,
                onClick = onConfirm,
            )
        }
    }
}

// ── 5b. Identity ready ─────────────────────────────────────────────────

@Composable
private fun SuccessStep(account: AccountIdentity?, onDone: () -> Unit) {
    val content = IdentityOnboardingContent
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(64.dp))
            Box(
                Modifier
                    .size(80.dp)
                    .clip(HexShape())
                    .background(BitOSColors.success.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = BitOSColors.success,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                content.SUCCESS_TITLE,
                fontSize = 24.sp,
                fontWeight = FontWeight.W800,
                color = BitOSColors.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                content.SUCCESS_BODY,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = BitOSColors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(BitOSColors.surface, RoundedCornerShape(14.dp))
                    .border(1.dp, BitOSColors.border, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel("Public key (npub)")
                    NpubRow(npub = account?.npub)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (account != null) {
                        HexAvatar(pubkey = account.pubkeyHex, size = 44)
                    }
                    Text(
                        content.SUCCESS_PROFILE_NOTE,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = BitOSColors.textTertiary,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            StateBanner(tone = StateBannerTone.INFO, text = content.SUCCESS_INFO)
        }
        Column(Modifier.padding(top = 8.dp, bottom = 28.dp)) {
            PrimaryButton(content = content.SUCCESS_DONE_LABEL, onClick = onDone)
        }
    }
}

// ── Shared flow atoms ──────────────────────────────────────────────────

@Composable
private fun OnboardingTopNav(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = BitOSColors.textPrimary,
            )
        }
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.W700,
            color = BitOSColors.textPrimary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = 0.8.sp,
        color = BitOSColors.textTertiary,
    )
}

@Composable
private fun HexPlate(icon: ImageVector, size: Int, dimmed: Boolean = false) {
    Box(
        Modifier
            .size(size.dp)
            .clip(HexShape())
            .background(BitOSColors.primary.copy(alpha = if (dimmed) 0.08f else 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (dimmed) BitOSColors.textTertiary else BitOSColors.primary,
            modifier = Modifier.size((size * 0.45f).dp),
        )
    }
}

@Composable
private fun AssistChip(label: String) {
    Box(
        Modifier
            .background(BitOSColors.primary.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .border(1.dp, BitOSColors.primary.copy(alpha = 0.4f), RoundedCornerShape(999.dp)),
    ) {
        Text(
            label,
            color = BitOSColors.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.W700,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PrimaryButton(content: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BitOSColors.primary,
            contentColor = Color(0xFF1A1000),
            disabledContainerColor = BitOSColors.primary.copy(alpha = 0.45f),
            disabledContentColor = Color(0xFF1A1000),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text(content, fontSize = 14.sp, fontWeight = FontWeight.W800)
    }
}

@Composable
private fun GhostButton(content: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = BitOSColors.textPrimary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text(content, fontSize = 14.sp, fontWeight = FontWeight.W700)
    }
}

@Composable
private fun RowScope.GhostPillButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = BitOSColors.textPrimary),
        modifier = Modifier.weight(1f),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.W600)
    }
}

/** npub display + copy row used by verification and success. */
@Composable
private fun NpubRow(npub: String?) {
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            npub?.let { space.bitos.app.ui.components.middleEllipsize(it) } ?: "—",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = BitOSColors.textPrimary,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .background(BitOSColors.surfaceElevated, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Box(
            Modifier
                .background(BitOSColors.primary.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                .border(1.dp, BitOSColors.primary.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                .clickable(enabled = npub != null, onClickLabel = "Copy npub") {
                    clipboard.setText(AnnotatedString(npub!!))
                },
        ) {
            Text(
                "Copy",
                color = BitOSColors.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.W700,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun NpubCard(npub: String?) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BitOSColors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Public key (npub)")
            NpubRow(npub)
        }
    }
}

/** Semantic icon tokens from the shared content contract. */
private fun onboardingIcon(token: String): ImageVector = when (token) {
    "key" -> Icons.Rounded.Key
    "zap" -> Icons.Rounded.Bolt
    "video" -> Icons.Rounded.OndemandVideo
    "sparkle" -> Icons.Rounded.AutoAwesome
    "download" -> Icons.Rounded.Download
    "link" -> Icons.Rounded.Link
    "android" -> Icons.Rounded.Android
    else -> Icons.Rounded.Key
}
