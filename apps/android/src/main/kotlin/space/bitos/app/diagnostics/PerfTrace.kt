package space.bitos.app.diagnostics

import android.os.Trace

/**
 * Performance trace-section contract (performance-audit-and-plan.md Phase 0).
 *
 * Mirrors the iOS `Perf` signpost names exactly so one Perfetto capture maps
 * both platforms onto the same budget rows (docs/engineering/
 * performance-baselines.md):
 * - `relay.decode` — one relay frame through decode + ID + BIP-340 verify.
 * - `feed.publish` — one coalesced UI projection (window snapshot → filter
 *   → rank → thread assembly).
 * - `poster.decode` — one poster decode (prefetcher misses).
 *
 * `android.os.Trace` sections surface in Perfetto/systrace automatically
 * (atrace). JVM unit tests run against framework stubs where Trace throws —
 * the guards make tracing a no-op there instead of failing every test.
 *
 * Sections are for NON-SUSPENDING blocks only: suspension would resume on a
 * different dispatcher and misattribute the section. The instrumented paths
 * (decode, verify, projection) are pure CPU by design.
 */
object PerfTrace {

    const val RELAY_DECODE = "relay.decode"
    const val FEED_PUBLISH = "feed.publish"
    const val POSTER_DECODE = "poster.decode"

    inline fun <T> section(name: String, block: () -> T): T {
        begin(name)
        try {
            return block()
        } finally {
            end()
        }
    }

    @PublishedApi
    internal fun begin(name: String) {
        try {
            Trace.beginSection(name.take(127))
        } catch (_: Throwable) {
            // JVM unit-test stub: tracing is a no-op there.
        }
    }

    @PublishedApi
    internal fun end() {
        try {
            Trace.endSection()
        } catch (_: Throwable) {
            // JVM unit-test stub.
        }
    }
}
