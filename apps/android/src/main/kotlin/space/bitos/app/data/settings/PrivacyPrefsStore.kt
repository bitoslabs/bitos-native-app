package space.bitos.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.bitos.core.settings.CommentPermission
import space.bitos.core.settings.MessagePermission
import space.bitos.core.settings.PrivacyPrefs
import space.bitos.core.settings.PrivacyPrefsContract

/**
 * APP-018a row 2 adapter: SharedPreferences-backed interaction-gate store
 * executing the shared [PrivacyPrefsContract]. Rules live in business-core;
 * this adapter persists and publishes.
 */
class PrivacyPrefsStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<PrivacyPrefs> = _state.asStateFlow()

    fun reload() {
        _state.value = load()
    }

    fun update(transform: (PrivacyPrefs) -> PrivacyPrefs) {
        val next = transform(_state.value)
        prefs.edit().putString(KEY_WIRE, PrivacyPrefsContract.encode(next)).apply()
        _state.value = next
    }

    fun setMessagePermission(permission: MessagePermission) =
        update { it.copy(messagePermission = permission) }

    fun setCommentPermission(permission: CommentPermission) =
        update { it.copy(commentPermission = permission) }

    private fun load(): PrivacyPrefs =
        prefs.getString(KEY_WIRE, null)?.let(PrivacyPrefsContract::decode) ?: PrivacyPrefs()

    companion object {
        const val PREFS_NAME = "bitos_privacy"
        const val KEY_WIRE = "privacy_prefs_v1"
    }
}
