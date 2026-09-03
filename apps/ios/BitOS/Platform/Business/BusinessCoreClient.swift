import BusinessCore
import Foundation

// MARK: - Swift domain projections
//
// Plain Swift value types for SwiftUI consumption. All protocol rules live
// once in shared/business-core; this facade only maps Kotlin bridge results
// into native types. No Nostr logic may be re-implemented here.

/// A decoded, ID-verified, signed relay event (mirror of `BusinessCoreBridge.Event`).
struct VerifiedEvent: Sendable, Equatable {
    let id: String
    let pubkey: String
    let createdAt: Int64
    let kind: Int
    let tags: [[String]]
    let content: String
    let relayUrl: String?
    let signature: String
}

/// Normalized feed note (mirror of `BusinessCoreBridge.Note`).
struct FeedNote: Sendable, Equatable, Identifiable {
    let id: String
    let pubkey: String
    let content: String
    let createdAt: Int64
    let kind: Int
    let replyTo: String?
    let hashtags: [String]
    let mentions: [String]
    let mediaUrls: [String]
    let isProtocolPayload: Bool
    var repostedBy: String? = nil
    var video: MediaMetadata? = nil
    var contentWarning: Bool = false
    /// APP-009 NIP-10 thread anchors (root / immediate parent). */
    var threadRootId: String? = nil
    var threadParentId: String? = nil
    /** APP-008 poll labels (index order; empty = not a poll). */
    var pollOptions: [String] = []
    /** APP-007 remix source (id + author); nulls = original work. */
    var remixOfEventId: String? = nil
    var remixOfPubkey: String? = nil
    /** APP-007 `license` tag (remix advisory gate); nil = permissive. */
    var license: String? = nil
}

/// Display-oriented media attachment (mirror of the shared `MediaMetadata`).
struct MediaMetadata: Sendable, Equatable {
    let url: String
    let mimeType: String?
    let posterUrl: String?
    let width: Int?
    let height: Int?
    /// NIP-92 imeta duration in whole seconds; nil = unknown.
    var durationSeconds: Int64? = nil
    /// FED-004 mirror chain (NIP-92 `fallback`), order preserved.
    var fallbackUrls: [String] = []
    /// FED-004 rendition ladder `url|height|bitrate` spec rows (tall→short).
    var renditionSpecs: [String] = []

    /// URLs of the ladder rows, tall→short (failover tail of the chain).
    var renditionUrls: [String] {
        renditionSpecs.compactMap { row in
            row.split(separator: "|", maxSplits: 2, omittingEmptySubsequences: false).first.map(String.init)
        }
    }
}

/// Bounded kind-0 profile projection (mirror of `BusinessCoreBridge.Profile`).
struct ProfileMetadata: Sendable, Equatable {
    let pubkey: String
    let name: String?
    let displayName: String?
    let about: String?
    let picture: String?
    let nip05: String?
    let lud16: String?

    var banner: String? = nil
    var website: String? = nil

    var bestDisplayName: String {
        (displayName?.isEmpty == false ? displayName : nil)
            ?? (name?.isEmpty == false ? name : nil)
            ?? String(pubkey.prefix(8))
    }
}

/// Native handle over the shared bounded feed window.
protocol FeedWindowing: AnyObject {
    @discardableResult
    func insert(_ note: FeedNote) -> Bool
    /// Older-page insert: evicts at the head so a full window can page
    /// backward instead of dropping the just-landed older page.
    @discardableResult
    func insertOlder(_ note: FeedNote) -> Bool
    func snapshot() -> [FeedNote]
    func count() -> Int
}

/// Feature seam over the shared BusinessCore bridge. Views and stores depend
/// on this protocol, never on Kotlin interop types.
protocol BusinessCoreClient: Sendable {
    /// Decode one relay frame into an ID-verified, signed event.
    /// Returns nil for malformed frames, size-bound violations, ID hash
    /// mismatches and unsigned events.
    func decodeVerifiedEvent(message: String, relay: String?) -> VerifiedEvent?
    /// Bounded NIP-01 EOSE subscription id; nil for every other frame.
    func relayEoseSubscriptionId(message: String) -> String?

    func isFeedKind(_ kind: Int) -> Bool
    func isProfileKind(_ kind: Int) -> Bool

    /// APP-004 content-filter rule (shared core; Swift-note seam).
    /// `showProtocolNotes` re-admits protocol-payload notes (web parity).
    func feedFilterMatches(note: FeedNote, filterOrdinal: Int, ownPubkeyHex: String?, likedIds: [String], showProtocolNotes: Bool) -> Bool
    /// Content classification (web `content-classification.ts` parity).
    func isProtocolPayload(_ content: String) -> Bool
    func isMachineTag(_ tag: String) -> Bool
    func humanTags(_ tags: [String]) -> [String]
    /// APP-004 empty-feed retry delay in ms (shared `EmptyFeedRetry` policy:
    /// 2 s exponential backoff capped at 30 s).
    func emptyFeedRetryDelayMs(attempt: Int) -> Int
    /// Cold-start account bootstrap (shared `AccountBootstrap` policy):
    /// whether an unresolved account head (own kind-0, kind-3 contacts,
    /// bookmarks, blocks) should be re-issued now.
    func accountBootstrapShouldReissue(resolved: Bool, attempts: Int, connectedRelays: Int) -> Bool
    /// Shared `AccountBootstrap`: grown relay connectivity opens a fresh
    /// attempt episode.
    func accountBootstrapShouldOpenEpisode(previousConnected: Int, currentConnected: Int) -> Bool
    /// APP-004 pagination: one older page — feed kinds before `until`.
    func olderFeedRequest(subscriptionId: String, until: Int64, limit: Int) -> String
    /// FED-004 walk budget: fresh playable notes one load-more targets.
    func bitzWalkPageBudget() -> Int
    /// FED-004 near-edge buffer that starts the next walk.
    func bitzWalkPrefetchThreshold() -> Int
    func profile(from event: VerifiedEvent) -> ProfileMetadata?
    func feedNote(from event: VerifiedEvent) -> FeedNote
    func feedRequest(subscriptionId: String) -> String
    func feedRequestSince(subscriptionId: String, since: Int64) -> String
    func profileRequest(subscriptionId: String, authors: [String]) -> String
    func close(subscriptionId: String) -> String
    func makeFeedWindow(maxItems: Int) -> any FeedWindowing

    // Persistence contract (DAT-001/002): versioned DDL + codecs shared with
    // Android through the common core.
    func eventStoreSchemaVersion() -> Int
    func eventStoreDdl() -> [String]
    func eventStoreMigrations() -> [Int: [String]]
    func tagsToJson(_ tags: [[String]]) -> String
    func tagsFromJson(_ raw: String) -> [[String]]?

    // APP-019 studio seams (plan MST-005/010..015): the meme editor works
    // on the project wire; corrupt wires normalize to "" and unknown
    // commands are no-ops, never throws.
    func memeProjectNormalize(_ projectJson: String) -> String
    func memeApplyCommand(_ projectJson: String, commandJson: String) -> String
    /// Top-most overlay id at the normalized point; "" = no hit.
    func memeHitTest(_ projectJson: String, x: Float, y: Float) -> String
    /// Caption palette as index-ordered 6-hex RGB rows (16 entries).
    func memePalette() -> [String]
    /// Deterministic default overlay (JSON object) for an `add` command;
    /// "" when the project wire is corrupt or the kind unknown.
    func memeDefaultOverlay(_ projectJson: String, kind: String, text: String) -> String
    /// Estimated overlay bounds `"width|height"` (normalized) for the
    /// selection chrome; "" when the overlay is unknown.
    func memeBounds(_ projectJson: String, overlayId: String) -> String
    /// Composite fx paint state at media time — `"scale|rot|dx|dy|alpha"`
    /// with the half-open visibility window folded into alpha (0 outside);
    /// a negative time = poster (identity). "" when unknown. Stage
    /// previews AND export keyframe sampling run through this (no Swift
    /// fx mirror).
    func memeFxTransformAt(_ projectJson: String, overlayId: String, atMs: Int64) -> String
    /// The full SFX cue mix over an export window as a base64 WAV
    /// (`renderCueTrack` — deterministic); "" when no cue is audible.
    /// `durationMs` is the OUTPUT duration; a rate ≠ 1 maps cue times
    /// into that timeline first.
    func memeSfxTrackWavBase64(_ projectJson: String, durationMs: Int64, rate: Float) -> String
    /// Sticker packs `[{"id","label","stickers":[…]}]` (web port).
    func memeStickerPacks() -> String

    // APP-019 meme wire document (MST-019): the `com.bitos.bitz.meme` v1
    // interop wire. Foreign schema ids/versions normalize to "" — never throws.
    /// Tolerant parse + canonical re-encode (passthrough preserved).
    func memeWireNormalize(_ wireJson: String, nowMs: Int64) -> String
    /// Wire document → local project wire (styles, stickers, windows, fx).
    func memeWireToLocal(_ wireJson: String) -> String
    /// Local project wire → wire document JSON (blank overlays drop).
    func localToMemeWire(_ projectJson: String, nowMs: Int64) -> String
    /// APP-019 export draw plan (MST-016): target-px paint commands for the
    /// Swift rasterizer; "" when the project wire is corrupt.
    func memeExportPlan(_ projectJson: String, sourceWidth: Int, sourceHeight: Int) -> String
    /// M2 GIF planning (shared rules): plan steps JSON for per-frame holds.
    func memeGifPlan(_ delaysMsJson: String, pinnedSec: Double) -> String
    /// M2 GIF ladder canvas `"width|height"` for a step; "" past the cap.
    func memeGifLadderCanvas(_ width: Int, _ height: Int, _ step: Int) -> String

    // APP-019 mass production (plan M4 wave 5 / MST-048): the Create hub
    // drives the batch document through four JSON seams; corrupt wires
    // decode to "" — never throws.
    /// Fresh batch document (canonical starter project + recipe).
    func massBatchNew(_ name: String, nowMs: Int64) -> String
    /// One mutation op on the batch wire (addRow/setValue/approve/…).
    func massBatchOp(_ docJson: String, opJson: String) -> String
    /// Full render plan: per-row severity/notes/approval/publish + counts.
    func massBatchPlan(_ docJson: String, readableJson: String) -> String
    /// CSV import: `{"doc":…,"notes":[…]}`.
    func massBatchImportCsv(_ docJson: String, csv: String) -> String

    // APP-019 looks (plan MST-043, web `look.ts` port).
    /// Look catalog `[{"id","label","css"}]` (id order, `none` first).
    func memeLooks() -> String
    /// Composed 4×5 matrix (20 floats) + `"blur"` px for a look id.
    func memeLookMatrix(_ lookId: String?) -> String

    // APP-019 video cut policy: long clips are CUT with a message, never
    // rejected (shared MemeVideoCutRules, both apps).
    /// Pick-time cap: `{"startMs","endMs","cut","message","attempts"}`.
    func memeVideoCutFor(durationMs: Int64) -> String
    /// Export ladder step: `{"endMs"}` (0 = cannot shrink further).
    func memeVideoCutForSize(currentMs: Int64, sizeBytes: Int64, maxBytes: Int64) -> String

    // M5 multi-clip timeline seams (shared clip list rules).
    /// Replaces a VIDEO project's clips from `[{"id","start","end","vol","look"}]`;
    /// "" when the project is corrupt or not video.
    func memeTimelineSyncClips(projectJson: String, clipsJson: String) -> String
    /// Timeline output duration ms (Σ window ÷ speed); −1 on a corrupt wire.
    func memeTimelineDurationMs(projectJson: String) -> Int64

    // APP-019 synth SFX (plan MST-041): catalog + base64 WAV previews.
    /// Kind-30078 shared-template summary row; "" when the shape is foreign.
    func memeSharedTemplateSummary(tagsJson: String, content: String) -> String
    /// Apply a shared template onto a project wire (fresh-id clone).
    func memeApplySharedTemplate(projectJson: String, tagsJson: String, content: String) -> String
    /// MST-042 remix lineage tags (TagsCodec JSON) from the project wire.
    func memeRemixTagsFor(projectJson: String, sourceEventId: String, sourcePubkey: String) -> String
    /// Built-in template pack `[{"id","label","emoji"}]` (MST-040 rail).
    func memeTemplates() -> String
    /// Apply a template onto a project wire (fresh-id clone); "" corrupt.
    func memeApplyTemplate(_ projectJson: String, templateId: String) -> String
    /// Catalog `[{"id","label","sfx":[…]}]` (5 buckets, 31 sounds).
    func memeSfxCatalog() -> String
    /// Rendered preview as a base64 WAV (mono 16-bit 44.1 kHz); "" junk id.
    func memeSfxWavBase64(_ sfxId: String, gain: Double) -> String
}

/// Production client backed by the BusinessCore XCFramework.
///
/// `BusinessCoreBridge` is stateless; `@unchecked Sendable` is limited to
/// that immutable instance. `FeedWindow` instances are created on the main
/// actor and confined there by their owner.
final class FrameworkBusinessCoreClient: BusinessCoreClient, @unchecked Sendable {
    private let bridge = BusinessCoreBridge()

    func decodeVerifiedEvent(message: String, relay: String?) -> VerifiedEvent? {
        guard let event = bridge.decodeEvent(message: message, relayUrl: relay) else { return nil }
        return VerifiedEvent(
            id: event.id,
            pubkey: event.pubkey,
            createdAt: event.createdAt,
            kind: Int(event.kind),
            tags: event.tags.map { $0.map { $0 as String } },
            content: event.content,
            relayUrl: event.relayUrl,
            signature: event.signature
        )
    }

    func relayEoseSubscriptionId(message: String) -> String? {
        bridge.relayEoseSubscriptionId(message: message)
    }

    func isFeedKind(_ kind: Int) -> Bool {
        bridge.isFeedKind(kind: Int32(kind))
    }

    func isProfileKind(_ kind: Int) -> Bool {
        bridge.isProfileKind(kind: Int32(kind))
    }

    func feedFilterMatches(note: FeedNote, filterOrdinal: Int, ownPubkeyHex: String?, likedIds: [String], showProtocolNotes: Bool) -> Bool {
        bridge.feedFilterMatches(
            note: note.bridgeNote,
            filterOrdinal: Int32(filterOrdinal),
            ownPubkeyHex: ownPubkeyHex,
            likedIds: likedIds,
            showProtocolNotes: showProtocolNotes
        )
    }

    func isProtocolPayload(_ content: String) -> Bool {
        bridge.isProtocolPayload(content: content)
    }

    func isMachineTag(_ tag: String) -> Bool {
        bridge.isMachineTag(tag: tag)
    }

    func humanTags(_ tags: [String]) -> [String] {
        bridge.humanTags(tags: tags).map { $0 as String }
    }

    func emptyFeedRetryDelayMs(attempt: Int) -> Int {
        Int(bridge.emptyFeedRetryDelayMs(attempt: Int32(attempt)))
    }

    func accountBootstrapShouldReissue(resolved: Bool, attempts: Int, connectedRelays: Int) -> Bool {
        bridge.accountBootstrapShouldReissue(resolved: resolved, attempts: Int32(attempts), connectedRelays: Int32(connectedRelays))
    }

    func accountBootstrapShouldOpenEpisode(previousConnected: Int, currentConnected: Int) -> Bool {
        bridge.accountBootstrapShouldOpenEpisode(previousConnected: Int32(previousConnected), currentConnected: Int32(currentConnected))
    }

    func olderFeedRequest(subscriptionId: String, until: Int64, limit: Int) -> String {
        bridge.olderFeedRequest(subscriptionId: subscriptionId, until: until, limit: Int32(limit))
    }

    func bitzWalkPageBudget() -> Int {
        Int(bridge.bitzWalkPageBudget())
    }

    func bitzWalkPrefetchThreshold() -> Int {
        Int(bridge.bitzWalkPrefetchThreshold())
    }

    func profile(from event: VerifiedEvent) -> ProfileMetadata? {
        bridge.profile(event: event.bridgeEvent(bridge: bridge)).map {
            ProfileMetadata(
                pubkey: $0.pubkey,
                name: $0.name,
                displayName: $0.displayName,
                about: $0.about,
                picture: $0.picture,
                nip05: $0.nip05,
                lud16: $0.lud16,
                banner: $0.banner,
                website: $0.website
            )
        }
    }

    func feedNote(from event: VerifiedEvent) -> FeedNote {
        let note = bridge.feedNote(event: event.bridgeEvent(bridge: bridge))
        return FeedNote(
            id: note.id,
            pubkey: note.pubkey,
            content: note.content,
            createdAt: note.createdAt,
            kind: Int(note.kind),
            replyTo: note.replyTo,
            hashtags: note.hashtags.map { $0 as String },
            mentions: note.mentions.map { $0 as String },
            mediaUrls: note.mediaUrls.map { $0 as String },
            isProtocolPayload: note.protocolPayload,
            repostedBy: note.repostedBy,
            video: note.videoUrl.map {
                MediaMetadata(
                    url: $0,
                    mimeType: note.videoMime,
                    posterUrl: note.posterUrl,
                    width: note.videoWidth?.intValue,
                    height: note.videoHeight?.intValue,
                    durationSeconds: note.durationSeconds?.int64Value,
                    fallbackUrls: note.fallbackUrls.map { $0 as String },
                    renditionSpecs: note.renditionSpecs.map { $0 as String }
                )
            },
            contentWarning: note.contentWarning,
            threadRootId: note.threadRootId,
            threadParentId: note.threadParentId,
            pollOptions: note.pollOptions.map { $0 as String },
            remixOfEventId: note.remixOfEventId,
            remixOfPubkey: note.remixOfPubkey,
            license: note.license
        )
    }

    func feedRequest(subscriptionId: String) -> String {
        bridge.feedRequest(subscriptionId: subscriptionId)
    }

    func feedRequestSince(subscriptionId: String, since: Int64) -> String {
        bridge.feedRequestSince(subscriptionId: subscriptionId, since: since)
    }

    func profileRequest(subscriptionId: String, authors: [String]) -> String {
        bridge.profileRequest(subscriptionId: subscriptionId, authors: authors)
    }

    func close(subscriptionId: String) -> String {
        bridge.close(subscriptionId: subscriptionId)
    }

    func makeFeedWindow(maxItems: Int) -> any FeedWindowing {
        SharedFeedWindow(bridge: bridge, maxItems: Int32(maxItems))
    }

    /// Narrow read-only access for platform stores (contacts, following).
    func bridgeForFollowing() -> BusinessCoreBridge {
        bridge
    }

    func eventStoreSchemaVersion() -> Int {
        Int(bridge.eventStoreSchemaVersion())
    }

    func eventStoreDdl() -> [String] {
        bridge.eventStoreDdl() as? [String] ?? []
    }

    func eventStoreMigrations() -> [Int: [String]] {
        var migrations: [Int: [String]] = [:]
        for (key, value) in bridge.eventStoreMigrations() {
            if let intKey = key as? Int, let values = value as? [String] {
                migrations[intKey] = values
            }
        }
        return migrations
    }

    func tagsToJson(_ tags: [[String]]) -> String {
        bridge.tagsToJson(tags: tags)
    }

    func tagsFromJson(_ raw: String) -> [[String]]? {
        bridge.tagsFromJson(raw: raw)
    }

    // APP-019 studio seams — thin passthroughs to the bridge.

    func memeProjectNormalize(_ projectJson: String) -> String {
        bridge.memeProjectNormalize(projectJson: projectJson)
    }

    func memeApplyCommand(_ projectJson: String, commandJson: String) -> String {
        bridge.memeApplyCommand(projectJson: projectJson, commandJson: commandJson)
    }

    func memeHitTest(_ projectJson: String, x: Float, y: Float) -> String {
        bridge.memeHitTest(projectJson: projectJson, x: x, y: y)
    }

    func memePalette() -> [String] {
        (bridge.memePalette() as? [String]) ?? []
    }

    func memeDefaultOverlay(_ projectJson: String, kind: String, text: String) -> String {
        bridge.memeDefaultOverlay(projectJson: projectJson, kind: kind, text: text)
    }

    func memeBounds(_ projectJson: String, overlayId: String) -> String {
        bridge.memeBounds(projectJson: projectJson, overlayId: overlayId)
    }

    func memeFxTransformAt(_ projectJson: String, overlayId: String, atMs: Int64) -> String {
        bridge.memeFxTransformAt(projectJson: projectJson, overlayId: overlayId, atMs: atMs)
    }

    func memeSfxTrackWavBase64(_ projectJson: String, durationMs: Int64, rate: Float) -> String {
        bridge.memeSfxTrackWavBase64(projectJson: projectJson, durationMs: durationMs, rate: rate)
    }

    func memeStickerPacks() -> String {
        bridge.memeStickerPacks()
    }

    func massBatchNew(_ name: String, nowMs: Int64) -> String {
        bridge.massBatchNew(name: name, nowMs: nowMs)
    }

    func massBatchOp(_ docJson: String, opJson: String) -> String {
        bridge.massBatchOp(docJson: docJson, opJson: opJson)
    }

    func massBatchPlan(_ docJson: String, readableJson: String) -> String {
        bridge.massBatchPlan(docJson: docJson, readableJson: readableJson)
    }

    func massBatchImportCsv(_ docJson: String, csv: String) -> String {
        bridge.massBatchImportCsv(docJson: docJson, csv: csv)
    }

    func memeLooks() -> String {
        bridge.memeLooks()
    }

    func memeLookMatrix(_ lookId: String?) -> String {
        bridge.memeLookMatrix(lookId: lookId)
    }

    func memeVideoCutFor(durationMs: Int64) -> String {
        bridge.memeVideoCutFor(durationMs: durationMs)
    }

    func memeVideoCutForSize(currentMs: Int64, sizeBytes: Int64, maxBytes: Int64) -> String {
        bridge.memeVideoCutForSize(currentMs: currentMs, sizeBytes: sizeBytes, maxBytes: maxBytes)
    }

    func memeTimelineSyncClips(projectJson: String, clipsJson: String) -> String {
        bridge.memeTimelineSyncClips(projectJson: projectJson, clipsJson: clipsJson)
    }

    func memeTimelineDurationMs(projectJson: String) -> Int64 {
        bridge.memeTimelineDurationMs(projectJson: projectJson)
    }

    func memeTemplates() -> String {
        bridge.memeTemplates()
    }

    func memeSharedTemplateSummary(tagsJson: String, content: String) -> String {
        bridge.memeSharedTemplateSummary(tagsJson: tagsJson, content: content)
    }

    func memeApplySharedTemplate(projectJson: String, tagsJson: String, content: String) -> String {
        bridge.memeApplySharedTemplate(projectJson: projectJson, tagsJson: tagsJson, content: content)
    }

    func memeRemixTagsFor(projectJson: String, sourceEventId: String, sourcePubkey: String) -> String {
        bridge.memeRemixTagsFor(
            projectJson: projectJson, sourceEventId: sourceEventId,
            sourcePubkey: sourcePubkey, relaysJson: "[]", license: "", attribution: ""
        )
    }

    func memeApplyTemplate(_ projectJson: String, templateId: String) -> String {
        bridge.memeApplyTemplate(projectJson: projectJson, templateId: templateId)
    }

    func memeSfxCatalog() -> String {
        bridge.memeSfxCatalog()
    }

    func memeSfxWavBase64(_ sfxId: String, gain: Double) -> String {
        bridge.memeSfxWavBase64(sfxId: sfxId, gain: gain)
    }

    func memeWireNormalize(_ wireJson: String, nowMs: Int64) -> String {
        bridge.memeWireNormalize(wireJson: wireJson, nowMs: nowMs)
    }

    func memeWireToLocal(_ wireJson: String) -> String {
        bridge.memeWireToLocal(wireJson: wireJson)
    }

    func localToMemeWire(_ projectJson: String, nowMs: Int64) -> String {
        bridge.localToMemeWire(projectJson: projectJson, nowMs: nowMs)
    }

    func memeExportPlan(_ projectJson: String, sourceWidth: Int, sourceHeight: Int) -> String {
        bridge.memeExportPlan(
            projectJson: projectJson,
            sourceWidth: Int32(sourceWidth),
            sourceHeight: Int32(sourceHeight)
        )
    }

    func memeGifPlan(_ delaysMsJson: String, pinnedSec: Double) -> String {
        bridge.memeGifPlan(delaysMsJson: delaysMsJson, pinnedSec: pinnedSec)
    }

    func memeGifLadderCanvas(_ width: Int, _ height: Int, _ step: Int) -> String {
        bridge.memeGifLadderCanvas(width: Int32(width), height: Int32(height), step: Int32(step))
    }
}

private final class SharedFeedWindow: FeedWindowing {
    private let window: FeedWindow

    init(bridge: BusinessCoreBridge, maxItems: Int32) {
        self.window = bridge.makeWindow(maxItems: maxItems)
    }

    @discardableResult
    func insert(_ note: FeedNote) -> Bool {
        window.insert(note: note.bridgeNote)
    }

    @discardableResult
    func insertOlder(_ note: FeedNote) -> Bool {
        window.insertOlder(note: note.bridgeNote)
    }

    func snapshot() -> [FeedNote] {
        window.snapshot().map { note in
            FeedNote(
                id: note.id,
                pubkey: note.pubkey,
                content: note.content,
                createdAt: note.createdAt,
                kind: Int(note.kind),
                replyTo: note.replyTo,
                hashtags: note.hashtags.map { $0 as String },
                mentions: note.mentions.map { $0 as String },
                mediaUrls: note.mediaUrls.map { $0 as String },
                isProtocolPayload: note.protocolPayload,
                repostedBy: note.repostedBy,
                video: note.videoUrl.map {
                    MediaMetadata(
                        url: $0,
                        mimeType: note.videoMime,
                        posterUrl: note.posterUrl,
                        width: note.videoWidth?.intValue,
                        height: note.videoHeight?.intValue,
                        durationSeconds: note.durationSeconds?.int64Value,
                        fallbackUrls: note.fallbackUrls.map { $0 as String },
                        renditionSpecs: note.renditionSpecs.map { $0 as String }
                    )
                },
                contentWarning: note.contentWarning,
                threadRootId: note.threadRootId,
                threadParentId: note.threadParentId,
                pollOptions: note.pollOptions.map { $0 as String },
                remixOfEventId: note.remixOfEventId,
                remixOfPubkey: note.remixOfPubkey,
                license: note.license
            )
        }
    }

    func count() -> Int {
        Int(window.size())
    }
}

private extension FeedNote {
    var bridgeNote: BusinessCoreBridge.Note {
        BusinessCoreBridge.Note(
            id: id,
            pubkey: pubkey,
            content: content,
            createdAt: createdAt,
            kind: Int32(kind),
            replyTo: replyTo,
            hashtags: hashtags,
            mentions: mentions,
            mediaUrls: mediaUrls,
            protocolPayload: isProtocolPayload,
            repostedBy: repostedBy,
            videoUrl: video?.url,
            videoMime: video?.mimeType,
            posterUrl: video?.posterUrl,
            videoWidth: video?.width.map { KotlinInt(value: Int32(truncatingIfNeeded: $0)) },
            videoHeight: video?.height.map { KotlinInt(value: Int32(truncatingIfNeeded: $0)) },
            durationSeconds: video?.durationSeconds.map { KotlinLong(value: $0) },
            contentWarning: contentWarning,
            threadRootId: threadRootId,
            threadParentId: threadParentId,
            pollOptions: pollOptions,
            remixOfEventId: remixOfEventId,
            remixOfPubkey: remixOfPubkey,
            license: license,
            fallbackUrls: video?.fallbackUrls ?? [],
            renditionSpecs: video?.renditionSpecs ?? []
        )
    }
}

private extension VerifiedEvent {
    /// Rebuilds the bridge event handle for a second bridge call
    /// (profile/normalization). Cheap: struct copies only.
    func bridgeEvent(bridge: BusinessCoreBridge) -> BusinessCoreBridge.Event {
        BusinessCoreBridge.Event(
            id: id,
            pubkey: pubkey,
            createdAt: createdAt,
            kind: Int32(kind),
            tags: tags,
            content: content,
            relayUrl: relayUrl,
            signature: signature
        )
    }
}

/// Deterministic test client serving checked-in fixture frames.
struct FixtureBusinessCoreClient: BusinessCoreClient {
    func decodeVerifiedEvent(message: String, relay: String?) -> VerifiedEvent? {
        FrameworkBusinessCoreClient().decodeVerifiedEvent(message: message, relay: relay)
    }
    func relayEoseSubscriptionId(message: String) -> String? {
        guard let data = message.data(using: .utf8),
              let frame = try? JSONSerialization.jsonObject(with: data) as? [Any],
              frame.count == 2,
              frame[0] as? String == "EOSE" else { return nil }
        return frame[1] as? String
    }

    func isFeedKind(_ kind: Int) -> Bool { kind == 1 || kind == 21 || kind == 22 }
    func isProfileKind(_ kind: Int) -> Bool { kind == 0 }
    func feedFilterMatches(note: FeedNote, filterOrdinal: Int, ownPubkeyHex: String?, likedIds: [String], showProtocolNotes: Bool) -> Bool {
        // Same seam as decode: the shared rule runs once in the framework.
        FrameworkBusinessCoreClient().feedFilterMatches(
            note: note, filterOrdinal: filterOrdinal, ownPubkeyHex: ownPubkeyHex,
            likedIds: likedIds, showProtocolNotes: showProtocolNotes
        )
    }
    func isProtocolPayload(_ content: String) -> Bool {
        FrameworkBusinessCoreClient().isProtocolPayload(content)
    }
    func isMachineTag(_ tag: String) -> Bool {
        FrameworkBusinessCoreClient().isMachineTag(tag)
    }
    func humanTags(_ tags: [String]) -> [String] {
        FrameworkBusinessCoreClient().humanTags(tags)
    }
    func emptyFeedRetryDelayMs(attempt: Int) -> Int { 2_000 }
    func accountBootstrapShouldReissue(resolved: Bool, attempts: Int, connectedRelays: Int) -> Bool {
        FrameworkBusinessCoreClient().accountBootstrapShouldReissue(resolved: resolved, attempts: attempts, connectedRelays: connectedRelays)
    }
    func accountBootstrapShouldOpenEpisode(previousConnected: Int, currentConnected: Int) -> Bool {
        FrameworkBusinessCoreClient().accountBootstrapShouldOpenEpisode(previousConnected: previousConnected, currentConnected: currentConnected)
    }
    func olderFeedRequest(subscriptionId: String, until: Int64, limit: Int) -> String { "" }
    func bitzWalkPageBudget() -> Int { 10 }
    func bitzWalkPrefetchThreshold() -> Int { 10 }
    func profile(from event: VerifiedEvent) -> ProfileMetadata? { nil }
    func feedNote(from event: VerifiedEvent) -> FeedNote {
        FeedNote(id: event.id, pubkey: event.pubkey, content: event.content,
                 createdAt: event.createdAt, kind: event.kind, replyTo: nil,
                 hashtags: [], mentions: [], mediaUrls: [], isProtocolPayload: false,
                 video: MediaMetadata(url: "", mimeType: nil, posterUrl: nil, width: nil, height: nil, durationSeconds: nil))
    }
    func feedRequest(subscriptionId: String) -> String { "" }
    func feedRequestSince(subscriptionId: String, since: Int64) -> String { "" }
    func profileRequest(subscriptionId: String, authors: [String]) -> String { "" }
    func close(subscriptionId: String) -> String { "" }
    func makeFeedWindow(maxItems: Int) -> any FeedWindowing { NoopWindow() }
    func eventStoreSchemaVersion() -> Int { 1 }
    func eventStoreDdl() -> [String] { [] }
    func eventStoreMigrations() -> [Int: [String]] { [:] }
    func tagsToJson(_ tags: [[String]]) -> String { "[]" }
    func tagsFromJson(_ raw: String) -> [[String]]? { nil }

    // Meme seams delegate to the framework (same decode-once rule).
    func memeProjectNormalize(_ projectJson: String) -> String {
        FrameworkBusinessCoreClient().memeProjectNormalize(projectJson)
    }
    func memeApplyCommand(_ projectJson: String, commandJson: String) -> String {
        FrameworkBusinessCoreClient().memeApplyCommand(projectJson, commandJson: commandJson)
    }
    func memeHitTest(_ projectJson: String, x: Float, y: Float) -> String {
        FrameworkBusinessCoreClient().memeHitTest(projectJson, x: x, y: y)
    }
    func memePalette() -> [String] { FrameworkBusinessCoreClient().memePalette() }
    func memeDefaultOverlay(_ projectJson: String, kind: String, text: String) -> String {
        FrameworkBusinessCoreClient().memeDefaultOverlay(projectJson, kind: kind, text: text)
    }
    func memeBounds(_ projectJson: String, overlayId: String) -> String {
        FrameworkBusinessCoreClient().memeBounds(projectJson, overlayId: overlayId)
    }
    func memeFxTransformAt(_ projectJson: String, overlayId: String, atMs: Int64) -> String {
        FrameworkBusinessCoreClient().memeFxTransformAt(projectJson, overlayId: overlayId, atMs: atMs)
    }
    func memeSfxTrackWavBase64(_ projectJson: String, durationMs: Int64, rate: Float) -> String {
        FrameworkBusinessCoreClient().memeSfxTrackWavBase64(projectJson, durationMs: durationMs, rate: rate)
    }
    func memeStickerPacks() -> String { FrameworkBusinessCoreClient().memeStickerPacks() }
    func massBatchNew(_ name: String, nowMs: Int64) -> String {
        FrameworkBusinessCoreClient().massBatchNew(name, nowMs: nowMs)
    }
    func massBatchOp(_ docJson: String, opJson: String) -> String {
        FrameworkBusinessCoreClient().massBatchOp(docJson, opJson: opJson)
    }
    func massBatchPlan(_ docJson: String, readableJson: String) -> String {
        FrameworkBusinessCoreClient().massBatchPlan(docJson, readableJson: readableJson)
    }
    func massBatchImportCsv(_ docJson: String, csv: String) -> String {
        FrameworkBusinessCoreClient().massBatchImportCsv(docJson, csv: csv)
    }
    func memeLooks() -> String { FrameworkBusinessCoreClient().memeLooks() }
    func memeLookMatrix(_ lookId: String?) -> String {
        FrameworkBusinessCoreClient().memeLookMatrix(lookId)
    }
    func memeVideoCutFor(durationMs: Int64) -> String {
        FrameworkBusinessCoreClient().memeVideoCutFor(durationMs: durationMs)
    }
    func memeVideoCutForSize(currentMs: Int64, sizeBytes: Int64, maxBytes: Int64) -> String {
        FrameworkBusinessCoreClient().memeVideoCutForSize(currentMs: currentMs, sizeBytes: sizeBytes, maxBytes: maxBytes)
    }
    func memeTimelineSyncClips(projectJson: String, clipsJson: String) -> String {
        FrameworkBusinessCoreClient().memeTimelineSyncClips(projectJson: projectJson, clipsJson: clipsJson)
    }
    func memeTimelineDurationMs(projectJson: String) -> Int64 {
        FrameworkBusinessCoreClient().memeTimelineDurationMs(projectJson: projectJson)
    }
    func memeSfxCatalog() -> String { FrameworkBusinessCoreClient().memeSfxCatalog() }
    func memeTemplates() -> String { FrameworkBusinessCoreClient().memeTemplates() }
    func memeSharedTemplateSummary(tagsJson: String, content: String) -> String {
        FrameworkBusinessCoreClient().memeSharedTemplateSummary(tagsJson: tagsJson, content: content)
    }
    func memeApplySharedTemplate(projectJson: String, tagsJson: String, content: String) -> String {
        FrameworkBusinessCoreClient().memeApplySharedTemplate(projectJson: projectJson, tagsJson: tagsJson, content: content)
    }
    func memeRemixTagsFor(projectJson: String, sourceEventId: String, sourcePubkey: String) -> String {
        FrameworkBusinessCoreClient().memeRemixTagsFor(
            projectJson: projectJson, sourceEventId: sourceEventId, sourcePubkey: sourcePubkey
        )
    }
    func memeApplyTemplate(_ projectJson: String, templateId: String) -> String {
        FrameworkBusinessCoreClient().memeApplyTemplate(projectJson, templateId: templateId)
    }
    func memeSfxWavBase64(_ sfxId: String, gain: Double) -> String {
        FrameworkBusinessCoreClient().memeSfxWavBase64(sfxId, gain: gain)
    }
    func memeWireNormalize(_ wireJson: String, nowMs: Int64) -> String {
        FrameworkBusinessCoreClient().memeWireNormalize(wireJson, nowMs: nowMs)
    }
    func memeWireToLocal(_ wireJson: String) -> String {
        FrameworkBusinessCoreClient().memeWireToLocal(wireJson)
    }
    func localToMemeWire(_ projectJson: String, nowMs: Int64) -> String {
        FrameworkBusinessCoreClient().localToMemeWire(projectJson, nowMs: nowMs)
    }
    func memeExportPlan(_ projectJson: String, sourceWidth: Int, sourceHeight: Int) -> String {
        FrameworkBusinessCoreClient().memeExportPlan(projectJson, sourceWidth: sourceWidth, sourceHeight: sourceHeight)
    }
    func memeGifPlan(_ delaysMsJson: String, pinnedSec: Double) -> String {
        FrameworkBusinessCoreClient().memeGifPlan(delaysMsJson, pinnedSec: pinnedSec)
    }
    func memeGifLadderCanvas(_ width: Int, _ height: Int, _ step: Int) -> String {
        FrameworkBusinessCoreClient().memeGifLadderCanvas(width, height, step)
    }
}

private final class NoopWindow: FeedWindowing {
    func insert(_ note: FeedNote) -> Bool { true }
    func insertOlder(_ note: FeedNote) -> Bool { true }
    func snapshot() -> [FeedNote] { [] }
    func count() -> Int { 0 }
}
