package space.bitos.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import space.bitos.app.data.feed.DefaultRelays
import space.bitos.app.data.feed.FeedRepository
import space.bitos.app.data.db.SqliteEventCache
import space.bitos.app.data.relay.OkHttpRelayTransport
import space.bitos.app.data.relay.RelayPool
import space.bitos.app.data.relay.relayHttpClient

/**
 * Process composition root. Builds the relay pool, repository and feature
 * stores once; activities receive dependencies by construction, never by
 * global lookup. Scope is cancelled when the process dies.
 */
class BitOsApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val relayPool: RelayPool by lazy {
        RelayPool(
            scope = applicationScope,
            urls = DefaultRelays.urls,
            transportFactory = { relay, onClosed ->
                OkHttpRelayTransport(relay, relayHttpClient(), onClosed)
            },
        )
    }

    val feedRepository: FeedRepository by lazy {
        FeedRepository(
            scope = applicationScope,
            pool = relayPool,
            cache = SqliteEventCache(this),
        )
    }

    val notePublisher: space.bitos.app.data.publish.NotePublisher by lazy {
        space.bitos.app.data.publish.NotePublisher(applicationScope, relayPool)
    }

    val notifications: space.bitos.app.data.feed.NotificationRepository by lazy {
        space.bitos.app.data.feed.NotificationRepository(
            scope = applicationScope,
            pool = relayPool,
            prefs = SharedPrefsNotificationPrefs(
                getSharedPreferences("bitos_notifications", MODE_PRIVATE),
            ),
        )
    }

    val searchRepository: space.bitos.app.data.feed.SearchRepository by lazy {
        space.bitos.app.data.feed.SearchRepository(applicationScope, relayPool)
    }

    val authorRepository: space.bitos.app.data.feed.AuthorRepository by lazy {
        space.bitos.app.data.feed.AuthorRepository(applicationScope, relayPool)
    }

    val settingsStore: space.bitos.app.data.settings.SettingsStore by lazy {
        space.bitos.app.data.settings.SettingsStore(this)
    }

    val muteStore: space.bitos.app.data.feed.MuteStore by lazy {
        space.bitos.app.data.feed.MuteStore(this)
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}

/** Read-id + per-type mute persistence for the notification inbox (bounded by the repository). */
private class SharedPrefsNotificationPrefs(
    private val prefs: android.content.SharedPreferences,
) : space.bitos.app.data.feed.NotificationPrefs {
    override fun readIds(): Set<String> = prefs.getStringSet("read_ids", emptySet()) ?: emptySet()

    override fun save(readIds: Set<String>) {
        prefs.edit().putStringSet("read_ids", readIds).apply()
    }

    override fun mutedKinds(): Set<String> = prefs.getStringSet("muted_kinds", emptySet()) ?: emptySet()

    override fun saveMutedKinds(kinds: Set<String>) {
        prefs.edit().putStringSet("muted_kinds", kinds).apply()
    }
}
