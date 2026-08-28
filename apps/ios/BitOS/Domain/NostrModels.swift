import Foundation

/// Validated relay websocket URL for the native transport layer. The relay
/// pool is platform-owned (URLSession sockets); protocol-level URL rules
/// live once in the shared core and the bridge validates relay inputs
/// before decoding events.
struct RelayURL: Hashable, Sendable {
    let rawValue: String

    var host: String {
        rawValue
            .replacingOccurrences(of: "wss://", with: "")
            .replacingOccurrences(of: "ws://", with: "")
            .split(separator: "/").first.map(String.init) ?? rawValue
    }

    static func parse(_ raw: String) -> RelayURL? {
        let candidate = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (8...512).contains(candidate.count) else { return nil }
        guard let url = URL(string: candidate), let scheme = url.scheme?.lowercased(),
              scheme == "wss" || scheme == "ws",
              url.host() != nil else { return nil }
        let normalized = candidate.hasSuffix("/")
            ? String(candidate.dropLast()) : candidate
        return RelayURL(rawValue: normalized)
    }
}
