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
