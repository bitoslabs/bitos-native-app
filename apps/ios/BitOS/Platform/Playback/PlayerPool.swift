import AVFoundation
import Foundation

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
        }
        // Create missing slots (bounded to keepIds.count <= 3).
        for id in keepIds where slots[id] == nil {
            guard let url = notes.first(where: { $0.id == id })?.video?.url,
                  let videoURL = URL(string: url) else { continue }
            let player = AVQueuePlayer()
            player.isMuted = muted
            let item = AVPlayerItem(url: videoURL)
            let looper = AVPlayerLooper(player: player, templateItem: item)
            slots[id] = Slot(player: player, looper: looper)
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
