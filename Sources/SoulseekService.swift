import Foundation

struct SoulseekFileResult: Codable, Identifiable, Hashable {
    let id: String
    let username: String
    let filename: String
    let basename: String
    let size: Int64
    let qualityTag: String
    let uploadSpeed: Double
    let hasFreeUploadSlot: Bool
    let queueLength: Int
}

struct SoulseekFolderPack: Codable, Identifiable, Hashable {
    let id: String
    let username: String
    let parentFolder: String
    let folderDisplayName: String
    let uploadSpeed: Double
    let hasFreeUploadSlot: Bool
    let queueLength: Int
    let totalSize: Int64
    let fileCount: Int
    let qualityTag: String
    let summary: String
    let files: [SoulseekFileResult]
}

private struct SoulseekSearchStartResponse: Decodable {
    let searchId: String
}

private struct SoulseekSearchResultsResponse: Decodable {
    let searchId: String
    let isComplete: Bool
    let files: [SoulseekFileResult]
    let folders: [SoulseekFolderPack]
}

struct SoulseekTransferItem: Codable, Identifiable, Hashable {
    let id: String
    let username: String
    let filename: String
    let title: String
    let size: Int64
    let percent: Double
    let speedBytesPerSec: Double
    let stage: String
    let statusText: String
    let driveFileId: String
    let driveFolderPath: String
    let error: String
}

private struct SoulseekTransfersResponse: Decodable {
    let transfers: [SoulseekTransferItem]
    let completedCount: Int
}

private struct SoulseekEnqueueFile: Encodable {
    let filename: String
    let size: Int64
}

private struct SoulseekDownloadRequest: Encodable {
    let username: String
    let files: [SoulseekEnqueueFile]
    let accessToken: String
    let refreshToken: String
    let targetFolderId: String
}

private struct SoulseekDownloadResponse: Decodable {
    let status: String
    let queued: Int
}

private struct SoulseekHealthResponse: Decodable {
    let ok: Bool
    let service: String?
}

enum SoulseekFormatting {
    static func formatSize(_ bytes: Int64) -> String {
        let gb = Double(bytes) / 1_073_741_824.0
        if gb >= 1.0 {
            return String(format: "%.1f GB", gb)
        }
        let mb = max(1.0, Double(bytes) / 1_048_576.0)
        return String(format: "%.0f MB", mb)
    }

    static func formatSpeed(_ bytesPerSec: Double) -> String {
        if bytesPerSec <= 0 {
            return "0 KB/s"
        }
        let mbs = bytesPerSec / 1_048_576.0
        if mbs >= 0.1 {
            return String(format: "%.1f MB/s", mbs)
        }
        let kbs = max(1.0, bytesPerSec / 1024.0)
        return String(format: "%.0f KB/s", kbs)
    }
}

@MainActor
final class SoulseekService: ObservableObject {
    static let shared = SoulseekService()
    nonisolated static let bridgePort = 5031

    @Published private(set) var bridgeReachable = false
    @Published private(set) var isScanning = false
    @Published private(set) var statusMessage = "Not connected"
    @Published private(set) var isSearching = false
    @Published private(set) var searchError: String?
    @Published private(set) var folders: [SoulseekFolderPack] = []
    @Published private(set) var files: [SoulseekFileResult] = []
    @Published private(set) var transfers: [SoulseekTransferItem] = []
    @Published private(set) var completedCount = 0
    @Published private(set) var activeEnqueueId: String?
    @Published private(set) var actionMessage: String?

    var onUploadCompleted: (@MainActor () async -> Void)?

    private let apiSession: URLSession
    private let scanSession: URLSession

    private var activeSearchTask: Task<Void, Never>?
    private var pollingTask: Task<Void, Never>?
    private var searchGeneration = 0
    private var hasSeededTransfers = false
    private var knownCompletedIds: Set<String> = []
    private var lastCompletedCount = 0

    private init() {
        let apiConfig = URLSessionConfiguration.ephemeral
        apiConfig.timeoutIntervalForRequest = 15
        apiConfig.timeoutIntervalForResource = 30
        self.apiSession = URLSession(configuration: apiConfig)

        let scanConfig = URLSessionConfiguration.ephemeral
        scanConfig.timeoutIntervalForRequest = 0.55
        scanConfig.timeoutIntervalForResource = 0.85
        self.scanSession = URLSession(configuration: scanConfig)
    }

    func setManualBridgeHost(_ rawHost: String) async {
        let cleaned = SyncManager.cleanHost(rawHost)
        SyncManager.shared.setSoulseekBridgeHost(cleaned)
        if cleaned.isEmpty {
            bridgeReachable = false
            statusMessage = "Not configured"
            return
        }
        await checkBridgeHealth()
        if bridgeReachable {
            await refreshTransfers()
        }
    }

    func ensureConnectedAndRefresh() async {
        let host = SyncManager.shared.soulseekBridgeHost
        if host.isEmpty {
            await scanWiFiForBridge()
        } else {
            await checkBridgeHealth()
        }
        if bridgeReachable {
            await refreshTransfers()
        }
    }

    @discardableResult
    func checkBridgeHealth() async -> Bool {
        let host = SyncManager.shared.soulseekBridgeHost
        guard !host.isEmpty else {
            bridgeReachable = false
            statusMessage = "Not configured"
            return false
        }
        let ok = await Self.probeBridgeHealth(host: host, session: scanSession)
        bridgeReachable = ok
        statusMessage = ok ? "Connected to \(host):\(Self.bridgePort)" : "Bridge offline at \(host):\(Self.bridgePort)"
        return ok
    }

    func scanWiFiForBridge() async {
        guard !isScanning else { return }
        isScanning = true
        statusMessage = "Scanning local Wi-Fi (:\(Self.bridgePort))…"
        defer { isScanning = false }

        guard let localIP = SyncManager.detectLocalIPv4() else {
            bridgeReachable = false
            statusMessage = "Could not detect Wi-Fi IP"
            return
        }
        let parts = localIP.split(separator: ".")
        guard parts.count == 4 else {
            bridgeReachable = false
            statusMessage = "Unsupported local IP: \(localIP)"
            return
        }
        let prefix = "\(parts[0]).\(parts[1]).\(parts[2])"
        let configured = SyncManager.shared.soulseekBridgeHost

        var candidates: [String] = []
        if !configured.isEmpty {
            candidates.append(configured)
        }
        for lastOctet in 1...254 {
            let ip = "\(prefix).\(lastOctet)"
            if ip != localIP && ip != configured {
                candidates.append(ip)
            }
        }

        let session = scanSession
        let foundHost: String? = await withTaskGroup(of: String?.self) { group in
            var index = 0
            let maxConcurrent = 48
            while index < min(maxConcurrent, candidates.count) {
                let host = candidates[index]
                index += 1
                group.addTask {
                    await Self.probeBridgeHealth(host: host, session: session) ? host : nil
                }
            }
            while let result = await group.next() {
                if let hit = result {
                    group.cancelAll()
                    return hit
                }
                if index < candidates.count {
                    let host = candidates[index]
                    index += 1
                    group.addTask {
                        await Self.probeBridgeHealth(host: host, session: session) ? host : nil
                    }
                }
            }
            return nil
        }

        if let foundHost {
            SyncManager.shared.setSoulseekBridgeHost(foundHost)
            bridgeReachable = true
            statusMessage = "Connected to \(foundHost):\(Self.bridgePort)"
            await refreshTransfers()
        } else {
            bridgeReachable = false
            statusMessage = "No Soulseek bridge found on Wi-Fi"
        }
    }

    func startSearch(query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        activeSearchTask?.cancel()
        searchGeneration += 1
        let generation = searchGeneration

        isSearching = true
        searchError = nil
        actionMessage = nil
        folders = []
        files = []

        activeSearchTask = Task { [weak self] in
            guard let self else { return }
            await self.runSearch(query: trimmed, generation: generation)
        }
    }

    private func runSearch(query: String, generation: Int) async {
        defer {
            if searchGeneration == generation {
                isSearching = false
            }
        }

        guard let baseURL = currentBridgeBaseURL() else {
            if searchGeneration == generation {
                searchError = "Set your PC Bridge IP or tap Scan Wi-Fi first."
            }
            return
        }

        do {
            var startReq = URLRequest(url: baseURL.appendingPathComponent("api/search"))
            startReq.httpMethod = "POST"
            startReq.setValue("application/json", forHTTPHeaderField: "Content-Type")
            startReq.httpBody = try JSONSerialization.data(withJSONObject: ["query": query])

            let (startData, startResp) = try await apiSession.data(for: startReq)
            try Self.ensureSuccess(startResp, data: startData)
            let startDecoded = try JSONDecoder().decode(SoulseekSearchStartResponse.self, from: startData)
            bridgeReachable = true

            let encodedId = startDecoded.searchId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? startDecoded.searchId
            guard let resultsURL = URL(string: "\(baseURL.absoluteString)/api/search/\(encodedId)") else {
                throw URLError(.badURL)
            }

            for attempt in 0..<18 {
                if Task.isCancelled || searchGeneration != generation { return }
                let (data, resp) = try await apiSession.data(from: resultsURL)
                try Self.ensureSuccess(resp, data: data)
                let decoded = try JSONDecoder().decode(SoulseekSearchResultsResponse.self, from: data)
                if searchGeneration != generation { return }

                folders = decoded.folders
                files = decoded.files
                if decoded.isComplete || attempt == 17 {
                    return
                }
                try await Task.sleep(nanoseconds: 900_000_000)
            }
        } catch is CancellationError {
            return
        } catch {
            if searchGeneration == generation {
                searchError = error.localizedDescription
            }
        }
    }

    func downloadFolder(_ pack: SoulseekFolderPack, authManager: AuthManager, targetFolderId: String) async {
        await enqueue(
            enqueueId: pack.id,
            username: pack.username,
            files: pack.files,
            label: "\(pack.folderDisplayName) (\(pack.fileCount) files)",
            authManager: authManager,
            targetFolderId: targetFolderId
        )
    }

    func downloadFile(_ file: SoulseekFileResult, authManager: AuthManager, targetFolderId: String) async {
        await enqueue(
            enqueueId: file.id,
            username: file.username,
            files: [file],
            label: file.basename,
            authManager: authManager,
            targetFolderId: targetFolderId
        )
    }

    private func enqueue(
        enqueueId: String,
        username: String,
        files: [SoulseekFileResult],
        label: String,
        authManager: AuthManager,
        targetFolderId: String
    ) async {
        guard activeEnqueueId == nil else { return }
        guard let baseURL = currentBridgeBaseURL() else {
            actionMessage = "Bridge not configured."
            return
        }

        activeEnqueueId = enqueueId
        actionMessage = nil
        defer { activeEnqueueId = nil }

        do {
            let accessToken = try await authManager.validAccessToken()
            let refreshToken = authManager.currentRefreshToken ?? ""
            let resolvedFolder = targetFolderId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? DriveService.defaultFolderID
                : targetFolderId.trimmingCharacters(in: .whitespacesAndNewlines)

            let body = SoulseekDownloadRequest(
                username: username,
                files: files.map { SoulseekEnqueueFile(filename: $0.filename, size: $0.size) },
                accessToken: accessToken,
                refreshToken: refreshToken,
                targetFolderId: resolvedFolder
            )

            var req = URLRequest(url: baseURL.appendingPathComponent("api/download"))
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONEncoder().encode(body)

            let (data, resp) = try await apiSession.data(for: req)
            try Self.ensureSuccess(resp, data: data)
            let decoded = try JSONDecoder().decode(SoulseekDownloadResponse.self, from: data)
            actionMessage = "Queued \(decoded.queued) file(s) from \(label)"
            await refreshTransfers()
        } catch {
            actionMessage = "Queue failed: \(error.localizedDescription)"
        }
    }

    func startPollingTransfers() {
        guard pollingTask == nil else { return }
        pollingTask = Task { [weak self] in
            while !Task.isCancelled {
                if let self, !SyncManager.shared.soulseekBridgeHost.isEmpty {
                    await self.refreshTransfers()
                }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    func stopPollingTransfers() {
        pollingTask?.cancel()
        pollingTask = nil
    }

    func refreshTransfers() async {
        guard let baseURL = currentBridgeBaseURL() else { return }
        do {
            let (data, resp) = try await apiSession.data(from: baseURL.appendingPathComponent("api/transfers"))
            try Self.ensureSuccess(resp, data: data)
            let decoded = try JSONDecoder().decode(SoulseekTransfersResponse.self, from: data)
            bridgeReachable = true
            transfers = decoded.transfers
            completedCount = decoded.completedCount

            let currentCompletedIds = Set(
                decoded.transfers.filter { $0.stage == "completed" }.map(\.id)
            )
            let newlyCompleted = hasSeededTransfers && (
                !currentCompletedIds.subtracting(knownCompletedIds).isEmpty ||
                decoded.completedCount > lastCompletedCount
            )
            knownCompletedIds.formUnion(currentCompletedIds)
            lastCompletedCount = decoded.completedCount
            hasSeededTransfers = true

            if newlyCompleted {
                await onUploadCompleted?()
            }
        } catch {
            bridgeReachable = false
        }
    }

    private func currentBridgeBaseURL() -> URL? {
        let host = SyncManager.shared.soulseekBridgeHost
        guard !host.isEmpty else { return nil }
        return URL(string: "http://\(host):\(Self.bridgePort)")
    }

    nonisolated private static func probeBridgeHealth(host: String, session: URLSession) async -> Bool {
        guard let url = URL(string: "http://\(host):\(bridgePort)/api/health") else {
            return false
        }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        do {
            let (data, resp) = try await session.data(for: req)
            guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
                return false
            }
            let health = try JSONDecoder().decode(SoulseekHealthResponse.self, from: data)
            return health.ok && health.service == "drivestream-soulseek-bridge"
        } catch {
            return false
        }
    }

    private static func ensureSuccess(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        guard (200..<300).contains(http.statusCode) else {
            if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let msg = obj["error"] as? String {
                throw NSError(domain: "SoulseekService", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: msg])
            }
            throw NSError(
                domain: "SoulseekService",
                code: http.statusCode,
                userInfo: [NSLocalizedDescriptionKey: "Bridge returned HTTP \(http.statusCode)"]
            )
        }
    }
}
