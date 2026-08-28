package space.bitos.core.model

/**
 * Notification extraction from verified events (SOC-005). One event maps to
 * at most one notification targeting the account; extraction is bounded and
 * deterministic. Unknown/irrelevant events yield null.
 */
data class NotificationItem(
    val id: String,
    val authorPubkey: String,
    val kind: NotificationKind,
    val targetEventId: String?,
    val summary: String,
    val createdAt: Long,
    /** Zap amount in msat (bolt11 HRP) for ZAP items, else null. */
    val amountMsat: Long? = null,
)

enum class NotificationKind { REPLY, MENTION, REACTION, REPOST, ZAP, FOLLOW }

object NotificationExtractor {

    /**
     * Extracts a notification for [accountPubkey] from a verified event.
     * Returns null when the event does not target the account.
     */
    fun extract(event: NostrEvent, accountPubkey: String): NotificationItem? {
        if (event.pubkey.value == accountPubkey) return null // own events are not notifications
        val tagsTargetAccount = event.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == accountPubkey }
        if (!tagsTargetAccount && event.kind != ZapReceipt.RECEIPT_KIND) return null

        val targetEventId = event.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
        val summary = event.content.trim().let { if (it.length > 120) it.take(119) + "…" else it }

        return when (event.kind) {
            NostrKinds.SHORT_TEXT_NOTE -> NotificationItem(
                id = event.id.value,
                authorPubkey = event.pubkey.value,
                kind = if (targetEventId != null) NotificationKind.REPLY else NotificationKind.MENTION,
                targetEventId = targetEventId,
                summary = summary,
                createdAt = event.createdAt,
            )
            NostrKinds.GENERIC_REACTION -> NotificationItem(
                id = event.id.value,
                authorPubkey = event.pubkey.value,
                kind = NotificationKind.REACTION,
                targetEventId = targetEventId,
                summary = "+", // reaction content is conventionally "+"
                createdAt = event.createdAt,
            )
            NostrKinds.REPOST -> NotificationItem(
                id = event.id.value,
                authorPubkey = event.pubkey.value,
                kind = NotificationKind.REPOST,
                targetEventId = targetEventId,
                summary = "",
                createdAt = event.createdAt,
            )
            NostrKinds.CONTACT_LIST -> NotificationItem(
                // Kind-3 lists are republished; the repositories keep only
                // the newest follow per author (NotificationFilters.shouldKeep).
                id = event.id.value,
                authorPubkey = event.pubkey.value,
                kind = NotificationKind.FOLLOW,
                targetEventId = null,
                summary = "",
                createdAt = event.createdAt,
            )
            ZapReceipt.RECEIPT_KIND -> {
                // Zap receipts target the account via the p tag in the
                // embedded description event; the outer event is from the
                // LNURL server. Only count receipts whose p tag matches.
                val recipient = event.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1)
                if (recipient != accountPubkey) return null
                val sender = ZapReceipt.senderPubkey(event, accountPubkey)
                val amountMsat = ZapReceipt.amountMillisats(event)
                val amountText = amountMsat?.let {
                    if (it % 1_000 == 0L) "⚡ ${it / 1_000} sats" else "⚡ $it msat"
                } ?: "⚡ zap received"
                NotificationItem(
                    id = event.id.value,
                    authorPubkey = sender ?: "", // verified payer when the embedded 9734 checks out
                    kind = NotificationKind.ZAP,
                    targetEventId = targetEventId,
                    summary = amountText,
                    createdAt = event.createdAt,
                    amountMsat = amountMsat,
                )
            }
            else -> null
        }
    }
}
