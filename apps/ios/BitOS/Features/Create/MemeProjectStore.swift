import BusinessCore
import CryptoKit
import Foundation
import UIKit

/// On-disk continuation store for the Quick MEM editor (plan MST-018,
/// EDT-001/002): Application Support/studio/slots/<id>/ with slot.json
/// (shared MemeSlotCodec wire), copied-in assets (CAP-005: PhotosPicker
/// items are transient — bytes are copied) and poster.jpg (~256 px, ≤192
/// KB). The index lives at studio/index.json (shared codec + LRU rules).
/// Every read is lenient: a corrupt store degrades to "no slots" and
/// never blocks creating. All IO runs on the caller's background task.
struct MemeProjectStore: Sendable {

    struct SavedSlot {
        let document: MemeSlotDocumentUi
        /// Asset files keyed by project asset id (resume loads these).
        let assetFiles: [String: URL]
        let posterURL: URL?
    }

    /// Display projection of the decoded slot document.
    struct MemeSlotDocumentUi {
        let slotId: String
        let projectJson: String
        let assets: [MemeSlotAssetUi]
        let updatedAtMs: Int64
    }

    struct MemeSlotAssetUi {
        let id: String
        let fileName: String
        let aspect: CGFloat
    }

    struct SlotEntryUi: Identifiable, Equatable {
        let slotId: String
        let updatedAtMs: Int64
        let posterName: String?
        let label: String
        var id: String { slotId }
    }

    private let root: URL

    init(root: URL? = nil) {
        if let root {
            self.root = root
        } else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            self.root = base.appendingPathComponent("studio", isDirectory: true)
        }
        try? FileManager.default.createDirectory(at: self.root, withIntermediateDirectories: true)
    }

    // MARK: - Paths

    private func slotDir(_ slotId: String) -> URL {
        root.appendingPathComponent("slots", isDirectory: true)
            .appendingPathComponent(slotId, isDirectory: true)
    }

    private var indexURL: URL { root.appendingPathComponent("index.json") }

    // MARK: - Save

    /// Persists the slot: copies session assets in (idempotent), writes
    /// the wire, regenerates the poster, updates the index (LRU evictions
    /// delete their dirs here). Assets are `(id, image)`; the images are
    /// re-encoded as PNG for stable bytes.
    @discardableResult
    func save(
        slotId: String,
        projectJson: String,
        assets: [(id: String, image: UIImage, data: Data?)],
        dataAssets: [(id: String, data: Data, fileName: String)] = [],
        nowMs: Int64
    ) -> [String] {
        let dir = slotDir(slotId)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        var assetRows: [[String: Any]] = []
        for asset in assets {
            // MST-053: animated GIF assets persist their ORIGINAL bytes —
            // PNG-encoding would freeze the sticker on its first frame.
            let file = asset.data != nil ? "asset-\(asset.id).gif" : "asset-\(asset.id).png"
            let target = dir.appendingPathComponent(file)
            if !FileManager.default.fileExists(atPath: target.path) {
                if let bytes = asset.data {
                    try? bytes.write(to: target)
                } else if let png = asset.image.pngData() {
                    try? png.write(to: target)
                }
            }
            let size = UIImage(contentsOfFile: target.path)?.size ?? CGSize(width: 1, height: 1)
            let aspect = size.height > 0 ? size.width / size.height : 1
            assetRows.append(["id": asset.id, "file": file, "aspect": aspect])
        }
        // Raw-data assets (video clips): bytes persist verbatim (V1 video).
        for asset in dataAssets {
            let target = dir.appendingPathComponent(asset.fileName)
            if !FileManager.default.fileExists(atPath: target.path) {
                try? asset.data.write(to: target)
            }
            assetRows.append(["id": asset.id, "file": asset.fileName, "aspect": 1.0])
        }

        // Slot wire via the shared codec through the bridge.
        let client = FrameworkBusinessCoreClient()
        let documentJson = buildSlotWire(
            client: client, slotId: slotId, projectJson: projectJson,
            assetRows: assetRows, nowMs: nowMs
        )
        if let documentJson {
            try? documentJson.write(toFile: dir.appendingPathComponent("slot.json").path,
                                    atomically: true, encoding: .utf8)
        }

        // Poster from the first asset (~256 px JPEG).
        if let first = assets.first, let poster = Self.posterJpeg(first.image) {
            try? poster.write(to: dir.appendingPathComponent("poster.jpg"))
        }

        // Index upsert through the shared LRU rules.
        var entries = listSlots()
        let label = labelFor(projectJson: projectJson)
        let bumped = SlotEntryUi(slotId: slotId, updatedAtMs: nowMs, posterName: "poster.jpg", label: label)
        entries.removeAll { $0.slotId == slotId }
        entries.insert(bumped, at: 0)
        let bounded = Array(entries.prefix(MemeSlotsApi.maxSlots))
        let evicted = entries.dropFirst(MemeSlotsApi.maxSlots).map(\.slotId)
        evicted.forEach(deleteSlotFiles)
        try? indexJson(for: bounded).write(toFile: indexURL.path, atomically: true, encoding: .utf8)
        return Array(evicted)
    }

    private func buildSlotWire(
        client: FrameworkBusinessCoreClient,
        slotId: String,
        projectJson: String,
        assetRows: [[String: Any]],
        nowMs: Int64
    ) -> String? {
        // The slot file is the shared MemeSlotCodec shape; compose it as
        // JSON here (project wire embedded verbatim).
        guard let projectData = projectJson.data(using: .utf8),
              let projectObject = try? JSONSerialization.jsonObject(with: projectData) else { return nil }
        let document: [String: Any] = [
            "v": 1,
            "id": slotId,
            "updatedAt": nowMs,
            "assets": assetRows,
            "project": projectObject,
        ]
        guard JSONSerialization.isValidJSONObject(document),
              let data = try? JSONSerialization.data(withJSONObject: document) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func labelFor(projectJson: String) -> String {
        // Shared rule: the first overlay text (parsed leniently, no actor
        // isolation needed — the JSON walk is pure).
        guard let data = projectJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = root["overlays"] as? [[String: Any]] else { return "Image meme" }
        let first = rows.compactMap { ($0["text"] as? String)?.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty }
        guard let first else { return "Image meme" }
        let collapsed = first.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        return collapsed.count <= 40 ? String(collapsed) : String(collapsed.prefix(39)) + "…"
    }

    private static func posterJpeg(_ image: UIImage) -> Data? {
        let longEdge = max(image.size.width, image.size.height)
        let scale = min(1, 256 / longEdge)
        let sized = scale < 1
            ? image.resized(scale: scale)
            : image
        return sized.jpegData(compressionQuality: 0.8)
    }

    // MARK: - Read

    func listSlots() -> [SlotEntryUi] {
        guard let json = try? String(contentsOf: indexURL, encoding: .utf8) else { return [] }
        return Self.decodeIndex(json)
    }

    static func decodeIndex(_ json: String) -> [SlotEntryUi] {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (root["v"] as? NSNumber)?.intValue == 1,
              let rows = root["slots"] as? [[String: Any]] else { return [] }
        let entries: [SlotEntryUi?] = rows.map { row -> SlotEntryUi? in
            guard let id = row["id"] as? String, !id.isEmpty else { return nil }
            var poster: String?
            if let raw = row["poster"] as? String,
               !raw.isEmpty, !raw.hasPrefix("/"), !raw.contains("..") {
                poster = raw
            }
            let labelRaw = (row["label"] as? String) ?? ""
            let label = labelRaw.isEmpty ? "Untitled meme" : labelRaw
            return SlotEntryUi(
                slotId: id,
                updatedAtMs: (row["updatedAt"] as? NSNumber)?.int64Value ?? 0,
                posterName: poster,
                label: label
            )
        }
        return entries.compactMap { $0 }
    }

    private func indexJson(for entries: [SlotEntryUi]) -> String {
        let rows: [[String: Any]] = entries.map { entry in
            var row: [String: Any] = [
                "id": entry.slotId,
                "updatedAt": entry.updatedAtMs,
                "label": entry.label,
            ]
            if let poster = entry.posterName { row["poster"] = poster }
            return row
        }
        guard let data = try? JSONSerialization.data(withJSONObject: ["v": 1, "slots": rows]) else {
            return "{\"v\":1,\"slots\":[]}"
        }
        return String(data: data, encoding: .utf8) ?? "{\"v\":1,\"slots\":[]}"
    }

    func loadSlot(_ slotId: String) -> SavedSlot? {
        let wireURL = slotDir(slotId).appendingPathComponent("slot.json")
        guard let wire = try? String(contentsOf: wireURL, encoding: .utf8),
              let data = wire.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (root["v"] as? NSNumber)?.intValue == 1,
              let id = root["id"] as? String, !id.isEmpty else { return nil }

        guard let projectObject = root["project"],
              let projectData = try? JSONSerialization.data(withJSONObject: projectObject),
              let projectJson = String(data: projectData, encoding: .utf8) else { return nil }

        var assetFiles: [String: URL] = [:]
        var assetUis: [MemeSlotAssetUi] = []
        for raw in (root["assets"] as? [[String: Any]]) ?? [] {
            guard let assetId = raw["id"] as? String, !assetId.isEmpty,
                  let file = raw["file"] as? String,
                  !file.isEmpty, !file.hasPrefix("/"), !file.contains("..") else { continue }
            let url = slotDir(slotId).appendingPathComponent(file)
            assetFiles[assetId] = url
            assetUis.append(
                MemeSlotAssetUi(
                    id: assetId,
                    fileName: file,
                    aspect: CGFloat((raw["aspect"] as? NSNumber)?.floatValue ?? 1)
                )
            )
        }
        let posterURL = slotDir(slotId).appendingPathComponent("poster.jpg")
        let poster = FileManager.default.fileExists(atPath: posterURL.path) ? posterURL : nil
        return SavedSlot(
            document: MemeSlotDocumentUi(
                slotId: id,
                projectJson: projectJson,
                assets: assetUis,
                updatedAtMs: (root["updatedAt"] as? NSNumber)?.int64Value ?? 0
            ),
            assetFiles: assetFiles,
            posterURL: poster
        )
    }

    func posterURL(_ slotId: String) -> URL? {
        let url = slotDir(slotId).appendingPathComponent("poster.jpg")
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    // MARK: - Delete

    func deleteSlot(_ slotId: String) {
        deleteSlotFiles(slotId)
        var entries = listSlots()
        entries.removeAll { $0.slotId == slotId }
        try? indexJson(for: entries).write(toFile: indexURL.path, atomically: true, encoding: .utf8)
    }

    private func deleteSlotFiles(_ slotId: String) {
        try? FileManager.default.removeItem(at: slotDir(slotId))
    }
}

/// Shared constants the UI needs (kept in one place next to the store).
enum MemeSlotsApi {
    static let maxSlots = 6
    static let autosaveDebounceMs: UInt64 = 500
}

private extension UIImage {
    func resized(scale: CGFloat) -> UIImage {
        let size = CGSize(width: self.size.width * scale, height: self.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
