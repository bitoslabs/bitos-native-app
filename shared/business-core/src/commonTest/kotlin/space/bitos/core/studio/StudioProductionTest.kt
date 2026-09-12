package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mass-production + operator copy (plan MSU-060..063): the batch status
 * strip is bounded and null-safe, the template-batch count is clamped, and
 * the operator shortcut reference is unique and complete.
 */
class StudioProductionTest {

    private val production = StudioProduction

    @Test
    fun batchStripHidesWhenThereIsNoBatch() {
        assertNull(production.batchStrip(rendered = 0, total = 0))
        assertNull(production.batchStrip(rendered = 5, total = 0))
    }

    @Test
    fun batchStripReportsProgressAndLinksTheQueue() {
        val strip = production.batchStrip(rendered = 3, total = 8)
        assertTrue(strip != null && strip.contains("3 of 8 rendered"), strip)
        assertTrue(strip != null && strip.contains(production.BATCH_QUEUE_LINK), strip)
    }

    @Test
    fun batchStripClampsAHostileCount() {
        // A render count beyond the total (or negative) must never print
        // nonsense like "9 of 8" or "-1 of 8".
        assertTrue(production.batchStrip(rendered = 9, total = 8)!!.startsWith("8 of 8"))
        assertTrue(production.batchStrip(rendered = -3, total = 8)!!.startsWith("0 of 8"))
    }

    @Test
    fun templateBatchActionClampsAndPluralises() {
        assertEquals("Make 1 variant", production.templateBatchAction(1))
        assertEquals("Make 3 variants", production.templateBatchAction(3))
        // Above the cap the copy still states a sane count.
        assertEquals(
            "Make ${production.MAX_TEMPLATE_BATCH} variants",
            production.templateBatchAction(production.MAX_TEMPLATE_BATCH + 7),
        )
        assertEquals("Make 1 variant", production.templateBatchAction(0))
    }

    @Test
    fun controlsHaveUniqueIdsAndEveryRowNamesATouchAffordance() {
        val ids = production.CONTROLS.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate control id: $ids")
        // Touch-first: EVERY control must name an on-screen affordance, so a
        // phone with no keyboard can still act on the reference.
        assertTrue(
            production.CONTROLS.all { it.touch.isNotBlank() },
            "a control is touch-less: ${production.CONTROLS.filter { it.touch.isBlank() }}",
        )
        assertTrue(production.CONTROLS.all { it.label.isNotBlank() })
    }

    @Test
    fun boundKeysAreUniqueAndDeriveTheKeyboardList() {
        val bound = production.CONTROLS.mapNotNull { it.keys }
        assertEquals(bound.size, bound.toSet().size, "duplicate key binding: $bound")
        // The keyboard-only SHORTCUTS view is exactly the bound rows.
        assertEquals(bound.size, production.SHORTCUTS.size)
        assertEquals(bound.toSet(), production.SHORTCUTS.map { it.keys }.toSet())
    }

    @Test
    fun theOperatorSetCoversTheCoreActions() {
        val ids = production.CONTROLS.map { it.id }.toSet()
        // MSU-063 names these explicitly — they must all be documented.
        listOf("undo", "redo", "play-pause", "export", "publish").forEach { required ->
            assertTrue(required in ids, "missing control: $required")
        }
    }
}
