package space.bitos.core.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import space.bitos.core.model.RelayUrl

/**
 * Public NIP-46 account metadata (SBC-008c): everything the app needs to
 * reconnect to the bunker — and nothing secret. The client transport
 * secret stays sealed in the platform Keychain/Keystore, never here.
 */
data class Nip46AccountMetadata(
    /** hex64 bunker (remote signer) pubkey. */
    val bunkerPubkeyHex: String,
    /** 1..5 wss relay URLs carrying signer traffic. */
    val relays: List<String>,
    val label: String? = null,
    val connectionVersion: Int = 1,
)

/** Public NIP-55 account metadata: the pinned signer app (Android only). */
data class Nip55AccountMetadata(
    val signerPackage: String,
    val protocolVersion: Int = 1,
)

/**
 * One saved account on this device. Secrets NEVER live here — the registry
 * holds only public projections (pubkey, npub, display name, signer kind
 * and the signer's public connection metadata); secrets stay sealed in the
 * platform Keychain/Keystore under their pubkey-keyed slot.
 */
data class RegisteredAccount(
    val pubkeyHex: String,
    val npub: String,
    val displayName: String? = null,
    val addedAtSeconds: Long = 0L,
    val signerKind: SignerKind = SignerKind.LOCAL_KEY,
    val nip46: Nip46AccountMetadata? = null,
    val nip55: Nip55AccountMetadata? = null,
)

/**
 * Versioned multi-account registry (APP-018a row 1; SBC-008c schema v2):
 * bounded, hex-validated, pubkey-unique, lenient per-field decode — one
 * corrupt row never discards the rest, and a corrupt store never crashes
 * the switcher.
 *
 * v2 adds the signer kind plus per-kind public metadata (bunker relay set,
 * pinned NIP-55 package). v1 rows decode as LOCAL_KEY — the kind v1
 * installs actually were. A row whose signer metadata fails validation
 * degrades to READ_ONLY: the account stays visible and switchable but can
 * never silently sign with another account's signer configuration.
 */
object AccountRegistry {
    const val SCHEMA_VERSION = 2
    const val MAX_ACCOUNTS = 8
    const val MAX_WIRE_LENGTH = 8_192
    const val MAX_SIGNER_RELAYS = 5

    private val hex64 = Regex("^[0-9a-f]{64}$")
    private val packageName = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){1,6}$")

    fun isValidPubkey(value: String): Boolean = hex64.matches(value)

    /** Validates, dedupes by pubkey (first wins), caps the list, enforces signer-metadata consistency. */
    fun normalize(accounts: List<RegisteredAccount>): List<RegisteredAccount> {
        val seen = HashSet<String>(accounts.size)
        return accounts
            .map(::consistent)
            .filter { hex64.matches(it.pubkeyHex) && it.npub.isNotEmpty() }
            .filter { seen.add(it.pubkeyHex) }
            .take(MAX_ACCOUNTS)
    }

    fun encode(accounts: List<RegisteredAccount>): String {
        val array = buildJsonArray {
            normalize(accounts).forEach { account ->
                add(
                    buildJsonObject {
                        put("pk", account.pubkeyHex)
                        put("npub", account.npub)
                        account.displayName?.let { put("n", it) }
                        put("a", account.addedAtSeconds)
                        put("sk", account.signerKind.name)
                        account.nip46?.let { meta ->
                            put(
                                "n46",
                                buildJsonObject {
                                    put("bp", meta.bunkerPubkeyHex)
                                    put("r", buildJsonArray { meta.relays.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                                    meta.label?.let { put("l", it) }
                                    put("cv", meta.connectionVersion)
                                },
                            )
                        }
                        account.nip55?.let { meta ->
                            put(
                                "n55",
                                buildJsonObject {
                                    put("pkg", meta.signerPackage)
                                    put("pv", meta.protocolVersion)
                                },
                            )
                        }
                    },
                )
            }
        }
        return buildJsonObject {
            put("v", SCHEMA_VERSION)
            put("accounts", array)
        }.toString()
    }

    /** Per-field tolerant decode; corruption yields an empty registry. */
    fun decode(json: String): List<RegisteredAccount> {
        if (json.isEmpty() || json.length > MAX_WIRE_LENGTH) return emptyList()
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            when (root["v"]?.jsonPrimitive?.intOrNull) {
                1, 2 -> Unit
                else -> return emptyList() // unknown future schema: never guess
            }
            val rows = root["accounts"]?.jsonArray ?: return emptyList()
            rows.mapNotNull { element -> decodeRow(element.jsonObject) }.let(::normalize)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeRow(obj: kotlinx.serialization.json.JsonObject): RegisteredAccount? {
        val pubkey = obj["pk"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { hex64.matches(it) } ?: return null
        val npub = obj["npub"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
        val signerKind = when (val raw = obj["sk"]?.jsonPrimitive?.contentOrNull) {
            null -> SignerKind.LOCAL_KEY // v1 rows predate the kind field
            "LOCAL_KEY" -> SignerKind.LOCAL_KEY
            "REMOTE_NIP46" -> SignerKind.REMOTE_NIP46
            "EXTERNAL_NIP55" -> SignerKind.EXTERNAL_NIP55
            "READ_ONLY" -> SignerKind.READ_ONLY
            // Unknown kind in a future/edited store: keep the row browse-only.
            else -> SignerKind.READ_ONLY
        }
        return RegisteredAccount(
            pubkeyHex = pubkey,
            npub = npub,
            displayName = obj["n"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotEmpty() && it.length <= 64 },
            addedAtSeconds = obj["a"]?.jsonPrimitive?.longOrNull ?: 0L,
            signerKind = signerKind,
            nip46 = obj["n46"]?.jsonObject?.let(::decodeNip46),
            nip55 = obj["n55"]?.jsonObject?.let(::decodeNip55),
        )
    }

    private fun decodeNip46(obj: kotlinx.serialization.json.JsonObject): Nip46AccountMetadata? {
        val bunker = obj["bp"]?.jsonPrimitive?.contentOrNull?.takeIf { hex64.matches(it) } ?: return null
        val relays = obj["r"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.mapNotNull { raw -> RelayUrl.parse(raw)?.takeIf { url -> url.value.startsWith("wss://") }?.value }
            ?.distinct()
            ?.take(MAX_SIGNER_RELAYS)
            ?: return null
        if (relays.isEmpty()) return null
        return Nip46AccountMetadata(
            bunkerPubkeyHex = bunker,
            relays = relays,
            label = obj["l"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && it.length <= 64 },
            connectionVersion = obj["cv"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 1 } ?: 1,
        )
    }

    private fun decodeNip55(obj: kotlinx.serialization.json.JsonObject): Nip55AccountMetadata? {
        val pkg = obj["pkg"]?.jsonPrimitive?.contentOrNull?.takeIf { packageName.matches(it) } ?: return null
        return Nip55AccountMetadata(
            signerPackage = pkg,
            protocolVersion = obj["pv"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 1 } ?: 1,
        )
    }

    /**
     * Signer-kind/metadata consistency: a remote-signer row without valid
     * metadata degrades to READ_ONLY (never to LOCAL_KEY — the secret slot
     * belongs to whoever it belongs to); plain rows never carry remote
     * metadata.
     */
    private fun consistent(account: RegisteredAccount): RegisteredAccount = when (account.signerKind) {
        SignerKind.REMOTE_NIP46 ->
            if (validNip46(account.nip46)) account
            else account.copy(signerKind = SignerKind.READ_ONLY, nip46 = null, nip55 = null)
        SignerKind.EXTERNAL_NIP55 ->
            if (validNip55(account.nip55)) account
            else account.copy(signerKind = SignerKind.READ_ONLY, nip46 = null, nip55 = null)
        else -> account.copy(nip46 = null, nip55 = null)
    }

    private fun validNip46(meta: Nip46AccountMetadata?): Boolean {
        if (meta == null) return false
        if (!hex64.matches(meta.bunkerPubkeyHex)) return false
        val relays = meta.relays.distinct()
        if (relays.size !in 1..MAX_SIGNER_RELAYS) return false
        relays.forEach { raw ->
            val parsed = RelayUrl.parse(raw) ?: return false
            if (!parsed.value.startsWith("wss://")) return false
        }
        if (meta.label != null && meta.label.length > 64) return false
        return meta.connectionVersion >= 1
    }

    private fun validNip55(meta: Nip55AccountMetadata?): Boolean =
        meta != null && packageName.matches(meta.signerPackage) && meta.protocolVersion >= 1
}
