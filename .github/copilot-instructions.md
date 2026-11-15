# DriveStream Repository Instructions

## Dual-Platform Parity Law

This repository is a synchronized dual-platform monorepo:
- **iOS:** `Sources/*.swift`, `project.yml`, `.github/workflows/build-ipa.yml`
- **Android TV / Fire TV:** `android/app/src/main/java/com/drake/drivestream/*.kt`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `.github/workflows/build-apk.yml`

Mandatory rules for every code change:
1. Apply every feature, UI change, ad-block update, metadata adjustment, or bug fix to **both** the iOS Swift codebase and the Android TV Kotlin codebase in the same turn and commit (unless explicitly told otherwise).
2. Keep `DriveStreamSyncState` JSON schemas (`version: 1`, Unix epoch seconds for timestamps, field names `savedLinks`, `deletedLinkIds`, `watchProgress`, `customCovers`) strictly identical between `Sources/SyncManager.swift` and `android/app/src/main/java/com/drake/drivestream/SyncManager.kt`.
3. Keep `GET /api/sync`, `POST /api/sync` on Android TV (`0.0.0.0:8766`), and Google Drive `appDataFolder` sync (`drivestream_sync.json`, scope `drive.appdata`) compatible across platforms.
4. Ensure both `.github/workflows/build-ipa.yml` (`DriveStream-unsigned.ipa`) and `.github/workflows/build-apk.yml` (`DriveStream-firetv.apk`) run cleanly on every push to `main`.
