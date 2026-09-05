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
    GENERAL(listOf(NostrKinds.SHORT_TEXT_NOTE, NostrKinds.NORMAL_VIDEO, NostrKinds.SHORT_VIDEO)),
    BITZ_MEDIA(space.bitos.core.feed.BitzTimelinePolicy.MEDIA_KINDS),
}

/**
 * Local Discover search over the normal verified relay stream. It intentionally
 * does not create a NIP-50 REQ: relay search support varies and every result
 * must come from content the app has already received and verified.
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

        // NIP-01 hashtag recall: `#` has no defined meaning in a NIP-50
        // search string, so `#tag` queries broadcast the standard `#t`
        // filter instead — single-letter tag filters are indexed by every
        // conforming relay. Results still flow through the verified stream
        // and `SearchResults.matches` (local search stays verify-first);
        // free-text recall remains local-only (NIP-50 support varies).
        SearchResults.queryTag(query)?.let { tag ->
            subscriptionCounter += 1
            SearchResults.tagRequest(
                subscriptionId = "bitos-search-$subscriptionCounter",
                tag = tag,
                kinds = searchScope.kinds,
                limit = 50,
            )?.let(pool::broadcast)
        }

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
