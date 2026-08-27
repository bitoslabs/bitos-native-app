package space.bitos.core.model

import kotlin.jvm.JvmInline

private val lowerHex64 = Regex("^[0-9a-f]{64}$")

@JvmInline
value class EventId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): EventId? = raw.takeIf(lowerHex64::matches)?.let(::EventId)
    }
}

@JvmInline
value class Pubkey private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Pubkey? = raw.takeIf(lowerHex64::matches)?.let(::Pubkey)
    }
}

@JvmInline
value class MediaHash private constructor(val value: String) {
    companion object {
        fun parse(raw: String): MediaHash? = raw.takeIf(lowerHex64::matches)?.let(::MediaHash)
    }
}

sealed interface EventRef {
    data class Regular(val id: EventId) : EventRef

    data class Addressable(
        val kind: Int,
        val author: Pubkey,
        val identifier: String,
    ) : EventRef {
        init {
            require(kind in 30_000..39_999) { "Addressable event kind is required" }
            require(identifier.isNotBlank() && identifier.length <= 256) { "Invalid d tag" }
        }
    }
}
