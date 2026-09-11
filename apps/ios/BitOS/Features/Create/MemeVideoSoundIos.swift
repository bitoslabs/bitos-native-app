import AVFoundation
import CryptoKit
import BusinessCore

/// "Use this sound" Wave B extraction (plan docs/product/
/// use-this-sound-plan.md §5) — the iOS twin of Android's `MemeVideoSound`:
/// pull a picked video's audio out as a bounded m4a (AVAssetExportSession
/// AppleM4A — passthrough where the container allows, capped at 60 s),
/// then decode to mono float PCM at the shared bed rate (44.1 kHz) for
/// `MemeSoundMix` placement. The m4a IS the publish artifact (sha256 over
/// these bytes, uploaded before any signing); the PCM is the preview and
/// export bed.
enum MemeVideoSoundIos {

    /// One extracted soundtrack: the publishable bytes + bed metadata.
    struct Extracted {
        let m4aData: Data
        let durationMs: Int64
        let sha256Hex: String
    }

    /// Passthrough audio extraction capped at the shared 60 s bound.
    /// Nil when the source has no readable audio track (named upstream).
    static func extract(url: URL) async -> Extracted? {
        let asset = AVURLAsset(url: url)
        guard (try? await asset.loadTracks(withMediaType: .audio).first) != nil else { return nil }
        let assetDuration = try? await asset.load(.duration)
        let totalMs = Int64(((assetDuration?.seconds ?? 0) * 1000).rounded())
        let capMs = Int64(MemeSoundRules.shared.MAX_SOUND_DURATION_MS)
        let durationMs = min(max(totalMs, 1), capMs)
        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("meme-sound-\(UUID().uuidString).m4a")
        try? FileManager.default.removeItem(at: output)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            return nil
        }
        export.outputURL = output
        export.outputFileType = .m4a
        export.timeRange = CMTimeRange(
            start: .zero,
            end: CMTime(value: durationMs, timescale: 1000)
        )
        export.metadata = []
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            export.exportAsynchronously { continuation.resume() }
        }
        guard export.status == .completed, let bytes = try? Data(contentsOf: output) else {
            try? FileManager.default.removeItem(at: output)
            return nil
        }
        defer { try? FileManager.default.removeItem(at: output) }
        guard !bytes.isEmpty else { return nil }
        return Extracted(
            m4aData: bytes,
            durationMs: durationMs,
            sha256Hex: SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        )
    }

    /// Decodes the extracted m4a to MONO Float32 PCM at the shared bed
    /// rate — AVAssetReader converts (downmix + resample) in one pass, so
    /// the shared `MemeSoundMix` math receives bed-rate samples directly.
    /// Nil when the decoder refuses (named upstream).
    static func decodePcm(data: Data) -> [Float]? {
        let input = FileManager.default.temporaryDirectory
            .appendingPathComponent("meme-sound-in-\(UUID().uuidString).m4a")
        do {
            try data.write(to: input)
        } catch {
            return nil
        }
        defer { try? FileManager.default.removeItem(at: input) }
        let asset = AVURLAsset(url: input)
        guard let audioTrack = asset.tracks(withMediaType: .audio).first else { return nil }
        guard let reader = try? AVAssetReader(asset: asset) else { return nil }
        let bedRate = Int(MemeSoundMix.shared.BED_RATE)
        let output = AVAssetReaderTrackOutput(
            track: audioTrack,
            outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVLinearPCMBitDepthKey: 32,
                AVLinearPCMIsFloatKey: true,
                AVLinearPCMIsNonInterleaved: true,
                AVLinearPCMIsBigEndianKey: false,
                AVNumberOfChannelsKey: 1,
                AVSampleRateKey: bedRate,
            ]
        )
        output.alwaysCopiesSampleData = false
        reader.add(output)
        guard reader.startReading() else { return nil }
        var pcm: [Float] = []
        pcm.reserveCapacity(bedRate * 30)
        while let sampleBuffer = output.copyNextSampleBuffer() {
            guard let block = CMSampleBufferGetDataBuffer(sampleBuffer) else { continue }
            let length = CMBlockBufferGetDataLength(block)
            var data = Data(count: length)
            let ok = data.withUnsafeMutableBytes { buffer in
                guard let destination = buffer.baseAddress else { return false }
                return CMBlockBufferCopyDataBytes(
                    block, atOffset: 0, dataLength: length, destination: destination
                ) == kCMBlockBufferNoErr
            }
            if ok {
                pcm.append(contentsOf: data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                    raw.bindMemory(to: Float.self).map { $0 }
                })
            }
        }
        guard reader.status == .completed || reader.status == .reading else { return nil }
        return pcm.isEmpty ? nil : pcm
    }

    /// LE float32 PCM → base64 (the bridge seam's wire form).
    static func pcmBase64(_ pcm: [Float]) -> String {
        var bytes = Data(capacity: pcm.count * 4)
        for value in pcm {
            let bits = value.bitPattern
            withUnsafeBytes(of: bits.littleEndian) { bytes.append(contentsOf: $0) }
        }
        return bytes.base64EncodedString()
    }
}
