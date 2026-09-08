import BusinessCore
import CryptoKit
import Foundation

/// Blossom (BUD-02/11) uploader: hash → signed kind-24242 auth → Base64url
/// `Authorization: Nostr` header → `PUT {server}/upload` → verify the
/// returned hash matches the local one. Hash mismatch is blocking and
/// security-visible (PUB-002/006).
struct BlossomUploader: @unchecked Sendable { // bridge is stateless; see FrameworkBusinessCoreClient

    struct UploadFailure: Error { let message: String }

    /** Real pipeline checkpoints (drives the publish machine stepper). */
    enum UploadStage { case hashing, uploading, verifying }

    private let bridge: BusinessCoreBridge

    init(bridge: BusinessCoreBridge = BusinessCoreBridge()) {
        self.bridge = bridge
    }

    private func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// Uploads the bytes and returns the verified (url, hash, mime, size).
    /// `onStage` receives the REAL checkpoints (hash → upload → verify);
    /// `onProgress` reports REAL bytes sent during the PUT (socket truth
    /// from the task delegate, never estimated).
    func upload(
        bytes: Data,
        mimeType: String,
        identity: IdentityStore,
        serverUrl: String,
        nowSeconds: Int64 = Int64(Date.now.timeIntervalSince1970),
        onStage: ((UploadStage) -> Void)? = nil,
        onProgress: (@Sendable (Int, Int) -> Void)? = nil
    ) async throws -> (url: String, hash: String, mime: String, size: Int) {
        guard !bytes.isEmpty, bytes.count <= 64 * 1024 * 1024 else { throw UploadFailure(message: "file out of bounds") }
        onStage?(.hashing)
        let localHash = sha256Hex(bytes)
        let account = await identity.account
        guard let account else { throw UploadFailure(message: "signing refused") }
        // BUD-02: uploads live at {server}/upload; the auth token expires in
        // 10 minutes (signed upfront — production servers reject the legacy
        // unauthenticated probe with 400, never the 401 challenge).
        let endpoint = bridge.blossomUploadUrl(serverUrl: serverUrl)
        let expiration = nowSeconds + 600

        // 1. Compose + sign the kind-24242 auth event.
        guard let authId = bridge.composeUploadAuthEventId(
            authorPubkey: account.pubkeyHex,
            serverUrl: serverUrl,
            fileHashHex: localHash,
            sizeBytes: Int64(bytes.count),
            expirationSeconds: expiration,
            nowSeconds: nowSeconds
        ) else { throw UploadFailure(message: "auth event rejected") }
        guard let signature = await identity.signLocally(authId) else { throw UploadFailure(message: "signing refused") }
        guard let authHeader = bridge.uploadAuthHeader(
            authorPubkey: account.pubkeyHex,
            serverUrl: serverUrl,
            fileHashHex: localHash,
            sizeBytes: Int64(bytes.count),
            expirationSeconds: expiration,
            createdAtSeconds: nowSeconds,
            signatureHex: signature
        ) else { throw UploadFailure(message: "auth header rejected") }

        // 2. Authenticated PUT /upload (BUD-02: 201 new, 200 already stored).
        onStage?(.uploading)
        var authed = URLRequest(url: URL(string: endpoint)!)
        authed.httpMethod = "PUT"
        authed.setValue(authHeader, forHTTPHeaderField: "Authorization")
        authed.setValue(localHash, forHTTPHeaderField: "X-SHA-256")
        authed.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        authed.httpBody = bytes
        let (data, response) = try await ProgressUrlUpload.data(for: authed, onProgress: onProgress)
        guard let http2 = response as? HTTPURLResponse, http2.statusCode == 200 || http2.statusCode == 201 else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            let reason = String(data: data, encoding: .utf8).map { String($0.prefix(160)) } ?? ""
            throw UploadFailure(message: "upload failed: \(code)\(reason.isEmpty ? "" : " \(reason)")")
        }
        onStage?(.verifying)
        return try verified(from: String(data: data, encoding: .utf8), localHash: localHash, mime: mimeType, size: bytes.count)
    }

    private func verified(from body: String?, localHash: String, mime: String, size: Int) throws -> (url: String, hash: String, mime: String, size: Int) {
        guard let body else { throw UploadFailure(message: "empty server response") }
        let urlPattern = try! NSRegularExpression(pattern: #""url"\s*:\s*"([^"]{1,2048})""#)
        let hashPattern = try! NSRegularExpression(pattern: #""(?:sha256|x)"\s*:\s*"([0-9a-fA-F]{64})""#)
        let range = NSRange(body.startIndex..<body.endIndex, in: body)
        guard let urlMatch = urlPattern.firstMatch(in: body, range: range),
              let urlRange = Range(urlMatch.range(at: 1), in: body) else {
            throw UploadFailure(message: "no url in response")
        }
        guard let hashMatch = hashPattern.firstMatch(in: body, range: range),
              let hashRange = Range(hashMatch.range(at: 1), in: body) else {
            throw UploadFailure(message: "no hash in response")
        }
        let url = String(body[urlRange])
        let serverHash = String(body[hashRange])
        guard serverHash.lowercased() == localHash else {
            throw UploadFailure(message: "hash mismatch: server returned \(serverHash), local \(localHash)")
        }
        return (url, localHash, mime, size)
    }
}
