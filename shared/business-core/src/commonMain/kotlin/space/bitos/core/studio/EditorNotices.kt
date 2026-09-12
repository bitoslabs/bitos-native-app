package space.bitos.core.studio

/**
 * Editor notification value type (plan MSU-002). Replaces the shipped
 * behavior where every transient result was written to a single
 * `exportStatus` string and rendered in one `statusLine()` — no
 * auto-dismiss, no action, and indistinguishable from a real error.
 *
 * Severity semantics
 * ------------------
 *  • [Severity.INFO] — confirmation of a successful, unremarkable action.
 *  • [Severity.SUCCESS] — a completed operation worth acknowledging.
 *  • [Severity.ERROR] — a failure the creator must see. Errors are the
 *    ONLY severity the host may render persistently; every other severity
 *    auto-dismisses via [timeoutMs].
 *
 * [action] is an optional affordance (e.g. Undo on a destructive edit).
 * It carries a [NoticeAction.id] token — never a closure — so the value
 * stays pure, comparable and portable across the bridge; the platform host
 * resolves the token against its own handler table.
 */
object EditorNotices {

    enum class Severity { INFO, SUCCESS, ERROR }

    /**
     * A tappable affordance on a notice. [id] is a stable token the native
     * host switches on (`"undo"`, `"view_queue"`, `"retry_export"`, …).
     */
    data class NoticeAction(
        val id: String,
        val label: String,
    ) {
        companion object {
            /** The canonical Undo action offered on reversible results. */
            val UNDO = NoticeAction("undo", "Undo")

            /** Open the mass-production queue (MSU-060/061). */
            val VIEW_QUEUE = NoticeAction("view_queue", "View queue")

            /** Retry a failed export save. */
            val RETRY = NoticeAction("retry", "Retry")
        }
    }

    /**
     * One notice. [message] is bounded by [MAX_MESSAGE_CHARS]; [timeoutMs]
     * is the auto-dismiss window (0 = persistent, used for errors).
     * [id] is monotonic per editor session so a host can key animation and
     * dedupe a re-emitted identical notice (the shipped `exportStatus`
     * reassignments re-fired the same toast on every recomposition).
     */
    data class Notice(
        val id: Long,
        val severity: Severity,
        val message: String,
        val action: NoticeAction? = null,
        val timeoutMs: Int = timeoutFor(severity),
    )

    /** Human-readable cap — a notice is a line, never a paragraph. */
    const val MAX_MESSAGE_CHARS = 160

    /** Auto-dismiss windows by severity; errors stay until replaced. */
    fun timeoutFor(severity: Severity): Int = when (severity) {
        Severity.INFO -> 2_400
        Severity.SUCCESS -> 3_000
        Severity.ERROR -> 0
    }

    /**
     * Build a bounded, well-formed notice. Blank messages degrade to a
     * generic line rather than an invisible toast. [timeoutMs] < 0 clamps
     * to persistent for errors and to the severity default otherwise.
     */
    fun of(
        id: Long,
        severity: Severity,
        message: String,
        action: NoticeAction? = null,
        timeoutMs: Int? = null,
    ): Notice {
        val text = message.trim().take(MAX_MESSAGE_CHARS)
        val resolvedTimeout = timeoutMs?.coerceAtLeast(0) ?: timeoutFor(severity)
        return Notice(
            id = id,
            severity = severity,
            message = text.ifBlank {
                when (severity) {
                    Severity.INFO -> "Done"
                    Severity.SUCCESS -> "Saved"
                    Severity.ERROR -> "Something went wrong"
                }
            },
            action = action?.takeIf { it.id.isNotBlank() && it.label.isNotBlank() },
            // An error with a caller-supplied positive timeout still holds
            // long enough to be read; a zero timeout on a non-error would
            // render an unreadable flash, so it falls back to the default.
            timeoutMs = when {
                severity == Severity.ERROR && resolvedTimeout == 0 -> 0
                resolvedTimeout == 0 -> timeoutFor(severity)
                else -> resolvedTimeout
            },
        )
    }

    /** A transient success confirmation (no action). */
    fun success(id: Long, message: String): Notice = of(id, Severity.SUCCESS, message)

    /** A transient informational line. */
    fun info(id: Long, message: String): Notice = of(id, Severity.INFO, message)

    /**
     * A reversible destructive result: always offers Undo, per MSU-041 —
     * so the creator never needs a modal confirm for an undoable action.
     */
    fun undoable(id: Long, message: String): Notice =
        of(id, Severity.INFO, message, action = NoticeAction.UNDO)

    /**
     * A persistent failure. [message] should name the cause and the next
     * step (the shipped error copy already does; keep that discipline).
     */
    fun error(id: Long, message: String, action: NoticeAction? = null): Notice =
        of(id, Severity.ERROR, message, action = action)
}
