import BusinessCore
import Foundation
import Observation

/// APP-011 DM store (iOS mirror through the bridge).
@MainActor
@Observable
final class DmStore {
    private(set) var conversations: [DmConversationMirror] = []
    private(set) var hasAccount = false
    private(set) var loaded = false
    var openPeerPubkey: String?

    private let pool: RelayPool
    private let bridge: BusinessCoreBridge
    private let secretProvider: () async -> String?
    private var accountPubkey: String?
    private var watchTask: Task<Void, Never>?
    private var requested = false

    init(pool: RelayPool, bridge: BusinessCoreBridge = BusinessCoreBridge(), secretProvider: @escaping () async -> String?) {
        self.pool = pool
        self.bridge = bridge
        self.secretProvider = secretProvider
    }

    func setAccount(_ pubkey: String?) {
        guard accountPubkey != pubkey else { return }
        accountPubkey = pubkey
        requested = false
        conversations = []
        hasAccount = pubkey != nil
        loaded = false
        watchTask?.cancel()
        watchTask = nil
        guard pubkey != nil else { return }
        Task { await start() }
    }

    func openConversation(_ peerPubkey: String?) {
        openPeerPubkey = peerPubkey
    }

    func sendMessage(recipientPubkey: String, content: String) async -> Bool {
        guard let secret = await secretProvider(), let account = accountPubkey else { return false }
        let now = Int64(Date.now.timeIntervalSince1970)
        guard let outgoing = wrapMessage(secret, recipientPubkey, content, now) else { return false }
        publishWrapEvent(outgoing.wrapEvent)
        if let selfWrap = wrapMessage(secret, account, content, now) {
            publishWrapEvent(selfWrap.wrapEvent)
        }
        appendMessage(id: outgoing.rumorId, author: outgoing.rumorPubkey, peer: recipientPubkey, content: outgoing.rumorContent, createdAt: outgoing.rumorCreatedAt)
        return true
    }

    // MARK: - Internals

    private struct WrapResult {
        let rumorId: String
        let rumorPubkey: String
        let rumorContent: String
        let rumorCreatedAt: Int64
        let wrapEvent: BusinessCoreBridge.Event
    }

    private func wrapMessage(_ secret: String, _ recipient: String, _ content: String, _ now: Int64) -> WrapResult? {
        guard let result = bridge.secureDmWrapResult(
            senderPrivateKeyHex: secret,
            recipientPubkey: recipient,
            content: content,
            nowSeconds: now
        ) as? [String: Any] else { return nil }
        guard let rumorId = result["rumorId"] as? String,
              let rumorPubkey = result["rumorPubkey"] as? String,
              let rumorContent = result["rumorContent"] as? String,
              let rumorCreatedAt = (result["rumorCreatedAt"] as? NSNumber)?.int64Value,
              let wrapId = result["wrapId"] as? String,
              let wrapPubkey = result["wrapPubkey"] as? String,
              let wrapCreatedAt = (result["wrapCreatedAt"] as? NSNumber)?.int64Value,
              let wrapKind = (result["wrapKind"] as? NSNumber)?.int32Value,
              let wrapTags = result["wrapTags"] as? [[String]],
              let wrapContent = result["wrapContent"] as? String,
              let wrapSig = result["wrapSig"] as? String else { return nil }
        let event = BusinessCoreBridge.Event(
            id: wrapId, pubkey: wrapPubkey, createdAt: wrapCreatedAt,
            kind: wrapKind, tags: wrapTags, content: wrapContent,
            relayUrl: nil, signature: wrapSig
        )
        return WrapResult(
            rumorId: rumorId, rumorPubkey: rumorPubkey,
            rumorContent: rumorContent, rumorCreatedAt: rumorCreatedAt,
            wrapEvent: event
        )
    }

    private func publishWrapEvent(_ event: BusinessCoreBridge.Event) {
        if let frame = bridge.secureDmPublishMessage(wrap: event) {
            Task { await pool.broadcast(frame) }
        }
    }

    private func start() async {
        guard watchTask == nil, let account = accountPubkey else { return }
        await pool.start()
        if !requested {
            requested = true
            if let request = bridge.secureDmRequest(subscriptionId: "bitos-dms", accountPubkey: account) as String? {
                Task { await pool.broadcast(request) }
            }
        }
        let stream = await pool.frames()
        watchTask = Task { [weak self] in
            for await frame in stream {
                guard let self, !Task.isCancelled else { return }
                self.absorb(frame)
            }
        }
    }

    private var cachedSecret: String?
    private func secretProviderSync() -> String {
        if let cachedSecret { return cachedSecret }
        // Synchronous fallback — the async provider runs at start; the cached
        // value is refreshed there. This is only used for unwrap in the stream.
        cachedSecret = IdentityKeychain.loadSecret()
        return cachedSecret ?? ""
    }

    private func absorb(_ frame: RelayFrame) {
        guard let account = accountPubkey else { return }
        guard let map = bridge.secureDmUnwrap(message: frame.message, relayUrl: frame.relay.rawValue, myPrivateKeyHex: secretProviderSync()) as? [String: Any] else { return }
        // Map: {id, author, peer, content, createdAt}
        guard let id = map["id"] as? String,
              let author = map["author"] as? String,
              let content = map["content"] as? String,
              let createdAt = (map["createdAt"] as? NSNumber)?.int64Value else { return }
        let peer = (map["peer"] as? String) ?? account
        appendMessage(id: id, author: author, peer: peer, content: content, createdAt: createdAt)
    }

    private func appendMessage(id: String, author: String, peer: String, content: String, createdAt: Int64) {
        if conversations.contains(where: { conv in conv.messages.contains { $0.id == id } }) { return }
        let message = DmMessageMirror(id: id, authorPubkey: author, peerPubkey: peer, content: content, createdAt: createdAt)
        if let index = conversations.firstIndex(where: { $0.peerPubkey == peer }) {
            var conv = conversations[index]
            conv.messages.append(message)
            conv.messages.sort { $0.createdAt < $1.createdAt }
            conversations[index] = conv
        } else {
            conversations.append(DmConversationMirror(peerPubkey: peer, messages: [message]))
        }
        conversations.sort { $0.lastAt > $1.lastAt }
        if conversations.count > 32 { conversations = Array(conversations.prefix(32)) }
        loaded = true
    }
}

struct DmMessageMirror: Identifiable, Equatable, Sendable {
    let id: String
    let authorPubkey: String
    let peerPubkey: String
    let content: String
    let createdAt: Int64
}

struct DmConversationMirror: Identifiable, Equatable, Sendable {
    let peerPubkey: String
    var messages: [DmMessageMirror]
    var id: String { peerPubkey }
    var lastAt: Int64 { messages.last?.createdAt ?? 0 }
}
