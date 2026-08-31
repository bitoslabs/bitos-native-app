package space.bitos.app.data.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.bitos.app.data.relay.RelayPool
import space.bitos.core.model.InterestSet
import space.bitos.core.model.NostrEvent
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

/**
 * NIP-51 followed hashtags (kind 30015 d=interest) — web `hashtag-follows`
 * parity: the account's interest-set head syncs from relays (newest verified
 * wins); toggles flip optimistically and publish through the receipt
 * machine. One instance per process.
 */
class HashtagFollowsStore(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val hasher: EventHasher = Sha256EventHasher,
) {
    private val mutableState = MutableStateFlow<Set<String>>(emptySet())
    val hashtags: StateFlow<Set<String>> = mutableState.asStateFlow()

    private var accountPubkey: String? = null
    private var head: NostrEvent? = null
    private var collectJob: Job? = null
    private var requested = false

    init {
        collectJob = scope.launch {
            pool.frames.collect { frame ->
                val account = accountPubkey ?: return@collect
                val event = runCatching {
                    NostrEventCodec.decodeRelayEvent(hasher, frame.message, frame.relay)
                }.getOrNull() ?: return@collect
                if (!NostrEventCodec.verifySignature(hasher, event)) return@collect
                if (event.kind != InterestSet.KIND || event.pubkey.value != account) return@collect
                // Replaceable head: newest verified list wins.
                if (head != null && event.createdAt <= head!!.createdAt) return@collect
                head = event
                mutableState.value = InterestSet.followedHashtags(event).toSet()
            }
        }
        pool.start()
    }

    /** Account lifecycle: re-opens the interest-set head REQ. */
    fun setAccount(pubkey: String?) {
        accountPubkey = pubkey
        head = null
        requested = false
        mutableState.value = emptySet()
        subscribe()
    }

    /** Optimistic local flip; the caller publishes the resulting set. */
    fun toggle(tag: String): Set<String> {
        val normalized = InterestSet.normalize(tag)
        if (!InterestSet.isValid(normalized)) return mutableState.value
        val updated = if (normalized in mutableState.value) {
            mutableState.value - normalized
        } else {
            mutableState.value + normalized
        }
        mutableState.value = updated
        return updated
    }

    fun isFollowed(tag: String): Boolean =
        InterestSet.normalize(tag) in mutableState.value

    private fun subscribe() {
        val account = accountPubkey ?: return
        if (requested) return
        requested = true
        val filter = """{"kinds":[${InterestSet.KIND}],"authors":["$account"],"#d":["${InterestSet.D_TAG}"],"limit":1}"""
        pool.broadcast(NostrEventCodec.encodeRequest("bitos-interest", filter))
    }
}
