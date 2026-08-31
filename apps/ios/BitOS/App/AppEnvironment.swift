import BusinessCore
import Foundation
import Observation

/// Process composition root: builds platform adapters and feature stores
/// once. Views receive dependencies by construction, never by global lookup.
@MainActor
@Observable
final class AppEnvironment {
    let relayPool: RelayPool
    let feedStore: FeedStore
    let sentZaps: SentZapsStore
    let dmStore: DmStore
    let storiesStore: StoriesStore
    let businessCore: any BusinessCoreClient
    let identityStore: IdentityStore
    let notePublisher: NotePublisher
    let inboxStore: InboxStore
    let searchStore: SearchStore
    let authorStore: AuthorStore
    let relayManager: RelayManagerStore
    let algorithmStore: AlgorithmStore
    let privacyPrefs: PrivacyPrefsStore
    let profileLookup: ProfileLookupStore
    let posterImages: PosterImagePipeline
    /** Local ranking signals (hide + author/tag demotions). */
    let interaction: InteractionProfileStore
    /** NIP-51 followed hashtags (kind 30015 d=interest). */
    let hashtagFollows: HashtagFollowsStore

    init(
        relayPool: RelayPool? = nil,
        businessCore: (any BusinessCoreClient)? = nil,
        eventStore: EventStore? = nil
    ) {
        // Default arguments evaluate nonisolated (Swift 6); resolve the
        // defaults inside the MainActor body instead.
        let relayPool = relayPool ?? Self.bootRelayPool()
        let businessCore = businessCore ?? FrameworkBusinessCoreClient()
        let eventStore = eventStore ?? Self.defaultEventStore(client: FrameworkBusinessCoreClient())
        let interaction = InteractionProfileStore()
        self.relayPool = relayPool
        self.businessCore = businessCore
        self.feedStore = FeedStore(pool: relayPool, client: businessCore, eventStore: eventStore, interaction: interaction)
        self.identityStore = IdentityStore()
        self.notePublisher = NotePublisher(pool: relayPool, identity: identityStore)
        self.inboxStore = InboxStore(pool: relayPool)
        self.searchStore = SearchStore(pool: relayPool, client: businessCore)
        self.authorStore = AuthorStore(pool: relayPool, client: businessCore)
        self.relayManager = RelayManagerStore(pool: relayPool)
        let algorithm = AlgorithmStore()
        self.algorithmStore = algorithm
        self.privacyPrefs = PrivacyPrefsStore()
        self.storiesStore = StoriesStore(pool: relayPool)
        self.dmStore = DmStore(pool: relayPool) {
            IdentityKeychain.loadSecret()
        }
        self.profileLookup = ProfileLookupStore(pool: relayPool, client: businessCore)
        self.posterImages = PosterImagePipeline()
        self.interaction = interaction
        self.hashtagFollows = HashtagFollowsStore(pool: relayPool)
        // Algorithm wire drives the For-You ranking for the process lifetime.
        algorithm.sink = { [weak feedStore] json in
            feedStore?.setAlgorithm(snapshotJson: json)
        }
    }

    static func live() -> AppEnvironment {
        AppEnvironment()
    }

    /// Cold-start pool: the persisted managed relay set, or the defaults.
    private static func bootRelayPool() -> RelayPool {
        let bridge = BusinessCoreBridge()
        if let defaults = UserDefaults(suiteName: "bitos.settings"),
           let wire = defaults.string(forKey: RelayManagerStore.storageKey) {
            let decoded = bridge.relayListDecode(json: wire)
            let urls = decoded.compactMap { RelayURL.parse($0.url) }
            if !urls.isEmpty { return RelayPool(urls: urls) }
        }
        return RelayPool(urls: DefaultRelays.urls)
    }

    /// Opens the on-disk event store using the shared versioned DDL contract.
    /// Returns nil when the store cannot open (app continues cache-less).
    private static func defaultEventStore(client: BusinessCoreClient) -> EventStore? {
        let urls = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
        guard let support = urls.first else { return nil }
        do {
            try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
            let path = support.appendingPathComponent("bitos-events.sqlite3").path
            return try EventStore(
                path: path,
                schemaVersion: client.eventStoreSchemaVersion(),
                ddl: client.eventStoreDdl()
            )
        } catch {
            return nil
        }
    }
}
