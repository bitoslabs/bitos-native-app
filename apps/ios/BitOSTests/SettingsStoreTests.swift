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

    // MARK: - Bitz surface keys (APP-007, schema v4)

    func testBitzModeAndVideoMutePersistAndClearWithCache() {
        // Defaults: player surface, politely muted autoplay.
        XCTAssertEqual(store.state.bitzMode, .forYou)
        XCTAssertTrue(store.state.videoMuted)
        // Typed writes persist canonical wire and republish state.
        store.set(SettingsBitzMode.explore)
        store.setVideoMuted(false)
        XCTAssertEqual(defaults.string(forKey: "bitos_bitz_mode"), "explore")
        XCTAssertEqual(defaults.string(forKey: "bitos_video_muted"), "0")
        XCTAssertEqual(store.state.bitzMode, .explore)
        XCTAssertFalse(store.state.videoMuted)
        // Corrupt values self-heal through the shared rules.
        defaults.set("reels", forKey: "bitos_bitz_mode")
        defaults.set("loud", forKey: "bitos_video_muted")
        store.reload()
        XCTAssertEqual(store.state.bitzMode, .forYou)
        XCTAssertTrue(store.state.videoMuted)
        // Content prefs: clear with cache (not device globals).
        store.clearCache()
        XCTAssertNil(defaults.string(forKey: "bitos_bitz_mode"))
        XCTAssertNil(defaults.string(forKey: "bitos_video_muted"))
        XCTAssertEqual(store.state.bitzMode, .forYou)
        XCTAssertTrue(store.state.videoMuted)
    }

    // MARK: - Bitz shared-rule seam (search merge, share copy, duration)

    func testBitzBridgeRulesMergeDedupeAndFormat() throws {
        let rules = BitzBridgeRules()
        let local = FeedNote(
            id: "a", pubkey: "aa", content: "orange pill bitcoin", createdAt: 1, kind: 22,
            replyTo: nil, hashtags: [], mentions: [], mediaUrls: [], isProtocolPayload: false
        )
        let relayDupe = FeedNote(
            id: "a", pubkey: "aa", content: "orange pill bitcoin", createdAt: 1, kind: 22,
            replyTo: nil, hashtags: [], mentions: [], mediaUrls: [], isProtocolPayload: false
        )
        let relayNew = FeedNote(
            id: "b", pubkey: "bb", content: "bitcoin beach", createdAt: 2, kind: 22,
            replyTo: nil, hashtags: [], mentions: [], mediaUrls: [], isProtocolPayload: false
        )
        XCTAssertEqual(rules.mergedResultIds(query: "bitcoin", local: [local], relay: [relayDupe, relayNew], profiles: [:]), ["a", "b"])
        // Blank query = idle: stray relay echoes never repopulate results.
        XCTAssertEqual(rules.mergedResultIds(query: "  ", local: [local], relay: [relayNew], profiles: [:]), [])
        // Share copy carries the excerpt + attribution.
        XCTAssertTrue(rules.shareText(content: "hello world", authorNpub: "npub1abc").hasSuffix("— npub1abc · BitOS"))
        // Duration labels are locale-free.
        XCTAssertEqual(rules.formatDuration(59), "0:59")
        XCTAssertEqual(rules.formatDuration(3_723), "1:02:03")
    }
}
