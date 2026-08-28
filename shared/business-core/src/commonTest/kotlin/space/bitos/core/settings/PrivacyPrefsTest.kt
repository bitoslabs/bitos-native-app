package space.bitos.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APP-018a row 2 — interaction-gate store (legacy Flutter
 * `PrivacyNotificationSettings` parity): defaults, per-field tolerant
 * decode, enum validation, bounded wire.
 */
class PrivacyPrefsTest {

    @Test
    fun defaultsMatchTheLegacyStore() {
        val p = PrivacyPrefsContract.decode("{}")
        assertEquals(false, p.privateAccount)
        assertEquals(true, p.includeClientTag)
        assertEquals(true, p.activityVisible)
        assertEquals(true, p.readReceipts)
        assertEquals(true, p.sensitiveReason)
        assertEquals(true, p.storyShare)
        assertEquals(MessagePermission.EVERYONE, p.messagePermission)
        assertEquals(CommentPermission.EVERYONE, p.commentPermission)
    }

    @Test
    fun roundTripsEveryField() {
        val p = PrivacyPrefs(
            privateAccount = true,
            includeClientTag = false,
            activityVisible = false,
            readReceipts = false,
            sensitiveReason = false,
            storyShare = false,
            messagePermission = MessagePermission.FOLLOWERS,
            commentPermission = CommentPermission.FRIENDS,
        )
        assertEquals(p, PrivacyPrefsContract.decode(PrivacyPrefsContract.encode(p)))
    }

    @Test
    fun perFieldToleranceAndEnumHealing() {
        // One corrupt field never discards the rest.
        val healed = PrivacyPrefsContract.decode(
            """{"v":1,"privateAcc":true,"includeClientTag":false,"activity":"yes",
               "messagePermission":"nobody","commentPermission":"friends"}""",
        )
        assertEquals(true, healed.privateAccount)
        assertEquals(false, healed.includeClientTag)
        assertEquals(true, healed.activityVisible) // corrupt → default
        assertEquals(MessagePermission.EVERYONE, healed.messagePermission) // invalid enum → default
        assertEquals(CommentPermission.FRIENDS, healed.commentPermission)
        // Corrupt / oversized stores never crash.
        assertEquals(PrivacyPrefs(), PrivacyPrefsContract.decode("not json"))
        assertEquals(PrivacyPrefs(), PrivacyPrefsContract.decode("x".repeat(PrivacyPrefsContract.MAX_WIRE_LENGTH + 1)))
    }

    @Test
    fun schemaIsVersionedAndWireBounded() {
        assertEquals(1, PrivacyPrefsContract.SCHEMA_VERSION)
        assertTrue(PrivacyPrefsContract.encode(PrivacyPrefs()).length <= PrivacyPrefsContract.MAX_WIRE_LENGTH)
    }
}
