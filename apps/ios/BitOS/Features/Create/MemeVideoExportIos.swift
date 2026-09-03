import AVFoundation
import AVKit
import BusinessCore
import SwiftUI
import UIKit

/// Video meme export (plan MST-033; iOS split per plan §4.2): burns the
/// overlay composition into the clip with AVFoundation through the shared
/// bridge envelope — WYSIWYG with the stage. MST-044 close-out: overlays
/// with visibility windows or fx render as per-overlay CALayers whose
/// keyframes are SAMPLED from the shared `memeFxTransformAt` seam
/// (deterministic, ~30 ms steps); purely static projects keep the single
/// flattened layer. MST-041 close-out: the shared SFX cue mix rides as a
/// second composition audio track with an explicit mix.
enum MemeVideoExportIos {

    struct ExportError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    struct Probe {
        let width: Int
        let height: Int
        let durationMs: Int64
        let rotationDeg: Int
        var uprightWidth: Int { rotationDeg == 90 || rotationDeg == 270 ? height : width }
        var uprightHeight: Int { rotationDeg == 90 || rotationDeg == 270 ? width : height }
    }

    /** Probes a clip written to a temp file (rotation-aware upright dims). */
    static func probe(url: URL) -> Probe? {
        let asset = AVURLAsset(url: url)
        guard let track = asset.tracks(withMediaType: .video).first else { return nil }
        let seconds = CMTimeGetSeconds(asset.duration)
        guard seconds.isFinite, seconds > 0 else { return nil }
        let size = track.naturalSize.applying(track.preferredTransform)
        let width = Int(abs(size.width).rounded())
        let height = Int(abs(size.height).rounded())
        guard width > 0, height > 0 else { return nil }
        let transform = track.preferredTransform
        let rotation = Int(round(atan2(transform.b, transform.a) * 180 / .pi))
        let normalized = ((rotation % 360) + 360) % 360
        return Probe(
            width: width,
            height: height,
            durationMs: Int64((seconds * 1000).rounded()),
            rotationDeg: normalized
        )
    }

    /** MST-032: captures the frame at `seconds` as JPEG bytes (the cover). */
    static func captureCoverJpeg(clipURL: URL, seconds: Double) -> Data? {
        let asset = AVURLAsset(url: clipURL)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 1080, height: 1080)
        let time = CMTime(seconds: max(0, seconds), preferredTimescale: 600)
        guard let cgImage = try? generator.copyCGImage(at: time, actualTime: nil) else { return nil }
        return UIImage(cgImage: cgImage).jpegData(compressionQuality: 0.85)
    }

    /// Writes session bytes to a temp file (AVFoundation needs a URL).
    static func writeTempClip(_ data: Data) -> URL? {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("meme-clip-\(UUID().uuidString).mp4")
        do {
            try data.write(to: url)
            return url
        } catch {
            return nil
        }
    }

    /** 4×5 color-matrix wrapper: Sendable values only, so it can cross
     *  into the CI render handler (CIFilter itself is not Sendable). */
    private struct MatrixFilter: Sendable {
        let m: [Float]

        init(_ m: [Float]) { self.m = m }

        func apply(_ source: CIImage) -> CIImage {
            let filter = CIFilter(name: "CIColorMatrix")
            filter?.setValue(
                CIVector(x: CGFloat(m[0]), y: CGFloat(m[1]), z: CGFloat(m[2]), w: CGFloat(m[3])),
                forKey: "inputRVector"
            )
            filter?.setValue(
                CIVector(x: CGFloat(m[5]), y: CGFloat(m[6]), z: CGFloat(m[7]), w: CGFloat(m[8])),
                forKey: "inputGVector"
            )
            filter?.setValue(
                CIVector(x: CGFloat(m[10]), y: CGFloat(m[11]), z: CGFloat(m[12]), w: CGFloat(m[13])),
                forKey: "inputBVector"
            )
            filter?.setValue(
                CIVector(x: CGFloat(m[15]), y: CGFloat(m[16]), z: CGFloat(m[17]), w: CGFloat(m[18])),
                forKey: "inputAVector"
            )
            filter?.setValue(
                CIVector(x: CGFloat(m[4]), y: CGFloat(m[9]), z: CGFloat(m[14]), w: CGFloat(m[19])),
                forKey: "inputBiasVector"
            )
            let clamped = source.clampedToExtent()
            filter?.setValue(clamped, forKey: kCIInputImageKey)
            return (filter?.outputImage ?? clamped).cropped(to: source.extent)
        }
    }

    /**
     * Per-clip color grade (M5): renders the clip's source through the
     * shared 4×5 look matrix (`memeLookMatrix` seam) with a CI-filter
     * video composition and returns a temp graded file. The graded source
     * then flows into [composeClips] unchanged. Export-exact — the stage
     * preview keeps the whole-project look.
     */
    static func gradeClip(
        url: URL, lookId: String, client: any BusinessCoreClient
    ) async -> URL? {
        guard let data = client.memeLookMatrix(lookId).data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = (root["matrix"] as? [NSNumber]), rows.count == 20 else { return nil }
        let m = rows.map(\.floatValue)
        let asset = AVURLAsset(url: url)
        guard let track = asset.tracks(withMediaType: .video).first,
              let transform = try? track.preferredTransform else { return nil }
        let natural = track.naturalSize.applying(transform)
        let size = CGSize(width: abs(natural.width), height: abs(natural.height))
        guard size.width > 2, size.height > 2 else { return nil }

        // The matrix crosses into the render handler as plain Floats
        // (Sendable) — CIFilter itself is not Sendable.
        let filter = MatrixFilter(m)
        let composition = AVMutableVideoComposition(
            asset: asset,
            applyingCIFiltersWithHandler: { request in
                let source = request.sourceImage
                let output = filter.apply(source)
                request.finish(with: output, context: nil)
            }
        )
        composition.renderSize = size

        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("meme-graded-\(UUID().uuidString).mp4")
        guard let export = AVAssetExportSession(
            asset: asset, presetName: AVAssetExportPresetHighestQuality
        ) else { return nil }
        export.outputURL = output
        export.outputFileType = .mp4
        export.videoComposition = composition
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            export.exportAsynchronously { continuation.resume() }
        }
        guard export.status == .completed else {
            try? FileManager.default.removeItem(at: output)
            return nil
        }
        return output
    }

    /**
     * M5 timeline composer: concatenates the clip list's windows into one
     * self-contained MP4 (pass-through where possible). Per-clip speed is
     * applied by scaling each inserted range (window ÷ rate), per-clip
     * volume by audio-mix volume points, per-clip look by a CI grading
     * pass over that clip's source, so the composed file's media time IS
     * the timeline clock the overlays/cues are keyed to. The caller then
     * runs the normal single-clip burn-in export over the composed file.
     */
    static func composeClips(
        _ clips: [MemeEditorStore.EditorClip],
        rate: Float,
        projectLookId: String? = nil,
        client: (any BusinessCoreClient)? = nil
    ) async throws -> URL {
        guard let first = clips.first else {
            throw ExportError(message: "No clips to compose")
        }
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(
            withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid
        ), let audioTrack = composition.addMutableTrack(
            withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid
        ) else {
            throw ExportError(message: "Could not build the timeline")
        }
        let mix = AVMutableAudioMix()
        let audioParams = AVMutableAudioMixInputParameters(track: audioTrack)
        var cursor = CMTime.zero
        var gradedTemps: [URL] = []
        defer { gradedTemps.forEach { try? FileManager.default.removeItem(at: $0) } }
        for clip in clips {
            var sourceURL = clip.url
            // Per-clip grade: own look, else the project look (export-exact).
            if let effectiveLook = clip.lookId ?? projectLookId, let client,
               let graded = await gradeClip(url: clip.url, lookId: effectiveLook, client: client) {
                gradedTemps.append(graded)
                sourceURL = graded
            }
            let asset = AVURLAsset(url: sourceURL)
            let range = CMTimeRange(
                start: CMTime(value: clip.startMs, timescale: 1000),
                end: CMTime(value: clip.endMs, timescale: 1000)
            )
            if let sourceVideo = asset.tracks(withMediaType: .video).first {
                try videoTrack.insertTimeRange(range, of: sourceVideo, at: cursor)
            }
            if let sourceAudio = asset.tracks(withMediaType: .audio).first {
                try audioTrack.insertTimeRange(range, of: sourceAudio, at: cursor)
            }
            let inserted = CMTimeRange(start: cursor, duration: range.duration)
            if rate != 1 {
                // Scale to the output duration (window ÷ rate); audio
                // resamples with it (pitch follows, V1 accepted parity).
                let scaled = CMTimeMultiplyByFloat64(range.duration, multiplier: 1.0 / Double(max(0.01, rate)))
                videoTrack.scaleTimeRange(inserted, toDuration: scaled)
                audioTrack.scaleTimeRange(inserted, toDuration: scaled)
            }
            if clip.volume != 1 {
                audioParams.setVolume(clip.volume, at: cursor)
            }
            cursor = CMTimeAdd(cursor, inserted.duration)
        }
        mix.inputParameters = [audioParams]
        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("meme-timeline-\(UUID().uuidString).mp4")
        guard let export = AVAssetExportSession(
            asset: composition, presetName: AVAssetExportPresetHighestQuality
        ) else {
            throw ExportError(message: "Could not start the timeline render")
        }
        export.outputURL = output
        export.outputFileType = .mp4
        export.audioMix = mix
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            export.exportAsynchronously { continuation.resume() }
        }
        guard export.status == .completed else {
            throw ExportError(message: export.error?.localizedDescription ?? "Timeline render failed")
        }
        return output
    }

    /** Export-scoped wire: timeline media time is the composed file's time,
     *  so the burn-in runs full-length at 1× (speed already baked in). */
    static func timelineExportWire(projectJson: String, timelineMs: Int64, client: any BusinessCoreClient) -> String {
        var wire = client.memeApplyCommand(projectJson, commandJson: #"{"op":"speed","rate":1}"#)
        wire = client.memeApplyCommand(
            wire, commandJson: "{\"op\":\"trim\",\"start\":0,\"end\":\(timelineMs)}"
        )
        return wire
    }

    /**
     * Burns the overlay layer into the clip and returns the MP4 bytes.
     * The composition is a pass-through at the upright render size with
     * one full-frame image layer (the overlay plan) via the Core
     * Animation tool. [images] resolves IMAGE-layer asset ids to their
     * imported sources (video-mode source inserts).
     */
    static func export(
        clipURL: URL,
        probe: Probe,
        projectJson: String,
        client: any BusinessCoreClient,
        images: [String: UIImage] = [:]
    ) async throws -> Data {
        let width = max(2, probe.uprightWidth - probe.uprightWidth % 2)
        let height = max(2, probe.uprightHeight - probe.uprightHeight % 2)

        // Overlay burn-in (MST-044 close-out): static overlays keep the
        // single flattened layer; ANY window/fx switches to the
        // per-overlay CALayer tree whose keyframes are SAMPLED from the
        // shared math via memeFxTransformAt (no Swift fx mirror).
        let envelopeJson = client.memeExportPlan(projectJson, sourceWidth: width, sourceHeight: height)
        guard let envelopeData = envelopeJson.data(using: .utf8),
              let envelope = try? JSONSerialization.jsonObject(with: envelopeData) as? [String: Any],
              let rows = envelope["items"] as? [[String: Any]] else {
            throw ExportError(message: "The meme could not be planned for export")
        }
        let overlayRowsById: [String: [String: Any]] = {
            guard let data = projectJson.data(using: .utf8),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let overlayRows = root["overlays"] as? [[String: Any]] else { return [:] }
            var map: [String: [String: Any]] = [:]
            for row in overlayRows {
                if let id = row["id"] as? String { map[id] = row }
            }
            return map
        }()
        func isTimed(_ row: [String: Any]) -> Bool {
            let window = row["startMs"] != nil || row["endMs"] != nil
            let fx = (row["fx"] as? String).flatMap { $0.isEmpty || $0 == "none" ? nil : $0 } != nil
            return window || fx
        }
        let anyTimed = rows.contains { row in
            (row["id"] as? String).flatMap { overlayRowsById[$0] }.map(isTimed) ?? false
        }

        let asset = AVURLAsset(url: clipURL)
        guard let track = asset.tracks(withMediaType: .video).first else {
            throw ExportError(message: "The clip has no video track")
        }

        let composition = AVMutableComposition()
        guard let compositionVideo = composition.addMutableTrack(
            withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid
        ), let compositionAudio = composition.addMutableTrack(
            withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid
        ), let audioTrack = asset.tracks(withMediaType: .audio).first else {
            throw ExportError(message: "The clip tracks could not be composed")
        }
        // The project trim window is the export contract (MST-030
        // revision): over-long clips are CUT here, not rejected.
        let keep = await VideoStageIos.trimWindow(of: projectJson, assetDuration: asset.duration)
        try compositionVideo.insertTimeRange(
            CMTimeRange(start: .zero, duration: keep), of: track, at: .zero
        )
        try compositionAudio.insertTimeRange(
            CMTimeRange(start: .zero, duration: keep), of: audioTrack, at: .zero
        )
        // V2 suite Speed chip: whole-clip rate compresses both tracks to
        // keep/rate (audio pitch follows — V1 accepted, documented).
        let rate = projectSpeed(of: projectJson)
        let outputDuration: CMTime
        if rate != 1 {
            outputDuration = CMTime(seconds: keep.seconds / Double(rate), preferredTimescale: 600)
            let window = CMTimeRange(start: .zero, duration: keep)
            compositionVideo.scaleTimeRange(window, toDuration: outputDuration)
            compositionAudio.scaleTimeRange(window, toDuration: outputDuration)
        } else {
            outputDuration = keep
        }

        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: .zero, duration: outputDuration)
        let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compositionVideo)
        // Rotate to upright + fill the render size.
        let uprightTransform = track.preferredTransform.concatenating(
            CGAffineTransform(scaleX: CGFloat(width) / CGFloat(probe.uprightWidth),
                              y: CGFloat(height) / CGFloat(probe.uprightHeight))
        )
        layerInstruction.setTransform(uprightTransform, at: .zero)
        instruction.layerInstructions = [layerInstruction]

        let videoComposition = AVMutableVideoComposition()
        videoComposition.renderSize = CGSize(width: width, height: height)
        videoComposition.frameDuration = CMTime(value: 1, timescale: 30)
        videoComposition.instructions = [instruction]

        let parentLayer = CALayer()
        let videoLayer = CALayer()
        parentLayer.frame = CGRect(x: 0, y: 0, width: width, height: height)
        videoLayer.frame = parentLayer.bounds
        parentLayer.addSublayer(videoLayer)
        let overlayLayer = CALayer()
        overlayLayer.frame = parentLayer.bounds
        parentLayer.addSublayer(overlayLayer)

        if anyTimed {
            // Per-overlay tree: tight content layers positioned at their
            // overlay centers (anchor pivot), keyframed over the OUTPUT
            // duration in 33 ms samples (media time = output + trimStart).
            // Pen ink first — a static layer under every overlay.
            let strokeRows = envelope["strokes"] as? [[String: Any]] ?? []
            if !strokeRows.isEmpty {
                let strokeFormat = UIGraphicsImageRendererFormat()
                strokeFormat.scale = 1
                let strokesImage = UIGraphicsImageRenderer(
                    size: CGSize(width: width, height: height), format: strokeFormat
                ).image { context in
                    MemeRaster.paintStrokes(strokeRows, in: context.cgContext)
                }
                let strokesLayer = CALayer()
                strokesLayer.frame = parentLayer.bounds
                strokesLayer.contents = strokesImage.cgImage
                overlayLayer.addSublayer(strokesLayer)
            }
            let trimStart = trimStartMs(of: projectJson)
            let durationSec = max(0.01, outputDuration.seconds)
            let canvas = CGSize(width: width, height: height)
            for row in rows {
                guard let id = row["id"] as? String else { continue }
                let timed = (overlayRowsById[id]).map(isTimed) ?? false
                let size = MemeRaster.contentSize(row, images: images)
                let contentFormat = UIGraphicsImageRendererFormat()
                contentFormat.scale = 1
                var paintRow = row
                paintRow["rot"] = 0.0 // static rotation rides the layer transform
                let content = UIGraphicsImageRenderer(size: size, format: contentFormat).image { context in
                    MemeRaster.paint(paintRow, in: context.cgContext, images: images, centerOverride: CGPoint(x: size.width / 2, y: size.height / 2))
                }
                let layer = CALayer()
                layer.bounds = CGRect(origin: .zero, size: size)
                layer.position = CGPoint(
                    x: (row["x"] as? NSNumber)?.doubleValue ?? 0,
                    y: (row["y"] as? NSNumber)?.doubleValue ?? 0
                )
                layer.contents = content.cgImage
                let staticRotDeg = (row["rot"] as? NSNumber)?.doubleValue ?? 0
                layer.transform = CATransform3DMakeRotation(CGFloat(staticRotDeg) * .pi / 180, 0, 0, 1)
                overlayLayer.addSublayer(layer)
                if timed {
                    applyTimedAnimation(
                        to: layer,
                        overlayId: id,
                        projectJson: projectJson,
                        client: client,
                        canvasSize: canvas,
                        durationSec: durationSec,
                        rate: rate,
                        trimStartMs: trimStart,
                        staticRotDeg: staticRotDeg
                    )
                }
            }
        } else {
            // Fast path: one flattened static layer (the classic burn) —
            // ink under the captions here too.
            let strokeRows = envelope["strokes"] as? [[String: Any]] ?? []
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            let overlayImage = UIGraphicsImageRenderer(
                size: CGSize(width: width, height: height), format: format
            ).image { context in
                MemeRaster.paintStrokes(strokeRows, in: context.cgContext)
                for row in rows {
                    MemeRaster.paint(row, in: context.cgContext, images: images)
                }
            }
            overlayLayer.contents = overlayImage.cgImage
        }
        videoComposition.animationTool = AVVideoCompositionCoreAnimationTool(
            postProcessingAsVideoLayer: videoLayer, in: parentLayer
        )

        // SFX burn-in (MST-041 close-out): the shared cue mix rides as a
        // second audio track; the explicit mix keeps both audible
        // (Android mixes the same PCM through Media3 sequences).
        var audioMix: AVMutableAudioMix?
        var sfxWavURL: URL?
        let sfxB64 = client.memeSfxTrackWavBase64(
            projectJson,
            durationMs: Int64((outputDuration.seconds * 1000).rounded()),
            rate: rate
        )
        if !sfxB64.isEmpty,
           let wav = Data(base64Encoded: sfxB64) {
            let wavURL = FileManager.default.temporaryDirectory
                .appendingPathComponent("meme-sfx-\(UUID().uuidString).wav")
            if (try? wav.write(to: wavURL)) != nil {
                sfxWavURL = wavURL
                let sfxAsset = AVURLAsset(url: wavURL)
                if let sfxSource = sfxAsset.tracks(withMediaType: .audio).first,
                   let sfxTrack = composition.addMutableTrack(
                       withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid
                   ) {
                    let range = CMTimeRange(
                        start: .zero,
                        duration: CMTime(
                            seconds: min(outputDuration.seconds, sfxAsset.duration.seconds),
                            preferredTimescale: 600
                        )
                    )
                    try? sfxTrack.insertTimeRange(range, of: sfxSource, at: .zero)
                    let mix = AVMutableAudioMix()
                    let originalParams = AVMutableAudioMixInputParameters(track: compositionAudio)
                    originalParams.setVolume(1, at: .zero)
                    let sfxParams = AVMutableAudioMixInputParameters(track: sfxTrack)
                    sfxParams.setVolume(1, at: .zero)
                    mix.inputParameters = [originalParams, sfxParams]
                    audioMix = mix
                }
            }
        }

        let outputURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("meme-video-out-\(UUID().uuidString).mp4")
        try? FileManager.default.removeItem(at: outputURL)
        guard let export = AVAssetExportSession(
            asset: composition, presetName: AVAssetExportPresetHighestQuality
        ) else {
            if let sfxWavURL { try? FileManager.default.removeItem(at: sfxWavURL) }
            throw ExportError(message: "Video exporter unavailable")
        }
        export.videoComposition = videoComposition
        export.audioMix = audioMix
        export.outputURL = outputURL
        export.outputFileType = .mp4
        await export.export()
        defer {
            try? FileManager.default.removeItem(at: outputURL)
            if let sfxWavURL { try? FileManager.default.removeItem(at: sfxWavURL) }
        }
        guard export.status == .completed,
              let data = try? Data(contentsOf: outputURL) else {
            throw ExportError(message: export.error?.localizedDescription ?? "Video export failed")
        }
        return data
    }

    /**
     * Keyframes one overlay layer over the whole output duration, sampled
     * from the shared `memeFxTransformAt` seam (scale|rot|dx|dy|alpha —
     * the window folds into alpha, so out-of-window samples are 0). The
     * static rotation rides every sample; keyTimes are normalized 0..1.
     */
    private static func applyTimedAnimation(
        to layer: CALayer,
        overlayId: String,
        projectJson: String,
        client: any BusinessCoreClient,
        canvasSize: CGSize,
        durationSec: Double,
        rate: Float,
        trimStartMs: Int64,
        staticRotDeg: Double
    ) {
        let step = durationSec > 40 ? 0.066 : 0.033
        let sampleCount = Int(ceil(durationSec / step)) + 1
        guard sampleCount >= 2, sampleCount <= 4000 else { return }
        var transforms: [CATransform3D] = []
        var opacities: [Float] = []
        var keyTimes: [NSNumber] = []
        for index in 0..<sampleCount {
            let t = Double(index) * step
            // Output time → media time: × rate + trim start (windows and
            // fx live on the media timeline).
            let mediaMs = Int64((t * Double(rate) * 1000).rounded()) + trimStartMs
            let state = client.memeFxTransformAt(projectJson, overlayId: overlayId, atMs: mediaMs)
            let parts = state.split(separator: "|").compactMap { Double($0) }
            guard parts.count == 5 else { continue }
            var tr = CATransform3DMakeRotation(
                CGFloat(staticRotDeg + parts[1] * 180 / .pi), 0, 0, 1
            )
            tr = CATransform3DScale(tr, CGFloat(parts[0]), CGFloat(parts[0]), 1)
            tr = CATransform3DTranslate(
                tr, CGFloat(parts[2]) * canvasSize.width, CGFloat(parts[3]) * canvasSize.height, 0
            )
            transforms.append(tr)
            opacities.append(Float(parts[4]))
            let normalized = index == sampleCount - 1 ? 1.0 : min(1, t / durationSec)
            keyTimes.append(NSNumber(value: normalized))
        }
        guard transforms.count >= 2, keyTimes.first?.doubleValue == 0, keyTimes.last?.doubleValue == 1 else { return }
        let transformAnim = CAKeyframeAnimation(keyPath: "transform")
        transformAnim.values = transforms
        transformAnim.keyTimes = keyTimes
        transformAnim.duration = durationSec
        transformAnim.fillMode = .both
        transformAnim.isRemovedOnCompletion = false
        layer.add(transformAnim, forKey: "memeFxTransform")
        let opacityAnim = CAKeyframeAnimation(keyPath: "opacity")
        opacityAnim.values = opacities
        opacityAnim.keyTimes = keyTimes
        opacityAnim.duration = durationSec
        opacityAnim.fillMode = .both
        opacityAnim.isRemovedOnCompletion = false
        layer.add(opacityAnim, forKey: "memeFxOpacity")
    }

    /// `{"trim":[startMs,endMs]}` start (0 when absent) — the output
    /// timeline's t0 anchor for media-time windows.
    static func trimStartMs(of projectJson: String) -> Int64 {
        guard let data = projectJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let trim = root["trim"] as? [Any], trim.count == 2,
              let s = (trim[0] as? NSNumber)?.int64Value else { return 0 }
        return max(0, s)
    }

    /// `{"speed":rate}` clamped 0.5–2 (junk/absent → 1 = source speed).
    static func projectSpeed(of projectJson: String) -> Float {
        guard let data = projectJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = (root["speed"] as? NSNumber)?.floatValue else { return 1 }
        return raw.isNaN || raw <= 0 ? 1 : min(2, max(0.5, raw))
    }
}


/// External transport for the V2 suite dock (mockup app-15 `scr-suite`):
/// the stage registers its player controls here so the timeline dock can
/// play/pause and seek without owning the player instance.
final class VideoTransportIos {
    var playPause: () -> Void = {}
    var seekToSeconds: (Double) -> Void = { _ in }
    var isPlaying: () -> Bool = { false }

    func apply(playPause: @escaping () -> Void, seek: @escaping (Double) -> Void, playing: @escaping () -> Bool) {
        self.playPause = playPause
        self.seekToSeconds = seek
        self.isPlaying = playing
    }

    func reset() {
        playPause = {}
        seekToSeconds = { _ in }
        isPlaying = { false }
    }
}

/// Video stage (plan MST-031, iOS): AVKit player surface with the meme
/// overlays composed on top at any playhead + play/pause + scrub. V1
/// burns overlays across the whole clip (matching the export).
/// [layerImages] resolves IMAGE layer asset ids (source inserts).
struct VideoStageIos: View {
    /// M5: the whole timeline — one player over a composition of the clip
    /// windows (speed + per-clip volume baked in like the exporter, so the
    /// stage clock IS the timeline clock the overlays/cues are keyed to).
    let clips: [MemeEditorStore.EditorClip]
    let rate: Float
    @Bindable var store: MemeEditorStore
    let projectJson: String
    let client: any BusinessCoreClient
    var coverSet: Bool = false
    var onSetCover: (Double) -> Void = { _ in }
    /// SFX cue markers (MST-041) + playhead tracking for cue placement.
    var cueAtSeconds: [Double] = []
    var onPositionChange: (Double) -> Void = { _ in }
    /// IMAGE layer sources: overlay asset id → image.
    var layerImages: [String: UIImage] = [:]
    /// Suite mode hides the scrub row — the timeline dock owns transport.
    var showScrub: Bool = true
    /// Player control surface for the suite dock (registered, not owned).
    var transport: VideoTransportIos? = nil

    private var probe: MemeVideoExportIos.Probe? { clips.first?.probe }
    private var timelineSeconds: Double {
        Double(clips.reduce(Int64(0)) { $0 + Int64((Double(max(0, $1.endMs - $1.startMs)) / Double(max(0.01, rate))).rounded()) }) / 1000
    }

    /// Builds the stage composition (windows + speed scaling + volume mix).
    private func buildComposition() -> (AVMutableComposition, AVMutableAudioMix)? {
        guard let first = clips.first else { return nil }
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(
            withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid
        ), let audioTrack = composition.addMutableTrack(
            withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid
        ) else { return nil }
        let mix = AVMutableAudioMix()
        let audioParams = AVMutableAudioMixInputParameters(track: audioTrack)
        var cursor = CMTime.zero
        for clip in clips {
            let asset = AVURLAsset(url: clip.url)
            let range = CMTimeRange(
                start: CMTime(value: clip.startMs, timescale: 1000),
                end: CMTime(value: clip.endMs, timescale: 1000)
            )
            if let sourceVideo = asset.tracks(withMediaType: .video).first {
                try? videoTrack.insertTimeRange(range, of: sourceVideo, at: cursor)
            }
            if let sourceAudio = asset.tracks(withMediaType: .audio).first {
                try? audioTrack.insertTimeRange(range, of: sourceAudio, at: cursor)
            }
            let inserted = CMTimeRange(start: cursor, duration: range.duration)
            if rate != 1 {
                let scaled = CMTimeMultiplyByFloat64(range.duration, multiplier: 1.0 / Double(max(0.01, rate)))
                videoTrack.scaleTimeRange(inserted, toDuration: scaled)
                audioTrack.scaleTimeRange(inserted, toDuration: scaled)
            }
            audioParams.setVolume(clip.volume, at: cursor)
            cursor = CMTimeAdd(cursor, inserted.duration)
        }
        mix.inputParameters = [audioParams]
        _ = first
        return (composition, mix)
    }

    @State private var player: AVPlayer?
    @State private var positionSeconds: Double = 0
    @State private var durationSeconds: Double = 0.01
    @State private var playing = false

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.xs) {
            GeometryReader { proxy in
                let aspect = CGFloat(probe?.uprightWidth ?? 1080) / CGFloat(max(1, probe?.uprightHeight ?? 1920))
                let fitted = fittedSize(container: proxy.size, aspect: aspect)
                ZStack {
                    if let player {
                        VideoPlayer(player: player)
                            .frame(width: fitted.width, height: fitted.height)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    ForEach(store.overlays) { overlay in
                        // MST-044: out-of-window overlays hide; fx tracks the
                        // playhead (both via the shared seam).
                        if MemeFxBridge.visibleAt(overlay.id, projectJson: projectJson, atSec: positionSeconds, client: client) {
                            OverlayUiView(
                                overlay: overlay,
                                stageSize: fitted,
                                selected: overlay.id == store.selectedId,
                                paletteHex: store.paletteHex,
                                fx: MemeFxBridge.paintState(overlay.id, projectJson: projectJson, atSec: positionSeconds, client: client),
                                layerImage: overlay.isImage ? overlay.assetId.flatMap { layerImages[$0] } : nil
                            )
                        }
                    }
                    if let selected = store.overlays.first(where: { $0.id == store.selectedId }),
                       let bounds = store.boundsFraction(for: selected.id) {
                        DeleteHandleView(overlay: selected, stageSize: fitted, bounds: bounds)
                    }
                }
                .frame(width: proxy.size.width, height: proxy.size.height)
                .contentShape(Rectangle())
                // Tap-through selection on the stage surface.
                .onTapGesture { location in
                    let hit = store.selectAt(
                        x: Float(location.x / max(1, fitted.width)),
                        y: Float(location.y / max(1, fitted.height))
                    )
                    if !hit { store.clearSelection() }
                }
            }
            if showScrub {
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    Button {
                        guard let player else { return }
                        if player.timeControlStatus == .playing {
                            player.pause()
                        } else {
                            player.play()
                        }
                    } label: {
                        Image(systemName: playing ? "pause.fill" : "play.fill")
                            .foregroundStyle(BitOSTheme.textPrimary)
                    }
                    .accessibilityLabel(playing ? "Pause" : "Play")
                    ZStack {
                        Slider(
                            value: Binding(
                                get: { positionSeconds },
                                set: { player?.seek(to: CMTime(seconds: $0, preferredTimescale: 600)) }
                            ),
                            in: 0...max(0.01, durationSeconds)
                        )
                        GeometryReader { geo in
                            ForEach(Array(cueAtSeconds.enumerated()), id: \.offset) { _, at in
                                Capsule()
                                    .fill(BitOSTheme.accent)
                                    .frame(width: 3, height: 14)
                                    .position(
                                        x: geo.size.width * at / max(0.01, durationSeconds),
                                        y: geo.size.height / 2
                                    )
                            }
                        }
                        .allowsHitTesting(false)
                    }
                    Text(String(format: "%.0fs", positionSeconds))
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textSecondary)
                    Button {
                        onSetCover(positionSeconds)
                    } label: {
                        Text(coverSet ? "Cover \u{2713}" : "Set cover")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(coverSet ? BitOSTheme.success : BitOSTheme.accent)
                    }
                    .accessibilityLabel("Set cover at playhead")
                }
                .padding(.horizontal, BitOSTheme.Spacing.md)
            }
        }
        .onAppear {
            guard let (composition, mix) = buildComposition() else { return }
            let item = AVPlayerItem(asset: composition)
            item.audioMix = mix
            let avPlayer = AVPlayer(playerItem: item)
            player = avPlayer
            durationSeconds = max(0.01, timelineSeconds)
            avPlayer.play()
            transport?.apply { [weak avPlayer] in
                guard let avPlayer else { return }
                if avPlayer.timeControlStatus == .playing {
                    avPlayer.pause()
                } else {
                    avPlayer.play()
                }
            } seek: { [weak avPlayer] seconds in
                avPlayer?.seek(to: CMTime(seconds: max(0, seconds), preferredTimescale: 600))
            } playing: { [weak avPlayer] in
                avPlayer?.timeControlStatus == .playing
            }
            Task {
                // Poll the playhead (light; the editor owns the stage).
                while !Task.isCancelled {
                    if let player {
                        positionSeconds = CMTimeGetSeconds(player.currentTime())
                        onPositionChange(positionSeconds)
                        playing = player.timeControlStatus == .playing
                    }
                    try? await Task.sleep(nanoseconds: 100_000_000)
                }
            }
        }
        .onDisappear {
            player?.pause()
            transport?.reset()
        }
    }

    private func fittedSize(container: CGSize, aspect: CGFloat) -> CGSize {
        guard aspect > 0 else { return container }
        let byWidth = CGSize(width: container.width, height: container.width / aspect)
        if byWidth.height <= container.height { return byWidth }
        return CGSize(width: container.height * aspect, height: container.height)
    }


    /// `{"trim":[startMs,endMs]}` clamped to the asset; 0/absent end = full.
    static func trimWindow(of projectJson: String, assetDuration: CMTime) async -> CMTime {
        let durationMs = Int64(max(0, assetDuration.seconds) * 1000)
        var startMs: Int64 = 0
        var endMs = durationMs
        if let data = projectJson.data(using: .utf8),
           let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let trim = root["trim"] as? [Any],
           trim.count == 2,
           let s = (trim[0] as? NSNumber)?.int64Value,
           let e = (trim[1] as? NSNumber)?.int64Value {
            startMs = min(max(0, s), durationMs)
            endMs = min(max(e, startMs), durationMs)
            if e <= 0 { endMs = durationMs }
        }
        return CMTime(seconds: Double(endMs - startMs) / 1000, preferredTimescale: 600)
    }
}

/**
 * MST-041/044 display shim — DELEGATED to the shared `memeFxTransformAt`
 * seam (plan close-out: the Swift mirror of MemeFxRules is gone; the
 * stage preview and the export keyframe sampler read the same math).
 */
enum MemeFxBridge {
    /// Composite paint state at media time; identity when unresolvable.
    /// alpha 0 ⇔ outside the half-open window (the shared rule folds it).
    static func paintState(
        _ id: String,
        projectJson: String,
        atSec: Double,
        client: any BusinessCoreClient
    ) -> FxTransformUi {
        let state = client.memeFxTransformAt(projectJson, overlayId: id, atMs: Int64(atSec * 1000))
        let parts = state.split(separator: "|").compactMap { Float($0) }
        guard parts.count == 5 else { return FxTransformUi() }
        return FxTransformUi(
            scale: parts[0], rotateRad: parts[1], dx: parts[2], dy: parts[3], alpha: parts[4]
        )
    }

    /// Half-open visibility via the seam's folded alpha (0 = hidden).
    static func visibleAt(
        _ id: String,
        projectJson: String,
        atSec: Double,
        client: any BusinessCoreClient
    ) -> Bool {
        paintState(id, projectJson: projectJson, atSec: atSec, client: client).alpha > 0.001
    }
}

/// Display-side fx transform (mirrors the shared MemeFxRules.FxTransform).
struct FxTransformUi {
    var scale: Float = 1
    var rotateRad: Float = 0
    var dx: Float = 0
    var dy: Float = 0
    var alpha: Float = 1
}
