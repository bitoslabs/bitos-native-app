import XCTest
@testable import BitOS

final class BusinessCoreClientTests: XCTestCase {
    func testScaffoldAdapterIsReachable() async {
        let subject = ScaffoldBusinessCoreClient()
        let label = await subject.publishStatusLabel()
        XCTAssertFalse(label.isEmpty)
    }
}
