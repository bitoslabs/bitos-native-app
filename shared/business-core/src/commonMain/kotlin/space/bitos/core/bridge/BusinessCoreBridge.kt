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
import space.bitos.core.model.MediaRendition
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
        /** APP-007 remix source (id + author pubkey); null = original work. */
        val remixOfEventId: String? = null,
        val remixOfPubkey: String? = null,
        /** APP-007 `license` tag (remix advisory gate); null = permissive. */
        val license: String? = null,
        /** FED-004 mirror chain (NIP-92 `fallback`), order preserved. */
        val fallbackUrls: List<String> = emptyList(),
        /** FED-004 rendition ladder as `url|height|bitrate` spec rows. */
        val renditionSpecs: List<String> = emptyList(),
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

    /** EOSE subscription id, used to complete parallel-relay page batches early. */
    fun relayEoseSubscriptionId(message: String): String? =
        NostrEventCodec.relayEoseSubscriptionId(message)

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
        showProtocolNotes: Boolean = false,
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
                MediaMetadata(
                    url = it,
                    mimeType = note.videoMime,
                    posterUrl = note.posterUrl,
                    width = note.videoWidth,
                    height = note.videoHeight,
                    durationSeconds = note.durationSeconds,
                    fallbackUrls = note.fallbackUrls,
                    renditions = note.renditionSpecs.mapNotNull(::parseRenditionSpec),
                )
            },
            repostedBy = note.repostedBy,
            contentWarning = note.contentWarning,
            threadRootId = note.threadRootId,
            threadParentId = note.threadParentId,
        )
        return space.bitos.core.feed.FeedFilters.passes(coreNote, filter, ownPubkeyHex, likedIds.toSet(), showProtocolNotes)
    }

    /**
     * Content classification (web `content-classification.ts` parity):
     * serialized channel rosters are machine traffic, not reader content.
     */
    fun isProtocolPayload(content: String): Boolean =
        space.bitos.core.nostr.ContentClassification.isProtocolPayload(content)

    /** Bot coordination tags (`udal-*`) never carry a human topic. */
    fun isMachineTag(tag: String): Boolean =
        space.bitos.core.nostr.ContentClassification.isMachineTag(tag)

    /** Filter a tag list down to the human-meaningful entries. */
    fun humanTags(tags: List<String>): List<String> =
        space.bitos.core.nostr.ContentClassification.humanTags(tags)


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

    /**
     * Cold-start account bootstrap: whether an unresolved account head (own
     * kind-0, kind-3 contacts, bookmarks, blocks) should be re-issued now —
     * shared `AccountBootstrap` policy through the client seam.
     */
    fun accountBootstrapShouldReissue(resolved: Boolean, attempts: Int, connectedRelays: Int): Boolean =
        space.bitos.core.identity.AccountBootstrap.shouldReissue(resolved, attempts, connectedRelays)

    /** Shared `AccountBootstrap`: fresh relay connectivity opens a new episode. */
    fun accountBootstrapShouldOpenEpisode(previousConnected: Int, currentConnected: Int): Boolean =
        space.bitos.core.identity.AccountBootstrap.shouldOpenEpisode(previousConnected, currentConnected)

    /** APP-004 pagination: one older page — feed kinds before `until`. */
    fun olderFeedRequest(subscriptionId: String, until: Long, limit: Int): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            space.bitos.core.feed.BitzTimelinePolicy.batchFilters(until),
        )

    /** FED-004 walk bound: page budget counts fresh media only. */
    fun bitzWalkPageBudget(): Int = space.bitos.core.feed.BitzTimelinePolicy.PAGE_FRESH_MEDIA_TARGET

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

    /** APP-007 Explore window: 24 initially, then 10 per explicit load-more. */
    fun bitzExploreVisibleCount(loadMoreCount: Int): Int =
        space.bitos.core.feed.BitzExplore.visibleCount(loadMoreCount)

    /** Bitz pagination walk bounds (FED-004): fresh-media budget, max batches. */
    fun bitzWalkMaxBatches(): Int = space.bitos.core.feed.BitzTimelinePolicy.MAX_QUERY_BATCHES

    /** Bitz pagination walk bound: per-batch hard deadline in ms. */
    fun bitzWalkMaxWaitMs(): Long = space.bitos.core.feed.BitzTimelinePolicy.PAGE_MAX_WAIT_MS

    /** Bitz player render-window growth per near-edge trigger. */
    fun bitzWalkRenderBatch(): Int = space.bitos.core.feed.BitzTimelinePolicy.RENDER_BATCH

    /** Bitz prefetch threshold: fetch older when ≤ N loaded-unrendered remain. */
    fun bitzWalkPrefetchThreshold(): Int =
        space.bitos.core.feed.BitzTimelinePolicy.PREFETCH_BUFFER_THRESHOLD

    /**
     * FED-004 rendition pick (shared rule, both players): the ladder rides
     * the note as `url|height|bitrate` rows; the pick is tallest fitting
     * `targetHeight × 1.25`, smallest-on-overshoot, primary when no ladder.
     */
    fun mediaPickRenditionUrl(renditionSpecs: List<String>, primaryUrl: String, targetHeight: Int, qualityWire: String): String {
        val renditions = renditionSpecs.mapNotNull(::parseRenditionSpec)
        if (renditions.isEmpty()) return primaryUrl
        val media = MediaMetadata(
            url = primaryUrl,
            mimeType = null,
            posterUrl = null,
            width = null,
            height = null,
            durationSeconds = null,
            fallbackUrls = emptyList(),
            renditions = renditions,
        )
        // APP-018 `bitos_video_quality` pick (UX U9): AUTO keeps the
        // display-fitting pick; HIGH forces the tallest rung; LOW is the
        // data saver (shortest rung ≥360p, else shortest).
        return when (space.bitos.core.settings.VideoQualitySetting.parse(qualityWire)) {
            space.bitos.core.settings.VideoQualitySetting.HIGH -> media.selectTallestRendition()
            space.bitos.core.settings.VideoQualitySetting.LOW -> media.selectDataSaverRendition()
            space.bitos.core.settings.VideoQualitySetting.AUTO -> media.selectRendition(targetHeight)
        }
    }

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
            fallbackUrls = note.video?.fallbackUrls ?: emptyList(),
            renditionSpecs = note.video?.renditions?.map { renditionSpec(it) } ?: emptyList(),
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

    /** Reconnect/pull-to-refresh head query from a second-granularity watermark. */
    fun feedRequestSince(subscriptionId: String, since: Long): String = try {
        NostrEventCodec.encodeRequest(
            subscriptionId,
            space.bitos.core.feed.BitzQuery.headFilters(since),
        )
    } catch (_: NostrEventCodec.Rejected) {
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

    /**
     * Import-field rule wire (ID-004): one classifier for the SwiftUI login
     * field's live feedback. `secretHex`/`pubkeyHex`/`npub` are non-null iff
     * verdict == "READY"; the derived identity powers the live
     * derived-identity preview (KF-6).
     */
    class KeyImportCheckWire(
        val verdict: String,
        val message: String?,
        val secretHex: String?,
        val pubkeyHex: String? = null,
        val npub: String? = null,
    )

    fun keyImportCheck(raw: String): KeyImportCheckWire {
        val check = space.bitos.core.identity.KeyImportForm.check(raw)
        return KeyImportCheckWire(check.verdict.name, check.message, check.secretHex, check.pubkeyHex, check.npub)
    }

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
     * Whether the cursor sits in a trailing @query at all (a bare `@`
     * counts): distinguishes "not composing" from the empty query that
     * [composerMentionQuery] collapses to "" — the empty query must still
     * surface the unfiltered candidate list (legacy parity).
     */
    fun composerIsComposingMention(text: String, cursor: Int): Boolean =
        space.bitos.core.publish.ComposerRules.mentionQueryAt(text, cursor) != null

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

    /**
     * Identity onboarding content (spec §4, docs/ui/app-01): shared copy for
     * the SwiftUI onboarding flow — welcome props, method cards, backup gate
     * and confirmation copy, decoded as a map-of-maps by the iOS mirror.
     */
    fun identityOnboardingContent(): Map<String, Any> {
        val content = space.bitos.core.settings.IdentityOnboardingContent
        return mapOf(
            "schemaVersion" to content.SCHEMA_VERSION,
            "appName" to content.APP_NAME,
            "tagline" to content.TAGLINE,
            "guestToast" to content.GUEST_TOAST,
            "privacyFootnote" to content.PRIVACY_FOOTNOTE,
            "valueProps" to content.VALUE_PROPS.map { prop ->
                mapOf("id" to prop.id, "icon" to prop.iconToken, "title" to prop.title, "body" to prop.body)
            },
            "methodTitle" to content.METHOD_TITLE,
            "methodBody" to content.METHOD_BODY,
            "methodInfo" to content.METHOD_INFO,
            "nip46ComingSoon" to content.NIP46_COMING_SOON,
            "nip55ComingSoon" to content.NIP55_COMING_SOON,
            "methods" to content.METHODS.map { method ->
                mapOf(
                    "id" to method.id,
                    "icon" to method.iconToken,
                    "title" to method.title,
                    "subtitle" to method.subtitle,
                    "recommended" to method.recommended,
                    "available" to method.available,
                    "androidOnly" to method.androidOnly,
                )
            },
            "importFieldLabel" to content.IMPORT_FIELD_LABEL,
            "importReviewNote" to content.IMPORT_REVIEW_NOTE,
            "derivedIdentityLabel" to content.DERIVED_IDENTITY_LABEL,
            "addAccountTitle" to content.ADD_ACCOUNT_TITLE,
            "addAccountSubtitle" to content.ADD_ACCOUNT_SUBTITLE,
            "addAccountReviewLabel" to content.ADD_ACCOUNT_REVIEW_LABEL,
            "addAccountCreateLabel" to content.ADD_ACCOUNT_CREATE_LABEL,
            "addAccountCancelLabel" to content.ADD_ACCOUNT_CANCEL_LABEL,
            "nsecHelpTitle" to content.NSEC_HELP_TITLE,
            "nsecHelpItems" to content.NSEC_HELP_ITEMS,
            "backupWarning" to content.BACKUP_WARNING,
            "backupFieldLabel" to content.BACKUP_FIELD_LABEL,
            "backupRevealPrompt" to content.BACKUP_REVEAL_PROMPT,
            "backupAdvice" to content.BACKUP_ADVICE,
            "backupNextStepsTitle" to content.BACKUP_NEXT_STEPS_TITLE,
            "backupNextSteps" to content.BACKUP_NEXT_STEPS,
            "backupAckLabel" to content.BACKUP_ACK_LABEL,
            "backupConfirmLabel" to content.BACKUP_CONFIRM_LABEL,
            "verifyTitle" to content.VERIFY_TITLE,
            "verifyBody" to content.VERIFY_BODY,
            "verifyConfirmLabel" to content.VERIFY_CONFIRM_LABEL,
            "successTitle" to content.SUCCESS_TITLE,
            "successBody" to content.SUCCESS_BODY,
            "successProfileNote" to content.SUCCESS_PROFILE_NOTE,
            "successInfo" to content.SUCCESS_INFO,
            "successDoneLabel" to content.SUCCESS_DONE_LABEL,
            "copyNpubLabel" to content.COPY_NPUB_LABEL,
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

    // ── APP-009 thread open: state-plate copy (mockup app-10, shared) ──

    fun threadOpenLoadingTitle(): String = space.bitos.core.feed.ThreadOpenCopy.LOADING_TITLE
    fun threadOpenInvalidTitle(): String = space.bitos.core.feed.ThreadOpenCopy.INVALID_TITLE
    fun threadOpenInvalidBody(): String = space.bitos.core.feed.ThreadOpenCopy.INVALID_BODY
    fun threadOpenNotFoundTitle(): String = space.bitos.core.feed.ThreadOpenCopy.NOT_FOUND_TITLE
    fun threadOpenNotFoundBody(): String = space.bitos.core.feed.ThreadOpenCopy.NOT_FOUND_BODY
    fun threadOpenRetryLabel(): String = space.bitos.core.feed.ThreadOpenCopy.RETRY
    fun threadOpenRetryWithHintsLabel(): String = space.bitos.core.feed.ThreadOpenCopy.RETRY_WITH_HINTS
    fun threadOpenAddRelayLabel(): String = space.bitos.core.feed.ThreadOpenCopy.ADD_RELAY

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

    // ── APP-011 DM presentation rules (shared so platforms agree) ────

    /** Unread count for one conversation (received messages past the cursor). */
    fun dmUnreadCount(
        peerMessages: List<Map<String, Any>>,
        myPubkey: String,
        lastReadAt: Long,
    ): Int {
        val conversation = dmConversation(peerMessages) ?: return 0
        return space.bitos.core.model.DmPresentation.unreadCount(conversation, myPubkey, lastReadAt)
    }

    /** Generic NIP-17 preview line (never plaintext outside the chat). */
    fun dmPreviewLine(
        peerMessages: List<Map<String, Any>>,
        myPubkey: String,
        lastReadAt: Long,
    ): String {
        val conversation = dmConversation(peerMessages) ?: return "No messages"
        return space.bitos.core.model.DmPresentation.previewLine(conversation, myPubkey, lastReadAt)
    }

    /** Message-request acceptance verdict for one peer. */
    fun dmIsAccepted(
        peerPubkey: String,
        everSentTo: List<String>,
        explicitlyAccepted: List<String>,
        explicitlyDeclined: List<String>,
    ): Boolean = space.bitos.core.model.DmPresentation.isAccepted(
        peerPubkey = peerPubkey,
        everSentTo = everSentTo.toSet(),
        explicitlyAccepted = explicitlyAccepted.toSet(),
        explicitlyDeclined = explicitlyDeclined.toSet(),
    )

    /** Next read cursor when a conversation opens (never rewinds). */
    fun dmNextCursor(
        peerMessages: List<Map<String, Any>>,
        currentCursor: Long,
    ): Long {
        val conversation = dmConversation(peerMessages) ?: return currentCursor
        return space.bitos.core.model.DmPresentation.nextCursor(conversation, currentCursor)
    }

    /** {id, authorPubkey, peerPubkey, content, createdAt} maps → conversation. */
    private fun dmConversation(peerMessages: List<Map<String, Any>>): space.bitos.core.model.DmConversation? {
        if (peerMessages.isEmpty()) return null
        val messages = peerMessages.mapNotNull { map ->
            val id = map["id"] as? String ?: return@mapNotNull null
            val author = map["authorPubkey"] as? String ?: return@mapNotNull null
            val peer = map["peerPubkey"] as? String ?: return@mapNotNull null
            val content = map["content"] as? String ?: return@mapNotNull null
            val createdAt = (map["createdAt"] as? Number)?.toLong() ?: return@mapNotNull null
            space.bitos.core.model.DmMessage(id, author, peer, content, createdAt)
        }
        if (messages.isEmpty()) return null
        return space.bitos.core.model.DmConversation(
            peerPubkey = messages.first().peerPubkey,
            messages = messages.sortedBy { it.createdAt },
        )
    }

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

    /**
     * Per-author zap summary (profile "Sats zapped" stat + Zaps tab),
     * shared rule. Received arrays are the notification-stream shape the
     * ledger merge already consumes; sent records only count when their
     * recipient is [pubkey].
     */
    fun authorZapsSummary(
        pubkey: String,
        receivedSatsJson: String,
        receivedFromJson: String,
        sentRecordsJson: String,
    ): String? {
        val receivedSats = try {
            Json.parseToJsonElement(receivedSatsJson).jsonArray.mapNotNull {
                (it.jsonPrimitive).content.toLongOrNull()
            }
        } catch (_: Exception) {
            return null
        }
        val receivedFrom = try {
            Json.parseToJsonElement(receivedFromJson).jsonArray.map { (it.jsonPrimitive).content }
        } catch (_: Exception) {
            return null
        }
        val sent = try {
            sentZapRecordsFromJson(sentRecordsJson)
        } catch (_: Exception) {
            return null
        }
        val summary = space.bitos.core.model.AuthorZaps.summary(
            pubkey = pubkey,
            receivedSats = receivedSats,
            receivedFrom = receivedFrom,
            sentRecords = sent,
        )
        return buildJsonObject {
            put("received", summary.receivedSats)
            put("receivedCount", summary.receivedCount)
            put("sent", summary.sentSats)
            put("sentCount", summary.sentCount)
            put("total", summary.totalSats)
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

    // ── APP-019 studio seams (plan MST-005): the Swift editor works on the
    // project wire without leaking Kotlin types. Corrupt wires → "".

    /** Decode + clamp + re-encode a meme project wire; "" when corrupt. */
    fun memeProjectNormalize(projectJson: String): String =
        space.bitos.core.studio.MemeProjectContract.decode(projectJson)
            ?.let(space.bitos.core.studio.MemeProjectContract::encode) ?: ""

    // ── M5 timeline seams: the shared clip list rules for both platforms'
    // multi-clip editors (clips JSON = the wire's `clips` rows). ─────────

    /**
     * Replaces a VIDEO project's timeline clips from a clips JSON array
     * (`[{"id":…,"start":ms,"end":ms,"vol":f?,"look":id?}]`), bounded and
     * clamped by the contract; the first clip's window mirrors into the
     * legacy trim fields. Non-video project or corrupt input → "".
     */
    fun memeTimelineSyncClips(projectJson: String, clipsJson: String): String {
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return ""
        if (project.mode != space.bitos.core.studio.MemeMode.VIDEO) return ""
        val clips = space.bitos.core.studio.MemeProjectContract.decodeClipsJson(clipsJson) ?: return ""
        val first = clips.firstOrNull()
        return space.bitos.core.studio.MemeProjectContract.encode(
            project.copy(
                clips = clips,
                trimStartMs = first?.startMs ?: 0L,
                trimEndMs = first?.endMs ?: 0L,
            ),
        )
    }

    /**
     * Timeline output duration in ms — Σ(window ÷ clamped project speed)
     * over the clip list (empty list → 0). Corrupt project → −1.
     */
    fun memeTimelineDurationMs(projectJson: String): Long {
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return -1L
        if (project.mode != space.bitos.core.studio.MemeMode.VIDEO) return 0L
        val rate = space.bitos.core.studio.MemeProjectContract.clampSpeed(project.speed)
        return project.clips.sumOf { clip ->
            (((clip.endMs - clip.startMs).coerceAtLeast(0L)) / rate).toLong()
        }
    }

    /** Apply one MemeCommand wire to the project wire; an undecodable
     * command is a no-op (the editor never hard-fails on a bad tap).
     * "" only when the project wire itself is corrupt. */
    fun memeApplyCommand(projectJson: String, commandJson: String): String {
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return ""
        val command = space.bitos.core.studio.MemeCommandCodec.decode(commandJson)
            ?: return space.bitos.core.studio.MemeProjectContract.encode(project)
        return space.bitos.core.studio.MemeProjectContract.encode(
            space.bitos.core.studio.MemeRules.apply(project, command),
        )
    }

    /** Top-most overlay id at the normalized point ("" = no hit). */
    fun memeHitTest(projectJson: String, x: Float, y: Float): String =
        space.bitos.core.studio.MemeProjectContract.decode(projectJson)
            ?.let { space.bitos.core.studio.MemeRules.hitTest(it, x, y) }?.id ?: ""

    /** Caption palette (16 ARGB entries) as uppercase zero-padded 6-hex RGB
     * rows, index-ordered — the Swift text sheet paints swatches from these. */
    fun memePalette(): List<String> =
        space.bitos.core.studio.MemeRules.PALETTE.map { color ->
            (color.toInt() and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
        }

    /**
     * Deterministic default overlay for the project (staggered placement,
     * unique id — shared `MemeRules.defaultOverlay`) as an overlay JSON
     * object ready to embed in an `{"op":"add"}` command; "" when the
     * project wire is corrupt or the kind is unknown.
     */
    fun memeDefaultOverlay(projectJson: String, kind: String, text: String): String {
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return ""
        val overlayKind = space.bitos.core.studio.MemeOverlayKind.entries
            .firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: return ""
        val overlay = space.bitos.core.studio.MemeRules.defaultOverlay(project, overlayKind, text)
        return space.bitos.core.studio.MemeProjectContract.overlayJson(overlay).toString()
    }

    /**
     * Estimated overlay bounds on the normalized canvas (shared
     * `MemeRules.estimateBounds`) as `"width|height"`, feeding the Swift
     * selection chrome and delete handle; "" when project/id is unknown.
     */
    fun memeBounds(projectJson: String, overlayId: String): String {
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return ""
        val overlay = project.overlays.firstOrNull { it.id == overlayId } ?: return ""
        val (width, height) = space.bitos.core.studio.MemeRules.estimateBounds(overlay)
        return "$width|$height"
    }

    /**
     * Composite paint state of one overlay at media time (plan MST-044
     * close-out): the shared fx math with the half-open visibility window
     * folded into alpha (0 outside). `"scale|rot|dx|dy|alpha"`; a negative
     * [atMs] = poster (identity, always visible); "" when project/id is
     * unknown. The Swift stage preview and the export keyframe sampling
     * BOTH run through here — no display-side mirror of `MemeFxRules`.
     */
    fun memeFxTransformAt(projectJson: String, overlayId: String, atMs: Long): String {
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return ""
        val overlay = project.overlays.firstOrNull { it.id == overlayId } ?: return ""
        if (atMs < 0) return "1.0|0.0|0.0|0.0|1.0"
        val t = space.bitos.core.studio.MemeFxRules.transformAt(overlay, atMs)
        val alpha = if (space.bitos.core.studio.MemeFxRules.visibleAt(overlay, atMs)) t.alpha else 0f
        return "${t.scale}|${t.rotateRad}|${t.dx}|${t.dy}|$alpha"
    }

    /**
     * The full SFX cue mix over an export window as a base64 WAV (shared
     * `SfxSynth.renderCueTrack` — deterministic, 44.1 kHz stereo, master
     * gain 0.5); "" when the project has no audible cues in the window.
     * [durationMs] is the OUTPUT duration; a rate ≠ 1 maps the cue times
     * into that timeline first (`SfxSynth.cuesInOutputTimeline`). The iOS
     * exporter burns this as its second composition audio track (Android
     * mixes the same PCM through Media3 sequences).
     */
    fun memeSfxTrackWavBase64(projectJson: String, durationMs: Long, rate: Float = 1f): String {
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return ""
        val outputCues = space.bitos.core.studio.SfxSynth.cuesInOutputTimeline(project.sfxCues, rate)
        if (!space.bitos.core.studio.SfxSynth.hasAudibleCues(outputCues, durationMs)) return ""
        val pcm = space.bitos.core.studio.SfxSynth.pcm16Le(
            space.bitos.core.studio.SfxSynth.renderCueTrack(outputCues, durationMs),
        )
        return kotlin.io.encoding.Base64.Default.encode(space.bitos.core.studio.SfxSynth.wav(pcm))
    }

    // ── APP-019 meme wire document (plan MST-019): the `com.bitos.bitz.meme`
    // v1 interop wire. Foreign schema ids/versions → ""; parse never throws.

    /** Tolerant parse + canonical re-encode (passthrough preserved). */
    fun memeWireNormalize(wireJson: String, nowMs: Long): String =
        space.bitos.core.studio.MemeWireCodec.normalize(wireJson, nowMs) ?: ""

    /** Wire document → local project wire (styles, stickers, windows, fx). */
    fun memeWireToLocal(wireJson: String): String =
        space.bitos.core.studio.MemeWireCodec.decode(wireJson, nowMs = 0L)
            ?.let { space.bitos.core.studio.MemeProjectContract.encode(space.bitos.core.studio.MemeWireConvert.wireToLocal(it)) }
            ?: ""

    /** Local project wire → wire document JSON (blank overlays drop). */
    fun localToMemeWire(projectJson: String, nowMs: Long): String {
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return ""
        return space.bitos.core.studio.MemeWireCodec.encode(
            space.bitos.core.studio.MemeWireConvert.localToWire(project, nowMs),
        )
    }


    /** Composes the unsigned video meme (kind 22 portrait / 21 landscape,
     * MST-034) and returns its id. */
    fun composeMemeVideoEventId(
        authorPubkey: String,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        portrait: Boolean,
        url: String,
        sha256Hex: String,
        mimeType: String,
        sizeBytes: Long,
        width: Long,
        height: Long,
        durationMs: Long,
        nowSeconds: Long,
        thumbUrl: String? = null,
    ): String? {
        val media = try {
            space.bitos.core.model.UploadedMedia(
                url, sha256Hex, mimeType, sizeBytes, width?.toInt(), height?.toInt(), durationMs, thumbUrl,
            )
        } catch (_: IllegalArgumentException) {
            return null
        }
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeMemeVideoNote(authorPubkey, caption, altText, contentWarningReason, portrait, media)
            ?.idHex
    }

    /** The ["EVENT", …] frame for the signed video meme, or null. */
    fun memeVideoPublishMessage(
        authorPubkey: String,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        portrait: Boolean,
        url: String,
        sha256Hex: String,
        mimeType: String,
        sizeBytes: Long,
        width: Long,
        height: Long,
        durationMs: Long,
        createdAtSeconds: Long,
        signatureHex: String,
        thumbUrl: String? = null,
    ): String? {
        val media = try {
            space.bitos.core.model.UploadedMedia(
                url, sha256Hex, mimeType, sizeBytes, width?.toInt(), height?.toInt(), durationMs, thumbUrl,
            )
        } catch (_: IllegalArgumentException) {
            return null
        }
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeMemeVideoNote(
            authorPubkey, caption, altText, contentWarningReason, portrait, media,
        ) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /**
     * MST-042 remix tags for a meme publish: `["remix", id, relays…]` +
     * `["meme", <compact payload ≤700 via the ladder>]` + `["p", author]`
     * (+ optional `license`/`attribution`), as TagsCodec JSON from the
     * project wire; "" when the wire is corrupt. Media-only degradations
     * still return remix/p (the layout payload alone is dropped).
     */
    fun memeRemixTagsFor(
        projectJson: String,
        sourceEventId: String,
        sourcePubkey: String,
        relaysJson: String,
        license: String,
        attribution: String,
    ): String {
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return ""
        val document = space.bitos.core.studio.MemeWireConvert.localToWire(
            project, nowMs = 0L,
        )
        val degraded = space.bitos.core.studio.MemeRemix.encodeDegraded(document)
        val relays = try {
            kotlinx.serialization.json.Json.parseToJsonElement(relaysJson).jsonArray
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                .take(space.bitos.core.feed.RemixRules.MAX_RELAY_HINTS)
        } catch (_: Exception) {
            emptyList()
        }
        val tags = space.bitos.core.feed.RemixRules.tagsFor(sourceEventId, sourcePubkey, relays).toMutableList()
        degraded.payload?.let { payload -> tags += listOf("meme", payload) }
        if (license.isNotBlank() && license in space.bitos.core.feed.RemixRules.LICENSES) {
            tags += listOf("license", license)
        }
        if (attribution.isNotBlank()) {
            tags += space.bitos.core.feed.RemixRules.attributionTag(attribution)
        }
        return kotlinx.serialization.json.buildJsonArray {
            tags.forEach { tag -> add(kotlinx.serialization.json.buildJsonArray { tag.forEach(::add) }) }
        }.toString()
    }

    // ── M2 GIF planning seams: iOS rasters/encodes natively (plan §4.2 —
    // CGImageSource decode + CGImageDestination encode) but the TIMING and
    // ladder rules stay single-sourced in the shared planner.

    /**
     * Export-plan steps for a frame list: `{"steps":[{"atSec","delayMs"}],
     * "durationSec","capped"}` from per-frame holds (ms). Null pin = auto.
     */
    fun memeGifPlan(delaysMsJson: String, pinnedSec: Double): String {
        val delays = try {
            Json.parseToJsonElement(delaysMsJson).jsonArray
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
        } catch (_: Exception) {
            emptyList()
        }
        val timings = run {
            var at = 0.0
            delays.map { delay ->
                val clamped = delay.coerceIn(20, 1000)
                val row = space.bitos.core.studio.GifExportPlan.FrameTiming(
                    atSec = at, durationSec = clamped / 1000.0,
                )
                at += clamped / 1000.0
                row
            }
        }
        val pin = if (pinnedSec.isFinite() && pinnedSec > 0) pinnedSec else null
        val plan = space.bitos.core.studio.GifExportPlan.plan(timings, pin)
        return buildJsonObject {
            put("durationSec", plan.durationSec)
            put("capped", plan.capped)
            put("steps", buildJsonArray {
                plan.steps.forEach { step ->
                    add(
                        buildJsonObject {
                            put("atSec", step.atSec)
                            put("delayMs", step.delayMs)
                        },
                    )
                }
            })
        }.toString()
    }

    /** Ladder canvas for step `step` as `"width|height"`; "" past the cap. */
    fun memeGifLadderCanvas(width: Int, height: Int, step: Int): String {
        val canvas = space.bitos.core.studio.GifExportPlan.SizeLadder.canvasFor(width, height, step)
        return canvas?.let { (w, h) -> "$w|$h" } ?: ""
    }

    /**
     * APP-019 export envelope (plan MST-016): the evened output canvas for
     * the source dims plus the target-px paint rows for the Swift
     * rasterizer as `{"width":…,"height":…,"items":[…]}`; "" when the
     * project wire is corrupt.
     */
    fun memeExportPlan(projectJson: String, sourceWidth: Int, sourceHeight: Int): String {
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return ""
        return space.bitos.core.studio.MemeExportRules.exportEnvelope(project, sourceWidth, sourceHeight)
    }

    /** Sticker packs (web `stickers.ts` port) as
     * `[{"id":…,"label":…,"stickers":[…]}]` JSON for the sheet. */
    fun memeStickerPacks(): String = buildJsonArray {
        space.bitos.core.studio.StickerCatalog.PACKS.forEach { pack ->
            add(buildJsonObject {
                put("id", pack.id)
                put("label", pack.label)
                put("stickers", buildJsonArray { pack.stickers.forEach(::add) })
            })
        }
    }.toString()

    // ── APP-019 mass production (plan M4 wave 5 / MST-048): the Swift
    // Create hub drives the same batch document through four JSON seams.
    // Corrupt wires → "" (the hub never hard-fails on a bad tap).

    /** Fresh batch document (canonical starter project + recipe). */
    fun massBatchNew(name: String, nowMs: Long): String {
        val document = space.bitos.core.studio.MassBatchDocument(
            batchId = "mb-" + nowMs.toString(36),
            name = name.take(80).ifBlank { "Untitled batch" },
            recipe = space.bitos.core.studio.MassBatch.defaultRecipe(
                space.bitos.core.studio.MassBatch.starterProject(),
            ),
            rows = emptyList(),
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
        return space.bitos.core.studio.MassBatchCodec.encode(document)
    }

    /**
     * One mutation on the batch wire. Ops (JSON):
     * `addRow` · `removeRow{row}` · `setValue{row,slot,value}` ·
     * `setAsset{row,slot,file?}` · `editRecipe{naming?,caption?}` (forks
     * once rows exist) · `approve{row,approve,nowMs}` ·
     * `approveAll{nowMs,readable[]}` · `withRender{row,posterName,posterHash}`
     * · `withPublish{row,state,failure?,event?}`. Unknown ops → the wire
     * unchanged; corrupt wire → "".
     */
    fun massBatchOp(docJson: String, opJson: String): String {
        val document = space.bitos.core.studio.MassBatchCodec.decode(docJson) ?: return ""
        val op = try {
            Json.parseToJsonElement(opJson).jsonObject
        } catch (_: Exception) {
            return space.bitos.core.studio.MassBatchCodec.encode(document)
        }
        val kind = (op["op"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return docJson
        val rowId = (op["row"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val next: space.bitos.core.studio.MassBatchDocument = when (kind) {
            "addRow" -> {
                val max = document.rows.maxOfOrNull { row ->
                    row.id.removePrefix("r").takeWhile(Char::isDigit).toIntOrNull() ?: 0
                } ?: 0
                document.copy(rows = document.rows + space.bitos.core.studio.MassRow("r${max + 1}"))
            }
            "removeRow" -> document.copy(rows = document.rows.filterNot { it.id == rowId })
            "setValue" -> {
                val slotId = (op["slot"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return docJson
                val value = (op["value"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                document.copy(
                    rows = document.rows.map { row ->
                        if (row.id == rowId) row.copy(values = row.values + (slotId to value.take(300))) else row
                    },
                )
            }
            "setAsset" -> {
                val slotId = (op["slot"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return docJson
                val file = (op["file"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.takeIf { it.isNotBlank() && !it.startsWith("/") && !it.contains("..") }
                document.copy(
                    rows = document.rows.map { row ->
                        if (row.id != rowId) {
                            row
                        } else if (file != null) {
                            row.copy(assetFiles = row.assetFiles + (slotId to file))
                        } else {
                            row.copy(assetFiles = row.assetFiles - slotId)
                        }
                    },
                )
            }
            "editRecipe" -> {
                var recipe = document.recipe
                (op["naming"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let {
                    recipe = recipe.copy(naming = it.take(64).ifBlank { "memes_{i}" })
                }
                (op["caption"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let {
                    recipe = recipe.copy(caption = it.take(1000))
                }
                if (document.rows.isEmpty()) document.copy(recipe = recipe)
                else space.bitos.core.studio.MassBatchRules.forkRecipe(document, recipe)
            }
            "approve" -> {
                val approve = (op["approve"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                val nowMs = (op["nowMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                    ?: 0L
                val index = document.rows.indexOfFirst { it.id == rowId }
                if (index < 0 || rowId == null) {
                    document
                } else {
                    val row = document.rows[index]
                    val variant = space.bitos.core.studio.MassBatchRules.resolveVariant(
                        document.recipe, row, index + 1,
                    )
                    val hash = if (approve) {
                        space.bitos.core.studio.MassBatchRules.contentHash(document.recipe.version, row, variant.project)
                    } else {
                        null
                    }
                    document.copy(
                        states = space.bitos.core.studio.MassBatchRules.withApproval(
                            document.states, rowId, hash,
                            document.states[rowId]?.posterHash, nowMs,
                        ),
                    )
                }
            }
            "approveAll" -> {
                val nowMs = (op["nowMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                    ?: 0L
                val readable = readableSet(op)
                val validations = space.bitos.core.studio.MassBatchRules.validateAll(
                    document.recipe, document.rows, { readable.contains(it) },
                )
                var states = document.states
                document.rows.withIndex().forEach { (index, row) ->
                    if (validations[row.id]?.queueable == true) {
                        val variant = space.bitos.core.studio.MassBatchRules.resolveVariant(
                            document.recipe, row, index + 1,
                        )
                        val hash = space.bitos.core.studio.MassBatchRules.contentHash(
                            document.recipe.version, row, variant.project,
                        )
                        val state = states[row.id]
                        if (state == null ||
                            !space.bitos.core.studio.MassBatchRules.approvalValid(state, hash, state.posterHash)
                        ) {
                            states = space.bitos.core.studio.MassBatchRules.withApproval(
                                states, row.id, hash, states[row.id]?.posterHash, nowMs,
                            )
                        }
                    }
                }
                document.copy(states = states)
            }
            "withRender" -> {
                val posterName = (op["posterName"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return docJson
                val posterHash = (op["posterHash"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                document.copy(
                    states = space.bitos.core.studio.MassBatchRules.withRender(
                        document.states, rowId ?: "", posterName, posterHash,
                    ),
                )
            }
            "withPublish" -> {
                val stateName = (op["state"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return docJson
                val state = space.bitos.core.studio.MassPublishState.entries
                    .firstOrNull { it.name.equals(stateName, ignoreCase = true) } ?: return docJson
                val failure = (op["failure"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                document.copy(
                    states = space.bitos.core.studio.MassBatchRules.withPublishState(
                        document.states, rowId ?: "", state, failure = failure,
                    ),
                )
            }
            else -> return docJson
        }
        return space.bitos.core.studio.MassBatchCodec.encode(next)
    }

    /**
     * Everything the UI renders, in one call: per-row severity/notes/
     * approval/publish/poster plus resolved names & captions, counts and
     * the approved queue size. `readableJson` = `["file",…]` of stored
     * row-asset files that actually resolve on disk.
     */
    fun massBatchPlan(docJson: String, readableJson: String): String {
        val document = space.bitos.core.studio.MassBatchCodec.decode(docJson) ?: return ""
        val readable = try {
            Json.parseToJsonElement(readableJson).jsonArray
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val validations = space.bitos.core.studio.MassBatchRules.validateAll(
            document.recipe, document.rows, { readable.contains(it) },
        )
        var ok = 0; var warn = 0; var blocked = 0
        validations.values.forEach {
            when (it.severity) {
                space.bitos.core.studio.MassBatchRules.Severity.OK -> ok++
                space.bitos.core.studio.MassBatchRules.Severity.WARN -> warn++
                space.bitos.core.studio.MassBatchRules.Severity.BLOCKER -> blocked++
            }
        }
        return buildJsonObject {
            put("name", document.name)
            put("recipeVersion", document.recipe.version)
            put("frozen", document.rows.isNotEmpty())
            put("naming", document.recipe.naming)
            put("caption", document.recipe.caption)
            put("cw", document.recipe.contentWarningReason ?: "")
            put("slots", buildJsonArray {
                document.recipe.slots.forEach { slot ->
                    add(buildJsonObject {
                        put("id", slot.id)
                        put("name", slot.name)
                        put("type", slot.type.name.lowercase())
                        put("required", slot.required)
                        if (slot.enumValues.isNotEmpty()) {
                            put("enum", buildJsonArray { slot.enumValues.forEach(::add) })
                        }
                    })
                }
            })
            put("rows", buildJsonArray {
                document.rows.withIndex().forEach { (index, row) ->
                    val variant = space.bitos.core.studio.MassBatchRules.resolveVariant(
                        document.recipe, row, index + 1,
                    )
                    val hash = space.bitos.core.studio.MassBatchRules.contentHash(
                        document.recipe.version, row, variant.project,
                    )
                    val state = document.states[row.id]
                    val validation = validations[row.id]
                    add(buildJsonObject {
                        put("id", row.id)
                        put("index", index + 1)
                        put("values", buildJsonObject {
                            row.values.forEach { (key, value) -> put(key, value) }
                        })
                        put("assets", buildJsonObject {
                            row.assetFiles.forEach { (key, file) -> put(key, file) }
                        })
                        put("severity", (validation?.severity ?: space.bitos.core.studio.MassBatchRules.Severity.OK).name.lowercase())
                        put("notes", buildJsonArray {
                            (validation?.notes ?: emptyList()).forEach { note -> add(note.message) }
                        })
                        put("approved", state?.let {
                            space.bitos.core.studio.MassBatchRules.approvalValid(it, hash, it.posterHash)
                        } ?: false)
                        put("publish", (state?.publish ?: space.bitos.core.studio.MassPublishState.WAITING).name.lowercase())
                        state?.publishedEventId?.let { put("event", it) }
                        state?.posterName?.let { put("poster", it) }
                        put("name", variant.name)
                        put("caption", variant.caption)
                        // Resolved variant for rendering/publishing (iOS).
                        put("projectJson", space.bitos.core.studio.MemeProjectContract.encode(variant.project))
                        put("assetMap", buildJsonObject {
                            variant.assetOverrides.forEach { (key, file) -> put(key, file) }
                        })
                    })
                }
            })
            put("counts", buildJsonObject {
                put("ok", ok); put("warn", warn); put("blocked", blocked)
            })
            put("queueCount", space.bitos.core.studio.MassBatchRules.queue(document, validations).size)
        }.toString()
    }

    /** CSV import: `{"doc":…,"notes":[…]}`; junk CSV → notes-only result. */
    fun massBatchImportCsv(docJson: String, csv: String): String {
        val document = space.bitos.core.studio.MassBatchCodec.decode(docJson) ?: return ""
        val imported = space.bitos.core.studio.MassBatchRules.importCsv(csv, document.recipe)
        var counter = document.rows.maxOfOrNull { row ->
            row.id.removePrefix("r").takeWhile(Char::isDigit).toIntOrNull() ?: 0
        } ?: 0
        val rows = imported.rows.map { row -> counter += 1; row.copy(id = "r$counter") }
        val next = if (rows.isEmpty()) {
            document
        } else if (document.rows.isEmpty()) {
            space.bitos.core.studio.MassBatchRules.forkRecipe(document, document.recipe).copy(rows = rows)
        } else {
            document.copy(rows = document.rows + rows)
        }
        return buildJsonObject {
            put("doc", space.bitos.core.studio.MassBatchCodec.encode(next))
            put("notes", buildJsonArray { imported.notes.forEach(::add) })
        }.toString()
    }

    private fun readableSet(op: kotlinx.serialization.json.JsonObject): Set<String> = try {
        (op["readable"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.toSet() ?: emptySet()
    } catch (_: Exception) {
        emptySet()
    }

    // ── APP-019 looks (plan MST-043, web `look.ts` port): 8 grades whose
    // CSS chains are composed into one 4×5 color matrix both rasterizers
    // apply natively (preview == export; the grade touches media only).

    // ── APP-019 built-in templates (plan MST-040 / wave 1 rail).

    /** Pack catalog `[{"id","label","emoji"}]` (id order). */
    fun memeTemplates(): String = buildJsonArray {
        space.bitos.core.studio.MemeTemplates.PACK.forEach { template ->
            add(buildJsonObject {
                put("id", template.id)
                put("label", template.label)
                put("emoji", template.emoji)
            })
        }
    }.toString()

    /** Kind-30078 shared-template summary row; "" when the shape is foreign. */
    fun memeSharedTemplateSummary(tagsJson: String, content: String): String {
        val template = decodeSharedTemplate(tagsJson, content) ?: return ""
        return buildJsonObject {
            put("id", template.id)
            put("label", template.label)
            put("emoji", template.emoji)
            put("priceSats", template.priceSats)
            put("category", template.category)
        }.toString()
    }

    /** Apply a shared template onto a project wire (fresh-id clone). */
    fun memeApplySharedTemplate(projectJson: String, tagsJson: String, content: String): String {
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return ""
        val template = decodeSharedTemplate(tagsJson, content) ?: return ""
        return space.bitos.core.studio.MemeProjectContract.encode(
            space.bitos.core.studio.MemeTemplateContract.apply(project, template),
        )
    }

    private fun decodeSharedTemplate(tagsJson: String, content: String): space.bitos.core.studio.MemeTemplateContract.SharedTemplate? {
        val tags = space.bitos.core.store.TagsCodec.decode(tagsJson) ?: return null
        return space.bitos.core.studio.MemeTemplateContract.parse(tags, content)
    }

    /** Apply a template onto a project wire (fresh-id clone); "" corrupt. */
    fun memeApplyTemplate(projectJson: String, templateId: String): String {
        val project = space.bitos.core.studio.MemeProjectContract.decode(projectJson) ?: return ""
        return space.bitos.core.studio.MemeProjectContract.encode(
            space.bitos.core.studio.MemeTemplates.apply(project, templateId),
        )
    }

    // ── APP-019 synth SFX (plan MST-041 / §3.6): 31 recipes as data, a
    // pure-Kotlin renderer + WAV writer; previews play per platform.

    /** Catalog `[{"id","label","sfx":[…]}]` (5 buckets, 31 sounds). */
    fun memeSfxCatalog(): String = buildJsonArray {
        space.bitos.core.studio.SfxSynth.BUCKETS.forEach { bucket ->
            add(buildJsonObject {
                put("id", bucket.id)
                put("label", bucket.label)
                put("sfx", buildJsonArray { bucket.sfx.forEach(::add) })
            })
        }
    }.toString()

    /** Rendered preview as a base64 WAV (mono 16-bit 44.1 kHz); "" junk id. */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun memeSfxWavBase64(sfxId: String, gain: Double): String {
        val recipe = space.bitos.core.studio.SfxSynth.recipeOf(sfxId) ?: return ""
        val pcm = space.bitos.core.studio.SfxSynth.renderPcm(recipe, gain)
        val wav = space.bitos.core.studio.SfxSynth.wav(space.bitos.core.studio.SfxSynth.pcm16Le(pcm))
        return kotlin.io.encoding.Base64.Default.encode(wav)
    }

    // ── APP-019 video cut policy (MST-030/MST-034 revision): long clips
    // are CUT with a message, never rejected — shared rules, both apps.

    /** Pick-time cap: `{"startMs","endMs","cut","message","attempts"}`. */
    fun memeVideoCutFor(durationMs: Long): String {
        val cut = space.bitos.core.studio.MemeVideoCutRules.cutForDuration(durationMs)
        return buildJsonObject {
            put("startMs", cut.startMs)
            put("endMs", cut.endMs)
            put("cut", cut.cut)
            put("message", cut.message ?: "")
            put("attempts", space.bitos.core.studio.MemeVideoCutRules.MAX_CUT_ATTEMPTS)
        }.toString()
    }

    /** Export ladder step: `{"endMs"}` (0 = cannot shrink further). */
    fun memeVideoCutForSize(currentMs: Long, sizeBytes: Long, maxBytes: Long): String {
        val cut = space.bitos.core.studio.MemeVideoCutRules.nextCutForSize(currentMs, sizeBytes, maxBytes)
        return buildJsonObject {
            put("endMs", cut?.endMs ?: 0L)
            put("message", cut?.message ?: "")
        }.toString()
    }

    /** Look catalog `[{"id","label","css"}]` (id order, `none` first). */
    fun memeLooks(): String = buildJsonArray {
        space.bitos.core.studio.MemeLooks.ALL.forEach { look ->
            add(buildJsonObject {
                put("id", look.id)
                put("label", look.label)
                put("css", look.css)
            })
        }
    }.toString()

    /**
     * The composed 4×5 matrix for a look id as a flat 20-float JSON array
     * (identity for none/unknown) plus `"blur"` px — the Swift rasterizer
     * feeds CIColorMatrix with the same values Android uses.
     */
    fun memeLookMatrix(lookId: String?): String = buildJsonObject {
        val look = space.bitos.core.studio.MemeLooks.lookOf(lookId)
        put("matrix", buildJsonArray {
            space.bitos.core.studio.MemeLooks.matrix(look).forEach { value -> add(value) }
        })
        put("blur", look.blurPx)
    }.toString()

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

    /** Composes the unsigned kind-5 deletion (NIP-09) and returns its id. */
    fun composeDeletionEventId(
        targetEventIds: List<String>,
        authorPubkey: String,
        reason: String,
        nowSeconds: Long,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeDeletion(targetEventIds, authorPubkey, reason)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-5 deletion, or null. */
    fun deletionPublishMessage(
        targetEventIds: List<String>,
        authorPubkey: String,
        reason: String,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeDeletion(targetEventIds, authorPubkey, reason) ?: return null
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

    /** REQ for replies to one event (NIP-01 tagged #e filter). ADR-003
     *  migration window: kind-1111 comments ride the same thread. */
    fun commentsRequest(subscriptionId: String, targetEventId: String): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[${NostrKinds.SHORT_TEXT_NOTE},${NostrKinds.VIDEO_COMMENT},7,6,${space.bitos.core.model.ZapReceipt.RECEIPT_KIND}],"#e":["$targetEventId"],"limit":50}""",
        )

    /** NIP-22 companion REQ: kind-1111 comments root-tag the target with
     *  UPPERCASE `E`, so the plain `#e` filter misses them — ask both
     *  explicitly (relay tag filters are case-sensitive). */
    fun commentsRootRequest(subscriptionId: String, targetEventId: String): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[${NostrKinds.VIDEO_COMMENT}],"#E":["$targetEventId"],"limit":50}""",
        )

    /** NIP-22 comment tags as JSON (reply-bar parity with [replyTagsJson]). */
    fun commentTagsJson(
        targetEventId: String,
        targetPubkey: String,
        targetKind: Long,
        parentEventId: String?,
        parentPubkey: String?,
        content: String,
    ): String? =
        space.bitos.core.publish.NoteComposer
            .commentTags(targetEventId, targetPubkey, targetKind.toInt(), parentEventId, parentPubkey, content)
            ?.let(space.bitos.core.store.TagsCodec::encode)

    /** NIP-22 tags-aware kind-1111 compose id. */
    fun composeCommentWithTagsEventId(content: String, pubkeyHex: String, nowSeconds: Long, tagsJson: String): String? {
        val tags = space.bitos.core.store.TagsCodec.decode(tagsJson) ?: return null
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeCommentWithTags(pubkeyHex, content, tags)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-1111 comment, or null. */
    fun commentWithTagsPublishMessage(
        content: String,
        pubkeyHex: String,
        createdAtSeconds: Long,
        signatureHex: String,
        tagsJson: String,
    ): String? {
        val tags = space.bitos.core.store.TagsCodec.decode(tagsJson) ?: return null
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val note = composer.composeCommentWithTags(pubkeyHex, content, tags) ?: return null
        return composer.publishMessage(note, signatureHex)
    }

    /** Composes the unsigned kind-1018 poll vote and returns its id. */
    fun composePollVoteEventId(
        targetEventId: String,
        optionIndex: Long,
        authorPubkey: String,
        nowSeconds: Long,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composePollVote(targetEventId, optionIndex.toInt(), authorPubkey)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-1018 poll vote, or null. */
    fun pollVotePublishMessage(
        targetEventId: String,
        optionIndex: Long,
        authorPubkey: String,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composePollVote(targetEventId, optionIndex.toInt(), authorPubkey) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** REQ for one poll's kind-1018 votes (bounded relay fan-in). */
    fun pollVotesRequest(subscriptionId: String, targetEventId: String): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[${NostrKinds.POLL_RESPONSE}],"#e":["$targetEventId"],"limit":${space.bitos.core.model.PollVotes.MAX_VOTERS}}""",
        )

    /**
     * APP-009 root resolution: `note1`/`nevent1`/`naddr1` (± `nostr:`
     * prefix) → pointer map. Forms: `id` → {form, id, author, relays};
     * `coord` → {form, kind(int), pubkey, d, author, relays}. Null = invalid.
     * Accepts bare 64-char lowercase hex ids next to the bech32 forms
     * (shared `ThreadOpen.classify`).
     */
    fun eventRefParse(bech32: String): Map<String, Any>? {
        val ref = space.bitos.core.feed.ThreadOpen.classify(bech32) ?: return null
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

    // ── NIP-51 interest set (kind 30015 d=interest — followed hashtags) ──

    /** Composes the unsigned interest set and returns its canonical id. */
    fun composeInterestSetEventId(authorPubkey: String, hashtags: List<String>, nowSeconds: Long): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeInterestSet(authorPubkey, hashtags)?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed interest set, or null. */
    fun interestSetPublishMessage(
        authorPubkey: String,
        hashtags: List<String>,
        createdAtSeconds: Long,
        signatureHex: String,
    ): String? {
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeInterestSet(authorPubkey, hashtags) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** REQ for the account's interest-set head (replaceable, newest wins). */
    fun interestSetRequest(subscriptionId: String, accountPubkey: String): String =
        NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[${space.bitos.core.model.InterestSet.KIND}],"authors":["$accountPubkey"],""" +
                """"#d":["${space.bitos.core.model.InterestSet.D_TAG}"],"limit":1}""",
        )

    /** Followed hashtags from the ACCOUNT's verified kind-30015 frame, or null. */
    fun interestSetHashtags(message: String, relayUrl: String, accountPubkey: String): List<String>? {
        val event = interestSetEvent(message, relayUrl) ?: return null
        if (event.pubkey.value != accountPubkey) return null
        return space.bitos.core.model.InterestSet.followedHashtags(event)
    }

    /** The account's verified kind-30015 frame's created_at, or null. */
    fun interestSetCreatedAt(message: String, relayUrl: String, accountPubkey: String): Long? {
        val event = interestSetEvent(message, relayUrl) ?: return null
        if (event.pubkey.value != accountPubkey) return null
        return event.createdAt
    }

    private fun interestSetEvent(message: String, relayUrl: String): space.bitos.core.model.NostrEvent? {
        val relay = RelayUrl.parse(relayUrl) ?: return null
        val event = try {
            NostrEventCodec.decodeRelayEvent(Sha256EventHasher, message, relay)
        } catch (_: NostrEventCodec.Rejected) {
            return null
        }
        if (!NostrEventCodec.verifySignature(Sha256EventHasher, event)) return null
        if (event.kind != space.bitos.core.model.InterestSet.KIND) return null
        return event
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
        /** Local negative feedback (web interaction-profile parity). */
        dismissedNoteIdsJson: String = "[]",
        mutedAuthorsJson: String = "[]",
        mutedTagsJson: String = "[]",
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
                dismissedNoteIds = parseStringSet(dismissedNoteIdsJson),
                mutedAuthors = parseStringSet(mutedAuthorsJson),
                mutedTags = parseStringSet(mutedTagsJson),
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

    /**
     * The bare signed kind-9734 event JSON `{...}` for the LNURL `nostr`
     * param (LUD-06/NIP-57): plain object, never a relay `[…"EVENT"…]`
     * frame. APP-014 zap fix; null when composition fails.
     */
    fun zapRequestEventJson(
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
        return composer.signedEventJson(unsigned, signatureHex)
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
     * LUD-16 pay-params endpoint `https://<domain>/.well-known/lnurlp/<user>`
     * for a `user@domain` address — domain lowercased, local part preserved
     * and percent-encoded (web `lnurlEndpointOf` parity); null when invalid.
     */
    fun lnurlPayEndpointUrl(lud16: String): String? =
        space.bitos.core.model.LnurlPay.payEndpointUrl(lud16)

    /** Provider error text (`reason`/`errors`) from a failed LNURL body, or null. */
    fun lnurlProviderError(body: String): String? =
        space.bitos.core.model.LnurlPay.providerError(body)

    /** Amount-range failure message (null = payment amount is in range). */
    fun lnurlAmountFailure(minMillisats: Long, maxMillisats: Long, amountMillisats: Long): String? =
        space.bitos.core.model.LnurlPay.amountFailure(minMillisats, maxMillisats, amountMillisats)

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
        lnurlHint: String?,
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

    /** BUD-02: the `PUT {server}/upload` endpoint for a server base URL. */
    fun blossomUploadUrl(serverUrl: String): String =
        space.bitos.core.model.Blossom.uploadUrl(serverUrl)

    /** The BUD-11 `Nostr <base64url(event)>` header value from the signed kind-24242. */
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
        // BUD-11: the Authorization token is the signed event object `{...}`
        // under Base64url — never the `["EVENT", {...}]` relay frame.
        val eventJson = composer.signedEventJson(unsigned, signatureHex) ?: return null
        return space.bitos.core.model.Blossom.authorizationHeaderValue(eventJson)
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
        altText: String = "",
        contentWarningReason: String? = null,
    ): String? {
        val media = try {
            space.bitos.core.model.UploadedMedia(url, sha256Hex, mimeType, sizeBytes, width?.toInt(), height?.toInt(), durationMs)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        return composer.composeMediaNote(authorPubkey, caption, media, altText, contentWarningReason)?.idHex
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
        altText: String = "",
        contentWarningReason: String? = null,
    ): String? {
        val media = try {
            space.bitos.core.model.UploadedMedia(url, sha256Hex, mimeType, sizeBytes, width?.toInt(), height?.toInt(), durationMs)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val unsigned = composer.composeMediaNote(authorPubkey, caption, media, altText, contentWarningReason) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** Composes the unsigned kind-20 picture meme and returns its id (MST-017). */
    fun composeMemePictureEventId(
        authorPubkey: String,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        url: String,
        sha256Hex: String,
        mimeType: String,
        sizeBytes: Long,
        width: Long,
        height: Long,
        nowSeconds: Long,
        extraTagsJson: String = "",
    ): String? {
        val media = try {
            space.bitos.core.model.UploadedMedia(url, sha256Hex, mimeType, sizeBytes, width?.toInt(), height?.toInt())
        } catch (_: IllegalArgumentException) {
            return null
        }
        val composer = space.bitos.core.publish.NoteComposer(clock = { nowSeconds })
        val extra = space.bitos.core.store.TagsCodec.decode(extraTagsJson) ?: emptyList()
        return composer.composeMemePictureNote(
            authorPubkey, caption, altText, contentWarningReason, media, extraTags = extra,
        )
            ?.idHex
    }

    /** The ["EVENT", {...}] frame for the signed kind-20 picture meme, or null. */
    fun memePicturePublishMessage(
        authorPubkey: String,
        caption: String,
        altText: String,
        contentWarningReason: String?,
        url: String,
        sha256Hex: String,
        mimeType: String,
        sizeBytes: Long,
        width: Long,
        height: Long,
        createdAtSeconds: Long,
        signatureHex: String,
        extraTagsJson: String = "",
    ): String? {
        val media = try {
            space.bitos.core.model.UploadedMedia(url, sha256Hex, mimeType, sizeBytes, width?.toInt(), height?.toInt())
        } catch (_: IllegalArgumentException) {
            return null
        }
        val composer = space.bitos.core.publish.NoteComposer(clock = { createdAtSeconds })
        val extra = space.bitos.core.store.TagsCodec.decode(extraTagsJson) ?: emptyList()
        val unsigned = composer.composeMemePictureNote(
            authorPubkey, caption, altText, contentWarningReason, media, extraTags = extra,
        ) ?: return null
        return composer.publishMessage(unsigned, signatureHex)
    }

    /** Page size for notification REQs (web `PAGE_LIMIT` parity). */
    val NOTIFICATION_PAGE_LIMIT: Int get() = 60

    /**
     * REQ for events targeting the account (notification inbox). Zap
     * receipts keep their own filter so relay per-filter limits cannot crowd
     * them out of a busy account's history (web parity); [untilSeconds]
     * pages older history (0 = live head subscription).
     */
    fun notificationsRequest(
        subscriptionId: String,
        accountPubkey: String,
        untilSeconds: Long = 0,
        limit: Int = NOTIFICATION_PAGE_LIMIT,
    ): String {
        val timeBound = if (untilSeconds > 0) ",\"until\":$untilSeconds" else ""
        return NostrEventCodec.encodeRequest(
            subscriptionId,
            listOf(
                """{"kinds":[1,7,6,${space.bitos.core.model.NostrKinds.GENERIC_REPOST},3],"#p":["$accountPubkey"],"limit":$limit$timeBound}""",
                """{"kinds":[${space.bitos.core.model.ZapReceipt.RECEIPT_KIND}],"#p":["$accountPubkey"],"limit":$limit$timeBound}""",
            ),
        )
    }

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

    /** NIP-50 Bitz search REQ: standard NIP-68/NIP-71 media kinds only
     *  (Bitz discovery/query standard — Bitz never discovers via kind-1). */
    fun bitzSearchRequest(subscriptionId: String, query: String, limit: Int): String? =
        searchRequest(subscriptionId, query, space.bitos.core.feed.BitzTimelinePolicy.MEDIA_KINDS, limit)

    /** npub → hex pubkey, when the query is an exact npub (creator search). */
    fun resolveNpub(query: String): String? {
        val trimmed = query.trim()
        return if (trimmed.startsWith("npub1")) space.bitos.core.identity.NostrKeyCodec.parseNpub(trimmed) else null
    }

    /** REQ for one author's profile + notes. First page includes kind-0;
     *  follow-up pages drop kind-0 and page backward from [untilSeconds]
     *  (the caller dedupes by event id). Media and text kinds are SPLIT
     *  into two filters (web `loadReels` parity: Nostr `limit` applies per
     *  relay per filter, so one combined filter would spend the whole
     *  window on text) and the media window is queried deep because
     *  dedicated video kinds are ~100% renderable bitz. Limits coerce
     *  into the 1..500 window so the REQ stays size-bounded. */
    fun authorRequest(
        subscriptionId: String,
        authorPubkey: String,
        mediaLimit: Int = 60,
        textLimit: Int = 150,
        untilSeconds: Long? = null,
    ): String {
        val boundedMedia = mediaLimit.coerceIn(1, 500)
        val boundedText = textLimit.coerceIn(1, 500)
        val profileFilter = if (untilSeconds == null) {
            listOf("""{"kinds":[0],"authors":["$authorPubkey"],"limit":1}""")
        } else {
            emptyList()
        }
        val filters = profileFilter + listOf(
            """{"kinds":[20,21,22,34235,34236],"authors":["$authorPubkey"],"limit":$boundedMedia""" +
                (untilSeconds?.let { ",\"until\":$it" } ?: "") + "}",
            """{"kinds":[1],"authors":["$authorPubkey"],"limit":$boundedText""" +
                (untilSeconds?.let { ",\"until\":$it" } ?: "") + "}",
        )
        return NostrEventCodec.encodeRequest(subscriptionId, filters)
    }

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

    /** Older-page insert: evicts at the head so a full window can page backward. */
    fun insertOlder(note: BusinessCoreBridge.Note): Boolean =
        aggregator.insertOlder(note.toCore())

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
        threadRootId = threadRootId,
        threadParentId = threadParentId,
        hashtags = hashtags,
        mentions = mentions,
        mediaUrls = mediaUrls,
        isProtocolPayload = protocolPayload,
        repostedBy = repostedBy,
        video = videoUrl?.let {
            MediaMetadata(
                url = it,
                mimeType = videoMime,
                posterUrl = posterUrl,
                width = videoWidth,
                height = videoHeight,
                durationSeconds = durationSeconds,
                fallbackUrls = fallbackUrls,
                renditions = renditionSpecs.mapNotNull(::parseRenditionSpec),
            )
        },
        contentWarning = contentWarning,
        poll = pollOptions.takeIf { it.isNotEmpty() }?.let { options ->
            space.bitos.core.model.Poll(
                question = content,
                options = options.mapIndexed { index, label ->
                    space.bitos.core.model.PollOption(index, label)
                },
            )
        },
        remixOfEventId = remixOfEventId,
        remixOfPubkey = remixOfPubkey,
        license = license,
    )

    private fun FeedNote.toBridge() = BusinessCoreBridge.Note(
        id = id,
        pubkey = pubkey,
        content = content,
        createdAt = createdAt,
        kind = kind,
        replyTo = replyTo,
        threadRootId = threadRootId,
        threadParentId = threadParentId,
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
        durationSeconds = video?.durationSeconds,
        contentWarning = contentWarning,
        pollOptions = poll?.options?.map { it.label } ?: emptyList(),
        remixOfEventId = remixOfEventId,
        remixOfPubkey = remixOfPubkey,
        license = license,
        fallbackUrls = video?.fallbackUrls ?: emptyList(),
        renditionSpecs = video?.renditions?.map { renditionSpec(it) } ?: emptyList(),
    )
}

/** Local 4-tuple for the ledger bridge parsing. */
private data class Quadruple(val first: Long, val second: String, val third: Long, val fourth: String?)

/** FED-004 spec row codec: `url|height|bitrate`. */
private fun renditionSpec(rendition: MediaRendition): String =
    "${rendition.url}|${rendition.height}|${rendition.bitrate}"

private fun parseRenditionSpec(spec: String): MediaRendition? {
    val parts = spec.split('|')
    if (parts.size != 3) return null
    val url = parts[0].takeIf { it.startsWith("http") } ?: return null
    val height = parts[1].toIntOrNull()?.takeIf { it in 1..100_000 } ?: return null
    val bitrate = parts[2].toLongOrNull()?.takeIf { it in 0..2_000_000_000L } ?: return null
    return MediaRendition(url, height, bitrate)
}
