package space.bitos.core.publish

import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportComposerTest {

    private val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val targetAuthor = "e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301"
    private val targetId = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
    private val composer = NoteComposer(clock = { 1_710_000_000 })

    @Test
    fun composesNip56Report() {
        val report = composer.composeReport(targetId, targetAuthor, author, "spam")!!
        assertEquals(1_984, report.kind)
        assertEquals("spam", report.content)
        // Tags: e + p + L/l moderation labels
        assertTrue(report.tags.any { it.first() == "e" && it.getOrNull(3) == "report" })
        assertTrue(report.tags.any { it.first() == "p" && it.getOrNull(3) == "report" })
        assertTrue(report.tags.any { it.first() == "L" && it.getOrNull(1) == "MODERATION" })
        assertTrue(report.tags.any { it.first() == "l" && it.getOrNull(1) == "spam" })

        // Round trip through the verified codec.
        val frame = composer.publishMessage(report, "dd".repeat(64))!!
        val decoded = NostrEventCodec.decodeClientEventFrame(Sha256EventHasher, frame, RelayUrl.parse("wss://relay.test"))
        assertEquals(1_984, decoded.kind)
        assertEquals(report.tags, decoded.tags)
    }

    @Test
    fun authorOnlyReportHasNoETag() {
        val report = composer.composeReport(null, targetAuthor, author, "impersonation")!!
        assertTrue(report.tags.none { it.first() == "e" })
        assertTrue(report.tags.any { it.first() == "p" })
    }

    @Test
    fun rejectsInvalidReports() {
        assertNull(composer.composeReport(targetId, "bad", author, "spam"))
        assertNull(composer.composeReport(targetId, targetAuthor, "bad", "spam"))
        assertNull(composer.composeReport(targetId, targetAuthor, author, ""))
        assertNull(composer.composeReport(targetId, targetAuthor, author, "  "))
    }
}
