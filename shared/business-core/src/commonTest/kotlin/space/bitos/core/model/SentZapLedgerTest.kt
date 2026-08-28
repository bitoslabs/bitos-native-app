package space.bitos.core.model

import space.bitos.core.identity.DeterministicTestSigner
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-014 sent-zap ledger: versioned bounded wire, dedupe/newest-first
 * insert rule, sent+received merge and stat totals.
 */
class SentZapLedgerTest {

    private fun record(
        id: String = "r1",
        sats: Long = 21,
        to: String = "aa".repeat(32),
        at: Long = 1_000,
        note: String? = null,
        memo: String? = null,
    ) = SentZapRecord(id, sats, to, at, note, memo)

    @Test
    fun roundTripsThroughTheWire() {
        val records = listOf(
            record(id = "a", sats = 21, at = 2_000, note = "n1", memo = "great"),
            record(id = "b", sats = 1_000, at = 1_000),
        )
        assertEquals(records, SentZapLedger.decode(SentZapLedger.encode(records)))
    }

    @Test
    fun corruptOrWrongVersionWireYieldsEmpty() {
        assertTrue(SentZapLedger.decode("{nope").isEmpty())
        assertTrue(SentZapLedger.decode("""{"v":2,"records":[]}""").isEmpty())
        assertTrue(SentZapLedger.decode("x".repeat(SentZapLedger.MAX_WIRE_LENGTH + 1)).isEmpty())
        // Invalid rows drop; valid rows survive.
        val mixed = """{"v":1,"records":[
            {"id":"","sats":21,"to":"aa","at":1},
            {"id":"ok","sats":21,"to":"aa","at":2},
            {"id":"bad-sats","sats":-5,"to":"aa","at":3}
        ]}"""
        assertEquals(listOf("ok"), SentZapLedger.decode(mixed).map { it.id })
    }

    @Test
    fun insertDedupesSortsNewestFirstAndBounds() {
        val base = (1..SentZapLedger.MAX_RECORDS).map { record(id = "r$it", at = it.toLong()) }
        val next = SentZapLedger.withRecord(base, record(id = "r1", sats = 999, at = 5_000))
        assertEquals(SentZapLedger.MAX_RECORDS, next.size) // dedupe, not +1
        assertEquals(999, next.first { it.id == "r1" }.amountSats)
        assertEquals(5_000, next.first().createdAt) // newest first
    }

    @Test
    fun ledgerMergesSentAndReceivedNewestFirst() {
        val sent = listOf(record(id = "s1", sats = 100, to = "peer-a", at = 3_000, memo = "ty"))
        val entries = SentZapLedger.ledger(
            sent = sent,
            receivedSats = listOf(500, 21),
            receivedFrom = listOf("peer-b", "peer-c"),
            receivedAt = listOf(4_000L, 1_000L),
            receivedNote = listOf("note-1", null),
        )
        assertEquals(listOf(4_000L, 3_000L, 1_000L), entries.map { it.createdAt })
        assertEquals(
            listOf(SentZapLedger.Direction.RECEIVED, SentZapLedger.Direction.SENT, SentZapLedger.Direction.RECEIVED),
            entries.map { it.direction },
        )
        assertEquals("ty", entries[1].memo)
        assertEquals("note-1", entries[0].targetNoteId)
    }

    @Test
    fun totalsComputeReceivedSentAverageAndNet() {
        val entries = listOf(
            SentZapLedger.LedgerEntry(SentZapLedger.Direction.RECEIVED, 500, "a", 1),
            SentZapLedger.LedgerEntry(SentZapLedger.Direction.RECEIVED, 100, "a", 2),
            SentZapLedger.LedgerEntry(SentZapLedger.Direction.SENT, 21, "b", 3),
        )
        val totals = SentZapLedger.totals(entries)
        assertEquals(600, totals.receivedSats)
        assertEquals(21, totals.sentSats)
        assertEquals(300, totals.averageSats)
        assertEquals(579, totals.netSats)
    }

    /** Paid-matching: the receipt's embedded 9734 id is the request id. */
    @Test
    fun embeddedRequestIdMatchesTheSignedZapRequest() {
        val recipient = "aa".repeat(32)
        val payer = DeterministicTestSigner("0".repeat(63) + "1", Sha256EventHasher)
        val composer = NoteComposer(Sha256EventHasher, { 1_700_000_000L })
        val unsigned = composer.composeZapRequest(
            recipientPubkey = recipient,
            amountMillisats = 21_000,
            relays = emptyList(),
            lnurlHint = "pay@example.com",
            comment = "nice",
            authorPubkey = payer.publicKeyHex(),
            targetEventId = null,
        )!!
        val signature = kotlinx.coroutines.runBlocking { payer.sign(unsigned.messageBytes()) }!!
        val requestJson = composer.publishMessage(unsigned, signature)!!
        val receiptJson = """["EVENT","z",{"kind":9735,"created_at":1700000100,"tags":[["p","$recipient"],["description",${'"'.toString()}${requestJson.substringAfter(",").dropLast(1)}${'"'.toString()}]],"content":"","pubkey":"$recipient","id":"${"1".repeat(64)}","sig":"${"2".repeat(128)}"}]"""
        val receipt = NostrEventCodec.decodeRelayEvent(
            Sha256EventHasher, receiptJson, RelayUrl.parse("wss://relay.test")!!,
        )
        // The embedded request id is the canonical 9734 id we composed.
        assertEquals(unsigned.idHex, receipt?.let { ZapReceipt.embeddedRequestId(it) })
        // Non-receipt kinds never carry one.
        assertNull(ZapReceipt.embeddedRequestId(receipt!!.copy(kind = 1)))
    }
}
