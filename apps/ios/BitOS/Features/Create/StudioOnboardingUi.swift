import Foundation

/// Studio onboarding adapter (MSU-030..033): the device-local "coach seen"
/// flag plus tolerant decoders for the shared coach/empty-state copy.
///
/// The copy and the eligibility RULES live in the shared core
/// (`StudioOnboarding`); this only carries them across the platform
/// boundary. Mirrors the Android `StudioOnboardingUi.kt`.
enum StudioCoachPrefs {
    private static let key = "studio_coach_seen"

    static var hasSeenCoach: Bool {
        UserDefaults.standard.bool(forKey: key)
    }

    static func markCoachSeen() {
        UserDefaults.standard.set(true, forKey: key)
    }
}

/// One decoded coach step (shared `StudioOnboarding.CoachStep`).
struct StudioCoachStep: Identifiable, Equatable {
    let id: String
    let anchor: String
    let icon: String
    let title: String
    let body: String
}

/// Decoded coach plan: whether to run plus the shared steps + labels.
struct StudioCoachPlan: Equatable {
    let run: Bool
    let skipLabel: String
    let doneLabel: String
    let steps: [StudioCoachStep]

    static let none = StudioCoachPlan(run: false, skipLabel: "Skip", doneLabel: "Got it", steps: [])

    /// Tolerant decode: junk JSON degrades to "do not run" — guidance must
    /// never crash or block the editor.
    static func decode(_ json: String) -> StudioCoachPlan {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return .none }
        let steps: [StudioCoachStep] = (root["steps"] as? [[String: Any]] ?? []).compactMap { row in
            guard let id = row["id"] as? String, !id.isEmpty else { return nil }
            return StudioCoachStep(
                id: id,
                anchor: row["anchor"] as? String ?? "",
                icon: row["icon"] as? String ?? "",
                title: row["title"] as? String ?? "",
                body: row["body"] as? String ?? ""
            )
        }
        return StudioCoachPlan(
            run: root["run"] as? Bool ?? false,
            skipLabel: root["skipLabel"] as? String ?? "Skip",
            doneLabel: root["doneLabel"] as? String ?? "Got it",
            steps: steps
        )
    }

    /// Runs the shared eligibility rules through the bridge.
    static func query(
        hasSeenCoach: Bool,
        isResume: Bool,
        isRemix: Bool,
        isSoundSeed: Bool,
        isTemplateSeed: Bool,
        isCameraHandoff: Bool
    ) -> StudioCoachPlan {
        decode(
            FrameworkBusinessCoreClient().memeCoachPlan(
                hasSeenCoach: hasSeenCoach, isResume: isResume, isRemix: isRemix,
                isSoundSeed: isSoundSeed, isTemplateSeed: isTemplateSeed,
                isCameraHandoff: isCameraHandoff
            )
        )
    }
}

/// Decoded guiding empty-state copy for the current mode.
struct StudioEmptyState: Equatable {
    let title: String
    let body: String
    let icon: String
    let primary: String
    let secondary: String?
    let tertiary: String?
    let hint: String
    let undoHint: String
    let nothingToUndo: String
    let nothingToRedo: String

    static let imageFallback = StudioEmptyState(
        title: "Start with an image",
        body: "Tap Media to pick a photo, or start from a blank canvas.",
        icon: "media",
        primary: "Pick an image",
        secondary: "Start blank canvas",
        tertiary: nil,
        hint: "Add text, a sticker or a look — everything autosaves.",
        undoHint: "Undone — redo is beside undo in the top bar.",
        nothingToUndo: "Nothing to undo",
        nothingToRedo: "Nothing to redo"
    )

    /// Decodes the bridge seam for `mode` (`image|gif|video`). Junk falls
    /// back to the image copy so the screen is never blank.
    static func decode(mode: String) -> StudioEmptyState {
        guard let data = FrameworkBusinessCoreClient().memeEmptyState(mode: mode).data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return .imageFallback }
        func text(_ key: String, _ fallback: String) -> String {
            let value = root[key] as? String ?? ""
            return value.isEmpty ? fallback : value
        }
        func optional(_ key: String) -> String? {
            let value = root[key] as? String ?? ""
            return value.isEmpty ? nil : value
        }
        return StudioEmptyState(
            title: text("title", imageFallback.title),
            body: text("body", imageFallback.body),
            icon: text("icon", imageFallback.icon),
            primary: text("primary", imageFallback.primary),
            secondary: optional("secondary"),
            tertiary: optional("tertiary"),
            hint: text("hint", imageFallback.hint),
            undoHint: text("undoHint", imageFallback.undoHint),
            nothingToUndo: text("nothingToUndo", imageFallback.nothingToUndo),
            nothingToRedo: text("nothingToRedo", imageFallback.nothingToRedo)
        )
    }
}

/// MSU-050..052 publish-vs-export copy (explainer, verbs, review order,
/// result-card labels). Decoded from the bridge; junk falls back to working
/// copy so the flow is never blank. Mirrors Android `StudioPublishCopy`.
struct StudioPublishCopy: Equatable {
    let publishExplainer: String
    let primaryAction: String
    let exportAction: String
    let posted: String
    let view: String
    let share: String
    let makeAnother: String
    let verifyBeforeSign: String
    let reviewSteps: [(id: String, title: String)]

    static func == (lhs: StudioPublishCopy, rhs: StudioPublishCopy) -> Bool {
        lhs.publishExplainer == rhs.publishExplainer &&
            lhs.primaryAction == rhs.primaryAction &&
            lhs.exportAction == rhs.exportAction &&
            lhs.posted == rhs.posted &&
            lhs.view == rhs.view &&
            lhs.share == rhs.share &&
            lhs.makeAnother == rhs.makeAnother &&
            lhs.verifyBeforeSign == rhs.verifyBeforeSign &&
            lhs.reviewSteps.map(\.id) == rhs.reviewSteps.map(\.id) &&
            lhs.reviewSteps.map(\.title) == rhs.reviewSteps.map(\.title)
    }

    static let fallback = StudioPublishCopy(
        publishExplainer: "Publish posts to Nostr · Export saves a file to this device.",
        primaryAction: "Next",
        exportAction: "Save a copy",
        posted: "Posted",
        view: "View",
        share: "Share",
        makeAnother: "Make another",
        verifyBeforeSign: "Media uploads and hash-verifies before anything is signed.",
        reviewSteps: [
            ("preview", "Preview"),
            ("caption", "Caption"),
            ("tags", "Tags"),
            ("safety", "Safety"),
            ("publish", "Publish"),
        ]
    )

    /// Tolerant decode via the bridge seam; junk degrades to the fallback.
    static func decode() -> StudioPublishCopy {
        guard let data = FrameworkBusinessCoreClient().memePublishCopy().data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return .fallback }
        func text(_ key: String, _ fallbackValue: String) -> String {
            let value = root[key] as? String ?? ""
            return value.isEmpty ? fallbackValue : value
        }
        let steps: [(id: String, title: String)] =
            (root["reviewSteps"] as? [[String: Any]] ?? []).compactMap { row in
                guard let id = row["id"] as? String, !id.isEmpty else { return nil }
                return (id, row["title"] as? String ?? "")
            }
        return StudioPublishCopy(
            publishExplainer: text("publishExplainer", fallback.publishExplainer),
            primaryAction: text("primaryAction", fallback.primaryAction),
            exportAction: text("exportAction", fallback.exportAction),
            posted: text("posted", fallback.posted),
            view: text("view", fallback.view),
            share: text("share", fallback.share),
            makeAnother: text("makeAnother", fallback.makeAnother),
            verifyBeforeSign: text("verifyBeforeSign", fallback.verifyBeforeSign),
            reviewSteps: steps.isEmpty ? fallback.reviewSteps : steps
        )
    }
}

/// One documented operator shortcut (shared `StudioProduction.Shortcut`).
struct StudioShortcut: Identifiable, Equatable {
    let id: String
    /// Platform-neutral token string (`mod+shift+z`).
    let keys: String
    let label: String
}

/// One busy surface (shared `StudioProgress.ProgressSurface`).
struct StudioProgressSurface: Identifiable, Equatable {
    let id: String
    let title: String
    let body: String
    let determinate: Bool
}

/// One audited destructive surface (shared `StudioProgress.ConfirmRule`).
struct StudioConfirmRule: Identifiable, Equatable {
    let id: String
    /// `"confirm"` (irreversible ⇒ dialog) or `"undo"` (reversible ⇒ Undo notice).
    let mode: String
    let title: String
    let body: String
}

/// MSU-042..043 feedback-closure copy: busy-surface titles/bodies and the
/// confirm-vs-undo classification for every destructive surface. Decoded
/// from the bridge; junk falls back to working copy. Mirrors Android
/// `StudioFeedbackCopy`.
struct StudioFeedbackCopy: Equatable {
    let progress: [StudioProgressSurface]
    let confirms: [StudioConfirmRule]

    /// Resolve a surface by id; unknown ids return the export render.
    func surfaceFor(_ id: String?) -> StudioProgressSurface {
        progress.first { $0.id == id } ?? progress.first ?? StudioProgressSurface(
            id: "export-render",
            title: "Rendering your meme…",
            body: "This can take a moment for long clips — keep the screen open.",
            determinate: false
        )
    }

    /// True when the surface must show a confirm dialog (MSU-043).
    func requiresDialog(_ id: String?) -> Bool {
        confirms.first { $0.id == id }?.mode == "confirm"
    }

    /// The determinate fraction (0...1); nil when not determinate — never a
    /// simulated bar.
    func fraction(_ surface: StudioProgressSurface, done: Int, total: Int) -> Double? {
        guard surface.determinate else { return nil }
        guard total > 0 else { return 0 }
        return min(max(Double(done) / Double(total), 0), 1)
    }

    static let fallback = StudioFeedbackCopy(
        progress: [
            StudioProgressSurface(id: "import-clips", title: "Preparing clips…", body: "Probing and staging your source clips.", determinate: true),
            StudioProgressSurface(id: "export-render", title: "Rendering your meme…", body: "This can take a moment for long clips — keep the screen open.", determinate: false),
            StudioProgressSurface(id: "gif-ladder", title: "Fitting the GIF…", body: "Re-sampling frames to fit the size limit.", determinate: true),
            StudioProgressSurface(id: "batch-render", title: "Rendering variants…", body: "Every approved variant renders before it publishes.", determinate: true),
            StudioProgressSurface(id: "publish", title: "Publishing…", body: "Media uploads and hash-verifies before anything is signed.", determinate: true),
        ],
        confirms: [
            StudioConfirmRule(id: "delete-overlay", mode: "undo", title: "Delete overlay", body: ""),
            StudioConfirmRule(id: "delete-clip", mode: "undo", title: "Delete clip", body: ""),
            StudioConfirmRule(id: "delete-layer", mode: "undo", title: "Delete layer", body: ""),
            StudioConfirmRule(id: "mode-switch", mode: "confirm", title: "Start a new project?", body: "Switching clears the current media (overlays stay). Continue?"),
            StudioConfirmRule(id: "discard-draft", mode: "confirm", title: "Could not save the draft", body: "Your edits are still open. Retry the save to keep them, or delete the draft deliberately."),
            StudioConfirmRule(id: "discard-takes", mode: "confirm", title: "Discard takes?", body: "Recorded takes are removed when you leave."),
        ]
    )

    /// Tolerant decode via the bridge seam; junk degrades to the fallback.
    static func decode() -> StudioFeedbackCopy {
        guard let data = FrameworkBusinessCoreClient().memeFeedbackCopy().data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return .fallback }
        let progress: [StudioProgressSurface] =
            (root["progress"] as? [[String: Any]] ?? []).compactMap { row in
                guard let id = row["id"] as? String, !id.isEmpty else { return nil }
                return StudioProgressSurface(
                    id: id,
                    title: row["title"] as? String ?? "",
                    body: row["body"] as? String ?? "",
                    determinate: row["determinate"] as? Bool ?? false
                )
            }
        let confirms: [StudioConfirmRule] =
            (root["confirms"] as? [[String: Any]] ?? []).compactMap { row in
                guard let id = row["id"] as? String, !id.isEmpty else { return nil }
                return StudioConfirmRule(
                    id: id,
                    mode: row["mode"] as? String ?? "confirm",
                    title: row["title"] as? String ?? "",
                    body: row["body"] as? String ?? ""
                )
            }
        return StudioFeedbackCopy(
            progress: progress.isEmpty ? fallback.progress : progress,
            confirms: confirms.isEmpty ? fallback.confirms : confirms
        )
    }
}

/// MSU-060..063 mass-production + operator copy (batch base, batch status
/// strip, template-first batch, controls reference). Decoded from the
/// bridge; junk falls back to working copy so the flow is never blank.
/// Mirrors Android `StudioProductionCopy`.
///
/// Revised for touch-first phones: the reference is now [controls] — each
/// row names its on-screen affordance first and its optional hardware key
/// second — rather than a keyboard-only list a phone could never use.
struct StudioProductionCopy: Equatable {
    let batchBaseAction: String
    let batchBaseExplainer: String
    let batchSeeded: String
    let batchQueueLink: String
    let templateBatchCount: Int
    let templateBatchExplainer: String
    let controlsTitle: String
    let touchSectionTitle: String
    let keyboardSectionTitle: String
    let keyboardAbsentHint: String
    let shortcutsHint: String
    let controls: [StudioControl]

    /// Rows that have a bound hardware key (the keyboard section).
    var keyboardRows: [StudioControl] { controls.filter { !$0.keys.isEmpty } }

    /// MSU-061: the in-editor strip text, or nil when there is no batch.
    /// Clamps a hostile rendered count into `0...total` (never "9 of 8").
    func batchStrip(rendered: Int, total: Int) -> String? {
        guard total > 0 else { return nil }
        let done = min(max(rendered, 0), total)
        return "\(done) of \(total) rendered · \(batchQueueLink)"
    }

    /// MSU-062: the template-rail action label ("Make 3 variants").
    func templateBatchAction(count: Int) -> String {
        let bounded = min(max(count, 1), 12)
        return "Make \(bounded) variant\(bounded == 1 ? "" : "s")"
    }

    static let fallback = StudioProductionCopy(
        batchBaseAction: "Use as batch base",
        batchBaseExplainer: "Freezes this design as a reusable batch base — every variant varies the caption, not the layout.",
        batchSeeded: "Batch seeded from this design — caption slots are ready in the queue.",
        batchQueueLink: "View queue",
        templateBatchCount: 3,
        templateBatchExplainer: "Seeds a batch from this template — one caption slot per variant, editable in the queue.",
        controlsTitle: "Controls",
        touchSectionTitle: "On screen",
        keyboardSectionTitle: "Keyboard (optional)",
        keyboardAbsentHint: "Connect a keyboard for faster editing — every control here is also on screen.",
        shortcutsHint: "Works with a hardware or desktop-class keyboard; touch equivalents stay on screen.",
        controls: [
            StudioControl(id: "undo", label: "Undo", touch: "Top-bar ↶", keys: "mod+z"),
            StudioControl(id: "redo", label: "Redo", touch: "Top-bar ↷", keys: "mod+shift+z"),
            StudioControl(id: "play-pause", label: "Play / pause", touch: "Timeline ▷", keys: "space"),
            StudioControl(id: "prev", label: "Select previous", touch: "Tap a lane or layer", keys: "alt+arrowup"),
            StudioControl(id: "next", label: "Select next", touch: "Tap a lane or layer", keys: "alt+arrowdown"),
            StudioControl(id: "export", label: "Save a copy", touch: "Header ⤓", keys: "mod+e"),
            StudioControl(id: "publish", label: "Review & publish", touch: "Header Next", keys: "mod+enter"),
            StudioControl(id: "timeline", label: "Timeline workspace", touch: "More ▸ Timeline", keys: "mod+t"),
            StudioControl(id: "delete", label: "Delete selection", touch: "Select, then Delete", keys: "backspace"),
        ]
    )

    /// Tolerant decode via the bridge seam; junk degrades to the fallback.
    static func decode() -> StudioProductionCopy {
        guard let data = FrameworkBusinessCoreClient().memeProductionCopy().data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return .fallback }
        func text(_ key: String, _ fallbackValue: String) -> String {
            let value = root[key] as? String ?? ""
            return value.isEmpty ? fallbackValue : value
        }
        let controls: [StudioControl] =
            (root["controls"] as? [[String: Any]] ?? []).compactMap { row in
                guard let id = row["id"] as? String, !id.isEmpty else { return nil }
                return StudioControl(
                    id: id,
                    label: row["label"] as? String ?? "",
                    touch: row["touch"] as? String ?? "",
                    keys: row["keys"] as? String ?? ""
                )
            }
        return StudioProductionCopy(
            batchBaseAction: text("batchBaseAction", fallback.batchBaseAction),
            batchBaseExplainer: text("batchBaseExplainer", fallback.batchBaseExplainer),
            batchSeeded: text("batchSeeded", fallback.batchSeeded),
            batchQueueLink: text("batchQueueLink", fallback.batchQueueLink),
            templateBatchCount: root["templateBatchCount"] as? Int ?? fallback.templateBatchCount,
            templateBatchExplainer: text("templateBatchExplainer", fallback.templateBatchExplainer),
            controlsTitle: text("controlsTitle", fallback.controlsTitle),
            touchSectionTitle: text("touchSectionTitle", fallback.touchSectionTitle),
            keyboardSectionTitle: text("keyboardSectionTitle", fallback.keyboardSectionTitle),
            keyboardAbsentHint: text("keyboardAbsentHint", fallback.keyboardAbsentHint),
            shortcutsHint: text("shortcutsHint", fallback.shortcutsHint),
            controls: controls.isEmpty ? fallback.controls : controls
        )
    }
}

/// One documented editor control (shared `StudioProduction.Control`).
struct StudioControl: Identifiable, Equatable {
    let id: String
    let label: String
    /// The always-available on-screen affordance (touch).
    let touch: String
    /// Optional hardware accelerator; empty when none is bound.
    let keys: String
}