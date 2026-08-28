package space.bitos.app.data.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.bitos.app.data.relay.RelayPool
import space.bitos.core.model.NotificationExtractor
import space.bitos.core.model.NotificationFilters
import space.bitos.core.model.NotificationItem
import space.bitos.core.model.NotificationKind
import space.bitos.core.model.OriginNotes
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

/** Origin-note preview lifecycle (APP-012). */
sealed interface OriginNoteState {
    data object Loading : OriginNoteState
    data class Ready(val note: space.bitos.core.model.OriginNote) : OriginNoteState
    data object Unavailable : OriginNoteState
}

/** Bounded read-state + per-type mute persistence port (SharedPreferences/UserDefaults adapters). */
interface NotificationPrefs {
    fun readIds(): Set<String>
    fun save(readIds: Set<String>)
    fun mutedKinds(): Set<String>
    fun saveMutedKinds(kinds: Set<String>)
}

data class NotificationUiState(
    val items: List<NotificationItem> = emptyList(),
    /** True once the account's notification REQ resolved (even empty). */
    val loaded: Boolean = false,
    val hasAccount: Boolean = false,
    /** Item ids already read (unread stripe/dot + Unread tab). */
    val readIds: Set<String> = emptySet(),
    /** Raw relay frame per notification id (raw-JSON row action), bounded. */
    val rawEvents: Map<String, String> = emptyMap(),
    /** Origin-note preview per target event id. */
    val origins: Map<String, OriginNoteState> = emptyMap(),
    /** Per-type mutes (kind names); muted kinds never reach items or counts. */
    val mutedKinds: Set<NotificationKind> = emptySet(),
)

/**
 * Notification inbox repository (SOC-005 + APP-012): subscribes events
 * targeting the account (`#p` tagged filter over kinds 1/7/6/9735/3),
 * extracts bounded notifications from verified events, dedupes by event
 * id (republished follows collapse per author), keeps read state and raw
 * frames, and fetches bounded origin-note previews by event id.
 */
class NotificationRepository(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val hasher: EventHasher = Sha256EventHasher,
    private val prefs: NotificationPrefs,
    private val originTimeoutMillis: Long = 8_000,
) {
    private val items = LinkedHashMap<String, NotificationItem>()
    private val rawEvents = LinkedHashMap<String, String>()
    private val origins = LinkedHashMap<String, OriginNoteState>()
    private val readIds = LinkedHashSet<String>()
    private var mutedKinds = LinkedHashSet<NotificationKind>()
    private var accountPubkey: String? = null
    private var collectJob: Job? = null
    private var requested = false
    private var originBatch = 0

    private val mutableState = MutableStateFlow(NotificationUiState())
    val state: StateFlow<NotificationUiState> = mutableState.asStateFlow()

    fun setAccount(pubkey: String?) {
        // Idempotent: the shell wires account changes from app start (badge
        // feed) and the screen re-calls on every appearance.
        if (accountPubkey == pubkey && collectJob != null) {
            if (pubkey != null) start()
            return
        }
        accountPubkey = pubkey
        requested = false
        items.clear()
        rawEvents.clear()
        origins.clear()
        readIds.clear()
        readIds.addAll(prefs.readIds())
        mutedKinds.clear()
        mutedKinds.addAll(prefs.mutedKinds().mapNotNull { kindName ->
            NotificationKind.entries.firstOrNull { it.name == kindName }
        })
        mutableState.value = NotificationUiState(
            hasAccount = pubkey != null,
            readIds = readIds.toSet(),
            mutedKinds = mutedKinds.toSet(),
        )
        if (pubkey != null) {
            start()
            subscribe()
        } else {
            collectJob?.cancel()
            collectJob = null
        }
    }

    /** Per-type mutes: muted kinds drop from items, counts and the badge. */
    fun setMutedKinds(kinds: Set<NotificationKind>) {
        mutedKinds.clear()
        mutedKinds.addAll(kinds)
        prefs.saveMutedKinds(kinds.map { it.name }.toSet())
        // Evict already-collected muted items; keep read state (unmuting
        // re-collects on the next subscription round-trip).
        items.values.removeAll { it.kind in mutedKinds }
        publishState()
    }

    /** Marks one notification (or one aggregated group's ids) read. */
    fun markRead(ids: List<String>) {
        if (ids.isEmpty()) return
        readIds.addAll(ids)
        persistReadState()
        publishState()
    }

    fun markAllRead() = markRead(items.keys.toList())

    private fun persistReadState() {
        // Bounded: keep the newest 500 read ids.
        while (readIds.size > 500) readIds.remove(readIds.first())
        prefs.save(readIds.toSet())
    }

    /**
     * Requests origin-note previews for [ids] (batched ≤100 per REQ, relay
     * convention). Missing ids switch Loading → Unavailable after the
     * timeout so the row never spins forever.
     */
    fun requestOrigins(ids: List<String>) {
        if (accountPubkey == null) return
        val batch = ids.filter { it !in origins }.take(ORIGIN_BATCH)
        val request = NostrEventCodec.encodeIdsRequest("bitos-origin-${originBatch++}", batch)
        if (request == null) return
        batch.forEach { origins[it] = OriginNoteState.Loading }
        publishState()
        pool.broadcast(request)
        scope.launch {
            delay(originTimeoutMillis)
            var changed = false
            batch.forEach { id ->
                if (origins[id] is OriginNoteState.Loading) {
                    origins[id] = OriginNoteState.Unavailable
                    changed = true
                }
            }
            if (changed) publishState()
        }
    }

    private fun start() {
        if (collectJob != null) return
        pool.start()
        collectJob = scope.launch {
            pool.frames.collect { frame ->
                val event = runCatching {
                    NostrEventCodec.decodeRelayEvent(hasher, frame.message, frame.relay)
                }.getOrNull() ?: return@collect
                if (!NostrEventCodec.verifySignature(hasher, event)) return@collect
                absorbOrigin(event, frame.message)
                absorbNotification(event, frame.message)
            }
        }
    }

    private fun absorbOrigin(event: space.bitos.core.model.NostrEvent, rawMessage: String) {
        if (event.id.value in origins && origins[event.id.value] is OriginNoteState.Loading) {
            origins[event.id.value] = OriginNoteState.Ready(OriginNotes.project(event))
            // A fetched origin note is also a raw frame source for its row.
            rawEvents[event.id.value] = rawMessage
            publishState()
        }
    }

    private fun absorbNotification(event: space.bitos.core.model.NostrEvent, rawMessage: String) {
        val account = accountPubkey ?: return
        val notification = NotificationExtractor.extract(event, account) ?: return
        if (notification.kind in mutedKinds) return
        if (items.containsKey(notification.id)) return
        if (!NotificationFilters.shouldKeep(items.values.toList(), notification)) return
        items[notification.id] = notification
        if (items.size > MAX_ITEMS) items.remove(items.keys.first())
        rawEvents[notification.id] = rawMessage
        if (rawEvents.size > MAX_ITEMS) rawEvents.remove(rawEvents.keys.first())
        publishState()
    }

    private fun subscribe() {
        if (requested) return
        requested = true
        val account = accountPubkey ?: return
        val filter = """{"kinds":[1,7,6,9735,3],"#p":["$account"],"limit":50}"""
        pool.broadcast(NostrEventCodec.encodeRequest("bitos-notifications", filter))
    }

    private fun publishState() {
        mutableState.value = NotificationUiState(
            items = items.values.sortedByDescending { it.createdAt },
            loaded = items.isNotEmpty() || requested,
            hasAccount = accountPubkey != null,
            readIds = readIds.toSet(),
            rawEvents = rawEvents.toMap(),
            origins = origins.toMap(),
            mutedKinds = mutedKinds.toSet(),
        )
    }

    private companion object {
        const val MAX_ITEMS = 100
        const val ORIGIN_BATCH = 100
    }
}
