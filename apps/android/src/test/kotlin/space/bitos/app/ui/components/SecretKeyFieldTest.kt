package space.bitos.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ID-004 secret-key login field acceptance (Android adapter seam): the
 * `secretKeyReady` submit gate flows through the shared `KeyImportForm`
 * rule, so these checks pin what the Compose field renders — verbatim
 * parity with the shared `KeyImportFormTest` and the iOS seam test.
 */
class SecretKeyFieldTest {

    private val nsec = "nsec162knc0y70v8576su95ly75rpw2peffdkclvwnu9pktpafe0kquvqh3ydrs"
    private val npub = "npub194667yy2sqh4h4vlwssg7g5smhmqx4x9hgtfdjun8e46l30kxqqselzc9y"
    private val hexSecret = "d2ad3c3c9e7b0f4f6a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718"

    @Test
    fun submitGateAcceptsValidKeysOnly() {
        assertTrue(secretKeyReady(nsec))
        assertTrue(secretKeyReady(nsec.uppercase()))
        assertTrue(secretKeyReady(hexSecret))
        assertTrue(secretKeyReady("  $nsec\n"))
        assertFalse(secretKeyReady(""))
        assertFalse(secretKeyReady("   "))
        assertFalse(secretKeyReady(npub))
        assertFalse(secretKeyReady(nsec.dropLast(4)))
        assertFalse(secretKeyReady(nsec + "q"))
    }

    /** KF-6: a READY check carries the derived identity the preview card renders. */
    @Test
    fun readyCheckCarriesDerivedIdentity() {
        val check = space.bitos.core.identity.KeyImportForm.check(nsec)
        assertEquals(64, check.pubkeyHex?.length)
        assertTrue(check.npub!!.startsWith("npub1"))
        assertNull(space.bitos.core.identity.KeyImportForm.check(npub).npub)
        assertNull(space.bitos.core.identity.KeyImportForm.check("").pubkeyHex)
    }
}
