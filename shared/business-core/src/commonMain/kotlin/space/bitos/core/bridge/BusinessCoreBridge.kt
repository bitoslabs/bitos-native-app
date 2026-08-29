package space.bitos.core.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import space.bitos.core.feed.FeedAggregator
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.MediaMetadata
import space.bitos.core.model.NostrKinds
import space.bitos.core.model.ProfileMetadata
import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

/**
 * Narrow interop surface for the iOS Swift facade (SBC-002).
 *
 * Everything crossing this boundary is a String/primitive so Kotlin/Native
 * bridging stays predictable: no value classes, exceptions, fun-interface
 * ports or kotlinx types are exposed. Failures decode to `null` instead of
 * throwing so Swift callers never bridge NSErrors for expected rejections.
 *
 * Android consumes the shared core directly as a Gradle module; only iOS
 * needs this bridge.
 */
class BusinessCoreBridge {

    /** Decoded, ID-verified event. */
    class Event(
        val id: String,
        val pubkey: String,
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
        val relayUrl: String?,
        val signature: String,
    )

    /** Bounded kind-0 profile projection. */
    class Profile(
        val pubkey: String,
        val name: String?,
        val displayName: String?,
        val about: String?,
        val picture: String?,
        val nip05: String?,
        val lud16: String?,
        val banner: String?,
        val website: String?,
    )

    /** Normalized, display-oriented feed note. */
    class Note(
        val id: String,
        val pubkey: String,
        val content: String,
        val createdAt: Long,
        val kind: Int,
        val replyTo: String?,
        val hashtags: List<String>,
        val mentions: List<String>,
        val mediaUrls: List<String>,
        val protocolPayload: Boolean,
        val repostedBy: String? = null,
        val videoUrl: String? = null,
        val videoMime: String? = null,
        val posterUrl: String? = null,
        val videoWidth: Int? = null,
        val videoHeight: Int? = null,
        /** NIP-92 imeta duration in whole seconds; null = unknown. */
        val durationSeconds: Long? = null,
        val contentWarning: Boolean = false,
        /** APP-009 NIP-10 thread anchors (root / immediate parent). */
        val threadRootId: String? = null,
        val threadParentId: String? = null,
        /** APP-008 poll labels (index order; empty = not a poll). */
        val pollOptions: List<String> = emptyList(),
        /** APP-007 remix source (id + author pubkey); nulls = original work. */
        val remixOfEventId: String? = null,
        val remixOfPubkey: String? = null,
        /** APP-007 `license` tag (remix advisory gate); null = permissive. */
        val license: String? = null,
    )

    /**
     * Decode one `["EVENT", subId, event]` relay frame into an ID-verified,
     * SIGNATURE-VERIFIED event (both display-trust stages, SBC-005/SBC-006).
     * Returns null when the frame is malformed, violates a size bound, has
     * no signature, fails the ID hash check or fails BIP-340 verification.
     */
    fun decodeEvent(message: String, relayUrl: String?): Event? {
        if (message.length > space.bitos.core.model.NostrLimits.MAX_EVENT_BYTES) return null
        val relay = relayUrl?.let { RelayUrl.parse(it) } ?: return null
        return try {
            val event = NostrEventCodec.decodeRelayEvent(Sha256EventHasher, message, relay)
            if (!NostrEventCodec.verifySignature(Sha256EventHasher, event)) return null
            Event(
                id = event.id.value,
                pubkey = event.pubkey.value,
                createdAt = event.createdAt,
                kind = event.kind,
                tags = event.tags,
                content = event.content,
                relayUrl = relay.value,
                signature = event.signature ?: return null,
            )
        } catch (_: NostrEventCodec.Rejected) {
            null
        }
    }

    /** EVENT subscription id, used to keep explicitly requested older pages out of the live-arrival hold. */
    fun relayEventSubscriptionId(message: String): String? =
        NostrEventCodec.relayEventSubscriptionId(message)

    fun isFeedKind(kind: Int): Boolean = FeedNote.isFeedKind(kind)

    /**
     * APP-004 content-filter predicate over a verified feed note
     * (ordinal per [space.bitos.core.feed.FeedFilter]; out-of-range → ALL).
     */
    fun feedFilterMatches(
        note: Note,
        filterOrdinal: Int,
        ownPubkeyHex: String?,
        likedIds: List<String>,
    ): Boolean {
        val filter = space.bitos.core.feed.FeedFilter.entries.getOrNull(filterOrdinal) ?: space.bitos.core.feed.FeedFilter.ALL
        val coreNote = FeedNote(
            id = note.id,
            pubkey = note.pubkey,
            content = note.content,
            createdAt = note.createdAt,
            kind = note.kind,
            replyTo = note.replyTo,
            hashtags = note.hashtags,
            mentions = note.mentions,
            mediaUrls = note.mediaUrls,
            isProtocolPayload = note.protocolPayload,
            video = note.videoUrl?.let {
                MediaMetadata(url = it, mimeType = note.videoMime, posterUrl = note.posterUrl, width = note.videoWidth, height = note.videoHeight, durationSeconds = note.durationSeconds)
            },
            repostedBy = note.repostedBy,
            contentWarning = note.contentWarning,
            threadRootId = note.threadRootId,
            threadParentId = note.threadParentId,
        )
        return space.bitos.core.feed.FeedFilters.passes(coreNote, filter, ownPubkeyHex, likedIds.toSet())
    }

    /**
     * APP-005 NIP-27 rich-content tokens as stable JSON for the Swift
     * renderer (shape locked by `Nip27Test.tokensJsonShapeIsStableForTheBridge`).
     */
    fun richTokens(content: String): String = space.bitos.core.nostr.Nip27.tokensJson(content)

    /**
     * APP-004 empty-feed retry delay in ms for [attempt] (2 s exponential
     * backoff capped at 30 s — shared `EmptyFeedRetry` policy).
     */
    fun emptyFeedRetryDelayMs(attempt: Int): Long = space.bitos.core.feed.EmptyFeedRetry.delayMs(attempt)

    /** APP-004 pagination: one older page — feed kinds before `until`. */
    fun olderFeedRequest(subscriptionId: String, until: Long, limit: Int): String =
        NostrEventCodec.encodeRequest(subscriptionId, space.bitos.core.feed.BitzQuery.olderFilters(until))

    // ── Bitz surface (APP-007) ──────────────────────────────────────────

    /** Deterministic share copy for the Share action (note + video rail). */
    fun noteShareText(content: String, authorNpub: String): String =
        space.bitos.core.feed.NoteShare.text(content, authorNpub)

    /** Locale-free `m:ss` / `h:mm:ss` duration label for explore tiles. */
    fun formatDurationSeconds(seconds: Long): String =
        space.bitos.core.model.MediaMetadata.formatDuration(seconds)

    /** APP-007 compact rail-count label (legacy `_formatCount` parity). */
    fun bitzFormatCount(value: Long): String = space.bitos.core.feed.BitzFormat.count(value)

    /** APP-007 compact zap-sats label from summed millisats (null = hide). */
    fun bitzFormatSats(zapMillisats: Long): String? = space.bitos.core.feed.BitzFormat.sats(zapMillisats)

    /**
     * T16 deep-link seam: classifies one inbound URI through the shared
     * rule as `{"kind":"author"|"note"|"lightning","value":"…"}`; null =
     * not a BitOS deep link.
     */
    fun deepLinkJson(uri: String): String? {
        val target = space.bitos.core.nostr.DeepLinks.classify(uri) ?: return null
        val (kind, value) = when (target) {
            is space.bitos.core.nostr.DeepLinks.Target.Author -> "author" to target.pubkeyHex
            is space.bitos.core.nostr.DeepLinks.Target.Note -> "note" to target.reference
            is space.bitos.core.nostr.DeepLinks.Target.Lightning -> "lightning" to target.invoiceUri
        }
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"kind\":\"$kind\",\"value\":\"$escaped\"}"
    }

    // ── Remix attribution (APP-007, legacy web remix.ts parity) ────────

    /** Whether the source's license asks before remixing (advisory gate). */
    fun remixRequiresAsk(license: String?): Boolean =
        space.bitos.core.feed.RemixRules.requiresAsk(license)

    /**
     * APP-007 Chain seam: first remix source of a tag set (JSON
     * `[[name, …], …]`) packed as `eventId|pubkey` (pubkey empty when the
     * tag set carries no p attribution); null = original work.
     */
    fun remixSourceOfTags(tagsJson: String): String? {
        val tags = try {
            Json.parseToJsonElement(tagsJson).jsonArray.map { el ->
                el.jsonArray.map { it.jsonPrimitive.content }
            }
        } catch (_: Exception) {
            return null
        }
        val source = space.bitos.core.feed.RemixRules.sourceOf(tags) ?: return null
        return source.eventId + "|" + (source.pubkey ?: "")
    }

    /** Wire tags for a remix publish (remix marker + p attribution), JSON. */
    fun remixTagsJson(eventId: String, pubkey: String?, relaysJson: String): String =
        space.bitos.core.feed.RemixRules.tagsFor(
            eventId,
            pubkey,
            parseStringList(relaysJson),
        ).joinToString(prefix = "[", postfix = "]") { tag ->
            tag.joinToString(prefix = "[", separator = ",", postfix = "]") { value ->
                "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }
        }

    /** `["attribution", "remix of <label>"]` as a one-tag JSON array. */
    fun remixAttributionTagJson(label: String): String {
        val tag = space.bitos.core.feed.RemixRules.attributionTag(label)
        return "[[\"attribution\",\"" + tag[1].replace("\\", "\\\\").replace("\"", "\\\"") + "\"]]"
    }

    /** Merge seed tags with the composer's derived tags (dedup by name+param). */
    fun mergeTagsJson(baseJson: String, derivedJson: String): String =
        space.bitos.core.feed.RemixRules.mergeTagsJson(baseJson, derivedJson)

    private fun parseStringList(json: String): List<String> = try {
        Json.parseToJsonElement(json).jsonArray.mapNotNull { it.jsonPrimitive.content }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Bitz search seam for the Swift surface: filters the local window and
     * merges relay results (id-deduped, local-first, bounded) through the
     * shared policy, returning matched rows as `[{id,content,authorName},…]`
     * JSON. Blank/oversized query → `[]` (idle); malformed JSON input → null.
     */
    fun bitzSearchResults(query: String, localJson: String, relayJson: String): String? {
        val local = parseBitzEntries(localJson) ?: return null
        val relay = parseBitzEntries(relayJson) ?: return null
        val merged = space.bitos.core.feed.BitzSearch.results(query, local, relay)
        return buildJsonArray {
            merged.forEach { entry ->
                add(
                    buildJsonObject {
                        put("id", entry.id)
                        put("content", entry.content)
                        put("authorName", entry.authorName)
                    },
                )
            }
        }.toString()
    }

    /** APP-007 Explore window: 24 initially, then 18 per explicit load-more. */
    fun bitzExploreVisibleCount(loadMoreCount: Int): Int =
        space.bitos.core.feed.BitzExplore.visibleCount(loadMoreCount)

    private fun parseBitzEntries(json: String): List<space.bitos.core.feed.BitzSearch.Entry>? = try {
        val arr = Json.parseToJsonElement(json).jsonArray
        arr.map { el ->
            val o = el.jsonObject
            space.bitos.core.feed.BitzSearch.Entry(
                id = o["id"]?.jsonPrimitive?.content ?: "",
                content = o["content"]?.jsonPrimitive?.content ?: "",
                authorName = o["authorName"]?.jsonPrimitive?.content ?: "",
            )
        }
    } catch (_: Exception) {
        null
    }


    fun isProfileKind(kind: Int): Boolean = kind == NostrKinds.PROFILE_METADATA

    fun profile(event: Event): Profile? {
        if (event.kind != NostrKinds.PROFILE_METADATA) return null
        val metadata = ProfileMetadata.parse(event.toCore()) ?: return null
        return Profile(
            pubkey = metadata.pubkey.value,
            name = metadata.name,
            displayName = metadata.displayName,
            about = metadata.about,
            picture = metadata.picture,
            nip05 = metadata.nip05,
            lud16 = metadata.lud16,
            banner = metadata.banner,
            website = metadata.website,
        )
    }

    fun feedNote(event: Event): Note {
        val note = FeedNote.from(event.toCore())
        return Note(
            id = note.id,
            pubkey = note.pubkey,
            content = note.content,
            createdAt = note.createdAt,
            kind = note.kind,
            replyTo = note.replyTo,
            hashtags = note.hashtags,
            mentions = note.mentions,
            mediaUrls = note.mediaUrls,
            protocolPayload = note.isProtocolPayload,
            repostedBy = note.repostedBy,
            videoUrl = note.video?.url,
            videoMime = note.video?.mimeType,
            posterUrl = note.video?.posterUrl,
            videoWidth = note.video?.width,
            videoHeight = note.video?.height,
            durationSeconds = note.video?.durationSeconds,
            contentWarning = note.contentWarning,
            threadRootId = note.threadRootId,
            threadParentId = note.threadParentId,
            pollOptions = note.poll?.options?.map { it.label } ?: emptyList(),
            remixOfEventId = note.remixOfEventId,
            remixOfPubkey = note.remixOfPubkey,
            license = note.license,
        )
    }

    /** NIP-01 `["REQ", id, filter]` for the combined feed + profiles query. */
    fun feedRequest(subscriptionId: String): String = try {
        NostrEventCodec.encodeRequest(
            subscriptionId,
            space.bitos.core.feed.BitzQuery.initialFilters(),
        )
    } catch (_: NostrEventCodec.Rejected) {
        // Subscription ids are app-generated and bounded; reaching here is a
        // programmer error surfaced as an inert close message.
        NostrEventCodec.encodeClose(subscriptionId)
    }

    /** NIP-01 `["REQ", id, filter]` for a batched kind-0 author lookup. */
    fun profileRequest(subscriptionId: String, authors: List<String>): String {
        val requested = authors.distinct().take(48)
        if (requested.isEmpty()) return NostrEventCodec.encodeClose(subscriptionId)
        return try {
            val joined = requested.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
            NostrEventCodec.encodeRequest(
                subscriptionId,
                """{"kinds":[0],"authors":$joined,"limit":${requested.size}}""",
            )
        } catch (_: NostrEventCodec.Rejected) {
            NostrEventCodec.encodeClose(subscriptionId)
        }
    }

    fun close(subscriptionId: String): String = NostrEventCodec.encodeClose(subscriptionId)

    fun makeWindow(maxItems: Int): FeedWindow = FeedWindow(maxItems)

    /** Versioned event-store schema version (DAT-001/002 contract). */
    fun eventStoreSchemaVersion(): Int = space.bitos.core.store.EventStoreContract.SCHEMA_VERSION

    // ── Settings (APP-018) ──────────────────────────────────────────────

    /** Settings section catalog entry (key + group wire name). */
    class SettingsSectionEntry(val key: String, val group: String)

    /**
     * Typed, validated settings snapshot decoded from a string key-value
     * store. Enum fields are canonical wire strings; corrupt values already
     * fell back to defaults by contract.
     */
    class SettingsSnapshotResult(
        val schemaVersion: Int,
        val themeMode: String,
        val accentColorHex: String,
        val fontSize: String,
        val language: String,
        val notificationsEnabled: Boolean,
        val soundEnabled: Boolean,
        val hapticEnabled: Boolean,
        val compactMode: Boolean,
        val feedTimeline: String,
        val mediaPreview: Boolean,
        val showReactions: Boolean,
        val showProtocolNotes: Boolean,
        val mediaAutoPlay: String,
        val videoQuality: String,
        val videoPlaybackRate: String,
        val defaultZapAmount: Int,
        val timeZone: String,
        val dateFormat: String,
        val sensitiveMedia: String,
        val bitzMode: String,
        val videoMuted: Boolean,
    )

    fun settingsSchemaVersion(): Int = space.bitos.core.settings.SettingsContract.SCHEMA_VERSION

    fun settingsSnapshot(kv: Map<String, String>): SettingsSnapshotResult {
        val s = space.bitos.core.settings.SettingsCodec.decode(kv)
        return SettingsSnapshotResult(
            schemaVersion = space.bitos.core.settings.SettingsContract.SCHEMA_VERSION,
            themeMode = s.themeMode.wire,
            accentColorHex = s.accentColorHex,
            fontSize = s.fontSize.wire,
            language = s.language.wire,
            notificationsEnabled = s.notificationsEnabled,
            soundEnabled = s.soundEnabled,
            hapticEnabled = s.hapticEnabled,
            compactMode = s.compactMode,
            feedTimeline = s.feedTimeline.wire,
            mediaPreview = s.mediaPreview,
            showReactions = s.showReactions,
            showProtocolNotes = s.showProtocolNotes,
            mediaAutoPlay = s.mediaAutoPlay.wire,
            videoQuality = s.videoQuality.wire,
            videoPlaybackRate = s.videoPlaybackRate.wire,
            defaultZapAmount = s.defaultZapAmount,
            timeZone = s.timeZone,
            dateFormat = s.dateFormat.wire,
            sensitiveMedia = s.sensitiveMedia.wire,
            bitzMode = s.bitzMode.wire,
            videoMuted = s.videoMuted,
        )
    }

    /** Deterministic section catalog (web mobile index order). */
    fun settingsSections(): List<SettingsSectionEntry> =
        space.bitos.core.settings.SettingsContract.SECTIONS.map {
            SettingsSectionEntry(key = it.key, group = it.group.name)
        }

    /** Validate + canonicalize a raw value before persisting (null = reject). */
    fun settingsNormalizeValue(key: String, rawValue: String): String? =
        space.bitos.core.settings.SettingsRules.normalize(key, rawValue)

    /** Canonical storage keys (for adapters clearing/prefilling stores). */
    fun settingsKeys(): List<String> = listOf(
        space.bitos.core.settings.SettingsContract.KEY_THEME_MODE,
        space.bitos.core.settings.SettingsContract.KEY_ACCENT_COLOR,
        space.bitos.core.settings.SettingsContract.KEY_FONT_SIZE,
        space.bitos.core.settings.SettingsContract.KEY_LANGUAGE,
        space.bitos.core.settings.SettingsContract.KEY_NOTIFICATIONS_ENABLED,
        space.bitos.core.settings.SettingsContract.KEY_SOUND_ENABLED,
        space.bitos.core.settings.SettingsContract.KEY_HAPTIC_ENABLED,
        space.bitos.core.settings.SettingsContract.KEY_COMPACT_MODE,
        space.bitos.core.settings.SettingsContract.KEY_FEED_TIMELINE,
        space.bitos.core.settings.SettingsContract.KEY_FEED_MEDIA_PREVIEW,
        space.bitos.core.settings.SettingsContract.KEY_FEED_SHOW_REACTIONS,
        space.bitos.core.settings.SettingsContract.KEY_FEED_SHOW_PROTOCOL_NOTES,
        space.bitos.core.settings.SettingsContract.KEY_MEDIA_AUTO_PLAY,
        space.bitos.core.settings.SettingsContract.KEY_VIDEO_QUALITY,
        space.bitos.core.settings.SettingsContract.KEY_VIDEO_PLAYBACK_RATE,
        space.bitos.core.settings.SettingsContract.KEY_DEFAULT_ZAP_AMOUNT,
        space.bitos.core.settings.SettingsContract.KEY_TIME_ZONE,
        space.bitos.core.settings.SettingsContract.KEY_DATE_FORMAT,
        space.bitos.core.settings.SettingsContract.KEY_SENSITIVE_MEDIA,
        space.bitos.core.settings.SettingsContract.KEY_BITZ_MODE,
        space.bitos.core.settings.SettingsContract.KEY_VIDEO_MUTED,
    )

    /** Keys removable on "Clear cache" (device globals stay). */
    fun settingsClearCacheRemovableKeys(allKeys: List<String>): List<String> =
        space.bitos.core.settings.SettingsRules.clearCacheRemovableKeys(allKeys)

    /** Cache-size label (legacy `_formatBytes` parity). */
    fun formatCacheSize(bytes: Long): String =
        space.bitos.core.settings.SettingsRules.formatCacheSize(bytes)

    /** Short npub for display (legacy `_shortNpub` parity). */
    fun shortNpub(npub: String): String =
        space.bitos.core.settings.SettingsRules.shortNpub(npub)

    /** DDL statements to execute for a fresh event store. */
    fun eventStoreDdl(): List<String> = space.bitos.core.store.EventStoreContract.ddl

    /** Deterministic migrations keyed by target schema version. */
    fun eventStoreMigrations(): Map<Int, List<String>> = space.bitos.core.store.EventStoreContract.migrations

    /** Canonical tags encoding for the persistence boundary. */
    fun tagsToJson(tags: List<List<String>>): String = space.bitos.core.store.TagsCodec.encode(tags)

    fun tagsFromJson(raw: String): List<List<String>>? = space.bitos.core.store.TagsCodec.decode(raw)

    // ------------------------------------------------------------------
    // Identity seam (ID-001/ID-004): key math and NIP-19 codecs. Secret
    // bytes only ever transit function arguments; they never enter bridge
    // state. Platforms hand secrets straight from their secure stores.
    // ------------------------------------------------------------------

    /** Derives the x-only public key for a hex64 secret; null when invalid. */
    fun derivePublicKey(secretHex: String): String? =
        space.bitos.core.crypto.SchnorrSigning.publicKey(hexToBytes(secretHex, 32) ?: return null, Sha256EventHasher)
            ?.let(::bytesToLowercaseHex)

    /**
     * Deterministic BIP-340 signature for the import/backup tests and the
     * local-key signer. Production signing injects CSPRNG aux on the
     * platform side.
     */
    fun signDetached(messageHex: String, secretHex: String, auxHex: String): String? {
        val message = hexToBytes(messageHex, 32) ?: return null
        val secret = hexToBytes(secretHex, 32) ?: return null
        val aux = hexToBytes(auxHex, 32) ?: return null
        return space.bitos.core.crypto.SchnorrSigning.sign(message, secret, aux, Sha256EventHasher)
            ?.let(::bytesToLowercaseHex)
    }

    /** hex64 pubkey -> npub (display form). */
    fun npubEncode(pubkeyHex: String): String? = space.bitos.core.identity.NostrKeyCodec.npub(pubkeyHex)

    // ── Multi-account registry (APP-018a row 1) ─────────────────

    /** Registry row wire — public projections only, never secrets. */
    class RegisteredAccountWire(
        val pubkeyHex: String,
        val npub: String,
        val displayName: String? = null,
        val addedAtSeconds: Long = 0,
    )

    fun accountRegistryDecode(json: String): List<RegisteredAccountWire> =
        space.bitos.core.identity.AccountRegistry.decode(json).map {
            RegisteredAccountWire(it.pubkeyHex, it.npub, it.displayName, it.addedAtSeconds)
        }

    fun accountRegistryEncode(accounts: List<RegisteredAccountWire>): String =
        space.bitos.core.identity.AccountRegistry.encode(
            accounts.map {
                space.bitos.core.identity.RegisteredAccount(it.pubkeyHex, it.npub, it.displayName, it.addedAtSeconds)
            },
        )

    // ── Interaction gates (APP-018a row 2) ──────────────────────

    /** Privacy-prefs wire (eight gate fields; sensitive media + push toggles live elsewhere). */
    class PrivacyPrefsWire(
        val privateAccount: Boolean,
        val includeClientTag: Boolean,
        val activityVisible: Boolean,
        val readReceipts: Boolean,
        val sensitiveReason: Boolean,
        val storyShare: Boolean,
        val messagePermission: String,
        val commentPermission: String,
    )

    fun privacyPrefsDecode(json: String): PrivacyPrefsWire {
        val p = space.bitos.core.settings.PrivacyPrefsContract.decode(json)
        return PrivacyPrefsWire(
            p.privateAccount, p.includeClientTag, p.activityVisible, p.readReceipts,
            p.sensitiveReason, p.storyShare, p.messagePermission.wire, p.commentPermission.wire,
        )
    }

    fun privacyPrefsEncode(wire: PrivacyPrefsWire): String =
        space.bitos.core.settings.PrivacyPrefsContract.encode(
            space.bitos.core.settings.PrivacyPrefs(
                privateAccount = wire.privateAccount,
                includeClientTag = wire.includeClientTag,
                activityVisible = wire.activityVisible,
                readReceipts = wire.readReceipts,
                sensitiveReason = wire.sensitiveReason,
                storyShare = wire.storyShare,
                messagePermission = space.bitos.core.settings.MessagePermission.parse(wire.messagePermission),
                commentPermission = space.bitos.core.settings.CommentPermission.parse(wire.commentPermission),
            ),
        )

    /** npub -> hex64 pubkey. */
    fun parseNpub(encoded: String): String? = space.bitos.core.identity.NostrKeyCodec.parseNpub(encoded)

    /** nsec -> hex64 secret; only for the import transaction. */
    fun parseNsec(encoded: String): String? = space.bitos.core.identity.NostrKeyCodec.parseNsec(encoded)

    /** hex64 secret -> nsec; only for key-backup display. */
    fun nsecEncode(secretHex: String): String? = space.bitos.core.identity.NostrKeyCodec.nsec(secretHex)

    // ------------------------------------------------------------------
    // Publish seam (PUB-001 note path): compose, encode, parse receipts.
    // ------------------------------------------------------------------
    // APP-008 composer-page surface (shared `ComposerRules` for iOS;
    // tagsJson uses the `TagsCodec` wire form `[["t","…"],…]`).
    // ------------------------------------------------------------------

    /** Counter presentation rule: {label, ratio, remaining, near, over}. */
    fun composerCounter(length: Int): Map<String, Any> {
        val state = space.bitos.core.publish.ComposerRules.counterState(length)
        return mapOf(
            "label" to state.label,
            "ratio" to state.ratio.toDouble(),
            "remaining" to state.remaining,
            "near" to state.near,
            "over" to state.over,
        )
    }

    /** Cursor-preserving toolbar inserts: {text, cursor}. */
    fun composerInsertHashtag(text: String, cursor: Int): Map<String, Any> {
        val (next, at) = space.bitos.core.publish.ComposerRules.insertHashtag(text, cursor)
        return mapOf("text" to next, "cursor" to at)
    }

    fun composerInsertEmoji(text: String, cursor: Int, emoji: String): Map<String, Any> {
        val (next, at) = space.bitos.core.publish.ComposerRules.insertEmoji(text, cursor, emoji)
        return mapOf("text" to next, "cursor" to at)
    }

    /** Trailing @query at the cursor; empty string = none. */
    fun composerMentionQuery(text: String, cursor: Int): String =
        space.bitos.core.publish.ComposerRules.mentionQueryAt(text, cursor) ?: ""

    /**
     * Mention suggestions. Input `profilesJson`: [{pubkey, name,
     * displayName, picture?}]. Output (stable JSON, bridge-test locked):
     * [{"name":…,"pubkey":…,"npub":…,"picture":…}].
     */
    fun composerMentionSuggestions(query: String, profilesJson: String): String {
        val profiles = try {
            Json.parseToJsonElement(profilesJson).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                space.bitos.core.model.ProfileMetadata(
                    pubkey = space.bitos.core.model.Pubkey.parse(obj.getValue("pubkey").jsonPrimitive.content) ?: return@mapNotNull null,
                    name = obj["name"]?.jsonPrimitive?.content,
                    displayName = obj["displayName"]?.jsonPrimitive?.content,
                    about = null,
                    picture = obj["picture"]?.jsonPrimitive?.content,
                    nip05 = null,
                    lud16 = null,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        return space.bitos.core.publish.ComposerRules.mentionSuggestions(query, profiles).joinToString(prefix = "[", separator = ",", postfix = "]") { s ->
            buildJsonObject {
                put("name", s.name)
                put("pubkey", s.pubkeyHex)
                put("npub", s.npub)
                put("picture", s.pictureUrl ?: "")
            }.toString()
        }
    }

    /** Publish-time mention rewrite; `trackedJson`: [{name, npub}]. */
    fun composerRewriteMentions(content: String, trackedJson: String): String {
        val tracked = try {
            Json.parseToJsonElement(trackedJson).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                (obj["name"]?.jsonPrimitive?.content) to (obj["npub"]?.jsonPrimitive?.content)
            }.filter { it.first != null && it.second != null }.map { it.first!! to it.second!! }
        } catch (_: Exception) {
            emptyList()
        }
        return space.bitos.core.publish.ComposerRules.rewriteMentions(content, tracked)
    }

    /** Media content join; `urlsJson`: ["https://…", …]. */
    fun composerComposeContent(text: String, urlsJson: String): String {
        val urls = try {
            Json.parseToJsonElement(urlsJson).jsonArray.map { it.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyList()
        }
        return space.bitos.core.publish.ComposerRules.composeContent(text, urls)
    }

    /** Derived tags as the TagsCodec wire JSON (empty string when none). */
    fun composerDeriveTags(content: String, contentWarningReason: String?): String {
        val tags = space.bitos.core.publish.ComposerRules.deriveTags(content, contentWarningReason)
        return space.bitos.core.store.TagsCodec.encode(tags)
    }

    /** Quick-emoji palette (32, legacy parity). */
    fun composerEmojis(): List<String> = space.bitos.core.publish.ComposerRules.COMPOSER_EMOJIS

    // ------------------------------------------------------------------
    // APP-008 GIF picker (shared `GifPickerContract`; legacy Flutter sheet /
    // web GifPicker.svelte parity). Item JSON shape everywhere:
    // `{"id":…,"url":…,"preview":…,"w":…,"h":…}`.
    // ------------------------------------------------------------------

    /** Giphy request URL (trending when the query is blank); offset pages. */
    fun gifPickerUrl(query: String, offset: Int): String =
        space.bitos.core.publish.GifPickerContract.buildUrl(
            space.bitos.core.publish.GifPickerContract.DEFAULT_API_KEY,
            query,
            offset,
        )

    /** Giphy response parse → item array JSON (empty array when unparseable). */
    fun gifPickerParse(responseJson: String): String =
        space.bitos.core.publish.GifPickerContract.choicesToJson(
            space.bitos.core.publish.GifPickerContract.parseChoices(responseJson),
        )

    /** Pagination math: {nextOffset, hasMore}. */
    fun gifPickerPagination(responseJson: String, fetchedCount: Int, requestedOffset: Int): Map<String, Any> {
        val page = space.bitos.core.publish.GifPickerContract.pagination(responseJson, fetchedCount, requestedOffset)
        return mapOf("nextOffset" to page.nextOffset, "hasMore" to page.hasMore)
    }

    /** Recent merge (newest first, dedup by id, cap 12). `pickJson` = one item. */
    fun gifPickerMergeRecent(recentJson: String, pickJson: String): String {
        val pick = space.bitos.core.publish.GifPickerContract.choicesFromJson("[$pickJson]").firstOrNull()
            ?: return recentJson
        return space.bitos.core.publish.GifPickerContract.choicesToJson(
            space.bitos.core.publish.GifPickerContract.mergeRecent(
                space.bitos.core.publish.GifPickerContract.choicesFromJson(recentJson),
                pick,
            ),
        )
    }

    /** Versioned v1 cache wire from item arrays. */
    fun gifCacheEncode(recentJson: String, trendingJson: String, savedAtMs: Long): String =
        space.bitos.core.publish.GifPickerContract.cacheEncode(
            recent = space.bitos.core.publish.GifPickerContract.choicesFromJson(recentJson),
            trending = space.bitos.core.publish.GifPickerContract.choicesFromJson(trendingJson),
            savedAtMs = savedAtMs,
        )

    /** Cache decode → `{"recent":[…],"trending":[…],"savedAt":ms}` or null. */
    fun gifCacheDecode(json: String): String? {
        val cache = space.bitos.core.publish.GifPickerContract.cacheDecode(json) ?: return null
        return buildJsonObject {
            put("recent", Json.parseToJsonElement(choicesJson(cache.recent)))
            put("trending", Json.parseToJsonElement(choicesJson(cache.trending)))
            put("savedAt", cache.savedAtMs)
        }.toString()
    }

    private fun choicesJson(choices: List<space.bitos.core.publish.GifChoice>): String =
        space.bitos.core.publish.GifPickerContract.choicesToJson(choices)

    /** 24 h trending-cache freshness window. */
    fun gifCacheFresh(savedAtMs: Long, nowMs: Long): Boolean =
        space.bitos.core.publish.GifPickerContract.isCacheFresh(savedAtMs, nowMs)

    /** Search-as-you-type debounce (350 ms, legacy parity). */
    fun gifPickerDebounceMs(): Long = space.bitos.core.publish.GifPickerContract.SEARCH_DEBOUNCE_MS

    /** APP-014 zap-sheet presentation rules (shared `ZapFormat`). */
    fun zapEmoji(sats: Long): String = space.bitos.core.model.ZapFormat.emoji(sats)

    /** APP-010 People-tab rows (shared fan-in) as JSON [{pubkey,name,nip05,picture,notes}]. */
    fun searchPeopleJson(resultsJson: String, profilesJson: String): String {
        // Reconstruct from bridge Note projections + profile maps.
        val notes = try {
            Json.parseToJsonElement(resultsJson).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                space.bitos.core.feed.FeedNote(
                    id = obj.getValue("id").jsonPrimitive.content,
                    pubkey = obj.getValue("pubkey").jsonPrimitive.content,
                    content = "",
                    createdAt = 0,
                    kind = 1,
                    replyTo = null,
                    hashtags = (obj["hashtags"]?.jsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                    mentions = emptyList(),
                    mediaUrls = emptyList(),
                    isProtocolPayload = false,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        val profiles = try {
            Json.parseToJsonElement(profilesJson).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: return@mapNotNull null
                pubkey to space.bitos.core.model.ProfileMetadata(
                    pubkey = space.bitos.core.model.Pubkey.parse(pubkey) ?: return@mapNotNull null,
                    name = obj["name"]?.jsonPrimitive?.content,
                    displayName = obj["displayName"]?.jsonPrimitive?.content,
                    about = null,
                    picture = obj["picture"]?.jsonPrimitive?.content,
                    nip05 = obj["nip05"]?.jsonPrimitive?.content,
                    lud16 = null,
                )
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
        return space.bitos.core.feed.SearchResults.people(notes, profiles).joinToString(prefix = "[", separator = ",", postfix = "]") { person ->
            buildJsonObject {
                put("pubkey", person.pubkey)
                put("name", person.displayName)
                put("nip05", person.nip05 ?: "")
                put("picture", person.picture ?: "")
                put("notes", person.noteCount)
            }.toString()
        }
    }

    /** APP-010 Hashtag-tab rows as JSON [{tag,count}]. */
    fun searchHashtagsJson(resultsJson: String): String {
        val notes = try {
            Json.parseToJsonElement(resultsJson).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                space.bitos.core.feed.FeedNote(
                    id = obj.getValue("id").jsonPrimitive.content,
                    pubkey = obj.getValue("pubkey").jsonPrimitive.content,
                    content = "",
                    createdAt = 0,
                    kind = 1,
                    replyTo = null,
                    hashtags = (obj["hashtags"]?.jsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                    mentions = emptyList(),
                    mediaUrls = emptyList(),
                    isProtocolPayload = false,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        return space.bitos.core.feed.SearchResults.hashtags(notes).joinToString(prefix = "[", separator = ",", postfix = "]") { hit ->
            buildJsonObject {
                put("tag", hit.tag)
                put("count", hit.count)
            }.toString()
        }
    }

    /** APP-002 onboarding pages (shared contract) for the iOS carousel. */
    fun onboardingContent(): List<Map<String, Any>> =
        space.bitos.core.settings.OnboardingContent.pages.map { page ->
            mapOf(
                "id" to page.id,
                "icon" to page.iconToken,
                "title" to page.title,
                "body" to page.body,
            )
        }

    /** APP-020 static pages (legacy copy verbatim from the shared contract). */
    fun staticAboutHeadline(): String = space.bitos.core.settings.StaticPagesContent.ABOUT_HEADLINE
    fun staticAboutSubtitle(): String = space.bitos.core.settings.StaticPagesContent.ABOUT_SUBTITLE
    fun staticAboutHeroBody(): String = space.bitos.core.settings.StaticPagesContent.ABOUT_HERO_BODY
    fun staticAboutFeatures(): List<Map<String, Any>> =
        space.bitos.core.settings.StaticPagesContent.aboutFeatures.map { mapOf("title" to it.title, "body" to it.body) }

    fun staticPrivacyTitle(): String = space.bitos.core.settings.StaticPagesContent.PRIVACY_TITLE
    fun staticPrivacyUpdated(): String = space.bitos.core.settings.StaticPagesContent.PRIVACY_UPDATED
    fun staticPrivacyIntro(): String = space.bitos.core.settings.StaticPagesContent.PRIVACY_INTRO
    fun staticPrivacySummaryTitle(): String = space.bitos.core.settings.StaticPagesContent.PRIVACY_SUMMARY_TITLE
    fun staticPrivacySummaryBody(): String = space.bitos.core.settings.StaticPagesContent.PRIVACY_SUMMARY_BODY
    fun staticPrivacySections(): List<Map<String, Any>> =
        space.bitos.core.settings.StaticPagesContent.privacySections.map { mapOf("title" to it.title, "body" to it.body) }

    fun staticTermsTitle(): String = space.bitos.core.settings.StaticPagesContent.TERMS_TITLE
    fun staticTermsUpdated(): String = space.bitos.core.settings.StaticPagesContent.TERMS_UPDATED
    fun staticTermsIntro(): String = space.bitos.core.settings.StaticPagesContent.TERMS_INTRO
    fun staticTermsSummaryTitle(): String = space.bitos.core.settings.StaticPagesContent.TERMS_SUMMARY_TITLE
    fun staticTermsSummaryBody(): String = space.bitos.core.settings.StaticPagesContent.TERMS_SUMMARY_BODY
    fun staticTermsSections(): List<Map<String, Any>> =
        space.bitos.core.settings.StaticPagesContent.termsSections.map { mapOf("title" to it.title, "body" to it.body) }

    /** APP-006 Stories: subscribe REQ for account + followed authors. */
    fun storiesRequest(subscriptionId: String, authorPubkeys: List<String>): String {
        val authors = authorPubkeys.take(50).joinToString("\"", prefix = "[\"", postfix = "\"")
        return NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[${space.bitos.core.model.Stories.STORY_KIND},${space.bitos.core.model.Stories.DELETE_KIND}],"authors":$authors,"limit":100}""",
        )
    }

    /** APP-006: parse a verified kind-30315 frame → slide map (null = not a valid story). */
    fun storyFromFrame(message: String, relayUrl: String, nowSeconds: Long): Map<String, Any>? {
        val relay = RelayUrl.parse(relayUrl) ?: return null
        val event = try {
            NostrEventCodec.decodeRelayEvent(Sha256EventHasher, message, relay)
        } catch (_: NostrEventCodec.Rejected) {
            return null
        }
        if (!NostrEventCodec.verifySignature(Sha256EventHasher, event)) return null
        val slide = space.bitos.core.model.Stories.parseSlide(event, nowSeconds) ?: return null
        return mapOf(
            "id" to slide.id,
            "pubkey" to slide.pubkey,
            "content" to slide.content,
            "createdAt" to slide.createdAt,
            "expiresAt" to slide.expiresAt,
            "d" to (slide.d ?: ""),
            "imageUrl" to (slide.imageUrl ?: ""),
            "gradient" to (slide.gradient ?: ""),
            "pow" to (slide.pow ?: 0),
        )
    }

    /** APP-011 DMs: kind-1059 subscription REQ. */
    fun secureDmRequest(subscriptionId: String, accountPubkey: String): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[${space.bitos.core.publish.SecureDmComposer.KIND_GIFT_WRAP}],"#p":["$accountPubkey"],"limit":50}""",
        )

    /** APP-011: unwrap a gift wrap → rumor map {id, author, peer, content, createdAt}. */
    fun secureDmUnwrap(message: String, relayUrl: String, myPrivateKeyHex: String): Map<String, Any>? {
        val relay = RelayUrl.parse(relayUrl) ?: return null
        val event = try {
            NostrEventCodec.decodeRelayEvent(Sha256EventHasher, message, relay)
        } catch (_: NostrEventCodec.Rejected) {
            return null
        }
        val rumor = space.bitos.core.publish.SecureDmComposer.unwrap(event, myPrivateKeyHex) ?: return null
        val peer = rumor.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1) ?: return null
        return mapOf(
            "id" to rumor.id.value,
            "author" to rumor.pubkey.value,
            "peer" to peer,
            "content" to rumor.content,
            "createdAt" to rumor.createdAt,
        )
    }

    /** APP-011: wrap a message → {rumor, seal, wrap} Events. */
    fun secureDmWrap(senderPrivateKeyHex: String, recipientPubkey: String, content: String, nowSeconds: Long): space.bitos.core.publish.SecureDmComposer.WrappedMessage? {
        space.bitos.core.publish.SecureDmClock.now = { nowSeconds }
        return space.bitos.core.publish.SecureDmComposer.wrapMessage(senderPrivateKeyHex, recipientPubkey, content)
    }

    /** APP-011: wrap result as a flat map (iOS-friendly — no nested events). */
    fun secureDmWrapResult(
        senderPrivateKeyHex: String,
        recipientPubkey: String,
        content: String,
        nowSeconds: Long,
    ): Map<String, Any>? {
        space.bitos.core.publish.SecureDmClock.now = { nowSeconds }
        val wrapped = space.bitos.core.publish.SecureDmComposer.wrapMessage(senderPrivateKeyHex, recipientPubkey, content) ?: return null
        return mapOf(
            "rumorId" to wrapped.rumor.id.value,
            "rumorPubkey" to wrapped.rumor.pubkey.value,
            "rumorContent" to wrapped.rumor.content,
            "rumorCreatedAt" to wrapped.rumor.createdAt,
            "wrapId" to wrapped.wrap.id.value,
            "wrapPubkey" to wrapped.wrap.pubkey.value,
            "wrapCreatedAt" to wrapped.wrap.createdAt,
            "wrapKind" to wrapped.wrap.kind,
            "wrapTags" to wrapped.wrap.tags,
            "wrapContent" to wrapped.wrap.content,
            "wrapSig" to (wrapped.wrap.signature ?: return null),
        )
    }

    /** APP-011: the ["EVENT",…] publish frame for a wrapped DM event. */
    fun secureDmPublishMessage(wrap: Event): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { wrap.createdAt })
        val unsigned = space.bitos.core.publish.UnsignedNote(
            idHex = wrap.id,
            pubkeyHex = wrap.pubkey,
            createdAtSeconds = wrap.createdAt,
            kind = wrap.kind,
            tags = wrap.tags,
            content = wrap.content,
        )
        return composer.publishMessage(unsigned, wrap.signature ?: return null)
    }

    /** APP-009: bolt11 HRP msat for zap tallies; 0 when unparseable. */
    fun bolt11AmountMillisats(invoice: String): Long =
        space.bitos.core.model.Bolt11.amountMillisats(invoice) ?: 0L

    fun zapFormatSats(sats: Long): String = space.bitos.core.model.ZapFormat.sats(sats)

    /** APP-014 invoice expiry (epoch seconds); 0 when unparseable. */
    fun bolt11ExpirySeconds(invoice: String): Long =
        space.bitos.core.model.Bolt11.expirySeconds(invoice) ?: 0L

    // ── APP-014 sent-zap ledger + paid matching (iOS seam) ───────────

    /** Records JSON [{id,sats,to,at,note?,memo?}] → versioned ledger wire. */
    fun sentZapsEncode(recordsJson: String): String {
        val records = sentZapRecordsFromJson(recordsJson)
        return space.bitos.core.model.SentZapLedger.encode(records)
    }

    /** Ledger wire → normalized records JSON (null when corrupt). */
    fun sentZapsDecode(wire: String): String? {
        val records = space.bitos.core.model.SentZapLedger.decode(wire)
        return sentZapRecordsJson(records)
    }

    /** Merge local sent records with received zaps → entries JSON
     * (newest first; shared merge rule). receivedJson: [{sats,from,at,note?}]. */
    fun zapLedgerEntries(sentRecordsJson: String, receivedJson: String): String {
        val sent = sentZapRecordsFromJson(sentRecordsJson)
        val received = try {
            val array = Json.parseToJsonElement(receivedJson).jsonArray
            List(array.size) { index ->
                val obj = array[index].jsonObject
                Quadruple(
                    (obj["sats"]?.jsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                    (obj["from"]?.jsonPrimitive)?.content ?: "",
                    (obj["at"]?.jsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                    (obj["note"]?.jsonPrimitive)?.content?.takeIf { it.isNotEmpty() },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        val entries = space.bitos.core.model.SentZapLedger.ledger(
            sent = sent,
            receivedSats = received.map { it.first },
            receivedFrom = received.map { it.second },
            receivedAt = received.map { it.third },
            receivedNote = received.map { it.fourth },
        )
        return entries.joinToString(prefix = "[", separator = ",", postfix = "]") { entry ->
            buildJsonObject {
                put("direction", if (entry.direction == space.bitos.core.model.SentZapLedger.Direction.RECEIVED) "received" else "sent")
                put("sats", entry.sats)
                put("peer", entry.peerPubkey)
                put("at", entry.createdAt)
                put("memo", entry.memo ?: "")
                put("note", entry.targetNoteId ?: "")
            }.toString()
        }
    }

    /** Entries JSON → totals {received,sent,avg,net} (shared rule). */
    fun zapLedgerTotals(entriesJson: String): String? {
        val entries = try {
            Json.parseToJsonElement(entriesJson).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                space.bitos.core.model.SentZapLedger.LedgerEntry(
                    direction = if ((obj["direction"]?.jsonPrimitive)?.content == "received") {
                        space.bitos.core.model.SentZapLedger.Direction.RECEIVED
                    } else {
                        space.bitos.core.model.SentZapLedger.Direction.SENT
                    },
                    sats = (obj["sats"]?.jsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null,
                    peerPubkey = (obj["peer"]?.jsonPrimitive)?.content ?: "",
                    createdAt = (obj["at"]?.jsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                    memo = (obj["memo"]?.jsonPrimitive)?.content?.takeIf { it.isNotEmpty() },
                    targetNoteId = (obj["note"]?.jsonPrimitive)?.content?.takeIf { it.isNotEmpty() },
                )
            }
        } catch (_: Exception) {
            return null
        }
        val totals = space.bitos.core.model.SentZapLedger.totals(entries)
        return buildJsonObject {
            put("received", totals.receivedSats)
            put("sent", totals.sentSats)
            put("avg", totals.averageSats)
            put("net", totals.netSats)
        }.toString()
    }

    /** Paid-matching key from a verified 9735 relay frame (embedded 9734
     * canonical id through the client gate); null when absent/invalid. */
    fun embeddedZapRequestId(message: String, relayUrl: String): String? {
        val relay = RelayUrl.parse(relayUrl) ?: return null
        val event = try {
            NostrEventCodec.decodeRelayEvent(Sha256EventHasher, message, relay)
        } catch (_: NostrEventCodec.Rejected) {
            return null
        } ?: return null
        return space.bitos.core.model.ZapReceipt.embeddedRequestId(event)
    }

    private fun sentZapRecordsFromJson(recordsJson: String): List<space.bitos.core.model.SentZapRecord> = try {
        Json.parseToJsonElement(recordsJson).jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            space.bitos.core.model.SentZapRecord(
                id = (obj["id"]?.jsonPrimitive)?.content ?: return@mapNotNull null,
                amountSats = (obj["sats"]?.jsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null,
                recipientPubkey = (obj["to"]?.jsonPrimitive)?.content ?: return@mapNotNull null,
                createdAt = (obj["at"]?.jsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null,
                targetNoteId = (obj["note"]?.jsonPrimitive)?.content?.takeIf { it.isNotEmpty() },
                memo = (obj["memo"]?.jsonPrimitive)?.content?.takeIf { it.isNotEmpty() },
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun sentZapRecordsJson(records: List<space.bitos.core.model.SentZapRecord>): String =
        records.joinToString(prefix = "[", separator = ",", postfix = "]") { record ->
            buildJsonObject {
                put("id", record.id)
                put("sats", record.amountSats)
                put("to", record.recipientPubkey)
                put("at", record.createdAt)
                put("note", record.targetNoteId ?: "")
                put("memo", record.memo ?: "")
            }.toString()
        }

    /**
     * APP-008 composer-draft persistence seam: encode the draft wire
     * (urlsJson = ["https://…"], trackedJson = [{"n":…,"u":…}]).
     */
    fun composerDraftEncode(
        text: String,
        urlsJson: String,
        cwReason: String,
        trackedJson: String,
        powTarget: Int,
    ): String {
        val urls = try {
            Json.parseToJsonElement(urlsJson).jsonArray.map { it.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyList()
        }
        val tracked = try {
            Json.parseToJsonElement(trackedJson).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val name = (obj["n"]?.jsonPrimitive)?.content ?: return@mapNotNull null
                val npub = (obj["u"]?.jsonPrimitive)?.content ?: return@mapNotNull null
                name to npub
            }
        } catch (_: Exception) {
            emptyList()
        }
        return space.bitos.core.publish.ComposerDraftContract.encode(
            space.bitos.core.publish.ComposerDraft(
                text = text,
                remoteUrls = urls,
                contentWarningReason = cwReason.ifBlank { null },
                trackedMentions = tracked,
                powTarget = powTarget,
            ),
        )
    }

    /** Draft decode: {text, urls:[…], cw, mentions:[{n,u}], pow}; null when
     * the wire is corrupt/oversized (callers start from an empty draft). */
    fun composerDraftDecode(json: String): Map<String, Any>? {
        val draft = space.bitos.core.publish.ComposerDraftContract.decode(json) ?: return null
        return mapOf(
            "text" to draft.text,
            "urls" to draft.remoteUrls,
            "cw" to (draft.contentWarningReason ?: ""),
            "mentions" to draft.trackedMentions.map { (name, npub) -> mapOf("n" to name, "u" to npub) },
            "pow" to draft.powTarget,
        )
    }

    /**
     * APP-008 poll tags for the composer (validated 2–6/280/80 legacy
     * bounds) as TagsCodec JSON incl. hashtag t-tags from the question;
     * null when invalid.
     */
    fun composePollTags(question: String, optionsJson: String): String? {
        val options = try {
            Json.parseToJsonElement(optionsJson).jsonArray.map { it.jsonPrimitive.content }
        } catch (_: Exception) {
            return null
        }
        val pollTags = space.bitos.core.model.PollContract.pollTags(question, options) ?: return null
        val hashtagTags = space.bitos.core.publish.ComposerRules.deriveTags(question.trim())
            .filter { it.firstOrNull() == "t" }
        return space.bitos.core.store.TagsCodec.encode(pollTags + hashtagTags)
    }

    /** APP-008 tags-aware kind-1 compose/publish path. */
    fun composeTextNoteWithTagsEventId(content: String, pubkeyHex: String, nowSeconds: Long, tagsJson: String): String? {
        val tags = space.bitos.core.store.TagsCodec.decode(tagsJson) ?: return null
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeTextNote(pubkeyHex, content, tags)?.idHex
    }

    fun textNoteWithTagsPublishMessage(
        content: String,
        pubkeyHex: String,
        createdAtSeconds: Long,
        signatureHex: String,
        tagsJson: String,
    ): String? {
        val tags = space.bitos.core.store.TagsCodec.decode(tagsJson) ?: return null
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val note = composer.composeTextNote(pubkeyHex, content, tags) ?: return null
        return composer.publishMessage(note, signatureHex)
    }

    /** APP-008 tags-aware PoW: same `nonce:id` output contract as
     * [mineTextNotePow] but the template carries [tagsJson]. */
    fun mineTextNotePowWithTags(
        content: String,
        pubkeyHex: String,
        createdAtSeconds: Long,
        targetDifficulty: Int,
        startNonce: Long,
        maxAttempts: Long,
        tagsJson: String,
    ): String? {
        val tags = space.bitos.core.store.TagsCodec.decode(tagsJson) ?: return null
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val base = composer.composeTextNote(pubkeyHex, content, tags) ?: return null
        return space.bitos.core.nostr.Pow.mineChunk(
            Sha256EventHasher,
            base.pubkeyHex,
            base.createdAtSeconds,
            base.kind,
            base.tags,
            base.content,
            targetDifficulty,
            startNonce,
            maxAttempts,
        )?.let { "${'$'}{it.nonce}:${'$'}{it.idHex}" }
    }

    fun powTextNoteWithTagsEventId(
        content: String,
        pubkeyHex: String,
        createdAtSeconds: Long,
        nonce: Long,
        targetDifficulty: Int,
        tagsJson: String,
    ): String? {
        val tags = space.bitos.core.store.TagsCodec.decode(tagsJson) ?: return null
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        return composer.composeTextNoteWithPow(pubkeyHex, content, nonce, targetDifficulty, createdAtSeconds, tags)?.idHex
    }

    fun powTextNoteWithTagsPublishMessage(
        content: String,
        pubkeyHex: String,
        createdAtSeconds: Long,
        nonce: Long,
        targetDifficulty: Int,
        signatureHex: String,
        tagsJson: String,
    ): String? {
        val tags = space.bitos.core.store.TagsCodec.decode(tagsJson) ?: return null
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val note = composer.composeTextNoteWithPow(pubkeyHex, content, nonce, targetDifficulty, createdAtSeconds, tags) ?: return null
        return composer.publishMessage(note, signatureHex)
    }

    /** Composes the unsigned kind-1 note and returns its canonical id. */
    fun composeEventId(content: String, pubkeyHex: String, nowSeconds: Long): String? {

        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeTextNote(pubkeyHex, content)?.idHex
    }

    /** The ["EVENT", {...}] frame with the signature attached, or null. */
    fun publishMessage(content: String, pubkeyHex: String, createdAtSeconds: Long, signatureHex: String): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeTextNote(pubkeyHex, content) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    // -----------------------------------------------------------------
    // NIP-13 proof-of-work seam (APP-008 PowCard): chunked mining over a
    // kind-1 template, then publish with the committed nonce tag.
    // -----------------------------------------------------------------

    /**
     * Mines at most [maxAttempts] nonces for a kind-1 note (empty base
     * tags) with the FIXED [createdAtSeconds] — the published note must
     * reuse that timestamp. Returns `"nonce:idHex"` for the first attempt
     * meeting the target, else null (resume at `startNonce + maxAttempts`).
     * Callers drive chunks for progress/cancellation.
     */
    fun mineTextNotePow(
        content: String,
        pubkeyHex: String,
        createdAtSeconds: Long,
        targetDifficulty: Int,
        startNonce: Long,
        maxAttempts: Long,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val base = composer.composeTextNote(pubkeyHex, content) ?: return null
        return space.bitos.core.nostr.Pow.mineChunk(
            Sha256EventHasher,
            base.pubkeyHex,
            base.createdAtSeconds,
            base.kind,
            base.tags,
            base.content,
            targetDifficulty,
            startNonce,
            maxAttempts,
        )?.let { "${'$'}{it.nonce}:${'$'}{it.idHex}" }
    }

    /** Canonical id of the pow note (verification parity for callers). */
    fun powTextNoteEventId(
        content: String,
        pubkeyHex: String,
        createdAtSeconds: Long,
        nonce: Long,
        targetDifficulty: Int,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        return composer.composeTextNoteWithPow(pubkeyHex, content, nonce, targetDifficulty, createdAtSeconds)?.idHex
    }

    /** The ["EVENT", {...}] frame carrying the signed pow note. */
    fun powTextNotePublishMessage(
        content: String,
        pubkeyHex: String,
        createdAtSeconds: Long,
        nonce: Long,
        targetDifficulty: Int,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeTextNoteWithPow(pubkeyHex, content, nonce, targetDifficulty, createdAtSeconds) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** Composes the unsigned kind-7 reaction and returns its canonical id. */
    fun composeReactionEventId(targetEventId: String, targetPubkey: String, authorPubkey: String, nowSeconds: Long): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeReaction(targetEventId, targetPubkey, authorPubkey)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-7 reaction, or null. */
    fun reactionPublishMessage(
        targetEventId: String,
        targetPubkey: String,
        authorPubkey: String,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeReaction(targetEventId, targetPubkey, authorPubkey) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /**
     * Followed pubkeys from a verified kind-3 relay frame; null when the
     * frame is malformed or fails verification.
     */
    fun contactListAuthors(message: String, relayUrl: String): List<String>? {
        val relay = RelayUrl.parse(relayUrl) ?: return null
        val event = try {
            NostrEventCodec.decodeRelayEvent(Sha256EventHasher, message, relay)
        } catch (_: NostrEventCodec.Rejected) {
            return null
        }
        if (!NostrEventCodec.verifySignature(Sha256EventHasher, event)) return null
        return space.bitos.core.model.ContactList.followedPubkeys(event)
    }

    /** REQ for the account's own newest kind-3 contact list. */
    fun contactListRequest(subscriptionId: String, accountPubkey: String): String =
        NostrEventCodec.encodeRequest(subscriptionId, """{"kinds":[3],"authors":["$accountPubkey"],"limit":1}""")

    /** REQ for followed authors' notes (the Following timeline). */
    fun followingRequest(subscriptionId: String, authors: List<String>): String {
        val joined = authors.take(space.bitos.core.model.ContactList.MAX_FOLLOWS)
            .joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
        val filter = """{"kinds":[${NostrKinds.SHORT_TEXT_NOTE},${NostrKinds.NORMAL_VIDEO},${NostrKinds.SHORT_VIDEO}],"authors":$joined,"limit":40}"""
        return NostrEventCodec.encodeRequest(subscriptionId, filter)
    }

    /** Composes the unsigned kind-1 reply and returns its canonical id. */
    fun composeReplyEventId(
        content: String,
        targetEventId: String,
        targetPubkey: String,
        authorPubkey: String,
        relayHint: String?,
        nowSeconds: Long,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeReply(content, targetEventId, targetPubkey, authorPubkey, relayHint)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-1 reply, or null. */
    fun replyPublishMessage(
        content: String,
        targetEventId: String,
        targetPubkey: String,
        authorPubkey: String,
        relayHint: String?,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeReply(content, targetEventId, targetPubkey, authorPubkey, relayHint) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /**
     * APP-009 reply bar tags (legacy `publishReply` parity): both NIP-10
     * markers + participant p-tags + content entities, as the TagsCodec
     * wire JSON for `publishNote(tags:)`/`publishPowNote(tags:)`.
     * `targetPTagsJson`: `["<64-hex>", …]` (the target's p-tags).
     */
    fun replyTagsJson(
        rootEventId: String,
        targetEventId: String,
        targetPubkey: String,
        targetPTagsJson: String,
        content: String,
    ): String? {
        val participants = try {
            Json.parseToJsonElement(targetPTagsJson).jsonArray.map { it.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyList()
        }
        return space.bitos.core.publish.NoteComposer
            .replyTags(rootEventId, targetEventId, targetPubkey, participants, content)
            ?.let(space.bitos.core.store.TagsCodec::encode)
    }

    /** REQ for replies to one event (NIP-01 tagged #e filter). */
    fun commentsRequest(subscriptionId: String, targetEventId: String): String =
        NostrEventCodec.encodeRequest(subscriptionId, """{"kinds":[${NostrKinds.SHORT_TEXT_NOTE},7,6,${space.bitos.core.model.ZapReceipt.RECEIPT_KIND}],"#e":["$targetEventId"],"limit":50}""")

    /**
     * APP-009 root resolution: `note1`/`nevent1`/`naddr1` (± `nostr:`
     * prefix) → pointer map. Forms: `id` → {form, id, author, relays};
     * `coord` → {form, kind(int), pubkey, d, author, relays}. Null = invalid.
     */
    fun eventRefParse(bech32: String): Map<String, Any>? {
        val ref = space.bitos.core.nostr.EventRefs.parse(bech32) ?: return null
        return when (ref) {
            is space.bitos.core.nostr.EventRef.ById -> mapOf(
                "form" to "id",
                "id" to ref.id,
                "author" to (ref.authorPubkey ?: ""),
                "relays" to ref.relayHints,
            )
            is space.bitos.core.nostr.EventRef.ByCoordinate -> mapOf(
                "form" to "coord",
                "kind" to ref.kind,
                "pubkey" to ref.pubkey,
                "d" to ref.d,
                "author" to ref.authorPubkey,
                "relays" to ref.relayHints,
            )
        }
    }

    /** APP-009 root fetch by hex id (thread head REQ). */
    fun threadRootRequestById(subscriptionId: String, eventId: String): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            space.bitos.core.nostr.EventRefs.requestFilter(
                space.bitos.core.nostr.EventRef.ById(eventId, null, emptyList()),
            ),
        )

    /** APP-009 root fetch by NIP-33 coordinate (newest `#d` version). */
    fun threadRootRequestByCoordinate(subscriptionId: String, kind: Int, pubkey: String, d: String): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            space.bitos.core.nostr.EventRefs.requestFilter(
                space.bitos.core.nostr.EventRef.ByCoordinate(kind, pubkey, d, pubkey, emptyList()),
            ),
        )

    /**
     * APP-009 X-style threading for the iOS renderer (shared
     * `ThreadAssembly`). Input: JSON array of reply projections
     * `{"id":…,"createdAt":n,"threadRootId":…,"threadParentId":…}`.
     * Output (shape locked by `ThreadAssemblyTest.bridgeJsonShape`):
     * `[{"id":…,"depth":n,"parent":…,"orphan":bool}]`.
     */
    fun threadItemsJson(rootId: String, itemsJson: String): String? {
        val replies = try {
            Json.parseToJsonElement(itemsJson).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                FeedNote(
                    id = obj.getValue("id").jsonPrimitive.content,
                    pubkey = "t",
                    content = "",
                    createdAt = obj.getValue("createdAt").jsonPrimitive.content.toLongOrNull() ?: return@mapNotNull null,
                    kind = 1,
                    replyTo = null,
                    threadRootId = (obj["threadRootId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotEmpty() },
                    threadParentId = (obj["threadParentId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotEmpty() },
                    hashtags = emptyList(),
                    mentions = emptyList(),
                    mediaUrls = emptyList(),
                    isProtocolPayload = false,
                )
            }
        } catch (_: Exception) {
            return null
        }
        return space.bitos.core.feed.ThreadAssembly.assemble(rootId, replies).joinToString(prefix = "[", separator = ",", postfix = "]") { item ->
            buildJsonObject {
                put("id", item.id)
                put("depth", item.depth)
                put("parent", item.parentId ?: "")
                put("orphan", item.orphan)
            }.toString()
        }
    }

    /** Composes the unsigned kind-3 follow list and returns its canonical id. */
    fun composeFollowListEventId(authorPubkey: String, follows: List<String>, nowSeconds: Long): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeFollowList(authorPubkey, follows)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-3 follow list, or null. */
    fun followListPublishMessage(
        authorPubkey: String,
        follows: List<String>,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeFollowList(authorPubkey, follows) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    // ── Relay manager (APP-018 §3.18, NIP-65) ──────────────────────

    /** Managed-relay wire row for the bridge ([RelayListContract] JSON in/out). */
    class RelayEntryWire(val url: String, val read: Boolean, val write: Boolean, val primary: Boolean) {
        constructor(url: String, read: Boolean, write: Boolean) : this(url, read, write, false)
    }

    /** Lenient decode of the persisted managed set; corruption yields []. */
    fun relayListDecode(json: String): List<RelayEntryWire> =
        space.bitos.core.model.RelayListContract.decode(json).map {
            RelayEntryWire(url = it.url.value, read = it.read, write = it.write, primary = it.primary)
        }

    /** Normalizes + encodes the managed set to the versioned wire JSON. */
    fun relayListEncode(entries: List<RelayEntryWire>): String =
        space.bitos.core.model.RelayListContract.encode(
            entries.mapNotNull { wire ->
                space.bitos.core.model.RelayUrl.parse(wire.url)
                    ?.let { space.bitos.core.model.RelayEntry(it, wire.read, wire.write, wire.primary) }
            },
        )

    /** QR matrix rows as bit-packed Longs (bit i = module i, true = dark); empty when unencodable. */
    fun qrMatrix(text: String): List<Long> =
        space.bitos.core.nostr.QrCode.encode(text)?.map { row ->
            var bits = 0L
            row.forEachIndexed { i, dark -> if (dark) bits = bits or (1L shl i) }
            bits
        } ?: emptyList()

    /** Canonical url for a valid add-field input, or null (validation rule). */
    fun relayUrlNormalize(raw: String): String? =
        space.bitos.core.model.RelayUrl.parse(raw)?.value

    // ── App facts (APP-018 help/about content, APP-020 parity) ─────

    /** Static help/about content wire (single source in `AppFacts`). */
    class AppFactsWire(
        val appName: String,
        val tagline: String,
        val license: String,
        val builtOn: String,
        val supportedNips: List<Long>,
        val linkNips: String,
        val linkNostr: String,
        val linkSource: String,
        val supportNpub: String,
        val supportTiersSats: List<Long>,
        val recommendedTierSats: Long,
        val contributorNpubs: List<String>,
        val contributeNote: String,
    )

    /** FAQ entry wire for the settings help section. */
    class FaqEntryWire(val question: String, val answer: String)

    fun appFacts(): AppFactsWire = AppFactsWire(
        appName = space.bitos.core.settings.AppFacts.APP_NAME,
        tagline = space.bitos.core.settings.AppFacts.TAGLINE,
        license = space.bitos.core.settings.AppFacts.LICENSE,
        builtOn = space.bitos.core.settings.AppFacts.BUILT_ON,
        supportedNips = space.bitos.core.settings.AppFacts.SUPPORTED_NIPS.map { it.toLong() },
        linkNips = space.bitos.core.settings.AppFacts.LINK_NIPS,
        linkNostr = space.bitos.core.settings.AppFacts.LINK_NOSTR,
        linkSource = space.bitos.core.settings.AppFacts.LINK_SOURCE,
        supportNpub = space.bitos.core.settings.AppFacts.SUPPORT_NPUB,
        supportTiersSats = space.bitos.core.settings.AppFacts.SUPPORT_TIERS.map { it.sats.toLong() },
        recommendedTierSats = space.bitos.core.settings.AppFacts.SUPPORT_TIERS
            .firstOrNull { it.recommended }?.sats?.toLong() ?: 0,
        contributorNpubs = space.bitos.core.settings.AppFacts.CONTRIBUTOR_NPUBS,
        contributeNote = space.bitos.core.settings.AppFacts.CONTRIBUTE_NOTE,
    )

    fun appFactsFaq(): List<FaqEntryWire> =
        space.bitos.core.settings.AppFacts.FAQ.map {
            FaqEntryWire(question = it.question, answer = it.answer)
        }

    // ── Algorithm preferences (APP-018 §3.18, origin parity) ──────────

    /** Algorithm wire types (surface/signal keys are the shared wire names). */
    class AlgoSignalWire(val enabled: Boolean, val weight: Double)
    class AlgoSurfaceWire(val enabled: Boolean, val diversityEnabled: Boolean, val signals: Map<String, AlgoSignalWire>) {
        constructor(enabled: Boolean, signals: Map<String, AlgoSignalWire>) :
            this(enabled, true, signals)
    }
    class AlgoSnapshotWire(val freshnessHours: Long, val surfaces: Map<String, AlgoSurfaceWire>)

    fun algorithmFreshnessSteps(): List<Long> =
        space.bitos.core.feed.AlgorithmContract.FRESHNESS_STEPS_HOURS.map { it.toLong() }

    fun algorithmSnapshotDecode(json: String): AlgoSnapshotWire {
        val s = space.bitos.core.feed.AlgorithmContract.decode(json)
        return snapshotToWire(s)
    }

    fun algorithmSnapshotEncode(wire: AlgoSnapshotWire): String {
        val s = wireToSnapshot(wire)
        return space.bitos.core.feed.AlgorithmContract.encode(s)
    }

    fun algorithmPresetWire(surfaceWire: String, presetWire: String): AlgoSurfaceWire {
        val surface = space.bitos.core.feed.AlgorithmSurface.entries.first { it.wire == surfaceWire }
        val preset = when (presetWire) {
            "LATEST" -> space.bitos.core.feed.AlgorithmPresetId.LATEST
            "TRENDING" -> space.bitos.core.feed.AlgorithmPresetId.TRENDING
            "TRUSTED" -> space.bitos.core.feed.AlgorithmPresetId.TRUSTED
            else -> space.bitos.core.feed.AlgorithmPresetId.BALANCED
        }
        return surfaceToWire(surface, space.bitos.core.feed.AlgorithmContract.preset(surface, preset))
    }

    fun algorithmDetectPreset(surfaceWire: String, wire: AlgoSurfaceWire): String {
        val surface = space.bitos.core.feed.AlgorithmSurface.entries.first { it.wire == surfaceWire }
        val setting = wireToSurface(wire)
        return space.bitos.core.feed.AlgorithmContract.detectPreset(surface, setting).name
    }

    /**
     * Ranking seam for the Swift feed mirror: orders minimal note rows
     * (JSON `[{"id","pubkey","createdAt"},…]`) through the shared engine
     * and returns the ordered ids.
     */
    fun algorithmRankIds(
        notesJson: String,
        surfaceWire: String,
        snapshotJson: String,
        followingJson: String,
        zapCountsJson: String,
        replyCountsJson: String,
        nowSeconds: Long,
    ): List<String> {
        val surface = space.bitos.core.feed.AlgorithmSurface.entries.first { it.wire == surfaceWire }
        val snapshot = space.bitos.core.feed.AlgorithmContract.decode(snapshotJson)
        val notes = parseMinimalNotes(notesJson) ?: return emptyList()
        val following = parseStringSet(followingJson)
        val zapCounts = parseCountMap(zapCountsJson)
        val replyCounts = parseCountMap(replyCountsJson)
        return space.bitos.core.feed.FeedRanking.rank(
            notes = notes,
            surface = surface,
            snapshot = snapshot,
            ctx = space.bitos.core.feed.RankingContext(
                nowSeconds = nowSeconds,
                following = following,
                zapCounts = zapCounts,
                replyCounts = replyCounts,
            ),
        ).map { it.id }
    }

    private fun snapshotToWire(s: space.bitos.core.feed.AlgorithmSnapshot) = AlgoSnapshotWire(
        freshnessHours = s.freshnessHours.toLong(),
        surfaces = s.surfaces.entries.associate { (surface, setting) ->
            surface.wire to surfaceToWire(surface, setting)
        },
    )

    private fun wireToSnapshot(w: AlgoSnapshotWire): space.bitos.core.feed.AlgorithmSnapshot {
        val surfaces = w.surfaces.entries.mapNotNull { (key, wire) ->
            space.bitos.core.feed.AlgorithmSurface.entries
                .firstOrNull { it.wire == key }
                ?.let { it to wireToSurface(wire) }
        }.toMap()
        return space.bitos.core.feed.AlgorithmSnapshot(w.freshnessHours.toInt(), surfaces)
    }

    private fun surfaceToWire(surface: space.bitos.core.feed.AlgorithmSurface, setting: space.bitos.core.feed.SurfaceSetting) =
        AlgoSurfaceWire(
            enabled = setting.enabled,
            diversityEnabled = setting.diversityEnabled,
            signals = setting.signals.entries.associate { (signal, s) ->
                signal.wire to AlgoSignalWire(s.enabled, s.weight)
            },
        )

    private fun wireToSurface(w: AlgoSurfaceWire): space.bitos.core.feed.SurfaceSetting =
        space.bitos.core.feed.AlgorithmContract.normalize(
            space.bitos.core.feed.SurfaceSetting(
                enabled = w.enabled,
                signals = w.signals.entries.mapNotNull { (key, s) ->
                    space.bitos.core.feed.AlgorithmSignal.entries
                        .firstOrNull { it.wire == key }
                        ?.let { it to space.bitos.core.feed.SignalSetting(s.enabled, s.weight) }
                }.toMap(),
            ),
        )

    private fun parseMinimalNotes(json: String): List<space.bitos.core.feed.FeedNote>? = try {
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonArray
        arr.map { el ->
            val o = el.jsonObject
            space.bitos.core.feed.FeedNote(
                id = o["id"]?.jsonPrimitive?.content ?: "",
                pubkey = o["pubkey"]?.jsonPrimitive?.content ?: "",
                content = "",
                createdAt = o["createdAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                kind = 1,
                replyTo = null,
                hashtags = emptyList(),
                mentions = emptyList(),
                mediaUrls = emptyList(),
                isProtocolPayload = false,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun parseStringSet(json: String): Set<String> = try {
        kotlinx.serialization.json.Json.parseToJsonElement(json).jsonArray
            .mapNotNull { it.jsonPrimitive.content }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun parseCountMap(json: String): Map<String, Int> = try {
        kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
            .entries.associate { (k, v) -> k to (v.jsonPrimitive.content.toIntOrNull() ?: 0) }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Composes the unsigned NIP-65 relay list and returns its canonical id. */
    fun composeRelayListEventId(authorPubkey: String, relayListJson: String, nowSeconds: Long): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeRelayList(
            authorPubkey,
            space.bitos.core.model.RelayListContract.decode(relayListJson),
        )?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-10002 relay list, or null. */
    fun relayListPublishMessage(
        authorPubkey: String,
        relayListJson: String,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeRelayList(
            authorPubkey,
            space.bitos.core.model.RelayListContract.decode(relayListJson),
        ) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** APP-012/APP-018 blocked-list REQ (NIP-51 kind 10004 head). */
    fun encodeBlockListRequest(subscriptionId: String, accountPubkey: String): String? =
        space.bitos.core.nostr.NostrEventCodec.encodeBlockListRequest(subscriptionId, accountPubkey)

    /** Composes the unsigned kind-10004 block list and returns its canonical id. */
    fun composeBlockListEventId(authorPubkey: String, blocked: List<String>, nowSeconds: Long): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeBlockList(authorPubkey, blocked)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-10004 block list, or null. */
    fun blockListPublishMessage(
        authorPubkey: String,
        blocked: List<String>,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeBlockList(authorPubkey, blocked) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** Composes the unsigned kind-6 repost and returns its canonical id. */
    fun composeRepostEventId(targetEventId: String, targetPubkey: String, authorPubkey: String, nowSeconds: Long): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeRepost(targetEventId, targetPubkey, authorPubkey)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-6 repost, or null. */
    fun repostPublishMessage(
        targetEventId: String,
        targetPubkey: String,
        authorPubkey: String,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeRepost(targetEventId, targetPubkey, authorPubkey) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** Composes the unsigned kind-30003 bookmark list and returns its id. */
    fun composeBookmarkListEventId(authorPubkey: String, eventIds: List<String>, nowSeconds: Long): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeBookmarkList(authorPubkey, eventIds)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed bookmark list, or null. */
    fun bookmarkListPublishMessage(
        authorPubkey: String,
        eventIds: List<String>,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeBookmarkList(authorPubkey, eventIds) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** Saved ids from a verified kind-30003 relay frame, or null. */
    fun bookmarkIds(message: String, relayUrl: String): List<String>? {
        val relay = RelayUrl.parse(relayUrl) ?: return null
        val event = try {
            NostrEventCodec.decodeRelayEvent(Sha256EventHasher, message, relay)
        } catch (_: NostrEventCodec.Rejected) {
            return null
        }
        if (!NostrEventCodec.verifySignature(Sha256EventHasher, event)) return null
        return space.bitos.core.model.BookmarkList.bookmarkedIds(event)
    }

    /** Blocked pubkeys from a verified kind-10004 head (null on any other kind). */
    fun blockListPubkeys(message: String, relayUrl: String): List<String>? {
        val relay = RelayUrl.parse(relayUrl) ?: return null
        val event = try {
            NostrEventCodec.decodeRelayEvent(Sha256EventHasher, message, relay)
        } catch (_: NostrEventCodec.Rejected) {
            return null
        }
        if (!NostrEventCodec.verifySignature(Sha256EventHasher, event)) return null
        return space.bitos.core.model.BlockList.blockedPubkeys(event)?.toList()
    }

    /** REQ for the account's bookmark list (addressable coordinate). */
    fun bookmarkListRequest(subscriptionId: String, accountPubkey: String): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[${space.bitos.core.model.BookmarkList.KIND}],"authors":["$accountPubkey"],"#d":["${space.bitos.core.model.BookmarkList.D_TAG}"],"limit":1}""",
        )

    /** Composes the unsigned kind-9734 zap request and returns its id. */
    fun composeZapRequestEventId(
        recipientPubkey: String,
        amountMillisats: Long,
        relays: List<String>,
        lnurlHint: String,
        comment: String,
        authorPubkey: String,
        targetEventId: String?,
        nowSeconds: Long,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeZapRequest(
            recipientPubkey, amountMillisats, relays, lnurlHint, comment, authorPubkey, targetEventId,
        )?.idHex
    }

    /** The signed kind-9734 JSON (for the LNURL `nostr` param), or null. */
    fun zapRequestMessage(
        recipientPubkey: String,
        amountMillisats: Long,
        relays: List<String>,
        lnurlHint: String,
        comment: String,
        authorPubkey: String,
        targetEventId: String?,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeZapRequest(
            recipientPubkey, amountMillisats, relays, lnurlHint, comment, authorPubkey, targetEventId,
        ) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** REQ for zap receipts (kind 9735) targeting one note. */
    fun zapReceiptsRequest(subscriptionId: String, targetEventId: String): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[${space.bitos.core.model.ZapReceipt.RECEIPT_KIND}],"#e":["$targetEventId"],"limit":50}""",
        )

    /** Target event id of a verified 9735 frame, for count grouping. */
    fun zapReceiptTarget(message: String, relayUrl: String): String? {
        val relay = RelayUrl.parse(relayUrl) ?: return null
        val event = try {
            NostrEventCodec.decodeRelayEvent(Sha256EventHasher, message, relay)
        } catch (_: NostrEventCodec.Rejected) {
            return null
        }
        if (!NostrEventCodec.verifySignature(Sha256EventHasher, event)) return null
        return space.bitos.core.model.ZapReceipt.targetEventId(event)
    }

    /** LNURL-pay params as [callback, minSendable, maxSendable, allowsNostr], or null. */
    fun lnurlParsePayRequest(body: String): List<Any>? {
        val pay = space.bitos.core.model.LnurlPay.parsePayRequest(body) ?: return null
        return listOf(pay.callback, pay.minSendableMillisats, pay.maxSendableMillisats, pay.allowsNostr)
    }

    /**
     * Callback URL with amount + optional nostr event, or null when the
     * amount is outside [minMillisats, maxMillisats] from the real params.
     */
    fun lnurlBuildCallbackUrl(
        callback: String,
        amount: Long,
        minMillisats: Long,
        maxMillisats: Long,
        allowsNostr: Boolean,
        nostrEvent: String?,
        lnurlHint: String,
    ): String? {
        val pay = try {
            space.bitos.core.model.LnurlPay.PayRequest(callback, minMillisats, maxMillisats, allowsNostr, null)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return space.bitos.core.model.LnurlPay.buildCallbackUrl(pay, amount, nostrEvent, lnurlHint)
    }

    /** bolt11 payment request from a callback response, or null. */
    fun lnurlParseInvoice(body: String): String? =
        space.bitos.core.model.LnurlPay.parseInvoice(body)?.paymentRequest

    /** APP-014 LUD-21: the invoice's verify URL (null when the provider
     * does not support LUD-21). */
    fun lnurlInvoiceVerifyUrl(body: String): String? =
        space.bitos.core.model.LnurlPay.parseInvoice(body)?.verifyUrl

    /** LUD-21 settle classification for a verify poll response body. */
    fun lnurlVerifySettled(body: String): Boolean =
        space.bitos.core.model.LnurlPay.verifySettled(body)

    /** Composes the unsigned Blossom kind-24242 upload auth and returns its id. */
    fun composeUploadAuthEventId(
        authorPubkey: String,
        serverUrl: String,
        fileHashHex: String,
        sizeBytes: Long,
        expirationSeconds: Long,
        nowSeconds: Long,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeUploadAuth(authorPubkey, serverUrl, fileHashHex, sizeBytes, expirationSeconds, nowSeconds)?.idHex
    }

    /** The URL-encoded Nostr auth header value from the signed kind-24242. */
    fun uploadAuthHeader(
        authorPubkey: String,
        serverUrl: String,
        fileHashHex: String,
        sizeBytes: Long,
        expirationSeconds: Long,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeUploadAuth(
            authorPubkey, serverUrl, fileHashHex, sizeBytes, expirationSeconds, createdAtSeconds,
        ) ?: return null
        val frame = composer.publishMessage(unsigned, signatureHex) ?: return null
        return "Nostr " + space.bitos.core.model.Blossom.encodeQueryComponent(frame)
    }

    /** Parses a WWW-Authenticate challenge's expiration, or null. */
    fun blossomChallengeExpiration(headerValue: String): Long? =
        space.bitos.core.model.Blossom.challengeExpiration(headerValue)

    /** Composes the unsigned kind-22 media note and returns its id. */
    fun composeMediaNoteEventId(
        authorPubkey: String,
        caption: String,
        url: String,
        sha256Hex: String,
        mimeType: String,
        sizeBytes: Long,
        width: Long,
        height: Long,
        durationMs: Long,
        nowSeconds: Long,
    ): String? {
        val media = try {
            space.bitos.core.model.UploadedMedia(url, sha256Hex, mimeType, sizeBytes, width?.toInt(), height?.toInt(), durationMs)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeMediaNote(authorPubkey, caption, media)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-22 media note, or null. */
    fun mediaNotePublishMessage(
        authorPubkey: String,
        caption: String,
        url: String,
        sha256Hex: String,
        mimeType: String,
        sizeBytes: Long,
        width: Long,
        height: Long,
        durationMs: Long,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val media = try {
            space.bitos.core.model.UploadedMedia(url, sha256Hex, mimeType, sizeBytes, width?.toInt(), height?.toInt(), durationMs)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeMediaNote(authorPubkey, caption, media) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** REQ for events targeting the account (notification inbox). */
    fun notificationsRequest(subscriptionId: String, accountPubkey: String): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[1,7,6,${space.bitos.core.model.ZapReceipt.RECEIPT_KIND},3],"#p":["$accountPubkey"],"limit":50}""",
        )

    /** APP-012 blocked-author filter: REQ for the account's kind-10004 list. */
    fun blockListRequest(subscriptionId: String, accountPubkey: String): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[${space.bitos.core.model.BlockList.KIND}],"authors":["$accountPubkey"],"limit":1}""",
        )

    /**
     * APP-012 blocked set from a verified kind-10004 frame authored by the
     * account. Map keys: `createdAt` (Long), `pubkeys` (List<String>).
     * The store keeps the newest verified head (compare createdAt).
     */
    fun blockListFromFrame(message: String, relayUrl: String, accountPubkey: String): Map<String, Any>? {
        val relay = RelayUrl.parse(relayUrl) ?: return null
        val event = try {
            NostrEventCodec.decodeRelayEvent(Sha256EventHasher, message, relay)
        } catch (_: NostrEventCodec.Rejected) {
            return null
        }
        if (!NostrEventCodec.verifySignature(Sha256EventHasher, event)) return null
        if (event.pubkey.value != accountPubkey) return null
        val blocked = space.bitos.core.model.BlockList.blockedPubkeys(event) ?: return null
        return mapOf(
            "createdAt" to event.createdAt,
            "pubkeys" to blocked.toList(),
        )
    }

    /** APP-012 search-row predicate (shared `NotificationFilters` rule). */
    fun notificationQueryMatches(summary: String, authorName: String?, query: String): Boolean {
        val item = space.bitos.core.model.NotificationItem(
            id = "q",
            authorPubkey = "q",
            kind = space.bitos.core.model.NotificationKind.REACTION,
            targetEventId = null,
            summary = summary,
            createdAt = 0,
        )
        return space.bitos.core.model.NotificationFilters.queryMatches(item, query, authorName)
    }

    /**
     * APP-012 read-cursor rule: explicit marks OR createdAt ≤ cursor. Pass a
     * negative [cursorSeconds] when no cursor is persisted yet.
     */
    fun notificationCursorIsRead(
        id: String,
        createdAtSeconds: Long,
        cursorSeconds: Long,
        explicitlyRead: List<String>,
    ): Boolean =
        space.bitos.core.model.NotificationFilters.isRead(
            space.bitos.core.model.NotificationItem(
                id = id,
                authorPubkey = "q",
                kind = space.bitos.core.model.NotificationKind.REACTION,
                targetEventId = null,
                summary = "",
                createdAt = createdAtSeconds,
            ),
            cursorSeconds = cursorSeconds.takeIf { it >= 0 },
            explicitlyRead = explicitlyRead.toSet(),
        )

    /**
     * APP-012 origin-note REQ: fetch up to [ids] events by id, batched to
     * ≤100 ids per request (relay convention). Invalid/non-hex ids drop.
     */
    fun eventsByIdsRequest(subscriptionId: String, ids: List<String>): String? =
        NostrEventCodec.encodeIdsRequest(subscriptionId, ids)

    /**
     * APP-012 origin-note preview from a verified relay frame, when the
     * event id is in the wanted set. Map keys: id, authorPubkey, kind,
     * createdAt, excerpt, thumbUrl (empty when none).
     */
    fun originNoteFromFrame(message: String, relayUrl: String, wantedIds: List<String>): Map<String, Any>? {
        if (wantedIds.isEmpty()) return null
        val relay = RelayUrl.parse(relayUrl) ?: return null
        val event = try {
            NostrEventCodec.decodeRelayEvent(Sha256EventHasher, message, relay)
        } catch (_: NostrEventCodec.Rejected) {
            return null
        }
        if (!NostrEventCodec.verifySignature(Sha256EventHasher, event)) return null
        if (event.id.value !in wantedIds) return null
        val note = space.bitos.core.model.OriginNotes.project(event)
        return mapOf(
            "id" to note.id,
            "authorPubkey" to note.authorPubkey,
            "kind" to note.kind,
            "createdAt" to note.createdAt,
            "excerpt" to note.excerpt,
            "thumbUrl" to (note.thumbUrl ?: ""),
            "content" to note.content,
            "mediaUrls" to note.mediaUrls,
            "contentWarning" to note.contentWarning,
        )
    }

    /**
     * Extracts a notification for the account from a verified relay frame.
     * Returns a map [id, authorPubkey, kind(int), targetEventId, summary,
     * createdAt(long)] or null.
     */
    fun extractNotification(message: String, relayUrl: String, accountPubkey: String): Map<String, Any>? {
        val relay = RelayUrl.parse(relayUrl) ?: return null
        val event = try {
            NostrEventCodec.decodeRelayEvent(Sha256EventHasher, message, relay)
        } catch (_: NostrEventCodec.Rejected) {
            return null
        }
        if (!NostrEventCodec.verifySignature(Sha256EventHasher, event)) return null
        val item = space.bitos.core.model.NotificationExtractor.extract(event, accountPubkey) ?: return null
        return mapOf(
            "id" to item.id,
            "authorPubkey" to item.authorPubkey,
            "kind" to when (item.kind) {
                space.bitos.core.model.NotificationKind.REPLY -> 0
                space.bitos.core.model.NotificationKind.MENTION -> 1
                space.bitos.core.model.NotificationKind.REACTION -> 2
                space.bitos.core.model.NotificationKind.REPOST -> 3
                space.bitos.core.model.NotificationKind.ZAP -> 4
                space.bitos.core.model.NotificationKind.FOLLOW -> 5
            },
            "targetEventId" to (item.targetEventId ?: ""),
            "summary" to item.summary,
            "createdAt" to item.createdAt,
            "amountMsat" to (item.amountMsat ?: -1L),
        )
    }

    /**
     * APP-012 pure tab predicate (ordinal per [space.bitos.core.model.NotificationTab]).
     */
    fun notificationTabMatches(kindInt: Int, tabOrdinal: Int, isRead: Boolean): Boolean {
        val kind = intToKind(kindInt) ?: return false
        val tab = space.bitos.core.model.NotificationTab.entries.getOrNull(tabOrdinal)
            ?: space.bitos.core.model.NotificationTab.ALL
        return space.bitos.core.model.NotificationFilters.tabMatches(kind, tab, isRead)
    }

    /** APP-012 activity-chip predicate (ordinal per [space.bitos.core.model.NotificationActivity]). */
    fun notificationActivityMatches(kindInt: Int, activityOrdinal: Int): Boolean {
        val kind = intToKind(kindInt) ?: return false
        val activity = space.bitos.core.model.NotificationActivity.entries.getOrNull(activityOrdinal)
            ?: space.bitos.core.model.NotificationActivity.NONE
        return space.bitos.core.model.NotificationFilters.activityMatches(kind, activity)
    }

    private fun intToKind(kindInt: Int): space.bitos.core.model.NotificationKind? =
        space.bitos.core.model.NotificationKind.entries.getOrNull(kindInt)

    /**
     * APP-012 day-section grouping for the iOS renderer. Input: JSON array of
     * notification items (the shape `extractNotification` produces: id,
     * authorPubkey, kind int, targetEventId, summary, createdAt long).
     * Output shape is locked by `NotificationBridgeJsonTest.groupingJsonShapeIsStable`:
     * `{"sections":[{"day":n,"groups":[{"id":…,"kind":n,"actors":[…],
     * "actorCount":n,"target":…,"summary":…,"newest":n,"items":[…]}]}]}`.
     */
    fun groupNotificationsJson(itemsJson: String, nowSeconds: Long): String? {
        val items = try {
            Json.parseToJsonElement(itemsJson).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                space.bitos.core.model.NotificationItem(
                    id = obj.getValue("id").jsonPrimitive.content,
                    authorPubkey = obj.getValue("authorPubkey").jsonPrimitive.content,
                    kind = space.bitos.core.model.NotificationKind.entries.getOrNull(
                        obj.getValue("kind").jsonPrimitive.content.toIntOrNull() ?: return@mapNotNull null,
                    ) ?: return@mapNotNull null,
                    targetEventId = obj["targetEventId"]?.jsonPrimitive?.content?.ifEmpty { null },
                    summary = obj.getValue("summary").jsonPrimitive.content,
                    createdAt = obj.getValue("createdAt").jsonPrimitive.content.toLongOrNull() ?: return@mapNotNull null,
                    amountMsat = obj["amountMsat"]?.jsonPrimitive?.content?.toLongOrNull(),
                )
            }
        } catch (_: Exception) {
            return null
        }
        val sections = space.bitos.core.model.NotificationSections.sections(items, nowSeconds)
        return buildJsonObject {
            put("sections", buildJsonArray {
                sections.forEach { section ->
                    add(buildJsonObject {
                        put("day", section.epochDay)
                        put("groups", buildJsonArray {
                            section.groups.forEach { group ->
                                add(buildJsonObject {
                                    put("id", group.id)
                                    put("kind", space.bitos.core.model.NotificationKind.entries.indexOf(group.kind))
                                    put("actors", buildJsonArray { group.actors.forEach { add(it) } })
                                    put("actorCount", group.actorCount)
                                    put("target", group.targetEventId ?: "")
                                    put("summary", group.sampleSummary)
                                    put("newest", group.newestAt)
                                    put("msat", group.totalMsat)
                                    put("items", buildJsonArray { group.itemIds.forEach { add(it) } })
                                })
                            }
                        })
                    })
                }
            })
        }.toString()
    }

    /** NIP-50 search REQ (full-text search over kinds, relay support varies). */
    fun searchRequest(subscriptionId: String, query: String, kinds: List<Int>, limit: Int): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.length > 128) return null
        val boundedKinds = kinds.filter { it in 0..65_535 }.take(8)
        if (boundedKinds.isEmpty()) return null
        val boundedLimit = limit.coerceIn(1..100)
        val escaped = NostrEventCodec.escape(trimmed)
        return NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[${boundedKinds.joinToString(",")}],"search":"$escaped","limit":$boundedLimit}""",
        )
    }

    /** npub → hex pubkey, when the query is an exact npub (creator search). */
    fun resolveNpub(query: String): String? {
        val trimmed = query.trim()
        return if (trimmed.startsWith("npub1")) space.bitos.core.identity.NostrKeyCodec.parseNpub(trimmed) else null
    }

    /** REQ for one author's profile + notes. */
    fun authorRequest(subscriptionId: String, authorPubkey: String): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[0,1,21,22],"authors":["$authorPubkey"],"limit":20}""",
        )

    /** Composes the unsigned kind-0 profile event and returns its id. */
    fun composeProfileEventId(
        authorPubkey: String,
        name: String,
        displayName: String,
        about: String,
        picture: String,
        nip05: String,
        lud16: String,
        nowSeconds: Long,
        banner: String = "",
        website: String = "",
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeProfileMetadata(authorPubkey, name, displayName, about, picture, nip05, lud16, banner, website)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-0 profile, or null. */
    fun profilePublishMessage(
        authorPubkey: String,
        name: String,
        displayName: String,
        about: String,
        picture: String,
        nip05: String,
        lud16: String,
        createdAtSeconds: Long,
        signatureHex: String,
        banner: String = "",
        website: String = "",
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeProfileMetadata(authorPubkey, name, displayName, about, picture, nip05, lud16, banner, website) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** Composes the unsigned kind-1984 report and returns its id. */
    fun composeReportEventId(
        targetEventId: String?,
        targetPubkey: String,
        authorPubkey: String,
        reason: String,
        relayHint: String?,
        nowSeconds: Long,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeReport(targetEventId, targetPubkey, authorPubkey, reason, relayHint)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-1984 report, or null. */
    fun reportPublishMessage(
        targetEventId: String?,
        targetPubkey: String,
        authorPubkey: String,
        reason: String,
        relayHint: String?,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeReport(targetEventId, targetPubkey, authorPubkey, reason, relayHint) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** Parses an OK receipt: true accepted, false rejected, null not-an-OK. */
    fun parseOkAccepted(message: String): Boolean? =
        space.bitos.core.publish.NoteComposer.parseOkMessage(message)?.accepted

    /** Event id carried by an OK receipt, when the message is one. */
    fun okEventId(message: String): String? =
        space.bitos.core.publish.NoteComposer.parseOkMessage(message)?.eventId

    /** Relay's detail text for an OK receipt (empty when accepted silently). */
    fun okDetail(message: String): String? =
        space.bitos.core.publish.NoteComposer.parseOkMessage(message)?.message

    private fun hexToBytes(hex: String, expectBytes: Int): ByteArray? {
        if (hex.length != expectBytes * 2) return null
        val out = ByteArray(expectBytes)
        for (index in 0 until expectBytes) {
            val hi = when (val c = hex[index * 2]) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                else -> return null
            }
            val lo = when (val c = hex[index * 2 + 1]) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                else -> return null
            }
            out[index] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun bytesToLowercaseHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(digits[value shr 4]).append(digits[value and 0x0f])
        }
        return out.toString()
    }

    private fun Event.toCore() = space.bitos.core.model.NostrEvent(
        id = space.bitos.core.model.EventId.parse(id)!!,
        pubkey = space.bitos.core.model.Pubkey.parse(pubkey)!!,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        signature = signature,
        receivedFromRelay = RelayUrl.parse(relayUrl ?: ""),
    )
}

/**
 * Bounded feed window over the shared `FeedAggregator` rules: dedupe by
 * verified id, newest-first with stable tie-break, size-bounded.
 */
class FeedWindow(maxItems: Int) {
    private val aggregator = FeedAggregator(maxItems)

    fun insert(note: BusinessCoreBridge.Note): Boolean =
        aggregator.insert(note.toCore())

    fun snapshot(): List<BusinessCoreBridge.Note> =
        aggregator.snapshot().map { it.toBridge() }

    fun size(): Int = aggregator.size()

    private fun BusinessCoreBridge.Note.toCore() = FeedNote(
        id = id,
        pubkey = pubkey,
        content = content,
        createdAt = createdAt,
        kind = kind,
        replyTo = replyTo,
        hashtags = hashtags,
        mentions = mentions,
        mediaUrls = mediaUrls,
        isProtocolPayload = protocolPayload,
        repostedBy = repostedBy,
        video = videoUrl?.let { MediaMetadata(it, videoMime, posterUrl, videoWidth, videoHeight) },
    )

    private fun FeedNote.toBridge() = BusinessCoreBridge.Note(
        id = id,
        pubkey = pubkey,
        content = content,
        createdAt = createdAt,
        kind = kind,
        replyTo = replyTo,
        hashtags = hashtags,
        mentions = mentions,
        mediaUrls = mediaUrls,
        protocolPayload = isProtocolPayload,
        repostedBy = repostedBy,
        videoUrl = video?.url,
        videoMime = video?.mimeType,
        posterUrl = video?.posterUrl,
        videoWidth = video?.width,
        videoHeight = video?.height,
    )
}

/** Local 4-tuple for the ledger bridge parsing. */
private data class Quadruple(val first: Long, val second: String, val third: Long, val fourth: String?)
