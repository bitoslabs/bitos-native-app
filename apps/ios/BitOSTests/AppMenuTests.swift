import SwiftUI
import XCTest
@testable import BitOS

/**
 * APP-022 AppMenu layout contract (unified feature spec §4): the
 * screen-clamped `showAt` geometry ported from the legacy clients —
 * preferred below-right of the anchor, shift-left, flip-above, margin clamp.
 */
final class AppMenuTests: XCTestCase {

    private let screen = CGSize(width: 390, height: 844)

    // MARK: - Estimated size

    func testEstimatedSizeCountsRowsAndDividers() {
        let entries: [AppMenuEntry] = [
            .item(AppMenuItem(id: "a", label: "A")),
            .divider,
            .item(AppMenuItem(id: "b", label: "B")),
            .item(AppMenuItem(id: "c", label: "C")),
        ]
        let size = AppMenuLayout.estimatedSize(entries: entries)
        XCTAssertEqual(size.width, AppMenuLayout.width)
        // cardPadding 12 + 3 rows (44) + 1 divider (9)
        XCTAssertEqual(size.height, 12 + 3 * 44 + 9, accuracy: 0.001)
    }

    // MARK: - Clamped frame

    func testOpensBelowRightWhenItFits() {
        let frame = AppMenuLayout.frame(
            anchor: CGPoint(x: 100, y: 200),
            menuSize: CGSize(width: 240, height: 190),
            screen: screen
        )
        XCTAssertEqual(frame.minX, 104, accuracy: 0.001)
        XCTAssertEqual(frame.minY, 204, accuracy: 0.001)
    }

    func testShiftsLeftAtRightScreenEdge() {
        let frame = AppMenuLayout.frame(
            anchor: CGPoint(x: 380, y: 200),
            menuSize: CGSize(width: 240, height: 190),
            screen: screen
        )
        XCTAssertEqual(frame.minX, 390 - 8 - 240, accuracy: 0.001)
        XCTAssertEqual(frame.minY, 204, accuracy: 0.001)
    }

    func testFlipsAboveWhenBottomWouldClip() {
        let frame = AppMenuLayout.frame(
            anchor: CGPoint(x: 100, y: 700),
            menuSize: CGSize(width: 240, height: 190),
            screen: screen
        )
        XCTAssertEqual(frame.minX, 104, accuracy: 0.001)
        XCTAssertEqual(frame.minY, 700 - 4 - 190, accuracy: 0.001)
    }

    func testFlipClampsToTopMargin() {
        let frame = AppMenuLayout.frame(
            anchor: CGPoint(x: 100, y: 60),
            menuSize: CGSize(width: 240, height: 800),
            screen: screen
        )
        XCTAssertEqual(frame.minY, AppMenuLayout.margin, accuracy: 0.001)
    }

    func testClampsToLeadingMarginWhenMenuIsNearlyScreenWidth() {
        let frame = AppMenuLayout.frame(
            anchor: CGPoint(x: 2, y: 200),
            menuSize: CGSize(width: 370, height: 100),
            screen: screen
        )
        XCTAssertEqual(frame.minX, AppMenuLayout.margin, accuracy: 0.001)
    }
}
