import SwiftUI

/**
 * NIP-13 proof-of-work UI (unified feature spec §3.8/§4, APP-008/APP-022):
 * `PowBadge` — compact difficulty indicator; `PowCard` — difficulty slider
 * + hash visualization + chunked background mining with progress, cancel
 * and result. Mining never runs on the UI thread: the card drives bounded
 * chunk windows through the injected miner (the note publisher seam on the
 * feature side). Reset the card identity (`.id(...)`) when content or
 * target changes — a mined nonce is valid for exactly one template.
 */

/// A completed mining session; publish must reuse `createdAt`.
struct PowOutcome: Equatable, Sendable {
    let nonce: Int64
    let idHex: String
    let targetDifficulty: Int
    let createdAt: Int64
}

/// Compact difficulty pill with segmented hash-strength bar.
struct PowBadge: View {
    let difficulty: Int

    private var filledSegments: Int { min(8, max(0, difficulty / 4)) }

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.xs) {
            Image(systemName: AppIcons.zap)
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(BitOSTheme.zap)
            Text("\(difficulty)-bit")
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(BitOSTheme.zap)
            HStack(spacing: 2) {
                ForEach(0..<8, id: \.self) { index in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(index < filledSegments ? BitOSTheme.zap : BitOSTheme.border)
                        .frame(width: 4, height: 8)
                }
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.sm)
        .padding(.vertical, 4)
        .background(Capsule().fill(BitOSTheme.zap.opacity(0.12)))
        .accessibilityLabel("Proof of work \(difficulty) bits")
    }
}

/// Difficulty selector + mining driver card (composer toolbar section).
/// Difficulty rank selector (PowCard's slider + segmented hash bars) for
/// flows where MINING HAPPENS ELSEWHERE — e.g. the meme post-details row,
/// which mines after the media upload (the imeta must be final before the
/// template exists).
struct PowDifficultySelector: View {
    @Binding var target: Int
    var enabled = true

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            Slider(
                value: Binding(
                    get: { Double(target) },
                    set: { target = Int($0) }
                ),
                in: 0...Double(PowCard.maxDifficulty),
                step: 1
            )
            .disabled(!enabled)
            .accessibilityLabel("Proof of work difficulty")
            .accessibilityValue("\(target) bits")
            hashViz
        }
    }

    /// Segmented hash visualization of the selected difficulty.
    private var hashViz: some View {
        let segments = 15
        let filled = target * segments / PowCard.maxDifficulty
        return HStack(spacing: 2) {
            ForEach(0..<segments, id: \.self) { index in
                RoundedRectangle(cornerRadius: 1)
                    .fill(index < filled ? BitOSTheme.accent.opacity(0.35 + 0.65 * Double(index) / Double(segments)) : BitOSTheme.divider)
                    .frame(height: 10)
                    .frame(maxWidth: .infinity)
            }
        }
    }
}

struct PowCard: View {
    @Binding var target: Int
    /// One bounded mining window: (createdAt, startNonce, attempts) → hit or nil.
    let mineChunk: (_ createdAt: Int64, _ startNonce: Int64, _ attempts: Int64) async -> (nonce: Int64, idHex: String)?
    let onMined: (PowOutcome?) -> Void

    static let maxDifficulty = 30
    private let chunkAttempts: Int64 = 20_000
    private let hardAttemptCap: Int64 = 5_000_000

    enum Phase: Equatable {
        case idle
        case mining(attempts: Int64)
        case mined(PowOutcome)
        case exhausted(attempts: Int64)
    }

    @State private var phase: Phase = .idle
    @State private var mineTask: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            header
            PowDifficultySelector(target: $target, enabled: !isMining)
            statusRow
        }
        .padding(BitOSTheme.Spacing.base)
        .background(
            RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                .fill(BitOSTheme.surface)
        )
        .onDisappear { mineTask?.cancel() }
    }

    // MARK: Sections

    private var header: some View {
        HStack {
            Text("Proof of work")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(BitOSTheme.textPrimary)
            Spacer()
            if case .mined(let outcome) = phase {
                PowBadge(difficulty: outcome.targetDifficulty)
            } else if target > 0 {
                Text("\(target) bits")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
        }
    }

    @ViewBuilder
    private var statusRow: some View {
        switch phase {
        case .idle:
            HStack {
                Text(target == 0 ? "Off — publishes without proof of work." : "Optional; higher targets take exponentially longer.")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
                Spacer()
                if target > 0 {
                    Button("Mine") { startMining() }
                        .font(.system(size: 13, weight: .semibold))
                        .buttonStyle(.bordered)
                        .tint(BitOSTheme.zap)
                        .fixedSize()
                }
            }
        case .mining(let attempts):
            HStack {
                ProgressView()
                    .controlSize(.small)
                Text("Mining… \(attempts.formatted()) hashes")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textSecondary)
                Spacer()
                Button("Cancel") {
                    mineTask?.cancel()
                    phase = .idle
                }
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(BitOSTheme.error)
                .fixedSize()
            }
        case .mined(let outcome):
            VStack(alignment: .leading, spacing: 4) {
                Text("Mined nonce \(outcome.nonce) — id \(outcome.idHex.prefix(12))…")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(BitOSTheme.textSecondary)
                Button("Clear") {
                    phase = .idle
                    onMined(nil)
                }
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(BitOSTheme.textTertiary)
            }
        case .exhausted(let attempts):
            HStack(spacing: 6) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.warning)
                Text("No nonce found after \(attempts.formatted()) hashes at \(target) bits. Lower the target and retry.")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.warning)
                Spacer()
                Button("Retry") { startMining() }
                    .font(.system(size: 13, weight: .semibold))
                    .buttonStyle(.bordered)
                    .tint(BitOSTheme.warning)
                    .fixedSize()
            }
        }
    }

    // MARK: Mining driver

    private var isMining: Bool {
        if case .mining = phase { return true }
        return false
    }

    private func startMining() {
        guard case .idle = phase, target > 0 else { return }
        let target = self.target
        let createdAt = Int64(Date.now.timeIntervalSince1970)
        let miner = mineChunk
        let chunkAttempts = self.chunkAttempts
        let hardCap = self.hardAttemptCap
        phase = .mining(attempts: 0)

        mineTask = Task {
            var start: Int64 = 0
            var total: Int64 = 0
            while !Task.isCancelled {
                guard let hit = await miner(createdAt, start, chunkAttempts) else {
                    start += chunkAttempts
                    total += chunkAttempts
                    if total >= hardCap {
                        await MainActor.run {
                            phase = .exhausted(attempts: total)
                        }
                        return
                    }
                    await MainActor.run {
                        phase = .mining(attempts: total)
                    }
                    continue
                }
                let outcome = PowOutcome(
                    nonce: hit.nonce,
                    idHex: hit.idHex,
                    targetDifficulty: target,
                    createdAt: createdAt
                )
                await MainActor.run {
                    phase = .mined(outcome)
                    onMined(outcome)
                }
                return
            }
        }
    }
}
