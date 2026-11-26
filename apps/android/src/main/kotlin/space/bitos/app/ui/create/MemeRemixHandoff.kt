package space.bitos.app.ui.create

import space.bitos.app.ui.create.meme.MemeRemixSeed
import space.bitos.core.feed.FeedNote
import space.bitos.core.feed.RemixRules

/**
 * One mapper from a VERIFIED [FeedNote] to the editor/composer handoffs
 * (MEM-005b): the Bitz card's Remix action and the Create-hub remix
 * source picker build the seed through HERE, never their own copies —
 * the precedence (video imeta → inline media), the relay hints and the
 * attribution tags stay single-sourced per platform. The deterministic
 * rules themselves live in shared `RemixRules`/`RemixPreflight`.
 */
object MemeRemixHandoff {

    /**
     * The editor handoff: source media, `meme` layout payload, relay hints
     * and the author label for the attribution credit. Null when nothing
     * loadable exists (callers offer the credited-post fallback).
     */
    fun editorSeed(
        note: FeedNote,
        creatorLabel: String,
        writeRelays: List<String>,
    ): MemeRemixSeed? {
        val mediaUrl = space.bitos.core.studio.RemixPreflight.mediaUrlOf(note) ?: return null
        return MemeRemixSeed(
            eventId = note.id,
            pubkey = note.pubkey,
            label = creatorLabel,
            relays = RemixRules.relayHints(note.remixRelays, writeRelays),
            mediaUrl = mediaUrl,
            isVideo = note.video != null,
            memeTag = note.memeTag,
        )
    }

    /** Seeded composer tags for the no-media credited-post fallback. */
    fun creditedPostTags(note: FeedNote, creatorLabel: String): List<List<String>> =
        RemixRules.tagsFor(note.id, note.pubkey) + listOfNotNull(
            RemixRules.attributionTag(creatorLabel),
        )
}
