package space.bitos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import space.bitos.app.ui.theme.AppIcons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.bitos.core.nostr.Pow
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import space.bitos.core.publish.UnsignedNote
import space.bitos.app.ui.theme.BitOSColors

/**
 * NIP-13 proof-of-work UI (unified feature spec §3.8/§4, APP-008/APP-022):
 * [PowBadge] — compact difficulty indicator; [PowCard] — difficulty slider +
 * hash visualization + chunked background mining (Dispatchers.Default, never
 * the UI thread) with progress, cancel and result. A mined nonce is valid
 * for exactly one template: the card keys its session on
 * `content + pubkey + target`, so any edit restarts cleanly.
 */

/** A completed mining session; publish must reuse the timestamp. */
data class PowOutcome(
    val nonce: Long,
    val idHex: String,
    val targetDifficulty: Int,
    val createdAtSeconds: Long,
)

private const val MAX_DIFFICULTY = 30
private const val CHUNK_ATTEMPTS = 20_000L
private const val HARD_ATTEMPT_CAP = 5_000_000L

/** Locale-grouped attempt counts ("5,000,000 hashes"). */
private val GROUPED = java.text.NumberFormat.getIntegerInstance()

/** Compact difficulty pill with segmented hash-strength bar. */
@Composable
fun PowBadge(difficulty: Int, modifier: Modifier = Modifier) {
    val filled = (difficulty / 4).coerceIn(0, 8)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(BitOSColors.zap.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = "Proof of work $difficulty bits" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(AppIcons.Zap, contentDescription = null, tint = BitOSColors.zap, modifier = Modifier.size(10.dp))
        Text("$difficulty-bit", fontSize = 10.sp, fontWeight = FontWeight.W600, color = BitOSColors.zap)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(8) { index ->
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(width = 4.dp, height = 8.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (index < filled) BitOSColors.zap else BitOSColors.border),
                )
            }
        }
    }
}

/** Difficulty selector + mining driver card (composer toolbar section). */
@Composable
fun PowCard(
    target: Int,
    onTargetChange: (Int) -> Unit,
    content: String,
    pubkeyHex: String,
    onMined: (PowOutcome?) -> Unit,
    modifier: Modifier = Modifier,
    /** APP-008: mining template tags (the nonce tag is appended by the
     * miner; the template must byte-match the published event). */
    baseTags: List<List<String>> = emptyList(),
    /** Non-kind-1 mining template (e.g. the APP-006 kind-30315 story):
     *  built once per session with the fixed mining timestamp. When set,
     *  [templateKey] replaces `content + baseTags` as the session key that
     *  invalidates a previous nonce on any edit. */
    template: ((createdAtSeconds: Long) -> UnsignedNote?)? = null,
    templateKey: String? = null,
) {
    var mining by remember { mutableStateOf(false) }
    var attempts by remember { mutableLongStateOf(0L) }
    val sessionKey = template?.let { templateKey ?: content } ?: content
    var outcome by remember(sessionKey, pubkeyHex, target, baseTags, templateKey) { mutableStateOf<PowOutcome?>(null) }
    var exhausted by remember(sessionKey, pubkeyHex, target, baseTags, templateKey) { mutableStateOf(false) }
    var mineJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun stop(notify: Boolean) {
        mineJob?.cancel()
        mineJob = null
        mining = false
        if (notify) onMined(null)
    }

    // Any template change invalidates a previous nonce.
    LaunchedEffect(sessionKey, pubkeyHex, target, templateKey) {
        outcome = null
        exhausted = false
        attempts = 0
        stop(notify = true)
    }

    /** Starts the chunked mining driver on the default dispatcher. */
    fun startMining() {
        if (mining || target <= 0) return
        mining = true
        exhausted = false
        attempts = 0
        val minedTarget = target
        mineJob = scope.launch {
            val createdAt = System.currentTimeMillis() / 1000
            // Template fixed for the whole session (createdAt included).
            val buildTemplate: () -> UnsignedNote? = {
                template?.invoke(createdAt)
                    ?: NoteComposer(clock = { createdAt }).composeTextNote(pubkeyHex, content, baseTags)
            }
            val templateNote: UnsignedNote = withContext(Dispatchers.Default) { buildTemplate() }
                ?: run {
                    mining = false
                    return@launch
                }
            var start = 0L
            while (true) {
                val hit = withContext(Dispatchers.Default) {
                    Pow.mineChunk(
                        Sha256EventHasher,
                        templateNote.pubkeyHex,
                        templateNote.createdAtSeconds,
                        templateNote.kind,
                        templateNote.tags,
                        templateNote.content,
                        minedTarget,
                        start,
                        CHUNK_ATTEMPTS,
                    )
                }
                if (hit != null) {
                    val result = PowOutcome(
                        nonce = hit.nonce,
                        idHex = hit.idHex,
                        targetDifficulty = minedTarget,
                        createdAtSeconds = templateNote.createdAtSeconds,
                    )
                    outcome = result
                    mining = false
                    onMined(result)
                    return@launch
                }
                start += CHUNK_ATTEMPTS
                attempts += CHUNK_ATTEMPTS
                if (attempts >= HARD_ATTEMPT_CAP) {
                    exhausted = true
                    mining = false
                    return@launch
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BitOSColors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Proof of work", fontSize = 13.sp, fontWeight = FontWeight.W600, color = BitOSColors.textPrimary)
            when {
                outcome != null -> PowBadge(difficulty = outcome!!.targetDifficulty)
                target > 0 -> Text("$target bits", fontSize = 12.sp, fontWeight = FontWeight.W600, color = BitOSColors.textSecondary)
            }
        }

        Slider(
            value = target.toFloat(),
            onValueChange = { onTargetChange(it.toInt()) },
            valueRange = 0f..MAX_DIFFICULTY.toFloat(),
            steps = MAX_DIFFICULTY - 1,
            enabled = !mining,
            modifier = Modifier.semantics { contentDescription = "Proof of work difficulty $target bits" },
        )

        // Segmented hash visualization of the selected difficulty.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val segments = 15
            val filled = target * segments / MAX_DIFFICULTY
            repeat(segments) { index ->
                val fraction = index.toFloat() / segments
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (index < filled) BitOSColors.primary.copy(alpha = 0.35f + 0.65f * fraction)
                            else BitOSColors.divider,
                        ),
                )
            }
        }

        when {
            outcome != null -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Mined nonce ${outcome!!.nonce} — id ${outcome!!.idHex.take(12)}…",
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = BitOSColors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = {
                    outcome = null
                    onMined(null)
                }) { Text("Clear", color = BitOSColors.textTertiary) }
            }
            mining -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = BitOSColors.zap)
                    Text("Mining… ${GROUPED.format(attempts)} hashes", fontSize = 11.sp, color = BitOSColors.textSecondary)
                }
                androidx.compose.material3.TextButton(onClick = { stop(notify = false) }) {
                    Text("Cancel", color = BitOSColors.error)
                }
            }
            exhausted -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        AppIcons.ReportSpam,
                        contentDescription = null,
                        tint = BitOSColors.warning,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        "No nonce found after ${GROUPED.format(attempts)} hashes at $target bits. Lower the target and retry.",
                        fontSize = 11.sp,
                        color = BitOSColors.warning,
                    )
                }
                OutlinedButton(onClick = { startMining() }) { Text("Retry") }
            }
            else -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (target == 0) "Off — publishes without proof of work."
                    else "Optional; higher targets take exponentially longer.",
                    fontSize = 11.sp,
                    color = BitOSColors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                if (target > 0) {
                    Button(onClick = { startMining() }) { Text("Mine") }
                }
            }
        }
    }
}
