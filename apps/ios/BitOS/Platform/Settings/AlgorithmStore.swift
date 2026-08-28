import BusinessCore
import Foundation
import Observation

/**
 * APP-018 algorithm-preferences adapter (origin parity): persists the
 * versioned shared wire in UserDefaults and republishes typed state for
 * the settings UI. All rules (presets, steps, freshness, normalization)
 * live in business-core through the bridge; this adapter only persists,
 * decodes and notifies. Every mutation re-encodes the canonical wire and
 * hands it to [sink] — the feed store re-ranks For-You from that wire.
 */
@MainActor
@Observable
final class AlgorithmStore {
    static let storageKey = "algo_prefs_v1"

    private(set) var freshnessHours: Int
    private(set) var surfaces: [String: SurfaceState]
    private let defaults: UserDefaults
    private let bridge = BusinessCoreBridge()

    /// Receives the canonical wire on boot and every change (feeds the ranker).
    var sink: ((String) -> Void)?

    struct SignalState: Equatable {
        var enabled: Bool
        var weight: Double
    }

    struct SurfaceState: Equatable {
        var enabled: Bool
        var diversityEnabled: Bool = true
        var signals: [String: SignalState]
    }

    init(defaults: UserDefaults? = UserDefaults(suiteName: "bitos_algo")) {
        self.defaults = defaults ?? .standard
        let stored = self.defaults.string(forKey: Self.storageKey)
        let decoded = bridge.algorithmSnapshotDecode(json: stored ?? "")
        freshnessHours = Int(decoded.freshnessHours)
        surfaces = decoded.surfaces.mapValues(algoSurfaceToState)
        // Canonical boot push (a corrupt store self-heals to defaults).
        sink?(bridge.algorithmSnapshotEncode(wire: decoded))
    }

    // MARK: - Edits (through shared rules, then persist + notify)

    func setEnabled(surface: String, enabled: Bool) {
        guard var state = surfaces[surface] else { return }
        state.enabled = enabled
        surfaces[surface] = state
        persist()
    }

    func setPreset(surface: String, preset: String) {
        let presetWire = bridge.algorithmPresetWire(surfaceWire: surface, presetWire: preset)
        surfaces[surface] = algoSurfaceToState(presetWire)
        persist()
    }

    func setDiversity(surface: String, enabled: Bool) {
        guard var state = surfaces[surface] else { return }
        state.diversityEnabled = enabled
        surfaces[surface] = state
        persist()
    }

    /// Reset: re-applies the detected preset (clears custom weight tweaks).
    func resetToPreset(surface: String) {
        setPreset(surface: surface, preset: detectPreset(surface: surface))
    }

    func setSignal(surface: String, signal: String, enabled: Bool, weight: Double) {
        var state = surfaces[surface] ?? SurfaceState(enabled: true, signals: [:])
        state.signals[signal] = SignalState(enabled: enabled, weight: weight)
        surfaces[surface] = state
        persist()
    }

    func setFreshness(hours: Int) {
        freshnessHours = hours
        persist()
    }

    func detectPreset(surface: String) -> String {
        guard let state = surfaces[surface] else { return "BALANCED" }
        return bridge.algorithmDetectPreset(surfaceWire: surface, wire: algoStateToSurface(state))
    }

    func signalState(surface: String, signal: String) -> SignalState {
        surfaces[surface]?.signals[signal] ?? SignalState(enabled: false, weight: 0)
    }

    // MARK: - Internals

    private func persist() {
        let json = bridge.algorithmSnapshotEncode(wire: algoSnapshotWire())
        defaults.set(json, forKey: Self.storageKey)
        sink?(json)
    }

    private func algoSnapshotWire() -> BusinessCoreBridge.AlgoSnapshotWire {
        BusinessCoreBridge.AlgoSnapshotWire(
            freshnessHours: Int64(freshnessHours),
            surfaces: surfaces.mapValues { algoStateToSurface($0) }
        )
    }
}

// MARK: - Wire ⇄ state (nonisolated helpers)

private func algoStateToSurface(_ state: AlgorithmStore.SurfaceState) -> BusinessCoreBridge.AlgoSurfaceWire {
    BusinessCoreBridge.AlgoSurfaceWire(
        enabled: state.enabled,
        diversityEnabled: state.diversityEnabled,
        signals: state.signals.mapValues {
            BusinessCoreBridge.AlgoSignalWire(enabled: $0.enabled, weight: $0.weight)
        }
    )
}

private func algoSurfaceToState(_ wire: BusinessCoreBridge.AlgoSurfaceWire) -> AlgorithmStore.SurfaceState {
    AlgorithmStore.SurfaceState(
        enabled: wire.enabled,
        signals: wire.signals.mapValues {
            AlgorithmStore.SignalState(enabled: $0.enabled, weight: $0.weight)
        }
    )
}
