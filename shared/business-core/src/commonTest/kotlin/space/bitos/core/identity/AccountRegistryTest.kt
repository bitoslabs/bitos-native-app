package space.bitos.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-018a row 1 — multi-account registry (legacy Flutter `AccountManager`
 * parity, mobile-hardened): bounded, hex-validated, pubkey-unique, lenient
 * per-row decode. SBC-008c v2: signer kind + per-kind public metadata,
 * v1→v2 migration, corrupt-metadata degradation to READ_ONLY.
 */
class AccountRegistryTest {

    private fun account(
        pk: String,
        npub: String = "npub1" + "x".repeat(58),
        name: String? = null,
        signerKind: SignerKind = SignerKind.LOCAL_KEY,
        nip46: Nip46AccountMetadata? = null,
        nip55: Nip55AccountMetadata? = null,
    ) = RegisteredAccount(
        pubkeyHex = pk, npub = npub, displayName = name, addedAtSeconds = 42,
        signerKind = signerKind, nip46 = nip46, nip55 = nip55,
    )

    private val bunkerMeta = Nip46AccountMetadata(
        bunkerPubkeyHex = "b".repeat(64),
        relays = listOf("wss://relay1.example.com", "wss://relay2.example.com"),
        label = "Alby",
    )

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
            account("b".repeat(64), signerKind = SignerKind.REMOTE_NIP46, nip46 = bunkerMeta),
            account("c".repeat(64), signerKind = SignerKind.EXTERNAL_NIP55, nip55 = Nip55AccountMetadata("com.example.signer")),
        )
        val wire = AccountRegistry.encode(accounts)
        assertTrue(wire.length <= AccountRegistry.MAX_WIRE_LENGTH)
        assertEquals(accounts, AccountRegistry.decode(wire))
        assertTrue("nsec" !in wire && "secret" !in wire.lowercase())
        assertTrue("\"v\":2" in wire)
    }

    @Test
    fun perRowToleranceAndCorruptionFallBack() {
        val healed = AccountRegistry.decode(
            """{"v":2,"accounts":[
                {"pk":"${"a".repeat(64)}","npub":"npub1good","n":"A","sk":"LOCAL_KEY"},
                {"pk":"nothex","npub":"npub1bad"},
                {"pk":"${"c".repeat(64)}","npub":"npub1second"}
            ]}""",
        )
        assertEquals(2, healed.size)
        assertEquals("npub1good", healed.first().npub)
        // Row without sk (v1 shape inside v2) is LOCAL_KEY.
        assertEquals(SignerKind.LOCAL_KEY, healed[1].signerKind)
        assertEquals(emptyList(), AccountRegistry.decode("not json"))
        assertEquals(emptyList(), AccountRegistry.decode("{\"v\":2}"))
        assertEquals(emptyList(), AccountRegistry.decode("x".repeat(AccountRegistry.MAX_WIRE_LENGTH + 1)))
    }

    @Test
    fun v1WireMigratesToLocalKey() {
        val v1 = """{"v":1,"accounts":[{"pk":"${"a".repeat(64)}","npub":"npub1legacy","n":"Old","a":7}]}"""
        val migrated = AccountRegistry.decode(v1)
        assertEquals(1, migrated.size)
        assertEquals(SignerKind.LOCAL_KEY, migrated.first().signerKind)
        assertNull(migrated.first().nip46)
        // Re-encode upgrades the wire to v2 without losing data.
        val v2 = AccountRegistry.encode(migrated)
        assertEquals(migrated, AccountRegistry.decode(v2))
        assertTrue("\"v\":2" in v2)
    }

    @Test
    fun unknownSchemaVersionYieldsEmptyRegistry() {
        assertEquals(emptyList(), AccountRegistry.decode("""{"v":3,"accounts":[{"pk":"${"a".repeat(64)}","npub":"npub1"}]}"""))
    }

    @Test
    fun remoteRowWithoutValidMetadataDegradesToReadOnly() {
        // NIP-46 kind but no metadata at all.
        val noMeta = account("a".repeat(64), signerKind = SignerKind.REMOTE_NIP46)
        assertEquals(SignerKind.READ_ONLY, AccountRegistry.normalize(listOf(noMeta)).first().signerKind)

        // Metadata with a bad bunker pubkey.
        val badBunker = account(
            "b".repeat(64), signerKind = SignerKind.REMOTE_NIP46,
            nip46 = bunkerMeta.copy(bunkerPubkeyHex = "zz"),
        )
        val degraded = AccountRegistry.normalize(listOf(badBunker)).first()
        assertEquals(SignerKind.READ_ONLY, degraded.signerKind)
        assertNull(degraded.nip46)

        // Non-wss relay in metadata.
        val plainRelay = account(
            "c".repeat(64), signerKind = SignerKind.REMOTE_NIP46,
            nip46 = bunkerMeta.copy(relays = listOf("ws://relay.example.com")),
        )
        assertEquals(SignerKind.READ_ONLY, AccountRegistry.normalize(listOf(plainRelay)).first().signerKind)

        // NIP-55 kind without a package.
        val noPackage = account("d".repeat(64), signerKind = SignerKind.EXTERNAL_NIP55)
        assertEquals(SignerKind.READ_ONLY, AccountRegistry.normalize(listOf(noPackage)).first().signerKind)
    }

    @Test
    fun corruptMetadataRowDegradesWithoutLosingTheAccount() {
        val wire = """{"v":2,"accounts":[
            {"pk":"${"a".repeat(64)}","npub":"npub1good","sk":"REMOTE_NIP46","n46":{"bp":"nothex","r":["wss://relay.example.com"]}}
        ]}"""
        val healed = AccountRegistry.decode(wire)
        assertEquals(1, healed.size)
        assertEquals(SignerKind.READ_ONLY, healed.first().signerKind)
        assertNull(healed.first().nip46)
    }

    @Test
    fun unknownSignerKindStaysReadOnlyAndLocalRowsDropRemoteMetadata() {
        val wire = """{"v":2,"accounts":[
            {"pk":"${"a".repeat(64)}","npub":"npub1future","sk":"SOME_FUTURE_KIND"}
        ]}"""
        assertEquals(SignerKind.READ_ONLY, AccountRegistry.decode(wire).first().signerKind)

        // A LOCAL_KEY row that somehow carries bunker metadata never keeps it.
        val smuggled = account("b".repeat(64), nip46 = bunkerMeta)
        val cleaned = AccountRegistry.normalize(listOf(smuggled)).first()
        assertEquals(SignerKind.LOCAL_KEY, cleaned.signerKind)
        assertNull(cleaned.nip46)
    }
}
