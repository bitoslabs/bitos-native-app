import BusinessCore
import CryptoKit
import Foundation

/// Blossom (BUD-02) uploader: hash → challenge → signed kind-24242 auth →
/// PUT bytes → verify the returned hash matches the local one. Hash mismatch
/// is blocking and security-visible (PUB-002/006).
struct BlossomUploader: @unchecked Sendable { // bridge is stateless; see FrameworkBusinessCoreClient

    struct UploadFailure: Error { let message: String }

    private let bridge: BusinessCoreBridge

    init(bridge: BusinessCoreBridge = BusinessCoreBridge()) {
        self.bridge = bridge
    }

    private func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// Uploads the bytes and returns the verified (url, hash, mime, size).
    func upload(
        bytes: Data,
        mimeType: String,
        identity: IdentityStore,
        serverUrl: String,
        nowSeconds: Int64 = Int64(Date.now.timeIntervalSince1970)
    ) async throws -> (url: String, hash: String, mime: String, size: Int) {
        guard !bytes.isEmpty, bytes.count <= 64 * 1024 * 1024 else { throw UploadFailure(message: "file out of bounds") }
        let localHash = sha256Hex(bytes)
        let account = await identity.account
        guard let account else { throw UploadFailure(message: "signing refused") }

        var request = URLRequest(url: URL(string: serverUrl)!)
        request.httpMethod = "PUT"
        request.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        request.httpBody = bytes

        // 1. Unauthenticated PUT -> expect a 401 challenge.
        let (emptyData, firstResponse) = try await URLSession.shared.data(for: request)
        guard let http = firstResponse as? HTTPURLResponse else { throw UploadFailure(message: "no response") }
        if http.statusCode == 200 {
            return try verified(from: String(data: emptyData, encoding: .utf8), localHash: localHash, mime: mimeType, size: bytes.count)
        }
        guard http.statusCode == 401 else { throw UploadFailure(message: "server rejected upload: \(http.statusCode)") }
        guard let challenge = http.value(forHTTPHeaderField: "WWW-Authenticate") else {
            throw UploadFailure(message: "server sent no auth challenge")
        }

        // 2. Compose + sign the kind-24242 auth event with the challenge's expiration.
        let expiration = bridge.blossomChallengeExpiration(headerValue: challenge)?.int64Value ?? (nowSeconds + 600)
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

        // 3. Authenticated PUT.
        var authed = URLRequest(url: URL(string: serverUrl)!)
        authed.httpMethod = "PUT"
        authed.setValue(authHeader, forHTTPHeaderField: "Authorization")
        authed.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        authed.httpBody = bytes
        let (data, response) = try await URLSession.shared.data(for: authed)
        guard let http2 = response as? HTTPURLResponse, http2.statusCode == 200 else {
            throw UploadFailure(message: "upload failed")
        }
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
