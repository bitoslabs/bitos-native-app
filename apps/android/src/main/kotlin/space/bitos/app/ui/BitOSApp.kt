package space.bitos.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import space.bitos.app.ui.theme.BitOSTheme

private enum class Destination(val label: String) {
    HOME("Home"),
    DISCOVER("Discover"),
    CREATE("Create"),
    INBOX("Inbox"),
    PROFILE("Profile"),
}

@Composable
fun BitOSApp() {
    BitOSTheme {
        var destination by remember { mutableStateOf(Destination.HOME) }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Icon(item.icon(), contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(destination.placeholder())
            }
        }
    }
}

private fun Destination.icon() = when (this) {
    Destination.HOME -> Icons.Outlined.Home
    Destination.DISCOVER -> Icons.Outlined.Search
    Destination.CREATE -> Icons.Outlined.AddCircle
    Destination.INBOX -> Icons.Outlined.Notifications
    Destination.PROFILE -> Icons.Outlined.AccountCircle
}

private fun Destination.placeholder() = when (this) {
    Destination.HOME -> "Bitz feed foundation"
    Destination.DISCOVER -> "Creators, sounds and templates"
    Destination.CREATE -> "Camera, Quick Bitz and MEM Studio"
    Destination.INBOX -> "Activity and secure messages"
    Destination.PROFILE -> "Identity and Creator Studio"
}
