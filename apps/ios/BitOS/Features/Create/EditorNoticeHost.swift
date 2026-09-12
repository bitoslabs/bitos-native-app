import Foundation
import Observation

/// The visible notice decoded from the shared host (MSU-040).
struct EditorNoticeUi: Equatable, Identifiable {
    let id: Int64
    let severity: String   // "info" | "success" | "error"
    let message: String
    let timeoutMs: Int
    let actionId: String?
    let actionLabel: String?
}

/// SwiftUI-facing wrapper over the shared `NoticeHost` (plan MSU-040).
///
/// The shipped editor wrote results to `notice` / `exportState` and rendered
/// them with no timer, so "Layer added" and "Save failed" looked identical
/// and never went away. This owns the lifecycle — the RULES live in the
/// shared `NoticeHost` via the bridge, so both platforms behave the same.
///
/// `postLegacyStatus` is a **migration shim** for the existing call sites:
/// it classifies the message by content so un-migrated strings still get a
/// sensible severity. New code should call `info` / `success` / `error` /
/// `undoable`.
@Observable
final class EditorNoticeHost {
    private(set) var current: EditorNoticeUi?
    /// 0..1 progress toward auto-dismiss (1 = persistent).
    private(set) var remaining: Float = 0

    private var stateJson = ""
    private let client: any BusinessCoreClient

    init(client: any BusinessCoreClient = FrameworkBusinessCoreClient()) {
        self.client = client
    }

    func post(severity: String, message: String, actionId: String? = nil, actionLabel: String? = nil) {
        stateJson = client.memeNoticePost(
            stateJson: stateJson, severity: severity, message: message,
            actionId: actionId, actionLabel: actionLabel, timeoutMs: -1
        )
        sync()
    }

    func info(_ message: String) { post(severity: "info", message: message) }

    func success(_ message: String) { post(severity: "success", message: message) }

    func error(_ message: String) { post(severity: "error", message: message) }

    /// A reversible destructive result — always offers Undo (MSU-041).
    func undoable(_ message: String) {
        post(severity: "info", message: message, actionId: "undo", actionLabel: "Undo")
    }

    /// Migration shim (see the class doc).
    func postLegacyStatus(_ message: String) {
        if Self.looksLikeFailure(message) { error(message) } else { info(message) }
    }

    func dismiss() {
        stateJson = client.memeNoticeDismiss(stateJson: stateJson)
        sync()
    }

    /// Advance the host clock; called from the host's timer loop.
    func tick(deltaMs: Int) {
        guard current != nil else { return }
        stateJson = client.memeNoticeTick(stateJson: stateJson, deltaMs: deltaMs)
        sync()
    }

    /// Parse the bridge JSON into UI state.
    private func sync() {
        guard let data = stateJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            current = nil
            remaining = 0
            return
        }
        remaining = (root["remaining"] as? NSNumber)?.floatValue ?? 0
        guard let notice = root["notice"] as? [String: Any] else {
            current = nil
            return
        }
        let action = notice["action"] as? [String: Any]
        current = EditorNoticeUi(
            id: (notice["id"] as? NSNumber)?.int64Value ?? 0,
            severity: notice["severity"] as? String ?? "info",
            message: notice["message"] as? String ?? "",
            timeoutMs: (notice["timeoutMs"] as? NSNumber)?.intValue ?? 0,
            actionId: action?["id"] as? String,
            actionLabel: action?["label"] as? String
        )
    }

    private static func looksLikeFailure(_ message: String) -> Bool {
        let lower = message.lowercased()
        return failureMarkers.contains { lower.contains($0) }
    }

    private static let failureMarkers = [
        "could not", "failed", "not readable", "unreadable", "missing",
        "mismatch", "limit reached", "is larger than", "no readable",
        "is not loadable", "not loadable", "broken", "cannot", "can't",
        "exceed", "too close", "still", "before switching",
    ]
}
