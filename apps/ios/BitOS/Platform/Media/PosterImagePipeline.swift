import Foundation
import ImageIO
import Observation
import UIKit

/**
 * Process-owned, bounded poster loader. Requests share in-flight work, use the
 * URL cache, prepare display-sized images before publication, and retain at
 * most 96 / 48 MiB of decoded thumbnails.
 */
@MainActor
@Observable
final class PosterImagePipeline {
    @ObservationIgnored private let cache = NSCache<NSString, UIImage>()
    @ObservationIgnored private var inFlight: [String: Task<UIImage?, Never>] = [:]
    @ObservationIgnored private let session: URLSession

    init() {
        cache.countLimit = 96
        cache.totalCostLimit = 48 * 1_024 * 1_024
        let configuration = URLSessionConfiguration.default
        configuration.urlCache = URLCache(
            memoryCapacity: 16 * 1_024 * 1_024,
            diskCapacity: 128 * 1_024 * 1_024
        )
        configuration.requestCachePolicy = .returnCacheDataElseLoad
        session = URLSession(configuration: configuration)
    }

    func image(urlString: String, maxPixelSize: CGFloat) async -> UIImage? {
        guard let url = URL(string: urlString) else { return nil }
        let pixelBucket = max(64, Int(maxPixelSize.rounded(.up)))
        let key = "\(url.absoluteString)|\(pixelBucket)"
        if let cached = cache.object(forKey: key as NSString) { return cached }
        if let existing = inFlight[key] { return await existing.value }

        // Download + decode run OFF the main actor (audit R8) via ImageIO
        // thumbnailing — decode-to-target-size in one pass, no full-size
        // intermediate and no UIKit main-actor isolation.
        let session = self.session
        let maxSourceBytes = Self.maxSourceBytes
        let task = Task.detached(priority: .utility) { () -> UIImage? in
            // Phase 0 signpost: one poster download+decode at rendered size.
            let signpost = Perf.signposter.beginInterval(Perf.Interval.posterDecode)
            defer { Perf.signposter.endInterval(Perf.Interval.posterDecode, signpost) }
            do {
                let (data, response) = try await session.data(from: url)
                if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                    return nil
                }
                guard data.count <= maxSourceBytes else { return nil }
                let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
                guard let imageSource = CGImageSourceCreateWithData(data as CFData, sourceOptions) else {
                    return nil
                }
                let thumbnailOptions: [CFString: Any] = [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceShouldCacheImmediately: true,
                    kCGImageSourceThumbnailMaxPixelSize: pixelBucket,
                ]
                guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, thumbnailOptions as CFDictionary) else {
                    return nil
                }
                return UIImage(cgImage: thumbnail)
            } catch {
                return nil
            }
        }
        inFlight[key] = task
        let loaded = await task.value
        inFlight[key] = nil
        if let loaded {
            let pixels = loaded.size.width * loaded.scale * loaded.size.height * loaded.scale
            cache.setObject(loaded, forKey: key as NSString, cost: Int(pixels * 4))
        }
        return loaded
    }

    /** Bounded adjacent-window warming used by the Explore grid. */
    func prefetch(urlStrings: [String], maxPixelSize: CGFloat) async {
        var seen = Set<String>()
        let unique = urlStrings.filter { !$0.isEmpty && seen.insert($0).inserted }.prefix(Self.maxPrefetchCount)
        let tasks = unique.map { url in
            Task { @MainActor [weak self] in
                guard let self else { return }
                _ = await self.image(urlString: url, maxPixelSize: maxPixelSize)
            }
        }
        for task in tasks { await task.value }
    }

    private static let maxPrefetchCount = 12
    private static let maxSourceBytes = 16 * 1_024 * 1_024
}
