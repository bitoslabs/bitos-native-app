package space.bitos.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    init {
        // Algorithm preferences drive the For-You ranking for the process
        // lifetime (collect from start so boot reads the persisted snapshot).
        applicationScope.launch {
            algorithmStore.snapshot.collect { feedRepository.setAlgorithm(it) }
        }
    }

    /** Interaction-gate store (APP-018a row 2 — origin parity). */
    val privacyPrefs: space.bitos.app.data.settings.PrivacyPrefsStore by lazy {
        space.bitos.app.data.settings.PrivacyPrefsStore(this)
    }

    /** Algorithm preferences (APP-018 §3.18 — origin parity). */
    val algorithmStore: space.bitos.app.data.feed.AlgorithmStore by lazy {
        space.bitos.app.data.feed.AlgorithmStore(this)
    }

    /** Cold-start managed set: the persisted relays, or the platform defaults. */
    private val bootRelays: List<space.bitos.core.model.RelayEntry> by lazy {
        space.bitos.app.data.relay.RelayManager.bootEntries(
            getSharedPreferences(space.bitos.app.data.relay.RelayManager.PREFS_NAME, MODE_PRIVATE),
        )
    }

    private val relayPool: RelayPool by lazy {
        RelayPool(
            scope = applicationScope,
            urls = bootRelays.map { it.url },
            transportFactory = { relay, onClosed ->
                OkHttpRelayTransport(relay, relayHttpClient(), onClosed)
            },
        )
    }

    /** Relays manager (APP-018): persisted managed set + live pool edits. */
    val relayManager: space.bitos.app.data.relay.RelayManager by lazy {
        space.bitos.app.data.relay.RelayManager(
            pool = relayPool,
            prefs = getSharedPreferences(space.bitos.app.data.relay.RelayManager.PREFS_NAME, MODE_PRIVATE),
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

    override fun cursorSeconds(): Long = prefs.getLong("cursor_seconds", -1L)

    override fun saveCursorSeconds(seconds: Long) {
        prefs.edit().putLong("cursor_seconds", seconds).apply()
    }
}
