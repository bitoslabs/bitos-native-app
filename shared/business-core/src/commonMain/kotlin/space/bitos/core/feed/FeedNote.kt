package space.bitos.core.feed

import space.bitos.core.model.MediaMetadata
import space.bitos.core.model.NostrEvent
import space.bitos.core.model.NostrKinds

/**
 * Normalized, display-oriented projection of a feed event. Mirrors the web
 * `toFeedNote` semantics: reply-parent resolution across NIP-10 markers and
 * NIP-22 uppercase conventions, hashtag/mention extraction and lightweight
 * content classification. Pure and deterministic; presentation-free.
 */
data class FeedNote(
    val id: String,
    val pubkey: String,
    val content: String,
    val createdAt: Long,
    val kind: Int,
    val replyTo: String?,
    /** APP-009 NIP-10 thread root (`e` marker `root` or first positional). */
    val threadRootId: String? = null,
    /** APP-009 NIP-10 immediate parent (marker `reply` or last positional). */
    val threadParentId: String? = null,
    val hashtags: List<String>,
    val mentions: List<String>,
    val mediaUrls: List<String>,
    val isProtocolPayload: Boolean,
    val video: MediaMetadata? = null,
    /** Reposter's pubkey when this note is displayed from a kind-6 repost. */
    val repostedBy: String? = null,
    /** NIP-36 content warning (tag `content-warning` / label `content warning`). */
    val contentWarning: Boolean = false,
) {
    companion object {
        private val hashtagPattern = Regex("(?:^|\\s)#([\\p{L}\\p{N}_-]{2,60})")
        private val mentionPattern = Regex("@([\\w.]{1,100})")
        private val mediaUrlPattern = Regex("https?://\\S+\\.(?:apng|avif|gif|jpe?g|png|webp)(?:[?#]\\S*)?", RegexOption.IGNORE_CASE)
        private val videoUrlPattern = Regex("https?://\\S+\\.(?:mp4|webm|mov|m4v)(?:[?#]\\S*)?", RegexOption.IGNORE_CASE)
        private val protocolPayloadPattern = Regex("^\\s*[\\[{]")

        fun from(event: NostrEvent): FeedNote {
            // Kind-6 reposts resolve to the embedded original with attribution.
            if (event.kind == space.bitos.core.model.NostrKinds.REPOST) {
                val resolved = RepostParser.resolve(event)
                if (resolved != null) {
                    val original = from(resolved.first)
                    return original.copy(repostedBy = resolved.second)
                }
                // Unresolvable reposts are invisible in the feed.
                return FeedNote(
                    id = event.id.value, pubkey = event.pubkey.value, content = "",
                    createdAt = event.createdAt, kind = event.kind, replyTo = null,
                    hashtags = emptyList(), mentions = emptyList(), mediaUrls = emptyList(),
                    isProtocolPayload = true,
                )
            }
            val replyTag = event.tags.firstOrNull { it.firstOrNull() == "e" && it.size >= 4 && it[3] == "reply" }
            val nip22Parent = event.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                ?: event.tags.firstOrNull { it.firstOrNull() == "E" }?.getOrNull(1)
            // APP-009: marker-aware thread anchors (root/parent per NIP-10).
            val (threadRootId, threadParentId) = ThreadAssembly.rootAndParent(event.tags)

            return FeedNote(
                id = event.id.value,
                pubkey = event.pubkey.value,
                content = event.content,
                createdAt = event.createdAt,
                kind = event.kind,
                replyTo = replyTag?.getOrNull(1) ?: nip22Parent,
                threadRootId = threadRootId,
                threadParentId = threadParentId,
                hashtags = hashtagPattern.findAll(event.content).mapNotNull { it.groupValues[1].takeIf(String::isNotBlank) }.distinct().take(24).toList(),
                mentions = mentionPattern.findAll(event.content).map { it.groupValues[1] }.distinct().take(24).toList(),
                mediaUrls = (mediaUrlPattern.findAll(event.content) + videoUrlPattern.findAll(event.content))
                    .map { it.value }.distinct().take(8).toList(),
                isProtocolPayload = protocolPayloadPattern.containsMatchIn(event.content),
                video = MediaMetadata.fromEvent(event),
                contentWarning = space.bitos.core.nostr.Nip36.hasContentWarning(event.tags),
            )
        }

        fun isFeedKind(kind: Int): Boolean = kind == NostrKinds.SHORT_TEXT_NOTE || kind == NostrKinds.VIDEO
    }
}

/**
 * Relay-observed aggregate for one note (counts are lower bounds until the
 * index reconciles them; UI must label partial coverage, never invent totals).
 */
data class NoteActivity(
    val reactions: Int = 0,
    val reposts: Int = 0,
    val replies: Int = 0,
    val zaps: Int = 0,
    val zapSats: Long = 0,
)
