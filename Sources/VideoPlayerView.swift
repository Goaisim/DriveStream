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
    case vlc(URL)
    case avPlayer(AVURLAsset)
}

struct VideoPlayerView: View {
    let fileId: String
    let title: String
    let engine: PlaybackEngine

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        switch engine {
        case .vlc(let streamURL):
            VLCPlayerContainerView(fileId: fileId, title: title, streamURL: streamURL) {
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

// MARK: - MobileVLCKit Player

private struct VLCTrackOption: Identifiable, Hashable {
    let id: Int32
    let name: String
}

@MainActor
private final class VLCPlayerController: NSObject, ObservableObject, VLCMediaPlayerDelegate {
    let mediaPlayer = VLCMediaPlayer()

    @Published var isPlaying = false
    @Published var isBuffering = true
    @Published var currentTime: TimeInterval = 0
    @Published var duration: TimeInterval = 0
    @Published var audioTracks: [VLCTrackOption] = []
    @Published var subtitleTracks: [VLCTrackOption] = []
    @Published var currentAudioTrackIndex: Int32 = -1
    @Published var currentSubtitleTrackIndex: Int32 = -1
    @Published var errorMessage: String?

    private let fileId: String
    private var didAttemptResume = false
    private var didStartPlayback = false
    private var didStop = false
    private var lastSavedAt: Date = .distantPast

    init(fileId: String) {
        self.fileId = fileId
        super.init()
        mediaPlayer.delegate = self
    }

    func start(url: URL, drawable: UIView) {
        guard !didStartPlayback else { return }
        didStartPlayback = true

        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            // Proceed if audio session setup fails on simulator
        }

        let media = VLCMedia(url: url)
        media.addOption(":network-caching=1500")
        mediaPlayer.drawable = drawable
        mediaPlayer.media = media
        mediaPlayer.play()
    }

    func togglePlayPause() {
        if mediaPlayer.isPlaying {
            mediaPlayer.pause()
        } else {
            mediaPlayer.play()
        }
        isPlaying = mediaPlayer.isPlaying
    }

    func skip(seconds: Int32) {
        if seconds >= 0 {
            mediaPlayer.jumpForward(seconds)
        } else {
            mediaPlayer.jumpBackward(-seconds)
        }
        syncTimeState()
    }

    func seek(to seconds: TimeInterval) {
        guard seconds.isFinite, seconds >= 0 else { return }
        let clampedMs = Int32(min(seconds * 1000.0, Double(Int32.max - 1)))
        mediaPlayer.time = VLCTime(int: clampedMs)
        currentTime = seconds
    }

    func selectAudioTrack(_ index: Int32) {
        mediaPlayer.currentAudioTrackIndex = index
        currentAudioTrackIndex = index
    }

    func selectSubtitleTrack(_ index: Int32) {
        mediaPlayer.currentVideoSubTitleIndex = index
        currentSubtitleTrackIndex = index
    }

    func refreshTracks() {
        let aNames = mediaPlayer.audioTrackNames.compactMap { $0 as? String }
        let aIndexes = mediaPlayer.audioTrackIndexes.compactMap { ($0 as? NSNumber)?.int32Value }
        audioTracks = zip(aIndexes, aNames).map { VLCTrackOption(id: $0.0, name: $0.1) }
        currentAudioTrackIndex = mediaPlayer.currentAudioTrackIndex

        let sNames = mediaPlayer.videoSubTitlesNames.compactMap { $0 as? String }
        let sIndexes = mediaPlayer.videoSubTitlesIndexes.compactMap { ($0 as? NSNumber)?.int32Value }
        subtitleTracks = zip(sIndexes, sNames).map { VLCTrackOption(id: $0.0, name: $0.1) }
        currentSubtitleTrackIndex = mediaPlayer.currentVideoSubTitleIndex
    }

    func stopAndSave() {
        guard !didStop else { return }
        didStop = true
        saveProgress(force: true)
        mediaPlayer.delegate = nil
        mediaPlayer.stop()
        mediaPlayer.drawable = nil
    }

    private func syncTimeState() {
        let currentMs = max(mediaPlayer.time.intValue, 0)
        currentTime = TimeInterval(currentMs) / 1000.0

        if let lengthMs = mediaPlayer.media?.length.intValue, lengthMs > 0 {
            duration = TimeInterval(lengthMs) / 1000.0
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

    // MARK: - VLCMediaPlayerDelegate

    nonisolated func mediaPlayerStateChanged(_ aNotification: Notification) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            let state = self.mediaPlayer.state
            self.isPlaying = self.mediaPlayer.isPlaying
            if state == .opening {
                self.isBuffering = true
            } else if state == .buffering {
                if !self.mediaPlayer.isPlaying && self.currentTime == 0 {
                    self.isBuffering = true
                }
            } else {
                self.isBuffering = false
            }

            if state == .playing || state == .paused {
                self.syncTimeState()
                self.refreshTracks()
                self.attemptResumeIfNeeded()
            } else if state == .error {
                self.errorMessage = "Unable to play stream via MobileVLCKit."
            } else if state == .ended {
                self.saveProgress(force: true)
            }
        }
    }

    nonisolated func mediaPlayerTimeChanged(_ aNotification: Notification) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.isPlaying = self.mediaPlayer.isPlaying
            self.isBuffering = false
            self.syncTimeState()
            if self.audioTracks.isEmpty {
                self.refreshTracks()
            }
            self.attemptResumeIfNeeded()
            self.saveProgress(force: false)
        }
    }
}

private final class VLCDrawableUIView: UIView {
    var onMountedInWindow: ((UIView) -> Void)?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            onMountedInWindow?(self)
        }
    }
}

private struct VLCVideoSurface: UIViewRepresentable {
    let controller: VLCPlayerController
    let streamURL: URL

    func makeUIView(context: Context) -> UIView {
        let view = VLCDrawableUIView()
        view.backgroundColor = .black
        view.onMountedInWindow = { [weak controller] mountedView in
            controller?.start(url: streamURL, drawable: mountedView)
        }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

private struct VLCPlayerContainerView: View {
    let fileId: String
    let title: String
    let streamURL: URL
    let onClose: () -> Void

    @StateObject private var controller: VLCPlayerController
    @State private var showControls = true
    @State private var isScrubbing = false
    @State private var scrubPosition: TimeInterval = 0
    @State private var isLandscape = false
    @State private var hideTask: Task<Void, Never>?

    init(fileId: String, title: String, streamURL: URL, onClose: @escaping () -> Void) {
        self.fileId = fileId
        self.title = title
        self.streamURL = streamURL
        self.onClose = onClose
        _controller = StateObject(wrappedValue: VLCPlayerController(fileId: fileId))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VLCVideoSurface(controller: controller, streamURL: streamURL)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        showControls.toggle()
                    }
                    scheduleAutoHide()
                }

            if controller.isBuffering && controller.errorMessage == nil {
                ProgressView()
                    .scaleEffect(1.3)
                    .tint(.white)
                    .padding(18)
                    .background(.black.opacity(0.55))
                    .clipShape(Circle())
            }

            if let error = controller.errorMessage {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.largeTitle)
                        .foregroundStyle(.yellow)
                    Text(error)
                        .font(.subheadline)
                        .foregroundStyle(.white)
                    Button("Close", action: closePlayer)
                        .buttonStyle(.borderedProminent)
                }
                .padding(24)
                .background(.ultraThinMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }

            if showControls {
                controlsOverlay
                    .transition(.opacity)
            }
        }
        .statusBarHidden(true)
        .onAppear {
            scheduleAutoHide()
        }
        .onDisappear {
            hideTask?.cancel()
            controller.stopAndSave()
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

                if !controller.audioTracks.isEmpty {
                    Menu {
                        ForEach(controller.audioTracks) { track in
                            Button {
                                controller.selectAudioTrack(track.id)
                            } label: {
                                if track.id == controller.currentAudioTrackIndex {
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

                if !controller.subtitleTracks.isEmpty {
                    Menu {
                        ForEach(controller.subtitleTracks) { track in
                            Button {
                                controller.selectSubtitleTrack(track.id)
                            } label: {
                                if track.id == controller.currentSubtitleTrackIndex {
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
                    controller.skip(seconds: -10)
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
                    controller.togglePlayPause()
                    scheduleAutoHide()
                } label: {
                    Image(systemName: controller.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 38, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(20)
                        .background(.black.opacity(0.55))
                        .clipShape(Circle())
                }

                Button {
                    controller.skip(seconds: 10)
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
                        get: { isScrubbing ? scrubPosition : controller.currentTime },
                        set: { newValue in
                            scrubPosition = newValue
                        }
                    ),
                    in: 0...max(controller.duration, 1),
                    onEditingChanged: { editing in
                        isScrubbing = editing
                        if !editing {
                            controller.seek(to: scrubPosition)
                            scheduleAutoHide()
                        } else {
                            hideTask?.cancel()
                        }
                    }
                )
                .tint(.blue)

                HStack {
                    Text(formatTimestamp(isScrubbing ? scrubPosition : controller.currentTime))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.white)
                    Spacer()
                    Text(formatTimestamp(controller.duration))
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
        controller.stopAndSave()
        resetOrientation()
        onClose()
    }

    private func scheduleAutoHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                if controller.isPlaying && !isScrubbing {
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
