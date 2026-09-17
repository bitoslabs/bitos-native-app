import BusinessCore
import Foundation

/// Live connection phase surfaced to the awaiting-signer UI (ID-006a).
enum Nip46SessionPhase: String, Sendable {
    case idle, connecting, awaitingSigner, closed
}

/// One account's bunker connection target — public metadata only.
struct Nip46SessionConfig: Sendable {
    let bunkerPubkeyHex: String
    let relays: [RelayURL]
    let label: String?
    let accountPubkeyHex: String
}

/// Setup handshake result (ID-006b): bunker-confirmed account key or typed failure.
enum Nip46SetupResult: Sendable {
    case connected(accountPubkeyHex: String)
    case failed(code: String)
}

/// Swift mirror of the shared `SignEventResult` for the publish seam.
enum Nip46SignOutcome: Sendable, Equatable {
    case signed(eventJson: String, id: String, sig: String)
    case denied
    case timedOut
    case unavailable(String)
    case rejected(String)
    case cancelled
    case invalid(String)

    var outcomeCode: String {
        switch self {
        case .signed: return "signed"
        case .denied: return "denied"
        case .timedOut: return "timeout"
        case .unavailable: return "unavailable"
        case .rejected: return "rejected"
        case .cancelled: return "cancelled"
        case .invalid: return "invalid"
        }
    }
}

/**
 * NIP-46 relay-lane session (ID-006a, iOS): dedicated cancellable sockets
 * for one account's bunker — separate from the feed `RelayPool` budget,
 * alive ONLY while a foreground request is waiting. Protocol logic (URI
 * parsing, NIP-44 wrap, correlation, validation) lives in the shared
 * reducer behind `BusinessCoreBridge`; this actor owns sockets, the
 * Keychain transport key, deadlines and teardown. No secret ever leaves
 * this actor except as bridge function arguments.
 */
actor Nip46SignerSession {

    private let config: Nip46SessionConfig
    private let urlSession: URLSession
    private let signTimeoutMs: Int64
    private let setupTimeoutMs: Int64

    private var transportSecretHex: String?
    private var transportPubkeyHex: String?
    private var stateJson: String = ""
    private var lastSentEventJson: String = ""
    private var lastSentEventId: String = ""
    private var sockets: [RelayURL: URLSessionWebSocketTask] = [:]
    private var receiveTasks: [RelayURL: Task<Void, Never>] = [:]
    private var outcome: [String: Any]?

    private(set) var phase: Nip46SessionPhase = .idle
    private(set) var awaitingAuthUrl = false

    /// Setup attempts use an ephemeral transport key: nothing is sealed
    /// until the user confirms the returned account (ID-006b).
    private let ephemeralTransportKey: Bool

    init(config: Nip46SessionConfig,
         urlSession: URLSession = .bitOSRelaySession(),
         signTimeoutMs: Int64 = 120_000,
         setupTimeoutMs: Int64 = 30_000,
         ephemeralTransportKey: Bool = false) {
        self.config = config
        self.urlSession = urlSession
        self.signTimeoutMs = signTimeoutMs
        self.setupTimeoutMs = setupTimeoutMs
        self.ephemeralTransportKey = ephemeralTransportKey
    }

    // MARK: - Public surface

    /// Ensures the client transport key exists (sealed per-account slot).
    func ensureTransportKey() -> Bool {
        if transportSecretHex != nil { return true }
        if ephemeralTransportKey {
            guard let fresh = Self.randomHex64() else { return false }
            transportSecretHex = fresh
            transportPubkeyHex = BusinessCoreBridge().derivePublicKey(secretHex: fresh)
            return transportPubkeyHex != nil
        }
        let existing = IdentityKeychain.loadNip46TransportSecret(slotPubkey: config.accountPubkeyHex)
        let secret: String
        if let existing {
            secret = existing
        } else {
            guard let fresh = Self.randomHex64() else { return false }
            guard IdentityKeychain.saveNip46TransportSecret(fresh, slotPubkey: config.accountPubkeyHex) else { return false }
            secret = fresh
        }
        guard let pubkey = BusinessCoreBridge().derivePublicKey(secretHex: secret) else { return false }
        transportSecretHex = secret
        transportPubkeyHex = pubkey
        return true
    }

    /// The `nostrconnect://` pairing URI for this session's transport key.
    func pairingUri(secret: String, appName: String) -> String? {
        guard ensureTransportKey(), let clientPubkey = transportPubkeyHex else { return nil }
        let relaysJson = Self.jsonArrayString(config.relays.map(\.rawValue))
        return BusinessCoreBridge().nip46BuildPairingUri(
            pubkeyHex: clientPubkey, relaysJson: relaysJson, secret: secret, appName: appName
        )
    }

    /// bunker:// setup handshake: `connect` then `get_public_key`.
    func connectAndGetPublicKey(pairingSecret: String?) async -> Nip46SetupResult {
        guard ensureTransportKey() else { return .failed(code: "transport_key") }
        let connectParams = Self.jsonArrayString(
            pairingSecret.map { [config.bunkerPubkeyHex, $0] } ?? [config.bunkerPubkeyHex]        )
        switch await runRpc(method: "connect", paramsJson: connectParams, originJson: nil, timeoutMs: setupTimeoutMs) {
        case .ack, .connected:
            break
        case .failed(let code):
            return .failed(code: code)
        case .signed:
            return .failed(code: "unexpected_result")
        }
        switch await runRpc(method: "get_public_key", paramsJson: "[]", originJson: nil, timeoutMs: setupTimeoutMs) {
        case .connected(let pubkey):
            return .connected(accountPubkeyHex: pubkey)
        case .failed(let code):
            return .failed(code: code)
        default:
            return .failed(code: "unexpected_result")
        }
    }

    /// sign_event through the shared validation gate.
    func approveEvent(requestId: String, accountPubkeyHex: String, createdAtSeconds: Int64,
                      kind: Int, tagsJson: String, content: String) async -> Nip46SignOutcome {
        guard accountPubkeyHex == config.accountPubkeyHex else { return .rejected("wrong_pubkey") }
        guard ensureTransportKey() else { return .unavailable("transport_key") }
        guard let originJson = BusinessCoreBridge().signEventRequestJson(
            requestId: requestId, accountPubkeyHex: accountPubkeyHex, createdAtSeconds: createdAtSeconds,
            kind: Int32(kind), tagsJson: tagsJson, content: content
        ) else { return .invalid("request") }
        guard let unsignedWire = BusinessCoreBridge().signEventUnsignedWire(
            requestId: requestId, accountPubkeyHex: accountPubkeyHex, createdAtSeconds: createdAtSeconds,
            kind: Int32(kind), tagsJson: tagsJson, content: content
        ) else { return .invalid("request") }
        let paramsJson = Self.jsonArrayString([unsignedWire])
        switch await runRpc(method: "sign_event", paramsJson: paramsJson, originJson: originJson, timeoutMs: signTimeoutMs) {
        case .signed(let eventJson, let id, let sig):
            return .signed(eventJson: eventJson, id: id, sig: sig)
        case .failed(let code):
            return Self.outcomeFromCode(code)
        case .connected, .ack:
            return .unavailable("unexpected_result")
        }
    }

    /// User-visible cancel of the wait (awaiting sheet's Cancel action).
    func cancelWait() async {
        if let step = Self.stepDictionary(BusinessCoreBridge().nip46OnCancelled(stateJson: stateJson)) {
            applyStep(step)
        }
        await teardown()
        phase = .closed
    }

    /// ID-006d: teardown on switch/sign-out/timeout/app exit.
    func shutdown() async {
        await cancelWait()
    }

    /// ID-006d: account removal wipes the sealed transport key slot.
    func wipeTransportSecret() {
        IdentityKeychain.removeNip46TransportSlot(pubkey: config.accountPubkeyHex)
        transportSecretHex = nil
        transportPubkeyHex = nil
    }

    // MARK: - RPC engine

    private enum RpcResult {
        case signed(eventJson: String, id: String, sig: String)
        case connected(accountPubkeyHex: String)
        case ack
        case failed(code: String)
    }

    private func runRpc(method: String, paramsJson: String, originJson: String?, timeoutMs: Int64) async -> RpcResult {
        guard let secret = transportSecretHex, let clientPubkey = transportPubkeyHex else {
            return .failed(code: "transport_key")
        }
        let bridge = BusinessCoreBridge()
        let requestId = Self.randomId()
        guard let envelope = bridge.nip46EncodeRequest(requestId: requestId, method: method, paramsJson: paramsJson) else {
            return .failed(code: "invalid.envelope")
        }
        guard let nonceHex = Self.randomHex64(),
              let encrypted = bridge.nip46Encrypt(plainEnvelope: envelope, transportSecretHex: secret, peerPubkeyHex: config.bunkerPubkeyHex, nonceHex: nonceHex) else {
            return .failed(code: "invalid.encrypt")
        }
        let subscriptionId = "nip46-" + Self.randomId()
        let nowSeconds = Int64(Date().timeIntervalSince1970)

        phase = .connecting
        guard await ensureSocketsConnected() else {
            await teardown()
            phase = .closed
            return .failed(code: "relay_unreachable")
        }

        stateJson = bridge.nip46StateInit()
        outcome = nil
        guard let step = bridge.nip46Begin(
            stateJson: stateJson, requestId: requestId, method: method, deadlineMs: timeoutMs,
            signerPubkeyHex: config.bunkerPubkeyHex, originJson: originJson ?? "",
            envelopePlainJson: envelope, encryptedContent: encrypted,
            createdAtSeconds: nowSeconds, clientPubkeyHex: clientPubkey, subscriptionId: subscriptionId        ).flatMap(Self.stepDictionary) else {
            await teardown()
            phase = .closed
            return .failed(code: "invalid.begin")
        }
        applyStep(step)

        phase = .awaitingSigner
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1000)
        while Date() < deadline {
            if let outcome {
                await teardown()
                phase = .closed
                return Self.rpcResult(fromOutcome: outcome)
            }
            try? await Task.sleep(nanoseconds: 25_000_000)
        }
        // Deadline passed without a terminal outcome.
        if let expired = Self.stepDictionary(bridge.nip46OnExpired(stateJson: stateJson, nowMs: timeoutMs + 1)) {
            applyStep(expired)
        }
        await teardown()
        phase = .closed
        if let outcome {
            return Self.rpcResult(fromOutcome: outcome)
        }
        return .failed(code: "timeout")
    }

    /// Opens sockets to every bunker relay and starts their receive loops;
    /// connectivity failures surface through the request deadline instead
    /// of a blocking open probe (URLSession has no open callback).
    private func ensureSocketsConnected() async -> Bool {
        for relay in config.relays {
            guard let url = URL(string: relay.rawValue) else { continue }
            let socket = urlSession.webSocketTask(with: url)
            sockets[relay] = socket
            socket.resume()
            startReceiveLoop(relay: relay, socket: socket)
        }
        return !sockets.isEmpty
    }

    private func startReceiveLoop(relay: RelayURL, socket: URLSessionWebSocketTask) {
        receiveTasks[relay]?.cancel()
        receiveTasks[relay] = Task { [weak self] in
            while !Task.isCancelled {
                guard let message = try? await socket.receive(), case .string(let text) = message else { break }
                await self?.handleRelayMessage(text, relay: relay)
            }
        }
    }

    /// One relay message: shared trust gate → decrypt → reducer step.
    private func handleRelayMessage(_ text: String, relay: RelayURL) async {
        guard let secret = transportSecretHex, let clientPubkey = transportPubkeyHex else { return }
        let bridge = BusinessCoreBridge()
        guard let event = bridge.decodeEvent(message: text, relayUrl: relay.rawValue) else { return }
        guard event.kind == 24133 else { return }
        guard event.pubkey == config.bunkerPubkeyHex else { return }
        guard event.tags.first?.first == "p", event.tags.first?.dropFirst().first == clientPubkey else { return }
        guard let plain = bridge.nip46Decrypt(payload: event.content, transportSecretHex: secret, peerPubkeyHex: event.pubkey) else { return }

        // Inbound pairing connect request (nostrconnect flow).
        if let requestStep = Self.stepDictionary(
            bridge.nip46OnSignerConnect(stateJson: stateJson, fromPubkeyHex: event.pubkey, plainRequestEnvelope: plain, clientPubkeyHex: clientPubkey)
        ), Self.hasOutcome(requestStep) {
            applyStep(requestStep)
            return
        }
        // auth_url challenge notice first (stays in-flight), then handling.
        stateJson = bridge.nip46OnAuthUrl(stateJson: stateJson, plainEnvelope: plain)
        if let step = Self.stepDictionary(bridge.nip46OnResponse(stateJson: stateJson, fromPubkeyHex: event.pubkey, plainEnvelope: plain)) {
            applyStep(step)
        }
    }

    // MARK: - Step plumbing

    private func applyStep(_ step: [String: Any]) {
        if let state = step["state"] as? String { stateJson = state }
        if let outcomeDict = step["outcome"] as? [String: Any] { outcome = outcomeDict }
        if let commands = step["commands"] as? [[String: Any]] {
            executeCommands(commands)
        }
    }

    private func executeCommands(_ commands: [[String: Any]]) {
        let bridge = BusinessCoreBridge()
        for command in commands {
            switch command["type"] as? String {
            case "open_lane":
                if let frame = command["frame"] as? String { broadcast(frame) }
            case "close_lane":
                if let frame = command["frame"] as? String { broadcast(frame) }
            case "send_request":
                if let eventJson = command["event"] as? String, let id = command["id"] as? String {
                    lastSentEventJson = eventJson
                    lastSentEventId = id
                    sendSignedEvent(json: eventJson, id: id)
                }
            case "send_response":
                if let envelope = command["envelope"] as? String,
                   let secret = transportSecretHex, let clientPubkey = transportPubkeyHex,
                   let nonceHex = Self.randomHex64(),
                   let encrypted = bridge.nip46Encrypt(plainEnvelope: envelope, transportSecretHex: secret, peerPubkeyHex: config.bunkerPubkeyHex, nonceHex: nonceHex),
                   let event = bridge.nip46BuildRequestEvent(
                       fromPubkeyHex: clientPubkey, toSignerPubkeyHex: config.bunkerPubkeyHex,
                       encryptedContent: encrypted, createdAtSeconds: Int64(Date().timeIntervalSince1970)                   ) {
                    sendSignedEvent(json: event["json"] ?? "", id: event["id"] ?? "")
                }
            case "teardown":
                break
            default:
                break
            }
        }
    }

    private func sendSignedEvent(json: String, id: String) {
        let bridge = BusinessCoreBridge()
        guard let secret = transportSecretHex, let auxHex = Self.randomHex64(),
              let signature = bridge.signDetached(messageHex: id, secretHex: secret, auxHex: auxHex),
              let frame = bridge.nip46EventFrame(unsignedEventJson: json, eventIdHex: id, signatureHex: signature) else { return }
        broadcast(frame)
    }

    private func broadcast(_ frame: String) {
        for socket in sockets.values {
            socket.send(.string(frame)) { _ in }
        }
    }

    private func teardown() async {
        if let closeFrame = closeFrameForCurrentSubscription() {
            broadcast(closeFrame)
        }
        receiveTasks.values.forEach { $0.cancel() }
        receiveTasks.removeAll()
        for socket in sockets.values { socket.cancel(with: .normalClosure, reason: nil) }
        sockets.removeAll()
    }

    private func closeFrameForCurrentSubscription() -> String? {
        // The current subscription id is embedded in the state token; the
        // reducer's cancel path emits the close_lane command instead. This
        // fallback covers deadline teardown.
        guard let data = stateJson.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let sub = dict["sub"] as? String else { return nil }
        return BusinessCoreBridge().nip46CloseFrame(subscriptionId: sub)
    }

    private func currentStepState() -> String? {
        stateJson.isEmpty ? nil : stateJson
    }

    // MARK: - Outcome mapping

    private static func rpcResult(fromOutcome dict: [String: Any]) -> RpcResult {
        switch dict["type"] as? String {
        case "approved":
            let eventJson = (dict["event"] as? String) ?? ""
            let id = (dict["id"] as? String) ?? ""
            let sig = (dict["sig"] as? String) ?? ""
            return .signed(eventJson: eventJson, id: id, sig: sig)
        case "public_key":
            return .connected(accountPubkeyHex: (dict["pubkey"] as? String) ?? "")
        case "connect_ack":
            return .ack
        default:
            let code = (dict["code"] as? String) ?? "unavailable"
            return .failed(code: code == "signed" ? "unavailable" : code)
        }
    }

    private static func outcomeFromCode(_ code: String) -> Nip46SignOutcome {
        switch code {
        case "denied": return .denied
        case "timeout": return .timedOut
        case "cancelled": return .cancelled
        case "unavailable": return .unavailable("signer")
        case "invalid": return .invalid("request")
        default: return .rejected(code)
        }
    }

    private static func hasOutcome(_ step: [String: Any]) -> Bool {
        step["outcome"] != nil
    }

    // MARK: - Helpers

    private static func stepDictionary(_ json: String?) -> [String: Any]? {
        guard let json, let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return dict
    }

    private static func jsonArrayString(_ values: [String]) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: values)) ?? Data("[]".utf8)
        return String(data: data, encoding: .utf8) ?? "[]"
    }

    private static func randomId() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(16).description
    }

    private static func randomHex64() -> String? {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, 32, &bytes) == errSecSuccess else { return nil }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}
