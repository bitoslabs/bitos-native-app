import XCTest
@testable import BitOS

final class BusinessCoreClientTests: XCTestCase {
    func testFrameworkClientIsReachable() {
        let client = FrameworkBusinessCoreClient()
        XCTAssertTrue(client.isFeedKind(1))
        XCTAssertTrue(client.isFeedKind(22))
        XCTAssertFalse(client.isFeedKind(0))
        XCTAssertTrue(client.isProfileKind(0))
    }

    /// Raw-event viewer seam (card ⋯ "View raw event JSON"): a decoded
    /// signed frame rebuilds the canonical NIP-01 event object — canonical
    /// key order, escaped content, relay frame wrapper dropped, sig kept.
    func testEventJsonRebuildsTheCanonicalSignedObject() {
        let client = FrameworkBusinessCoreClient()
        // Verbatim fixture frame from contracts/nostr/fixtures/verification-vectors.json.
        let frame = #"["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]"#
        guard let decoded = client.decodeVerifiedEventFrame(message: frame, relay: "wss://relay.damus.io") else {
            return XCTFail("fixture frame must decode")
        }
        let json = client.eventJson(decoded.event)
        XCTAssertTrue(json.hasPrefix(#"{"id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001""#), json)
        XCTAssertTrue(json.contains(#""created_at":1710000000,"kind":1,"tags":[["t","bitcoin"]],"content":"gm from BitOS""#), json)
        XCTAssertTrue(json.hasSuffix(#","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}"#), json)
    }

    /// APP-019 studio seams (plan MST-005/010..015): the meme editor works
    /// the project wire through the client — normalize clamps, commands
    /// apply, hit-test selects, palette/packs feed the sheets, bounds feed
    /// the selection chrome. Mirrors the common `memeSeams*` battery.
    func testMemeStudioSeams() {
        let client = FrameworkBusinessCoreClient()
        let project = #"{"v":1,"mode":"image","assets":[],"overlays":[]}"#

        let normalized = client.memeProjectNormalize(project)
        XCTAssertTrue(normalized.contains(#""mode":"image""#), normalized)
        XCTAssertEqual("", client.memeProjectNormalize("junk"))
        XCTAssertEqual("", client.memeApplyCommand("junk", commandJson: "{\"op\":\"remove\",\"id\":\"o1\"}"))

        // Default overlay is add-ready and round-trips through a command.
        let overlayJson = client.memeDefaultOverlay(normalized, kind: "text", text: "gm")
        XCTAssertTrue(overlayJson.contains(#""id":"o1""#), overlayJson)
        let added = client.memeApplyCommand(
            normalized,
            commandJson: "{\"op\":\"add\",\"overlay\":\(overlayJson)}"
        )
        XCTAssertTrue(added.contains(#""text":"gm""#), added)
        // Unknown kind / corrupt project → "" (never a half-built overlay).
        XCTAssertEqual("", client.memeDefaultOverlay(normalized, kind: "nope", text: "gm"))

        // Hit-test + bounds feed the selection chrome.
        XCTAssertEqual("o1", client.memeHitTest(added, x: 0.42, y: 0.35))
        XCTAssertEqual("", client.memeHitTest(added, x: 0.99, y: 0.99))
        XCTAssertTrue(client.memeBounds(added, overlayId: "o1").contains("|"))
        XCTAssertEqual("", client.memeBounds(added, overlayId: "ghost"))

        // Palette + sticker packs feed the sheets (web-parity shapes).
        let palette = client.memePalette()
        XCTAssertEqual(16, palette.count)
        XCTAssertEqual("FFFFFF", palette.first)
        let packs = MemeEditorStore.decodePacks(client.memeStickerPacks())
        XCTAssertEqual(6, packs.count)
        XCTAssertEqual(8, packs.first?.stickers.count)
    }

    /// MST-019: the `com.bitos.bitz.meme` v1 interop wire through the
    /// client — normalize re-stamps and keeps passthrough, wire → local
    /// lands fraction sizes in px, local → wire re-exports the web shape.
    func testMemeWireDocumentSeams() {
        let client = FrameworkBusinessCoreClient()
        let wire = #"""
        {"schema":"com.bitos.bitz.meme","version":1,
         "overlays":[{"id":"t1","text":"wen moon","x":0.2,"y":0.8,
          "size":0.09,"color":"#fde047","font":"impact","startMs":250,
          "endMs":9000,"fx":"pop","futureField":7}]}
        """#
        let now: Int64 = 1_700_000_000_000

        let normalized = client.memeWireNormalize(wire, nowMs: now)
        XCTAssertTrue(normalized.contains(#""schema":"com.bitos.bitz.meme""#), normalized)
        XCTAssertTrue(normalized.contains(#""futureField":7"#), normalized, "passthrough preserved")
        XCTAssertTrue(normalized.contains(#""updatedAt":\#(now)"#), normalized)
        XCTAssertEqual("", client.memeWireNormalize(#"{"schema":"com.other.meme"}"#, nowMs: now))
        XCTAssertEqual("", client.memeWireToLocal("junk"))
        XCTAssertEqual("", client.localToMemeWire("junk", nowMs: now))

        let local = client.memeWireToLocal(wire)
        XCTAssertTrue(local.contains(#""size":97"#), local, "0.09 × 1080 → 97 px")
        XCTAssertTrue(local.contains(#""startMs":250"#), local)
        XCTAssertTrue(local.contains(#""fx":"pop""#), local)

        let exported = client.localToMemeWire(local, nowMs: now)
        XCTAssertTrue(exported.contains(#""text":"wen moon""#), exported)
        XCTAssertTrue(exported.contains(#""size":0.0898"#), exported, "px-referenced quantization")
    }

    /// MST-016: the export envelope carries the evened, long-edge-capped
    /// canvas plus target-px paint rows — the Swift rasterizer paints only.
    @MainActor
    func testMemeExportEnvelopeSeam() {
        let client = FrameworkBusinessCoreClient()
        let store = MemeEditorStore(client: FixtureBusinessCoreClient())
        store.addOverlay(kind: "text", text: "gm")
        let envelope = client.memeExportPlan(
            store.projectJson,
            sourceWidth: 1080,
            sourceHeight: 1920
        )
        XCTAssertTrue(envelope.contains(#""width":608"#), envelope)
        XCTAssertTrue(envelope.contains(#""height":1080"#), envelope)
        XCTAssertTrue(envelope.contains(#""text":"GM""#), envelope, "caps default applies")
        XCTAssertEqual("", client.memeExportPlan("junk", sourceWidth: 10, sourceHeight: 10))
    }

    /// The Swift editor store applies commands through the same seams and
    /// keeps history bounded: a coalesced style burst and a gesture each
    /// collapse to ONE undo step.
    @MainActor
    func testMemeEditorStoreEditingLoop() {
        let store = MemeEditorStore(client: FixtureBusinessCoreClient())
        store.addOverlay(kind: "text", text: "gm")
        XCTAssertEqual(1, store.overlays.count)
        XCTAssertEqual(store.overlays.first?.id, store.selectedId)
        XCTAssertTrue(store.canUndo)

        // Two style edits in a burst = one undo step back to pre-burst.
        guard let id = store.selectedId else { return XCTFail("no selection") }
        store.updateStyle(id, fields: ["shadow": true])
        store.updateStyle(id, fields: ["color": 3])
        store.undo()
        guard let restored = store.overlays.first else { return XCTFail("overlay lost") }
        XCTAssertFalse(restored.shadow)
        XCTAssertEqual(0, restored.colorIndex)

        // Gesture: pan live, end once → one step back to pre-gesture.
        let preGesture = store.overlays.first?.x
        store.beginGesture()
        store.gesturePan(dx: 0.3, dy: 0)
        store.endGesture()
        XCTAssertNotEqual(preGesture, store.overlays.first?.x)
        store.undo()
        XCTAssertEqual(preGesture, store.overlays.first?.x)

        // Tap empty canvas deselects; undo exhaustion stops cleanly.
        store.clearSelection()
        while store.canUndo { store.undo() }
        XCTAssertTrue(store.overlays.isEmpty)
    }

    /// Prototype `create-edit` FX-panel parity: the adjust seam composes
    /// the look + manual sliders into ONE matrix, and `SetAdjust` lands on
    /// the wire as an undoable, coalesced step. Mirrors `MemeAdjustTest`.
    @MainActor
    func testMemeAdjustSeamAndEditingLoop() {
        let client = FrameworkBusinessCoreClient()
        // Defaults reduce to the plain look envelope (byte-identical).
        XCTAssertEqual(
            client.memeLookMatrix("vhs"),
            client.memeAdjustMatrix("vhs", brightness: 1, contrast: 1, saturation: 1)
        )
        // A real slider moves the matrix away from the plain look.
        let composed = client.memeAdjustMatrix("vhs", brightness: 1.5, contrast: 1, saturation: 1)
        XCTAssertTrue(composed.contains("\"matrix\":"), composed)
        XCTAssertNotEqual(client.memeLookMatrix("vhs"), composed)

        // Store loop: the triple rides the wire, and a slider burst
        // collapses to ONE undo step back to the pre-adjust state.
        let store = MemeEditorStore(client: FixtureBusinessCoreClient())
        XCTAssertFalse(store.hasAdjust)
        store.setAdjust(brightness: 1.2, contrast: 1, saturation: 0.5)
        XCTAssertTrue(store.hasAdjust)
        XCTAssertTrue(store.projectJson.contains("\"adjust\""), store.projectJson)
        store.setAdjust(brightness: 1.4, contrast: 1, saturation: 0.5)
        XCTAssertEqual(1.4, store.adjustBrightness, accuracy: 0.001)
        store.undo()
        XCTAssertFalse(store.hasAdjust, "one burst = one step back to pre-adjust")

        // Classic meme captions land as a positioned pair in ONE step.
        store.addMemeCaptions(top: "when the fee", bottom: "drops", fontSlot: "impact")
        XCTAssertEqual(2, store.overlays.count)
        XCTAssertEqual(0.16, store.overlays.first?.y ?? 0, accuracy: 0.001)
        XCTAssertEqual(0.84, store.overlays.last?.y ?? 0, accuracy: 0.001)
        XCTAssertEqual("WHEN THE FEE", store.overlays.first?.text)
        store.undo()
        XCTAssertTrue(store.overlays.isEmpty, "the pair undoes as one step")
    }

    /// Cold-start account bootstrap (shared `AccountBootstrap` through the
    /// client seam): unresolved heads re-issue while connected and within
    /// budget; grown connectivity opens a new episode.
    func testAccountBootstrapSeam() {
        let client = FixtureBusinessCoreClient()
        XCTAssertTrue(client.accountBootstrapShouldReissue(resolved: false, attempts: 0, connectedRelays: 1))
        XCTAssertFalse(client.accountBootstrapShouldReissue(resolved: true, attempts: 0, connectedRelays: 3))
        XCTAssertFalse(client.accountBootstrapShouldReissue(resolved: false, attempts: 0, connectedRelays: 0))
        XCTAssertFalse(client.accountBootstrapShouldReissue(resolved: false, attempts: 5, connectedRelays: 2))
        XCTAssertTrue(client.accountBootstrapShouldOpenEpisode(previousConnected: 0, currentConnected: 1))
        XCTAssertFalse(client.accountBootstrapShouldOpenEpisode(previousConnected: 2, currentConnected: 1))
    }

    /// Adapter-contract (performance-audit R11): a note projected through
    /// the shared window survives insert → snapshot with EVERY presentation
    /// field intact — thread anchors, poll options, remix source, license
    /// and the FED-004 media ladder included.
    func testFeedWindowRoundTripsAllPresentationFields() {
        let client = FrameworkBusinessCoreClient()
        let window = client.makeFeedWindow(maxItems: 4)
        let full = FeedNote(
            id: String(repeating: "11", count: 32),
            pubkey: String(repeating: "22", count: 32),
            content: "poll + remix + video",
            createdAt: 1_710_005_000,
            kind: 1,
            replyTo: String(repeating: "33", count: 32),
            hashtags: ["bitcoin"],
            mentions: ["satoshi"],
            mediaUrls: ["https://cdn.io/a.png"],
            isProtocolPayload: false,
            repostedBy: String(repeating: "44", count: 32),
            video: MediaMetadata(
                url: "https://cdn.io/a.mp4",
                mimeType: "video/mp4",
                posterUrl: "https://cdn.io/a.jpg",
                width: 1080,
                height: 1920,
                durationSeconds: 42,
                fallbackUrls: ["https://mirror.io/a.mp4"],
                renditionSpecs: ["https://cdn.io/a-720.mp4|720|2500000"]
            ),
            contentWarning: true,
            threadRootId: String(repeating: "55", count: 32),
            threadParentId: String(repeating: "66", count: 32),
            pollOptions: ["yes", "no"],
            remixOfEventId: String(repeating: "77", count: 32),
            remixOfPubkey: String(repeating: "88", count: 32),
            license: "CC-BY-4.0"
        )
        XCTAssertTrue(window.insert(full))
        XCTAssertFalse(window.insert(full))
        let snapshot = window.snapshot()
        XCTAssertEqual(snapshot.count, 1)
        let roundTripped = snapshot[0]

        XCTAssertEqual(roundTripped.id, full.id)
        XCTAssertEqual(roundTripped.replyTo, full.replyTo)
        XCTAssertEqual(roundTripped.threadRootId, full.threadRootId)
        XCTAssertEqual(roundTripped.threadParentId, full.threadParentId)
        XCTAssertEqual(roundTripped.contentWarning, full.contentWarning)
        XCTAssertEqual(roundTripped.pollOptions, full.pollOptions)
        XCTAssertEqual(roundTripped.remixOfEventId, full.remixOfEventId)
        XCTAssertEqual(roundTripped.remixOfPubkey, full.remixOfPubkey)
        XCTAssertEqual(roundTripped.license, full.license)
        XCTAssertEqual(roundTripped.repostedBy, full.repostedBy)
        XCTAssertEqual(roundTripped.video?.durationSeconds, full.video?.durationSeconds)
        XCTAssertEqual(roundTripped.video?.fallbackUrls, full.video?.fallbackUrls)
        XCTAssertEqual(roundTripped.video?.renditionSpecs, full.video?.renditionSpecs)
        XCTAssertEqual(roundTripped.video?.url, full.video?.url)
        XCTAssertEqual(roundTripped.video?.posterUrl, full.video?.posterUrl)
    }

    /// Home "load more" at the cap (UX U7 regression contract): an
    /// older-page insert into a FULL window evicts at the head (newest
    /// out), so a bounded backwards walk extends the window instead of
    /// dropping the just-landed older note at the tail.
    func testInsertOlderExtendsAFullWindowBackward() {
        let client = FrameworkBusinessCoreClient()
        let window = client.makeFeedWindow(maxItems: 3)
        func note(_ id: String, _ createdAt: Int64) -> FeedNote {
            FeedNote(
                id: id, pubkey: String(repeating: "22", count: 32), content: id,
                createdAt: createdAt, kind: 1, replyTo: nil, hashtags: [], mentions: [],
                mediaUrls: [], isProtocolPayload: false
            )
        }
        window.insert(note("a", 300))
        window.insert(note("b", 200))
        window.insert(note("c", 100))
        XCTAssertTrue(window.insertOlder(note("d", 50)))
        XCTAssertEqual(window.count(), 3)
        XCTAssertEqual(window.snapshot().map(\.id), ["b", "c", "d"])
        XCTAssertFalse(window.insertOlder(note("d", 50)))
        // Live arrivals keep tail eviction — refresh re-opens the head.
        window.insert(note("e", 400))
        XCTAssertEqual(window.snapshot().map(\.id), ["e", "b", "c"])
    }
}
