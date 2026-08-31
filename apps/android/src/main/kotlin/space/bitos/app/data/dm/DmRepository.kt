package space.bitos.app.data.dm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.bitos.app.data.relay.RelayFrame
import space.bitos.app.data.relay.RelayPool
import space.bitos.core.model.DmConversation
import space.bitos.core.model.DmGrouping
import space.bitos.core.model.DmMessage
import space.bitos.core.model.DmPresentation
import space.bitos.core.model.NostrEvent
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.RelayOk
import space.bitos.core.publish.SecureDmComposer

/** Per-conversation view state derived by the repository (shared rules). */
data class DmConversationState(
    val conversation: DmConversation,
    val unreadCount: Int,
    /** NIP-17 generic preview line (never plaintext outside the chat). */
    val previewLine: String,
)

/** One publish in flight: rumor id → relay receipts (delivery honesty). */
data class DmOutgoingMessage(
    val rumorId: String,
    val peerPubkey: String,
    val content: String,
    val createdAt: Long,
)

/** Message-request verdict shared with the UI. */
enum class DmRequestState { ACCEPTED, REQUEST, DECLINED }

data class DmUiState(
    val conversations: List<DmConversationState> = emptyList(),
    /** Unaccepted peers whose inbound messages wait in Message requests. */
    val requests: List<DmConversationState> = emptyList(),
    val hasAccount: Boolean = false,
    val loaded: Boolean = false,
    /** One chat's peer when a conversation is open. */
    val openPeerPubkey: String? = null,
    /** Unread across accepted conversations (shell badge source). */
    val unreadCount: Int = 0,
    /** Request count (shell badge source; mock "2 waiting"). */
    val requestCount: Int = 0,
    /** Most recent publish verdict per rumor id: true = delivered. */
    val deliveryByRumorId: Map<String, Boolean> = emptyMap(),
)

/**
 * Bounded DM read-cursor + acceptance persistence port
 * (SharedPreferences adapter mirrors the notification prefs port).
 */
interface DmPrefs {
    /** Last-read created-at second per peer pubkey. */
    fun readCursors(): Map<String, Long>

    fun saveReadCursors(cursors: Map<String, Long>)

    /** Peers the user explicitly accepted in Message requests. */
    fun acceptedPeers(): Set<String>

    fun saveAcceptedPeers(peers: Set<String>)

    /** Peers whose requests the user deleted (stays silent to sender). */
    fun declinedPeers(): Set<String>

    fun saveDeclinedPeers(peers: Set<String>)
}

/** In-memory prefs for tests and previews. */
class InMemoryDmPrefs : DmPrefs {
    private val cursors = mutableMapOf<String, Long>()
    private val accepted = mutableSetOf<String>()
    private val declined = mutableSetOf<String>()
    override fun readCursors(): Map<String, Long> = cursors.toMap()
    override fun saveReadCursors(cursors: Map<String, Long>) {
        this.cursors.putAll(cursors)
        if (this.cursors.size > MAX_PERSISTED) {
            val keep = this.cursors.entries.sortedByDescending { it.value }.take(MAX_PERSISTED)
            this.cursors.clear()
            this.cursors.putAll(keep.associate { it.key to it.value })
        }
    }

    override fun acceptedPeers(): Set<String> = accepted.toSet()
    override fun saveAcceptedPeers(peers: Set<String>) {
        accepted.clear()
        accepted.addAll(peers.take(MAX_PERSISTED))
    }

    override fun declinedPeers(): Set<String> = declined.toSet()
    override fun saveDeclinedPeers(peers: Set<String>) {
        declined.clear()
        declined.addAll(peers.take(MAX_PERSISTED))
    }

    companion object {
        const val MAX_PERSISTED = 512
    }
}

/**
 * APP-011 DM repository: subscribes kind-1059 gift wraps addressed to the
 * account (#p filter), unwraps them through the shared NIP-17 composer
 * (silent null for wraps that aren't ours), groups into per-peer
 * conversations via the shared `DmGrouping` rule, and publishes wrapped
 * messages through the same machine.
 *
 * Conversation state beyond grouping — read cursors, unread counts,
 * message-request acceptance and generic NIP-17 previews — derives from
 * the shared `DmPresentation` rules so Compose and SwiftUI agree.
 */
class DmRepository(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val hasher: EventHasher = Sha256EventHasher,
    private val secretProvider: suspend () -> String?, // hex private key
    private val prefs: DmPrefs = InMemoryDmPrefs(),
) {
    private val messages = LinkedHashMap<String, DmMessage>() // rumor id → message
    private var accountPubkey: String? = null
    private var collectJob: Job? = null
    private var requested = false

    /** Rumor ids this account authored (sent side of delivery ticks). */
    private val sentRumorIds = LinkedHashSet<String>()
    private val readCursors = LinkedHashMap<String, Long>()
    private val acceptedPeers = mutableSetOf<String>()
    private val declinedPeers = mutableSetOf<String>()
    private val pendingDeliveries = LinkedHashMap<String, DmOutgoingMessage>() // rumor id → publish
    private val deliveryByRumorId = LinkedHashMap<String, Boolean>()

    private val mutableState = MutableStateFlow(DmUiState())
    val state: StateFlow<DmUiState> = mutableState.asStateFlow()

    fun setAccount(pubkey: String?) {
        if (accountPubkey == pubkey && collectJob != null) {
            if (pubkey != null) subscribe()
            return
        }
        accountPubkey = pubkey
        requested = false
        messages.clear()
        pendingDeliveries.clear()
        deliveryByRumorId.clear()
        mutableState.value = DmUiState(hasAccount = pubkey != null)
        collectJob?.cancel()
        collectJob = null
        loadPrefs()
        if (pubkey != null) {
            start()
            subscribe()
            publishState()
        }
    }

    fun openConversation(peerPubkey: String?) {
        mutableState.value = mutableState.value.copy(openPeerPubkey = peerPubkey)
        if (peerPubkey != null) markConversationRead(peerPubkey)
    }

    /** Advances the peer's cursor to the conversation's newest message. */
    fun markConversationRead(peerPubkey: String) {
        val conversation = conversationsNow().firstOrNull { it.peerPubkey == peerPubkey } ?: return
        val next = DmPresentation.nextCursor(conversation, readCursors[peerPubkey] ?: 0)
        if (next <= (readCursors[peerPubkey] ?: 0)) return
        readCursors[peerPubkey] = next
        persistCursors()
        publishState()
    }

    /** Accepts a message request: the thread joins the main list. */
    fun acceptRequest(peerPubkey: String) {
        declinedPeers.remove(peerPubkey)
        acceptedPeers.add(peerPubkey)
        persistPeers()
        publishState()
    }

    /** Deletes a request: rows hide locally; the sender is never told. */
    fun declineRequest(peerPubkey: String) {
        acceptedPeers.remove(peerPubkey)
        declinedPeers.add(peerPubkey)
        persistPeers()
        publishState()
    }

    /**
     * Publishes a NIP-17 wrapped message: one wrap to the recipient plus
     * one to the sender themself (web parity — own devices decrypt).
     * Talking to a peer accepts their request (shared rule).
     */
    suspend fun sendMessage(recipientPubkey: String, content: String): Boolean {
        val secret = secretProvider() ?: return false
        val account = accountPubkey ?: return false
        val now = System.currentTimeMillis() / 1000
        val outgoing = SecureDmComposer.wrapMessage(
            senderPrivateKeyHex = secret,
            recipientPubkey = recipientPubkey,
            content = content,
            hasher = hasher,
            nowSeconds = now,
        ) ?: return false
        // Wrap to the recipient.
        publishWrap(outgoing.wrap)
        // Self-wrap so our other devices see the message.
        val selfWrap = SecureDmComposer.wrapMessage(
            senderPrivateKeyHex = secret,
            recipientPubkey = account,
            content = content,
            hasher = hasher,
            nowSeconds = now,
        )
        if (selfWrap != null) publishWrap(selfWrap.wrap)
        if (recipientPubkey !in acceptedPeers) {
            acceptedPeers.add(recipientPubkey)
            declinedPeers.remove(recipientPubkey)
            persistPeers()
        }
        // Track wrap ids so OK receipts resolve delivery for this rumor.
        val rumorId = outgoing.rumor.id.value
        pendingDeliveries[rumorId] = DmOutgoingMessage(
            rumorId = rumorId,
            peerPubkey = recipientPubkey,
            content = content,
            createdAt = now,
        )
        pendingWrapIds.getOrPut(rumorId) { mutableSetOf() }.apply {
            add(outgoing.wrap.id.value)
            selfWrap?.let { add(it.wrap.id.value) }
        }
        // Optimistically append the outgoing message.
        absorbRumor(outgoing.rumor, account, recipientPubkey)
        launchDeliveryWatch(rumorId)
        return true
    }

    /** Delivery ticks: ["OK", wrapId, true] → delivered (first accept wins). */
    private fun launchDeliveryWatch(rumorId: String) {
        scope.launch {
            val deadline = System.currentTimeMillis() + DELIVERY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && deliveryByRumorId[rumorId] != true) {
                kotlinx.coroutines.delay(250)
            }
            if (deliveryByRumorId[rumorId] != true && rumorId in pendingDeliveries) {
                publishState() // render the honest "sending" state
            }
        }
    }

    private fun publishWrap(wrap: NostrEvent) {
        val sig = wrap.signature ?: return
        val frame = """["EVENT",{"id":"${wrap.id.value}","pubkey":"${wrap.pubkey.value}","created_at":${wrap.createdAt},"kind":${wrap.kind},"tags":[${wrap.tags.joinToString(",") { tag -> tag.joinToString(",", "[", "]") { v -> "\"$v\"" } }}],"content":"${wrap.content}","sig":"$sig"}]"""
        pool.broadcast(frame)
    }

    private fun start() {
        if (collectJob != null) return
        pool.start()
        collectJob = scope.launch {
            pool.frames.collect { frame ->
                val ok = NoteRelayOkParser.parse(frame.message)
                if (ok != null) {
                    absorbOk(frame, ok)
                    return@collect
                }
                val event = runCatching {
                    NostrEventCodec.decodeRelayEvent(hasher, frame.message, frame.relay)
                }.getOrNull() ?: return@collect
                if (event.kind == SecureDmComposer.KIND_GIFT_WRAP) {
                    absorbGiftWrap(event)
                }
            }
        }
    }

    private fun absorbOk(frame: RelayFrame, ok: RelayOk) {
        if (!ok.accepted) return
        // Any pending publish whose wrap event id was accepted is delivered.
        val delivered = pendingWrapIds.entries.firstOrNull { (_, ids) -> ok.eventId in ids }?.key ?: return
        deliveryByRumorId[delivered] = true
        pendingDeliveries.remove(delivered)
        pendingWrapIds.remove(delivered)
        publishState()
    }

    /** wrap event id(s) per rumor id — OK receipts reference wrap ids. */
    private val pendingWrapIds = LinkedHashMap<String, MutableSet<String>>()

    private fun absorbGiftWrap(wrap: NostrEvent) {
        val account = accountPubkey ?: return
        // Only wraps tagged to us.
        if (!wrap.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == account }) return
        val secret = kotlinx.coroutines.runBlocking { secretProvider() } ?: return
        val rumor = SecureDmComposer.unwrap(wrap, secret, hasher) ?: return
        // The rumor's peer side: the tagged p if present, else the wrap sender's peer.
        val peer = rumor.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1) ?: return
        absorbRumor(rumor, peer, account)
    }

    private fun absorbRumor(rumor: NostrEvent, peerA: String, peerB: String) {
        if (rumor.id.value in messages) return
        // The DmMessage peer is the OTHER party from my perspective.
        val myPubkey = accountPubkey ?: return
        val other = if (rumor.pubkey.value == myPubkey) peerA else rumor.pubkey.value
        messages[rumor.id.value] = DmMessage(
            id = rumor.id.value,
            authorPubkey = rumor.pubkey.value,
            peerPubkey = other,
            content = rumor.content,
            createdAt = rumor.createdAt,
        )
        if (rumor.pubkey.value == myPubkey) sentRumorIds.add(rumor.id.value)
        if (messages.size > MAX_STORED) messages.remove(messages.keys.first())
        publishState()
    }

    private fun subscribe() {
        if (requested) return
        requested = true
        val account = accountPubkey ?: return
        val filter = """{"kinds":[${SecureDmComposer.KIND_GIFT_WRAP}],"#p":["$account"],"limit":50}"""
        pool.broadcast(NostrEventCodec.encodeRequest("bitos-dms", filter))
    }

    // ── state derivation (shared rules) ─────────────────────────────

    private fun conversationsNow(): List<DmConversation> {
        val my = accountPubkey ?: return emptyList()
        return DmGrouping.group(messages.values.toList(), my)
    }

    private fun publishState() {
        val my = accountPubkey ?: return
        val grouped = conversationsNow()
        val accepted = mutableListOf<DmConversationState>()
        val requests = mutableListOf<DmConversationState>()
        for (conversation in grouped) {
            val cursor = readCursors[conversation.peerPubkey] ?: 0
            val state = DmConversationState(
                conversation = conversation,
                unreadCount = DmPresentation.unreadCount(conversation, my, cursor),
                previewLine = DmPresentation.previewLine(conversation, my, cursor),
            )
            val isAccepted = DmPresentation.isAccepted(
                peerPubkey = conversation.peerPubkey,
                everSentTo = sentRumorPeers(),
                explicitlyAccepted = acceptedPeers,
                explicitlyDeclined = declinedPeers,
            )
            if (isAccepted) accepted.add(state) else requests.add(state)
        }
        mutableState.value = mutableState.value.copy(
            conversations = accepted,
            requests = requests,
            loaded = true,
            unreadCount = accepted.sumOf { it.unreadCount },
            requestCount = requests.size,
            deliveryByRumorId = deliveryByRumorId.toMap(),
        )
    }

    /** Peers this account has ever messaged (outgoing side). */
    private fun sentRumorPeers(): Set<String> =
        messages.values.filter { it.authorPubkey == accountPubkey }.map { it.peerPubkey }.toSet()

    private fun loadPrefs() {
        readCursors.clear()
        readCursors.putAll(prefs.readCursors())
        acceptedPeers.clear()
        acceptedPeers.addAll(prefs.acceptedPeers())
        declinedPeers.clear()
        declinedPeers.addAll(prefs.declinedPeers())
    }

    private fun persistCursors() {
        while (readCursors.size > InMemoryDmPrefs.MAX_PERSISTED) {
            readCursors.remove(readCursors.entries.minByOrNull { it.value }?.key ?: break)
        }
        prefs.saveReadCursors(readCursors.toMap())
    }

    private fun persistPeers() {
        prefs.saveAcceptedPeers(acceptedPeers.toSet())
        prefs.saveDeclinedPeers(declinedPeers.toSet())
    }

    private companion object {
        const val MAX_STORED = 500
        const val DELIVERY_TIMEOUT_MS = 10_000L
    }
}

/** Parses ["OK", id, bool, msg] frames (shared codec rule). */
private object NoteRelayOkParser {
    fun parse(message: String): RelayOk? = space.bitos.core.publish.NoteComposer.parseOkMessage(message)
}
