import SwiftUI
import UIKit

/**
 * AppMenu system (unified feature spec §4, APP-022): the screen-clamped
 * popover menu (`AppMenu.showAt` parity with the legacy web/Flutter
 * clients) plus the bottom-sheet menu variant used by long menus
 * (Bitz comments host, mobile "More" hubs).
 *
 * Layout contract: `AppMenuLayout` is pure geometry — preferred position is
 * below-right of the anchor, clamped to screen margins, flipped above the
 * anchor when the bottom edge would clip. Android parity uses the native
 * DropdownMenu (platform clamping), sharing the same entry model.
 */

// MARK: - Model

struct AppMenuItem: Identifiable, Equatable {
    let id: String
    let label: String
    var systemImage: String?
    var isDestructive: Bool = false
    var isChecked: Bool = false

    init(
        id: String,
        label: String,
        systemImage: String? = nil,
        isDestructive: Bool = false,
        isChecked: Bool = false
    ) {
        self.id = id
        self.label = label
        self.systemImage = systemImage
        self.isDestructive = isDestructive
        self.isChecked = isChecked
    }
}

enum AppMenuEntry: Identifiable, Equatable {
    case item(AppMenuItem)
    case divider

    var id: String {
        switch self {
        case .item(let item): item.id
        case .divider: "divider"
        }
    }
}

// MARK: - Layout (pure; tested)

enum AppMenuLayout {
    static let rowHeight: CGFloat = 44
    static let dividerHeight: CGFloat = 9
    static let cardPadding: CGFloat = 12
    /// Width auto-fits the widest label (legacy dropdown parity): floor is
    /// the web Popover `min-w-52` (208), cap keeps long labels bounded.
    static let minWidth: CGFloat = 208
    static let maxWidth: CGFloat = 280
    static let margin: CGFloat = 8
    static let anchorOffset: CGFloat = 4

    /// Row chrome added around the measured label: 12×2 row padding,
    /// 6×2 card side insets, icon slot (20 + 12 gap), check slot (12 + 12).
    private static let rowHorizontalPadding: CGFloat = 24
    private static let cardSideInset: CGFloat = 12
    private static let iconSlot: CGFloat = 32
    private static let checkSlot: CGFloat = 24

    /// Menu width for a set of entries: the widest label + row chrome,
    /// clamped to [minWidth, maxWidth]. Deterministic (fixed font
    /// metrics) so screen clamping stays pure and testable.
    static func width(entries: [AppMenuEntry]) -> CGFloat {
        let font = UIFont.systemFont(ofSize: 13, weight: .semibold)
        let attributes: [NSAttributedString.Key: Any] = [.font: font]
        var widestLabel: CGFloat = 0
        var hasIcon = false
        var hasCheck = false
        for entry in entries {
            if case .item(let item) = entry {
                widestLabel = max(widestLabel, (item.label as NSString).size(withAttributes: attributes).width)
                hasIcon = hasIcon || item.systemImage != nil
                hasCheck = hasCheck || item.isChecked
            }
        }
        var content = widestLabel + rowHorizontalPadding + cardSideInset
        if hasIcon { content += iconSlot }
        if hasCheck { content += checkSlot }
        return min(max(content.rounded(.up), minWidth), maxWidth)
    }

    /// Estimated popover size before first render (deterministic clamping).
    static func estimatedSize(entries: [AppMenuEntry]) -> CGSize {
        var height = cardPadding
        for entry in entries {
            height += entry == .divider ? dividerHeight : rowHeight
        }
        return CGSize(width: width(entries: entries), height: height)
    }

    /// Screen-clamped frame for a menu opening at `anchor` (host-local
    /// space). Preferred below-right; shifts left, flips above, and clamps
    /// to `margin` exactly like the legacy `showAt`.
    static func frame(
        anchor: CGPoint,
        menuSize: CGSize,
        screen: CGSize,
        margin: CGFloat = margin
    ) -> CGRect {
        var x = anchor.x + anchorOffset
        var y = anchor.y + anchorOffset
        let maxRight = screen.width - margin
        let maxBottom = screen.height - margin

        if x + menuSize.width > maxRight {
            x = max(margin, maxRight - menuSize.width)
        }
        if y + menuSize.height > maxBottom {
            y = anchor.y - anchorOffset - menuSize.height
        }
        if x < margin { x = margin }
        if y < margin { y = margin }
        return CGRect(origin: CGPoint(x: x, y: y), size: menuSize)
    }
}

// MARK: - Presentation

/// A menu presentation request: `anchor` is in window (global) space —
/// captured by `AppMenuAnchorButton` at tap time.
struct AppMenuPresentation: Identifiable {
    let id = UUID()
    let anchor: CGPoint
    let entries: [AppMenuEntry]
    let onSelect: (String) -> Void
}

/// Hosts screen-level popover menus over the modified view. Screens own
/// their presentations (no global state): set the binding, get a clamped
/// popover with scrim-dismiss.
extension View {
    func appMenuHost(_ presentation: Binding<AppMenuPresentation?>) -> some View {
        modifier(AppMenuHostModifier(presentation: presentation))
    }
}

private struct AppMenuHostModifier: ViewModifier {
    @Binding var presentation: AppMenuPresentation?

    func body(content: Content) -> some View {
        content.overlay {
            if let presentation {
                AppMenuHostSurface(presentation: presentation) {
                    self.presentation = nil
                }
            }
        }
    }
}

private struct AppMenuHostSurface: View {
    let presentation: AppMenuPresentation
    let dismiss: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    var body: some View {
        GeometryReader { geo in
            let hostFrame = geo.frame(in: .global)
            let localAnchor = CGPoint(
                x: presentation.anchor.x - hostFrame.minX,
                y: presentation.anchor.y - hostFrame.minY
            )
            let size = AppMenuLayout.estimatedSize(entries: presentation.entries)
            let frame = AppMenuLayout.frame(anchor: localAnchor, menuSize: size, screen: geo.size)

            ZStack(alignment: .topLeading) {
                Color.black
                    .opacity(0.15)
                    .ignoresSafeArea()
                    .onTapGesture(perform: dismiss)
                    .accessibilityHidden(true)

                AppMenu(entries: presentation.entries) { id in
                    dismiss()
                    presentation.onSelect(id)
                }
                .frame(width: size.width, height: size.height, alignment: .topLeading)
                .scaleEffect(appeared ? 1 : 0.96)
                .opacity(appeared ? 1 : 0)
                .position(x: frame.midX, y: frame.midY)
            }
        }
        .onAppear {
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                appeared = true
            }
        }
    }
}

// MARK: - Menu card

struct AppMenu: View {
    let entries: [AppMenuEntry]
    let onSelect: (String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ForEach(entries) { entry in
                switch entry {
                case .item(let item):
                    // Web MenuItem parity: rows carry their own rounded-lg
                    // pill with a press fill; the card insets them p-1.5.
                    AppMenuItemRow(item: item) { onSelect(item.id) }
                        .padding(.horizontal, 6)
                case .divider:
                    AppMenuDividerView()
                }
            }
        }
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(BitOSTheme.surfaceOverlay)
        .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous))
        // menuPop shadow parity (DESIGN_SYSTEM: 0/16/48 @ 50%)
        .shadow(color: .black.opacity(0.5), radius: 16, y: 8)
    }
}

struct AppMenuItemRow: View {
    let item: AppMenuItem
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let symbol = item.systemImage {
                AppIcons.image(for: symbol)
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(item.isDestructive ? BitOSTheme.error : BitOSTheme.textSecondary)
                        .frame(width: 20)
                }
                Text(item.label)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(item.isDestructive ? BitOSTheme.error : BitOSTheme.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 0)
                if item.isChecked {
                    Image(systemName: AppIcons.check)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(BitOSTheme.accent)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(minHeight: AppMenuLayout.rowHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(AppMenuItemPressStyle(destructive: item.isDestructive))
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(item.isChecked ? [.isSelected] : [])
    }
}

/// Web MenuItem parity: each row is a rounded-lg (8 pt) pill whose fill
/// appears while pressed — web `--interactive-hover-bg` (white 6%) for
/// normal rows, the error tone for destructive ones.
private struct AppMenuItemPressStyle: ButtonStyle {
    let destructive: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm, style: .continuous)
                    .fill(configuration.isPressed ? pressFill : Color.clear)
            )
    }

    private var pressFill: Color {
        destructive ? BitOSTheme.error.opacity(0.12) : BitOSTheme.textPrimary.opacity(0.06)
    }
}

private struct AppMenuDividerView: View {
    var body: some View {
        // Web MenuDivider parity: 1 px rule spanning the padded content
        // width (my-1 vertical rhythm).
        Rectangle()
            .fill(BitOSTheme.divider)
            .frame(height: 1)
            .padding(.horizontal, 6)
            .padding(.vertical, 4)
    }
}

// MARK: - Bottom-sheet menu (legacy Flutter `AppBottomSheetMenu` parity)

/// Bottom-sheet menu (legacy chrome: drag handle + 28° top radius via the
/// presenting sheet, centered bold title, 48 pt rows with a 12° rounded
/// press, bare 18 pt leading icon, red destructive tone).
/// Present inside `.sheet { AppBottomSheetMenu(...) }`.
struct AppBottomSheetMenu: View {
    let title: String?
    let entries: [AppMenuEntry]
    let onSelect: (String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(BitOSTheme.border)
                .frame(width: 36, height: 4)
                .padding(.top, 8)
            if let title {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 12)
                    .padding(.bottom, 2)
            }
            VStack(spacing: 0) {
                ForEach(entries) { entry in
                    switch entry {
                    case .item(let item):
                        AppMenuItemRow(item: item) { onSelect(item.id) }
                    case .divider:
                        AppMenuDividerView()
                    }
                }
            }
            .padding(.top, 6)
            .padding(.horizontal, 12)
            .padding(.bottom, 14)
        }
        .frame(maxWidth: .infinity)
        .background(BitOSTheme.surface)
    }
}

// MARK: - Anchor button

/// Icon button that reports its global top-right anchor on tap, so the
/// hosting screen can open `AppMenu` clamped at the trigger.
struct AppMenuAnchorButton: View {
    var symbol: String = AppIcons.more
    var tint: Color = .white
    let label: String
    let action: (CGPoint) -> Void

    var body: some View {
        AppIcons.image(for: symbol)
            .font(.system(size: 22, weight: .medium))
            .foregroundStyle(tint)
            .padding(10)
            .background(Circle().fill(.black.opacity(0.2)))
            .overlay {
                GeometryReader { geo in
                    Color.clear
                        .contentShape(Rectangle())
                        .onTapGesture {
                            let frame = geo.frame(in: .global)
                            action(CGPoint(x: frame.maxX, y: frame.minY))
                        }
                }
            }
            .accessibilityLabel(label)
            .accessibilityAddTraits(.isButton)
    }
}

/// Icon-only dismiss affordance for sheet toolbars and headers (user
/// decision 2026-08-29): sheets close with ✕, never a labelled Close button.
struct SheetCloseButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            AppIcons.image(for: AppIcons.close)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(BitOSTheme.textSecondary)
        }
        .accessibilityLabel("Close")
    }
}
