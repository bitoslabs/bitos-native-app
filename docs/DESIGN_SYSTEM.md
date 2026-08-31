# bitos.space — Design System & UI Guidelines

> Visual language and component standards for a premium social media experience.

---

## 0. Native implementation map (single source of truth chain)

Token *values* are owned once by the versioned shared contract
`shared/business-core/src/commonMain/kotlin/space/bitos/core/design/DesignTokens.kt`
(SCHEMA_VERSION 1) and mirrored into both platforms — extend, don't fork:

| Layer | File | Owns |
|:--|:--|:--|
| Contract | `shared/.../design/DesignTokens.kt` | both palettes (dark default + AA-tuned light), type/spacing/radius/avatar/motion scales, hex geometry fractions, WCAG 2.1 contrast engine |
| Contract tests | `shared/.../commonTest/.../design/DesignTokensTest.kt` | contrast floors per mode (§2.6 as executable rules), dark-legacy value pins, scale invariants |
| SwiftUI | `apps/ios/BitOS/DesignSystem/BitOSTheme.swift` | dynamic dark/light `Color`s (trait-resolved + `modeOverride` for APP-023), `BitOSType`, `BitOSMotion`, `BitOSRadius/Spacing/AvatarSize`, glow modifiers |
| Compose | `apps/android/.../ui/theme/Theme.kt` | `BitOSPalette` (dark + light) via `LocalBitOSColors`, both Material schemes, `BitOSTheme(darkTheme)` |
| Components | `apps/ios/BitOS/DesignSystem/App{Button,Chip,Skeleton}.swift` · `apps/android/.../ui/designsystem/*.kt` | button (primary/on-surface/ghost/danger + loading + pressed-scale), chip (default/active/brand/removable), card, shimmer skeletons, matched empty state |

Rules of the chain:

1. **Light mode is derived, not guessed.** The dark accent fails AA as
   text/labels on light surfaces, so light mode ships per-mode *text*
   roles (`textLink`, `errorText`, `successText`, `warningText`,
   `infoText`, `accentText`) at 700-range values; light `textSecondary`
   is `0x4B5563` and tertiary `0x717684` (spec's originals missed the
   4.5:1 / 3:1 floors — enforced by `DesignTokensTest`). Social action
   colors also darken in light so icons hold 3:1 (WCAG 1.4.11).
2. **Existing call sites keep compiling**: `BitOSTheme.surface` /
   `BitOSColors.background` resolve dynamically now; screens pick up
   light mode without edits. Prefer the `*Text` roles for any text on
   surfaces and `textPrimary`/`background` inverted buttons over raw
   accent-as-text.
3. **APP-023 wiring (live)**: the iOS settings adapter syncs
   `BitOSTheme.modeOverride`/`accentOverride` from `ThemeModeSetting` +
   accent hex on every reload (launch + live edits) while the shell and
   every sheet drive `preferredColorScheme(BitOSTheme.preferredScheme)`;
   Android passes `BitOSTheme(darkTheme = …, accentColorHex = …)` from
   the same settings and resolves `BitOSColors` through
   `LocalBitOSColors`. Neither belongs to views.
4. Adding a token: bump `DesignTokens.SCHEMA_VERSION`, add the value in
   both palettes, extend `DesignTokensTest`, then mirror in both theme
   files. Bundling Inter/JetBrains Mono or swapping icon backing later
   changes only the theme files, never call sites.

---

## 1. Design Principles

### 1.1 Core Principles

| Principle | Description |
|:---|:---|
| **Decentralized DNA** | UI should communicate sovereignty — key-first identity, relay visibility |
| **Content First** | Media and text dominate the viewport. UI chrome is minimal |
| **Familiar Patterns** | Users from TikTok/IG/FB should feel instantly comfortable |
| **Smooth & Alive** | Micro-animations on every interaction. Nothing feels static |
| **Dark by Default** | Optimized for OLED. Light mode available but dark is primary |

### 1.2 Platform Inspiration Map

| Feature Area | Inspiration | Our Interpretation |
|:---|:---|:---|
| Reels feed | TikTok | Full-screen vertical swipe with overlaid controls |
| Profile grid | Instagram | 3-column photo grid + list view toggle |
| Feed cards | Twitter/X | Clean text-focused cards with compact action bar |
| Stories | Instagram/Snapchat | Circular avatars at top, tap-through progression |
| DMs | Instagram/iMessage | Bubble chat with encryption indicator |
| Explore | Instagram Explore | Mosaic grid with trending hashtag chips |
| Zaps | Cash App | Lightning animation, quick amount picker |

---

## 2. Color System

### 2.1 Dark Theme (Primary)

```dart
// lib/app/core/theme/app_colors.dart

abstract class AppColors {
  // ── Background Surfaces ──────────────────────────
  static const background      = Color(0xFF0A0A0F);    // Near-black
  static const surface         = Color(0xFF12121A);    // Card/sheet background
  static const surfaceElevated = Color(0xFF1A1A26);    // Elevated cards, modals
  static const surfaceOverlay  = Color(0xFF22222E);    // Dropdowns, tooltips

  // ── Brand Colors ─────────────────────────────────
  static const primary         = Color(0xFFF7931A);    // Bitcoin orange (brand)
  static const primaryLight    = Color(0xFFF9A84B);    // Light orange (hover)
  static const primaryDark     = Color(0xFFD4790F);    // Dark orange (pressed)
  static const primaryGlow     = Color(0x33F7931A);    // Orange glow (20% opacity)

  // ── Accent Colors ────────────────────────────────
  static const accent          = Color(0xFF06B6D4);    // Cyan accent
  static const accentWarm      = Color(0xFFF59E0B);    // Amber (zaps/lightning)
  static const accentPink      = Color(0xFFEC4899);    // Pink (likes/hearts)

  // ── Text Colors ──────────────────────────────────
  static const textPrimary     = Color(0xFFF8F8FF);    // Primary text (98% white)
  static const textSecondary   = Color(0xFF9CA3AF);    // Secondary (gray-400)
  static const textTertiary    = Color(0xFF6B7280);    // Hint text (gray-500)
  static const textLink        = Color(0xFFF7931A);    // Clickable links

  // ── Semantic Colors ──────────────────────────────
  static const success         = Color(0xFF10B981);    // Green (connected, success)
  static const warning         = Color(0xFFF59E0B);    // Amber (warning)
  static const error           = Color(0xFFEF4444);    // Red (error, disconnect)
  static const info            = Color(0xFF3B82F6);    // Blue (info)

  // ── Interaction Colors ───────────────────────────
  static const like            = Color(0xFFEC4899);    // Heart/like pink
  static const repost          = Color(0xFF10B981);    // Repost green
  static const zap             = Color(0xFFF59E0B);    // Lightning amber
  static const reply           = Color(0xFF3B82F6);    // Reply blue
  static const bookmark        = Color(0xFFF7931A);    // Bookmark orange

  // ── Borders & Dividers ───────────────────────────
  static const border          = Color(0xFF2A2A3A);    // Subtle border
  static const borderFocused   = Color(0xFFF7931A);    // Focused input border
  static const divider         = Color(0xFF1F1F2E);    // Section divider

  // ── Gradients ────────────────────────────────────
  static const gradientPrimary = LinearGradient(
    colors: [Color(0xFFF7931A), Color(0xFFF59E0B)],    // Orange → Amber
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
  );

  static const gradientZap = LinearGradient(
    colors: [Color(0xFFF59E0B), Color(0xFFF97316)],    // Amber → Orange
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
  );

  static const gradientDark = LinearGradient(
    colors: [Color(0x00000000), Color(0xCC000000)],    // Transparent → Black
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
  );
}
```

### 2.2 Light Theme

```dart
abstract class AppColorsLight {
  static const background      = Color(0xFFFAFAFC);
  static const surface         = Color(0xFFFFFFFF);
  static const surfaceElevated = Color(0xFFF5F5F7);
  static const textPrimary     = Color(0xFF111827);
  static const textSecondary   = Color(0xFF6B7280);
  // ... same brand/semantic colors as dark theme
}
```

---

## 3. Typography

### 3.1 Type Scale

```dart
// lib/app/core/theme/app_typography.dart

// Font: Inter (Google Fonts) — clean, modern, excellent readability
// Fallback: SF Pro (iOS), Roboto (Android)

abstract class AppTypography {
  static const fontFamily = 'Inter';

  // ── Display ──────────────────────────────────────
  static const displayLarge = TextStyle(
    fontFamily: fontFamily,
    fontSize: 32,
    fontWeight: FontWeight.w700,
    height: 1.2,
    letterSpacing: -0.5,
  );

  // ── Headlines ────────────────────────────────────
  static const headlineLarge = TextStyle(
    fontFamily: fontFamily,
    fontSize: 24,
    fontWeight: FontWeight.w700,
    height: 1.3,
    letterSpacing: -0.3,
  );

  static const headlineMedium = TextStyle(
    fontFamily: fontFamily,
    fontSize: 20,
    fontWeight: FontWeight.w600,
    height: 1.35,
    letterSpacing: -0.2,
  );

  static const headlineSmall = TextStyle(
    fontFamily: fontFamily,
    fontSize: 18,
    fontWeight: FontWeight.w600,
    height: 1.4,
  );

  // ── Body ─────────────────────────────────────────
  static const bodyLarge = TextStyle(
    fontFamily: fontFamily,
    fontSize: 16,
    fontWeight: FontWeight.w400,
    height: 1.5,
  );

  static const bodyMedium = TextStyle(
    fontFamily: fontFamily,
    fontSize: 14,
    fontWeight: FontWeight.w400,
    height: 1.5,
  );

  static const bodySmall = TextStyle(
    fontFamily: fontFamily,
    fontSize: 12,
    fontWeight: FontWeight.w400,
    height: 1.5,
  );

  // ── Labels ───────────────────────────────────────
  static const labelLarge = TextStyle(
    fontFamily: fontFamily,
    fontSize: 14,
    fontWeight: FontWeight.w600,
    height: 1.4,
    letterSpacing: 0.1,
  );

  static const labelMedium = TextStyle(
    fontFamily: fontFamily,
    fontSize: 12,
    fontWeight: FontWeight.w500,
    height: 1.4,
    letterSpacing: 0.2,
  );

  static const labelSmall = TextStyle(
    fontFamily: fontFamily,
    fontSize: 10,
    fontWeight: FontWeight.w500,
    height: 1.4,
    letterSpacing: 0.3,
  );

  // ── Special ──────────────────────────────────────
  static const mono = TextStyle(
    fontFamily: 'JetBrains Mono',  // For npub/nsec display
    fontSize: 13,
    fontWeight: FontWeight.w400,
    height: 1.6,
    letterSpacing: 0.5,
  );
}
```

### 3.2 Usage Rules

```dart
// ✅ CORRECT — use type scale tokens
Text(
  event.content,
  style: AppTypography.bodyLarge.copyWith(
    color: AppColors.textPrimary,
  ),
);

Text(
  timeAgo(event.createdAt),
  style: AppTypography.labelSmall.copyWith(
    color: AppColors.textTertiary,
  ),
);

// For npub/nsec display
Text(
  npub.truncate(24),
  style: AppTypography.mono.copyWith(
    color: AppColors.textSecondary,
  ),
);
```

```dart
// ❌ WRONG — hardcoded text styles
Text('Hello', style: TextStyle(fontSize: 16, color: Colors.white));
```

---

## 4. Spacing & Layout

### 4.1 Spacing Scale

```dart
// lib/app/core/theme/app_spacing.dart

/// 4px base unit spacing system.
/// All spacing uses multiples of 4 for visual consistency.
abstract class AppSpacing {
  static const double xs   = 4;    // Tight: icon padding, inline gaps
  static const double sm   = 8;    // Small: between related items
  static const double md   = 12;   // Medium: section gaps
  static const double base = 16;   // Base: standard padding, card padding
  static const double lg   = 20;   // Large: between sections
  static const double xl   = 24;   // Extra large: screen margins
  static const double xxl  = 32;   // Double extra: hero spacing
  static const double xxxl = 48;   // Triple extra: major section breaks

  // ── Semantic spacing ──────────────────────────────
  static const double screenPadding = xl;      // 24 — screen edge padding
  static const double cardPadding   = base;    // 16 — inside cards
  static const double sectionGap    = xxl;     // 32 — between feed sections
  static const double listItemGap   = sm;      // 8 — between list items
  static const double iconTextGap   = sm;      // 8 — between icon and label
  static const double avatarGap     = md;      // 12 — avatar to text
}
```

### 4.2 Usage

```dart
// ✅ CORRECT — use spacing tokens
Padding(
  padding: const EdgeInsets.all(AppSpacing.base),
  child: Column(
    children: [
      const SizedBox(height: AppSpacing.sm),
      Text(title),
      const SizedBox(height: AppSpacing.xs),
      Text(subtitle),
    ],
  ),
);
```

```dart
// ❌ WRONG — magic numbers
Padding(
  padding: const EdgeInsets.all(15),  // Why 15? Use system
  child: Column(
    children: [
      const SizedBox(height: 7),  // Why 7? Not on 4px grid
    ],
  ),
);
```

---

## 5. Border Radius

```dart
abstract class AppRadius {
  static const double xs   = 4;     // Small chips, tags
  static const double sm   = 8;     // Buttons, inputs
  static const double md   = 12;    // Cards
  static const double lg   = 16;    // Bottom sheets, large cards
  static const double xl   = 20;    // Modals
  static const double full = 999;   // Circular: avatars, pills

  // Pre-built BorderRadius for convenience
  static final smAll   = BorderRadius.circular(sm);
  static final mdAll   = BorderRadius.circular(md);
  static final lgAll   = BorderRadius.circular(lg);
  static final xlAll   = BorderRadius.circular(xl);
  static final circle  = BorderRadius.circular(full);
}
```

---

## 6. Shadows & Elevation

```dart
// lib/app/core/theme/app_shadows.dart

abstract class AppShadows {
  // Dark theme: use colored glow instead of traditional shadows
  static final cardGlow = [
    BoxShadow(
      color: AppColors.primary.withOpacity(0.05),
      blurRadius: 20,
      offset: const Offset(0, 4),
    ),
  ];

  static final elevatedGlow = [
    BoxShadow(
      color: AppColors.primary.withOpacity(0.1),
      blurRadius: 30,
      offset: const Offset(0, 8),
    ),
  ];

  static final bottomNavShadow = [
    BoxShadow(
      color: Colors.black.withOpacity(0.3),
      blurRadius: 20,
      offset: const Offset(0, -4),
    ),
  ];
}
```

---

## 7. Animation Standards

### 7.1 Duration Scale

```dart
abstract class AppDurations {
  static const instant  = Duration(milliseconds: 100);   // Micro-interactions
  static const fast     = Duration(milliseconds: 200);   // Button press, toggle
  static const normal   = Duration(milliseconds: 300);   // Page transitions
  static const slow     = Duration(milliseconds: 500);   // Complex animations
  static const emphasis = Duration(milliseconds: 800);   // Dramatic reveals
}
```

### 7.2 Curves

```dart
abstract class AppCurves {
  static const standard    = Curves.easeInOutCubic;      // Default for most
  static const enter       = Curves.easeOutCubic;        // Elements entering
  static const exit        = Curves.easeInCubic;         // Elements leaving
  static const bounce      = Curves.elasticOut;          // Playful (like button)
  static const decelerate  = Curves.decelerate;          // Natural slow-down
}
```

### 7.3 Required Animations

| Interaction | Animation | Duration | Curve |
|:---|:---|:---|:---|
| Like (heart) | Scale up → bounce back + color fill | 300ms | `elasticOut` |
| Repost | Rotate icon 360° | 400ms | `easeInOutCubic` |
| Zap | Lightning bolt descend + shake | 500ms | `easeOutBack` |
| Pull-to-refresh | Custom Lottie indicator | — | — |
| Page transition | Shared element hero | 300ms | `easeInOutCubic` |
| Bottom sheet | Slide up with fade | 300ms | `easeOutCubic` |
| Double-tap heart | Scale in + fade out floating heart | 800ms | `easeOut` |
| New post FAB | Scale + rotate on appear | 200ms | `easeOutBack` |

### 7.4 Example: Like Animation

```dart
class LikeButton extends StatefulWidget {
  const LikeButton({super.key, required this.isLiked, required this.onTap});
  final bool isLiked;
  final VoidCallback onTap;

  @override
  State<LikeButton> createState() => _LikeButtonState();
}

class _LikeButtonState extends State<LikeButton>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  late final Animation<double> _scaleAnimation;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: AppDurations.normal,
    );
    _scaleAnimation = TweenSequence<double>([
      TweenSequenceItem(tween: Tween(begin: 1.0, end: 1.3), weight: 50),
      TweenSequenceItem(tween: Tween(begin: 1.3, end: 1.0), weight: 50),
    ]).animate(CurvedAnimation(
      parent: _controller,
      curve: AppCurves.bounce,
    ));
  }

  void _handleTap() {
    if (!widget.isLiked) {
      _controller.forward(from: 0);
      HapticFeedback.lightImpact();  // Haptic on like
    }
    widget.onTap();
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: _handleTap,
      child: ScaleTransition(
        scale: _scaleAnimation,
        child: Icon(
          widget.isLiked ? Icons.favorite : Icons.favorite_border,
          color: widget.isLiked ? AppColors.like : AppColors.textSecondary,
          size: 24,
        ),
      ),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }
}
```

---

## 8. Component Catalog

### 8.1 Avatar

```dart
/// Standard avatar component. Used in feed cards, profiles, DMs, mentions.
///
/// Sizes: xs(24), sm(32), md(40), lg(56), xl(80), xxl(120)
class AppAvatar extends StatelessWidget {
  const AppAvatar({
    super.key,
    required this.imageUrl,
    this.size = AppAvatarSize.md,
    this.isOnline = false,
    this.hasStory = false,
    this.isVerified = false,
    this.onTap,
  });

  // Size enum maps to exact pixel values
  // xs=24, sm=32, md=40, lg=56, xl=80, xxl=120
}
```

### 8.2 Action Bar

```dart
/// Standard action bar for feed items.
///
/// Layout: [reply] [repost] [like] [zap] [bookmark] [share]
///
/// Each action shows count (if > 0) and supports animation.
class FeedActionBar extends StatelessWidget {
  const FeedActionBar({
    super.key,
    required this.event,
    required this.onReply,
    required this.onRepost,
    required this.onLike,
    required this.onZap,
    required this.onBookmark,
    required this.onShare,
  });
}
```

### 8.3 Standard Screen Layout

```dart
/// Every screen should follow this structure:
///
/// Scaffold(
///   appBar: [Custom or null for immersive screens],
///   body: [One of these patterns]:
///     - Loading: AppShimmer / skeleton
///     - Error: ErrorRetryWidget(message, onRetry)
///     - Empty: EmptyStateWidget(icon, title, subtitle, action)
///     - Data: [actual content]
///   floatingActionButton: [if applicable],
///   bottomNavigationBar: [main nav only],
/// )
```

---

## 9. Glassmorphism & Effects

```dart
/// Standard glass effect for overlays, bottom sheets, nav bars.
class GlassContainer extends StatelessWidget {
  const GlassContainer({super.key, required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: AppRadius.lgAll,
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Container(
          decoration: BoxDecoration(
            color: AppColors.surface.withOpacity(0.7),
            borderRadius: AppRadius.lgAll,
            border: Border.all(
              color: AppColors.border.withOpacity(0.2),
            ),
          ),
          child: child,
        ),
      ),
    );
  }
}
```

---

## 10. Accessibility Requirements

| Element | Requirement |
|:---|:---|
| **Touch targets** | Minimum 48×48px (Material guidelines) |
| **Color contrast** | 4.5:1 minimum for body text, 3:1 for large text |
| **Semantic labels** | All icons and images must have `semanticLabel` |
| **Screen reader** | All interactive elements must have `Semantics` wrapper |
| **Motion** | Respect `MediaQuery.disableAnimations` |
| **Font scaling** | Support system font size up to 200% |

```dart
// ✅ CORRECT — accessible icon button
IconButton(
  icon: const Icon(Icons.favorite),
  tooltip: 'Like this post',  // Screen reader and long-press hint
  onPressed: onLike,
);

// ✅ CORRECT — semantic image
Image.network(
  url,
  semanticLabel: 'Photo posted by ${author.displayName}',
);

// ✅ CORRECT — respect reduced motion
final reduceMotion = MediaQuery.of(context).disableAnimations;
final duration = reduceMotion ? Duration.zero : AppDurations.normal;
```
