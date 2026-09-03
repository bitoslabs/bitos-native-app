package space.bitos.core.studio

/**
 * Emoji sticker packs (plan MST-014; web `src/lib/meme/stickers.ts` port,
 * pack-for-pack verbatim). A sticker is deliberately NOT a new schema
 * object: it rides `MemeOverlay` with `kind = STICKER`, a single-grapheme
 * text and no outline — so drafts, templates and (from MST-019) the remix
 * wire payload carry stickers unchanged. Recents are editor-session state
 * (≤ [MAX_RECENT_STICKERS], seeded by first use); they never touch the
 * project wire.
 */
object StickerCatalog {

    data class StickerPack(
        val id: String,
        val label: String,
        val stickers: List<String>,
    )

    /** Curated packs — punchy meme vocabulary, no licensing (system emoji). */
    val PACKS: List<StickerPack> = listOf(
        StickerPack("crypto", "Crypto", listOf("₿", "🚀", "🌕", "⚡", "🟠", "📈", "💎", "🙌")),
        StickerPack("reactions", "Reactions", listOf("😂", "💀", "😈", "🤡", "👀", "🔥", "💯", "🫡")),
        StickerPack("moods", "Moods", listOf("😭", "🥲", "😤", "🤯", "😴", "🤔", "😳", "🥶")),
        StickerPack("money", "Money", listOf("💰", "🤑", "💸", "🪙", "📈", "📉", "🏦", "⚡")),
        StickerPack("chaos", "Chaos", listOf("💥", "🌪️", "🚨", "☠️", "🎮", "🏆", "🧠", "🍄")),
        StickerPack("love", "Love", listOf("❤️", "🧡", "💜", "🖤", "💌", "🫶", "😭", "✨")),
    )

    /** Distinct emoji across all packs (recents seed order, deduped). */
    val ALL: List<String> = PACKS.flatMap { it.stickers }.distinct()

    const val MAX_RECENT_STICKERS = 16

    /**
     * Bounded recents: most-recent-first, deduped, capped. Unknown entries
     * are dropped so a corrupted store can never inject arbitrary text into
     * the sticker grid.
     */
    fun recents(history: List<String>, justUsed: String?): List<String> {
        val valid = ALL.toSet()
        val ordered = mutableListOf<String>()
        if (justUsed != null && justUsed in valid) ordered += justUsed
        history.forEach { sticker ->
            if (sticker in valid && sticker !in ordered) ordered += sticker
        }
        return ordered.take(MAX_RECENT_STICKERS)
    }

    /**
     * Emoji-only text (web `isEmojiOnly`): at least one emoji and no other
     * visible characters. Variation selectors (U+FE0F), ZWJ (U+200D),
     * keycap and skin-tone modifiers are structural and allowed; Bitcoin's
     * ₿ counts as a first-class sticker.
     */
    fun isEmojiOnly(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        var sawEmoji = false
        var index = 0
        while (index < trimmed.length) {
            val first = trimmed[index]
            val codePoint = if (first.isHighSurrogate() && index + 1 < trimmed.length &&
                trimmed[index + 1].isLowSurrogate()
            ) {
                0x10000 + ((first.code - 0xD800) shl 10) + (trimmed[index + 1].code - 0xDC00)
            } else {
                first.code
            }
            index += if (codePoint >= 0x10000) 2 else 1
            when (codePoint) {
                0x200D, 0xFE0F, 0x20E3 -> continue
                // Skin-tone modifiers (structural).
                in 0x1F3FB..0x1F3FF -> continue
                // Regional indicators (flags).
                in 0x1F1E6..0x1F1FF -> sawEmoji = true
                0x20BF -> sawEmoji = true
                0x2196, 0x2197, 0x2198, 0x2199 -> sawEmoji = true
                in 0x2600..0x27BF -> sawEmoji = true
                in 0x2B00..0x2BFF -> sawEmoji = true
                in 0x1F000..0x1FAFF -> sawEmoji = true
                else -> return false
            }
        }
        return sawEmoji
    }
}
