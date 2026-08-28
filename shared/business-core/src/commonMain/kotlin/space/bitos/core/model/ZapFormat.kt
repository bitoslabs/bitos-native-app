package space.bitos.core.model

/**
 * APP-014 zap-sheet presentation rules (legacy Flutter `zap_dialog.dart` /
 * web `NoteZapDialog.svelte` parity): the quick-pick presets, the
 * YakiHonne-style amount tiers and the compact sats formatter.
 */
object ZapFormat {

    /** Web-parity quick-pick amounts (`walletPrefs.amounts` default). */
    val PRESETS: List<Int> = listOf(21, 100, 500, 1000)

    /** Amount tier emoji: ⚡ ≤50 · 💜 ≤250 · 🔥 ≤750 · 🚀 above. */
    fun emoji(sats: Long): String = when {
        sats <= 50 -> "⚡"
        sats <= 250 -> "💜"
        sats <= 750 -> "🔥"
        else -> "🚀"
    }

    /** Compact sats label: `999`, `1K`, `1.2K`, `1M`, `1.5M`. */
    fun sats(sats: Long): String = when {
        sats >= 1_000_000 -> trimUnit(sats / 1_000_000.0) + "M"
        sats >= 1_000 -> trimUnit(sats / 1_000.0) + "K"
        else -> sats.toString()
    }

    private fun trimUnit(value: Double): String {
        val tenths = (value * 10).toLong()
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L) whole.toString() else "$whole.$frac"
    }
}
