package space.bitos.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APP-009 live tallies: merge rules per kind (7 reactions, 6 reposts,
 * 9735 zaps + summed msat), unknown kinds no-op, eviction bound.
 */
class NoteTallyTest {

    @Test
    fun mergesEachKindIntoItsOwnCounter() {
        var tally: NoteTally? = null
        tally = NoteTallies.merge(tally, 7)
        tally = NoteTallies.merge(tally, 7)
        tally = NoteTallies.merge(tally, 6)
        tally = NoteTallies.merge(tally, 9_735, amountMillisats = 21_000)
        tally = NoteTallies.merge(tally, 9_735, amountMillisats = 1_000_000)
        assertEquals(NoteTally(reactions = 2, reposts = 1, zaps = 2, zapMillisats = 1_021_000), tally)
    }

    @Test
    fun unknownKindsAreNoOpsAndMissingAmountsAddZero() {
        var tally = NoteTallies.merge(null, 1)
        tally = NoteTallies.merge(tally, 3)
        tally = NoteTallies.merge(tally, 9_735)
        assertEquals(NoteTally(zaps = 1, zapMillisats = 0), tally)
    }

    @Test
    fun evictionKeepsTheNewestWindow() {
        val keys = LinkedHashSet<Int>()
        for (key in 0 until NoteTallies.MAX_TRACKED_NOTES + 5) NoteTallies.evict(keys, key)
        assertEquals(NoteTallies.MAX_TRACKED_NOTES, keys.size)
        assertTrue(keys.last() == NoteTallies.MAX_TRACKED_NOTES + 4)
        // The oldest five were evicted.
        assertTrue(4 !in keys)
        assertTrue(5 in keys)
    }
}
