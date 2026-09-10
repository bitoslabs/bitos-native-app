package space.bitos.app.data.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.bitos.app.data.relay.RelayPool
import space.bitos.app.data.relay.VerifiedPoolFrame
import space.bitos.core.bridge.BusinessCoreBridge
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.studio.SharedSoundContract

/**
 * Relay-fetched shared sounds (plan MST-047 / wave 2): subscribes to
 * kind-30078 app-data events, keeps the newest event per sound id whose
 * d-tag addresses `com.bitos.bitz:sound:*`, and exposes summary rows
 * through the tested bridge seam. Signature-verified; hostile shapes
 * and non-ingestable licenses (anything but CC0 / CC-BY / CC-BY-NC)
 * drop inside the shared contract. "Use sound" hands off to the editor
 * through the MST-050 Wave C/D `MemeSoundSeed(isAudioOnly)` path —
 * hash-verified download, no re-upload.
 */
class SharedSoundStore(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val hasher: EventHasher = Sha256EventHasher,
) {
    data class Row(
        /** The kind-30078 event id — provenance for the attach path. */
        val eventId: String,
        val id: String,
        val label: String,
        val url: String,
        val sha256: String,
        val license: String,
        val attribution: String,
        val durationMs: Long,
        val createdAt: Long,
        val authorPubkey: String,
    )

    private val bridge = BusinessCoreBridge()

    private val mutableRows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = mutableRows.asStateFlow()

    private var requested = false
    private var collectJob: Job? = null

    init {
        collectJob = scope.launch {
            pool.verifiedFrames.collect { gated ->
                val event = (gated as? VerifiedPoolFrame.Verified)?.event ?: return@collect
                if (event.kind != SHARED_SOUND_KIND) return@collect
                val dTag = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: return@collect
                if (!SharedSoundContract.isSoundDTag(dTag)) return@collect
                val tagsJson = space.bitos.core.store.TagsCodec.encode(event.tags)
                val summary = bridge.memeSharedSoundSummary(tagsJson, event.content)
                if (summary.isEmpty()) return@collect
                rowFrom(summary, event.createdAt, event.id.value)?.let(::upsert)
            }
        }
    }

    fun subscribe() {
        if (requested) return
        requested = true
        pool.broadcast(
            NostrEventCodec.encodeRequest(
                "bitos-sounds",
                """{"kinds":[$SHARED_SOUND_KIND],"limit":200}""",
            ),
        )
    }

    /**
     * NIP-50 relay search (MST-047 sheet UX): one bounded REQ carrying
     * the `search` filter field, closing the previous search sub first.
     * Relay support is OPTIONAL (docs: "never assume universal relay
     * support") — the sheet's client-side label/topics filter is the
     * primary path; this only augments the rail.
     */
    fun search(query: String) {
        val trimmed = query.trim().take(80)
        if (trimmed.length < MIN_SEARCH_CHARS) return
        pool.broadcast("[\"CLOSE\",\"$SEARCH_SUB_ID\"]")
        pool.broadcast(
            NostrEventCodec.encodeRequest(
                SEARCH_SUB_ID,
                "{\"kinds\":[$SHARED_SOUND_KIND],\"search\":\"" +
                    NostrEventCodec.escape(trimmed) + "\",\"limit\":100}",
            ),
        )
    }

    /** Newest-wins per sound id; rail-capped, newest-first. */
    private fun upsert(row: Row) {
        val current = mutableRows.value
        val existing = current.firstOrNull { it.id == row.id }
        if (existing != null && existing.createdAt >= row.createdAt) return
        val next = (current.filterNot { it.id == row.id } + row)
            .sortedByDescending { it.createdAt }
            .take(SharedSoundContract.MAX_SHARED_ROWS)
        mutableRows.value = next
    }

    private companion object {
        const val SHARED_SOUND_KIND = 30_078
        const val SEARCH_SUB_ID = "bitos-sounds-search"
        const val MIN_SEARCH_CHARS = 3
    }
}

/** Bridge summary row → store row (parses the tested JSON shape). */
private fun rowFrom(summaryJson: String, createdAt: Long, eventId: String): SharedSoundStore.Row? =
    runCatching {
        val root = org.json.JSONObject(summaryJson)
        SharedSoundStore.Row(
            eventId = eventId.take(space.bitos.core.studio.MemeSoundRules.MAX_SOURCE_ID_LENGTH),
            id = root.optString("id"),
            label = root.optString("label").ifBlank { root.optString("id") },
            url = root.optString("url"),
            sha256 = root.optString("sha256"),
            license = root.optString("license"),
            attribution = root.optString("attribution"),
            durationMs = root.optLong("durationMs", 0L),
            createdAt = createdAt,
            authorPubkey = root.optString("authorPubkey"),
        )
    }.getOrNull()?.takeIf { it.id.isNotBlank() && it.url.isNotBlank() && it.sha256.length == 64 }
