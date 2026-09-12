package space.bitos.core.studio

/**
 * Editor notice host (plan MSU-040). Holds the ONE notice currently on
 * screen and decides when it goes away.
 *
 * The shipped editor wrote every result to a single `exportStatus` string
 * and rendered it with no timer, so confirmations ("Frame hold 200 ms")
 * and failures ("Could not render the design — …") looked identical and
 * stayed forever. This type separates the two by severity and owns the
 * lifecycle so both platforms behave the same:
 *
 *  • **Newest wins.** A new notice replaces the current one — a queue of
 *    toasts stacked over a phone canvas would cover the editor.
 *  • **Duplicates are swallowed.** Re-posting the same message+severity
 *    keeps the existing notice (and its original deadline) instead of
 *    restarting the animation, which is what the shipped string did on
 *    every recomposition.
 *  • **Timeout by severity.** Errors are persistent (0 = stay); everything
 *    else auto-dismisses after `EditorNotices.timeoutFor`.
 *
 * Pure and UI-free: the platform renders [current] and calls [tick] from
 * its frame/clock loop. State is a value — copy it, compare it, test it.
 */
object NoticeHost {

    /** Host state: the visible notice and the elapsed time on it. */
    data class State(
        val current: EditorNotices.Notice? = null,
        /** Milliseconds the current notice has been visible. */
        val elapsedMs: Int = 0,
    ) {
        companion object {
            val Empty = State()
        }
    }

    /**
     * Post [notice]. Returns the next state:
     *  • a duplicate of the current notice is ignored (same message and
     *    severity — the id may differ, since callers mint fresh ids);
     *  • otherwise the notice replaces the current one and the clock resets.
     */
    fun post(state: State, notice: EditorNotices.Notice): State {
        val current = state.current
        if (current != null &&
            current.message == notice.message &&
            current.severity == notice.severity &&
            current.action?.id == notice.action?.id
        ) {
            return state
        }
        return State(current = notice, elapsedMs = 0)
    }

    /** Dismiss immediately (the user tapped the notice or its action). */
    fun dismiss(state: State): State =
        if (state.current == null) state else State.Empty

    /**
     * Advance the clock by [deltaMs] and auto-dismiss when the current
     * notice has outlived its timeout. A persistent notice
     * (`timeoutMs == 0`) never expires here.
     */
    fun tick(state: State, deltaMs: Int): State {
        val current = state.current ?: return state
        if (current.timeoutMs <= 0 || deltaMs <= 0) return state
        val elapsed = state.elapsedMs + deltaMs
        return if (elapsed >= current.timeoutMs) State.Empty
        else state.copy(elapsedMs = elapsed)
    }

    /** 0..1 progress toward auto-dismiss (1 = persistent, never expires). */
    fun remainingFraction(state: State): Float {
        val current = state.current ?: return 0f
        if (current.timeoutMs <= 0) return 1f
        val left = (current.timeoutMs - state.elapsedMs).coerceAtLeast(0)
        return left.toFloat() / current.timeoutMs.toFloat()
    }

    /** Whether [state] holds a notice the host should draw. */
    fun isVisible(state: State): Boolean = state.current != null
}
