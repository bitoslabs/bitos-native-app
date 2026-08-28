package space.bitos.app.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors

/** Chats tab placeholder (APP-011 §3.11, Wave 2): honest empty state —
 * NIP-17 DMs are not implemented yet; nothing fake is shown. */
@Composable
fun ChatsScreen() {
    Column(
        Modifier.fillMaxSize().background(BitOSColors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(AppIcons.Chat, contentDescription = null, tint = BitOSColors.textTertiary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("Messages", fontSize = 18.sp, fontWeight = FontWeight.W600, color = BitOSColors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "End-to-end encrypted DMs (NIP-17) arrive in Wave 2.\nNothing to show yet.",
            fontSize = 13.sp,
            color = BitOSColors.textSecondary,
            lineHeight = 18.sp,
        )
    }
}
