import BusinessCore
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// GIF export glue (plan MST-022; iOS split per plan §4.2): the SHARED
/// planner (through the bridge seam) picks which moments hold and for how
/// long, UIGraphicsImageRenderer paints each frame with the overlays
/// burned in, and CGImageDestination emits the looping GIF89a. The size
/// ladder halves the long edge (≤3 steps) when the encode busts 8 MB.
enum MemeGifExportIos {

    struct ExportError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    struct Result {
        let data: Data
        let canvasWidth: Int
        let canvasHeight: Int
        let ladderStep: Int
        let capped: Bool
    }

    static func export(
        frames: [UIImage],
        delaysMs: [Int],
        projectJson: String,
        client: any BusinessCoreClient,
        images: [String: UIImage] = [:]
    ) throws -> Result {
        guard !frames.isEmpty else { throw ExportError(message: "Pick frames first") }

        // Shared planner through the bridge (single-source timing rules).
        let delaysJson = delaysMs.map(String.init).joined(separator: ",")
        let planJson = client.memeGifPlan("[\(delaysJson)]", pinnedSec: 0)
        guard let planData = planJson.data(using: .utf8),
              let plan = try? JSONSerialization.jsonObject(with: planData) as? [String: Any],
              let stepRows = plan["steps"] as? [[String: Any]] else {
            throw ExportError(message: "The GIF could not be planned")
        }
        let capped = (plan["capped"] as? NSNumber)?.boolValue ?? false
        let steps: [(atSec: Double, delayMs: Int)] = stepRows.compactMap { row in
            guard let at = (row["atSec"] as? NSNumber)?.doubleValue,
                  let delay = (row["delayMs"] as? NSNumber)?.intValue else { return nil }
            return (at, delay)
        }
        guard !steps.isEmpty else { throw ExportError(message: "The GIF plan is empty") }

        let source = frames[0]
        let sourceWidth = Int(source.size.width)
        let sourceHeight = Int(source.size.height)

        var step = 0
        while true {
            let canvasRaw = client.memeGifLadderCanvas(sourceWidth, sourceHeight, step)
            let parts = canvasRaw.split(separator: "|").compactMap { Int($0) }
            guard parts.count == 2 else { throw ExportError(message: "GIF canvas ladder exhausted") }
            let width = parts[0]
            let height = parts[1]
            let encoded = try encode(
                frames: frames, delaysMs: delaysMs, steps: steps,
                projectJson: projectJson, client: client,
                width: width, height: height, images: images
            )
            if encoded.count <= 8 * 1024 * 1024 || step >= 3 {
                return Result(
                    data: encoded, canvasWidth: width, canvasHeight: height,
                    ladderStep: step, capped: capped
                )
            }
            step += 1
        }
    }

    private static func encode(
        frames: [UIImage],
        delaysMs: [Int],
        steps: [(atSec: Double, delayMs: Int)],
        projectJson: String,
        client: any BusinessCoreClient,
        width: Int,
        height: Int,
        images: [String: UIImage] = [:]
    ) throws -> Data {
        // The shared envelope carries the per-canvas paint rows (overlays
        // burned in at the ladder canvas size).
        let envelopeJson = client.memeExportPlan(projectJson, sourceWidth: width, sourceHeight: height)
        guard let envelopeData = envelopeJson.data(using: .utf8),
              let envelope = try? JSONSerialization.jsonObject(with: envelopeData) as? [String: Any],
              let rows = envelope["items"] as? [[String: Any]] else {
            throw ExportError(message: "The meme could not be planned for export")
        }

        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            output, UTType.gif.identifier as CFString, steps.count, nil
        ) else {
            throw ExportError(message: "GIF encoder unavailable")
        }
        // MST-043 + adjust: the media grade burns into every frame the
        // same way the still rasterizer paints it (identity = fast path,
        // applyLook returns nil and the raw frame draws).
        let lookId = MemeEditorStore.lookId(ofProject: projectJson)
        let adjust = MemeEditorStore.adjustTriple(ofProject: projectJson)
        let matrixJson = client.memeAdjustMatrix(
            lookId, brightness: adjust.bri, contrast: adjust.con, saturation: adjust.sat
        )
        let size = CGSize(width: width, height: height)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1

        for stepRow in steps {
            let frame = frameActive(at: stepRow.atSec, frames: frames, delays: delaysMs)
            let media = MemeRaster.applyLook(frame, matrixJson: matrixJson) ?? frame
            let renderer = UIGraphicsImageRenderer(size: size, format: format)
            let composed = renderer.image { context in
                media.draw(in: CGRect(origin: .zero, size: size))
                // Pen ink under the captions on every frame.
                MemeRaster.paintStrokes(
                    envelope["strokes"] as? [[String: Any]] ?? [],
                    in: context.cgContext
                )
                for row in rows {
                    MemeRaster.paint(row, in: context.cgContext, images: images)
                }
            }
            let properties: [CFString: Any] = [
                kCGImagePropertyGIFDictionary: [
                    kCGImagePropertyGIFDelayTime: Double(stepRow.delayMs) / 1000.0,
                ],
            ]
            CGImageDestinationAddImage(destination, composed.cgImage!, properties as CFDictionary)
        }
        guard CGImageDestinationFinalize(destination) else {
            throw ExportError(message: "GIF encoding failed")
        }
        return output as Data
    }

    private static func frameActive(at atSec: Double, frames: [UIImage], delays: [Int]) -> UIImage {
        var acc = 0.0
        for (index, frame) in frames.enumerated() where index < delays.count {
            acc += Double(delays[index]) / 1000.0
            if atSec < acc { return frame }
        }
        return frames.last ?? frames[0]
    }
}
