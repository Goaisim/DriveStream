import SwiftUI
import UIKit

final class AppDelegate: NSObject, UIApplicationDelegate {
    static var orientationLock: UIInterfaceOrientationMask = .allButUpsideDown

    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        Self.orientationLock
    }
}

@main
struct DriveStreamApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var authManager = AuthManager.shared
    @Environment(\.scenePhase) private var scenePhase

    init() {
        UserDefaults.standard.register(defaults: [
            "drive_folder_id": DriveService.defaultFolderID
        ])
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(authManager)
                .preferredColorScheme(.dark)
                .task {
                    LocalStreamProxy.shared.start()
                    await authManager.refreshIfNeededOnWake()
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active {
                        Task { await authManager.refreshIfNeededOnWake() }
                    }
                }
        }
    }
}
