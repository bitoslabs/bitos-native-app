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
import space.bitos.core.studio.MemeTemplateContract

/**
 * Relay-fetched shared templates (plan MST-045 / wave 3): subscribes to
 * kind-30078 app-data events, keeps the newest event per template id whose
 * d-tag addresses `com.bitos.bitz:template:*`, and exposes summary rows
 * (the raw tags+content ride along so Apply reuses the tested bridge
 * parse — never re-trusted client-side). Signature-verified; hostile
 * shapes drop inside the shared contract.
 */
class SharedTemplateStore(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val hasher: EventHasher = Sha256EventHasher,
) {
    data class Row(
        val id: String,
        val label: String,
        val emoji: String,
        val priceSats: Long,
        val category: String,
        val createdAt: Long,
        val tagsJson: String,
        val content: String,
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
                if (event.kind != SHARED_TEMPLATE_KIND) return@collect
                val dTag = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: return@collect
                if (!MemeTemplateContract.isTemplateDTag(dTag)) return@collect
                val tagsJson = space.bitos.core.store.TagsCodec.encode(event.tags)
                val summary = bridge.memeSharedTemplateSummary(tagsJson, event.content)
                if (summary.isEmpty()) return@collect
                rowFrom(summary, event.createdAt, tagsJson, event.content)?.let(::upsert)
            }
        }
    }

    fun subscribe() {
        if (requested) return
        requested = true
        pool.broadcast(
            NostrEventCodec.encodeRequest(
                "bitos-templates",
                """{"kinds":[$SHARED_TEMPLATE_KIND],"limit":200}""",
            ),
        )
    }

    /** Newest-wins per template id; rail-capped, newest-first. */
    private fun upsert(row: Row) {
        val current = mutableRows.value
        val existing = current.firstOrNull { it.id == row.id }
        if (existing != null && existing.createdAt >= row.createdAt) return
        val next = (current.filterNot { it.id == row.id } + row)
            .sortedByDescending { it.createdAt }
            .take(MemeTemplateContract.MAX_SHARED_ROWS)
        mutableRows.value = next
    }

    private companion object {
        const val SHARED_TEMPLATE_KIND = 30_078
    }
}

/** Bridge summary row → store row (parses the tested JSON shape). */
private fun rowFrom(summaryJson: String, createdAt: Long, tagsJson: String, content: String): SharedTemplateStore.Row? =
    runCatching {
        val root = org.json.JSONObject(summaryJson)
        SharedTemplateStore.Row(
            id = root.optString("id"),
            label = root.optString("label").ifBlank { root.optString("id") },
            emoji = root.optString("emoji").ifBlank { "\uD83D\uDDBC" },
            priceSats = root.optLong("priceSats", 0L),
            category = root.optString("category").ifBlank { "meme" },
            createdAt = createdAt, tagsJson = tagsJson, content = content,
        )
    }.getOrNull()?.takeIf { it.id.isNotBlank() }
