import AVFoundation
import SwiftUI
import UIKit

/**
 * APP-006 story media primitives (web `StoryViewer` parity):
 * `StoryGifView` animates GIF attachments (SwiftUI `AsyncImage` renders
 * only the first frame), `StoryVideoLayer` is a controls-free, muted,
 * aspect-fill AVPlayer surface that reports measured duration and end.
 * Media URLs arrive from the shared stories parser (bounded HTTPS only).
 */

/// Animated GIF renderer: decodes frames + delays off-main via the shared
/// `GifDecoder` (DesignSystem), then plays them in `GifFramePlayer`.
struct StoryGifView: View {
    let url: URL

    @State private var frames: [UIImage] = []
    @State private var totalDuration: Double = 0
    @State private var loadFailed = false

    var body: some View {
        Group {
            if frames.isEmpty {
                if loadFailed {
                    Color.black
                } else {
                    Color.black.opacity(0.001)
                }
            } else {
                GifFramePlayer(frames: frames, duration: totalDuration)
            }
        }
        .task(id: url) {
            guard let (data, _) = try? await URLSession.shared.data(from: url) else {
                loadFailed = true
                return
            }
            let decoded = GifDecoder.decode(data)
            frames = decoded.frames
            totalDuration = decoded.duration
            loadFailed = decoded.frames.isEmpty
        }
    }
}

/// Controls-free, muted, aspect-fill video surface for story slides.
/// Reports the measured duration (progress-timer cap), buffering state,
/// playback end and source errors.
struct StoryVideoLayer: UIViewRepresentable {
    let url: URL
    let paused: Bool
    let onEnded: () -> Void
    let onDurationMeasured: (Double) -> Void
    var onBuffering: (Bool) -> Void = { _ in }
    var onError: () -> Void = {}

    final class PlayerHostView: UIView {
        var player: AVPlayer? {
            didSet { playerLayer.player = player }
        }

        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> PlayerHostView {
        let view = PlayerHostView()
        view.playerLayer.videoGravity = .resizeAspectFill
        let item = AVPlayerItem(url: url)
        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        view.player = player
        context.coordinator.attach(
            player: player, item: item,
            onEnded: onEnded, onDuration: onDurationMeasured,
            onBuffering: onBuffering, onError: onError
        )
        player.play()
        return view
    }

    func updateUIView(_ uiView: PlayerHostView, context: Context) {
        guard let player = uiView.player else { return }
        if paused { player.pause() } else { player.play() }
    }

    static func dismantleUIView(_ uiView: PlayerHostView, coordinator: Coordinator) {
        coordinator.detach()
        uiView.player?.replaceCurrentItem(with: nil)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: @unchecked Sendable {
        private var endObserver: NSObjectProtocol?
        private var statusObserver: NSKeyValueObservation?
        private var timeControlObserver: NSKeyValueObservation?
        private var player: AVPlayer?
        private var onEnded: (() -> Void)?
        private var onDuration: ((Double) -> Void)?
        private var onBuffering: ((Bool) -> Void)?
        private var onError: (() -> Void)?

        func attach(
            player: AVPlayer,
            item: AVPlayerItem,
            onEnded: @escaping () -> Void,
            onDuration: @escaping (Double) -> Void,
            onBuffering: @escaping (Bool) -> Void,
            onError: @escaping () -> Void
        ) {
            self.player = player
            self.onEnded = onEnded
            self.onDuration = onDuration
            self.onBuffering = onBuffering
            self.onError = onError
            endObserver = NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: item,
                queue: .main
            ) { [weak self] _ in
                self?.onEnded?()
            }
            statusObserver = item.observe(\.status, options: [.new]) { [weak self] item, _ in
                switch item.status {
                case .readyToPlay:
                    let seconds = item.duration.seconds
                    if seconds.isFinite, seconds > 0 {
                        DispatchQueue.main.async { self?.onDuration?(seconds) }
                    }
                case .failed:
                    DispatchQueue.main.async { self?.onBuffering?(false); self?.onError?() }
                default:
                    break
                }
            }
            // Buffering = waiting for media while meant to be playing.
            timeControlObserver = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
                let waiting = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
                DispatchQueue.main.async { self?.onBuffering?(waiting) }
            }
        }

        func detach() {
            if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
            statusObserver?.invalidate()
            timeControlObserver?.invalidate()
            endObserver = nil
            statusObserver = nil
            timeControlObserver = nil
            player?.pause()
            player = nil
            onEnded = nil
            onDuration = nil
            onBuffering = nil
            onError = nil
        }

        deinit {
            if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
            statusObserver?.invalidate()
            timeControlObserver?.invalidate()
        }
    }
}
