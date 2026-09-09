import AVFoundation
import CoreGraphics
import UIKit

/// Blank-video source (plan `meme-blank-canvas-crossmode-plan.md` D1 /
/// MST-070): synthesizes a solid-color, silent H.264 MP4 — the "canvas
/// is the media" trick. The generated file is a perfectly ordinary clip:
/// written to a temp URL, probed, and appended through `appendClip` like
/// a camera take, so the whole M5 machinery (timeline, trim/split/speed/
/// volume/look, SFX mix, layers, export, publish, slots) works on it
/// unchanged.
///
/// Duration is exact because pixel buffers carry explicit presentation
/// times — synthesizing a 10 s canvas takes milliseconds, not 10 s.
/// Deterministic given (ratio, bg, duration): solid color compresses to
/// a few dozen KiB.
enum BlankClipSourceIos {

    enum BlankError: LocalizedError {
        case invalidInputs
        case writerFailed(String)
        var errorDescription: String? {
            switch self {
            case .invalidInputs: return "Invalid canvas inputs"
            case .writerFailed(let why): return "Canvas writer failed: \(why)"
            }
        }
    }

    private static let fps = 30
    private static let longEdge: CGFloat = 720
    private static let bitRate: Int = 1_000_000

    /// Writes the solid canvas clip to a NEW temp file URL and returns it.
    /// - Parameters:
    ///   - ratioId: `w:h` preset id from the shared `MemeCanvas` rules.
    ///   - bgHex: `#rrggbb` canvas background.
    ///   - durationMs: exact source length (1 s…60 s, snapped to 30 fps).
    static func create(ratioId: String, bgHex: String, durationMs: Int64) throws -> URL {
        let terms = ratioId.split(separator: ":").compactMap { Int($0) }
        guard terms.count == 2, terms[0] > 0, terms[1] > 0,
              bgHex.count == 7, bgHex.hasPrefix("#"),
              let rgb = Self.rgb(bgHex) else {
            throw BlankError.invalidInputs
        }
        let durationMs = min(max(durationMs, 1_000), 60_000)
        var width = longEdge
        var height = (longEdge * CGFloat(terms[1]) / CGFloat(terms[0])).rounded(.down)
        if height > longEdge {
            height = longEdge
            width = (longEdge * CGFloat(terms[0]) / CGFloat(terms[1])).rounded(.down)
        }
        // Even dims (encoder requirement).
        var w = Int(width) & ~1
        var h = Int(height) & ~1
        if w <= 0 { w = 2 }
        if h <= 0 { h = 2 }
        let totalFrames = max(1, Int((durationMs * Int64(fps)) / 1000))

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("blank-canvas-\(UUID().uuidString).mp4")
        try? FileManager.default.removeItem(at: url)

        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: w,
            AVVideoHeightKey: h,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: bitRate,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264MainAutoLevel,
            ],
        ]
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
                kCVPixelBufferWidthKey as String: w,
                kCVPixelBufferHeightKey as String: h,
            ]
        )
        writer.add(input)
        guard writer.startWriting() else {
            throw BlankError.writerFailed(writer.error?.localizedDescription ?? "startWriting")
        }
        writer.startSession(atSourceTime: .zero)

        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        UIColor(red: CGFloat(rgb.0) / 255, green: CGFloat(rgb.1) / 255, blue: CGFloat(rgb.2) / 255, alpha: 1)
            .getRed(&red, green: &green, blue: &blue, alpha: &alpha)

        var frame = 0
        while frame < totalFrames {
            if input.isReadyForMoreMediaData {
                guard let buffer = makePixelBuffer(width: w, height: h, red: red, green: green, blue: blue) else {
                    throw BlankError.writerFailed("pixel buffer")
                }
                let pts = CMTime(value: CMTimeValue(frame), timescale: CMTimeScale(fps))
                if !adaptor.append(buffer, withPresentationTime: pts) {
                    throw BlankError.writerFailed(writer.error?.localizedDescription ?? "append")
                }
                frame += 1
            } else {
                // Writer backpressure — yield the cooperative pool briefly.
                Thread.sleep(forTimeInterval: 0.002)
            }
        }
        input.markAsFinished()
        let semaphore = DispatchSemaphore(value: 0)
        writer.finishWriting { semaphore.signal() }
        semaphore.wait()
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: url)
            throw BlankError.writerFailed(writer.error?.localizedDescription ?? "finishWriting")
        }
        return url
    }

    /// `#rrggbb` → (r, g, b) bytes; nil when malformed.
    private static func rgb(_ hex: String) -> (UInt8, UInt8, UInt8)? {
        let chars = Array(hex.dropFirst())
        guard chars.count == 6,
              let value = UInt64(String(chars), radix: 16) else { return nil }
        return (UInt8((value >> 16) & 0xFF), UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF))
    }

    private static func makePixelBuffer(
        width: Int, height: Int, red: CGFloat, green: CGFloat, blue: CGFloat
    ) -> CVPixelBuffer? {
        var buffer: CVPixelBuffer?
        let attrs: [String: Any] = [
            kCVPixelBufferCGImageCompatibilityKey as String: true,
        ]
        CVPixelBufferCreate(
            kCFAllocatorDefault, width, height, kCVPixelFormatType_32ARGB, attrs as CFDictionary, &buffer
        )
        guard let buffer else { return nil }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
        let r = UInt8((red * 255).rounded())
        let g = UInt8((green * 255).rounded())
        let b = UInt8((blue * 255).rounded())
        let row = base.assumingMemoryBound(to: UInt32.self)
        for y in 0..<height {
            let line = row + (y * bytesPerRow / 4)
            for x in 0..<width {
                // 32ARGB, little-endian layout in memory: B, G, R, A.
                line[x] = 0xFF00_0000 | (UInt32(b) << 16) | (UInt32(g) << 8) | UInt32(r)
            }
        }
        return buffer
    }
}
