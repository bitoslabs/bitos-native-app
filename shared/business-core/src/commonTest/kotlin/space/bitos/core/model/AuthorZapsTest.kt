package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per-author zap summary (profile "Sats zapped" stat + Zaps tab): the same
 * ledger inputs as the wallet, filtered to one pubkey.
 */
class AuthorZapsTest {

    private val author = "aa".repeat(32)
    private val other = "bb".repeat(32)

    @Test
    fun summaryCountsOnlyThatPubkeysReceivedZaps() {
        val summary = AuthorZaps.summary(
            pubkey = author,
            receivedSats = listOf(100L, 21L, 5L),
            receivedFrom = listOf(author, other, author),
        )
        assertEquals(105L, summary.receivedSats)
        assertEquals(2, summary.receivedCount)
        assertEquals(105L, summary.totalSats)
        assertTrue(summary.hasAny)
    }

    @Test
    fun summaryIncludesViewerSentRecordsForThatPubkey() {
        val sent = listOf(
            SentZapRecord("a", 50L, author, 2_000),
            SentZapRecord("b", 1_000L, other, 1_000),
        )
        val summary = AuthorZaps.summary(
            pubkey = author,
            receivedSats = emptyList(),
            receivedFrom = emptyList(),
            sentRecords = sent,
        )
        assertEquals(50L, summary.sentSats)
        assertEquals(1, summary.sentCount)
        assertEquals(50L, summary.totalSats)
    }

    @Test
    fun emptyInputsYieldZeroSummary() {
        val summary = AuthorZaps.summary(author, emptyList(), emptyList())
        assertEquals(0L, summary.totalSats)
        assertFalse(summary.hasAny)
    }

    @Test
    fun mismatchedParallelArraysNeverCrash() {
        // Defensive: zip truncates to the shorter list.
        val summary = AuthorZaps.summary(
            pubkey = author,
            receivedSats = listOf(10L, 20L, 30L),
            receivedFrom = listOf(author),
        )
        assertEquals(10L, summary.receivedSats)
        assertEquals(1, summary.receivedCount)
    }
}
