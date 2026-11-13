import Foundation

struct ParsedMediaInfo: Hashable {
    let title: String
    let season: Int?
    let episode: Int?

    var isEpisode: Bool {
        season != nil && episode != nil
    }

    var episodeCode: String? {
        guard let season, let episode else { return nil }
        return String(format: "S%02dE%02d", season, episode)
    }
}

struct TMDBMetadata: Codable, Hashable {
    let title: String
    let episodeTitle: String?
    let overview: String?
    let posterURL: URL?
    let stillURL: URL?
    let season: Int?
    let episode: Int?
}

@MainActor
final class TMDBService {
    static let shared = TMDBService()

    private struct CachedShow: Codable {
        let id: Int
        let name: String
        let overview: String?
        let posterPath: String?
        let backdropPath: String?
    }

    private struct CacheStore: Codable {
        var shows: [String: CachedShow] = [:]
        var items: [String: TMDBMetadata] = [:]
    }

    private let session: URLSession
    private let cacheURL: URL
    private var cache: CacheStore

    init(session: URLSession = .shared) {
        self.session = session
        let cachesDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        self.cacheURL = cachesDir.appendingPathComponent("tmdb_cache.json")
        if let data = try? Data(contentsOf: cacheURL),
           let decoded = try? JSONDecoder().decode(CacheStore.self, from: data) {
            self.cache = decoded
        } else {
            self.cache = CacheStore()
        }
    }

    func parseFilename(_ filename: String, folderHint: String? = nil) -> ParsedMediaInfo {
        let baseName = (filename as NSString).deletingPathExtension

        let patterns = [
            #"(?i)(?<![A-Za-z0-9])S(\d{1,2})[.\s_-]?E(\d{1,3})(?![A-Za-z0-9])"#,
            #"(?i)(?<!\d)(\d{1,2})x(\d{1,3})(?!\d)"#,
            #"(?i)(?<![A-Za-z0-9])Season\s*(\d{1,2})\s*(?:Episode|Ep\.?)\s*(\d{1,3})(?![A-Za-z0-9])"#
        ]

        for pattern in patterns {
            if let regex = try? NSRegularExpression(pattern: pattern),
               let match = regex.firstMatch(in: baseName, range: NSRange(baseName.startIndex..., in: baseName)),
               match.numberOfRanges >= 3,
               let seasonRange = Range(match.range(at: 1), in: baseName),
               let episodeRange = Range(match.range(at: 2), in: baseName),
               let fullRange = Range(match.range(at: 0), in: baseName),
               let season = Int(baseName[seasonRange]),
               let episode = Int(baseName[episodeRange]) {

                let rawPrefix = String(baseName[..<fullRange.lowerBound])
                var cleanedTitle = cleanTitle(rawPrefix)
                if cleanedTitle.isEmpty, let folderHint {
                    cleanedTitle = cleanTitle(folderHint)
                }
                if cleanedTitle.isEmpty {
                    cleanedTitle = cleanTitle(baseName)
                }
                return ParsedMediaInfo(title: cleanedTitle, season: season, episode: episode)
            }
        }

        let cleaned = cleanTitle(baseName)
        return ParsedMediaInfo(
            title: cleaned.isEmpty ? (folderHint ?? baseName) : cleaned,
            season: nil,
            episode: nil
        )
    }

    func metadata(for file: DriveFile, apiKey: String) async -> TMDBMetadata {
        let parsed = parseFilename(file.name, folderHint: file.folderName)
        let fallback = TMDBMetadata(
            title: parsed.title,
            episodeTitle: parsed.episodeCode,
            overview: nil,
            posterURL: nil,
            stillURL: file.thumbnailLink.flatMap(URL.init(string:)),
            season: parsed.season,
            episode: parsed.episode
        )

        let trimmedKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty else {
            return fallback
        }

        let itemKey = "\(parsed.title.lowercased())|\(parsed.season ?? -1)|\(parsed.episode ?? -1)"
        if let cached = cache.items[itemKey] {
            return cached
        }

        do {
            let fetched: TMDBMetadata
            if let season = parsed.season, let episode = parsed.episode {
                fetched = try await fetchTVMetadata(
                    query: parsed.title,
                    season: season,
                    episode: episode,
                    apiKey: trimmedKey,
                    fallbackThumbnail: fallback.stillURL
                )
            } else {
                fetched = try await fetchMovieMetadata(
                    query: parsed.title,
                    apiKey: trimmedKey,
                    fallbackThumbnail: fallback.stillURL
                )
            }
            cache.items[itemKey] = fetched
            saveCache()
            return fetched
        } catch {
            return fallback
        }
    }

    func clearCache() {
        cache = CacheStore()
        try? FileManager.default.removeItem(at: cacheURL)
    }

    // MARK: - TMDB API

    private func fetchTVMetadata(
        query: String,
        season: Int,
        episode: Int,
        apiKey: String,
        fallbackThumbnail: URL?
    ) async throws -> TMDBMetadata {
        let show = try await searchOrCachedShow(query: query, apiKey: apiKey)
        let posterURL = show.posterPath.flatMap { URL(string: "https://image.tmdb.org/t/p/w500\($0)") }
        let backdropURL = show.backdropPath.flatMap { URL(string: "https://image.tmdb.org/t/p/w780\($0)") }

        var components = URLComponents(string: "https://api.themoviedb.org/3/tv/\(show.id)/season/\(season)/episode/\(episode)")!
        components.queryItems = [URLQueryItem(name: "api_key", value: apiKey)]

        let (data, response) = try await session.data(from: components.url!)
        if let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) {
            let ep = try JSONDecoder().decode(TMDBEpisodeResponse.self, from: data)
            let stillURL = ep.stillPath.flatMap { URL(string: "https://image.tmdb.org/t/p/w780\($0)") }
            return TMDBMetadata(
                title: show.name,
                episodeTitle: ep.name,
                overview: ep.overview?.isEmpty == false ? ep.overview : show.overview,
                posterURL: posterURL,
                stillURL: stillURL ?? backdropURL ?? fallbackThumbnail,
                season: season,
                episode: episode
            )
        }

        return TMDBMetadata(
            title: show.name,
            episodeTitle: String(format: "S%02dE%02d", season, episode),
            overview: show.overview,
            posterURL: posterURL,
            stillURL: backdropURL ?? fallbackThumbnail,
            season: season,
            episode: episode
        )
    }

    private func searchOrCachedShow(query: String, apiKey: String) async throws -> CachedShow {
        let key = query.lowercased()
        if let cached = cache.shows[key] {
            return cached
        }

        var components = URLComponents(string: "https://api.themoviedb.org/3/search/tv")!
        components.queryItems = [
            URLQueryItem(name: "api_key", value: apiKey),
            URLQueryItem(name: "query", value: query)
        ]

        let (data, response) = try await session.data(from: components.url!)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }

        let decoded = try JSONDecoder().decode(TMDBSearchTVResponse.self, from: data)
        guard let first = decoded.results.first else {
            throw URLError(.resourceUnavailable)
        }

        let show = CachedShow(
            id: first.id,
            name: first.name,
            overview: first.overview,
            posterPath: first.posterPath,
            backdropPath: first.backdropPath
        )
        cache.shows[key] = show
        saveCache()
        return show
    }

    private func fetchMovieMetadata(
        query: String,
        apiKey: String,
        fallbackThumbnail: URL?
    ) async throws -> TMDBMetadata {
        var components = URLComponents(string: "https://api.themoviedb.org/3/search/movie")!
        components.queryItems = [
            URLQueryItem(name: "api_key", value: apiKey),
            URLQueryItem(name: "query", value: query)
        ]

        let (data, response) = try await session.data(from: components.url!)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }

        let decoded = try JSONDecoder().decode(TMDBSearchMovieResponse.self, from: data)
        guard let first = decoded.results.first else {
            throw URLError(.resourceUnavailable)
        }

        let posterURL = first.posterPath.flatMap { URL(string: "https://image.tmdb.org/t/p/w500\($0)") }
        let backdropURL = first.backdropPath.flatMap { URL(string: "https://image.tmdb.org/t/p/w780\($0)") }

        return TMDBMetadata(
            title: first.title,
            episodeTitle: nil,
            overview: first.overview,
            posterURL: posterURL,
            stillURL: backdropURL ?? posterURL ?? fallbackThumbnail,
            season: nil,
            episode: nil
        )
    }

    // MARK: - Helpers

    private func cleanTitle(_ raw: String) -> String {
        var text = raw
            .replacingOccurrences(of: ".", with: " ")
            .replacingOccurrences(of: "_", with: " ")

        // Strip trailing release quality/codec tags and bracketed metadata
        let stripPatterns = [
            #"\[[^\]]*\]"#,
            #"\((?:19|20)\d{2}\)"#,
            #"(?i)\b(2160p|1080p|720p|480p|4k|hdr|web-?dl|webrip|blu-?ray|bdrip|dvdrip|hdtv|x264|x265|hevc|h\.?264|h\.?265|aac|dts|atmos)\b.*"#
        ]
        for pattern in stripPatterns {
            text = text.replacingOccurrences(of: pattern, with: "", options: .regularExpression)
        }

        text = text.replacingOccurrences(of: #"\s*[-–—]+\s*$"#, with: "", options: .regularExpression)
        text = text.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func saveCache() {
        guard let data = try? JSONEncoder().encode(cache) else { return }
        try? data.write(to: cacheURL, options: .atomic)
    }
}

private struct TMDBSearchTVResponse: Decodable {
    struct Show: Decodable {
        let id: Int
        let name: String
        let overview: String?
        let posterPath: String?
        let backdropPath: String?

        enum CodingKeys: String, CodingKey {
            case id, name, overview
            case posterPath = "poster_path"
            case backdropPath = "backdrop_path"
        }
    }
    let results: [Show]
}

private struct TMDBEpisodeResponse: Decodable {
    let name: String
    let overview: String?
    let stillPath: String?

    enum CodingKeys: String, CodingKey {
        case name, overview
        case stillPath = "still_path"
    }
}

private struct TMDBSearchMovieResponse: Decodable {
    struct Movie: Decodable {
        let id: Int
        let title: String
        let overview: String?
        let posterPath: String?
        let backdropPath: String?

        enum CodingKeys: String, CodingKey {
            case id, title, overview
            case posterPath = "poster_path"
            case backdropPath = "backdrop_path"
        }
    }
    let results: [Movie]
}
