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
    }

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
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
    ].compactMap(RelayURL.parse)

    /// Write-capable subset (nostr.band is read-only in the web client).
    static let writeUrls: [RelayURL] = [
        "wss://relay.damus.io",
        "wss://nos.lol",
    ].compactMap(RelayURL.parse)

    static let writeHosts: [String] = writeUrls.map(\.host)
}
