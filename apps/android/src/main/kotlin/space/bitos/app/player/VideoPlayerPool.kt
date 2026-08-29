package space.bitos.app.player

import android.content.Context
import androidx.media3.common.MediaItem
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
 */
class VideoPlayerPool(
    private val context: Context,
    private val canAutoplay: () -> Boolean = { true },
    private val rateProvider: () -> Float = { 1f },
    private val mutedProvider: () -> Boolean = { false },
) {

    private val players = LinkedHashMap<String, ExoPlayer>()

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
            }
        }
        // Create missing slots (bounded to keep.size <= 3).
        val muted = mutedProvider()
        keep.forEach { note ->
            if (note.id !in players) {
                players[note.id] = ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(note.video!!.url))
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = if (muted) 0f else 1f
                    prepare()
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
    }
}
