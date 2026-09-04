package space.bitos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.core.feed.ExternalVideoPreview

/** Image-only provider card. Opening uses the parent's external-link gate. */
@Composable
fun ExternalVideoPreviewCard(preview: ExternalVideoPreview, onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(BitOSColors.surfaceElevated)
            .clickable(onClickLabel = "Open ${preview.providerName} video") { onOpen(preview.url) },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
            RemoteBitmapImage(preview.thumbnailUrl, Modifier.fillMaxWidth())
            Icon(AppIcons.Play, null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
        Text(preview.providerName, color = BitOSColors.textPrimary, modifier = Modifier.padding(12.dp, 8.dp))
    }
}
