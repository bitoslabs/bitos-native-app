package space.bitos.app.ui.create.meme

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import space.bitos.core.studio.EditorNotices
import space.bitos.core.studio.NoticeHost

/**
 * Compose-state wrapper over the shared [NoticeHost] (plan MSU-040).
 *
 * The shipped editor wrote every result to one `exportStatus` string and
 * rendered it with no timer, so "Frame hold 200 ms" and "Could not render
 * the design — …" looked identical and never went away. This controller
 * owns the typed API (`info` / `success` / `error` / `undoable`) and the
 * lifecycle (auto-dismiss non-errors, keep errors, newest wins, duplicates
 * swallowed) — the rules themselves live in the shared `NoticeHost`.
 *
 * [exportStatus] is a **migration shim**: the editor still has many
 * `exportStatus = "…"` writes. Routing them through here means they
 * immediately gain the host lifecycle, and each can be upgraded to a typed
 * call (`undoable`, `success`) as its semantics are reviewed. New code must
 * use the typed methods — the shim exists only so the migration is safe.
 */
@Stable
class EditorNoticeController {

    var host by mutableStateOf(NoticeHost.State.Empty)
        private set

    private var nextId = 1L

    /** The visible notice, if any. */
    val current: EditorNotices.Notice? get() = host.current

    fun isVisible(): Boolean = NoticeHost.isVisible(host)

    /** Progress toward auto-dismiss (0..1; 1 = persistent). */
    fun remainingFraction(): Float = NoticeHost.remainingFraction(host)

    private fun post(notice: EditorNotices.Notice) {
        host = NoticeHost.post(host, notice)
    }

    fun info(message: String) {
        post(EditorNotices.info(nextId++, message))
    }

    fun success(message: String) {
        post(EditorNotices.success(nextId++, message))
    }

    fun error(message: String) {
        post(EditorNotices.error(nextId++, message))
    }

    /** A reversible destructive result — always offers Undo (MSU-041). */
    fun undoable(message: String) {
        post(EditorNotices.undoable(nextId++, message))
    }

    fun dismiss() {
        host = NoticeHost.dismiss(host)
    }

    /** Advance the host clock; called from the host's frame loop. */
    fun tick(deltaMs: Int) {
        host = NoticeHost.tick(host, deltaMs)
    }

    /**
     * Migration shim (see the class doc): classify a legacy status string
     * by its content so un-migrated call sites still get a sensible
     * severity. Failure-flavored copy (the shipped error strings) becomes a
     * persistent error; everything else is a transient confirmation.
     */
    fun postLegacyStatus(message: String) {
        if (looksLikeFailure(message)) error(message) else info(message)
    }

    /** The visible message, for callers that still read a status string. */
    val visibleMessage: String? get() = host.current?.message

    private fun looksLikeFailure(message: String): Boolean {
        val lower = message.lowercase()
        return FAILURE_MARKERS.any { lower.contains(it) }
    }

    private companion object {
        /** Substrings that mark the shipped copy as a failure. */
        val FAILURE_MARKERS = listOf(
            "could not", "failed", "not readable", "unreadable", "missing",
            "mismatch", "limit reached", "is larger than", "no readable",
            "is not loadable", "not loadable", "broken", "cannot", "can't",
            "exceed", "too close", "still", "before switching",
        )
    }
}
