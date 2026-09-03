import ImageIO
import UIKit
import UniformTypeIdentifiers

/// GIF frame source for the Quick MEM editor (plan MST-020; iOS split per
/// plan §4.2): CGImageSource decode with per-frame delays from the GIF
/// properties. Frames composite CUMULATIVELY onto a canvas (disposal 1 —
/// the overwhelmingly common meme-GIF layout); partial-frame disposal-2
/// sources may ghost, an accepted V1 caveat (the Android/web path uses the
/// shared pure decoder with full disposal handling). Holds clamp to the
/// 20 ms floor; sub-2cs delays become 100 ms (browser heuristic parity).
enum GifFrameSourceIos {

    struct Frames {
        let images: [UIImage]
        let delaysMs: [Int]
        var count: Int { images.count }
        var isAnimated: Bool { images.count > 1 }
    }

    static let stillDelayMs = 100
    static let minDelayMs = 20

    /// Decodes GIF bytes into composited frames + per-frame holds.
    static func decode(_ data: Data) -> Frames? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let frameCount = CGImageSourceGetCount(source)
        guard frameCount > 0 else { return nil }

        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let widthRaw = properties[kCGImagePropertyPixelWidth] as? NSNumber,
              let heightRaw = properties[kCGImagePropertyPixelHeight] as? NSNumber,
              let first = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        _ = first
        let width = widthRaw.intValue
        let height = heightRaw.intValue
        guard width > 0, height > 0 else { return nil }

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        var canvas = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { _ in }
        var images: [UIImage] = []
        var delays: [Int] = []

        for index in 0..<frameCount {
            guard let frame = CGImageSourceCreateImageAtIndex(source, index, nil) else { continue }
            let delay = delayMs(source: source, index: index)
            let composited = UIGraphicsImageRenderer(
                size: CGSize(width: width, height: height), format: format
            ).image { context in
                canvas.draw(at: .zero)
                context.cgContext.interpolationQuality = .none
                context.cgContext.draw(frame, in: CGRect(
                    x: 0, y: 0, width: CGFloat(frame.width), height: CGFloat(frame.height)
                ))
            }
            canvas = composited
            images.append(composited)
            delays.append(delay)
        }

        guard !images.isEmpty else { return nil }
        return Frames(images: images, delaysMs: delays)
    }

    /// Static image picked in GIF mode: one 100 ms still frame.
    static func stillFrame(_ data: Data) -> Frames? {
        guard let image = UIImage(data: data) else { return nil }
        return Frames(images: [image], delaysMs: [stillDelayMs])
    }

    private static func delayMs(source: CGImageSource, index: Int) -> Int {
        guard let frameProps = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any],
              let gif = frameProps[kCGImagePropertyGIFDictionary] as? [CFString: Any] else {
            return stillDelayMs
        }
        let seconds: Double
        if let unclamped = (gif[kCGImagePropertyGIFUnclampedDelayTime] as? NSNumber)?.doubleValue,
           unclamped > 0 {
            seconds = unclamped
        } else if let clamped = (gif[kCGImagePropertyGIFDelayTime] as? NSNumber)?.doubleValue,
                  clamped > 0 {
            seconds = clamped
        } else {
            seconds = 0
        }
        let ms = Int((seconds * 1000).rounded())
        // Sub-2cs holds render as 10cs in browsers; clamp to the floor.
        return if ms < minDelayMs { stillDelayMs } else { min(ms, 1000) }
    }
}
