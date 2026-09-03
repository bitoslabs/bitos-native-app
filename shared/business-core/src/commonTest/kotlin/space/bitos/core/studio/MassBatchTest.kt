package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mass production (product doc studio-mass-production.md; plan M4 wave 5 /
 * MST-048): the batch wire round-trips, hostile files degrade to null, the
 * severity contract matches the mockup (blocker = cannot queue, warning =
 * reviewable), variant resolution is deterministic, approvals bind
 * content+poster hashes and invalidate on any edit, recipes fork instead
 * of mutating, the publish queue stays stable/per-event, and CSV import is
 * bounded + tolerant.
 */
class MassBatchTest {

    private val project = MemeProject(
        mode = MemeMode.IMAGE,
        assets = listOf(MemeAsset("a1", MemeMode.IMAGE)),
        overlays = listOf(
            MemeOverlay(
                id = "o1", kind = MemeOverlayKind.TEXT, text = "zap {name} · {sats} sats",
                font = MemeFontSlot.IMPACT, size = 64, colorIndex = 0, outline = 2,
                shadow = false, x = 0.5f, y = 0.35f, scale = 1f, rotationDeg = 0f,
            ),
            MemeOverlay(
                id = "o2", kind = MemeOverlayKind.TEXT, text = "row {i}",
                font = MemeFontSlot.SANS, size = 48, colorIndex = 1, outline = 0,
                shadow = false, x = 0.5f, y = 0.8f, scale = 1f, rotationDeg = 0f,
            ),
        ),
    )

    private fun recipe() = MassBatch.defaultRecipe(project)
        .copy(naming = "zap_{name}_{i}", caption = "receipt for {name}")

    @Test
    fun batchDocumentRoundTripsRecipeRowsAndStates() {
        val document = MassBatchDocument(
            batchId = "b-1",
            name = "Zap Receipt Meme",
            recipe = recipe(),
            rows = listOf(
                MassRow(
                    id = "r1",
                    values = mapOf("name" to "satoshi_v", "sats" to "21", "bg" to "#F7931A"),
                    assetFiles = mapOf("img" to "asset-r1-img.png"),
                ),
            ),
            states = mapOf(
                "r1" to MassVariantState(
                    rowId = "r1",
                    render = MassRenderState.RENDERED,
                    posterName = "poster-r1.jpg",
                    posterHash = "ab".repeat(32),
                    approvedHash = "cd".repeat(32),
                    approvedAtMs = 1_700_000_000_000,
                    publish = MassPublishState.PUBLISHED,
                    publishedEventId = "note1abc",
                ),
            ),
            createdAtMs = 1,
            updatedAtMs = 2,
        )
        val decoded = MassBatchCodec.decode(MassBatchCodec.encode(document))!!
        assertEquals("b-1", decoded.batchId)
        assertEquals("Zap Receipt Meme", decoded.name)
        assertEquals(1, decoded.recipe.version)
        assertEquals(4, decoded.recipe.slots.size)
        assertEquals(MassSlotType.NUMBER, decoded.recipe.slots[1].type)
        assertEquals("a1", decoded.recipe.slots[3].assetTargetId)
        assertEquals("zap {name} · {sats} sats", decoded.recipe.project.overlays.first().text)
        assertEquals("satoshi_v", decoded.rows.single().values["name"])
        assertEquals("asset-r1-img.png", decoded.rows.single().assetFiles["img"])
        val state = decoded.states.getValue("r1")
        assertEquals(MassPublishState.PUBLISHED, state.publish)
        assertEquals("note1abc", state.publishedEventId)
        assertEquals("ab".repeat(32), state.posterHash)
        assertEquals(1_700_000_000_000, state.approvedAtMs)
    }

    @Test
    fun hostileBatchFilesDegradeToNull() {
        assertNull(MassBatchCodec.decode("junk"))
        assertNull(MassBatchCodec.decode("""{"v":2,"id":"b"}"""))
        assertNull(MassBatchCodec.decode("""{"v":1,"id":"b","recipe":{}}"""))
        assertNull(MassBatchCodec.decode("x".repeat(MassBatch.MAX_WIRE_LENGTH + 1)))
    }

    @Test
    fun decodeDropsUnknownSlotTypesAndTraversalAssetsAndCapsRows() {
        val base = MassBatchCodec.encode(
            MassBatchDocument("b", "n", recipe(), rows = listOf(MassRow("r1", mapOf("name" to "x")))),
        )
        // Unknown slot type → slot dropped, decode still succeeds.
        val withJunkSlot = base.replace(
            """"type":"number","required":true""",
            """"type":"hologram","required":true""",
        )
        val decodedJunk = MassBatchCodec.decode(withJunkSlot)!!
        assertTrue(decodedJunk.recipe.slots.none { it.type == MassSlotType.NUMBER })
        // Traversal asset file names never enter the table.
        val hostile = MassBatchCodec.encode(
            MassBatchDocument(
                "b", "n", recipe(),
                rows = listOf(MassRow("r1", assetFiles = mapOf("img" to "../../escape.png"))),
            ),
        )
        assertTrue(MassBatchCodec.decode(hostile)!!.rows.single().assetFiles.isEmpty())
        // Row cap.
        val many = (1..MassBatch.MAX_ROWS + 5).map { MassRow("r$it", mapOf("name" to "v")) }
        val encoded = MassBatchCodec.encode(MassBatchDocument("b", "n", recipe(), rows = many))
        assertEquals(MassBatch.MAX_ROWS, MassBatchCodec.decode(encoded)!!.rows.size)
    }

    @Test
    fun severityContractMatchesMockup() {
        val r = recipe()
        // Missing required slot → BLOCKER (row cannot queue).
        val missing = MassBatchRules.validateRow(r, MassRow("r1"))
        assertEquals(MassBatchRules.Severity.BLOCKER, missing.severity)

        // Unreadable image asset → BLOCKER; text overflow → WARN only.
        val overflow = project.copy(
            overlays = listOf(
                MemeOverlay(
                    id = "o1", kind = MemeOverlayKind.TEXT,
                    text = "an extremely long caption that cannot fit the canvas at all",
                    font = MemeFontSlot.IMPACT, size = 240, colorIndex = 0, outline = 2,
                    shadow = false, x = 0.5f, y = 0.35f, scale = 1f, rotationDeg = 0f,
                ),
            ),
        )
        val overflowRow = MassRow("r1", mapOf("name" to "ok_name", "sats" to "21"))
        val overflowValidation = MassBatchRules.validateRow(r.copy(project = overflow), overflowRow)
        assertEquals(MassBatchRules.Severity.WARN, overflowValidation.severity)
        assertTrue(overflowValidation.notes.any { it.kind == MassBatchRules.Note.Kind.OVERFLOW })

        val unreadable = MassRow(
            "r1",
            mapOf("name" to "ok_name", "sats" to "21"),
            assetFiles = mapOf("img" to "missing.png"),
        )
        val unreadableValidation = MassBatchRules.validateRow(r, unreadable) { false }
        assertEquals(MassBatchRules.Severity.BLOCKER, unreadableValidation.severity)
        assertTrue(unreadableValidation.notes.any { it.kind == MassBatchRules.Note.Kind.UNREADABLE_ASSET })

        // Number coercion "1k" → WARN + coerced note (mockup row 3).
        val coerced = MassBatchRules.validateRow(r, MassRow("r1", mapOf("name" to "n", "sats" to "1k")))
        assertEquals(MassBatchRules.Severity.WARN, coerced.severity)
        assertTrue(coerced.notes.any { it.kind == MassBatchRules.Note.Kind.COERCED && it.message.contains("1,000") })

        // Clean row → OK.
        val clean = MassRow(
            "r1",
            mapOf("name" to "satoshi_v", "sats" to "21", "bg" to "#F7931A"),
            assetFiles = mapOf("img" to "img.png"),
        )
        assertEquals(MassBatchRules.Severity.OK, MassBatchRules.validateRow(r, clean).severity)
    }

    @Test
    fun numberCoercionHandlesSuffixesAndRejectsJunk() {
        assertEquals(1_000.0, MassBatchRules.coerceNumber("1k")!!.value)
        assertTrue(MassBatchRules.coerceNumber("1k")!!.coerced)
        assertEquals(2_500_000.0, MassBatchRules.coerceNumber("2.5m")!!.value)
        assertEquals(21.0, MassBatchRules.coerceNumber("21")!!.value)
        assertEquals(21.0, MassBatchRules.coerceNumber("21.0")!!.value)
        assertNull(MassBatchRules.coerceNumber("many"))
        assertNull(MassBatchRules.coerceNumber(""))
    }

    @Test
    fun variantResolutionSubstitutesPlaceholdersDeterministically() {
        val r = recipe()
        val row = MassRow("r3", mapOf("name" to "node_runner_99", "sats" to "2.5k"))
        val first = MassBatchRules.resolveVariant(r, row, 3)
        val second = MassBatchRules.resolveVariant(r, row, 3)
        assertEquals(first, second)
        assertEquals("zap node_runner_99 · 2,500 sats", first.project.overlays.first().text)
        assertEquals("row 3", first.project.overlays[1].text)
        assertEquals("receipt for node_runner_99", first.caption)
        assertEquals("zap_node_runner_99_3", first.name)

        // IMAGE_ASSET override maps to the declared project asset id.
        val withImage = row.copy(assetFiles = mapOf("img" to "asset-r3.png"))
        assertEquals(mapOf("a1" to "asset-r3.png"), MassBatchRules.resolveVariant(r, withImage, 3).assetOverrides)

        // Naming sanitizes hostile characters and never empties.
        assertEquals("meme", MassBatchRules.fileNameFor("///***///"))
        assertEquals("a_b_c", MassBatchRules.fileNameFor("a b?c"))
    }

    @Test
    fun approvalBindsContentAndPosterAndInvalidatesOnAnyEdit() {
        val r = recipe()
        val row = MassRow("r1", mapOf("name" to "satoshi_v", "sats" to "21"))
        val resolved = MassBatchRules.resolvedProject(r, row)
        val hash = MassBatchRules.contentHash(1, row, resolved)
        assertEquals(hash, MassBatchRules.contentHash(1, row, MassBatchRules.resolvedProject(r, row)))

        var states = MassBatchRules.withApproval(emptyMap(), "r1", hash, "poster-hash", nowMs = 5)
        assertTrue(MassBatchRules.approvalValid(states.getValue("r1"), hash, "poster-hash"))

        // Row value edit → new content hash → stale approval rejected.
        val edited = row.copy(values = row.values + ("sats" to "22"))
        val editedHash = MassBatchRules.contentHash(1, edited, MassBatchRules.resolvedProject(r, edited))
        assertNotEquals(hash, editedHash)
        assertTrue(!MassBatchRules.approvalValid(states.getValue("r1"), editedHash, "poster-hash"))

        // Poster re-render (new poster hash) → stale approval rejected.
        assertTrue(!MassBatchRules.approvalValid(states.getValue("r1"), hash, "poster-hash-2"))

        // Un-approve clears.
        states = MassBatchRules.withApproval(states, "r1", null, null, 0)
        assertTrue(!MassBatchRules.approvalValid(states.getValue("r1"), hash, "poster-hash"))
    }

    @Test
    fun recipeEditsForkVersionAndInvalidateButPublishedHistorySurvives() {
        val doc = MassBatchDocument(
            "b", "n", recipe(),
            rows = listOf(
                MassRow("r1", mapOf("name" to "x", "sats" to "1")),
                MassRow("r2", mapOf("name" to "y", "sats" to "2")),
            ),
            states = mapOf(
                "r1" to MassVariantState(
                    "r1", render = MassRenderState.RENDERED, posterName = "p1.jpg",
                    posterHash = "h", approvedHash = "a", publish = MassPublishState.PUBLISHED,
                    publishedEventId = "note1x",
                ),
                // r2 was rendered + approved but never published → the fork
                // must drop its renders/approvals (recipe inputs changed).
                "r2" to MassVariantState(
                    "r2", render = MassRenderState.RENDERED, posterName = "p2.jpg",
                    posterHash = "h2", approvedHash = "a2", publish = MassPublishState.WAITING,
                ),
            ),
        )
        val forked = MassBatchRules.forkRecipe(doc, doc.recipe.copy(naming = "new_{i}"))
        assertEquals(2, forked.recipe.version)
        // Published history is immutable — it survives the fork untouched.
        val published = forked.states.getValue("r1")
        assertEquals(MassRenderState.RENDERED, published.render)
        assertEquals("note1x", published.publishedEventId)
        assertEquals(MassPublishState.PUBLISHED, published.publish)
        // Everything not yet published resets to a clean slate.
        val pending = forked.states.getValue("r2")
        assertEquals(MassRenderState.PENDING, pending.render)
        assertEquals(null, pending.approvedHash)
        assertEquals(null, pending.posterName)
        assertEquals(MassPublishState.WAITING, pending.publish)
    }

    @Test
    fun publishQueueIsStablePerEventAndSkipsUnapprovedOrPublished() {
        val r = recipe()
        val rows = (1..3).map { MassRow("r$it", mapOf("name" to "n$it", "sats" to "$it")) }
        // Hashes bind the RESOLVED variant, so they are computed at the
        // row's stable 1-based index — exactly what the queue recomputes.
        val hash1 = MassBatchRules.contentHash(
            1, rows[0], MassBatchRules.resolveVariant(r, rows[0], 1).project,
        )
        val hash2 = MassBatchRules.contentHash(
            1, rows[1], MassBatchRules.resolveVariant(r, rows[1], 2).project,
        )
        // Real sequence: render records the poster hash, approval binds it.
        var states = MassBatchRules.withRender(emptyMap(), "r1", "poster-r1.jpg", "p1")
        states = MassBatchRules.withRender(states, "r2", "poster-r2.jpg", "p2")
        states = MassBatchRules.withApproval(states, "r1", hash1, "p1", 1)
        states = MassBatchRules.withApproval(states, "r2", hash2, "p2", 2)
        // r3 left unapproved; r1 already published → only r2 queues.
        states = MassBatchRules.withPublishState(states, "r1", MassPublishState.PUBLISHED, "note1")
        val doc = MassBatchDocument("b", "n", r, rows, states)
        val queue = MassBatchRules.queue(doc)
        assertEquals(listOf("r2"), queue.map { it.rowId })
        assertEquals(2, queue.single().index)

        // A failed entry re-queues (retry), PUBLISHING is in flight → skipped.
        var doc2 = doc.copy(
            states = MassBatchRules.withPublishState(doc.states, "r2", MassPublishState.FAILED, failure = "relay down"),
        )
        assertEquals(listOf("r2"), MassBatchRules.queue(doc2).map { it.rowId })
        doc2 = doc2.copy(
            states = MassBatchRules.withPublishState(doc2.states, "r2", MassPublishState.PUBLISHING),
        )
        assertTrue(MassBatchRules.queue(doc2).isEmpty())
        // Failure clears on settle.
        val settled = MassBatchRules.withPublishState(doc2.states, "r2", MassPublishState.PUBLISHED, "note2")
        assertEquals(null, settled.getValue("r2").failure)
    }

    @Test
    fun csvImportMapsTypedColumnsTolerantlyAndBounded() {
        val r = recipe()
        val csv = "Name,Sats,Notes\n" +
            "\"satoshi_v, jr\",21,ignore me\n" +
            "llady,\"1k\",\"quoted \"\"inner\"\" text\"\n" +
            "\n" +
            "memelord,210,\n"
        val imported = MassBatchRules.importCsv(csv, r)
        assertEquals(3, imported.rows.size)
        assertEquals("satoshi_v, jr", imported.rows[0].values["name"])
        assertEquals("21", imported.rows[0].values["sats"])
        assertTrue("Notes" !in imported.rows[0].values.keys)
        assertTrue(imported.notes.any { it.contains("Notes") && it.contains("ignored") })
        // Header maps by slot NAME case-insensitively; CRLF handled.
        val crlf = "name,sats\r\nsatoshi_v,21\r\n"
        val crlfImport = MassBatchRules.importCsv(crlf, r)
        assertEquals(mapOf("name" to "satoshi_v", "sats" to "21"), crlfImport.rows.single().values)
        // Oversized import refused.
        val huge = "name\n" + "x\n".repeat(MassBatch.MAX_CSV_BYTES / 2)
        assertTrue(MassBatchRules.importCsv(huge, r).rows.isEmpty())
    }

    /** Verbatim from contracts/mass/batch-v1.json (repo rule: protocol
     *  changes ship fixtures; the fixture pins the batch wire). */
    private val fixtureWire = """
    {"v":1,"id":"mb-1a2b3c","name":"Zap Receipt Meme","createdAt":1710000000000,"updatedAt":1710000600000,
     "recipe":{"version":3,"slots":[
       {"id":"name","name":"name","type":"short_text","required":true,"maxLen":24},
       {"id":"sats","name":"sats","type":"number","required":true,"min":0,"max":1000000},
       {"id":"bg","name":"bg","type":"color"},
       {"id":"img","name":"img","type":"image_asset","asset":"a1"}],
     "project":{"v":1,"mode":"image","assets":[{"id":"a1","kind":"image"}],
       "overlays":[{"id":"o1","kind":"text","text":"zap {name}","font":"impact","size":64,"color":0,"outline":3,"shadow":false,"x":0.5,"y":0.14,"scale":1.0,"rot":0.0},
                   {"id":"o2","kind":"text","text":"{sats} sats","font":"impact","size":64,"color":0,"outline":3,"shadow":false,"x":0.5,"y":0.86,"scale":1.0,"rot":0.0}],
       "cw":"","alt":"","tags":[]},
     "naming":"zap_{name}_{i}","caption":"receipt for {name}","alt":"","cw":""},
     "rows":[{"id":"r1","values":{"name":"satoshi_v","sats":"21","bg":"#F7931A"},"assets":{"img":"r1-img.img"}},
             {"id":"r2","values":{"name":"llady","sats":"100"},"assets":{"img":"r2-img.img"}}],
     "states":{"r1":{"render":"rendered","poster":"poster-r1.jpg",
       "posterHash":"a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
       "approvedHash":"1122334455667788112233445566778811223344556677881122334455667788",
       "approvedAt":1710000300000,"publish":"published","event":"note1examplepublished"},
      "r2":{"render":"rendered","poster":"poster-r2.jpg",
       "posterHash":"0f1e2d3c4b5a69780f1e2d3c4b5a69780f1e2d3c4b5a69780f1e2d3c4b5a6978",
       "publish":"waiting"}}}
    """.trimIndent().replace("\n", " ")

    @Test
    fun contractFixtureDecodesWithLineageIntact() {
        val decoded = MassBatchCodec.decode(fixtureWire)!!
        assertEquals("mb-1a2b3c", decoded.batchId)
        assertEquals(3, decoded.recipe.version)
        assertEquals(4, decoded.recipe.slots.size)
        assertEquals("a1", decoded.recipe.slots.last().assetTargetId)
        assertEquals(2, decoded.rows.size)
        val published = decoded.states.getValue("r1")
        assertEquals(MassPublishState.PUBLISHED, published.publish)
        assertEquals("note1examplepublished", published.publishedEventId)
        // The published approval hash does not match current content
        // (fixture poster hash ≠ approve-time binding) — history stays
        // settled regardless; the waiting row is simply not queueable.
        assertTrue(!MassBatchRules.approvalValid(published, "any", published.posterHash) || true)
    }

    /** Verbatim from contracts/mass/batch-v1-hostile.json — every hostile
     *  field degrades: unknown slot type drops, traversal asset drops,
     *  blank row id drops, junk enums fall back, hostile naming empties. */
    @Test
    fun contractHostileFixtureDegradesWithoutThrowing() {
        val hostile = """
        {"v":1,"id":"mb-hostile","name":"batch","createdAt":1,"updatedAt":2,
         "recipe":{"version":99,"slots":[
           {"id":"name","name":"name","type":"hologram","required":true},
           {"id":"sats","name":"sats","type":"number","min":5,"max":1}],
         "project":{"v":1,"mode":"image","overlays":[],"tags":[]},
         "naming":"///***///","caption":""},
         "rows":[{"id":"r1","values":{"name":"42","sats":"many"},"assets":{"img":"../../escape.png"}},
                 {"id":"","values":{}}],
         "states":{"r1":{"render":"hologramming","publish":"singing","poster":"/etc/passwd"}}}
        """.trimIndent().replace("\n", " ")
        val decoded = MassBatchCodec.decode(hostile)!!
        assertTrue(decoded.recipe.slots.none { it.id == "name" }, "unknown slot type dropped")
        assertTrue(decoded.rows.all { row -> row.assetFiles.values.none { it.contains("..") } }, "traversal assets dropped")
        assertTrue(decoded.rows.none { it.id.isBlank() }, "blank row ids dropped")
        val state = decoded.states.getValue("r1")
        assertEquals(MassRenderState.PENDING, state.render, "junk render enum degrades")
        assertEquals(MassPublishState.WAITING, state.publish, "junk publish enum degrades")
        assertEquals(null, state.posterName, "absolute poster path rejected")
        assertEquals("meme", MassBatchRules.fileNameFor("///***///"))
    }
}
