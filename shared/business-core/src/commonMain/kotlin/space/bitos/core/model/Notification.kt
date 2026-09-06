package space.bitos.core.model

import space.bitos.core.nostr.Nip27
import space.bitos.core.nostr.RichToken

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

    /** Row-summary bound (chars) — persisted/window schemas stay size-bounded. */
    const val SUMMARY_MAX = 120

    /**
     * Extracts a notification for [accountPubkey] from a verified event.
     * Returns null when the event does not target the account. Classification
     * mirrors the web client: marker-tagged threads are replies (the reply
     * marker wins over the root when they differ), a #p-tagged note without
     * thread markers is a standalone mention whose target is the quoted
     * note (first `e` tag), negative reactions (`-`) never notify, kind-16
     * generic reposts repost, and zap receipts prefer the *second* `e` tag
     * (the first is the zapped note's own reference in most receipts).
     * NIP-22 kind-1111 comments (feed cards, bitz videos) are replies whose
     * deep link is the uppercase `E` root — the commented post — with the
     * lowercase `e` parent (the answered comment) as fallback.
     */
    fun extract(event: NostrEvent, accountPubkey: String): NotificationItem? {
        if (event.pubkey.value == accountPubkey) return null // own events are not notifications
        // NIP-22 comments target the root author via the uppercase `P` tag
        // even when the lowercase `p` participants only name the parent.
        val tagsTargetAccount = event.tags.any { tag ->
            (tag.firstOrNull() == "p" && tag.getOrNull(1) == accountPubkey) ||
                (event.kind == NostrKinds.VIDEO_COMMENT && tag.firstOrNull() == "P" && tag.getOrNull(1) == accountPubkey)
        }
        if (!tagsTargetAccount && event.kind != ZapReceipt.RECEIPT_KIND) return null

        val eventTags = event.tags.filter { it.firstOrNull() == "e" && !it.getOrNull(1).isNullOrEmpty() }
        val summary = plainSummary(event.content)

        return when (event.kind) {
            NostrKinds.SHORT_TEXT_NOTE -> {
                val target = replyTarget(eventTags)
                if (target != null) {
                    NotificationItem(
                        id = event.id.value,
                        authorPubkey = event.pubkey.value,
                        kind = NotificationKind.REPLY,
                        targetEventId = target,
                        summary = summary,
                        createdAt = event.createdAt,
                    )
                } else {
                    // Standalone mention; a quoted note (plain `e` tag, no
                    // thread marker) becomes the deep link.
                    NotificationItem(
                        id = event.id.value,
                        authorPubkey = event.pubkey.value,
                        kind = NotificationKind.MENTION,
                        targetEventId = eventTags.firstOrNull()?.get(1),
                        summary = summary,
                        createdAt = event.createdAt,
                    )
                }
            }
            NostrKinds.VIDEO_COMMENT -> NotificationItem(
                id = event.id.value,
                authorPubkey = event.pubkey.value,
                kind = NotificationKind.REPLY,
                targetEventId = event.tag("E").getOrNull(1)
                    ?: eventTags.firstOrNull()?.get(1),
                summary = summary,
                createdAt = event.createdAt,
            )
            NostrKinds.GENERIC_REACTION -> {
                if (event.content.trim() == "-") return null // negative reaction: never a like
                NotificationItem(
                    id = event.id.value,
                    authorPubkey = event.pubkey.value,
                    kind = NotificationKind.REACTION,
                    targetEventId = replyTarget(eventTags) ?: eventTags.firstOrNull()?.get(1),
                    summary = summary.ifEmpty { "+" },
                    createdAt = event.createdAt,
                )
            }
            NostrKinds.REPOST, NostrKinds.GENERIC_REPOST -> NotificationItem(
                id = event.id.value,
                authorPubkey = event.pubkey.value,
                kind = NotificationKind.REPOST,
                targetEventId = repostTarget(eventTags, event.content),
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
                    // Receipts commonly carry the request's own `e` first;
                    // the zapped note is the second (web parity).
                    targetEventId = eventTags.getOrNull(1)?.get(1) ?: eventTags.firstOrNull()?.get(1),
                    summary = amountText,
                    createdAt = event.createdAt,
                    amountMsat = amountMsat,
                )
            }
            else -> null
        }
    }

    /**
     * Plain-text summary shown in list rows: links (media included) and
     * nostr entities are stripped — the platform renders media as tiles, so
     * a raw `https://…/img1.gif` must never surface as row text. Same token
     * rule as `OriginNotes` excerpts; hashtags survive, output is bounded.
     */
    private fun plainSummary(content: String): String {
        val builder = StringBuilder()
        for (token in Nip27.tokenize(content)) {
            when (token) {
                is RichToken.Link -> Unit // URLs render as media/tiles, not text
                is RichToken.Nostr -> Unit // entity text dropped; names need profile data
                is RichToken.Hashtag -> builder.append('#').append(token.tag).append(' ')
                is RichToken.Text -> builder.append(token.value)
            }
        }
        return builder.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { if (it.length > SUMMARY_MAX) it.take(SUMMARY_MAX - 1) + "…" else it }
    }

    /** Thread-targeted reply detection: `e` tags carrying root/reply markers. */
    private fun replyTarget(eventTags: List<List<String>>): String? {
        val root = eventTags.firstOrNull { it.getOrNull(3) == "root" }?.get(1)
            ?: eventTags.firstOrNull()?.get(1)
        val reply = eventTags.firstOrNull { it.getOrNull(3) == "reply" }?.get(1)
        return if (reply != null && reply != root) reply else root?.takeIf { hasThreadMarker(eventTags) }
    }

    private fun hasThreadMarker(eventTags: List<List<String>>): Boolean =
        eventTags.any { it.getOrNull(3) == "reply" || it.getOrNull(3) == "root" }

    /** Generic reposts embed the target as JSON; fall back to the first `e`. */
    private fun repostTarget(eventTags: List<List<String>>, content: String): String? {
        if (eventTags.isNotEmpty()) return eventTags.first().get(1)
        val embedded = content.trim().removePrefix("\"").removeSuffix("\"")
        return embedded.substringAfter("\"id\":\"", missingDelimiterValue = "")
            .substringBefore('"')
            .takeIf { it.length == 64 }
    }
}
