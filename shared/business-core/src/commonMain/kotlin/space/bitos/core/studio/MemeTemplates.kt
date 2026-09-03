package space.bitos.core.studio

/**
 * Built-in template pack (plan MST-040 / wave 1: "templates rail seeded
 * from a first built-in pack"). Templates are overlay recipes — applying
 * one clones its overlays with FRESH ids onto the current project (web
 * `shared-templates` apply semantics: a template never mutates history).
 * Marketplace/zap-priced shared templates (kind 30078, wave 3) are a
 * different source feeding the same apply path.
 */
data class MemeTemplate(
    val id: String,
    val label: String,
    val emoji: String,
    val overlays: List<MemeOverlay>,
)

object MemeTemplates {

    const val MAX_PACK_SIZE = 24

    private fun text(
        text: String,
        y: Float,
        size: Int = MemeRules.DEFAULT_TEXT_SIZE,
        font: MemeFontSlot = MemeFontSlot.IMPACT,
        colorIndex: Int = 0,
        outline: Int = 3,
    ) = MemeOverlay(
        id = "", // re-seeded on apply
        kind = MemeOverlayKind.TEXT,
        text = text,
        font = font,
        size = size,
        colorIndex = colorIndex,
        outline = outline,
        shadow = false,
        x = 0.5f,
        y = y,
        scale = 1f,
        rotationDeg = 0f,
    )

    /** The first built-in pack (web `meme-studio-config` classics). */
    val PACK: List<MemeTemplate> = listOf(
        MemeTemplate("classic", "Classic", "🖼", listOf(text("TOP TEXT", 0.12f), text("BOTTOM TEXT", 0.86f))),
        MemeTemplate("zap-receipt", "Zap Receipt", "⚡", listOf(text("zap {name}", 0.14f), text("{sats} sats", 0.86f))),
        MemeTemplate("fee-market", "Fee Market", "⛏", listOf(text("WHEN THE FEE MARKET", 0.12f), text("OPENS", 0.86f))),
        MemeTemplate("hold", "Hold", "🤲", listOf(text("me explaining why", 0.12f, font = MemeFontSlot.SANS), text("i'm still holding", 0.86f, font = MemeFontSlot.SANS))),
        MemeTemplate("number-go-up", "Number Go Up", "📈", listOf(text("NUMBER GO UP", 0.12f, colorIndex = 2), text("(it went down)", 0.86f, colorIndex = 1))),
        MemeTemplate("sticker-btc", "Bitcoin Sticker", "₿", listOf(text("₿", 0.5f, size = MemeRules.DEFAULT_STICKER_SIZE, outline = 0), text("in satoshis we trust", 0.9f))),
    )

    fun templateOf(id: String?): MemeTemplate? = PACK.firstOrNull { it.id == id }

    /**
     * Applies a template: overlays clone with fresh deterministic ids onto
     * the project (replacing existing overlays), keeping mode/assets and
     * every other field. Unknown id → the project unchanged.
     */
    fun apply(project: MemeProject, templateId: String?): MemeProject {
        val template = templateOf(templateId) ?: return project
        var seed = 0
        val cloned = template.overlays.take(MemeProjectContract.MAX_OVERLAYS).map { overlay ->
            seed += 1
            overlay.copy(id = "t$seed-${template.id.take(12)}")
        }
        return project.copy(overlays = cloned)
    }
}
