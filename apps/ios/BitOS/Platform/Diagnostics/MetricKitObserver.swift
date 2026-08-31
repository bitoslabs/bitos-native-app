import Foundation
import MetricKit
import os

/**
 * Field diagnostics (performance-audit Phase 0 follow-up): MetricKit
 * delivers real-device hang, crash, CPU-exception and disk-write diagnostics
 * plus daily metric payloads (launch, responsiveness) from internal and
 * TestFlight builds — the production counterpart to the `Perf` signposts.
 *
 * Payloads arrive at most daily, off the main thread, and are summarized as
 * REDACTED counts only. No content, keys, URLs or user data ever reach a log
 * line (AGENTS.md logging rules). Hitch/jank field data closes the loop the
 * simulator cannot: what real users experienced.
 */
final class MetricKitObserver: NSObject, MXMetricManagerSubscriber, @unchecked Sendable {

    private static let logger = Logger(subsystem: "space.bitos.app", category: "metrics")
    private static let shared = MetricKitObserver()

    /// Registers the process subscriber (idempotent; call once at launch).
    static func start() {
        MXMetricManager.shared.add(shared)
    }

    private override init() {
        super.init()
    }

    // MARK: - Daily metric payloads (launch, responsiveness histograms)

    func didReceive(_ payloads: [MXMetricPayload]) {
        for payload in payloads {
            var notes: [String] = []
            if let launch = payload.applicationLaunchMetrics {
                notes.append("launch-samples=\(launch.histogrammedTimeToFirstDraw.totalBucketCount)")
            }
            if let responsiveness = payload.applicationResponsivenessMetrics {
                notes.append("hang-samples=\(responsiveness.histogrammedApplicationHangTime.totalBucketCount)")
            }
            if !notes.isEmpty {
                Self.logger.info("metrickit: \(notes.joined(separator: " "), privacy: .public)")
            }
        }
    }

    // MARK: - Diagnostic payloads (hangs, crashes, exceptions)

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            var notes: [String] = []
            if let hangs = payload.hangDiagnostics { notes.append("hangs=\(hangs.count)") }
            if let crashes = payload.crashDiagnostics { notes.append("crashes=\(crashes.count)") }
            if let cpu = payload.cpuExceptionDiagnostics { notes.append("cpu=\(cpu.count)") }
            if let disk = payload.diskWriteExceptionDiagnostics { notes.append("disk=\(disk.count)") }
            if !notes.isEmpty {
                Self.logger.notice("metrickit-diagnostics: \(notes.joined(separator: " "), privacy: .public)")
            }
        }
    }
}
