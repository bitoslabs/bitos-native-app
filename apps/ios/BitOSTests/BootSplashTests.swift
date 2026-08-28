import SwiftUI
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
