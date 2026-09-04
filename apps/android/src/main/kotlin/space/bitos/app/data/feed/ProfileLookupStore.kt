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
import space.bitos.core.model.ProfileMetadata
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

/**
 * One-shot multi-pubkey kind-0 lookup (legacy `SupportController` /
 * `ContributorsWidget` fetch parity): subscribes `kinds:[0] authors:[…]`,
 * keeps the NEWEST verified metadata per pubkey, and settles after a
 * timeout. Pure display projection; no cache, no persistence.
 */
class ProfileLookupStore(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val hasher: EventHasher = Sha256EventHasher,
) {
    private var collectJob: Job? = null
    private var settleJob: Job? = null

    private val _profiles = MutableStateFlow<Map<String, ProfileMetadata>>(emptyMap())
    val profiles: StateFlow<Map<String, ProfileMetadata>> = _profiles.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var seenAt = mutableMapOf<String, Long>()
    private var wanted: Set<String> = emptySet()

    init {
        collectJob = scope.launch {
            pool.verifiedFrames.collect { gated ->
                val event = (gated as? VerifiedPoolFrame.Verified)?.event ?: return@collect
                if (event.kind != space.bitos.core.model.NostrKinds.PROFILE_METADATA) return@collect
                val key = event.pubkey.value
                if (key !in wanted) return@collect
                val known = seenAt[key] ?: Long.MIN_VALUE
                if (event.createdAt < known) return@collect
                val metadata = ProfileMetadata.parse(event) ?: return@collect
                seenAt[key] = event.createdAt
                _profiles.value = _profiles.value + (key to metadata)
            }
        }
    }

    /** Looks up one or more pubkeys; settles (loading=false) after 8 s. */
    fun lookup(pubkeys: List<String>) {
        wanted = pubkeys.toSet()
        seenAt = mutableMapOf()
        _profiles.value = emptyMap()
        _loading.value = true
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(8_000)
            _loading.value = false
        }
        val authors = pubkeys.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
        pool.broadcast(
            NostrEventCodec.encodeRequest(
                "bitos-lookup",
                "{\"kinds\":[0],\"authors\":$authors,\"limit\":${(pubkeys.size * 2).coerceAtMost(20)}}",
            ),
        )
    }

    fun stop() {
        wanted = emptySet()
        _loading.value = false
        settleJob?.cancel()
    }
}
