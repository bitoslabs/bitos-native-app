package space.bitos.app.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.bitos.app.identity.IdentityFeatureFlags
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.BitosTextField
import space.bitos.app.ui.components.StateBanner
import space.bitos.app.ui.components.StateBannerTone
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.core.settings.IdentityOnboardingContent

/**
 * Remote-signer setup steps (ID-006b / ID-007a): NIP-46 paste-a-bunker
 * and NIP-55 pick-an-app. Rendered only behind their feature flags — the
 * method cards stay "Soon" when a flag is off.
 */
@Composable
fun Nip46SetupStep(
    identityViewModel: IdentityViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val content = IdentityOnboardingContent
    var input by remember { mutableStateOf("") }
    val preview by identityViewModel.remoteSetup.collectAsState()
    val error by identityViewModel.remoteSetupError.collectAsState()
    val busy by identityViewModel.remoteSetupBusy.collectAsState()

    LaunchedEffect(preview) {
        if (preview != null && preview?.kind == space.bitos.core.identity.SignerKind.REMOTE_NIP46) {
            // Confirm state is rendered inline below; nothing auto-saves.
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(content.NIP46_SETUP_TITLE, fontSize = 24.sp, fontWeight = FontWeight.W800, color = BitOSColors.textPrimary)
        Text(content.NIP46_SETUP_BODY, fontSize = 14.sp, lineHeight = 20.sp, color = BitOSColors.textSecondary)
        val current = preview
        if (current == null) {
            BitosTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = content.NIP46_FIELD_LABEL,
                singleLine = true,
            )
            error?.let { StateBanner(tone = StateBannerTone.WARN, text = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text("Back", color = BitOSColors.textSecondary) }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { identityViewModel.startNip46Setup(input.trim()) },
                    enabled = !busy && input.isNotBlank(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(content.NIP46_CONNECT_LABEL, color = BitOSColors.primary, fontWeight = FontWeight.W700)
                }
            }
        } else {
            // The signer's own answer — confirm npub before saving.
            Text(content.NIP46_CONFIRM_TITLE, fontSize = 18.sp, fontWeight = FontWeight.W800, color = BitOSColors.textPrimary)
            Text(content.NIP46_CONFIRM_BODY, fontSize = 13.sp, color = BitOSColors.textSecondary)
            Text(
                current.npub,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = BitOSColors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
            current.nip46?.let { meta ->
                Text(
                    "Signer relays: " + meta.relays.joinToString(", "),
                    fontSize = 11.sp,
                    color = BitOSColors.textTertiary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = identityViewModel::cancelRemoteSetup) { Text("Back", color = BitOSColors.textSecondary) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    identityViewModel.confirmRemoteSetup()
                    onDone()
                }) { Text("Use this identity", color = BitOSColors.primary, fontWeight = FontWeight.W700) }
            }
        }
    }
}

/** NIP-55 pick-an-app step: discovery list → one-time get_public_key → confirm. */
@Composable
fun Nip55SetupStep(
    identityViewModel: IdentityViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val content = IdentityOnboardingContent
    val context = LocalContext.current
    val setup by identityViewModel.remoteSetup.collectAsState()
    val error by identityViewModel.remoteSetupError.collectAsState()

    var signers by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pendingPackage by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pkg = pendingPackage
        if (pkg != null) {
            val extras = buildMap {
                result.data?.extras?.keySet()?.forEach { key ->
                    (result.data?.extras?.get(key) as? String)?.let { put(key, it) }
                }
            }
            val parsed = space.bitos.app.identity.nip55.Nip55Contracts.parseResult(
                extras, result.resultCode == android.app.Activity.RESULT_OK,
                space.bitos.app.identity.nip55.Nip55Contracts.TYPE_GET_PUBLIC_KEY,
            )
            val pubkey = space.bitos.app.identity.nip55.Nip55Contracts.validateSetupResult(parsed, pkg)
            if (pubkey != null) {
                identityViewModel.prepareNip55Setup(pubkey, pkg)
            } else if (parsed is space.bitos.app.identity.nip55.Nip55Contracts.RawResult.Refused) {
                identityViewModel.cancelRemoteSetup()
            }
        }
        pendingPackage = null
    }

    LaunchedEffect(Unit) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        signers = context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                info.loadLabel(context.packageManager).toString().takeIf { it.isNotEmpty() }
                    ?.let { label -> label to info.activityInfo.packageName }
            }
            .distinctBy { it.second }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(content.NIP55_SETUP_TITLE, fontSize = 24.sp, fontWeight = FontWeight.W800, color = BitOSColors.textPrimary)
        Text(content.NIP55_SETUP_BODY, fontSize = 14.sp, lineHeight = 20.sp, color = BitOSColors.textSecondary)
        val current = setup
        if (current == null) {
            if (signers.isEmpty()) {
                StateBanner(
                    tone = StateBannerTone.INFO,
                    text = "No signer app found. Install one (e.g. Amber) and come back.",
                )
            }
            signers.forEach { (label, pkg) ->
                TextButton(onClick = {
                    val spec = space.bitos.app.identity.nip55.Nip55Contracts.getPublicKeySpec(pkg)
                    pendingPackage = pkg
                    launcher.launch(
                        Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
                            `package` = pkg
                            putExtra(space.bitos.app.identity.nip55.Nip55Contracts.EXTRA_TYPE, spec.type)
                            putExtra(space.bitos.app.identity.nip55.Nip55Contracts.EXTRA_ID, spec.requestId)
                        },
                    )
                }) { Text("$label · $pkg", fontSize = 13.sp, color = BitOSColors.primary) }
            }
            error?.let { StateBanner(tone = StateBannerTone.WARN, text = it) }
        } else {
            Text(content.NIP46_CONFIRM_TITLE, fontSize = 18.sp, fontWeight = FontWeight.W800, color = BitOSColors.textPrimary)
            Text(content.NIP46_CONFIRM_BODY, fontSize = 13.sp, color = BitOSColors.textSecondary)
            Text(
                current.npub,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = BitOSColors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
            current.nip55Package?.let {
                Text("Signer app: $it", fontSize = 11.sp, color = BitOSColors.textTertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = identityViewModel::cancelRemoteSetup) { Text("Back", color = BitOSColors.textSecondary) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    identityViewModel.confirmRemoteSetup()
                    onDone()
                }) { Text("Use this identity", color = BitOSColors.primary, fontWeight = FontWeight.W700) }
            }
        }
        TextButton(onClick = onBack) { Text("Back", color = BitOSColors.textSecondary) }
    }
}
