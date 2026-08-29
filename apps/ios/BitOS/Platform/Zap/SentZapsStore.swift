import BusinessCore
import Foundation
import Observation

/**
 * APP-014 sent-zap ledger adapter (iOS): the versioned shared wire
 * (`SentZapLedger` through the bridge) in UserDefaults. Sent zaps are
 * local-only records — the 9735 is published by the recipient's provider
 * and can never be observed as `author = me`.
 */
@MainActor
@Observable
final class SentZapsStore {
    private(set) var records: [SentZapRecord] = []
    private let bridge: BusinessCoreBridge
    private let defaults: UserDefaults
    private static let wireKey = "bitos_sent_zaps"

    struct SentZapRecord: Identifiable, Equatable, Sendable {
        let id: String
        let amountSats: Int64
        let recipientPubkey: String
        let createdAt: Int64
        let targetNoteId: String?
        let memo: String?
    }

    init(bridge: BusinessCoreBridge = BusinessCoreBridge(), defaults: UserDefaults = .standard) {
        self.bridge = bridge
        self.defaults = defaults
        load()
    }

    private func load() {
        guard let wire = defaults.string(forKey: Self.wireKey),
              let json = (bridge.sentZapsDecode(wire: wire) as String?),
              let data = json.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            records = []
            return
        }
        records = array.compactMap { obj in
            guard let id = obj["id"] as? String,
                  let sats = (obj["sats"] as? NSNumber)?.int64Value,
                  let to = obj["to"] as? String,
                  let at = (obj["at"] as? NSNumber)?.int64Value else { return nil }
            return SentZapRecord(
                id: id, amountSats: sats, recipientPubkey: to, createdAt: at,
                targetNoteId: (obj["note"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                memo: (obj["memo"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            )
        }
    }

    /// Insert rule lives in the shared contract (dedupe/newest/bounded) —
    /// the adapter round-trips through the bridge wire.
    func record(_ record: SentZapRecord) {
        var next = records.filter { $0.id != record.id }
        next.insert(record, at: 0)
        next.sort { $0.createdAt > $1.createdAt }
        records = Array(next.prefix(200))
        persist()
    }

    private func persist() {
        let array = records.map { record -> [String: Any] in
            var obj: [String: Any] = [
                "id": record.id, "sats": record.amountSats,
                "to": record.recipientPubkey, "at": record.createdAt,
            ]
            if let note = record.targetNoteId { obj["note"] = note }
            if let memo = record.memo { obj["memo"] = memo }
            return obj
        }
        guard let data = try? JSONSerialization.data(withJSONObject: array),
              let json = String(data: data, encoding: .utf8) else { return }
        defaults.set(bridge.sentZapsEncode(recordsJson: json), forKey: Self.wireKey)
    }
}
