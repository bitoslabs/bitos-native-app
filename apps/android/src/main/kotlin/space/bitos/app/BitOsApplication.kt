package space.bitos.app

import android.app.Application
import android.util.Log
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

    /** One-shot kind-0 lookups (support/contributors widgets). */
    val profileLookup: space.bitos.app.data.feed.ProfileLookupStore by lazy {
        space.bitos.app.data.feed.ProfileLookupStore(applicationScope, relayPool)
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
            // Web feedPreferences parity: read live so a settings change
            // reconciles on the next publish without rebuilding the repo.
            showProtocolNotes = { settingsStore.snapshot.value.showProtocolNotes },
        )
    }

    val notePublisher: space.bitos.app.data.publish.NotePublisher by lazy {
        space.bitos.app.data.publish.NotePublisher(applicationScope, relayPool)
    }

    val storiesRepository: space.bitos.app.data.stories.StoriesRepository by lazy {
        space.bitos.app.data.stories.StoriesRepository(applicationScope, relayPool)
    }

    val dmRepository: space.bitos.app.data.dm.DmRepository by lazy {
        val keyStore = space.bitos.app.identity.SecureKeyStore(this)
        space.bitos.app.data.dm.DmRepository(
            applicationScope,
            relayPool,
        ) { keyStore.loadSecret() }
    }

    val sentZapsStore: space.bitos.app.data.zap.SentZapsStore by lazy {
        space.bitos.app.data.zap.SentZapsStore(this)
    }

    val composerDraftStore: space.bitos.app.data.publish.ComposerDraftStore by lazy {
        space.bitos.app.data.publish.ComposerDraftStore(this)
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

    override fun onCreate() {
        super.onCreate()
        debugProcess("created")
        // Application properties are now fully initialized. Starting this in
        // `init` can dereference the later lazy delegates while Android is
        // still constructing the Application and crash before MainActivity.
        applicationScope.launch {
            algorithmStore.snapshot.collect { feedRepository.setAlgorithm(it) }
        }
    }

    override fun onTerminate() {
        debugProcess("terminating")
        applicationScope.cancel()
        super.onTerminate()
    }

    private fun debugProcess(event: String) {
        if (BuildConfig.DEBUG) {
            Log.d(PROCESS_LOG_TAG, event)
        }
    }

    private companion object {
        const val PROCESS_LOG_TAG = "BitOS.Process"
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
