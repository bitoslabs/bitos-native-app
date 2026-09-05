import SwiftUI
import PhotosUI
import Photos
import BusinessCore

/// Quick MEM image editor (plan MST-010..015; wave 1 of the M1 execution
/// note in `docs/native/meme-studio-plan.md`). Layout per the app-15
/// `scr-quick` mockup: chrome (✕ · mode chips · undo) → stage (media +
/// overlays, drag/pinch/twist) → media tray → text/sticker tools.
///
/// All edits go through the shared-core seams on `BusinessCoreClient`
/// (`memeApplyCommand` etc.), so clamping, caps, hit-testing and bounds
/// stay single-sourced with Android. Export (MST-016) and publish
/// (MST-017) arrive in later waves — the editor is session-only until
/// MST-018 lands the project store.

/// Display projection of one overlay row on the project wire.
struct MemeOverlayUi: Identifiable, Equatable {
    let id: String
    let isSticker: Bool
    let text: String
    let font: String
    let size: Int
    let colorIndex: Int
    let outline: Int
    let shadow: Bool
    let x: Float
    let y: Float
    let scale: Float
    let rot: Float
    var fx: String? = nil
    var startMs: Int64? = nil
    var endMs: Int64? = nil
    /// IMAGE layers (video-mode source insert): paint this asset instead of text.
    var isImage: Bool = false
    var assetId: String? = nil
}

/// One imported session asset (image ref + decoded aspect).
struct MemeEditorAsset: Identifiable, Equatable {
    let id: String
    let image: UIImage
    let aspect: CGFloat
}

/// One pen stroke (V2 Draw chip): normalized polyline under the overlays.
struct MemeStrokeUi: Identifiable, Equatable {
    let id: String
    let colorIndex: Int
    let widthNorm: Float
    let points: [Float]
}

@MainActor
@Observable
final class MemeEditorStore {
    private let client: any BusinessCoreClient

    /// Canonical project wire — the single source of truth.
    private(set) var projectJson: String
    private(set) var overlays: [MemeOverlayUi] = []
    private(set) var selectedId: String?
    private(set) var canUndo = false
    private(set) var canRedo = false
    private(set) var packs: [MemeStickerPack] = []
    private(set) var recents: [String] = []
    private(set) var exportState: MemeExportState = .idle

    struct MemeStickerPack: Identifiable, Equatable {
        let id: String
        let label: String
        let stickers: [String]
    }

    /// Hex palette rows from the shared rule (index-ordered).
    private(set) var paletteHex: [String] = []

    /// Undo history = pre-step project wires (bounded). Redo (V2 suite
    /// dock) holds the post-undo wires; any new edit clears the branch.
    private var history: [String] = []
    private var redoHistory: [String] = []
    private var gestureSnapshot: String?

    var isEmpty: Bool {
        overlays.isEmpty && assets.isEmpty && gifFrames.isEmpty && videoClipData == nil
    }
    var assets: [MemeEditorAsset] = []
    var activeAssetId: String?

    var activeAsset: MemeEditorAsset? {
        assets.first { $0.id == activeAssetId }
    }

    var canAddOverlay: Bool { overlays.count < Self.maxOverlays }

    private static let maxOverlays = 48
    private static let maxHistory = 60
    private static let maxRecentStickers = 16

    init(client: any BusinessCoreClient = FrameworkBusinessCoreClient()) {
        self.client = client
        // An empty image-mode project; the wire normalizes through the seam.
        let empty = #"{"v":1,"mode":"image","assets":[],"overlays":[]}"#
        self.projectJson = client.memeProjectNormalize(empty)
        if projectJson.isEmpty { projectJson = empty }
        paletteHex = client.memePalette()
        packs = Self.decodePacks(client.memeStickerPacks())
        refresh()
    }

    // MARK: - SFX cues (MST-041: synth sounds at media time)

    private(set) var sfxCues: [(id: String, sfx: String, atMs: Int64)] = []

    private var previewPlayer: AVAudioPlayer?

    func addSfxCue(_ sfx: String, atMs: Int64) {
        let id = "c\(sfxCues.count + 1)-\(Int(Date.now.timeIntervalSince1970 * 1000) % 1000)"
        apply(commandJson: Self.encode(["op": "cue-add", "cue": ["id": id, "sfx": sfx, "at": atMs, "g": 1]]))
    }

    func removeSfxCue(_ id: String) {
        apply(commandJson: Self.encode(["op": "cue-del", "id": id]))
    }

    func previewSfx(_ sfx: String) {
        // Bridge returns a non-optional base64 payload; decode can still fail.
        let b64 = FrameworkBusinessCoreClient().memeSfxWavBase64(sfx, gain: 1)
        guard let data = Data(base64Encoded: b64),
              let player = try? AVAudioPlayer(data: data) else { return }
        previewPlayer?.stop()
        player.play()
        previewPlayer = player
    }

    private func refreshSfxCues() {
        guard let data = projectJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = root["sfx"] as? [[String: Any]] else {
            sfxCues = []
            return
        }
        sfxCues = rows.compactMap { row in
            guard let id = row["id"] as? String, let sfx = row["sfx"] as? String else { return nil }
            return (id, sfx, (row["at"] as? NSNumber)?.int64Value ?? 0)
        }
    }

    // MARK: - Looks (MST-043: media-only grades; SetLook is undoable)

    /// Active grade id parsed from the wire (nil = none).
    private(set) var lookId: String?

    /// Whole-clip playback rate parsed from the wire (V2 suite Speed).
    private(set) var speed: Float = 1

    /// Trim window parsed from the wire (for the Trim sheet display).
    private(set) var trimStartMs: Int64 = 0
    private(set) var trimEndMs: Int64 = 0

    /** Sets the playback rate (clamped 0.5–2× by the shared rule). */
    func setSpeed(_ rate: Float) {
        apply(commandJson: Self.encode(["op": "speed", "rate": rate]))
    }

    /** Sets the export trim window in media ms (undoable). */
    func setTrim(startMs: Int64, endMs: Int64) {
        apply(commandJson: Self.encode(["op": "trim", "start": startMs, "end": endMs]))
    }

    // MARK: - Pen drawing (V2 Draw chip)

    private(set) var drawStrokes: [MemeStrokeUi] = []

    /** Commits a finished stroke; true when it landed (budget caps adds). */
    @discardableResult
    func addStroke(colorIndex: Int, widthNorm: Float, points: [Float]) -> Bool {
        let before = projectJson
        let id = "d\(drawStrokes.count + 1)-\(Int(Date.now.timeIntervalSince1970 * 1000) % 1000)"
        apply(commandJson: Self.encode([
            "op": "stroke-add",
            "stroke": ["id": id, "c": colorIndex, "w": widthNorm, "p": points] as [String: Any],
        ]))
        let landed = projectJson != before
        if landed { pushHistory(before) }
        return landed
    }

    /** Removes the newest stroke (pen "undo stroke"). */
    func removeLastStroke() {
        guard let last = drawStrokes.last else { return }
        let before = projectJson
        apply(commandJson: Self.encode(["op": "stroke-del", "id": last.id]))
        if projectJson != before { pushHistory(before) }
    }

    func clearDrawing() {
        guard !drawStrokes.isEmpty else { return }
        let before = projectJson
        apply(commandJson: Self.encode(["op": "draw-clear"]))
        if projectJson != before { pushHistory(before) }
    }

    private func refreshDrawStrokes() {
        guard let data = projectJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = root["draw"] as? [[String: Any]] else {
            drawStrokes = []
            return
        }
        drawStrokes = rows.compactMap { row in
            guard let id = row["id"] as? String,
                  let points = (row["p"] as? [NSNumber])?.map(\.floatValue), points.count >= 4 else {
                return nil
            }
            return MemeStrokeUi(
                id: id,
                colorIndex: (row["c"] as? NSNumber)?.intValue ?? 0,
                widthNorm: (row["w"] as? NSNumber)?.floatValue ?? 0.008,
                points: points
            )
        }
    }

    private var gradeCache: [String: UIImage] = [:]

    func setLook(_ id: String) {
        apply(commandJson: Self.encode(["op": "look", "look": id]))
    }

    /// Manual fine-tune (prototype FX sliders): brightness/contrast/
    /// saturation multipliers over the look preset; a slider burst within
    /// 300 ms collapses into ONE undo step (EDT-004).
    private(set) var adjustBrightness: Float = 1
    private(set) var adjustContrast: Float = 1
    private(set) var adjustSaturation: Float = 1

    var hasAdjust: Bool {
        adjustBrightness != 1 || adjustContrast != 1 || adjustSaturation != 1
    }

    func setAdjust(brightness: Float, contrast: Float, saturation: Float) {
        let before = projectJson
        apply(commandJson: Self.encode([
            "op": "adjust", "bri": brightness, "con": contrast, "sat": saturation,
        ]))
        guard projectJson != before else { return }
        let now = Date()
        if let last = lastAdjustEdit, now.timeIntervalSince(last) < 0.3 {
            lastAdjustEdit = now // extend the burst — no new step
        } else {
            pushHistory(before)
            lastAdjustEdit = now
        }
    }

    private var lastAdjustEdit: Date?

    /// Pure JSON parse — nonisolated so export helpers can read the grade.
       nonisolated static func lookId(ofProject projectJson: String) -> String? {
        guard let data = projectJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return root["look"] as? String
    }

    /// Adjust triple parse for the detached export tasks (1/1/1 = default).
    nonisolated static func adjustTriple(ofProject projectJson: String) -> (bri: Float, con: Float, sat: Float) {
        guard let data = projectJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let adjust = root["adjust"] as? [String: Any] else { return (1, 1, 1) }
        return (
            (adjust["bri"] as? NSNumber)?.floatValue ?? 1,
            (adjust["con"] as? NSNumber)?.floatValue ?? 1,
            (adjust["sat"] as? NSNumber)?.floatValue ?? 1
        )
    }

    /// Stage-preview image with the grade applied (cached per asset/look).
    func gradedImage(_ image: UIImage, cacheKey: String) -> UIImage {
        let key = "\(cacheKey)|\(lookId ?? "none")|\(adjustBrightness)-\(adjustContrast)-\(adjustSaturation)"
        if let cached = gradeCache[key] { return cached }
        // The seam composes look + adjust into one matrix; identity (no
        // look, default adjust) returns nil from applyLook — raw image.
        guard let graded = MemeRaster.applyLook(
            image,
            matrixJson: client.memeAdjustMatrix(
                lookId,
                brightness: adjustBrightness,
                contrast: adjustContrast,
                saturation: adjustSaturation
            )
        ) else { return image }
        gradeCache[key] = graded
        return graded
    }

    // MARK: - Wire application

    /// Applies one command wire through the shared core; no-ops on junk.
    private func apply(commandJson: String) {
        let next = client.memeApplyCommand(projectJson, commandJson: commandJson)
        if !next.isEmpty && next != projectJson {
            projectJson = next
            refresh()
        }
    }

    /// Recomputes display projections after a wire change.
    private func refresh() {
        overlays = Self.parseOverlays(projectJson)
        refreshSfxCues()
        refreshDrawStrokes()
        let nextLook = Self.lookId(ofProject: projectJson)
        if nextLook != lookId { lookId = nextLook; gradeCache.removeAll() }
        if let data = projectJson.data(using: .utf8),
           let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            let adjust = root["adjust"] as? [String: Any]
            let nextBri = (adjust?["bri"] as? NSNumber)?.floatValue ?? 1
            let nextCon = (adjust?["con"] as? NSNumber)?.floatValue ?? 1
            let nextSat = (adjust?["sat"] as? NSNumber)?.floatValue ?? 1
            if nextBri != adjustBrightness || nextCon != adjustContrast || nextSat != adjustSaturation {
                adjustBrightness = nextBri
                adjustContrast = nextCon
                adjustSaturation = nextSat
                gradeCache.removeAll()
            }
            let nextSpeed = (root["speed"] as? NSNumber)?.floatValue ?? 1
            let clamped = nextSpeed.isNaN || nextSpeed <= 0 ? 1 : min(2, max(0.5, nextSpeed))
            if nextSpeed != speed { speed = clamped }
            if let trim = root["trim"] as? [Any], trim.count == 2 {
                trimStartMs = (trim[0] as? NSNumber)?.int64Value ?? 0
                trimEndMs = (trim[1] as? NSNumber)?.int64Value ?? 0
            } else {
                trimStartMs = 0
                trimEndMs = 0
            }
        }
        if let selectedId, !overlays.contains(where: { $0.id == selectedId }) {
            self.selectedId = nil
        }
        canUndo = !history.isEmpty
        canRedo = !redoHistory.isEmpty
        revision += 1
    }

    static func parseOverlays(_ json: String) -> [MemeOverlayUi] {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = root["overlays"] as? [[String: Any]] else { return [] }
        return rows.compactMap { row in
            guard let id = row["id"] as? String else { return nil }
            return MemeOverlayUi(
                id: id,
                isSticker: (row["kind"] as? String) == "sticker",
                text: row["text"] as? String ?? "",
                font: row["font"] as? String ?? "impact",
                size: (row["size"] as? NSNumber)?.intValue ?? 48,
                colorIndex: (row["color"] as? NSNumber)?.intValue ?? 0,
                outline: (row["outline"] as? NSNumber)?.intValue ?? 0,
                shadow: (row["shadow"] as? NSNumber)?.boolValue ?? false,
                x: (row["x"] as? NSNumber)?.floatValue ?? 0.5,
                y: (row["y"] as? NSNumber)?.floatValue ?? 0.5,
                scale: (row["scale"] as? NSNumber)?.floatValue ?? 1,
                rot: (row["rot"] as? NSNumber)?.floatValue ?? 0,
                fx: row["fx"] as? String,
                startMs: (row["startMs"] as? NSNumber)?.int64Value,
                endMs: (row["endMs"] as? NSNumber)?.int64Value,
                isImage: (row["kind"] as? String) == "image",
                assetId: row["asset"] as? String
            )
        }
    }

    static func decodePacks(_ json: String) -> [MemeStickerPack] {
        guard let data = json.data(using: .utf8),
              let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return rows.compactMap { row in
            guard let id = row["id"] as? String else { return nil }
            return MemeStickerPack(
                id: id,
                label: row["label"] as? String ?? id,
                stickers: row["stickers"] as? [String] ?? []
            )
        }
    }

    // MARK: - Undo / redo

    func undo() {
        guard let previous = history.popLast() else { return }
        redoHistory.append(projectJson)
        if redoHistory.count > Self.maxHistory { redoHistory.removeFirst() }
        projectJson = previous
        refresh()
        // The wire's clip list travels with the history: rebuild the
        // session from it; when a source is missing keep the session list
        // and re-sync instead of dropping clips.
        if !rebuildClipsFromWire() { syncWireClips() }
    }

    /// Re-applies the last undone wire; any new edit clears the branch.
    func redo() {
        guard let next = redoHistory.popLast() else { return }
        history.append(projectJson)
        if history.count > Self.maxHistory { history.removeFirst() }
        projectJson = next
        refresh()
        if !rebuildClipsFromWire() { syncWireClips() }
    }

    private func pushHistory(_ before: String) {
        history.append(before)
        if history.count > Self.maxHistory { history.removeFirst() }
        redoHistory.removeAll()
    }

    /// IMAGE layer sources: overlay asset id → image (export + stage).
    var layerImages: [String: UIImage] {
        var map: [String: UIImage] = [:]
        for asset in assets where map[asset.id] == nil {
            map[asset.id] = asset.image
        }
        return map
    }

    // MARK: - Assets (session ids a1…a9; refs live in `assets`)

    @discardableResult
    func addAsset(image: UIImage) -> String? {
        // Video mode: assets are IMAGE layer sources only (≤6, the clip
        // lives in `videoClipData`) — and none becomes `activeAsset`
        // (Looks stays image-mode only).
        let cap = isVideoMode ? Self.videoLayerCap : Self.imageAssetCap
        guard assets.count < cap else { return nil }
        let id = "a\(assets.count + 1)-\(image.hashValue.magnitude % 100_000)"
        guard !assets.contains(where: { $0.id == id }) else { return nil }
        let aspect = image.size.height > 0 ? image.size.width / image.size.height : 1
        let asset = MemeEditorAsset(id: id, image: image, aspect: aspect)
        assets.append(asset)
        if activeAssetId == nil && !isVideoMode { activeAssetId = id }
        return id
    }

    static let imageAssetCap = 9
    /// Web `image-overlay.ts` parity: ≤6 stacked sources over the clip.
    static let videoLayerCap = 6
    /// SfxSynth.MAX_CUES parity (display cap for the suite readout).
    static let sfxCueCap = 16

    // MARK: - Overlays

    /// Adds a default-placed overlay and selects it (shared placement).
    func addOverlay(kind: String, text: String) {
        guard canAddOverlay else { return }
        let overlayJson = client.memeDefaultOverlay(projectJson, kind: kind, text: text)
        guard !overlayJson.isEmpty,
              let data = overlayJson.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) else { return }
        let before = projectJson
        apply(commandJson: Self.encode(["op": "add", "overlay": object]))
        if projectJson != before {
            pushHistory(before)
            selectedId = Self.parseOverlays(projectJson).last?.id
        }
    }

    func removeOverlay(_ id: String) {
        let before = projectJson
        apply(commandJson: Self.encode(["op": "remove", "id": id]))
        if projectJson != before {
            pushHistory(before)
            if selectedId == id { selectedId = nil }
        }
    }

    /// Selects one overlay by id (Layers sheet); unknown ids deselect.
    func select(_ id: String) {
        selectedId = overlays.contains { $0.id == id } ? id : nil
        revision += 1
    }

    /// Image layer (video-mode source insert): a default-placed IMAGE
    /// overlay bound to an asset id the caller just imported.
    func addImageOverlay(assetId: String) {
        guard canAddOverlay else { return }
        let overlayJson = client.memeDefaultOverlay(projectJson, kind: "image", text: "")
        guard !overlayJson.isEmpty,
              let data = overlayJson.data(using: .utf8),
              var object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        object["asset"] = assetId
        let before = projectJson
        apply(commandJson: Self.encode(["op": "add", "overlay": object]))
        if projectJson != before {
            pushHistory(before)
            selectedId = Self.parseOverlays(projectJson).last?.id
        }
    }

    /**
     * Classic meme captions (prototype `create-edit` "Meme" hot tool):
     * TOP/BOTTOM text overlays at the canonical positions — classic look
     * (impact-style font, heavy outline, caps) — landing as ONE undo step
     * for the pair. Empty halves are skipped.
     */
    func addMemeCaptions(top: String, bottom: String, fontSlot: String) {
        guard canAddOverlay else { return }
        let before = projectJson
        var lastAdded: String?
        for (raw, y) in [(top, Float(0.16)), (bottom, Float(0.84))] {
            let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty, overlays.count + 1 <= Self.maxOverlays else { continue }
            let overlayJson = client.memeDefaultOverlay(projectJson, kind: "text", text: text.uppercased())
            guard !overlayJson.isEmpty,
                  let data = overlayJson.data(using: .utf8),
                  var object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                continue
            }
            object["x"] = 0.5
            object["y"] = y
            object["font"] = fontSlot
            object["size"] = 64
            object["outline"] = 3
            apply(commandJson: Self.encode(["op": "add", "overlay": object]))
            if projectJson != before {
                lastAdded = Self.parseOverlays(projectJson).last?.id
            }
        }
        if projectJson != before {
            pushHistory(before)
            selectedId = lastAdded
        }
    }

    /// Style/field edit; a burst on the same overlay within 300 ms merges
    /// into one history step (EDT-004 coalescing, snapshot flavor).
    func updateStyle(_ id: String, fields: [String: Any]) {        let before = projectJson
        var command = fields
        command["op"] = "update"
        command["id"] = id
        apply(commandJson: Self.encode(command))
        guard projectJson != before else { return }
        let now = Date()
        if let last = lastCoalescedEdit, last.id == id,
           now.timeIntervalSince(last.at) < 0.3 {
            lastCoalescedEdit = (id, now) // extend the burst — no new step
        } else {
            pushHistory(before)
            lastCoalescedEdit = (id, now)
        }
    }

    private var lastCoalescedEdit: (id: String, at: Date)?

    // MARK: - Gestures (drag / pinch / twist; tap = hit-test)

    private var gestureBegan = false
    private var gestureMoved = false

    func beginGesture() {
        guard !gestureBegan else { return }
        gestureBegan = true
        gestureMoved = false
        gestureSnapshot = projectJson
    }

    /// Live geometry update for the selection (normalized deltas from the
    /// previous gesture event — callers pass incremental deltas).
    func gesturePan(dx: Float, dy: Float) {
        guard let selected = selectedOverlay else { return }
        gestureMoved = gestureMoved || dx != 0 || dy != 0
        applyGeometry([
            "x": selected.x + dx,
            "y": selected.y + dy,
        ])
    }

    func gestureScale(by factor: Float) {
        guard let selected = selectedOverlay, factor != 1 else { return }
        gestureMoved = true
        applyGeometry(["scale": selected.scale * factor])
    }

    func gestureRotate(by degrees: Float) {
        guard let selected = selectedOverlay, degrees != 0 else { return }
        gestureMoved = true
        applyGeometry(["rot": selected.rot + degrees])
    }

    private var selectedOverlay: MemeOverlayUi? {
        overlays.first { $0.id == selectedId }
    }

    private func applyGeometry(_ fields: [String: Any]) {
        guard let id = selectedId else { return }
        var command = fields
        command["op"] = "update"
        command["id"] = id
        apply(commandJson: Self.encode(command))
    }

    func endGesture() {
        guard gestureBegan else { return }
        gestureBegan = false
        defer { gestureSnapshot = nil }
        if gestureMoved, let snapshot = gestureSnapshot, snapshot != projectJson {
            pushHistory(snapshot)
        }
    }

    func cancelGesture() {
        guard gestureBegan else { return }
        gestureBegan = false
        gestureMoved = false
        if let snapshot = gestureSnapshot {
            projectJson = snapshot
            refresh()
        }
        gestureSnapshot = nil
    }

    // MARK: - Selection

    /// Hit-tests the normalized tap point; true when an overlay was hit.
    @discardableResult
    func selectAt(x: Float, y: Float) -> Bool {
        let id = client.memeHitTest(projectJson, x: x, y: y)
        selectedId = id.isEmpty ? nil : id
        return !id.isEmpty
    }

    func clearSelection() {
        selectedId = nil
    }

    /// Estimated normalized bounds for the selection chrome.
    func boundsFraction(for id: String) -> (width: Float, height: Float)? {
        let raw = client.memeBounds(projectJson, overlayId: id)
        let parts = raw.split(separator: "|").compactMap { Float($0) }
        guard parts.count == 2 else { return nil }
        return (parts[0], parts[1])
    }

    // MARK: - Video mode (M3 → M5: multi-clip timeline, composition stage)

    /// One timeline clip: source + window in source media time. List order
    /// = timeline order; output duration per clip = window ÷ speed.
    struct EditorClip {
        var id: String
        let data: Data
        let url: URL
        let probe: MemeVideoExportIos.Probe
        var startMs: Int64
        var endMs: Int64
        var volume: Float
        var lookId: String?
    }

    private(set) var clips: [EditorClip] = []
    private(set) var selectedClipIndex = 0

    /// Single-clip compatibility surface (legacy call sites).
    var videoClipData: Data? { clips.first?.data }
    var videoProbe: MemeVideoExportIos.Probe? { clips.first?.probe }
    var videoClipURL: URL? { clips.first?.url }
    private(set) var videoRevision = 0
    /** MST-032: separately-uploaded cover URL (session-only in V1). */
    private(set) var coverThumbUrl: String?

    /// Effective per-clip playback rate (whole-project speed).
    var rate: Float { speed }

    /// Output duration of one clip's window (ms).
    func clipOutputMs(_ clip: EditorClip) -> Int64 {
        Int64((Double(max(0, clip.endMs - clip.startMs)) / Double(max(0.01, rate))).rounded())
    }

    /// Timeline offset of clip [index] (ms).
    func clipOffsetMs(_ index: Int) -> Int64 {
        guard index > 0 else { return 0 }
        return clips.prefix(index).reduce(0) { $0 + clipOutputMs($1) }
    }

    /// Whole-timeline output duration (ms).
    var timelineDurationMs: Int64 { clips.reduce(0) { $0 + clipOutputMs($1) } }

    /// Maps a timeline position → (clip, source media ms).
    func timelineToMedia(_ timelineMs: Int64) -> (clip: EditorClip, mediaMs: Int64)? {
        for (index, clip) in clips.enumerated() {
            let start = clipOffsetMs(index)
            if timelineMs < start + clipOutputMs(clip) || index == clips.count - 1 {
                return (clip, clip.startMs + Int64((Double(timelineMs - start) * Double(rate)).rounded()))
            }
        }
        return nil
    }

    /// Mirrors the clip list into the project wire through the shared seam.
    private func syncWireClips() {
        guard isVideoMode else { return }
        let rows: [[String: Any]] = clips.map {
            var row: [String: Any] = ["id": $0.id, "start": $0.startMs, "end": $0.endMs]
            if $0.volume != 1 { row["vol"] = $0.volume }
            if let look = $0.lookId { row["look"] = look }
            return row
        }
        guard let data = try? JSONSerialization.data(withJSONObject: rows),
              let json = String(data: data, encoding: .utf8) else { return }
        let next = client.memeTimelineSyncClips(projectJson: projectJson, clipsJson: json)
        if !next.isEmpty {
            projectJson = next
            refresh()
        }
    }

    /// Undoable-clips support: id → source clip (bytes/url/probe are
    /// shared, not copied) so a restored wire clip list can rebuild the
    /// session after undo/redo. Bounded — ≤8 ids live per session and
    /// removed halves linger for their redo step.
    private var clipArchive: [String: EditorClip] = [:]

    private func archiveClip(_ clip: EditorClip) {
        clipArchive[clip.id] = clip
        if clipArchive.count > 32, let oldest = clipArchive.keys.first {
            clipArchive.removeValue(forKey: oldest)
        }
    }

    /// Rebuilds the session clips from a restored wire (undo/redo). False
    /// when a wire row's source is missing — the caller then re-syncs the
    /// session list into the wire (sticky fallback) instead of dropping.
    private func rebuildClipsFromWire() -> Bool {
        guard isVideoMode else { return true }
        let rows = wireClipEntries()
        var rebuilt: [EditorClip] = []
        rebuilt.reserveCapacity(rows.count)
        for row in rows {
            guard let base = clipArchive[row.id] else { return false }
            let start = min(row.startMs, base.probe.durationMs)
            let end = min(row.endMs, base.probe.durationMs)
            guard end > start else { return false }
            rebuilt.append(
                EditorClip(
                    id: row.id, data: base.data, url: base.url, probe: base.probe,
                    startMs: start, endMs: end, volume: row.volume, lookId: row.lookId
                )
            )
        }
        clips = rebuilt
        selectedClipIndex = min(selectedClipIndex, max(0, clips.count - 1))
        videoRevision += 1
        return true
    }

    /** Fresh unique clip ids survive splits/removals. */
    private func maxClipCounter() -> Int {
        clips.compactMap { Int($0.id.dropFirst().prefix { $0.isNumber }) }.max() ?? 0
    }

    /// Appends a source as a new timeline clip (cut rules applied; the
    /// reason surfaces as a notice). Returns false when caps refuse it.
    /// Seeding passes `undoable: false` — a session start is not an edit.
    @discardableResult
    func appendClip(data: Data, undoable: Bool = true) -> Bool {
        guard clips.count < 8 else {
            setNotice("Clip limit reached (8)")
            return false
        }
        guard let url = MemeVideoExportIos.writeTempClip(data),
              let probe = MemeVideoExportIos.probe(url: url) else {
            setNotice("Could not read this video")
            return false
        }
        let cutJson = client.memeVideoCutFor(durationMs: probe.durationMs)
        let cut = Self.parseCut(cutJson)
        let start = cut?.cut == true ? cut!.startMs : 0
        let end = cut?.cut == true ? cut!.endMs : probe.durationMs
        if cut?.cut == true { setNotice(cut!.message) }
        if undoable { pushHistory(projectJson) }
        let clip = EditorClip(
            id: "v\(maxClipCounter() + 1)", data: data, url: url, probe: probe,
            startMs: start, endMs: end, volume: 1, lookId: nil
        )
        clips.append(clip)
        archiveClip(clip)
        selectedClipIndex = clips.count - 1
        videoRevision += 1
        syncWireClips()
        return true
    }

    func removeClip(at index: Int) {
        guard clips.indices.contains(index) else { return }
        pushHistory(projectJson)
        clips.remove(at: index)
        selectedClipIndex = min(selectedClipIndex, max(0, clips.count - 1))
        videoRevision += 1
        syncWireClips()
    }

    /** Selects a timeline clip (dock lanes, Clips sheet). */
    func selectClip(_ index: Int) {
        guard clips.indices.contains(index) else { return }
        selectedClipIndex = index
        videoRevision += 1
    }

    func moveClip(at index: Int, by delta: Int) {
        let target = index + delta
        guard clips.indices.contains(index), clips.indices.contains(target) else { return }
        pushHistory(projectJson)
        clips.insert(clips.remove(at: index), at: target)
        selectedClipIndex = target
        videoRevision += 1
        syncWireClips()
    }

    func setClipWindow(index: Int, startMs: Int64, endMs: Int64) {
        guard clips.indices.contains(index) else { return }
        pushHistory(projectJson)
        clips[index].startMs = startMs
        clips[index].endMs = max(startMs + 200, endMs)
        videoRevision += 1
        syncWireClips()
    }

    func setClipVolume(index: Int, volume: Float) {
        guard clips.indices.contains(index) else { return }
        pushHistory(projectJson)
        clips[index].volume = min(2, max(0, volume))
        videoRevision += 1
        syncWireClips()
    }

    func setClipLook(index: Int, lookId: String?) {
        guard clips.indices.contains(index) else { return }
        pushHistory(projectJson)
        clips[index].lookId = lookId
        videoRevision += 1
        syncWireClips()
    }

    /// Splits the clip under the timeline playhead into two clips over the
    /// same source (≥200 ms each side; volume/look carry to both halves).
    @discardableResult
    func splitClip(atTimelineMs timelineMs: Int64) -> Bool {
        guard clips.count < 8 else {
            setNotice("Clip limit reached (8)")
            return false
        }
        for (index, clip) in clips.enumerated() {
            let offset = clipOffsetMs(index)
            let out = clipOutputMs(clip)
            guard timelineMs >= offset, timelineMs < offset + out else { continue }
            let intoMedia = Int64((Double(timelineMs - offset) * Double(rate)).rounded())
            let splitAt = clip.startMs + intoMedia
            guard splitAt - clip.startMs >= 200, clip.endMs - splitAt >= 200 else {
                setNotice("Too close to a clip edge to split")
                return false
            }
            pushHistory(projectJson)
            var second = clip
            second.id = "v\(maxClipCounter() + 1)"
            second.startMs = splitAt
            clips[index].endMs = splitAt
            clips.insert(second, at: index + 1)
            archiveClip(clips[index])
            archiveClip(second)
            selectedClipIndex = index
            videoRevision += 1
            syncWireClips()
            return true
        }
        return false
    }

    /// Replaces the clip list from a resumed slot's wire + asset files.
    func restoreClips(from entries: [(id: String, url: URL, startMs: Int64, endMs: Int64, volume: Float, lookId: String?)]) {
        clips = entries.compactMap { entry in
            guard let data = try? Data(contentsOf: entry.url),
                  let probe = MemeVideoExportIos.probe(url: entry.url) else { return nil }
            let start = min(entry.startMs, probe.durationMs)
            let end = min(entry.endMs, probe.durationMs)
            // A probe that disagrees with the wire enough to collapse the
            // window would render a zero-length segment — drop it instead.
            guard end > start else { return nil }
            let clip = EditorClip(
                id: entry.id, data: data, url: entry.url, probe: probe,
                startMs: start,
                endMs: end,
                volume: entry.volume, lookId: entry.lookId
            )
            archiveClip(clip)
            return clip
        }
        selectedClipIndex = 0
        videoRevision += 1
    }

    /// Wire clips that a resume could NOT bring back (missing file,
    /// unreadable bytes, probe/wire mismatch). While > 0 the session is an
    /// incomplete view of the slot and autosave must not persist over the
    /// last good copy — a partial save erases the dropped clips' asset rows
    /// and the timeline becomes unrecoverable.
    private(set) var timelineRestoreDroppedClips = 0

    func setTimelineRestoreDroppedClips(_ count: Int) {
        timelineRestoreDroppedClips = count
    }

    /// The wire's clip rows (resume source): id/start/end/vol/look.
    func wireClipEntries() -> [(id: String, startMs: Int64, endMs: Int64, volume: Float, lookId: String?)] {
        guard let data = projectJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = root["clips"] as? [[String: Any]] else { return [] }
        return rows.compactMap { row in
            guard let id = row["id"] as? String else { return nil }
            return (
                id,
                (row["start"] as? NSNumber)?.int64Value ?? 0,
                (row["end"] as? NSNumber)?.int64Value ?? 0,
                (row["vol"] as? NSNumber)?.floatValue ?? 1,
                row["look"] as? String
            )
        }
    }

    func setCoverThumbUrl(_ url: String?) {
        coverThumbUrl = url
    }

    // MARK: - Canvas (image/GIF: ratio preset + background)

    /** Canvas ratio preset id (`source`/`w:h`); nil = media frame. */
    var canvasRatio: String? { Self.canvasRatio(ofProject: projectJson) }

    /** Canvas background `#rrggbb`; nil = platform default. */
    var canvasBg: String? { Self.canvasBg(ofProject: projectJson) }

    /** Wire canvas ratio (`source`/`w:h`); nil when unset/malformed. */
    nonisolated static func canvasRatio(ofProject json: String) -> String? {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let canvas = root["canvas"] as? [String: Any],
              let ratio = canvas["ratio"] as? String else { return nil }
        return ratio
    }

    /** Wire canvas background `#rrggbb`; nil when unset/malformed. */
    nonisolated static func canvasBg(ofProject json: String) -> String? {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let canvas = root["canvas"] as? [String: Any],
              let bg = canvas["bg"] as? String else { return nil }
        return bg
    }

    /** Sets both canvas fields (nil ratio = source); undoable like any edit. */
    func setCanvas(ratio: String?, bg: String?) {
        let next = client.memeSetCanvas(projectJson, ratio: ratio, bg: bg)
        guard !next.isEmpty else { return }
        pushHistory(projectJson)
        projectJson = next
        refresh()
    }

    /** Canvas aspect override for the stage; nil = keep the media's. */
    var canvasAspect: CGFloat? {
        guard let ratio = canvasRatio else { return nil }
        let terms = ratio.split(separator: ":").compactMap { Int($0) }
        guard terms.count == 2, terms[1] > 0 else { return nil }
        return CGFloat(terms[0]) / CGFloat(terms[1])
    }

    /** Stage/export background; nil = the theme surface. */
    var canvasBackgroundHex: String? { canvasBg }

    /** `#rrggbb` → Color (stage/export fill); nil when malformed. */
    nonisolated static func colorHex(_ hex: String) -> Color? {
        guard hex.count == 7, hex.hasPrefix("#"),
              let value = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        return Color(hex: value)
    }

    func setExportMessage(_ message: String) {
        exportState = .failed(message)
    }

    var isVideoMode: Bool {
        projectJson.contains("\"mode\":\"video\"")
    }

    /// Legacy single-clip entry — now appends to the timeline (M5).
    func setVideoClip(data: Data) {
        appendClip(data: data)
    }

    private struct VideoCut {
        let startMs: Int64, endMs: Int64
        let cut: Bool
        let message: String
    }

    private static func parseCut(_ json: String) -> VideoCut? {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return VideoCut(
            startMs: (root["startMs"] as? NSNumber)?.int64Value ?? 0,
            endMs: (root["endMs"] as? NSNumber)?.int64Value ?? 0,
            cut: (root["cut"] as? Bool) ?? false,
            message: (root["message"] as? String) ?? ""
        )
    }

    /// Informational banner (distinct from export failures): the video cut
    /// reason lives here until the next action clears it.
    private(set) var notice: String?

    func setNotice(_ message: String?) {
        notice = message
    }

    func setExportFailure(_ message: String) {
        exportState = .failed(message)
    }

    func switchModeToVideo() {
        guard !isVideoMode else { return }
        seedProjectJson(#"{"v":1,"mode":"video","assets":[],"overlays":[]}"#)
        assets.removeAll()
        activeAssetId = nil
        gifFrames.removeAll()
        videoRevision += 1
    }

    // MARK: - GIF mode (M2: frames, loop preview, uniform delay)

    struct MemeGifFrame: Identifiable, Equatable {
        let id: String
        let image: UIImage
        let delayMs: Int
        static func == (lhs: MemeGifFrame, rhs: MemeGifFrame) -> Bool { lhs.id == rhs.id }
    }

    private(set) var gifFrames: [MemeGifFrame] = []
    private(set) var gifPreviewIndex = 0
    /** Uniform hold override (ms); 0 = keep per-frame source delays. */
    private(set) var gifUniformDelayMs = 0
    /** Drives the loop-preview task (bumped with every frame change). */
    private(set) var gifRevision = 0

    var isGifMode: Bool {
        projectJson.contains("\"mode\":\"gif\"")
    }

    var gifActiveFrame: MemeGifFrame? {
        guard !gifFrames.isEmpty else { return nil }
        return gifFrames[min(gifPreviewIndex, gifFrames.count - 1)]
    }

    /// Decoded frames enter the tray here (cap: 60 per the contract).
    func addGifFrames(_ frames: [MemeGifFrame]) {
        let cap = Self.maxGifFrames - gifFrames.count
        guard cap > 0 else { return }
        let base = gifFrames.count
        let accepted = frames.prefix(cap).enumerated().map { pair in
            MemeGifFrame(
                id: "f\(base + pair.offset + 1)",
                image: pair.element.image,
                delayMs: pair.element.delayMs
            )
        }
        gifFrames += accepted
        gifPreviewIndex = 0
        gifRevision += 1
    }

    /// Long-press drag reorder (swap-with-neighbor semantics).
    func moveGifFrame(from: Int, to: Int) {
        guard gifFrames.indices.contains(from), gifFrames.indices.contains(to), from != to else { return }
        let frame = gifFrames.remove(at: from)
        gifFrames.insert(frame, at: to)
        gifPreviewIndex = to
        gifRevision += 1
    }

    func removeGifFrame(at index: Int) {
        guard gifFrames.indices.contains(index) else { return }
        gifFrames.remove(at: index)
        if gifPreviewIndex >= gifFrames.count { gifPreviewIndex = max(0, gifFrames.count - 1) }
        gifRevision += 1
    }

    func selectGifFrame(_ index: Int) {
        guard gifFrames.indices.contains(index) else { return }
        gifPreviewIndex = index
        gifPreviewTick += 1
    }

    func setGifUniformDelay(_ ms: Int) {
        gifUniformDelayMs = ms
        gifRevision += 1
    }

    /// The loop preview's hold for the CURRENT frame (ms).
    func gifCurrentHoldMs() -> Int {
        if gifUniformDelayMs > 0 { return gifUniformDelayMs }
        guard let frame = gifActiveFrame else { return 100 }
        return max(20, frame.delayMs)
    }

    func advanceGifPreview() {
        guard gifFrames.count > 1 else { return }
        gifPreviewIndex = (gifPreviewIndex + 1) % gifFrames.count
        gifPreviewTick += 1
    }

    /** Loop-preview re-key tick (advances re-run the hold task). */
    private(set) var gifPreviewTick = 0

    var gifFramesCount: Int { gifFrames.count }

    func switchModeToGif() {
        guard !isGifMode else { return }
        seedProjectJson(#"{"v":1,"mode":"gif","assets":[],"overlays":[]}"#)
        assets.removeAll()
        activeAssetId = nil
        clearClips()
        gifRevision += 1
    }

    func switchModeToImage() {
        guard isGifMode || isVideoMode else { return }
        seedProjectJson(#"{"v":1,"mode":"image","assets":[],"overlays":[]}"#)
        gifFrames.removeAll()
        gifPreviewIndex = 0
        clearClips()
        gifRevision += 1
    }

    private func clearClips() {
        clips.removeAll()
        selectedClipIndex = 0
        videoRevision += 1
    }

    /// Mode switch keeps overlays: re-seed the wire mode in place. IMAGE
    /// layers do NOT transplant — their sources are video-session-bound.
    private func seedProjectJson(_ json: String) {
        // Preserve overlays by transplanting them into the new-mode wire.
        let overlays = overlays.filter { !$0.isImage }
        let normalized = client.memeProjectNormalize(json)
        projectJson = normalized.isEmpty ? json : normalized
        history.removeAll()
        redoHistory.removeAll()
        selectedId = nil
        refresh()
        for overlay in overlays {
            addOverlay(kind: overlay.isSticker ? "sticker" : "text", text: overlay.text)
            if let id = selectedId {
                updateStyle(id, fields: [
                    "x": overlay.x, "y": overlay.y, "size": overlay.size,
                    "color": overlay.colorIndex, "outline": overlay.outline,
                    "shadow": overlay.shadow, "font": overlay.font,
                ])
            }
        }
    }

    private static let maxGifFrames = 60

    // MARK: - Resume (MST-018: restore a persisted slot exactly)

    /** Bump on every wire change — drives the debounced autosave task. */
    private(set) var revision = 0

    func restore(projectJson savedJson: String) {
        let normalized = client.memeProjectNormalize(savedJson)
        projectJson = normalized.isEmpty ? savedJson : normalized
        history.removeAll()
        redoHistory.removeAll()
        lastCoalescedEdit = nil
        selectedId = nil
        refresh()
    }

    // MARK: - Stickers

    func addSticker(_ emoji: String) {
        guard canAddOverlay else { return }
        addOverlay(kind: "sticker", text: emoji)
        recents.removeAll { $0 == emoji }
        recents.insert(emoji, at: 0)
        if recents.count > Self.maxRecentStickers {
            recents.removeLast()
        }
    }

    // MARK: - Export (MST-016: render the shared plan → save to Photos)

    /** Export input for the current timeline: a single clip passes through
     *  unchanged; a multi-clip timeline composes to one file first (speed
     *  and per-clip volume baked in — the composed media time IS the
     *  timeline clock the overlays/cues are keyed to). */
    func exportSource() async throws -> (url: URL, probe: MemeVideoExportIos.Probe, wire: String) {
        guard let first = clips.first else {
            throw NSError(domain: "MemeEditor", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Pick a clip first",
            ])
        }
        // A single ungraded clip passes through unchanged; anything with a
        // look or a manual adjust (per-clip or project) composes so the
        // grade burns exactly.
        if clips.count == 1, (first.lookId ?? lookId) == nil, !hasAdjust {
            return (first.url, first.probe, projectJson)
        }
        let url = try await MemeVideoExportIos.composeClips(
            clips, rate: rate, projectLookId: lookId,
            projectAdjust: (adjustBrightness, adjustContrast, adjustSaturation),
            client: client
        )
        let probe = MemeVideoExportIos.Probe(
            width: first.probe.width,
            height: first.probe.height,
            durationMs: timelineDurationMs,
            rotationDeg: first.probe.rotationDeg
        )
        let wire = MemeVideoExportIos.timelineExportWire(
            projectJson: projectJson, timelineMs: timelineDurationMs, client: client
        )
        return (url, probe, wire)
    }

    /** Over-size publish ladder (M5): the TAIL clip's window shrinks to the
     *  allowed output duration; the timeline recomposes for re-measuring. */
    private func shrinkTailClip(toTimelineMs limit: Int64) {
        var remaining = max(200, limit)
        for index in clips.indices {
            if index < clips.count - 1 {
                remaining -= clipOutputMs(clips[index])
            } else {
                let window = Int64((Double(remaining) * Double(rate)).rounded())
                clips[index].endMs = min(clips[index].startMs + window, clips[index].probe.durationMs)
            }
        }
        videoRevision += 1
        syncWireClips()
    }

    func exportActiveAssetToPhotos() {
        guard exportState != .saving else { return }
        if isVideoMode {
            guard !clips.isEmpty else {
                exportState = .failed("Pick a clip first")
                return
            }
            exportState = .saving
            let client = self.client
            let images = layerImages
            let exportJob = exportJobs.begin(format: "mp4", nowMs: nowMs())
            Task {
                do {
                    let source = try await exportSource()
                    let data = try await MemeVideoExportIos.export(
                        clipURL: source.url, probe: source.probe,
                        projectJson: source.wire, client: client,
                        images: images
                    )
                    guard exportJobs.artifactReady(exportJob, bytes: data, nowMs: nowMs()) else {
                        throw MemeRaster.ExportError(message: "Could not persist the render")
                    }
                    exportJobs.update(exportJob, phase: "saving", nowMs: nowMs())
                    try await MemeRaster.saveVideoToPhotos(data)
                    exportJobs.finish(exportJob)
                    exportState = .saved
                } catch {
                    exportJobs.update(exportJob, phase: "failed", error: error.localizedDescription, nowMs: nowMs())
                    exportState = .failed(error.localizedDescription)
                }
            }
            return
        }
        if isGifMode {
            guard gifFramesCount > 0 else {
                exportState = .failed("Pick frames first")
                return
            }
            exportState = .saving
            let project = projectJson
            let client = self.client
            let frames = gifFrames.map { MemeGifFrame(id: $0.id, image: $0.image, delayMs: holdMs(for: $0)) }
            let exportJob = exportJobs.begin(format: "gif", nowMs: nowMs())
            Task {
                do {
                    let result = try await Task.detached(priority: .userInitiated) {
                        try MemeGifExportIos.export(
                            frames: frames.map(\.image),
                            delaysMs: frames.map(\.delayMs),
                            projectJson: project,
                            client: client
                        )
                    }.value
                    guard exportJobs.artifactReady(exportJob, bytes: result.data, nowMs: nowMs()) else {
                        throw MemeRaster.ExportError(message: "Could not persist the render")
                    }
                    exportJobs.update(exportJob, phase: "saving", nowMs: nowMs())
                    try await MemeRaster.saveToPhotos(result.data)
                    exportJobs.finish(exportJob)
                    exportState = result.ladderStep > 0 || result.capped
                        ? .savedAdjusted("Saved at a smaller size (downscaled ×\(result.ladderStep))")
                        : .saved
                } catch {
                    exportJobs.update(exportJob, phase: "failed", error: error.localizedDescription, nowMs: nowMs())
                    exportState = .failed(error.localizedDescription)
                }
            }
            return
        }
        guard let asset = activeAsset else {
            exportState = .failed("Pick an image first")
            return
        }
        exportState = .saving
        let project = projectJson
        let client = self.client
        let source = asset.image
        let exportJob = exportJobs.begin(format: "png", nowMs: nowMs())
        Task {
            do {
                let data = try await Task.detached(priority: .userInitiated) {
                    try MemeRaster.renderPngData(asset: source, projectJson: project, client: client)
                }.value
                guard exportJobs.artifactReady(exportJob, bytes: data, nowMs: nowMs()) else {
                    throw MemeRaster.ExportError(message: "Could not persist the render")
                }
                exportJobs.update(exportJob, phase: "saving", nowMs: nowMs())
                try await MemeRaster.saveToPhotos(data)
                exportJobs.finish(exportJob)
                exportState = .saved
            } catch {
                exportJobs.update(exportJob, phase: "failed", error: error.localizedDescription, nowMs: nowMs())
                exportState = .failed(error.localizedDescription)
            }
        }
    }

    /// MUX-05 retry: a failed/needs-review destination save re-runs FROM THE
    /// PERSISTED ARTIFACT — no re-render. A needsReview job asks the user
    /// to confirm the state first (Photos saves are add-only).
    func retryExportSave(jobId: Int) {
        guard exportState != .saving,
              let job = exportJobs.job(jobId),
              let data = exportJobs.loadArtifact(job) else { return }
        exportState = .saving
        exportJobs.update(jobId, phase: "saving", clearError: true, nowMs: nowMs())
        Task {
            do {
                if job.format == "mp4" {
                    try await MemeRaster.saveVideoToPhotos(data)
                } else {
                    try await MemeRaster.saveToPhotos(data)
                }
                exportJobs.finish(jobId)
                exportState = .saved
            } catch {
                exportJobs.update(jobId, phase: "failed", error: error.localizedDescription, nowMs: nowMs())
                exportState = .failed(error.localizedDescription)
            }
        }
    }

    func discardExportJob(jobId: Int) {
        exportJobs.discard(jobId)
    }

    /** Effective hold for a frame (uniform override > source delay). */
    private func holdMs(for frame: MemeGifFrame) -> Int {
        max(20, gifUniformDelayMs > 0 ? gifUniformDelayMs : frame.delayMs)
    }

    // MARK: - Publish (MST-017: render → hash-verified upload → kind 20)

    private(set) var publishState: MemePublishPhase = .idle
    private(set) var publishFailure: String?

    /** Prototype `#/publishing` machine: the REAL pipeline steps, updated
     * at each checkpoint (render → hash → upload → verify → build → sign →
     * relay → confirm). Nil while no publish runs. */
    private(set) var publishStep: PublishMachineStep?
    /** Stable per-attempt job number (prototype "job NNNN" chip). */
    private(set) var publishJobId = 0
    /** Canonical event id of the in-flight/last publish (nil until built). */
    private(set) var publishEventId: String?

    /// Durable publish-job ledger (prototype `#/queue`): attempts survive
    /// crashes; recovery offers verify / discard / retry-from-media.
    var jobStore = MemePublishJobStore()
    private(set) var ledgerJobId: Int?

    /// Draft persistence state (MUX-01): saving is acknowledged, never
    /// assumed — "saved" only appears after the write returns.
    enum DraftSaveState: Equatable { case idle, saving, saved, failed }
    var draftSaveState: DraftSaveState = .idle

    /// Durable export jobs (MUX-05): retry a failed destination save from
    /// the persisted artifact — never a re-render.
    var exportJobs = MemeExportJobStore()

    enum PublishMachineStep: Int, CaseIterable, Equatable {
        case render, hash, upload, verify, build, sign, relay, confirm
    }

    enum MemePublishPhase: Equatable {
        case idle
        case uploading
        case publishing
        case done
    }

    func publishActiveAsset(
        caption: String,
        altText: String,
        contentWarningReason: String?,
        remixEventId: String = "",
        remixAuthor: String = "",
        extraTags: [[String]] = [],
        identity: IdentityStore,
        publisher: NotePublisher,
        bridge: BusinessCoreBridge
    ) {
        guard publishState != .uploading, publishState != .publishing else { return }
        guard isVideoMode || isGifMode || activeAsset != nil else {
            publishFailure = "Pick an image first"
            return
        }
        publishState = .uploading
        publishFailure = nil
        publishStep = .render
        publishJobId = Int.random(in: 1000...9999)
        publishEventId = nil
        let project = projectJson
        let client = self.client
        let gifSourceFrames = gifFrames.map { MemeGifFrame(id: $0.id, image: $0.image, delayMs: holdMs(for: $0)) }
        let source = activeAsset?.image
        Task {
            do {
                let mime: String
                let bytes: Data
                var width = 0
                var height = 0
                var videoDurationMs: Int64 = 0
                if isVideoMode, !clips.isEmpty {
                    // MST-034/M5: video memes publish as kind 22/21 by
                    // orientation. Over-size exports are CUT (the tail clip's
                    // window shrinks through the ladder), not failed.
                    var source = try await exportSource()
                    var exported = try await MemeVideoExportIos.export(
                        clipURL: source.url, probe: source.probe,
                        projectJson: source.wire, client: client,
                        images: layerImages
                    )
                    let maxBytes = 64 * 1024 * 1024
                    var durationMs = source.probe.durationMs
                    var attempts = 0
                    while exported.count > maxBytes && attempts < 3 {
                        guard let cutData = client.memeVideoCutForSize(
                            currentMs: durationMs,
                            sizeBytes: Int64(exported.count),
                            maxBytes: Int64(maxBytes)
                        ).data(using: .utf8),
                            let cutRoot = try? JSONSerialization.jsonObject(with: cutData) as? [String: Any],
                            let nextEnd = (cutRoot["endMs"] as? NSNumber)?.int64Value,
                            nextEnd > 0, nextEnd < durationMs else { break }
                        self.setNotice((cutRoot["message"] as? String) ?? "over the size limit — trimmed")
                        self.shrinkTailClip(toTimelineMs: nextEnd)
                        source = try await self.exportSource()
                        durationMs = source.probe.durationMs
                        attempts += 1
                        exported = try await MemeVideoExportIos.export(
                            clipURL: source.url, probe: source.probe,
                            projectJson: source.wire, client: client,
                            images: self.layerImages
                        )
                    }
                    bytes = exported
                    width = source.probe.uprightWidth
                    height = source.probe.uprightHeight
                    // Composed/probed duration is already OUTPUT time for
                    // multi-clip; single-clip divides by the wire rate.
                    videoDurationMs = clips.count > 1 ? durationMs : Int64((Double(durationMs) / Double(speed)).rounded())
                    mime = "video/mp4"
                } else if isGifMode && gifSourceFrames.count > 0 {
                    // MST-023: GIF memes publish as kind-20 m image/gif.
                    let exported = try await Task.detached(priority: .userInitiated) {
                        try MemeGifExportIos.export(
                            frames: gifSourceFrames.map(\.image),
                            delaysMs: gifSourceFrames.map(\.delayMs),
                            projectJson: project,
                            client: client
                        )
                    }.value
                    bytes = exported.data
                    width = exported.canvasWidth
                    height = exported.canvasHeight
                    mime = "image/gif"
                } else {
                    // 1. Render at export resolution (dims ride the event imeta).
                    guard let source else {
                        publishFailure = "Pick an image first"
                        publishState = .idle
                        return
                    }
                    let png = try await Task.detached(priority: .userInitiated) {
                        try MemeRaster.renderPngData(asset: source, projectJson: project, client: client)
                    }.value
                    let envelopeJson = client.memeExportPlan(
                        project,
                        sourceWidth: Int(source.size.width),
                        sourceHeight: Int(source.size.height)
                    )
                    if let data = envelopeJson.data(using: .utf8),
                       let envelope = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                        width = (envelope["width"] as? NSNumber)?.intValue ?? 0
                        height = (envelope["height"] as? NSNumber)?.intValue ?? 0
                    }
                    bytes = png
                    mime = "image/png"
                }
                // Ledger: persist the attempt + rendered bytes (durable job).
                ledgerJobId = jobStore.begin(
                    mode: isVideoMode ? "video" : isGifMode ? "gif" : "image",
                    caption: caption, altText: altText,
                    contentWarningReason: contentWarningReason,
                    extraTagsJson: Self.encodeTags(extraTags),
                    remixEventId: remixEventId, remixAuthor: remixAuthor,
                    bytes: bytes, mime: mime, width: width, height: height,
                    durationMs: videoDurationMs, coverThumbUrl: coverThumbUrl,
                    nowMs: Int64(Date.now.timeIntervalSince1970 * 1000)
                )
                let ledgerId = ledgerJobId ?? 0
                // 2. Hash-verified Blossom upload (nothing signs before this).
                let uploaded = try await BlossomUploader(bridge: bridge).upload(
                    bytes: bytes, mimeType: mime, identity: identity,
                    serverUrl: "https://blossom.primal.net",
                    onStage: { stage in
                        switch stage {
                        case .hashing:
                            self.publishStep = .hash
                            self.jobStore.update(ledgerId, stage: 1, nowMs: self.nowMs())
                        case .uploading:
                            self.publishStep = .upload
                            self.jobStore.update(ledgerId, stage: 2, nowMs: self.nowMs())
                        case .verifying:
                            self.publishStep = .verify
                            self.jobStore.update(ledgerId, stage: 3, nowMs: self.nowMs())
                        }
                    }
                )
                // 3. Kind-20 through the receipt machine. Post-details
                // extras (explicit t-tags + license) ride every mode;
                // remix lineage merges ahead of them on the picture path.
                publishState = .publishing
                var lineageTags: [[String]] = []
                if !remixEventId.isEmpty {
                    let remixJson = client.memeRemixTagsFor(
                        projectJson: project, sourceEventId: remixEventId,
                        sourcePubkey: remixAuthor
                    )
                    if let data = remixJson.data(using: .utf8),
                       let array = try? JSONSerialization.jsonObject(with: data) as? [[String]] {
                        lineageTags = array
                    }
                }
                let extrasJson = Self.encodeTags(lineageTags + extraTags)
                publishStep = .build
                jobStore.update(
                    ledgerId, stage: 4, mediaUrl: uploaded.url, sha256: uploaded.hash,
                    nowMs: nowMs()
                )
                let onStage: @MainActor (MemeNoteStage) -> Void = { stage in
                    switch stage {
                    case .built(let eventId):
                        self.publishEventId = eventId
                        self.publishStep = .sign
                        self.jobStore.update(ledgerId, stage: 5, eventId: eventId, nowMs: self.nowMs())
                    case .signed:
                        self.publishStep = .relay
                        self.jobStore.update(ledgerId, stage: 6, nowMs: self.nowMs())
                    case .relayed:
                        self.publishStep = .confirm
                        self.jobStore.update(ledgerId, stage: 7, nowMs: self.nowMs())
                    }
                }
                if isVideoMode {
                    await publisher.publishMemeVideoNote(
                        caption: caption,
                        altText: altText,
                        contentWarningReason: contentWarningReason,
                        url: uploaded.url, hash: uploaded.hash, size: bytes.count,
                        width: width, height: height, durationMs: videoDurationMs,
                        thumbUrl: coverThumbUrl,
                        extraTagsJson: extrasJson,
                        onStage: onStage
                    )
                } else {
                    await publisher.publishMemePictureNote(
                        caption: caption,
                        altText: altText,
                        contentWarningReason: contentWarningReason,
                        url: uploaded.url, hash: uploaded.hash, size: bytes.count,
                        width: width, height: height,
                        remixTagsJson: extrasJson,
                        onStage: onStage
                    )
                }
                publishState = .done
                if publisher.result == .published {
                    jobStore.finish(ledgerId, nowMs: nowMs())
                } else {
                    jobStore.update(
                        ledgerId, status: "failed",
                        error: "No relay confirmed the event — it may still land; verify before retrying.",
                        nowMs: nowMs()
                    )
                }
            } catch {
                publishState = .idle
                publishFailure = error.localizedDescription
                jobStore.update(ledgerJobId ?? 0, status: "failed", error: error.localizedDescription, nowMs: nowMs())
            }
        }
    }

    private func nowMs() -> Int64 {
        Int64(Date.now.timeIntervalSince1970 * 1000)
    }

    /// Queue retry: re-runs the machine from the persisted media (the job's
    /// bytes ARE the render). Upload is idempotent by hash; refused while an
    /// event id exists (that note may already be live — verify instead).
    func resumePublish(
        jobId: Int,
        identity: IdentityStore,
        publisher: NotePublisher,
        bridge: BusinessCoreBridge
    ) {
        guard publishState != .uploading, publishState != .publishing,
              let job = jobStore.job(jobId), job.retryAllowed,
              let bytes = jobStore.loadBytes(job) else { return }
        publisher.dismiss()
        publishState = .uploading
        publishFailure = nil
        publishStep = .hash
        publishJobId = job.id
        publishEventId = job.eventId
        ledgerJobId = job.id
        let extrasJson = job.extraTagsJson
        Task { [weak self] in
            guard let self else { return }
            do {
                let uploaded = try await BlossomUploader(bridge: bridge).upload(
                    bytes: bytes, mimeType: job.mime, identity: identity,
                    serverUrl: "https://blossom.primal.net",
                    onStage: { stage in
                        switch stage {
                        case .hashing:
                            self.publishStep = .hash
                            self.jobStore.update(job.id, stage: 1, nowMs: self.nowMs())
                        case .uploading:
                            self.publishStep = .upload
                            self.jobStore.update(job.id, stage: 2, nowMs: self.nowMs())
                        case .verifying:
                            self.publishStep = .verify
                            self.jobStore.update(job.id, stage: 3, nowMs: self.nowMs())
                        }
                    }
                )
                publishState = .publishing
                self.publishStep = .build
                self.jobStore.update(job.id, stage: 4, mediaUrl: uploaded.url, sha256: uploaded.hash, nowMs: self.nowMs())
                let onStage: @MainActor (MemeNoteStage) -> Void = { stage in
                    switch stage {
                    case .built(let eventId):
                        self.publishEventId = eventId
                        self.publishStep = .sign
                        self.jobStore.update(job.id, stage: 5, eventId: eventId, nowMs: self.nowMs())
                    case .signed:
                        self.publishStep = .relay
                        self.jobStore.update(job.id, stage: 6, nowMs: self.nowMs())
                    case .relayed:
                        self.publishStep = .confirm
                        self.jobStore.update(job.id, stage: 7, nowMs: self.nowMs())
                    }
                }
                if job.mode == "video" {
                    await publisher.publishMemeVideoNote(
                        caption: job.caption, altText: job.altText,
                        contentWarningReason: job.contentWarningReason,
                        url: uploaded.url, hash: uploaded.hash, size: bytes.count,
                        width: job.width, height: job.height, durationMs: job.durationMs,
                        thumbUrl: job.coverThumbUrl,
                        extraTagsJson: extrasJson,
                        onStage: onStage
                    )
                } else {
                    await publisher.publishMemePictureNote(
                        caption: job.caption, altText: job.altText,
                        contentWarningReason: job.contentWarningReason,
                        url: uploaded.url, hash: uploaded.hash, size: bytes.count,
                        width: job.width, height: job.height,
                        remixTagsJson: extrasJson,
                        onStage: onStage
                    )
                }
                publishState = .done
                if publisher.result == .published {
                    jobStore.finish(job.id, nowMs: nowMs())
                } else {
                    jobStore.update(
                        job.id, status: "failed",
                        error: "No relay confirmed the event — it may still land; verify before retrying.",
                        nowMs: nowMs()
                    )
                }
            } catch {
                publishState = .idle
                publishFailure = error.localizedDescription
                jobStore.update(job.id, status: "failed", error: error.localizedDescription, nowMs: nowMs())
            }
        }
    }

    // MARK: - Wire helpers

    static func encode(_ object: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// TagsCodec `[[name,…],…]` JSON for the extra-tags seams (t-tags,
    /// license, remix lineage merged).
    static func encodeTags(_ tags: [[String]]) -> String {
        guard JSONSerialization.isValidJSONObject(tags),
              let data = try? JSONSerialization.data(withJSONObject: tags) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }
}

struct MemeEditorView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @State private var store: MemeEditorStore
    @State private var stageSize: CGSize = .zero
    @State private var showDiscard = false
    @State private var editingOverlayId: String?
    /** Inline tool panel (prototype `create-edit` panels open under the
     * quick-tool chips instead of covering the canvas with a sheet). */
    @State private var activePanel: EditorPanel?
    @State private var showLooks = false
    @State private var showSfx = false
    @State private var showLayers = false
    @State private var showTrim = false
    @State private var showSpeed = false
    @State private var showCanvas = false
    /** M5 per-clip management (select · reorder · remove · add). */
    @State private var showClipSheet = false
    /** M5 per-clip audio (volume · mute). */
    @State private var showVolume = false
    /** V2 Draw mode: pen strokes captured on the stage (all modes). */
    @State private var drawMode = false
    @State private var penColorIndex = 2
    @State private var penWidthNorm: Float = 0.008
    @State private var liveStrokePoints: [Float] = []
    /** V2 suite expert dock (mockup app-15 scr-suite) — video mode only. */
    @State private var suiteMode = false
    private let videoTransport = VideoTransportIos()
    @State private var videoPositionSec: Double = 0
    /** Prototype flow: editor → Post details → Preflight → publish. */
    @State private var showDetailsFlow = false
    /** MUX-04: output settings before any export fires. */
    @State private var showExportSheet = false
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var confirmModeSwitch = false
    @State private var pendingGifMode = false
    @State private var pendingVideoMode = false
    @State private var videoTrimData: Data?
    @State private var showVideoTrim = false
    @State private var videoPickItem: PhotosPickerItem?
    @State private var slotId: String

    let slotStore: MemeProjectStore?
    let resumeSlot: MemeProjectStore.SavedSlot?
    let templateId: String?
    let sharedTagsJson: String?
    let sharedContent: String?
    /** CAP handoff: camera record-screen take that seeds video mode. */
    let videoSeed: Data?
    /// M5 take-native handoff: ALL camera takes as timeline clips.
    let videoSeeds: [Data]?
    /// MUX-06: hand the frozen design + rendered poster to mass production.
    var onMakeVariations: ((String, Data) -> Void)? = nil
    let onSlotsChanged: () -> Void

    init(
        slotStore: MemeProjectStore? = nil,
        resumeSlot: MemeProjectStore.SavedSlot? = nil,
        templateId: String? = nil,
        sharedTagsJson: String? = nil,
        sharedContent: String? = nil,
        videoSeed: Data? = nil,
        videoSeeds: [Data]? = nil,
        onSlotsChanged: @escaping () -> Void = {},
        onMakeVariations: ((String, Data) -> Void)? = nil
    ) {
        self.slotStore = slotStore
        self.resumeSlot = resumeSlot
        self.templateId = templateId
        self.sharedTagsJson = sharedTagsJson
        self.sharedContent = sharedContent
        self.videoSeed = videoSeed
        self.videoSeeds = videoSeeds
        self.onSlotsChanged = onSlotsChanged
        self.onMakeVariations = onMakeVariations
        let seed = resumeSlot?.document.projectJson
        _store = State(initialValue: {
            let store = MemeEditorStore()
            if let seed { store.restore(projectJson: seed) }
            // MST-040: template seeds overlay onto the (restored) project.
            if let templateId {
                let seeded = FrameworkBusinessCoreClient().memeApplyTemplate(
                    store.projectJson, templateId: templateId
                )
                if !seeded.isEmpty { store.restore(projectJson: seeded) }
            }
            // MST-045: shared (kind-30078) template seeds the same way.
            if let sharedTagsJson, let sharedContent {
                let seeded = FrameworkBusinessCoreClient().memeApplySharedTemplate(
                    projectJson: store.projectJson, tagsJson: sharedTagsJson, content: sharedContent
                )
                if !seeded.isEmpty { store.restore(projectJson: seeded) }
            }
            return store
        }())
        _slotId = State(initialValue: resumeSlot?.document.slotId
            ?? "s-" + UUID().uuidString.prefix(13))
    }

    var body: some View { sheetLayer }

    /// The bare chrome/branch/next column.
    private var layoutLayer: some View {
        VStack(spacing: 0) {
            chrome
            if suiteMode && store.isVideoMode && !store.clips.isEmpty {
                // Expert suite keeps the full-bleed stage + dock layout
                // (mockup app-15 scr-suite); the prototype layout below is
                // the default editor experience.
                suiteLayout
            } else {
                editorLayout
            }
        }
    }

    /// Layout + lifecycle (autosave, publish watch, GIF loop, session seed,
    /// mode/discard dialogs) — split out of `body` so no single expression
    /// exceeds the type-checker's budget.
    private var lifecycleLayer: some View {
        layoutLayer
        .background(BitOSTheme.background)
        .preferredColorScheme(nil)
        // MST-018: debounced autosave keyed on every wire/asset/clip change.
        .task(id: "save-\(store.revision)-\(store.assets.count)-\(store.activeAssetId ?? "")-\(store.videoRevision)") {
            await runAutosave()
        }
        .onChange(of: store.publishState) { _, phase in
            if phase == .done { clearSlot() }
        }
        // MST-021: the stage loops the frame reel at each frame's hold.
        .task(id: "loop-\(store.gifRevision)-\(store.gifPreviewTick)") {
            guard store.isGifMode, store.gifFramesCount > 1 else { return }
            try? await Task.sleep(nanoseconds: UInt64(store.gifCurrentHoldMs()) * 1_000_000)
            guard !Task.isCancelled else { return }
            store.advanceGifPreview()
        }
        .onAppear { seedEditorSession() }

        .confirmationDialog(
            pendingVideoMode ? "Start a video project?"
                : pendingGifMode ? "Start a GIF project?" : "Start an image project?",
            isPresented: $confirmModeSwitch,
            titleVisibility: .visible
        ) {
            Button("Switch") {
                if pendingVideoMode {
                    store.switchModeToVideo()
                } else if pendingGifMode {
                    store.switchModeToGif()
                } else {
                    store.switchModeToImage()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Switching clears the current media (overlays stay).")
        }
        .confirmationDialog(
            "Could not save the draft",
            isPresented: $showDiscard,
            titleVisibility: .visible
        ) {
            Button("Retry save") {
                Task {
                    await saveDraftNow()
                    if store.draftSaveState == .saved { dismiss() }
                }
            }
            Button("Keep editing", role: .cancel) {}
            Button("Delete draft", role: .destructive) {
                clearSlot()
                dismiss()
            }
        } message: {
            Text("Your edits are still open. Retry the save to keep them, or delete the draft deliberately.")
        }
    }

    /// Sheets, pickers and the trim cover (the presentation layer).
    private var sheetLayer: some View {
        lifecycleLayer
        .sheet(isPresented: Binding(
            get: { editingOverlayId != nil },
            set: { if !$0 { editingOverlayId = nil } }
        )) {
            if let id = editingOverlayId {
                TextSheet(store: store, overlayId: id)
                    .presentationDetents([.medium, .large])
            }
        }
        .sheet(isPresented: $showLooks) {
            LooksSheet(store: store)
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $showSfx) {
            SfxSheet(store: store, positionSec: videoPositionSec)
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showLayers) {
            LayersSheetView(
                store: store,
                onInsert: {
                    showLayers = false
                    isPicking = true
                }
            )
            .presentationDetents([.medium])
        }
        .sheet(isPresented: $showTrim) {
            TrimSheetView(store: store)
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $showClipSheet) {
            ClipsSheetView(
                store: store,
                onAdd: {
                    showClipSheet = false
                    isPickingVideo = true
                }
            )
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $showVolume) {
            VolumeSheetView(store: store)
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $showSpeed) {
            SpeedSheetView(store: store)
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $showCanvas) {
            CanvasSheet(store: store)
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $showDetailsFlow) {
            MemePostFlowView(
                store: store,
                identity: identity,
                publisher: environment.notePublisher,
                onPublished: {
                    showDetailsFlow = false
                    dismiss()
                }
            )
        }
        .photosPicker(
            isPresented: $isPicking,
            selection: $pickerItems,
            maxSelectionCount: MemeEditorStore.imageAssetCap,
            matching: .images
        )
        .photosPicker(
            isPresented: $isPickingVideo,
            selection: $videoPickItem,
            matching: .videos
        )
        .onChange(of: pickerItems) { _, items in
            guard !items.isEmpty else { return }
            Task { await loadPicked(items) }
        }
        .onChange(of: videoPickItem) { _, item in
            guard let item else { return }
            videoPickItem = nil
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self),
                      let url = MemeVideoExportIos.writeTempClip(data),
                      let probe = MemeVideoExportIos.probe(url: url) else {
                    store.setExportFailure("Clip unreadable")
                    return
                }
                // Long clips are NOT rejected — setVideoClip cuts them to
                // the allowed window and says why (MST-030 revision).
                videoTrimData = data
                showVideoTrim = true
            }
        }
        .fullScreenCover(isPresented: $showVideoTrim) {
            if let data = videoTrimData {
                // MST-030: the picked clip flows through the EXISTING trim
                // screen; its "use" output feeds the meme stage unchanged.
                VideoPreviewScreen(
                    data: data,
                    mimeType: "video/mp4",
                    onUse: { trimmed, _ in
                        showVideoTrim = false
                        videoTrimData = nil
                        store.setVideoClip(data: trimmed)
                    },
                    onRetake: {
                        showVideoTrim = false
                        videoTrimData = nil
                    }
                )
            }
        }
    }

    /// Session seeding (CAP handoff, trim handoff, slot resume).
    private func seedEditorSession() {
        // CAP handoff (M5): camera takes enter video mode AS CLIPS — no
        // merge, each take probed + cut by the shared rules.
        if let videoSeeds, !videoSeeds.isEmpty, store.clips.isEmpty,
           store.assets.isEmpty, store.gifFramesCount == 0 {
            store.switchModeToVideo()
            for seed in videoSeeds {
                // Seeding IS the session start, not a user edit step.
                store.appendClip(data: seed, undoable: false)
            }
            return
        }
        if let videoSeed, store.videoClipData == nil, store.assets.isEmpty,
           store.gifFramesCount == 0 {
            store.switchModeToVideo()
            videoTrimData = videoSeed
            showVideoTrim = true
            return
        }
        // Resume: seed the asset tray (image mode) or the frame tray
        // (GIF mode — holds collapse to a uniform 100 ms in V1).
        guard let resumeSlot, store.assets.isEmpty, store.gifFramesCount == 0,
              store.videoClipData == nil else { return }
            if store.isVideoMode {
            // M5: the wire's clip list + slot asset files rebuild the
            // whole timeline (v1 slots migrate into a single clip).
            let wireEntries = store.wireClipEntries()
            let entries = wireEntries.compactMap { entry -> (id: String, url: URL, startMs: Int64, endMs: Int64, volume: Float, lookId: String?)? in
                guard let url = resumeSlot.assetFiles[entry.id] else { return nil }
                return (entry.id, url, entry.startMs, entry.endMs, entry.volume, entry.lookId)
            }
            if !entries.isEmpty {
                store.restoreClips(from: entries)
            }
            // Every wire clip that did not come back marks the session
            // incomplete (file/probe failure) — visible to the user, and
            // autosave stands down so the last good slot survives.
            store.setTimelineRestoreDroppedClips(wireEntries.count - store.clips.count)
            if store.timelineRestoreDroppedClips > 0 {
                store.setNotice(
                    "\(store.timelineRestoreDroppedClips) timeline clip(s) could not be restored — "
                        + "the last saved draft is kept; reopen it to retry"
                )
            }
            let videoClipIds = Set(store.clips.map(\.id))
            if !store.clips.isEmpty {
                // IMAGE layers resume with the slot (their PNG assets
                // ride the same assetFiles map; GIF-inserts stay still).
                for asset in resumeSlot.document.assets where !videoClipIds.contains(asset.id) {
                    if let url = resumeSlot.assetFiles[asset.id],
                       let image = UIImage(contentsOfFile: url.path) {
                        store.addAsset(image: image)
                    }
                }
            }
            return
        }
        for asset in resumeSlot.document.assets.sorted(by: { $0.id < $1.id }) {
            if let url = resumeSlot.assetFiles[asset.id],
               let image = UIImage(contentsOfFile: url.path) {
                if store.isGifMode {
                    store.addGifFrames([
                        MemeEditorStore.MemeGifFrame(
                            id: asset.id, image: image, delayMs: 100
                        ),
                    ])
                } else {
                    store.addAsset(image: image)
                }
            }
        }
    }

    /// Expert-suite layout: full-bleed flexible stage + dock (app-15 scr-suite).
    private var suiteLayout: some View {
        VStack(spacing: 0) {
            stage
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .overlay {
                    if drawMode { drawCaptureOverlay }
                }
                .overlay(alignment: .bottomLeading) {
                    if !stageMetaChips.isEmpty { stageMetaChipRow }
                }
            if drawMode { penControlsRow }
            tray
        }
    }

    /// Prototype `#/create-edit` layout: scrolling canvas → mode pills +
    /// undo → quick tools → tray → timeline, with the per-mode bar +
    /// status line pinned below. Tool panels open as native bottom sheets.
    private var editorLayout: some View {
        VStack(spacing: 0) {
            GeometryReader { available in
            ScrollView(showsIndicators: false) {
                VStack(spacing: BitOSTheme.Spacing.sm) {
                    stageCard(height: max(180, available.size.height - (store.isVideoMode ? 240 : 190)))
                    modePillsRow
                    quickTools
                    if drawMode { penControlsRow }
                    // The prototype timeline is already the compact clip
                    // strip. Video source insertion lives in Timeline so
                    // the basic editor does not show a duplicate strip.
                    if !store.isVideoMode { tray }
                    if store.isVideoMode && !store.clips.isEmpty {
                        timelineSection
                    }
                }
                .padding(.horizontal, BitOSTheme.Spacing.base)
                .padding(.top, BitOSTheme.Spacing.xs)
                .padding(.bottom, BitOSTheme.Spacing.sm)
            }
            }
            if store.selectedId != nil {
                selectionControls
            }
            perModeBar
            statusLine
        }
        .sheet(item: $activePanel) { panel in
            editorPanel(panel)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showExportSheet) {
            ExportSettingsSheet(store: store, onMakeVariations: onMakeVariations)
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
        }
    }

    private var penControlsRow: some View {
        PenControlsRowIos(
            store: store,
            colorIndex: penColorIndex,
            onPickColor: { penColorIndex = $0 },
            widthNorm: penWidthNorm,
            onPickWidth: { penWidthNorm = $0 },
            onDone: {
                liveStrokePoints = []
                drawMode = false
            }
        )
    }

    // ── Top chrome (prototype topbar: back · "Editor" · draft save) ────

    private var chrome: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            Button {
                Task {
                    // MUX-01: closing persists first — leave-with-work keeps
                    // the draft; a failed save offers retry, never a silent
                    // "discard". Deleting the draft is its own deliberate act.
                    if store.isEmpty {
                        clearSlot()
                        dismiss()
                        return
                    }
                    await saveDraftNow()
                    if store.draftSaveState == .saved {
                        dismiss()
                    } else {
                        showDiscard = true
                    }
                }
            } label: {
                AppIcons.image(for: AppIcons.close)
                    .foregroundStyle(BitOSTheme.textPrimary)
            }
            .accessibilityLabel("Close editor")
            Text("Editor")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(BitOSTheme.textPrimary)
            Spacer(minLength: 0)
            headerNextButton
            Button {
                Task {
                    await saveDraftNow()
                    store.setNotice(
                        store.draftSaveState == .saved
                            ? "Draft saved ✓ — resumes from Create hub"
                            : "Could not save the draft — check storage and try again"
                    )
                }
            } label: {
                Group {
                    if store.draftSaveState == .saving {
                        ProgressView()
                    } else if store.draftSaveState == .failed {
                        AppIcons.image(for: AppIcons.close)
                            .foregroundStyle(BitOSTheme.warning)
                    } else {
                        AppIcons.image(for: AppIcons.save)
                            .foregroundStyle(
                                store.draftSaveState == .saved
                                    ? BitOSTheme.success : BitOSTheme.textPrimary
                            )
                    }
                }
            }
            .disabled(slotStore == nil)
            .accessibilityLabel("Save draft")
        }
        .padding(.horizontal, BitOSTheme.Spacing.md)
        .padding(.vertical, BitOSTheme.Spacing.sm)
    }

    // ── Mode pills + undo (prototype mode switcher row) ────────────────

    private var modePillsRow: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            HStack(spacing: 4) {
                ModePill(label: "VIDEO", active: store.isVideoMode) {
                    requestModeSwitch(toVideo: true)
                }
                ModePill(label: "GIF", active: store.isGifMode) {
                    requestModeSwitch(toGif: true)
                }
                ModePill(label: "IMAGE", active: !store.isGifMode && !store.isVideoMode) {
                    requestModeSwitch(toGif: false)
                }
            }
            .padding(4)
            .background(Capsule().fill(BitOSTheme.surface))
            .overlay(Capsule().strokeBorder(BitOSTheme.border, lineWidth: 1))
            Spacer(minLength: 0)
            // Source add (frame/image/clip per mode) lives here with undo so
            // the pick affordance isn't buried in the tray.
            if store.isVideoMode {
                Button {
                    isPickingVideo = true
                } label: {
                    AppIcons.image(for: AppIcons.video)
                        .foregroundStyle(BitOSTheme.textPrimary)
                }
                .accessibilityLabel("Add clip")
            }
            Button {
                isPicking = true
            } label: {
                AppIcons.image(for: AppIcons.photo)
                    .foregroundStyle(BitOSTheme.textPrimary)
            }
            .accessibilityLabel(
                store.isGifMode ? "Add frames"
                    : (store.isVideoMode ? "Add image layer" : "Add image")
            )
            Button {
                store.undo()
            } label: {
                AppIcons.image(for: AppIcons.undo)
                    .foregroundStyle(store.canUndo ? BitOSTheme.textPrimary : BitOSTheme.textSecondary.opacity(0.4))
            }
            .disabled(!store.canUndo)
            .accessibilityLabel("Undo")
        }
    }

    // ── Stage ───────────────────────────────────────────────────────────

    /** Media aspect for the draw layer's fitted rect (stage parity). */
    private var mediaAspect: CGFloat {
        // A pinned canvas ratio owns the stage frame (media letterboxes).
        if let canvas = store.canvasAspect { return canvas }
        if store.isVideoMode, let probe = store.videoProbe {
            return CGFloat(probe.uprightWidth) / CGFloat(max(1, probe.uprightHeight))
        }
        if store.isGifMode, let frame = store.gifActiveFrame {
            return frame.image.size.width / max(1, frame.image.size.height)
        }
        if let asset = store.activeAsset {
            return asset.aspect > 0 ? asset.aspect : 0.5625
        }
        return 0.5625
    }

    /** Draw-mode overlay: fitted to the media rect, owns all touches. */
    private var drawCaptureOverlay: some View {
        DrawLayerIos(
            strokes: store.drawStrokes,
            livePoints: liveStrokePoints,
            colorIndex: penColorIndex,
            widthNorm: penWidthNorm,
            paletteHex: store.paletteHex,
            aspect: mediaAspect,
            onLiveChange: { liveStrokePoints = $0 },
            onStroke: { points in
                liveStrokePoints = []
                store.addStroke(colorIndex: penColorIndex, widthNorm: penWidthNorm, points: points)
            }
        )
    }

    private var stage: some View {
        GeometryReader { proxy in
            let container = proxy.size
            ZStack {
                if store.isVideoMode, !store.clips.isEmpty {
                    VideoStageIos(
                        clips: store.clips,
                        rate: store.rate,
                        store: store,
                        projectJson: store.projectJson,
                        client: environment.businessCore,
                        coverSet: store.coverThumbUrl != nil,
                        onSetCover: { seconds in
                            guard let mapped = store.timelineToMedia(Int64(seconds * 1000)) else { return }
                            Task {
                                guard let jpeg = MemeVideoExportIos.captureCoverJpeg(
                                    clipURL: mapped.clip.url, seconds: Double(mapped.mediaMs) / 1000
                                ) else {
                                    store.setExportMessage("Cover capture failed")
                                    return
                                }
                                do {
                                    let bridge = (environment.businessCore as? FrameworkBusinessCoreClient)?
                                        .bridgeForFollowing() ?? BusinessCoreBridge()
                                    let uploaded = try await BlossomUploader(bridge: bridge).upload(
                                        bytes: jpeg, mimeType: "image/jpeg", identity: identity,
                                        serverUrl: "https://blossom.primal.net"
                                    )
                                    store.setCoverThumbUrl(uploaded.url)
                                } catch {
                                    store.setExportMessage("Cover upload failed")
                                }
                            }
                        },
                        cueAtSeconds: store.sfxCues.map { Double($0.atMs) / 1000 },
                        onPositionChange: { videoPositionSec = $0 },
                        layerImages: store.layerImages,
                        showScrub: !suiteMode,
                        transport: videoTransport
                    )
                } else if store.isGifMode, let frame = store.gifActiveFrame {
                    let aspect = frame.image.size.width / max(1, frame.image.size.height)
                    let fitted = fittedStageSize(container: container, aspect: aspect)
                    ZStack {
                        // WYSIWYG: the frame previews through the same
                        // grade cache the export burns (look + adjust).
                        Image(uiImage: store.gradedImage(frame.image, cacheKey: frame.id))
                            .resizable()
                            .scaledToFill()
                            .frame(width: fitted.width, height: fitted.height)
                            .clipped()
                        ForEach(store.overlays) { overlay in
                            OverlayUiView(
                                overlay: overlay,
                                stageSize: fitted,
                                selected: overlay.id == store.selectedId,
                                paletteHex: store.paletteHex
                            )
                        }
                        if let selected = store.overlays.first(where: { $0.id == store.selectedId }),
                           let bounds = store.boundsFraction(for: selected.id) {
                            DeleteHandleView(
                                overlay: selected,
                                stageSize: fitted,
                                bounds: bounds
                            )
                        }
                        if store.selectedId != nil {
                            VStack {}
                                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                                .overlay(alignment: .topLeading) {
                                    Text("drag · scale · rotate")
                                        .font(.system(size: 9, weight: .semibold))
                                        .foregroundStyle(.white)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 3)
                                        .background(Capsule().fill(Color.black.opacity(0.55)))
                                }
                                .allowsHitTesting(false)
                        }
                    }
                    .frame(width: fitted.width, height: fitted.height)
                    .stageGestures(store: store, stageSize: fitted)
                } else if let asset = store.activeAsset {
                    let fitted = fittedStageSize(container: container, aspect: asset.aspect)
                    ZStack {
                        Image(uiImage: store.gradedImage(asset.image, cacheKey: asset.id))
                            .resizable()
                            .scaledToFill()
                            .frame(width: fitted.width, height: fitted.height)
                            .clipped()
                        ForEach(store.overlays) { overlay in
                            OverlayUiView(
                                overlay: overlay,
                                stageSize: fitted,
                                selected: overlay.id == store.selectedId,
                                paletteHex: store.paletteHex
                            )
                        }
                        if let selected = store.overlays.first(where: { $0.id == store.selectedId }),
                           let bounds = store.boundsFraction(for: selected.id) {
                            DeleteHandleView(
                                overlay: selected,
                                stageSize: fitted,
                                bounds: bounds
                            )
                        }
                        if store.selectedId != nil {
                            VStack {}
                                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                                .overlay(alignment: .topLeading) {
                                    Text("drag · scale · rotate")
                                        .font(.system(size: 9, weight: .semibold))
                                        .foregroundStyle(.white)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 3)
                                        .background(Capsule().fill(Color.black.opacity(0.55)))
                                }
                                .allowsHitTesting(false)
                        }
                    }
                    .frame(width: fitted.width, height: fitted.height)
                    .stageGestures(store: store, stageSize: fitted)
                } else {
                    emptyCta(container: container)
                }
            }
            .frame(width: container.width, height: container.height)
        }
        .padding(.horizontal, BitOSTheme.Spacing.md)
        .padding(.vertical, BitOSTheme.Spacing.sm)
    }

    private func fittedStageSize(container: CGSize, aspect: CGFloat) -> CGSize {
        guard aspect > 0 else { return container }
        let byWidth = CGSize(width: container.width, height: container.width / aspect)
        if byWidth.height <= container.height { return byWidth }
        return CGSize(width: container.height * aspect, height: container.height)
    }

    // ── Canvas meta chips (prototype `1080×1920 · 9:16` + duration) ─────

    /** Mode-dependent media facts said out loud on the canvas. */
    private var stageMetaChips: [String] {
        if store.isVideoMode {
            guard !store.clips.isEmpty else { return [] }
            let seconds = Int(store.timelineDurationMs) / 1000
            return [
                String(
                    format: "%02d:%02d · %d clip%@",
                    seconds / 60, seconds % 60, store.clips.count,
                    store.clips.count == 1 ? "" : "s"
                ),
            ]
        }
        if store.isGifMode {
            guard store.gifFramesCount > 0 else { return [] }
            let delay = store.gifUniformDelayMs > 0
                ? "\(store.gifUniformDelayMs) ms" : "source delay"
            return ["\(store.gifFramesCount) frames · \(delay)"]
        }
        guard let asset = store.activeAsset else { return [] }
        let width = Int((asset.image.size.width * asset.image.scale).rounded())
        let height = Int((asset.image.size.height * asset.image.scale).rounded())
        return ["\(width)×\(height) · \(ratioLabel(width, height))"]
    }

    private var stageMetaChipRow: some View {
        HStack(spacing: 4) {
            ForEach(stageMetaChips, id: \.self) { chip in
                Text(chip)
                    .font(.system(size: 9, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(Color.black.opacity(0.55)))
                    .allowsHitTesting(false)
            }
        }
        .padding(.leading, BitOSTheme.Spacing.md + 4)
        .padding(.bottom, 2)
    }

    /** Canonical ratio label with tolerance; exotic shapes fall to n:1. */
    private func ratioLabel(_ width: Int, _ height: Int) -> String {
        let aspect = CGFloat(width) / CGFloat(max(1, height))
        let canonical: [(String, CGFloat)] = [
            ("9:16", 9.0 / 16), ("3:4", 3.0 / 4), ("1:1", 1), ("4:5", 4.0 / 5),
            ("4:3", 4.0 / 3), ("16:9", 16.0 / 9),
        ]
        for (label, value) in canonical where abs(aspect - value) < 0.02 { return label }
        return String(format: "%.2f:1", aspect)
    }

    private func emptyCta(container: CGSize) -> some View {
        let side = min(container.width * 0.8, min(container.height, container.width * 0.8))
        return Button {
            if store.isVideoMode { isPickingVideo = true } else { isPicking = true }
        } label: {
            VStack(spacing: BitOSTheme.Spacing.sm) {
                AppIcons.image(for: AppIcons.photo)
                    .font(.system(size: 34, weight: .medium))
                    .foregroundStyle(BitOSTheme.accent)
                Text(store.isVideoMode ? "Pick a clip" : "Pick an image")
                    .font(.subheadline.weight(.semibold))
                Text(store.isVideoMode ? "Up to 60 seconds" : "Up to \(MemeEditorStore.imageAssetCap) images")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .frame(width: max(side, 120), height: max(side, 120))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .strokeBorder(BitOSTheme.border, style: StrokeStyle(lineWidth: 1.5, dash: [7, 5]))
            )
        }
    }

    @State private var isPicking = false
    @State private var isPickingVideo = false

    // ── Tray ────────────────────────────────────────────────────────────

    /** Mode switch with confirm when media exists (overlays survive). */
    private func requestModeSwitch(toGif: Bool = false, toVideo: Bool = false) {
        let hasMedia = !store.assets.isEmpty || store.gifFramesCount > 0 || store.videoClipData != nil
        if !hasMedia {
            if toVideo {
                store.switchModeToVideo()
            } else if toGif {
                store.switchModeToGif()
            } else {
                store.switchModeToImage()
            }
        } else {
            pendingGifMode = toGif
            pendingVideoMode = toVideo
            confirmModeSwitch = true
        }
    }

    @ViewBuilder
    private var tray: some View {
        if store.isVideoMode && suiteMode && !store.clips.isEmpty {
            SuiteDockView(
                store: store,
                transport: videoTransport,
                positionSec: videoPositionSec,
                onOpenLayers: { showLayers = true },
                onOpenLooks: { showLooks = true },
                onOpenSfx: { showSfx = true },
                onOpenDraw: { drawMode = true },
                onOpenTrim: { showTrim = true },
                onOpenSpeed: { showSpeed = true },
                onOpenClips: { showClipSheet = true },
                onOpenVolume: { showVolume = true },
                onSplit: { store.splitClip(atTimelineMs: Int64(videoPositionSec * 1000)) },
                onExport: { store.exportActiveAssetToPhotos() },
                onClose: { suiteMode = false }
            )
        } else if store.isGifMode {
            GifFrameTrayView(store: store)
        } else {
            // Video mode: the tray holds IMAGE layer sources; image mode
            // holds the background candidates.
            ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                ForEach(store.assets) { asset in
                    Button {
                        if store.isVideoMode {
                            // Tapping a layer selects its bound overlay.
                            if let layer = store.overlays.first(where: { $0.isImage && $0.assetId == asset.id }) {
                                store.select(layer.id)
                            }
                        } else {
                            store.activeAssetId = asset.id
                        }
                    } label: {
                        Image(uiImage: asset.image)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 56, height: 56)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .strokeBorder(
                                        asset.id == store.activeAssetId ? BitOSTheme.accent : BitOSTheme.border,
                                        lineWidth: asset.id == store.activeAssetId ? 2 : 1
                                    )
                            )
                    }
                    .accessibilityLabel(store.isVideoMode ? "Layer \(asset.id)" : "Asset \(asset.id)")
                }
                // Adding lives in the mode row beside undo (single
                // source-add affordance) — the tray only selects.
            }
            .padding(.horizontal, BitOSTheme.Spacing.md)
        }
        }
    }

    // ── Prototype layout sections ───────────────────────────────────────

    /** The canvas as a bounded card (prototype `ed-canvas`): fixed height,
     * rounded, meta chips pinned bottom-leading, gestures unchanged. */
    private func stageCard(height: CGFloat) -> some View {
        stage
            .frame(height: height)
            .frame(maxWidth: .infinity)
            .background(
                store.canvasBackgroundHex.flatMap { MemeEditorStore.colorHex($0) }
                    ?? BitOSTheme.surface
            )
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .overlay {
                if drawMode { drawCaptureOverlay }
            }
            .overlay(alignment: .bottomLeading) {
                if !stageMetaChips.isEmpty { stageMetaChipRow }
            }
    }

    /** Quick tool chips (prototype: Meme · Text · Stickers · Sound ·
     * Effects; Draw/Save stay as native extras). Chip taps toggle the
     * matching inline panel. */
    private var quickTools: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: BitOSTheme.Spacing.md) {
                QuickToolChip(
                    symbol: AppIcons.remix, label: "Meme", hot: true,
                    active: activePanel == .meme,
                    enabled: store.canAddOverlay
                ) {
                    togglePanel(.meme)
                }
                QuickToolChip(
                    symbol: AppIcons.textStyle, label: "Text",
                    active: activePanel == .text,
                    enabled: store.canAddOverlay
                ) {
                    togglePanel(.text)
                }
                QuickToolChip(
                    symbol: AppIcons.sticker, label: "Stickers",
                    active: activePanel == .stickers,
                    enabled: store.canAddOverlay
                ) {
                    togglePanel(.stickers)
                }
                QuickToolChip(
                    symbol: AppIcons.sfx, label: "Sound",
                    active: activePanel == .sound,
                    enabled: store.isVideoMode && store.videoClipData != nil
                ) {
                    togglePanel(.sound)
                }
                QuickToolChip(
                    symbol: AppIcons.looks, label: "Look",
                    active: activePanel == .fx,
                    enabled: store.activeAsset != nil || store.gifFramesCount > 0 ||
                        (store.isVideoMode && !store.clips.isEmpty)
                ) {
                    togglePanel(.fx)
                }
                QuickToolChip(
                    symbol: AppIcons.pen, label: "Draw",
                    active: drawMode,
                    enabled: true
                ) {
                    activePanel = nil
                    drawMode.toggle()
                }
                QuickToolChip(
                    symbol: AppIcons.save, label: "Export",
                    active: false,
                    enabled: (store.activeAsset != nil || store.gifFramesCount > 0 ||
                        store.videoClipData != nil) && store.exportState != .saving
                ) {
                    showExportSheet = true
                }
            }
        }
    }

    private func togglePanel(_ panel: EditorPanel) {
        drawMode = false
        activePanel = activePanel == panel ? nil : panel
    }

    /** The tool panel as a bottom sheet: title row + the panel content. */
    @ViewBuilder
    private func editorPanel(_ panel: EditorPanel) -> some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            HStack {
                Text(panel.title)
                    .font(.headline)
                Spacer()
                Button("Done") { activePanel = nil }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BitOSTheme.accent)
            }
            panelBody(panel)
        }
        .padding(.horizontal, BitOSTheme.Spacing.md)
        .padding(.top, BitOSTheme.Spacing.sm)
        .padding(.bottom, BitOSTheme.Spacing.lg)
        .background(BitOSTheme.background)
    }

    @ViewBuilder
    private func panelBody(_ panel: EditorPanel) -> some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            switch panel {
            case .meme:
                MemePanelContent(store: store)
            case .text:
                TextPanelContent(store: store)
            case .stickers:
                StickerPanelContent(store: store)
            case .sound:
                SoundPanelContent(
                    cueCount: store.sfxCues.count,
                    onOpenStudio: {
                        activePanel = nil
                        showSfx = true
                    }
                )
            case .fx:
                FxPanelContent(store: store)
            }
        }
    }

    /// Compact clip timeline (prototype `edTimeline`): proportional
    /// segments, selection, playhead + the Split/Delete/Mute/Speed/Layer
    /// clip-tool row — all wired to the real clip store ops the expert
    /// suite uses.
    private var timelineSection: some View {
        let totalMs = max(1, store.timelineDurationMs)
        let positionMs = min(Int64(videoPositionSec * 1000), totalMs)
        return VStack(spacing: BitOSTheme.Spacing.xs) {
            HStack {
                Text(monoClock(0))
                Spacer()
                Text("\(monoClock(positionMs)) / \(monoClock(totalMs))")
                    .foregroundStyle(BitOSTheme.accent)
                Spacer()
                Text(monoClock(totalMs))
            }
            .font(.system(size: 10, weight: .semibold, design: .monospaced))
            .foregroundStyle(BitOSTheme.textSecondary)
            GeometryReader { geo in
                let gaps = CGFloat(max(0, store.clips.count - 1)) * 2
                ZStack(alignment: .leading) {
                    HStack(spacing: 2) {
                        ForEach(Array(store.clips.enumerated()), id: \.element.id) { index, clip in
                            let fraction = CGFloat(store.clipOutputMs(clip)) / CGFloat(totalMs)
                            Button {
                                store.selectClip(index)
                            } label: {
                                VStack(spacing: 2) {
                                    Text("vdo \(index + 1)")
                                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                                    HStack(spacing: 3) {
                                        if clip.volume == 0 {
                                            Text("🔇").font(.system(size: 9))
                                        }
                                        Text("\(Int((store.clipOutputMs(clip) + 999) / 1000))s")
                                            .font(.system(size: 9, weight: .semibold, design: .monospaced))
                                    }
                                }
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 40)
                                .background(
                                    RoundedRectangle(cornerRadius: 6)
                                        .fill(index == store.selectedClipIndex
                                              ? BitOSTheme.accent.opacity(0.85)
                                              : BitOSTheme.textSecondary.opacity(0.35))
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 6)
                                        .strokeBorder(
                                            index == store.selectedClipIndex ? BitOSTheme.accent : .clear,
                                            lineWidth: 2
                                        )
                                )
                            }
                            .frame(width: max(0, (geo.size.width - gaps) * fraction))
                            .accessibilityLabel("Clip \(index + 1)")
                        }
                    }
                    Rectangle()
                        .fill(BitOSTheme.accent)
                        .frame(width: 2, height: 46)
                        .offset(x: geo.size.width * CGFloat(positionMs) / CGFloat(totalMs))
                        .allowsHitTesting(false)
                        .accessibilityLabel("Playhead")
                }
            }
            .frame(height: 46)
            if store.rate != 1 {
                Text("whole-timeline speed \(store.rate, specifier: "%.2f")×")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            HStack {
                Spacer()
                ClipTool(icon: "film", label: "Split") {
                    if store.splitClip(atTimelineMs: positionMs) {
                        store.setNotice("Clip split at the playhead")
                    } else {
                        store.setNotice("Nothing to split at the playhead")
                    }
                }
                Spacer()
                ClipTool(icon: "trash", label: "Delete") {
                    if store.clips.count > 1 {
                        store.removeClip(at: store.selectedClipIndex)
                        store.setNotice("Clip deleted")
                    } else {
                        store.setNotice("Keep at least one clip")
                    }
                }
                Spacer()
                ClipTool(icon: "mic.slash", label: "Mute") {
                    guard store.clips.indices.contains(store.selectedClipIndex) else { return }
                    let current = store.clips[store.selectedClipIndex].volume
                    store.setClipVolume(
                        index: store.selectedClipIndex,
                        volume: current == 0 ? 1 : 0
                    )
                    store.setNotice(current == 0 ? "Clip sound on" : "Clip muted")
                }
                Spacer()
                ClipTool(icon: "timer", label: "Speed") {
                    activePanel = nil
                    showSpeed = true
                }
                Spacer()
                ClipTool(icon: "square.3.layers.3d", label: "Layer") { showLayers = true }
                Spacer()
            }
        }
    }

    /// Accessible manipulation for the selected overlay (MUX-03): explicit
    /// nudge / resize / rotate / edit / delete controls — no precision
    /// gestures required. Every action is one undoable command.
    @ViewBuilder
    private var selectionControls: some View {
        if let overlay = store.overlays.first(where: { $0.id == store.selectedId }) {
            HStack(spacing: BitOSTheme.Spacing.md) {
                Button {
                    store.updateStyle(overlay.id, fields: ["x": overlay.x - 0.05])
                } label: { Image(systemName: "arrow.left") }
                    .accessibilityLabel("Nudge left")
                Button {
                    store.updateStyle(overlay.id, fields: ["x": overlay.x + 0.05])
                } label: { Image(systemName: "arrow.right") }
                    .accessibilityLabel("Nudge right")
                Button {
                    store.updateStyle(overlay.id, fields: ["y": overlay.y - 0.05])
                } label: { Image(systemName: "arrow.up") }
                    .accessibilityLabel("Nudge up")
                Button {
                    store.updateStyle(overlay.id, fields: ["y": overlay.y + 0.05])
                } label: { Image(systemName: "arrow.down") }
                    .accessibilityLabel("Nudge down")
                Button {
                    store.updateStyle(overlay.id, fields: ["scale": overlay.scale * 0.9])
                } label: { Image(systemName: "minus.magnifyingglass") }
                    .accessibilityLabel("Shrink")
                Button {
                    store.updateStyle(overlay.id, fields: ["scale": overlay.scale * 1.1])
                } label: { Image(systemName: "plus.magnifyingglass") }
                    .accessibilityLabel("Enlarge")
                Button {
                    store.updateStyle(overlay.id, fields: ["rot": overlay.rot - 15])
                } label: { Image(systemName: "arrow.counterclockwise") }
                    .accessibilityLabel("Rotate left")
                Button {
                    store.updateStyle(overlay.id, fields: ["rot": overlay.rot + 15])
                } label: { Image(systemName: "arrow.clockwise") }
                    .accessibilityLabel("Rotate right")
                if !overlay.isSticker && overlay.assetId == nil {
                    Button {
                        editingOverlayId = overlay.id
                    } label: { Image(systemName: "textformat") }
                        .accessibilityLabel("Edit text")
                }
                Button(role: .destructive) {
                    store.removeOverlay(overlay.id)
                } label: { Image(systemName: "trash") }
                    .accessibilityLabel("Delete overlay")
            }
            .font(.system(size: 14, weight: .semibold))
            .buttonStyle(.borderless)
            .foregroundStyle(BitOSTheme.textSecondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, BitOSTheme.Spacing.xs)
            .overlay(alignment: .top) { Divider() }
            .padding(.horizontal, BitOSTheme.Spacing.md)
        }
    }

    /** Per-mode bottom toolbar (prototype `edBar`). Real features open;
     * prototype-mocked slots say which wave ships them. */
    private var perModeBar: some View {
        HStack {
            if store.isVideoMode {
                ClipTool(icon: "film", label: "Clips") { showClipSheet = true }
                ClipTool(icon: "slider.horizontal.3", label: "Adjust") { togglePanel(.fx) }
                ClipTool(icon: "scissors", label: "Trim") { showTrim = true }
                ClipTool(icon: "square.3.layers.3d", label: "Overlay") {
                    activePanel = nil
                    showLayers = true
                }
                ClipTool(icon: "wand.and.stars", label: "Timeline") {
                    activePanel = nil
                    suiteMode = true
                }
            } else if store.isGifMode {
                ClipTool(icon: "rectangle.on.rectangle", label: "Canvas") { showCanvas = true }
                ClipTool(icon: "timer", label: "Speed") {
                    let next = store.gifUniformDelayMs >= 200 ? 50 : store.gifUniformDelayMs + 50
                    store.setGifUniformDelay(next)
                    store.setNotice("Frame hold \(next) ms")
                }
                ClipTool(icon: "arrow.triangle.2.circlepath", label: "Loop") {
                    store.setNotice("GIFs loop forever — nothing to set")
                }
                ClipTool(icon: "camera.filters", label: "Filter") { togglePanel(.fx) }
                ClipTool(icon: "textformat", label: "Text") { togglePanel(.text) }
            } else {
                ClipTool(icon: "rectangle.on.rectangle", label: "Canvas") { showCanvas = true }
                ClipTool(icon: "textformat", label: "Text") { togglePanel(.text) }
                ClipTool(icon: "camera.filters", label: "Filter") { togglePanel(.fx) }
                ClipTool(icon: "slider.horizontal.3", label: "Adjust") { togglePanel(.fx) }
            }
        }
        .padding(.vertical, BitOSTheme.Spacing.sm)
        .overlay(alignment: .top) {
            Divider()
        }
        .padding(.horizontal, BitOSTheme.Spacing.md)
    }

    /** Status line (export results, notices) — one 16 pt row. */
    private var statusLine: some View {
        HStack(spacing: BitOSTheme.Spacing.xs) {
            switch store.exportState {
            case .saving:
                ProgressView()
            case .saved:
                HStack(spacing: 4) {
                    AppIcons.image(for: AppIcons.checkCircle)
                    Text("Saved to Photos")
                }
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textSecondary)
            case .savedAdjusted(let note):
                HStack(spacing: 4) {
                    AppIcons.image(for: AppIcons.checkCircle)
                    Text(note)
                }
                .font(.caption2)
                .foregroundStyle(BitOSTheme.success)
            case .failed(let message):
                Text(message)
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.error)
            case .idle:
                if let notice = store.notice, !notice.isEmpty {
                    Text(notice)
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textSecondary)
                } else if store.selectedId != nil {
                    Text("drag · scale · rotate")
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(minHeight: 16)
        .padding(.horizontal, BitOSTheme.Spacing.md)
    }

    /// `1.2 s`-style media clock for the timeline ruler.
    private func monoClock(_ ms: Int64) -> String {
        String(format: "%02d:%02d", Int(ms / 1000) / 60, Int(ms / 1000) % 60)
    }

    /// Header publish entry (prototype "Next · post details") — inline with
    /// draft save so the bottom stack stays tool-only.
    private var headerNextButton: some View {
        let hasMedia = store.activeAsset != nil || store.gifFramesCount > 0 ||
            store.videoClipData != nil
        let busy = store.publishState == .uploading || store.publishState == .publishing
        // An incompletely restored timeline must not publish — the post
        // would be an irreversible partial video.
        let restoreIncomplete = store.isVideoMode && store.timelineRestoreDroppedClips > 0
        return Button {
            activePanel = nil
            showDetailsFlow = true
        } label: {
            Text("Next")
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, BitOSTheme.Spacing.sm)
                .padding(.vertical, 4)
        }
        .buttonStyle(.borderedProminent)
        .tint(BitOSTheme.accent)
        .disabled(!hasMedia || busy || restoreIncomplete)
        .accessibilityLabel("Next — post details")
    }

    private func loadPicked(_ items: [PhotosPickerItem]) async {
        if store.isGifMode {
            await loadPickedGifFrames(items)
            return
        }
        if store.isVideoMode {
            // V2 source insert: every pick becomes an IMAGE layer bound to
            // its fresh asset (GIFs paint their first frame — V1).
            for item in items {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let image = UIImage(data: data),
                   let id = store.addAsset(image: image) {
                    store.addImageOverlay(assetId: id)
                }
            }
            pickerItems = []
            return
        }
        // Image mode: the FIRST pick is the background; every later pick
        // STACKS on the canvas as a draggable image layer (the same
        // overlay binding video-mode inserts use).
        let hadBackground = !store.assets.isEmpty
        let room = MemeEditorStore.imageAssetCap - store.assets.count
        var added = 0
        for item in items where added < room {
            if let data = try? await item.loadTransferable(type: Data.self),
               let image = UIImage(data: data),
               let id = store.addAsset(image: image) {
                added += 1
                if hadBackground || added > 1 {
                    store.addImageOverlay(assetId: id)
                    store.setNotice("Layer added — drag to place it on the stack")
                }
            }
        }
        pickerItems = []
    }

    /** GIF mode: animated picks decode into frames; stills land as 100 ms. */
    private func loadPickedGifFrames(_ items: [PhotosPickerItem]) async {
        var frames: [MemeEditorStore.MemeGifFrame] = []
        for item in items where store.gifFramesCount + frames.count < 60 {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            let decoded = await Task.detached(priority: .userInitiated) {
                GifFrameSourceIos.decode(data)
            }.value
            if let decoded {
                decoded.images.enumerated().forEach { pair in
                    let delay = decoded.delaysMs.indices.contains(pair.offset)
                        ? decoded.delaysMs[pair.offset]
                        : GifFrameSourceIos.stillDelayMs
                    frames.append(
                        MemeEditorStore.MemeGifFrame(id: "new", image: pair.element, delayMs: delay)
                    )
                }
            } else if let image = UIImage(data: data) {
                frames.append(
                    MemeEditorStore.MemeGifFrame(
                        id: "new", image: image, delayMs: GifFrameSourceIos.stillDelayMs
                    )
                )
            }
        }
        store.addGifFrames(frames)
        pickerItems = []
    }

    // MARK: - Slot lifecycle (MST-018 autosave + clear)

    /// Debounced autosave: 500 ms after every committed edit or asset
    /// change, the session persists (assets copy-in + wire + poster +
    /// LRU index). Kill/relaunch loses nothing the user saw committed.
    private func runAutosave() async {
        guard let slotStore else { return }
        if store.isEmpty { return }
        try? await Task.sleep(nanoseconds: MemeSlotsApi.autosaveDebounceMs * 1_000_000)
        guard !Task.isCancelled else { return }
        await saveDraftNow()
    }

    /// Awaited durable save (MUX-01): the UI may only claim "saved" after
    /// the write returns; failures keep the draft open and offer retry.
    private func saveDraftNow() async {
        guard let slotStore else {
            store.draftSaveState = .saved
            return
        }
        // An incompletely restored timeline must never persist: the slot
        // save rewrites the asset list from this session, so a partial one
        // would erase the dropped clips' files from the draft for good.
        if store.isVideoMode && store.timelineRestoreDroppedClips > 0 {
            store.draftSaveState = .failed
            store.setNotice(
                "Timeline incompletely restored — the last saved draft is kept; reopen it to retry"
            )
            return
        }
        store.draftSaveState = .saving
        let wire = store.projectJson
        var assets = store.assets.map { ($0.id, $0.image) }
        // GIF frames persist as slot assets too (f1…fN, PNG bytes).
        assets += store.gifFrames.map { ($0.id, $0.image) }
        var dataAssets: [(id: String, data: Data, fileName: String)] = []
        // M5: every timeline clip source persists (v1…vN).
        for clip in store.clips {
            dataAssets.append((clip.id, clip.data, "asset-\(clip.id).mp4"))
        }
        let id = slotId
        // The awaited completion of the slot write IS the durable
        // acknowledgement this store exposes (non-failing API).
        _ = await Task.detached(priority: .utility) {
            slotStore.save(
                slotId: id, projectJson: wire, assets: assets,
                dataAssets: dataAssets,
                nowMs: Int64(Date.now.timeIntervalSince1970 * 1000)
            )
        }.value
        store.draftSaveState = .saved
        onSlotsChanged()
    }

    /// Deletes the WIP slot (published or discarded — both end the WIP).
    private func clearSlot() {
        guard let slotStore else { return }
        let id = slotId
        Task.detached(priority: .utility) {
            slotStore.deleteSlot(id)
        }
        onSlotsChanged()
    }
}


/// Prototype mode-switcher pill: uppercase, filled orange when active.
private struct ModePill: View {
    let label: String
    let active: Bool
    var action: () -> Void = {}

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 11, weight: .bold))
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(Capsule().fill(active ? BitOSTheme.accent : Color.clear))
                .foregroundStyle(active ? Color.black : BitOSTheme.textSecondary)
        }
        .accessibilityLabel("\(label) mode\(active ? ", selected" : "")")
    }
}

/// Prototype quick-tool: compact circular icon + caption. Keep a 52 pt
/// hit target while matching the web control's 46 px visual rhythm.
private struct QuickToolChip: View {
    let symbol: String
    let label: String
    var hot: Bool = false
    let active: Bool
    let enabled: Bool
    var action: () -> Void = {}

    var body: some View {
        Button(action: action) {
            VStack(spacing: 1) {
                AppIcons.image(for: symbol)
                    .font(.system(size: 15, weight: .medium))
                Text(label)
                    .font(.system(size: 9, weight: .bold))
                    .lineLimit(1)
            }
            .frame(width: 52, height: 52)
            .background(
                Circle().fill(
                    active ? BitOSTheme.accent.opacity(0.22)
                        : hot ? BitOSTheme.accent.opacity(0.12) : BitOSTheme.surface
                )
            )
            .overlay(
                Circle().strokeBorder(
                    active || hot ? BitOSTheme.accent : BitOSTheme.border,
                    lineWidth: 1
                )
            )
            .foregroundStyle(active || hot ? BitOSTheme.accent : BitOSTheme.textSecondary)
        }
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityLabel(label)
    }
}

/// Prototype clip-tool / per-mode-bar entry: icon over a 9 pt caption.
private struct ClipTool: View {
    let icon: String
    let label: String
    var action: () -> Void = {}

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .medium))
                Text(label)
                    .font(.system(size: 9, weight: .semibold))
            }
            .foregroundStyle(BitOSTheme.textSecondary)
        }
        .accessibilityLabel(label)
    }
}


/// One overlay: attributed text (outline/shadow) centered on its
/// normalized position, scaled + rotated around that center. IMAGE
/// layers paint their imported source instead of text.
struct OverlayUiView: View {
    let overlay: MemeOverlayUi
    let stageSize: CGSize
    var selected: Bool
    let paletteHex: [String]
    /// MST-044 display transform (default = none).
    var fx: FxTransformUi? = nil
    /// IMAGE layers: the resolved source image (nil = nothing paints).
    var layerImage: UIImage? = nil

    var body: some View {
        let center = CGPoint(
            x: CGFloat(overlay.x) * stageSize.width,
            y: CGFloat(overlay.y) * stageSize.height
        )
        // Font/outline px live on the 1080-HIGH reference (web
        // `paintOverlay`), shared with the export rasterizer → WYSIWYG.
        let fontPx = CGFloat(overlay.size) * stageSize.height / 1080 * CGFloat(overlay.scale)
        let outlinePx = CGFloat(overlay.outline) * stageSize.height / 1080 * CGFloat(overlay.scale)
        return ZStack {
            if overlay.isImage, let layerImage {
                // Source-insert layer: aspect-kept, height on the same
                // 1080 reference the export rasterizer uses.
                Image(uiImage: layerImage)
                    .resizable()
                    .scaledToFill()
                    .frame(
                        width: fontPx * max(0.2, layerImage.size.width / max(1, layerImage.size.height)),
                        height: fontPx
                    )
                    .clipped()
            } else {
                OutlinedTextView(
                    text: overlay.text,
                    font: Self.slotFont(overlay.font, size: fontPx),
                    color: Self.paletteColor(overlay.colorIndex, paletteHex: paletteHex),
                    outlinePx: overlay.isSticker ? 0 : outlinePx,
                    shadow: overlay.shadow
                )
            }
            if selected {
                // Dashed bounds + orange corner dots (mockup scr-quick).
                let bounds = RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(
                        BitOSTheme.accent,
                        style: StrokeStyle(lineWidth: 1.5, dash: [6, 4])
                    )
                    .padding(.horizontal, -8)
                    .padding(.vertical, -4)
                bounds
                bounds.overlay(alignment: .topLeading) {
                    CornerDot()
                }
                bounds.overlay(alignment: .bottomTrailing) {
                    CornerDot()
                }
            }
        }
        .position(
            CGPoint(
                x: center.x + CGFloat(fx?.dx ?? 0) * stageSize.width,
                y: center.y + CGFloat(fx?.dy ?? 0) * stageSize.height
            )
        )
        .scaleEffect(CGFloat(overlay.scale) * CGFloat(fx?.scale ?? 1))
        .rotationEffect(.degrees(Double(overlay.rot) + Double((fx?.rotateRad ?? 0) * 180 / .pi)))
        .opacity(Double(fx?.alpha ?? 1))
        .allowsHitTesting(false)
    }

    nonisolated static func paletteColor(_ index: Int, paletteHex: [String]) -> UIColor {
        let clamped = max(0, min(index, max(paletteHex.count - 1, 0)))
        guard clamped < paletteHex.count, let value = UInt32(paletteHex[clamped], radix: 16) else {
            return UIColor.white
        }
        return UIColor(red: CGFloat((value >> 16) & 0xFF) / 255,
                       green: CGFloat((value >> 8) & 0xFF) / 255,
                       blue: CGFloat(value & 0xFF) / 255,
                       alpha: 1)
    }

    nonisolated static func slotFont(_ slot: String, size: CGFloat) -> UIFont {
        switch slot {
        case "impact":
            return UIFont.systemFont(ofSize: size, weight: .black)
        case "serif":
            let base = UIFont.systemFont(ofSize: size, weight: .bold)
            guard let descriptor = base.fontDescriptor.withDesign(.serif) else { return base }
            return UIFont(descriptor: descriptor, size: size)
        case "mono":
            let base = UIFont.systemFont(ofSize: size, weight: .medium)
            guard let descriptor = base.fontDescriptor.withDesign(.monospaced) else { return base }
            return UIFont(descriptor: descriptor, size: size)
        default:
            return UIFont.systemFont(ofSize: size, weight: .bold)
        }
    }
}

/// Classic meme outline + shadow via NSAttributedString (negative stroke
/// width paints stroke under fill — the exact web `stroke` look).
private struct OutlinedTextView: UIViewRepresentable {
    let text: String
    let font: UIFont
    let color: UIColor
    let outlinePx: CGFloat
    let shadow: Bool

    func makeUIView(context: Context) -> UILabel {
        let label = UILabel()
        label.numberOfLines = 4
        label.lineBreakMode = .byWordWrapping
        label.textAlignment = .center
        return label
    }

    func updateUIView(_ label: UILabel, context: Context) {
        let attributes: NSMutableAttributedString = {
            let attributed = NSMutableAttributedString(string: text)
            let full = NSRange(location: 0, length: (text as NSString).length)
            attributed.addAttribute(.font, value: font, range: full)
            attributed.addAttribute(.foregroundColor, value: color, range: full)
            if outlinePx > 0 {
                attributed.addAttribute(.strokeWidth, value: -outlinePx * 2, range: full)
                attributed.addAttribute(.strokeColor, value: UIColor.black, range: full)
            }
            if shadow {
                let nsShadow = NSShadow()
                nsShadow.shadowColor = UIColor.black
                nsShadow.shadowOffset = CGSize(width: 3, height: 3)
                nsShadow.shadowBlurRadius = 3
                attributed.addAttribute(.shadow, value: nsShadow, range: full)
            }
            return attributed
        }()
        label.attributedText = attributes
    }
}

/// Mockup scr-quick: orange corner dot with a dark rim anchoring the
/// dashed selection bounds.
private struct CornerDot: View {
    var body: some View {
        Circle()
            .fill(BitOSTheme.accent)
            .overlay(Circle().strokeBorder(Color.black, lineWidth: 1.5))
            .frame(width: 9, height: 9)
            .padding(3)
    }
}

struct DeleteHandleView: View {
    let overlay: MemeOverlayUi
    let stageSize: CGSize
    let bounds: (width: Float, height: Float)

    var body: some View {
        let center = CGPoint(
            x: CGFloat(overlay.x) * stageSize.width + CGFloat(bounds.width) * stageSize.width / 2,
            y: CGFloat(overlay.y) * stageSize.height - CGFloat(bounds.height) * stageSize.height / 2
        )
        AppIcons.image(for: AppIcons.close)
            .font(.system(size: 10, weight: .bold))
            .foregroundStyle(Color(uiColor: .darkGray))
            .frame(width: 24, height: 24)
            .background(Circle().fill(.white))
            .overlay(Circle().strokeBorder(Color(uiColor: .darkGray), lineWidth: 1.5))
            .position(center)
            .allowsHitTesting(false)
    }
}

/// Stage gesture layer: down → live transform of the selection; up → tap
/// hit-tests deletion/selection (mirrors the Compose `stageGestures`).
private struct StageGestures: ViewModifier {
    let store: MemeEditorStore
    let stageSize: CGSize
    @State private var began = false
    @State private var moved = false
    @State private var startLocation: CGPoint = .zero
    @State private var lastPan: CGSize = .zero
    @State private var lastMagnification: CGFloat = 1
    @State private var lastRotation: CGFloat = 0

    private var tapRadius: CGFloat { 22 }

    func body(content: Content) -> some View {
        content
            .contentShape(Rectangle())
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        ensureBegan(at: value.startLocation)
                        let translation = value.translation
                        if abs(translation.width) > 1 || abs(translation.height) > 1 {
                            moved = true
                            // Incremental delta (translation is cumulative).
                            store.gesturePan(
                                dx: Float((translation.width - lastPan.width) / max(stageSize.width, 1)),
                                dy: Float((translation.height - lastPan.height) / max(stageSize.height, 1))
                            )
                            lastPan = translation
                        }
                    }
                    .onEnded { value in
                        defer { reset() }
                        guard began else { return }
                        if !moved {
                            handleTap(at: value.startLocation)
                        }
                    }
            )
            .simultaneousGesture(
                MagnifyGesture()
                    .onChanged { value in
                        guard began else { return }
                        let factor = value.magnification / lastMagnification
                        lastMagnification = value.magnification
                        store.gestureScale(by: Float(factor))
                    }
                    .onEnded { _ in lastMagnification = 1 }
            )
            .simultaneousGesture(
                RotateGesture()
                    .onChanged { value in
                        guard began else { return }
                        let degrees = value.rotation.degrees
                        let delta = degrees - lastRotation
                        lastRotation = degrees
                        store.gestureRotate(by: Float(delta))
                    }
                    .onEnded { _ in lastRotation = 0 }
            )
    }

    private func ensureBegan(at location: CGPoint) {
        guard !began else { return }
        began = true
        moved = false
        startLocation = location
        lastPan = .zero
        lastMagnification = 1
        lastRotation = 0
        store.beginGesture()
    }

    private func reset() {
        began = false
        moved = false
        store.endGesture()
    }

    private func handleTap(at point: CGPoint) {
        // Delete handle first (top-end of the selection bounds).
        if let selected = store.overlays.first(where: { $0.id == store.selectedId }),
           let bounds = store.boundsFraction(for: selected.id) {
            let center = CGPoint(
                x: CGFloat(selected.x) * stageSize.width + CGFloat(bounds.width) * stageSize.width / 2,
                y: CGFloat(selected.y) * stageSize.height - CGFloat(bounds.height) * stageSize.height / 2
            )
            let dx = point.x - center.x
            let dy = point.y - center.y
            if dx * dx + dy * dy <= tapRadius * tapRadius {
                store.removeOverlay(selected.id)
                return
            }
        }
        let hit = store.selectAt(
            x: Float(point.x / max(stageSize.width, 1)),
            y: Float(point.y / max(stageSize.height, 1))
        )
        if !hit { store.clearSelection() }
    }
}

private extension View {
    func stageGestures(store: MemeEditorStore, stageSize: CGSize) -> some View {
        modifier(StageGestures(store: store, stageSize: stageSize))
    }
}


// MARK: - GIF frame tray (M2: ordered frames, long-press drag reorder,
// uniform delay control)

private struct GifFrameTrayView: View {
    let store: MemeEditorStore
    @State private var draggingIndex: Int?

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.xs) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    ForEach(store.gifFrames) { frame in
                        if let index = store.gifFrames.firstIndex(where: { $0.id == frame.id }) {
                            Image(uiImage: frame.image)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 56, height: 56)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10)
                                        .strokeBorder(
                                            index == store.gifPreviewIndex
                                                ? BitOSTheme.accent : BitOSTheme.border,
                                            lineWidth: index == store.gifPreviewIndex ? 2 : 1
                                        )
                                )
                                .onTapGesture { store.selectGifFrame(index) }
                                .onDrag {
                                    draggingIndex = index
                                    return NSItemProvider(object: frame.id as NSString)
                                }
                                .onDrop(
                                    of: [.text],
                                    delegate: GifReorderDelegate(
                                        target: index,
                                        dragging: $draggingIndex,
                                        store: store
                                    )
                                )
                                .accessibilityLabel("Frame \(index + 1) — drag to reorder")
                        }
                    }
                    // Adding lives in the mode row beside undo (single
                    // source-add affordance) — the tray only reorders/selects.
                }
                .padding(.horizontal, BitOSTheme.Spacing.md)
            }
            HStack {
                Text("Frame delay")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)
                Slider(
                    value: Binding(
                        get: { Double(store.gifUniformDelayMs > 0 ? store.gifUniformDelayMs : 100) },
                        set: { store.setGifUniformDelay(Int($0)) }
                    ),
                    in: 20...1000
                )
                Text(
                    store.gifUniformDelayMs > 0
                        ? "\(store.gifUniformDelayMs) ms"
                        : "source (\(store.gifFramesCount))"
                )
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .frame(width: 90, alignment: .trailing)
            }
            .padding(.horizontal, BitOSTheme.Spacing.md)
        }
    }
}

private struct GifReorderDelegate: DropDelegate {
    let target: Int
    @Binding var dragging: Int?
    let store: MemeEditorStore

    func dropEntered(info: DropInfo) {
        guard let from = dragging, from != target else { return }
        store.moveGifFrame(from: from, to: target)
        dragging = target
    }

    func performDrop(info: DropInfo) -> Bool {
        dragging = nil
        return true
    }

    func dropUpdated(info: DropInfo) -> DropInfo? { info }
}

// MARK: - Publish sheet (MST-017)

// MARK: - Text sheet

private struct TextSheet: View {
    @Environment(\.dismiss) private var dismiss
    let store: MemeEditorStore
    let overlayId: String

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            if let overlay = store.overlays.first(where: { $0.id == overlayId }) {
                BitosField("Text", text: Binding(
                    get: { overlay.text },
                    set: { store.updateStyle(overlayId, fields: ["text": $0]) }
                ))
                

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: BitOSTheme.Spacing.xs) {
                        ForEach(["impact", "sans", "serif", "mono"], id: \.self) { slot in
                            ChipButton(label: slotLabel(slot), active: overlay.font == slot) {
                                store.updateStyle(overlayId, fields: ["font": slot])
                            }
                        }
                    }
                }

                // Motion fx (MST-044) + visibility window (video).
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: BitOSTheme.Spacing.xs) {
                        ForEach(["pop", "fade", "shake", "spin"], id: \.self) { option in
                            ChipButton(label: option, active: overlay.fx == option) {
                                store.updateStyle(overlayId, fields: ["fx": option])
                            }
                        }
                        if overlay.fx != nil {
                            ChipButton(label: "none", active: false) {
                                store.updateStyle(overlayId, fields: ["clearFx": true])
                            }
                        }
                    }
                }
                if store.isVideoMode {
                    HStack(spacing: BitOSTheme.Spacing.sm) {
                        BitosField(
                            "Start s",
                            text: Binding(
                                get: { String((overlay.startMs ?? 0) / 1000) },
                                set: { store.updateStyle(overlayId, fields: ["startMs": Int64(Double($0) ?? 0) * 1000]) }
                            )
                        )
                        
                        BitosField(
                            "End s (0 = always)",
                            text: Binding(
                                get: { String((overlay.endMs ?? 0) / 1000) },
                                set: { raw in
                                    let end = Int64((Double(raw) ?? 0) * 1000)
                                    store.updateStyle(overlayId, fields: end <= 0 ? ["clearEndMs": true] : ["endMs": end])
                                }
                            )
                        )
                        
                    }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: BitOSTheme.Spacing.sm) {
                        ForEach(Array(store.paletteHex.enumerated()), id: \.offset) { index, hex in
                            Button {
                                store.updateStyle(overlayId, fields: ["color": index])
                            } label: {
                                Circle()
                                    .fill(Color(uiColor: OverlayUiView.paletteColor(index, paletteHex: store.paletteHex)))
                                    .frame(width: 32, height: 32)
                                    .overlay(
                                        Circle().strokeBorder(
                                            overlay.colorIndex == index ? BitOSTheme.accent : BitOSTheme.border,
                                            lineWidth: overlay.colorIndex == index ? 3 : 1
                                        )
                                    )
                            }
                        }
                    }
                }

                Stepper(
                    "Size \(overlay.size)",
                    value: Binding(
                        get: { overlay.size },
                        set: { store.updateStyle(overlayId, fields: ["size": $0]) }
                    ),
                    in: 12...240,
                    step: 4
                )
                Stepper(
                    "Outline \(overlay.outline)",
                    value: Binding(
                        get: { overlay.outline },
                        set: { store.updateStyle(overlayId, fields: ["outline": $0]) }
                    ),
                    in: 0...12,
                    step: 1
                )
                Toggle("Shadow", isOn: Binding(
                    get: { overlay.shadow },
                    set: { store.updateStyle(overlayId, fields: ["shadow": $0]) }
                ))

                HStack {
                    Button("Delete", role: .destructive) {
                        store.removeOverlay(overlayId)
                        dismiss()
                    }
                    Spacer()
                    Button("Done") {
                        if overlay.text.trimmingCharacters(in: .whitespaces).isEmpty {
                            store.removeOverlay(overlayId)
                        }
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            } else {
                Text("Text")
            }
        }
        .padding()
    }

    private func slotLabel(_ slot: String) -> String {
        switch slot {
        case "impact": return "Impact"
        case "sans": return "Sans"
        case "serif": return "Serif"
        case "mono": return "Mono"
        default: return slot
        }
    }
}

private struct ChipButton: View {
    let label: String
    let active: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(active ? BitOSTheme.accent.opacity(0.18) : BitOSTheme.surface))
                .overlay(Capsule().strokeBorder(active ? BitOSTheme.accent : BitOSTheme.border, lineWidth: 1))
                .foregroundStyle(active ? BitOSTheme.accent : BitOSTheme.textSecondary)
        }
    }
}

// MARK: - Sticker sheet

private struct StickerCell: View {
    let emoji: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(emoji)
                .font(.system(size: 26))
                .frame(width: 68, height: 52)
                .background(RoundedRectangle(cornerRadius: 12).fill(BitOSTheme.surface))
        }
    }
}

// MARK: - Inline tool panels (prototype `edPanel`)

/// Output settings before export (MUX-04): one sheet across modes showing
/// EXACTLY the profile the pipeline will render — the only tested profile
/// per mode, so preview equals output by construction. Automatic
/// adjustments (GIF downscale ladder, video duration cuts) are disclosed
/// up front; the editable master is never mutated.
struct ExportSettingsSheet: View {
    @Environment(\.dismiss) private var dismissSheet
    let store: MemeEditorStore
    var onMakeVariations: ((String, Data) -> Void)? = nil
    @State private var variationsBusy = false

    private var designEligible: Bool {
        !store.isGifMode && !store.isVideoMode && store.activeAsset != nil &&
            store.overlays.contains { !$0.isSticker && !$0.text.isEmpty }
    }

    /// The profile facts, derived from the same rules the exporters use.
    private var formatRow: (String, String) {
        if store.isVideoMode, !store.clips.isEmpty {
            let probe = store.videoProbe
            let seconds = Int(store.timelineDurationMs) / 1000
            return (
                "MP4 · \(probe.map { "\($0.uprightWidth)×\($0.uprightHeight)" } ?? "source size") · \(seconds) s",
                "Over-size exports are automatically trimmed to fit the 64 MB cap — the adjusted result is shown before you post."
            )
        }
        if store.isGifMode, store.gifFramesCount > 0 {
            let delay = store.gifUniformDelayMs
            return (
                "GIF · \(store.gifFramesCount) frames\(delay > 0 ? " · \(delay) ms/frame" : " · source timing")",
                "Oversized GIFs automatically downscale — you'll see “Saved at a smaller size” if that happens."
            )
        }
        if let asset = store.activeAsset {
            let width = Int((asset.image.size.width * asset.image.scale).rounded())
            let height = Int((asset.image.size.height * asset.image.scale).rounded())
            return ("PNG · \(width)×\(height)", "Full-quality PNG at the media's resolution.")
        }
        return ("—", "Pick media first.")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            Text("Export").font(.headline)
            HStack(spacing: BitOSTheme.Spacing.md) {
                MemePostPreviewThumb(store: store)
                    .frame(width: 56, height: 80)
                VStack(alignment: .leading, spacing: 4) {
                    Text(formatRow.0).font(.subheadline.weight(.semibold))
                    Text("Destination: \(store.isVideoMode ? "Movies" : "Photos")")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                    Text("File size is shown after the render (estimates would be guesses).")
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            }
            Text(formatRow.1)
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if case .failed(let message) = store.exportState {
                Text(message).font(.caption).foregroundStyle(BitOSTheme.error)
            }
            if !store.exportJobs.recoverable.isEmpty {
                Text("Recovered exports")
                    .font(.subheadline.weight(.semibold))
                ForEach(store.exportJobs.recoverable) { job in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("\(job.format.uppercased()) · \(job.artifactBytes / 1024) KB")
                                .font(.caption.weight(.semibold))
                            Spacer()
                            if job.phase == "needsReview" {
                                Text("check Photos first — the save may have finished")
                                    .font(.caption2)
                                    .foregroundStyle(BitOSTheme.warning)
                                    .multilineTextAlignment(.trailing)
                            }
                        }
                        if let error = job.lastError {
                            Text(error).font(.caption2).foregroundStyle(BitOSTheme.error)
                        }
                        HStack {
                            Button("Retry save") { store.retryExportSave(jobId: job.id) }
                                .font(.caption.weight(.semibold))
                            Spacer()
                            Button("Discard", role: .destructive) { store.discardExportJob(jobId: job.id) }
                                .font(.caption)
                        }
                    }
                    .padding(BitOSTheme.Spacing.sm)
                    .background(BitOSTheme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
                }
                Text("Retry reuses the rendered file — it never re-renders.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            if designEligible, let onMakeVariations {
                Button {
                    variationsBusy = true
                    Task {
                        do {
                            let png = try await Task.detached(priority: .userInitiated) { [store] in
                                try await MemeRaster.renderPngData(
                                    asset: store.activeAsset!.image,
                                    projectJson: store.projectJson,
                                    client: FrameworkBusinessCoreClient()
                                )
                            }.value
                            dismissSheet()
                            onMakeVariations(store.projectJson, png)
                        } catch {
                            store.setNotice("Could not render the design — \(error.localizedDescription)")
                        }
                        variationsBusy = false
                    }
                } label: {
                    HStack {
                        if variationsBusy { ProgressView() }
                        Text("Make variations from this design")
                            .font(.subheadline.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(variationsBusy || store.exportState == .saving)
                Text("Freezes this design and varies every caption per row.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            Button {
                dismissSheet()
                store.exportActiveAssetToPhotos()
            } label: {
                HStack {
                    if store.exportState == .saving { ProgressView().tint(.white) }
                    Text(store.exportState == .saving ? "Rendering…" : "Export")
                        .font(.subheadline.weight(.semibold))
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(BitOSTheme.accent)
            .disabled(store.exportState == .saving ||
                (store.activeAsset == nil && store.gifFramesCount == 0 && store.videoClipData == nil))
            Spacer(minLength: 0)
        }
        .padding()
        .background(BitOSTheme.background)
    }
}

/// The editor tool panels (prototype create-edit contents, presented as
/// native bottom sheets — platform idiom, canvas stays visible above).
enum EditorPanel: String, CaseIterable, Identifiable {
    case meme
    case text
    case stickers
    case sound
    case fx

    var id: String { rawValue }

    var title: String {
        switch self {
        case .meme: return "Meme generator"
        case .text: return "Text"
        case .stickers: return "Stickers"
        case .sound: return "Sound"
        case .fx: return "Look"
        }
    }
}

/// Classic meme generator panel: TOP/BOTTOM caption pair + font slot,
/// landing at the canonical positions with the classic heavy-outline look
/// — one undo step for the pair, then drag/scale on the stage.
private struct MemePanelContent: View {
    let store: MemeEditorStore
    @State private var top = ""
    @State private var bottom = ""
    @State private var fontSlot = "impact"

    /// Prototype font pills mapped to the shared semantic slots.
    private let slots: [(id: String, label: String)] = [
        ("impact", "Impact"), ("serif", "Comic"), ("sans", "Modern"),
    ]

    private var canAdd: Bool {
        !top.trimmingCharacters(in: .whitespaces).isEmpty ||
            !bottom.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            BitosField("TOP TEXT", text: $top)
                
                .autocorrectionDisabled()
                .textInputAutocapitalization(.characters)
            BitosField("BOTTOM TEXT", text: $bottom)
                
                .autocorrectionDisabled()
                .textInputAutocapitalization(.characters)
            HStack(spacing: BitOSTheme.Spacing.xs) {
                ForEach(slots, id: \.id) { slot in
                    ChipButton(label: slot.label, active: fontSlot == slot.id) {
                        fontSlot = slot.id
                    }
                }
            }
            Button {
                store.addMemeCaptions(top: top, bottom: bottom, fontSlot: fontSlot)
                top = ""
                bottom = ""
                store.setNotice("Captions added — drag on the canvas to fine-tune")
            } label: {
                Text("Add to canvas")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(BitOSTheme.accent)
            .disabled(!canAdd)
        }
    }
}

/// Quick-text panel (prototype text panel): type once, pick a font slot,
/// add — tapping the overlay on the stage opens the full style editor.
private struct TextPanelContent: View {
    let store: MemeEditorStore
    @State private var text = ""
    @State private var fontSlot = "sans"

    private let slots: [(id: String, label: String)] = [
        ("sans", "Modern"), ("impact", "Impact"), ("serif", "Comic"),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            BitosField("Type something…", text: $text)
                
                .onSubmit(addText)
            HStack(spacing: BitOSTheme.Spacing.xs) {
                ForEach(slots, id: \.id) { slot in
                    ChipButton(label: slot.label, active: fontSlot == slot.id) {
                        fontSlot = slot.id
                    }
                }
            }
            Button(action: addText) {
                Text("Add text")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(BitOSTheme.accent)
            .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    private func addText() {
        let value = text.trimmingCharacters(in: .whitespaces)
        guard !value.isEmpty else { return }
        store.addOverlay(kind: "text", text: value)
        if let id = store.selectedId, fontSlot != "sans" {
            store.updateStyle(id, fields: ["font": fontSlot])
        }
        text = ""
        store.setNotice("Text added — tap it on the canvas for the full style editor")
    }
}

/// Sticker panel (prototype sticker grid): recents + packs, bounded so the
/// stage above stays visible.
private struct StickerPanelContent: View {
    let store: MemeEditorStore
    @State private var packId: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            if !store.recents.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: BitOSTheme.Spacing.xs) {
                        ForEach(store.recents, id: \.self) { emoji in
                            StickerCell(emoji: emoji) { store.addSticker(emoji) }
                        }
                    }
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: BitOSTheme.Spacing.xs) {
                    ForEach(store.packs) { pack in
                        ChipButton(label: pack.label, active: currentPackId == pack.id) {
                            packId = pack.id
                        }
                    }
                }
            }
            if let pack = store.packs.first(where: { $0.id == currentPackId }) {
                ScrollView(showsIndicators: false) {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 56))], spacing: BitOSTheme.Spacing.xs) {
                        ForEach(pack.stickers, id: \.self) { emoji in
                            StickerCell(emoji: emoji) { store.addSticker(emoji) }
                        }
                    }
                }
                .frame(maxHeight: 150)
            }
        }
    }

    private var currentPackId: String {
        if packId.isEmpty { return store.packs.first?.id ?? "" }
        return packId
    }
}

/// Sound panel (prototype sound row): current synth cue summary + a jump
/// into the full cue studio sheet.
private struct SoundPanelContent: View {
    let cueCount: Int
    let onOpenStudio: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(cueCount == 0 ? "Original clip audio" : "\(cueCount) synth cue\(cueCount == 1 ? "" : "s")")
                        .font(.subheadline)
                    Text(cueCount == 0
                         ? "Drop risers, zaps and coin SFX at the playhead"
                         : "Cues bake into the export mix")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                Spacer()
                Button("Change", action: onOpenStudio)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BitOSTheme.accent)
            }
        }
    }
}

/// Effects panel (prototype fx panel): the 8 look presets as horizontal
/// chips + the brightness/contrast/saturation sliders over the look.
private struct FxPanelContent: View {
    @Bindable var store: MemeEditorStore

    private struct LookRow: Identifiable { let id: String, label: String }

    private var lookRows: [LookRow] {
        guard let data = FrameworkBusinessCoreClient().memeLooks().data(using: .utf8),
              let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return [LookRow(id: "none", label: "None")]
        }
        return rows.compactMap { row in
            guard let id = row["id"] as? String else { return nil }
            return LookRow(id: id, label: (row["label"] as? String) ?? id)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: BitOSTheme.Spacing.xs) {
                    ForEach(lookRows, id: \.id) { look in
                        ChipButton(
                            label: look.label,
                            active: look.id == (store.lookId ?? "none")
                        ) {
                            store.setLook(look.id)
                        }
                    }
                }
            }
            adjustSlider(
                label: "Brightness", value: Binding(
                    get: { Double(store.adjustBrightness) },
                    set: { store.setAdjust(brightness: Float($0), contrast: store.adjustContrast, saturation: store.adjustSaturation) }
                ),
                in: 0.4...1.6
            )
            adjustSlider(
                label: "Contrast", value: Binding(
                    get: { Double(store.adjustContrast) },
                    set: { store.setAdjust(brightness: store.adjustBrightness, contrast: Float($0), saturation: store.adjustSaturation) }
                ),
                in: 0.4...1.6
            )
            adjustSlider(
                label: "Saturation", value: Binding(
                    get: { Double(store.adjustSaturation) },
                    set: { store.setAdjust(brightness: store.adjustBrightness, contrast: store.adjustContrast, saturation: Float($0)) }
                ),
                in: 0...2
            )
            if store.hasAdjust {
                Button {
                    store.setAdjust(brightness: 1, contrast: 1, saturation: 1)
                } label: {
                    Text("Reset adjust")
                        .font(.caption.weight(.semibold))
                }
                .foregroundStyle(BitOSTheme.accent)
            }
        }
    }

    private func adjustSlider(label: String, value: Binding<Double>, in range: ClosedRange<Double>) -> some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            Text(label)
                .font(.caption.weight(.semibold))
                .frame(width: 84, alignment: .leading)
            Slider(value: value, in: range)
            Text("\(Int((value.wrappedValue * 100).rounded()))%")
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .foregroundStyle(BitOSTheme.accent)
                .frame(width: 42, alignment: .trailing)
        }
    }
}


// MARK: - Export raster (MST-016)

enum MemeExportState: Equatable {
    case idle
    case saving
    case saved
    /// Successful output the pipeline adjusted (downscale ladder, cut) —
    /// a SUCCESS with the adjustment named, never styled as failure.
    case savedAdjusted(String)
    case failed(String)
}

/// Off-screen raster + device save (plan MST-016): paints the shared
/// `MemeExportRules` envelope (size math + draw rows) with UIKit so the
/// export geometry equals the stage preview, then lands the PNG in the
/// photo library (add-only permission; `NSPhotoLibraryAddUsageDescription`
/// already ships in Info.plist). Single-shot fast job — cancellable long
/// exports arrive with the GIF/video waves.
enum MemeRaster {

    /// Applies a composed 4×5 color matrix (shared MemeLooks engine, the
    /// same values Android burns in) via CIColorMatrix; nil = unchanged.
    /// The 5th matrix column is 0..255 → CI bias works in 0..1 units.
    static func applyLook(_ image: UIImage, matrixJson: String) -> UIImage? {
        guard let data = matrixJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = root["matrix"] as? [NSNumber], rows.count == 20,
              let input = CIImage(image: image) else { return nil }
        let m = rows.map { CGFloat(truncating: $0) }
        let identity: [CGFloat] = [1, 0, 0, 0, 0,
                                   0, 1, 0, 0, 0,
                                   0, 0, 1, 0, 0,
                                   0, 0, 0, 1, 0]
        if m == identity { return nil }
        let filter = CIFilter(name: "CIColorMatrix")
        filter?.setValue(input, forKey: kCIInputImageKey)
        filter?.setValue(CIVector(x: m[0], y: m[1], z: m[2], w: m[3]), forKey: "inputRVector")
        filter?.setValue(CIVector(x: m[5], y: m[6], z: m[7], w: m[8]), forKey: "inputGVector")
        filter?.setValue(CIVector(x: m[10], y: m[11], z: m[12], w: m[13]), forKey: "inputBVector")
        filter?.setValue(CIVector(x: m[15], y: m[16], z: m[17], w: m[18]), forKey: "inputAVector")
        filter?.setValue(
            CIVector(x: m[4] / 255, y: m[9] / 255, z: m[14] / 255, w: m[19] / 255),
            forKey: "inputBiasVector"
        )
        guard let output = filter?.outputImage,
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    struct ExportError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// Renders asset + project to PNG bytes at the export resolution.
    static func renderPngData(
        asset: UIImage,
        projectJson: String,
        client: any BusinessCoreClient
    ) throws -> Data {
        // MST-043 + adjust: the grade burns into the MEDIA only (the
        // composed look+adjust matrix, same values the Android rasterizer
        // uses); overlays draw unfiltered.
        var media = asset
        let projectLookId = MemeEditorStore.lookId(ofProject: projectJson)
        let adjust = MemeEditorStore.adjustTriple(ofProject: projectJson)
        let hasAdjust = adjust.bri != 1 || adjust.con != 1 || adjust.sat != 1
        if projectLookId != nil || hasAdjust {
            if let graded = applyLook(
                asset,
                matrixJson: client.memeAdjustMatrix(
                    projectLookId,
                    brightness: adjust.bri,
                    contrast: adjust.con,
                    saturation: adjust.sat
                )
            ) {
                media = graded
            }
        }
        // The shared envelope owns the evened, long-edge-capped canvas.
        let envelopeJson = client.memeExportPlan(
            projectJson,
            sourceWidth: Int(asset.size.width),
            sourceHeight: Int(asset.size.height)
        )
        guard envelopeJson.contains("\"width\""),
              let envelopeData = envelopeJson.data(using: .utf8),
              let envelope = try? JSONSerialization.jsonObject(with: envelopeData) as? [String: Any],
              let width = (envelope["width"] as? NSNumber)?.doubleValue,
              let height = (envelope["height"] as? NSNumber)?.doubleValue,
              let rows = envelope["items"] as? [[String: Any]] else {
            throw ExportError(message: "The meme could not be planned for export")
        }
        // A pinned canvas re-frames the export: same long-edge budget,
        // media letterboxed centered, background filled, plan re-mapped to
        // the canvas size (the plan normalizes against given dims).
        var size = CGSize(width: width, height: height)
        var mediaRect = CGRect(origin: .zero, size: size)
        var canvasRows = rows
        var canvasStrokes = envelope["strokes"] as? [[String: Any]] ?? []
        let canvasRatio = MemeEditorStore.canvasRatio(ofProject: projectJson)
        let canvasBgHex = MemeEditorStore.canvasBg(ofProject: projectJson)
        if let ratio = canvasRatio {
            let terms = ratio.split(separator: ":").compactMap { Int($0) }
            if terms.count == 2, terms[0] > 0, terms[1] > 0 {
                let aspect = CGFloat(terms[0]) / CGFloat(terms[1])
                let longEdge = max(width, height)
                let canvasSize = aspect >= 1
                    ? CGSize(width: longEdge, height: longEdge / aspect)
                    : CGSize(width: longEdge * aspect, height: longEdge)
                let evened = CGSize(
                    width: (canvasSize.width / 2).rounded() * 2,
                    height: (canvasSize.height / 2).rounded() * 2
                )
                let scale = min(evened.width / width, evened.height / height)
                let fitted = CGSize(width: width * scale, height: height * scale)
                mediaRect = CGRect(
                    x: (evened.width - fitted.width) / 2,
                    y: (evened.height - fitted.height) / 2,
                    width: fitted.width,
                    height: fitted.height
                )
                size = evened
                let reJson = client.memeExportPlan(
                    projectJson,
                    sourceWidth: Int(evened.width),
                    sourceHeight: Int(evened.height)
                )
                if let reData = reJson.data(using: .utf8),
                   let rePlan = try? JSONSerialization.jsonObject(with: reData) as? [String: Any],
                   let reRows = rePlan["items"] as? [[String: Any]] {
                    canvasRows = reRows
                    canvasStrokes = rePlan["strokes"] as? [[String: Any]] ?? []
                }
            }
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let image = renderer.image { context in
            if let bgHex = canvasBgHex, let bg = MemeEditorStore.colorHex(bgHex) {
                context.cgContext.setFillColor(UIColor(bg).cgColor)
                context.cgContext.fill(CGRect(origin: .zero, size: size))
            }
            media.draw(in: mediaRect)
            paintStrokes(canvasStrokes, in: context.cgContext)
            for row in canvasRows {
                paint(row, in: context.cgContext)
            }
        }
        guard let data = image.pngData() else {
            throw ExportError(message: "PNG encoding failed")
        }
        return data
    }

    static func paint(_ row: [String: Any], in cgContext: CGContext, images: [String: UIImage] = [:], centerOverride: CGPoint? = nil) {
        var paintRow = row
        if let centerOverride {
            paintRow["x"] = centerOverride.x
            paintRow["y"] = centerOverride.y
        }
        if (paintRow["image"] as? Bool) == true {
            paintImage(paintRow, in: cgContext, images: images)
            return
        }
        let lines = paintRow["lines"] as? [String] ?? []
        let fontSize = (paintRow["fontSize"] as? NSNumber)?.doubleValue ?? 10
        let x = (paintRow["x"] as? NSNumber)?.doubleValue ?? 0
        let y = (paintRow["y"] as? NSNumber)?.doubleValue ?? 0
        let rotation = (paintRow["rot"] as? NSNumber)?.doubleValue ?? 0
        let outline = (paintRow["outline"] as? NSNumber)?.doubleValue ?? 0
        let shadow = (paintRow["shadow"] as? NSNumber)?.boolValue ?? false
        guard !lines.isEmpty else { return }

        cgContext.saveGState()
        cgContext.translateBy(x: CGFloat(x), y: CGFloat(y))
        cgContext.rotate(by: CGFloat(rotation) * .pi / 180)

        let lineHeight = CGFloat(fontSize) * 1.2
        let totalHeight = lineHeight * CGFloat(lines.count)
        let paragraph = NSAttributedString(string: "\n", attributes: [.font: UIFont.systemFont(ofSize: CGFloat(fontSize))])
        for (index, line) in lines.enumerated() {
            let attributes = textAttributes(paintRow, fontSize: CGFloat(fontSize), outline: CGFloat(outline), shadow: shadow)
            let attributed = NSMutableAttributedString(string: line, attributes: attributes)
            if index < lines.count - 1 {
                attributed.append(paragraph)
            }
            let bounds = attributed.size()
            // UIKit draws from the top-left; start above center and step down.
            let origin = CGPoint(
                x: -bounds.width / 2,
                y: -totalHeight / 2 + lineHeight * CGFloat(index) + (lineHeight - bounds.height) / 2
            )
            attributed.draw(at: origin)
        }
        cgContext.restoreGState()
    }

    /// IMAGE layers: fontSize is the layer's target height; width keeps the
    /// source aspect. An unresolvable asset paints nothing (the shared plan
    /// already drops layers without an asset id).
    private static func paintImage(_ row: [String: Any], in cgContext: CGContext, images: [String: UIImage]) {
        guard let assetId = row["asset"] as? String,
              let image = images[assetId],
              let cgImage = image.cgImage else { return }
        let height = CGFloat((row["fontSize"] as? NSNumber)?.doubleValue ?? 0)
        guard height > 0 else { return }
        let width = height * CGFloat(image.size.width) / max(1, image.size.height)
        let x = (row["x"] as? NSNumber)?.doubleValue ?? 0
        let y = (row["y"] as? NSNumber)?.doubleValue ?? 0
        let rotation = (row["rot"] as? NSNumber)?.doubleValue ?? 0

        cgContext.saveGState()
        cgContext.translateBy(x: CGFloat(x), y: CGFloat(y))
        cgContext.rotate(by: CGFloat(rotation) * .pi / 180)
        // UIKit context is top-left flipped; draw through UIImage keeps parity
        // with the still-raster path.
        UIGraphicsPushContext(cgContext)
        image.draw(in: CGRect(x: -width / 2, y: -height / 2, width: width, height: height))
        UIGraphicsPopContext()
        cgContext.restoreGState()
    }

    /**
     * Pen strokes from the export envelope (rows `{"c": ARGB, "w": px,
     * "p": [x,y,…]}` in canvas px): polylines with round caps/joins —
     * stage parity. Paint BEFORE overlay items (ink sits under captions).
     */
    static func paintStrokes(_ rows: [[String: Any]], in cgContext: CGContext) {
        for row in rows {
            let coordinates = (row["p"] as? [NSNumber])?.map { CGFloat($0.doubleValue) } ?? []
            guard coordinates.count >= 4,
                  let colorValue = (row["c"] as? NSNumber)?.uint64Value else { continue }
            let width = CGFloat((row["w"] as? NSNumber)?.doubleValue ?? 0)
            guard width > 0 else { continue }
            var points: [CGPoint] = []
            var index = 0
            while index + 1 < coordinates.count {
                points.append(CGPoint(x: coordinates[index], y: coordinates[index + 1]))
                index += 2
            }
            cgContext.saveGState()
            cgContext.setStrokeColor(
                red: CGFloat((colorValue >> 16) & 0xFF) / 255,
                green: CGFloat((colorValue >> 8) & 0xFF) / 255,
                blue: CGFloat(colorValue & 0xFF) / 255,
                alpha: CGFloat((colorValue >> 24) & 0xFF) / 255
            )
            cgContext.setLineWidth(width)
            cgContext.setLineCap(.round)
            cgContext.setLineJoin(.round)
            cgContext.addLines(between: points)
            cgContext.strokePath()
            cgContext.restoreGState()
        }
    }

    /**
     * Tight content bounds for one envelope row (video export's per-overlay
     * CALayers render into exactly this size, positioned at the row center
     * so transforms pivot on the overlay like the stage does).
     */
    static func contentSize(_ row: [String: Any], images: [String: UIImage]) -> CGSize {
        if (row["image"] as? Bool) == true {
            let height = CGFloat((row["fontSize"] as? NSNumber)?.doubleValue ?? 0)
            let assetId = row["asset"] as? String ?? ""
            let image = images[assetId]
            let aspect = (image?.size.width ?? 1) / max(1, image?.size.height ?? 1)
            return CGSize(width: max(2, ceil(height * aspect)), height: max(2, ceil(height)))
        }
        let fontSize = CGFloat((row["fontSize"] as? NSNumber)?.doubleValue ?? 10)
        let outline = CGFloat((row["outline"] as? NSNumber)?.doubleValue ?? 0)
        let shadow = (row["shadow"] as? NSNumber)?.boolValue ?? false
        let lines = row["lines"] as? [String] ?? []
        var width: CGFloat = 2
        var height: CGFloat = 0
        for line in lines {
            let attributed = NSMutableAttributedString(
                string: line,
                attributes: textAttributes(row, fontSize: fontSize, outline: outline, shadow: shadow)
            )
            width = max(width, attributed.size().width)
            height += fontSize * 1.2
        }
        return CGSize(width: ceil(width) + 4, height: ceil(height) + 4)
    }

    private static func textAttributes(
        _ row: [String: Any],
        fontSize: CGFloat,
        outline: CGFloat,
        shadow: Bool
    ) -> [NSAttributedString.Key: Any] {
        let slot = row["font"] as? String ?? "impact"
        var attributes: [NSAttributedString.Key: Any] = [
            .font: OverlayUiView.slotFont(slot, size: fontSize)
        ]
        if let colorValue = (row["color"] as? NSNumber)?.uint64Value {
            attributes[.foregroundColor] = UIColor(
                red: CGFloat((colorValue >> 16) & 0xFF) / 255,
                green: CGFloat((colorValue >> 8) & 0xFF) / 255,
                blue: CGFloat(colorValue & 0xFF) / 255,
                alpha: CGFloat((colorValue >> 24) & 0xFF) / 255
            )
        }
        if outline > 0 {
            attributes[.strokeWidth] = -outline
            attributes[.strokeColor] = UIColor.black
        }
        if shadow {
            let nsShadow = NSShadow()
            nsShadow.shadowColor = UIColor.black
            nsShadow.shadowOffset = CGSize(width: fontSize * 0.06, height: fontSize * 0.06)
            nsShadow.shadowBlurRadius = fontSize * 0.125
            attributes[.shadow] = nsShadow
        }
        return attributes
    }

    /// Photo-library video save (add-only).
    static func saveVideoToPhotos(_ data: Data) async throws {
        switch PHPhotoLibrary.authorizationStatus(for: .addOnly) {
        case .authorized, .limited:
            break
        case .notDetermined:
            let granted = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
            guard granted == .authorized || granted == .limited else {
                throw ExportError(message: "Photos permission denied")
            }
        default:
            throw ExportError(message: "Photos permission denied")
        }
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .video, data: data, options: nil)
        }
    }

    /// Photo-library save (add-only); throws on denied permission.
    static func saveToPhotos(_ data: Data) async throws {
        switch PHPhotoLibrary.authorizationStatus(for: .addOnly) {
        case .authorized, .limited:
            break
        case .notDetermined:
            let granted = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
            guard granted == .authorized || granted == .limited else {
                throw ExportError(message: "Photos permission denied")
            }
        default:
            throw ExportError(message: "Photos permission denied")
        }
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .photo, data: data, options: nil)
        }
    }
}

/// Color-grade picker (MST-043): the 8 web presets, media-only, undoable.
/// The Adjust section (prototype `create-edit` FX panel) adds manual
/// brightness/contrast/saturation sliders composed over the preset.
private struct LooksSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var store: MemeEditorStore
    private let columns = [GridItem(.adaptive(minimum: 88), spacing: BitOSTheme.Spacing.sm)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Color look").font(.subheadline.weight(.semibold))
                    Text("Applies to the media only — captions stay crisp. Undo works.")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                LazyVGrid(columns: columns, spacing: BitOSTheme.Spacing.sm) {
                    ForEach(lookRows, id: \.id) { look in
                        lookButton(look)
                    }
                }
                Divider()
                VStack(alignment: .leading, spacing: 2) {
                    Text("Adjust").font(.subheadline.weight(.semibold))
                    Text("Fine-tune over the look — burns into the export like the preset.")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                adjustSlider(
                    label: "Brightness", value: Binding(
                        get: { Double(store.adjustBrightness) },
                        set: { store.setAdjust(brightness: Float($0), contrast: store.adjustContrast, saturation: store.adjustSaturation) }
                    ),
                    in: 0.4...1.6
                )
                adjustSlider(
                    label: "Contrast", value: Binding(
                        get: { Double(store.adjustContrast) },
                        set: { store.setAdjust(brightness: store.adjustBrightness, contrast: Float($0), saturation: store.adjustSaturation) }
                    ),
                    in: 0.4...1.6
                )
                adjustSlider(
                    label: "Saturation", value: Binding(
                        get: { Double(store.adjustSaturation) },
                        set: { store.setAdjust(brightness: store.adjustBrightness, contrast: store.adjustContrast, saturation: Float($0)) }
                    ),
                    in: 0...2
                )
                if store.hasAdjust {
                    Button {
                        store.setAdjust(brightness: 1, contrast: 1, saturation: 1)
                    } label: {
                        Text("Reset adjust")
                            .font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(BitOSTheme.accent)
                }
                Spacer(minLength: 0)
            }
            .padding(BitOSTheme.Spacing.md)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    /// One labeled slider with the % readout (prototype FX panel shape).
    private func adjustSlider(label: String, value: Binding<Double>, in range: ClosedRange<Double>) -> some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            Text(label)
                .font(.caption.weight(.semibold))
                .frame(width: 84, alignment: .leading)
            Slider(value: value, in: range)
            Text("\(Int((value.wrappedValue * 100).rounded()))%")
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .foregroundStyle(BitOSTheme.accent)
                .frame(width: 42, alignment: .trailing)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label) \(Int((value.wrappedValue * 100).rounded())) percent")
        .accessibilityAdjustableAction { direction in
            let step = (range.upperBound - range.lowerBound) / 20
            let next = direction == .increment
                ? min(range.upperBound, value.wrappedValue + step)
                : max(range.lowerBound, value.wrappedValue - step)
            value.wrappedValue = next
        }
    }

    /// Split out of `body` — the grid button chain exceeded the type-checker's
    /// expression budget (the module compiled green again once extracted).
    private func lookButton(_ look: LookRow) -> some View {
        Button {
            store.setLook(look.id)
        } label: {
            let active = look.id == (store.lookId ?? "none")
            return Text(look.label)
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, BitOSTheme.Spacing.sm)
                .background(active ? BitOSTheme.accent : BitOSTheme.surface)
                .foregroundStyle(active ? Color.black : BitOSTheme.textPrimary)
                .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
        }
        .buttonStyle(.plain)
    }

    /// Catalog rows from the shared seam (id order, `none` first).
    private struct LookRow: Identifiable { let id: String, label: String }

    private var lookRows: [LookRow] {
        guard let data = FrameworkBusinessCoreClient().memeLooks().data(using: .utf8),
              let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return [LookRow(id: "none", label: "None")]
        }
        return rows.compactMap { row in
            guard let id = row["id"] as? String else { return nil }
            return LookRow(id: id, label: (row["label"] as? String) ?? id)
        }
    }
}

/// SFX sheet (MST-041): buckets × 31 synth sounds — tap previews (base64
/// WAV through the shared renderer), ＋ schedules a cue at the playhead.
private struct SfxSheet: View {
    @Environment(\.dismiss) private var dismiss
    let store: MemeEditorStore
    let positionSec: Double
    @State private var bucketId = "funny"
    @State private var search = ""

    private struct Bucket: Identifiable {
        let id: String, label: String
        let sfx: [String]
    }

    private struct TemplateRow: Identifiable {
        let id: String, label: String, emoji: String
        let cues: [(sfx: String, atMs: Int64)]
    }

    /// One catalog read: buckets + labels + templates (shared seam).
    private static func catalog() -> (buckets: [Bucket], labels: [String: String], templates: [TemplateRow])? {
        guard let data = FrameworkBusinessCoreClient().memeSfxCatalog().data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let labels = (root["labels"] as? [String: String]) ?? [:]
        let bucketRows = (root["buckets"] as? [[String: Any]]) ?? []
        let buckets = bucketRows.compactMap { (row) -> Bucket? in
            guard let id = row["id"] as? String else { return nil }
            return Bucket(
                id: id,
                label: (row["label"] as? String) ?? id,
                sfx: (row["sfx"] as? [String]) ?? []
            )
        }
        let templateRows = (root["templates"] as? [[String: Any]]) ?? []
        let templates = templateRows.compactMap { (row) -> TemplateRow? in
            guard let id = row["id"] as? String else { return nil }
            let cues = (row["cues"] as? [[String: Any]] ?? []).compactMap { (cue) -> (String, Int64)? in
                guard let sfx = cue["sfx"] as? String else { return nil }
                return (sfx, (cue["at"] as? NSNumber)?.int64Value ?? 0)
            }
            guard !cues.isEmpty else { return nil }
            return TemplateRow(
                id: id,
                label: (row["label"] as? String) ?? id,
                emoji: (row["emoji"] as? String) ?? "🔊",
                cues: cues
            )
        }
        return (buckets, labels, templates)
    }

    /// Case/diacritic-insensitive label search (web filterEntries parity):
    /// empty query keeps the bucket view; a query flattens everything.
    private var matching: [(sfx: String, label: String)] {
        guard let catalog = Self.catalog() else { return [] }
        let all = catalog.buckets.flatMap { bucket in
            bucket.sfx.map { ($0, catalog.labels[$0] ?? $0) }
        }
        let query = search.trimmingCharacters(in: .whitespaces).lowercased()
        guard !query.isEmpty else { return all }
        return all.filter { $0.1.lowercased().contains(query) }
    }

    var body: some View {
        let catalog = Self.catalog()
        let allBuckets = catalog?.buckets ?? []
        let templates = catalog?.templates ?? []
        let searching = !search.trimmingCharacters(in: .whitespaces).isEmpty
        let bucket = allBuckets.first { $0.id == bucketId } ?? allBuckets.first
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Sound effects").font(.headline)
                Text(String(
                    format: "Tap a sound to preview · Add cue schedules it at %.1fs (≤16, fully synthesized)",
                    positionSec
                ))
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
            }
            BitosField("Search sounds", text: $search)
                .autocorrectionDisabled()
            if !searching {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: BitOSTheme.Spacing.xs) {
                        ForEach(allBuckets) { b in
                            Button(b.label) { bucketId = b.id }
                                .font(.caption.weight(.semibold))
                                .padding(.horizontal, 10).padding(.vertical, 4)
                                .background(b.id == bucketId ? BitOSTheme.accent : BitOSTheme.surface)
                                .foregroundStyle(b.id == bucketId ? Color.black : BitOSTheme.textPrimary)
                                .clipShape(Capsule())
                        }
                    }
                }
            }
            let rows = searching
                ? matching
                : (bucket?.sfx ?? []).map { ($0, catalog?.labels[$0] ?? $0) }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: BitOSTheme.Spacing.xs)]) {
                ForEach(rows, id: \.sfx) { row in
                    VStack(spacing: 4) {
                        Button {
                            store.previewSfx(row.sfx)
                        } label: {
                            VStack(spacing: 2) {
                                Text(row.label).font(.caption.weight(.semibold)).lineLimit(1)
                                AppIcons.image(for: AppIcons.play)
                                    .font(.caption2)
                                    .foregroundStyle(BitOSTheme.textSecondary)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, BitOSTheme.Spacing.xs)
                            .background(BitOSTheme.surface)
                            .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
                        }
                        .buttonStyle(.plain)
                        Button {
                            store.addSfxCue(row.sfx, atMs: Int64(positionSec * 1000))
                            dismiss()
                        } label: {
                            HStack(spacing: 2) {
                                AppIcons.image(for: AppIcons.add)
                                Text("cue")
                            }
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(BitOSTheme.accent)
                        }
                    }
                }
            }
            if !searching && !templates.isEmpty {
                Text("Templates").font(.subheadline.weight(.semibold))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: BitOSTheme.Spacing.xs) {
                        ForEach(templates) { template in
                            Button {
                                let base = Int64(positionSec * 1000)
                                template.cues.forEach { cue in
                                    store.addSfxCue(cue.sfx, atMs: base + cue.atMs)
                                }
                                store.setNotice("\(template.label) staged at \(String(format: "%.1f", positionSec))s")
                                dismiss()
                            } label: {
                                VStack(spacing: 2) {
                                    Text(template.emoji).font(.title3)
                                    Text(template.label)
                                        .font(.caption2.weight(.semibold))
                                        .lineLimit(1)
                                    Text("\(template.cues.count) cues")
                                        .font(.system(size: 8))
                                        .foregroundStyle(BitOSTheme.textSecondary)
                                }
                                .padding(BitOSTheme.Spacing.xs)
                                .frame(minWidth: 84)
                                .background(BitOSTheme.surface)
                                .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm))
                                .overlay(
                                    RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm)
                                        .strokeBorder(BitOSTheme.border, lineWidth: 1)
                                )
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Apply template \(template.label)")
                        }
                    }
                }
            }
            if !store.sfxCues.isEmpty {
                Text("Cues (\(store.sfxCues.count)/16)").font(.subheadline.weight(.semibold))
                ForEach(store.sfxCues, id: \.id) { cue in
                    HStack {
                        Text(String(format: "%@ @ %.1fs", cue.sfx, Double(cue.atMs) / 1000))
                            .font(.caption)
                        Spacer()
                        Button {
                            store.previewSfx(cue.sfx)
                        } label: {
                            AppIcons.image(for: AppIcons.play)
                                .font(.caption)
                                .foregroundStyle(BitOSTheme.accent)
                        }
                        .accessibilityLabel("Preview cue")
                        Button {
                            store.removeSfxCue(cue.id)
                        } label: {
                            AppIcons.image(for: AppIcons.close)
                                .font(.caption)
                                .foregroundStyle(BitOSTheme.textSecondary)
                        }
                        .accessibilityLabel("Remove cue")
                    }
                }
            }
        }
        .padding(BitOSTheme.Spacing.md)
    }
}

// ══════════════════════════════════════════════════════════════════════
// V2 suite (mockup app-15 `scr-suite`) + source-insert layers
// ══════════════════════════════════════════════════════════════════════

/// scr-suite clock (mm:ss).
private func suiteClock(_ ms: Int64) -> String {
    let totalSeconds = Int(ms / 1000)
    return String(format: "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

/// One mono-labeled track lane (video · overlays · audio · sfx).
private struct SuiteTrackLane<Content: View>: View {
    let label: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            Text(label)
                .font(.system(size: 9, design: .monospaced))
                .foregroundStyle(BitOSTheme.textTertiary)
                .frame(width: 48, alignment: .leading)
            ZStack(alignment: .leading) {
                content()
            }
            .frame(height: 18)
            .frame(maxWidth: .infinity)
            .background(BitOSTheme.surfaceElevated)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .overlay(
                RoundedRectangle(cornerRadius: 6).strokeBorder(BitOSTheme.border, lineWidth: 1)
            )
            .padding(.vertical, 1)
        }
    }
}

/// The expert dock (mockup `scr-suite`): tracks with a scrubbable
/// playhead, the contextual tool chips and the undo/redo · Preview ·
/// Export action row. Real data only — overlay segments are the shared
/// visibility windows, sfx ticks the project cues.
private struct SuiteDockView: View {
    @Bindable var store: MemeEditorStore
    let transport: VideoTransportIos
    let positionSec: Double
    var onOpenLayers: () -> Void
    var onOpenLooks: () -> Void
    var onOpenSfx: () -> Void
    var onOpenDraw: () -> Void
    var onOpenTrim: () -> Void
    var onOpenSpeed: () -> Void
    var onOpenClips: () -> Void
    var onOpenVolume: () -> Void
    var onSplit: () -> Void
    var onExport: () -> Void
    var onClose: () -> Void

    @State private var playing = false

    private var durationSec: Double {
        max(0.01, Double(store.timelineDurationMs) / 1000)
    }

    private var laneCount: Int {
        store.clips.count +
            store.overlays.filter(\.isImage).count + 3
    }

    private var stackHeight: CGFloat { CGFloat(laneCount * 20 + 2) }

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.xs) {
            // ── Tracks + playhead (drag anywhere to seek) ──────────────
            GeometryReader { geo in
                let fraction = min(max(positionSec / durationSec, 0), 1)
                ZStack(alignment: .topLeading) {
                    trackLanes(width: geo.size.width)
                    // Playhead line over the whole lane stack.
                    Rectangle()
                        .fill(BitOSTheme.accent)
                        .frame(width: 2, height: stackHeight)
                        .offset(x: geo.size.width * fraction - 1)
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let f = min(max(value.location.x / max(1, geo.size.width), 0), 1)
                            transport.seekToSeconds(f * durationSec)
                        }
                )
            }
            .frame(height: stackHeight + 4)
            // ── Time readout (mono, like the mockup footer) ────────────
            HStack {
                Text(suiteClock(Int64(positionSec * 1000)))
                Spacer()
                Text(store.sfxCues.isEmpty
                     ? "cue 0/\(MemeEditorStore.sfxCueCap)"
                     : "cue \(store.sfxCues.count)/\(MemeEditorStore.sfxCueCap) · \(store.sfxCues.max { $0.atMs < $1.atMs }?.sfx ?? "")")
                Spacer()
                Text(suiteClock(store.timelineDurationMs))
            }
            .font(.system(size: 9, design: .monospaced))
            .foregroundStyle(BitOSTheme.textTertiary)
            // ── Contextual tool chips ───────────────────────────────────
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: BitOSTheme.Spacing.xs) {
                    SuiteChip(label: "Clips", symbol: AppIcons.appsGrid, enabled: true, action: onOpenClips)
                    SuiteChip(label: "Draw", symbol: AppIcons.pen, enabled: true, action: onOpenDraw)
                    SuiteChip(label: "Layers", symbol: AppIcons.appsGrid, enabled: true, action: onOpenLayers)
                    SuiteChip(label: "Looks", symbol: AppIcons.looks, enabled: store.isVideoMode ? !store.clips.isEmpty : store.activeAsset != nil, action: onOpenLooks)
                    SuiteChip(label: "Trim", symbol: "scissors", enabled: true, action: onOpenTrim)
                    SuiteChip(label: "Split", symbol: "scissors", enabled: true, action: onSplit)
                    SuiteChip(label: "SFX ≤\(MemeEditorStore.sfxCueCap)", symbol: AppIcons.sfx, enabled: true, action: onOpenSfx)
                    SuiteChip(label: "Volume", symbol: AppIcons.musicNote, enabled: true, action: onOpenVolume)
                    SuiteChip(label: "Speed", symbol: "gauge", enabled: true, action: onOpenSpeed)
                }
            }
            // ── Action row: undo/redo · autosave · Preview · Export ────
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button {
                    store.undo()
                } label: {
                    AppIcons.image(for: AppIcons.undo)
                        .foregroundStyle(store.canUndo ? BitOSTheme.textPrimary : BitOSTheme.textTertiary)
                }
                .disabled(!store.canUndo)
                .accessibilityLabel("Undo")
                Button {
                    store.redo()
                } label: {
                    Image(systemName: "arrow.uturn.forward")
                        .foregroundStyle(store.canRedo ? BitOSTheme.textPrimary : BitOSTheme.textTertiary)
                }
                .disabled(!store.canRedo)
                .accessibilityLabel("Redo")
                Text("autosave on")
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    transport.playPause()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: playing ? "pause.fill" : "play.fill")
                            .font(.caption)
                        Text("Preview").font(.caption)
                    }
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(playing ? "Pause preview" : "Play preview")
                Button {
                    onExport()
                } label: {
                    if store.exportState == .saving {
                        ProgressView().controlSize(.small)
                    } else {
                        Text("Export").font(.caption)
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(BitOSTheme.accent)
                .disabled(store.exportState == .saving)
                Button {
                    onClose()
                } label: {
                    AppIcons.image(for: AppIcons.close)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                .accessibilityLabel("Close suite")
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.md)
        .padding(.vertical, BitOSTheme.Spacing.sm)
        .task(id: "suite-transport") {
            // Poll the registered player state (light).
            while !Task.isCancelled {
                playing = transport.isPlaying()
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
    }

    /// Split out of `body` — the lane stack exceeded the type-checker's
    /// expression budget.
    private func trackLanes(width: CGFloat) -> some View {
        VStack(spacing: 2) {
            clipLanes(width: width)
            imageLayerLanes(width: width)
            SuiteTrackLane(label: "overlays") {
                overlayLaneSegments(width: width)
            }
            SuiteTrackLane(label: "audio") {
                Rectangle()
                    .fill(Color(hex: 0x6650E3A0))
                    .clipShape(RoundedRectangle(cornerRadius: 4))
            }
            SuiteTrackLane(label: "sfx") {
                sfxLaneTicks(width: width)
            }
        }
    }

    /// M5: one lane per timeline clip, staggered by its timeline offset.
    private func clipLanes(width: CGFloat) -> some View {
        ForEach(Array(store.clips.enumerated()), id: \.element.id) { index, clip in
            SuiteTrackLane(label: "vdo \(index + 1)") {
                let out = Double(store.clipOutputMs(clip)) / 1000
                let left = min(max(Double(store.clipOffsetMs(index)) / 1000 / durationSec, 0), 1)
                let laneWidth = min(max(out / durationSec, 0.01), 1 - left)
                RoundedRectangle(cornerRadius: 4)
                    .fill(LinearGradient(
                        colors: [Color(hex: 0x3A1505), Color(hex: 0xC2570F)],
                        startPoint: .top, endPoint: .bottom
                    ))
                    .frame(width: max(4, width * laneWidth))
                    .offset(x: width * left)
                    .overlay(
                        RoundedRectangle(cornerRadius: 4)
                            .strokeBorder(
                                index == store.selectedClipIndex ? BitOSTheme.accent : .clear,
                                lineWidth: 1.5
                            )
                    )
            }
        }
    }

    /// M5: one lane per IMAGE layer, segment = visibility window.
    private func imageLayerLanes(width: CGFloat) -> some View {
        let layers = store.overlays.filter(\.isImage)
        return ForEach(Array(layers.enumerated()), id: \.element.id) { index, layer in
            SuiteTrackLane(label: "image \(index + 1)") {
                let start = Double(layer.startMs ?? 0) / 1000
                let end = Double(layer.endMs ?? 0) / 1000
                let left = min(max(start / durationSec, 0), 1)
                let effectiveEnd = end <= start ? durationSec : end
                let laneWidth = min(max(effectiveEnd / durationSec - left, 0.01), 1 - left)
                RoundedRectangle(cornerRadius: 4)
                    .fill(Color(hex: 0x808B5CF6))
                    .frame(width: max(4, width * laneWidth))
                    .offset(x: width * left)
            }
        }
    }

    private func overlayLaneSegments(width: CGFloat) -> some View {
        ForEach(Array(store.overlays.enumerated()), id: \.element.id) { index, overlay in
            let start = Double(overlay.startMs ?? 0) / 1000
            let end = Double(overlay.endMs ?? 0) / 1000
            let left = min(max(start / durationSec, 0), 1)
            let effectiveEnd = end <= start ? durationSec : end
            let laneWidth = min(max(effectiveEnd / durationSec - left, 0.01), 1 - left)
            RoundedRectangle(cornerRadius: 4)
                .fill(index % 2 == 0 ? Color(hex: 0x80EC4899) : Color(hex: 0x8006B6D4))
                .frame(width: max(4, width * laneWidth))
                .offset(x: width * left)
        }
    }

    private func sfxLaneTicks(width: CGFloat) -> some View {
        ForEach(Array(store.sfxCues.enumerated()), id: \.offset) { _, cue in
            let fraction = Double(cue.atMs) / 1000 / durationSec
            Capsule()
                .fill(Color(hex: 0xFFB000))
                .frame(width: 3, height: 12)
                .offset(x: width * fraction)
        }
    }
}

/// One contextual tool chip; disabled chips carry the honest "soon" tag.
private struct SuiteChip: View {
    let label: String
    let symbol: String
    var enabled: Bool = false
    var action: () -> Void = {}

    var body: some View {
        Button {
            guard enabled else { return }
            action()
        } label: {
            HStack(spacing: 4) {
                AppIcons.image(for: symbol)
                    .font(.system(size: 10, weight: .semibold))
                Text(enabled ? label : "\(label) · soon")
                    .font(.system(size: 10, weight: .semibold))
            }
            .foregroundStyle(enabled ? BitOSTheme.textPrimary : BitOSTheme.textTertiary)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(
                Capsule().fill(enabled ? BitOSTheme.surfaceElevated : BitOSTheme.surface)
            )
            .overlay(
                Capsule().strokeBorder(
                    enabled ? BitOSTheme.border : BitOSTheme.border.opacity(0.5),
                    lineWidth: 1
                )
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label)\(enabled ? "" : " (soon)")")
    }
}

/// Layers sheet (source-insert management): every IMAGE overlay with its
/// thumbnail, select and delete; "Insert image…" opens the picker. GIF
/// inserts paint their first frame (V1 semantics, said out loud).
private struct LayersSheetView: View {
    @Bindable var store: MemeEditorStore
    var onInsert: () -> Void

    private var layers: [MemeOverlayUi] {
        store.overlays.filter(\.isImage)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            Text("Layers").font(.headline)
            Text("Insert image or GIF sources over the clip (≤\(MemeEditorStore.videoLayerCap)). GIFs paint their first frame.")
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
            if layers.isEmpty {
                Text("No layers yet — insert a source to stack it over the clip.")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            ForEach(layers) { layer in
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    Group {
                        if let assetId = layer.assetId, let image = store.layerImages[assetId] {
                            Image(uiImage: image).resizable().scaledToFill()
                        } else {
                            AppIcons.image(for: AppIcons.photo)
                                .foregroundStyle(BitOSTheme.textSecondary)
                        }
                    }
                    .frame(width: 44, height: 44)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .background(RoundedRectangle(cornerRadius: 8).fill(BitOSTheme.surface))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Layer \(layer.assetId ?? "?")")
                            .font(.caption.weight(.semibold))
                        Text(layer.startMs != nil || layer.endMs != nil
                             ? "\(suiteClock(layer.startMs ?? 0)) – \(suiteClock(layer.endMs ?? 0))"
                             : "always visible")
                            .font(.caption2)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    Spacer()
                    Button("Select") {
                        store.select(layer.id)
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BitOSTheme.accent)
                    Button {
                        store.removeOverlay(layer.id)
                    } label: {
                        AppIcons.image(for: AppIcons.close)
                            .font(.caption)
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    .accessibilityLabel("Delete layer")
                }
            }
            Button {
                onInsert()
            } label: {
                HStack(spacing: 4) {
                    AppIcons.image(for: AppIcons.add).font(.caption)
                    Text("Insert image…").font(.subheadline)
                }
            }
            .buttonStyle(.bordered)
            Spacer(minLength: 0)
        }
        .padding(BitOSTheme.Spacing.md)
    }

}

/// Trim sheet (V2 suite): the export window over the source clip, with
/// the effective output duration (window ÷ speed) said out loud. Applies
/// the same undoable trim command the pick flow uses.
private struct TrimSheetView: View {
    @Bindable var store: MemeEditorStore

    @State private var startSec: Double = 0
    @State private var endSec: Double = 0
    @State private var seeded = false

    /// M5: the sheet trims the SELECTED clip's window in its own source.
    private var clip: MemeEditorStore.EditorClip? {
        store.clips.indices.contains(store.selectedClipIndex)
            ? store.clips[store.selectedClipIndex] : store.clips.first
    }

    private var durationSec: Double {
        max(0.01, Double(clip?.probe.durationMs ?? 0) / 1000)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            Text("Trim").font(.headline)
            if !seeded {
                Color.clear.frame(height: 0)
                    .onAppear {
                        startSec = Double(clip?.startMs ?? store.trimStartMs) / 1000
                        endSec = Double((clip?.endMs ?? store.trimEndMs) > 0 ? (clip?.endMs ?? store.trimEndMs) : (clip?.probe.durationMs ?? 0)) / 1000
                        seeded = true
                    }
            }
            let effective = max(0, endSec - startSec) / Double(store.speed)
            Text(String(
                format: "The selected clip's window over its source. %02d:%02d – %02d:%02d → %02d:%02d at %.2g×",
                Int(startSec) / 60, Int(startSec) % 60,
                Int(endSec) / 60, Int(endSec) % 60,
                Int(effective) / 60, Int(effective) % 60,
                store.speed
            ))
            .font(.caption)
            .foregroundStyle(BitOSTheme.textSecondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(String(format: "Start %02d:%02d", Int(startSec) / 60, Int(startSec) % 60))
                    .font(.caption.weight(.semibold))
                Slider(
                    value: $startSec,
                    in: 0...max(startSec, endSec - 0.2)
                )
                Text(String(format: "End %02d:%02d", Int(endSec) / 60, Int(endSec) % 60))
                    .font(.caption.weight(.semibold))
                Slider(
                    value: $endSec,
                    in: min(endSec, startSec + 0.2)...durationSec
                )
            }
            Button {
                if let clip, let index = store.clips.firstIndex(where: { $0.id == clip.id }) {
                    store.setClipWindow(
                        index: index,
                        startMs: Int64(startSec * 1000),
                        endMs: Int64(endSec * 1000)
                    )
                }
            } label: {
                Text("Apply trim").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(BitOSTheme.accent)
            Spacer(minLength: 0)
        }
        .padding(BitOSTheme.Spacing.md)
    }
}

/// Speed sheet (V2 suite): whole-clip playback rate (web speed-track
/// bounds 0.5–2×). Pitch shifts with the clip in V1 — said out loud.
/// Canvas settings (image/GIF): ratio preset + background color (shared
/// `MemeCanvas` rules through the wire; the media letterboxes onto it).
private struct CanvasSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var store: MemeEditorStore
    @State private var picked = Color.white

    /** Shared 7-color web palette + plain black/white swatches. */
    private let swatches: [String] = [
        "#000000", "#ffffff", "#fde047", "#f97316",
        "#22d3ee", "#a3e635", "#f472b6",
    ]

    private static func color(fromHex hex: String) -> Color? {
        guard hex.count == 7, hex.hasPrefix("#"),
              let value = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        return Color(hex: value)
    }

    private static func hex(_ color: Color) -> String {
        let ui = UIColor(color)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ui.getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(
            format: "#%02x%02x%02x",
            Int((r * 255).rounded()), Int((g * 255).rounded()), Int((b * 255).rounded())
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            Text("Canvas").font(.headline)
            if store.isVideoMode {
                Text("Video keeps its source frame in V1 — canvas settings apply to image and GIF memes.")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)
            } else {
                Text("Size")
                    .font(.subheadline.weight(.semibold))
                HStack(spacing: BitOSTheme.Spacing.xs) {
                    ForEach(
                        ["source", "1:1", "4:5", "9:16", "16:9"],
                        id: \.self
                    ) { ratio in
                        let label = ratio == "source" ? "Source" : ratio
                        Button(label) {
                            store.setCanvas(ratio: ratio, bg: store.canvasBg)
                        }
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(
                            (store.canvasRatio ?? "source") == ratio
                                ? BitOSTheme.accent : BitOSTheme.surface
                        )
                        .foregroundStyle(
                            (store.canvasRatio ?? "source") == ratio ? Color.black : BitOSTheme.textPrimary
                        )
                        .clipShape(Capsule())
                    }
                }
                Text("Background")
                    .font(.subheadline.weight(.semibold))
                HStack(spacing: BitOSTheme.Spacing.xs) {
                    ForEach(swatches, id: \.self) { hex in
                        Button {
                            store.setCanvas(ratio: store.canvasRatio, bg: hex)
                        } label: {
                            Circle()
                                .fill(Self.color(fromHex: hex) ?? .white)
                                .frame(width: 28, height: 28)
                                .overlay(
                                    Circle().strokeBorder(
                                        store.canvasBg == hex ? BitOSTheme.accent : BitOSTheme.border,
                                        lineWidth: store.canvasBg == hex ? 2 : 1
                                    )
                                )
                                .background(Self.color(fromHex: hex) ?? .clear)
                        }
                        .accessibilityLabel("Background \(hex)")
                    }
                    ColorPicker("Custom", selection: $picked, supportsOpacity: false)
                        .labelsHidden()
                        .onChange(of: picked) { _, color in
                            store.setCanvas(ratio: store.canvasRatio, bg: Self.hex(color))
                        }
                    if store.canvasBg != nil {
                        Button("Clear") {
                            store.setCanvas(ratio: store.canvasRatio, bg: nil)
                        }
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                    }
                }
                Text("The media letterboxes onto the canvas; captions and layers keep their positions.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            Button("Done") { dismiss() }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(BitOSTheme.accent)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.horizontal, BitOSTheme.Spacing.md)
        .padding(.top, BitOSTheme.Spacing.sm)
        .padding(.bottom, BitOSTheme.Spacing.lg)
    }
}

private struct SpeedSheetView: View {
    @Bindable var store: MemeEditorStore

    private var mediaDurationMs: Int64 {
        store.trimEndMs > 0 ? store.trimEndMs : (store.videoProbe?.durationMs ?? 0)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            Text("Speed").font(.headline)
            Text("Whole-clip rate (0.5–2×). Audio pitch follows the clip in V1.")
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
            HStack(spacing: BitOSTheme.Spacing.xs) {
                ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { option in
                    Button {
                        store.setSpeed(Float(option))
                    } label: {
                        Text(String(format: "%g×", option))
                            .font(.caption.weight(.bold))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(
                                Capsule().fill(
                                    abs(store.speed - Float(option)) < 0.01
                                        ? BitOSTheme.accent : BitOSTheme.surfaceElevated
                                )
                            )
                            .overlay(
                                Capsule().strokeBorder(
                                    abs(store.speed - Float(option)) < 0.01
                                        ? BitOSTheme.accent : BitOSTheme.border,
                                    lineWidth: 1
                                )
                            )
                            .foregroundStyle(
                                abs(store.speed - Float(option)) < 0.01
                                    ? Color.black : BitOSTheme.textPrimary
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(format: "%g times speed", option))
                }
            }
            if mediaDurationMs > 0 {
                let out = Double(mediaDurationMs) / Double(store.speed)
                Text(String(
                    format: "Output length %02d:%02d (from %02d:%02d)",
                    Int(out) / 60000, (Int(out) % 60000) / 1000,
                    Int(mediaDurationMs) / 60000, (Int(mediaDurationMs) % 60000) / 1000
                ))
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .padding(BitOSTheme.Spacing.md)
    }
}

/// M5 clip sheet: the timeline's clip list — select, reorder, remove, add.
private struct ClipsSheetView: View {
    @Bindable var store: MemeEditorStore
    var onAdd: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            Text("Clips").font(.headline)
            Text(String(
                format: "%d clip(s) play back-to-back · %02d:%02d total",
                store.clips.count,
                Int(store.timelineDurationMs) / 60000,
                (Int(store.timelineDurationMs) % 60000) / 1000
            ))
            .font(.caption)
            .foregroundStyle(BitOSTheme.textSecondary)
            ForEach(Array(store.clips.enumerated()), id: \.element.id) { index, clip in
                let selected = index == store.selectedClipIndex
                Button {
                    store.selectClip(index)
                } label: {
                    HStack(spacing: BitOSTheme.Spacing.xs) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("vdo \(index + 1) · \(clip.id)")
                                .font(.caption.weight(.semibold))
                            Text(String(
                                format: "%02d:%02d – %02d:%02d (source) → %02d:%02d at %.2g×",
                                Int(clip.startMs) / 60000, (Int(clip.startMs) % 60000) / 1000,
                                Int(clip.endMs) / 60000, (Int(clip.endMs) % 60000) / 1000,
                                Int(store.clipOutputMs(clip)) / 60000,
                                (Int(store.clipOutputMs(clip)) % 60000) / 1000,
                                store.rate
                            ))
                            .font(.caption2)
                            .foregroundStyle(BitOSTheme.textSecondary)
                        }
                        Spacer()
                        Button {
                            store.moveClip(at: index, by: -1)
                        } label: {
                            Image(systemName: "chevron.left")
                                .foregroundStyle(BitOSTheme.textSecondary)
                        }
                        .disabled(index == 0)
                        .accessibilityLabel("Move clip earlier")
                        Button {
                            store.moveClip(at: index, by: 1)
                        } label: {
                            Image(systemName: "chevron.right")
                                .foregroundStyle(BitOSTheme.textSecondary)
                        }
                        .disabled(index == store.clips.count - 1)
                        .accessibilityLabel("Move clip later")
                        Button {
                            store.removeClip(at: index)
                        } label: {
                            Image(systemName: "xmark")
                                .foregroundStyle(BitOSTheme.error)
                        }
                        .accessibilityLabel("Remove clip")
                    }
                    .padding(BitOSTheme.Spacing.sm)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(selected ? BitOSTheme.accent.opacity(0.12) : BitOSTheme.surfaceElevated)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .strokeBorder(selected ? BitOSTheme.accent : BitOSTheme.border, lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clip \(index + 1)")
            }
            Button {
                onAdd()
            } label: {
                HStack {
                    Image(systemName: "plus")
                    Text("Add clip…")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            Spacer(minLength: 0)
        }
        .padding(BitOSTheme.Spacing.md)
    }
}

/// M5 per-clip audio sheet: volume 0…2× (0 = mute) for the selected clip.
private struct VolumeSheetView: View {
    @Bindable var store: MemeEditorStore
    @State private var value: Double = 1

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            Text("Volume").font(.headline)
            Text("vdo \(min(store.selectedClipIndex, max(0, store.clips.count - 1)) + 1) · volume applies to preview and export")
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
            HStack {
                Toggle(isOn: Binding(
                    get: { value <= 0 },
                    set: { value = $0 ? 0 : 1 }
                )) {
                    Text("Mute").font(.caption)
                }
                .toggleStyle(.switch)
                Spacer()
                Text(value <= 0 ? "muted" : String(format: "%.2f×", value))
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            Slider(value: $value, in: 0...2)
            Button {
                if store.clips.indices.contains(store.selectedClipIndex) {
                    store.setClipVolume(index: store.selectedClipIndex, volume: Float(value))
                }
            } label: {
                Text("Apply").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(BitOSTheme.accent)
            Spacer(minLength: 0)
        }
        .padding(BitOSTheme.Spacing.md)
        .onAppear {
            value = Double(
                store.clips.indices.contains(store.selectedClipIndex)
                    ? store.clips[store.selectedClipIndex].volume : 1
            )
        }
    }
}

/// Draw-mode capture layer (V2 Draw chip): fits the media rect (stage
/// parity), paints the committed strokes + the live in-progress one, and
/// owns the drag — one normalized polyline per gesture, committed as an
/// undoable stroke-add command.
private struct DrawLayerIos: View {
    let strokes: [MemeStrokeUi]
    let livePoints: [Float]
    let colorIndex: Int
    let widthNorm: Float
    let paletteHex: [String]
    let aspect: CGFloat
    let onLiveChange: ([Float]) -> Void
    let onStroke: ([Float]) -> Void

    var body: some View {
        GeometryReader { proxy in
            let fitted = fittedSize(container: proxy.size)
            ZStack {
                Canvas { context, size in
                    for stroke in strokes {
                        drawStroke(
                            context: context, size: size,
                            colorIndex: stroke.colorIndex,
                            widthNorm: stroke.widthNorm,
                            points: stroke.points
                        )
                    }
                    if livePoints.count >= 4 {
                        drawStroke(
                            context: context, size: size,
                            colorIndex: colorIndex,
                            widthNorm: widthNorm,
                            points: livePoints
                        )
                    }
                }
                .frame(width: fitted.width, height: fitted.height)
                .position(
                    x: proxy.size.width / 2 + (fitted.width - proxy.size.width) / 2,
                    y: proxy.size.height / 2 + (fitted.height - proxy.size.height) / 2
                )
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let x = Float((value.location.x - (proxy.size.width - fitted.width) / 2) / max(1, fitted.width))
                        let y = Float((value.location.y - (proxy.size.height - fitted.height) / 2) / max(1, fitted.height))
                        var points = onLiveSnapshot()
                        // Dedupe micro-jitter (≤ 2 pt screen distance).
                        if points.count >= 2,
                           abs(x - points[points.count - 2]) < 0.004,
                           abs(y - points[points.count - 1]) < 0.004 { return }
                        points.append(min(max(x, 0), 1))
                        points.append(min(max(y, 0), 1))
                        if points.count > 3000 { return }
                        onLiveChange(points)
                    }
                    .onEnded { _ in
                        let points = onLiveSnapshot()
                        onLiveChange([])
                        if points.count >= 4 { onStroke(points) }
                    }
            )
        }
    }

    /// The live points ride the parent state; snapshots on demand.
    private func onLiveSnapshot() -> [Float] { livePoints }

    private func fittedSize(container: CGSize) -> CGSize {
        guard aspect > 0 else { return container }
        let byWidth = CGSize(width: container.width, height: container.width / aspect)
        if byWidth.height <= container.height { return byWidth }
        return CGSize(width: container.height * aspect, height: container.height)
    }

    private func drawStroke(
        context: GraphicsContext,
        size: CGSize,
        colorIndex: Int,
        widthNorm: Float,
        points: [Float]
    ) {
        guard points.count >= 4 else { return }
        var path = Path()
        path.move(to: CGPoint(x: CGFloat(points[0]) * size.width, y: CGFloat(points[1]) * size.height))
        var index = 2
        while index + 1 < points.count {
            path.addLine(to: CGPoint(x: CGFloat(points[index]) * size.width, y: CGFloat(points[index + 1]) * size.height))
            index += 2
        }
        context.stroke(
            path,
            with: .color(Color(OverlayUiView.paletteColor(colorIndex, paletteHex: paletteHex))),
            lineWidth: CGFloat(widthNorm) * size.height
        )
    }
}

/// Pen controls while draw mode is active: palette dots, three widths,
/// undo-stroke, clear, done.
private struct PenControlsRowIos: View {
    let store: MemeEditorStore
    let colorIndex: Int
    let onPickColor: (Int) -> Void
    let widthNorm: Float
    let onPickWidth: (Float) -> Void
    let onDone: () -> Void

    private let penPalette = [0, 1, 2, 4, 6, 8, 10, 12]
    private let widths: [(Float, String)] = [(0.004, "Thin"), (0.008, "Medium"), (0.016, "Thick")]

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            ForEach(penPalette, id: \.self) { index in
                Button {
                    onPickColor(index)
                } label: {
                    Circle()
                        .fill(Color(OverlayUiView.paletteColor(index, paletteHex: store.paletteHex)))
                        .frame(width: index == colorIndex ? 22 : 16)
                        .overlay(
                            Circle().strokeBorder(
                                index == colorIndex ? BitOSTheme.accent : BitOSTheme.border,
                                lineWidth: index == colorIndex ? 2 : 1
                            )
                        )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Pen color \(index)")
            }
            Spacer()
            ForEach(widths, id: \.0) { width, label in
                Button {
                    onPickWidth(width)
                } label: {
                    Circle()
                        .strokeBorder(
                            width == widthNorm ? BitOSTheme.accent : BitOSTheme.border,
                            lineWidth: width == widthNorm ? 2 : 1
                        )
                        .frame(width: width == widthNorm ? 22 : 16)
                        .overlay(Circle().fill(BitOSTheme.textPrimary).frame(width: 6))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(label) pen")
            }
            Button {
                store.removeLastStroke()
            } label: {
                AppIcons.image(for: AppIcons.undo)
                    .foregroundStyle(store.drawStrokes.isEmpty ? BitOSTheme.textTertiary : BitOSTheme.textPrimary)
            }
            .disabled(store.drawStrokes.isEmpty)
            .accessibilityLabel("Undo stroke")
            Button {
                store.clearDrawing()
            } label: {
                AppIcons.image(for: AppIcons.delete)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .disabled(store.drawStrokes.isEmpty)
            .accessibilityLabel("Clear drawing")
            Button("Done") { onDone() }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(BitOSTheme.accent)
        }
        .padding(.horizontal, BitOSTheme.Spacing.md)
        .padding(.vertical, BitOSTheme.Spacing.xs)
    }
}
