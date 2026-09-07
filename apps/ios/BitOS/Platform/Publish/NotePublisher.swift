import BusinessCore
import Foundation
import Observation

/// One relay's receipt for the in-flight publish (PUB-008).
struct PublishReceipt: Sendable, Equatable, Identifiable {
    let relayHost: String
    var accepted: Bool?
    var detail: String?
    var id: String { relayHost }
}

enum PublishResult: Sendable, Equatable {
    case published
    case rejected(detail: String?)
    case timeout
    case signingRefused
    case invalid
}

/** Real meme-publish checkpoints (drives the publish machine stepper):
 * built → signed → relayed (first OK or timeout resolved the send). The
 * String payload is the canonical event id. */
enum MemeNoteStage: Sendable {
    case built(String)
    case signed(String)
    case relayed(String)
}

@MainActor
@Observable
final class NotePublisher {
    private(set) var inFlightId: String?
    private(set) var receipts: [PublishReceipt] = []
    private(set) var result: PublishResult?
    private(set) var busy = false

    private let pool: RelayPool
    private let bridge: BusinessCoreBridge
    private let identity: IdentityStore
    /** Privacy prefs — read at publish time (web clientTag() opt-in parity). */
    private let privacyPrefs: PrivacyPrefsStore?
    private var watchTask: Task<Void, Never>?

    init(pool: RelayPool, identity: IdentityStore, bridge: BusinessCoreBridge = BusinessCoreBridge(), privacyPrefs: PrivacyPrefsStore? = nil) {
        self.pool = pool
        self.privacyPrefs = privacyPrefs
        self.identity = identity
        self.bridge = bridge
    }

    /// Compose → sign (refusal fails without sending) → targeted fan-out →
    /// first acceptance completes; rejection-only timeouts surface reasons.
    func publish(content: String) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }

        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeEventId(content: content, pubkeyHex: account.pubkeyHex, nowSeconds: now),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.publishMessage(
                  content: content, pubkeyHex: account.pubkeyHex, createdAtSeconds: now, signatureHex: signature
              ) else {
            result = frameFailure(for: content, account: account, now: now)
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// One bounded mining window for a kind-1 PoW note (NIP-13, APP-008),
    /// off the main actor. `createdAt` must stay fixed for the session and
    /// be reused by `publishPow`. Returns nil when the window is exhausted
    /// (resume at `startNonce + attempts`).
    func minePowChunk(
        content: String,
        targetDifficulty: Int32,
        createdAt: Int64,
        startNonce: Int64,
        attempts: Int64
    ) async -> (nonce: Int64, idHex: String)? {
        guard let account = identity.account else { return nil }
        let bridge = self.bridge
        let raw = await Task.detached(priority: .userInitiated) {
            bridge.mineTextNotePow(
                content: content,
                pubkeyHex: account.pubkeyHex,
                createdAtSeconds: createdAt,
                targetDifficulty: targetDifficulty,
                startNonce: startNonce,
                maxAttempts: attempts
            )
        }.value
        guard let raw, let separator = raw.firstIndex(of: ":") else { return nil }
        let nonce = Int64(raw[raw.startIndex..<separator])
        guard let nonce else { return nil }
        return (nonce, String(raw[raw.index(after: separator)...]))
    }

    /// Kind-1 note with a pre-mined NIP-13 nonce tag (APP-008 PowCard).
    /// `nonce`/`targetDifficulty`/`createdAt` come from the mining session
    /// — publish must reuse the mined timestamp.
    func publishPow(content: String, nonce: Int64, targetDifficulty: Int32, createdAt: Int64) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        guard let eventId = bridge.powTextNoteEventId(
                  content: content, pubkeyHex: account.pubkeyHex, createdAtSeconds: createdAt,
                  nonce: nonce, targetDifficulty: targetDifficulty
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.powTextNotePublishMessage(
                  content: content, pubkeyHex: account.pubkeyHex, createdAtSeconds: createdAt,
                  nonce: nonce, targetDifficulty: targetDifficulty, signatureHex: signature
              ) else {
            result = .signingRefused
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// APP-008 composer page: kind-1 with derived tags (ComposerRules via
    /// the bridge; tagsJson is the TagsCodec wire form).
    func publishNote(content: String, tagsJson: String) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeTextNoteWithTagsEventId(
                  content: content, pubkeyHex: account.pubkeyHex, nowSeconds: now, tagsJson: tagsJson
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.textNoteWithTagsPublishMessage(
                  content: content, pubkeyHex: account.pubkeyHex, createdAtSeconds: now,
                  signatureHex: signature, tagsJson: tagsJson
              ) else {
            result = .signingRefused
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// NIP-22 kind-1111 comment on a non-kind-1 event (web `feed.comment`
    /// parity): tags come from the bridge's `commentTagsJson`.
    func publishComment(content: String, tagsJson: String) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeCommentWithTagsEventId(
                  content: content, pubkeyHex: account.pubkeyHex, nowSeconds: now, tagsJson: tagsJson
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.commentWithTagsPublishMessage(
                  content: content, pubkeyHex: account.pubkeyHex, createdAtSeconds: now,
                  signatureHex: signature, tagsJson: tagsJson
              ) else {
            result = .signingRefused
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// Tags-aware mining window for the composer page (template carries
    /// the same tags the publisher will commit).
    func minePowChunkWithTags(
        content: String,
        targetDifficulty: Int32,
        createdAt: Int64,
        startNonce: Int64,
        attempts: Int64,
        tagsJson: String
    ) async -> (nonce: Int64, idHex: String)? {
        guard let account = identity.account else { return nil }
        let bridge = self.bridge
        let raw = await Task.detached(priority: .userInitiated) {
            bridge.mineTextNotePowWithTags(
                content: content,
                pubkeyHex: account.pubkeyHex,
                createdAtSeconds: createdAt,
                targetDifficulty: targetDifficulty,
                startNonce: startNonce,
                maxAttempts: attempts,
                tagsJson: tagsJson
            )
        }.value
        guard let raw, let separator = raw.firstIndex(of: ":") else { return nil }
        let nonce = Int64(raw[raw.startIndex..<separator])
        guard let nonce else { return nil }
        return (nonce, String(raw[raw.index(after: separator)...]))
    }

    /// APP-008 composer page PoW publish: nonce + derived tags.
    func publishPowNote(
        content: String,
        nonce: Int64,
        targetDifficulty: Int32,
        createdAt: Int64,
        tagsJson: String
    ) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        guard let eventId = bridge.powTextNoteWithTagsEventId(
                  content: content, pubkeyHex: account.pubkeyHex, createdAtSeconds: createdAt,
                  nonce: nonce, targetDifficulty: targetDifficulty, tagsJson: tagsJson
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.powTextNoteWithTagsPublishMessage(
                  content: content, pubkeyHex: account.pubkeyHex, createdAtSeconds: createdAt,
                  nonce: nonce, targetDifficulty: targetDifficulty, signatureHex: signature, tagsJson: tagsJson
              ) else {
            result = .signingRefused
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// Comment-sheet PoW: mining window over the NIP-22 kind-1111 template
    /// (the same `commentTags` the publisher will commit).
    func minePowCommentChunkWithTags(
        content: String,
        targetDifficulty: Int32,
        createdAt: Int64,
        startNonce: Int64,
        attempts: Int64,
        tagsJson: String
    ) async -> (nonce: Int64, idHex: String)? {
        guard let account = identity.account else { return nil }
        let bridge = self.bridge
        let raw = await Task.detached(priority: .userInitiated) {
            bridge.mineCommentPowWithTags(
                content: content,
                pubkeyHex: account.pubkeyHex,
                createdAtSeconds: createdAt,
                targetDifficulty: targetDifficulty,
                startNonce: startNonce,
                maxAttempts: attempts,
                tagsJson: tagsJson
            )
        }.value
        guard let raw, let separator = raw.firstIndex(of: ":") else { return nil }
        let nonce = Int64(raw[raw.startIndex..<separator])
        guard let nonce else { return nil }
        return (nonce, String(raw[raw.index(after: separator)...]))
    }

    /// Comment-sheet PoW publish: kind-1111 comment + committed nonce tag.
    func publishPowComment(
        content: String,
        nonce: Int64,
        targetDifficulty: Int32,
        createdAt: Int64,
        tagsJson: String
    ) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        guard let eventId = bridge.powCommentWithTagsEventId(
                  content: content, pubkeyHex: account.pubkeyHex, createdAtSeconds: createdAt,
                  nonce: nonce, targetDifficulty: targetDifficulty, tagsJson: tagsJson
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.powCommentWithTagsPublishMessage(
                  content: content, pubkeyHex: account.pubkeyHex, createdAtSeconds: createdAt,
                  nonce: nonce, targetDifficulty: targetDifficulty, signatureHex: signature, tagsJson: tagsJson
              ) else {
            result = .signingRefused
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// Kind-7 reaction through the same machine (SOC-003). One at a time.
    func publishReaction(targetEventId: String, targetPubkey: String) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeReactionEventId(
                  targetEventId: targetEventId, targetPubkey: targetPubkey, authorPubkey: account.pubkeyHex, nowSeconds: now
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.reactionPublishMessage(
                  targetEventId: targetEventId, targetPubkey: targetPubkey, authorPubkey: account.pubkeyHex,
                  createdAtSeconds: now, signatureHex: signature
              ) else {
            result = .signingRefused
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// APP-006 story like (kind 7 ❤️ with e/p/a target tags, web
    /// `stories.like` parity; tagsJson is the TagsCodec wire form).
    func publishStoryReaction(emoji: String, tagsJson: String) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeReactionWithTagsEventId(
                  emoji: emoji, tagsJson: tagsJson, authorPubkey: account.pubkeyHex, nowSeconds: now
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.reactionWithTagsPublishMessage(
                  emoji: emoji, tagsJson: tagsJson, authorPubkey: account.pubkeyHex,
                  createdAtSeconds: now, signatureHex: signature
              ) else {
            result = .invalid
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// APP-006 story publish (web `stories.publish` parity): kind-30315 with
    /// per-image NIP-92 imeta, an optional video imeta, background gradient
    /// for text-only slides and a content-warning tag for sensitive media.
    func publishStory(
        text: String,
        imageUrls: [String],
        background: String?,
        altText: String,
        sensitive: Bool,
        videoUrl: String? = nil,
        videoMime: String? = nil,
        videoDurationMs: Int64 = 0,
        videoPoster: String? = nil
    ) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        let dTag = "bitos-story-\(now)-\(String(UInt32.random(in: 0...UInt32.max), radix: 36))"
        guard let eventId = bridge.composeStoryEventId(
                  pubkeyHex: account.pubkeyHex, text: text, imageUrls: imageUrls,
                  background: background, altText: altText, sensitive: sensitive,
                  dTag: dTag, nowSeconds: now,
                  videoUrl: videoUrl, videoMime: videoMime, videoDurationMs: videoDurationMs, videoPoster: videoPoster
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.storyPublishMessage(
                  pubkeyHex: account.pubkeyHex, text: text, imageUrls: imageUrls,
                  background: background, altText: altText, sensitive: sensitive,
                  dTag: dTag, createdAtSeconds: now, signatureHex: signature,
                  videoUrl: videoUrl, videoMime: videoMime, videoDurationMs: videoDurationMs, videoPoster: videoPoster
              ) else {
            result = .invalid
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// APP-006 story PoW: one bounded mining window over the kind-30315
    /// template at the FIXED `createdAt` (expiration derives from it; the
    /// dTag is part of the template). Off the main actor; same
    /// `nonce:idHex` contract as `minePowChunkWithTags`.
    func mineStoryPowChunk(
        text: String,
        imageUrls: [String],
        background: String?,
        altText: String,
        sensitive: Bool,
        dTag: String,
        targetDifficulty: Int32,
        createdAt: Int64,
        startNonce: Int64,
        attempts: Int64,
        videoUrl: String? = nil,
        videoMime: String? = nil,
        videoDurationMs: Int64 = 0,
        videoPoster: String? = nil
    ) async -> (nonce: Int64, idHex: String)? {
        guard let account = identity.account else { return nil }
        let bridge = self.bridge
        let raw = await Task.detached(priority: .userInitiated) {
            bridge.mineStoryPow(
                pubkeyHex: account.pubkeyHex, text: text, imageUrls: imageUrls,
                background: background, altText: altText, sensitive: sensitive,
                dTag: dTag, createdAtSeconds: createdAt,
                targetDifficulty: targetDifficulty,
                startNonce: startNonce, maxAttempts: attempts,
                videoUrl: videoUrl, videoMime: videoMime, videoDurationMs: videoDurationMs, videoPoster: videoPoster
            )
        }.value
        guard let raw, let separator = raw.firstIndex(of: ":") else { return nil }
        let nonce = Int64(raw[raw.startIndex..<separator])
        guard let nonce else { return nil }
        return (nonce, String(raw[raw.index(after: separator)...]))
    }

    /// APP-006 story PoW publish: the nonce was mined over the exact
    /// kind-30315 template (same `dTag` + `createdAt` the PowCard session
    /// committed), so the published event reproduces the mined id.
    func publishStoryWithPow(
        text: String,
        imageUrls: [String],
        background: String?,
        altText: String,
        sensitive: Bool,
        dTag: String,
        nonce: Int64,
        targetDifficulty: Int32,
        createdAt: Int64,
        videoUrl: String? = nil,
        videoMime: String? = nil,
        videoDurationMs: Int64 = 0,
        videoPoster: String? = nil
    ) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        guard let eventId = bridge.powStoryEventId(
                  pubkeyHex: account.pubkeyHex, text: text, imageUrls: imageUrls,
                  background: background, altText: altText, sensitive: sensitive,
                  dTag: dTag, nonce: nonce, targetDifficulty: targetDifficulty,
                  createdAtSeconds: createdAt,
                  videoUrl: videoUrl, videoMime: videoMime, videoDurationMs: videoDurationMs, videoPoster: videoPoster
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.powStoryPublishMessage(
                  pubkeyHex: account.pubkeyHex, text: text, imageUrls: imageUrls,
                  background: background, altText: altText, sensitive: sensitive,
                  dTag: dTag, nonce: nonce, targetDifficulty: targetDifficulty,
                  createdAtSeconds: createdAt, signatureHex: signature,
                  videoUrl: videoUrl, videoMime: videoMime, videoDurationMs: videoDurationMs, videoPoster: videoPoster
              ) else {
            result = .invalid
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// Kind-1 reply (NIP-10) through the same machine (SOC-002).
    func publishReply(content: String, targetEventId: String, targetPubkey: String) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeReplyEventId(
                  content: content, targetEventId: targetEventId, targetPubkey: targetPubkey,
                  authorPubkey: account.pubkeyHex, relayHint: "", nowSeconds: now
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.replyPublishMessage(
                  content: content, targetEventId: targetEventId, targetPubkey: targetPubkey,
                  authorPubkey: account.pubkeyHex, relayHint: "",
                  createdAtSeconds: now, signatureHex: signature
              ) else {
            result = .signingRefused
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// Kind-3 follow-list publish (SOC-001 write path).
    func publishFollowList(follows: [String]) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeFollowListEventId(
                  authorPubkey: account.pubkeyHex, follows: follows, nowSeconds: now
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.followListPublishMessage(
                  authorPubkey: account.pubkeyHex, follows: follows,
                  createdAtSeconds: now, signatureHex: signature
              ) else {
            result = .invalid
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// Kind-10002 relay-list publish (NIP-65, APP-018 relays manager):
    /// composes from the managed-set wire JSON and fans out to the set's
    /// write-role relays through the same receipt machine.
    func publishRelayList(relayListJson: String, writeUrls: [RelayURL]) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeRelayListEventId(
                  authorPubkey: account.pubkeyHex, relayListJson: relayListJson, nowSeconds: now
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.relayListPublishMessage(
                  authorPubkey: account.pubkeyHex, relayListJson: relayListJson,
                  createdAtSeconds: now, signatureHex: signature
              ) else {
            result = .invalid
            return
        }
        await send(eventId: eventId, frame: frame, writeUrls: writeUrls.isEmpty ? DefaultRelays.writeUrls : writeUrls)
    }

    /// Kind-10004 block-list publish (NIP-51, APP-018 privacy): replaces
    /// the head with the given set (unblock = publish without).
    func publishBlockList(blocked: [String], writeUrls: [RelayURL]) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeBlockListEventId(
                  authorPubkey: account.pubkeyHex, blocked: blocked, nowSeconds: now
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.blockListPublishMessage(
                  authorPubkey: account.pubkeyHex, blocked: blocked,
                  createdAtSeconds: now, signatureHex: signature
              ) else {
            result = .invalid
            return
        }
        await send(eventId: eventId, frame: frame, writeUrls: writeUrls.isEmpty ? DefaultRelays.writeUrls : writeUrls)
    }

    /// Kind-6 repost (NIP-18) through the same machine.
    func publishRepost(targetEventId: String, targetPubkey: String) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeRepostEventId(
                  targetEventId: targetEventId, targetPubkey: targetPubkey,
                  authorPubkey: account.pubkeyHex, nowSeconds: now
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.repostPublishMessage(
                  targetEventId: targetEventId, targetPubkey: targetPubkey,
                  authorPubkey: account.pubkeyHex, createdAtSeconds: now, signatureHex: signature
              ) else {
            result = .invalid
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// NIP-51 interest set publish (kind 30015 d=interest — followed hashtags).
    func publishInterestSet(hashtags: [String]) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeInterestSetEventId(
                  authorPubkey: account.pubkeyHex, hashtags: hashtags, nowSeconds: now
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.interestSetPublishMessage(
                  authorPubkey: account.pubkeyHex, hashtags: hashtags,
                  createdAtSeconds: now, signatureHex: signature
              ) else {
            result = .signingRefused
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// Kind-1018 poll vote publish (web `votePoll` wire parity).
    func publishPollVote(targetEventId: String, optionIndex: Int) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composePollVoteEventId(
                  targetEventId: targetEventId, optionIndex: Int64(optionIndex),
                  authorPubkey: account.pubkeyHex, nowSeconds: now
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.pollVotePublishMessage(
                  targetEventId: targetEventId, optionIndex: Int64(optionIndex),
                  authorPubkey: account.pubkeyHex, createdAtSeconds: now, signatureHex: signature
              ) else {
            result = .signingRefused
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// Kind-5 deletion publish (NIP-09, web `feed.deleteNote` parity).
    func publishDeletion(targetEventIds: [String], reason: String = "Deleted from BitOS") async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeDeletionEventId(
                  targetEventIds: targetEventIds, authorPubkey: account.pubkeyHex,
                  reason: reason, nowSeconds: now
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.deletionPublishMessage(
                  targetEventIds: targetEventIds, authorPubkey: account.pubkeyHex,
                  reason: reason, createdAtSeconds: now, signatureHex: signature
              ) else {
            result = .invalid
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// Kind-30003 bookmark-list publish (NIP-51, addressable head).
    func publishBookmarkList(eventIds: [String]) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeBookmarkListEventId(
                  authorPubkey: account.pubkeyHex, eventIds: eventIds, nowSeconds: now
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.bookmarkListPublishMessage(
                  authorPubkey: account.pubkeyHex, eventIds: eventIds,
                  createdAtSeconds: now, signatureHex: signature
              ) else {
            result = .invalid
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// Kind-22 media note from a verified upload (PUB media path).
    func publishMediaNote(
        caption: String,
        altText: String = "",
        contentWarningReason: String? = nil,
        url: String, hash: String, mime: String, size: Int,
        width: Int? = nil, height: Int? = nil, durationMs: Int64? = nil
    ) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeMediaNoteEventId(
                  authorPubkey: account.pubkeyHex, caption: caption,
                  url: url, sha256Hex: hash, mimeType: mime, sizeBytes: Int64(size),
                  width: Int64(width ?? 0), height: Int64(height ?? 0), durationMs: durationMs ?? 0,
                  nowSeconds: now, altText: altText, contentWarningReason: contentWarningReason,
                  includeClientTag: includeClientTag
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.mediaNotePublishMessage(
                  authorPubkey: account.pubkeyHex, caption: caption,
                  url: url, sha256Hex: hash, mimeType: mime, sizeBytes: Int64(size),
                  width: Int64(width ?? 0), height: Int64(height ?? 0), durationMs: durationMs ?? 0,
                  createdAtSeconds: now, signatureHex: signature,
                  altText: altText, contentWarningReason: contentWarningReason,
                  includeClientTag: includeClientTag
              ) else {
            result = .invalid
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// Kind-20 picture meme from a verified upload (MST-017): web tag
    /// order (t-tags, alt, imeta, CW) → sign → receipt machine. The media
    /// MUST come from a hash-verified Blossom upload (same contract as the
    /// kind-22 path).
    /// Kind-20 twin of `mineMemeVideoPow` (same bounded-window contract).
    private func mineMemePicturePow(
        authorPubkey: String, caption: String, altText: String,
        contentWarningReason: String?, url: String, hash: String,
        size: Int, width: Int, height: Int,
        extraTagsJson: String, target: Int32
    ) async -> (nonce: Int64, idHex: String, createdAt: Int64)? {
        let minedAt = Int64(Date.now.timeIntervalSince1970)
        let chunk: Int64 = 20_000
        let cap: Int64 = 5_000_000
        var startNonce: Int64 = 0
        var attempted: Int64 = 0
        let bridge = self.bridge
        let includeClient = includeClientTag
        while attempted < cap {
            let raw = await Task.detached(priority: .userInitiated) {
                bridge.mineMemePicturePow(
                    authorPubkey: authorPubkey, caption: caption, altText: altText,
                    contentWarningReason: contentWarningReason,
                    url: url, sha256Hex: hash, mimeType: "image/png",
                    sizeBytes: Int64(size), width: Int64(width), height: Int64(height),
                    nowSeconds: minedAt, extraTagsJson: extraTagsJson,
                    includeClientTag: includeClient,
                    targetDifficulty: target, startNonce: startNonce, maxAttempts: chunk
                )
            }.value
            if let raw, let separator = raw.firstIndex(of: ":"),
               let nonce = Int64(raw[raw.startIndex..<separator]) {
                return (nonce, String(raw[raw.index(after: separator)...]), minedAt)
            }
            startNonce += chunk
            attempted += chunk
        }
        return nil
    }

    func publishMemePictureNote(
        caption: String,
        altText: String,
        contentWarningReason: String?,
        url: String, hash: String, size: Int,
        width: Int, height: Int,
        remixTagsJson: String = "",
        powBits: Int32 = 0,
        onStage: (@MainActor (MemeNoteStage) -> Void)? = nil
    ) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        var eventId: String
        var frame: String
        if powBits > 0 {
            guard let pow = await mineMemePicturePow(
                      authorPubkey: account.pubkeyHex, caption: caption, altText: altText,
                      contentWarningReason: contentWarningReason, url: url, hash: hash,
                      size: size, width: width, height: height,
                      extraTagsJson: remixTagsJson, target: powBits
                  ),
                  let signature = await identity.signLocally(pow.idHex),
                  let message = bridge.powMemePicturePublishMessage(
                      authorPubkey: account.pubkeyHex, caption: caption,
                      altText: altText, contentWarningReason: contentWarningReason,
                      url: url, sha256Hex: hash, mimeType: "image/png",
                      sizeBytes: Int64(size), width: Int64(width), height: Int64(height),
                      createdAtSeconds: pow.createdAt, nonce: pow.nonce,
                      targetDifficulty: powBits, signatureHex: signature,
                      extraTagsJson: remixTagsJson, includeClientTag: includeClientTag
                  ) else {
                result = .invalid
                return
            }
            eventId = pow.idHex
            frame = message
        } else {
            guard let plainId = bridge.composeMemePictureEventId(
                      authorPubkey: account.pubkeyHex, caption: caption,
                      altText: altText, contentWarningReason: contentWarningReason,
                      url: url, sha256Hex: hash, mimeType: "image/png",
                      sizeBytes: Int64(size), width: Int64(width), height: Int64(height),
                      nowSeconds: now, extraTagsJson: remixTagsJson,
                      includeClientTag: includeClientTag
                  ),
                  let signature = await identity.signLocally(plainId),
                  let message = bridge.memePicturePublishMessage(
                      authorPubkey: account.pubkeyHex, caption: caption,
                      altText: altText, contentWarningReason: contentWarningReason,
                      url: url, sha256Hex: hash, mimeType: "image/png",
                      sizeBytes: Int64(size), width: Int64(width), height: Int64(height),
                      createdAtSeconds: now, signatureHex: signature,
                      extraTagsJson: remixTagsJson,
                      includeClientTag: includeClientTag
                  ) else {
                result = .invalid
                return
            }
            eventId = plainId
            frame = message
        }
        onStage?(.built(eventId))
        onStage?(.signed(eventId))
        await send(eventId: eventId, frame: frame)
        onStage?(.relayed(eventId))
    }

    /// Video meme from a verified upload (MST-034): kind 22 portrait /
    /// 21 landscape through the receipt machine.
    func publishMemeVideoNote(
        caption: String,
        altText: String,
        contentWarningReason: String?,
        url: String, hash: String, size: Int,
        width: Int, height: Int, durationMs: Int64,
        thumbUrl: String? = nil,
        extraTagsJson: String = "",
        powBits: Int32 = 0,
        onStage: (@MainActor (MemeNoteStage) -> Void)? = nil
    ) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let portrait = height >= width
        let now = Int64(Date.now.timeIntervalSince1970)
        var eventId: String
        var frame: String
        if powBits > 0 {
            // Post-upload PoW (MST post-details): mine the exact kind-22/21
            // template, sign the MINED id, byte-match the publish frame.
            guard let pow = await mineMemeVideoPow(
                      authorPubkey: account.pubkeyHex, caption: caption, altText: altText,
                      contentWarningReason: contentWarningReason, portrait: portrait,
                      url: url, hash: hash, size: size, width: width, height: height,
                      durationMs: durationMs, thumbUrl: thumbUrl,
                      extraTagsJson: extraTagsJson, target: powBits
                  ),
                  let signature = await identity.signLocally(pow.idHex),
                  let message = bridge.powMemeVideoPublishMessage(
                      authorPubkey: account.pubkeyHex, caption: caption, altText: altText,
                      contentWarningReason: contentWarningReason, portrait: portrait,
                      url: url, sha256Hex: hash, mimeType: "video/mp4",
                      sizeBytes: Int64(size), width: Int64(width), height: Int64(height),
                      durationMs: durationMs, createdAtSeconds: pow.createdAt,
                      nonce: pow.nonce, targetDifficulty: powBits,
                      signatureHex: signature, thumbUrl: thumbUrl,
                      extraTagsJson: extraTagsJson, includeClientTag: includeClientTag
                  ) else {
                // Window exhausted (or invalid template) — never mine
                // forever; surface "try fewer bits".
                result = .invalid
                return
            }
            eventId = pow.idHex
            frame = message
        } else {
            guard let plainId = bridge.composeMemeVideoEventId(
                      authorPubkey: account.pubkeyHex, caption: caption,
                      altText: altText, contentWarningReason: contentWarningReason,
                      portrait: portrait,
                      url: url, sha256Hex: hash, mimeType: "video/mp4",
                      sizeBytes: Int64(size), width: Int64(width), height: Int64(height),
                      durationMs: durationMs, nowSeconds: now,
                      thumbUrl: thumbUrl,
                      extraTagsJson: extraTagsJson,
                      includeClientTag: includeClientTag
                  ),
                  let signature = await identity.signLocally(plainId),
                  let message = bridge.memeVideoPublishMessage(
                      authorPubkey: account.pubkeyHex, caption: caption,
                      altText: altText, contentWarningReason: contentWarningReason,
                      portrait: portrait,
                      url: url, sha256Hex: hash, mimeType: "video/mp4",
                      sizeBytes: Int64(size), width: Int64(width), height: Int64(height),
                      durationMs: durationMs, createdAtSeconds: now, signatureHex: signature,
                      thumbUrl: thumbUrl,
                      extraTagsJson: extraTagsJson,
                      includeClientTag: includeClientTag
                  ) else {
                result = .invalid
                return
            }
            eventId = plainId
            frame = message
        }
        onStage?(.built(eventId))
        onStage?(.signed(eventId))
        await send(eventId: eventId, frame: frame)
        onStage?(.relayed(eventId))
    }

    /// Bounded chunked mining over the exact kind-22/21 template (PowCard
    /// bounds: 20k per window, 5M hard cap), off the main actor. nil = the
    /// window was exhausted. The mined template pins one timestamp — the
    /// publish path must reuse it byte-for-byte.
    private func mineMemeVideoPow(
        authorPubkey: String, caption: String, altText: String,
        contentWarningReason: String?, portrait: Bool,
        url: String, hash: String, size: Int,
        width: Int, height: Int, durationMs: Int64,
        thumbUrl: String?, extraTagsJson: String, target: Int32
    ) async -> (nonce: Int64, idHex: String, createdAt: Int64)? {
        let minedAt = Int64(Date.now.timeIntervalSince1970)
        let chunk: Int64 = 20_000
        let cap: Int64 = 5_000_000
        var startNonce: Int64 = 0
        var attempted: Int64 = 0
        let bridge = self.bridge
        let includeClient = includeClientTag
        while attempted < cap {
            let raw = await Task.detached(priority: .userInitiated) {
                bridge.mineMemeVideoPow(
                    authorPubkey: authorPubkey, caption: caption, altText: altText,
                    contentWarningReason: contentWarningReason, portrait: portrait,
                    url: url, sha256Hex: hash, mimeType: "video/mp4",
                    sizeBytes: Int64(size), width: Int64(width), height: Int64(height),
                    durationMs: durationMs, nowSeconds: minedAt, thumbUrl: thumbUrl,
                    extraTagsJson: extraTagsJson, includeClientTag: includeClient,
                    targetDifficulty: target, startNonce: startNonce, maxAttempts: chunk
                )
            }.value
            if let raw, let separator = raw.firstIndex(of: ":"),
               let nonce = Int64(raw[raw.startIndex..<separator]) {
                return (nonce, String(raw[raw.index(after: separator)...]), minedAt)
            }
            startNonce += chunk
            attempted += chunk
        }
        return nil
    }

    /// Kind-0 profile metadata publish through the receipt machine.
    func publishProfile(name: String, displayName: String, about: String, picture: String, nip05: String, lud16: String, banner: String = "", website: String = "") async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeProfileEventId(
                  authorPubkey: account.pubkeyHex, name: name, displayName: displayName,
                  about: about, picture: picture, nip05: nip05, lud16: lud16, nowSeconds: now,
                  banner: banner, website: website
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.profilePublishMessage(
                  authorPubkey: account.pubkeyHex, name: name, displayName: displayName,
                  about: about, picture: picture, nip05: nip05, lud16: lud16,
                  createdAtSeconds: now, signatureHex: signature,
                  banner: banner, website: website
              ) else {
            result = .invalid
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    /// Kind-1984 report publish through the receipt machine (NIP-56).
    func publishReport(targetEventId: String?, targetPubkey: String, reason: String) async {
        guard result == nil, inFlightId == nil, !busy else { return }
        busy = true
        defer { busy = false }
        guard let account = identity.account else {
            result = .signingRefused
            return
        }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let eventId = bridge.composeReportEventId(
                  targetEventId: targetEventId, targetPubkey: targetPubkey,
                  authorPubkey: account.pubkeyHex, reason: reason, relayHint: "", nowSeconds: now
              ),
              let signature = await identity.signLocally(eventId),
              let frame = bridge.reportPublishMessage(
                  targetEventId: targetEventId, targetPubkey: targetPubkey,
                  authorPubkey: account.pubkeyHex, reason: reason, relayHint: "",
                  createdAtSeconds: now, signatureHex: signature
              ) else {
            result = .invalid
            return
        }
        await send(eventId: eventId, frame: frame)
    }

    private func frameFailure(for content: String, account: AccountIdentity, now: Int64) -> PublishResult {
        _ = content; _ = account; _ = now
        return .invalid
    }

    /// `["client","BitOS"]` rides bitz/media publishes when the privacy
    /// pref allows branding (web clientTag() parity); read live so a
    /// settings flip applies to the next publish without a restart.
    private var includeClientTag: Bool { privacyPrefs?.state.includeClientTag ?? false }

    private func send(eventId: String, frame: String, writeUrls: [RelayURL] = DefaultRelays.writeUrls) async {
        inFlightId = eventId
        receipts = writeUrls.map { PublishReceipt(relayHost: $0.host) }

        let stream = await pool.frames()
        let boxedBridge = StatelessBridge(bridge: bridge)
        watchTask = FrameIngest.pump(
            stream: stream,
            isAlive: { [weak self] in self != nil },
            ingest: Self.receiptIngest(boxedBridge)
        ) { [weak self] receipt in
            await self?.absorbReceipt(receipt)
        }

        await pool.broadcast(frame, to: writeUrls)

        // First acceptance anywhere completes; otherwise surface reasons on timeout.
        let deadline = ContinuousClock.now + .seconds(10)
        var sawReceipt = false
        while ContinuousClock.now < deadline {
            if receipts.contains(where: { $0.accepted == true }) { break }
            sawReceipt = sawReceipt || receipts.contains(where: { $0.accepted == false })
            try? await Task.sleep(for: .milliseconds(100))
        }
        watchTask?.cancel()

        if receipts.contains(where: { $0.accepted == true }) {
            result = .published
        } else if sawReceipt {
            result = .rejected(detail: receipts.first(where: { $0.accepted == false })?.detail)
        } else {
            result = .timeout
        }

        // Feed and Bitz rails share this publisher with the composer. Keep a
        // terminal receipt visible briefly, then release the publisher so a
        // later card action can sign and fan out to relays.
        let terminalResult = result
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1500))
            guard let self, self.inFlightId == nil, self.result == terminalResult else { return }
            self.dismiss()
        }
    }

    /// Sendable relay-OK receipt extracted off the main actor.
    private struct OkReceipt: Sendable {
        let eventId: String
        let accepted: Bool
        let detail: String?
        let relayHost: String
    }

    /// Relay OK parse — runs OFF the main actor; every frame while a publish
    /// is in flight pays three JSON scans (id/accepted/detail) that used to
    /// sit on the main actor (audit §3.1).
    private nonisolated static func receiptIngest(
        _ boxedBridge: StatelessBridge
    ) -> @Sendable (RelayFrame) -> OkReceipt? {
        { frame in
            guard let eventId = boxedBridge.bridge.okEventId(message: frame.message) as String? else { return nil }
            return OkReceipt(
                eventId: eventId,
                accepted: (boxedBridge.bridge.parseOkAccepted(message: frame.message) as? Bool) ?? false,
                detail: boxedBridge.bridge.okDetail(message: frame.message) as String?,
                relayHost: frame.relay.host
            )
        }
    }

    private func absorbReceipt(_ receipt: OkReceipt) {
        guard receipt.eventId == inFlightId else { return }
        if let index = receipts.firstIndex(where: { $0.relayHost == receipt.relayHost }) {
            receipts[index].accepted = receipt.accepted
            receipts[index].detail = receipt.detail
        }
    }

    func dismiss() {
        watchTask?.cancel()
        inFlightId = nil
        receipts = []
        result = nil
    }
}
