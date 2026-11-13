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

struct CoverSearchResult: Identifiable, Hashable {
    enum Source: Hashable {
        case tvMaze(showId: Int)
        case iTunes

        var label: String {
            switch self {
            case .tvMaze: return "TVMaze"
            case .iTunes: return "iTunes"
            }
        }
    }

    let id: String
    let source: Source
    let title: String
    let year: String?
    let posterURL: URL?
    let summary: String?
}

@MainActor
final class TMDBService {
    static let shared = TMDBService()

    private struct CachedEpisode: Codable {
        let name: String
        let summary: String?
        let stillURL: URL?
    }

    private struct CachedShow: Codable {
        var tvMazeId: Int?
        var name: String
        var summary: String?
        var posterURL: URL?
        var episodes: [String: CachedEpisode]
    }

    private struct CacheStore: Codable {
        var shows: [String: CachedShow] = [:]
        var manualBindings: [String: CachedShow] = [:]
    }

    private let session: URLSession
    private let cacheURL: URL
    private var cache: CacheStore
    private var inFlightLookups: [String: Task<CachedShow?, Never>] = [:]

    init(session: URLSession = .shared) {
        self.session = session
        let cachesDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        self.cacheURL = cachesDir.appendingPathComponent("show_metadata_cache.json")
        if let data = try? Data(contentsOf: cacheURL),
           let decoded = try? JSONDecoder().decode(CacheStore.self, from: data) {
            self.cache = decoded
        } else {
            self.cache = CacheStore()
        }
    }

    func normalizedShowKey(_ title: String) -> String {
        let lowered = title.lowercased()
            .replacingOccurrences(of: #"[^\p{L}\p{N}\s]"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return lowered
    }

    func parseFilename(_ filename: String, folderHint: String? = nil) -> ParsedMediaInfo {
        let lastComponent = (filename as NSString).lastPathComponent
        let baseName = (lastComponent as NSString).deletingPathExtension

        let patterns = [
            #"(?i)(?<![A-Za-z0-9])S(\d{1,2})[.\s_-]?E(\d{1,3})(?![A-Za-z0-9])"#,
            #"(?i)(?<!\d)(\d{1,2})x(\d{1,3})(?!\d)"#,
            #"(?i)(?<![A-Za-z0-9])Season\s*(\d{1,2})\s*(?:Episode|Ep\.?)\s*(\d{1,3})(?![A-Za-z0-9])"#
        ]

        let validFolderHint: String? = {
            guard let folderHint else { return nil }
            let trimmed = folderHint.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty || isSeasonFolderName(trimmed) {
                return nil
            }
            return cleanTitle(trimmed)
        }()

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
                if isSeasonFolderName(cleanedTitle) {
                    cleanedTitle = ""
                }
                if cleanedTitle.isEmpty, let validFolderHint, !validFolderHint.isEmpty {
                    cleanedTitle = validFolderHint
                }
                if cleanedTitle.isEmpty {
                    cleanedTitle = cleanTitle(baseName)
                }
                return ParsedMediaInfo(title: cleanedTitle, season: season, episode: episode)
            }
        }

        var cleaned = cleanTitle(baseName)
        if isSeasonFolderName(cleaned) {
            cleaned = ""
        }
        let resolved = cleaned.isEmpty ? (validFolderHint ?? baseName) : cleaned
        return ParsedMediaInfo(title: resolved, season: nil, episode: nil)
    }

    func isSeasonOnlyTitle(_ title: String) -> Bool {
        isSeasonFolderName(title.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    func metadata(for file: DriveFile) async -> TMDBMetadata {
        let parsed = parseFilename(file.name, folderHint: file.folderName)
        let driveThumb = file.thumbnailLink.flatMap(URL.init(string:))
        let showKey = normalizedShowKey(parsed.title)

        let show = await resolveShow(query: parsed.title, showKey: showKey)
        if let show {
            var episodeTitle: String? = parsed.episodeCode
            var overview = show.summary
            var stillURL = driveThumb ?? show.posterURL

            if let season = parsed.season, let episode = parsed.episode,
               let ep = show.episodes[episodeKey(season: season, episode: episode)] {
                if !ep.name.isEmpty {
                    episodeTitle = ep.name
                }
                if let epSummary = ep.summary, !epSummary.isEmpty {
                    overview = epSummary
                }
                if let epStill = ep.stillURL {
                    stillURL = epStill
                }
            }

            return TMDBMetadata(
                title: show.name,
                episodeTitle: episodeTitle,
                overview: overview,
                posterURL: show.posterURL,
                stillURL: stillURL,
                season: parsed.season,
                episode: parsed.episode
            )
        }

        return TMDBMetadata(
            title: parsed.title,
            episodeTitle: parsed.episodeCode,
            overview: nil,
            posterURL: nil,
            stillURL: driveThumb,
            season: parsed.season,
            episode: parsed.episode
        )
    }

    func searchShowCandidates(query: String) async -> [CoverSearchResult] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }

        async let tvMazeResults = searchTVMaze(query: trimmed)
        async let iTunesResults = searchITunes(query: trimmed)

        let (tvMaze, itunes) = await (tvMazeResults, iTunesResults)
        return tvMaze + itunes
    }

    func applyCoverSelection(_ selection: CoverSearchResult, forShowTitle showTitle: String) async {
        let showKey = normalizedShowKey(showTitle)
        guard !showKey.isEmpty else { return }

        switch selection.source {
        case .tvMaze(let showId):
            if let fetched = await fetchTVMazeShowById(showId) {
                var updated = fetched
                if updated.posterURL == nil {
                    updated.posterURL = selection.posterURL
                }
                cache.manualBindings[showKey] = updated
                cache.shows[showKey] = updated
                saveCache()
            }

        case .iTunes:
            var existing = cache.manualBindings[showKey] ?? cache.shows[showKey]
            if existing == nil {
                existing = await fetchTVMazeSingleShow(query: selection.title)
            }
            var bound = existing ?? CachedShow(
                tvMazeId: nil,
                name: showTitle,
                summary: selection.summary,
                posterURL: selection.posterURL,
                episodes: [:]
            )
            if let poster = selection.posterURL {
                bound.posterURL = poster
            }
            cache.manualBindings[showKey] = bound
            cache.shows[showKey] = bound
            saveCache()
        }
    }

    func clearCache() {
        inFlightLookups.values.forEach { $0.cancel() }
        inFlightLookups.removeAll()
        cache = CacheStore()
        try? FileManager.default.removeItem(at: cacheURL)
    }

    // MARK: - Resolution & Coalescing

    private func resolveShow(query: String, showKey: String) async -> CachedShow? {
        guard !showKey.isEmpty else { return nil }
        if let bound = cache.manualBindings[showKey] {
            return bound
        }
        if let cached = cache.shows[showKey] {
            return cached
        }
        if let existingTask = inFlightLookups[showKey] {
            return await existingTask.value
        }

        let task = Task<CachedShow?, Never> {
            defer { inFlightLookups.removeValue(forKey: showKey) }

            var resolved = await fetchTVMazeSingleShow(query: query)
            if resolved?.posterURL == nil {
                let itunes = await searchITunes(query: query)
                if let firstArt = itunes.first?.posterURL {
                    if resolved != nil {
                        resolved?.posterURL = firstArt
                    } else {
                        resolved = CachedShow(
                            tvMazeId: nil,
                            name: query,
                            summary: itunes.first?.summary,
                            posterURL: firstArt,
                            episodes: [:]
                        )
                    }
                }
            }

            if let resolved {
                cache.shows[showKey] = resolved
                saveCache()
            }
            return resolved
        }

        inFlightLookups[showKey] = task
        return await task.value
    }

    // MARK: - TVMaze API

    private func fetchTVMazeSingleShow(query: String) async -> CachedShow? {
        var components = URLComponents(string: "https://api.tvmaze.com/singlesearch/shows")!
        components.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "embed", value: "episodes")
        ]
        guard let url = components.url else { return nil }

        do {
            let (data, response) = try await session.data(from: url)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                return nil
            }
            let show = try JSONDecoder().decode(TVMazeShowDTO.self, from: data)
            return makeCachedShow(from: show)
        } catch {
            return nil
        }
    }

    private func fetchTVMazeShowById(_ id: Int) async -> CachedShow? {
        var components = URLComponents(string: "https://api.tvmaze.com/shows/\(id)")!
        components.queryItems = [URLQueryItem(name: "embed", value: "episodes")]
        guard let url = components.url else { return nil }

        do {
            let (data, response) = try await session.data(from: url)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                return nil
            }
            let show = try JSONDecoder().decode(TVMazeShowDTO.self, from: data)
            return makeCachedShow(from: show)
        } catch {
            return nil
        }
    }

    private func searchTVMaze(query: String) async -> [CoverSearchResult] {
        var components = URLComponents(string: "https://api.tvmaze.com/search/shows")!
        components.queryItems = [URLQueryItem(name: "q", value: query)]
        guard let url = components.url else { return [] }

        do {
            let (data, response) = try await session.data(from: url)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                return []
            }
            let items = try JSONDecoder().decode([TVMazeSearchItemDTO].self, from: data)
            return items.prefix(8).map { item in
                let show = item.show
                let year = show.premiered.map { String($0.prefix(4)) }
                let poster = (show.image?.original ?? show.image?.medium).flatMap(URL.init(string:))
                return CoverSearchResult(
                    id: "tvmaze-\(show.id)",
                    source: .tvMaze(showId: show.id),
                    title: show.name,
                    year: year,
                    posterURL: poster,
                    summary: stripHTML(show.summary)
                )
            }
        } catch {
            return []
        }
    }

    private func makeCachedShow(from dto: TVMazeShowDTO) -> CachedShow {
        var episodeMap: [String: CachedEpisode] = [:]
        for ep in dto.embedded?.episodes ?? [] {
            guard let season = ep.season, let number = ep.number else { continue }
            let still = (ep.image?.original ?? ep.image?.medium).flatMap(URL.init(string:))
            episodeMap[episodeKey(season: season, episode: number)] = CachedEpisode(
                name: ep.name ?? "",
                summary: stripHTML(ep.summary),
                stillURL: still
            )
        }
        let poster = (dto.image?.original ?? dto.image?.medium).flatMap(URL.init(string:))
        return CachedShow(
            tvMazeId: dto.id,
            name: dto.name,
            summary: stripHTML(dto.summary),
            posterURL: poster,
            episodes: episodeMap
        )
    }

    // MARK: - iTunes Search API

    private func searchITunes(query: String) async -> [CoverSearchResult] {
        var components = URLComponents(string: "https://itunes.apple.com/search")!
        components.queryItems = [
            URLQueryItem(name: "term", value: query),
            URLQueryItem(name: "media", value: "tvShow"),
            URLQueryItem(name: "entity", value: "tvSeason"),
            URLQueryItem(name: "limit", value: "8")
        ]
        guard let url = components.url else { return [] }

        do {
            let (data, response) = try await session.data(from: url)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                return []
            }
            let decoded = try JSONDecoder().decode(ITunesSearchResponseDTO.self, from: data)
            return decoded.results.compactMap { result in
                let rawArt = result.artworkUrl100 ?? result.artworkUrl60
                let highResArt = rawArt?
                    .replacingOccurrences(of: "100x100bb", with: "600x600bb")
                    .replacingOccurrences(of: "60x60bb", with: "600x600bb")
                let posterURL = highResArt.flatMap(URL.init(string:))
                let year = result.releaseDate.map { String($0.prefix(4)) }
                let title = result.collectionName ?? result.artistName ?? query
                let idSuffix = result.collectionId.map(String.init) ?? title
                return CoverSearchResult(
                    id: "itunes-\(idSuffix)",
                    source: .iTunes,
                    title: title,
                    year: year,
                    posterURL: posterURL,
                    summary: stripHTML(result.longDescription)
                )
            }
        } catch {
            return []
        }
    }

    // MARK: - Helpers

    private func episodeKey(season: Int, episode: Int) -> String {
        "S\(season)E\(episode)"
    }

    private func isSeasonFolderName(_ name: String) -> Bool {
        name.range(
            of: #"^(?:season(?:\s+.*|\d+)?|s\s*\d+|specials|extras)$"#,
            options: [.regularExpression, .caseInsensitive]
        ) != nil
    }

    private func cleanTitle(_ raw: String) -> String {
        var text = raw
            .replacingOccurrences(of: ".", with: " ")
            .replacingOccurrences(of: "_", with: " ")

        let stripPatterns = [
            #"\[[^\]]*\]"#,
            #"\((?:19|20)\d{2}\)"#,
            #"(?i)\b(2160p|1080p|720p|480p|4k|hdr|web-?dl|webrip|blu-?ray|bdrip|dvdrip|hdtv|x264|x265|hevc|h\.?264|h\.?265|aac|dts|atmos)\b.*"#
        ]
        for pattern in stripPatterns {
            text = text.replacingOccurrences(of: pattern, with: "", options: .regularExpression)
        }

        text = text.replacingOccurrences(of: #"\s*[-–—:]+\s*$"#, with: "", options: .regularExpression)
        text = text.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func stripHTML(_ html: String?) -> String? {
        guard let html, !html.isEmpty else { return nil }
        let stripped = html
            .replacingOccurrences(of: #"<[^>]+>"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return stripped.isEmpty ? nil : stripped
    }

    private func saveCache() {
        guard let data = try? JSONEncoder().encode(cache) else { return }
        try? data.write(to: cacheURL, options: .atomic)
    }
}

// MARK: - DTOs

private struct TVMazeSearchItemDTO: Decodable {
    let show: TVMazeShowDTO
}

private struct TVMazeShowDTO: Decodable {
    struct ImageDTO: Decodable {
        let medium: String?
        let original: String?
    }

    struct EmbeddedDTO: Decodable {
        let episodes: [TVMazeEpisodeDTO]
    }

    struct TVMazeEpisodeDTO: Decodable {
        let season: Int?
        let number: Int?
        let name: String?
        let summary: String?
        let image: ImageDTO?
    }

    let id: Int
    let name: String
    let premiered: String?
    let summary: String?
    let image: ImageDTO?
    let embedded: EmbeddedDTO?

    enum CodingKeys: String, CodingKey {
        case id, name, premiered, summary, image
        case embedded = "_embedded"
    }
}

private struct ITunesSearchResponseDTO: Decodable {
    struct ResultDTO: Decodable {
        let collectionId: Int?
        let artistName: String?
        let collectionName: String?
        let artworkUrl60: String?
        let artworkUrl100: String?
        let releaseDate: String?
        let longDescription: String?
    }
    let results: [ResultDTO]
}
