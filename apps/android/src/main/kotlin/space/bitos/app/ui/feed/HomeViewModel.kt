package space.bitos.app.ui.feed

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.bitos.app.data.feed.FeedRepository
import space.bitos.app.data.feed.FeedTimeline
import space.bitos.app.data.feed.FeedUiState

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

    init {
        repository.start()
        // Account changes drive the Following timeline and the inbox.
        viewModelScope.launch {
            identityViewModel?.state?.collect { state ->
                repository.setAccount(state.account?.pubkeyHex)
                notifications?.setAccount(state.account?.pubkeyHex)
            }
        }
        // Mutes filter all feed windows.
        viewModelScope.launch {
            muteStore?.muted?.collect { muted -> repository.setMuted(muted) }
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

    /** APP-015: re-fetch saved notes missing from the local map. */
    fun loadBookmarked() = repository.loadBookmarked()

    // APP-004: content-filter window + new-notes hold/reveal.
    fun selectFilter(filter: space.bitos.core.feed.FeedFilter) = repository.selectFilter(filter)

    fun revealPendingNotes() = repository.revealPendingNotes()

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

    /** Fetched note for an in-app ref open (null while in flight). */
    fun refNote(raw: String): space.bitos.core.feed.FeedNote? = repository.refNote(raw)

    fun toggleLike(note: space.bitos.core.feed.FeedNote) {        val turningOn = note.id !in mutableLocalActions.value.liked
        mutableLocalActions.value = mutableLocalActions.value.copy(liked = toggle(mutableLocalActions.value.liked, note.id))
        repository.setLikedIds(mutableLocalActions.value.liked)
        // Signed accounts publish a real kind-7 reaction on like; unlikes
        // stay local until reaction deletion (kind 5) lands with SOC-002.
        if (turningOn && notePublisher != null && identityViewModel != null) {
            notePublisher.publishReactionWith(
                targetEventId = note.id,
                targetPubkey = note.pubkey,
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

    fun selectZapAmount(sats: Long) {
        mutableZap.value = mutableZap.value.copy(amountSats = sats)
    }

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

    fun dismissZap() {
        mutableZap.value = space.bitos.app.ui.feed.ZapUiState()
    }

    /** LNURL flow: params → signed 9734 → invoice. Failures surface; never crashes UI. */
    fun zap(note: space.bitos.core.feed.FeedNote, comment: String = "", anonymous: Boolean = false) {
        val profile = repository.state.value.profiles[note.pubkey]
        val lud16 = profile?.lud16 ?: run {
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
                val nostrJson = if (anonymous) null else signedZapRequest(payRequest, amountSats, lud16, note, comment)
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
        note: space.bitos.core.feed.FeedNote,
        comment: String = "",
    ): String? {
        val signer = identityViewModel?.createSigner() ?: return null
        val composer = space.bitos.core.publish.NoteComposer(clock = { System.currentTimeMillis() / 1000 })
        val unsigned = composer.composeZapRequest(
            recipientPubkey = note.pubkey,
            amountMillisats = amountSats * 1000,
            relays = space.bitos.app.data.feed.DefaultRelays.urls.map { it.value },
            lnurlHint = lud16,
            comment = comment,
            authorPubkey = signer.publicKeyHex(),
            targetEventId = note.id,
        ) ?: return null
        currentZapRequestId = unsigned.idHex
        val signature = signer.sign(unsigned.messageBytes()) ?: return null
        return if (payRequest.allowsNostr) composer.publishMessage(unsigned, signature) else null
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
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(repository, notePublisher, identityViewModel, notifications, muteStore) as T
            }
    }
}

/** Optimistic local interaction state, keyed by verified event id. */
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
