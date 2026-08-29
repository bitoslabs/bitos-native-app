package space.bitos.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import space.bitos.core.feed.FeedNote

/**
 * Three-slot playback coordinator (FED-002).
 *
 * At most the visible note and its immediate neighbors hold players; only
 * the visible note plays. Slots are keyed by verified event id, so a
 * recomposed page always rebinds by stable identity — a stale page holding a
 * released id simply gets null (the token/lease protection from the
 * architecture doc, expressed by id-keyed lifetimes).
 *
 * Owned by the feed surface composition; releaseAll on dispose. Not a
 * service locator: exactly one pool exists per feed surface.
 *
 * APP-018 functional settings: [canAutoplay] applies the persisted media
 * autoplay policy (always / unmetered-wifi / never), [rateProvider] the
 * persisted playback-rate step and [mutedProvider] the persisted mute
 * memory (APP-007) — all read live so a settings change takes effect on the
 * next reconciliation without rebuilding the pool.
 *
 * Reconciliation is list-based: the caller passes the exact paged note list
 * so a filtered surface (Bitz videos) never resolves neighbor slots against
 * an unfiltered window.
 *
 * FED-004 rendition + failover: each slot resolves its media through
 * [MediaSources.forNote] — the shared rendition pick (tallest fitting the
 * display, ±25% headroom, fallback to the primary URL) plus the imeta
 * mirror chain. An item error walks that candidate list in order; primary
 * first, then mirrors, then lower renditions — mirroring the web's
 * `resolveVideoUrl` failover without ABR (a static pick per slot).
 */
class VideoPlayerPool(
    private val context: Context,
    private val canAutoplay: () -> Boolean = { true },
    private val rateProvider: () -> Float = { 1f },
    private val mutedProvider: () -> Boolean = { false },
) {

    private val players = LinkedHashMap<String, ExoPlayer>()

    /**
     * FED-004: candidate URLs per note, in failover order. The primary
     * rendition pick leads; remaining entries are walked only on player
     * error. Bounded by the shared parser (≤8 mirrors, ≤8 renditions).
     */
    private val mediaSources = HashMap<String, MediaSources>()

    private var failoverSlot: String? = null

    /** Reconcile slots with [visibleIndex] ± 1 in [notes] (the paged list). */
    fun update(visibleIndex: Int, notes: List<FeedNote>) {
        if (visibleIndex !in notes.indices) {
            players.values.forEach { it.playWhenReady = false }
            return
        }
        val keep = buildList {
            for (index in (visibleIndex - 1)..(visibleIndex + 1)) {
                if (index in notes.indices) add(notes[index])
            }
        }.filter { it.video != null }

        // Release slots outside the window.
        players.keys.toList().forEach { id ->
            if (keep.none { it.id == id }) {
                players.remove(id)?.release()
                mediaSources.remove(id)
                if (failoverSlot == id) failoverSlot = null
            }
        }
        // Create missing slots (bounded to keep.size <= 3).
        val muted = mutedProvider()
        keep.forEach { note ->
            if (note.id !in players) {
                val sources = MediaSources.forNote(note)
                mediaSources[note.id] = sources
                players[note.id] = ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(sources.current()))
                    prepare()
                    addListener(failoverListener(note.id))
                }.apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = if (muted) 0f else 1f
                }
            }
        }
        // Exactly the visible video plays — gated by the autoplay policy.
        val autoplay = canAutoplay()
        val rate = rateProvider()
        val visibleId = notes[visibleIndex].id
        players.forEach { (id, player) ->
            player.playWhenReady = id == visibleId && autoplay
            if (player.playbackParameters.speed != rate) {
                player.setPlaybackSpeed(rate)
            }
            player.volume = if (muted) 0f else 1f
        }
    }

    /**
     * FED-004: on item error, advance to the next candidate URL (mirror or
     * lower rendition) and re-prepare. Marked `@Volatile`-free — all pool
     * entry points are called from the main thread (composition + ExoPlayer
     * application-thread callbacks).
     */
    private fun failoverListener(noteId: String) = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val sources = mediaSources[noteId] ?: return
            val next = sources.advance() ?: return
            players[noteId]?.let { player ->
                failoverSlot = noteId
                player.setMediaItem(MediaItem.fromUri(next), 0L)
                player.prepare()
                if (player.playWhenReady) player.play()
            }
        }
    }

    fun playerFor(noteId: String): ExoPlayer? = players[noteId]

    /** Per-slot temporary rate multiplier (long-press 2× fast-forward);
     *  null restores the persisted playback-rate setting. */
    private val rateOverrides = HashMap<String, Float>()

    fun setRateOverride(noteId: String, multiplier: Float?) {
        val player = players[noteId] ?: return
        if (multiplier == null) {
            rateOverrides.remove(noteId)
            player.setPlaybackSpeed(rateProvider())
        } else {
            rateOverrides[noteId] = multiplier
            player.setPlaybackSpeed(rateProvider() * multiplier)
        }
    }

    /** Apply the mute memory immediately (settings change, not just at reconcile). */
    fun applyMuted(muted: Boolean) {
        players.values.forEach { it.volume = if (muted) 0f else 1f }
    }

    fun togglePlay(noteId: String) {
        players[noteId]?.let { player ->
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    /** Seek [noteId]'s slot by [deltaMs], clamped to the prepared media. */
    fun seekBy(noteId: String, deltaMs: Long) {
        val player = players[noteId] ?: return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, duration))
    }

    /** Absolute seek for the scrubber; 0 when media is unbounded. */
    fun seekTo(noteId: String, positionMs: Long) {
        val player = players[noteId] ?: return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo(positionMs.coerceIn(0L, duration))
    }

    fun positionMs(noteId: String): Long = players[noteId]?.currentPosition ?: 0L

    /** Prepared media duration; 0 while unknown/unbounded (live-ish sources). */
    fun durationMs(noteId: String): Long =
        players[noteId]?.duration?.takeIf { it > 0 } ?: 0L

    fun releaseAll() {
        players.values.forEach(ExoPlayer::release)
        players.clear()
        mediaSources.clear()
        failoverSlot = null
    }
}

/**
 * FED-004 candidate chain for one note: the rendition pick first, then
 * mirrors, then every other rendition (tall→short). A pure ordering over
 * shared-core data — no platform state.
 */
internal class MediaSources private constructor(private val candidates: List<String>) {

    private var index = 0

    fun current(): String = candidates[index]

    /** Advance on error; null when the chain is exhausted. */
    fun advance(): String? {
        if (index + 1 >= candidates.size) return null
        index += 1
        return candidates[index]
    }

    companion object {
        /** Display long edge for the rendition pick (hdpi range). */
        private const val TARGET_HEIGHT = 1920

        fun forNote(note: FeedNote): MediaSources {
            val video = note.video ?: return MediaSources(listOf(""))
            val ordered = buildList {
                add(video.selectRendition(TARGET_HEIGHT))
                video.fallbackUrls.forEach { add(it) }
                video.renditions.forEach { add(it.url) }
            }.distinct()
            // The pick may coincide with the primary; never hand the failed
            // URL back. Keep at least one entry so the slot can prepare.
            val chain = if (ordered.isEmpty()) listOf(video.url) else ordered
            return MediaSources(chain)
        }
    }
}
