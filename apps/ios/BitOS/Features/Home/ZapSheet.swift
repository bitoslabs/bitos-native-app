import BusinessCore
import SwiftUI

/// Zap sheet UI state (SOC-008 client path).
enum ZapPhase: Sendable, Equatable { case pickAmount, fetching, invoice, failed }

struct ZapUiState: Sendable, Equatable {
    var phase: ZapPhase = .pickAmount
    var amountSats: Int = 21
    var invoice: String?
    var failure: String?
    var busy = false
}

/**
 * Zap sheet: amount presets → signed kind-9734 zap request → LNURL invoice
 * for payment in any external wallet. No wallet connection lives here
 * (NWC is a separate safety-gated slice).
 */
struct ZapSheet: View {
    let note: FeedNote
    let profiles: [String: ProfileMetadata]
    let onClose: () -> Void
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @State private var state = ZapUiState()

    private static let presets = [21, 100, 1_000, 10_000]

    private var lud16: String? { profiles[note.pubkey]?.lud16 }

    private var bridge: BusinessCoreBridge {
        bridge ?? BusinessCoreBridge()
    }

    var body: some View {
        NavigationStack {
            content
                .padding(BitOSTheme.Spacing.screen)
                .background(BitOSTheme.background)
                .navigationTitle("Zap ⚡")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Close") { onClose() } }
                }
        }
        .preferredColorScheme(.dark)
        .onAppear { environment.feedStore.loadZaps(targetEventId: note.id) }
    }

    @ViewBuilder
    private var content: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            if lud16 == nil {
                Text("This author has no Lightning address in their profile, so zaps cannot be sent.")
                    .font(.footnote)
                    .foregroundStyle(BitOSTheme.textSecondary)
            } else if state.phase == .failed || state.failure != nil {
                Text(state.failure ?? "Zap failed.")
                    .font(.footnote)
                    .foregroundStyle(BitOSTheme.error)
            } else if state.phase == .invoice, let invoice = state.invoice {
                Text("Invoice ready — pay from any Lightning wallet:")
                    .font(.footnote)
                    .foregroundStyle(BitOSTheme.textSecondary)
                Text(invoice)
                    .font(.caption.monospaced())
                    .foregroundStyle(BitOSTheme.zap)
                    .lineLimit(4)
                    .padding(BitOSTheme.Spacing.base)
                    .frame(maxWidth: .infinity)
                    .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surfaceElevated))
                Button("Copy invoice") { UIPasteboard.general.string = invoice }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
            } else {
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    ForEach(Self.presets, id: \.self) { sats in
                        Button("\(sats)s") { state.amountSats = sats }
                            .buttonStyle(.bordered)
                            .tint(state.amountSats == sats ? BitOSTheme.accent : BitOSTheme.textSecondary)
                    }
                }
                Button(state.busy ? "Fetching invoice…" : "Zap \(state.amountSats) sats") {
                    Task { await zap() }
                }
                .buttonStyle(.borderedProminent)
                .tint(BitOSTheme.accent)
                .disabled(state.busy)
                Text("The zap request is signed with your key and sent to the recipient's Lightning server; payment happens in your wallet.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
            Spacer()
        }
    }

    private func zap() async {
        guard let lud16 else { return }
        state.phase = .fetching
        state.busy = true
        state.failure = nil
        do {
            let payRequest = try await fetchPayRequest(lud16: lud16)
            var nostrJson: String?
            if payRequest.allowsNostr, let account = identity.account {
                let now = Int64(Date.now.timeIntervalSince1970)
                if let eventId = bridge.composeZapRequestEventId(
                    recipientPubkey: note.pubkey, amountMillisats: Int64(state.amountSats) * 1000,
                    relays: DefaultRelays.urls.map(\.rawValue), lnurlHint: lud16, comment: "",
                    authorPubkey: account.pubkeyHex, targetEventId: note.id, nowSeconds: now
                ), let signature = await identity.signLocally(eventId),
                   let frame = bridge.zapRequestMessage(
                    recipientPubkey: note.pubkey, amountMillisats: Int64(state.amountSats) * 1000,
                    relays: DefaultRelays.urls.map(\.rawValue), lnurlHint: lud16, comment: "",
                    authorPubkey: account.pubkeyHex, targetEventId: note.id,
                    createdAtSeconds: now, signatureHex: signature
                   ) {
                    nostrJson = frame
                }
            }
            let invoice = try await fetchInvoice(payRequest: payRequest, amountMillisats: Int64(state.amountSats) * 1000, nostrJson: nostrJson, lud16: lud16)
            state.phase = .invoice
            state.invoice = invoice
            state.busy = false
        } catch {
            state.phase = .failed
            state.failure = error.localizedDescription
            state.busy = false
        }
    }

    struct LnurlError: Error { let message: String }

    private func fetchPayRequest(lud16: String) async throws -> LnurlPayParams {
        let parts = lud16.lowercased().split(separator: "@")
        guard parts.count == 2, !parts[0].isEmpty else { throw LnurlError(message: "invalid lightning address") }
        let (data, response) = try await URLSession.shared.data(from: URL(string: "https://\(parts[1])/.well-known/lnurlp/\(parts[0])")!)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw LnurlError(message: "LNURL server unreachable") }
        return try LnurlPayParams(jsonBody: String(data: data, encoding: .utf8) ?? "", bridge: bridge)
    }

    private func fetchInvoice(payRequest: LnurlPayParams, amountMillisats: Int64, nostrJson: String?, lud16: String) async throws -> String {
        guard let url = payRequest.callbackUrl(amountMillisats: amountMillisats, nostrJson: nostrJson, lud16: lud16, bridge: bridge) else {
            throw LnurlError(message: "amount out of range")
        }
        let (data, response) = try await URLSession.shared.data(from: url)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw LnurlError(message: "LNURL server unreachable") }
        return try InvoiceResponse(jsonBody: String(data: data, encoding: .utf8) ?? "", bridge: bridge).paymentRequest
    }
}

/// Thin wrappers over the shared LnurlPay parsing through the bridge.
private struct LnurlPayParams {
    let callback: String
    let minMillisats: Int64
    let maxMillisats: Int64
    let allowsNostr: Bool

    init(jsonBody: String, bridge: BusinessCoreBridge) throws {
        // Parse via the shared pure rules through a minimal bridge surface:
        // the bridge validates HTTPS, bounds, and tag == payRequest.
        guard let params = bridge.lnurlParsePayRequest(body: jsonBody) as? [Any],
              let callback = params[0] as? String,
              let min = (params[1] as? KotlinInt)?.int64Value,
              let max = (params[2] as? KotlinInt)?.int64Value else {
            throw ZapSheet.LnurlError(message: "invalid LNURL-pay response")
        }
        self.callback = callback
        self.minMillisats = min
        self.maxMillisats = max
        self.allowsNostr = (params[3] as? KotlinBoolean)?.boolValue ?? false
    }

    func callbackUrl(amountMillisats: Int64, nostrJson: String?, lud16: String, bridge: BusinessCoreBridge) -> URL? {
        let url = bridge.lnurlBuildCallbackUrl(
            callback: callback, amount: amountMillisats, minMillisats: minMillisats, maxMillisats: maxMillisats,
            allowsNostr: allowsNostr, nostrEvent: nostrJson, lnurlHint: lud16
        ) as? String
        return url.flatMap(URL.init(string:))
    }
}

private struct InvoiceResponse {
    let paymentRequest: String

    init(jsonBody: String, bridge: BusinessCoreBridge) throws {
        guard let pr = bridge.lnurlParseInvoice(body: jsonBody) else {
            throw ZapSheet.LnurlError(message: "no invoice returned")
        }
        self.paymentRequest = pr
    }
}
