import Foundation
import Observation

/**
 * Local ranking signals (web `interaction-profile` parity): dismissed notes
 * (hide / not-interested) and softer author/topic demotions that feed the
 * shared For-You ranker. Device-local only — never published, never synced.
 */
@MainActor
@Observable
final class InteractionProfileStore {
    private(set) var dismissedNotes: Set<String> = []
    private(set) var demotedAuthors: Set<String> = []
    private(set) var demotedTags: Set<String> = []

    private let defaults: UserDefaults

    private static let dismissedKey = "bitos.interaction.dismissed"
    private static let authorsKey = "bitos.interaction.demotedAuthors"
    private static let tagsKey = "bitos.interaction.demotedTags"
    private static let maxDismissed = 500
    private static let maxDemotions = 200

    init(defaults: UserDefaults = UserDefaults.standard) {
        self.defaults = defaults
        dismissedNotes = Self.load(defaults, key: Self.dismissedKey, max: Self.maxDismissed)
        demotedAuthors = Self.load(defaults, key: Self.authorsKey, max: Self.maxDemotions)
        demotedTags = Self.load(defaults, key: Self.tagsKey, max: Self.maxDemotions)
    }

    // MARK: - Dismiss (hide)

    func dismissNote(_ noteId: String) {
        guard !noteId.isEmpty else { return }
        dismissedNotes.insert(noteId)
        if dismissedNotes.count > Self.maxDismissed {
            dismissedNotes = Set(dismissedNotes.suffix(Self.maxDismissed))
        }
        persist(dismissedNotes, key: Self.dismissedKey)
    }

    func isDismissed(_ noteId: String) -> Bool { dismissedNotes.contains(noteId) }

    // MARK: - Author demotion (show less from)

    func demoteAuthor(_ pubkey: String) {
        guard pubkey.count == 64 else { return }
        demotedAuthors.insert(pubkey)
        if demotedAuthors.count > Self.maxDemotions {
            demotedAuthors = Set(demotedAuthors.suffix(Self.maxDemotions))
        }
        persist(demotedAuthors, key: Self.authorsKey)
    }

    func toggleDemotedAuthor(_ pubkey: String) {
        if demotedAuthors.contains(pubkey) {
            demotedAuthors.remove(pubkey)
        } else {
            demoteAuthor(pubkey)
        }
        persist(demotedAuthors, key: Self.authorsKey)
    }

    func isAuthorDemoted(_ pubkey: String) -> Bool { demotedAuthors.contains(pubkey) }

    // MARK: - Tag demotion (show less about)

    func demoteTag(_ tag: String) {
        let bounded = String(tag.prefix(32)).lowercased()
        guard !bounded.isEmpty else { return }
        demotedTags.insert(bounded)
        if demotedTags.count > Self.maxDemotions {
            demotedTags = Set(demotedTags.suffix(Self.maxDemotions))
        }
        persist(demotedTags, key: Self.tagsKey)
    }

    func toggleDemotedTag(_ tag: String) {
        let bounded = String(tag.prefix(32)).lowercased()
        if demotedTags.contains(bounded) {
            demotedTags.remove(bounded)
            persist(demotedTags, key: Self.tagsKey)
        } else {
            demoteTag(bounded)
        }
    }

    func isTagDemoted(_ tag: String) -> Bool { demotedTags.contains(tag.lowercased()) }

    // MARK: - Persistence

    private func persist(_ values: Set<String>, key: String) {
        defaults.set(Array(values), forKey: key)
    }

    private static func load(_ defaults: UserDefaults, key: String, max: Int) -> Set<String> {
        let raw = defaults.stringArray(forKey: key) ?? []
        return Set(raw.filter { (1...64).contains($0.count) }.prefix(max))
    }
}
