package space.bitos.core.model

/**
 * Per-author zap summary derived from the same ledger inputs the zap wallet
 * uses (local sent records + verified received kind-9735 receipts).
 *
 * Web full-page profile parity: the stats row shows a truthful
 * "Sats zapped" figure and the Zaps tab lists per-peer activity. For the
 * own profile both directions count; for an author page only the sats the
 * world zapped that author (received) are attributable from relays.
 */
object AuthorZaps {

    /** Aggregated zap facts for one pubkey. */
    data class Summary(
        val receivedSats: Long,
        val receivedCount: Int,
        val sentSats: Long,
        val sentCount: Int,
    ) {
        /** Web label parity: everything this profile zapped + received. */
        val totalSats: Long get() = receivedSats + sentSats
        val hasAny: Boolean get() = receivedCount > 0 || sentCount > 0
    }

    /**
     * Received-side summary for one pubkey from parallel notification
     * arrays (the shape [SentZapLedger.ledger] consumes). Local sent
     * records only apply to the wallet owner, so they are optional.
     */
    fun summary(
        pubkey: String,
        receivedSats: List<Long>,
        receivedFrom: List<String>,
        sentRecords: List<SentZapRecord> = emptyList(),
    ): Summary {
        val received = receivedSats.zip(receivedFrom).filter { it.second == pubkey }
        val sent = sentRecords.filter { it.recipientPubkey == pubkey }
        return Summary(
            receivedSats = received.sumOf { it.first },
            receivedCount = received.size,
            sentSats = sent.sumOf { it.amountSats },
            sentCount = sent.size,
        )
    }
}
