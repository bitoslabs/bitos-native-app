@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package space.bitos.app
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.BitOSApp
import space.bitos.app.ui.feed.HomeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val relayManager = application.relayManager

        val identity: IdentityViewModel by viewModels(factoryProducer = { IdentityViewModel.factory(application, publisher) })
        val viewModel: HomeViewModel by viewModels(factoryProducer = {
            HomeViewModel.factory(repository, publisher, identity, notificationRepository, muteStore)
        })
        val mediaPublish: space.bitos.app.ui.feed.MediaPublishViewModel by viewModels(factoryProducer = {
            space.bitos.app.ui.feed.MediaPublishViewModel.factory(application, publisher, identity)
        })

        setContent {
            BitOSApp(
                homeViewModel = viewModel,
                identityViewModel = identity,
                notePublisher = publisher,
                composerDraftStore = application.composerDraftStore,
                mediaPublishViewModel = mediaPublish,
                notifications = notificationRepository,
                searchRepository = searchRepository,
                authorRepository = authorRepository,
                settingsStore = settingsStore,
                feedRepository = repository,
                relayManager = relayManager,
                algorithmStore = algorithmStore,
                privacyPrefs = privacyPrefs,
            )
        }
    }
}
