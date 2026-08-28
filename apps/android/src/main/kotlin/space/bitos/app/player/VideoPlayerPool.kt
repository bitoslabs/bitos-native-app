package space.bitos.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import space.bitos.app.data.feed.FeedUiState
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
 * Owned by the feed screen composition; releaseAll on dispose. Not a
 * service locator: exactly one pool exists per feed surface.
 *
 * APP-018 functional settings: [canAutoplay] applies the persisted media
 * autoplay policy (always / unmetered-wifi / never) and [rateProvider] the
 * persisted playback-rate step — both read live so a settings change takes
 * effect on the next reconciliation without rebuilding the pool.
 */
class VideoPlayerPool(
    private val context: Context,
    private val canAutoplay: () -> Boolean = { true },
    private val rateProvider: () -> Float = { 1f },
) {

    private val players = LinkedHashMap<String, ExoPlayer>()

    /** Reconcile slots with [visibleIndex] ± 1 in [state.notes]. */
    fun update(visibleIndex: Int, state: FeedUiState) {
        val notes = state.notes
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
        keep.forEach { note ->
            if (note.id !in players) {
                players[note.id] = ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(note.video!!.url))
                    repeatMode = Player.REPEAT_MODE_ONE
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
        }
    }

    fun playerFor(noteId: String): ExoPlayer? = players[noteId]

    fun togglePlay(noteId: String) {
        players[noteId]?.let { player ->
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    fun releaseAll() {
        players.values.forEach(ExoPlayer::release)
        players.clear()
    }
}
