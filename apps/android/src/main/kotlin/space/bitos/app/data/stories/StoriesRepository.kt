package space.bitos.app.data.stories

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.bitos.app.data.relay.RelayPool
import space.bitos.app.ui.stories.StorySeenPrefs
import space.bitos.core.model.Stories
import space.bitos.core.model.StoryAuthor
import space.bitos.core.model.StorySlide
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

data class StoriesUiState(
    val authors: List<StoryAuthor> = emptyList(),
    val seenIds: Set<String> = emptySet(),
    val hasAccount: Boolean = false,
)

/**
 * APP-006 stories repository: subscribes kind-30315 stories for the
 * account + everyone followed (#p or authors filter), kind-5 deletions
 * remove slides by e-tag, expired pruned, seen persisted (legacy
 * `StoriesController` parity).
 */
class StoriesRepository(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val hasher: EventHasher = Sha256EventHasher,
    private val seenStore: (android.content.Context) -> Unit = {},
) {
    private val slides = LinkedHashMap<String, StorySlide>() // id → slide
    private val deletedIds = HashSet<String>()
    private var followingPubkeys: Set<String> = emptySet()
    private var accountPubkey: String? = null
    private var collectJob: Job? = null
    private var requested = false
    private var seenIds: Set<String> = emptySet()

    private val mutableState = MutableStateFlow(StoriesUiState())
    val state: StateFlow<StoriesUiState> = mutableState.asStateFlow()

    fun setAccount(pubkey: String?, following: Set<String>, context: Context) {
        val changed = accountPubkey != pubkey || followingPubkeys != following
        accountPubkey = pubkey
        followingPubkeys = following
        seenIds = StorySeenPrefs.seenIds(context)
        if (changed) {
            requested = false
            slides.clear()
            publishState()
        }
        if (pubkey != null) {
            start()
            subscribe()
        } else {
            collectJob?.cancel()
            collectJob = null
        }
    }

    fun markSeen(slideId: String, context: Context) {
        StorySeenPrefs.markSeen(context, slideId)
        seenIds = seenIds + slideId
        publishState()
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
                when (event.kind) {
                    Stories.STORY_KIND -> ingestStory(event)
                    Stories.DELETE_KIND -> ingestDeletion(event)
                }
            }
        }
    }

    private fun ingestStory(event: space.bitos.core.model.NostrEvent) {
        val now = System.currentTimeMillis() / 1000
        val slide = Stories.parseSlide(event, now) ?: return
        if (slide.id in deletedIds) return
        val current = Stories.authors(slides.values.toList(), now)
        // Insert through the shared rule.
        val updated = Stories.insert(slides.values.toList(), slide)
        slides.clear()
        updated.forEach { slides[it.id] = it }
        publishState()
    }

    private fun ingestDeletion(event: space.bitos.core.model.NostrEvent) {
        var removed = false
        for (tag in event.tags) {
            if (tag.firstOrNull() == "e" && tag.size > 1) {
                deletedIds.add(tag[1])
                if (slides.remove(tag[1]) != null) removed = true
            }
        }
        if (removed) publishState()
    }

    private fun subscribe() {
        if (requested) return
        requested = true
        val authors = listOfNotNull(accountPubkey) + followingPubkeys.take(50)
        if (authors.isEmpty()) return
        val authorsJson = authors.joinToString("\",\"", prefix = "[\"", postfix = "\"]")
        val filter = """{"kinds":[${Stories.STORY_KIND},${Stories.DELETE_KIND}],"authors":$authorsJson,"limit":100}"""
        pool.broadcast(NostrEventCodec.encodeRequest("bitos-stories", filter))
    }

    private fun publishState() {
        val now = System.currentTimeMillis() / 1000
        mutableState.value = StoriesUiState(
            authors = Stories.authors(slides.values.toList(), now),
            seenIds = seenIds,
            hasAccount = accountPubkey != null,
        )
    }
}
