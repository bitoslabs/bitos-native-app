package space.bitos.core.studio

import space.bitos.core.studio.EditorNotices.NoticeAction
import space.bitos.core.studio.EditorNotices.Severity
import space.bitos.core.studio.NoticeHost.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Notice-host lifecycle (plan MSU-040): newest wins, duplicates are
 * swallowed, timeouts follow severity, persistent notices never expire.
 */
class NoticeHostTest {

    private val notices = EditorNotices
    private val host = NoticeHost

    private fun info(id: Long, message: String) =
        notices.of(id, Severity.INFO, message)

    @Test
    fun postingShowsTheNoticeAndResetsTheClock() {
        val posted = host.post(State.Empty, info(1, "Frame hold 200 ms"))
        assertTrue(host.isVisible(posted))
        assertEquals("Frame hold 200 ms", posted.current?.message)
        assertEquals(0, posted.elapsedMs)
    }

    @Test
    fun aNewNoticeReplacesTheCurrentOne() {
        val first = host.post(State.Empty, info(1, "one"))
        val second = host.post(first, info(2, "two"))
        assertEquals("two", second.current?.message)
        assertEquals(0, second.elapsedMs, "the clock restarts for the new notice")
    }

    @Test
    fun duplicateNoticesAreSwallowedSoTheAnimationDoesNotRestart() {
        // The shipped `exportStatus = "…"` reassignment re-fired the same
        // toast on every recomposition; posting an identical message must
        // keep the original notice AND its elapsed time.
        val posted = host.post(State.Empty, info(1, "Layer added"))
        val aged = host.tick(posted, 1_000)
        val again = host.post(aged, info(2, "Layer added"))
        assertEquals(aged, again, "an identical notice must be a no-op")
    }

    @Test
    fun sameMessageWithADifferentSeverityIsANewNotice() {
        val infoNotice = host.post(State.Empty, info(1, "Clip deleted"))
        val errorNotice = host.post(infoNotice, notices.error(2, "Clip deleted"))
        assertEquals(Severity.ERROR, errorNotice.current?.severity)
        assertEquals(0, errorNotice.elapsedMs)
    }

    @Test
    fun sameMessageWithADifferentActionIsANewNotice() {
        val plain = host.post(State.Empty, info(1, "Overlay removed"))
        val undoable = host.post(plain, notices.undoable(2, "Overlay removed"))
        assertEquals(NoticeAction.UNDO, undoable.current?.action)
    }

    @Test
    fun transientNoticesExpireAtTheirSeverityTimeout() {
        val posted = host.post(State.Empty, info(1, "Working"))
        val timeout = notices.timeoutFor(Severity.INFO)
        // Not yet expired one tick before the deadline.
        assertTrue(host.isVisible(host.tick(posted, timeout - 1)))
        // Expired at/after the deadline.
        assertFalse(host.isVisible(host.tick(posted, timeout)))
    }

    @Test
    fun persistentErrorsNeverExpire() {
        val posted = host.post(State.Empty, notices.error(1, "Save failed"))
        // A huge elapsed time still cannot dismiss an error.
        assertTrue(host.isVisible(host.tick(posted, 10_000_000)))
    }

    @Test
    fun successUsesItsOwnLongerLifetime() {
        val posted = host.post(State.Empty, notices.success(1, "Saved to Photos"))
        val infoTimeout = notices.timeoutFor(Severity.INFO)
        // Still up after the INFO window would have closed.
        assertTrue(host.isVisible(host.tick(posted, infoTimeout + 100)))
        assertFalse(host.isVisible(host.tick(posted, notices.timeoutFor(Severity.SUCCESS))))
    }

    @Test
    fun dismissClearsImmediatelyAndTickOnEmptyIsStable() {
        val posted = host.post(State.Empty, notices.error(1, "Nope"))
        assertEquals(State.Empty, host.dismiss(posted))
        // Ticking an empty host never resurrects or crashes.
        assertEquals(State.Empty, host.tick(State.Empty, 5_000))
        // Negative/zero deltas are ignored (clock never runs backwards).
        assertEquals(posted, host.tick(posted, -100))
        assertEquals(posted, host.tick(posted, 0))
    }

    @Test
    fun remainingFractionDrivesTheHostProgressIndicator() {
        val posted = host.post(State.Empty, info(1, "Working"))
        assertEquals(1f, host.remainingFraction(posted), 1e-6f)
        val timeout = notices.timeoutFor(Severity.INFO)
        assertEquals(0.5f, host.remainingFraction(host.tick(posted, timeout / 2)), 0.01f)
        // A persistent notice reports 1 (no progress bar, never expires).
        val persistent = host.post(State.Empty, notices.error(1, "Nope"))
        assertEquals(1f, host.remainingFraction(persistent), 1e-6f)
        // Empty reports 0.
        assertEquals(0f, host.remainingFraction(State.Empty), 1e-6f)
    }

    @Test
    fun emptyHostIsNotVisible() {
        assertFalse(host.isVisible(State.Empty))
    }
}
