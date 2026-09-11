import Foundation
import Observation

/// Local shared-sound library persistence (MST-047 W4b, plan §3.5):
/// Application Support/studio/sounds/index.json (the shared,
/// common-tested codec through the bridge seam — LRU, ≤ 30 entries)
/// plus one `<id>.bin` per saved artifact. Save is gated by the shared
/// `ingestCheck` (≤ 15 s, ≤ 8 MB); re-attach verifies the bytes against
/// the entry sha before anything mixes — same rule as relay downloads
/// (Android parity).
@MainActor
@Observable
final class SharedSoundLibraryStore {
    struct Entry: Identifiable, Equatable {
        let id: String
        let label: String
        let url: String
        let sha256: String
        let license: String
        let durationMs: Int64
        let savedAtMs: Int64
        let sourceEventId: String
        let authorPubkey: String
    }

    private(set) var entries: [Entry] = []

    private let root: URL
    private let client = FrameworkBusinessCoreClient()

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("studio")
        root = base.appendingPathComponent("sounds")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        load()
    }

    /// Index read through the tolerant shared codec (junk → empty).
    func load() {
        let indexUrl = root.appendingPathComponent("index.json")
        guard let json = try? String(contentsOf: indexUrl, encoding: .utf8) else { return }
        let canonical = client.memeSharedSoundLibraryDecode(json: json)
        guard !canonical.isEmpty,
              let data = canonical.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = root["sounds"] as? [[String: Any]] else { return }
        entries = rows.compactMap { row in
            guard let id = row["id"] as? String, !id.isEmpty,
                  let sha = row["sha256"] as? String, sha.count == 64,
                  let duration = row["durationMs"] as? NSNumber else { return nil }
            return Entry(
                id: id,
                label: (row["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? id,
                url: (row["url"] as? String) ?? "",
                sha256: sha,
                license: (row["license"] as? String) ?? "",
                durationMs: duration.int64Value,
                savedAtMs: (row["savedAtMs"] as? NSNumber)?.int64Value ?? 0,
                sourceEventId: (row["src"] as? String) ?? "",
                authorPubkey: (row["author"] as? String) ?? ""
            )
        }
    }

    /// Saved bytes for one entry; nil when the artifact file is gone
    /// (callers degrade to re-download by URL, if any).
    func bytes(id: String) -> Data? {
        guard isSafeId(id) else { return nil }
        let url = root.appendingPathComponent("\(id).bin")
        return try? Data(contentsOf: url)
    }

    /// Adds (or replaces by id) one sound with its audio. Returns false —
    /// leaving the index untouched — when the shared ingest gate or the
    /// path-safety check rejects it.
    @discardableResult
    func save(
        id: String,
        label: String,
        url: String,
        sha256: String,
        license: String,
        durationMs: Int64,
        sourceEventId: String = "",
        authorPubkey: String = "",
        audio: Data
    ) -> Bool {
        guard isSafeId(id),
              client.memeSharedSoundIngestCheck(
                  decodedDurationMs: durationMs, byteCount: Int64(audio.count)
              ) else { return false }
        let entryJson: String
        let payload: [String: Any] = [
            "id": id, "label": label, "url": url, "sha256": sha256,
            "license": license, "durationMs": durationMs,
            "savedAtMs": Int64(Date.now.timeIntervalSince1970 * 1000),
            "src": sourceEventId, "author": authorPubkey,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let json = String(data: data, encoding: .utf8) else { return false }
        entryJson = json
        let currentIndex = (try? String(
            contentsOf: root.appendingPathComponent("index.json"), encoding: .utf8
        )) ?? ""
        let next = client.memeSharedSoundLibraryAdd(json: currentIndex, entryJson: entryJson)
        let beforeIds = Set(entries.map(\.id))
        do {
            try audio.write(to: root.appendingPathComponent("\(id).bin"))
            try next.write(
                to: root.appendingPathComponent("index.json"),
                atomically: true, encoding: .utf8
            )
        } catch {
            return false
        }
        load()
        // LRU eviction must delete the dropped artifact files too.
        let afterIds = Set(entries.map(\.id))
        beforeIds.subtracting(afterIds).forEach { evicted in
            guard isSafeId(evicted) else { return }
            try? FileManager.default.removeItem(at: root.appendingPathComponent("\(evicted).bin"))
        }
        return true
    }

    func remove(id: String) {
        guard isSafeId(id) else { return }
        let kept = entries.filter { $0.id != id }
        let index = SharedSoundLibraryStore.encodeIndex(kept)
        try? index.write(to: root.appendingPathComponent("index.json"), atomically: true, encoding: .utf8)
        try? FileManager.default.removeItem(at: root.appendingPathComponent("\(id).bin"))
        load()
    }

    private func isSafeId(_ id: String) -> Bool {
        !id.isEmpty && id.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" || $0 == "." }
    }

    /// Direct canonical encode for a row list (the shared codec's shape).
    private static func encodeIndex(_ entries: [Entry]) -> String {
        let rows: [[String: Any]] = entries.map {
            [
                "id": $0.id, "label": $0.label, "url": $0.url, "sha256": $0.sha256,
                "license": $0.license, "durationMs": $0.durationMs,
                "savedAtMs": $0.savedAtMs, "src": $0.sourceEventId, "author": $0.authorPubkey,
            ]
        }
        let payload: [String: Any] = ["v": 1, "sounds": rows]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let json = String(data: data, encoding: .utf8) else { return "{\"v\":1,\"sounds\":[]}" }
        return json
    }
}
