import BusinessCore
import SwiftUI

/**
 * Support/donate sheet (legacy Flutter `SupportWidget` parity): the official
 * support npub's kind-0 → lud16 → LNURL-pay invoice for the chosen tier
 * (coffee / expert★ / production / premium + custom), with QR + copy +
 * open-wallet. No custodian: sats go straight to the project's address.
 */
struct SupportDonateSheet: View {
    let supportNpub: String
    let tiers: [(sats: Int, recommended: Bool)]
    let lookup: ProfileLookupStore
    let onDismiss: () -> Void

    @State private var selectedSats: Int
    @State private var customSats = ""
    @State private var invoice: String?
    @State private var invoiceBusy = false
    @State private var invoiceError: String?
    @State private var copied = false

    init(supportNpub: String,
         tiers: [(sats: Int, recommended: Bool)],
         lookup: ProfileLookupStore,
         onDismiss: @escaping () -> Void) {
        self.supportNpub = supportNpub
        self.tiers = tiers
        self.lookup = lookup
        self.onDismiss = onDismiss
        _selectedSats = State(initialValue: tiers.first(where: { $0.recommended })?.sats ?? tiers.first!.sats)
    }

    private var supportPubkey: String? {
        BusinessCoreBridge().parseNpub(encoded: supportNpub)
    }

    private var profile: ProfileMetadata? {
        supportPubkey.flatMap { lookup.profiles[$0] }
    }

    private var lud16: String? {
        profile?.lud16.flatMap { $0.isEmpty ? nil : $0 }
    }

    private var amount: Int {
        Int(customSats).flatMap { $0 > 0 ? $0 : nil } ?? selectedSats
    }

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            Text("Support BitOS")
                .font(.system(size: 20, weight: .bold))
            if lookup.loading && lud16 == nil {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    Text("Resolving the project Lightning address\\u{2026}")
                        .font(.system(size: 13))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            } else if let lud16 {
                if let invoice {
                    invoicePanel(invoice, lud16: lud16)
                } else {
                    tierPanel(lud16: lud16)
                }
            } else {
                Text("Couldn't resolve a Lightning address for the support account. Try again later.")
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.error)
            }
            Button("Close", action: onDismiss)
                .foregroundStyle(BitOSTheme.textSecondary)
        }
        .padding(BitOSTheme.Spacing.base)
        .background(BitOSTheme.background)
        .task {
            lookup.start()
            if let supportPubkey {
                lookup.lookup(pubkeys: [supportPubkey])
            }
        }
    }

    // MARK: - Tier selection

    private func tierPanel(lud16: String) -> some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            Text("\\u{26A1} \\(lud16)")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(BitOSTheme.textTertiary)
            HStack(spacing: 8) {
                ForEach(tiers, id: \.sats) { tier in
                    let selected = customSats.isEmpty && selectedSats == tier.sats
                    Button {
                        selectedSats = tier.sats
                        customSats = ""
                    } label: {
                        VStack(spacing: 2) {
                            if tier.recommended {
                                Text("\\u{2605}").font(.system(size: 10)).foregroundStyle(BitOSTheme.warning)
                            }
                            Text("\(tier.sats)")
                                .font(.system(size: 15, weight: .bold, design: .monospaced))
                            Text("sats").font(.system(size: 10))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(
                            selected ? BitOSTheme.accent.opacity(0.14)
                                : (tier.recommended ? BitOSTheme.accent.opacity(0.07)
                                   : BitOSTheme.surfaceOverlay.opacity(0.5)),
                            in: RoundedRectangle(cornerRadius: 12)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            TextField("Custom amount (sats)", text: $customSats)
                .keyboardType(.numberPad)
                .font(.system(size: 13, design: .monospaced))
                .textFieldStyle(.roundedBorder)
                .onChange(of: customSats) { _, next in
                    customSats = String(next.prefix(7)).filter(\.isNumber)
                }
            if let invoiceError {
                Text(invoiceError).font(.system(size: 12)).foregroundStyle(BitOSTheme.error)
            }
            Button {
                fetchInvoice(lud16: lud16)
            } label: {
                HStack(spacing: 8) {
                    if invoiceBusy { ProgressView().controlSize(.small) }
                    Text("Support with \\(amount) sats")
                        .font(.system(size: 15, weight: .semibold))
                }
                .foregroundStyle(BitOSTheme.accent)
            }
            .disabled(invoiceBusy)
        }
    }

    // MARK: - Invoice panel

    private func invoicePanel(_ invoice: String, lud16: String) -> some View {
        VStack(spacing: BitOSTheme.Spacing.sm) {
            InvoiceQrView(invoice: invoice)
                .frame(width: 210, height: 210)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
            Text("Pay \\(amount) sats in any Lightning wallet.")
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.textSecondary)
            HStack {
                Button {
                    UIPasteboard.general.string = invoice
                    copied = true
                } label: {
                    Label(copied ? "Copied" : "Copy invoice", systemImage: copied ? AppIcons.check : AppIcons.copy)
                        .foregroundStyle(BitOSTheme.accent)
                }
                Button("Open wallet") {
                    if let url = URL(string: "lightning:\\(invoice)") {
                        UIApplication.shared.open(url)
                    }
                }
                .foregroundStyle(BitOSTheme.accent)
            }
        }
    }

    // MARK: - LNURL (mirror of ZapSheet's minimal client)

    private func fetchInvoice(lud16: String) {
        invoiceBusy = true
        invoiceError = nil
        Task {
            defer { invoiceBusy = false }
            do {
                let parts = lud16.lowercased().split(separator: "@")
                guard parts.count == 2 else { throw Err("invalid lightning address") }
                let meta = try await httpString("https://\\(parts[1])/.well-known/lnurlp/\\(parts[0])")
                guard let pr = try await invoiceFromCallback(meta, millisats: Int64(amount) * 1000) else {
                    throw Err("no invoice returned")
                }
                invoice = pr
            } catch {
                invoiceError = "LNURL server unreachable."
            }
        }
    }

    private struct Err: LocalizedError { let message: String; init(_ m: String) { message = m } }

    private func httpString(_ urlString: String) async throws -> String {
        guard let url = URL(string: urlString) else { throw Err("bad url") }
        let (data, response) = try await URLSession.shared.data(from: url)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw Err("unreachable") }
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// Minimal LNURL-pay: parse callback+minSendable from the metadata JSON,
    /// then hit the callback with amount + empty comment.
    private func invoiceFromCallback(_ metaJson: String, millisats: Int64) async throws -> String? {
        guard let data = metaJson.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let callback = obj["callback"] as? String,
              let minSendable = obj["minSendable"] as? Int64,
              millisats >= minSendable else { return nil }
        let sep = callback.contains("?") ? "&" : "?"
        let url = "\\(callback)\\(sep)amount=\\(millisats)"
        let body = try await httpString(url)
        guard let bodyData = body.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: bodyData) as? [String: Any],
              let pr = parsed["pr"] as? String else { return nil }
        return pr
    }
}

/// Invoice QR via CoreImage (long payloads beyond the shared V1-6 encoder).
struct InvoiceQrView: View {
    let invoice: String

    var body: some View {
        if let image = qrImage {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .padding(10)
        }
    }

    private var qrImage: UIImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(invoice.uppercased().utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let context = CIContext()
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
