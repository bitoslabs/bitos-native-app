import Foundation

/// URL request execution with REAL upload byte progress: the session
/// task delegate's `didSendBodyData` (socket bytes, never estimated).
/// Falls back to the shared session when no progress is requested so
/// existing call sites keep their exact behavior.
enum ProgressUrlUpload {
    static func data(for request: URLRequest, onProgress: (@Sendable (Int, Int) -> Void)? = nil) async throws -> (Data, URLResponse) {
        guard let onProgress else {
            return try await URLSession.shared.data(for: request)
        }
        final class ProgressDelegate: NSObject, URLSessionDataDelegate {
            let report: @Sendable (Int, Int) -> Void
            init(report: @escaping @Sendable (Int, Int) -> Void) { self.report = report }

            func urlSession(
                _ session: URLSession,
                task: URLSessionTask,
                didSendBodyData bytesSent: Int64,
                totalBytesSent: Int64,
                totalBytesExpectedToSend: Int64
            ) {
                report(Int(totalBytesSent), Int(max(0, totalBytesExpectedToSend)))
            }
        }
        let delegate = ProgressDelegate(report: onProgress)
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        return try await session.data(for: request)
    }
}
