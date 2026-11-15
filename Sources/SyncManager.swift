import Combine
import Darwin
import Foundation

struct DriveStreamSyncState: Codable, Equatable {
    struct SyncedSavedLink: Codable, Equatable {
        let id: String
        let title: String
        let urlString: String
        let dateAdded: Double
    }

    struct WatchProgressEntry: Codable, Equatable {
        let positionSeconds: Double
        let durationSeconds: Double
        let updatedAt: Double
    }

    var version: Int = 1
    var savedLinks: [SyncedSavedLink] = []
    var deletedLinkIds: [String: Double] = [:]
    var watchProgress: [String: WatchProgressEntry] = [:]
    var customCovers: [String: CustomCoverMatch] = [:]

    enum CodingKeys: String, CodingKey {
        case version
        case savedLinks
        case deletedLinkIds
        case watchProgress
        case customCovers
    }

    init(
        version: Int = 1,
        savedLinks: [SyncedSavedLink] = [],
        deletedLinkIds: [String: Double] = [:],
        watchProgress: [String: WatchProgressEntry] = [:],
        customCovers: [String: CustomCoverMatch] = [:]
    ) {
        self.version = version
        self.savedLinks = savedLinks
        self.deletedLinkIds = deletedLinkIds
        self.watchProgress = watchProgress
        self.customCovers = customCovers
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        version = try container.decodeIfPresent(Int.self, forKey: .version) ?? 1
        savedLinks = try container.decodeIfPresent([SyncedSavedLink].self, forKey: .savedLinks) ?? []
        deletedLinkIds = try container.decodeIfPresent([String: Double].self, forKey: .deletedLinkIds) ?? [:]
        watchProgress = try container.decodeIfPresent([String: WatchProgressEntry].self, forKey: .watchProgress) ?? [:]
        customCovers = try container.decodeIfPresent([String: CustomCoverMatch].self, forKey: .customCovers) ?? [:]
    }
}

@MainActor
final class SyncManager: ObservableObject {
    static let shared = SyncManager()

    nonisolated static let peerPort = 8766
    static let cloudFileName = "drivestream_sync.json"

    private enum Keys {
        static let peerHost = "peer_sync_host"
    }

    @Published var peerHost: String {
        didSet {
            let trimmed = Self.cleanHost(peerHost)
            if trimmed != peerHost {
                peerHost = trimmed
                return
            }
            UserDefaults.standard.set(trimmed, forKey: Keys.peerHost)
        }
    }

    @Published private(set) var isSyncing = false
    @Published private(set) var isScanning = false
    @Published private(set) var statusMessage = "Idle"
    @Published private(set) var lastSyncDate: Date?
    @Published private(set) var coversRevision = 0

    private let cloudSession: URLSession
    private let peerSession: URLSession

    private var cachedAppDataFileId: String?
    private var pendingDebounceTask: Task<Void, Never>?
    private var activeSyncTask: Task<Void, Never>?
    private var needsAnotherSync = false
    private var isApplyingRemote = false
    private var didAttemptAutoDiscoveryThisLaunch = false

    private init() {
        let savedHost = UserDefaults.standard.string(forKey: Keys.peerHost) ?? ""
        self.peerHost = Self.cleanHost(savedHost)

        let cloudConfig = URLSessionConfiguration.ephemeral
        cloudConfig.timeoutIntervalForRequest = 15
        cloudConfig.timeoutIntervalForResource = 25
        self.cloudSession = URLSession(configuration: cloudConfig)

        let peerConfig = URLSessionConfiguration.ephemeral
        peerConfig.timeoutIntervalForRequest = 3.5
        peerConfig.timeoutIntervalForResource = 5.0
        self.peerSession = URLSession(configuration: peerConfig)
    }

    func resetCloudStateOnSignOut() {
        cachedAppDataFileId = nil
    }

    func scheduleSyncAfterChange() {
        guard !isApplyingRemote else { return }
        pendingDebounceTask?.cancel()
        pendingDebounceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            await self?.syncAll(discoverIfMissing: false)
        }
    }

    func syncOnLaunch() async {
        await syncAll(discoverIfMissing: peerHost.isEmpty)
    }

    func syncAll(discoverIfMissing: Bool = false) async {
        if let active = activeSyncTask {
            needsAnotherSync = true
            await active.value
            return
        }

        let task = Task { [weak self] in
            guard let self else { return }
            repeat {
                self.needsAnotherSync = false
                await self.performSyncPass(discoverIfMissing: discoverIfMissing)
            } while self.needsAnotherSync
        }
        activeSyncTask = task
        await task.value
        activeSyncTask = nil
    }

    func scanLocalNetworkForFireTV() async -> String? {
        guard !isScanning else { return nil }
        isScanning = true
        statusMessage = "Scanning local Wi-Fi for Fire TV…"
        defer { isScanning = false }

        let foundHost = await discoverFirstPeerHost()
        if let foundHost {
            peerHost = foundHost
            statusMessage = "Found Fire TV at \(foundHost)"
            await syncAll(discoverIfMissing: false)
            return foundHost
        } else {
            statusMessage = "No Fire TV found on Wi-Fi (port 8766)"
            return nil
        }
    }

    private func performSyncPass(discoverIfMissing: Bool) async {
        isSyncing = true
        defer { isSyncing = false }

        var notes: [String] = []

        // Channel A: Instant Home Wi-Fi Peer Sync
        if peerHost.isEmpty && discoverIfMissing && !didAttemptAutoDiscoveryThisLaunch {
            didAttemptAutoDiscoveryThisLaunch = true
            if let discovered = await discoverFirstPeerHost() {
                peerHost = discovered
            }
        }

        if !peerHost.isEmpty {
            let peerOk = await syncWithPeer(host: peerHost)
            notes.append(peerOk ? "Wi-Fi OK" : "Wi-Fi offline")
        }

        // Channel B: Google Drive Cloud Sync (appDataFolder)
        if AuthManager.shared.isAuthenticated && AuthManager.shared.hasAppDataScope {
            let cloudOk = await syncWithGoogleDriveAppData()
            notes.append(cloudOk ? "Drive Cloud OK" : "Drive Cloud error")
        }

        if !notes.isEmpty {
            lastSyncDate = Date()
            statusMessage = notes.joined(separator: " • ")
        } else if AuthManager.shared.isAuthenticated && !AuthManager.shared.hasAppDataScope {
            statusMessage = "Enable Cloud Sync in Settings or add Fire TV IP"
        } else {
            statusMessage = "Ready"
        }
    }

    // MARK: - Snapshot & Merge

    func exportLocalState() -> DriveStreamSyncState {
        let linksManager = SavedLinksManager.shared
        let syncedLinks = linksManager.links.map { link in
            DriveStreamSyncState.SyncedSavedLink(
                id: link.id.uuidString.lowercased(),
                title: link.title,
                urlString: link.urlString,
                dateAdded: link.dateAdded.timeIntervalSince1970
            )
        }

        var syncedProgress: [String: DriveStreamSyncState.WatchProgressEntry] = [:]
        for (fileId, progress) in PlaybackStore.shared.progressByFileId {
            syncedProgress[fileId] = DriveStreamSyncState.WatchProgressEntry(
                positionSeconds: progress.position,
                durationSeconds: progress.duration,
                updatedAt: progress.updatedAt.timeIntervalSince1970
            )
        }

        let covers = TMDBService.shared.exportCustomCoversForSync()

        return DriveStreamSyncState(
            version: 1,
            savedLinks: syncedLinks,
            deletedLinkIds: linksManager.deletedTombstones,
            watchProgress: syncedProgress,
            customCovers: covers
        )
    }

    func applyMergedState(_ merged: DriveStreamSyncState) async {
        isApplyingRemote = true
        defer { isApplyingRemote = false }

        let convertedLinks = merged.savedLinks.map { item in
            SavedLink(
                id: UUID(uuidString: item.id) ?? Self.deterministicUUID(from: item.id),
                title: item.title,
                urlString: item.urlString,
                dateAdded: Date(timeIntervalSince1970: item.dateAdded)
            )
        }
        SavedLinksManager.shared.applySyncedState(
            links: convertedLinks,
            deletedTombstones: merged.deletedLinkIds
        )

        var convertedProgress: [String: PlaybackProgress] = [:]
        for (fileId, entry) in merged.watchProgress {
            convertedProgress[fileId] = PlaybackProgress(
                fileId: fileId,
                position: entry.positionSeconds,
                duration: entry.durationSeconds,
                updatedAt: Date(timeIntervalSince1970: entry.updatedAt)
            )
        }
        PlaybackStore.shared.applySyncedEntries(convertedProgress)

        let coversChanged = await TMDBService.shared.applyMergedCustomCovers(merged.customCovers)
        if coversChanged {
            coversRevision += 1
        }
    }

    static func merge(_ local: DriveStreamSyncState, _ remote: DriveStreamSyncState) -> DriveStreamSyncState {
        var mergedDeleted: [String: Double] = [:]
        for (k, v) in local.deletedLinkIds {
            let key = k.lowercased()
            mergedDeleted[key] = max(mergedDeleted[key] ?? 0, v)
        }
        for (k, v) in remote.deletedLinkIds {
            let key = k.lowercased()
            mergedDeleted[key] = max(mergedDeleted[key] ?? 0, v)
        }

        var linksById: [String: DriveStreamSyncState.SyncedSavedLink] = [:]
        for item in local.savedLinks + remote.savedLinks {
            let key = item.id.lowercased()
            let normalized = DriveStreamSyncState.SyncedSavedLink(
                id: key,
                title: item.title,
                urlString: item.urlString,
                dateAdded: item.dateAdded
            )
            if let existing = linksById[key] {
                if normalized.dateAdded > existing.dateAdded ||
                    (normalized.dateAdded == existing.dateAdded && normalized.title.count > existing.title.count) {
                    linksById[key] = normalized
                }
            } else {
                linksById[key] = normalized
            }
        }

        // Remove links deleted at or after their latest dateAdded, then deduplicate identical URLs.
        var linksByCanonicalURL: [String: DriveStreamSyncState.SyncedSavedLink] = [:]
        for item in linksById.values {
            if let deletedAt = mergedDeleted[item.id], deletedAt >= item.dateAdded {
                continue
            }
            let urlKey = canonicalURLKey(item.urlString)
            if let existing = linksByCanonicalURL[urlKey] {
                if item.dateAdded > existing.dateAdded {
                    linksByCanonicalURL[urlKey] = item
                }
            } else {
                linksByCanonicalURL[urlKey] = item
            }
        }

        let sortedLinks = linksByCanonicalURL.values.sorted {
            if $0.dateAdded != $1.dateAdded {
                return $0.dateAdded > $1.dateAdded
            }
            return $0.id < $1.id
        }

        var mergedProgress = local.watchProgress
        for (fileId, remoteEntry) in remote.watchProgress {
            if let localEntry = mergedProgress[fileId] {
                if remoteEntry.updatedAt > localEntry.updatedAt ||
                    (remoteEntry.updatedAt == localEntry.updatedAt && remoteEntry.positionSeconds > localEntry.positionSeconds) {
                    mergedProgress[fileId] = remoteEntry
                }
            } else {
                mergedProgress[fileId] = remoteEntry
            }
        }

        var mergedCovers = local.customCovers
        for (showKey, remoteCover) in remote.customCovers {
            if let localCover = mergedCovers[showKey] {
                let localTs = localCover.updatedAt ?? 0
                let remoteTs = remoteCover.updatedAt ?? 0
                if remoteTs > localTs {
                    mergedCovers[showKey] = remoteCover
                } else if abs(remoteTs - localTs) < 0.001 {
                    let remoteScore = (remoteCover.posterUrl != nil ? 1 : 0) + (remoteCover.tvmazeShowId != nil ? 1 : 0)
                    let localScore = (localCover.posterUrl != nil ? 1 : 0) + (localCover.tvmazeShowId != nil ? 1 : 0)
                    if remoteScore > localScore {
                        mergedCovers[showKey] = remoteCover
                    }
                }
            } else {
                mergedCovers[showKey] = remoteCover
            }
        }

        return DriveStreamSyncState(
            version: 1,
            savedLinks: sortedLinks,
            deletedLinkIds: mergedDeleted,
            watchProgress: mergedProgress,
            customCovers: mergedCovers
        )
    }

    // MARK: - Channel A: Home Wi-Fi Peer Sync

    private func syncWithPeer(host: String) async -> Bool {
        guard let url = URL(string: "http://\(host):\(Self.peerPort)/api/sync") else { return false }
        let localSnapshot = exportLocalState()
        guard let bodyData = try? JSONEncoder().encode(localSnapshot) else { return false }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = bodyData

        do {
            let (data, response) = try await peerSession.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                return false
            }
            let remoteMerged = try JSONDecoder().decode(DriveStreamSyncState.self, from: data)
            let merged = Self.merge(exportLocalState(), remoteMerged)
            await applyMergedState(merged)
            return true
        } catch {
            return false
        }
    }

    nonisolated private func discoverFirstPeerHost() async -> String? {
        guard let localIP = Self.detectLocalIPv4() else { return nil }
        let octets = localIP.split(separator: ".")
        guard octets.count == 4 else { return nil }
        let prefix = "\(octets[0]).\(octets[1]).\(octets[2])"

        let scanConfig = URLSessionConfiguration.ephemeral
        scanConfig.timeoutIntervalForRequest = 0.5
        scanConfig.timeoutIntervalForResource = 0.75
        let scanSession = URLSession(configuration: scanConfig)
        defer { scanSession.invalidateAndCancel() }

        let candidates = (1...254).compactMap { hostPart -> String? in
            let ip = "\(prefix).\(hostPart)"
            return ip == localIP ? nil : ip
        }

        let batchSize = 48
        var offset = 0
        while offset < candidates.count {
            let end = min(offset + batchSize, candidates.count)
            let batch = candidates[offset..<end]

            let found: String? = await withTaskGroup(of: String?.self, returning: String?.self) { group in
                for ip in batch {
                    group.addTask {
                        guard let url = URL(string: "http://\(ip):\(SyncManager.peerPort)/api/sync") else {
                            return nil
                        }
                        var req = URLRequest(url: url)
                        req.httpMethod = "GET"
                        guard let (data, resp) = try? await scanSession.data(for: req),
                              let http = resp as? HTTPURLResponse,
                              http.statusCode == 200,
                              let decoded = try? JSONDecoder().decode(DriveStreamSyncState.self, from: data),
                              decoded.version >= 1 else {
                            return nil
                        }
                        return ip
                    }
                }
                for await result in group {
                    if let hit = result {
                        group.cancelAll()
                        return hit
                    }
                }
                return nil
            }

            if let found {
                return found
            }
            offset = end
        }
        return nil
    }

    // MARK: - Channel B: Google Drive Cloud Sync (appDataFolder)

    private func syncWithGoogleDriveAppData() async -> Bool {
        do {
            var token = try await AuthManager.shared.validAccessToken()
            var fileId = cachedAppDataFileId
            if fileId == nil {
                let (foundId, updatedToken) = try await findCloudSyncFileId(token: token)
                fileId = foundId
                cachedAppDataFileId = foundId
                token = updatedToken
            }

            var remoteState: DriveStreamSyncState?
            if let existingId = fileId {
                let (downloaded, status, updatedToken) = try await downloadCloudSyncFile(fileId: existingId, token: token)
                token = updatedToken
                if status == 404 {
                    cachedAppDataFileId = nil
                    fileId = nil
                } else {
                    remoteState = downloaded
                }
            }

            let localState = exportLocalState()
            let mergedState: DriveStreamSyncState
            let needsUpload: Bool

            if let remoteState {
                mergedState = Self.merge(localState, remoteState)
                needsUpload = (mergedState != remoteState)
            } else {
                mergedState = Self.merge(localState, DriveStreamSyncState())
                needsUpload = true
            }

            await applyMergedState(mergedState)

            if needsUpload {
                let latestMerged = Self.merge(exportLocalState(), mergedState)
                let payload = try JSONEncoder().encode(latestMerged)
                if let existingId = fileId {
                    let uploadedOk = try await updateCloudSyncFile(fileId: existingId, data: payload, token: token)
                    if !uploadedOk {
                        cachedAppDataFileId = nil
                    }
                } else {
                    cachedAppDataFileId = try await createCloudSyncFile(data: payload, token: token)
                }
            }
            return true
        } catch CloudSyncError.insufficientScope {
            AuthManager.shared.markAppDataScopeRevoked()
            return false
        } catch {
            return false
        }
    }

    private func findCloudSyncFileId(token: String) async throws -> (String?, String) {
        var components = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        components.queryItems = [
            URLQueryItem(name: "spaces", value: "appDataFolder"),
            URLQueryItem(name: "q", value: "name='\(Self.cloudFileName)' and trashed=false"),
            URLQueryItem(name: "fields", value: "files(id,name,modifiedTime)"),
            URLQueryItem(name: "pageSize", value: "1")
        ]

        let request = URLRequest(url: components.url!)
        let (data, http, activeToken) = try await executeAuthedDriveRequest(request, token: token)
        guard (200..<300).contains(http.statusCode) else {
            throw CloudSyncError.httpFailure(http.statusCode)
        }
        struct ListResp: Decodable {
            struct FileItem: Decodable { let id: String }
            let files: [FileItem]
        }
        let decoded = try JSONDecoder().decode(ListResp.self, from: data)
        return (decoded.files.first?.id, activeToken)
    }

    private func downloadCloudSyncFile(fileId: String, token: String) async throws -> (DriveStreamSyncState?, Int, String) {
        let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(fileId)?alt=media")!
        let request = URLRequest(url: url)
        let (data, http, activeToken) = try await executeAuthedDriveRequest(request, token: token)
        if http.statusCode == 404 {
            return (nil, 404, activeToken)
        }
        guard (200..<300).contains(http.statusCode) else {
            throw CloudSyncError.httpFailure(http.statusCode)
        }
        let decoded = try? JSONDecoder().decode(DriveStreamSyncState.self, from: data)
        return (decoded, http.statusCode, activeToken)
    }

    private func updateCloudSyncFile(fileId: String, data payload: Data, token: String) async throws -> Bool {
        let url = URL(string: "https://www.googleapis.com/upload/drive/v3/files/\(fileId)?uploadType=media")!
        var request = URLRequest(url: url)
        request.httpMethod = "PATCH"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = payload
        let (_, http, _) = try await executeAuthedDriveRequest(request, token: token)
        if http.statusCode == 404 {
            return false
        }
        guard (200..<300).contains(http.statusCode) else {
            throw CloudSyncError.httpFailure(http.statusCode)
        }
        return true
    }

    private func createCloudSyncFile(data payload: Data, token: String) async throws -> String? {
        let boundary = "drivestream-sync-\(UUID().uuidString)"
        let url = URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        var body = Data()
        body.append(Data("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".utf8))
        body.append(Data("{\"name\":\"\(Self.cloudFileName)\",\"parents\":[\"appDataFolder\"]}\r\n".utf8))
        body.append(Data("--\(boundary)\r\nContent-Type: application/json\r\n\r\n".utf8))
        body.append(payload)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        request.httpBody = body

        let (data, http, _) = try await executeAuthedDriveRequest(request, token: token)
        guard (200..<300).contains(http.statusCode) else {
            throw CloudSyncError.httpFailure(http.statusCode)
        }
        struct CreateResp: Decodable { let id: String }
        return try? JSONDecoder().decode(CreateResp.self, from: data).id
    }

    private func executeAuthedDriveRequest(
        _ baseRequest: URLRequest,
        token initialToken: String
    ) async throws -> (Data, HTTPURLResponse, String) {
        var activeToken = initialToken
        for attempt in 0..<2 {
            var request = baseRequest
            request.setValue("Bearer \(activeToken)", forHTTPHeaderField: "Authorization")
            let (data, response) = try await cloudSession.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw URLError(.badServerResponse)
            }
            if http.statusCode == 401 && attempt == 0 {
                activeToken = try await AuthManager.shared.refreshAccessToken()
                continue
            }
            if http.statusCode == 403 {
                let text = String(data: data, encoding: .utf8) ?? ""
                if text.contains("insufficientPermissions") || text.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT") {
                    throw CloudSyncError.insufficientScope
                }
            }
            return (data, http, activeToken)
        }
        throw CloudSyncError.httpFailure(401)
    }

    // MARK: - Helpers

    private enum CloudSyncError: Error {
        case insufficientScope
        case httpFailure(Int)
    }

    private static func cleanHost(_ raw: String) -> String {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.lowercased().hasPrefix("http://") {
            text = String(text.dropFirst(7))
        } else if text.lowercased().hasPrefix("https://") {
            text = String(text.dropFirst(8))
        }
        if let slash = text.firstIndex(of: "/") {
            text = String(text[..<slash])
        }
        if let colon = text.lastIndex(of: ":"), !text.contains("]") {
            let portPart = String(text[text.index(after: colon)...])
            if Int(portPart) != nil {
                text = String(text[..<colon])
            }
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func canonicalURLKey(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: trimmed) else {
            return trimmed.lowercased()
        }
        components.scheme = components.scheme?.lowercased()
        components.host = components.host?.lowercased()
        if components.path == "/" {
            components.path = ""
        }
        return components.string ?? trimmed.lowercased()
    }

    private static func deterministicUUID(from seed: String) -> UUID {
        var bytes = [UInt8](repeating: 0, count: 16)
        let utf8 = Array(seed.utf8)
        for (i, b) in utf8.enumerated() {
            bytes[i % 16] ^= b
        }
        bytes[6] = (bytes[6] & 0x0F) | 0x40
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }

    nonisolated private static func detectLocalIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        var fallback: String?
        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let current = ptr {
            defer { ptr = current.pointee.ifa_next }
            guard let addr = current.pointee.ifa_addr,
                  addr.pointee.sa_family == UInt8(AF_INET) else {
                continue
            }
            let flags = UInt32(current.pointee.ifa_flags)
            // IFF_UP = 0x1, IFF_LOOPBACK = 0x8
            guard (flags & 0x1) != 0, (flags & 0x8) == 0 else {
                continue
            }
            let name = String(cString: current.pointee.ifa_name)
            var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            var sinAddr = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
            guard inet_ntop(AF_INET, &sinAddr, &buffer, socklen_t(INET_ADDRSTRLEN)) != nil else {
                continue
            }
            let ip = String(cString: buffer)
            if name == "en0" {
                return ip
            }
            if fallback == nil {
                fallback = ip
            }
        }
        return fallback
    }
}
