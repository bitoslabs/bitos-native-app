package space.bitos.app.ui.onboarding

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.settings.OnboardingContent

/** `hasOnboarded` persistence (versioned key, device-local). */
object OnboardingPrefs {
    private const val PREFS = "bitos_onboarding"
    private const val KEY_DONE = "has_onboarded"

    fun hasOnboarded(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markOnboarded(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}

/**
 * APP-002 onboarding carousel (legacy Flutter `onboarding_view` parity):
 * 4 pages from the shared `OnboardingContent` contract — dot indicator,
 * Next/Back, Skip → auth, Get Started on the last page.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
) {
    val pages = OnboardingContent.pages
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == pages.size - 1

    Box(
        Modifier
            .fillMaxSize()
            .background(BitOSColors.background),
    ) {
        HorizontalPager(state = pagerState) { index ->
            OnboardingPageContent(pages[index])
        }

        // Skip (top-right).
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopEnd)
                .padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.lg),
        ) {
            Spacer(Modifier.weight(1f))
            if (!isLast) {
                TextButton(onClick = onDone) { Text("Skip", color = BitOSColors.textSecondary) }
            }
        }

        // Bottom: dots + step counter + Next/Get Started.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.screen)
                .padding(bottom = BitOSSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Dots.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(pages.size) { index ->
                    val selected = pagerState.currentPage == index
                    val width by animateDpAsState(
                        targetValue = if (selected) 24.dp else 8.dp,
                        animationSpec = spring(dampingRatio = 0.7f),
                        label = "dot",
                    )
                    Box(
                        Modifier
                            .size(width = width, height = 8.dp)
                            .clip(CircleShape)
                            .background(if (selected) BitOSColors.primary else BitOSColors.textTertiary.copy(alpha = 0.4f))
                            .clickable(onClickLabel = "Go to page ${index + 1}") {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                    )
                }
            }
            Spacer(Modifier.height(BitOSSpacing.lg))
            // Step counter.
            Text(
                "${pagerState.currentPage + 1} of ${pages.size}",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textTertiary,
            )
            Spacer(Modifier.height(BitOSSpacing.md))
            Button(
                onClick = {
                    if (isLast) {
                        onDone()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isLast) "Get Started" else "Next",
                    fontWeight = FontWeight.W800,
                )
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(page: space.bitos.core.settings.OnboardingPage) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = BitOSSpacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Icon medallion.
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(BitOSColors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            AppIcons.let { icons ->
                val token = when (page.iconToken) {
                    "globe" -> icons.Globe
                    "qrCode" -> icons.QrCode
                    "zap" -> icons.Zap
                    else -> icons.Globe
                }
                androidx.compose.material3.Icon(
                    token,
                    contentDescription = null,
                    tint = BitOSColors.primary,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        Spacer(Modifier.height(BitOSSpacing.xl))
        Text(
            page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.W800,
            color = BitOSColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(BitOSSpacing.md))
        Text(
            page.body,
            style = MaterialTheme.typography.bodyMedium,
            color = BitOSColors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = BitOSSpacing.xl),
        )
    }
}
