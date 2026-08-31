import Foundation
import SQLite3

/// Row shape persisted by the shared event-store contract (DAT-001).
struct StoredEvent: Sendable, Equatable {
    let id: String
    let pubkey: String
    let createdAt: Int64
    let kind: Int
    let tagsJson: String
    let content: String
    let signature: String
    let relayUrl: String?
    let firstSeenAt: Int64
}

/**
 * SQLite-backed verified event cache (DAT-001/003).
 *
 * The schema is NOT defined here: the versioned DDL, bounds and query
 * templates come from `EventStoreContract` in shared/business-core and are
 * injected through the initializer (the Swift facade passes them from the
 * bridge). iOS and Android therefore execute byte-identical schemas.
 *
 * Synchronous by design; callers confine it to a background task. All
 * statements are prepared once and reused.
 *
 * `Sendable` (unchecked): the connection opens with SQLITE_OPEN_FULLMUTEX,
 * so SQLite serializes all access; prepared statements go through the same
 * serialized connection.
 */
final class EventStore {
    enum EventStoreError: Error, Equatable {
        case open(String)
        case prepare(String)
        case step(String)
    }

    private var db: OpaquePointer?
    private let insertStatement: OpaquePointer?
    private let selectStatement: OpaquePointer?
    private let pruneStatement: OpaquePointer?
    private let schemaVersion: Int

    /// - Parameters:
    ///   - path: file path, or `:memory:` for tests.
    ///   - schemaVersion: from the shared contract (PRAGMA user_version).
    ///   - ddl: creation statements from the shared contract.
    init(path: String, schemaVersion: Int, ddl: [String]) throws {
        self.schemaVersion = schemaVersion

        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(path, &handle, flags, nil) == SQLITE_OK, handle != nil else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown"
            throw EventStoreError.open(message)
        }
        db = handle

        // Write-ahead logging + relaxed sync: the event cache is a
        // rebuildable projection (DAT-003), so durability trades down for
        // burst-write throughput (performance-audit-and-plan.md Phase 3).
        var pragmaMessage: UnsafeMutablePointer<CChar>?
        _ = sqlite3_exec(db, "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;", nil, nil, &pragmaMessage)
        if let pragmaMessage { sqlite3_free(pragmaMessage) }

        for statement in ddl {
            var errorMessage: UnsafeMutablePointer<CChar>?
            guard sqlite3_exec(db, statement, nil, nil, &errorMessage) == SQLITE_OK else {
                let reason = errorMessage.map { String(cString: $0) } ?? "unknown"
                sqlite3_free(errorMessage)
                throw EventStoreError.prepare(reason)
            }
        }

        let existingVersion = Self.userVersion(of: db)
        if existingVersion == 0 {
            var errorMessage: UnsafeMutablePointer<CChar>?
            guard sqlite3_exec(db, "PRAGMA user_version = \(schemaVersion)", nil, nil, &errorMessage) == SQLITE_OK else {
                let reason = errorMessage.map { String(cString: $0) } ?? "unknown"
                sqlite3_free(errorMessage)
                throw EventStoreError.prepare(reason)
            }
        } else if existingVersion != schemaVersion {
            // Deterministic migrations arrive with the contract; v1 has none.
            guard existingVersion < schemaVersion else {
                throw EventStoreError.prepare("store schema \(existingVersion) newer than contract \(schemaVersion)")
            }
        }

        let insert = try Self.prepare(
            "INSERT OR REPLACE INTO events " +
            "(id, pubkey, created_at, kind, tags, content, sig, relay, first_seen_at) VALUES (?,?,?,?,?,?,?,?,?)",
            on: db
        )
        let select = try Self.prepare("SELECT * FROM events ORDER BY created_at DESC, id LIMIT ?", on: db)
        let prune = try Self.prepare(
            "DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY created_at DESC, id LIMIT ?)",
            on: db
        )
        insertStatement = insert
        selectStatement = select
        pruneStatement = prune
    }

    deinit {
        [insertStatement, selectStatement, pruneStatement].forEach { sqlite3_finalize($0) }
        sqlite3_close(db)
    }

    private static func prepare(_ sql: String, on db: OpaquePointer?) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else {
            throw EventStoreError.prepare(String(cString: sqlite3_errmsg(db)))
        }
        return statement
    }

    // MARK: - Operations

    /// Binds one event to the prepared insert statement and steps it.
    /// Burst callers use [insertBatch]; single [insert] remains for tests
    /// and one-off writes.
    private func bindAndStep(_ statement: OpaquePointer?, _ event: StoredEvent) throws {
        sqlite3_reset(statement)
        sqlite3_bind_text(statement, 1, event.id, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(statement, 2, event.pubkey, -1, SQLITE_TRANSIENT)
        sqlite3_bind_int64(statement, 3, event.createdAt)
        sqlite3_bind_int(statement, 4, Int32(event.kind))
        sqlite3_bind_text(statement, 5, event.tagsJson, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(statement, 6, event.content, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(statement, 7, event.signature, -1, SQLITE_TRANSIENT)
        if let relay = event.relayUrl {
            sqlite3_bind_text(statement, 8, relay, -1, SQLITE_TRANSIENT)
        } else {
            sqlite3_bind_null(statement, 8)
        }
        sqlite3_bind_int64(statement, 9, event.firstSeenAt)
        guard sqlite3_step(statement) == SQLITE_DONE else {
            throw EventStoreError.step(String(cString: sqlite3_errmsg(db)))
        }
    }

    /**
     * Inserts a burst of verified events in ONE transaction — one fsync per
     * batch instead of one per event keeps relay EOSE bursts off the disk
     * critical path (performance-audit-and-plan.md Phase 3). A failed batch
     * rolls back atomically.
     */
    func insertBatch(_ events: [StoredEvent]) throws {
        guard let statement = insertStatement, !events.isEmpty else { return }
        var beginError: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(db, "BEGIN IMMEDIATE", nil, nil, &beginError) == SQLITE_OK else {
            let reason = beginError.map { String(cString: $0) } ?? "unknown"
            sqlite3_free(beginError)
            throw EventStoreError.step("begin: \(reason)")
        }
        do {
            for event in events {
                try bindAndStep(statement, event)
            }
            var commitError: UnsafeMutablePointer<CChar>?
            if sqlite3_exec(db, "COMMIT", nil, nil, &commitError) != SQLITE_OK {
                let reason = commitError.map { String(cString: $0) } ?? "unknown"
                sqlite3_free(commitError)
                sqlite3_exec(db, "ROLLBACK", nil, nil, nil)
                throw EventStoreError.step("commit: \(reason)")
            }
        } catch {
            sqlite3_exec(db, "ROLLBACK", nil, nil, nil)
            throw error
        }
    }

    func insert(_ event: StoredEvent) throws {
        guard let statement = insertStatement else { throw EventStoreError.prepare("insert") }
        try bindAndStep(statement, event)
    }

    func recentEvents(limit: Int) throws -> [StoredEvent] {
        guard let statement = selectStatement else { throw EventStoreError.prepare("select") }
        sqlite3_reset(statement)
        sqlite3_bind_int(statement, 1, Int32(limit))
        var events: [StoredEvent] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            let relay: String?
            if sqlite3_column_type(statement, 7) != SQLITE_NULL {
                relay = String(cString: sqlite3_column_text(statement, 7))
            } else {
                relay = nil
            }
            events.append(
                StoredEvent(
                    id: String(cString: sqlite3_column_text(statement, 0)),
                    pubkey: String(cString: sqlite3_column_text(statement, 1)),
                    createdAt: sqlite3_column_int64(statement, 2),
                    kind: Int(sqlite3_column_int64(statement, 3)),
                    tagsJson: String(cString: sqlite3_column_text(statement, 4)),
                    content: String(cString: sqlite3_column_text(statement, 5)),
                    signature: String(cString: sqlite3_column_text(statement, 6)),
                    relayUrl: relay,
                    firstSeenAt: sqlite3_column_int64(statement, 8)
                )
            )
        }
        return events
    }

    func prune(maxRows: Int) throws {
        guard let statement = pruneStatement else { throw EventStoreError.prepare("prune") }
        sqlite3_reset(statement)
        sqlite3_bind_int(statement, 1, Int32(maxRows))
        guard sqlite3_step(statement) == SQLITE_DONE else {
            throw EventStoreError.step(String(cString: sqlite3_errmsg(db)))
        }
    }

    var rowCount: Int {
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }
        guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM events", -1, &statement, nil) == SQLITE_OK,
              sqlite3_step(statement) == SQLITE_ROW else { return 0 }
        return Int(sqlite3_column_int64(statement, 0))
    }

    private static func userVersion(of db: OpaquePointer?) -> Int {
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }
        guard sqlite3_prepare_v2(db, "PRAGMA user_version", -1, &statement, nil) == SQLITE_OK,
              sqlite3_step(statement) == SQLITE_ROW else { return 0 }
        return Int(sqlite3_column_int64(statement, 0))
    }

    var userVersion: Int { Self.userVersion(of: db) }

    private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
}

extension EventStore: @unchecked Sendable {}
