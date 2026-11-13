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

    let id: String
    let name: String
    let mimeType: String
    let size: String?
    let thumbnailLink: String?
    let videoMediaMetadata: VideoMediaMetadata?
    var folderName: String?

    var formattedSize: String? {
        guard let size, let bytes = Int64(size) else { return nil }
        return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

private struct DriveFileListResponse: Decodable {
    let nextPageToken: String?
    let files: [DriveFile]
}

final class DriveService {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func fetchVideoFiles(folderID: String?, authManager: AuthManager) async throws -> [DriveFile] {
        let trimmedFolder = folderID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if trimmedFolder.isEmpty {
            let query = "trashed = false and (mimeType contains 'video/' or name contains '.mp4' or name contains '.m4v' or name contains '.mov')"
            return try await listAllPages(query: query, folderName: nil, authManager: authManager)
        }

        // Breadth-first traversal so nested Show/Season folders are all discovered
        var allVideos: [DriveFile] = []
        var visitedFolders: Set<String> = [trimmedFolder]
        var queue: [(id: String, showHint: String?)] = [(trimmedFolder, nil)]

        while !queue.isEmpty {
            let current = queue.removeFirst()
            let escapedId = current.id.replacingOccurrences(of: "'", with: "\\'")

            let videoQuery = "'\(escapedId)' in parents and trashed = false and (mimeType contains 'video/' or name contains '.mp4' or name contains '.m4v' or name contains '.mov')"
            let videos = try await listAllPages(query: videoQuery, folderName: current.showHint, authManager: authManager)
            allVideos.append(contentsOf: videos)

            let folderQuery = "'\(escapedId)' in parents and trashed = false and mimeType = 'application/vnd.google-apps.folder'"
            let subfolders = try await listAllPages(query: folderQuery, folderName: nil, authManager: authManager)
            for folder in subfolders where !visitedFolders.contains(folder.id) {
                visitedFolders.insert(folder.id)
                let nextHint = isSeasonFolder(folder.name) ? (current.showHint ?? folder.name) : folder.name
                queue.append((folder.id, nextHint))
            }
        }

        return allVideos
    }

    private func isSeasonFolder(_ name: String) -> Bool {
        name.range(of: #"^(?:season|s)\s*\d+$"#, options: [.regularExpression, .caseInsensitive]) != nil
    }

    func makePlayerAsset(for fileId: String, accessToken: String) -> AVURLAsset {
        let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(fileId)?alt=media")!
        let headers = ["Authorization": "Bearer \(accessToken)"]
        return AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
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
            URLQueryItem(name: "fields", value: "nextPageToken,files(id,name,mimeType,size,thumbnailLink,videoMediaMetadata)"),
            URLQueryItem(name: "pageSize", value: "200"),
            URLQueryItem(name: "supportsAllDrives", value: "true"),
            URLQueryItem(name: "includeItemsFromAllDrives", value: "true"),
            URLQueryItem(name: "orderBy", value: "name_natural")
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
