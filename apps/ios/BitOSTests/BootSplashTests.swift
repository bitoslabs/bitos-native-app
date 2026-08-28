import SwiftUI
import UIKit
import XCTest
@testable import BitOS

/**
 * Boot splash timing contract (legacy Flutter `BootSplashScreen` / web
 * `pow-boot-seg` parity) + the bezier bolt geometry.
 */
final class BootSplashTests: XCTestCase {

    // MARK: - PoW segment sweep

    func testSegmentsSweepWithStaggeredDelay() {
        // Segment 0 lights immediately (no delay, on-fraction 0.45).
        XCTAssertTrue(BootSplashTiming.powSegmentOn(t: 0, index: 0))
        // Segment 1 (delay 0.12) is off just before its delay elapses…
        XCTAssertFalse(BootSplashTiming.powSegmentOn(t: 0.11, index: 1))
        // …and lights right after.
        XCTAssertTrue(BootSplashTiming.powSegmentOn(t: 0.13, index: 1))
        // Segment 0 turns off after 45% of the loop.
        XCTAssertTrue(BootSplashTiming.powSegmentOn(t: 0.44, index: 0))
        XCTAssertFalse(BootSplashTiming.powSegmentOn(t: 0.46, index: 0))
    }

    func testSweepWrapsAroundTheLoop() {
        // Near the loop end, later segments light first (wrap-around):
        // t=0.99 → segment 8 (delay 0.96) has local 0.03 (on),
        // segment 0 has local 0.99 (off).
        XCTAssertTrue(BootSplashTiming.powSegmentOn(t: 0.99, index: 8))
        XCTAssertFalse(BootSplashTiming.powSegmentOn(t: 0.99, index: 0))
    }

    func testConstantsMatchWeb() {
        XCTAssertEqual(BootSplashTiming.segmentCount, 9)
        XCTAssertEqual(BootSplashTiming.segmentSweepDelay, 0.12, accuracy: 0.0001)
        XCTAssertEqual(BootSplashTiming.segmentOnFraction, 0.45, accuracy: 0.0001)
        XCTAssertEqual(BootSplashTiming.loopDuration, 2.0, accuracy: 0.0001)
        XCTAssertEqual(BootSplashTiming.minDisplay, 0.9, accuracy: 0.0001)
        XCTAssertEqual(BootSplashTiming.fadeOut, 0.3, accuracy: 0.0001)
    }

    // MARK: - Breathing

    func testBreathingOscillatesWithinTwoPercent() {
        XCTAssertEqual(BootSplashTiming.breathe(t: 0), 1.0, accuracy: 1e-4)
        XCTAssertEqual(BootSplashTiming.breathe(t: 0.25), 1.02, accuracy: 1e-4) // sin(π/2)
        XCTAssertEqual(BootSplashTiming.breathe(t: 0.75), 0.98, accuracy: 1e-4) // sin(3π/2)
    }

    // MARK: - Animated gradient border (no spin)

    func testBorderWavePingPongsSmoothly() {
        // Cosine wave: 0 → ½ (quarter) → 1 (half) → ½ (¾) → seamless wrap.
        XCTAssertEqual(BootSplashTiming.borderWave(0), 0, accuracy: 1e-4)
        XCTAssertEqual(BootSplashTiming.borderWave(0.25), 0.5, accuracy: 1e-4)
        XCTAssertEqual(BootSplashTiming.borderWave(0.5), 1, accuracy: 1e-4)
        XCTAssertEqual(BootSplashTiming.borderWave(0.75), 0.5, accuracy: 1e-4)
        // Continuous across the loop wrap — no rotation-style hard cut.
        XCTAssertEqual(
            BootSplashTiming.borderWave(0.999),
            BootSplashTiming.borderWave(0.001),
            accuracy: 0.02
        )
    }

    func testBorderStopsRipplePhaseShiftedNotRotated() {
        // Stop 0 peaks (full yellow) at t=0.5; neighbours peak a third of a
        // loop apart — stops stay anchored, only colors evolve (no spin).
        XCTAssertGreaterThan(BootSplashTiming.borderStopWave(0.5, stop: 0), 0.99)
        XCTAssertLessThan(BootSplashTiming.borderStopWave(0.5, stop: 1), 0.26)
        XCTAssertLessThan(BootSplashTiming.borderStopWave(0.5, stop: 2), 0.26)
        // The wrap stop (k=3) repeats stop 0's color → seamless gradient join.
        XCTAssertEqual(
            BootSplashTiming.borderStopWave(0.5, stop: 3),
            BootSplashTiming.borderStopWave(0.5, stop: 0),
            accuracy: 1e-6
        )
        // Waves never leave the 0…1 lerp domain at any loop time.
        for i in 0...19 {
            let t = Double(i) / 19
            for k in 0..<3 {
                let w = BootSplashTiming.borderStopWave(t, stop: k)
                XCTAssertTrue((0...1).contains(w))
            }
        }
        XCTAssertEqual(BootSplashTiming.borderStopCount, 3)
    }

    /// SwiftUI `Color` → sRGB components via UIKit (Color has no component API).
    private func rgba(_ color: Color) -> (r: Double, g: Double, b: Double, a: Double) {
        var (r, g, b, a) = (0.0, 0.0, 0.0, 0.0)
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        return (r, g, b, a)
    }

    func testBorderColorRipplesBetweenBrandColors() {
        // t=0 → stop color is pure Bitcoin orange; half a loop later → yellow.
        let orange = rgba(BootSplashTiming.orange)
        let yellow = rgba(BootSplashTiming.yellow)
        let c0 = rgba(BootSplashTiming.borderColor(t: 0, stop: 0))
        XCTAssertEqual(c0.r, orange.r, accuracy: 1e-3)
        XCTAssertEqual(c0.g, orange.g, accuracy: 1e-3)
        XCTAssertEqual(c0.b, orange.b, accuracy: 1e-3)
        let c1 = rgba(BootSplashTiming.borderColor(t: 0.5, stop: 0))
        XCTAssertEqual(c1.r, yellow.r, accuracy: 1e-3)
        XCTAssertEqual(c1.g, yellow.g, accuracy: 1e-3)
        XCTAssertEqual(c1.b, yellow.b, accuracy: 1e-3)
        // Quarter loop = even mix (continuous flow, not a jump).
        let mid = rgba(BootSplashTiming.borderColor(t: 0.25, stop: 0))
        XCTAssertEqual(mid.r, (orange.r + yellow.r) / 2, accuracy: 1e-3)
    }

    func testMixClampsFraction() {
        let orange = rgba(BootSplashTiming.orange)
        let yellow = rgba(BootSplashTiming.yellow)
        let below = rgba(BootSplashTiming.mix(BootSplashTiming.orange, BootSplashTiming.yellow, fraction: -1))
        let above = rgba(BootSplashTiming.mix(BootSplashTiming.orange, BootSplashTiming.yellow, fraction: 2))
        XCTAssertEqual(below.r, orange.r, accuracy: 1e-3)
        XCTAssertEqual(above.b, yellow.b, accuracy: 1e-3)
    }

    // MARK: - Bolt geometry

    func testBoltPathPreservesViewportAspectAndFillsContainment() {
        // Square frame: contain → width-limited (664×297 ≈ 2.236:1).
        let square = CGRect(x: 0, y: 0, width: 100, height: 100)
        let path = BoltMark.path(in: square)
        let bounds = path.boundingRect
        XCTAssertEqual(bounds.width, 100, accuracy: 0.001)
        XCTAssertEqual(bounds.height, 100 / (664.0 / 297.0), accuracy: 0.001)
        XCTAssertEqual(bounds.midY, 50, accuracy: 0.001) // vertically centered

        // Wide frame: height-limited.
        let wide = CGRect(x: 10, y: 20, width: 400, height: 100)
        let wideBounds = BoltMark.path(in: wide).boundingRect
        XCTAssertEqual(wideBounds.height, 100, accuracy: 0.001)
        XCTAssertEqual(wideBounds.width, 100 * (664.0 / 297.0), accuracy: 0.001)
        XCTAssertEqual(wideBounds.minX, 10 + (400 - wideBounds.width) / 2, accuracy: 0.001)
    }
}
