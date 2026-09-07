package space.bitos.app.ui.feed

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import space.bitos.app.data.feed.FeedRepository
import space.bitos.app.data.feed.FeedTimeline
import space.bitos.app.data.feed.FeedUiState

/**
 * The immutable inputs that can change a rendered home-feed card or footer.
 *
 * Feed chrome (the pending-notes pill, relay status, timeline tabs) changes
 * more often than cards do. Keeping this projection separate lets Compose
 * skip the lazy list on chrome-only StateFlow emissions.
 */
@Immutable
data class HomeFeedContentState(
    val notes: List<space.bitos.core.feed.FeedNote> = emptyList(),
    val profiles: Map<String, space.bitos.core.model.ProfileMetadata> = emptyMap(),
    val bookmarkedIds: Set<String> = emptySet(),
    val pollTallies: Map<String, space.bitos.core.model.PollTally> = emptyMap(),
    val isLoadingOlder: Boolean = false,
    val noMoreOlder: Boolean = false,
)

/**
 * Native feature store for the Home surface. Owns UI-only state (local
 * optimistic action toggles); product rules stay in the shared core and
 * repository. Never touches sockets or the database directly.
 */
class HomeViewModel(
    private val repository: FeedRepository,
    private val notePublisher: space.bitos.app.data.publish.NotePublisher? = null,
    private val identityViewModel: space.bitos.app.identity.IdentityViewModel? = null,
    private val notifications: space.bitos.app.data.feed.NotificationRepository? = null,
    private val muteStore: space.bitos.app.data.feed.MuteStore? = null,
    private val sentZaps: space.bitos.app.data.zap.SentZapsStore? = null,
    private val interactionProfile: space.bitos.app.data.feed.InteractionProfileStore? = null,
    private val hashtagFollowsStore: space.bitos.app.data.feed.HashtagFollowsStore? = null,
) : ViewModel() {

    private val mutableLocalActions = MutableStateFlow(LocalActions())
    private val mutableZap = MutableStateFlow(space.bitos.app.ui.feed.ZapUiState())
    val zapState: StateFlow<space.bitos.app.ui.feed.ZapUiState> = mutableZap.asStateFlow()
    private val lnurlClient = space.bitos.app.data.zap.LnurlPayClient()
    private var verifyJob: kotlinx.coroutines.Job? = null
    val localActions: StateFlow<LocalActions> = mutableLocalActions.asStateFlow()

    // APP-007 Chain sheet: one walk at a time, keyed to the tapped note.
    private val mutableRemixChain = MutableStateFlow(RemixChainUiState())
    val remixChainState: StateFlow<RemixChainUiState> = mutableRemixChain.asStateFlow()
    private var chainJob: kotlinx.coroutines.Job? = null

    val state: StateFlow<FeedUiState> = repository.state

    /**
     * Profile-only projection for shell consumers such as Chats and the
     * author-zap sheet. Keeping them off the full [FeedUiState] prevents a
     * note, pagination, relay-health, or tally update from invalidating the
     * top-level navigation tree.
     */
    val feedProfiles: StateFlow<Map<String, space.bitos.core.model.ProfileMetadata>> = repository.state
        .map { state -> state.profiles }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            // Keep the latest small profile map ready so opening Chats or a
            // zap sheet never flashes the construction-time empty value.
            started = SharingStarted.Eagerly,
            initialValue = repository.state.value.profiles,
        )

    /**
     * Card-list projection. `distinctUntilChanged` retains the last emitted
     * instance during pending-pill/relay-health updates, so the keyed
     * LazyColumn receives stable inputs and can skip its rows.
     */
    val feedContentState: StateFlow<HomeFeedContentState> = repository.state
        .map { state ->
            HomeFeedContentState(
                notes = state.notes,
                profiles = state.profiles,
                bookmarkedIds = state.bookmarkedIds,
                pollTallies = state.pollTallies,
                isLoadingOlder = state.isLoadingOlder,
                noMoreOlder = state.noMoreOlder,
            )
        }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = HomeFeedContentState(
                notes = repository.state.value.notes,
                profiles = repository.state.value.profiles,
                bookmarkedIds = repository.state.value.bookmarkedIds,
                pollTallies = repository.state.value.pollTallies,
                isLoadingOlder = repository.state.value.isLoadingOlder,
                noMoreOlder = repository.state.value.noMoreOlder,
            ),
        )

    init {
        repository.start()
        // Account changes drive the Following timeline and the inbox.
        viewModelScope.launch {
            identityViewModel?.state?.collect { state ->
                repository.setAccount(state.account?.pubkeyHex)
                notifications?.setAccount(state.account?.pubkeyHex)
                hashtagFollowsStore?.setAccount(state.account?.pubkeyHex)
            }
        }
        // Mutes filter all feed windows.
        viewModelScope.launch {
            muteStore?.muted?.collect { muted -> repository.setMuted(muted) }
        }
        // Local ranking signals (hide + demotions) drive the shared ranker.
        viewModelScope.launch {
            val profile = interactionProfile ?: return@launch
            combine(profile.dismissedNotes, profile.demotedAuthors, profile.demotedTags) { dismissed, authors, tags ->
                Triple(dismissed, authors, tags)
            }.collect { (dismissed, authors, tags) ->
                repository.setInteractionProfile(dismissed, authors, tags)
            }
        }
    }

    fun selectTimeline(timeline: FeedTimeline) {
        repository.selectTimeline(timeline)
    }

    /** APP-007 Chain: walk the remix ancestry of [note] (shared rule). */
    fun loadRemixChain(note: space.bitos.core.feed.FeedNote) {
        val sourceId = note.remixOfEventId ?: return
        val source = space.bitos.core.feed.RemixRules.Source(sourceId, note.remixOfPubkey, emptyList())
        chainJob?.cancel()
        mutableRemixChain.value = RemixChainUiState(isLoading = true, rootId = note.id)
        chainJob = viewModelScope.launch {
            val outcome = repository.loadRemixChain(note.id, source)
            mutableRemixChain.value = RemixChainUiState(isLoading = false, rootId = note.id, outcome = outcome)
        }
    }

    /** Ancestor lookup for sheet row tap-through (opens its thread). */
    fun remixAncestorNote(id: String): space.bitos.core.feed.FeedNote? = repository.remixAncestorNote(id)

    /** Card ⋯ raw-event viewer (NIP-01 canonical object; null out of window). */
    fun rawEventJson(eventId: String): String? = repository.rawEventJson(eventId)

    /** APP-015: re-fetch saved notes missing from the local map. */
    fun loadBookmarked() = repository.loadBookmarked()

    // APP-004: content-filter window + new-notes hold/reveal.
    fun selectFilter(filter: space.bitos.core.feed.FeedFilter) = repository.selectFilter(filter)

    fun holdNewNotes(hold: Boolean) = repository.holdNewNotes(hold)

    fun refresh() {
        repository.refresh()
    }

    /** APP-004: manual retry from the relay-error / empty state. */
    fun retryNow() {
        repository.retryNow()
    }

    /** APP-004: infinite-scroll pagination — one older page at a time. */
    fun loadOlder() {
        repository.loadOlder()
    }

    /** In-app note-ref open (delegates the bounded fetch to the repository). */
    fun openNoteReference(raw: String) = repository.openNoteReference(raw)

    /**
     * APP-006: kind-0 metadata for story authors (web `profiles.ensure`
     * parity) — display names + pictures in the bar, viewer and activity
     * sheet. Bounded by the repository's batch cap.
     */
    fun requestStoryAuthorProfiles(pubkeys: List<String>) {
        if (pubkeys.isEmpty()) return
        repository.requestMentionProfiles(pubkeys)
    }

    /** Fetched note for an in-app ref open (null while in flight). */
    fun refNote(raw: String): space.bitos.core.feed.FeedNote? = repository.refNote(raw)

    fun toggleLike(note: space.bitos.core.feed.FeedNote) {        val turningOn = note.id !in mutableLocalActions.value.liked
        mutableLocalActions.value = mutableLocalActions.value.copy(liked = toggle(mutableLocalActions.value.liked, note.id))
        repository.setLikedIds(mutableLocalActions.value.liked)
        if (notePublisher == null || identityViewModel == null) return
        // Signed accounts publish a real kind-7 reaction on like; an unlike
        // deletes my reaction event (kind-5, web `unlikeNote` parity).
        if (turningOn) {
            notePublisher.publishReactionWith(
                targetEventId = note.id,
                targetPubkey = note.pubkey,
                signerProvider = { identityViewModel.createSigner() },
                writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
            )
        } else {
            val reactionId = repository.state.value.myReactionEventIds[note.id] ?: return
            notePublisher.publishDeletion(
                targetEventIds = listOf(reactionId),
                signerProvider = { identityViewModel.createSigner() },
                writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
            )
        }
    }

    /**
     * APP-009 reply bar publish (legacy `publishReply` wire): content join
     * for attachments + shared `replyTags` (both NIP-10 markers, participant
     * p-tags, content entities) through the tags-aware note path, with the
     * optional pre-mined PoW nonce. Optimistic append happens on relay ACK.
     */
    fun reply(
        text: String,
        note: space.bitos.core.feed.FeedNote,
        attachments: List<String> = emptyList(),
        pow: space.bitos.app.ui.components.PowOutcome? = null,
    ) {
        if (notePublisher == null || identityViewModel == null) return
        val content = space.bitos.core.publish.ComposerRules.composeContent(text, attachments)
        if (content.isBlank()) return
        // Root resolution: the target's own root marker, else the target IS
        // the thread root (a root reply repeats the id in both markers).
        val rootEventId = note.threadRootId ?: note.id
        val tags = space.bitos.core.publish.NoteComposer.replyTags(
            rootEventId = rootEventId,
            targetEventId = note.id,
            targetPubkey = note.pubkey,
            targetPTags = note.mentions,
            content = content,
        ) ?: return
        if (pow != null) {
            notePublisher.publishPowNoteWith(
                content, tags, pow.nonce, pow.targetDifficulty, pow.createdAtSeconds,
                { identityViewModel.createSigner() }, space.bitos.app.data.feed.DefaultRelays.writeUrls,
            )
        } else {
            notePublisher.publishNoteWith(
                content, tags,
                { identityViewModel.createSigner() }, space.bitos.app.data.feed.DefaultRelays.writeUrls,
            )
        }
    }

    /**
     * NIP-22 comment (kind 1111) on a non-kind-1 event — web `feed.comment`
     * parity: the composer switches automatically for media targets. [root]
     * is the commented event; [parent] is the comment being answered (null
     * for top-level). [pow] carries a pre-mined nonce over the same tags.
     */
    fun comment(
        text: String,
        root: space.bitos.core.feed.FeedNote,
        parent: space.bitos.core.feed.FeedNote?,
        attachments: List<String> = emptyList(),
        pow: space.bitos.app.ui.components.PowOutcome? = null,
    ) {
        if (notePublisher == null || identityViewModel == null) return
        if (root.kind == space.bitos.core.model.NostrKinds.SHORT_TEXT_NOTE) return
        val content = space.bitos.core.publish.ComposerRules.composeContent(text, attachments)
        if (content.isBlank()) return
        val tags = space.bitos.core.publish.NoteComposer.commentTags(
            targetEventId = root.id,
            targetPubkey = root.pubkey,
            targetKind = root.kind,
            parentEventId = parent?.id?.takeIf { it != root.id },
            parentPubkey = parent?.pubkey?.takeIf { parent.id != root.id },
            content = content,
        ) ?: return
        if (pow != null) {
            notePublisher.publishPowCommentWith(
                content, tags, pow.nonce, pow.targetDifficulty, pow.createdAtSeconds,
                { identityViewModel.createSigner() }, space.bitos.app.data.feed.DefaultRelays.writeUrls,
            )
        } else {
            notePublisher.publishCommentWith(
                content, tags,
                { identityViewModel.createSigner() }, space.bitos.app.data.feed.DefaultRelays.writeUrls,
            )
        }
    }

    /** Follow/unfollow: optimistic local flip + kind-3 publish when signed in. */
    fun toggleFollow(author: String) {
        val following = repository.state.value.following
        val updated = repository.applyFollowChange(author, add = author !in following) ?: return
        if (notePublisher != null && identityViewModel != null) {
            notePublisher.publishFollowList(
                follows = updated,
                signerProvider = { identityViewModel.createSigner() },
                writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
            )
        }
    }

    /** Kind-6 repost through the publish machine (SOC-003). */
    fun repost(note: space.bitos.core.feed.FeedNote) {
        if (notePublisher == null || identityViewModel == null) return
        notePublisher.publishRepostWith(
            targetEventId = note.id,
            targetPubkey = note.pubkey,
            signerProvider = { identityViewModel.createSigner() },
            writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
        )
    }

    /** Kind-5 deletion of one of the account's own notes/comments (NIP-09). */
    fun deleteNote(note: space.bitos.core.feed.FeedNote) {
        val account = identityViewModel?.state?.value?.account ?: return
        if (note.pubkey != account.pubkeyHex) return
        if (notePublisher == null || identityViewModel == null) return
        notePublisher.publishDeletion(
            targetEventIds = listOf(note.id),
            signerProvider = { identityViewModel.createSigner() },
            writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
        )
    }

    fun selectZapAmount(sats: Long) {
        mutableZap.value = mutableZap.value.copy(amountSats = sats)
    }

    /** APP-014: snapshot of the local sent-zap ledger (inbox zap-out rows). */
    fun sentZapRecords(): List<space.bitos.core.model.SentZapRecord> =
        sentZaps?.load() ?: emptyList()

    /** APP-014: record a paid zap into the local sent ledger. */
    fun onZapPaid(note: space.bitos.core.feed.FeedNote, amountSats: Long, memo: String = "") {
        val store = sentZaps ?: return
        store.record(
            space.bitos.core.model.SentZapRecord(
                id = mutableZap.value.requestId
                    ?: note.id + ":" + amountSats + ":" + (System.currentTimeMillis() / 1000),
                amountSats = amountSats,
                recipientPubkey = note.pubkey,
                createdAt = System.currentTimeMillis() / 1000,
                targetNoteId = note.id,
                memo = memo.takeIf { it.isNotBlank() },
            ),
        )
        currentZapRequestId = null
    }

    /** Records a paid profile zap (no target note) into the sent ledger. */
    fun onAuthorZapPaid(recipientPubkey: String, amountSats: Long, memo: String = "") {
        val store = sentZaps ?: return
        store.record(
            space.bitos.core.model.SentZapRecord(
                id = mutableZap.value.requestId
                    ?: recipientPubkey + ":" + amountSats + ":" + (System.currentTimeMillis() / 1000),
                amountSats = amountSats,
                recipientPubkey = recipientPubkey,
                createdAt = System.currentTimeMillis() / 1000,
                targetNoteId = null,
                memo = memo.takeIf { it.isNotBlank() },
            ),
        )
        currentZapRequestId = null
    }

    fun dismissZap() {
        mutableZap.value = space.bitos.app.ui.feed.ZapUiState()
    }

    /** LNURL flow: params → signed 9734 → invoice. Failures surface; never crashes UI. */
    fun zap(note: space.bitos.core.feed.FeedNote, comment: String = "", anonymous: Boolean = false) {
        val profile = repository.state.value.profiles[note.pubkey]
        zapRecipient(note.pubkey, profile?.lud16, note.id, comment, anonymous)
    }

    /**
     * Profile zap (NIP-57 p-tag only): the same LNURL flow without a target
     * note — the 9734 request carries only the recipient `p` tag.
     */
    fun zapAuthor(recipientPubkey: String, lud16: String?, comment: String = "", anonymous: Boolean = false) {
        zapRecipient(recipientPubkey, lud16, null, comment, anonymous)
    }

    // ── APP-006 story engagement (web `stories.like/unlike/reply` parity) ──

    /** Story like: kind 7 ❤️ with e/p/a target tags. */
    fun likeStorySlide(slide: space.bitos.core.model.StorySlide) {
        if (notePublisher == null || identityViewModel == null) return
        notePublisher.publishReactionWithTags(
            emoji = "❤️",
            tags = space.bitos.core.model.StoriesInteractions.targetTags(slide),
            signerProvider = { identityViewModel.createSigner() },
            writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
        )
    }

    /** Story unlike: kind-5 delete of my reaction event. */
    fun unlikeStorySlide(myLikeEventId: String) {
        if (notePublisher == null || identityViewModel == null) return
        notePublisher.publishDeletion(
            targetEventIds = listOf(myLikeEventId),
            signerProvider = { identityViewModel.createSigner() },
            writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
        )
    }

    /** Story reply: kind 1 with e/p/a `reply`-marker tags. */
    fun replyToStorySlide(slide: space.bitos.core.model.StorySlide, text: String) {
        if (notePublisher == null || identityViewModel == null) return
        val content = text.trim()
        if (content.isEmpty()) return
        notePublisher.publishNoteWith(
            content, space.bitos.core.model.StoriesInteractions.replyTags(slide),
            { identityViewModel.createSigner() }, space.bitos.app.data.feed.DefaultRelays.writeUrls,
        )
    }

    /** Delete my own story slide (kind 5; the repo also drops it locally). */
    fun deleteStorySlide(slideId: String) {
        if (notePublisher == null || identityViewModel == null) return
        notePublisher.publishDeletion(
            targetEventIds = listOf(slideId),
            signerProvider = { identityViewModel.createSigner() },
            writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
        )
    }

    /** Story zap: the LNURL flow with the slide id as the target event. */
    fun zapStory(recipientPubkey: String, lud16: String?, targetEventId: String, comment: String = "", anonymous: Boolean = false) {
        zapRecipient(recipientPubkey, lud16, targetEventId, comment, anonymous)
    }

    private fun zapRecipient(
        recipientPubkey: String,
        lud16: String?,
        targetEventId: String?,
        comment: String,
        anonymous: Boolean,
    ) {
        if (lud16 == null) {
            mutableZap.value = mutableZap.value.copy(
                phase = space.bitos.app.ui.feed.ZapPhase.FAILED,
                failure = "This author has no Lightning address in their profile, so zaps cannot be sent.",
            )
            return
        }
        val amountSats = mutableZap.value.amountSats
        mutableZap.value = mutableZap.value.copy(phase = space.bitos.app.ui.feed.ZapPhase.FETCHING, busy = true, failure = null)
        viewModelScope.launch {
            try {
                val payRequest = lnurlClient.fetchPayRequest(lud16)
                val nostrJson = if (anonymous) null else signedZapRequest(payRequest, amountSats, lud16, recipientPubkey, targetEventId, comment)
                // APP-014: remember the 9734 id — the paid watcher matches
                // the receipt whose embedded request id equals it exactly.
                mutableZap.value = mutableZap.value.copy(requestId = currentZapRequestId)
                val invoice = lnurlClient.fetchInvoice(payRequest, amountSats * 1000, nostrJson, lud16)
                mutableZap.value = mutableZap.value.copy(
                    phase = space.bitos.app.ui.feed.ZapPhase.INVOICE,
                    invoice = invoice.paymentRequest,
                    verifyUrl = invoice.verifyUrl,
                    busy = false,
                )
                // LUD-21 settle polling (legacy `pollVerify` parity): the
                // payment signal for QR/external-wallet flows — races the
                // 9735 receipt watch; first signal wins.
                val verifyUrl = invoice.verifyUrl
                if (verifyUrl != null) {
                    val expiry = space.bitos.core.model.Bolt11.expirySeconds(invoice.paymentRequest) ?: 0L
                    verifyJob?.cancel()
                    verifyJob = viewModelScope.launch {
                        while (isActive && System.currentTimeMillis() / 1000 < expiry) {
                            delay(3_000)
                            if (lnurlClient.fetchVerifySettled(verifyUrl)) {
                                mutableZap.value = mutableZap.value.copy(verifySettled = true)
                                break
                            }
                        }
                    }
                }
            } catch (failure: Exception) {
                mutableZap.value = mutableZap.value.copy(
                    phase = space.bitos.app.ui.feed.ZapPhase.FAILED,
                    failure = failure.message ?: "Zap failed.",
                    busy = false,
                )
            }
        }
    }

    private var currentZapRequestId: String? = null

    private suspend fun signedZapRequest(
        payRequest: space.bitos.core.model.LnurlPay.PayRequest,
        amountSats: Long,
        lud16: String,
        recipientPubkey: String,
        targetEventId: String?,
        comment: String = "",
    ): String? {
        val signer = identityViewModel?.createSigner() ?: return null
        val composer = space.bitos.core.publish.NoteComposer(clock = { System.currentTimeMillis() / 1000 })
        val unsigned = composer.composeZapRequest(
            recipientPubkey = recipientPubkey,
            amountMillisats = amountSats * 1000,
            relays = space.bitos.app.data.feed.DefaultRelays.urls.map { it.value },
            lnurlHint = lud16,
            comment = comment,
            authorPubkey = signer.publicKeyHex(),
            targetEventId = targetEventId,
        ) ?: return null
        currentZapRequestId = unsigned.idHex
        val signature = signer.sign(unsigned.messageBytes()) ?: return null
        // LUD-06: the `nostr` param is the BARE signed event object, never
        // a relay `["EVENT",…]` frame (APP-014 fix — servers reject frames).
        return if (payRequest.allowsNostr) composer.signedEventJson(unsigned, signature) else null
    }

    fun loadZaps(targetEventId: String) {
        repository.loadZaps(targetEventId)
    }

    fun loadComments(targetEventId: String) {
        repository.loadComments(targetEventId)
    }

    /** Report (kind 1984) through the receipt machine; mute is device-local. */
    fun report(note: space.bitos.core.feed.FeedNote, reason: String) {
        if (notePublisher == null || identityViewModel == null) return
        notePublisher.publishReport(
            targetEventId = note.id,
            targetPubkey = note.pubkey,
            reason = reason,
            signerProvider = { identityViewModel.createSigner() },
            writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
        )
    }

    fun toggleMute(author: String) {
        val store = muteStore ?: return
        if (store.isMuted(author)) store.unmute(author) else store.mute(author)
    }

    fun isMuted(author: String): Boolean = muteStore?.isMuted(author) ?: false

    // ── Local ranking signals (web interaction-profile parity) ─────────

    val interaction: space.bitos.app.data.feed.InteractionProfileStore? get() = interactionProfile

    /** Not interested: hide the note AND demote its author + topics. */
    fun notInterested(note: space.bitos.core.feed.FeedNote) {
        val profile = interactionProfile ?: return
        profile.dismissNote(note.id)
        profile.demoteAuthor(note.pubkey)
        note.hashtags.forEach(profile::demoteTag)
    }

    /** Hide this note only (no ranking demotions). */
    fun hideNote(note: space.bitos.core.feed.FeedNote) {
        interactionProfile?.dismissNote(note.id)
    }

    fun toggleShowLessFrom(author: String) {
        interactionProfile?.toggleDemotedAuthor(author)
    }

    fun toggleShowLessAbout(tag: String) {
        interactionProfile?.toggleDemotedTag(tag)
    }

    // ── NIP-51 followed hashtags (web hashtag-follows parity) ──────────

    val hashtagFollows: space.bitos.app.data.feed.HashtagFollowsStore? get() = hashtagFollowsStore

    fun isHashtagFollowed(tag: String): Boolean =
        hashtagFollowsStore?.isFollowed(tag) == true

    /** Optimistic flip + interest-set (kind 30015 d=interest) publish. */
    fun toggleHashtagFollow(tag: String) {
        val store = hashtagFollowsStore ?: return
        val updated = store.toggle(tag)
        if (notePublisher == null || identityViewModel == null) return
        notePublisher.publishInterestSet(
            hashtags = updated.toList(),
            signerProvider = { identityViewModel.createSigner() },
            writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
        )
    }

    /** APP-008 poll vote: optimistic local flip + kind-1018 publish. */
    fun votePoll(note: space.bitos.core.feed.FeedNote, optionIndex: Int) {
        repository.applyOptimisticPollVote(note.id, optionIndex)
        if (notePublisher == null || identityViewModel == null) return
        notePublisher.publishPollVote(
            targetEventId = note.id,
            optionIndex = optionIndex,
            signerProvider = { identityViewModel.createSigner() },
            writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
        )
    }

    /** One-shot REQ for a poll's votes (called when a poll card renders). */
    fun loadPollVotes(noteId: String) {
        repository.loadPollVotes(noteId)
    }

    fun toggleBookmark(noteId: String) {
        // Signed accounts: optimistic relay-backed set + kind-30003 publish.
        // Signed out: local-only optimistic state.
        val updated = repository.applyBookmarkChange(noteId, add = noteId !in repository.state.value.bookmarkedIds)
        if (updated != null) {
            if (notePublisher != null && identityViewModel != null) {
                notePublisher.publishBookmarkList(
                    eventIds = updated,
                    signerProvider = { identityViewModel.createSigner() },
                    writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
                )
            }
        } else {
            mutableLocalActions.value = mutableLocalActions.value.copy(bookmarked = toggle(mutableLocalActions.value.bookmarked, noteId))
        }
    }

    private fun toggle(set: Set<String>, id: String): Set<String> =
        if (id in set) set - id else set + id

    override fun onCleared() {
        repository.stop()
    }

    companion object {
        fun factory(
            repository: FeedRepository,
            notePublisher: space.bitos.app.data.publish.NotePublisher,
            identityViewModel: space.bitos.app.identity.IdentityViewModel,
            notifications: space.bitos.app.data.feed.NotificationRepository,
            muteStore: space.bitos.app.data.feed.MuteStore,
            interactionProfile: space.bitos.app.data.feed.InteractionProfileStore? = null,
            hashtagFollowsStore: space.bitos.app.data.feed.HashtagFollowsStore? = null,
            sentZaps: space.bitos.app.data.zap.SentZapsStore? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(repository, notePublisher, identityViewModel, notifications, muteStore, sentZaps, interactionProfile, hashtagFollowsStore) as T
            }
    }
}

/** Optimistic local interaction state, keyed by verified event id. Values are
 * replaced, never mutated, before publishing to Compose. */
@Immutable
data class LocalActions(
    val liked: Set<String> = emptySet(),
    val bookmarked: Set<String> = emptySet(),
)

/** APP-007 Chain sheet state: one walk, keyed to the tapped note id. */
data class RemixChainUiState(
    val isLoading: Boolean = false,
    val rootId: String? = null,
    val outcome: space.bitos.core.feed.RemixChain.Outcome? = null,
)
