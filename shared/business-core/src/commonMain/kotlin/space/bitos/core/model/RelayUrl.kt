package space.bitos.core.model

import kotlin.jvm.JvmInline

/**
 * Validated relay websocket URL.
 *
 * Raw strings never cross into domain logic (repository rule: value types at
 * trust boundaries). Only `wss` and `ws` schemes are accepted; plain http
 * relay endpoints are rejected because NIP-11/http reads are a separate
 * adapter concern.
 */
@JvmInline
value class RelayUrl private constructor(val value: String) {

    fun host(): String = value.removePrefix("wss://").removePrefix("ws://").substringBefore('/')

    companion object {
        private val allowed = Regex("^wss?://[a-zA-Z0-9._~-]+(:\\d{1,5})?(/[a-zA-Z0-9._~/?=-]*)?$")

        fun parse(raw: String): RelayUrl? {
            val candidate = raw.trim().takeIf { it.length in 8..512 } ?: return null
            if (!allowed.matches(candidate)) return null
            return RelayUrl(candidate.removeSuffix("/"))
        }
    }
}
