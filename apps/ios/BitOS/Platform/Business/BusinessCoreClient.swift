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
}

/// Display-oriented media attachment (mirror of the shared `MediaMetadata`).
struct MediaMetadata: Sendable, Equatable {
    let url: String
    let mimeType: String?
    let posterUrl: String?
    let width: Int?
    let height: Int?
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

    func isFeedKind(_ kind: Int) -> Bool
    func isProfileKind(_ kind: Int) -> Bool

    /// APP-004 content-filter rule (shared core; Swift-note seam).
    func feedFilterMatches(note: FeedNote, filterOrdinal: Int, ownPubkeyHex: String?, likedIds: [String]) -> Bool
    /// APP-004 empty-feed retry delay in ms (shared `EmptyFeedRetry` policy:
    /// 2 s exponential backoff capped at 30 s).
    func emptyFeedRetryDelayMs(attempt: Int) -> Int
    /// APP-004 pagination: one older page — feed kinds before `until`.
    func olderFeedRequest(subscriptionId: String, until: Int64, limit: Int) -> String
    func profile(from event: VerifiedEvent) -> ProfileMetadata?
    func feedNote(from event: VerifiedEvent) -> FeedNote
    func feedRequest(subscriptionId: String) -> String
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

    func isFeedKind(_ kind: Int) -> Bool {
        bridge.isFeedKind(kind: Int32(kind))
    }

    func isProfileKind(_ kind: Int) -> Bool {
        bridge.isProfileKind(kind: Int32(kind))
    }

    func feedFilterMatches(note: FeedNote, filterOrdinal: Int, ownPubkeyHex: String?, likedIds: [String]) -> Bool {
        bridge.feedFilterMatches(
            note: note.bridgeNote,
            filterOrdinal: Int32(filterOrdinal),
            ownPubkeyHex: ownPubkeyHex,
            likedIds: likedIds
        )
    }

    func emptyFeedRetryDelayMs(attempt: Int) -> Int {
        Int(bridge.emptyFeedRetryDelayMs(attempt: Int32(attempt)))
    }

    func olderFeedRequest(subscriptionId: String, until: Int64, limit: Int) -> String {
        bridge.olderFeedRequest(subscriptionId: subscriptionId, until: until, limit: Int32(limit))
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
                lud16: $0.lud16
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
                    height: note.videoHeight?.intValue
                )
            },
            contentWarning: note.contentWarning,
            threadRootId: note.threadRootId,
            threadParentId: note.threadParentId,
            pollOptions: note.pollOptions.map { $0 as String }
        )
    }

    func feedRequest(subscriptionId: String) -> String {
        bridge.feedRequest(subscriptionId: subscriptionId)
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
                        height: note.videoHeight?.intValue
                    )
                },
                contentWarning: note.contentWarning
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
            contentWarning: contentWarning,
            threadRootId: threadRootId,
            threadParentId: threadParentId,
            pollOptions: pollOptions
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

    func isFeedKind(_ kind: Int) -> Bool { kind == 1 || kind == 22 }
    func isProfileKind(_ kind: Int) -> Bool { kind == 0 }
    func feedFilterMatches(note: FeedNote, filterOrdinal: Int, ownPubkeyHex: String?, likedIds: [String]) -> Bool {
        // Fixtures always pass; production goes through the shared core.
        true
    }
    func emptyFeedRetryDelayMs(attempt: Int) -> Int { 2_000 }
    func olderFeedRequest(subscriptionId: String, until: Int64, limit: Int) -> String { "" }
    func profile(from event: VerifiedEvent) -> ProfileMetadata? { nil }
    func feedNote(from event: VerifiedEvent) -> FeedNote {
        FeedNote(id: event.id, pubkey: event.pubkey, content: event.content,
                 createdAt: event.createdAt, kind: event.kind, replyTo: nil,
                 hashtags: [], mentions: [], mediaUrls: [], isProtocolPayload: false,
                 video: MediaMetadata(url: "", mimeType: nil, posterUrl: nil, width: nil, height: nil))
    }
    func feedRequest(subscriptionId: String) -> String { "" }
    func profileRequest(subscriptionId: String, authors: [String]) -> String { "" }
    func close(subscriptionId: String) -> String { "" }
    func makeFeedWindow(maxItems: Int) -> any FeedWindowing { NoopWindow() }
    func eventStoreSchemaVersion() -> Int { 1 }
    func eventStoreDdl() -> [String] { [] }
    func eventStoreMigrations() -> [Int: [String]] { [:] }
    func tagsToJson(_ tags: [[String]]) -> String { "[]" }
    func tagsFromJson(_ raw: String) -> [[String]]? { nil }
}

private final class NoopWindow: FeedWindowing {
    func insert(_ note: FeedNote) -> Bool { true }
    func snapshot() -> [FeedNote] { [] }
    func count() -> Int { 0 }
}
