import BusinessCore
import XCTest
@testable import BitOS

/**
 * APP-018 adapter contract (AGENTS: shared business changes require native
 * adapter-contract tests): `SettingsStore` must persist canonical wire
 * values through the shared bridge rules and republish a typed snapshot.
 */
@MainActor
final class SettingsStoreTests: XCTestCase {

    private var suiteName: String!
    private var defaults: UserDefaults!
    private var store: SettingsStore!

    override func setUp() async throws {
        suiteName = "bitos.settings.tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        defaults.removePersistentDomain(forName: suiteName)
        store = SettingsStore(defaults: defaults)
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suiteName)
    }

    func testDecodesDefaultsFromEmptyStore() {
        XCTAssertEqual(store.state.themeMode, .dark)
        XCTAssertTrue(store.state.notificationsEnabled)
        XCTAssertEqual(store.state.defaultZapAmount, 21)
        XCTAssertEqual(store.state.mediaAutoPlay, .wifi)
    }

    func testWritesCanonicalizeThroughSharedRules() {
        // Legacy boolean spelling canonicalizes to "1".
        XCTAssertTrue(store.setRaw("true", forKey: "bitos_notifications"))
        XCTAssertEqual(defaults.string(forKey: "bitos_notifications"), "1")
        // Rejected values persist nothing.
        XCTAssertFalse(store.setRaw("0", forKey: "bitos_default_zap_amount"))
        XCTAssertNil(defaults.string(forKey: "bitos_default_zap_amount"))
        XCTAssertFalse(store.setRaw("1", forKey: "bitos_unknown"))
        // Typed write reflects in the published state.
        store.set(SettingsFeedTimeline.trending)
        XCTAssertEqual(store.state.feedTimeline, .trending)
        XCTAssertEqual(defaults.string(forKey: "bitos_feed_timeline"), "trending")
    }

    func testCorruptPersistedValuesFallBackToDefaults() {
        defaults.set("neon", forKey: "bitos_theme_mode")
        defaults.set("abc", forKey: "bitos_default_zap_amount")
        store.reload()
        XCTAssertEqual(store.state.themeMode, .dark)
        XCTAssertEqual(store.state.defaultZapAmount, 21)
    }

    func testClearCacheKeepsProtectedDeviceGlobals() {
        store.set(.system)
        store.set(SettingsFeedTimeline.trending)
        store.setDefaultZapAmount(100)
        store.clearCache()
        XCTAssertEqual(defaults.string(forKey: "bitos_theme_mode"), "system") // protected
        XCTAssertNil(defaults.string(forKey: "bitos_feed_timeline"))          // reset
        XCTAssertEqual(store.state.feedTimeline, .latest)
        XCTAssertEqual(store.state.defaultZapAmount, 21)
    }

    func testShortNpubUsesSharedRule() {
        let npub = "npub1" + String(repeating: "a", count: 55)
        XCTAssertEqual(store.shortNpub(npub), "npub1aaaaa…aaaaaa")
    }

    func testSectionCatalogMatchesSharedOrdering() {
        XCTAssertEqual(
            store.sections.map(\.key),
            ["account", "lightning", "privacy", "notifications", "appearance",
             "algorithm", "security", "media", "language", "relays", "help", "about"]
        )
    }
}
