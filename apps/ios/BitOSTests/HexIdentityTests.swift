import SwiftUI
import XCTest
@testable import BitOS

/**
 * APP-022 hex identity primitives (unified feature spec §2.5/§4): the
 * flat-top hexagon clip contract shared with the web `.hex-clip` and the
 * Android `HexShape`, plus deterministic identity-gradient derivation.
 */
final class HexIdentityTests: XCTestCase {

    // MARK: - Geometry

    func testVerticesMatchCssHexClipPolygon() {
        let rect = CGRect(x: 0, y: 0, width: 100, height: 100)
        let v = HexIdentity.vertices(in: rect)
        XCTAssertEqual(v.count, 6)
        XCTAssertEqual(v[0].x, 25, accuracy: 0.001)
        XCTAssertEqual(v[0].y, 6.7, accuracy: 0.001)
        XCTAssertEqual(v[1].x, 75, accuracy: 0.001)
        XCTAssertEqual(v[1].y, 6.7, accuracy: 0.001)
        XCTAssertEqual(v[2].x, 100, accuracy: 0.001)
        XCTAssertEqual(v[2].y, 50, accuracy: 0.001)
        XCTAssertEqual(v[3].x, 75, accuracy: 0.001)
        XCTAssertEqual(v[3].y, 93.3, accuracy: 0.001)
        XCTAssertEqual(v[4].x, 25, accuracy: 0.001)
        XCTAssertEqual(v[4].y, 93.3, accuracy: 0.001)
        XCTAssertEqual(v[5].x, 0, accuracy: 0.001)
        XCTAssertEqual(v[5].y, 50, accuracy: 0.001)
    }

    func testShapeInscribesRegularHexagonInRect() {
        let rect = CGRect(x: 10, y: 20, width: 80, height: 60)
        let path = HexShape().path(in: rect)
        // Touches left/right edges; 6.7% vertical inset (web `.hex-clip`).
        XCTAssertEqual(path.boundingRect.minX, rect.minX, accuracy: 0.001)
        XCTAssertEqual(path.boundingRect.width, rect.width, accuracy: 0.001)
        XCTAssertEqual(path.boundingRect.minY, rect.minY + rect.height * 0.067, accuracy: 0.001)
        XCTAssertEqual(path.boundingRect.height, rect.height * 0.866, accuracy: 0.001)
        XCTAssertEqual(HexIdentity.vertices(in: rect).first?.x ?? 0, 30, accuracy: 0.001)
    }

    // MARK: - Identity gradient

    func testAvatarGradientDeterministicAndDistinct() {
        let key = "a1b2c3d4" + String(repeating: "0", count: 56)
        let a = HexIdentity.avatarGradient(for: key)
        let aAgain = HexIdentity.avatarGradient(for: key)
        XCTAssertEqual(a.start, aAgain.start)
        XCTAssertEqual(a.end, aAgain.end)
        XCTAssertNotEqual(a.start, a.end)

        let other = HexIdentity.avatarGradient(for: "00000010" + String(repeating: "0", count: 56))
        XCTAssertNotEqual(a.start, other.start)
    }

    func testHighSeedPrefixDoesNotCollapseToZeroHue() {
        // "ffffffff" exceeds Int ranges; UInt32 parsing must keep a stable hue.
        let high = HexIdentity.avatarGradient(for: "ffffffff" + String(repeating: "0", count: 56))
        let zero = HexIdentity.avatarGradient(for: "00000000" + String(repeating: "0", count: 56))
        XCTAssertNotEqual(high.start, zero.start)
    }

    func testNonHexPrefixFallsBackToZeroSeed() {
        XCTAssertEqual(
            HexIdentity.avatarGradient(for: "zz-not-hex").start,
            HexIdentity.avatarGradient(for: "0000zz").start
        )
    }
}
