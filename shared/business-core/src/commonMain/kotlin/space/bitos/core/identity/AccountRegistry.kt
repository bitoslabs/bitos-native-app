package space.bitos.core.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One saved account on this device. Secrets NEVER live here — the registry
 * holds only public projections (pubkey, npub, display name, added-at);
 * secrets stay sealed in the platform Keychain/Keystore under their
 * pubkey-keyed slot.
 */
data class RegisteredAccount(
    val pubkeyHex: String,
    val npub: String,
    val displayName: String? = null,
    val addedAtSeconds: Long = 0L,
)

/**
 * Versioned multi-account registry (APP-018a row 1; legacy Flutter
 * `AccountManager` parity, mobile-hardened): bounded, hex-validated,
 * pubkey-unique, lenient per-field decode — one corrupt row never discards
 * the rest, and a corrupt store never crashes the switcher.
 */
object AccountRegistry {
    const val SCHEMA_VERSION = 1
    const val MAX_ACCOUNTS = 8
    const val MAX_WIRE_LENGTH = 4_096

    private val hex64 = Regex("^[0-9a-f]{64}$")

    fun isValidPubkey(value: String): Boolean = hex64.matches(value)

    /** Validates, dedupes by pubkey (first wins) and caps the list. */
    fun normalize(accounts: List<RegisteredAccount>): List<RegisteredAccount> {
        val seen = HashSet<String>(accounts.size)
        return accounts.filter { hex64.matches(it.pubkeyHex) && it.npub.isNotEmpty() }
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
        if (json.length > MAX_WIRE_LENGTH) return emptyList()
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            val rows = root["accounts"]?.jsonArray ?: return emptyList()
            rows.mapNotNull { element ->
                val obj = element.jsonObject
                val pubkey = obj["pk"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { hex64.matches(it) } ?: return@mapNotNull null
                val npub = obj["npub"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return@mapNotNull null
                RegisteredAccount(
                    pubkeyHex = pubkey,
                    npub = npub,
                    displayName = obj["n"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() && it.length <= 64 },
                    addedAtSeconds = obj["a"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }.let(::normalize)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
