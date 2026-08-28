import BusinessCore
import Foundation
import Observation

/// One managed relay row for SwiftUI (value mirror of the bridge wire).
struct ManagedRelay: Equatable, Identifiable {
    let url: String
    var read: Bool
    var write: Bool
    var primary: Bool = false
    var id: String { url }
}

/**
 * APP-018 relays-manager adapter: owns the persisted managed relay set
 * (versioned `RelayListContract` wire in UserDefaults) and applies changes
 * to the live `RelayPool` — add/remove edits connect and disconnect
 * sockets on the spot. All rules (bounds, dedupe, NIP-65 projection) live
 * in business-core through the bridge; this adapter only persists, applies
 * and publishes state. Invariants mirror the Android `RelayManager`: the
 * set is never empty (a corrupt store falls back to the platform
 * defaults) and every entry keeps at least one role.
 */
@MainActor
@Observable
final class RelayManagerStore {
    static let storageKey = "relay_list_v1"
    private static let maxRelays = 16

    private(set) var relays: [ManagedRelay] = []
    /// Relay URL → live connection state (raw wire: "connected" etc.).
    private(set) var connectionStates: [String: String] = [:]

    private let defaults: UserDefaults
    private let pool: RelayPool
    private let bridge = BusinessCoreBridge()

    init(pool: RelayPool, defaults: UserDefaults? = UserDefaults(suiteName: "bitos.settings")) {
        self.pool = pool
        self.defaults = defaults ?? .standard
        relays = Self.decodeStored(defaults: self.defaults, bridge: bridge)
        // Guarantee pool state matches the loaded set regardless of how the
        // pool was constructed (idempotent actor adds).
        let urls = relays.compactMap { RelayURL.parse($0.url) }
        Task { for url in urls { await pool.add(url) } }
    }

    // MARK: - Edits (validate through the shared contract, then apply)

    /// Adds a relay (read+write by default); false on invalid/duplicate/full.
    @discardableResult
    func add(rawUrl: String) -> Bool {
        guard let canonical = bridge.relayUrlNormalize(raw: rawUrl),
              !relays.contains(where: { $0.url == canonical }),
              relays.count < Self.maxRelays else { return false }
        apply(relays + [ManagedRelay(url: canonical, read: true, write: true)])
        return true
    }

    /// Removes a relay; the last remaining relay is never removed.
    func remove(url: String) {
        let next = relays.filter { $0.url != url }
        guard !next.isEmpty else { return }
        apply(next)
    }

    /// Sets/clears the primary ⭐ (at most one; write relays only).
    func setPrimary(url: String, primary: Bool) {
        apply(relays.map { relay in
            if relay.url == url && primary && relay.write {
                ManagedRelay(url: relay.url, read: relay.read, write: relay.write, primary: true)
            } else {
                ManagedRelay(url: relay.url, read: relay.read, write: relay.write, primary: false)
            }
        })
    }

    /// Sets a relay's roles; an entry would lose both roles → no-op.
    func setRoles(url: String, read: Bool, write: Bool) {
        guard read || write else { return }
        apply(relays.map { relay in
            relay.url == url ? ManagedRelay(url: relay.url, read: read, write: write) : relay
        })
    }

    /// Wire JSON for the NIP-65 publish path (NotePublisher).
    func encode() -> String {
        bridge.relayListEncode(
            entries: relays.map { BusinessCoreBridge.RelayEntryWire(url: $0.url, read: $0.read, write: $0.write, primary: $0.primary) }
        )
    }

    /// Write-role relays (publish fan-out targets).
    func writeUrls() -> [RelayURL] {
        relays.filter(\.write)
            .sorted { $0.primary && !$1.primary }
            .compactMap { RelayURL.parse($0.url) }
    }

    /// Refreshes the live per-relay connection states (status dots).
    func refreshConnectionStates() async {
        let states = await pool.relayStates()
        connectionStates = states.reduce(into: [:]) { result, pair in
            result[pair.key.rawValue] = pair.value.rawValue
        }
    }

    /// Live connected-count (header parity with the feed health pill).
    func health() async -> RelayHealth {
        await pool.health()
    }

    // MARK: - Internals

    private func apply(_ next: [ManagedRelay]) {
        guard !next.isEmpty else { return }
        let before = Set(relays.map(\.url))
        let after = Set(next.map(\.url))
        relays = next
        defaults.set(encode(), forKey: Self.storageKey)
        let added = after.subtracting(before).compactMap { RelayURL.parse($0) }
        let removed = before.subtracting(after).compactMap { RelayURL.parse($0) }
        Task {
            for url in added { await pool.add(url) }
            for url in removed { await pool.remove(url) }
        }
    }

    /// Loads the persisted set; corrupt/empty stores fall back to defaults.
    private static func decodeStored(defaults: UserDefaults, bridge: BusinessCoreBridge) -> [ManagedRelay] {
        if let wire = defaults.string(forKey: storageKey) {
            let decoded = bridge.relayListDecode(json: wire)
            if !decoded.isEmpty {
                return decoded.map { ManagedRelay(url: $0.url, read: $0.read, write: $0.write, primary: $0.primary) }
            }
        }
        return DefaultRelays.urls.map { url in
            ManagedRelay(
                url: url.rawValue,
                read: true,
                write: DefaultRelays.writeUrls.contains(url)
            )
        }
    }
}
