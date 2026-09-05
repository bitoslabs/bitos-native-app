package space.bitos.core.studio

/**
 * Sound TEMPLATES (web MemeSoundSuggestions parity slice): ready cue
 * layouts — one tap schedules the whole set relative to a base point, the
 * same way the web suggestion chips stage a vibe (laugh track, dramatic
 * beat, cash register). Pure data over [SfxSynth] recipes: no assets, no
 * playback — the native sheets preview each cue with the synth they
 * already use. Templates are ADDITIVE (applied cues join existing ones,
 * capped by [SfxSynth.MAX_CUES] — a template that would overflow is
 * truncated, never silently drops existing cues).
 */
object SfxTemplates {

    /** One staged cue: recipe id + offset from the apply point (ms). */
    data class Cue(val sfx: String, val atMs: Long)

    data class Template(
        val id: String,
        val label: String,
        val emoji: String,
        val cues: List<Cue>,
    )

    /** Bounded catalog — every id must exist in [SfxSynth.RECIPES]. */
    val ALL: List<Template> = listOf(
        Template(
            "laugh-track", "Laugh track", "😂",
            listOf(Cue("crowd-laugh", 0), Cue("laugh", 350), Cue("gasp", 1_200)),
        ),
        Template(
            "dramatic-hit", "Dramatic hit", "💥",
            listOf(Cue("drumroll", 0), Cue("bass-hit", 1_400), Cue("explosion", 1_750)),
        ),
        Template(
            "cash-register", "Cash register", "💰",
            listOf(Cue("cash", 0), Cue("coin", 250), Cue("coin", 500), Cue("jackpot", 900)),
        ),
        Template(
            "awkward", "Awkward…", "🦗",
            listOf(Cue("awkward-silence", 0), Cue("record-scratch", 1_500), Cue("bruh", 1_900)),
        ),
        Template(
            "game-over", "Game over", "🎮",
            listOf(Cue("game-over", 0), Cue("reverse-whoosh", 600)),
        ),
        Template(
            "suspense", "Suspense", "😳",
            listOf(Cue("drumroll", 0), Cue("gasp", 1_800), Cue("sad-trombone", 2_300)),
        ),
        Template(
            "fast-transition", "Fast transition", "💨",
            listOf(Cue("whoosh", 0), Cue("pop", 180), Cue("ding", 400)),
        ),
    )

    /**
     * Stages a template at [atMs]: existing cues stay, template cues land
     * at their offsets, the list is capped at [SfxSynth.MAX_CUES].
     */
    fun apply(existing: List<MemeSfxCue>, templateId: String, atMs: Long): List<MemeSfxCue> {
        val template = ALL.firstOrNull { it.id == templateId } ?: return existing
        val base = atMs.coerceAtLeast(0)
        val staged = template.cues
            .filter { SfxSynth.RECIPES[it.sfx] != null }
            .mapIndexed { index, cue ->
                MemeSfxCue(
                    id = "t${template.id.take(8)}-$index-${base % 1000}",
                    sfx = cue.sfx,
                    atMs = base + cue.atMs,
                    gain = 1f,
                )
            }
        return (existing + staged).take(SfxSynth.MAX_CUES)
    }
}
