import BusinessCore
import Foundation
import Observation

// MARK: - Swift value mirrors (wire strings from the shared contract)

enum SettingsThemeMode: String, CaseIterable, Identifiable {
    case light, dark, system
    var id: String { rawValue }
    var label: String {
        switch self {
        case .light: "Light"
        case .dark: "Dark"
        case .system: "System"
        }
    }
    var symbol: String {
        switch self {
        case .light: "sun.max"
        case .dark: "moon"
        case .system: "gear"
        }
    }
}

enum SettingsFontSize: String, CaseIterable, Identifiable {
    case small, `default`, large
    case extraLarge = "extra_large"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .small: "Small"
        case .default: "Default"
        case .large: "Large"
        case .extraLarge: "Extra Large"
        }
    }
}

enum SettingsLanguage: String, CaseIterable, Identifiable {
    case english = "en"
    case lao = "lo"
    var id: String { rawValue }
    var label: String { self == .english ? "English" : "ລາວ / Lao" }
}

enum SettingsFeedTimeline: String, CaseIterable, Identifiable {
    case latest, trending
    var id: String { rawValue }
    var label: String { self == .latest ? "Latest" : "Trending" }
}

enum SettingsMediaAutoPlay: String, CaseIterable, Identifiable {
    case always, wifi, never
    var id: String { rawValue }
    var label: String {
        switch self {
        case .always: "Always"
        case .wifi: "Wi-Fi Only"
        case .never: "Never"
        }
    }
}

enum SettingsVideoQuality: String, CaseIterable, Identifiable {
    case auto, high, low
    var id: String { rawValue }
    var label: String {
        switch self {
        case .auto: "Auto"
        case .high: "High"
        case .low: "Low"
        }
    }
}

enum SettingsPlaybackRate: String, CaseIterable, Identifiable {
    case x0_5 = "0.5", x0_75 = "0.75", x1 = "1", x1_25 = "1.25", x1_5 = "1.5", x2 = "2"
    var id: String { rawValue }
    var label: String { rawValue + "×" }
}

enum SettingsSensitiveMedia: String, CaseIterable, Identifiable {
    case cover, show
    var id: String { rawValue }
    var label: String {
        switch self {
        case .cover: "Cover by default"
        case .show: "Show directly"
        }
    }
}

enum SettingsDateFormat: String, CaseIterable, Identifiable {
    case mdy = "MDY", dmy = "DMY", ymd = "YMD"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .mdy: "Month · Day · Year"
        case .dmy: "Day · Month · Year"
        case .ymd: "Year · Month · Day"
        }
    }
}

/// Bitz surface mode (APP-007): the view the reels tab boots into.
enum SettingsBitzMode: String, CaseIterable, Identifiable {
    case explore, following
    case forYou = "for_you"
    // Wire values must equal the shared BitzModeSetting (settings v5).
    case trending, zapped
    var id: String { rawValue }
    var label: String {
        switch self {
        case .explore: "Explore"
        case .following: "Following"
        case .forYou: "For you"
        case .trending: "Trending"
        case .zapped: "Most zapped"
        }
    }
}

import SwiftUI

/// Theme preference → SwiftUI color scheme (nil = follow system).
extension SettingsThemeMode {
    var colorScheme: ColorScheme? {
        switch self {
        case .light: .light
        case .dark: .dark
        case .system: nil
        }
    }
}

/// Typed, immutable settings state mirrored from the shared snapshot.
struct SettingsState: Equatable {
    var themeMode: SettingsThemeMode = .dark
    var accentColorHex = "#F7931A"
    var fontSize: SettingsFontSize = .default
    var language: SettingsLanguage = .english
    var notificationsEnabled = true
    var soundEnabled = true
    var hapticEnabled = true
    var compactMode = false
    var feedTimeline: SettingsFeedTimeline = .latest
    var mediaPreview = true
    var showReactions = true
    var showProtocolNotes = false
    var mediaAutoPlay: SettingsMediaAutoPlay = .wifi
    var videoQuality: SettingsVideoQuality = .auto
    var playbackRate: SettingsPlaybackRate = .x1
    var defaultZapAmount = 21
    var timeZone = "auto"
    var dateFormat: SettingsDateFormat = .mdy
    var sensitiveMedia: SettingsSensitiveMedia = .cover
    var bitzMode: SettingsBitzMode = .forYou
    /// Autoplay starts politely muted (legacy web parity); persists.
    var videoMuted = true
}

/**
 * APP-018 settings adapter: UserDefaults-backed key-value store executing
 * the shared `space.bitos.core.settings` contract through the bridge. All
 * rules (defaults, validation, canonical wire values, clear-cache
 * protection, cache formatting) live in business-core; this adapter only
 * persists and notifies SwiftUI.
 */@MainActor
@Observable
final class SettingsStore {
    private(set) var state = SettingsState()

    /// Preferences storage (versioned by name; settings keys are the
    /// legacy `bitos_*` contract keys).
    private let defaults: UserDefaults
    private let bridge = BusinessCoreBridge()

    init(defaults: UserDefaults? = UserDefaults(suiteName: "bitos.settings")) {
        self.defaults = defaults ?? .standard
        reload()
    }

    /// Reload the typed snapshot from persisted wire values.
    func reload() {
        let snapshot = bridge.settingsSnapshot(kv: persistedKv())
        state = SettingsState(
            themeMode: SettingsThemeMode(rawValue: snapshot.themeMode) ?? .dark,
            accentColorHex: snapshot.accentColorHex,
            fontSize: SettingsFontSize(rawValue: snapshot.fontSize) ?? .default,
            language: SettingsLanguage(rawValue: snapshot.language) ?? .english,
            notificationsEnabled: snapshot.notificationsEnabled,
            soundEnabled: snapshot.soundEnabled,
            hapticEnabled: snapshot.hapticEnabled,
            compactMode: snapshot.compactMode,
            feedTimeline: SettingsFeedTimeline(rawValue: snapshot.feedTimeline) ?? .latest,
            mediaPreview: snapshot.mediaPreview,
            showReactions: snapshot.showReactions,
            showProtocolNotes: snapshot.showProtocolNotes,
            mediaAutoPlay: SettingsMediaAutoPlay(rawValue: snapshot.mediaAutoPlay) ?? .wifi,
            videoQuality: SettingsVideoQuality(rawValue: snapshot.videoQuality) ?? .auto,
            playbackRate: SettingsPlaybackRate(rawValue: snapshot.videoPlaybackRate) ?? .x1,
            defaultZapAmount: Int(snapshot.defaultZapAmount),
            timeZone: snapshot.timeZone,
            dateFormat: SettingsDateFormat(rawValue: snapshot.dateFormat) ?? .mdy,
            sensitiveMedia: SettingsSensitiveMedia(rawValue: snapshot.sensitiveMedia) ?? .cover,
            bitzMode: SettingsBitzMode(rawValue: snapshot.bitzMode) ?? .forYou,
            videoMuted: snapshot.videoMuted
        )
    }

    /// Persist one canonical wire value through shared validation; returns
    /// false (and persists nothing) when the shared rules reject it.
    @discardableResult
    func setRaw(_ rawValue: String, forKey key: String) -> Bool {
        guard let canonical = bridge.settingsNormalizeValue(key: key, rawValue: rawValue) else { return false }
        defaults.set(canonical, forKey: key)
        reload()
        return true
    }

    // MARK: - Typed setters (state → wire → shared validation → persist)

    func set(_ mode: SettingsThemeMode) { setRaw(mode.rawValue, forKey: "bitos_theme_mode") }
    func set(_ size: SettingsFontSize) { setRaw(size.rawValue, forKey: "bitos_font_size") }
    func set(_ language: SettingsLanguage) { setRaw(language.rawValue, forKey: "bitos_language") }
    func set(_ timeline: SettingsFeedTimeline) { setRaw(timeline.rawValue, forKey: "bitos_feed_timeline") }
    func set(_ autoPlay: SettingsMediaAutoPlay) { setRaw(autoPlay.rawValue, forKey: "bitos_media_auto_play") }
    func set(_ quality: SettingsVideoQuality) { setRaw(quality.rawValue, forKey: "bitos_video_quality") }
    func set(_ rate: SettingsPlaybackRate) { setRaw(rate.rawValue, forKey: "bitos_video_playback_rate") }
    func set(_ format: SettingsDateFormat) { setRaw(format.rawValue, forKey: "bitos_date_format") }
    func set(_ sensitive: SettingsSensitiveMedia) { setRaw(sensitive.rawValue, forKey: "bitos_sensitive_media") }
    func set(_ mode: SettingsBitzMode) { setRaw(mode.rawValue, forKey: "bitos_bitz_mode") }

    /** APP-007 mute memory: video autoplay starts muted; choice persists. */
    func setVideoMuted(_ muted: Bool) { setRaw(muted ? "1" : "0", forKey: "bitos_video_muted") }

    /** Accent palette value (`#RRGGBB`); shared rule canonicalizes to uppercase. */
    func setAccentColor(hex: String) { setRaw(hex, forKey: "bitos_accent_color") }

    func setNotifications(_ enabled: Bool) { setRaw(enabled ? "1" : "0", forKey: "bitos_notifications") }
    func setSound(_ enabled: Bool) { setRaw(enabled ? "1" : "0", forKey: "bitos_sound") }
    func setHaptic(_ enabled: Bool) { setRaw(enabled ? "1" : "0", forKey: "bitos_haptic") }
    func setCompactMode(_ enabled: Bool) { setRaw(enabled ? "1" : "0", forKey: "bitos_compact_mode") }
    func setMediaPreview(_ enabled: Bool) { setRaw(enabled ? "1" : "0", forKey: "bitos_feed_media_preview") }
    func setShowReactions(_ enabled: Bool) { setRaw(enabled ? "1" : "0", forKey: "bitos_feed_show_reactions") }
    func setShowProtocolNotes(_ enabled: Bool) { setRaw(enabled ? "1" : "0", forKey: "bitos_feed_show_protocol_notes") }

    func setDefaultZapAmount(_ amount: Int) {
        setRaw(String(amount), forKey: "bitos_default_zap_amount")
    }

    // MARK: - Cache (legacy parity: clear content prefs, keep device globals)

    /// Sum of persisted settings bytes (legacy counted stored pref values).
    var cacheSizeLabel: String {
        bridge.formatCacheSize(bytes: Int64(persistedKv().values.reduce(0) { $0 + $1.utf8.count }))
    }

    /// Clear cache: remove every settings key except the protected device
    /// globals (theme/font/language), then reload defaults.
    func clearCache() {
        let allKeys = Array(persistedKv().keys)
        for key in bridge.settingsClearCacheRemovableKeys(allKeys: allKeys) {
            defaults.removeObject(forKey: key)
        }
        reload()
    }

    /// Ordered section catalog from the shared contract (web mobile index).
    var sections: [(key: String, group: String)] {
        bridge.settingsSections().map { ($0.key, $0.group) }
    }

    /// Short npub for display (shared rule).
    func shortNpub(_ npub: String) -> String {
        bridge.shortNpub(npub: npub)
    }

    private func persistedKv() -> [String: String] {
        var kv: [String: String] = [:]
        for key in bridge.settingsKeys() {
            if let value = defaults.string(forKey: key) {
                kv[key] = value
            }
        }
        return kv
    }
}
