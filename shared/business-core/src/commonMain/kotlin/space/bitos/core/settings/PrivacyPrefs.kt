package space.bitos.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Interaction gates (APP-018a row 2; legacy Flutter
 * `PrivacyNotificationSettings` port). Device-local, one persisted store.
 *
 * Scope note: `hideSensitiveMedia` maps to the settings contract's
 * `bitos_sensitive_media` (`cover` ≡ hide) — NOT duplicated here — and the
 * push per-type toggles are the notification kind mutes (APP-012). This
 * contract owns the eight fields the old app kept in its privacy card that
 * have no other home. readReceipts/DM gates persist now and take effect
 * when NIP-17 DMs ship (W2) — same as the old app, which also persisted
 * without enforcement.
 */
enum class MessagePermission(val wire: String) {
    FOLLOWERS("followers"), EVERYONE("everyone"), NONE("none");

    companion object {
        val DEFAULT = EVERYONE
        fun parse(raw: String?): MessagePermission = entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}

enum class CommentPermission(val wire: String) {
    EVERYONE("everyone"), FOLLOWERS("followers"), FRIENDS("friends");

    companion object {
        val DEFAULT = EVERYONE
        fun parse(raw: String?): CommentPermission = entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}

data class PrivacyPrefs(
    /** Private account: content hidden from non-followers (display gate). */
    val privateAccount: Boolean = false,
    /** Include the client tag (`branding`) on published events (NIP-89). */
    val includeClientTag: Boolean = true,
    /** Show my activity (zaps/reactions) to others (display gate). */
    val activityVisible: Boolean = true,
    /** Send read receipts when DMs ship (persisted now, enforced W2). */
    val readReceipts: Boolean = true,
    /** Show the author's sensitive-content warning reason text. */
    val sensitiveReason: Boolean = true,
    /** Allow others to share my notes to their stories. */
    val storyShare: Boolean = true,
    val messagePermission: MessagePermission = MessagePermission.DEFAULT,
    val commentPermission: CommentPermission = CommentPermission.DEFAULT,
)

object PrivacyPrefsContract {
    const val SCHEMA_VERSION = 1
    const val MAX_WIRE_LENGTH = 1_024

    fun encode(prefs: PrivacyPrefs): String = buildJsonObject {
        put("v", SCHEMA_VERSION)
        put("privateAcc", prefs.privateAccount)
        put("includeClientTag", prefs.includeClientTag)
        put("activity", prefs.activityVisible)
        put("readReceipts", prefs.readReceipts)
        put("sensitiveReason", prefs.sensitiveReason)
        put("storyShare", prefs.storyShare)
        put("messagePermission", prefs.messagePermission.wire)
        put("commentPermission", prefs.commentPermission.wire)
    }.toString()

    /** Per-field tolerant (one corrupt field never discards the rest). */
    fun decode(json: String): PrivacyPrefs {
        if (json.length > MAX_WIRE_LENGTH) return PrivacyPrefs()
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            PrivacyPrefs(
                privateAccount = (root["privateAcc"] as? JsonPrimitive)?.booleanOrNull ?: false,
                includeClientTag = (root["includeClientTag"] as? JsonPrimitive)?.booleanOrNull ?: true,
                activityVisible = (root["activity"] as? JsonPrimitive)?.booleanOrNull ?: true,
                readReceipts = (root["readReceipts"] as? JsonPrimitive)?.booleanOrNull ?: true,
                sensitiveReason = (root["sensitiveReason"] as? JsonPrimitive)?.booleanOrNull ?: true,
                storyShare = (root["storyShare"] as? JsonPrimitive)?.booleanOrNull ?: true,
                messagePermission = MessagePermission.parse((root["messagePermission"] as? JsonPrimitive)?.content),
                commentPermission = CommentPermission.parse((root["commentPermission"] as? JsonPrimitive)?.content),
            )
        } catch (_: Exception) {
            PrivacyPrefs()
        }
    }
}
