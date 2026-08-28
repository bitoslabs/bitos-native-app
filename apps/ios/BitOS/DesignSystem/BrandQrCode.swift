import BusinessCore
import SwiftUI

/**
 * Branded QR (APP-022; legacy Flutter `BrandQrCode` parity): white card,
 * gray-900 modules, bitcoin-orange hex cover with the bolt glyph. The
 * matrix comes from the shared pure encoder through the bridge —
 * deterministic and identical to Android.
 */
struct BrandQrCodeView: View {
    let value: String
    var size: CGFloat = 224

    var body: some View {
        let kotlinRows = BusinessCoreBridge().qrMatrix(text: value)
        let rows: [Int64] = kotlinRows.map { $0.int64Value }
        if rows.isEmpty { return AnyView(EmptyView()) }
        let n = rows.count
        return AnyView(
            ZStack {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.white)
                QrGridView(rows: rows, n: n)
                    .padding(10)
                BrandCover()
                    .frame(width: (size - 20) * 0.22, height: (size - 20) * 0.22)
            }
            .frame(width: size, height: size)
        )
    }
}

private struct QrGridView: View {
    let rows: [Int64]
    let n: Int

    var body: some View {
        Canvas { context, canvasSize in
            let cell = canvasSize.width / CGFloat(n + 1)
            let origin = cell / 2
            let moduleColor = Color(hex: 0x111827)
            for (y, rowBits) in rows.enumerated() {
                var bits = rowBits
                var x = 0
                while bits != 0 {
                    if bits & 1 != 0 {
                        let rect = CGRect(
                            x: origin + CGFloat(x) * cell,
                            y: origin + CGFloat(y) * cell,
                            width: cell + 0.5,
                            height: cell + 0.5
                        )
                        context.fill(Path(rect), with: .color(moduleColor))
                    }
                    bits >>= 1
                    x += 1
                }
            }
        }
    }
}

/// Orange hex disc with the white bolt glyph (scan-safe center cover).
private struct BrandCover: View {
    var body: some View {
        ZStack {
            Circle().fill(Color.white).padding(-6)
            HexShape()
                .fill(Color(hex: 0xF7931A))
            BoltGlyph()
                .fill(Color.white)
                .padding(10)
        }
    }
}

private struct BoltGlyph: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let w = rect.width
        let h = rect.height
        p.move(to: CGPoint(x: rect.minX + w * 0.60, y: rect.minY + h * 0.10))
        p.addLine(to: CGPoint(x: rect.minX + w * 0.25, y: rect.minY + h * 0.55))
        p.addLine(to: CGPoint(x: rect.minX + w * 0.52, y: rect.minY + h * 0.55))
        p.addLine(to: CGPoint(x: rect.minX + w * 0.40, y: rect.minY + h * 0.90))
        p.addLine(to: CGPoint(x: rect.minX + w * 0.75, y: rect.minY + h * 0.45))
        p.addLine(to: CGPoint(x: rect.minX + w * 0.48, y: rect.minY + h * 0.45))
        p.closeSubpath()
        return p
    }
}
