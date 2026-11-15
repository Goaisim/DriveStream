import SwiftUI

struct SoulseekView: View {
    enum HubTab: String, CaseIterable {
        case search = "Search"
        case transfers = "Transfers"
    }

    enum SearchMode: String, CaseIterable {
        case folders = "Season / Folder Packs"
        case files = "Individual Files"
    }

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var authManager: AuthManager
    @ObservedObject private var syncManager = SyncManager.shared
    @ObservedObject private var soulseek = SoulseekService.shared

    let targetFolderId: String
    let onLibraryItemAdded: () async -> Void

    @State private var selectedTab: HubTab = .search
    @State private var searchMode: SearchMode = .folders
    @State private var searchQuery = ""
    @State private var bridgeHostDraft = ""

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                VStack(spacing: 12) {
                    bridgeCard
                    reauthWarningBanner

                    Picker("Section", selection: $selectedTab) {
                        Text(HubTab.search.rawValue).tag(HubTab.search)
                        Text("\(HubTab.transfers.rawValue) (\(soulseek.transfers.count))").tag(HubTab.transfers)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)

                    if let actionMsg = soulseek.actionMessage {
                        Text(actionMsg)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.green)
                            .padding(.horizontal)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    if selectedTab == .search {
                        searchTabContent
                    } else {
                        transfersTabContent
                    }
                }
                .padding(.top, 8)
            }
            .preferredColorScheme(.dark)
            .navigationTitle("Soulseek Media Hub")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .task {
            bridgeHostDraft = syncManager.soulseekBridgeHost
            soulseek.onUploadCompleted = {
                await onLibraryItemAdded()
            }
            await soulseek.ensureConnectedAndRefresh()
            bridgeHostDraft = syncManager.soulseekBridgeHost
            soulseek.startPollingTransfers()
        }
        .onDisappear {
            soulseek.stopPollingTransfers()
        }
    }

    private var bridgeCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Circle()
                    .fill(soulseek.bridgeReachable ? Color.green : Color.orange)
                    .frame(width: 9, height: 9)
                Text(soulseek.statusMessage)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(soulseek.bridgeReachable ? .white : .orange)
                Spacer()
                Button {
                    Task {
                        await soulseek.scanWiFiForBridge()
                        bridgeHostDraft = syncManager.soulseekBridgeHost
                    }
                } label: {
                    if soulseek.isScanning {
                        ProgressView().controlSize(.small)
                    } else {
                        Label("Scan Wi-Fi", systemImage: "wifi")
                            .font(.caption.weight(.semibold))
                    }
                }
                .buttonStyle(.bordered)
                .disabled(soulseek.isScanning)
            }

            HStack(spacing: 8) {
                TextField("PC Bridge IP (e.g. 192.168.0.105)", text: $bridgeHostDraft)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Connect") {
                    Task {
                        await soulseek.setManualBridgeHost(bridgeHostDraft)
                        bridgeHostDraft = syncManager.soulseekBridgeHost
                    }
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(12)
        .background(Color(white: 0.11), in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal)
    }

    @ViewBuilder
    private var reauthWarningBanner: some View {
        if !authManager.isAuthenticated || !authManager.hasDriveWriteScope {
            VStack(alignment: .leading, spacing: 6) {
                Text("Google Drive upload access required")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.yellow)
                Text("Reconnect Google Drive to grant upload permission into your library folder.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Button {
                    Task { await authManager.signIn() }
                } label: {
                    Text(authManager.isAuthenticated ? "Grant Drive Upload Permission" : "Sign In to Google Drive")
                        .font(.caption.weight(.semibold))
                }
                .buttonStyle(.borderedProminent)
                .tint(.orange)
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.orange.opacity(0.16), in: RoundedRectangle(cornerRadius: 10))
            .padding(.horizontal)
        }
    }

    private var searchTabContent: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                TextField("Search movies, shows, or S01...", text: $searchQuery)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                    .onSubmit {
                        soulseek.startSearch(query: searchQuery)
                    }

                Button {
                    soulseek.startSearch(query: searchQuery)
                } label: {
                    if soulseek.isSearching {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "magnifyingglass")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(.horizontal)

            Picker("Result View", selection: $searchMode) {
                Text("\(SearchMode.folders.rawValue) (\(soulseek.folders.count))").tag(SearchMode.folders)
                Text("\(SearchMode.files.rawValue) (\(soulseek.files.count))").tag(SearchMode.files)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)

            if let err = soulseek.searchError {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .padding(.horizontal)
            }

            List {
                if searchMode == .folders {
                    if soulseek.folders.isEmpty && !soulseek.isSearching {
                        ContentUnavailableView(
                            "Search Soulseek",
                            systemImage: "folder.badge.plus",
                            description: Text("Find full season folders and batch-upload them straight to Google Drive.")
                        )
                        .listRowBackground(Color.clear)
                    } else {
                        ForEach(soulseek.folders) { pack in
                            folderRow(pack)
                                .listRowBackground(Color(white: 0.1))
                        }
                    }
                } else {
                    if soulseek.files.isEmpty && !soulseek.isSearching {
                        ContentUnavailableView(
                            "No Video Files Yet",
                            systemImage: "film",
                            description: Text("Search for a movie or episode to see individual .mkv/.mp4 peers.")
                        )
                        .listRowBackground(Color.clear)
                    } else {
                        ForEach(soulseek.files) { file in
                            fileRow(file)
                                .listRowBackground(Color(white: 0.1))
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private func folderRow(_ pack: SoulseekFolderPack) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(pack.folderDisplayName)
                        .font(.headline)
                        .foregroundStyle(.white)
                    Text(pack.summary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text(pack.qualityTag)
                    .font(.caption2.weight(.bold))
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.blue.opacity(0.28), in: Capsule())
                    .foregroundStyle(.cyan)
            }

            peerMetaRow(
                username: pack.username,
                hasFreeSlot: pack.hasFreeUploadSlot,
                queueLength: pack.queueLength,
                uploadSpeed: pack.uploadSpeed
            )

            Button {
                Task {
                    await soulseek.downloadFolder(
                        pack,
                        authManager: authManager,
                        targetFolderId: targetFolderId
                    )
                    selectedTab = .transfers
                }
            } label: {
                HStack {
                    if soulseek.activeEnqueueId == pack.id {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "icloud.and.arrow.down")
                    }
                    Text("Download Entire Folder to Drive")
                        .font(.subheadline.weight(.semibold))
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(soulseek.activeEnqueueId != nil || !authManager.isAuthenticated || !authManager.hasDriveWriteScope)
        }
        .padding(.vertical, 4)
    }

    private func fileRow(_ file: SoulseekFileResult) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(file.basename)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                    Text(SoulseekFormatting.formatSize(file.size))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text(file.qualityTag)
                    .font(.caption2.weight(.bold))
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.blue.opacity(0.28), in: Capsule())
                    .foregroundStyle(.cyan)
            }

            peerMetaRow(
                username: file.username,
                hasFreeSlot: file.hasFreeUploadSlot,
                queueLength: file.queueLength,
                uploadSpeed: file.uploadSpeed
            )

            Button {
                Task {
                    await soulseek.downloadFile(
                        file,
                        authManager: authManager,
                        targetFolderId: targetFolderId
                    )
                    selectedTab = .transfers
                }
            } label: {
                HStack {
                    if soulseek.activeEnqueueId == file.id {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "arrow.down.circle")
                    }
                    Text("Download & Upload to Drive")
                        .font(.subheadline.weight(.semibold))
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(soulseek.activeEnqueueId != nil || !authManager.isAuthenticated || !authManager.hasDriveWriteScope)
        }
        .padding(.vertical, 4)
    }

    private func peerMetaRow(username: String, hasFreeSlot: Bool, queueLength: Int, uploadSpeed: Double) -> some View {
        HStack(spacing: 8) {
            Text(username)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            Text(hasFreeSlot ? "Free Slot" : "Queue: \(queueLength)")
                .font(.caption2.weight(.bold))
                .padding(.horizontal, 7)
                .padding(.vertical, 2)
                .background(
                    (hasFreeSlot ? Color.green : Color.orange).opacity(0.24),
                    in: Capsule()
                )
                .foregroundStyle(hasFreeSlot ? .green : .orange)

            Spacer()

            Text(SoulseekFormatting.formatSpeed(uploadSpeed))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }

    private var transfersTabContent: some View {
        List {
            if soulseek.transfers.isEmpty {
                ContentUnavailableView(
                    "No Active Transfers",
                    systemImage: "icloud.and.arrow.up",
                    description: Text("Queued Soulseek downloads automatically upload into your Google Drive library.")
                )
                .listRowBackground(Color.clear)
            } else {
                ForEach(soulseek.transfers) { item in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(alignment: .top) {
                            Text(item.title)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.white)
                                .lineLimit(2)
                            Spacer()
                            if item.stage == "completed" {
                                Text("Added to Library ✓")
                                    .font(.caption2.weight(.bold))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(Color.green.opacity(0.25), in: Capsule())
                                    .foregroundStyle(.green)
                            }
                        }

                        ProgressView(value: min(max(item.percent, 0), 100), total: 100)
                            .tint(progressColor(for: item.stage))

                        HStack {
                            Text(item.statusText)
                                .font(.caption)
                                .foregroundStyle(item.stage == "failed" ? .red : .secondary)
                            Spacer()
                            Text("\(item.username) • \(SoulseekFormatting.formatSize(item.size))")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }

                        if !item.driveFolderPath.isEmpty {
                            Text("Drive: \(item.driveFolderPath)")
                                .font(.caption2)
                                .foregroundStyle(.cyan)
                        }
                    }
                    .padding(.vertical, 4)
                    .listRowBackground(Color(white: 0.1))
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func progressColor(for stage: String) -> Color {
        switch stage {
        case "completed":
            return .green
        case "failed":
            return .red
        case "uploading", "queued_upload":
            return .cyan
        default:
            return .blue
        }
    }
}
