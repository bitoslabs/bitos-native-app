package space.bitos.app.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.feed.FeedNote
import space.bitos.core.studio.MemeSoundTrending

/**
 * Trending sounds (APP-021 bootstrap / "use this sound" Wave D, plan
 * docs/product/use-this-sound-plan.md §5): counting `sound` tags over the
 * LIVE feed window IS the rank — no marketplace. Rows are deterministic
 * (shared `MemeSoundTrending.rank`, 3-day half-life); "Use in Studio"
 * re-attaches by URL through the Wave C editor path (hash-verified, no
 * re-upload).
 */
@Composable
fun TrendingSoundsScreen(
    notes: List<FeedNote>,
    onClose: () -> Unit,
    onUseSound: (MemeSoundTrending.Row) -> Unit,
) {
    val rows = androidx.compose.runtime.remember(notes) {
        val now = System.currentTimeMillis()
        val usages = notes.mapNotNull { note ->
            note.soundOf?.let { sound ->
                MemeSoundTrending.Usage(
                    eventId = note.id,
                    createdAtMs = note.createdAt,
                    url = sound.url,
                    sha256 = sound.sha256,
                    sourceEventId = sound.eventId,
                    authorPubkey = sound.pubkey,
                )
            }
        }
        MemeSoundTrending.rank(usages, nowMs = now)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .verticalScroll(rememberScrollState())
            .padding(BitOSSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Trending sounds",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) {
                Text("Done", color = BitOSColors.primary, fontWeight = FontWeight.W600)
            }
        }
        Text(
            "Most-borrowed ♪ in your feed — ranked with a 3-day half-life.",
            style = MaterialTheme.typography.bodySmall,
            color = BitOSColors.textSecondary,
        )
        if (rows.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
                    Text("No borrowed sounds yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                    Text(
                        "Open a video Bitz → Sound to borrow its audio, or publish a meme with a picked soundtrack — every borrow counts here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                    )
                }
            }
        }
        rows.forEach { row ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BitOSColors.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(BitOSSpacing.base),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BitOSColors.primaryContainer,
                    ) {
                        Icon(
                            AppIcons.MusicNote,
                            contentDescription = null,
                            tint = BitOSColors.primary,
                            modifier = Modifier.padding(10.dp).size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(BitOSSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text("Original sound", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W600)
                        Text(
                            "${row.uses} borrow${if (row.uses == 1) "" else "s"} · trending",
                            style = MaterialTheme.typography.labelSmall,
                            color = BitOSColors.textSecondary,
                        )
                    }
                    TextButton(
                        onClick = { onUseSound(row) },
                    ) {
                        Text("Use in Studio", color = BitOSColors.primary, fontWeight = FontWeight.W600)
                    }
                }
            }
        }
    }
}
