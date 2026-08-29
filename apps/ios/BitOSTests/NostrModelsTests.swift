import XCTest
@testable import BitOS

/**
 * Tests over the native seams: `RelayURL` parsing for the platform transport
 * and the Swift facade over the BusinessCore XCFramework, driven by the same
 * fixture vectors as the Kotlin common suite
 * (`contracts/nostr/fixtures/verification-vectors.json`).
 *
 * Protocol vectors (canonical serialization, ID commitment, bounded decode)
 * live once in the shared common tests; this target only verifies the native
 * seam behavior.
 */
final class NostrModelsTests: XCTestCase {

    // MARK: - RelayURL (native transport value type)

    func testRelayUrlParsesAndRejects() {
        XCTAssertEqual(RelayURL.parse("wss://relay.damus.io")?.rawValue, "wss://relay.damus.io")
        XCTAssertEqual(RelayURL.parse(" wss://relay.damus.io/ ")?.rawValue, "wss://relay.damus.io")
        XCTAssertEqual(RelayURL.parse("wss://relay.damus.io")?.host, "relay.damus.io")
        XCTAssertEqual(RelayURL.parse("ws://relay.example:8080/path")?.host, "relay.example:8080")
        XCTAssertNil(RelayURL.parse("https://relay.damus.io"))
        XCTAssertNil(RelayURL.parse("wss://"))
        XCTAssertNil(RelayURL.parse(""))
        XCTAssertNil(RelayURL.parse("not a url"))
    }
}

/**
 * APP-012 read-model seam: locks the new bridge surface (origin-note media
 * strip keys, read-cursor + search predicates) against the XCFramework.
 * The underlying rules are locked once in the shared common suite; the
 * NIP-36 tag form needs tagged compose helpers and stays covered there.
 */
final class NotificationBridgeRuleTests: XCTestCase {
    private let bridge = BusinessCoreBridge()
    private let secretHex = String(repeating: "01", count: 32) // public test key

    func testOriginNoteFrameCarriesTheMediaStrip() throws {
        let pubkey = try XCTUnwrap(bridge.derivePublicKey(secretHex: secretHex))
        let content = "pics https://a.example/1.png and https://a.example/2.jpg end"
        let id = try XCTUnwrap(bridge.composeEventId(content: content, pubkeyHex: pubkey, nowSeconds: 1_700_000_000))
        let signature = try XCTUnwrap(bridge.signDetached(messageHex: id, secretHex: secretHex, auxHex: ""))
        let frame = #"["EVENT","sub",{"id":"\#(id)","pubkey":"\#(pubkey)","created_at":1700000000,"kind":1,"tags":[],"content":"\#(content)","sig":"\#(signature)"}]"#

        let note = try XCTUnwrap(
            bridge.originNoteFromFrame(message: frame, relayUrl: "wss://relay.test", wantedIds: [id]) as? [String: Any]
        )
        XCTAssertEqual(note["mediaUrls"] as? [String], ["https://a.example/1.png", "https://a.example/2.jpg"])
        XCTAssertEqual((note["contentWarning"] as? KotlinBoolean)?.boolValue, false)

        // An id outside the wanted set yields nothing.
        XCTAssertNil(bridge.originNoteFromFrame(message: frame, relayUrl: "wss://relay.test", wantedIds: ["ff" + String(repeating: "0", count: 63)]))
    }

    func testCursorAndQueryPredicates() {
        // Cursor: at-or-below reads implicitly; above stays unread; explicit
        // ids read without any cursor (−1 sentinel).
        XCTAssertTrue(bridge.notificationCursorIsRead(id: "a", createdAtSeconds: 100, cursorSeconds: 100, explicitlyRead: []))
        XCTAssertTrue(bridge.notificationCursorIsRead(id: "a", createdAtSeconds: 99, cursorSeconds: 100, explicitlyRead: []))
        XCTAssertFalse(bridge.notificationCursorIsRead(id: "a", createdAtSeconds: 101, cursorSeconds: 100, explicitlyRead: []))
        XCTAssertTrue(bridge.notificationCursorIsRead(id: "a", createdAtSeconds: 500, cursorSeconds: -1, explicitlyRead: ["a"]))

        // Search: summary + author-name contains, case-insensitive.
        XCTAssertTrue(bridge.notificationQueryMatches(summary: "Great post about Bitcoin", authorName: nil, query: "bitcoin"))
        XCTAssertFalse(bridge.notificationQueryMatches(summary: "Great post", authorName: "Satoshi", query: "alice"))
        XCTAssertTrue(bridge.notificationQueryMatches(summary: "hello", authorName: "Satoshi Nakamoto", query: "satoshi"))
        XCTAssertTrue(bridge.notificationQueryMatches(summary: "anything", authorName: nil, query: "  "))
    }
}

final class BusinessCoreFacadeTests: XCTestCase {
    private let client = FrameworkBusinessCoreClient()

    func testDecodesVerifiedFixtureEvent() {
        let event = client.decodeVerifiedEvent(
            message: Self.validTextNoteMessage, relay: "wss://relay.damus.io"
        )
        XCTAssertNotNil(event)
        XCTAssertEqual(event?.id, "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a")
        XCTAssertEqual(event?.kind, 1)
        XCTAssertEqual(event?.content, "gm from BitOS")
        XCTAssertEqual(event?.relayUrl, "wss://relay.damus.io")
        XCTAssertTrue(client.isFeedKind(event!.kind))
        XCTAssertFalse(client.isProfileKind(event!.kind))

        let note = client.feedNote(from: event!)
        XCTAssertEqual(note.id, event?.id)
        XCTAssertEqual(note.hashtags, ["bitcoin"])
    }

    func testRejectsUnsignedAndMalformedFrames() {
        let unsigned = #"["EVENT","sub1",{"id":"\#(String(repeating: "0", count: 64))","pubkey":"\#(String(repeating: "a", count: 64))","created_at":1,"kind":1,"tags":[],"content":"x"}]"#
        XCTAssertNil(client.decodeVerifiedEvent(message: unsigned, relay: "wss://relay.damus.io"))
        XCTAssertNil(client.decodeVerifiedEvent(message: "not json", relay: "wss://relay.damus.io"))
        XCTAssertNil(client.decodeVerifiedEvent(message: #"["NOTICE","x"]"#, relay: "wss://relay.damus.io"))
        XCTAssertNil(client.decodeVerifiedEvent(message: Self.validTextNoteMessage, relay: "https://not-websocket.example"))
        XCTAssertNil(client.decodeVerifiedEvent(message: Self.validTextNoteMessage, relay: nil))
    }

    func testParsesFixtureProfile() {
        let event = client.decodeVerifiedEvent(
            message: Self.validProfileMetadataMessage, relay: "wss://nos.lol"
        )
        XCTAssertNotNil(event)
        let profile = client.profile(from: event!)
        XCTAssertEqual(profile?.name, "satoshi")
        XCTAssertEqual(profile?.displayName, "Satoshi ₿")
        XCTAssertEqual(profile?.bestDisplayName, "Satoshi ₿")
    }

    func testRejectsSignatureUnverifiedEvents() {
        // SBC-006: a well-formed event whose ID was recomputed for tampered
        // content, carrying another event's signature, must be rejected by
        // the decode path (both trust stages run in the shared bridge).
        XCTAssertNil(client.decodeVerifiedEvent(message: Self.validIdWrongSignatureMessage, relay: "wss://relay.damus.io"))
    }

    func testEncodesSubscriptionMessages() {
        let request = client.feedRequest(subscriptionId: "feed1")
        XCTAssertTrue(request.hasPrefix(#"["REQ","feed1","#))
        XCTAssertTrue(request.contains(#""kinds":[21,22],"limit":16"#))
        XCTAssertTrue(request.contains(#""kinds":[1],"limit":48"#))
        XCTAssertEqual(client.close(subscriptionId: "feed1"), #"["CLOSE","feed1"]"#)
    }

    func testFeedWindowDeduplicatesAndBounds() {
        let window = client.makeFeedWindow(maxItems: 3)
        let event = client.decodeVerifiedEvent(message: Self.validTextNoteMessage, relay: "wss://nos.lol")!
        let note = client.feedNote(from: event)
        XCTAssertTrue(window.insert(note))
        XCTAssertFalse(window.insert(note))
        XCTAssertEqual(window.count(), 1)

        let fresh = (0..<10).map { index in
            FeedNote(id: "id\(index)", pubkey: note.pubkey, content: "c\(index)",
                     createdAt: 1_710_003_000 + Int64(index), kind: 1, replyTo: nil,
                     hashtags: [], mentions: [], mediaUrls: [], isProtocolPayload: false)
        }
        fresh.forEach { window.insert($0) }
        XCTAssertEqual(window.count(), 3)
        XCTAssertEqual(window.snapshot().map(\.id), ["id9", "id8", "id7"])
    }

    // Verbatim relay frames from contracts/nostr/fixtures/verification-vectors.json.
    private static let validTextNoteMessage = #"["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]"#
    private static let validProfileMetadataMessage = #"["EVENT","sub1",{"kind":0,"created_at":1710000100,"tags":[],"content":"{\"name\":\"satoshi\",\"display_name\":\"Satoshi ₿\",\"about\":\"test vector\"}","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"1bdf4e4f9f406ce12418060a4f6296fb1f983d5cd75d6cd583263e09ae78d2a5","sig":"f21868a6a8be0823603064a38832404508746fd6ce3c92a627eabc55b0562bd6477901518273b2c483c628c7b1be74cb444a33fbbe26d174fc211cca234c11a6"}]"#
    private static let validIdWrongSignatureMessage = #"["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"tampered but re-identified","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"2cbc3c8affa0828e03b11f975337317f8e415397933fc87069265e17f719e95b","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]"#
}
