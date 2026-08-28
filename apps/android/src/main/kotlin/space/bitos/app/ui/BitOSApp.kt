@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package space.bitos.app.ui
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import space.bitos.app.R
import space.bitos.app.ui.create.CreateScreen
import space.bitos.app.ui.discover.DiscoverScreen
import space.bitos.app.ui.feed.FeedScreen
import space.bitos.app.ui.feed.HomeViewModel
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.PlayArrow
import space.bitos.app.ui.inbox.ChatsScreen
import space.bitos.app.ui.inbox.InboxScreen
import space.bitos.app.ui.profile.ProfileScreen
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSTheme

/** Six-tab product shell (user decision 2026-08-28, legacy-app parity):
 * Home · Bitz · Discover · Chats · Activity · You. Studio/Create entry
 * points stay on the Home FAB, the Bitz header and the future You hub;
 * Settings pushes from You. */
private enum class TopLevelDestination(val label: String, @DrawableRes val iconRes: Int) {
    HOME("Home", R.drawable.solar_nav_home),
    BITZ("Bitz", R.drawable.solar_nav_bitz),
    DISCOVER("Discover", R.drawable.solar_nav_discover),
    CHATS("Chats", R.drawable.solar_nav_chats),
    ACTIVITY("Activity", R.drawable.solar_nav_activity),
    YOU("You", R.drawable.solar_nav_you),
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun BitOSApp(
    homeViewModel: HomeViewModel,
    identityViewModel: space.bitos.app.identity.IdentityViewModel,
    notePublisher: space.bitos.app.data.publish.NotePublisher,
    mediaPublishViewModel: space.bitos.app.ui.feed.MediaPublishViewModel,
    notifications: space.bitos.app.data.feed.NotificationRepository,
    searchRepository: space.bitos.app.data.feed.SearchRepository,
    authorRepository: space.bitos.app.data.feed.AuthorRepository,
    settingsStore: space.bitos.app.data.settings.SettingsStore,
    feedRepository: space.bitos.app.data.feed.FeedRepository,
    relayManager: space.bitos.app.data.relay.RelayManager,
    algorithmStore: space.bitos.app.data.feed.AlgorithmStore,
    privacyPrefs: space.bitos.app.data.settings.PrivacyPrefsStore,
) {
    // Fast access (user decision 2026-08-28): the native system splash hands
    // off straight into the six-tab shell — the branded BootSplashScreen is
    // disabled at app entry (component retained in the library, APP-022).

    BitOSTheme {
        var destination by rememberSaveable { mutableStateOf(TopLevelDestination.HOME) }
        // APP-003/APP-004: re-tap on the active Home/Bitz tab scrolls the
        // feed to top; a re-tap while already at top refreshes it.
        var feedRetapTick by remember { mutableStateOf(0) }
        // APP-018 privacy: sensitive-media default gates every cover.
        val settingsSnapshot by settingsStore.snapshot.collectAsStateWithLifecycle()
        val sensitiveShowByDefault =
            settingsSnapshot.sensitiveMedia == space.bitos.core.settings.SensitiveMediaSetting.SHOW
        val identity by identityViewModel.state.collectAsStateWithLifecycle()
        val notificationsState by notifications.state.collectAsStateWithLifecycle()
        // Shell-level account wiring: the Activity badge needs the inbox
        // subscription alive from app start, not only while the tab is open.
        androidx.compose.runtime.LaunchedEffect(identity.account?.pubkeyHex) {
            notifications.setAccount(identity.account?.pubkeyHex)
        }
        val unreadCount = notificationsState.items.count { it.id !in notificationsState.readIds }
        Scaffold(
            containerColor = BitOSColors.background,
            bottomBar = {
                NavigationBar(containerColor = BitOSColors.surface) {
                    TopLevelDestination.entries.filterNot { it == TopLevelDestination.DISCOVER }.forEach { item ->
                        val badge = if (item == TopLevelDestination.ACTIVITY && unreadCount > 0) {
                            if (unreadCount > 9) "9+" else unreadCount.toString()
                        } else {
                            null
                        }
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = {
                                if (destination == item &&
                                    (item == TopLevelDestination.HOME || item == TopLevelDestination.BITZ)
                                ) {
                                    feedRetapTick++
                                } else {
                                    destination = item
                                }
                            },
                            icon = {
                                androidx.compose.material3.BadgedBox(badge = {
                                    if (badge != null) {
                                        androidx.compose.material3.Badge {
                                            Text(badge, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }) {
                                    Icon(painterResource(item.iconRes), contentDescription = item.label)
                                }
                            },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BitOSColors.primary,
                                selectedTextColor = BitOSColors.primary,
                                indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unselectedIconColor = BitOSColors.textSecondary,
                                unselectedTextColor = BitOSColors.textSecondary,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopStart,
            ) {
                when (destination) {
                    TopLevelDestination.HOME -> FeedScreen(homeViewModel, identityViewModel, notePublisher, mediaPublishViewModel, authorRepository, videoOnly = false, onOpenProfile = { destination = TopLevelDestination.YOU }, onOpenDiscover = { destination = TopLevelDestination.DISCOVER }, onOpenHub = { destination = TopLevelDestination.YOU }, retapTick = feedRetapTick, sensitiveShowByDefault = sensitiveShowByDefault)
                    TopLevelDestination.BITZ -> FeedScreen(homeViewModel, identityViewModel, notePublisher, mediaPublishViewModel, authorRepository, videoOnly = true, onOpenProfile = { destination = TopLevelDestination.YOU }, onOpenDiscover = { destination = TopLevelDestination.DISCOVER }, onOpenHub = { destination = TopLevelDestination.YOU }, retapTick = feedRetapTick, sensitiveShowByDefault = sensitiveShowByDefault)
                    TopLevelDestination.DISCOVER -> space.bitos.app.ui.discover.DiscoverScreen(
                        searchRepository,
                        homeViewModel,
                        identityViewModel,
                        notePublisher,
                    )
                    TopLevelDestination.CHATS -> space.bitos.app.ui.inbox.ChatsScreen()
                    TopLevelDestination.ACTIVITY -> space.bitos.app.ui.inbox.InboxScreen(
                        identityViewModel,
                        notifications,
                        homeViewModel,
                        notePublisher,
                        authorRepository,
                        sensitiveShowByDefault = sensitiveShowByDefault,
                    )
                    TopLevelDestination.YOU -> space.bitos.app.ui.profile.ProfileScreen(identityViewModel, settingsStore, feedRepository, relayManager, notePublisher, notifications, algorithmStore, homeViewModel, privacyPrefs)
                }
            }
        }
    }
}
