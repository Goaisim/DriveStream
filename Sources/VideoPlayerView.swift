import AVFoundation
import AVKit
import SwiftUI

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

struct VideoPlayerView: View {
    let fileId: String
    let title: String
    let asset: AVURLAsset
    @Environment(\.dismiss) private var dismiss

    var body: some View {
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
            // Playback still proceeds if audio session configuration fails on simulator
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

    final class Coordinator {
        private let fileId: String
        private weak var player: AVPlayer?
        private var timeObserverToken: Any?
        private var statusObservation: NSKeyValueObservation?
        private var didSeekToSavedPosition = false

        init(fileId: String) {
            self.fileId = fileId
        }

        @MainActor
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

        @MainActor
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

        @MainActor
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
