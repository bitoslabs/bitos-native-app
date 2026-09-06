package space.bitos.app.data.stories

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.bitos.app.data.relay.RelayConnectionState
import space.bitos.app.data.relay.RelayPool
import space.bitos.app.data.relay.VerifiedPoolFrame
import space.bitos.app.ui.stories.StorySeenPrefs
import space.bitos.core.model.Stories
import space.bitos.core.model.StoriesInteractions
import space.bitos.core.model.StoryAuthor
import space.bitos.core.model.StorySlide
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

/** One reaction row for the activity sheet (web `StoryReaction` parity). */
data class StoryReactionUi(
    val pubkey: String,
    val emoji: String,
    val at: Long,
)

/** One public reply (web `StoryReply` parity). */
data class StoryReplyUi(
    val id: String,
    val pubkey: String,
    val text: String,
    val at: Long,
)

/** Aggregated engagement for one slide (web `StoryInteraction` parity). */
data class StoryInteractionUi(
    val likeCount: Int = 0,
    val viewCount: Int = 0,
    val replyCount: Int = 0,
    val zapCount: Int = 0,
    val zapSats: Long = 0,
    val likedByMe: Boolean = false,
    val myLikeEventId: String? = null,
    val likes: List<StoryReactionUi> = emptyList(),
    val replies: List<StoryReplyUi> = emptyList(),
)

data class StoriesUiState(
    val authors: List<StoryAuthor> = emptyList(),
    val publicAuthors: List<StoryAuthor> = emptyList(),
    val seenIds: Set<String> = emptySet(),
    val hasAccount: Boolean = false,
    /** Per-slide engagement for the viewer (slide id → snapshot). */
    val interactions: Map<String, StoryInteractionUi> = emptyMap(),
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
    private val slides = LinkedHashMap<String, StorySlide>() // own + followed, id → slide
    private val publicSlides = LinkedHashMap<String, StorySlide>() // relay discovery, id → slide
    private val deletedIds = HashSet<String>()
    private var followingPubkeys: Set<String> = emptySet()
    private var accountPubkey: String? = null
    private var collectJob: Job? = null
    private var resubscribeJob: Job? = null
    private var requested = false
    private var seenIds: Set<String> = emptySet()

    // Engagement lanes (web `stories` parity): latest reaction per pubkey,
    // replies by event id, zap receipts by event id; the seen-set dedupes
    // relay replays and is FIFO-evicted to stay bounded.
    private data class ReactionRow(val emoji: String, val at: Long, val eventId: String)

    private val reactionsBySlide = HashMap<String, LinkedHashMap<String, ReactionRow>>()
    private val repliesBySlide = HashMap<String, LinkedHashMap<String, StoryReplyUi>>()
    private val zapsBySlide = HashMap<String, LinkedHashMap<String, Long>>()
    private val activitySeen = LinkedHashSet<String>()

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
            publicSlides.clear()
            reactionsBySlide.clear()
            repliesBySlide.clear()
            zapsBySlide.clear()
            activitySeen.clear()
            publishState()
        }
        // Public discovery works in browse mode too; account/following
        // stories are simply absent until an identity is available.
        start()
        subscribe()
    }

    fun markSeen(slideId: String, context: Context) {
        StorySeenPrefs.markSeen(context, slideId)
        seenIds = seenIds + slideId
        publishState()
    }

    /** Engagement REQ for the viewer's slides (web `loadActivity` parity). */
    fun loadActivity(slidesToLoad: List<StorySlide>) {
        val addresses = slidesToLoad.mapNotNull { StoriesInteractions.addressOf(it) }
        val filters = StoriesInteractions.activityFilters(slidesToLoad.map { it.id }, addresses)
            ?: return
        pool.broadcast(NostrEventCodec.encodeRequest("bitos-story-activity", filters))
    }

    /** Drops a slide locally (own kind-5 deletion publish, web parity). */
    fun removeSlide(slideId: String) {
        if (slides.remove(slideId) != null || publicSlides.remove(slideId) != null) {
            publishState()
        }
    }

    private fun start() {
        if (collectJob != null) return
        pool.start()
        collectJob = scope.launch {
            pool.verifiedFrames.collect { gated ->
                val event = (gated as? VerifiedPoolFrame.Verified)?.event ?: return@collect
                when (event.kind) {
                    Stories.STORY_KIND -> ingestStory(event)
                    Stories.DELETE_KIND -> ingestDeletion(event)
                    StoriesInteractions.REACTION_KIND,
                    StoriesInteractions.REPLY_KIND,
                    StoriesInteractions.ZAP_KIND,
                    -> ingestActivity(event)
                }
            }
        }
        // Deliver-once guard: transports DROP sends to unconnected sockets,
        // and a REQ raced with connection startup would otherwise be lost for
        // the whole session (requested stays unlatched, so subscribe() retries
        // on the first connected-relay transition and no-ops afterwards).
        resubscribeJob = scope.launch {
            pool.statesFlow.collect { states ->
                if (states.values.any { it == RelayConnectionState.CONNECTED }) subscribe()
            }
        }
    }

    private fun ingestStory(event: space.bitos.core.model.NostrEvent) {
        val now = System.currentTimeMillis() / 1000
        val slide = Stories.parseSlide(event, now) ?: return
        if (slide.id in deletedIds) return
        val target = if (isKnownAuthor(slide.pubkey)) slides else publicSlides
        // Insert through the shared rule, keeping discovery separate from the
        // account/following lane so the UI can label it truthfully.
        val updated = Stories.insert(target.values.toList(), slide)
        target.clear()
        updated.forEach { target[it.id] = it }
        publishState()
    }

    private fun ingestDeletion(event: space.bitos.core.model.NostrEvent) {
        var removed = false
        for (tag in event.tags) {
            if (tag.firstOrNull() == "e" && tag.size > 1) {
                deletedIds.add(tag[1])
                if (slides.remove(tag[1]) != null) removed = true
                if (publicSlides.remove(tag[1]) != null) removed = true
            }
        }
        if (removed) publishState()
    }

    /** Classify + fold one engagement event into the per-slide lanes. */
    private fun ingestActivity(event: space.bitos.core.model.NostrEvent) {
        if (activitySeen.contains(event.id.value)) return
        activitySeen.add(event.id.value)
        if (activitySeen.size > 4_000) activitySeen.remove(activitySeen.first())
        val tracked = slides.values + publicSlides.values
        val addresses = tracked.mapNotNull { s -> StoriesInteractions.addressOf(s)?.let { it to s.id } }.toMap()
        val activity = StoriesInteractions.project(event, tracked.map { it.id }.toSet(), addresses) ?: return
        when (activity.type) {
            StoriesInteractions.StoryActivityEvent.Type.LIKE,
            StoriesInteractions.StoryActivityEvent.Type.VIEW,
            -> {
                // Keep the latest reaction per pubkey (web ingestActivity).
                val lane = reactionsBySlide.getOrPut(activity.slideId) { LinkedHashMap() }
                val prev = lane[activity.pubkey]
                if (prev == null || prev.at <= activity.at) {
                    lane[activity.pubkey] = ReactionRow(activity.emoji, activity.at, activity.eventId)
                }
            }
            StoriesInteractions.StoryActivityEvent.Type.REPLY ->
                repliesBySlide.getOrPut(activity.slideId) { LinkedHashMap() }[activity.eventId] =
                    StoryReplyUi(activity.eventId, activity.pubkey, activity.text, activity.at)
            StoriesInteractions.StoryActivityEvent.Type.ZAP ->
                zapsBySlide.getOrPut(activity.slideId) { LinkedHashMap() }[activity.eventId] = activity.sats
        }
        publishState()
    }

    private fun subscribe() {
        if (requested) return
        val authors = listOfNotNull(accountPubkey) + followingPubkeys.take(50)
        // Latch only when the REQ can leave the process — sends to
        // unconnected sockets are dropped, so an unlatched request retries
        // via the statesFlow watcher in start().
        if (pool.states.values.any { it == RelayConnectionState.CONNECTED }) {
            requested = true
        }
        if (authors.isNotEmpty()) {
            val authorsJson = authors.joinToString("\",\"", prefix = "[\"", postfix = "\"]")
            val filter = """{"kinds":[${Stories.STORY_KIND},${Stories.DELETE_KIND}],"authors":$authorsJson,"limit":100}"""
            pool.broadcast(NostrEventCodec.encodeRequest("bitos-stories", filter))
        }
        // A small, bounded relay-wide sample keeps the public lane useful
        // when the account does not follow anyone with an active story.
        pool.broadcast(NostrEventCodec.encodeRequest(
            "bitos-public-stories",
            """{"kinds":[${Stories.STORY_KIND}],"limit":20}""",
        ))
    }

    private fun isKnownAuthor(pubkey: String): Boolean =
        pubkey == accountPubkey || pubkey in followingPubkeys

    private fun publishState() {
        val now = System.currentTimeMillis() / 1000
        mutableState.value = StoriesUiState(
            authors = Stories.authors(slides.values.toList(), now, ownPubkey = accountPubkey),
            publicAuthors = Stories.authors(publicSlides.values.toList(), now)
                .map { it.copy(isPublicDiscovery = true) },
            seenIds = seenIds,
            hasAccount = accountPubkey != null,
            interactions = buildInteractions(),
        )
    }

    /** Web `buildInteraction` parity: likes/views/zaps/replies per slide. */
    private fun buildInteractions(): Map<String, StoryInteractionUi> {
        val me = accountPubkey ?: return emptyMap()
        val slideIds = (slides.keys + publicSlides.keys).toSet()
        return buildMap(slideIds.size) {
            for (slideId in slideIds) {
                val reactions = reactionsBySlide[slideId] ?: emptyMap()
                val replyMap = repliesBySlide[slideId] ?: emptyMap()
                val zapMap = zapsBySlide[slideId] ?: emptyMap()
                val likes = reactions.entries
                    .filter { !StoriesInteractions.isViewContent(it.value.emoji) }
                    .sortedByDescending { it.value.at }
                    .map { (pubkey, row) -> StoryReactionUi(pubkey, row.emoji, row.at) }
                val replies = replyMap.values.sortedBy { it.at }.take(100)
                val viewers = reactions.filter { StoriesInteractions.isViewContent(it.value.emoji) }.keys +
                    replies.map { it.pubkey }
                val myLike = reactions.entries.firstOrNull { it.key == me && !StoriesInteractions.isViewContent(it.value.emoji) }
                put(
                    slideId,
                    StoryInteractionUi(
                        likeCount = likes.size,
                        viewCount = viewers.size,
                        replyCount = replies.size,
                        zapCount = zapMap.size,
                        zapSats = zapMap.values.sum(),
                        likedByMe = myLike != null,
                        myLikeEventId = myLike?.value?.eventId,
                        likes = likes.take(100),
                        replies = replies,
                    ),
                )
            }
        }
    }
}
