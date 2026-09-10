import Foundation
import Observation

/// Relay-fetched shared sounds (plan MST-047 / wave 2): subscribes to
/// kind-30078 app-data events, keeps the newest verified event per
/// sound id whose d-tag addresses `com.bitos.bitz:sound:*`, and exposes
/// summary rows via the tested bridge seam (Android parity). The shared
/// contract drops hostile shapes and non-ingestable licenses (anything
/// but CC0 / CC-BY / CC-BY-NC). "Use sound" hands off through the
/// MST-050 Wave C/D `MemeSoundSeed(isAudioOnly)` path — hash-verified
/// download, no re-upload.
@MainActor
@Observable
final class SharedSoundStore {
    struct Row: Identifiable {
        /// The kind-30078 event id — provenance for the attach path.
        let eventId: String
        let id: String
        let label: String
        let url: String
        let sha256: String
        let license: String
        let attribution: String
        let durationMs: Int64
        let createdAt: Int64
        let authorPubkey: String
    }

    private(set) var rows: [Row] = []

    private let pool: RelayPool
    private let client = FrameworkBusinessCoreClient()
    private var framesTask: Task<Void, Never>?
    private var requested = false

    init(pool: RelayPool) {
        self.pool = pool
    }

    /// Starts collecting frames (idempotent) and issues the REQ once.
    func start() {
        guard framesTask == nil else { return }
        Task { [weak self, pool, client] in
            let stream = await pool.verifiedFrames(client: client)
            guard let self, !Task.isCancelled else { return }
            self.framesTask = FrameIngest.pump(
                gated: stream,
                isAlive: { [weak self] in self != nil },
                ingest: Self.soundFromGated(client)
            ) { [weak self] row in
                await self?.upsert(row)
            }
        }
        guard !requested else { return }
        requested = true
        Task { [pool] in
            await pool.start()
            await pool.broadcast(
                #"["REQ","bitos-sounds",{"kinds":[30078],"limit":200}]"#
            )
        }
    }

    /// NIP-50 relay search (MST-047 sheet UX): one bounded REQ carrying
    /// the `search` filter field, closing the previous search sub first.
    /// Relay support is OPTIONAL (docs: "never assume universal relay
    /// support") — the sheet's client-side filter is the primary path.
    func search(_ query: String) {
        let trimmed = String(query.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        guard trimmed.count >= 3 else { return }
        Task { [pool] in
            await pool.broadcast("[\"CLOSE\",\"bitos-sounds-search\"]")
            await pool.broadcast(
                "[\"REQ\",\"bitos-sounds-search\",{\"kinds\":[30078],\"search\":\"" +
                trimmed.replacingOccurrences(of: "\\", with: "\\\\")
                    .replacingOccurrences(of: "\"", with: "\\\"") +
                "\",\"limit\":100}]"
            )
        }
    }

    /// Sound extraction from an already-verified frame — runs OFF the
    /// main actor: d-tag filter, summary bridge call and row
    /// construction; the main actor only upserts finished rows.
    private nonisolated static func soundFromGated(
        _ client: FrameworkBusinessCoreClient
    ) -> @Sendable (GatedFrame) -> Row? {
        { gated in
            guard case .event(let gatedEvent) = gated else { return nil }
            let event = gatedEvent.event
            guard event.kind == 30078,
                  let dTag = event.tags.first(where: { $0.first == "d" })?.dropFirst().first,
                  dTag.hasPrefix("com.bitos.bitz:sound:") else { return nil }
            guard let tagsData = try? JSONSerialization.data(withJSONObject: event.tags),
                  let tagsJson = String(data: tagsData, encoding: .utf8) else { return nil }
            let summary = client.memeSharedSoundSummary(tagsJson: tagsJson, content: event.content)
            guard !summary.isEmpty,
                  let data = summary.data(using: .utf8),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let id = root["id"] as? String, !id.isEmpty,
                  let url = root["url"] as? String, !url.isEmpty,
                  let sha256 = root["sha256"] as? String, sha256.count == 64 else { return nil }
            return Row(
                eventId: event.id,
                id: id,
                label: (root["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? id,
                url: url,
                sha256: sha256,
                license: (root["license"] as? String) ?? "",
                attribution: (root["attribution"] as? String) ?? "",
                durationMs: (root["durationMs"] as? NSNumber)?.int64Value ?? 0,
                createdAt: event.createdAt,
                authorPubkey: (root["authorPubkey"] as? String) ?? ""
            )
        }
    }

    /// Newest-wins per sound id; rail-capped (24), newest-first.
    private func upsert(_ row: Row) {
        if let existing = rows.first(where: { $0.id == row.id }), existing.createdAt >= row.createdAt {
            return
        }
        rows = (rows.filter { $0.id != row.id } + [row])
            .sorted { $0.createdAt > $1.createdAt }
            .prefix(24)
            .map { $0 }
    }
}
