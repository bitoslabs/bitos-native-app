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

    /// Keyed by verified event id; bounded to three entries by update().
    private var slots: [String: Slot] = [:]

    /// Reconcile slots with the note at [visibleId] ± 1.
    func update(visibleId: String?, notes: [FeedNote]) {
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
            let item = AVPlayerItem(url: videoURL)
            let looper = AVPlayerLooper(player: player, templateItem: item)
            slots[id] = Slot(player: player, looper: looper)
        }
        // Exactly the visible video plays.
        for (id, slot) in slots {
            if id == visibleId {
                slot.player.play()
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

    func releaseAll() {
        slots.values.forEach { $0.player.pause() }
        slots.removeAll()
    }

    var slotCount: Int { slots.count }
}
