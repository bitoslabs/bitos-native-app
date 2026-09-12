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

    /// MSU-001..003 shell contract through the client: the editor bars,
    /// notice host and Back resolution render from these seams, so both
    /// platforms share one tool vocabulary and one back rule. Mirrors the
    /// common `memeToolCatalogSeam*` / `memeEditor*Seam*` battery.
    func testMemeShellContractSeams() {
        let client = FrameworkBusinessCoreClient()

        // Tool catalogue: the primary cap travels with it, tokens are stable
        // and lowercase, and every mode bucket exists.
        let catalog = client.memeToolCatalog()
        XCTAssertTrue(catalog.contains(#""maxPrimary":5"#), catalog)
        XCTAssertTrue(catalog.contains(#""id":"media""#), catalog)
        XCTAssertTrue(catalog.contains(#""id":"time_window""#), catalog)
        for mode in ["image", "gif", "video"] {
            XCTAssertTrue(catalog.contains(#""\#(mode)""#), catalog)
        }
        // The shipped duplicate tool labels must not reappear.
        for legacy in ["Filter", "Adjust", "Effects"] {
            XCTAssertFalse(catalog.contains(#""label":"\#(legacy)""#), catalog)
        }

        // Notice defaults: errors are persistent, others auto-dismiss; an
        // unknown severity resolves to INFO (never throws).
        let info = client.memeEditorNoticeDefault(severity: "info")
        XCTAssertTrue(info.contains(#""timeoutMs":2400"#), info)
        XCTAssertTrue(info.contains(#""persistent":false"#), info)
        let error = client.memeEditorNoticeDefault(severity: "error")
        XCTAssertTrue(error.contains(#""timeoutMs":0"#), error)
        XCTAssertTrue(error.contains(#""persistent":true"#), error)
        XCTAssertEqual(info, client.memeEditorNoticeDefault(severity: "nonsense"))

        // Back contract: nothing open → exit; a sheet closes first.
        let none = client.memeEditorSurfaceBack(surface: "none")
        XCTAssertTrue(none.contains(#""exits":true"#), none)
        let look = client.memeEditorSurfaceBack(surface: "sheet:look")
        XCTAssertTrue(look.contains(#""exits":false"#), look)
        XCTAssertTrue(look.contains(#""next":"none""#), look)
        XCTAssertTrue(client.memeEditorSurfaceBack(surface: "compose:o1").contains(#""next":"none""#))
        XCTAssertTrue(client.memeEditorSurfaceBack(surface: "timeline").contains(#""exits":false"#))
        // A malformed token degrades to "none" (lenient, never throws).
        XCTAssertEqual(none, client.memeEditorSurfaceBack(surface: "garbage"))
    }

    /// MSU-040 notice host through the client: post/tick/dismiss, with
    /// duplicates swallowed and errors persistent.
    func testMemeNoticeHostSeams() {
        let client = FrameworkBusinessCoreClient()
        let posted = client.memeNoticePost(
            stateJson: "", severity: "info", message: "Layer added",
            actionId: nil, actionLabel: nil, timeoutMs: -1
        )
        XCTAssertTrue(posted.contains(#""visible":true"#), posted)
        XCTAssertTrue(posted.contains(#""message":"Layer added""#), posted)
        // An identical re-post does not restart the clock.
        let again = client.memeNoticePost(
            stateJson: posted, severity: "info", message: "Layer added",
            actionId: nil, actionLabel: nil, timeoutMs: -1
        )
        XCTAssertTrue(again.contains(#""elapsedMs":0"#), again)
        // Past the INFO window → dismissed.
        XCTAssertTrue(client.memeNoticeTick(stateJson: posted, deltaMs: 3000).contains(#""visible":false"#))
        // An error is persistent.
        let error = client.memeNoticePost(
            stateJson: "", severity: "error", message: "Save failed",
            actionId: nil, actionLabel: nil, timeoutMs: -1
        )
        XCTAssertTrue(client.memeNoticeTick(stateJson: error, deltaMs: 10_000_000).contains(#""visible":true"#))
        // The Undo action token rides along.
        let undoable = client.memeNoticePost(
            stateJson: "", severity: "info", message: "Overlay removed",
            actionId: "undo", actionLabel: "Undo", timeoutMs: -1
        )
        XCTAssertTrue(undoable.contains(#""id":"undo""#), undoable)
        XCTAssertTrue(client.memeNoticeDismiss(stateJson: undoable).contains(#""visible":false"#))
        // Junk state degrades to empty (never throws).
        XCTAssertTrue(client.memeNoticeTick(stateJson: "junk", deltaMs: 10).contains(#""visible":false"#))
    }

    /// MSU-050..052: the publish-vs-export copy seam decodes the shared
    /// contract (explainer, verbs, review order, result-card labels).
    func testMemePublishCopySeam() {
        let client = FrameworkBusinessCoreClient()
        let json = client.memePublishCopy()
        XCTAssertTrue(json.contains(#""primaryAction":"Next""#), json)
        XCTAssertTrue(json.contains(#""exportAction":"Save a copy""#), json)
        XCTAssertTrue(json.contains(#""posted":"Posted""#), json)
        XCTAssertTrue(json.contains(#""makeAnother":"Make another""#), json)
        XCTAssertTrue(json.contains(#""id":"preview""#), json)
        let decoded = StudioPublishCopy.decode()
        XCTAssertEqual(decoded.reviewSteps.map(\.id), ["preview", "caption", "tags", "safety", "publish"])
        XCTAssertEqual(decoded.primaryAction, "Next")
    }

    /// MSU-060..063: the mass-production + operator copy seam decodes the
    /// shared contract (batch base, strip, template batch, shortcuts).
    func testMemeProductionCopySeam() {
        let client = FrameworkBusinessCoreClient()
        let json = client.memeProductionCopy()
        XCTAssertTrue(json.contains(#""batchBaseAction":"Use as batch base""#), json)
        XCTAssertTrue(json.contains(#""shortcutsTitle":"Keyboard shortcuts""#), json)
        XCTAssertTrue(json.contains(#""id":"undo""#), json)
        let decoded = StudioProductionCopy.decode()
        XCTAssertEqual(decoded.batchBaseAction, "Use as batch base")
        XCTAssertEqual(decoded.batchStrip(rendered: 3, total: 8), "3 of 8 rendered · View queue")
        XCTAssertNil(decoded.batchStrip(rendered: 0, total: 0))
        XCTAssertEqual(decoded.batchStrip(rendered: 9, total: 8), "8 of 8 rendered · View queue")
        XCTAssertEqual(decoded.templateBatchAction(count: 3), "Make 3 variants")
        XCTAssertEqual(decoded.templateBatchAction(count: 1), "Make 1 variant")
        // Touch-first: every control names an on-screen affordance.
        XCTAssertTrue(decoded.controls.contains { $0.id == "publish" && $0.touch == "Header Next" })
        XCTAssertTrue(decoded.controls.allSatisfy { !$0.touch.isEmpty })
        XCTAssertEqual(decoded.keyboardRows.count, 9)
    }

    /// MSU-042..043: the feedback-closure seam decodes the busy surfaces
    /// and the confirm-vs-undo classification.
    func testMemeFeedbackCopySeam() {
        let client = FrameworkBusinessCoreClient()
        let json = client.memeFeedbackCopy()
        XCTAssertTrue(json.contains(#""id":"export-render""#), json)
        XCTAssertTrue(json.contains(#""determinate":false"#), json)
        XCTAssertTrue(json.contains(#""id":"mode-switch""#), json)
        XCTAssertTrue(json.contains(#""mode":"undo""#), json)
        let decoded = StudioFeedbackCopy.decode()
        XCTAssertEqual(decoded.surfaceFor("import-clips").title, "Preparing clips…")
        XCTAssertFalse(decoded.surfaceFor("export-render").determinate)
        XCTAssertTrue(decoded.requiresDialog("mode-switch"))
        XCTAssertFalse(decoded.requiresDialog("delete-overlay"))
        XCTAssertNil(decoded.fraction(decoded.surfaceFor("export-render"), done: 1, total: 2))
        XCTAssertEqual(decoded.fraction(decoded.surfaceFor("import-clips"), done: 1, total: 2), 0.5)
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

    /// MST-036 export-quality seams: the shared preset table + canvas math
    /// + estimate/gate serve iOS verbatim — identical numbers to Android
    /// (mirrors the common `memeEncoderPlanAndEstimateSeams…` battery).
    func testMemeEncoderPlanAndEstimateSeams() {
        let client = FrameworkBusinessCoreClient()
        // AUTO on a 9:16 portrait source: long edge caps at 1080 → 608×1080
        // at 6 Mbps + 128 k audio (the shared web-parity canvas math).
        let plan = client.memeEncoderPlan("P1080", quality: "HIGH", sourceWidth: 1080, sourceHeight: 1920)
        XCTAssertTrue(plan.contains(#""bitrate":6000000"#), plan)
        XCTAssertTrue(plan.contains(#""audioBitrate":128000"#), plan)
        XCTAssertTrue(plan.contains(#""width":608"#), plan)
        XCTAssertTrue(plan.contains(#""height":1080"#), plan)
        XCTAssertEqual(plan, client.memeEncoderPlan("p1080", quality: "high", sourceWidth: 1080, sourceHeight: 1920))
        // Estimate + gate: 90 s at AUTO ≈ 71 MB → blocked; 720p/Medium fits.
        let blocked = client.memeExportEstimate("P1080", quality: "HIGH", durationMs: 90_000)
        XCTAssertTrue(blocked.contains(#""publishFits":false"#), blocked)
        XCTAssertTrue(blocked.contains(#""bytes":71008200"#), blocked)
        let fits = client.memeExportEstimate("P720", quality: "MEDIUM", durationMs: 90_000)
        XCTAssertTrue(fits.contains(#""publishFits":true"#), fits)
        XCTAssertTrue(fits.contains(#""label":"≈ 29.0 MB""#), fits)
        // Unknown tiers normalize to "" — never throws.
        XCTAssertEqual("", client.memeEncoderPlan("P2160", quality: "HIGH", sourceWidth: 1080, sourceHeight: 1920))
        XCTAssertEqual("", client.memeExportEstimate("P720", quality: "ULTRA", durationMs: 90_000))
        // The exporter's preset parse consumes the same seam.
        let probe = MemeVideoExportIos.Probe(width: 1080, height: 1920, durationMs: 60_000, rotationDeg: 0)
        let parsed = MemeVideoExportIos.encoderPlan(.auto, probe: probe, client: client)
        XCTAssertEqual(6_000_000, parsed?.videoBitrate)
        XCTAssertEqual(608, parsed?.width)
        XCTAssertEqual(1080, parsed?.height)
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
