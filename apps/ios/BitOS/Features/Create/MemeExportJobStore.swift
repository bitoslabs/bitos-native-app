import Foundation

/// Durable export jobs (MUX-05): the rendered artifact is persisted BEFORE
/// the destination save, so an interrupted save retries from the artifact
/// without re-rendering. A job stuck in `saving` on relaunch becomes
/// needsReview — Photos saves are add-only and cannot be queried back, so
/// the honest reconciliation is asking the user to check.
struct MemeExportJob: Codable, Identifiable, Equatable {
    var v: Int = 1
    var id: Int
    var createdAtMs: Int64
    var updatedAtMs: Int64
    var format: String      // "png" | "gif" | "mp4"
    /// rendering | rendered | saving | needsReview | done
    var phase: String
    var artifactFile: String
    var artifactBytes: Int
    var lastError: String?
}

struct MemeExportJobStore {
    let directory: URL
    private(set) var jobs: [MemeExportJob] = []
    private let cap = 10

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("meme-export-jobs", isDirectory: true)
        self.directory = base
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: base.appendingPathComponent("jobs.json")),
           let decoded = try? JSONDecoder().decode([MemeExportJob].self, from: data) {
            jobs = decoded.map { job in
                // A crash mid-save can't be reconciled against Photos
                // (add-only) — surface it for review instead of assuming.
                job.phase == "saving" ? Self.phaseNeedsReview(job) : job
            }
        }
    }

    /// A crash mid-save can't be reconciled against Photos (add-only) —
    /// surface it for review instead of assuming success or failure.
    private static func phaseNeedsReview(_ job: MemeExportJob) -> MemeExportJob {
        var reviewed = job
        reviewed.phase = "needsReview"
        return reviewed
    }

    private func persist(_ list: [MemeExportJob]) {
        if let data = try? JSONEncoder().encode(list) {
            try? data.write(to: directory.appendingPathComponent("jobs.json"), options: .atomic)
        }
    }

    @discardableResult
    mutating func begin(format: String, nowMs: Int64) -> Int {
        let id = Int((nowMs / 1000) % 10_000)
        let job = MemeExportJob(
            id: id, createdAtMs: nowMs, updatedAtMs: nowMs,
            format: format, phase: "rendering",
            artifactFile: "export-\(id)-\(nowMs).bin", artifactBytes: 0
        )
        jobs.removeAll { $0.id == id }
        jobs.insert(job, at: 0)
        jobs = Array(jobs.prefix(cap))
        persist(jobs)
        return id
    }

    /// Artifact persisted BEFORE the destination save (persist-then-effect).
    mutating func artifactReady(_ id: Int, bytes: Data, nowMs: Int64) -> Bool {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return false }
        let ok = (try? bytes.write(to: directory.appendingPathComponent(jobs[index].artifactFile), options: .atomic)) != nil
        if ok {
            jobs[index].phase = "rendered"
            jobs[index].artifactBytes = bytes.count
        }
        jobs[index].updatedAtMs = nowMs
        persist(jobs)
        return ok
    }

    mutating func update(_ id: Int, phase: String? = nil, error: String? = nil, clearError: Bool = false, nowMs: Int64) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        if let phase { jobs[index].phase = phase }
        if let error { jobs[index].lastError = error }
        if clearError { jobs[index].lastError = nil }
        jobs[index].updatedAtMs = nowMs
        persist(jobs)
    }

    /// Done: the destination holds the output — the artifact goes away.
    mutating func finish(_ id: Int) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(jobs[index].artifactFile))
        jobs.remove(at: index)
        persist(jobs)
    }

    mutating func discard(_ id: Int) {
        guard let job = jobs.first(where: { $0.id == id }) else { return }
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(job.artifactFile))
        jobs.removeAll { $0.id == id }
        persist(jobs)
    }

    func job(_ id: Int) -> MemeExportJob? { jobs.first { $0.id == id } }
    func loadArtifact(_ job: MemeExportJob) -> Data? {
        try? Data(contentsOf: directory.appendingPathComponent(job.artifactFile))
    }
    /// Jobs whose rendered artifact is intact (retry reuses it, no rerender).
    var recoverable: [MemeExportJob] { jobs.filter { $0.phase == "rendered" || $0.phase == "needsReview" || $0.phase == "failed" } }
}
