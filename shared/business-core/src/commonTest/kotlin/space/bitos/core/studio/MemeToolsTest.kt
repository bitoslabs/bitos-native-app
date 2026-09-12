package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shell-contract invariants (plan MSU-001). These are the rules that make
 * the shipped usability bugs unrepresentable, so they are asserted rather
 * than documented:
 *
 *  1. No mode repeats a tool id in its primary list.
 *  2. Media · Text · Sticker · Look appear in every mode, within the cap.
 *  3. Primary order is a stable subsequence of the canonical order.
 *  4. A tool id belongs to exactly one tier.
 *  5. There is exactly ONE look/grade/adjust tool id (no duplicates).
 */
class MemeToolsTest {

    private val tools = MemeTools

    @Test
    fun everyModeShowsTheUniversalFourWithinThePrimaryCap() {
        MemeMode.entries.forEach { mode ->
            val primary = tools.primaryFor(mode)
            // Never more than the cap (room for the More tile), never fewer
            // than the four creative essentials.
            assertTrue(
                primary.size in 4..tools.MAX_PRIMARY_TOOLS,
                "$mode primary size ${primary.size} outside 4..${tools.MAX_PRIMARY_TOOLS}: $primary",
            )
            listOf(
                MemeTools.ToolId.MEDIA,
                MemeTools.ToolId.TEXT,
                MemeTools.ToolId.STICKER,
                MemeTools.ToolId.LOOK,
            ).forEach { id -> assertTrue(id in primary, "$mode is missing $id") }
        }
        // Sound is mode-gated: GIF is silent, IMAGE has no timeline cue
        // target — only VIDEO carries an audio track.
        assertEquals(tools.MAX_PRIMARY_TOOLS, tools.primaryFor(MemeMode.VIDEO).size)
        assertTrue(MemeTools.ToolId.SOUND !in tools.primaryFor(MemeMode.GIF))
        assertTrue(MemeTools.ToolId.SOUND !in tools.primaryFor(MemeMode.IMAGE))
        assertTrue(MemeTools.ToolId.SOUND in tools.primaryFor(MemeMode.VIDEO))
    }

    @Test
    fun noModeRepeatsAToolIdInItsPrimaryList() {
        MemeMode.entries.forEach { mode ->
            val primary = tools.primaryFor(mode)
            assertEquals(primary.size, primary.toSet().size, "$mode repeats a primary tool: $primary")
        }
    }

    @Test
    fun primaryOrderIsAStableSubsequenceOfTheCanonicalOrder() {
        val canonical = tools.PRIMARY_ORDER
        MemeMode.entries.forEach { mode ->
            val indices = tools.primaryFor(mode).map { canonical.indexOf(it) }
            assertTrue(indices.all { it >= 0 }, "$mode has a primary tool outside PRIMARY_ORDER")
            assertEquals(
                indices.sorted(),
                indices,
                "$mode reorders the bar — the tool bar must not reshuffle across modes",
            )
        }
    }

    @Test
    fun everyToolIdBelongsToExactlyOneTier() {
        val primaryIds = tools.PRIMARY_ORDER.toSet()
        val advancedIds = tools.ADVANCED_ORDER.toSet()
        val selectionIds = MemeTools.SelectionKind.entries
            .flatMap { tools.selectionFor(it) }
            .toSet()

        // Primary and advanced never overlap (the shipped duplicate-chip bug).
        assertTrue(
            primaryIds.intersect(advancedIds).isEmpty(),
            "primary ∩ advanced: ${primaryIds.intersect(advancedIds)}",
        )
        // Selection actions are contextual, never standing primary tools.
        assertTrue(
            primaryIds.intersect(selectionIds).isEmpty(),
            "primary ∩ selection: ${primaryIds.intersect(selectionIds)}",
        )
        // Every declared tool id is reachable from at least one tier.
        val reachable = primaryIds + advancedIds + selectionIds
        MemeTools.ToolId.entries.forEach { id ->
            assertTrue(id in reachable, "$id is declared but unreachable from any tier")
        }
    }

    @Test
    fun thereIsExactlyOneLookToolIdSoGradeAdjustAndMotionCannotSplit() {
        val labelMatches = MemeTools.ToolId.entries.filter { it.label == "Look" }
        assertEquals(1, labelMatches.size, "Look must be one tool, found $labelMatches")
        // The historical duplicate ids (Filter / Adjust / Effects) must not
        // reappear as separate tools.
        listOf("Filter", "Adjust", "Effects").forEach { legacy ->
            assertTrue(
                MemeTools.ToolId.entries.none { it.label == legacy },
                "legacy duplicate tool '$legacy' reintroduced",
            )
        }
    }

    @Test
    fun selectedNothingYieldsNoActionsAndEachKindYieldsSomething() {
        assertTrue(tools.selectionFor(null).isEmpty())
        assertTrue(tools.selectionFor(MemeTools.SelectionKind.NONE).isEmpty())
        listOf(
            MemeTools.SelectionKind.TEXT,
            MemeTools.SelectionKind.STICKER,
            MemeTools.SelectionKind.IMAGE_LAYER,
            MemeTools.SelectionKind.CLIP,
            MemeTools.SelectionKind.GIF_FRAME,
            MemeTools.SelectionKind.SFX_CUE,
        ).forEach { kind ->
            assertTrue(tools.selectionFor(kind).isNotEmpty(), "$kind has no actions")
            // Delete is the one action every selection must offer.
            assertTrue(
                MemeTools.ToolId.DELETE in tools.selectionFor(kind),
                "$kind cannot be deleted",
            )
        }
    }

    @Test
    fun unknownModeFallsBackToTheImageSetInsteadOfEmpty() {
        assertEquals(tools.primaryFor(MemeMode.IMAGE), tools.primaryFor(null))
        assertTrue(tools.advancedFor(null).isNotEmpty())
    }

    @Test
    fun catalogJsonCarriesStableTokensAndThePrimaryCap() {
        val json = tools.catalogJson()
        // Stable lowercase tokens the native `when` switches on.
        assertTrue(json.contains("\"id\":\"media\""), json)
        assertTrue(json.contains("\"id\":\"time_window\""), json)
        assertTrue(json.contains("\"maxPrimary\":${tools.MAX_PRIMARY_TOOLS}"), json)
        // Every mode bucket exists.
        MemeMode.entries.forEach { mode ->
            assertTrue(json.contains("\"${mode.name.lowercase()}\""), "missing bucket ${mode.name}")
        }
    }

    @Test
    fun iconKeysAreDistinctWithinEachTier() {
        // The shipped AppsGrid glyph meant Overlay + Layers + Clips at once —
        // three unrelated things behind one picture. The rule that prevents
        // that is: no two chips the creator sees *together* share a glyph.
        // (The same meaning may reuse a glyph across tiers — e.g. the
        // Timeline entry and the clip-selection "Timeline" action.)
        fun keysOf(ids: List<MemeTools.ToolId>) = ids.map { it.iconKey }

        val advanced = tools.ADVANCED_ORDER
        val advancedKeys = keysOf(advanced)
        assertEquals(
            advancedKeys.size,
            advancedKeys.toSet().size,
            "two advanced tools share a glyph: $advancedKeys",
        )

        MemeMode.entries.forEach { mode ->
            val primaryKeys = keysOf(tools.primaryFor(mode))
            assertEquals(
                primaryKeys.size,
                primaryKeys.toSet().size,
                "$mode primary tools share a glyph: $primaryKeys",
            )
        }

        MemeTools.SelectionKind.entries.forEach { kind ->
            val selectionKeys = keysOf(tools.selectionFor(kind))
            assertEquals(
                selectionKeys.size,
                selectionKeys.toSet().size,
                "$kind selection actions share a glyph: $selectionKeys",
            )
        }

        // The generic grid glyph is retired from the studio vocabulary.
        val allKeys = MemeTools.ToolId.entries.map { it.iconKey }
        assertTrue("apps_grid" !in allKeys, "the generic apps_grid key must not be reused")
    }
}
