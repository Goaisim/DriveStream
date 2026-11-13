import AVFoundation
import AVKit
import MobileVLCKit
import SwiftUI
import UIKit

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
    let fileId: String
    let title: String
    let engine: PlaybackEngine

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        switch engine {
        case .vlc:
            VLCPlayerContainerView(fileId: fileId, title: title) {
                dismiss()
            }
        case .avPlayer(let asset):
            ZStack(alignment: .topLeading) {
                Color.black.ignoresSafeArea()
                AVPlayerViewControllerWrapper(fileId: fileId, asset: asset)
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

// MARK: - MobileVLCKit ViewModel & Player

private struct VLCTrackOption: Identifiable, Hashable {
    let id: Int32
    let name: String
}

@MainActor
private final class VLCPlayerViewModel: NSObject, ObservableObject, VLCMediaPlayerDelegate {
    let player = VLCMediaPlayer()
    let videoSurfaceView: UIView = {
        let view = UIView()
        view.backgroundColor = .black
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        return view
    }()

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

    private let fileId: String
    private var didStartPlayback = false
    private var didAttemptResume = false
    private var didStop = false
    private var lastSavedAt: Date = .distantPast

    init(fileId: String) {
        self.fileId = fileId
        super.init()
        player.drawable = videoSurfaceView
        player.delegate = self
    }

    func startPlayback() async {
        guard !didStartPlayback else { return }
        didStartPlayback = true

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
        guard let proxyURL = LocalStreamProxy.shared.streamURL(for: fileId, port: port) else {
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

    func togglePlayPause() {
        if player.isPlaying {
            player.pause()
        } else {
            player.play()
        }
        isPlaying = player.isPlaying
    }

    func skip(seconds: Int32) {
        if seconds >= 0 {
            player.jumpForward(seconds)
        } else {
            player.jumpBackward(-seconds)
        }
        syncTimeState()
    }

    func seek(to seconds: TimeInterval) {
        guard seconds.isFinite, seconds >= 0 else { return }
        let clampedMs = Int32(min(seconds * 1000.0, Double(Int32.max - 1)))
        player.time = VLCTime(int: clampedMs)
        currentTime = seconds
    }

    func selectAudioTrack(_ index: Int32) {
        player.currentAudioTrackIndex = index
        currentAudioTrackIndex = index
    }

    func selectSubtitleTrack(_ index: Int32) {
        player.currentVideoSubTitleIndex = index
        currentSubtitleTrackIndex = index
    }

    func refreshTracks() {
        let aNames = player.audioTrackNames.compactMap { $0 as? String }
        let aIndexes = player.audioTrackIndexes.compactMap { ($0 as? NSNumber)?.int32Value }
        audioTracks = zip(aIndexes, aNames).map { VLCTrackOption(id: $0.0, name: $0.1) }
        currentAudioTrackIndex = player.currentAudioTrackIndex

        let sNames = player.videoSubTitlesNames.compactMap { $0 as? String }
        let sIndexes = player.videoSubTitlesIndexes.compactMap { ($0 as? NSNumber)?.int32Value }
        subtitleTracks = zip(sIndexes, sNames).map { VLCTrackOption(id: $0.0, name: $0.1) }
        currentSubtitleTrackIndex = player.currentVideoSubTitleIndex
    }

    func stopAndSave() {
        guard !didStop else { return }
        didStop = true
        saveProgress(force: true)
        player.delegate = nil
        player.stop()
        player.drawable = nil
    }

    private func syncTimeState() {
        let timeMs = max(player.time.intValue, 0)
        currentTime = Double(timeMs) / 1000.0

        let lengthMs = player.media?.length.intValue ?? 0
        if lengthMs > 0 {
            duration = Double(lengthMs) / 1000.0
        }
    }

    private func attemptResumeIfNeeded() {
        guard !didAttemptResume, duration > 0 else { return }
        didAttemptResume = true
        if let saved = PlaybackStore.shared.progress(for: fileId),
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
        PlaybackStore.shared.save(fileId: fileId, position: currentTime, duration: duration)
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
                self.currentTime = Double(max(self.player.time.intValue, 0)) / 1000.0
                self.duration = Double(max(self.player.media?.length.intValue ?? 0, 0)) / 1000.0
            }
            if self.audioTracks.isEmpty {
                self.refreshTracks()
            }
            self.attemptResumeIfNeeded()
            self.saveProgress(force: false)
        }
    }
}

private struct VLCVideoSurfaceView: UIViewRepresentable {
    let surfaceView: UIView

    func makeUIView(context: Context) -> UIView {
        surfaceView
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

private struct VLCPlayerContainerView: View {
    let fileId: String
    let title: String
    let onClose: () -> Void

    @StateObject private var viewModel: VLCPlayerViewModel
    @ObservedObject private var proxy = LocalStreamProxy.shared

    @State private var showControls = true
    @State private var isScrubbing = false
    @State private var scrubPosition: TimeInterval = 0
    @State private var isLandscape = false
    @State private var hideTask: Task<Void, Never>?

    init(fileId: String, title: String, onClose: @escaping () -> Void) {
        self.fileId = fileId
        self.title = title
        self.onClose = onClose
        _viewModel = StateObject(wrappedValue: VLCPlayerViewModel(fileId: fileId))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VLCVideoSurfaceView(surfaceView: viewModel.videoSurfaceView)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        showControls.toggle()
                    }
                    scheduleAutoHide()
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
        }
        .statusBarHidden(true)
        .task {
            scheduleAutoHide()
            await viewModel.startPlayback()
        }
        .onDisappear {
            hideTask?.cancel()
            viewModel.stopAndSave()
            resetOrientation()
        }
    }

    private var controlsOverlay: some View {
        VStack {
            // Top bar
            HStack(spacing: 12) {
                Button(action: closePlayer) {
                    Image(systemName: "xmark")
                        .font(.headline)
                        .foregroundStyle(.white)
                        .padding(10)
                        .background(.black.opacity(0.55))
                        .clipShape(Circle())
                }

                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)

                Spacer()

                if !viewModel.audioTracks.isEmpty {
                    Menu {
                        ForEach(viewModel.audioTracks) { track in
                            Button {
                                viewModel.selectAudioTrack(track.id)
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

                if !viewModel.subtitleTracks.isEmpty {
                    Menu {
                        ForEach(viewModel.subtitleTracks) { track in
                            Button {
                                viewModel.selectSubtitleTrack(track.id)
                            } label: {
                                if track.id == viewModel.currentSubtitleTrackIndex {
                                    Label(track.name, systemImage: "checkmark")
                                } else {
                                    Text(track.name)
                                }
                            }
                        }
                    } label: {
                        Image(systemName: "captions.bubble.fill")
                            .font(.subheadline)
                            .foregroundStyle(.white)
                            .padding(10)
                            .background(.black.opacity(0.55))
                            .clipShape(Circle())
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
            HStack(spacing: 44) {
                Button {
                    viewModel.skip(seconds: -10)
                    scheduleAutoHide()
                } label: {
                    Image(systemName: "gobackward.10")
                        .font(.system(size: 30, weight: .medium))
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
                        .font(.system(size: 38, weight: .bold))
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
                        .font(.system(size: 30, weight: .medium))
                        .foregroundStyle(.white)
                        .padding(14)
                        .background(.black.opacity(0.45))
                        .clipShape(Circle())
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
                        if !editing {
                            viewModel.seek(to: scrubPosition)
                            scheduleAutoHide()
                        } else {
                            hideTask?.cancel()
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
        )
    }

    private func closePlayer() {
        viewModel.stopAndSave()
        resetOrientation()
        onClose()
    }

    private func scheduleAutoHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                if viewModel.isPlaying && !isScrubbing {
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
