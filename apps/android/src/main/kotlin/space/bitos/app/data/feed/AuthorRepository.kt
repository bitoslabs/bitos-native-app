package space.bitos.app.data.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.bitos.app.data.relay.RelayPool
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.NostrKinds
import space.bitos.core.model.ProfileMetadata
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

data class AuthorUiState(
    val pubkey: String? = null,
    val profile: ProfileMetadata? = null,
    val notes: List<FeedNote> = emptyList(),
    val isFollowing: Boolean = false,
    val isLoading: Boolean = true,
)

/**
 * Author profile repository: targeted profile + notes REQ for one pubkey,
 * verified fan-in, bounded window. One instance at a time (the open sheet).
 */
class AuthorRepository(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val hasher: EventHasher = Sha256EventHasher,
) {
    private val notes = LinkedHashMap<String, FeedNote>()
    private var profile: ProfileMetadata? = null
    private var profileAt = Long.MIN_VALUE
    private var pubkey: String? = null
    private var collectJob: Job? = null
    private var requested = false

    private val mutableState = MutableStateFlow(AuthorUiState())
    val state: StateFlow<AuthorUiState> = mutableState.asStateFlow()

    init {
        collectJob = scope.launch {
            pool.frames.collect { frame ->
                val event = runCatching {
                    NostrEventCodec.decodeRelayEvent(hasher, frame.message, frame.relay)
                }.getOrNull() ?: return@collect
                if (!NostrEventCodec.verifySignature(hasher, event)) return@collect
                val target = pubkey ?: return@collect
                if (event.pubkey.value != target) return@collect
                when {
                    event.kind == NostrKinds.PROFILE_METADATA -> {
                        val metadata = ProfileMetadata.parse(event) ?: return@collect
                        if (event.createdAt >= profileAt) {
                            profile = metadata
                            profileAt = event.createdAt
                            publishState()
                        }
                    }
                    FeedNote.isFeedKind(event.kind) -> {
                        val note = FeedNote.from(event)
                        if (!notes.containsKey(note.id)) {
                            notes[note.id] = note
                            publishState()
                        }
                    }
                }
            }
        }
        pool.start()
    }

    /** Opens the profile for one author; clears previous state. */
    fun open(authorPubkey: String) {
        pubkey = authorPubkey
        notes.clear()
        profile = null
        profileAt = Long.MIN_VALUE
        requested = false
        mutableState.value = AuthorUiState(pubkey = authorPubkey, isLoading = true)
        subscribe()
    }

    fun close() {
        pubkey = null
        mutableState.value = AuthorUiState()
    }

    /** Follow state is driven by the feed repository (single source). */
    fun setFollowing(following: Boolean) {
        mutableState.value = mutableState.value.copy(isFollowing = following)
    }

    private fun subscribe() {
        if (requested) return
        requested = true
        val target = pubkey ?: return
        val filter = """{"kinds":[0,1,22],"authors":["$target"],"limit":20}"""
        pool.broadcast(NostrEventCodec.encodeRequest("bitos-author", filter))
        // Settle: mark not-loading after a window even without results.
        scope.launch {
            kotlinx.coroutines.delay(3_000)
            if (mutableState.value.isLoading) {
                mutableState.value = mutableState.value.copy(isLoading = false)
            }
        }
    }

    private fun publishState() {
        mutableState.value = AuthorUiState(
            pubkey = pubkey,
            profile = profile,
            notes = notes.values.sortedByDescending { it.createdAt },
            isFollowing = mutableState.value.isFollowing,
            isLoading = false,
        )
    }
}
