package space.bitos.core.publish

import space.bitos.core.model.NostrKinds
import space.bitos.core.model.NostrLimits
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

/** Injected clock port (SBC-004): deterministic now for tests. */
fun interface PublishClock {
    fun nowSeconds(): Long
}

/** An unsigned, ID-committed event awaiting exactly one signature. */
data class UnsignedNote(
    val idHex: String,
    val pubkeyHex: String,
    val createdAtSeconds: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
) {
    fun messageBytes(): ByteArray = idHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** One relay's `["OK", id, bool, message]` receipt (PUB-008). */
data class RelayOk(
    val eventId: String,
    val accepted: Boolean,
    val message: String,
)

/**
 * Text-note composition and publish-message encoding (PUB-007 note path).
 *
 * The unsigned event is built from bounded content with a canonical,
 * codec-verified ID; the signature is attached only when the signer returns
 * one — signer refusal leaves the pipeline failed with the draft intact on
 * the caller side. Pure: no relay, storage or secret access.
 */
class NoteComposer(
    private val hasher: EventHasher = Sha256EventHasher,
    private val clock: PublishClock,
) {

    /** Builds the unsigned kind-1 note; null when content is out of bounds. */
    fun composeTextNote(pubkeyHex: String, content: String): UnsignedNote? =
        composeTextNote(pubkeyHex, content, emptyList())

    /** APP-008: kind-1 with explicit tags (composer derives them via
     * `ComposerRules.deriveTags` — hashtags, NIP-27 entities, NIP-36). */
    fun composeTextNote(pubkeyHex: String, content: String, tags: List<List<String>>): UnsignedNote? {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NOTE_LENGTH) return null
        if (tags.size > space.bitos.core.model.NostrLimits.MAX_TAGS) return null
        return compose(pubkeyHex, NostrKinds.SHORT_TEXT_NOTE, tags, trimmed)
    }

    /**
     * APP-008 poll (legacy wire: kind-1 question + `poll_option` tags +
     * hashtag t-tags from the question — web `feed.postPoll` parity).
     */
    fun composePoll(pubkeyHex: String, question: String, options: List<String>): UnsignedNote? {
        val pollTags = space.bitos.core.model.PollContract.pollTags(question, options) ?: return null
        val trimmed = question.trim()
        val hashtagTags = space.bitos.core.publish.ComposerRules.deriveTags(trimmed)
            .filter { it.firstOrNull() == "t" }
        return compose(pubkeyHex, NostrKinds.SHORT_TEXT_NOTE, pollTags + hashtagTags, trimmed)
    }

    /** Builds the unsigned kind-7 reaction (NIP-25) targeting an event. */
    fun composeReaction(targetEventId: String, targetPubkey: String, authorPubkey: String): UnsignedNote? {
        if (!targetEventId.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (!targetPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        return compose(
            authorPubkey,
            NostrKinds.GENERIC_REACTION,
            listOf(listOf("e", targetEventId), listOf("p", targetPubkey)),
            "+",
        )
    }

    /**
     * Builds the unsigned kind-7 reaction with caller-provided tags (APP-006
     * story likes: e/p/a target tags from `StoriesInteractions.targetTags`,
     * content "❤️" — web `stories.like` parity).
     */
    fun composeReactionWithTags(pubkeyHex: String, emoji: String, tags: List<List<String>>): UnsignedNote? {
        if (!pubkeyHex.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (tags.isEmpty() || tags.size > space.bitos.core.model.NostrLimits.MAX_TAGS) return null
        val boundedEmoji = emoji.trim().take(16)
        if (boundedEmoji.isEmpty()) return null
        return compose(pubkeyHex, NostrKinds.GENERIC_REACTION, tags, boundedEmoji)
    }

    /**
     * APP-006 story publish (web `stories.publish` parity): kind-30315 with a
     * unique `d`, 24 h expiration, one NIP-92 imeta per image (alt on the
     * first only), an optional video imeta (`url` + `m video/…` + `thumb`
     * + `duration`), the `background` gradient for text-only slides and a
     * content-warning tag for sensitive media. Media URLs mirror into the
     * content for link-only clients.
     */
    fun composeStory(
        pubkeyHex: String,
        text: String,
        imageUrls: List<String>,
        background: String?,
        altText: String?,
        sensitive: Boolean,
        dTag: String,
        videoUrl: String? = null,
        videoMime: String? = null,
        videoDurationMs: Long? = null,
        videoPoster: String? = null,
    ): UnsignedNote? = storyEvent(
        pubkeyHex, text, imageUrls, background, altText, sensitive, dTag,
        clock.nowSeconds(), emptyList(), videoUrl, videoMime, videoDurationMs, videoPoster,
    )

    /**
     * Kind-30315 story with a pre-mined NIP-13 nonce tag (APP-006 story
     * PowCard path): the template [space.bitos.core.nostr.Pow.mineChunk]
     * hashed is exactly this event's fields at [createdAtSeconds] — the
     * expiration derives from that timestamp (not the wall clock) so a
     * mined nonce stays valid between mining and publish, and the nonce
     * tag is appended last to byte-match the miner's serialization.
     */
    fun composeStoryWithPow(
        pubkeyHex: String,
        text: String,
        imageUrls: List<String>,
        background: String?,
        altText: String?,
        sensitive: Boolean,
        dTag: String,
        nonce: Long,
        targetDifficulty: Int,
        createdAtSeconds: Long,
        videoUrl: String? = null,
        videoMime: String? = null,
        videoDurationMs: Long? = null,
        videoPoster: String? = null,
    ): UnsignedNote? = storyEvent(
        pubkeyHex, text, imageUrls, background, altText, sensitive, dTag,
        createdAtSeconds, listOf(space.bitos.core.nostr.Pow.nonceTag(nonce, targetDifficulty)),
        videoUrl, videoMime, videoDurationMs, videoPoster,
    )

    /** The kind-30315 template both story paths share (tags, content, id). */
    private fun storyEvent(
        pubkeyHex: String,
        text: String,
        imageUrls: List<String>,
        background: String?,
        altText: String?,
        sensitive: Boolean,
        dTag: String,
        createdAtSeconds: Long,
        extraTags: List<List<String>>,
        videoUrl: String? = null,
        videoMime: String? = null,
        videoDurationMs: Long? = null,
        videoPoster: String? = null,
    ): UnsignedNote? {
        if (createdAtSeconds <= 0) return null
        if (!pubkeyHex.matches(Regex("^[0-9a-f]{64}$"))) return null
        val boundedText = text.trim().take(280)
        val boundedImages = imageUrls
            .filter { it.startsWith("https://") }
            .distinct()
            .take(space.bitos.core.model.Stories.MAX_STORY_IMAGES)
        // One video per slide (web `stories` parity: the first video imeta
        // is the slide's video; the viewer plays it instead of a carousel).
        val boundedVideo = videoUrl?.trim()?.takeIf { it.startsWith("https://") && it.length <= 1024 }
        if (boundedText.isEmpty() && boundedImages.isEmpty() && boundedVideo == null) return null
        if (dTag.isBlank() || dTag.length > 128) return null
        val tags = mutableListOf(
            listOf("d", dTag),
            listOf("expiration", (createdAtSeconds + space.bitos.core.model.Stories.STORY_TTL_SECONDS).toString()),
        )
        // Hashtags from the caption (web extractHashtagTags).
        space.bitos.core.publish.ComposerRules.deriveTags(boundedText)
            .filter { it.firstOrNull() == "t" }
            .forEach { tags.add(it) }
        if (boundedImages.isEmpty() && boundedVideo == null && !background.isNullOrBlank() && background.length <= 256) {
            tags.add(listOf("background", background.trim()))
        }
        if (sensitive && (boundedImages.isNotEmpty() || boundedVideo != null)) {
            tags.add(listOf("content-warning", "Sensitive media"))
        }
        val boundedAlt = altText?.trim()?.take(280)
        boundedImages.forEachIndexed { index, url ->
            val imeta = mutableListOf("url $url")
            if (index == 0 && !boundedAlt.isNullOrBlank()) imeta.add("alt $boundedAlt")
            tags.add(listOf("imeta") + imeta)
        }
        if (boundedVideo != null) {
            val mime = videoMime?.trim()
                ?.takeIf { it.startsWith("video/") && it.length <= 64 }
                ?: "video/mp4"
            val imeta = mutableListOf("url $boundedVideo", "m $mime")
            // `thumb` (NIP-92): poster frame — the instant rail/viewer
            // preview while the video streams.
            videoPoster?.trim()
                ?.takeIf { it.startsWith("https://") && it.length <= 1024 }
                ?.let { imeta.add("thumb $it") }
            // `duration <seconds>s` (NIP-92): the viewer's auto-advance cap.
            // Integer math — never locale-formatted.
            val tenths = ((videoDurationMs ?: 0L).coerceAtLeast(0L)) / 100
            if (tenths > 0) {
                val whole = tenths / 10
                val fraction = tenths % 10
                imeta.add(if (fraction == 0L) "duration ${whole}s" else "duration $whole.${fraction}s")
            }
            tags.add(listOf("imeta") + imeta)
        }
        tags.addAll(extraTags)
        val content = (listOf(boundedText) + boundedImages + listOfNotNull(boundedVideo))
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val id = NostrEventCodec.computeId(hasher, pubkeyHex, createdAtSeconds, space.bitos.core.model.Stories.STORY_KIND, tags, content)
        return UnsignedNote(id, pubkeyHex, createdAtSeconds, space.bitos.core.model.Stories.STORY_KIND, tags, content)
    }

    /**
     * Builds the unsigned kind-5 deletion (NIP-09): one `e` tag per target
     * event, bounded, authored by the original event's key only. Web
     * `feed.deleteNote` parity (content "Deleted from BitOS").
     */
    fun composeDeletion(
        targetEventIds: List<String>,
        authorPubkey: String,
        reason: String = "Deleted from BitOS",
    ): UnsignedNote? {
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val bounded = targetEventIds
            .filter { it.matches(Regex("^[0-9a-f]{64}$")) }
            .distinct()
            .take(MAX_DELETION_TARGETS)
        if (bounded.isEmpty()) return null
        val boundedReason = reason.take(140)
        return compose(
            authorPubkey,
            NostrKinds.EVENT_DELETION,
            bounded.map { listOf("e", it) },
            boundedReason,
        )
    }

    /**
     * Builds the unsigned kind-1 reply (NIP-10): `e` tag with the "reply"
     * marker and optional relay hint, `p` tag naming the parent author.
     */
    fun composeReply(
        content: String,
        targetEventId: String,
        targetPubkey: String,
        authorPubkey: String,
        relayHint: String? = null,
    ): UnsignedNote? {
        if (!targetEventId.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (!targetPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NOTE_LENGTH) return null
        val eTag = if (!relayHint.isNullOrBlank() && relayHint.length <= 512) {
            listOf("e", targetEventId, relayHint, "reply")
        } else {
            listOf("e", targetEventId, "", "reply")
        }
        return compose(
            authorPubkey,
            NostrKinds.SHORT_TEXT_NOTE,
            listOf(eTag, listOf("p", targetPubkey)),
            trimmed,
        )
    }

    /**
     * Builds the unsigned kind-3 contact list (NIP-02) for the given
     * follow set — one `p` tag per followed pubkey, bounded and validated.
     */
    fun composeFollowList(authorPubkey: String, follows: List<String>): UnsignedNote? {
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val bounded = follows
            .filter { it.matches(Regex("^[0-9a-f]{64}$")) && it != authorPubkey } // no self-follow
            .distinct()
            .take(space.bitos.core.model.ContactList.MAX_FOLLOWS)
        return compose(
            authorPubkey,
            NostrKinds.CONTACT_LIST,
            bounded.map { listOf("p", it) },
            "",
        )
    }

    /** NIP-51 interest set (kind 30015, d=interest) — followed hashtags. */
    fun composeInterestSet(authorPubkey: String, hashtags: List<String>): UnsignedNote? {
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val bounded = hashtags
            .map(space.bitos.core.model.InterestSet::normalize)
            .filter(space.bitos.core.model.InterestSet::isValid)
            .distinct()
            .take(space.bitos.core.model.InterestSet.MAX_TAGS)
        return compose(
            authorPubkey,
            space.bitos.core.model.InterestSet.KIND,
            listOf(listOf("d", space.bitos.core.model.InterestSet.D_TAG)) +
                bounded.map { listOf("t", it) },
            "",
        )
    }

    /**
     * Builds the unsigned NIP-65 relay list (kind 10002): one `r` tag per
     * managed relay — marker omitted for read+write, `"read"`/`"write"`
     * otherwise. Empty or role-less sets compose nothing.
     */
    fun composeRelayList(
        authorPubkey: String,
        entries: List<space.bitos.core.model.RelayEntry>,
    ): UnsignedNote? {
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val bounded = space.bitos.core.model.RelayListContract.normalize(entries)
        if (bounded.isEmpty()) return null
        return compose(
            authorPubkey,
            space.bitos.core.model.RelayListContract.KIND,
            space.bitos.core.model.RelayListContract.nip65Tags(bounded),
            "",
        )
    }

    /**
     * Builds the unsigned NIP-51 public block list (kind 10004): one `p`
     * tag per blocked pubkey, bounded and validated. An empty set composes
     * nothing (publishing an unblock-everything head is explicit at the UI).
     */
    fun composeBlockList(authorPubkey: String, blocked: List<String>): UnsignedNote? {
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val bounded = blocked
            .filter { it.matches(Regex("^[0-9a-f]{64}$")) }
            .distinct()
            .take(space.bitos.core.model.BlockList.MAX_BLOCKS)
        if (bounded.isEmpty()) return null
        return compose(
            authorPubkey,
            space.bitos.core.model.BlockList.KIND,
            bounded.map { listOf("p", it) },
            "",
        )
    }

    /**
     * Builds the unsigned kind-6 repost (NIP-18): `e` tag naming the target
     * (optional relay hint), `p` tag naming its author, empty content.
     */
    fun composeRepost(
        targetEventId: String,
        targetPubkey: String,
        authorPubkey: String,
        relayHint: String? = null,
    ): UnsignedNote? {
        if (!targetEventId.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (!targetPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val eTag = if (!relayHint.isNullOrBlank() && relayHint.length <= 512) {
            listOf("e", targetEventId, relayHint, "")
        } else {
            listOf("e", targetEventId, "", "")
        }
        return compose(
            authorPubkey,
            NostrKinds.REPOST,
            listOf(eTag, listOf("p", targetPubkey)),
            "",
        )
    }

    /**
     * Builds the unsigned NIP-51 bookmark list (kind 30003, addressable at
     * the empty `d` coordinate): `d` + one `e` tag per saved event.
     */
    fun composeBookmarkList(authorPubkey: String, eventIds: List<String>): UnsignedNote? {
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val bounded = eventIds
            .filter { it.matches(Regex("^[0-9a-f]{64}$")) }
            .distinct()
            .take(space.bitos.core.model.BookmarkList.MAX_BOOKMARKS)
        return compose(
            authorPubkey,
            space.bitos.core.model.BookmarkList.KIND,
            listOf(listOf("d", space.bitos.core.model.BookmarkList.D_TAG)) + bounded.map { listOf("e", it) },
            "",
        )
    }

    /**
     * Builds the unsigned NIP-57 zap request (kind 9734): signed by the
     * PAYER and sent to the recipient's LNURL server via the `nostr` param —
     * the client never publishes it to relays.
     */
    fun composeZapRequest(
        recipientPubkey: String,
        amountMillisats: Long,
        relays: List<String>,
        lnurlHint: String,
        comment: String,
        authorPubkey: String,
        targetEventId: String? = null,
    ): UnsignedNote? {
        if (!recipientPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (amountMillisats !in 1..1_000_000_000_000L) return null
        if (lnurlHint.isBlank() || lnurlHint.length > 512) return null
        if (targetEventId != null && !targetEventId.matches(Regex("^[0-9a-f]{64}$"))) return null
        val boundedRelays = relays.filter { it.startsWith("wss://") }.take(4)
        if (boundedRelays.isEmpty()) return null
        val boundedComment = comment.trim().take(280)
        val tags = buildList {
            add(listOf("p", recipientPubkey))
            if (targetEventId != null) add(listOf("e", targetEventId))
            add(listOf("relays") + boundedRelays)
            add(listOf("amount", amountMillisats.toString()))
            add(listOf("lnurl", lnurlHint))
        }
        return compose(authorPubkey, 9_734, tags, boundedComment)
    }

    /** Blossom kind-24242 upload authorization event input. */
    fun composeUploadAuth(
        authorPubkey: String,
        serverUrl: String,
        fileHashHex: String,
        sizeBytes: Long,
        expirationSeconds: Long,
        nowSeconds: Long,
    ): UnsignedNote? {
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        // HTTPS in production; loopback HTTP is allowed for the local dev stack.
        val serverAllowed = serverUrl.startsWith("https://") ||
            serverUrl.startsWith("http://127.0.0.1:") || serverUrl.startsWith("http://localhost:")
        if (!serverAllowed || serverUrl.length > 2048) return null
        if (!fileHashHex.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (sizeBytes !in 1..space.bitos.core.model.Blossom.MAX_FILE_BYTES) return null
        if (expirationSeconds <= nowSeconds || expirationSeconds > nowSeconds + 3600) return null
        // BUD-11: content MUST be human-readable; a `server` tag scopes the
        // token to the target host (servers verify it when present).
        val host = serverUrl.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').take(255).takeIf { it.isNotEmpty() }
        val tags = buildList {
            add(listOf("t", "upload"))
            add(listOf("expiration", expirationSeconds.toString()))
            add(listOf("x", fileHashHex))
            add(listOf("size", sizeBytes.toString()))
            if (host != null) add(listOf("server", host))
        }
        return compose(
            authorPubkey,
            space.bitos.core.model.Blossom.AUTH_KIND,
            tags,
            "Upload Blob",
        )
    }

    /**
     * Kind-22 video note with caption + NIP-92 imeta descriptor. Post-
     * details parity with the meme paths: caption hashtags become t-tags,
     * alt falls back to the caption when the publisher left it blank
     * (NIP-31 accessibility floor), and a non-blank reason gates playback
     * behind NIP-36 `content-warning`.
     */
    fun composeMediaNote(
        authorPubkey: String,
        caption: String,
        media: space.bitos.core.model.UploadedMedia,
        altText: String = "",
        contentWarningReason: String? = null,
        includeClientTag: Boolean = false,
    ): UnsignedNote? {
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val trimmed = caption.trim().take(MAX_NOTE_LENGTH)
        val tags = mutableListOf<List<String>>()
        tags += ComposerRules.deriveTags(trimmed).filter { it.firstOrNull() == "t" }
        if (includeClientTag) tags += clientTag()
        val alt = altText.ifBlank { trimmed }.trim().take(200)
        if (alt.isNotEmpty()) tags += listOf("alt", alt)
        tags += listOf(listOf("imeta") + media.imetaFields())
        contentWarningReason?.takeIf { it.isNotBlank() }?.let { reason ->
            tags += listOf("content-warning", reason.trim().take(120))
        }
        return compose(authorPubkey, NostrKinds.VIDEO, tags, trimmed)
    }

    /**
     * Video meme (plan MST-034; web `feed.postBitz` video kinds): portrait
     * renders publish as kind 22 (NIP-71 short-form), landscape as kind 21
     * — the orientation comes from the EXPORTED frame (height ≥ width ⇒
     * portrait), matching what clients actually play. Tag order matches
     * the picture path; the imeta gains `duration` (seconds) via
     * [space.bitos.core.model.UploadedMedia.imetaFields].
     */
    fun composeMemeVideoNote(
        authorPubkey: String,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        portrait: Boolean,
        media: space.bitos.core.model.UploadedMedia,
        extraTags: List<List<String>> = emptyList(),
        includeClientTag: Boolean = false,
    ): UnsignedNote? = memeVideoEvent(
        authorPubkey, caption, altText, contentWarningReason, portrait, media,
        extraTags, includeClientTag, clock.nowSeconds(),
    )

    /**
     * Video meme with a pre-mined NIP-13 nonce tag (MST post-details PoW
     * path, `composeStoryWithPow` contract): the mined template hashed is
     * exactly [memeVideoEvent] at [createdAtSeconds] — the nonce tag is
     * appended LAST so the published event byte-matches the miner's
     * serialization.
     */
    fun composeMemeVideoNoteWithPow(
        authorPubkey: String,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        portrait: Boolean,
        media: space.bitos.core.model.UploadedMedia,
        extraTags: List<List<String>> = emptyList(),
        includeClientTag: Boolean = false,
        nonce: Long,
        targetDifficulty: Int,
        createdAtSeconds: Long,
    ): UnsignedNote? = memeVideoEvent(
        authorPubkey, caption, altText, contentWarningReason, portrait, media,
        extraTags, includeClientTag, createdAtSeconds,
        powTags = listOf(space.bitos.core.nostr.Pow.nonceTag(nonce, targetDifficulty)),
    )

    /** The kind-22/21 template both video meme paths share. */
    private fun memeVideoEvent(
        authorPubkey: String,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        portrait: Boolean,
        media: space.bitos.core.model.UploadedMedia,
        extraTags: List<List<String>>,
        includeClientTag: Boolean,
        createdAtSeconds: Long,
        powTags: List<List<String>> = emptyList(),
    ): UnsignedNote? {
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val kind = if (portrait) NostrKinds.SHORT_VIDEO else NostrKinds.NORMAL_VIDEO
        val trimmedCaption = caption.trim().take(space.bitos.core.studio.MemeWire.MAX_CAPTION)
        val tags = mutableListOf<List<String>>()
        tags += ComposerRules.deriveTags(trimmedCaption).filter { it.firstOrNull() == "t" }
        // Web `postBitz` prefix order: t-tags → client → extra tags
        // (remix lineage) → alt → imeta.
        if (includeClientTag) tags += clientTag()
        tags += extraTags
        val alt = altText.ifBlank { trimmedCaption }.trim().take(200)
        if (alt.isNotEmpty()) tags += listOf("alt", alt)
        tags += listOf(listOf("imeta") + media.imetaFields())
        contentWarningReason?.takeIf { it.isNotBlank() }?.let { reason ->
            tags += listOf("content-warning", reason.trim().take(120))
        }
        // PoW: the nonce tag rides LAST — the miner hashed exactly this
        // byte layout (see [space.bitos.core.nostr.Pow.mineChunk]).
        tags += powTags
        if (createdAtSeconds <= 0) return null
        val id = NostrEventCodec.computeId(hasher, authorPubkey, createdAtSeconds, kind, tags, trimmedCaption)
        return UnsignedNote(id, authorPubkey, createdAtSeconds, kind, tags, trimmedCaption)
    }

    /**
     * Kind-20 picture meme (plan MST-017; web `feed.postBitz` picture path
     * parity). Tag order per web: caption hashtag t-tags, NIP-31 `alt`
     * (explicit alt, or the caption's first 200 chars — pushed only when
     * non-empty), `imeta` (url m x size dim — the plan §3.4 superset of the
     * web fields; `x` is the verified Blossom hash), then NIP-36
     * `content-warning` last when sensitive. Caption caps: the meme SOFT
     * 300 is a UI counter concern; the HARD cap truncates at 1000 (wire
     * parity — a single kind-1 destination is NOT this path).
     */
    fun composeMemePictureNote(
        authorPubkey: String,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        media: space.bitos.core.model.UploadedMedia,
        extraTags: List<List<String>> = emptyList(),
        includeClientTag: Boolean = false,
    ): UnsignedNote? = memePictureEvent(
        authorPubkey, caption, altText, contentWarningReason, media,
        extraTags, includeClientTag, clock.nowSeconds(),
    )

    /**
     * Picture meme with a pre-mined NIP-13 nonce tag (same contract as
     * [composeMemeVideoNoteWithPow]).
     */
    fun composeMemePictureNoteWithPow(
        authorPubkey: String,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        media: space.bitos.core.model.UploadedMedia,
        extraTags: List<List<String>> = emptyList(),
        includeClientTag: Boolean = false,
        nonce: Long,
        targetDifficulty: Int,
        createdAtSeconds: Long,
    ): UnsignedNote? = memePictureEvent(
        authorPubkey, caption, altText, contentWarningReason, media,
        extraTags, includeClientTag, createdAtSeconds,
        powTags = listOf(space.bitos.core.nostr.Pow.nonceTag(nonce, targetDifficulty)),
    )

    /** The kind-20 template both picture meme paths share. */
    private fun memePictureEvent(
        authorPubkey: String,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        media: space.bitos.core.model.UploadedMedia,
        extraTags: List<List<String>>,
        includeClientTag: Boolean,
        createdAtSeconds: Long,
        powTags: List<List<String>> = emptyList(),
    ): UnsignedNote? {
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val trimmedCaption = caption.trim().take(space.bitos.core.studio.MemeWire.MAX_CAPTION)
        val tags = mutableListOf<List<String>>()
        tags += ComposerRules.deriveTags(trimmedCaption).filter { it.firstOrNull() == "t" }
        // Web `postBitz` order: client branding, then extra tags (remix
        // lineage), both BEFORE alt.
        if (includeClientTag) tags += clientTag()
        tags += extraTags
        val alt = altText.ifBlank { trimmedCaption }.trim().take(200)
        if (alt.isNotEmpty()) tags += listOf("alt", alt)
        tags += listOf(listOf("imeta") + media.imetaFields())
        contentWarningReason?.takeIf { it.isNotBlank() }?.let { reason ->
            tags += listOf("content-warning", reason.trim().take(120))
        }
        // PoW: the nonce tag rides LAST — byte-match with the miner.
        tags += powTags
        if (createdAtSeconds <= 0) return null
        val id = NostrEventCodec.computeId(hasher, authorPubkey, createdAtSeconds, NostrKinds.PICTURE, tags, trimmedCaption)
        return UnsignedNote(id, authorPubkey, createdAtSeconds, NostrKinds.PICTURE, tags, trimmedCaption)
    }

    /** Builds the unsigned kind-0 profile metadata event from bounded fields. */
    fun composeProfileMetadata(
        authorPubkey: String,
        name: String,
        displayName: String,
        about: String,
        picture: String,
        nip05: String,
        lud16: String,
        banner: String = "",
        website: String = "",
    ): UnsignedNote? {
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (name.length > 256 || displayName.length > 256) return null
        if (about.length > 1024) return null
        if (picture.length > 512 || nip05.length > 256 || lud16.length > 256) return null
        if (banner.length > 512 || website.length > 256) return null
        // Banner follows the picture URL policy (HTTPS-only).
        if (banner.isNotEmpty() && !banner.startsWith("https://") &&
            !banner.startsWith("http://127.0.0.1:") && !banner.startsWith("http://localhost:")
        ) return null
        // Website accepts http(s).
        if (website.isNotEmpty() && !website.startsWith("https://") && !website.startsWith("http://")) return null
        // HTTPS-only pictures (loopback for dev).
        if (picture.isNotEmpty() && !picture.startsWith("https://") &&
            !picture.startsWith("http://127.0.0.1:") && !picture.startsWith("http://localhost:")
        ) return null
        // lud16 must be user@domain shape when present.
        if (lud16.isNotEmpty() && !lud16.matches(Regex("^[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}$"))) return null

        val json = buildString {
            append('{')
            if (name.isNotEmpty()) append("\"name\":\"").append(NostrEventCodec.escape(name)).append("\",")
            if (displayName.isNotEmpty()) append("\"display_name\":\"").append(NostrEventCodec.escape(displayName)).append("\",")
            if (about.isNotEmpty()) append("\"about\":\"").append(NostrEventCodec.escape(about)).append("\",")
            if (picture.isNotEmpty()) append("\"picture\":\"").append(NostrEventCodec.escape(picture)).append("\",")
            if (banner.isNotEmpty()) append("\"banner\":\"").append(NostrEventCodec.escape(banner)).append("\",")
            if (website.isNotEmpty()) append("\"website\":\"").append(NostrEventCodec.escape(website)).append("\",")
            if (nip05.isNotEmpty()) append("\"nip05\":\"").append(NostrEventCodec.escape(nip05)).append("\",")
            if (lud16.isNotEmpty()) append("\"lud16\":\"").append(NostrEventCodec.escape(lud16)).append("\",")
            // Trim trailing comma.
            if (length > 1) setLength(length - 1)
            append('}')
        }
        return compose(authorPubkey, NostrKinds.PROFILE_METADATA, emptyList(), json)
    }

    /** NIP-22 kind-1111 comment compose (web `feed.comment` parity). */
    fun composeCommentWithTags(pubkeyHex: String, content: String, tags: List<List<String>>): UnsignedNote? {
        if (!pubkeyHex.matches(Regex("^[0-9a-f]{64}$"))) return null
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NOTE_LENGTH) return null
        return compose(pubkeyHex, NostrKinds.VIDEO_COMMENT, tags, trimmed)
    }

    /**
     * NIP-22 kind-1111 comment with a pre-mined NIP-13 nonce tag
     * (comment-sheet PowCard path): mirrors [composeTextNoteWithPow] — the
     * nonce tag is appended LAST so the published event byte-matches what
     * [space.bitos.core.nostr.Pow.mineChunk] hashed over [baseTags]
     * (built by [commentTags]).
     */
    fun composeCommentWithPow(
        pubkeyHex: String,
        content: String,
        nonce: Long,
        targetDifficulty: Int,
        createdAtSeconds: Long,
        baseTags: List<List<String>> = emptyList(),
    ): UnsignedNote? {
        if (!pubkeyHex.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (createdAtSeconds <= 0) return null
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NOTE_LENGTH) return null
        if (baseTags.size + 1 > space.bitos.core.model.NostrLimits.MAX_TAGS) return null
        val tags = baseTags + listOf(space.bitos.core.nostr.Pow.nonceTag(nonce, targetDifficulty))
        val id = NostrEventCodec.computeId(hasher, pubkeyHex, createdAtSeconds, NostrKinds.VIDEO_COMMENT, tags, trimmed)
        return UnsignedNote(id, pubkeyHex, createdAtSeconds, NostrKinds.VIDEO_COMMENT, tags, trimmed)
    }

    /**
     * Kind-1018 poll vote (web `votePoll` wire parity): empty content,
     * `["e", poll]` + `["response", optionIndex]` tags.
     */
    fun composePollVote(targetEventId: String, optionIndex: Int, authorPubkey: String): UnsignedNote? {
        if (!targetEventId.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (optionIndex !in 0..255) return null
        return compose(
            authorPubkey,
            NostrKinds.POLL_RESPONSE,
            listOf(listOf("e", targetEventId), listOf("response", optionIndex.toString())),
            "",
        )
    }

    /** Builds the unsigned NIP-56 report event (kind 1984). */
    fun composeReport(
        targetEventId: String?,
        targetPubkey: String,
        authorPubkey: String,
        reason: String,
        relayHint: String? = null,
    ): UnsignedNote? {
        if (!targetPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val boundedReason = reason.trim().take(120)
        if (boundedReason.isEmpty()) return null
        val tags = buildList {
            if (!targetEventId.isNullOrBlank() && targetEventId.matches(Regex("^[0-9a-f]{64}$"))) {
                val eTag = if (!relayHint.isNullOrBlank() && relayHint.length <= 512) {
                    listOf("e", targetEventId, relayHint, "report")
                } else {
                    listOf("e", targetEventId, "", "report")
                }
                add(eTag)
            }
            add(listOf("p", targetPubkey, "", "report"))
            add(listOf("L", "MODERATION"))
            add(listOf("l", boundedReason))
        }
        return compose(authorPubkey, 1_984, tags, boundedReason)
    }

    private fun compose(pubkeyHex: String, kind: Int, tags: List<List<String>>, content: String): UnsignedNote? {
        val createdAt = clock.nowSeconds()
        if (createdAt <= 0) return null
        val id = NostrEventCodec.computeId(hasher, pubkeyHex, createdAt, kind, tags, content)
        return UnsignedNote(id, pubkeyHex, createdAt, kind, tags, content)
    }

    /**
     * NIP-89-ish client branding tag (web `client-tag.ts` parity — the
     * exact `["client","BitOS"]` the web postBitz emits). Opt-in: callers
     * pass the `PrivacyPrefs.includeClientTag` resolution; the composer
     * itself stays pure.
     */
    private fun clientTag(): List<String> = listOf("client", CLIENT_NAME)


    /**
     * Kind-1 note with a pre-mined NIP-13 nonce tag (APP-008 PowCard path):
     * `nonce` was found by [space.bitos.core.nostr.Pow.mineChunk] over the
     * same fields; the ID is recomputed with the committed tag so the frame
     * passes the verified gate unchanged.
     */
    fun composeTextNoteWithPow(
        pubkeyHex: String,
        content: String,
        nonce: Long,
        targetDifficulty: Int,
        createdAtSeconds: Long,
        baseTags: List<List<String>> = emptyList(),
    ): UnsignedNote? {
        if (createdAtSeconds <= 0) return null
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NOTE_LENGTH) return null
        if (baseTags.size + 1 > space.bitos.core.model.NostrLimits.MAX_TAGS) return null
        // The nonce tag is APPENDED — [Pow.mineChunk] commits it as the last
        // tag while hashing, so the published event must byte-match that
        // serialization or the recomputed ID loses the mined difficulty.
        val tags = baseTags + listOf(space.bitos.core.nostr.Pow.nonceTag(nonce, targetDifficulty))
        val id = NostrEventCodec.computeId(hasher, pubkeyHex, createdAtSeconds, NostrKinds.SHORT_TEXT_NOTE, tags, trimmed)
        return UnsignedNote(id, pubkeyHex, createdAtSeconds, NostrKinds.SHORT_TEXT_NOTE, tags, trimmed)
    }

    /** The signed event object `{...}` (LNURL `nostr` param — never a relay frame). */
    fun signedEventJson(note: UnsignedNote, signatureHex: String): String? {
        if (!signatureHex.matches(Regex("^[0-9a-f]{128}$"))) return null
        val frame = publishMessage(note, signatureHex) ?: return null
        return frame.substringAfter(',').removeSuffix("]")
    }

    /** The `["EVENT", {...}]` frame carrying the signed event, or null when malformed. */
    fun publishMessage(note: UnsignedNote, signatureHex: String): String? {
        if (!signatureHex.matches(Regex("^[0-9a-f]{128}$"))) return null
        val tagsJson = buildString {
            append('[')
            note.tags.forEachIndexed { tagIndex, tag ->
                if (tagIndex > 0) append(',')
                append('[')
                tag.forEachIndexed { itemIndex, item ->
                    if (itemIndex > 0) append(',')
                    append('"').append(NostrEventCodec.escape(item)).append('"')
                }
                append(']')
            }
            append(']')
        }
        val escapedContent = NostrEventCodec.escape(note.content)
        return "[\"EVENT\",{\"id\":\"${note.idHex}\",\"pubkey\":\"${note.pubkeyHex}\"," +
            "\"created_at\":${note.createdAtSeconds},\"kind\":${note.kind}," +
            "\"tags\":$tagsJson,\"content\":\"$escapedContent\",\"sig\":\"$signatureHex\"}]"
    }

    companion object {
        /** UI-side bound, deliberately far below the protocol limit. */
        const val MAX_NOTE_LENGTH: Int = 16_000

        /** Branding value for the opt-in `client` tag (web client-tag.ts). */
        const val CLIENT_NAME = "BitOS"

        /** APP-009 participant p-tag bound (hostile targets stay bounded). */
        const val MAX_REPLY_PARTICIPANTS: Int = 16

        /** NIP-09 one-deletion target bound (batch deletes stay bounded). */
        const val MAX_DELETION_TARGETS: Int = 50

        private val hex64 = Regex("^[0-9a-f]{64}$")

        /**
         * APP-009 reply tag derivation (legacy `publishReply` / web
         * `feed.reply` parity): ALWAYS both NIP-10 markers — `['e', root,
         * '', 'root']` + `['e', target, '', 'reply']` (a root reply repeats
         * the id in both) — then participant p-tags: the replied-to author
         * plus everyone already p-tagged in the target (64-hex only,
         * deduped, ≤ [MAX_REPLY_PARTICIPANTS]); then the content-derived
         * entities and hashtags via ComposerRules.deriveTags, deduped per
         * kind against everything before them. Invalid ids → null.
         */
        fun replyTags(
            rootEventId: String,
            targetEventId: String,
            targetPubkey: String,
            targetPTags: List<String>,
            content: String,
        ): List<List<String>>? {
            if (!rootEventId.matches(hex64)) return null
            if (!targetEventId.matches(hex64)) return null
            if (!targetPubkey.matches(hex64)) return null
            val markers = listOf(
                listOf("e", rootEventId, "", "root"),
                listOf("e", targetEventId, "", "reply"),
            )
            val participants = (listOf(targetPubkey) + targetPTags)
                .filter { it.matches(hex64) }
                .distinct()
                .take(MAX_REPLY_PARTICIPANTS)
                .map { listOf("p", it) }
            // The two markers are exempt from dedupe (a root reply repeats
            // the id on purpose); everything after merges per kind+value.
            val seen = mutableSetOf<Pair<String, String>>()
            markers.forEach { seen += "e" to it[1] }
            participants.forEach { seen += "p" to it[1] }
            val contentTags = ComposerRules.deriveTags(content)
                .filter { tag ->
                    val key = tag.firstOrNull().orEmpty() to tag.getOrNull(1).orEmpty()
                    key.second.isEmpty() || seen.add(key)
                }
            return markers + participants + contentTags
        }

        /**
         * NIP-22 comment tags (web `feed.comment` parity, ADR-003):
         * uppercase E/K/P root tags anchor the commented event; lowercase
         * e/k parent tags point at the comment being answered (the target
         * itself for top-level comments). Relay hints are omitted (legal,
         * keeps tags small). Kind-1 targets must use NIP-10 [replyTags].
         */
        fun commentTags(
            targetEventId: String,
            targetPubkey: String,
            targetKind: Int,
            parentEventId: String?,
            parentPubkey: String?,
            content: String,
        ): List<List<String>>? {
            if (!targetEventId.matches(hex64)) return null
            if (!targetPubkey.matches(hex64)) return null
            if (targetKind == NostrKinds.SHORT_TEXT_NOTE) return null
            val parent = parentEventId ?: targetEventId
            if (!parent.matches(hex64)) return null
            if (parentPubkey != null && !parentPubkey.matches(hex64)) return null
            val markers = listOf(
                listOf("E", targetEventId),
                listOf("K", targetKind.toString()),
                listOf("P", targetPubkey),
                listOf("e", parent),
                listOf("k", (if (parentEventId != null) NostrKinds.VIDEO_COMMENT else targetKind).toString()),
            )
            val participants = listOfNotNull(targetPubkey, parentPubkey)
                .distinct()
                .take(MAX_REPLY_PARTICIPANTS)
                .map { listOf("p", it) }
            // NIP-22 markers are exempt from dedupe (top-level repeats the
            // target id on purpose); derived entities merge per kind+value.
            val seen = mutableSetOf<Pair<String, String>>()
            markers.forEach { seen += it[0] to it[1] }
            participants.forEach { seen += "p" to it[1] }
            val contentTags = ComposerRules.deriveTags(content)
                .filter { tag ->
                    val key = tag.firstOrNull().orEmpty() to tag.getOrNull(1).orEmpty()
                    key.second.isEmpty() || seen.add(key)
                }
            return markers + participants + contentTags
        }

        /**
         * Parses `["OK", <eventId>, <bool>, <message>]`; null for anything
         * else. The message text is capped to keep hostile relays bounded.
         */
        fun parseOkMessage(message: String): RelayOk? {
            val trimmed = message.trim()
            if (trimmed.length > NostrLimits.MAX_EVENT_BYTES) return null
            val elements = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
            }.getOrNull() as? kotlinx.serialization.json.JsonArray ?: return null
            if (elements.size < 3 || elements.size > 4) return null
            val type = (elements[0] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (type != "OK") return null
            val eventId = (elements[1] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (!eventId.matches(Regex("^[0-9a-f]{64}$"))) return null
            val accepted = when ((elements[2] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                "true" -> true
                "false" -> false
                else -> return null
            }
            val detail = elements.getOrNull(3)
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.takeIf { it.isString }?.content?.take(200) ?: ""
            return RelayOk(eventId, accepted, detail)
        }
    }
}
