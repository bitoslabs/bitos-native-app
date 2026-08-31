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
    /** APP-014 LUD-21: provider verify URL + settle signal. */
    var verifyUrl: String?
    var verifySettled = false
}

/**
 * Zap sheet: amount presets → signed kind-9734 zap request → LNURL invoice
 * for payment in any external wallet. No wallet connection lives here
 * (NWC is a separate safety-gated slice).
 */
struct ZapSheet: View {
    /** Target note for a note zap; nil for a profile zap (NIP-57 p-tag only). */
    let note: FeedNote?
    /** Recipient in both modes: the note author or the zapped profile. */
    let recipientPubkey: String
    let profiles: [String: ProfileMetadata]
    let initialAmountSats: Int
    var zapCount: Int = 0
    /** APP-014: verified request ids that landed for this note (paid match). */
    var paidRequestIds: Set<String> = []
    /** APP-014: records into the sent ledger when payment confirms. */
    var onPaid: (Int, String) -> Void = { _, _ in }
    let onClose: () -> Void
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @State private var state: ZapUiState
    @State private var customAmount = ""
    @State private var comment = ""
    @State private var anonymous = false
    @State private var copiedKind: String?
    @State private var paid = false
    @State private var zapCountAtInvoice = -1
    @State private var requestId: String?
    @State private var verifyTask: Task<Void, Never>?
    @State private var nowSeconds = Int64(Date.now.timeIntervalSince1970)
    private let bridge = BusinessCoreBridge()

    private static let presets = [21, 100, 500, 1000]
    private static let autoCloseNanos: UInt64 = 2_400_000_000

    /// APP-018 functional setting: opens on the persisted default zap amount.
    init(note: FeedNote, profiles: [String: ProfileMetadata],
         initialAmountSats: Int = 21, zapCount: Int = 0,
         paidRequestIds: Set<String> = [],
         onPaid: @escaping (Int, String) -> Void = { _, _ in },
         onClose: @escaping () -> Void) {
        self.note = note
        self.recipientPubkey = note.pubkey
        self.profiles = profiles
        self.initialAmountSats = initialAmountSats
        self.zapCount = zapCount
        self.paidRequestIds = paidRequestIds
        self.onPaid = onPaid
        self.onClose = onClose
        _state = State(initialValue: ZapUiState(amountSats: initialAmountSats))
    }

    /// Profile zap (no target note): the kind-9734 request carries only the
    /// `p` tag; paid detection rides the LUD-21 verify settle alone.
    init(authorPubkey: String, profile: ProfileMetadata?,
         initialAmountSats: Int = 21,
         onPaid: @escaping (Int, String) -> Void = { _, _ in },
         onClose: @escaping () -> Void) {
        self.note = nil
        self.recipientPubkey = authorPubkey
        self.profiles = [authorPubkey: profile].compactMapValues { $0 }
        self.initialAmountSats = initialAmountSats
        self.zapCount = 0
        self.paidRequestIds = []
        self.onPaid = onPaid
        self.onClose = onClose
        _state = State(initialValue: ZapUiState(amountSats: initialAmountSats))
    }

    private var lud16: String? { profiles[recipientPubkey]?.lud16 }
    private var displayName: String? { profiles[recipientPubkey]?.bestDisplayName }
    private var amount: Int {
        Int(customAmount).flatMap { $0 > 0 ? $0 : nil } ?? state.amountSats
    }
    private var expiry: Int64 {
        state.invoice.map { bridge.bolt11ExpirySeconds(invoice: $0) } ?? 0
    }
    private var secondsLeft: Int64 { max(0, expiry - nowSeconds) }
    private var expired: Bool { state.invoice != nil && !paid && expiry > 0 && secondsLeft == 0 }

    var body: some View {
        NavigationStack {
            content
                .padding(BitOSTheme.Spacing.screen)
                .background(BitOSTheme.background)
                .navigationTitle("Zap ⚡")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { SheetCloseButton(action: onClose) }
                }
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
        .onAppear {
            // Profile zaps have no note receipt stream to watch (LUD-21 only).
            if let note { environment.feedStore.loadZaps(targetEventId: note.id) }
        }
        .onChange(of: zapCount) { _, count in
            // Paid: EXACT request-id match when we signed a 9734;
            // unsigned/anonymous keeps the count signal.
            if !paid && state.invoice != nil && zapCountAtInvoice >= 0 && count > zapCountAtInvoice {
                paid = true
            }
        }
        .onChange(of: paidRequestIds) { _, ids in
            if !paid, state.invoice != nil, let requestId, ids.contains(requestId) {
                paid = true
            }
        }
        .onChange(of: state.verifySettled) { _, settled in
            if settled && !paid && state.invoice != nil {
                paid = true
            }
        }
        .onDisappear { verifyTask?.cancel() }
        .onChange(of: state.invoice) { _, invoice in
            if invoice != nil { zapCountAtInvoice = zapCount }
        }
        .task(id: state.invoice) {
            guard state.invoice != nil else { return }
            while !Task.isCancelled {
                nowSeconds = Int64(Date.now.timeIntervalSince1970)
                try? await Task.sleep(for: .seconds(1))
            }
        }
        .task(id: paid) {
            guard paid else { return }
            onPaid(amount, comment.trimmingCharacters(in: .whitespacesAndNewlines))
            try? await Task.sleep(nanoseconds: Self.autoCloseNanos)
            onClose()
        }
    }

    @ViewBuilder
    private var content: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            ZapHeaderView(
                paid: paid,
                recipientName: displayName,
                recipientPubkey: recipientPubkey,
                recipientPicture: profiles[recipientPubkey]?.picture,
                lud16: lud16,
                copiedKind: copiedKind,
                onCopyAddress: {
                    UIPasteboard.general.string = lud16 ?? ""
                    copiedKind = "address"
                },
                onClose: onClose
            )
            if paid {
                PaidCard(
                    amount: amount,
                    name: displayName,
                    comment: comment,
                    bridge: bridge
                )
            } else if lud16 == nil {
                Text("This author has no Lightning address in their profile, so zaps cannot be sent.")
                    .font(.footnote)
                    .foregroundStyle(BitOSTheme.textSecondary)
            } else if state.phase == .failed || state.failure != nil {
                Text(state.failure ?? "Zap failed.")
                    .font(.footnote)
                    .foregroundStyle(BitOSTheme.error)
            } else if state.phase == .invoice, let invoice = state.invoice {
                InvoiceCard(
                    invoice: invoice,
                    amount: amount,
                    secondsLeft: secondsLeft,
                    expired: expired,
                    busy: state.busy,
                    copiedKind: copiedKind,
                    bridge: bridge,
                    onCopy: {
                        UIPasteboard.general.string = invoice
                        copiedKind = "invoice"
                    },
                    onOpenWallet: {
                        if let url = URL(string: "lightning:\(invoice)") {
                            UIApplication.shared.open(url)
                        } else {
                            UIPasteboard.general.string = invoice
                            copiedKind = "invoice"
                        }
                    }
                )
            } else {
                AmountStepView(
                    presets: Self.presets,
                    selected: state.amountSats,
                    custom: $customAmount,
                    comment: $comment,
                    anonymous: $anonymous,
                    hasIdentity: identity.account != nil,
                    amount: amount,
                    busy: state.busy,
                    bridge: bridge,
                    onSelect: { sats in
                        customAmount = ""
                        state.amountSats = sats
                    },
                    onZap: { Task { await zap() } }
                )
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
            if payRequest.allowsNostr, let account = identity.account, !anonymous {
                let now = Int64(Date.now.timeIntervalSince1970)
                if let eventId = bridge.composeZapRequestEventId(
                    recipientPubkey: recipientPubkey, amountMillisats: Int64(amount) * 1000,
                    relays: DefaultRelays.urls.map(\.rawValue), lnurlHint: lud16, comment: comment,
                    authorPubkey: account.pubkeyHex, targetEventId: note?.id, nowSeconds: now
                ), let signature = await identity.signLocally(eventId),
                   // LUD-06: the `nostr` param is the BARE signed event
                   // object `{...}` — never a relay `["EVENT",…]` frame
                   // (APP-014 fix: servers reject frames outright).
                   let frame = bridge.zapRequestEventJson(
                    recipientPubkey: recipientPubkey, amountMillisats: Int64(amount) * 1000,
                    relays: DefaultRelays.urls.map(\.rawValue), lnurlHint: lud16, comment: comment,
                    authorPubkey: account.pubkeyHex, targetEventId: note?.id,
                    createdAtSeconds: now, signatureHex: signature
                   ) {
                    nostrJson = frame
                    requestId = eventId
                }
            }
            let invoice = try await fetchInvoice(payRequest: payRequest, amountMillisats: Int64(amount) * 1000, nostrJson: nostrJson, lud16: lud16)
            state.phase = .invoice
            state.invoice = invoice.bolt11
            state.verifyUrl = invoice.verifyUrl
            state.busy = false
            // LUD-21 settle polling (legacy `pollVerify` parity): the QR /
            // external-wallet payment signal — races the 9735 watch.
            if let verifyUrl = invoice.verifyUrl {
                verifyTask?.cancel()
                let expiry = bridge.bolt11ExpirySeconds(invoice: invoice.bolt11)
                let clientBridge = bridge
                verifyTask = Task { @MainActor in
                    while !Task.isCancelled,
                          expiry <= 0 || Int64(Date.now.timeIntervalSince1970) < expiry {
                        try? await Task.sleep(for: .seconds(3))
                        guard !Task.isCancelled else { return }
                        if let (data, response) = try? await URLSession.shared.data(from: URL(string: verifyUrl)!),
                           (response as? HTTPURLResponse)?.statusCode == 200,
                           let body = String(data: data, encoding: .utf8),
                           clientBridge.lnurlVerifySettled(body: body) {
                            state.verifySettled = true
                            return
                        }
                    }
                }
            }
        } catch {
            state.phase = .failed
            state.failure = error.localizedDescription
            state.busy = false
        }
    }

    struct LnurlError: Error { let message: String }

    private func fetchPayRequest(lud16: String) async throws -> LnurlPayParams {
        // Shared LUD-16 rule (`LnurlPay.payEndpointUrl`): domain lowercased,
        // local part preserved and percent-encoded (APP-014 fix — the old
        // whole-string lowercase built wrong URLs; some providers are
        // case-sensitive and unencoded parts break hosts).
        guard let endpoint = bridge.lnurlPayEndpointUrl(lud16: lud16), let url = URL(string: endpoint) else {
            throw LnurlError(message: "invalid lightning address")
        }
        let (data, response) = try await URLSession.shared.data(from: url)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw LnurlError(message: "Could not reach the Lightning provider.")
        }
        let body = String(data: data, encoding: .utf8) ?? ""
        do {
            return try LnurlPayParams(jsonBody: body, bridge: bridge)
        } catch {
            throw LnurlError(message: bridge.lnurlProviderError(body: body) ?? "The Lightning provider rejected the request.")
        }
    }

    private func fetchInvoice(payRequest: LnurlPayParams, amountMillisats: Int64, nostrJson: String?, lud16: String) async throws -> (bolt11: String, verifyUrl: String?) {
        guard let url = payRequest.callbackUrl(amountMillisats: amountMillisats, nostrJson: nostrJson, lud16: lud16, bridge: bridge) else {
            throw LnurlError(message: bridge.lnurlAmountFailure(
                minMillisats: payRequest.minMillisats, maxMillisats: payRequest.maxMillisats, amountMillisats: amountMillisats
            ) ?? "invalid zap request")
        }
        let (data, response) = try await URLSession.shared.data(from: url)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw LnurlError(message: "Could not create a Lightning invoice.")
        }
        let body = String(data: data, encoding: .utf8) ?? ""
        let parsed: InvoiceResponse
        do {
            parsed = try InvoiceResponse(jsonBody: body, bridge: bridge)
        } catch {
            throw LnurlError(message: bridge.lnurlProviderError(body: body) ?? "No invoice was returned.")
        }
        let verifyUrl = bridge.lnurlInvoiceVerifyUrl(body: body).flatMap { URL(string: $0) == nil ? nil : $0 }
        return (parsed.paymentRequest, verifyUrl)
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
        guard let params = bridge.lnurlParsePayRequest(body: jsonBody),
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
        )
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

// MARK: - APP-014 legacy-parity zap sheet components

private struct ZapHeaderView: View {
    let paid: Bool
    let recipientName: String?
    let recipientPubkey: String
    var recipientPicture: String? = nil
    let lud16: String?
    let copiedKind: String?
    let onCopyAddress: () -> Void
    let onClose: () -> Void

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            VStack(alignment: .leading, spacing: 1) {
                Text(paid ? "Zap sent" : "Zap ⚡")
                    .font(.system(size: 19, weight: .bold))
                HStack(spacing: 4) {
                    Text("⚡").font(.system(size: 11))
                    Text("zapping \(recipientName ?? String(recipientPubkey.prefix(8)) + "…")")
                        .font(.footnote)
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .lineLimit(1)
                }
            }
            Spacer()
            PubkeyAvatarView(
                pubkey: recipientPubkey,
                size: 40,
                picture: recipientPicture,
                label: recipientName,
                hasLightning: lud16 != nil
            )
            if lud16 != nil {
                Button(action: onCopyAddress) {
                    Text(copiedKind == "address" ? "✓" : "⌗")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(copiedKind == "address" ? BitOSTheme.success : BitOSTheme.textSecondary)
                        .frame(width: 26, height: 26)
                }
                .accessibilityLabel("Copy Lightning address")
            }
            SheetCloseButton(action: onClose)
        }
    }
}

private struct AmountStepView: View {
    let presets: [Int]
    let selected: Int
    @Binding var custom: String
    @Binding var comment: String
    @Binding var anonymous: Bool
    let hasIdentity: Bool
    let amount: Int
    let busy: Bool
    let bridge: BusinessCoreBridge
    let onSelect: (Int) -> Void
    let onZap: () -> Void

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.sm) {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                ForEach(presets, id: \.self) { sats in
                    AmountTileView(
                        sats: sats,
                        selected: sats == selected && custom.isEmpty,
                        bridge: bridge
                    ) { onSelect(sats) }
                }
            }
            HStack {
                BitosField("Custom amount", text: $custom)
                    .keyboardType(.numberPad)
                    .onChange(of: custom) { _, value in
                        custom = String(value.filter(\.isNumber).prefix(8))
                    }
                Text(bridge.zapEmoji(sats: Int64(amount)))
                    .font(.system(size: 16))
            }
            .padding(.horizontal, BitOSTheme.Spacing.md)
            .padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 12).fill(BitOSTheme.surfaceElevated))
            BitosField("Add a comment… (\(comment.count)/200)", text: $comment)
                .onChange(of: comment) { _, value in
                    if value.count > 200 { comment = String(value.prefix(200)) }
                }
                .padding(.horizontal, BitOSTheme.Spacing.md)
                .padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 12).fill(BitOSTheme.surfaceElevated))
            if hasIdentity {
                Toggle(isOn: $anonymous) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Zap anonymously").font(.system(size: 13, weight: .semibold))
                        Text("Hide your key from the zap receipt")
                            .font(.system(size: 10))
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                }
                .toggleStyle(.switch)
                .tint(BitOSTheme.accent)
            } else {
                Text("Signed out — this zap will be anonymous.")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button(action: onZap) {
                HStack(spacing: 6) {
                    if busy {
                        ProgressView().tint(.white)
                    } else {
                        Text(bridge.zapEmoji(sats: Int64(amount)))
                        Text("Zap \(bridge.zapFormatSats(sats: Int64(amount))) sats")
                            .font(.system(size: 14, weight: .heavy))
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 12).fill(busy ? BitOSTheme.zap.opacity(0.6) : BitOSTheme.zap))
                .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
            .disabled(busy)
            Text("The zap request is signed with your key and sent to the recipient's Lightning server; payment happens in your wallet.")
                .font(.system(size: 11))
                .foregroundStyle(BitOSTheme.textTertiary)
        }
    }
}

private struct AmountTileView: View {
    let sats: Int
    let selected: Bool
    let bridge: BusinessCoreBridge
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: 2) {
                Text(bridge.zapEmoji(sats: Int64(sats))).font(.system(size: 16))
                Text(bridge.zapFormatSats(sats: Int64(sats)))
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(selected ? BitOSTheme.zap : BitOSTheme.textPrimary)
                Text("sats")
                    .font(.system(size: 9))
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 72)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(selected ? BitOSTheme.zap.opacity(0.12) : BitOSTheme.surfaceElevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(selected ? BitOSTheme.zap.opacity(0.5) : BitOSTheme.divider,
                                  lineWidth: selected ? 1.4 : 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Zap \(sats) sats")
    }
}

private struct InvoiceCard: View {
    let invoice: String
    let amount: Int
    let secondsLeft: Int64
    let expired: Bool
    let busy: Bool
    let copiedKind: String?
    let bridge: BusinessCoreBridge
    let onCopy: () -> Void
    let onOpenWallet: () -> Void

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            HStack {
                Text(expired ? "Invoice expired" : "Invoice ready · \(bridge.zapFormatSats(sats: Int64(amount))) sats")
                    .font(.system(size: 14, weight: .bold))
                Spacer()
                if !expired && secondsLeft > 0 {
                    let urgent = secondsLeft < 120
                    Text(String(format: "%d:%02d", secondsLeft / 60, secondsLeft % 60))
                        .font(.system(size: 12, weight: .bold).monospaced())
                        .foregroundStyle(urgent ? BitOSTheme.warning : BitOSTheme.textSecondary)
                }
            }
            .frame(maxWidth: .infinity)
            if let qr = zapQrImage(invoice) {
                Image(uiImage: qr)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 190, height: 190)
                    .background(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .opacity(expired ? 0.35 : 1)
                    .accessibilityLabel("Payment QR code")
            }
            HStack(spacing: 4) {
                Text("⚡").font(.system(size: 12))
                Text("\(bridge.zapFormatSats(sats: Int64(amount))) sats")
                    .font(.system(size: 13, weight: .heavy))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, BitOSTheme.Spacing.md)
            .padding(.vertical, 5)
            .background(Capsule().fill(BitOSTheme.zap))
            if expired {
                Button(action: onOpenWallet) {
                    Text(busy ? "Creating…" : "New invoice")
                        .font(.system(size: 13, weight: .semibold))
                }
                .disabled(busy)
            } else {
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    Button(action: onOpenWallet) {
                        Text("Open wallet")
                            .font(.system(size: 13, weight: .bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 9)
                            .background(RoundedRectangle(cornerRadius: 12).fill(BitOSTheme.zap))
                            .foregroundStyle(.white)
                    }
                    .buttonStyle(.plain)
                    Button(action: onCopy) {
                        Text(copiedKind == "invoice" ? "✓" : "Copy")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(BitOSTheme.accent)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 9)
                            .background(RoundedRectangle(cornerRadius: 12).strokeBorder(BitOSTheme.divider))
                    }
                    .buttonStyle(.plain)
                }
                Text(invoice.prefix(16) + "…" + invoice.suffix(8))
                    .font(.system(size: 10).monospaced())
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
        }
        .padding(BitOSTheme.Spacing.md)
        .background(RoundedRectangle(cornerRadius: 16).fill(BitOSTheme.surfaceElevated))
    }

    /// CoreImage QR (payment-grade).
    private func zapQrImage(_ value: String) -> UIImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator"),
              let data = value.uppercased().data(using: .utf8) else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

private struct PaidCard: View {
    let amount: Int
    let name: String?
    let comment: String
    let bridge: BusinessCoreBridge
    @State private var remaining: CGFloat = 1

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            ZStack {
                Circle().fill(BitOSTheme.success.opacity(0.12)).frame(width: 56, height: 56)
                Text("✓").font(.system(size: 26, weight: .heavy)).foregroundStyle(BitOSTheme.success)
            }
            Text("Sent \(bridge.zapFormatSats(sats: Int64(amount))) sats to \(name ?? "the author")")
                .font(.system(size: 15, weight: .bold))
            Text("Your zap is on the relays.")
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.textSecondary)
            if !comment.trimmingCharacters(in: .whitespaces).isEmpty {
                Text("“\(comment)”")
                    .font(.system(size: 12))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(BitOSTheme.success.opacity(0.15))
                    Capsule().fill(BitOSTheme.success).frame(width: geo.size.width * remaining)
                }
            }
            .frame(width: 220, height: 3)
            Text("Closing…").font(.system(size: 10)).foregroundStyle(BitOSTheme.textTertiary)
        }
        .task {
            for _ in 0..<48 {
                try? await Task.sleep(nanoseconds: 50_000_000)
                remaining -= 0.05
            }
        }
    }
}
