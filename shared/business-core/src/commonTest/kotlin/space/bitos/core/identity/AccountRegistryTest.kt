package space.bitos.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APP-018a row 1 — multi-account registry (legacy Flutter `AccountManager`
 * parity, mobile-hardened): bounded, hex-validated, pubkey-unique, lenient
 * per-row decode.
 */
class AccountRegistryTest {

    private fun account(pk: String, npub: String = "npub1" + "x".repeat(58), name: String? = null) =
        RegisteredAccount(pubkeyHex = pk, npub = npub, displayName = name, addedAtSeconds = 42)

    @Test
    fun normalizeValidatesDedupesAndCaps() {
        val a = account("a".repeat(64), name = "Alpha")
        val dupA = account("a".repeat(64), npub = "npub1other")
        val bad = account("zzz") // non-hex dropped
        val filler = (1..10).map { account(it.toString(16).padStart(64, '0')) }
        val normalized = AccountRegistry.normalize(listOf(a, dupA, bad) + filler)
        assertEquals(8, normalized.size) // capped
        assertEquals("Alpha", normalized.first().displayName) // first wins on dupes
        assertTrue(normalized.none { it.pubkeyHex == "zzz" })
    }

    @Test
    fun wireRoundTripsAndIsSecretFree() {
        val accounts = listOf(
            account("a".repeat(64), name = "Alpha"),
            account("b".repeat(64)),
        )
        val wire = AccountRegistry.encode(accounts)
        assertTrue(wire.length <= AccountRegistry.MAX_WIRE_LENGTH)
        assertEquals(accounts, AccountRegistry.decode(wire))
        assertTrue("nsec" !in wire && "secret" !in wire.lowercase())
    }

    @Test
    fun perRowToleranceAndCorruptionFallBack() {
        val healed = AccountRegistry.decode(
            """{"v":1,"accounts":[
                {"pk":"${"a".repeat(64)}","npub":"npub1good","n":"A"},
                {"pk":"nothex","npub":"npub1bad"},
                {"pk":"${"c".repeat(64)}"}
            ]}""",
        )
        assertEquals(1, healed.size)
        assertEquals("npub1good", healed.first().npub)
        assertEquals(emptyList(), AccountRegistry.decode("not json"))
        assertEquals(emptyList(), AccountRegistry.decode("{\"v\":1}"))
        assertEquals(emptyList(), AccountRegistry.decode("x".repeat(AccountRegistry.MAX_WIRE_LENGTH + 1)))
    }
}
