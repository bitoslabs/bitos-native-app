package space.bitos.app.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing

private data class CreateAction(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val description: String,
)

private val quickActions = listOf(
    CreateAction(Icons.Outlined.CameraAlt, "Record Bitz", "Camera capture", "Record a portrait clip with segment control"),
    CreateAction(Icons.Outlined.PhotoLibrary, "Import media", "Photos and files", "Copy assets into the project catalog"),
    CreateAction(Icons.Outlined.Flip, "Quick MEM", "Fast meme path", "Caption, look and stickers in seconds"),
    CreateAction(Icons.Outlined.LibraryMusic, "Use a sound", "Sound library", "Start a project from a licensed sound"),
    CreateAction(Icons.Outlined.Star, "Remix", "Provenance-aware", "Build on a template with attribution"),
)

/**
 * Create surface: fast paths into capture and the Studio. Camera, import
 * and editors are native-only features (CAP/EDT epics) and arrive with the
 * capture phase; the chooser is the stable entry contract.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(mediaPublishViewModel: space.bitos.app.ui.feed.MediaPublishViewModel) {
    var showCamera by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showImport by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val mediaState by mediaPublishViewModel.state.collectAsStateWithLifecycle()

    if (showCamera) {
        CameraScreen(
            onCaptured = { bytes, mime -> mediaPublishViewModel.mediaCaptured(bytes, mime); showCamera = false },
            onCancel = { showCamera = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .verticalScroll(rememberScrollState())
            .padding(BitOSSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        Text("Create", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(BitOSSpacing.xs))
        quickActions.forEachIndexed { index, action ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        when (index) {
                            0 -> Modifier.clickable(onClickLabel = action.title) { showCamera = true }
                            1 -> Modifier.clickable(onClickLabel = action.title) { showImport = true }
                            else -> Modifier
                        },
                    )
                    .semantics { contentDescription = "${action.title}, ${action.subtitle}" },
            ) {
                Row(
                    modifier = Modifier.padding(BitOSSpacing.base),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(12.dp), color = BitOSColors.primaryContainer) {
                        Icon(
                            action.icon,
                            contentDescription = null,
                            tint = BitOSColors.primary,
                            modifier = Modifier.padding(10.dp).size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(BitOSSpacing.md))
                    Column {
                        Text(action.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                        Text(action.description, style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = BitOSColors.surfaceElevated,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                Text("Project library", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                Text(
                    "Drafts live on-device by default. The library lists projects, storage usage and recovery state once the editor phase lands.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textSecondary,
                )
            }
        }
    }

    if (showImport) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { mediaPublishViewModel.cancel(); showImport = false }) {
            space.bitos.app.ui.feed.ImportMediaContent(
                state = mediaState,
                onPublish = mediaPublishViewModel::publish,
                onPick = { uri, resolver -> mediaPublishViewModel.mediaPicked(uri) },
                onCancel = { mediaPublishViewModel.cancel(); showImport = false },
            )
        }
    }
}
