import XCTest
@testable import BitOS

final class BusinessCoreClientTests: XCTestCase {
    func testFrameworkClientIsReachable() {
        let client = FrameworkBusinessCoreClient()
        XCTAssertTrue(client.isFeedKind(1))
        XCTAssertTrue(client.isFeedKind(22))
        XCTAssertFalse(client.isFeedKind(0))
        XCTAssertTrue(client.isProfileKind(0))
    }
}
