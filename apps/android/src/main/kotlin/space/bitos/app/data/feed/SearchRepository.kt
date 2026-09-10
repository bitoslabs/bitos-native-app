package space.bitos.app.data.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.bitos.app.data.relay.RelayPool
import space.bitos.app.data.relay.VerifiedPoolFrame
import space.bitos.core.feed.FeedNote
import space.bitos.core.feed.SearchResults
import space.bitos.core.model.NostrKinds
import space.bitos.core.model.ProfileMetadata
import space.bitos.core.nostr.EventHasher
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

/** Local Discover scopes; Bitz remains restricted to standard media kinds. */
enum class SearchScope(val kinds: List<Int>) {
    /** Web `DISCOVER_CONTENT_KINDS` parity: text + all four media kinds. */
    GENERAL(NostrKinds.feedKinds),
    BITZ_MEDIA(space.bitos.core.feed.BitzTimelinePolicy.MEDIA_KINDS),
}

/**
 * Discover search over the verified relay stream. Each debounced query
 * broadcasts one multi-filter REQ (NIP-50 `search` + NIP-01 `#t` + bounded
 * recent-sample fallback — web discover parity), so relays without NIP-50
 * still deliver matchable events; every result must still pass
 * [SearchResults.matches] on content the app has received and verified.
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
    private var settleJob: Job? = null
    private var subscriptionCounter = 0
    private var activeQuery: String? = null
    private var activeScope: SearchScope = SearchScope.GENERAL

    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    init {
        collectJob = scope.launch {
            pool.verifiedFrames.collect { gated ->
                val frame = gated as? VerifiedPoolFrame.Verified ?: return@collect
                val event = frame.event
                when (event.kind) {
                    NostrKinds.PROFILE_METADATA -> {
                        if (activeQuery == null) return@collect
                        val metadata = ProfileMetadata.parse(event) ?: return@collect
                        val knownAt = profileTimestamps[metadata.pubkey.value] ?: Long.MIN_VALUE
                        if (event.createdAt >= knownAt) {
                            profiles[metadata.pubkey.value] = metadata
                            profileTimestamps[metadata.pubkey.value] = event.createdAt
                            publishState()
                        }
                    }
                    else -> if (
                        FeedNote.isFeedKind(event.kind) &&
                        event.kind in activeScope.kinds
                    ) {
                        val note = FeedNote.from(event)
                        val query = activeQuery ?: return@collect
                        if (!SearchResults.matches(note, query)) return@collect
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
        searchJob?.cancel()
        settleJob?.cancel()
        activeQuery = null
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results.clear()
            profiles.clear()
            profileTimestamps.clear()
            mutableState.value = SearchUiState()
            return
        }
        // A changed query must never show the previous query's cards during
        // its debounce window.
        results.clear()
        profiles.clear()
        profileTimestamps.clear()
        mutableState.value = SearchUiState(query = query)
        searchJob = scope.launch {
            delay(DEBOUNCE_MS)
            performSearch(trimmed, searchScope)
        }
    }

    private suspend fun performSearch(query: String, searchScope: SearchScope) {
        results.clear()
        profiles.clear()
        profileTimestamps.clear()

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
        activeQuery = query
        activeScope = searchScope

        // Web discover parity: one multi-filter REQ — the NIP-50 `search`
        // filter for relays that support it, the NIP-01 `#t` filter for
        // indexed hashtag recall (the only filter for `#tag` queries, since
        // NIP-50 leaves `#` undefined in search strings), and a bounded
        // recent sample so relays without NIP-50 still deliver matchable
        // events. Results flow through the verified stream and
        // `SearchResults.matches` — local search stays verify-first.
        subscriptionCounter += 1
        SearchResults.relaySearchRequest(
            subscriptionId = "bitos-search-$subscriptionCounter",
            query = query,
            kinds = searchScope.kinds,
        )?.let(pool::broadcast)

        // Resolve the search-in-progress state after a settling window.
        settleJob = scope.launch {
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
        )
    }

    private companion object {
        const val MAX_RESULTS = 100
        const val DEBOUNCE_MS = 400L
        const val SETTLE_MS = 3_000L
    }
}
