package space.bitos.app.data.feed

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local ranking signals (web `interaction-profile` parity): dismissed notes
 * (hide / not-interested) and softer author/topic demotions that feed the
 * shared For-You ranker. Device-local only — never published, never synced.
 */
class InteractionProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("bitos_interaction_profile", Context.MODE_PRIVATE)

    private val mutableDismissed = MutableStateFlow(load(KEY_DISMISSED, 64, MAX_DISMISSED))
    private val mutableDemotedAuthors = MutableStateFlow(load(KEY_AUTHORS, 64, MAX_DEMOTIONS))
    private val mutableDemotedTags = MutableStateFlow(load(KEY_TAGS, 32, MAX_DEMOTIONS))

    /** Hidden note ids — the ranker's one intentional drop. */
    val dismissedNotes: StateFlow<Set<String>> = mutableDismissed.asStateFlow()
    /** Ranking-level author demotions (softer than a protocol mute). */
    val demotedAuthors: StateFlow<Set<String>> = mutableDemotedAuthors.asStateFlow()
    /** Ranking-level topic demotions. */
    val demotedTags: StateFlow<Set<String>> = mutableDemotedTags.asStateFlow()

    fun dismissNote(noteId: String) {
        update(mutableDismissed, KEY_DISMISSED, noteId, add = true, max = MAX_DISMISSED)
    }

    fun isDismissed(noteId: String): Boolean = noteId in mutableDismissed.value

    fun demoteAuthor(pubkey: String) {
        update(mutableDemotedAuthors, KEY_AUTHORS, pubkey, add = true, max = MAX_DEMOTIONS)
    }

    fun toggleDemotedAuthor(pubkey: String) {
        update(mutableDemotedAuthors, KEY_AUTHORS, pubkey, pubkey !in mutableDemotedAuthors.value, MAX_DEMOTIONS)
    }

    fun isAuthorDemoted(pubkey: String): Boolean = pubkey in mutableDemotedAuthors.value

    fun demoteTag(tag: String) {
        update(mutableDemotedTags, KEY_TAGS, tag, add = true, max = MAX_DEMOTIONS)
    }

    fun toggleDemotedTag(tag: String) {
        update(mutableDemotedTags, KEY_TAGS, tag, tag !in mutableDemotedTags.value, MAX_DEMOTIONS)
    }

    fun isTagDemoted(tag: String): Boolean = tag in mutableDemotedTags.value

    private fun update(
        flow: MutableStateFlow<Set<String>>,
        key: String,
        value: String,
        add: Boolean,
        max: Int,
    ) {
        val current = flow.value
        val updated = if (add) {
            if (current.size >= max && value !in current) return
            current + value
        } else {
            current - value
        }
        flow.value = updated
        prefs.edit().putStringSet(key, updated).apply()
    }

    private fun load(key: String, maxLength: Int, max: Int): Set<String> =
        prefs.getStringSet(key, emptySet())
            ?.filter { it.length in 1..maxLength }
            ?.take(max)
            ?.toSet()
            ?: emptySet()

    private companion object {
        const val KEY_DISMISSED = "dismissed_notes"
        const val KEY_AUTHORS = "demoted_authors"
        const val KEY_TAGS = "demoted_tags"
        const val MAX_DISMISSED = 500
        const val MAX_DEMOTIONS = 200
    }
}
