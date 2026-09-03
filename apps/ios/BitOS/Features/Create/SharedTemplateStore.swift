import Foundation
import Observation

/// Relay-fetched shared templates (plan MST-045 / wave 3): subscribes to
/// kind-30078 app-data events, keeps the newest verified event per
/// template id whose d-tag addresses `com.bitos.bitz:template:*`, and
/// exposes summary rows via the tested bridge seam (raw tags+content ride
/// along so Apply re-parses through the shared contract — Android parity).
@MainActor
@Observable
final class SharedTemplateStore {
    struct Row: Identifiable {
        let id: String
        let label: String
        let emoji: String
        let priceSats: Int64
        let category: String
        let createdAt: Int64
        let tagsJson: String
        let content: String

        /// Bolt glyph comes from the Solar zap icon at the render site.
        var priceLabel: String? { priceSats > 0 ? "\(priceSats) sats" : nil }
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
            let stream = await pool.frames()
            guard let self, !Task.isCancelled else { return }
            self.framesTask = FrameIngest.pump(
                stream: stream,
                isAlive: { [weak self] in self != nil },
                ingest: Self.templateIngest(client)
            ) { [weak self] row in
                await self?.upsert(row)
            }
        }
        guard !requested else { return }
        requested = true
        Task { [pool] in
            await pool.start()
            await pool.broadcast(
                #"["REQ","bitos-templates",{"kinds":[30078],"limit":200}]"#
            )
        }
    }

    /// Protocol gate + template extraction in one off-main step: decode,
    /// d-tag filter, summary bridge call and row construction all run on the
    /// ingest task; the main actor only upserts finished rows.
    private nonisolated static func templateIngest(
        _ client: FrameworkBusinessCoreClient
    ) -> @Sendable (RelayFrame) -> Row? {
        { frame in
            guard let event = client.decodeVerifiedEvent(message: frame.message, relay: frame.relay.rawValue),
                  event.kind == 30078,
                  let dTag = event.tags.first(where: { $0.first == "d" })?.dropFirst().first,
                  dTag.hasPrefix("com.bitos.bitz:template:") else { return nil }
            guard let tagsData = try? JSONSerialization.data(withJSONObject: event.tags),
                  let tagsJson = String(data: tagsData, encoding: .utf8) else { return nil }
            let summary = client.memeSharedTemplateSummary(tagsJson: tagsJson, content: event.content)
            guard !summary.isEmpty,
                  let data = summary.data(using: .utf8),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let id = root["id"] as? String, !id.isEmpty else { return nil }
            return Row(
                id: id,
                label: (root["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? id,
                emoji: (root["emoji"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "🖼",
                priceSats: (root["priceSats"] as? NSNumber)?.int64Value ?? 0,
                category: (root["category"] as? String) ?? "meme",
                createdAt: event.createdAt,
                tagsJson: tagsJson,
                content: event.content
            )
        }
    }

    /// Newest-wins per template id; rail-capped (24), newest-first.
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
