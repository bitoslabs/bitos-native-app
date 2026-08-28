import XCTest
@testable import BitOS

/**
 * EventStore behavior over an in-memory database executing the same shared
 * DDL the production store receives from the BusinessCore bridge (the DDL
 * below is mirrored from `EventStoreContract` in shared/business-core and is
 * locked there by `EventStoreContractTest`).
 */
final class EventStoreTests: XCTestCase {

    private func makeStore() throws -> EventStore {
        try EventStore(path: ":memory:", schemaVersion: 1, ddl: [
            """
            CREATE TABLE IF NOT EXISTS events (
                id TEXT PRIMARY KEY,
                pubkey TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                kind INTEGER NOT NULL,
                tags TEXT NOT NULL,
                content TEXT NOT NULL,
                sig TEXT NOT NULL,
                relay TEXT,
                first_seen_at INTEGER NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_events_created_at ON events (created_at DESC, id)",
        ])
    }

    private func event(id: String, createdAt: Int64, content: String) -> StoredEvent {
        StoredEvent(
            id: id, pubkey: String(repeating: "2", count: 64), createdAt: createdAt,
            kind: 1, tagsJson: #"[["t","bitcoin"]]"#, content: content,
            signature: String(repeating: "a", count: 128), relayUrl: "wss://relay.damus.io",
            firstSeenAt: 1_710_000_000
        )
    }

    func testInsertLoadAndDeduplicate() throws {
        let store = try makeStore()
        XCTAssertEqual(store.userVersion, 1)
        try store.insert(event(id: "old", createdAt: 1_710_000_000, content: "first"))
        try store.insert(event(id: "new", createdAt: 1_710_000_100, content: "second"))
        // Same id replaces, never duplicates.
        try store.insert(event(id: "old", createdAt: 1_710_000_000, content: "first (replaced)"))

        let recent = try store.recentEvents(limit: 10)
        XCTAssertEqual(recent.map(\.id), ["new", "old"])
        XCTAssertEqual(recent.first?.content, "second")
        XCTAssertEqual(recent.last?.content, "first (replaced)")
        XCTAssertEqual(store.rowCount, 2)
    }

    func testPruneKeepsNewestRows() throws {
        let store = try makeStore()
        for index in 0..<20 {
            try store.insert(event(id: "id\(index)", createdAt: Int64(1_710_000_000 + index), content: "c\(index)"))
        }
        try store.prune(maxRows: 5)
        XCTAssertEqual(store.rowCount, 5)
        XCTAssertEqual(try store.recentEvents(limit: 10).map(\.id), ["id19", "id18", "id17", "id16", "id15"])
    }

    func testNullableRelayRoundTrips() throws {
        var stored = event(id: "n1", createdAt: 1, content: "no relay")
        stored = StoredEvent(
            id: stored.id, pubkey: stored.pubkey, createdAt: stored.createdAt,
            kind: stored.kind, tagsJson: stored.tagsJson, content: stored.content,
            signature: stored.signature, relayUrl: nil, firstSeenAt: stored.firstSeenAt
        )
        let store = try makeStore()
        try store.insert(stored)
        XCTAssertNil(try store.recentEvents(limit: 1).first?.relayUrl)
    }
}
