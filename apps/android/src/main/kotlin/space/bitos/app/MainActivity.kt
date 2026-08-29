@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package space.bitos.app
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.BitOSApp
import space.bitos.app.ui.feed.HomeViewModel

class MainActivity : ComponentActivity() {

    /** T16: the pending inbound deep link (cold start or onNewIntent). */
    private val pendingDeepLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debugActivity("created")
        // Swap the launch theme (branded window background / Android 12
        // splash) for the normal app theme before Compose mounts — legacy
        // Flutter NormalTheme parity.
        setTheme(R.style.Theme_BitOS)
        val application = application as BitOsApplication
        val repository = application.feedRepository
        val publisher = application.notePublisher
        val notificationRepository = application.notifications
        val muteStore = application.muteStore
        val searchRepository = application.searchRepository
        val authorRepository = application.authorRepository
        val settingsStore = application.settingsStore
        val algorithmStore = application.algorithmStore
        val privacyPrefs = application.privacyPrefs
        val profileLookup = application.profileLookup
        val relayManager = application.relayManager

        val identity: IdentityViewModel by viewModels(factoryProducer = { IdentityViewModel.factory(application, publisher) })
        val viewModel: HomeViewModel by viewModels(factoryProducer = {
            HomeViewModel.factory(repository, publisher, identity, notificationRepository, muteStore)
        })
        val mediaPublish: space.bitos.app.ui.feed.MediaPublishViewModel by viewModels(factoryProducer = {
            space.bitos.app.ui.feed.MediaPublishViewModel.factory(application, publisher, identity)
        })

        takeDeepLink(intent)

        setContent {
            BitOSApp(
                homeViewModel = viewModel,
                identityViewModel = identity,
                notePublisher = publisher,
                composerDraftStore = application.composerDraftStore,
                sentZapsStore = application.sentZapsStore,
                dmRepository = application.dmRepository,
                storiesRepository = application.storiesRepository,
                mediaPublishViewModel = mediaPublish,
                notifications = notificationRepository,
                searchRepository = searchRepository,
                authorRepository = authorRepository,
                settingsStore = settingsStore,
                feedRepository = repository,
                relayManager = relayManager,
                algorithmStore = algorithmStore,
                privacyPrefs = privacyPrefs,
                profileLookup = profileLookup,
                pendingDeepLink = pendingDeepLink.value,
                onDeepLinkConsumed = { pendingDeepLink.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugActivity("received a new intent")
        takeDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        debugActivity("started")
    }

    override fun onResume() {
        super.onResume()
        debugActivity("resumed")
    }

    override fun onPause() {
        debugActivity("paused")
        super.onPause()
    }

    override fun onStop() {
        debugActivity("stopped")
        super.onStop()
    }

    override fun onDestroy() {
        debugActivity("destroyed")
        super.onDestroy()
    }

    private fun takeDeepLink(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (space.bitos.core.nostr.DeepLinks.classify(data) != null) {
            pendingDeepLink.value = data
            debugActivity("accepted a supported deep link")
        } else {
            debugActivity("ignored an unsupported deep link")
        }
    }

    private fun debugActivity(event: String) {
        if (BuildConfig.DEBUG) {
            Log.d(ACTIVITY_LOG_TAG, event)
        }
    }

    private companion object {
        const val ACTIVITY_LOG_TAG = "BitOS.Activity"
    }
}
