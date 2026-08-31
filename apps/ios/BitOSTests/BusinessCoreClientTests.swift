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

    /// Cold-start account bootstrap (shared `AccountBootstrap` through the
    /// client seam): unresolved heads re-issue while connected and within
    /// budget; grown connectivity opens a new episode.
    func testAccountBootstrapSeam() {
        let client = FixtureBusinessCoreClient()
        XCTAssertTrue(client.accountBootstrapShouldReissue(resolved: false, attempts: 0, connectedRelays: 1))
        XCTAssertFalse(client.accountBootstrapShouldReissue(resolved: true, attempts: 0, connectedRelays: 3))
        XCTAssertFalse(client.accountBootstrapShouldReissue(resolved: false, attempts: 0, connectedRelays: 0))
        XCTAssertFalse(client.accountBootstrapShouldReissue(resolved: false, attempts: 5, connectedRelays: 2))
        XCTAssertTrue(client.accountBootstrapShouldOpenEpisode(previousConnected: 0, currentConnected: 1))
        XCTAssertFalse(client.accountBootstrapShouldOpenEpisode(previousConnected: 2, currentConnected: 1))
    }
}
