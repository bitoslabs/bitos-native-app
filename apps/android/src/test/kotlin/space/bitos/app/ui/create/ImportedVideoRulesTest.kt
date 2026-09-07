package space.bitos.app.ui.create

import space.bitos.app.ui.create.meme.MemeVideoExport
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Studio import-media contract (CAP/EDT): a picked library video seeds the
 * meme editor — the ONE publish path from the Create hub — and every
 * rejection names the reason and the fix while keeping the hub untouched
 * (docs/product/ux-ui-flows.md §6; the standalone "New video" publish
 * sheet is gone from the studio hub).
 */
class ImportedVideoRulesTest {

    @Test
    fun unreadablePicksAreNamed() {
        assertTrue(ImportedVideoRules.rejection(null)!!.contains("could not be read"))
        assertTrue(ImportedVideoRules.rejection(0L)!!.contains("could not be read"))
    }

    @Test
    fun oversizedSourcesNameTheCapAndTheFix() {
        val message = ImportedVideoRules.rejection(MemeVideoExport.MAX_SOURCE_BYTES + 1)
        assertTrue(message!!.contains("256 MB"), "names the cap: $message")
        assertTrue(message.contains("Trim"), "names the fix: $message")
    }

    @Test
    fun inBoundSourcesSeedTheEditor() {
        assertNull(ImportedVideoRules.rejection(1L))
        assertNull(ImportedVideoRules.rejection(MemeVideoExport.MAX_SOURCE_BYTES))
    }
}
