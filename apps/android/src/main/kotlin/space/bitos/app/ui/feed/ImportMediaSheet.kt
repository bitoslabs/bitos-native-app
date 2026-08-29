package space.bitos.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing

/** Picked media awaiting publish. */
data class PickedMedia(
    val bytes: ByteArray,
    val mimeType: String,
)

enum class MediaPublishPhase { PICK, UPLOADING, PUBLISHING, DONE }

data class MediaPublishUiState(
    val picked: PickedMedia? = null,
    val phase: MediaPublishPhase = MediaPublishPhase.PICK,
    val failure: String? = null,
)

/**
 * Media import → publish sheet (CAP-005 + PUB media path): gallery pick →
 * caption → hash-verified Blossom upload → kind-22 through the receipt
 * machine. Failures (oversized files, signer refusal, upload mismatch) are
 * surfaced, never swallowed.
 */
@Composable
fun ImportMediaContent(
    state: MediaPublishUiState,
    onPublish: (String) -> Unit,
    onPick: (android.net.Uri, android.content.ContentResolver) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var caption by remember { mutableStateOf("") }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { onPick(it, context.contentResolver) } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.screen)
            .padding(bottom = BitOSSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("New video", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            space.bitos.app.ui.components.SheetCloseIcon(onClose = onCancel)
        }

        state.failure?.let { failure ->
            Surface(shape = RoundedCornerShape(12.dp), color = BitOSColors.surface) {
                Text(
                    failure,
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.error,
                    modifier = Modifier.padding(BitOSSpacing.base),
                )
            }
        }

        when (state.phase) {
            MediaPublishPhase.PICK -> {
                if (state.picked == null) {
                    Button(onClick = { picker.launch("video/*") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Pick a video from your library")
                    }
                } else {
                    PickedSummary(state.picked!!)
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { if (it.length <= 2000) caption = it },
                        placeholder = { Text("Add a caption…") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                        OutlinedButton(onClick = { picker.launch("video/*") }) { Text("Change") }
                        Button(onClick = { onPublish(caption) }, enabled = caption.isNotBlank()) {
                            Text("Upload & publish")
                        }
                    }
                }
            }
            MediaPublishPhase.UPLOADING -> {
                Text("Uploading (hash-verified)…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                LinearProgressIndicator(color = BitOSColors.primary, modifier = Modifier.fillMaxWidth())
                state.picked?.let { PickedSummary(it) }
            }
            MediaPublishPhase.PUBLISHING -> {
                Text("Publishing to relays…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                LinearProgressIndicator(color = BitOSColors.primary, modifier = Modifier.fillMaxWidth())
            }
            MediaPublishPhase.DONE -> {
                Text(
                    "Published ✓ — your video appears in the feed once relays confirm.",
                    style = MaterialTheme.typography.titleMedium,
                    color = BitOSColors.success,
                    fontWeight = FontWeight.W600,
                )
                OutlinedButton(onClick = onCancel) { Text("Done") }
            }
        }
    }
}

@Composable
private fun PickedSummary(picked: PickedMedia) {
    Surface(shape = RoundedCornerShape(12.dp), color = BitOSColors.surface) {
        Column(Modifier.padding(BitOSSpacing.base)) {
            Text("${picked.mimeType} • ${picked.bytes.size / (1024 * 1024)}MB", style = MaterialTheme.typography.bodySmall)
        }
    }
}
