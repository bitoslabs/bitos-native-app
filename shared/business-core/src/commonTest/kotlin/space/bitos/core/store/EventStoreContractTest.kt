package space.bitos.core.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventStoreContractTest {

    @Test
    fun schemaIsVersionedAndBounded() {
        assertEquals(1, EventStoreContract.SCHEMA_VERSION)
        assertEquals(500, EventStoreContract.MAX_ROWS)
        assertEquals(200, EventStoreContract.COLD_START_HYDRATE_LIMIT)
    }

    @Test
    fun ddlContainsEveryContractColumn() {
        val flat = EventStoreContract.ddl.joinToString(" ")
        for (column in listOf(
            EventStoreContract.COL_ID, EventStoreContract.COL_PUBKEY,
            EventStoreContract.COL_CREATED_AT, EventStoreContract.COL_KIND,
            EventStoreContract.COL_TAGS, EventStoreContract.COL_CONTENT,
            EventStoreContract.COL_SIG, EventStoreContract.COL_RELAY,
            EventStoreContract.COL_FIRST_SEEN_AT,
        )) {
            assertTrue(flat.contains(column), "DDL missing column $column")
        }
        // Idempotent creation so cold starts re-execute safely.
        assertTrue(flat.contains("CREATE TABLE IF NOT EXISTS ${EventStoreContract.TABLE_EVENTS}"))
    }

    @Test
    fun queriesAreBoundedAndOrdered() {
        assertEquals(
            "SELECT * FROM events ORDER BY created_at DESC, id LIMIT 200",
            EventStoreContract.recentEventsQuery(EventStoreContract.COLD_START_HYDRATE_LIMIT),
        )
        assertEquals(
            "DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY created_at DESC, id LIMIT 500)",
            EventStoreContract.pruneStatement(EventStoreContract.MAX_ROWS),
        )
    }

    @Test
    fun migrationsAreDeterministicByTargetVersion() {
        assertTrue(EventStoreContract.migrations.isEmpty(), "v1 has no migrations yet")
    }
}

class TagsCodecTest {

    @Test
    fun roundTripsNestedLists() {
        val tags = listOf(listOf("e", "b".repeat(32)), listOf("p", "c".repeat(32)), listOf("t", "bitcoin"))
        val encoded = TagsCodec.encode(tags)
        assertEquals("""[["e","${"b".repeat(32)}"],["p","${"c".repeat(32)}"],["t","bitcoin"]]""", encoded)
        assertEquals(tags, TagsCodec.decode(encoded))
    }

    @Test
    fun roundTripsEmptyAndHostileStrings() {
        val tags = listOf(emptyList(), listOf("quote\"tag", "back\\slash", "new\nline", "unicode ₿"))
        assertEquals(tags, TagsCodec.decode(TagsCodec.encode(tags)))
        assertEquals("[]", TagsCodec.encode(emptyList()))
        assertEquals(emptyList<List<String>>(), TagsCodec.decode("[]"))
    }

    @Test
    fun decodeRejectsMalformedJson() {
        assertNull(TagsCodec.decode("not json"))
        assertNull(TagsCodec.decode("""{"a":1}"""))
        assertNull(TagsCodec.decode("""[["a"],["""))
    }
}
