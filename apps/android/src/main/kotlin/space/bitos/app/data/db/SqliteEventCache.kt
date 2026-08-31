package space.bitos.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.bitos.app.data.feed.EventCache
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.EventId
import space.bitos.core.model.NostrEvent
import space.bitos.core.model.NostrKinds
import space.bitos.core.model.Pubkey
import space.bitos.core.model.RelayUrl
import space.bitos.core.store.EventStoreContract
import space.bitos.core.store.TagsCodec

/**
 * SQLite-backed verified event cache executing the shared versioned DDL
 * contract (DAT-001/002). Room is the eventual ergonomic wrapper; KSP has
 * no Kotlin 2.4.x release yet, and raw SQLite keeps the schema byte-identical
 * to iOS through `EventStoreContract` — the stronger parity guarantee for
 * this phase. The port interface isolates the swap.
 */
class SqliteEventCache(
    context: Context,
    databaseName: String = "bitos-events.sqlite3",
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, EventStoreContract.SCHEMA_VERSION), EventCache {

    override suspend fun clearAllCache() {
        withContext(Dispatchers.IO) {
            writableDatabase.delete(EventStoreContract.TABLE_EVENTS, null, null)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        EventStoreContract.ddl.forEach(db::execSQL)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // Write-ahead logging matches the iOS EventStore WAL pragma (audit
        // R6 / performance-audit-and-plan.md Phase 3): batch flushes stay
        // off the per-write fsync critical path.
        db.enableWriteAheadLogging()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Deterministic ordered migrations from the shared contract.
        for (version in (oldVersion + 1)..newVersion) {
            EventStoreContract.migrations[version]?.forEach(db::execSQL)
        }
    }

    override suspend fun upsertVerified(event: NostrEvent): Unit = withContext(Dispatchers.IO) {
        val signature = event.signature ?: return@withContext
        val statement = writableDatabase.compileStatement(
            "INSERT OR REPLACE INTO ${EventStoreContract.TABLE_EVENTS} (" +
                "${EventStoreContract.COL_ID}, ${EventStoreContract.COL_PUBKEY}, ${EventStoreContract.COL_CREATED_AT}, " +
                "${EventStoreContract.COL_KIND}, ${EventStoreContract.COL_TAGS}, ${EventStoreContract.COL_CONTENT}, " +
                "${EventStoreContract.COL_SIG}, ${EventStoreContract.COL_RELAY}, ${EventStoreContract.COL_FIRST_SEEN_AT}) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
        )
        statement.use {
            it.bindString(1, event.id.value)
            it.bindString(2, event.pubkey.value)
            it.bindLong(3, event.createdAt)
            it.bindLong(4, event.kind.toLong())
            it.bindString(5, TagsCodec.encode(event.tags))
            it.bindString(6, event.content)
            it.bindString(7, signature)
            event.receivedFromRelay?.let { relay -> it.bindString(8, relay.value) } ?: it.bindNull(8)
            it.bindLong(9, System.currentTimeMillis())
            it.executeInsert()
        }
    }

    /** One transaction per burst (audit R6): prepared once, committed once. */
    override suspend fun upsertAll(events: List<NostrEvent>): Unit = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext
        val db = writableDatabase
        val statement = db.compileStatement(
            "INSERT OR REPLACE INTO ${EventStoreContract.TABLE_EVENTS} (" +
                "${EventStoreContract.COL_ID}, ${EventStoreContract.COL_PUBKEY}, ${EventStoreContract.COL_CREATED_AT}, " +
                "${EventStoreContract.COL_KIND}, ${EventStoreContract.COL_TAGS}, ${EventStoreContract.COL_CONTENT}, " +
                "${EventStoreContract.COL_SIG}, ${EventStoreContract.COL_RELAY}, ${EventStoreContract.COL_FIRST_SEEN_AT}) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
        )
        statement.use {
            db.beginTransaction()
            try {
                for (event in events) {
                    val signature = event.signature ?: continue
                    it.bindString(1, event.id.value)
                    it.bindString(2, event.pubkey.value)
                    it.bindLong(3, event.createdAt)
                    it.bindLong(4, event.kind.toLong())
                    it.bindString(5, TagsCodec.encode(event.tags))
                    it.bindString(6, event.content)
                    it.bindString(7, signature)
                    event.receivedFromRelay?.let { relay -> it.bindString(8, relay.value) } ?: it.bindNull(8)
                    it.bindLong(9, System.currentTimeMillis())
                    it.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override suspend fun recentEvents(limit: Int): List<NostrEvent> = withContext(Dispatchers.IO) {
        val events = mutableListOf<NostrEvent>()
        readableDatabase.rawQuery(EventStoreContract.recentEventsQuery(limit), null).use { cursor ->
            while (cursor.moveToNext()) {
                val decoded = rowToEvent(cursor) ?: continue
                events.add(decoded)
            }
        }
        events
    }

    override suspend fun pruneToLimit(maxRows: Int) = withContext(Dispatchers.IO) {
        writableDatabase.execSQL(EventStoreContract.pruneStatement(maxRows))
    }

    private fun rowToEvent(cursor: android.database.Cursor): NostrEvent? {
        val id = EventId.parse(cursor.getString(cursor.getColumnIndexOrThrow(EventStoreContract.COL_ID))) ?: return null
        val pubkey = Pubkey.parse(cursor.getString(cursor.getColumnIndexOrThrow(EventStoreContract.COL_PUBKEY))) ?: return null
        val tags = TagsCodec.decode(cursor.getString(cursor.getColumnIndexOrThrow(EventStoreContract.COL_TAGS))) ?: return null
        val relayRaw = cursor.getString(cursor.getColumnIndexOrThrow(EventStoreContract.COL_RELAY))
        val kind = cursor.getInt(cursor.getColumnIndexOrThrow(EventStoreContract.COL_KIND))
        if (kind !in 0..65_535) return null
        return NostrEvent(
            id = id,
            pubkey = pubkey,
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(EventStoreContract.COL_CREATED_AT)),
            kind = kind,
            tags = tags,
            content = cursor.getString(cursor.getColumnIndexOrThrow(EventStoreContract.COL_CONTENT)),
            signature = cursor.getString(cursor.getColumnIndexOrThrow(EventStoreContract.COL_SIG)),
            receivedFromRelay = relayRaw?.let(RelayUrl::parse),
        )
    }
}
