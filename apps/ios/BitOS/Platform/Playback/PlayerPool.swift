import AVFoundation
import BusinessCore
import Foundation
import UIKit

/**
 * Three-slot playback coordinator (FED-001).
 *
 * At most the visible note and its immediate neighbors hold players; only
 * the visible note plays. Slots are keyed by verified event id: a recomposed
 * page rebinds by stable identity, and a released id yields nil — that is
 * the stale-callback protection from the architecture doc, expressed as
 * id-keyed lifetimes (no callback from a released player can reach a view,
 * because the view re-asks the pool each render and gets nil).
 *
 * @MainActor: AVPlayer is not thread-safe; all slot bookkeeping and playback
 * control happens on the main actor as the feed UI requires.
 */
@MainActor
final class PlayerPool {
    private struct Slot {
        let player: AVQueuePlayer
        let looper: AVPlayerLooper
    }

    /// APP-018 functional settings: [autoplayAllowed] applies the persisted
    /// media autoplay policy (always / unmetered-wifi / never) and [rate] the
    /// persisted playback-rate step — supplied per reconciliation so a
    /// settings change applies without rebuilding the pool.

    /// Keyed by verified event id; bounded to three entries by update().
    private var slots: [String: Slot] = [:]

    /// FED-004 failover chain per slot: rendition pick first, then imeta
    /// mirrors, then remaining renditions. Walked only on item failure.
    private var mediaChains: [String: [String]] = [:]
    private var chainIndexes: [String: Int] = [:]
    private var failureObservers: [String: Any] = [:]

    /// Last persisted playback rate from settings (restore target for boosts).
    private var currentRate: Float = 1

    /// Reconcile slots with the note at [visibleId] ± 1.
    func update(visibleId: String?, notes: [FeedNote], autoplayAllowed: Bool = true, rate: Float = 1, muted: Bool = false) {
        currentRate = rate
        guard let visibleId,
              let visibleIndex = notes.firstIndex(where: { $0.id == visibleId }) else {
            slots.values.forEach { $0.player.pause() }
            return
        }

        let keepIds: [String] = ((visibleIndex - 1)...(visibleIndex + 1))
            .filter { notes.indices.contains($0) }
            .map { notes[$0].id }
            .filter { id in notes.first(where: { $0.id == id })?.video != nil }

        // Release slots outside the window.
        for id in slots.keys where !keepIds.contains(id) {
            slots.removeValue(forKey: id)?.player.pause()
            mediaChains.removeValue(forKey: id)
            chainIndexes.removeValue(forKey: id)
            if let observer = failureObservers.removeValue(forKey: id) {
                NotificationCenter.default.removeObserver(observer)
            }
        }
        // Create missing slots (bounded to keepIds.count <= 3).
        for id in keepIds where slots[id] == nil {
            guard let note = notes.first(where: { $0.id == id }),
                  let video = note.video else { continue }
            // FED-004: static rendition pick (tallest fitting the display
            // long edge with ±25% headroom, shared rule) — no ABR in V1.
            let screenHeight = awaitScreenHeight()
            let chain = mediaChain(for: video, targetHeight: screenHeight)
            guard let first = chain.first, let videoURL = URL(string: first) else { continue }
            mediaChains[id] = chain
            chainIndexes[id] = 0
            let player = AVQueuePlayer()
            player.isMuted = muted
            let item = AVPlayerItem(url: videoURL)
            let looper = AVPlayerLooper(player: player, templateItem: item)
            slots[id] = Slot(player: player, looper: looper)
            installFailureObserver(noteId: id, player: player)
        }
        // Exactly the visible video plays — gated by the autoplay policy,
        // with the persisted playback rate as the default (looping keeps it).
        let autoplay = autoplayAllowed
        for (id, slot) in slots {
            slot.player.defaultRate = rate
            slot.player.isMuted = muted
            if id == visibleId {
                if autoplay {
                    if slot.player.timeControlStatus == .paused { slot.player.play() }
                    slot.player.rate = rate == 0 ? 1 : rate
                } else {
                    slot.player.pause()
                }
            } else {
                slot.player.pause()
            }
        }
    }

    func player(for noteId: String) -> AVQueuePlayer? {
        slots[noteId]?.player
    }

    // MARK: FED-004 rendition pick + mirror failover

    private let bridge = BusinessCoreBridge()

    /// Display long edge for the rendition pick (memoized; main thread).
    private var cachedScreenHeight: Int?
    private func awaitScreenHeight() -> Int {
        if let cached = cachedScreenHeight { return cached }
        let height = Int(UIScreen.main.bounds.height)
        cachedScreenHeight = height
        return height
    }

    /// Candidate chain: shared rendition pick, then mirrors, then the
    /// remaining renditions (tall→short). Pure ordering over shared data.
    private func mediaChain(for video: MediaMetadata, targetHeight: Int) -> [String] {
        let pick = bridge.mediaPickRenditionUrl(
            renditionSpecs: video.renditionSpecs,
            primaryUrl: video.url,
            targetHeight: Int32(targetHeight)
        )
        var chain = [pick]
        chain.append(contentsOf: video.fallbackUrls)
        chain.append(contentsOf: video.renditionUrls)
        var seen = Set<String>()
        return chain.filter { seen.insert($0).inserted }
    }

    /// On item failure → advance to the next candidate and re-prepare.
    private func installFailureObserver(noteId: String, player: AVQueuePlayer) {
        let observer = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: player.currentItem,
            queue: .main
        ) { [weak self] _ in
            // AVPlayerLooper replaces items; observe the live one.
            guard let self, let item = player.currentItem,
                  item.status == .failed || Self.failedError(item) else { return }
            MainActor.assumeIsolated {
                self.swapToNextCandidate(noteId: noteId, animateResume: false)
            }
        }
        failureObservers[noteId] = observer
    }

    nonisolated private static func failedError(_ item: AVPlayerItem) -> Bool {
        // Playback stalls with an unrecoverable error also count for failover.
        guard let error = item.error else { return false }
        let ns = error as NSError
        return ns.domain == AVFoundationErrorDomain || ns.domain == NSURLErrorDomain
    }

    private func swapToNextCandidate(noteId: String, animateResume: Bool) {
        guard var index = chainIndexes[noteId],
              let chain = mediaChains[noteId] else { return }
        guard index + 1 < chain.count else { return }
        index += 1
        chainIndexes[noteId] = index
        guard let slot = slots[noteId], let next = URL(string: chain[index]) else { return }
        let wasPlaying = slot.player.timeControlStatus == .playing
        slot.looper.disableLooping()
        let item = AVPlayerItem(url: next)
        slot.player.replaceCurrentItem(with: item)
        installFailureObserver(noteId: noteId, player: slot.player)
        if wasPlaying || animateResume { slot.player.play() }
    }

    func togglePlay(noteId: String) {
        guard let slot = slots[noteId] else { return }
        if slot.player.timeControlStatus == .playing {
            slot.player.pause()
        } else {
            slot.player.play()
        }
    }

    /// APP-007 mute memory: apply immediately, not only at reconciliation.
    func setMuted(_ muted: Bool) {
        slots.values.forEach { $0.player.isMuted = muted }
    }

    /// Long-press 2× fast-forward: temporary rate multiplier for one slot;
    /// nil restores the persisted playback-rate setting.
    private var rateBoostNoteId: String?

    func setRateBoost(noteId: String?, multiplier: Float?) {
        if let previous = rateBoostNoteId, let player = slots[previous]?.player, previous != noteId {
            player.defaultRate = currentRate
            player.rate = currentRate
        }
        guard let noteId, let multiplier, let player = slots[noteId]?.player else {
            rateBoostNoteId = nil
            return
        }
        rateBoostNoteId = noteId
        let boosted = currentRate * multiplier
        player.defaultRate = boosted
        player.rate = boosted == 0 ? 1 : boosted
    }

    /// Seek [noteId]'s slot by [deltaMs] (±10 s pills), clamped to the media.
    func seekBy(noteId: String, deltaMs: Int64) {
        guard let player = slots[noteId]?.player else { return }
        let duration = player.currentItem?.duration.seconds ?? 0
        let bound = duration.isFinite && duration > 0 ? duration : .infinity
        let target = max(0, min(player.currentTime().seconds + Double(deltaMs) / 1_000, bound))
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600))
    }

    /// Absolute seek for the scrubber.
    func seekTo(noteId: String, positionMs: Int64) {
        guard let player = slots[noteId]?.player else { return }
        let duration = player.currentItem?.duration.seconds ?? 0
        let bound = duration.isFinite && duration > 0 ? duration : .infinity
        let target = max(0, min(Double(positionMs) / 1_000, bound))
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600))
    }

    /// Playback head in ms (0 while the slot is absent).
    func positionMs(noteId: String) -> Int64 {
        let seconds = slots[noteId]?.player.currentTime().seconds ?? 0
        return Int64(max(0, seconds) * 1_000)
    }

    /// Prepared media duration in ms; 0 while unknown/unbounded.
    func durationMs(noteId: String) -> Int64 {
        let seconds = slots[noteId]?.player.currentItem?.duration.seconds ?? 0
        guard seconds.isFinite, seconds > 0 else { return 0 }
        return Int64(seconds * 1_000)
    }

    func releaseAll() {
        slots.values.forEach { $0.player.pause() }
        slots.removeAll()
    }

    var slotCount: Int { slots.count }
}
