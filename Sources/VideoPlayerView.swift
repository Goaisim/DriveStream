import AVFoundation
import AVKit
import MobileVLCKit
import SwiftUI
import UIKit

struct PlayerEpisode: Identifiable, Hashable {
    let id: String
    let title: String
    let shortTitle: String
    let subtitle: String?
    let episodeCode: String?
    let stillURL: URL?
    let prefersVLC: Bool
}

struct PlaybackProgress: Codable, Hashable {
    let fileId: String
    let position: TimeInterval
    let duration: TimeInterval
    let updatedAt: Date

    var fraction: Double {
        guard duration > 0 else { return 0 }
        return min(max(position / duration, 0), 1)
    }

    var isInProgress: Bool {
        position >= 5 && (duration <= 0 || fraction < 0.95)
    }
}

@MainActor
final class PlaybackStore: ObservableObject {
    static let shared = PlaybackStore()

    private let keyPrefix = "playback_progress_"
    private let indexKey = "playback_progress_ids"

    @Published private(set) var progressByFileId: [String: PlaybackProgress] = [:]

    private init() {
        loadAll()
    }

    func progress(for fileId: String) -> PlaybackProgress? {
        progressByFileId[fileId]
    }

    func save(fileId: String, position: TimeInterval, duration: TimeInterval) {
        guard position.isFinite, position >= 0 else { return }
        let safeDuration = duration.isFinite && duration > 0 ? duration : 0
        let entry = PlaybackProgress(
            fileId: fileId,
            position: position,
            duration: safeDuration,
            updatedAt: Date()
        )
        progressByFileId[fileId] = entry

        if let data = try? JSONEncoder().encode(entry) {
            UserDefaults.standard.set(data, forKey: keyPrefix + fileId)
        }
        var ids = Set(UserDefaults.standard.stringArray(forKey: indexKey) ?? [])
        ids.insert(fileId)
        UserDefaults.standard.set(Array(ids), forKey: indexKey)
    }

    func clearProgress(for fileId: String) {
        progressByFileId.removeValue(forKey: fileId)
        UserDefaults.standard.removeObject(forKey: keyPrefix + fileId)
        var ids = Set(UserDefaults.standard.stringArray(forKey: indexKey) ?? [])
        ids.remove(fileId)
        UserDefaults.standard.set(Array(ids), forKey: indexKey)
    }

    private func loadAll() {
        let ids = UserDefaults.standard.stringArray(forKey: indexKey) ?? []
        var loaded: [String: PlaybackProgress] = [:]
        for id in ids {
            if let data = UserDefaults.standard.data(forKey: keyPrefix + id),
               let entry = try? JSONDecoder().decode(PlaybackProgress.self, from: data) {
                loaded[id] = entry
            }
        }
        progressByFileId = loaded
    }
}

enum PlaybackEngine {
    case vlc
    case avPlayer(AVURLAsset)
}

struct VideoPlayerView: View {
    let episodes: [PlayerEpisode]
    let initialEpisode: PlayerEpisode
    let engine: PlaybackEngine

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        switch engine {
        case .vlc:
            VLCPlayerContainerView(episodes: episodes, initialEpisode: initialEpisode) {
                dismiss()
            }
        case .avPlayer(let asset):
            ZStack(alignment: .topLeading) {
                Color.black.ignoresSafeArea()
                AVPlayerViewControllerWrapper(fileId: initialEpisode.id, asset: asset)
                    .ignoresSafeArea()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 28))
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, .black.opacity(0.6))
                        .padding(16)
                }
                .accessibilityLabel("Close player")
            }
            .statusBarHidden(true)
        }
    }
}

// MARK: - Non-Interactive Video Surface

private final class NonInteractiveVideoSurfaceView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    override func didAddSubview(_ subview: UIView) {
        super.didAddSubview(subview)
        subview.isUserInteractionEnabled = false
    }

    private func configure() {
        isUserInteractionEnabled = false
        backgroundColor = .black
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
    }
}

// MARK: - MobileVLCKit ViewModel & Player

private struct VLCTrackOption: Identifiable, Hashable {
    let id: Int32
    let name: String
}

@MainActor
private final class VLCPlayerViewModel: NSObject, ObservableObject, VLCMediaPlayerDelegate {
    let player = VLCMediaPlayer()
    let videoSurfaceView = NonInteractiveVideoSurfaceView()

    @Published var episodes: [PlayerEpisode]
    @Published var currentEpisode: PlayerEpisode
    @Published var isLoading = true
    @Published var isPlaying = false
    @Published var currentTime: TimeInterval = 0
    @Published var duration: TimeInterval = 0
    @Published var audioTracks: [VLCTrackOption] = []
    @Published var subtitleTracks: [VLCTrackOption] = []
    @Published var currentAudioTrackIndex: Int32 = -1
    @Published var currentSubtitleTrackIndex: Int32 = -1
    @Published var vlcStateString = "Initializing…"
    @Published var errorMessage: String?

    private var didStartPlayback = false
    private var didAttemptResume = false
    private var didApplyInitialSubtitleSetting = false
    private var userToggledSubtitles = false
    private var didStop = false
    private var lastSavedAt: Date = .distantPast
    private var seekIgnoreUntil: Date = .distantPast

    var currentEpisodeIndex: Int? {
        episodes.firstIndex(where: { $0.id == currentEpisode.id })
    }

    var hasPreviousEpisode: Bool {
        guard let idx = currentEpisodeIndex else { return false }
        return idx > 0
    }

    var hasNextEpisode: Bool {
        guard let idx = currentEpisodeIndex else { return false }
        return idx + 1 < episodes.count
    }

    var selectableSubtitles: [VLCTrackOption] {
        subtitleTracks.filter { $0.id != -1 }
    }

    var hasActiveSubtitles: Bool {
        currentSubtitleTrackIndex != -1
    }

    init(episodes: [PlayerEpisode], initialEpisode: PlayerEpisode) {
        self.episodes = episodes.isEmpty ? [initialEpisode] : episodes
        self.currentEpisode = initialEpisode
        super.init()
        player.drawable = videoSurfaceView
        player.delegate = self
    }

    func startPlayback() async {
        guard !didStartPlayback else { return }
        didStartPlayback = true
        didStop = false

        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            // Proceed if audio session setup fails on simulator
        }

        vlcStateString = "Refreshing Google auth…"
        do {
            _ = try await AuthManager.shared.validAccessToken()
        } catch {
            isLoading = false
            errorMessage = error.localizedDescription
            return
        }

        vlcStateString = "Starting proxy :8765…"
        let port = await LocalStreamProxy.shared.ensureReady()
        guard let proxyURL = LocalStreamProxy.shared.streamURL(for: currentEpisode.id, port: port) else {
            isLoading = false
            errorMessage = "Could not construct stream URL."
            return
        }

        let media = VLCMedia(url: proxyURL)
        media.addOptions(["network-caching": 1500])
        player.drawable = videoSurfaceView
        player.delegate = self
        player.media = media
        vlcStateString = "Opening stream…"
        player.play()
    }

    func switchToPreviousEpisode() {
        guard let idx = currentEpisodeIndex, idx > 0 else { return }
        switchToEpisode(episodes[idx - 1])
    }

    func switchToNextEpisode() {
        guard let idx = currentEpisodeIndex, idx + 1 < episodes.count else { return }
        switchToEpisode(episodes[idx + 1])
    }

    func switchToEpisode(_ episode: PlayerEpisode) {
        guard episode.id != currentEpisode.id else { return }

        saveProgress(force: true)
        player.delegate = nil
        player.stop()

        currentEpisode = episode
        didStartPlayback = false
        didAttemptResume = false
        didApplyInitialSubtitleSetting = false
        userToggledSubtitles = false
        isLoading = true
        isPlaying = false
        currentTime = 0
        duration = 0
        audioTracks = []
        subtitleTracks = []
        currentAudioTrackIndex = -1
        currentSubtitleTrackIndex = -1
        errorMessage = nil
        seekIgnoreUntil = .distantPast
        vlcStateString = "Switching episode…"

        Task { [weak self] in
            await self?.startPlayback()
        }
    }

    func togglePlayPause() {
        if player.isPlaying {
            player.pause()
        } else {
            player.play()
        }
        isPlaying = player.isPlaying
    }

    func skip(seconds: Double) {
        seek(to: currentTime + seconds)
    }

    func seek(to targetSeconds: TimeInterval) {
        guard targetSeconds.isFinite else { return }
        let upperBound = duration > 0 ? duration : max(targetSeconds, currentTime)
        let target = min(max(targetSeconds, 0), upperBound)

        seekIgnoreUntil = Date().addingTimeInterval(1.2)
        currentTime = target

        let targetMs = Int32(min(target * 1000.0, Double(Int32.max - 1)))
        if duration > 0 {
            player.position = Float(target / duration)
        }
        player.time = VLCTime(int: targetMs)
    }

    func selectAudioTrack(_ index: Int32) {
        player.currentAudioTrackIndex = index
        currentAudioTrackIndex = index
    }

    func selectSubtitleTrack(_ index: Int32) {
        userToggledSubtitles = true
        player.currentVideoSubTitleIndex = index
        currentSubtitleTrackIndex = index
    }

    func disableSubtitles() {
        userToggledSubtitles = true
        player.currentVideoSubTitleIndex = -1
        currentSubtitleTrackIndex = -1
    }

    func refreshTracks() {
        let aNames = player.audioTrackNames.compactMap { $0 as? String }
        let aIndexes = player.audioTrackIndexes.compactMap { ($0 as? NSNumber)?.int32Value }
        audioTracks = zip(aIndexes, aNames).map { VLCTrackOption(id: $0.0, name: $0.1) }
        currentAudioTrackIndex = player.currentAudioTrackIndex

        let sNames = player.videoSubTitlesNames.compactMap { $0 as? String }
        let sIndexes = player.videoSubTitlesIndexes.compactMap { ($0 as? NSNumber)?.int32Value }
        subtitleTracks = zip(sIndexes, sNames).map { VLCTrackOption(id: $0.0, name: $0.1) }

        if !userToggledSubtitles {
            if player.currentVideoSubTitleIndex != -1 {
                player.currentVideoSubTitleIndex = -1
            }
            currentSubtitleTrackIndex = -1
        } else {
            currentSubtitleTrackIndex = player.currentVideoSubTitleIndex
        }
    }

    func stopAndSave() {
        guard !didStop else { return }
        didStop = true
        saveProgress(force: true)
        player.delegate = nil
        player.stop()
        player.drawable = nil
    }

    private func applyInitialSubtitleOffIfNeeded() {
        guard !didApplyInitialSubtitleSetting, !userToggledSubtitles else { return }
        didApplyInitialSubtitleSetting = true
        player.currentVideoSubTitleIndex = -1
        currentSubtitleTrackIndex = -1
    }

    private func syncTimeState() {
        if Date() >= seekIgnoreUntil {
            let timeMs = max(player.time.intValue, 0)
            currentTime = Double(timeMs) / 1000.0
        }

        let lengthMs = player.media?.length.intValue ?? 0
        if lengthMs > 0 {
            duration = Double(lengthMs) / 1000.0
        }
    }

    private func attemptResumeIfNeeded() {
        guard !didAttemptResume, duration > 0 else { return }
        didAttemptResume = true
        if let saved = PlaybackStore.shared.progress(for: currentEpisode.id),
           saved.position > 1,
           saved.position / duration < 0.95 {
            seek(to: saved.position)
        }
    }

    private func saveProgress(force: Bool) {
        guard currentTime > 0 || force else { return }
        let now = Date()
        if !force && now.timeIntervalSince(lastSavedAt) < 5 {
            return
        }
        lastSavedAt = now
        PlaybackStore.shared.save(fileId: currentEpisode.id, position: currentTime, duration: duration)
    }

    private func stateLabel(for state: VLCMediaPlayerState) -> String {
        switch state {
        case .stopped: return "VLC: Stopped"
        case .opening: return "VLC: Opening"
        case .buffering: return "VLC: Buffering"
        case .ended: return "VLC: Ended"
        case .error: return "VLC: Error"
        case .playing: return "VLC: Playing"
        case .paused: return "VLC: Paused"
        @unknown default: return "VLC: Unknown"
        }
    }

    // MARK: - VLCMediaPlayerDelegate

    nonisolated func mediaPlayerStateChanged(_ aNotification: Notification) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            let state = self.player.state
            self.vlcStateString = self.stateLabel(for: state)
            self.isPlaying = self.player.isPlaying

            if state == .playing || state == .paused {
                self.isLoading = false
                self.applyInitialSubtitleOffIfNeeded()
                self.syncTimeState()
                self.refreshTracks()
                self.attemptResumeIfNeeded()
            } else if state == .error {
                self.isLoading = false
                self.errorMessage = "MobileVLCKit could not open stream (\(LocalStreamProxy.shared.lastStatus))."
            } else if state == .ended {
                self.isLoading = false
                self.saveProgress(force: true)
            }
        }
    }

    nonisolated func mediaPlayerTimeChanged(_ aNotification: Notification) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            if self.player.isPlaying || self.player.time.intValue > 0 {
                self.isLoading = false
                self.isPlaying = true
                self.applyInitialSubtitleOffIfNeeded()
                self.syncTimeState()
            }
            if self.audioTracks.isEmpty || self.subtitleTracks.isEmpty {
                self.refreshTracks()
            }
            self.attemptResumeIfNeeded()
            self.saveProgress(force: false)
        }
    }
}

private struct VLCVideoSurfaceView: UIViewRepresentable {
    let surfaceView: NonInteractiveVideoSurfaceView

    func makeUIView(context: Context) -> NonInteractiveVideoSurfaceView {
        surfaceView
    }

    func updateUIView(_ uiView: NonInteractiveVideoSurfaceView, context: Context) {}
}

private struct VLCPlayerContainerView: View {
    let onClose: () -> Void

    @StateObject private var viewModel: VLCPlayerViewModel
    @ObservedObject private var proxy = LocalStreamProxy.shared

    @State private var showControls = true
    @State private var isScrubbing = false
    @State private var scrubPosition: TimeInterval = 0
    @State private var isLandscape = false
    @State private var showEpisodeDrawer = false
    @State private var showSubtitleDialog = false
    @State private var doubleTapFeedback: String?
    @State private var hideTask: Task<Void, Never>?

    init(episodes: [PlayerEpisode], initialEpisode: PlayerEpisode, onClose: @escaping () -> Void) {
        self.onClose = onClose
        _viewModel = StateObject(wrappedValue: VLCPlayerViewModel(episodes: episodes, initialEpisode: initialEpisode))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VLCVideoSurfaceView(surfaceView: viewModel.videoSurfaceView)
                .ignoresSafeArea()

            // Full-screen transparent touch layer above VLC surface and below controls
            HStack(spacing: 0) {
                Color.white.opacity(0.001)
                    .contentShape(Rectangle())
                    .onTapGesture(count: 2) {
                        performDoubleTapSkip(-10)
                    }
                    .onTapGesture(count: 1) {
                        toggleControlsVisibility()
                    }

                Color.white.opacity(0.001)
                    .contentShape(Rectangle())
                    .onTapGesture(count: 2) {
                        performDoubleTapSkip(10)
                    }
                    .onTapGesture(count: 1) {
                        toggleControlsVisibility()
                    }
            }
            .ignoresSafeArea()

            if let doubleTapFeedback {
                Text(doubleTapFeedback)
                    .font(.headline.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                    .background(.black.opacity(0.75))
                    .clipShape(Capsule())
                    .allowsHitTesting(false)
            }

            if viewModel.isLoading && viewModel.errorMessage == nil {
                VStack(spacing: 12) {
                    ProgressView()
                        .scaleEffect(1.3)
                        .tint(.white)
                    Text(viewModel.vlcStateString)
                        .font(.caption.monospaced())
                        .foregroundStyle(.white)
                    Text(proxy.lastStatus)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .background(.black.opacity(0.7))
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .padding(.horizontal, 24)
                .allowsHitTesting(false)
            }

            if let error = viewModel.errorMessage {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.largeTitle)
                        .foregroundStyle(.yellow)
                    Text(error)
                        .font(.subheadline)
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                    Button("Close", action: closePlayer)
                        .buttonStyle(.borderedProminent)
                }
                .padding(24)
                .background(.ultraThinMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .padding(.horizontal, 24)
            }

            if showControls {
                controlsOverlay
                    .transition(.opacity)
            }

            if showEpisodeDrawer {
                episodeDrawerOverlay
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .statusBarHidden(true)
        .confirmationDialog("Subtitles", isPresented: $showSubtitleDialog, titleVisibility: .visible) {
            Button("Off") {
                viewModel.disableSubtitles()
                scheduleAutoHide()
            }
            ForEach(viewModel.selectableSubtitles) { track in
                Button(track.id == viewModel.currentSubtitleTrackIndex ? "✓ \(track.name)" : track.name) {
                    viewModel.selectSubtitleTrack(track.id)
                    scheduleAutoHide()
                }
            }
            Button("Cancel", role: .cancel) {
                scheduleAutoHide()
            }
        }
        .onChange(of: showEpisodeDrawer) { _, isOpen in
            if isOpen {
                cancelAutoHide()
            } else {
                scheduleAutoHide()
            }
        }
        .onChange(of: showSubtitleDialog) { _, isOpen in
            if isOpen {
                cancelAutoHide()
            } else {
                scheduleAutoHide()
            }
        }
        .task {
            scheduleAutoHide()
            await viewModel.startPlayback()
        }
        .onDisappear {
            cancelAutoHide()
            viewModel.stopAndSave()
            resetOrientation()
        }
    }

    private var controlsOverlay: some View {
        VStack {
            // Top bar
            HStack(spacing: 10) {
                Button(action: closePlayer) {
                    Image(systemName: "xmark")
                        .font(.headline)
                        .foregroundStyle(.white)
                        .padding(10)
                        .background(.black.opacity(0.55))
                        .clipShape(Circle())
                }

                Text(viewModel.currentEpisode.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)

                Spacer()

                if viewModel.episodes.count > 1 {
                    Button {
                        withAnimation(.easeInOut(duration: 0.22)) {
                            showEpisodeDrawer.toggle()
                        }
                    } label: {
                        Image(systemName: "list.and.film")
                            .font(.subheadline)
                            .foregroundStyle(.white)
                            .padding(10)
                            .background(showEpisodeDrawer ? Color.blue : .black.opacity(0.55))
                            .clipShape(Circle())
                    }
                    .accessibilityLabel("Episodes")
                }

                if !viewModel.audioTracks.isEmpty {
                    Menu {
                        ForEach(viewModel.audioTracks) { track in
                            Button {
                                viewModel.selectAudioTrack(track.id)
                                scheduleAutoHide()
                            } label: {
                                if track.id == viewModel.currentAudioTrackIndex {
                                    Label(track.name, systemImage: "checkmark")
                                } else {
                                    Text(track.name)
                                }
                            }
                        }
                    } label: {
                        Image(systemName: "speaker.wave.2.fill")
                            .font(.subheadline)
                            .foregroundStyle(.white)
                            .padding(10)
                            .background(.black.opacity(0.55))
                            .clipShape(Circle())
                    }
                }

                if !viewModel.selectableSubtitles.isEmpty {
                    Button {
                        handleCCTap()
                    } label: {
                        Image(systemName: viewModel.hasActiveSubtitles ? "captions.bubble.fill" : "captions.bubble")
                            .font(.subheadline)
                            .foregroundStyle(.white)
                            .padding(10)
                            .background(viewModel.hasActiveSubtitles ? Color.blue : .black.opacity(0.55))
                            .clipShape(Circle())
                    }
                    .contextMenu {
                        Button("Off") {
                            viewModel.disableSubtitles()
                            scheduleAutoHide()
                        }
                        ForEach(viewModel.selectableSubtitles) { track in
                            Button {
                                viewModel.selectSubtitleTrack(track.id)
                                scheduleAutoHide()
                            } label: {
                                if track.id == viewModel.currentSubtitleTrackIndex {
                                    Label(track.name, systemImage: "checkmark")
                                } else {
                                    Text(track.name)
                                }
                            }
                        }
                    }
                }

                Button(action: toggleLandscape) {
                    Image(systemName: isLandscape ? "rectangle.portrait.rotate" : "rectangle.landscape.rotate")
                        .font(.subheadline)
                        .foregroundStyle(.white)
                        .padding(10)
                        .background(.black.opacity(0.55))
                        .clipShape(Circle())
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)

            Spacer()

            // Center playback controls
            HStack(spacing: 26) {
                if viewModel.episodes.count > 1 {
                    Button {
                        viewModel.switchToPreviousEpisode()
                        scheduleAutoHide()
                    } label: {
                        Image(systemName: "backward.end.fill")
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(viewModel.hasPreviousEpisode ? .white : .white.opacity(0.35))
                            .padding(12)
                            .background(.black.opacity(0.45))
                            .clipShape(Circle())
                    }
                    .disabled(!viewModel.hasPreviousEpisode)
                }

                Button {
                    viewModel.skip(seconds: -10)
                    scheduleAutoHide()
                } label: {
                    Image(systemName: "gobackward.10")
                        .font(.system(size: 28, weight: .medium))
                        .foregroundStyle(.white)
                        .padding(14)
                        .background(.black.opacity(0.45))
                        .clipShape(Circle())
                }

                Button {
                    viewModel.togglePlayPause()
                    scheduleAutoHide()
                } label: {
                    Image(systemName: viewModel.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 36, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(20)
                        .background(.black.opacity(0.55))
                        .clipShape(Circle())
                }

                Button {
                    viewModel.skip(seconds: 10)
                    scheduleAutoHide()
                } label: {
                    Image(systemName: "goforward.10")
                        .font(.system(size: 28, weight: .medium))
                        .foregroundStyle(.white)
                        .padding(14)
                        .background(.black.opacity(0.45))
                        .clipShape(Circle())
                }

                if viewModel.episodes.count > 1 {
                    Button {
                        viewModel.switchToNextEpisode()
                        scheduleAutoHide()
                    } label: {
                        Image(systemName: "forward.end.fill")
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(viewModel.hasNextEpisode ? .white : .white.opacity(0.35))
                            .padding(12)
                            .background(.black.opacity(0.45))
                            .clipShape(Circle())
                    }
                    .disabled(!viewModel.hasNextEpisode)
                }
            }

            Spacer()

            // Bottom scrubber & timestamps
            VStack(spacing: 6) {
                Slider(
                    value: Binding(
                        get: { isScrubbing ? scrubPosition : viewModel.currentTime },
                        set: { newValue in
                            scrubPosition = newValue
                        }
                    ),
                    in: 0...max(viewModel.duration, 1),
                    onEditingChanged: { editing in
                        isScrubbing = editing
                        if editing {
                            scrubPosition = viewModel.currentTime
                            cancelAutoHide()
                        } else {
                            viewModel.seek(to: scrubPosition)
                            scheduleAutoHide()
                        }
                    }
                )
                .tint(.blue)

                HStack {
                    Text(formatTimestamp(isScrubbing ? scrubPosition : viewModel.currentTime))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.white)
                    Spacer()
                    Text(formatTimestamp(viewModel.duration))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 18)
            .background(
                LinearGradient(
                    colors: [.clear, .black.opacity(0.75)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
        }
        .background(
            LinearGradient(
                colors: [.black.opacity(0.65), .clear, .clear],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            .allowsHitTesting(false)
        )
    }

    private var episodeDrawerOverlay: some View {
        ZStack(alignment: .trailing) {
            Color.black.opacity(0.45)
                .ignoresSafeArea()
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        showEpisodeDrawer = false
                    }
                }

            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("Episodes")
                        .font(.headline)
                        .foregroundStyle(.white)
                    Spacer()
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            showEpisodeDrawer = false
                        }
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(16)

                Divider()

                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(viewModel.episodes) { ep in
                                let isCurrent = ep.id == viewModel.currentEpisode.id
                                Button {
                                    withAnimation(.easeInOut(duration: 0.2)) {
                                        showEpisodeDrawer = false
                                    }
                                    viewModel.switchToEpisode(ep)
                                } label: {
                                    HStack(spacing: 10) {
                                        Image(systemName: isCurrent ? "speaker.wave.2.fill" : "play.circle")
                                            .foregroundStyle(isCurrent ? .blue : .secondary)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(ep.shortTitle)
                                                .font(.subheadline.weight(isCurrent ? .bold : .medium))
                                                .foregroundStyle(.white)
                                                .lineLimit(2)
                                                .multilineTextAlignment(.leading)
                                            if let sub = ep.subtitle, !sub.isEmpty {
                                                Text(sub)
                                                    .font(.caption2)
                                                    .foregroundStyle(.secondary)
                                                    .lineLimit(1)
                                            }
                                        }
                                        Spacer()
                                    }
                                    .padding(10)
                                    .background(isCurrent ? Color.blue.opacity(0.22) : Color.white.opacity(0.06))
                                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                }
                                .id(ep.id)
                            }
                        }
                        .padding(12)
                    }
                    .onAppear {
                        proxy.scrollTo(viewModel.currentEpisode.id, anchor: .center)
                    }
                }
            }
            .frame(width: 300)
            .background(Color(red: 0.08, green: 0.08, blue: 0.12).opacity(0.96))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .padding(.vertical, 12)
            .padding(.trailing, 12)
        }
    }

    private func handleCCTap() {
        let tracks = viewModel.selectableSubtitles
        if viewModel.hasActiveSubtitles {
            viewModel.disableSubtitles()
            scheduleAutoHide()
        } else if tracks.count == 1 {
            viewModel.selectSubtitleTrack(tracks[0].id)
            scheduleAutoHide()
        } else if tracks.count > 1 {
            showSubtitleDialog = true
        }
    }

    private func toggleControlsVisibility() {
        withAnimation(.easeInOut(duration: 0.2)) {
            showControls.toggle()
        }
        if showControls {
            scheduleAutoHide()
        } else {
            cancelAutoHide()
        }
    }

    private func performDoubleTapSkip(_ delta: Double) {
        viewModel.skip(seconds: delta)
        let label = delta < 0 ? "-10s" : "+10s"
        withAnimation(.easeOut(duration: 0.15)) {
            doubleTapFeedback = label
        }
        if showControls {
            scheduleAutoHide()
        }
        Task {
            try? await Task.sleep(nanoseconds: 700_000_000)
            await MainActor.run {
                if doubleTapFeedback == label {
                    withAnimation(.easeIn(duration: 0.2)) {
                        doubleTapFeedback = nil
                    }
                }
            }
        }
    }

    private func closePlayer() {
        cancelAutoHide()
        viewModel.stopAndSave()
        resetOrientation()
        onClose()
    }

    private func cancelAutoHide() {
        hideTask?.cancel()
        hideTask = nil
    }

    private func scheduleAutoHide() {
        cancelAutoHide()
        guard !isScrubbing && !showEpisodeDrawer && !showSubtitleDialog else { return }
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                if viewModel.isPlaying && !isScrubbing && !showEpisodeDrawer && !showSubtitleDialog {
                    withAnimation(.easeInOut(duration: 0.25)) {
                        showControls = false
                    }
                }
            }
        }
    }

    private func toggleLandscape() {
        isLandscape.toggle()
        let mask: UIInterfaceOrientationMask = isLandscape ? .landscape : .portrait
        AppDelegate.orientationLock = mask
        requestGeometry(mask)
        scheduleAutoHide()
    }

    private func resetOrientation() {
        AppDelegate.orientationLock = .allButUpsideDown
        requestGeometry(.portrait)
    }

    private func requestGeometry(_ mask: UIInterfaceOrientationMask) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            return
        }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask))
        scene.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }

    private func formatTimestamp(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds)
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}

// MARK: - AVPlayer Fallback (MP4 / M4V / MOV)

private struct AVPlayerViewControllerWrapper: UIViewControllerRepresentable {
    let fileId: String
    let asset: AVURLAsset

    func makeCoordinator() -> Coordinator {
        Coordinator(fileId: fileId)
    }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            // Proceed if audio session setup fails on simulator
        }

        let playerItem = AVPlayerItem(asset: asset)
        let player = AVPlayer(playerItem: playerItem)
        player.allowsExternalPlayback = true

        let controller = AVPlayerViewController()
        controller.player = player
        controller.allowsPictureInPicturePlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        controller.showsPlaybackControls = true

        context.coordinator.configure(player: player, item: playerItem)
        player.play()
        return controller
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: AVPlayerViewController, coordinator: Coordinator) {
        MainActor.assumeIsolated {
            coordinator.teardown()
        }
        uiViewController.player?.pause()
        uiViewController.player = nil
    }

    @MainActor
    final class Coordinator {
        private let fileId: String
        private weak var player: AVPlayer?
        private var timeObserverToken: Any?
        private var statusObservation: NSKeyValueObservation?
        private var didSeekToSavedPosition = false

        nonisolated init(fileId: String) {
            self.fileId = fileId
        }

        func configure(player: AVPlayer, item: AVPlayerItem) {
            self.player = player

            if let saved = PlaybackStore.shared.progress(for: fileId),
               saved.position > 1,
               (saved.duration <= 0 || saved.fraction < 0.95) {
                let targetTime = CMTime(seconds: saved.position, preferredTimescale: 600)
                statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self, weak player] observedItem, _ in
                    guard let self, let player else { return }
                    Task { @MainActor in
                        guard !self.didSeekToSavedPosition, observedItem.status == .readyToPlay else { return }
                        self.didSeekToSavedPosition = true
                        _ = await player.seek(to: targetTime, toleranceBefore: .zero, toleranceAfter: .zero)
                    }
                }
            }

            let interval = CMTime(seconds: 5, preferredTimescale: 1)
            timeObserverToken = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] _ in
                Task { @MainActor in
                    self?.persistCurrentTime()
                }
            }
        }

        func persistCurrentTime() {
            guard let player, let item = player.currentItem else { return }
            let current = player.currentTime().seconds
            let duration = item.duration.seconds
            PlaybackStore.shared.save(
                fileId: fileId,
                position: current.isFinite ? current : 0,
                duration: duration.isFinite ? duration : 0
            )
        }

        func teardown() {
            persistCurrentTime()
            statusObservation?.invalidate()
            statusObservation = nil
            if let token = timeObserverToken, let player {
                player.removeTimeObserver(token)
            }
            timeObserverToken = nil
        }
    }
}
