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
    /** Allowlisted external-video link cards; never an executable embed. */
    val externalVideoPreviews: List<ExternalVideoPreview> = emptyList(),
    val isProtocolPayload: Boolean,
    val video: MediaMetadata? = null,
    /** Reposter's pubkey when this note is displayed from a kind-6 repost. */
    val repostedBy: String? = null,
    /** NIP-36 content warning (tag `content-warning` / label `content warning`). */
    val contentWarning: Boolean = false,
    /** APP-008 poll projection (kind-1 + poll_option tags; null = not a poll). */
    val poll: space.bitos.core.model.Poll? = null,
    /** APP-007 remix source (remix tag), null = original work. */
    val remixOfEventId: String? = null,
    /** APP-007 remix source author (first p tag alongside the remix marker). */
    val remixOfPubkey: String? = null,
    /** APP-007 relay hints from the remix tag (≤ [RemixRules.MAX_RELAY_HINTS]). */
    val remixRelays: List<String> = emptyList(),
    /**
     * MST-042 raw `meme` layout payload (≤ [space.bitos.core.studio.MemeRemix.MAX_TAG_CHARS]);
     * null = the note carries none. Editor-side decode seeds a remix.
     */
    val memeTag: String? = null,
    /** "Use this sound" (MST-050): the borrowed-sound source parsed from
     *  the `sound` tag — drives the card's ♪ chip and the Wave C seed. */
    val soundOf: space.bitos.core.studio.MemeSoundRules.Source? = null,
    /** APP-007 `license` tag value (remix advisory gate), null = permissive. */
    val license: String? = null,
    /** Advisory `["bitz:zaps", "off"]` marker — cards hide the zap action. */
    val zapsDisabled: Boolean = false,
) {
    companion object {
        private val hashtagPattern = Regex("(?:^|\\s)#([\\p{L}\\p{N}_-]{2,60})")
        private val mentionPattern = Regex("@([\\w.]{1,100})")
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
            // NIP-22 comments: lowercase `e` parent, UPPERCASE `E` root
            // fallback (web toFeedNote parity).
            val nip22Parent = event.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                ?: event.tags.firstOrNull { it.firstOrNull() == "E" }?.getOrNull(1)
                ?: event.tags.firstOrNull { it.firstOrNull() == "E" }?.getOrNull(1)
            // APP-009: marker-aware thread anchors (root/parent per NIP-10).
        val (threadRootId, threadParentId) = ThreadAssembly.rootAndParent(event.tags)
        val remixSource = RemixRules.sourceOf(event.tags)

        return FeedNote(
            id = event.id.value,
            pubkey = event.pubkey.value,
            content = event.content,
            createdAt = event.createdAt,
            kind = event.kind,
            replyTo = replyTag?.getOrNull(1) ?: nip22Parent,
            threadRootId = threadRootId,
            threadParentId = threadParentId,
            poll = space.bitos.core.model.PollContract.poll(event),
            hashtags = hashtagPattern.findAll(event.content).mapNotNull { it.groupValues[1].takeIf(String::isNotBlank) }.distinct().take(24).toList(),
            mentions = mentionPattern.findAll(event.content).map { it.groupValues[1] }.distinct().take(24).toList(),
            mediaUrls = InlineMediaUrls.fromContent(event.content),
            externalVideoPreviews = ExternalVideoPreviews.fromContent(event.content),
            isProtocolPayload = space.bitos.core.nostr.ContentClassification.isProtocolPayload(event.content),
            video = MediaMetadata.fromEvent(event),
            contentWarning = space.bitos.core.nostr.Nip36.hasContentWarning(event.tags, event.content),
            remixOfEventId = remixSource?.eventId,
            remixOfPubkey = remixSource?.pubkey,
            remixRelays = remixSource?.relays ?: emptyList(),
            memeTag = memeTagOf(event.tags),
            soundOf = space.bitos.core.studio.MemeSoundRules.sourceOf(event.tags),
            license = RemixRules.licenseOf(event.tags),
            zapsDisabled = ZapPolicy.isDisabled(event.tags),
        )
        }

        fun isFeedKind(kind: Int): Boolean = kind in NostrKinds.feedKinds

        /**
         * Raw `meme` layout payload, bounded at the compact codec's own cap
         * (longer tags decode to nothing, so projecting them is pointless).
         */
        private fun memeTagOf(tags: List<List<String>>): String? =
            tags.firstOrNull { it.firstOrNull() == "meme" && it.size >= 2 }
                ?.get(1)
                ?.takeIf { it.length <= space.bitos.core.studio.MemeRemix.MAX_TAG_CHARS }
    }
}

/**
 * Extracts image/video attachments from bare or Markdown links. Hosts such as
 * X commonly put the media type in `?format=jpg`, so path extensions alone
 * are insufficient. The extractor is pure, allowlisted and bounded because
 * its result drives native image/video loading.
 */
object InlineMediaUrls {
    private val imageType = Regex("\\.(?:apng|avif|gif|jpe?g|png|webp)(?:$|[?#])|[?&](?:format|fm|ext)=(?:apng|avif|gif|jpe?g|png|webp)(?:$|[&#])", RegexOption.IGNORE_CASE)
    private val videoType = Regex("\\.(?:mp4|webm|mov|m4v)(?:$|[?#])|[?&](?:format|fm|ext)=(?:mp4|webm|mov|m4v)(?:$|[&#])", RegexOption.IGNORE_CASE)

    fun fromContent(content: String): List<String> =
        space.bitos.core.nostr.Nip27.tokenize(content)
            .filterIsInstance<space.bitos.core.nostr.RichToken.Link>()
            .map { it.url }
            .filter(::isMediaUrl)
            .distinct()
            .take(8)

    fun isMediaUrl(url: String): Boolean = imageType.containsMatchIn(url) || videoType.containsMatchIn(url)

    fun isVideoUrl(url: String): Boolean = videoType.containsMatchIn(url)
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
