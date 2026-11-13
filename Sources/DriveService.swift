import AVFoundation
import Foundation

struct DriveFile: Identifiable, Codable, Hashable {
    struct VideoMediaMetadata: Codable, Hashable {
        let width: Int?
        let height: Int?
        let durationMillis: String?

        var durationSeconds: TimeInterval? {
            guard let durationMillis, let ms = Double(durationMillis) else { return nil }
            return ms / 1000.0
        }
    }

    struct ShortcutDetails: Codable, Hashable {
        let targetId: String?
        let targetMimeType: String?
    }

    let id: String
    let name: String
    let mimeType: String
    let size: String?
    let thumbnailLink: String?
    let videoMediaMetadata: VideoMediaMetadata?
    let parents: [String]?
    let shortcutDetails: ShortcutDetails?
    var folderName: String?

    var effectiveId: String {
        if mimeType == "application/vnd.google-apps.shortcut", let targetId = shortcutDetails?.targetId {
            return targetId
        }
        return id
    }

    var effectiveMimeType: String {
        if mimeType == "application/vnd.google-apps.shortcut", let targetMime = shortcutDetails?.targetMimeType {
            return targetMime
        }
        return mimeType
    }

    var fileExtension: String {
        (name as NSString).pathExtension.lowercased()
    }

    var isFolder: Bool {
        effectiveMimeType == "application/vnd.google-apps.folder"
    }

    var isSupportedVideo: Bool {
        guard !isFolder else { return false }
        if effectiveMimeType.lowercased().hasPrefix("video/") {
            return true
        }
        let videoExts: Set<String> = ["mp4", "m4v", "mov", "mkv", "avi", "webm", "wmv", "flv", "ts", "m2ts", "mpg", "mpeg", "ogv"]
        return videoExts.contains(fileExtension)
    }

    var prefersVLCPlayer: Bool {
        let nativeExtensions: Set<String> = ["mp4", "m4v", "mov"]
        if nativeExtensions.contains(fileExtension) {
            return false
        }
        if ["mkv", "avi", "webm", "wmv", "flv", "ts", "m2ts", "mpg", "mpeg", "ogv"].contains(fileExtension) {
            return true
        }
        return effectiveMimeType.lowercased().contains("matroska") || effectiveMimeType.lowercased().contains("avi")
    }

    var formattedSize: String? {
        guard let size, let bytes = Int64(size) else { return nil }
        return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

private struct DriveFileListResponse: Decodable {
    let nextPageToken: String?
    let files: [DriveFile]
}

private struct DriveNodeMetadata: Decodable {
    let id: String
    let name: String
    let parents: [String]?
}

final class DriveService {
    static let defaultFolderID = "89VXpiNB2PxwnR__gNUwAuwU2wnsT1ME"

    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func fetchVideoFiles(folderID: String?, authManager: AuthManager) async throws -> [DriveFile] {
        let trimmedFolder = folderID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        if trimmedFolder.isEmpty {
            let mediaFilter = "mimeType != 'application/vnd.google-apps.folder' and (mimeType contains 'video/' or name contains '.mp4' or name contains '.m4v' or name contains '.mov' or name contains '.mkv' or name contains '.avi')"
            let query = "trashed = false and \(mediaFilter)"
            var videos = try await listAllPages(query: query, folderName: nil, authManager: authManager)
            videos = videos.filter(\.isSupportedVideo)
            return await populateMissingShowHints(for: videos, authManager: authManager)
        }

        let initialHint = await resolveShowHint(forFolderId: trimmedFolder, authManager: authManager)

        // Breadth-first recursive scan across all subfolders (Season 01..04+) with pageSize=1000
        var allVideos: [DriveFile] = []
        var visitedFolders: Set<String> = [trimmedFolder]
        var queue: [(id: String, showHint: String?)] = [(trimmedFolder, initialHint)]

        while !queue.isEmpty {
            let current = queue.removeFirst()
            let escapedId = current.id.replacingOccurrences(of: "'", with: "\\'")
            let childrenQuery = "'\(escapedId)' in parents and trashed = false"
            let children = try await listAllPages(query: childrenQuery, folderName: current.showHint, authManager: authManager)

            for child in children {
                if child.isFolder {
                    let targetFolderId = child.effectiveId
                    guard !visitedFolders.contains(targetFolderId) else { continue }
                    visitedFolders.insert(targetFolderId)
                    let nextHint = isSeasonFolder(child.name) ? current.showHint : child.name
                    queue.append((targetFolderId, nextHint))
                } else if child.isSupportedVideo {
                    var resolvedFile = child
                    resolvedFile.folderName = current.showHint
                    allVideos.append(resolvedFile)
                }
            }
        }

        return allVideos
    }

    func makePlayerAsset(for fileId: String, accessToken: String) -> AVURLAsset {
        let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(fileId)?alt=media&acknowledgeAbuse=true&supportsAllDrives=true")!
        let headers = ["Authorization": "Bearer \(accessToken)"]
        return AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
    }

    // MARK: - Folder & Show Hint Helpers

    private func isSeasonFolder(_ name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.range(
            of: #"^(?:season(?:\s+.*|\d+)?|s\s*\d+|specials|extras)$"#,
            options: [.regularExpression, .caseInsensitive]
        ) != nil
    }

    private func isGenericRootFolder(_ name: String) -> Bool {
        let lower = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return ["my drive", "drive", "tv", "tv shows", "shows", "series", "videos", "movies", "media"].contains(lower)
    }

    private func resolveShowHint(forFolderId folderId: String, authManager: AuthManager) async -> String? {
        var cache: [String: DriveNodeMetadata] = [:]
        return await resolveNonSeasonAncestor(folderId: folderId, cache: &cache, authManager: authManager)
    }

    private func populateMissingShowHints(for files: [DriveFile], authManager: AuthManager) async -> [DriveFile] {
        var cache: [String: DriveNodeMetadata] = [:]
        var updated = files
        for idx in updated.indices {
            guard updated[idx].folderName == nil,
                  let parentId = updated[idx].parents?.first else { continue }
            updated[idx].folderName = await resolveNonSeasonAncestor(folderId: parentId, cache: &cache, authManager: authManager)
        }
        return updated
    }

    private func resolveNonSeasonAncestor(
        folderId: String,
        cache: inout [String: DriveNodeMetadata],
        authManager: AuthManager
    ) async -> String? {
        var currentId: String? = folderId
        var depth = 0
        while let id = currentId, depth < 4 {
            depth += 1
            let meta: DriveNodeMetadata?
            if let cached = cache[id] {
                meta = cached
            } else {
                meta = try? await fetchFolderMetadata(id: id, authManager: authManager)
                if let meta { cache[id] = meta }
            }
            guard let node = meta else { return nil }
            if !isSeasonFolder(node.name) {
                return isGenericRootFolder(node.name) ? nil : node.name
            }
            currentId = node.parents?.first
        }
        return nil
    }

    private func fetchFolderMetadata(id: String, authManager: AuthManager) async throws -> DriveNodeMetadata {
        let token = try await authManager.validAccessToken()
        let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(id)?fields=id,name,parents&supportsAllDrives=true")!
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await session.data(for: request)
        try validate(response: response, data: data)
        return try JSONDecoder().decode(DriveNodeMetadata.self, from: data)
    }

    // MARK: - Private

    private func listAllPages(query: String, folderName: String?, authManager: AuthManager) async throws -> [DriveFile] {
        var collected: [DriveFile] = []
        var pageToken: String?

        repeat {
            let page = try await fetchPage(query: query, pageToken: pageToken, authManager: authManager)
            for var file in page.files {
                file.folderName = folderName
                collected.append(file)
            }
            pageToken = page.nextPageToken
        } while pageToken != nil

        return collected
    }

    private func fetchPage(query: String, pageToken: String?, authManager: AuthManager) async throws -> DriveFileListResponse {
        let token = try await authManager.validAccessToken()
        let request = try buildListRequest(query: query, pageToken: pageToken, accessToken: token)
        let (data, response) = try await session.data(for: request)

        if let http = response as? HTTPURLResponse, http.statusCode == 401 {
            let refreshed = try await authManager.refreshAccessToken()
            let retryRequest = try buildListRequest(query: query, pageToken: pageToken, accessToken: refreshed)
            let (retryData, retryResponse) = try await session.data(for: retryRequest)
            try validate(response: retryResponse, data: retryData)
            return try JSONDecoder().decode(DriveFileListResponse.self, from: retryData)
        }

        try validate(response: response, data: data)
        return try JSONDecoder().decode(DriveFileListResponse.self, from: data)
    }

    private func buildListRequest(query: String, pageToken: String?, accessToken: String) throws -> URLRequest {
        var components = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        var items = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "fields", value: "nextPageToken,files(id,name,mimeType,size,thumbnailLink,videoMediaMetadata,parents,shortcutDetails)"),
            URLQueryItem(name: "pageSize", value: "1000"),
            URLQueryItem(name: "supportsAllDrives", value: "true"),
            URLQueryItem(name: "includeItemsFromAllDrives", value: "true")
        ]
        if let pageToken {
            items.append(URLQueryItem(name: "pageToken", value: pageToken))
        }
        components.queryItems = items

        guard let url = components.url else {
            throw DriveError.invalidURL
        }

        var request = URLRequest(url: url)
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func validate(response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else {
            throw DriveError.invalidResponse
        }
        guard (200..<300).contains(http.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? "HTTP \(http.statusCode)"
            throw DriveError.apiError(http.statusCode, body)
        }
    }
}

enum DriveError: LocalizedError {
    case invalidURL
    case invalidResponse
    case apiError(Int, String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Invalid Google Drive request URL."
        case .invalidResponse:
            return "Unexpected response from Google Drive."
        case .apiError(let code, let message):
            return "Google Drive error (\(code)): \(message)"
        }
    }
}
