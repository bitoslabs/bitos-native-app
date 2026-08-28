package space.bitos.core.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Versioned persistence contract for the verified event store (DAT-001/002).
 *
 * The logical schema lives here once; iOS executes the DDL verbatim through
 * the bridge and Android maps the column constants onto its store. Schema
 * changes bump [SCHEMA_VERSION] and add a deterministic migration statement
 * to [migrations] — never edit history.
 */
object EventStoreContract {
    const val SCHEMA_VERSION = 1

    const val TABLE_EVENTS = "events"
    const val COL_ID = "id"
    const val COL_PUBKEY = "pubkey"
    const val COL_CREATED_AT = "created_at"
    const val COL_KIND = "kind"
    const val COL_TAGS = "tags"
    const val COL_CONTENT = "content"
    const val COL_SIG = "sig"
    const val COL_RELAY = "relay"
    const val COL_FIRST_SEEN_AT = "first_seen_at"

    /** Bounded cache window (DAT-003): newest rows survive pruning. */
    const val MAX_ROWS = 500

    /** Rows hydrated into the feed window on cold start. */
    const val COLD_START_HYDRATE_LIMIT = 200

    val ddl: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $TABLE_EVENTS (
            $COL_ID TEXT PRIMARY KEY,
            $COL_PUBKEY TEXT NOT NULL,
            $COL_CREATED_AT INTEGER NOT NULL,
            $COL_KIND INTEGER NOT NULL,
            $COL_TAGS TEXT NOT NULL,
            $COL_CONTENT TEXT NOT NULL,
            $COL_SIG TEXT NOT NULL,
            $COL_RELAY TEXT,
            $COL_FIRST_SEEN_AT INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_events_created_at ON $TABLE_EVENTS ($COL_CREATED_AT DESC, $COL_ID)",
    )

    /** Deterministic, ordered migrations keyed by target version. */
    val migrations: Map<Int, List<String>> = emptyMap()

    fun recentEventsQuery(limit: Int): String =
        "SELECT * FROM $TABLE_EVENTS ORDER BY $COL_CREATED_AT DESC, $COL_ID LIMIT $limit"

    fun pruneStatement(maxRows: Int): String =
        "DELETE FROM $TABLE_EVENTS WHERE $COL_ID NOT IN " +
            "(SELECT $COL_ID FROM $TABLE_EVENTS ORDER BY $COL_CREATED_AT DESC, $COL_ID LIMIT $maxRows)"
}

/**
 * Canonical tags encoding for the `tags` column: a JSON array of string
 * arrays, preserving order. Encode/decode live here so both platforms store
 * byte-identical rows through the same rules.
 */
object TagsCodec {
    private val json = Json

    fun encode(tags: List<List<String>>): String =
        JsonArray(tags.map { tag -> JsonArray(tag.map { item -> kotlinx.serialization.json.JsonPrimitive(item) }) }).toString()

    fun decode(raw: String): List<List<String>>? = try {
        json.parseToJsonElement(raw).jsonArray.map { tagElement ->
            tagElement.jsonArray.map { it.jsonPrimitive.content }
        }
    } catch (_: Exception) {
        null
    }
}
