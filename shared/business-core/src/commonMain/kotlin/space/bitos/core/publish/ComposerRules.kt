package space.bitos.core.publish

import space.bitos.core.identity.NostrKeyCodec
import space.bitos.core.model.ProfileMetadata
import space.bitos.core.nostr.Nip27
import space.bitos.core.nostr.RichToken

/**
 * APP-008 composer rules (legacy Flutter `create_controller` / web Composer
 * parity): the deterministic product rules behind the note composer page —
 * character counting (4,000 soft / 16,000 hard), toolbar text inserts,
 * @-mention autocomplete + NIP-27 publish rewrite, media content join and
 * tag derivation (hashtags, inline nostr: entities, content warning).
 * Natives own presentation only.
 */
object ComposerRules {

    /** Web parity: counter target. */
    const val SOFT_LIMIT: Int = 4_000

    /** Web parity: posting is blocked beyond the hard limit. */
    const val HARD_LIMIT: Int = 16_000

    /** Legacy media cap (picks + URLs together). */
    const val MAX_IMAGES: Int = 4

    /** Web `candidates` parity: autocomplete caps at 6 suggestions. */
    const val MENTION_SUGGESTION_CAP: Int = 6

    /** Web COMPOSER_EMOJIS parity (32 quick emoji). */
    val COMPOSER_EMOJIS: List<String> = listOf(
        "₿", "⚡", "🚀", "🌈", "🌊", "🌟", "🌸",
        "❤️", "🔥", "✨", "🎉", "💯", "💩", "🐮",
        "😀", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎",
        "🤔", "🥳", "😴", "🤯", "🥺", "😭", "😢", "😡",
        "👍", "👎", "👏", "🙌", "🙏", "💪", "🫂", "👀",
    )

    // ── Character counter (progress-ring presentation rule) ──────────

    data class CounterState(
        /** `x / 4,000` grouped label; target switches at the soft limit. */
        val label: String,
        /** Fill ratio 0..1 (clamped). */
        val ratio: Float,
        val remaining: Int,
        val near: Boolean,
        val over: Boolean,
    )

    fun counterState(length: Int): CounterState {
        val target = if (length <= SOFT_LIMIT) SOFT_LIMIT else HARD_LIMIT
        val ratio = if (target <= 0) 0f else (length.toFloat() / target).coerceIn(0f, 1f)
        val over = length > HARD_LIMIT
        val near = !over && length > SOFT_LIMIT * 0.8f
        return CounterState(
            label = "${grouped(length.coerceAtLeast(0))} / ${grouped(target)}",
            ratio = ratio,
            remaining = (target - length),
            near = near,
            over = over,
        )
    }

    private fun grouped(value: Int): String = value.toString().replace(Regex("\\B(?=(\\d{3})+(?!\\d))"), ",")

    // ── Toolbar inserts (cursor-preserving, web parity) ─────────────

    /** One-tap `#` insertion with the leading-space rule. */
    fun insertHashtag(text: String, cursor: Int): Pair<String, Int> {
        val offset = cursor.coerceIn(0, text.length)
        val before = text.substring(0, offset)
        val after = text.substring(offset)
        val inserted = if (before.isNotEmpty() && !before.endsWith(' ') && !before.endsWith('\n')) " #" else "#"
        return (before + inserted + after) to (offset + inserted.length)
    }

    /** Emoji insertion with the leading-space rule (unless after newline). */
    fun insertEmoji(text: String, cursor: Int, emoji: String): Pair<String, Int> {
        val offset = cursor.coerceIn(0, text.length)
        val before = text.substring(0, offset)
        val after = text.substring(offset)
        val needsSpace = before.isNotEmpty() && !before.endsWith(' ') && !before.endsWith('\n')
        val inserted = (if (needsSpace) " " else "") + emoji
        return (before + inserted + after) to (offset + inserted.length)
    }

    // ── Mention autocomplete (web `mention` state parity) ───────────

    /** Trailing `@query` at the cursor, or null when not composing one. */
    fun mentionQueryAt(text: String, cursor: Int): String? {
        if (cursor < 0 || cursor > text.length) return null
        val match = Regex("@([\\w.]*)$").find(text.substring(0, cursor)) ?: return null
        return match.groupValues[1]
    }

    data class MentionSuggestion(
        val name: String,
        val pubkeyHex: String,
        val npub: String,
        val pictureUrl: String?,
    )

    /** Known-profile candidates matching [query] (case-insensitive), ≤ cap. */
    fun mentionSuggestions(
        query: String,
        profiles: Collection<ProfileMetadata>,
        cap: Int = MENTION_SUGGESTION_CAP,
    ): List<MentionSuggestion> {
        val needle = query.trim().lowercase()
        val out = mutableListOf<MentionSuggestion>()
        for (profile in profiles) {
            val name = profile.bestDisplayName.trim()
            if (name.isEmpty()) continue
            if (needle.isNotEmpty() && !name.lowercase().contains(needle)) continue
            val npub = NostrKeyCodec.npub(profile.pubkey.value) ?: continue
            out += MentionSuggestion(name, profile.pubkey.value, npub, profile.picture)
            if (out.size >= cap) break
        }
        return out
    }

    /**
     * Publish-time rewrite (web `rewriteMentions`): tracked `@Name` picks
     * become `nostr:npub…` NIP-27 entities so relays and clients resolve
     * the mention from the content itself.
     */
    fun rewriteMentions(content: String, tracked: List<Pair<String, String>>): String {
        var out = content
        for ((name, npub) in tracked) {
            if (name.isBlank()) continue
            out = out.replace("@$name", "nostr:$npub")
        }
        return out
    }

    // ── Content assembly + tag derivation ────────────────────────────

    /** Final content: trimmed text, one media URL per line appended. */
    fun composeContent(text: String, mediaUrls: List<String>): String {
        val trimmed = text.trim()
        return buildString {
            append(trimmed)
            for (url in mediaUrls) {
                if (isNotEmpty()) append('\n')
                append(url)
            }
        }
    }

    /**
     * Tags derived from the final content (never hand-tracked): `t` per
     * distinct lowercase hashtag; `p`/`e` per inline NIP-27 profile/note
     * entity; `a` per naddr coordinate (NIP-33); the NIP-36 tag when a
     * content warning is active.
     */
    fun deriveTags(content: String, contentWarningReason: String? = null): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        val seenHashtags = HashSet<String>()
        Regex("#(\\w+)").findAll(content).forEach { match ->
            val tag = match.groupValues[1].lowercase()
            if (seenHashtags.add(tag)) tags += listOf("t", tag)
        }
        val seenPubkeys = HashSet<String>()
        val seenEvents = HashSet<String>()
        val seenCoordinates = HashSet<String>()
        for (token in Nip27.tokenize(content)) {
            if (token !is RichToken.Nostr) continue
            when (token.entity) {
                RichToken.Entity.PROFILE ->
                    if (token.hex != null && seenPubkeys.add(token.hex)) tags += listOf("p", token.hex)
                RichToken.Entity.NOTE ->
                    if (token.hex != null && seenEvents.add(token.hex)) tags += listOf("e", token.hex)
                RichToken.Entity.ADDRESS ->
                    if (token.coordinate != null && seenCoordinates.add(token.coordinate)) {
                        tags += listOf("a", token.coordinate)
                    }
            }
        }
        if (!contentWarningReason.isNullOrBlank()) {
            tags += listOf("content-warning", contentWarningReason.trim())
        }
        return tags
    }
}
