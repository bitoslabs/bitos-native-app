package space.bitos.app.data.feed

import android.content.SharedPreferences

/** Public, account-scoped Following projection used until a relay kind-3
 * head confirms or replaces an optimistic follow change. */
interface FollowingProjectionStore {
    fun read(accountPubkey: String): List<String>
    fun write(accountPubkey: String, follows: Collection<String>)
}

class SharedPreferencesFollowingProjectionStore(
    private val preferences: SharedPreferences,
) : FollowingProjectionStore {
    override fun read(accountPubkey: String): List<String> =
        preferences.getStringSet(key(accountPubkey), emptySet()).orEmpty().toList()

    override fun write(accountPubkey: String, follows: Collection<String>) {
        preferences.edit().putStringSet(key(accountPubkey), follows.take(MAX_FOLLOWS).toSet()).apply()
    }

    private fun key(accountPubkey: String): String = "following.v1.$accountPubkey"
    private companion object { const val MAX_FOLLOWS = 500 }
}

class InMemoryFollowingProjectionStore : FollowingProjectionStore {
    private val values = mutableMapOf<String, List<String>>()
    override fun read(accountPubkey: String): List<String> = values[accountPubkey].orEmpty()
    override fun write(accountPubkey: String, follows: Collection<String>) {
        values[accountPubkey] = follows.take(500)
    }
}
