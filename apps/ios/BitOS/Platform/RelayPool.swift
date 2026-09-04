import Foundation

/// Connection state of a single relay socket.
enum RelayConnectionState: String, Sendable {
    case connecting
    case connected
    case disconnected
}

/// One relay frame received on an open socket.
struct RelayFrame: Sendable {
    let relay: RelayURL
    let message: String
}

/// Read-only health snapshot for UI presentation.
struct RelayHealth: Sendable, Equatable {
    let connected: Int
    let total: Int

    var isLive: Bool { connected > 0 }
}

/**
 * Relay pool (REL-001): one actor owning every relay websocket, its receive
 * loop, reconnect backoff with jitter and the merged frame stream. Views and
 * feature stores never touch URLSession directly.
 */
actor RelayPool {
    private final class Connection {
        let url: RelayURL
        var socket: URLSessionWebSocketTask?
        var receiveTask: Task<Void, Never>?
        var pingTask: Task<Void, Never>?
        var state: RelayConnectionState = .disconnected
        var attempts = 0

        init(url: RelayURL) {
            self.url = url
        }
    }

    private let urlSession: URLSession
    private var connections: [RelayURL: Connection] = [:]
    private var relayOrder: [RelayURL] = []
    private var continuations: [UUID: AsyncStream<RelayFrame>.Continuation] = [:]
    private var gatedContinuations: [UUID: AsyncStream<GatedFrame>.Continuation] = [:]
    private var gatedClient: (any BusinessCoreClient)?
    private var running = false

    init(urls: [RelayURL], urlSession: URLSession = .bitOSRelaySession()) {
        for url in urls {
            connections[url] = Connection(url: url)
            relayOrder.append(url)
        }
        self.urlSession = urlSession
    }

    /// Stream of relay frames; each caller receives its own stream.
    func frames() -> AsyncStream<RelayFrame> {
        let id = UUID()
        return AsyncStream { continuation in
            continuations[id] = continuation
            continuation.onTermination = { [weak self] _ in
                Task { await self?.removeContinuation(id) }
            }
        }
    }

    private func removeContinuation(_ id: UUID) {
        continuations.removeValue(forKey: id)
    }

    /// One shared decode stage (performance audit Phase 2): frames are
    /// decoded + ID-hash-checked + BIP-340-verified ONCE per process in the
    /// emit path, and every store consumes this stream instead of running
    /// its own gate — one JSON parse per frame instead of one per store.
    /// Callers pass the process-wide client; conformers are stateless, so
    /// the first registration's client decodes for everyone.
    func verifiedFrames(client: any BusinessCoreClient) -> AsyncStream<GatedFrame> {
        if gatedClient == nil { gatedClient = client }
        let id = UUID()
        return AsyncStream { continuation in
            gatedContinuations[id] = continuation
            continuation.onTermination = { [weak self] _ in
                Task { await self?.removeGatedContinuation(id) }
            }
        }
    }

    private func removeGatedContinuation(_ id: UUID) {
        gatedContinuations.removeValue(forKey: id)
    }

    func start() {
        guard !running else { return }
        running = true
        for url in connections.keys {
            connect(url)
        }
    }

    /// Adds a relay to the pool (relays manager): installs the connection
    /// and connects immediately when the pool is running. Idempotent.
    func add(_ url: RelayURL) {
        guard connections[url] == nil else { return }
        connections[url] = Connection(url: url)
        relayOrder.append(url)
        if running { connect(url) }
    }

    /// Removes a relay: cancels its receive loop + socket, drops state.
    func remove(_ url: RelayURL) {
        guard let connection = connections.removeValue(forKey: url) else { return }
        relayOrder.removeAll { $0 == url }
        connection.receiveTask?.cancel()
        connection.pingTask?.cancel()
        connection.socket?.cancel(with: .normalClosure, reason: nil)
        connection.state = .disconnected
    }

    /// Per-relay connection states (status dots in the relays manager).
    func relayStates() -> [RelayURL: RelayConnectionState] {
        connections.mapValues(\.state)
    }

    func shutdown() {
        running = false
        for connection in connections.values {
            connection.receiveTask?.cancel()
            connection.pingTask?.cancel()
            connection.socket?.cancel(with: .normalClosure, reason: nil)
            connection.state = .disconnected
        }
        for continuation in continuations.values {
            continuation.finish()
        }
        continuations.removeAll()
    }

    func broadcast(_ message: String) {
        for url in connections.keys {
            send(message, to: url)
        }
    }

    /** Targeted send: only relays in [urls] receive the message (write-role routing). */
    func broadcast(_ message: String, to urls: [RelayURL]) {
        for url in urls {
            send(message, to: url)
        }
    }

    /// First configured connected relay for latency-sensitive read requests.
    func primaryRelay() -> RelayURL? {
        relayOrder.first { connections[$0]?.state == .connected }
    }

    /// Configured relays other than `primary`, in their pool order.
    func fallbackRelays(excluding primary: RelayURL?) -> [RelayURL] {
        relayOrder.filter { $0 != primary && connections[$0] != nil }
    }

    /// Connected read relays at request time, used to aggregate EOSE safely.
    func connectedRelays() -> Set<RelayURL> {
        Set(relayOrder.filter { connections[$0]?.state == .connected })
    }

    func health() -> RelayHealth {
        let states = connections.values.map(\.state)
        return RelayHealth(
            connected: states.filter { $0 == .connected }.count,
            total: states.count
        )
    }

    // MARK: - Internals

    private func connect(_ url: RelayURL) {
        guard let connection = connections[url] else { return }
        guard connection.state != .connected && connection.state != .connecting else { return }

        connection.state = .connecting
        var request = URLRequest(url: URL(string: url.rawValue)!)
        request.timeoutInterval = 12
        let socket = urlSession.webSocketTask(with: request)
        connection.socket = socket
        socket.resume()
        connection.receiveTask = Task {
            await receiveLoop(on: socket, url: url)
        }
        startKeepAlive(for: url)
    }

    // MARK: - Keepalive (performance-audit R9)

    /// Client-initiated WebSocket pings. URLSession ANSWERS relay pings but
    /// never sends its own, so an idle socket is dropped silently by relays
    /// and the feed waits on reconnect backoff — the classic "slow feed
    /// after backgrounding". A 25 s cadence matches the Android OkHttp
    /// transport; a failed ping forces the socket through the normal
    /// disconnect path (capped backoff + reconnect), never a zombie socket.
    private func startKeepAlive(for url: RelayURL) {
        guard let connection = connections[url] else { return }
        connection.pingTask?.cancel()
        connection.pingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.keepAliveInterval)
                guard !Task.isCancelled else { return }
                await self?.sendKeepAlivePing(to: url)
            }
        }
    }

    private func sendKeepAlivePing(to url: RelayURL) {
        connections[url]?.socket?.sendPing { [weak self] error in
            guard let error else { return }
            // Dead socket: cancel it so the blocked receive completes with
            // an error and the receive loop runs the reconnect policy.
            Task { await self?.forceDisconnect(url, cause: error) }
        }
    }

    private func forceDisconnect(_ url: RelayURL, cause _: Error) {
        guard let connection = connections[url],
              connection.state == .connected || connection.state == .connecting else { return }
        connection.socket?.cancel(with: .goingAway, reason: nil)
    }

    /// Client keepalive cadence (matches OkHttp's 25 s ping interval).
    nonisolated static let keepAliveInterval: Duration = .seconds(25)

    private func send(_ message: String, to url: RelayURL) {
        connections[url]?.socket?.send(.string(message)) { [weak self] _ in
            // Send failures surface through the receive loop disconnect path.
            _ = self
        }
    }

    private func receiveLoop(on socket: URLSessionWebSocketTask, url: RelayURL) async {
        while !Task.isCancelled {
            do {
                let message = try await socket.receive()
                guard case .string(let text) = message else { continue }
                connections[url]?.state = .connected
                connections[url]?.attempts = 0
                emit(RelayFrame(relay: url, message: text))
            } catch {
                guard !Task.isCancelled else { return }
                handleDisconnect(url)
                return
            }
        }
    }

    private func handleDisconnect(_ url: RelayURL) {
        guard let connection = connections[url] else { return }
        connection.state = .disconnected
        connection.socket = nil
        connection.receiveTask?.cancel()
        connection.receiveTask = nil
        connection.pingTask?.cancel()
        connection.pingTask = nil

        guard running else { return }
        connection.attempts += 1
        let delay = Self.reconnectDelay(attempt: connection.attempts)
        Task {
            try? await Task.sleep(for: .milliseconds(delay))
            guard running, !Task.isCancelled else { return }
            connect(url)
        }
    }

    private func emit(_ frame: RelayFrame) {
        for continuation in continuations.values {
            continuation.yield(frame)
        }
        // Decode-once fan-out (audit Phase 2): with no verified-stream
        // registrations there is no decode cost at all.
        guard !gatedContinuations.isEmpty, let client = gatedClient else { return }
        guard let gated = FrameIngest.gate(frame, client: client) else { return }
        for continuation in gatedContinuations.values {
            continuation.yield(gated)
        }
    }

    /// Capped exponential backoff with full jitter.
    nonisolated static func reconnectDelay(attempt: Int) -> Int64 {
        let capped = Int64(1_000) << Int64(min(attempt, 5))
        return Int64.random(in: 0...capped)
    }
}

extension URLSession {
    /// Dedicated session configuration for relay sockets.
    static func bitOSRelaySession() -> URLSession {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 12
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }
}

/// Default public read relays, mirroring the web client's discovery set.
enum DefaultRelays {
    static let urls: [RelayURL] = [
        "wss://nostr-01.yakihonne.com",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
    ].compactMap(RelayURL.parse)

    /// Write-capable subset (nostr.band is read-only in the web client).
    static let writeUrls: [RelayURL] = [
        "wss://nostr-01.yakihonne.com",
        "wss://relay.damus.io",
        "wss://nos.lol",
    ].compactMap(RelayURL.parse)

    static let writeHosts: [String] = writeUrls.map(\.host)
}
