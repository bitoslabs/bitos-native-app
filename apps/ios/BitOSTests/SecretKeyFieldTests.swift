import BusinessCore
import XCTest
@testable import BitOS

/**
 * ID-004 secret-key login field acceptance (iOS adapter seam): the
 * `secretKeyReady` gate flows through `BusinessCoreBridge.keyImportCheck`,
 * so these checks pin the shared rule + verdict wire the SwiftUI field
 * renders — verbatim copy parity with the Compose field and the shared
 * `KeyImportFormTest` vectors.
 */
final class SecretKeyFieldTests: XCTestCase {

    private let nsec = "nsec162knc0y70v8576su95ly75rpw2peffdkclvwnu9pktpafe0kquvqh3ydrs"
    private let npub = "npub194667yy2sqh4h4vlwssg7g5smhmqx4x9hgtfdjun8e46l30kxqqselzc9y"
    private let hexSecret = "d2ad3c3c9e7b0f4f6a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718"

    func testSubmitGateAcceptsValidKeysOnly() {
        XCTAssertTrue(secretKeyReady(nsec))
        XCTAssertTrue(secretKeyReady(nsec.uppercased()))
        XCTAssertTrue(secretKeyReady(hexSecret))
        XCTAssertTrue(secretKeyReady("  \(nsec)\n"))
        XCTAssertFalse(secretKeyReady(""))
        XCTAssertFalse(secretKeyReady("   "))
        XCTAssertFalse(secretKeyReady(npub))
        XCTAssertFalse(secretKeyReady(String(nsec.dropLast(4))))
        XCTAssertFalse(secretKeyReady(nsec + "q"))
    }

    func testBridgeVerdictCarriesCopyAndSecret() {
        let bridge = BusinessCoreBridge()
        let ready = bridge.keyImportCheck(raw: nsec)
        XCTAssertEqual(ready.verdict, "READY")
        XCTAssertEqual(ready.secretHex, hexSecret)
        XCTAssertEqual(ready.message, "Valid nsec key.")
        let wrongType = bridge.keyImportCheck(raw: npub)
        XCTAssertEqual(wrongType.verdict, "WRONG_KEY_TYPE")
        XCTAssertEqual(wrongType.message, "That is a public key (npub); import needs the secret (nsec).")
        XCTAssertNil(wrongType.secretHex)
        XCTAssertEqual(bridge.keyImportCheck(raw: "  ").verdict, "EMPTY")
    }
}
