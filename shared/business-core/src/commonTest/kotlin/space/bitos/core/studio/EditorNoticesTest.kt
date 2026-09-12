package space.bitos.core.studio

import space.bitos.core.studio.EditorNotices.NoticeAction
import space.bitos.core.studio.EditorNotices.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Notice value-type rules (plan MSU-002): bounded text, severity-driven
 * timeouts, persistent errors, and an Undo affordance on reversible edits.
 */
class EditorNoticesTest {

    private val notices = EditorNotices

    @Test
    fun severityDrivesTheAutoDismissWindow() {
        assertEquals(2_400, notices.timeoutFor(Severity.INFO))
        assertEquals(3_000, notices.timeoutFor(Severity.SUCCESS))
        // Errors never auto-dismiss — the shipped single status line made a
        // failure indistinguishable from a "Frame hold 200 ms" confirmation.
        assertEquals(0, notices.timeoutFor(Severity.ERROR))
    }

    @Test
    fun messagesAreTrimmedAndBounded() {
        val long = "x".repeat(notices.MAX_MESSAGE_CHARS + 500)
        val notice = notices.of(1, Severity.INFO, long)
        assertEquals(notices.MAX_MESSAGE_CHARS, notice.message.length)
        assertEquals("Done", notices.of(2, Severity.INFO, "   ").message)
        assertEquals("Saved", notices.of(3, Severity.SUCCESS, "").message)
        assertEquals("Something went wrong", notices.of(4, Severity.ERROR, "\n").message)
    }

    @Test
    fun zeroTimeoutCannotFlashANonErrorNotice() {
        // A caller asking for 0 ms on a success would render an unreadable
        // flash; it falls back to the severity default instead.
        val success = notices.of(1, Severity.SUCCESS, "Saved", timeoutMs = 0)
        assertEquals(notices.timeoutFor(Severity.SUCCESS), success.timeoutMs)
        // An error keeps its persistent window.
        val error = notices.of(2, Severity.ERROR, "Nope", timeoutMs = 0)
        assertEquals(0, error.timeoutMs)
        // A negative timeout clamps to 0 (never negative).
        assertTrue(notices.of(3, Severity.INFO, "hi", timeoutMs = -50).timeoutMs >= 0)
    }

    @Test
    fun undoableNoticesAlwaysCarryTheUndoAction() {
        val notice = notices.undoable(7, "Overlay deleted")
        assertEquals(NoticeAction.UNDO, notice.action)
        assertEquals("Undo", notice.action?.label)
        assertEquals("undo", notice.action?.id)
    }

    @Test
    fun blankActionsAreDroppedInsteadOfRenderingAnEmptyButton() {
        val notice = notices.of(
            1, Severity.INFO, "ok",
            action = NoticeAction("", ""),
        )
        assertNull(notice.action)
    }

    @Test
    fun stableActionTokensTheNativeHostsSwitchOn() {
        assertEquals("undo", NoticeAction.UNDO.id)
        assertEquals("view_queue", NoticeAction.VIEW_QUEUE.id)
        assertEquals("retry", NoticeAction.RETRY.id)
    }

    @Test
    fun errorKeepsAnOptionalRetryAction() {
        val notice = notices.error(9, "Save failed", NoticeAction.RETRY)
        assertEquals(Severity.ERROR, notice.severity)
        assertEquals(0, notice.timeoutMs, "an actionable error must stay until handled")
        assertEquals(NoticeAction.RETRY, notice.action)
    }
}
