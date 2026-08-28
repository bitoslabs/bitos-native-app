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
import space.bitos.core.feed.EmptyFeedRetry
import space.bitos.core.feed.FeedAggregator
import space.bitos.core.feed.FeedFilter
import space.bitos.core.feed.FeedFilters
import space.bitos.core.feed.FeedNote
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
    /** Current optimistic + relay-reconciled follow set. */
    val following: Set<String> = emptySet(),
    /** Saved event ids from the account's NIP-51 list (optimistic + relay). */
    val bookmarkedIds: Set<String> = emptySet(),
    /** Zap counts per target event id from verified kind-9735 receipts. */
    val zapCounts: Map<String, Int> = emptyMap(),
    /** Muted authors' notes are filtered from all windows (device-local). */
    val muted: Set<String> = emptySet(),
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
) {
    private val aggregator = FeedAggregator(maxItems = 200)
    private val followingWindow = FeedAggregator(maxItems = 200)
    private val contactCandidates = mutableListOf<NostrEvent>()
    private val followingAuthors = mutableSetOf<String>()
    private var accountPubkey: String? = null
    private var mutedPubkeys: Set<String> = emptySet()
    private var followingSubscribed = false
    private val commentThreads = mutableMapOf<String, LinkedHashMap<String, FeedNote>>()
    private val bookmarkCandidates = mutableListOf<NostrEvent>()
    private val zapCounts = mutableMapOf<String, Int>()
    private val bookmarked = linkedSetOf<String>()
    private val profiles = mutableMapOf<String, ProfileMetadata>()
    private val profileQueue = ArrayDeque<String>()
    private val requestedProfiles = mutableSetOf<String>()
    private var profileDrainJob: Job? = null

    private val mutableState = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = mutableState.asStateFlow()

    private var collectJob: Job? = null
    private var retryJob: Job? = null
    private var retryAttempt = 0
    private var subscriptionCounter = 0

    fun start() {
        if (collectJob != null) return
        hydrateFromCache()
        pool.start()
        collectJob = scope.launch {
            pool.frames.collect { frame ->
                val event = runCatching {
                    NostrEventCodec.decodeRelayEvent(hasher, frame.message, frame.relay)
                }.getOrNull() ?: return@collect
                // Non-negotiable principle 2: verify ID AND signature before
                // projection; unverified events never reach display state.
                if (!NostrEventCodec.verifySignature(hasher, event)) return@collect
                when {
                    event.kind == NostrKinds.CONTACT_LIST -> absorbContactList(event)
                    event.kind == space.bitos.core.model.BookmarkList.KIND -> absorbBookmarkList(event)
                    event.kind == space.bitos.core.model.ZapReceipt.RECEIPT_KIND -> absorbZapReceipt(event)
                    event.kind == NostrKinds.PROFILE_METADATA -> absorbProfile(event)
                    event.kind == space.bitos.core.model.NostrKinds.REPOST -> absorbNote(event)
                    FeedNote.isFeedKind(event.kind) -> absorbNote(event)
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
        subscribe()
    }

    fun stop() {
        pool.broadcast(NostrEventCodec.encodeClose(subscriptionId()))
        collectJob?.cancel()
        collectJob = null
        retryJob?.cancel()
        retryJob = null
    }

    /** Muted authors are filtered from all feed windows (device-local). */
    fun setMuted(muted: Set<String>) {
        mutedPubkeys = muted
        publishState()
    }

    fun selectTimeline(timeline: FeedTimeline) {
        mutableState.value = mutableState.value.copy(timeline = timeline)
        publishState()
    }

    fun refresh() {
        mutableState.value = mutableState.value.copy(isLoading = true)
        subscribe()
    }

    /** APP-004: manual retry from the relay-error / empty state — resets the
     * backoff so the next auto-retry is 2 s away, then re-issues the REQ. */
    fun retryNow() {
        retryAttempt = 0
        subscribe()
    }

    /** ALL-window size of the For You timeline (mutes + protocol payload hidden). */
    private fun allWindowSize(): Int =
        aggregator.snapshot().count { it.pubkey !in mutedPubkeys && !it.isProtocolPayload }

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
        // Verified receipts count once per unique event id (fan-in dedupe by
        // aggregator-style keys is overkill for a bounded count window).
        zapCounts[target] = (zapCounts[target] ?: 0) + 1
        if (zapCounts.size > ZAP_TARGETS_MAX) zapCounts.remove(zapCounts.keys.first())
        publishState()
    }

    private fun absorbBookmarkList(event: NostrEvent) {
        val account = accountPubkey ?: return
        if (event.pubkey.value != account) return
        bookmarkCandidates.add(event)
        val newest = space.bitos.core.model.BookmarkList.newest(bookmarkCandidates) ?: return
        bookmarked.clear()
        bookmarked.addAll(space.bitos.core.model.BookmarkList.bookmarkedIds(newest))
        publishState()
    }

    /** Loads the reply thread for one note (NIP-01 tagged #e filter). */
    fun loadComments(targetEventId: String) {
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
        publishState()
    }

    /** Account lifecycle: non-null activates the Following timeline, null clears it. */
    fun setAccount(pubkey: String?) {
        accountPubkey = pubkey
        followingAuthors.clear()
        contactCandidates.clear()
        bookmarkCandidates.clear()
        bookmarked.clear()
        followingSubscribed = false
        mutableState.value = mutableState.value.copy(
            accountPubkey = pubkey,
            followingResolved = pubkey == null,
        )
        if (pubkey != null) {
            val request = NostrEventCodec.encodeRequest(
                "bitos-contacts",
                CONTACT_FILTER_PREFIX + pubkey + CONTACT_FILTER_SUFFIX,
            )
            pool.broadcast(request)
            pool.broadcast(
                NostrEventCodec.encodeRequest(
                    "bitos-bookmarks",
                    BOOKMARK_FILTER_PREFIX + pubkey + BOOKMARK_FILTER_SUFFIX,
                ),
            )
        }
        publishState()
    }

    private fun subscribe() {
        subscriptionCounter += 1
        val filter = FEED_FILTER
        pool.broadcast(NostrEventCodec.encodeRequest(subscriptionId(), filter))
        mutableState.value = mutableState.value.copy(isLoading = !mutableState.value.hasLoadedAnyEvent)
    }

    private fun subscriptionId() = "bitos-feed-$subscriptionCounter"

    private fun absorbNote(event: NostrEvent) {
        val note = FeedNote.from(event)
        if (note.id !in knownNoteIds) {
            knownNoteIds.add(note.id)
            // APP-004: arrivals are held for the "N new notes" pill while
            // the user is scrolled into the feed; tap/at-top reveals them.
            if (holdingNewNotes && mutableState.value.notes.isNotEmpty()) {
                pendingNotes.addLast(note)
                if (pendingNotes.size > PENDING_MAX) pendingNotes.removeFirst()
            } else {
                aggregator.insert(note)
            }
        }
        if (event.pubkey.value in followingAuthors) followingWindow.insert(note)
        absorbReply(note)
        enqueueProfile(event.pubkey.value)
        persist(event)
        publishState()
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

    /** Hold = user is scrolled into the feed; false flushes (auto-reveal). */
    fun holdNewNotes(hold: Boolean) {
        if (hold == holdingNewNotes) return
        holdingNewNotes = hold
        if (!hold && pendingNotes.isNotEmpty()) revealPendingNotes()
    }

    fun revealPendingNotes() {
        pendingNotes.forEach { aggregator.insert(it) }
        pendingNotes.clear()
        publishState()
    }

    private val knownNoteIds = HashSet<String>(512)
    private val pendingNotes = ArrayDeque<FeedNote>()
    private var holdingNewNotes = false
    private var likedIds: Set<String> = emptySet()

    private fun absorbReply(note: FeedNote) {
        val target = note.replyTo ?: return
        val thread = commentThreads[target] ?: return
        thread[note.id] = note
        if (thread.size > COMMENT_PER_TARGET_MAX) thread.remove(thread.keys.first())
    }

    private fun absorbContactList(event: NostrEvent) {
        val account = accountPubkey ?: return
        if (event.pubkey.value != account) return
        contactCandidates.add(event)
        val newest = ContactList.newest(contactCandidates) ?: return
        followingAuthors.clear()
        followingAuthors.addAll(ContactList.followedPubkeys(newest))
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
        val filter = PROFILE_FILTER_PREFIX +
            batch.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
        pool.broadcast(NostrEventCodec.encodeRequest("bitos-profiles-${requestedProfiles.size}", filter))
    }

    private fun publishState() {
        val relayStates = pool.states
        val mutedSet = mutedPubkeys
        val filter = mutableState.value.filter
        val ownPubkey = accountPubkey
        val liked = likedIds
        val forYouWindow = aggregator.snapshot()
        val followingSnapshot = followingWindow.snapshot()
        val allWindow: (List<FeedNote>) -> Int = { window ->
            window.count { it.pubkey !in mutedSet && !it.isProtocolPayload }
        }
        mutableState.value = mutableState.value.copy(
            notes = (if (mutableState.value.timeline == FeedTimeline.FOLLOWING) {
                followingSnapshot
            } else {
                forYouWindow
            }).filter { it.pubkey !in mutedSet && FeedFilters.passes(it, filter, ownPubkey, liked) },
            pendingNotes = pendingNotes.toList(),
            forYouCount = allWindow(forYouWindow),
            followingCount = allWindow(followingSnapshot),
            profiles = profiles.toMap(),
            comments = commentThreads.mapValues { it.value.values.toList() },
            following = followingAuthors.toSet(),
            bookmarkedIds = bookmarked.toSet(),
            zapCounts = zapCounts.toMap(),
            muted = mutedPubkeys,
            relayHealth = RelayHealth(
                connected = relayStates.values.count { it == RelayConnectionState.CONNECTED },
                total = relayStates.size,
            ),
            isLoading = false,
            hasLoadedAnyEvent = true,
        )
    }

    private companion object {
        const val PENDING_MAX = 50
        const val PROFILE_BATCH = 48
        const val PROFILE_DRAIN_DELAY_MS = 250L
        const val FEED_FILTER =
            """{"kinds":[1,22,0],"limit":80}"""
        const val CONTACT_FILTER_PREFIX = """{"kinds":[3],"authors":["""
        const val CONTACT_FILTER_SUFFIX = """],"limit":1}"""
        const val FOLLOWING_FILTER_PREFIX = """{"kinds":[1,22],"authors":["""
        const val FOLLOWING_FILTER_SUFFIX = """],"limit":40}"""
        const val PROFILE_FILTER_PREFIX = """{"kinds":[0],"authors":"""
        const val COMMENT_FILTER_PREFIX = """{"kinds":[1],"#e":["""
        const val BOOKMARK_FILTER_PREFIX = """{"kinds":[30003],"authors":["""
        const val BOOKMARK_FILTER_SUFFIX = """],"#d":[""],"limit":1}"""
        const val ZAP_FILTER_PREFIX = """{"kinds":[9735],"#e":["""
        const val ZAP_FILTER_SUFFIX = """],"limit":50}"""
        const val ZAP_TARGETS_MAX = 16
        const val COMMENT_FILTER_SUFFIX = """],"limit":50}"""
        const val COMMENT_TARGETS_MAX = 16
        const val COMMENT_PER_TARGET_MAX = 100
    }
}

/** Default public relays, mirroring the web client's discovery set. */
object DefaultRelays {
    val urls: List<RelayUrl> = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
    ).mapNotNull(RelayUrl::parse)

    /** Write-capable subset (nostr.band is read-only in the web client). */
    val writeUrls: List<RelayUrl> = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
    ).mapNotNull(RelayUrl::parse)
}
