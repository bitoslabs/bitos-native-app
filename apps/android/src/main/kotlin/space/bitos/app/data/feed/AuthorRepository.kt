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
import space.bitos.core.model.NostrEvent
import space.bitos.core.model.NostrKinds
import space.bitos.core.model.ProfileMetadata
import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

data class AuthorUiState(
    val pubkey: String? = null,
    val profile: ProfileMetadata? = null,
    val notes: List<FeedNote> = emptyList(),
    val isFollowing: Boolean = false,
    val isLoading: Boolean = true,
    /** False once a page returned fewer than a full page of new notes. */
    val canLoadMore: Boolean = true,
    val isLoadingMore: Boolean = false,
)

/**
 * Author profile repository: targeted profile + notes REQ for one pubkey,
 * verified fan-in, bounded window. Pages settle on ALL-RELAY EOSE (falls
 * back to a [PAGE_MAX_WAIT_MS] deadline — a dead relay cannot stall the
 * page behind its EOSE, web `loadReels` parity) and every page's REQ is
 * CLOSED on completion so overlapping subscriptions never re-stream old
 * windows. One instance at a time (the open sheet/page).
 */
class AuthorRepository(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val hasher: EventHasher = Sha256EventHasher,
) {
    private val notes = LinkedHashMap<String, FeedNote>()

    /**
     * Verified events per note id for the raw-event viewer (card ⋯ menu),
     * bounded. Serialized lazily on open — ingest never pays the encoding.
     */
    private val rawEvents = LinkedHashMap<String, NostrEvent>()
    private var profile: ProfileMetadata? = null
    private var profileAt = Long.MIN_VALUE
    private var pubkey: String? = null
    private var collectJob: Job? = null
    private var page = 0

    /** One page batch: settles when every expected relay sent EOSE. */
    private data class PageBatch(
        val subId: String,
        val expectedRelays: Set<RelayUrl>,
        val startedAtCount: Int,
        val eoseRelays: MutableSet<RelayUrl> = linkedSetOf(),
        var timeoutJob: Job? = null,
    )

    private var activePage: PageBatch? = null

    private val mutableState = MutableStateFlow(AuthorUiState())
    val state: StateFlow<AuthorUiState> = mutableState.asStateFlow()

    init {
        collectJob = scope.launch {
            pool.verifiedFrames.collect { gated ->
                when (gated) {
                    is VerifiedPoolFrame.Eose -> recordEose(gated.subscriptionId, gated.relay)
                    is VerifiedPoolFrame.Verified -> {
                        val event = gated.event
                        val target = pubkey ?: return@collect
                        if (event.pubkey.value != target) return@collect
                        when {
                            event.kind == NostrKinds.PROFILE_METADATA -> {
                                val metadata = ProfileMetadata.parse(event) ?: return@collect
                                if (event.createdAt >= profileAt) {
                                    profile = metadata
                                    profileAt = event.createdAt
                                    publishState()
                                }
                            }
                            FeedNote.isFeedKind(event.kind) -> {
                                val note = FeedNote.from(event)
                                retainRawEvent(event)
                                if (!notes.containsKey(note.id)) {
                                    notes[note.id] = note
                                    publishState()
                                }
                            }
                        }
                    }
                }
            }
        }
        pool.start()
    }

    /** Opens the profile for one author; clears previous state. */
    fun open(authorPubkey: String) {
        pubkey = authorPubkey
        notes.clear()
        synchronized(rawEvents) { rawEvents.clear() }
        profile = null
        profileAt = Long.MIN_VALUE
        page = 0
        closeActivePage()
        mutableState.value = AuthorUiState(pubkey = authorPubkey, isLoading = true)
        requestPage(untilSeconds = null)
    }

    fun close() {
        pubkey = null
        closeActivePage()
        mutableState.value = AuthorUiState()
    }

    /** Follow state is driven by the feed repository (single source). */
    fun setFollowing(following: Boolean) {
        mutableState.value = mutableState.value.copy(isFollowing = following)
    }

    /** Next older page (`until` = oldest loaded note) on demand. */
    fun loadMoreNotes() {
        val target = pubkey ?: return
        val state = mutableState.value
        if (!state.canLoadMore || state.isLoadingMore || notes.isEmpty()) return
        mutableState.value = state.copy(isLoadingMore = true)
        page += 1
        val until = notes.values.minOf { it.createdAt }
        requestPage(untilSeconds = until)
    }

    /** Re-issues the first page after an empty result — a timed-out or
     *  relay-less page is not proof the author has no notes. */
    fun retryFirstPage() {
        val target = pubkey ?: return
        if (notes.isNotEmpty()) return
        mutableState.value = mutableState.value.copy(isLoading = true, canLoadMore = true)
        requestPage(untilSeconds = null)
    }

    private fun requestPage(untilSeconds: Long?) {
        val target = pubkey ?: return
        val subId = if (untilSeconds == null) "bitos-author" else "bitos-author-$page"
        closeActivePage()
        val batch = PageBatch(
            subId = subId,
            expectedRelays = pool.connectedRelays(),
            startedAtCount = notes.size,
        )
        activePage = batch
        val request = space.bitos.core.bridge.BusinessCoreBridge().authorRequest(
            subscriptionId = subId,
            authorPubkey = target,
            untilSeconds = untilSeconds,
        )
        batch.timeoutJob = scope.launch {
            delay(PAGE_MAX_WAIT_MS)
            completePage(subId, timedOut = true)
        }
        // Synchronous on purpose: broadcast() is non-suspending, so the
        // CLOSE of the previous page (closeActivePage above) is already
        // on the wire before this REQ reuses its subscription id space.
        pool.broadcast(request)
    }

    private fun recordEose(subscriptionId: String, relay: RelayUrl) {
        val complete = synchronized(this) {
            val batch = activePage?.takeIf { it.subId == subscriptionId } ?: return
            batch.eoseRelays += relay
            batch.expectedRelays.isNotEmpty() && batch.eoseRelays.containsAll(batch.expectedRelays)
        }
        if (complete) completePage(subscriptionId, timedOut = false)
    }

    /** Settles one page: CLOSE the REQ, then judge the short-page rule. */
    private fun completePage(subscriptionId: String, timedOut: Boolean) {
        val batch = synchronized(this) {
            activePage?.takeIf { it.subId == subscriptionId }?.also { activePage = null }
        } ?: return
        batch.timeoutJob?.cancel()
        pool.broadcast(NostrEventCodec.encodeClose(batch.subId))
        val fresh = notes.size - batch.startedAtCount
        mutableState.value = mutableState.value.copy(
            isLoading = false,
            isLoadingMore = false,
            // A full EOSE short page means the author's history ended; a
            // deadline page stays retryable (a slow relay is not proof of
            // exhaustion — same rule as the feed's older-walk).
            canLoadMore = if (timedOut) true else fresh >= FRESH_PAGE_TARGET,
        )
    }

    private fun closeActivePage() {
        val batch = synchronized(this) { activePage?.also { activePage = null } } ?: return
        batch.timeoutJob?.cancel()
        pool.broadcast(NostrEventCodec.encodeClose(batch.subId))
    }

    private fun publishState() {
        mutableState.value = AuthorUiState(
            pubkey = pubkey,
            profile = profile,
            notes = notes.values.sortedByDescending { it.createdAt },
            isFollowing = mutableState.value.isFollowing,
            isLoading = false,
            canLoadMore = mutableState.value.canLoadMore,
            isLoadingMore = mutableState.value.isLoadingMore,
        )
    }

    /** NIP-01 canonical event-object JSON for the card ⋯ raw-event viewer. */
    fun rawEventJson(eventId: String): String? =
        synchronized(rawEvents) { rawEvents[eventId] }?.let(NostrEventCodec::encodeEventJson)

    private fun retainRawEvent(event: NostrEvent) {
        synchronized(rawEvents) {
            rawEvents[event.id.value] = event
            if (rawEvents.size > RAW_EVENTS_MAX) rawEvents.remove(rawEvents.keys.first())
        }
    }

    private companion object {
        /** Hard page deadline (web REELS_PAGE_MAX_WAIT_MS parity). */
        const val PAGE_MAX_WAIT_MS = 4_000L

        /** A page carrying this many NEW notes keeps `canLoadMore` true. */
        const val FRESH_PAGE_TARGET = 5

        /** Raw-event retention bound for the card ⋯ viewer. */
        const val RAW_EVENTS_MAX = 100
    }
}
