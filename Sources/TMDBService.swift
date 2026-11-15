import Foundation

struct ParsedMediaInfo: Hashable {
    let title: String
    let season: Int?
    let episode: Int?
    let year: Int?
    let isMovie: Bool

    init(title: String, season: Int?, episode: Int?, year: Int? = nil, isMovie: Bool = false) {
        self.title = title
        self.season = season
        self.episode = episode
        self.year = year
        self.isMovie = isMovie
    }

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
        case imdb

        var label: String {
            switch self {
            case .tvMaze: return "TVMaze"
            case .iTunes: return "iTunes"
            case .imdb: return "IMDb"
            }
        }
    }

    let id: String
    let source: Source
    let title: String
    let year: String?
    let posterURL: URL?
    let summary: String?
    let isMovie: Bool

    init(
        id: String,
        source: Source,
        title: String,
        year: String?,
        posterURL: URL?,
        summary: String?,
        isMovie: Bool = false
    ) {
        self.id = id
        self.source = source
        self.title = title
        self.year = year
        self.posterURL = posterURL
        self.summary = summary
        self.isMovie = isMovie
    }

    var kindLabel: String {
        let base = isMovie ? "Movie" : "TV Series"
        if let year, !year.isEmpty {
            return "\(base) (\(year))"
        }
        return base
    }
}

struct CustomCoverMatch: Codable, Equatable {
    let posterUrl: String?
    let tvmazeShowId: Int?
    let updatedAt: Double?

    init(posterUrl: String?, tvmazeShowId: Int?, updatedAt: Double? = nil) {
        self.posterUrl = posterUrl
        self.tvmazeShowId = tvmazeShowId
        self.updatedAt = updatedAt
    }
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
        var updatedAt: Double? = nil
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
                return ParsedMediaInfo(
                    title: cleanedTitle,
                    season: season,
                    episode: episode,
                    year: extractReleaseYear(rawPrefix).year,
                    isMovie: false
                )
            }
        }

        let extracted = extractReleaseYear(baseName)
        var cleaned = cleanTitle(extracted.titleWithoutYear)
        if cleaned.isEmpty {
            cleaned = cleanTitle(baseName)
        }
        if isSeasonFolderName(cleaned) {
            cleaned = ""
        }
        let resolved = cleaned.isEmpty ? (validFolderHint ?? baseName) : cleaned
        return ParsedMediaInfo(
            title: resolved,
            season: nil,
            episode: nil,
            year: extracted.year,
            isMovie: true
        )
    }

    func isSeasonOnlyTitle(_ title: String) -> Bool {
        isSeasonFolderName(title.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    func metadata(for file: DriveFile) async -> TMDBMetadata {
        let parsed = parseFilename(file.name, folderHint: file.folderName)
        let driveThumb = file.thumbnailLink.flatMap(URL.init(string:))
        let showKey = normalizedShowKey(parsed.title)

        let show = await resolveShow(
            query: parsed.title,
            showKey: showKey,
            isMovie: parsed.isMovie,
            year: parsed.year
        )
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

        let extracted = extractReleaseYear(trimmed)
        let stripped = cleanTitle(extracted.titleWithoutYear)
        let titleQuery = stripped.isEmpty ? trimmed : stripped

        async let movieITunes = searchITunes(query: titleQuery, isMovie: true, year: extracted.year)
        async let imdb = searchIMDbSuggestions(query: titleQuery, year: extracted.year)
        async let tvMaze = searchTVMaze(query: titleQuery)
        async let tvITunes = searchITunes(query: titleQuery, isMovie: false, year: nil)

        let (movies, imdbResults, shows, showSeasons) = await (movieITunes, imdb, tvMaze, tvITunes)
        if extracted.year != nil {
            return movies + imdbResults + shows + showSeasons
        }
        return shows + movies + imdbResults + showSeasons
    }

    func applyCoverSelection(_ selection: CoverSearchResult, forShowTitle showTitle: String) async {
        let showKey = normalizedShowKey(showTitle)
        guard !showKey.isEmpty else { return }
        let now = Date().timeIntervalSince1970

        switch selection.source {
        case .tvMaze(let showId):
            if let fetched = await fetchTVMazeShowById(showId) {
                var updated = fetched
                if updated.posterURL == nil {
                    updated.posterURL = selection.posterURL
                }
                updated.updatedAt = now
                cache.manualBindings[showKey] = updated
                cache.shows[showKey] = updated
                saveCache()
            }

        case .iTunes, .imdb:
            var existing = cache.manualBindings[showKey] ?? cache.shows[showKey]
            if existing == nil && !selection.isMovie {
                existing = await fetchTVMazeSingleShow(query: selection.title)
            }
            var bound = existing ?? CachedShow(
                tvMazeId: nil,
                name: selection.title.isEmpty ? showTitle : selection.title,
                summary: selection.summary,
                posterURL: selection.posterURL,
                episodes: [:]
            )
            if selection.isMovie {
                bound.tvMazeId = nil
            }
            if !selection.title.isEmpty {
                bound.name = selection.title
            }
            if let summary = selection.summary, !summary.isEmpty {
                bound.summary = summary
            }
            if let poster = selection.posterURL {
                bound.posterURL = poster
            }
            bound.updatedAt = now
            cache.manualBindings[showKey] = bound
            cache.shows[showKey] = bound
            saveCache()
        }
    }

    func exportCustomCoversForSync() -> [String: CustomCoverMatch] {
        var result: [String: CustomCoverMatch] = [:]
        for (showKey, show) in cache.manualBindings {
            result[showKey] = CustomCoverMatch(
                posterUrl: show.posterURL?.absoluteString,
                tvmazeShowId: show.tvMazeId,
                updatedAt: show.updatedAt
            )
        }
        return result
    }

    @discardableResult
    func applyMergedCustomCovers(_ merged: [String: CustomCoverMatch]) async -> Bool {
        var changed = false
        for (showKey, remote) in merged {
            guard !showKey.isEmpty else { continue }
            let existing = cache.manualBindings[showKey]
            let sameId = existing?.tvMazeId == remote.tvmazeShowId
            let samePoster = existing?.posterURL?.absoluteString == remote.posterUrl
            if var bumped = existing, sameId && samePoster {
                if (bumped.updatedAt ?? 0) < (remote.updatedAt ?? 0) {
                    bumped.updatedAt = remote.updatedAt
                    cache.manualBindings[showKey] = bumped
                    cache.shows[showKey] = bumped
                }
                continue
            }

            let resolvedURL = remote.posterUrl.flatMap { URL(string: $0) }
            var bound: CachedShow?

            if let showId = remote.tvmazeShowId {
                if var fetched = await fetchTVMazeShowById(showId) {
                    if let resolvedURL, fetched.posterURL == nil || resolvedURL != fetched.posterURL {
                        fetched.posterURL = resolvedURL
                    }
                    fetched.updatedAt = remote.updatedAt ?? Date().timeIntervalSince1970
                    bound = fetched
                }
            }

            if bound == nil {
                let fallback = existing ?? cache.shows[showKey]
                bound = CachedShow(
                    tvMazeId: remote.tvmazeShowId ?? fallback?.tvMazeId,
                    name: fallback?.name ?? showKey.capitalized,
                    summary: fallback?.summary,
                    posterURL: resolvedURL ?? fallback?.posterURL,
                    episodes: fallback?.episodes ?? [:],
                    updatedAt: remote.updatedAt ?? Date().timeIntervalSince1970
                )
            }

            if let bound {
                cache.manualBindings[showKey] = bound
                cache.shows[showKey] = bound
                changed = true
            }
        }

        if changed {
            saveCache()
        }
        return changed
    }

    func clearCache() {
        inFlightLookups.values.forEach { $0.cancel() }
        inFlightLookups.removeAll()
        cache = CacheStore()
        try? FileManager.default.removeItem(at: cacheURL)
    }

    // MARK: - Resolution & Coalescing

    private func resolveShow(
        query: String,
        showKey: String,
        isMovie: Bool = false,
        year: Int? = nil
    ) async -> CachedShow? {
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

            let resolved: CachedShow?
            if isMovie {
                if let movie = await resolveMovie(query: query, year: year) {
                    resolved = movie
                } else {
                    resolved = await resolveTVShow(query: query)
                }
            } else {
                if let show = await resolveTVShow(query: query) {
                    resolved = show
                } else {
                    resolved = await resolveMovie(query: query, year: year)
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

    private func resolveTVShow(query: String) async -> CachedShow? {
        var resolved = await fetchTVMazeSingleShow(query: query)
        if resolved?.posterURL == nil {
            let itunes = await searchITunes(query: query, isMovie: false)
            let first = itunes.first(where: { $0.posterURL != nil }) ?? itunes.first
            if let firstArt = first?.posterURL {
                if resolved != nil {
                    resolved?.posterURL = firstArt
                } else {
                    resolved = CachedShow(
                        tvMazeId: nil,
                        name: (first?.title.isEmpty == false ? first!.title : query),
                        summary: first?.summary,
                        posterURL: firstArt,
                        episodes: [:]
                    )
                }
            }
        }
        return resolved
    }

    private func resolveMovie(query: String, year: Int?) async -> CachedShow? {
        let itunes = await searchITunes(query: query, isMovie: true, year: year)
        let yearStr = year.map(String.init)
        if let yearStr,
           let exactItunes = itunes.first(where: { $0.year == yearStr && $0.posterURL != nil }),
           let art = exactItunes.posterURL {
            return CachedShow(
                tvMazeId: nil,
                name: exactItunes.title.isEmpty ? query : exactItunes.title,
                summary: exactItunes.summary,
                posterURL: art,
                episodes: [:]
            )
        }

        let imdb = await searchIMDbSuggestions(query: query, year: year)
        let moviePreferred = imdb.filter { $0.isMovie }
        let imdbPool = moviePreferred.isEmpty ? imdb : moviePreferred
        if let yearStr,
           let exactImdb = imdbPool.first(where: { $0.year == yearStr && $0.posterURL != nil }),
           let art = exactImdb.posterURL {
            return CachedShow(
                tvMazeId: nil,
                name: exactImdb.title.isEmpty ? query : exactImdb.title,
                summary: exactImdb.summary,
                posterURL: art,
                episodes: [:]
            )
        }

        if let best = pickBestMovieMatch(itunes, year: year) ?? pickBestMovieMatch(imdbPool, year: year),
           let art = best.posterURL {
            return CachedShow(
                tvMazeId: nil,
                name: best.title.isEmpty ? query : best.title,
                summary: best.summary,
                posterURL: art,
                episodes: [:]
            )
        }
        return nil
    }

    private func pickBestMovieMatch(_ results: [CoverSearchResult], year: Int?) -> CoverSearchResult? {
        let withPoster = results.filter { $0.posterURL != nil }
        if let year {
            let yearStr = String(year)
            if let yearMatch = withPoster.first(where: { $0.year == yearStr }) {
                return yearMatch
            }
        }
        return withPoster.first ?? results.first
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

    // MARK: - iTunes & IMDb Search APIs

    private func searchITunes(query: String, isMovie: Bool, year: Int? = nil) async -> [CoverSearchResult] {
        let termWithYear = (isMovie && year != nil) ? "\(query) \(year!)" : query
        let primary = await executeITunesSearch(term: termWithYear, isMovie: isMovie)
        if !primary.isEmpty || termWithYear == query {
            return primary
        }
        return await executeITunesSearch(term: query, isMovie: isMovie)
    }

    private func executeITunesSearch(term: String, isMovie: Bool) async -> [CoverSearchResult] {
        var components = URLComponents(string: "https://itunes.apple.com/search")!
        components.queryItems = [
            URLQueryItem(name: "term", value: term),
            URLQueryItem(name: "media", value: isMovie ? "movie" : "tvShow"),
            URLQueryItem(name: "entity", value: isMovie ? "movie" : "tvSeason"),
            URLQueryItem(name: "limit", value: isMovie ? "5" : "8")
        ]
        if let url = components.url {
            let primary = await fetchITunesResults(url: url, term: term, isMovie: isMovie, requireFeatureMovieKind: false)
            if !primary.isEmpty || !isMovie {
                return primary
            }
        }

        // iTunes Search sometimes returns 0 for media=movie; retry general US catalog filtered to feature-movie.
        var fallback = URLComponents(string: "https://itunes.apple.com/search")!
        fallback.queryItems = [
            URLQueryItem(name: "term", value: term),
            URLQueryItem(name: "country", value: "us"),
            URLQueryItem(name: "limit", value: "15")
        ]
        guard let fallbackURL = fallback.url else { return [] }
        return await fetchITunesResults(url: fallbackURL, term: term, isMovie: true, requireFeatureMovieKind: true)
    }

    private func fetchITunesResults(
        url: URL,
        term: String,
        isMovie: Bool,
        requireFeatureMovieKind: Bool
    ) async -> [CoverSearchResult] {
        do {
            let (data, response) = try await session.data(from: url)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                return []
            }
            let decoded = try JSONDecoder().decode(ITunesSearchResponseDTO.self, from: data)
            let filtered = requireFeatureMovieKind
                ? decoded.results.filter { $0.kind == "feature-movie" }
                : decoded.results
            return filtered.enumerated().map { index, result in
                let rawArt = result.artworkUrl100 ?? result.artworkUrl60
                let highResArt = rawArt?.replacingOccurrences(
                    of: #"\d+x\d+bb"#,
                    with: "600x900bb",
                    options: .regularExpression
                )
                let posterURL = highResArt.flatMap(URL.init(string:))
                let year = result.releaseDate.map { String($0.prefix(4)) }
                let title: String
                if isMovie {
                    title = result.trackName ?? result.collectionName ?? term
                } else {
                    title = result.collectionName ?? result.artistName ?? term
                }
                let idSuffix: String
                if isMovie, let trackId = result.trackId {
                    idSuffix = "movie-\(trackId)"
                } else if let collectionId = result.collectionId {
                    idSuffix = String(collectionId)
                } else {
                    idSuffix = "\(title)-\(index)"
                }
                let runtimeMin = result.trackTimeMillis.flatMap { $0 > 60_000 ? ($0 / 60_000) : nil }
                let rawDesc = stripHTML(result.longDescription ?? result.shortDescription)
                let summary: String?
                switch (runtimeMin, rawDesc) {
                case (let mins?, let desc?) where !desc.isEmpty:
                    summary = "\(mins)m • \(desc)"
                case (let mins?, _):
                    summary = "\(mins)m"
                case (nil, let desc):
                    summary = desc
                }
                return CoverSearchResult(
                    id: "itunes-\(idSuffix)",
                    source: .iTunes,
                    title: title,
                    year: year,
                    posterURL: posterURL,
                    summary: summary,
                    isMovie: isMovie
                )
            }
        } catch {
            return []
        }
    }

    private func searchIMDbSuggestions(query: String, year: Int? = nil) async -> [CoverSearchResult] {
        let candidates = year != nil ? ["\(query) \(year!)", query] : [query]
        for term in candidates {
            let found = await executeIMDbSuggestionQuery(term: term)
            if !found.isEmpty {
                return found
            }
        }
        return []
    }

    private func executeIMDbSuggestionQuery(term: String) async -> [CoverSearchResult] {
        let slug = term.lowercased()
            .replacingOccurrences(of: #"[^a-z0-9]+"#, with: "_", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        guard let first = slug.first, ("a"..."z").contains(first) || ("0"..."9").contains(first) else {
            return []
        }
        guard let url = URL(string: "https://v3.sg.media-imdb.com/suggestion/\(first)/\(slug).json") else {
            return []
        }

        do {
            let (data, response) = try await session.data(from: url)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                return []
            }
            let decoded = try JSONDecoder().decode(IMDbSuggestionResponseDTO.self, from: data)
            return decoded.d.prefix(8).compactMap { item in
                guard let id = item.id, id.hasPrefix("tt"), let title = item.l, !title.isEmpty else {
                    return nil
                }
                let posterURL = item.i?.imageUrl.flatMap(URL.init(string:))
                let year = item.y.flatMap { $0 > 1800 ? String($0) : nil }
                let qid = (item.qid ?? "").lowercased()
                let qText = (item.q ?? "").lowercased()
                let isMovie = qid != "tvseries" && qid != "tvminiseries" && !qText.contains("series")
                return CoverSearchResult(
                    id: "imdb-\(id)",
                    source: .imdb,
                    title: title,
                    year: year,
                    posterURL: posterURL,
                    summary: item.s,
                    isMovie: isMovie
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

    private func extractReleaseYear(_ raw: String) -> (year: Int?, titleWithoutYear: String) {
        let spaced = raw
            .replacingOccurrences(of: ".", with: " ")
            .replacingOccurrences(of: "_", with: " ")

        if let parenRegex = try? NSRegularExpression(pattern: #"\(((?:19|20)\d{2})\)"#) {
            let ns = spaced as NSString
            let matches = parenRegex.matches(in: spaced, range: NSRange(location: 0, length: ns.length))
            if let last = matches.last,
               last.numberOfRanges >= 2,
               let fullRange = Range(last.range(at: 0), in: spaced),
               let yearRange = Range(last.range(at: 1), in: spaced),
               let year = Int(spaced[yearRange]) {
                let prefix = String(spaced[..<fullRange.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
                if !prefix.isEmpty {
                    return (year, prefix)
                }
            }
        }

        if let bareRegex = try? NSRegularExpression(pattern: #"\b((?:19|20)\d{2})\b"#) {
            let ns = spaced as NSString
            let matches = bareRegex.matches(in: spaced, range: NSRange(location: 0, length: ns.length))
            if let last = matches.last(where: { $0.range.location > 0 }),
               last.numberOfRanges >= 2,
               let fullRange = Range(last.range(at: 0), in: spaced),
               let yearRange = Range(last.range(at: 1), in: spaced),
               let year = Int(spaced[yearRange]) {
                let prefix = String(spaced[..<fullRange.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
                if !prefix.isEmpty {
                    return (year, prefix)
                }
            }
        }

        return (nil, spaced)
    }

    private func cleanTitle(_ raw: String) -> String {
        var text = raw
            .replacingOccurrences(of: ".", with: " ")
            .replacingOccurrences(of: "_", with: " ")

        let stripPatterns = [
            #"\[[^\]]*\]"#,
            #"\((?:19|20)\d{2}\)"#,
            #"(?i)\b(2160p|1080p|720p|480p|4k|uhd|hdr|web-?dl|webrip|blu-?ray|brrip|bdrip|dvdrip|hdtv|x264|x265|hevc|h\.?264|h\.?265|aac|ac3|dts|atmos|remux|proper|repack|extended|unrated)\b.*"#
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
        let kind: String?
        let trackId: Int?
        let collectionId: Int?
        let artistName: String?
        let trackName: String?
        let collectionName: String?
        let artworkUrl60: String?
        let artworkUrl100: String?
        let releaseDate: String?
        let trackTimeMillis: Int?
        let shortDescription: String?
        let longDescription: String?
    }
    let results: [ResultDTO]
}

private struct IMDbSuggestionResponseDTO: Decodable {
    struct ImageDTO: Decodable {
        let imageUrl: String?
    }
    struct ItemDTO: Decodable {
        let id: String?
        let l: String?
        let q: String?
        let qid: String?
        let s: String?
        let y: Int?
        let i: ImageDTO?
    }
    let d: [ItemDTO]
}
