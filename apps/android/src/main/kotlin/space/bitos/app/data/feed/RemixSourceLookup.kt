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
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.studio.RemixPreflight
import space.bitos.core.studio.RemixSourceTargets
import space.bitos.core.studio.RemixTarget

/**
 * Verified by-id lookup for the Create-hub "Remix a Bitz" picker
 * (MEM-005a, plan `create-hub-sound-remix-plan.md`). One bounded REQ per
 * attempt; only an ID- and signature-verified frame whose event id
 * MATCHES the requested target resolves (the shared `RemixPreflight`
 * mismatch rule — a relay bug can never hand the editor a different
 * note). A named timeout keeps the "Checking this Bitz on relays…"
 * state honest and cancellable; `close()` cancels any in-flight fetch
 * (Back/Cancel preserves the hub and strands nothing).
 */
class RemixSourceLookup(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
) {
    sealed interface State {
        data object Idle : State
        /** Named loading stage; [target] echoes what is being fetched. */
        data class Looking(val target: RemixTarget.Event) : State
        /** Verified + id-matching note arrived (preflight classifies it). */
        data class Found(val note: FeedNote) : State
        /** Relays did not answer in time; [retry] re-runs the same target. */
        data class Timeout(val target: RemixTarget.Event) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var collectJob: Job? = null
    private var settleJob: Job? = null
    private var requestedId: String? = null
    private var subscriptionId: Int = 0
    private var waiter: kotlinx.coroutines.CompletableDeferred<FeedNote>? = null

    init {
        collectJob = scope.launch {
            pool.verifiedFrames.collect { gated ->
                val event = (gated as? VerifiedPoolFrame.Verified)?.event ?: return@collect
                val id = requestedId ?: return@collect
                if (event.id.value != id) return@collect
                // Kinds outside the lookup set never resolve a target.
                if (event.kind !in RemixPreflight.LOOKUP_KINDS) return@collect
                val note = FeedNote.from(event)
                if (!RemixPreflight.idMatches(id, note)) return@collect
                waiter?.complete(note)
            }
        }
    }

    /**
     * Parses [raw] (shared MEM-003a rules) and fetches the target. The
     * returned note is verified AND id-matching; null means the paste was
     * rejected (caller surfaces the shared rejection copy) or the fetch
     * was cancelled/timed out — check [state] for the named stage.
     */
    suspend fun resolve(raw: String): FeedNote? {
        val target = RemixSourceTargets.parse(raw) as? RemixTarget.Event ?: return null
        cancel()
        requestedId = target.id
        mutableState.value = State.Looking(target)
        subscriptionId += 1
        val subId = "$SUB_PREFIX-$subscriptionId"
        val deferred = kotlinx.coroutines.CompletableDeferred<FeedNote>()
        waiter = deferred
        pool.start()
        pool.broadcast(
            NostrEventCodec.encodeRequest(subId, RemixPreflight.lookupFilter(target.id)),
        )
        settleJob = scope.launch {
            delay(TIMEOUT_MS)
            if (deferred.isActive) {
                mutableState.value = State.Timeout(target)
                deferred.cancel()
            }
        }
        val note = runCatching { deferred.await() }.getOrNull()
        settleJob?.cancel()
        closeSubscription(subId)
        if (note != null) mutableState.value = State.Found(note)
        return note
    }

    /** Cancels any in-flight fetch and closes its subscription. */
    fun cancel() {
        settleJob?.cancel()
        waiter?.cancel()
        waiter = null
        requestedId = null
        mutableState.value = State.Idle
        closeSubscription("$SUB_PREFIX-$subscriptionId")
    }

    private fun closeSubscription(subId: String) {
        runCatching { pool.broadcast("[\"CLOSE\",\"$subId\"]") }
    }

    private companion object {
        const val SUB_PREFIX = "bitos-remix-lookup"
        const val TIMEOUT_MS = 8_000L
    }
}

/**
 * "Your recent Bitz" (MEM-005a): a bounded, account-scoped, verified list
 * of the signed-in key's own media notes — no server-side search, no
 * invented popularity ranking (recency is the order). The REQ closes on
 * settle so the picker's window never lingers; `reload()` re-runs it on
 * retry or account change (the collector is pubkey-scoped so a switched
 * account's rows never leak into the previous one's list).
 */
class RecentBitzStore(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
) {
    data class State(
        val rows: List<FeedNote> = emptyList(),
        val isLoading: Boolean = false,
        /** True when the window settled without a single relay answer. */
        val timedOut: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var collectJob: Job? = null
    private var settleJob: Job? = null
    private var authorPubkey: String? = null
    private var subscriptionCounter = 0

    init {
        collectJob = scope.launch {
            pool.verifiedFrames.collect { gated ->
                val event = (gated as? VerifiedPoolFrame.Verified)?.event ?: return@collect
                val author = authorPubkey ?: return@collect
                if (event.pubkey.value != author) return@collect
                if (event.kind !in MEDIA_KINDS) return@collect
                val note = FeedNote.from(event)
                val current = mutableState.value
                if (current.rows.any { it.id == note.id }) return@collect
                mutableState.value = current.copy(
                    rows = (listOf(note) + current.rows)
                        .sortedByDescending { it.createdAt }
                        .take(MAX_ROWS),
                    isLoading = false,
                    timedOut = false,
                )
            }
        }
    }

    /** Requests (or re-requests) the account's own recent bitz. */
    fun open(pubkey: String) {
        if (authorPubkey != pubkey && authorPubkey != null) {
            mutableState.value = State()
        }
        authorPubkey = pubkey
        mutableState.value = mutableState.value.copy(isLoading = true, timedOut = false)
        subscriptionCounter += 1
        val subId = "$SUB_PREFIX-$subscriptionCounter"
        settleJob?.cancel()
        pool.start()
        pool.broadcast(
            NostrEventCodec.encodeRequest(
                subId,
                """{"kinds":[${MEDIA_KINDS.joinToString(",")}],"authors":["$pubkey"],"limit":$MAX_ROWS}""",
            ),
        )
        settleJob = scope.launch {
            delay(SETTLE_MS)
            if (mutableState.value.isLoading) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    timedOut = mutableState.value.rows.isEmpty(),
                )
            }
        }
    }

    fun close() {
        settleJob?.cancel()
        runCatching { pool.broadcast("[\"CLOSE\",\"$SUB_PREFIX-$subscriptionCounter\"]") }
    }

    companion object {
        /** Account-scoped window bound (plan: "bounded… recent Bitz"). */
        const val MAX_ROWS = 20
        private val MEDIA_KINDS = listOf(20, 21, 22)
        private const val SUB_PREFIX = "bitos-recent-bitz"
        private const val SETTLE_MS = 6_000L
    }
}
