import BusinessCore
import XCTest
@testable import BitOS

/**
 * APP-018 relays-manager adapter contract (AGENTS: shared business changes
 * require native adapter tests): `RelayManagerStore` must execute the shared
 * relay-list contract — validation, non-empty invariant, role guards — and
 * persist the versioned wire through the bridge rules.
 */
@MainActor
final class RelayManagerStoreTests: XCTestCase {

    private var suiteName: String!
    private var defaults: UserDefaults!
    private var store: RelayManagerStore!

    override func setUp() async throws {
        suiteName = "bitos.relaymanager.tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        defaults.removePersistentDomain(forName: suiteName)
        // Empty pool: adds install connections but never connect (not running),
        // so the suite stays offline.
        store = RelayManagerStore(pool: RelayPool(urls: []), defaults: defaults)
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suiteName)
    }

    private func persistedWire() -> String? {
        defaults.string(forKey: RelayManagerStore.storageKey)
    }

    func testEmptyStoreBootsPlatformDefaults() {
        XCTAssertEqual(
            store.relays.map(\.url),
            DefaultRelays.urls.map(\.rawValue)
        )
        // Default roles mirror the write-capable subset.
        XCTAssertEqual(
            store.relays.filter(\.write).map(\.url),
            DefaultRelays.writeUrls.map(\.rawValue)
        )
        XCTAssertEqual("wss://nostr-01.yakihonne.com", store.relays.first?.url)
        XCTAssertTrue(store.relays.first?.primary ?? false)
    }

    func testAddValidatesPersistsAndRoundTrips() {
        XCTAssertTrue(store.add(rawUrl: "wss://added.relay"))
        XCTAssertEqual("wss://added.relay", store.relays.last?.url)
        XCTAssertTrue(store.relays.last?.write ?? false)
        // The persisted wire decodes back through the shared bridge rules.
        let wire = try XCTUnwrap(persistedWire())
        XCTAssertEqual(
            store.relays.map(\.url),
            BusinessCoreBridge().relayListDecode(json: wire).map(\.url)
        )
        // Invalid / duplicate adds change nothing.
        XCTAssertFalse(store.add(rawUrl: "http://nope.relay"))
        XCTAssertFalse(store.add(rawUrl: "wss://added.relay"))
    }

    func testRemoveNeverLeavesTheSetEmpty() {
        XCTAssertTrue(store.add(rawUrl: "wss://extra.relay"))
        store.remove(url: "wss://extra.relay")
        XCTAssertFalse(store.relays.contains { $0.url == "wss://extra.relay" })
        // The set never becomes empty: defaults survive repeated removals.
        for _ in 0..<10 {
            let first = try! XCTUnwrap(store.relays.first).url
            store.remove(url: first)
        }
        XCTAssertFalse(store.relays.isEmpty)
    }

    func testSetRolesGuardsBothFalseAndPersists() {
        let target = store.relays.first!
        store.setRoles(url: target.url, read: false, write: false) // no-op
        XCTAssertEqual(target, store.relays.first!)
        store.setRoles(url: target.url, read: true, write: false)
        let updated = store.relays.first!
        XCTAssertTrue(updated.read)
        XCTAssertFalse(updated.write)
        XCTAssertEqual(
            store.relays.map(\.url),
            BusinessCoreBridge().relayListDecode(json: try XCTUnwrap(persistedWire())).map(\.url)
        )
    }

    func testCorruptWireFallsBackToDefaults() {
        defaults.set("{\"v\":1,\"relays\":[{", forKey: RelayManagerStore.storageKey)
        let fresh = RelayManagerStore(pool: RelayPool(urls: []), defaults: defaults)
        XCTAssertEqual(DefaultRelays.urls.map(\.rawValue), fresh.relays.map(\.url))
    }

    func testEncodeFeedsTheNip65PublishPath() {
        store.setRoles(url: store.relays[2].url, read: true, write: false) // nostr.band stays read-only
        let wire = store.encode()
        let decoded = BusinessCoreBridge().relayListDecode(json: wire)
        XCTAssertEqual(store.relays.count, decoded.count)
        // Write-role projection matches the fan-out targets.
        XCTAssertEqual(
            store.relays.filter(\.write).map(\.url),
            decoded.filter(\.write).map(\.url)
        )
        // A composed kind-10002 id exists for the account path.
        XCTAssertNotNil(
            BusinessCoreBridge().composeRelayListEventId(
                authorPubkey: String(repeating: "a", count: 64),
                relayListJson: wire,
                nowSeconds: 1_700_000_000
            )
        )
    }
}
