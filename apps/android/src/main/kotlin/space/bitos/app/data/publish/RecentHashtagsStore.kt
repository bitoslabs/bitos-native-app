package space.bitos.app.data.publish

import android.content.Context
import space.bitos.core.publish.RecentHashtags

/**
 * Recently used hashtags: the shared `RecentHashtags` ledger as versioned
 * JSON in SharedPreferences (shape validation, recency merge and the
 * 64-entry cap all live in the shared rule). Recorded when a note/meme
 * publish is initiated; the "Recent" chip rows in the composer and the meme
 * post details read [suggestions] for one-tap reuse.
 */
class RecentHashtagsStore(context: Context) {

    private val prefs = context.getSharedPreferences("bitos.recent_hashtags", Context.MODE_PRIVATE)

    /** Fold the tags a publish just used into the ledger; persists async. */
    fun record(used: List<String>) {
        if (used.isEmpty()) return
        val merged = RecentHashtags.merge(
            RecentHashtags.fromJson(prefs.getString(KEY, null)),
            used,
            nowMs = System.currentTimeMillis(),
        )
        prefs.edit().putString(KEY, RecentHashtags.toJson(merged)).apply()
    }

    /** Chip row input: recent tags minus what this post already carries. */
    fun suggestions(exclude: Set<String>, limit: Int = 8): List<String> =
        RecentHashtags.suggestions(RecentHashtags.fromJson(prefs.getString(KEY, null)), exclude, limit)

    companion object {
        private const val KEY = "ledger.v1"

        @Volatile
        private var instance: RecentHashtagsStore? = null

        fun get(context: Context): RecentHashtagsStore =
            instance ?: synchronized(this) {
                instance ?: RecentHashtagsStore(context.applicationContext).also { instance = it }
            }
    }
}
