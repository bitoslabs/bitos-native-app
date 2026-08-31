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

    /** Kind-22 video note with caption + NIP-92 imeta descriptor. */
    fun composeMediaNote(
        authorPubkey: String,
        caption: String,
        media: space.bitos.core.model.UploadedMedia,
    ): UnsignedNote? {
        if (!authorPubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
        val trimmed = caption.trim().take(MAX_NOTE_LENGTH)
        return compose(
            authorPubkey,
            NostrKinds.VIDEO,
            listOf(listOf("imeta") + media.imetaFields()),
            trimmed,
        )
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
        // The nonce tag leads; the mining template must byte-match (PowCard
        // mines over the same baseTags).
        val tags = listOf(space.bitos.core.nostr.Pow.nonceTag(nonce, targetDifficulty)) + baseTags
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
