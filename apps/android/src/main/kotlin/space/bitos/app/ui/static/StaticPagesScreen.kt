package space.bitos.app.ui.static

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.settings.StaticPagesContent
import space.bitos.core.settings.StaticSection

/**
 * APP-020 static pages (spec §3.20): About · Privacy · Terms — the
 * complete legacy copy from shared `StaticPagesContent`, rendered as
 * section cards (legacy Flutter static_pages parity).
 */
@Composable
fun StaticPagesScreen(
    initialPage: String = "about",
    onClose: () -> Unit,
) {
    var page by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(initialPage) }

    Column(
        Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            ,
    ) {
        // Page switcher.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
        ) {
            listOf("about", "privacy", "terms").forEach { key ->
                val selected = page == key
                val label = when (key) {
                    "privacy" -> "Privacy"
                    "terms" -> "Terms"
                    else -> "About"
                }
                TextButton(onClick = { page = key }) {
                    Text(
                        label,
                        fontWeight = if (selected) FontWeight.W800 else FontWeight.W600,
                        color = if (selected) BitOSColors.primary else BitOSColors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close", color = BitOSColors.textSecondary) }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BitOSSpacing.screen)
                .padding(bottom = BitOSSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
        ) {
            when (page) {
                "privacy" -> PrivacyPage()
                "terms" -> TermsPage()
                else -> AboutPage()
            }
        }
    }
}

@Composable
private fun AboutPage() {
    // Hero.
    SectionCard {
        Column(Modifier.padding(BitOSSpacing.base), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                StaticPagesContent.ABOUT_HEADLINE,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.W900,
                color = BitOSColors.primary,
            )
            Text(
                StaticPagesContent.ABOUT_SUBTITLE,
                style = MaterialTheme.typography.labelMedium,
                color = BitOSColors.textTertiary,
            )
            Spacer(Modifier.height(BitOSSpacing.sm))
            Text(
                StaticPagesContent.ABOUT_HERO_BODY,
                style = MaterialTheme.typography.bodyMedium,
                color = BitOSColors.textSecondary,
            )
        }
    }
    SectionCard(StaticPagesContent.WHAT_IS_NOSTR_TITLE, StaticPagesContent.WHAT_IS_NOSTR_BODY)
    SectionCard(StaticPagesContent.OPEN_SOURCE_TITLE, StaticPagesContent.OPEN_SOURCE_BODY + "\n" + StaticPagesContent.OPEN_SOURCE_LAUNCH)
    // Features.
    Text("What makes BitOS different", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W800)
    StaticPagesContent.aboutFeatures.forEach { feature ->
        SectionCard(feature.title, feature.body)
    }
    // Legal link.
    Text(
        "By using BitOS you agree to our Terms and Privacy Policy.",
        style = MaterialTheme.typography.labelSmall,
        color = BitOSColors.textTertiary,
    )
}

@Composable
private fun PrivacyPage() {
    SectionCard {
        Column(Modifier.padding(BitOSSpacing.base)) {
            Text(StaticPagesContent.PRIVACY_TITLE, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.W800)
            Text(StaticPagesContent.PRIVACY_UPDATED, style = MaterialTheme.typography.labelSmall, color = BitOSColors.textTertiary)
            Spacer(Modifier.height(BitOSSpacing.sm))
            Text(StaticPagesContent.PRIVACY_INTRO, style = MaterialTheme.typography.bodyMedium, color = BitOSColors.textSecondary)
        }
    }
    SectionCard(StaticPagesContent.PRIVACY_SUMMARY_TITLE, StaticPagesContent.PRIVACY_SUMMARY_BODY, highlight = true)
    StaticPagesContent.privacySections.forEach { section ->
        SectionCard(section.title, section.body)
    }
}

@Composable
private fun TermsPage() {
    SectionCard {
        Column(Modifier.padding(BitOSSpacing.base)) {
            Text(StaticPagesContent.TERMS_TITLE, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.W800)
            Text(StaticPagesContent.TERMS_UPDATED, style = MaterialTheme.typography.labelSmall, color = BitOSColors.textTertiary)
            Spacer(Modifier.height(BitOSSpacing.sm))
            Text(StaticPagesContent.TERMS_INTRO, style = MaterialTheme.typography.bodyMedium, color = BitOSColors.textSecondary)
        }
    }
    SectionCard(StaticPagesContent.TERMS_SUMMARY_TITLE, StaticPagesContent.TERMS_SUMMARY_BODY, highlight = true)
    StaticPagesContent.termsSections.forEach { section ->
        SectionCard(section.title, section.body)
    }
}

@Composable
private fun SectionCard(title: String, body: String, highlight: Boolean = false) {
    SectionCard(highlight = highlight) {
        Column(Modifier.padding(BitOSSpacing.base)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W700)
            Spacer(Modifier.height(BitOSSpacing.xs))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = BitOSColors.textSecondary)
        }
    }
}

@Composable
private fun SectionCard(highlight: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (highlight) BitOSColors.primaryContainer else BitOSColors.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}
