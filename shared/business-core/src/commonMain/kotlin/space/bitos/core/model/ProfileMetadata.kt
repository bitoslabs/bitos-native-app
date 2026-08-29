package space.bitos.core.model

import kotlinx.serialization.json.Json

/**
 * Bounded kind-0 profile metadata projection. Unknown fields are ignored;
 * string lengths are capped so a hostile profile cannot inflate memory.
 */
data class ProfileMetadata(
    val pubkey: Pubkey,
    val name: String?,
    val displayName: String?,
    val about: String?,
    val picture: String?,
    val nip05: String?,
    val lud16: String?,
    val banner: String? = null,
    val website: String? = null,
) {
    val bestDisplayName: String
        get() = (displayName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() })
            ?: pubkey.value.take(8)

    companion object {
        private const val MAX_FIELD = 512
        private const val MAX_ABOUT = 2_048
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(event: NostrEvent): ProfileMetadata? {
            if (event.kind != NostrKinds.PROFILE_METADATA) return null
            val root = runCatching { json.parseToJsonElement(event.content).let { it as? kotlinx.serialization.json.JsonObject } }
                .getOrNull() ?: return null

            fun field(key: String, cap: Int): String? =
                (root[key] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.take(cap)

            return ProfileMetadata(
                pubkey = event.pubkey,
                name = field("name", MAX_FIELD),
                displayName = field("display_name", MAX_FIELD),
                about = field("about", MAX_ABOUT),
                picture = field("picture", MAX_FIELD),
                nip05 = field("nip05", MAX_FIELD),
                lud16 = field("lud16", MAX_FIELD),
                banner = field("banner", MAX_FIELD),
                website = field("website", MAX_FIELD),
            )
        }
    }
}
