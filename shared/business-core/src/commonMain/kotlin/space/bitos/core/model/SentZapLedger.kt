package space.bitos.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * APP-014 sent-zap ledger (legacy Flutter `SentZapsStore` / web
 * `wallet.recordSent` parity): sent zaps can never be observed from relays
 * (kind-9735 is published by the *recipient's* Lightning provider), so the
 * sent side of the ledger is a local device record. Versioned, bounded,
 * dedupe-by-request-id; received zaps come from the verified notification
 * stream and merge at display time.
 */
data class SentZapRecord(
    /** Zap-request (kind-9734) event id — dedupe + receipt match key. */
    val id: String,
    val amountSats: Long,
    val recipientPubkey: String,
    val createdAt: Long,
    val targetNoteId: String? = null,
    val memo: String? = null,
)

object SentZapLedger {

    const val SCHEMA_VERSION = 1

    /** Legacy store bound. */
    const val MAX_RECORDS = 200

    private const val MAX_MEMO = 200
    private const val MAX_ID = 64
    private const val MAX_SATS = 100_000_000_000L // 100k BTC sanity bound

    /** Bounded wire: 200 × (~64+64+200) + headroom. */
    const val MAX_WIRE_LENGTH = 98_304

    private val lenientJson = Json { ignoreUnknownKeys = true }

    fun encode(records: List<SentZapRecord>): String = buildJsonObject {
        put("v", SCHEMA_VERSION)
        put("records", buildJsonArray {
            normalized(records).forEach { record ->
                add(buildJsonObject {
                    put("id", record.id.take(MAX_ID))
                    put("sats", record.amountSats)
                    put("to", record.recipientPubkey.take(MAX_ID))
                    put("at", record.createdAt)
                    record.targetNoteId?.let { put("note", it.take(MAX_ID)) }
                    record.memo?.let { put("memo", it.take(MAX_MEMO)) }
                })
            }
        })
    }.toString()

    /** Lenient decode: corrupt/oversized → empty; bounds clamp. */
    fun decode(json: String): List<SentZapRecord> {
        if (json.length > MAX_WIRE_LENGTH) return emptyList()
        return try {
            val root = lenientJson.parseToJsonElement(json).jsonObject
            if ((root["v"] as? JsonPrimitive)?.content?.toIntOrNull() != SCHEMA_VERSION) return emptyList()
            val out = LinkedHashMap<String, SentZapRecord>()
            root["records"]?.jsonArray?.forEach { element ->
                val obj = element.jsonObject
                val id = (obj["id"] as? JsonPrimitive)?.content?.take(MAX_ID) ?: return@forEach
                val sats = (obj["sats"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@forEach
                val to = (obj["to"] as? JsonPrimitive)?.content?.take(MAX_ID) ?: return@forEach
                val at = (obj["at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@forEach
                if (id.isBlank() || sats !in 1..MAX_SATS || at <= 0) return@forEach
                if (out.containsKey(id)) return@forEach // dedupe, first wins
                out[id] = SentZapRecord(
                    id = id,
                    amountSats = sats,
                    recipientPubkey = to,
                    createdAt = at,
                    targetNoteId = (obj["note"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
                    memo = (obj["memo"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.take(MAX_MEMO),
                )
            }
            normalized(out.values.toList())
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Insert rule (legacy `record`): dedupe by id, newest first, ≤ bound. */
    fun withRecord(existing: List<SentZapRecord>, record: SentZapRecord): List<SentZapRecord> =
        normalized(listOf(record) + existing.filter { it.id != record.id })

    private fun normalized(records: List<SentZapRecord>): List<SentZapRecord> =
        records.distinctBy { it.id }
            .sortedByDescending { it.createdAt }
            .take(MAX_RECORDS)

    // ── Display ledger: merge local sent + relay received ───────────

    enum class Direction { RECEIVED, SENT }

    data class LedgerEntry(
        val direction: Direction,
        val sats: Long,
        val peerPubkey: String,
        val createdAt: Long,
        val memo: String? = null,
        val targetNoteId: String? = null,
    )

    /**
     * Merges the local [sent] records with verified received zaps
     * ([receivedSats]/[receivedFrom]/[receivedAt]/[receivedNote] parallel
     * arrays, the shape the notification stream produces). Newest first.
     */
    fun ledger(
        sent: List<SentZapRecord>,
        receivedSats: List<Long>,
        receivedFrom: List<String>,
        receivedAt: List<Long>,
        receivedNote: List<String?>,
    ): List<LedgerEntry> {
        val sentEntries = sent.map {
            LedgerEntry(Direction.SENT, it.amountSats, it.recipientPubkey, it.createdAt, it.memo, it.targetNoteId)
        }
        val receivedEntries = receivedSats.indices.mapNotNull { index ->
            val sats = receivedSats.getOrNull(index) ?: return@mapNotNull null
            if (sats <= 0) return@mapNotNull null
            LedgerEntry(
                Direction.RECEIVED,
                sats,
                receivedFrom.getOrNull(index) ?: return@mapNotNull null,
                receivedAt.getOrNull(index) ?: 0L,
                null,
                receivedNote.getOrNull(index),
            )
        }
        return (sentEntries + receivedEntries).sortedByDescending { it.createdAt }
    }

    data class Totals(val receivedSats: Long, val sentSats: Long, val averageSats: Long, val netSats: Long)

    fun totals(entries: List<LedgerEntry>): Totals {
        val received = entries.filter { it.direction == Direction.RECEIVED }.sumOf { it.sats }
        val sent = entries.filter { it.direction == Direction.SENT }.sumOf { it.sats }
        val receivedCount = entries.count { it.direction == Direction.RECEIVED }
        return Totals(
            receivedSats = received,
            sentSats = sent,
            averageSats = if (receivedCount == 0) 0 else received / receivedCount,
            netSats = received - sent,
        )
    }
}
