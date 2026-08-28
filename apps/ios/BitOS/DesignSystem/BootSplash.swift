import SwiftUI

/**
 * Branded boot/splash screen — 1:1 port of the legacy Flutter
 * `BootSplashScreen` (web BootSplash parity): the official lightning-bolt
 * mark inside the shared flat-top `HexShape` avatar, breathing + swept by a
 * rotating orbit gradient while core services hydrate; 9 PoW segments sweep
 * (web `pow-boot-seg`), the official wordmark and a mono status pill.
 * Holds `minDisplay` so the brand moment always lands, then fades out
 * before the app shell shows. The native launch screen (Info.plist
 * `UILaunchScreen`: SplashBackground + SplashLogo) hands off into this view
 * — same colors, same mark — so launch reads as one continuous brand
 * moment instead of a hard cut.
 */

/// Pure timing contract, shared with Android `BootSplashTiming` and the
/// web/Flutter `pow-boot-seg` rules (testable without UI).
enum BootSplashTiming {
    static let loopDuration: TimeInterval = 2.0        // master animation loop
    static let enterDuration: TimeInterval = 0.45      // entrance fade+scale
    static let minDisplay: TimeInterval = 0.9          // brand hold
    static let fadeOut: TimeInterval = 0.3             // web `bs-out`
    static let segmentCount = 9
    static let segmentSweepDelay = 0.12                // animation-delay i*0.12s
    static let segmentOnFraction = 0.45                // lit for 45% of loop
    static let segmentColorCross: TimeInterval = 0.24

    /// Web `pow-boot-seg`: segment `i` is lit when its staggered local time
    /// (loop-normalized `t` − i·0.12, wrapped into 0..<1) is within the
    /// on-fraction.
    static func powSegmentOn(t: Double, index i: Int) -> Bool {
        let delay = (Double(i) * segmentSweepDelay).truncatingRemainder(dividingBy: 1)
        let local = ((t - delay).truncatingRemainder(dividingBy: 1) + 1).truncatingRemainder(dividingBy: 1)
        return local < segmentOnFraction
    }

    /// Breathing scale: 1 ± 0.02 over the 2s loop.
    static func breathe(t: Double) -> Double {
        1.0 + 0.02 * sin(t * 2 * .pi)
    }

    // Brand palette (web/Flutter parity)
    static let orange = Color(hex: 0xF7931A)
    static let yellow = Color(hex: 0xFFD83D)
    static let backgroundLight = Color(hex: 0xF4F7FB)
    static let backgroundDark = Color(hex: 0x0A0A0F)
    static let surfaceDark = Color(hex: 0x171720)
}

/// The official lightning bolt (`bitos-lightning-bolt.svg`, 664×297 viewBox)
/// drawn as a bezier `Path` so it stays crisp at every density — no bundled
/// or executed SVG (safety rule: never render untrusted SVG).
enum BoltMark {
    static let viewport = CGSize(width: 664, height: 297)

    /// Bolt path contained (aspect-preserving, centered) in `rect`.
    static func path(in rect: CGRect) -> Path {
        let scale = min(rect.width / viewport.width, rect.height / viewport.height)
        let width = viewport.width * scale
        let height = viewport.height * scale
        let originX = rect.midX - width / 2
        let originY = rect.midY - height / 2
        func p(_ x: Double, _ y: Double) -> CGPoint {
            CGPoint(x: originX + x * scale, y: originY + y * scale)
        }
        var path = Path()
        path.move(to: p(0, 296))
        path.addCurve(to: p(99, 195), control1: p(37, 254), control2: p(69, 222))
        path.addCurve(to: p(199, 121), control1: p(135, 163), control2: p(168, 139))
        path.addLine(to: p(313, 51))
        path.addCurve(to: p(318, 57), control1: p(317, 49), control2: p(320, 52))
        path.addLine(to: p(306, 119))
        path.addCurve(to: p(484, 36), control1: p(370, 81), control2: p(429, 55))
        path.addCurve(to: p(664, 0), control1: p(547, 14), control2: p(606, 3))
        path.addCurve(to: p(548, 45), control1: p(619, 14), control2: p(580, 29))
        path.addCurve(to: p(441, 99), control1: p(508, 65), control2: p(472, 83))
        path.addCurve(to: p(312, 180), control1: p(397, 122), control2: p(354, 149))
        path.addLine(to: p(231, 243))
        path.addCurve(to: p(229, 236), control1: p(227, 246), control2: p(228, 241))
        path.addLine(to: p(241, 160))
        path.addCurve(to: p(101, 241), control1: p(195, 184), control2: p(148, 211))
        path.addCurve(to: p(0, 296), control1: p(65, 264), control2: p(31, 282))
        path.closeSubpath()
        return path
    }

    /// Yellow → Bitcoin-orange, SVG gradient x1,y1→x2,y2 (diagonal).
    static var gradient: LinearGradient {
        LinearGradient(
            colors: [Color(hex: 0xFFD83D), Color(hex: 0xFFB51B), Color(hex: 0xF7931A)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }
}

private struct BoltShape: Shape {
    func path(in rect: CGRect) -> Path { BoltMark.path(in: rect) }
}

/// Boot splash screen (see header). `onDismissed` fires after the hold +
/// fade-out complete, on the main actor.
struct BootSplashScreen: View {
    var status: String = "Booting BitOS…"
    var onDismissed: (() -> Void)?

    @Environment(\.colorScheme) private var colorScheme
    @State private var enterAmount = 0.0
    @State private var fadeAmount = 1.0

    private var background: Color {
        colorScheme == .dark ? BootSplashTiming.backgroundDark : BootSplashTiming.backgroundLight
    }

    var body: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate
                .truncatingRemainder(dividingBy: BootSplashTiming.loopDuration)
                / BootSplashTiming.loopDuration
            ZStack {
                background.ignoresSafeArea()
                VStack(spacing: 0) {
                    SplashHexAvatar(progress: t, colorScheme: colorScheme)
                        .padding(.bottom, 20)
                    Image("Wordmark")
                        .resizable()
                        .scaledToFit()
                        .frame(height: 26)
                        .padding(.bottom, 18)
                    PowBootSegments(progress: t, colorScheme: colorScheme)
                        .padding(.bottom, 12)
                    StatusPill(text: status, colorScheme: colorScheme)
                }
                .opacity(enterAmount)
                .scaleEffect(0.94 + 0.06 * enterAmount)
            }
            .opacity(fadeAmount)
        }
        .accessibilityLabel("Loading BitOS")
        .task {
            // Gentle entrance (Flutter Curves.easeOutCubic = cubic-bezier .33,1,.68,1).
            withAnimation(.timingCurve(0.33, 1, 0.68, 1, duration: BootSplashTiming.enterDuration)) {
                enterAmount = 1
            }
            // Hold the brand moment, then bs-out fade and release the shell.
            try? await Task.sleep(for: .seconds(BootSplashTiming.minDisplay))
            withAnimation(.easeOut(duration: BootSplashTiming.fadeOut)) {
                fadeAmount = 0
            }
            try? await Task.sleep(for: .seconds(BootSplashTiming.fadeOut))
            onDismissed?()
        }
    }
}

/// The bolt mark in the shared hex avatar: surface + orange glow +
/// rotating sweep-gradient orbit stroke + breathing scale
/// (legacy `_SplashHexAvatar`).
private struct SplashHexAvatar: View {
    let progress: Double
    let colorScheme: ColorScheme
    var size: CGFloat = 92

    private var surface: Color {
        colorScheme == .dark ? BootSplashTiming.surfaceDark : .white
    }

    /// SweepGradient(colors: [orange 12%, yellow 95%, orange 12%],
    /// stops: [0, 0.10, 0.20]) rotated by progress·2π.
    private var orbitGradient: AngularGradient {
        let rotation = Angle.degrees(progress * 360)
        return AngularGradient(
            stops: [
                .init(color: BootSplashTiming.orange.opacity(0.12), location: 0.0),
                .init(color: BootSplashTiming.yellow.opacity(0.95), location: 0.1),
                .init(color: BootSplashTiming.orange.opacity(0.12), location: 0.2),
                .init(color: BootSplashTiming.orange.opacity(0.12), location: 1.0),
            ],
            center: .center,
            startAngle: rotation,
            endAngle: rotation + .degrees(360)
        )
    }

    var body: some View {
        let breathe = BootSplashTiming.breathe(t: progress)
        ZStack {
            // Orange glow (BoxShadow 0x33F7931A blur 22 spread 1 parity).
            RadialGradient(
                colors: [BootSplashTiming.orange.opacity(0.20), .clear],
                center: .center,
                startRadius: 0,
                endRadius: size * 0.72
            )
            .frame(width: size * 1.44, height: size * 1.44)
            // Orbit: hexagon outline, inset 2pt (legacy hexPath(size−4).shift(2,2)).
            HexShape()
                .stroke(orbitGradient, style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
                .padding(2)
            // Hex surface with the bolt (padding 13/92 h · 20/92 v parity).
            HexShape()
                .fill(surface)
                .overlay(
                    BoltShape()
                        .fill(BoltMark.gradient)
                        .padding(.horizontal, size * 13 / 92)
                        .padding(.vertical, size * 20 / 92)
                )
        }
        .frame(width: size, height: size)
        .scaleEffect(breathe)
    }
}

/// 9 PoW segments in a staggered sweep (web `pow-boot-seg`).
private struct PowBootSegments: View {
    let progress: Double
    let colorScheme: ColorScheme

    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<BootSplashTiming.segmentCount, id: \.self) { i in
                PowSegment(
                    isOn: BootSplashTiming.powSegmentOn(t: progress, index: i),
                    colorScheme: colorScheme
                )
            }
        }
    }
}

private struct PowSegment: View {
    let isOn: Bool
    let colorScheme: ColorScheme

    private var color: Color {
        if isOn { return BootSplashTiming.orange }
        return colorScheme == .dark
            ? Color.white.opacity(0.10)
            : Color.black.opacity(0.08)
    }

    var body: some View {
        RoundedRectangle(cornerRadius: 2)
            .fill(color)
            .frame(width: 4, height: 14)
            .animation(
                .easeInOut(duration: BootSplashTiming.segmentColorCross),
                value: isOn
            )
    }
}

/// Mono status pill ("Booting BitOS…").
private struct StatusPill: View {
    let text: String
    let colorScheme: ColorScheme

    var body: some View {
        Text(text)
            .font(.system(size: 11, design: .monospaced))
            .foregroundStyle(colorScheme == .dark ? Color.white.opacity(0.45) : Color.black.opacity(0.45))
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(
                Capsule()
                    .fill(colorScheme == .dark ? Color.white.opacity(0.055) : Color.black.opacity(0.045))
                    .overlay(
                        Capsule().strokeBorder(
                            colorScheme == .dark ? Color.white.opacity(0.09) : Color.black.opacity(0.07),
                            lineWidth: 1
                        )
                    )
            )
    }
}
