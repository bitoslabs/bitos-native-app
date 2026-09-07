import BusinessCore
import CryptoKit
import Foundation

/// Durable publish-job ledger (prototype `#/queue`): every meme publish
/// attempt persists its inputs + stage so a crash never loses the run.
/// Records are small JSON; the rendered media rides as one file per job
/// (deleted on done/discard). Bounded to [cap] records, oldest pruned.
struct MemePublishJob: Codable, Identifiable, Equatable {
    var v: Int = 1
    var id: Int
    var createdAtMs: Int64
    var updatedAtMs: Int64
    var mode: String          // image | gif | video
    var caption: String
    var altText: String
    var contentWarningReason: String?
    var extraTagsJson: String
    var remixEventId: String
    var remixAuthor: String
    var mime: String
    var width: Int
    var height: Int
    var durationMs: Int64
    var coverThumbUrl: String?
    /// Pipeline stage index 0…7 (render…confirm) at last touch.
    var stage: Int
    var mediaUrl: String?
    var mediaSha256: String?
    var eventId: String?
    /// active | failed | done | discarded
    var status: String
    var lastError: String?
    /// Job media file name inside the store directory.
    var mediaFile: String

    var isTerminal: Bool { status == "done" || status == "discarded" }
    /// Retry is safe only before anything signed: once an event id exists
    /// the note may already be live — verify instead of re-sending.
    var retryAllowed: Bool { eventId == nil && status != "done" }
}

struct MemePublishJobStore {
    let directory: URL
    private(set) var jobs: [MemePublishJob] = []
    private let cap = 20

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("meme-publish-jobs", isDirectory: true)
        self.directory = base
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        load()
    }

    private func ledgerURL() -> URL { directory.appendingPathComponent("jobs.json") }
    private func mediaURL(_ job: MemePublishJob) -> URL { directory.appendingPathComponent(job.mediaFile) }

    private mutating func load() {
        guard let data = try? Data(contentsOf: ledgerURL()),
              let decoded = try? JSONDecoder().decode([MemePublishJob].self, from: data) else { return }
        jobs = decoded
    }

    private func persist(_ jobs: [MemePublishJob]) {
        if let data = try? JSONEncoder().encode(jobs) {
            try? data.write(to: ledgerURL(), options: .atomic)
        }
    }

    /// Creates a job + stores the rendered bytes. Returns the job id.
    @discardableResult
    mutating func begin(
        mode: String, caption: String, altText: String, contentWarningReason: String?,
        extraTagsJson: String, remixEventId: String, remixAuthor: String,
        bytes: Data, mime: String, width: Int, height: Int, durationMs: Int64,
        coverThumbUrl: String?, nowMs: Int64
    ) -> Int {
        let id = Int((nowMs / 1000) % 10_000) // stable, human-scale chip number
        let file = "job-\(id)-\(nowMs).bin"
        try? bytes.write(to: directory.appendingPathComponent(file), options: .atomic)
        let job = MemePublishJob(
            id: id, createdAtMs: nowMs, updatedAtMs: nowMs,
            mode: mode, caption: caption, altText: altText,
            contentWarningReason: contentWarningReason, extraTagsJson: extraTagsJson,
            remixEventId: remixEventId, remixAuthor: remixAuthor,
            mime: mime, width: width, height: height, durationMs: durationMs,
            coverThumbUrl: coverThumbUrl, stage: 0,
            status: "active", mediaFile: file
        )
        jobs.removeAll { $0.id == id }
        jobs.insert(job, at: 0)
        jobs = Array(jobs.prefix(cap))
        persist(jobs)
        return id
    }

    /// Stage checkpoint update (render…confirm index); optional facts.
    /// `extraTagsJson` re-persists the merged extras once the remix lineage
    /// has joined the draft tags, so a queue retry republishes the same set.
    mutating func update(_ id: Int, stage: Int? = nil, mediaUrl: String? = nil,
                         sha256: String? = nil, eventId: String? = nil,
                         status: String? = nil, error: String? = nil,
                         extraTagsJson: String? = nil, nowMs: Int64) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        if let stage { jobs[index].stage = stage }
        if let mediaUrl { jobs[index].mediaUrl = mediaUrl }
        if let extraTagsJson { jobs[index].extraTagsJson = extraTagsJson }
        if let sha256 { jobs[index].mediaSha256 = sha256 }
        if let eventId { jobs[index].eventId = eventId }
        if let status { jobs[index].status = status }
        if let error { jobs[index].lastError = error } else if status == "active" { jobs[index].lastError = nil }
        jobs[index].updatedAtMs = nowMs
        persist(jobs)
    }

    /// Done jobs drop their media (the note carries it now).
    mutating func finish(_ id: Int, nowMs: Int64) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        jobs[index].status = "done"
        jobs[index].stage = 8
        jobs[index].updatedAtMs = nowMs
        try? FileManager.default.removeItem(at: mediaURL(jobs[index]))
        persist(jobs)
    }

    mutating func discard(_ id: Int) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        try? FileManager.default.removeItem(at: mediaURL(jobs[index]))
        jobs.remove(at: index)
        persist(jobs)
    }

    func job(_ id: Int) -> MemePublishJob? { jobs.first { $0.id == id } }

    func loadBytes(_ job: MemePublishJob) -> Data? { try? Data(contentsOf: mediaURL(job)) }

    /// Verify integrity: the stored bytes still hash to the recorded digest.
    func integrityOK(_ job: MemePublishJob) -> Bool? {
        guard let expected = job.mediaSha256,
              let bytes = loadBytes(job) else { return nil }
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        return digest == expected
    }

    /// Non-terminal jobs (active or failed) — the recovery list.
    var recoverable: [MemePublishJob] { jobs.filter { !$0.isTerminal } }
}
