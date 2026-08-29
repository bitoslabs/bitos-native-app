import CoreImage.CIFilterBuiltins
import SwiftUI

/**
 * T16 `lightning:` deep-link viewer (spec §1.2): display-only — the branded
 * QR plus copy, with an explicit note that BitOS never sees the payment
 * (the LNURL zap flow stays the interactive path; this link arrived from
 * outside, so nothing about it is trusted beyond display).
 */
struct LightningInvoiceSheet: View {
    let invoiceUri: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: BitOSTheme.Spacing.lg) {
                qrImage
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 208, height: 208)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md))
                Text("Scan with any Lightning wallet to pay.")
                    .font(.footnote)
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .multilineTextAlignment(.center)
                Text("BitOS never sees the payment.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textTertiary)
                Button {
                    UIPasteboard.general.string = invoiceUri
                } label: {
                    Label("Copy invoice", systemImage: AppIcons.copy)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color(red: 0.04, green: 0.04, blue: 0.06))
                        .padding(.horizontal, 18)
                        .padding(.vertical, 9)
                        .background(BitOSTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Copy invoice")
            }
            .padding(BitOSTheme.Spacing.xl)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(BitOSTheme.background)
            .navigationTitle("Lightning invoice")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                        .foregroundStyle(BitOSTheme.accent)
                }
            }
        }
    }

    /// CoreImage QR (same generator as the zap invoice card).
    private var qrImage: Image {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(invoiceUri.utf8)
        filter.correctionLevel = "M"
        if let output = filter.outputImage,
           let cgImage = context.createCGImage(output, from: output.extent) {
            return Image(uiImage: UIImage(cgImage: cgImage))
        }
        return Image(systemName: "xmark.qrcode")
    }
}
