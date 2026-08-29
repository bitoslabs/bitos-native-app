package space.bitos.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.bitos.app.ui.components.AppBottomSheetMenu
import space.bitos.app.ui.components.AppMenuEntry
import space.bitos.app.ui.components.AppMenuItem
import space.bitos.app.ui.components.HexShape
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing

/**
 * Profile editing sheet: bounded fields → signed kind-0 through the receipt
 * machine. The relay echo (verified, newer) updates the profile projection.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    pubkey: String = "",
    /** Page mode hides the sheet header (back lives in the page top bar). */
    showHeader: Boolean = true,
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
    // Legacy step flow: pick target → source sheet (camera/library).
    var sourceTarget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var cameraTarget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

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

    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        val target = cameraTarget
        cameraTarget = null
        if (bitmap != null && target != null) {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, stream)
            scope.launch { handlePicked(target, stream.toByteArray()) }
        }
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
        if (showHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Edit profile", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                space.bitos.app.ui.components.SheetCloseIcon(onClose = onClose)
            }
        }

        // Legacy `_chooseImageSource` parity: camera or library step.
        sourceTarget?.let { target ->
            AppBottomSheetMenu(
                onDismissRequest = { sourceTarget = null },
                title = "Choose photo",
                entries = listOf(
                    AppMenuEntry.Item(AppMenuItem("camera", "Take photo", AppIcons.Camera)),
                    AppMenuEntry.Item(AppMenuItem("library", "Choose from library", AppIcons.Photo)),
                ),
                onSelect = { id ->
                    sourceTarget = null
                    if (id == "camera") {
                        cameraTarget = target
                        cameraLauncher.launch(null)
                    } else if (target == "avatar") {
                        avatarPicker.launch("image/*")
                    } else {
                        bannerPicker.launch("image/*")
                    }
                },
            )
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

        // ── Live header preview (legacy _ProfileHeaderPreview parity) ──
        EditHeaderPreview(
            pubkey = pubkey,
            name = name,
            displayName = displayName,
            nip05 = nip05,
            picture = picture,
            banner = banner,
            uploadingTarget = uploadingTarget,
            onPickAvatar = { sourceTarget = "avatar" },
            onPickBanner = { sourceTarget = "banner" },
        )

        // ── Legacy form card: single-column full-width compact fields. ──
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            FieldLabel("Username")
            space.bitos.app.ui.components.BitosTextField(
                value = name,
                onValueChange = { if (it.length <= 64) name = it },
                placeholder = "username",
                singleLine = true,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(Modifier.fillMaxWidth()) {
            FieldLabel("Display name")
            space.bitos.app.ui.components.BitosTextField(
                value = displayName,
                onValueChange = { if (it.length <= 64) displayName = it },
                placeholder = "Your name",
                singleLine = true,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(Modifier.fillMaxWidth()) {
            FieldLabel("Bio")
            space.bitos.app.ui.components.BitosTextField(
                value = about,
                onValueChange = { if (it.length <= 300) about = it },
                placeholder = "Tell the world about yourself…",
                singleLine = false,
                minLines = 3,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${'$'}{about.length} / 300 characters", fontSize = 11.sp, color = BitOSColors.textTertiary, modifier = Modifier.padding(top = 4.dp))
        }
        uploadError?.let { Text(it, fontSize = 12.sp, color = BitOSColors.error) }

        Column(Modifier.fillMaxWidth()) {
            FieldLabel(if (uploadingTarget == "avatar") "Uploading… (avatar URL)" else "Avatar picture URL")
            space.bitos.app.ui.components.BitosTextField(
                value = picture,
                onValueChange = { if (it.length <= 256) picture = it },
                placeholder = "https://…",
                singleLine = true,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(Modifier.fillMaxWidth()) {
            FieldLabel(if (uploadingTarget == "banner") "Uploading… (banner URL)" else "Banner picture URL")
            space.bitos.app.ui.components.BitosTextField(
                value = banner,
                onValueChange = { if (it.length <= 256) banner = it },
                placeholder = "https://…",
                singleLine = true,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(Modifier.fillMaxWidth()) {
            FieldLabel("Website")
            space.bitos.app.ui.components.BitosTextField(
                value = website,
                onValueChange = { if (it.length <= 128) website = it },
                placeholder = "https://example.com",
                singleLine = true,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(Modifier.fillMaxWidth()) {
            FieldLabel("NIP-05")
            space.bitos.app.ui.components.BitosTextField(
                value = nip05,
                onValueChange = { if (it.length <= 64) nip05 = it },
                placeholder = "name@example.com",
                singleLine = true,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(Modifier.fillMaxWidth()) {
            FieldLabel("Lightning address")
            space.bitos.app.ui.components.BitosTextField(
                value = lud16,
                onValueChange = { if (it.length <= 64) lud16 = it },
                placeholder = "name@getalby.com",
                singleLine = true,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            "Publishes a signed profile event to your relays; the change appears once confirmed.",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textTertiary,
            modifier = Modifier.padding(top = 8.dp),
        )

        // Legacy save: full-width pill with spinner; the page back cancels.
        Button(
            onClick = { onPublish(name, displayName, about, nip05, lud16, picture, banner, website) },
            enabled = !busy && uploadingTarget == null,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(48.dp),
        ) {
            if (busy) {
                androidx.compose.material3.CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(if (busy) "Saving…" else "Save changes", fontSize = 15.sp, fontWeight = FontWeight.W700)
        }
    }
}

/// Live preview of the profile hero exactly as it will render once saved
/// (legacy Flutter `_ProfileHeaderPreview` parity): banner 120 with the
/// "Change banner" pill, hex avatar 88 lifted -36 with a camera chip, and
/// the identity row (name + verified + @handle).
@Composable
private fun EditHeaderPreview(
    pubkey: String,
    name: String,
    displayName: String,
    nip05: String,
    picture: String,
    banner: String,
    uploadingTarget: String?,
    onPickAvatar: () -> Unit,
    onPickBanner: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        // Banner preview (or brand placeholder).
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BitOSColors.primary.copy(alpha = 0.15f)),
        ) {
            if (banner.isNotBlank()) {
                coil.compose.AsyncImage(
                    model = banner,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = BitOSColors.primary.copy(alpha = 0.40f),
                    modifier = Modifier.size(40.dp).align(Alignment.Center),
                )
            }
            if (uploadingTarget == "banner") {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    Text("Uploading", fontSize = 11.sp, fontWeight = FontWeight.W600, color = Color.White)
                }
            }
            // "Change banner" pill — bottom-right, black/60 glass.
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.60f))
                    .clickable(onClickLabel = "Change banner photo") { onPickBanner() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(AppIcons.Camera, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text("Change banner", fontSize = 11.sp, fontWeight = FontWeight.W600, color = Color.White)
            }
        }
        // Avatar + camera chip: lift exactly half the avatar (88/2) so the
        // hexagon's center sits ON the banner bottom edge (banner top pad 4
        // + 120 = 124; avatar top = 124 - 44 = 80) — the hero center line.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (124 - 44).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .shadow(8.dp, HexShape())
                    .clickable(onClickLabel = "Change profile picture") { onPickAvatar() },
                contentAlignment = Alignment.BottomCenter,
            ) {
                PubkeyAvatar(
                    pubkey = pubkey,
                    size = 88,
                    pictureUrl = picture.ifBlank { null },
                    label = displayName.ifBlank { name },
                )
                // Camera chip — bottom-left of center, clear of the ⚡ slot.
                Box(
                    Modifier
                        .offset(x = (-14).dp, y = 6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(BitOSColors.primary)
                        .border(2.dp, BitOSColors.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (uploadingTarget == "avatar") {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = Color.White)
                    } else {
                        Icon(AppIcons.Camera, contentDescription = "Change profile picture", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
            // Identity preview row.
            Column(
                Modifier.padding(top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayName.ifBlank { name.ifBlank { "Anonymous" } },
                        fontSize = 20.sp, fontWeight = FontWeight.W800, color = BitOSColors.textPrimary,
                    )
                    if (nip05.isNotBlank()) {
                        androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = "Verified",
                            tint = BitOSColors.accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                if (name.isNotBlank()) {
                    Text("@$name", fontSize = 12.sp, fontWeight = FontWeight.W600, color = BitOSColors.primary)
                }
            }
        }
    }
}
