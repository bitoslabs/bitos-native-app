package space.bitos.app.data.dm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.bitos.app.data.relay.RelayConnectionState
import space.bitos.app.data.relay.RelayFrame
import space.bitos.app.data.relay.RelayPool
import space.bitos.core.crypto.Nip44
import space.bitos.core.model.DmConversation
import space.bitos.core.model.DmGrouping
import space.bitos.core.model.DmMessage
import space.bitos.core.model.NostrEvent
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.SecureDmComposer

data class DmUiState(
    val conversations: List<DmConversation> = emptyList(),
    val hasAccount: Boolean = false,
    val loaded: Boolean = false,
    /** One chat's messages when a conversation is open. */
    val openPeerPubkey: String? = null,
)

/**
 * APP-011 DM repository: subscribes kind-1059 gift wraps addressed to the
 * account (#p filter), unwraps them through the shared NIP-17 composer
 * (silent null for wraps that aren't ours), groups into per-peer
 * conversations via the shared `DmGrouping` rule, and publishes wrapped
 * messages through the same machine.
 */
class DmRepository(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val hasher: EventHasher = Sha256EventHasher,
    private val secretProvider: suspend () -> String?, // hex private key
) {
    private val messages = LinkedHashMap<String, DmMessage>() // rumor id → message
    private var accountPubkey: String? = null
    private var collectJob: Job? = null
    private var requested = false

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
        mutableState.value = DmUiState(hasAccount = pubkey != null)
        collectJob?.cancel()
        collectJob = null
        if (pubkey != null) {
            start()
            subscribe()
        }
    }

    fun openConversation(peerPubkey: String?) {
        mutableState.value = mutableState.value.copy(openPeerPubkey = peerPubkey)
    }

    /**
     * Publishes a NIP-17 wrapped message: one wrap to the recipient plus
     * one to the sender themself (web parity — own devices decrypt).
     */
    suspend fun sendMessage(recipientPubkey: String, content: String): Boolean {
        val secret = secretProvider() ?: return false
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
            recipientPubkey = accountPubkey!!,
            content = content,
            hasher = hasher,
            nowSeconds = now,
        )
        if (selfWrap != null) publishWrap(selfWrap.wrap)
        // Optimistically append the outgoing message.
        absorbRumor(outgoing.rumor, accountPubkey!!, recipientPubkey)
        return true
    }

    private fun publishWrap(wrap: NostrEvent) {
        val sig = wrap.signature ?: return
        val frame = """["EVENT",{"id":"${'$'}{wrap.id.value}","pubkey":"${'$'}{wrap.pubkey.value}","created_at":${'$'}{wrap.createdAt},"kind":${'$'}{wrap.kind},"tags":[${'$'}{wrap.tags.joinToString(",") { tag -> tag.joinToString(",", "[", "]") { v -> "\"${'$'}v\"" } }}],"content":"${'$'}{wrap.content}","sig":"${'$'}sig"}]"""
        pool.broadcast(frame)
    }

    private fun start() {
        if (collectJob != null) return
        pool.start()
        collectJob = scope.launch {
            pool.frames.collect { frame ->
                val event = runCatching {
                    NostrEventCodec.decodeRelayEvent(hasher, frame.message, frame.relay)
                }.getOrNull() ?: return@collect
                if (event.kind == SecureDmComposer.KIND_GIFT_WRAP) {
                    absorbGiftWrap(event)
                }
            }
        }
    }

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

    private fun publishState() {
        val my = accountPubkey ?: return
        mutableState.value = mutableState.value.copy(
            conversations = DmGrouping.group(messages.values.toList(), my),
            loaded = true,
        )
    }

    private companion object {
        const val MAX_STORED = 500
    }
}
