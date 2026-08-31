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

    // MARK: - Appearance hooks (APP-023: theme + accent apply app-wide)

    func testThemeChangesSyncDesignSystemOverride() {
        store.set(.light)
        XCTAssertEqual(BitOSTheme.modeOverride, .light)
        XCTAssertEqual(BitOSTheme.preferredScheme, .light)
        store.set(.system)
        XCTAssertEqual(BitOSTheme.modeOverride, nil)
        XCTAssertEqual(BitOSTheme.preferredScheme, nil)
        store.set(.dark)
        XCTAssertEqual(BitOSTheme.modeOverride, .dark)
        // Corrupt wire falls back to dark (product default), never crashes.
        defaults.set("neon", forKey: "bitos_theme_mode")
        store.reload()
        XCTAssertEqual(BitOSTheme.modeOverride, .dark)
    }

    func testAccentChoiceSyncsOverrideAndFallsBackToBrand() {
        // Default: no override — the brand bitcoin orange renders.
        XCTAssertEqual(store.state.accentColorHex, "#F7931A")
        XCTAssertEqual(BitOSTheme.accentOverride, 0xF7931A)
        // Palette pick round-trips through shared validation (canonicalized
        // to uppercase) and re-syncs the override.
        XCTAssertTrue(store.setAccentColor(hex: "#6366f1"))
        XCTAssertEqual(store.state.accentColorHex, "#6366F1")
        XCTAssertEqual(BitOSTheme.accentOverride, 0x6366F1)
        // Rejected values persist nothing and keep the last good accent.
        XCTAssertFalse(store.setAccentColor(hex: "orange"))
        XCTAssertEqual(BitOSTheme.accentOverride, 0x6366F1)
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

    // MARK: - Bitz mode wires + rendition pick (FED-004)

    func testBitzModeEnumCarriesLegacyThreeTabs() {
        // iOS enum rawValues must equal the shared BitzModeSetting wires,
        // and the removed W2 wires parse back to the default (3-tab parity).
        XCTAssertEqual(SettingsBitzMode.allCases.count, 3)
        XCTAssertEqual(SettingsBitzMode.forYou.rawValue, "for_you")
        XCTAssertEqual(SettingsBitzMode(rawValue: "trending"), .forYou)
        XCTAssertEqual(SettingsBitzMode(rawValue: "zapped"), .forYou)
        XCTAssertEqual(SettingsBitzMode(rawValue: "bogus"), .forYou)
        // Round-trip through the settings store.
        store.set(SettingsBitzMode.following)
        XCTAssertEqual(defaults.string(forKey: "bitos_bitz_mode"), "following")
        store.reload()
        XCTAssertEqual(store.state.bitzMode, .following)
    }

    func testMediaRenditionPickPrefersTallestFittingFromBridgeSpecs() {
        let bridge = BusinessCoreBridge()
        let specs = [
            "https://x/2160.mp4|2160|8_000_000",
            "https://x/1080.mp4|1080|4_000_000",
            "https://x/720.mp4|720|2_500_000",
        ]
        // 1080 target → ×1.25 cap 1350 → tallest fitting = 1080.
        XCTAssertEqual(
            bridge.mediaPickRenditionUrl(renditionSpecs: specs, primaryUrl: "https://x/primary.mp4", targetHeight: 1080),
            "https://x/1080.mp4"
        )
        // 480 target → everything overshoots → smallest (client downscales).
        XCTAssertEqual(
            bridge.mediaPickRenditionUrl(renditionSpecs: specs, primaryUrl: "https://x/primary.mp4", targetHeight: 480),
            "https://x/720.mp4"
        )
        // No ladder → primary untouched.
        XCTAssertEqual(
            bridge.mediaPickRenditionUrl(renditionSpecs: [], primaryUrl: "https://x/primary.mp4", targetHeight: 1080),
            "https://x/primary.mp4"
        )
    }
}
