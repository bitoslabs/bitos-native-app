package space.bitos.app.ui.feed

import androidx.lifecycle.ViewModel
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
) : ViewModel() {

    private val mutableLocalActions = MutableStateFlow(LocalActions())
    private val mutableZap = MutableStateFlow(space.bitos.app.ui.feed.ZapUiState())
    val zapState: StateFlow<space.bitos.app.ui.feed.ZapUiState> = mutableZap.asStateFlow()
    private val lnurlClient = space.bitos.app.data.zap.LnurlPayClient()
    val localActions: StateFlow<LocalActions> = mutableLocalActions.asStateFlow()

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

    fun toggleLike(note: space.bitos.core.feed.FeedNote) {
        val turningOn = note.id !in mutableLocalActions.value.liked
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

    /** Publishes a kind-1 reply; optimistic append happens on relay ACK. */
    fun reply(text: String, note: space.bitos.core.feed.FeedNote) {
        if (notePublisher == null || identityViewModel == null) return
        notePublisher.publishReplyWith(
            content = text,
            targetEventId = note.id,
            targetPubkey = note.pubkey,
            signerProvider = { identityViewModel.createSigner() },
            writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
        )
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

    fun dismissZap() {
        mutableZap.value = space.bitos.app.ui.feed.ZapUiState()
    }

    /** LNURL flow: params → signed 9734 → invoice. Failures surface; never crashes UI. */
    fun zap(note: space.bitos.core.feed.FeedNote) {
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
                val nostrJson = signedZapRequest(payRequest, amountSats, lud16, note)
                val invoice = lnurlClient.fetchInvoice(payRequest, amountSats * 1000, nostrJson, lud16)
                mutableZap.value = mutableZap.value.copy(
                    phase = space.bitos.app.ui.feed.ZapPhase.INVOICE,
                    invoice = invoice,
                    busy = false,
                )
            } catch (failure: Exception) {
                mutableZap.value = mutableZap.value.copy(
                    phase = space.bitos.app.ui.feed.ZapPhase.FAILED,
                    failure = failure.message ?: "Zap failed.",
                    busy = false,
                )
            }
        }
    }

    private suspend fun signedZapRequest(
        payRequest: space.bitos.core.model.LnurlPay.PayRequest,
        amountSats: Long,
        lud16: String,
        note: space.bitos.core.feed.FeedNote,
    ): String? {
        val signer = identityViewModel?.createSigner() ?: return null
        val composer = space.bitos.core.publish.NoteComposer(clock = { System.currentTimeMillis() / 1000 })
        val unsigned = composer.composeZapRequest(
            recipientPubkey = note.pubkey,
            amountMillisats = amountSats * 1000,
            relays = space.bitos.app.data.feed.DefaultRelays.urls.map { it.value },
            lnurlHint = lud16,
            comment = "",
            authorPubkey = signer.publicKeyHex(),
            targetEventId = note.id,
        ) ?: return null
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
