import Foundation
import os

/**
 * Performance signpost contract (performance-audit-and-plan.md Phase 0).
 *
 * One subsystem/category for every hot-path interval so a single Instruments
 * template (os_signpost instrument, subsystem `space.bitos.app`) captures
 * the feed pipeline end to end. Signposts are compiled in for release
 * builds — recording is opt-in via Instruments/Console and the cost when
 * not recorded is a few nanoseconds per interval.
 *
 * Safety: interval names are static strings; the only interpolated values
 * are numeric counts/durations. No event content, keys or user data ever
 * reaches a signpost payload (repo logging rules, AGENTS.md).
 *
 * Interval contract (matches Android `PerfTrace` section names):
 * - `relay.decode` — one relay frame through decode + ID + BIP-340 verify
 *   (the ingest stage, off the main actor on iOS).
 * - `feed.publish` — one coalesced UI projection (window snapshot → filter
 *   → rank → thread assembly). Carries the projected row count.
 * - `poster.decode` — one poster download+decode at rendered size (cache
 *   misses only).
 */
enum Perf {
    static let subsystem = "space.bitos.app"
    static let signposter = OSSignposter(subsystem: subsystem, category: "perf")

    /// Interval names (shared with Android `PerfTrace` — never interpolate
    /// content into a name or payload).
    enum Interval {
        static let relayDecode: StaticString = "relay.decode"
        static let feedPublish: StaticString = "feed.publish"
        static let posterDecode: StaticString = "poster.decode"
    }

    /// Measures one synchronous interval; safe from any actor/thread.
    static func measure<T>(_ name: StaticString, _ block: () throws -> T) rethrows -> T {
        let state = signposter.beginInterval(name)
        defer { signposter.endInterval(name, state) }
        return try block()
    }

    /// Measures one interval with a numeric payload (row/frame count).
    static func measure<T>(_ name: StaticString, count: Int, _ block: () throws -> T) rethrows -> T {
        let state = signposter.beginInterval(name, "\(count, privacy: .public)")
        defer { signposter.endInterval(name, state) }
        return try block()
    }
}
