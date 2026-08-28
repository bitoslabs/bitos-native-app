import Foundation
import Observation

/// Process composition root: builds platform adapters and feature stores
/// once. Views receive dependencies by construction, never by global lookup.
@MainActor
@Observable
final class AppEnvironment {
    let relayPool: RelayPool
    let feedStore: FeedStore
    let businessCore: any BusinessCoreClient
    let identityStore: IdentityStore
    let notePublisher: NotePublisher
    let inboxStore: InboxStore
    let searchStore: SearchStore
    let authorStore: AuthorStore

    init(
        relayPool: RelayPool = RelayPool(urls: DefaultRelays.urls),
        businessCore: any BusinessCoreClient = FrameworkBusinessCoreClient(),
        eventStore: EventStore? = AppEnvironment.defaultEventStore(client: FrameworkBusinessCoreClient())
    ) {
        self.relayPool = relayPool
        self.businessCore = businessCore
        self.feedStore = FeedStore(pool: relayPool, client: businessCore, eventStore: eventStore)
        self.identityStore = IdentityStore()
        self.notePublisher = NotePublisher(pool: relayPool, identity: identityStore)
        self.inboxStore = InboxStore(pool: relayPool)
        self.searchStore = SearchStore(pool: relayPool, client: businessCore)
        self.authorStore = AuthorStore(pool: relayPool, client: businessCore)
    }

    static func live() -> AppEnvironment {
        AppEnvironment()
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
