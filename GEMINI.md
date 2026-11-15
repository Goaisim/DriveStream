# DriveStream Standing Instructions (Google Antigravity)

## Dual-Platform Parity Law

DriveStream is a synchronized dual-platform monorepo targeting two platforms:
- **iOS:** `Sources/*.swift`, `project.yml`, and `.github/workflows/build-ipa.yml` (`DriveStream-unsigned.ipa`)
- **Android TV / Fire TV:** `android/app/src/main/java/com/drake/drivestream/*.kt`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, and `.github/workflows/build-apk.yml` (`DriveStream-firetv.apk`)

Whenever the user asks for **any** new feature, UI change, ad-block update, metadata tweak, player fix, or bug fix (unless explicitly scoped to a single platform):
1. Implement the change in **BOTH** the iOS Swift codebase (`Sources/`) **AND** the Android TV Kotlin codebase (`android/app/src/main/java/com/drake/drivestream/`) in the **same turn and commit**.
2. Preserve **identical** cross-platform JSON schemas (`DriveStreamSyncState`, version `1`) and field names/units across `Sources/SyncManager.swift` and `android/app/src/main/java/com/drake/drivestream/SyncManager.kt`:
   - `version` (`Int` = `1`)
   - `savedLinks` (`id` UUID string, `title`, `urlString`, `dateAdded` Unix epoch seconds `Double`)
   - `deletedLinkIds` (map of lowercase UUID string -> Unix epoch seconds `Double`)
   - `watchProgress` (map of Drive `fileId` -> `{ positionSeconds, durationSeconds, updatedAt }` Unix epoch seconds `Double`)
   - `customCovers` (map of normalized show key -> `{ posterUrl, tvmazeShowId, updatedAt }`)
3. Preserve shared OAuth and Companion Server contracts:
   - Google OAuth scopes: `https://www.googleapis.com/auth/drive.readonly` + `https://www.googleapis.com/auth/drive.appdata`
   - Cloud sync file: `drivestream_sync.json` in Google Drive `appDataFolder`
   - Android TV LAN peer sync endpoints on port `8766`: `GET /api/sync` and `POST /api/sync`
4. Verify before finishing that `.github/workflows/build-ipa.yml` and `.github/workflows/build-apk.yml` both trigger on `push` to `main` so every commit produces matching `DriveStream-unsigned.ipa` and `DriveStream-firetv.apk` build artifacts.
