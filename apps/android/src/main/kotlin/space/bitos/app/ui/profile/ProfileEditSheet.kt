package space.bitos.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
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
    error: String? = null,
    busy: Boolean,
    onPublish: (name: String, displayName: String, about: String, nip05: String, lud16: String) -> Unit,
    onClose: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var displayName by rememberSaveable { mutableStateOf(initialDisplayName) }
    var about by rememberSaveable { mutableStateOf(initialAbout) }
    var nip05 by rememberSaveable { mutableStateOf(initialNip05) }
    var lud16 by rememberSaveable { mutableStateOf(initialLud16) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BitOSSpacing.screen)
            .padding(bottom = BitOSSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Edit profile", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Close") }
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

        OutlinedTextField(
            value = name, onValueChange = { if (it.length <= 64) name = it },
            label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = displayName, onValueChange = { if (it.length <= 64) displayName = it },
            label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = about, onValueChange = { if (it.length <= 512) about = it },
            label = { Text("About") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nip05, onValueChange = { if (it.length <= 64) nip05 = it },
            label = { Text("NIP-05 (user@domain)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = lud16, onValueChange = { if (it.length <= 64) lud16 = it },
            label = { Text("Lightning address (user@domain)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "Publishes a signed profile event to your relays; the change appears once confirmed.",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textTertiary,
        )

        Button(
            onClick = { onPublish(name, displayName, about, nip05, lud16) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Publishing…" else "Publish profile") }
    }
}
