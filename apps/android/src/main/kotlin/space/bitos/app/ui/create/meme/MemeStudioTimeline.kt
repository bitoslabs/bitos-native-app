package space.bitos.app.ui.create.meme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.studio.MemeProjectContract
import space.bitos.core.studio.MemeVideoCutRules
import java.util.Locale

/**
 * Unified scrubbable video timeline dock (CapCut / Reels parity).
 *
 * Isolates [positionMsProvider] reads inside [TimelineTransportRow] and
 * [TimelinePlayhead] so playback ticks never recompose the editor root
 * or stage.
 */
@Composable
internal fun TimelineStrip(
    clips: List<SessionClip>,
    selectedClipIndex: Int,
    positionMsProvider: () -> Long,
    totalMs: Long,
    remainingMs: Long,
    profile: MemeVideoCutRules.PublishProfile,
    onProfile: (MemeVideoCutRules.PublishProfile) -> Unit,
    onSelectClip: (Int) -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onMute: () -> Unit,
    onSpeed: () -> Unit,
    onLayer: () -> Unit,
    transport: VideoTransport,
    onSetCover: (Long) -> Unit,
) {
    val safeTotal = maxOf(1L, totalMs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.base),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
    ) {
        TimelineTransportRow(
            positionMsProvider = positionMsProvider,
            safeTotal = safeTotal,
            remainingMs = remainingMs,
            clipCount = clips.size,
            selectedClipIndex = selectedClipIndex,
            transport = transport,
            onSetCover = onSetCover,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
        ) {
            rowItems(MemeVideoCutRules.PublishProfile.entries.toList()) { option ->
                val selected = option == profile
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) BitOSColors.primary.copy(alpha = 0.16f)
                            else BitOSColors.surfaceElevated.copy(alpha = 0.55f),
                        )
                        .border(
                            1.dp,
                            if (selected) BitOSColors.primary else BitOSColors.border.copy(alpha = 0.45f),
                            RoundedCornerShape(50),
                        )
                        .clickable { onProfile(option) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) BitOSColors.primary else BitOSColors.textSecondary,
                    )
                }
            }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BitOSColors.surfaceElevated.copy(alpha = 0.65f))
                .border(1.dp, BitOSColors.border.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .pointerInput(safeTotal) {
                    detectTapGestures { tap ->
                        val fraction = (tap.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        transport.seekTo((fraction * safeTotal).toLong())
                    }
                }
                .pointerInput(safeTotal) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        transport.seekTo((fraction * safeTotal).toLong())
                    }
                },
        ) {
            val trackWidth = constraints.maxWidth.toFloat()
            Row(
                Modifier
                    .fillMaxHeight()
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                clips.forEachIndexed { index, clip ->
                    key(clip.id) {
                        TimelineClipSegment(
                            index = index,
                            widthPx = trackWidth * clipOutputMsOf(clip) / safeTotal,
                            outSec = ((clipOutputMsOf(clip) + 999) / 1000).toInt(),
                            speed = MemeProjectContract.clampSpeed(clip.speed),
                            muted = clip.volume == 0f,
                            selected = index == selectedClipIndex,
                            label = if (clip.id.startsWith("blank")) "canvas" else "vdo ${index + 1}",
                            onSelect = { onSelectClip(index) },
                        )
                    }
                }
            }
            TimelinePlayhead(
                positionMsProvider = positionMsProvider,
                safeTotal = safeTotal,
                trackWidth = trackWidth,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            ClipToolButton(AppIcons.Scissors, "Split", onSplit)
            ClipToolButton(AppIcons.Delete, "Delete", onDelete)
            ClipToolButton(AppIcons.Mute, "Mute", onMute)
            ClipToolButton(AppIcons.Speed, "Speed", onSpeed)
            ClipToolButton(AppIcons.Layer, "Layer", onLayer)
        }
    }
}

@Composable
private fun TimelineTransportRow(
    positionMsProvider: () -> Long,
    safeTotal: Long,
    remainingMs: Long,
    clipCount: Int,
    selectedClipIndex: Int,
    transport: VideoTransport,
    onSetCover: (Long) -> Unit,
) {
    val positionMs = positionMsProvider()
    fun mmss(ms: Long): String =
        String.format(Locale.US, "%02d:%02d", (ms / 1000) / 60, (ms / 1000) % 60)

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
    ) {
        IconButton(
            onClick = transport.playPause,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(BitOSColors.primary.copy(alpha = 0.14f)),
        ) {
            Icon(
                if (transport.isPlaying()) AppIcons.Pause else AppIcons.Play,
                contentDescription = "Play or pause",
                tint = BitOSColors.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            "${mmss(positionMs.coerceAtMost(safeTotal))} / ${mmss(safeTotal)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = BitOSColors.primary,
            fontWeight = FontWeight.W700,
        )
        Text(
            "· clip ${selectedClipIndex + 1}/$clipCount · ${MemeVideoCutRules.durationLabel(remainingMs)} left",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onSetCover(positionMs) }) {
            Text("Cover", fontWeight = FontWeight.W600)
        }
    }
}

@Composable
private fun TimelinePlayhead(
    positionMsProvider: () -> Long,
    safeTotal: Long,
    trackWidth: Float,
    modifier: Modifier = Modifier,
) {
    val positionMs = positionMsProvider()
    val xDp = with(LocalDensity.current) {
        (trackWidth * (positionMs.coerceIn(0L, safeTotal).toFloat() / safeTotal) - 4f).coerceAtLeast(0f).toDp()
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.offset(x = xDp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(BitOSColors.primary),
        )
        Box(
            Modifier
                .width(2.5.dp)
                .fillMaxHeight()
                .background(BitOSColors.primary),
        )
    }
}

/** One clip segment in the compact timeline. */
@Composable
internal fun TimelineClipSegment(
    index: Int,
    widthPx: Float,
    outSec: Int,
    speed: Float,
    muted: Boolean,
    selected: Boolean,
    label: String = "vdo ${index + 1}",
    onSelect: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .padding(start = if (index == 0) 0.dp else 1.5.dp, end = 1.5.dp)
            .width(with(LocalDensity.current) { widthPx.toDp() })
            .height(42.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (selected) BitOSColors.primary.copy(alpha = 0.85f)
                else BitOSColors.surface,
            )
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) BitOSColors.primary else BitOSColors.textTertiary.copy(alpha = 0.35f),
                RoundedCornerShape(7.dp),
            )
            .clickable { onSelect() }
            .semantics {
                contentDescription = "Clip ${index + 1}" +
                    (if (selected) ", selected" else "") + ", $outSec seconds"
            },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (muted) {
                Icon(
                    AppIcons.Mute,
                    contentDescription = "Muted",
                    tint = Color.White,
                    modifier = Modifier.size(10.dp),
                )
            }
            Text(
                "${outSec}s · ${"%.2g".format(Locale.US, speed)}×",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.W600,
                color = Color.White,
            )
        }
    }
}

/** Output window ms for one clip at the project rate (display math only). */
internal fun clipOutputMsOf(clip: SessionClip): Long =
    ((maxOf(0L, clip.endMs - clip.startMs)) /
        MemeProjectContract.clampSpeed(clip.speed)).toLong()

/** Icon-over-caption clip tool button. */
@Composable
internal fun ClipToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = label },
    ) {
        Icon(icon, contentDescription = null, tint = BitOSColors.textSecondary, modifier = Modifier.size(17.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary, fontWeight = FontWeight.W600)
    }
}
