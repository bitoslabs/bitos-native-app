package space.bitos.app.data.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.bitos.app.data.relay.RelayConnectionState
import space.bitos.app.data.relay.RelayPool
import space.bitos.core.feed.AlgorithmSnapshot
import space.bitos.core.feed.AlgorithmSurface
import space.bitos.core.feed.BitzTimelinePolicy
import space.bitos.core.feed.EmptyFeedRetry
import space.bitos.core.feed.FeedAggregator
import space.bitos.core.feed.FeedFilter
import space.bitos.core.feed.FeedFilters
import space.bitos.core.feed.FeedNote
import space.bitos.core.feed.FeedRanking
import space.bitos.core.feed.RankingContext
import space.bitos.core.bridge.BusinessCoreBridge
import space.bitos.core.identity.AccountBootstrap
import space.bitos.core.model.ContactList
import space.bitos.core.model.NostrEvent
import space.bitos.core.model.NostrKinds
import space.bitos.core.model.ProfileMetadata
import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.store.EventStoreContract

/** Which Home timeline the user is viewing. */
enum class FeedTimeline { FOR_YOU, FOLLOWING }

/** Relay health as presented in the feed header. */
data class RelayHealth(val connected: Int, val total: Int) {
    val isLive: Boolean get() = connected > 0
}

data class FeedUiState(
    val timeline: FeedTimeline = FeedTimeline.FOR_YOU,
    val filter: FeedFilter = FeedFilter.ALL,
    val notes: List<FeedNote> = emptyList(),
    val pendingNotes: List<FeedNote> = emptyList(),
    /** Live tab counts (spec §3.4): ALL-window size per timeline, mutes and
     * protocol payload hidden — what each tab shows under the All filter. */
    val forYouCount: Int = 0,
    val followingCount: Int = 0,
    /** APP-004 pagination: an older-notes REQ is in flight (footer spinner). */
    val isLoadingOlder: Boolean = false,
    /** True once an older-notes REQ made no progress — hidden until refresh. */
    val noMoreOlder: Boolean = false,
    val profiles: Map<String, ProfileMetadata> = emptyMap(),
    val relayHealth: RelayHealth = RelayHealth(0, 0),
    val isLoading: Boolean = false,
    /** True once at least one verified event arrived; drives empty states. */
    val hasLoadedAnyEvent: Boolean = false,
    /** Set when an identity is active; enables the Following timeline. */
    val accountPubkey: String? = null,
    /** True once the account's contact list resolved (even if empty). */
    val followingResolved: Boolean = false,
    /** Verified replies grouped by target event id (bounded). */
    val comments: Map<String, List<FeedNote>> = emptyMap(),
    /** APP-009 X-style display list per thread (shared assembly rule). */
    val threads: Map<String, List<space.bitos.core.feed.ThreadItem>> = emptyMap(),
    /** Current optimistic + relay-reconciled follow set. */
    val following: Set<String> = emptySet(),
    /** Saved event ids from the account's NIP-51 list (optimistic + relay). */
    val bookmarkedIds: Set<String> = emptySet(),
    /** APP-015 saved notes (newest-saved first; window + by-id refetch). */
    val bookmarkedNotes: List<FeedNote> = emptyList(),
    /** Zap counts per target event id from verified kind-9735 receipts. */
    val zapCounts: Map<String, Int> = emptyMap(),
    /** APP-014 paid-matching: verified embedded 9734 request ids per target. */
    val zapRequestIds: Map<String, Set<String>> = emptyMap(),
    /** APP-009 live per-note tallies (reactions/reposts/zaps+msat, shared rule). */
    val tallies: Map<String, space.bitos.core.feed.NoteTally> = emptyMap(),
    /** My kind-7 reaction event id per liked note (web `myEventId` parity) —
     *  the kind-5 unlike target. */
    val myReactionEventIds: Map<String, String> = emptyMap(),
    /** APP-008 poll tallies per poll note id (latest-vote-per-pubkey rule). */
    val pollTallies: Map<String, space.bitos.core.model.PollTally> = emptyMap(),
    /** Muted authors' notes are filtered from all windows (device-local). */
    val muted: Set<String> = emptySet(),
    /** Blocked authors (NIP-51 10004 head) — filtered like mutes, relay-derived. */
    val blocked: Set<String> = emptySet(),
)

/**
 * Read-only feed repository: subscribes verified notes on the configured
 * relays (ID + signature gate before projection), persists verified events
 * to the bounded local cache, hydrates the feed window on cold start, and
 * builds the Following timeline from the account's newest kind-3 contact
 * list once [setAccount] supplies an identity.
 */
class FeedRepository(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val hasher: EventHasher = Sha256EventHasher,
    private val cache: EventCache,
    /** Injectable so contract tests drive the APP-004 empty-feed backoff
     * without real-time waits; production uses the shared-core policy. */
    private val retryDelayMs: (Int) -> Long = EmptyFeedRetry::delayMs,
    /** Injectable so contract tests drive the cold-start bootstrap without
     * real-time waits; production polls at the shared health cadence. */
    private val bootstrapPollMs: Long = 2_000,
    /** Web feedPreferences parity: persisted reader opt-in that re-admits
     * protocol-payload notes into the feed windows. */
    private val showProtocolNotes: () -> Boolean = { false },
) {
    private val aggregator = FeedAggregator(maxItems = 200)
    private val followingWindow = FeedAggregator(maxItems = 200)
    private val contactCandidates = mutableListOf<NostrEvent>()
    private val followingAuthors = mutableSetOf<String>()
    private var accountPubkey: String? = null
    private var mutedPubkeys: Set<String> = emptySet()

    /** Blocked authors (NIP-51 kind 10004 head) — APP-012/APP-018 privacy. */
    private var blockedPubkeys: Set<String> = emptySet()
    private val blockCandidates = mutableListOf<NostrEvent>()
    private var followingSubscribed = false
    private val commentThreads = mutableMapOf<String, LinkedHashMap<String, FeedNote>>()

    /** APP-007 Chain: fetched remix ancestors (bounded; tags feed the walk). */
    private val remixChainEvents = LinkedHashMap<String, NostrEvent>()
    private var chainCounter = 0

    /** APP-015: saved-note bodies for ids outside the live feed window. */
    private val bookmarkedNoteMap = LinkedHashMap<String, FeedNote>()
    private val bookmarkCandidates = mutableListOf<NostrEvent>()
    private val zapCounts = mutableMapOf<String, Int>()
    private val zapRequestIdsBuffer = LinkedHashMap<String, LinkedHashSet<String>>()
    private val talliesBuffer = LinkedHashMap<String, space.bitos.core.feed.NoteTally>()
    /** My kind-7 event id per liked note (kind-5 unlike target, bounded). */
    private val myReactionEventIds = LinkedHashMap<String, String>()
    private val tallyTargets = LinkedHashSet<String>()
    private val bookmarked = linkedSetOf<String>()
    private val profiles = mutableMapOf<String, ProfileMetadata>()
    private val profileQueue = ArrayDeque<String>()
    private val requestedProfiles = mutableSetOf<String>()
    private var profileDrainJob: Job? = null
    private val profileFallbackJobs = mutableSetOf<Job>()
    private var profileRequestCounter = 0
    private val bridge = BusinessCoreBridge()
    // (size, comments, threadItems) per rootId — skips assembly when reply count unchanged.
    private data class ThreadCache(val size: Int, val comments: List<FeedNote>, val items: List<space.bitos.core.feed.ThreadItem>)
    private val threadCache = mutableMapOf<String, ThreadCache>()
    // Profiles snapshot regenerated only when a new profile is absorbed.
    private var profilesGeneration = 0
    private var cachedProfilesGeneration = -1
    private var cachedProfilesSnapshot: Map<String, ProfileMetadata> = emptyMap()

    private val mutableState = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = mutableState.asStateFlow()

    private var collectJob: Job? = null
    private var retryJob: Job? = null
    /**
     * Cold-start account bootstrap (shared `AccountBootstrap`): the one-shot
     * account heads fired in [setAccount] are lost when no relay socket is
     * open yet, so a connectivity watcher re-issues them while unresolved.
     */
    private var connectivityJob: Job? = null
    private var accountHeadAttempts = IntArray(4)
    private var bookmarkHeadReceived = false
    private var blockHeadReceived = false
    private var lastConnectedRelays = 0
    /** The initial head is a snapshot. Later frames from this subscription are
     * live arrivals and must not move the reader's current list. */
    private var headSubscriptionId: String? = null
    private var headExpectedRelays: Set<RelayUrl> = emptySet()
    private val headEoseRelays = mutableSetOf<RelayUrl>()
    private var initialSnapshotComplete = false
    private var headSnapshotDeadline: Job? = null
    private var retryAttempt = 0
    private var subscriptionCounter = 0
    private var olderCounter = 0
    private data class OlderLane(
        var anchorSeconds: Long? = null,
        var cursorSeconds: Long? = null,
        var exhausted: Boolean = false,
    )

    /** Exact results for one REQ, merged and deduped across parallel relays. */
    private data class OlderBatch(
        val timeline: FeedTimeline,
        val subId: String,
        val cursor: Long,
        val batches: Int,
        val freshMedia: Int,
        val emptyAttempts: Int,
        val expectedRelays: Set<RelayUrl>,
        val knownBefore: Set<String>,
        val returnedIds: MutableSet<String> = linkedSetOf(),
        val freshIds: MutableSet<String> = linkedSetOf(),
        val freshPlayableIds: MutableSet<String> = linkedSetOf(),
        val eoseRelays: MutableSet<RelayUrl> = linkedSetOf(),
        var oldestInBatch: Long? = null,
        var timeoutJob: Job? = null,
    )

    /** Pagination is independent per For You / Following window. */
    private val olderLanes = FeedTimeline.entries.associateWith { OlderLane() }.toMutableMap()
    private var loadingOlderTimeline: FeedTimeline? = null
    private val olderBatchLock = Any()
    private var activeOlderBatch: OlderBatch? = null

    fun start() {
        if (collectJob != null) return
        hydrateFromCache()
        pool.start()
        collectJob = scope.launch {
            pool.frames.collect { frame ->
                NostrEventCodec.relayEoseSubscriptionId(frame.message)?.let { subId ->
                    recordOlderEose(subId, frame.relay)
                    recordHeadEose(subId, frame.relay)
                    return@collect
                }
                val relaySubscriptionId = NostrEventCodec.relayEventSubscriptionId(frame.message)
                val event = runCatching {
                    NostrEventCodec.decodeRelayEvent(hasher, frame.message, frame.relay)
                }.getOrNull() ?: return@collect
                // Non-negotiable principle 2: verify ID AND signature before
                // projection; unverified events never reach display state.
                if (!NostrEventCodec.verifySignature(hasher, event)) return@collect
                when {
                    event.kind == NostrKinds.CONTACT_LIST -> absorbContactList(event)
                    event.kind == space.bitos.core.model.BookmarkList.KIND -> absorbBookmarkList(event)
                    event.kind == space.bitos.core.model.BlockList.KIND -> absorbBlockList(event)
                    event.kind == space.bitos.core.model.ZapReceipt.RECEIPT_KIND -> absorbZapReceipt(event)
                    event.kind == NostrKinds.PROFILE_METADATA -> absorbProfile(event)
                    event.kind == space.bitos.core.model.NostrKinds.REPOST -> {
                        // APP-009: reposts targeting a thread note count live.
                        tallyTargetFor(event.eTaggedIds())?.let { target -> mergeTally(target, event.kind, null) }
                        absorbNote(event)
                    }
                    event.kind == NostrKinds.GENERIC_REACTION -> {
                        // APP-009: kind-7 reactions tally per thread note.
                        tallyTargetFor(event.eTaggedIds())?.let { target -> mergeTally(target, event.kind, null) }
                        // Web `myEventId` parity: remember MY reaction event
                        // per note so an unlike can publish its kind-5.
                        val account = accountPubkey
                        if (account != null && event.pubkey.value == account) {
                            event.eTaggedIds().firstOrNull()?.let { target ->
                                myReactionEventIds[target] = event.id.value
                            }
                        }
                    }
                    // NIP-22 comments (ADR-003): kind-1111 projects into the
                    // comment thread only — never the feed windows.
                    event.kind == NostrKinds.VIDEO_COMMENT -> absorbReply(FeedNote.from(event))
                    // APP-008 poll votes: kind-1018 tallies per poll note.
                    event.kind == NostrKinds.POLL_RESPONSE -> absorbPollVote(event)
                    FeedNote.isFeedKind(event.kind) -> {
                        val note = FeedNote.from(event)
                        recordOlderEvent(relaySubscriptionId, event, note)
                        absorbNote(
                            event,
                            fromOlderPage = relaySubscriptionId
                                ?.startsWith(OLDER_SUBSCRIPTION_PREFIX) == true,
                        )
                    }
                }
            }
        }
        // APP-004: empty-feed auto-retry (shared `EmptyFeedRetry` policy —
        // 2 s exponential backoff capped at 30 s, relay-connectivity-gated:
        // a REQ only re-fires while the window is empty AND a relay is
        // connected; the pool owns reconnection otherwise).
        retryJob = scope.launch {
            while (true) {
                delay(retryDelayMs(retryAttempt))
                if (allWindowSize() > 0) {
                    retryAttempt = 0
                    continue
                }
                if (!EmptyFeedRetry.shouldResubscribe(windowEmpty = true, connectedRelays = connectedRelayCount())) continue
                retryAttempt = EmptyFeedRetry.advance(retryAttempt)
                subscribe()
            }
        }
        // Cold-start bootstrap: re-issue the account heads (profile/contacts/
        // bookmarks/blocks) that [setAccount] fired before any socket opened.
        // Polled rather than edge-driven: a relay that reconnects to the same
        // connected count must still grant a fresh re-issue pass.
        connectivityJob = scope.launch {
            while (true) {
                delay(bootstrapPollMs)
                val connected = connectedRelayCount()
                if (AccountBootstrap.shouldOpenEpisode(lastConnectedRelays, connected)) {
                    accountHeadAttempts = IntArray(accountHeadAttempts.size)
                }
                lastConnectedRelays = connected
                reissueUnresolvedAccountHeads(connected)
            }
        }
        subscribe()
    }

    fun stop() {
        cancelActiveOlderBatch()
        headSnapshotDeadline?.cancel()
        headSnapshotDeadline = null
        pool.broadcast(NostrEventCodec.encodeClose(subscriptionId()))
        collectJob?.cancel()
        collectJob = null
        retryJob?.cancel()
        retryJob = null
        connectivityJob?.cancel()
        connectivityJob = null
        profileDrainJob?.cancel()
        profileFallbackJobs.forEach(Job::cancel)
        profileFallbackJobs.clear()
    }

    /** Muted authors are filtered from all feed windows (device-local). */
    /**
     * Algorithm preferences (APP-018 §3.18): null or a disabled FEED
     * surface keeps the For-You window strictly chronological; the
     * Following timeline is ALWAYS chronological (origin rule).
     */
    fun setAlgorithm(snapshot: AlgorithmSnapshot?) {
        algorithm = snapshot
        publishState()
    }

    private var algorithm: AlgorithmSnapshot? = null

    /** Local ranking signals (hide + author/tag demotions). */
    private var dismissedNoteIds: Set<String> = emptySet()
    private var demotedAuthors: Set<String> = emptySet()
    private var demotedTags: Set<String> = emptySet()

    fun setMuted(muted: Set<String>) {
        mutedPubkeys = muted
        publishState()
    }

    /** Local ranking signals (web interaction-profile parity). */
    fun setInteractionProfile(dismissed: Set<String>, authors: Set<String>, tags: Set<String>) {
        dismissedNoteIds = dismissed
        demotedAuthors = authors
        demotedTags = tags
        publishState()
    }

    fun selectTimeline(timeline: FeedTimeline) {
        mutableState.value = mutableState.value.copy(timeline = timeline)
        publishState()
    }

    fun refresh() {
        // Fresh subscription re-opens the timeline head; older pages may
        // exist again after new arrivals push the window deeper.
        mutableState.value = mutableState.value.copy(isLoading = true, noMoreOlder = false)
        olderLanes.values.forEach {
            it.anchorSeconds = null
            it.cursorSeconds = null
            it.exhausted = false
        }
        cancelActiveOlderBatch()
        loadingOlderTimeline = null
        subscribe()
    }

    /** APP-004: manual retry from the relay-error / empty state — resets the
     * backoff so the next auto-retry is 2 s away, then re-issues the REQ. */
    fun retryNow() {
        retryAttempt = 0
        mutableState.value = mutableState.value.copy(noMoreOlder = false)
        olderLanes.values.forEach {
            it.anchorSeconds = null
            it.cursorSeconds = null
            it.exhausted = false
        }
        cancelActiveOlderBatch()
        loadingOlderTimeline = null
        subscribe()
    }

    /**
     * APP-004 pagination (Flutter `_fetchReels` parity, shared
     * `BitzTimelinePolicy` rules): one "load more" is a bounded backwards
     * `until`-walk whose page budget counts only FRESH VIDEO notes — a
     * relay page of text/duplicates must not strand the surface at a
     * short list. Pages that move the cursor but add nothing playable
     * auto-continue (up to 6 batches / 4 s each); a page with no new ids
     * at all advances a retry counter and exhausts after two empties.
     * The Following tab walks its OWN window (the global cursor is not
     * its boundary) — root cause of "load more does nothing" on that tab.
     */
    fun loadOlder() {
        if (loadingOlderTimeline != null) return
        val timeline = mutableState.value.timeline
        val lane = olderLanes.getValue(timeline)
        if (lane.exhausted) return
        val snapshot = windowFor(timeline).snapshot()
        if (snapshot.isEmpty()) return
        if (aggregator.snapshot().size + followingWindow.snapshot().size >= OLDER_WINDOW_MAX) {
            lane.exhausted = true
            publishState()
            return
        }
        val oldest = snapshot.minOf { it.createdAt }
        if (oldest != lane.anchorSeconds) {
            lane.anchorSeconds = oldest
            lane.cursorSeconds = BitzTimelinePolicy.cursor(oldest)
        }
        val cursor = lane.cursorSeconds ?: BitzTimelinePolicy.cursor(oldest)
        loadingOlderTimeline = timeline
        publishState()
        walkOlder(
            timeline = timeline,
            cursor = cursor,
            batches = 0,
            freshMedia = 0,
            emptyAttempts = 0,
        )
    }

    /** Per-tab pagination source: Following walks the follows window. */
    private fun windowFor(timeline: FeedTimeline): FeedAggregator =
        if (timeline == FeedTimeline.FOLLOWING) followingWindow else aggregator

    private fun walkOlder(
        timeline: FeedTimeline,
        cursor: Long,
        batches: Int,
        freshMedia: Int,
        emptyAttempts: Int,
    ) {
        if (!BitzTimelinePolicy.shouldContinue(freshMedia, batches)) {
            finishOlderWalk(timeline)
            return
        }
        olderCounter += 1
        val subId = "bitos-older-$olderCounter"
        val knownBefore = HashSet<String>(knownNoteIds.size + pendingForYou.size + pendingFollowing.size).apply {
            addAll(knownNoteIds)
            addAll(pendingForYou.map { it.id })
            addAll(pendingFollowing.map { it.id })
            addAll(aggregator.snapshot().map { it.id })
        }
        val batch = OlderBatch(
            timeline = timeline,
            subId = subId,
            cursor = cursor,
            batches = batches,
            freshMedia = freshMedia,
            emptyAttempts = emptyAttempts,
            expectedRelays = pool.connectedRelays(),
            knownBefore = knownBefore,
        )
        synchronized(olderBatchLock) { activeOlderBatch = batch }
        batch.timeoutJob = scope.launch {
            delay(BitzTimelinePolicy.PAGE_MAX_WAIT_MS)
            completeOlderBatch(subId)
        }
        pool.broadcast(
            NostrEventCodec.encodeRequest(subId, BitzTimelinePolicy.batchFilters(cursor)),
        )
    }

    private fun recordOlderEvent(subscriptionId: String?, event: NostrEvent, note: FeedNote) {
        if (subscriptionId == null) return
        synchronized(olderBatchLock) {
            val batch = activeOlderBatch?.takeIf { it.subId == subscriptionId } ?: return
            batch.returnedIds += note.id
            batch.oldestInBatch = minOf(batch.oldestInBatch ?: event.createdAt, event.createdAt)
            if (note.id !in batch.knownBefore && batch.freshIds.add(note.id) &&
                note.video != null &&
                (batch.timeline == FeedTimeline.FOR_YOU || event.pubkey.value in followingAuthors)
            ) {
                batch.freshPlayableIds += note.id
            }
        }
    }

    private fun recordOlderEose(subscriptionId: String, relay: RelayUrl) {
        val complete = synchronized(olderBatchLock) {
            val batch = activeOlderBatch?.takeIf { it.subId == subscriptionId } ?: return
            batch.eoseRelays += relay
            batch.expectedRelays.isNotEmpty() && batch.eoseRelays.containsAll(batch.expectedRelays)
        }
        if (complete) completeOlderBatch(subscriptionId)
    }

    /** Completes one exact subscription batch on all-EOSE or its hard timeout. */
    private fun completeOlderBatch(subscriptionId: String) {
        val batch = synchronized(olderBatchLock) {
            activeOlderBatch?.takeIf { it.subId == subscriptionId }?.also { activeOlderBatch = null }
        } ?: return
        batch.timeoutJob?.cancel()
        pool.broadcast(NostrEventCodec.encodeClose(batch.subId))
        if (loadingOlderTimeline != batch.timeline) return

        val freshIds = batch.freshIds.size
        val freshPlayable = batch.freshPlayableIds.size
        val nextCursor = BitzTimelinePolicy.advanceCursor(batch.oldestInBatch, batch.cursor)
        olderLanes.getValue(batch.timeline).cursorSeconds = nextCursor
        val confirmedEmpty = batch.expectedRelays.isNotEmpty() &&
            batch.eoseRelays.containsAll(batch.expectedRelays)
        val stalled = batch.returnedIds.isNotEmpty() &&
            BitzTimelinePolicy.relayStalled(batch.oldestInBatch, batch.cursor, freshIds)
        if (stalled) {
            olderLanes.getValue(batch.timeline).exhausted = true
            finishOlderWalk(batch.timeline)
            return
        }

        // A deadline is a slow/unavailable relay, not proof that history
        // ended. Leave this lane retryable so the near-edge trigger can ask
        // again; only all-relay EOSE empties contribute to exhaustion.
        if (batch.returnedIds.isEmpty() && !confirmedEmpty) {
            finishOlderWalk(batch.timeline)
            return
        }
        val nextEmptyAttempts = if (batch.returnedIds.isEmpty()) batch.emptyAttempts + 1 else 0
        if (nextEmptyAttempts >= OLDER_EMPTY_EXHAUST) {
            olderLanes.getValue(batch.timeline).exhausted = true
            finishOlderWalk(batch.timeline)
            return
        }
        walkOlder(
            timeline = batch.timeline,
            cursor = nextCursor,
            batches = batch.batches + 1,
            freshMedia = batch.freshMedia + freshPlayable,
            emptyAttempts = nextEmptyAttempts,
        )
    }

    private fun cancelActiveOlderBatch() {
        val batch = synchronized(olderBatchLock) {
            activeOlderBatch.also { activeOlderBatch = null }
        } ?: return
        batch.timeoutJob?.cancel()
        pool.broadcast(NostrEventCodec.encodeClose(batch.subId))
    }

    private fun finishOlderWalk(timeline: FeedTimeline) {
        if (loadingOlderTimeline == timeline) loadingOlderTimeline = null
        publishState()
    }

    /** ALL-window size of the For You timeline (mutes + protocol payload hidden). */
    private fun allWindowSize(): Int =
        aggregator.snapshot().count { it.pubkey !in mutedPubkeys && (showProtocolNotes() || !it.isProtocolPayload) }

    private fun space.bitos.core.model.NostrEvent.eTaggedIds(): List<String> =
        tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) }

    private fun connectedRelayCount(): Int =
        pool.states.values.count { it == RelayConnectionState.CONNECTED }

    /**
     * Optimistic follow change: updates the local set and Following window
     * immediately; the caller publishes the resulting kind-3 and the relay
     * echo (verified, newer) reconciles through absorbContactList.
     *
     * @return the new follow set for the kind-3 publish, or null when no
     *         account is active (nothing changed).
     */
    fun applyFollowChange(author: String, add: Boolean): List<String>? {
        val account = accountPubkey ?: return null
        if (author == account) return null
        val updated = if (add) followingAuthors + author else followingAuthors - author
        followingAuthors.clear()
        followingAuthors.addAll(updated)
        followingSubscribed = false
        subscribeFollowing()
        publishState()
        return updated.toList()
    }

    /**
     * Optimistic bookmark change: updates the local set immediately; the
     * caller publishes the resulting kind-30003 list and the relay echo
     * (verified, newer) reconciles. Null without an account.
     */
    fun applyBookmarkChange(eventId: String, add: Boolean): List<String>? {
        if (accountPubkey == null) return null
        if (add) bookmarked.add(eventId) else bookmarked.remove(eventId)
        if (bookmarked.size > space.bitos.core.model.BookmarkList.MAX_BOOKMARKS) {
            bookmarked.remove(bookmarked.first())
        }
        publishState()
        return bookmarked.toList()
    }

    /** Loads zap receipts for one note (NIP-01 tagged #e filter). */
    fun loadZaps(targetEventId: String) {
        val filter = ZAP_FILTER_PREFIX + targetEventId + ZAP_FILTER_SUFFIX
        pool.broadcast(NostrEventCodec.encodeRequest("bitos-zaps-$targetEventId".take(64), filter))
    }

    private fun absorbZapReceipt(event: NostrEvent) {
        val target = space.bitos.core.model.ZapReceipt.targetEventId(event) ?: return
        // APP-009: live zap tallies per thread note (root or reply).
        tallyTargetFor(listOf(target))?.let { tallyId ->
            val invoice = event.tags.firstOrNull { it.firstOrNull() == "bolt11" }?.getOrNull(1)
            mergeTally(tallyId, event.kind, space.bitos.core.model.Bolt11.amountMillisats(invoice ?: ""))
        }
        // APP-014: retain the embedded 9734 request id so the zap sheet can
        // match ITS receipt exactly (not just any zap to the same note).
        space.bitos.core.model.ZapReceipt.embeddedRequestId(event)?.let { requestId ->
            zapRequestIdsBuffer.getOrPut(target) { LinkedHashSet() }.add(requestId)
            if (zapRequestIdsBuffer.size > ZAP_TARGETS_MAX) zapRequestIdsBuffer.remove(zapRequestIdsBuffer.keys.first())
        }
        // Verified receipts count once per unique event id (fan-in dedupe by
        // aggregator-style keys is overkill for a bounded count window).
        zapCounts[target] = (zapCounts[target] ?: 0) + 1
        if (zapCounts.size > ZAP_TARGETS_MAX) zapCounts.remove(zapCounts.keys.first())
        publishState()
    }

    private fun absorbBookmarkList(event: NostrEvent) {
        val account = accountPubkey ?: return
        if (event.pubkey.value != account) return
        // The account's bookmark head resolved — the bootstrap stops re-asking.
        bookmarkHeadReceived = true
        bookmarkCandidates.add(event)
        val newest = space.bitos.core.model.BookmarkList.newest(bookmarkCandidates) ?: return
        bookmarked.clear()
        bookmarked.addAll(space.bitos.core.model.BookmarkList.bookmarkedIds(newest))
        publishState()
    }

    /** Blocked-author head (APP-012/018 privacy): newest verified 10004 wins. */
    private fun absorbBlockList(event: NostrEvent) {
        val account = accountPubkey ?: return
        if (event.pubkey.value != account) return
        // The account's block head resolved — the bootstrap stops re-asking.
        blockHeadReceived = true
        blockCandidates.add(event)
        val newest = space.bitos.core.model.BlockList.newest(blockCandidates) ?: return
        blockedPubkeys = space.bitos.core.model.BlockList.blockedPubkeys(newest) ?: blockedPubkeys
        publishState()
    }

    /** Current blocked set (privacy settings + unblock publish input). */
    fun blockedAuthors(): Set<String> = blockedPubkeys

    /** Wipes the local event cache (clear-cache, APP-018a row 5). */
    suspend fun clearEventCache() {
        cache.clearAllCache()
    }

    /**
     * APP-009: resolves the tally target — the event's FIRST e-tag when it
     * names a known note in an open thread window (root or any reply), so
     * reply rows carry live deltas too. Returns null when it targets
     * nothing we're tracking.
     */
    private fun tallyTargetFor(eTaggedIds: List<String>): String? {
        for (id in eTaggedIds) {
            if (id in tallyTargets) return id
            // Replies of an open thread: the comment window knows them.
            if (commentThreads.values.any { window -> id in window }) return id
        }
        return null
    }

    private fun mergeTally(target: String, kind: Int, amountMillisats: Long?) {
        talliesBuffer[target] = space.bitos.core.feed.NoteTallies.merge(talliesBuffer[target], kind, amountMillisats)
        space.bitos.core.feed.NoteTallies.evict(tallyTargets, target)
        publishState()
    }

    /** Loads the reply thread for one note (NIP-01 tagged #e filter). */
    fun loadComments(targetEventId: String) {
        space.bitos.core.feed.NoteTallies.evict(tallyTargets, targetEventId)
        if (commentThreads.containsKey(targetEventId)) {
            publishState()
            return
        }
        commentThreads[targetEventId] = LinkedHashMap()
        if (commentThreads.size > COMMENT_TARGETS_MAX) {
            commentThreads.remove(commentThreads.keys.first())
        }
        val filter = COMMENT_FILTER_PREFIX + targetEventId + COMMENT_FILTER_SUFFIX
        pool.broadcast(NostrEventCodec.encodeRequest("bitos-comments-$targetEventId".take(64), filter))
        // NIP-22 companion REQ: uppercase-`E` rooted comments are invisible
        // to the plain `#e` filter (relay tag filters are case-sensitive).
        pool.broadcast(
            NostrEventCodec.encodeRequest(
                "bitos-comments-e-$targetEventId".take(64),
                COMMENT_ROOT_FILTER_PREFIX + targetEventId + COMMENT_FILTER_SUFFIX,
            )
        )
        publishState()
    }

    /** Account lifecycle: non-null activates the Following timeline, null clears it. */
    fun setAccount(pubkey: String?) {
        accountPubkey = pubkey
        followingAuthors.clear()
        contactCandidates.clear()
        bookmarkCandidates.clear()
        bookmarked.clear()
        blockCandidates.clear()
        myReactionEventIds.clear()
        blockedPubkeys = emptySet()
        followingSubscribed = false
        // Shared `AccountBootstrap`: a new account episode re-arms the head
        // re-issue budget and the received flags.
        accountHeadAttempts = IntArray(accountHeadAttempts.size)
        bookmarkHeadReceived = false
        blockHeadReceived = false
        mutableState.value = mutableState.value.copy(
            accountPubkey = pubkey,
            followingResolved = pubkey == null,
        )
        if (pubkey != null) {
            // The signed-in profile is not necessarily an author in the feed.
            // Request its kind-0 head explicitly so the You surface has a
            // fresh projection on a cold start and after account switching.
            requestProfile(pubkey, force = true)
            requestContactHead(pubkey)
            requestBookmarkHead(pubkey)
            requestBlockHead(pubkey)
        }
        publishState()
    }

    // ── One-shot account heads (kind-0 / kind-3 / 30003 / 10004) ──────────
    //
    // A cold start composes these before any relay socket opened and the
    // transport drops them silently; [reissueUnresolvedAccountHeads] asks
    // again on connectivity until each head resolves (shared
    // `AccountBootstrap` budget, reset when connectivity grows).

    private fun requestContactHead(pubkey: String) {
        pool.broadcast(
            NostrEventCodec.encodeRequest(
                "bitos-contacts",
                CONTACT_FILTER_PREFIX + pubkey + CONTACT_FILTER_SUFFIX,
            ),
        )
    }

    private fun requestBookmarkHead(pubkey: String) {
        pool.broadcast(
            NostrEventCodec.encodeRequest(
                "bitos-bookmarks",
                BOOKMARK_FILTER_PREFIX + pubkey + BOOKMARK_FILTER_SUFFIX,
            ),
        )
    }

    private fun requestBlockHead(pubkey: String) {
        NostrEventCodec.encodeBlockListRequest("bitos-blocks", pubkey)?.let(pool::broadcast)
    }

    private fun reissueUnresolvedAccountHeads(connectedRelays: Int) {
        val pubkey = accountPubkey ?: return
        if (AccountBootstrap.shouldReissue(
                resolved = profiles.containsKey(pubkey),
                attempts = accountHeadAttempts[HEAD_PROFILE],
                connectedRelays = connectedRelays,
            )
        ) {
            accountHeadAttempts[HEAD_PROFILE] += 1
            requestProfile(pubkey, force = true)
        }
        if (AccountBootstrap.shouldReissue(
                resolved = mutableState.value.followingResolved,
                attempts = accountHeadAttempts[HEAD_CONTACTS],
                connectedRelays = connectedRelays,
            )
        ) {
            accountHeadAttempts[HEAD_CONTACTS] += 1
            requestContactHead(pubkey)
        }
        if (AccountBootstrap.shouldReissue(
                resolved = bookmarkHeadReceived,
                attempts = accountHeadAttempts[HEAD_BOOKMARKS],
                connectedRelays = connectedRelays,
            )
        ) {
            accountHeadAttempts[HEAD_BOOKMARKS] += 1
            requestBookmarkHead(pubkey)
        }
        if (AccountBootstrap.shouldReissue(
                resolved = blockHeadReceived,
                attempts = accountHeadAttempts[HEAD_BLOCKS],
                connectedRelays = connectedRelays,
            )
        ) {
            accountHeadAttempts[HEAD_BLOCKS] += 1
            requestBlockHead(pubkey)
        }
    }

    private fun subscribe() {
        subscriptionCounter += 1
        val subId = subscriptionId()
        headSubscriptionId = subId
        headExpectedRelays = pool.connectedRelays()
        headEoseRelays.clear()
        initialSnapshotComplete = false
        headSnapshotDeadline?.cancel()
        // Some relays do not send EOSE for a persistent subscription. Treat
        // the bounded initial window as complete after a short deadline so
        // live traffic cannot keep inserting cards indefinitely.
        headSnapshotDeadline = scope.launch {
            delay(HEAD_SNAPSHOT_MAX_WAIT_MS)
            completeInitialSnapshot(subId)
        }
        pool.broadcast(
            NostrEventCodec.encodeRequest(
                subId,
                space.bitos.core.feed.BitzQuery.initialFilters(),
            ),
        )
        mutableState.value = mutableState.value.copy(isLoading = !mutableState.value.hasLoadedAnyEvent)
    }

    private fun subscriptionId() = "bitos-feed-$subscriptionCounter"

    private fun recordHeadEose(subscriptionId: String, relay: RelayUrl) {
        if (subscriptionId != headSubscriptionId) return
        headEoseRelays += relay
        if (headExpectedRelays.isNotEmpty() && headEoseRelays.containsAll(headExpectedRelays)) {
            completeInitialSnapshot(subscriptionId)
        }
    }

    private fun completeInitialSnapshot(subscriptionId: String) {
        if (subscriptionId != headSubscriptionId || initialSnapshotComplete) return
        initialSnapshotComplete = true
        headSnapshotDeadline?.cancel()
        headSnapshotDeadline = null
    }

    private fun absorbNote(event: NostrEvent, fromOlderPage: Boolean = false) {
        val note = FeedNote.from(event)
        if (note.id !in knownNoteIds) {
            knownNoteIds.add(note.id)
            // Once the initial bounded snapshot is complete, every live
            // arrival is held. The UI changes only the small pending pill;
            // it never shifts the reader's list behind their finger.
            val holdLiveArrival = !fromOlderPage && initialSnapshotComplete &&
                aggregator.snapshot().isNotEmpty()
            if (holdLiveArrival) {
                pendingForYou.addLast(note)
                if (pendingForYou.size > PENDING_MAX) pendingForYou.removeFirst()
            } else {
                aggregator.insert(note)
            }
            if (event.pubkey.value in followingAuthors) {
                if (holdLiveArrival && followingWindow.snapshot().isNotEmpty()) {
                    pendingFollowing.addLast(note)
                    if (pendingFollowing.size > PENDING_MAX) pendingFollowing.removeFirst()
                } else {
                    followingWindow.insert(note)
                }
            }
        }
        absorbReply(note)
        enqueueProfile(event.pubkey.value)
        persist(event)
        // APP-007 Chain: by-id fetches for the remix ancestry land here too.
        synchronized(remixChainEvents) {
            remixChainEvents[note.id] = event
            if (remixChainEvents.size > CHAIN_ANCESTORS_MAX) {
                remixChainEvents.remove(remixChainEvents.keys.first())
            }
        }
        // APP-015: keep saved-note bodies for the bookmarks page.
        if (note.id in bookmarked) {
            bookmarkedNoteMap[note.id] = note
            if (bookmarkedNoteMap.size > space.bitos.core.model.BookmarkList.MAX_BOOKMARKS) {
                bookmarkedNoteMap.remove(bookmarkedNoteMap.keys.first())
            }
        }
        publishState()
    }

    /**
     * APP-015 page: re-fetch saved notes that are missing from the local
     * map (newest 100 ids, shared codec REQ); arrivals fill the map via
     * absorbNote and republish.
     */
    fun loadBookmarked() {
        val missing = bookmarked.filter { it !in bookmarkedNoteMap }.takeLast(BOOKMARK_FETCH_MAX)
        if (missing.isNotEmpty()) {
            pool.broadcast(
                NostrEventCodec.encodeRequest(
                    "bitos-saved-notes".take(64),
                    """{"ids":[${missing.joinToString(separator = "\",\"", prefix = "\"", postfix = "\"")}],"limit":$BOOKMARK_FETCH_MAX}""",
                ),
            )
        }
        publishState()
    }

    /**
     * APP-007 Chain sheet: walks the remix ancestry one ancestor at a time
     * (shared `RemixChain` rule — cycle-safe, hex-validated, ≤32) fetching
     * each ancestor's tags with a bounded single-id REQ + wait.
     */
    suspend fun loadRemixChain(
        rootId: String,
        source: space.bitos.core.feed.RemixRules.Source,
    ): space.bitos.core.feed.RemixChain.Outcome =
        space.bitos.core.feed.RemixChain.walk(rootId, source) { id ->
            chainCounter += 1
            pool.broadcast(
                NostrEventCodec.encodeRequest(
                    "bitos-chain-$chainCounter".take(64),
                    """{"ids":["$id"],"limit":1}""",
                ),
            )
            awaitRemixAncestor(id)?.tags
        }

    /** Ancestor note for the sheet rows (tap-through opens its thread). */
    fun remixAncestorNote(id: String): FeedNote? =
        synchronized(remixChainEvents) { remixChainEvents[id] }?.let(FeedNote::from)

    private suspend fun awaitRemixAncestor(id: String): NostrEvent? {
        repeat(CHAIN_AWAIT_POLLS) {
            synchronized(remixChainEvents) { remixChainEvents[id] }?.let { return it }
            kotlinx.coroutines.delay(CHAIN_AWAIT_INTERVAL_MS)
        }
        return null
    }

    // -----------------------------------------------------------------
    // APP-004: filter window + new-notes hold/reveal
    // -----------------------------------------------------------------

    fun selectFilter(filter: FeedFilter) {
        mutableState.value = mutableState.value.copy(filter = filter)
        publishState()
    }

    fun setLikedIds(liked: Set<String>) {
        likedIds = liked
        if (mutableState.value.filter == FeedFilter.LIKED) publishState()
    }

    /** Legacy UI hook. New arrivals are never auto-revealed merely because
     * the reader reached the top; only [revealPendingNotes] changes the list. */
    fun holdNewNotes(@Suppress("UNUSED_PARAMETER") hold: Boolean) = Unit

    fun revealPendingNotes() {
        val pending = pendingFor(mutableState.value.timeline)
        pending.forEach { windowFor(mutableState.value.timeline).insert(it) }
        pending.clear()
        publishState()
    }

    private val knownNoteIds = HashSet<String>(512)
    private val pendingForYou = ArrayDeque<FeedNote>()
    private val pendingFollowing = ArrayDeque<FeedNote>()
    private var likedIds: Set<String> = emptySet()

    private fun pendingFor(timeline: FeedTimeline): ArrayDeque<FeedNote> =
        if (timeline == FeedTimeline.FOLLOWING) pendingFollowing else pendingForYou

    private fun absorbReply(note: FeedNote) {
        // Arrival keys on the direct parent; a nested reply whose parent
        // comment has no open thread falls back to its root tag so the
        // thread the reader opened still receives it (NIP-10 + NIP-22).
        val thread = commentThreads[note.replyTo]
            ?: note.threadRootId?.let { commentThreads[it] }
            ?: return
        thread[note.id] = note
        if (thread.size > COMMENT_PER_TARGET_MAX) thread.remove(thread.keys.first())
    }

    // ── APP-008 poll votes (web `rebuildPoll` parity) ───────────────────

    /** pollId → pubkey → latest vote. Bounded: ≤16 polls × MAX_VOTERS. */
    private val pollVoters = LinkedHashMap<String, LinkedHashMap<String, space.bitos.core.model.PollVote>>()
    private val pollVotes = space.bitos.core.model.PollVotes()
    private val pollVotesRequested = HashSet<String>()

    private fun absorbPollVote(event: NostrEvent) {
        val target = event.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1) ?: return
        val option = event.tags.firstOrNull { it.firstOrNull() == "response" }?.getOrNull(1)?.toIntOrNull() ?: return
        if (option < 0 || option > 255) return
        val voters = pollVoters.getOrPut(target) { LinkedHashMap() }
        if (pollVoters.size > POLL_TARGETS_MAX) pollVoters.remove(pollVoters.keys.first())
        if (voters.size >= space.bitos.core.model.PollVotes.MAX_VOTERS && event.pubkey.value !in voters) return
        pollVotes.absorb(
            voters,
            space.bitos.core.model.PollVote(event.pubkey.value, option, event.createdAt),
        )
        publishState()
    }

    /** One-shot REQ for a poll's kind-1018 votes (called when it renders). */
    fun loadPollVotes(targetEventId: String) {
        if (!pollVotesRequested.add(targetEventId)) return
        pollVoters.getOrPut(targetEventId) { LinkedHashMap() }
        val filter = """{"kinds":[${NostrKinds.POLL_RESPONSE}],"#e":["$targetEventId"],"limit":${space.bitos.core.model.PollVotes.MAX_VOTERS}}"""
        pool.broadcast(NostrEventCodec.encodeRequest("bitos-pollvotes-$targetEventId".take(64), filter))
        publishState()
    }

    /** Optimistic local vote before the kind-1018 relay echo lands. */
    fun applyOptimisticPollVote(pollId: String, optionIndex: Int) {
        val account = accountPubkey ?: return
        val voters = pollVoters.getOrPut(pollId) { LinkedHashMap() }
        pollVotes.absorb(
            voters,
            space.bitos.core.model.PollVote(account, optionIndex, System.currentTimeMillis() / 1_000),
        )
        publishState()
    }

    private fun absorbContactList(event: NostrEvent) {
        val account = accountPubkey ?: return
        if (event.pubkey.value != account) return
        contactCandidates.add(event)
        val newest = ContactList.newest(contactCandidates) ?: return
        followingAuthors.clear()
        followingAuthors.addAll(ContactList.followedPubkeys(newest))
        // A contact list can contain people that have not posted in the
        // current feed window. Resolve their kind-0 metadata for connection
        // surfaces rather than showing anonymous placeholder rows.
        followingAuthors.forEach(::enqueueProfile)
        followingSubscribed = false
        subscribeFollowing()
        mutableState.value = mutableState.value.copy(followingResolved = true)
        publishState()
    }

    private fun subscribeFollowing() {
        if (followingSubscribed || followingAuthors.isEmpty()) return
        followingSubscribed = true
        val authors = followingAuthors.take(ContactList.MAX_FOLLOWS)
            .joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
        val filter = FOLLOWING_FILTER_PREFIX + authors + FOLLOWING_FILTER_SUFFIX
        pool.broadcast(NostrEventCodec.encodeRequest("bitos-following", filter))
    }

    private fun absorbProfile(event: NostrEvent) {
        val metadata = ProfileMetadata.parse(event) ?: return
        val knownAt = profileTimestamps[metadata.pubkey.value] ?: Long.MIN_VALUE
        if (event.createdAt >= knownAt) {
            profiles[metadata.pubkey.value] = metadata
            profileTimestamps[metadata.pubkey.value] = event.createdAt
        }
        persist(event)
        profilesGeneration++
        publishState()
    }

    /** Cold-start hydration: newest cached verified events fill the window. */
    private fun hydrateFromCache() {
        scope.launch {
            val cached = runCatching { cache.recentEvents(EventStoreContract.COLD_START_HYDRATE_LIMIT) }
                .getOrNull().orEmpty()
            cached.forEach { event ->
                when {
                    event.kind == NostrKinds.PROFILE_METADATA -> absorbProfile(event)
                    FeedNote.isFeedKind(event.kind) -> {
                        val note = FeedNote.from(event)
                        aggregator.insert(note)
                        if (event.pubkey.value in followingAuthors) followingWindow.insert(note)
                    }
                }
            }
            if (cached.isNotEmpty()) {
                mutableState.value = mutableState.value.copy(hasLoadedAnyEvent = true, isLoading = false)
                publishState()
            }
            runCatching { cache.pruneToLimit(EventStoreContract.MAX_ROWS) }
        }
    }

    /** Fire-and-forget persistence of verified events; cache failures never break display. */
    private fun persist(event: NostrEvent) {
        scope.launch { runCatching { cache.upsertVerified(event) } }
    }

    private val profileTimestamps = mutableMapOf<String, Long>()

    /** Mentioned pubkeys (NIP-27 profile entities) — names resolve for @display. */
    fun requestMentionProfiles(pubkeys: List<String>) {
        pubkeys.take(PROFILE_BATCH).forEach(::enqueueProfile)
    }

    /**
     * In-app note-reference open (web parity): note1/nevent1/naddr1 tapped
     * in a body → fetch the head by id/coordinate; the caller polls
     * [refNote] until it lands (bounded), then opens the thread sheet.
     */
    fun openNoteReference(raw: String) {
        val ref = space.bitos.core.nostr.EventRefs.parse(raw) ?: return
        when (ref) {
            is space.bitos.core.nostr.EventRef.ById ->
                pool.broadcast(
                    NostrEventCodec.encodeRequest(
                        "bitos-ref-open".take(64),
                        """{"ids":["${ref.id}"],"limit":1}""",
                    ),
                )
            is space.bitos.core.nostr.EventRef.ByCoordinate ->
                pool.broadcast(
                    NostrEventCodec.encodeRequest(
                        "bitos-ref-open".take(64),
                        """{"kinds":[${ref.kind}],"authors":["${ref.pubkey}"],"#d":["${ref.d}"],"limit":1}""",
                    ),
                )
        }
    }

    /** Fetched note for an in-app ref open (null while in flight). */
    fun refNote(raw: String): FeedNote? {
        return when (val ref = space.bitos.core.nostr.EventRefs.parse(raw)) {
            is space.bitos.core.nostr.EventRef.ById -> synchronized(remixChainEvents) { remixChainEvents[ref.id] }
                // Coordinate refs match on kind+author: the newest absorbed
                // event with that shape (the REQ above is limit-1, relay-newest).
            is space.bitos.core.nostr.EventRef.ByCoordinate ->
                synchronized(remixChainEvents) {
                    remixChainEvents.values.lastOrNull { event ->
                        event.kind == ref.kind && event.pubkey.value == ref.pubkey
                    }
                }
            null -> null
        }?.let(FeedNote::from)
    }

    /** Requests a profile head for a native surface outside the feed window. */
    fun requestProfile(pubkey: String, force: Boolean = false) {
        if (pubkey.isBlank()) return
        if (force) {
            profileRequestCounter += 1
            pool.broadcast(bridge.profileRequest("bitos-profile-head-$profileRequestCounter", listOf(pubkey)))
            return
        }
        enqueueProfile(pubkey)
    }

    private fun enqueueProfile(pubkey: String) {
        if (profiles.containsKey(pubkey)) return
        if (pubkey !in profileQueue && pubkey !in requestedProfiles) {
            profileQueue.addLast(pubkey)
            if (profileQueue.size >= PROFILE_BATCH) {
                profileDrainJob?.cancel()
                drainProfiles()
            } else {
                // Debounce small batches so single arrivals do not spam REQs.
                profileDrainJob?.cancel()
                profileDrainJob = scope.launch {
                    delay(PROFILE_DRAIN_DELAY_MS)
                    drainProfiles()
                }
            }
        }
    }

    private fun drainProfiles() {
        val batch = buildList {
            while (size < PROFILE_BATCH && profileQueue.isNotEmpty()) add(profileQueue.removeFirst())
        }
        if (batch.isEmpty()) return
        requestedProfiles.addAll(batch)
        profileRequestCounter += 1
        val primary = pool.primaryRelay()
        val request = bridge.profileRequest("bitos-profiles-$profileRequestCounter", batch)
        if (primary == null) pool.broadcast(request) else pool.sendTo(listOf(primary), request)

        val fallback = scope.launch {
            delay(PROFILE_FALLBACK_DELAY_MS)
            val unresolved = batch.filterNot(profiles::containsKey)
            val relays = pool.fallbackRelays(primary)
            if (unresolved.isNotEmpty() && relays.isNotEmpty()) {
                profileRequestCounter += 1
                pool.sendTo(relays, bridge.profileRequest("bitos-profiles-fallback-$profileRequestCounter", unresolved))
            }
        }
        profileFallbackJobs += fallback
    }

    private fun publishState() {
        val relayStates = pool.states
        val hiddenSet = mutedPubkeys + blockedPubkeys
        val filter = mutableState.value.filter
        val ownPubkey = accountPubkey
        val liked = likedIds
        val protocolNotesVisible = showProtocolNotes()
        val forYouWindow = aggregator.snapshot()
        val rankedForYou = algorithm?.let { snapshot ->
            FeedRanking.rank(
                notes = forYouWindow,
                surface = AlgorithmSurface.FEED,
                snapshot = snapshot,
                ctx = RankingContext(
                    nowSeconds = System.currentTimeMillis() / 1_000,
                    following = followingAuthors,
                    zapCounts = zapCounts.toMap(),
                    replyCounts = commentThreads.mapValues { it.value.size },
                    dismissedNoteIds = dismissedNoteIds,
                    mutedAuthors = demotedAuthors,
                    mutedTags = demotedTags,
                ),
            )
        } ?: forYouWindow
        val followingSnapshot = followingWindow.snapshot()
        val allWindow: (List<FeedNote>) -> Int = { window ->
            window.count {
                it.pubkey !in hiddenSet &&
                    it.id !in dismissedNoteIds &&
                    (protocolNotesVisible || !it.isProtocolPayload)
            }
        }
        mutableState.value = mutableState.value.copy(
            notes = (if (mutableState.value.timeline == FeedTimeline.FOLLOWING) {
                followingSnapshot
            } else {
                rankedForYou
            }).filter {
                it.pubkey !in hiddenSet &&
                    it.id !in dismissedNoteIds &&
                    FeedFilters.passes(it, filter, ownPubkey, liked, protocolNotesVisible)
            },
            pendingNotes = pendingFor(mutableState.value.timeline).toList(),
            forYouCount = allWindow(forYouWindow),
            followingCount = allWindow(followingSnapshot),
            profiles = run {
                if (cachedProfilesGeneration != profilesGeneration) {
                    cachedProfilesSnapshot = profiles.toMap()
                    cachedProfilesGeneration = profilesGeneration
                }
                cachedProfilesSnapshot
            },
            comments = commentThreads.mapValues { (rootId, replies) ->
                val size = replies.size
                threadCache[rootId]?.takeIf { it.size == size }?.comments
                    ?: run {
                        val noteList = replies.values.toList()
                        val items = space.bitos.core.feed.ThreadAssembly.assemble(rootId, noteList)
                        threadCache[rootId] = ThreadCache(size, noteList, items)
                        noteList
                    }
            },
            threads = commentThreads.mapValues { (rootId, replies) ->
                val size = replies.size
                threadCache[rootId]?.takeIf { it.size == size }?.items
                    ?: space.bitos.core.feed.ThreadAssembly.assemble(rootId, replies.values.toList()).also {
                        val noteList = replies.values.toList()
                        threadCache[rootId] = ThreadCache(size, noteList, it)
                    }
            }.also { threadCache.keys.retainAll(commentThreads.keys) },
            following = followingAuthors.toSet(),
            bookmarkedIds = bookmarked.toSet(),
            bookmarkedNotes = if (bookmarked.isEmpty()) {
                emptyList()
            } else {
                val byId = aggregator.snapshot().associateBy { it.id }
                bookmarked.toList().asReversed().mapNotNull { id -> bookmarkedNoteMap[id] ?: byId[id] }
            },
            zapCounts = zapCounts.toMap(),
            zapRequestIds = zapRequestIdsBuffer.mapValues { it.value.toSet() },
            tallies = talliesBuffer.toMap(),
            myReactionEventIds = myReactionEventIds.toMap(),
            pollTallies = pollVoters.mapValues { (_, voters) ->
                pollVotes.tally(voters, ownPubkey)
            },
            muted = mutedPubkeys,
            blocked = blockedPubkeys,
            relayHealth = RelayHealth(
                connected = relayStates.values.count { it == RelayConnectionState.CONNECTED },
                total = relayStates.size,
            ),
            isLoading = false,
            hasLoadedAnyEvent = true,
            isLoadingOlder = loadingOlderTimeline == mutableState.value.timeline,
            noMoreOlder = olderLanes.getValue(mutableState.value.timeline).exhausted,
        )
    }

    private companion object {
        const val PENDING_MAX = 50
        /** Persistent subscriptions may omit EOSE; bound initial catch-up. */
        const val HEAD_SNAPSHOT_MAX_WAIT_MS = 2_500L

        /** Indexes into [accountHeadAttempts] (shared `AccountBootstrap`). */
        const val HEAD_PROFILE = 0
        const val HEAD_CONTACTS = 1
        const val HEAD_BOOKMARKS = 2
        const val HEAD_BLOCKS = 3
        const val PROFILE_BATCH = 48
        const val PROFILE_DRAIN_DELAY_MS = 250L
        const val PROFILE_FALLBACK_DELAY_MS = 900L
        const val OLDER_WINDOW_MAX = 200
        const val OLDER_WATCHDOG_MS = 8_000L
        const val OLDER_SUBSCRIPTION_PREFIX = "bitos-older-"

        /** Two consecutive all-duplicate pages = relays exhausted for now. */
        const val OLDER_EMPTY_EXHAUST = 2
        const val CONTACT_FILTER_PREFIX = """{"kinds":[3],"authors":["""
        const val CONTACT_FILTER_SUFFIX = """],"limit":1}"""
        const val FOLLOWING_FILTER_PREFIX = """{"kinds":[1,21,22],"authors":["""
        const val FOLLOWING_FILTER_SUFFIX = """],"limit":40}"""
        const val COMMENT_FILTER_PREFIX = """{"kinds":[1,1111,7,6,9735],"#e":["""
    /** NIP-22 companion: kind-1111 comments root-tagged with UPPERCASE `E`. */
    const val COMMENT_ROOT_FILTER_PREFIX = """{"kinds":[1111],"#E":["""
        const val BOOKMARK_FILTER_PREFIX = """{"kinds":[30003],"authors":["""
        const val BOOKMARK_FILTER_SUFFIX = """],"#d":[""],"limit":1}"""
        const val ZAP_FILTER_PREFIX = """{"kinds":[9735],"#e":["""
        const val ZAP_FILTER_SUFFIX = """],"limit":50}"""
        const val ZAP_TARGETS_MAX = 16
        const val COMMENT_FILTER_SUFFIX = """],"limit":50}"""
        const val COMMENT_TARGETS_MAX = 16
        /** APP-008 poll vote window bound (matches the thread cache). */
        const val POLL_TARGETS_MAX = 16
        const val COMMENT_PER_TARGET_MAX = 100

        /** APP-007 Chain: ancestor cache + per-hop fetch wait (3 s). */
        const val CHAIN_ANCESTORS_MAX = 48
        const val CHAIN_AWAIT_POLLS = 20
        const val CHAIN_AWAIT_INTERVAL_MS = 150L

        /** APP-015: by-id re-fetch bound for the bookmarks page. */
        const val BOOKMARK_FETCH_MAX = 100
    }
}

/** Default public relays, mirroring the web client's discovery set. */
object DefaultRelays {
    val urls: List<RelayUrl> = listOf(
        "wss://nostr-01.yakihonne.com",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
    ).mapNotNull(RelayUrl::parse)

    /** Write-capable subset (nostr.band is read-only in the web client). */
    val writeUrls: List<RelayUrl> = listOf(
        "wss://nostr-01.yakihonne.com",
        "wss://relay.damus.io",
        "wss://nos.lol",
    ).mapNotNull(RelayUrl::parse)
}
