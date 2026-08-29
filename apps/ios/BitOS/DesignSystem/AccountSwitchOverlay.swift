import SwiftUI

/**
 * Branded full-page account-switch overlay (legacy `AccountSwitcherOverlay`
 * parity): opaque scrim + breathing hex mark with a rotating amber orbit
 * ring; the FROM identity holds ~450 ms then morphs into the target (scale
 * 0.55→1 + fade + slight rotation); staged captions with a determinate
 * progress fill; check-badge completion pop; a readable done beat and a
 * 1250 ms brand floor before the fade-out reveals the rebound app.
 */
struct AccountSwitchOverlayView: View {
    let fromPubkey: String?
    let toPubkey: String
    let toName: String?
    let switchAction: () -> Void
    let onFinished: () -> Void

    private enum Phase { case running, done }

    @State private var phase: Phase = .running
    @State private var shownPubkey: String?
    @State private var progress: Double = 0.1
    @State private var stage = 0
    @State private var fade = 1.0
    @State private var breathe = true
    @State private var orbitAngle = 0.0
    @State private var started = false

    private var stageText: String {
        phase == .done ? "Done" : stages[stage]
    }

    private var titleText: String {
        if phase == .done {
            return "Now using \(toName ?? "your account")"
        }
        return "Switching accounts"
    }

    private let stages = [
        "Reading the sealed key",
        "Connecting relays",
        "Loading profile",
        "Rebuilding your feed",
    ]

    var body: some View {
        ZStack {
            Color(hex: 0x0A0A0F).opacity(fade).ignoresSafeArea()
            VStack(spacing: 22) {
                handoffMark
                Text(titleText)
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(Color(hex: 0xF8F8FF))
                Text(stageText)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Color(hex: 0x6B7280))
                ProgressView(value: progress)
                    .progressViewStyle(.linear)
                    .tint(Color(hex: 0xF7931A))
                    .frame(width: 180, height: 3)
            }
        }
        .task {
            guard !started else { return }
            started = true
            let start = Date()
            withAnimation(.linear(duration: 1).repeatForever(autoreverses: true)) {
                breathe = false
            }
            for index in stages.indices {
                stage = index
                progress = Double(index + 1) / 4 * 0.75
                try? await Task.sleep(for: .milliseconds(180))
            }
            try? await Task.sleep(for: .milliseconds(200))
            withAnimation(.spring(response: 0.45, dampingFraction: 0.6)) {
                shownPubkey = toPubkey
            }
            switchAction()
            progress = 1
            withAnimation(.easeOut(duration: 0.32)) { phase = .done }
            try? await Task.sleep(for: .milliseconds(700)) // done beat
            let elapsed = Date().timeIntervalSince(start) * 1000
            if elapsed < 1250 {
                try? await Task.sleep(for: .milliseconds(Int(1250 - elapsed))) // brand floor
            }
            withAnimation(.easeOut(duration: 0.24)) { fade = 0 }
            try? await Task.sleep(for: .milliseconds(260))
            onFinished()
        }
        .onAppear {
            shownPubkey = fromPubkey ?? toPubkey
        }
    }

    // MARK: - Hex mark (orbit ring + avatar handoff + completion badge)

    private var handoffMark: some View {
        ZStack {
            orbitRing
            avatarHandoff
            if phase == .done {
                checkBadge
            }
        }
        .frame(width: 128, height: 128)
        .scaleEffect(breathe ? 1.0 : 1.02)
    }

    /// Timeline-driven orbit (transaction-proof: nothing can interrupt the
    /// spin — the angle derives from wall-clock time, not animated state).
    private var orbitRing: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate
            let angle = (t * 180.0).truncatingRemainder(dividingBy: 360) // 0.5 rev/s
            let frac = angle / 360
            let arc = 0.16
            var stops: [Gradient.Stop] = [
                .init(color: dimAmber, location: 0),
            ]
            let peak = frac + arc / 2
            if peak <= 1 {
                if frac > 0 { stops.append(.init(color: dimAmber, location: frac)) }
                stops.append(.init(color: brightAmber, location: peak))
                stops.append(.init(color: dimAmber, location: min(frac + arc, 1)))
            } else {
                stops.append(.init(color: brightAmber, location: min((frac + 1) / 2, 1)))
                stops.append(.init(color: dimAmber, location: 1))
            }
            stops.append(.init(color: dimAmber, location: 1))
            return HexShape()
                .stroke(
                    AngularGradient(stops: stops, center: .center),
                    lineWidth: 1.8
                )
                .padding(2)
        }
    }

    private var dimAmber: Color { Color(hex: 0xF7931A).opacity(0.12) }
    private var brightAmber: Color { Color(hex: 0xFFD83D).opacity(0.95) }

    private var avatarHandoff: some View {
        ZStack {
            if let shown = shownPubkey {
                PubkeyAvatarView(pubkey: shown, size: 92)
                    .id("avatar-\\(shown)")
                    .transition(
                        .asymmetric(
                            insertion: .scale(scale: 0.55).combined(with: .opacity),
                            removal: .scale(scale: 0.8).combined(with: .opacity)
                        )
                    )
            }
        }
        .animation(.spring(response: 0.45, dampingFraction: 0.6), value: shownPubkey)
    }

    private var checkBadge: some View {
        Image(systemName: AppIcons.check)
            .font(.system(size: 16, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: 34, height: 34)
            .background(Circle().fill(Color(hex: 0x10B981)))
            .offset(x: 34, y: 34)
            .transition(.scale(scale: 0.2).combined(with: .opacity))
    }
}
