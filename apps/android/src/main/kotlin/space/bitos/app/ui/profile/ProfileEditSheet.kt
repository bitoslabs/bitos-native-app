package space.bitos.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing

/**
 * Profile editing sheet: bounded fields → signed kind-0 through the receipt
 * machine. The relay echo (verified, newer) updates the profile projection.
 */
@Composable
fun ProfileEditContent(
    initialName: String = "",
    initialDisplayName: String = "",
    initialAbout: String = "",
    initialNip05: String = "",
    initialLud16: String = "",
    initialPicture: String = "",
    initialBanner: String = "",
    initialWebsite: String = "",
    error: String? = null,
    busy: Boolean,
    onUploadImage: (suspend (target: String, bytes: ByteArray) -> String?)? = null,
    onPublish: (name: String, displayName: String, about: String, nip05: String, lud16: String, picture: String, banner: String, website: String) -> Unit,
    onClose: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var displayName by rememberSaveable { mutableStateOf(initialDisplayName) }
    var about by rememberSaveable { mutableStateOf(initialAbout) }
    var nip05 by rememberSaveable { mutableStateOf(initialNip05) }
    var lud16 by rememberSaveable { mutableStateOf(initialLud16) }
    var picture by rememberSaveable { mutableStateOf(initialPicture) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var banner by rememberSaveable { mutableStateOf(initialBanner) }
    var website by rememberSaveable { mutableStateOf(initialWebsite) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var uploadingTarget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var uploadError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    suspend fun handlePicked(target: String, bytes: ByteArray?) {
        if (bytes == null || onUploadImage == null) return
        uploadingTarget = target
        uploadError = null
        val url = onUploadImage(target, bytes)
        uploadingTarget = null
        if (url == null) {
            uploadError = "Upload failed — check your connection and try again."
        } else {
            when (target) {
                "avatar" -> picture = url
                "banner" -> banner = url
            }
        }
    }

    // Photo pickers (legacy image_picker parity; center-crop inside upload).
    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        val bytes = uri?.let { runCatching { context.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() } }.getOrNull() }
        scope.launch { handlePicked("avatar", bytes) }
    }
    val bannerPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        val bytes = uri?.let { runCatching { context.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() } }.getOrNull() }
        scope.launch { handlePicked("banner", bytes) }
    }

    @Composable
    fun FieldLabel(text: String) {
        Text(
            text.uppercase(),
            fontSize = 12.sp, fontWeight = FontWeight.W700,
            color = BitOSColors.textSecondary,
            modifier = Modifier.padding(bottom = 6.dp, top = 4.dp),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BitOSSpacing.screen)
            .padding(bottom = BitOSSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Edit profile", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            space.bitos.app.ui.components.SheetCloseIcon(onClose = onClose)
        }

        error?.let { err ->
            Surface(shape = RoundedCornerShape(12.dp), color = BitOSColors.surface) {
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.error,
                    modifier = Modifier.padding(BitOSSpacing.base),
                )
            }
        }

        // ── Web-form grid (settings/+page.svelte parity) ─────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.md), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Username")
                space.bitos.app.ui.components.BitosTextField(
                    value = name,
                    onValueChange = { if (it.length <= 64) name = it },
                    placeholder = "username",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("Display name")
                space.bitos.app.ui.components.BitosTextField(
                    value = displayName,
                    onValueChange = { if (it.length <= 64) displayName = it },
                    placeholder = "Your name",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        FieldLabel("Bio")
        space.bitos.app.ui.components.BitosTextField(
            value = about,
            onValueChange = { if (it.length <= 300) about = it },
            placeholder = "Tell the world about yourself…",
            singleLine = false,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("${'$'}{about.length} / 300 characters", fontSize = 11.sp, color = BitOSColors.textTertiary)
        uploadError?.let { Text(it, fontSize = 12.sp, color = BitOSColors.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.md), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                FieldLabel(if (uploadingTarget == "avatar") "Uploading…" else "Avatar")
                space.bitos.app.ui.components.BitosTextField(
                    value = picture,
                    onValueChange = { if (it.length <= 256) picture = it },
                    placeholder = "https://…",
                    singleLine = true,
                    trailingIcon = {
                        androidx.compose.material3.IconButton(
                            onClick = { avatarPicker.launch("image/*") },
                            enabled = onUploadImage != null && uploadingTarget == null,
                        ) {
                            if (uploadingTarget == "avatar") {
                                androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            } else {
                                androidx.compose.material3.Icon(
                                    androidx.compose.material.icons.Icons.Outlined.Upload,
                                    contentDescription = "Upload avatar",
                                    tint = BitOSColors.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.weight(1f)) {
                FieldLabel(if (uploadingTarget == "banner") "Uploading…" else "Banner")
                space.bitos.app.ui.components.BitosTextField(
                    value = banner,
                    onValueChange = { if (it.length <= 256) banner = it },
                    placeholder = "https://…",
                    singleLine = true,
                    trailingIcon = {
                        androidx.compose.material3.IconButton(
                            onClick = { bannerPicker.launch("image/*") },
                            enabled = onUploadImage != null && uploadingTarget == null,
                        ) {
                            if (uploadingTarget == "banner") {
                                androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            } else {
                                androidx.compose.material3.Icon(
                                    androidx.compose.material.icons.Icons.Outlined.Upload,
                                    contentDescription = "Upload banner",
                                    tint = BitOSColors.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.md), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Website")
                space.bitos.app.ui.components.BitosTextField(
                    value = website,
                    onValueChange = { if (it.length <= 128) website = it },
                    placeholder = "https://example.com",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("NIP-05")
                space.bitos.app.ui.components.BitosTextField(
                    value = nip05,
                    onValueChange = { if (it.length <= 64) nip05 = it },
                    placeholder = "name@example.com",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        FieldLabel("Lightning address")
        space.bitos.app.ui.components.BitosTextField(
            value = lud16,
            onValueChange = { if (it.length <= 64) lud16 = it },
            placeholder = "name@getalby.com",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "Publishes a signed profile event to your relays; the change appears once confirmed.",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textTertiary,
            modifier = Modifier.padding(top = 8.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm), modifier = Modifier.padding(top = 8.dp)) {
            Button(
                onClick = { onPublish(name, displayName, about, nip05, lud16, picture, banner, website) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(if (busy) "Saving…" else "Save changes") }
            OutlinedButton(onClick = onClose, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
    }
}
