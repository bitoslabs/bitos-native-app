package space.bitos.app.data.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.bitos.app.data.relay.RelayPool
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.NostrKinds
import space.bitos.core.model.ProfileMetadata
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

data class SearchUiState(
    val query: String = "",
    val results: List<FeedNote> = emptyList(),
    val profiles: Map<String, ProfileMetadata> = emptyMap(),
    val resolvedNpub: String? = null,
    /** APP-009: the query was a note1/nevent1/naddr1 reference — the first
     * result (when it arrives) is the thread root. */
    val isRefSearch: Boolean = false,
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
)

/**
 * NIP-50 search scopes (Bitz discovery/query standard, web docs/SYSTEM.md):
 * Bitz searches the standard NIP-68/NIP-71 media kinds only — it never
 * discovers media by scanning kind-1 text notes. Discover keeps its
 * text+video kinds.
 */
enum class SearchScope(val kinds: List<Int>) {
    GENERAL(listOf(NostrKinds.SHORT_TEXT_NOTE, NostrKinds.NORMAL_VIDEO, NostrKinds.SHORT_VIDEO)),
    BITZ_MEDIA(space.bitos.core.feed.BitzTimelinePolicy.MEDIA_KINDS),
}

/**
 * NIP-50 search repository (SOC-004): debounced text search over feed kinds,
 * npub resolution for creator queries, verified results in a bounded window.
 * Results clear when the query clears.
 */
class SearchRepository(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val hasher: EventHasher = Sha256EventHasher,
) {
    private val results = LinkedHashMap<String, FeedNote>()
    private val profiles = mutableMapOf<String, ProfileMetadata>()
    private var collectJob: Job? = null
    private var searchJob: Job? = null
    private var subscriptionCounter = 0

    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    init {
        collectJob = scope.launch {
            pool.frames.collect { frame ->
                val event = runCatching {
                    NostrEventCodec.decodeRelayEvent(hasher, frame.message, frame.relay)
                }.getOrNull() ?: return@collect
                if (!NostrEventCodec.verifySignature(hasher, event)) return@collect
                when (event.kind) {
                    NostrKinds.PROFILE_METADATA -> {
                        val metadata = ProfileMetadata.parse(event) ?: return@collect
                        val knownAt = profileTimestamps[metadata.pubkey.value] ?: Long.MIN_VALUE
                        if (event.createdAt >= knownAt) {
                            profiles[metadata.pubkey.value] = metadata
                            profileTimestamps[metadata.pubkey.value] = event.createdAt
                            publishState()
                        }
                    }
                    else -> if (FeedNote.isFeedKind(event.kind)) {
                        val note = FeedNote.from(event)
                        if (results.containsKey(note.id)) return@collect
                        results[note.id] = note
                        if (results.size > MAX_RESULTS) results.remove(results.keys.first())
                        publishState()
                    }
                }
            }
        }
        pool.start()
    }

    private val profileTimestamps = mutableMapOf<String, Long>()

    /** Debounced search trigger; empty query clears results. The scope owns
     *  the queried kind set (Discover general vs Bitz media-only). */
    fun search(query: String, searchScope: SearchScope = SearchScope.GENERAL) {
        mutableState.value = mutableState.value.copy(query = query)
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results.clear()
            profiles.clear()
            mutableState.value = SearchUiState()
            return
        }
        searchJob = scope.launch {
            delay(DEBOUNCE_MS)
            performSearch(trimmed, searchScope)
        }
    }

    private suspend fun performSearch(query: String, searchScope: SearchScope) {
        subscriptionCounter += 1
        results.clear()
        profiles.clear()

        val npub = if (query.startsWith("npub1")) {
            space.bitos.core.identity.NostrKeyCodec.parseNpub(query)
        } else null

        // APP-009 root resolution: note1/nevent1/naddr1 fetches the thread
        // head directly (id or newest NIP-33 coordinate version).
        val eventRef = space.bitos.core.nostr.EventRefs.parse(query)
        mutableState.value = mutableState.value.copy(
            isSearching = true,
            hasSearched = true,
            resolvedNpub = npub,
            isRefSearch = eventRef != null,
        )

        if (eventRef != null) {
            pool.broadcast(
                NostrEventCodec.encodeRequest(
                    "bitos-ref-$subscriptionCounter",
                    space.bitos.core.nostr.EventRefs.requestFilter(eventRef),
                ),
            )
        } else {
        // Text search over the scope's kind set (NIP-50; relay support varies
        // — the empty result state says "relays may not support search").
        val kinds = searchScope.kinds.joinToString(",")
        val filter = """{"kinds":[$kinds],"search":"${NostrEventCodec.escape(query)}","limit":50}"""
        pool.broadcast(NostrEventCodec.encodeRequest("bitos-search-$subscriptionCounter", filter))

        // npub: request the creator's profile + notes directly.
        if (npub != null) {
            pool.broadcast(
                NostrEventCodec.encodeRequest(
                    "bitos-search-profile-$subscriptionCounter",
                    """{"kinds":[0,1,21,22],"authors":["$npub"],"limit":20}""",
                ),
            )
        }
        } // ref-search branch: no NIP-50/npub fan-out

        // Resolve the search-in-progress state after a settling window.
        scope.launch {
            delay(SETTLE_MS)
            if (mutableState.value.isSearching && mutableState.value.query.trim() == query) {
                mutableState.value = mutableState.value.copy(isSearching = false)
            }
        }
    }

    private fun publishState() {
        mutableState.value = mutableState.value.copy(
            results = results.values.sortedByDescending { it.createdAt },
            profiles = profiles.toMap(),
            isSearching = false,
        )
    }

    private companion object {
        const val MAX_RESULTS = 100
        const val DEBOUNCE_MS = 400L
        const val SETTLE_MS = 3_000L
    }
}
