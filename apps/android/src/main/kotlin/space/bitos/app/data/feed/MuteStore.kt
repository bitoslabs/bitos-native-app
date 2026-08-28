package space.bitos.app.data.feed

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local-only muted-pubkey set (SOC-001). Mutes are a device-local preference:
 * they never publish an event, never sync, and filter the feed, comments,
 * notifications and search. Persisted as a simple bounded list.
 */
class MuteStore(context: Context) {

    private val prefs = context.getSharedPreferences("bitos_mutes", Context.MODE_PRIVATE)

    private val mutableMuted = MutableStateFlow<Set<String>>(load())
    val muted: StateFlow<Set<String>> = mutableMuted.asStateFlow()

    fun isMuted(pubkey: String): Boolean = pubkey in mutableMuted.value

    fun mute(pubkey: String) {
        val updated = mutableMuted.value + pubkey
        if (updated.size > MAX_MUTES) return
        mutableMuted.value = updated
        persist(updated)
    }

    fun unmute(pubkey: String) {
        val updated = mutableMuted.value - pubkey
        mutableMuted.value = updated
        persist(updated)
    }

    private fun load(): Set<String> =
        prefs.getStringSet(KEY, emptySet())?.filter { it.length == 64 }?.toSet() ?: emptySet()

    private fun persist(mutes: Set<String>) {
        prefs.edit().putStringSet(KEY, mutes).apply()
    }

    private companion object {
        const val KEY = "muted_pubkeys"
        const val MAX_MUTES = 500
    }
}
