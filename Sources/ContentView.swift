import AVFoundation
import SwiftUI

// MARK: - View Models & Domain Models

struct MediaItem: Identifiable, Hashable {
    let file: DriveFile
    let parsed: ParsedMediaInfo
    var metadata: TMDBMetadata?

    var id: String { file.id }

    var showTitle: String {
        metadata?.title ?? parsed.title
    }

    var episodeCode: String? {
        parsed.episodeCode
    }

    var episodeTitle: String? {
        metadata?.episodeTitle
    }

    var overview: String? {
        metadata?.overview
    }

    var stillURL: URL? {
        metadata?.stillURL ?? file.thumbnailLink.flatMap(URL.init(string:))
    }

    var posterURL: URL? {
        metadata?.posterURL ?? stillURL
    }
}

struct ShowGroup: Identifiable, Hashable {
    let id: String
    let title: String
    let items: [MediaItem]

    var posterURL: URL? {
        items.compactMap(\.posterURL).first
    }

    var seasons: [Int] {
        let numbers = Set(items.compactMap { $0.parsed.season })
        return numbers.sorted()
    }

    var isSeries: Bool {
        items.count > 1 || items.contains(where: { $0.parsed.isEpisode })
    }
}

struct ActivePlayback: Identifiable {
    let id: String
    let title: String
    let asset: AVURLAsset
}

@MainActor
final class LibraryViewModel: ObservableObject {
    @Published private(set) var items: [MediaItem] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let driveService = DriveService()
    private var loadGeneration = 0

    var showGroups: [ShowGroup] {
        let grouped = Dictionary(grouping: items) { item in
            item.showTitle.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        }

        return grouped.map { key, groupItems in
            let sorted = groupItems.sorted { lhs, rhs in
                let ls = lhs.parsed.season ?? Int.max
                let rs = rhs.parsed.season ?? Int.max
                if ls != rs { return ls < rs }
                let le = lhs.parsed.episode ?? Int.max
                let re = rhs.parsed.episode ?? Int.max
                if le != re { return le < re }
                return lhs.file.name.localizedStandardCompare(rhs.file.name) == .orderedAscending
            }
            return ShowGroup(
                id: key.isEmpty ? UUID().uuidString : key,
                title: sorted.first?.showTitle ?? "Videos",
                items: sorted
            )
        }
        .sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    func loadLibrary(authManager: AuthManager, folderID: String, tmdbAPIKey: String) async {
        loadGeneration += 1
        let generation = loadGeneration
        isLoading = true
        errorMessage = nil

        do {
            let files = try await driveService.fetchVideoFiles(folderID: folderID, authManager: authManager)
            guard generation == loadGeneration else { return }

            let initialItems = files.map { file in
                MediaItem(
                    file: file,
                    parsed: TMDBService.shared.parseFilename(file.name, folderHint: file.folderName),
                    metadata: nil
                )
            }
            items = initialItems
            isLoading = false

            let trimmedKey = tmdbAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmedKey.isEmpty, !initialItems.isEmpty else { return }

            await enrichMetadata(for: initialItems, apiKey: trimmedKey, generation: generation)
        } catch {
            guard generation == loadGeneration else { return }
            isLoading = false
            errorMessage = error.localizedDescription
        }
    }

    func makePlayback(for item: MediaItem, authManager: AuthManager) async -> ActivePlayback? {
        do {
            let token = try await authManager.validAccessToken()
            let asset = driveService.makePlayerAsset(for: item.file.id, accessToken: token)
            let displayTitle: String
            if let code = item.episodeCode, let epTitle = item.episodeTitle, epTitle != code {
                displayTitle = "\(item.showTitle) • \(code) \(epTitle)"
            } else if let code = item.episodeCode {
                displayTitle = "\(item.showTitle) • \(code)"
            } else {
                displayTitle = item.showTitle
            }
            return ActivePlayback(id: item.file.id, title: displayTitle, asset: asset)
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    private func enrichMetadata(for sourceItems: [MediaItem], apiKey: String, generation: Int) async {
        var updated = sourceItems
        let batchSize = 5

        for start in stride(from: 0, to: sourceItems.count, by: batchSize) {
            guard generation == loadGeneration else { return }
            let end = min(start + batchSize, sourceItems.count)

            await withTaskGroup(of: (Int, TMDBMetadata).self) { group in
                for idx in start..<end {
                    let file = sourceItems[idx].file
                    group.addTask {
                        let meta = await TMDBService.shared.metadata(for: file, apiKey: apiKey)
                        return (idx, meta)
                    }
                }
                for await (idx, meta) in group {
                    updated[idx].metadata = meta
                }
            }

            guard generation == loadGeneration else { return }
            items = updated
        }
    }
}

// MARK: - Root Content View

struct ContentView: View {
    @EnvironmentObject private var authManager: AuthManager

    var body: some View {
        Group {
            if authManager.isAuthenticated {
                LibraryHomeView()
            } else {
                SignInView()
            }
        }
        .animation(.easeInOut(duration: 0.25), value: authManager.isAuthenticated)
    }
}

// MARK: - Sign In View

private struct SignInView: View {
    @EnvironmentObject private var authManager: AuthManager

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.08, green: 0.09, blue: 0.14), .black],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(spacing: 28) {
                Spacer()

                ZStack {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .fill(Color.blue.opacity(0.18))
                        .frame(width: 92, height: 92)
                    Image(systemName: "play.rectangle.fill")
                        .font(.system(size: 44))
                        .foregroundStyle(.blue)
                }

                VStack(spacing: 10) {
                    Text("DriveStream")
                        .font(.system(size: 34, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)

                    Text("Stream videos directly from Google Drive with instant scrubbing, Picture-in-Picture, AirPlay, and TMDB metadata.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }

                if let error = authManager.authError {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                }

                Spacer()

                Button {
                    Task { await authManager.signIn() }
                } label: {
                    HStack(spacing: 10) {
                        if authManager.isSigningIn {
                            ProgressView()
                                .tint(.black)
                        } else {
                            Image(systemName: "person.crop.circle.badge.checkmark")
                                .font(.headline)
                        }
                        Text(authManager.isSigningIn ? "Signing In…" : "Sign in with Google")
                            .fontWeight(.semibold)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(Color.white)
                    .foregroundStyle(.black)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .disabled(authManager.isSigningIn)
                .padding(.horizontal, 28)
                .padding(.bottom, 36)
            }
        }
    }
}

// MARK: - Library Home View

private struct LibraryHomeView: View {
    @EnvironmentObject private var authManager: AuthManager
    @StateObject private var viewModel = LibraryViewModel()
    @ObservedObject private var playbackStore = PlaybackStore.shared

    @AppStorage("drive_folder_id") private var folderID = ""
    @AppStorage("tmdb_api_key") private var tmdbAPIKey = ""

    @State private var showSettings = false
    @State private var activePlayback: ActivePlayback?
    @State private var hasLoadedOnce = false

    private var continueWatchingItems: [(item: MediaItem, progress: PlaybackProgress)] {
        viewModel.items.compactMap { item in
            guard let progress = playbackStore.progress(for: item.file.id), progress.isInProgress else {
                return nil
            }
            return (item, progress)
        }
        .sorted { $0.progress.updatedAt > $1.progress.updatedAt }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color(red: 0.04, green: 0.04, blue: 0.06)
                    .ignoresSafeArea()

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 24) {
                        if let error = viewModel.errorMessage {
                            errorBanner(error)
                        }

                        if !continueWatchingItems.isEmpty {
                            continueWatchingSection
                        }

                        librarySection
                    }
                    .padding(.vertical, 12)
                }
                .refreshable {
                    await reload()
                }
                .overlay {
                    if viewModel.isLoading && viewModel.items.isEmpty {
                        VStack(spacing: 12) {
                            ProgressView()
                                .scaleEffect(1.2)
                            Text("Scanning Google Drive…")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    } else if !viewModel.isLoading && viewModel.items.isEmpty && viewModel.errorMessage == nil {
                        ContentUnavailableView(
                            "No Videos Found",
                            systemImage: "film.stack",
                            description: Text(folderID.isEmpty ? "No video files were found in your Google Drive." : "No videos found in the configured Folder ID. Check Settings or pull to refresh.")
                        )
                    }
                }
            }
            .navigationTitle("DriveStream")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        Task { await reload() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(viewModel.isLoading)

                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .task {
                guard !hasLoadedOnce else { return }
                hasLoadedOnce = true
                await reload()
            }
            .sheet(isPresented: $showSettings) {
                SettingsView(
                    folderID: $folderID,
                    tmdbAPIKey: $tmdbAPIKey,
                    onSaveOrChange: {
                        Task { await reload() }
                    }
                )
            }
            .fullScreenCover(item: $activePlayback) { playback in
                VideoPlayerView(fileId: playback.id, title: playback.title, asset: playback.asset)
            }
        }
    }

    private var continueWatchingSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Continue Watching")
                .font(.title3.bold())
                .padding(.horizontal, 16)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 14) {
                    ForEach(continueWatchingItems, id: \.item.id) { entry in
                        Button {
                            play(entry.item)
                        } label: {
                            ContinueWatchingCard(item: entry.item, progress: entry.progress)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button(role: .destructive) {
                                playbackStore.clearProgress(for: entry.item.file.id)
                            } label: {
                                Label("Remove from Continue Watching", systemImage: "xmark.circle")
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private var librarySection: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !viewModel.showGroups.isEmpty {
                Text("Library")
                    .font(.title3.bold())
                    .padding(.horizontal, 16)
            }

            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 155), spacing: 16)],
                spacing: 20
            ) {
                ForEach(viewModel.showGroups) { group in
                    if group.isSeries {
                        NavigationLink {
                            ShowDetailView(group: group, onPlay: play)
                        } label: {
                            ShowCard(group: group)
                        }
                        .buttonStyle(.plain)
                    } else if let singleItem = group.items.first {
                        Button {
                            play(singleItem)
                        } label: {
                            ShowCard(group: group)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.yellow)
            Text(message)
                .font(.footnote)
                .foregroundStyle(.white)
            Spacer()
        }
        .padding(12)
        .background(Color.red.opacity(0.22))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.horizontal, 16)
    }

    private func reload() async {
        await viewModel.loadLibrary(
            authManager: authManager,
            folderID: folderID,
            tmdbAPIKey: tmdbAPIKey
        )
    }

    private func play(_ item: MediaItem) {
        Task {
            activePlayback = await viewModel.makePlayback(for: item, authManager: authManager)
        }
    }
}

// MARK: - Cards

private struct ContinueWatchingCard: View {
    let item: MediaItem
    let progress: PlaybackProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottomLeading) {
                RemoteThumbnailView(url: item.stillURL, fallbackSymbol: "play.fill")
                    .frame(width: 230, height: 129)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                LinearGradient(
                    colors: [.clear, .black.opacity(0.75)],
                    startPoint: .center,
                    endPoint: .bottom
                )
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    if let code = item.episodeCode {
                        Text(code)
                            .font(.caption2.bold())
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(.ultraThinMaterial)
                            .clipShape(Capsule())
                    }

                    ProgressView(value: progress.fraction)
                        .tint(.blue)
                }
                .padding(8)
            }

            Text(item.showTitle)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .frame(width: 230, alignment: .leading)

            Text(subtitleText)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .frame(width: 230, alignment: .leading)
        }
    }

    private var subtitleText: String {
        if let epTitle = item.episodeTitle, !epTitle.isEmpty, epTitle != item.episodeCode {
            return epTitle
        }
        return formatRemaining(progress)
    }

    private func formatRemaining(_ progress: PlaybackProgress) -> String {
        guard progress.duration > 0 else {
            return "Resume at \(formatTime(progress.position))"
        }
        let remaining = max(progress.duration - progress.position, 0)
        let mins = Int(remaining / 60)
        return mins > 0 ? "\(mins)m left" : "<1m left"
    }

    private func formatTime(_ seconds: TimeInterval) -> String {
        let total = Int(seconds)
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}

private struct ShowCard: View {
    let group: ShowGroup

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .topTrailing) {
                RemoteThumbnailView(url: group.posterURL, fallbackSymbol: group.isSeries ? "tv" : "film")
                    .aspectRatio(2 / 3, contentMode: .fill)
                    .frame(maxWidth: .infinity)
                    .background(Color.white.opacity(0.06))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                if group.isSeries {
                    Text("\(group.items.count) ep")
                        .font(.caption2.bold())
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(.black.opacity(0.75))
                        .clipShape(Capsule())
                        .padding(8)
                }
            }

            Text(group.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
        }
    }
}

// MARK: - Show Detail View

private struct ShowDetailView: View {
    let group: ShowGroup
    let onPlay: (MediaItem) -> Void

    @ObservedObject private var playbackStore = PlaybackStore.shared
    @State private var selectedSeason: Int?

    init(group: ShowGroup, onPlay: @escaping (MediaItem) -> Void) {
        self.group = group
        self.onPlay = onPlay
        _selectedSeason = State(initialValue: group.seasons.first)
    }

    private var filteredItems: [MediaItem] {
        guard let selectedSeason, !group.seasons.isEmpty else {
            return group.items
        }
        return group.items.filter { $0.parsed.season == selectedSeason }
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                if group.seasons.count > 1 {
                    Picker("Season", selection: $selectedSeason) {
                        ForEach(group.seasons, id: \.self) { season in
                            Text("Season \(season)").tag(Optional(season))
                        }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 16)
                }

                ForEach(filteredItems) { item in
                    Button {
                        onPlay(item)
                    } label: {
                        EpisodeRow(
                            item: item,
                            progress: playbackStore.progress(for: item.file.id)
                        )
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 16)
                }
            }
            .padding(.vertical, 12)
        }
        .background(Color(red: 0.04, green: 0.04, blue: 0.06).ignoresSafeArea())
        .navigationTitle(group.title)
        .navigationBarTitleDisplayMode(.large)
    }
}

private struct EpisodeRow: View {
    let item: MediaItem
    let progress: PlaybackProgress?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack(alignment: .bottom) {
                RemoteThumbnailView(url: item.stillURL, fallbackSymbol: "play.circle")
                    .frame(width: 142, height: 80)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                if let progress, progress.isInProgress {
                    ProgressView(value: progress.fraction)
                        .tint(.blue)
                        .padding(.horizontal, 4)
                        .padding(.bottom, 4)
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    if let code = item.episodeCode {
                        Text(code)
                            .font(.caption.bold())
                            .foregroundStyle(.blue)
                    }
                    if let size = item.file.formattedSize {
                        Text(size)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }

                Text(displayName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(2)

                if let overview = item.overview, !overview.isEmpty {
                    Text(overview)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(10)
        .background(Color.white.opacity(0.05))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var displayName: String {
        if let epTitle = item.episodeTitle, !epTitle.isEmpty, epTitle != item.episodeCode {
            return epTitle
        }
        return item.file.name
    }
}

// MARK: - Settings View

private struct SettingsView: View {
    @EnvironmentObject private var authManager: AuthManager
    @Environment(\.dismiss) private var dismiss

    @Binding var folderID: String
    @Binding var tmdbAPIKey: String
    let onSaveOrChange: () -> Void

    @State private var draftFolderID = ""
    @State private var draftTMDBKey = ""
    @State private var clearedCache = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Google Drive Folder ID (optional)", text: $draftFolderID)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("Google Drive")
                } footer: {
                    Text("Paste the ID from the end of a Drive folder URL (drive.google.com/drive/folders/<ID>), or leave blank to scan all videos in your Drive.")
                }

                Section {
                    TextField("TMDB API Key (v3)", text: $draftTMDBKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("TMDB Metadata (Optional)")
                } footer: {
                    Text("Enter a free TMDB v3 API key to fetch show posters, 16:9 episode stills, titles, and overviews. Leave blank to use Google Drive thumbnails.")
                }

                Section("Account & Cache") {
                    Button {
                        TMDBService.shared.clearCache()
                        clearedCache = true
                    } label: {
                        Text(clearedCache ? "TMDB Cache Cleared" : "Clear TMDB Metadata Cache")
                    }

                    Button(role: .destructive) {
                        authManager.signOut()
                        dismiss()
                    } label: {
                        Text("Sign Out of Google")
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let trimmedFolder = draftFolderID.trimmingCharacters(in: .whitespacesAndNewlines)
                        let trimmedKey = draftTMDBKey.trimmingCharacters(in: .whitespacesAndNewlines)
                        let changed = trimmedFolder != folderID || trimmedKey != tmdbAPIKey || clearedCache
                        folderID = trimmedFolder
                        tmdbAPIKey = trimmedKey
                        dismiss()
                        if changed {
                            onSaveOrChange()
                        }
                    }
                }
            }
            .onAppear {
                draftFolderID = folderID
                draftTMDBKey = tmdbAPIKey
            }
        }
    }
}

// MARK: - Shared Thumbnail Loader

private struct RemoteThumbnailView: View {
    let url: URL?
    let fallbackSymbol: String

    var body: some View {
        if let url {
            AsyncImage(url: url) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .scaledToFill()
                case .failure:
                    placeholder
                case .empty:
                    ZStack {
                        placeholder
                        ProgressView()
                            .controlSize(.small)
                    }
                @unknown default:
                    placeholder
                }
            }
        } else {
            placeholder
        }
    }

    private var placeholder: some View {
        ZStack {
            Color.white.opacity(0.08)
            Image(systemName: fallbackSymbol)
                .font(.title2)
                .foregroundStyle(.secondary)
        }
    }
}
