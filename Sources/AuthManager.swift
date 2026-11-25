import AuthenticationServices
import CryptoKit
import Foundation
import Security
import UIKit

struct DiscoveredTV: Identifiable, Hashable {
    let id: String
    let ip: String
    let device: String
    let signedIn: Bool
}

struct TVSessionPayload: Codable {
    let accessToken: String
    let refreshToken: String
    let expiresAt: Int64

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresAt = "expires_at"
    }
}

@MainActor
final class AuthManager: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = AuthManager()
    static let clientID = "134960325804-ohug3donf2m9l58oj794tah7f0il4arb.apps.googleusercontent.com"
    static let callbackScheme = "com.googleusercontent.apps.134960325804-ohug3donf2m9l58oj794tah7f0il4arb"
    static let redirectURI = "com.googleusercontent.apps.134960325804-ohug3donf2m9l58oj794tah7f0il4arb:/oauthredirect"
    static let appDataScope = "https://www.googleapis.com/auth/drive.appdata"
    static let driveWriteScope = "https://www.googleapis.com/auth/drive"
    static let scope = "https://www.googleapis.com/auth/drive https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/drive.appdata"

    private enum Keys {
        static let accessToken = "oauth_access_token"
        static let refreshToken = "oauth_refresh_token"
        static let expirationDate = "oauth_expiration_date"
        static let grantedScopes = "oauth_granted_scopes"
        static let keychainService = "com.drake.drivestream.auth"
        static let keychainAccount = "oauth_session"
    }

    private struct PersistedSession: Codable {
        var accessToken: String
        var refreshToken: String
        var expiresAt: TimeInterval
        var grantedScopes: String
    }

    @Published private(set) var isAuthenticated = false
    @Published private(set) var isSigningIn = false
    @Published private(set) var hasAppDataScope = false
    @Published private(set) var hasDriveWriteScope = false
    @Published private(set) var accessTokenExpiresAt: Date?
    @Published private(set) var lastRefreshError: String?
    @Published var authError: String?

    var currentRefreshToken: String? {
        let token = loadPersistedSession().refreshToken.trimmingCharacters(in: .whitespacesAndNewlines)
        return token.isEmpty ? nil : token
    }

    var hasRefreshToken: Bool {
        currentRefreshToken != nil
    }

    var tokenDiagnostics: String {
        let session = loadPersistedSession()
        if session.refreshToken.isEmpty {
            if let err = lastRefreshError, !err.isEmpty {
                return "Session: Signed Out | Last Error: \(err)"
            }
            return "Session: Not Signed In | Access Token: None"
        }

        let accessPart: String
        if !session.accessToken.isEmpty && session.expiresAt > 0 {
            let remaining = session.expiresAt - Date().timeIntervalSince1970
            if remaining > 0 {
                let mins = Int(remaining / 60)
                accessPart = mins >= 1 ? "Valid (\(mins)m left)" : "Valid (<1m left)"
            } else {
                accessPart = "Expired (will auto-refresh)"
            }
        } else {
            accessPart = "Pending refresh"
        }

        let base = "Session: Refresh Token Saved ✓ | Access Token: \(accessPart)"
        if let err = lastRefreshError, !err.isEmpty {
            return "\(base) | Error: \(err)"
        }
        return base
    }

    private var activeSession: ASWebAuthenticationSession?
    private var refreshTask: Task<String, Error>?

    override init() {
        super.init()
        let restored = loadAndRepairPersistedSession()
        isAuthenticated = !restored.refreshToken.isEmpty
        hasAppDataScope = Self.containsAppDataScope(restored.grantedScopes)
        hasDriveWriteScope = Self.containsDriveWriteScope(restored.grantedScopes)
        accessTokenExpiresAt = restored.expiresAt > 0 ? Date(timeIntervalSince1970: restored.expiresAt) : nil
    }

    func signIn() async {
        guard !isSigningIn else { return }
        isSigningIn = true
        authError = nil
        defer { isSigningIn = false }

        do {
            let verifier = generateCodeVerifier()
            let challenge = generateCodeChallenge(from: verifier)

            var components = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
            components.queryItems = [
                URLQueryItem(name: "client_id", value: Self.clientID),
                URLQueryItem(name: "redirect_uri", value: Self.redirectURI),
                URLQueryItem(name: "response_type", value: "code"),
                URLQueryItem(name: "scope", value: Self.scope),
                URLQueryItem(name: "code_challenge", value: challenge),
                URLQueryItem(name: "code_challenge_method", value: "S256"),
                URLQueryItem(name: "access_type", value: "offline"),
                URLQueryItem(name: "prompt", value: "consent")
            ]

            guard let authURL = components.url else {
                throw AuthError.invalidURL
            }

            let callbackURL = try await performWebAuth(url: authURL)
            guard let queryItems = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)?.queryItems else {
                throw AuthError.missingCode
            }
            if let errorMsg = queryItems.first(where: { $0.name == "error" })?.value {
                throw AuthError.oauthError(errorMsg)
            }
            guard let code = queryItems.first(where: { $0.name == "code" })?.value else {
                throw AuthError.missingCode
            }

            try await exchangeCodeForToken(code: code, verifier: verifier)
            lastRefreshError = nil
            isAuthenticated = hasRefreshToken
        } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin {
            // User cancelled login; keep error blank
        } catch {
            authError = error.localizedDescription
        }
    }

    func enableCloudSync() async {
        await signIn()
    }

    func markAppDataScopeRevoked() {
        var session = loadPersistedSession()
        session.grantedScopes = ""
        persistSession(session)
        hasAppDataScope = false
        hasDriveWriteScope = false
    }

    func signOut() {
        signOutInternal(clearErrors: true)
    }

    private func signOutInternal(clearErrors: Bool) {
        refreshTask?.cancel()
        refreshTask = nil
        deleteStoredSession()
        isAuthenticated = false
        hasAppDataScope = false
        hasDriveWriteScope = false
        accessTokenExpiresAt = nil
        if clearErrors {
            authError = nil
            lastRefreshError = nil
        }
        SyncManager.shared.resetCloudStateOnSignOut()
    }

    func validAccessToken() async throws -> String {
        let session = loadPersistedSession()
        if !session.accessToken.isEmpty,
           session.expiresAt - Date().timeIntervalSince1970 > 60 {
            return session.accessToken
        }
        return try await refreshAccessToken()
    }

    func refreshIfNeededOnWake() async {
        guard hasRefreshToken else { return }
        let session = loadPersistedSession()
        if !session.accessToken.isEmpty,
           session.expiresAt - Date().timeIntervalSince1970 > 60 {
            return
        }
        _ = try? await refreshAccessToken()
    }

    func refreshAccessToken() async throws -> String {
        if let existingTask = refreshTask {
            return try await existingTask.value
        }

        let task = Task<String, Error> {
            defer { refreshTask = nil }

            guard let refreshToken = currentRefreshToken, !refreshToken.isEmpty else {
                signOutInternal(clearErrors: false)
                lastRefreshError = "Missing refresh token"
                throw AuthError.missingRefreshToken
            }

            let params = [
                "client_id": Self.clientID,
                "refresh_token": refreshToken,
                "grant_type": "refresh_token"
            ]

            var lastError: Error = AuthError.invalidResponse
            for attempt in 1...3 {
                if attempt > 1 {
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                }
                do {
                    let response = try await postTokenRequest(params: params)
                    saveTokens(response)
                    lastRefreshError = nil
                    authError = nil
                    isAuthenticated = hasRefreshToken
                    return response.accessToken
                } catch AuthError.invalidGrant(let details) {
                    lastRefreshError = "invalid_grant: \(details)"
                    authError = AuthError.invalidGrant(details).localizedDescription
                    signOutInternal(clearErrors: false)
                    throw AuthError.invalidGrant(details)
                } catch {
                    lastError = error
                }
            }

            // Transient failure: preserve refresh token and keep the user signed in.
            lastRefreshError = lastError.localizedDescription
            isAuthenticated = hasRefreshToken
            throw lastError
        }

        refreshTask = task
        return try await task.value
    }

    // MARK: - iPhone-to-TV Wi-Fi Sign-In

    func scanForDriveStreamTVs() async -> [DiscoveredTV] {
        guard let localIP = SyncManager.detectLocalIPv4() else { return [] }
        let octets = localIP.split(separator: ".")
        guard octets.count == 4 else { return [] }
        let prefix = "\(octets[0]).\(octets[1]).\(octets[2])"

        let candidates = (1...254).compactMap { hostPart -> String? in
            let ip = "\(prefix).\(hostPart)"
            return ip == localIP ? nil : ip
        }

        return await Task.detached(priority: .userInitiated) {
            let config = URLSessionConfiguration.ephemeral
            config.timeoutIntervalForRequest = 0.6
            config.timeoutIntervalForResource = 0.85
            let session = URLSession(configuration: config)
            defer { session.invalidateAndCancel() }

            var found: [DiscoveredTV] = []
            let batchSize = 48
            var offset = 0
            while offset < candidates.count {
                let end = min(offset + batchSize, candidates.count)
                let batch = candidates[offset..<end]
                let hits = await withTaskGroup(of: DiscoveredTV?.self, returning: [DiscoveredTV].self) { group in
                    for ip in batch {
                        group.addTask {
                            guard let url = URL(string: "http://\(ip):\(SyncManager.peerPort)/api/ping") else {
                                return nil
                            }
                            var req = URLRequest(url: url)
                            req.httpMethod = "GET"
                            guard let (data, resp) = try? await session.data(for: req),
                                  let http = resp as? HTTPURLResponse,
                                  http.statusCode == 200,
                                  let ping = try? JSONDecoder().decode(TVPingResponse.self, from: data),
                                  ping.app == "DriveStreamTV" else {
                                return nil
                            }
                            return DiscoveredTV(
                                id: ip,
                                ip: ip,
                                device: ping.device.isEmpty ? "DriveStream TV" : ping.device,
                                signedIn: ping.signedIn
                            )
                        }
                    }
                    var results: [DiscoveredTV] = []
                    for await item in group {
                        if let item { results.append(item) }
                    }
                    return results
                }
                found.append(contentsOf: hits)
                offset = end
            }
            return found.sorted { $0.ip.localizedStandardCompare($1.ip) == .orderedAscending }
        }.value
    }

    func pushLoginToTV(host: String) async throws -> String {
        let cleanHost = SyncManager.cleanHost(host)
        guard !cleanHost.isEmpty else {
            throw AuthError.invalidTVHost
        }

        let payload = try await buildTVSessionPayload()
        guard let url = URL(string: "http://\(cleanHost):\(SyncManager.peerPort)/api/import-session") else {
            throw AuthError.invalidTVHost
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(payload)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw AuthError.invalidResponse
        }

        struct ImportResp: Decodable {
            let ok: Bool?
            let error: String?
        }
        let decoded = try? JSONDecoder().decode(ImportResp.self, from: data)
        guard (200..<300).contains(http.statusCode), decoded?.ok != false else {
            let errText = decoded?.error ?? (String(data: data, encoding: .utf8) ?? "HTTP \(http.statusCode)")
            throw AuthError.tvPushFailed(errText)
        }
        return "TV Signed In ✓"
    }

    func exportSessionCode() async throws -> String {
        let payload = try await buildTVSessionPayload()
        let data = try JSONEncoder().encode(payload)
        return data.base64EncodedString()
    }

    private func buildTVSessionPayload() async throws -> TVSessionPayload {
        guard let refreshToken = currentRefreshToken, !refreshToken.isEmpty else {
            throw AuthError.missingRefreshToken
        }
        let freshAccess = (try? await validAccessToken()) ?? loadPersistedSession().accessToken
        let latest = loadPersistedSession()
        let expiresAt = latest.expiresAt > 0 ? Int64(latest.expiresAt) : Int64(Date().timeIntervalSince1970)
        return TVSessionPayload(
            accessToken: freshAccess,
            refreshToken: latest.refreshToken.isEmpty ? refreshToken : latest.refreshToken,
            expiresAt: expiresAt
        )
    }

    // MARK: - ASWebAuthenticationPresentationContextProviding

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            return scene.windows.first(where: { $0.isKeyWindow }) ?? UIWindow(windowScene: scene)
        }
        return UIWindow()
    }

    // MARK: - Private

    private func performWebAuth(url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(
                url: url,
                callbackURLScheme: Self.callbackScheme
            ) { [weak self] callbackURL, error in
                Task { @MainActor in
                    self?.activeSession = nil
                }
                if let error {
                    continuation.resume(throwing: error)
                } else if let callbackURL {
                    continuation.resume(returning: callbackURL)
                } else {
                    continuation.resume(throwing: AuthError.missingCode)
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            self.activeSession = session
            if !session.start() {
                self.activeSession = nil
                continuation.resume(throwing: AuthError.sessionStartFailed)
            }
        }
    }

    private func exchangeCodeForToken(code: String, verifier: String) async throws {
        let params = [
            "client_id": Self.clientID,
            "code": code,
            "code_verifier": verifier,
            "grant_type": "authorization_code",
            "redirect_uri": Self.redirectURI
        ]
        let response = try await postTokenRequest(params: params)
        saveTokens(response)
    }

    private func postTokenRequest(params: [String: String]) async throws -> TokenResponse {
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = formEncodedBody(params)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw AuthError.invalidResponse
        }
        guard (200..<300).contains(http.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? "HTTP \(http.statusCode)"
            if http.statusCode == 400 || http.statusCode == 401 {
                struct GoogleErrorBody: Decodable { let error: String?; let error_description: String? }
                let errObj = try? JSONDecoder().decode(GoogleErrorBody.self, from: data)
                if errObj?.error == "invalid_grant" || body.contains("\"invalid_grant\"") {
                    throw AuthError.invalidGrant(errObj?.error_description ?? body)
                }
            }
            throw AuthError.tokenExchangeFailed(body)
        }

        return try JSONDecoder().decode(TokenResponse.self, from: data)
    }

    private func saveTokens(_ response: TokenResponse) {
        var session = loadPersistedSession()
        session.accessToken = response.accessToken
        // Preserve existing refresh_token when refresh responses omit it.
        if let refresh = response.refreshToken?.trimmingCharacters(in: .whitespacesAndNewlines), !refresh.isEmpty {
            session.refreshToken = refresh
        }
        let expiryDate = Date().addingTimeInterval(TimeInterval(response.expiresIn))
        session.expiresAt = expiryDate.timeIntervalSince1970
        if let scope = response.scope, !scope.isEmpty {
            session.grantedScopes = scope
        }
        persistSession(session)

        accessTokenExpiresAt = expiryDate
        hasAppDataScope = Self.containsAppDataScope(session.grantedScopes)
        hasDriveWriteScope = Self.containsDriveWriteScope(session.grantedScopes)
        isAuthenticated = !session.refreshToken.isEmpty
    }

    private func loadAndRepairPersistedSession() -> PersistedSession {
        let defaults = loadFromUserDefaults()
        let keychain = loadFromKeychain()

        let chosen: PersistedSession
        switch (!defaults.refreshToken.isEmpty, !(keychain?.refreshToken.isEmpty ?? true)) {
        case (true, true):
            if let kc = keychain, kc.expiresAt > defaults.expiresAt {
                chosen = kc
            } else {
                chosen = defaults
            }
        case (true, false):
            chosen = defaults
        case (false, true):
            chosen = keychain!
        case (false, false):
            chosen = defaults
        }

        if !chosen.refreshToken.isEmpty {
            persistSession(chosen)
        }
        return chosen
    }

    private func loadPersistedSession() -> PersistedSession {
        let defaults = loadFromUserDefaults()
        if !defaults.refreshToken.isEmpty {
            return defaults
        }
        if let kc = loadFromKeychain(), !kc.refreshToken.isEmpty {
            saveToUserDefaults(kc)
            return kc
        }
        return defaults
    }

    private func persistSession(_ session: PersistedSession) {
        saveToUserDefaults(session)
        saveToKeychain(session)
    }

    private func deleteStoredSession() {
        let ud = UserDefaults.standard
        ud.removeObject(forKey: Keys.accessToken)
        ud.removeObject(forKey: Keys.refreshToken)
        ud.removeObject(forKey: Keys.expirationDate)
        ud.removeObject(forKey: Keys.grantedScopes)
        deleteFromKeychain()
    }

    private func loadFromUserDefaults() -> PersistedSession {
        let ud = UserDefaults.standard
        let access = ud.string(forKey: Keys.accessToken) ?? ""
        let refresh = ud.string(forKey: Keys.refreshToken) ?? ""
        let expiry = (ud.object(forKey: Keys.expirationDate) as? Date)?.timeIntervalSince1970 ?? 0
        let scopes = ud.string(forKey: Keys.grantedScopes) ?? ""
        return PersistedSession(accessToken: access, refreshToken: refresh, expiresAt: expiry, grantedScopes: scopes)
    }

    private func saveToUserDefaults(_ session: PersistedSession) {
        let ud = UserDefaults.standard
        ud.set(session.accessToken, forKey: Keys.accessToken)
        if !session.refreshToken.isEmpty {
            ud.set(session.refreshToken, forKey: Keys.refreshToken)
        }
        if session.expiresAt > 0 {
            ud.set(Date(timeIntervalSince1970: session.expiresAt), forKey: Keys.expirationDate)
        }
        if !session.grantedScopes.isEmpty {
            ud.set(session.grantedScopes, forKey: Keys.grantedScopes)
        } else {
            ud.removeObject(forKey: Keys.grantedScopes)
        }
    }

    private func keychainQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Keys.keychainService,
            kSecAttrAccount as String: Keys.keychainAccount
        ]
    }

    private func loadFromKeychain() -> PersistedSession? {
        var query = keychainQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(PersistedSession.self, from: data)
    }

    private func saveToKeychain(_ session: PersistedSession) {
        guard let data = try? JSONEncoder().encode(session) else { return }
        let query = keychainQuery()
        let attrs: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]
        let status = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            var addQuery = query
            addQuery[kSecValueData as String] = data
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(addQuery as CFDictionary, nil)
        }
    }

    private func deleteFromKeychain() {
        SecItemDelete(keychainQuery() as CFDictionary)
    }

    private static func containsAppDataScope(_ scopeString: String?) -> Bool {
        guard let scopeString else { return false }
        return scopeString.split(whereSeparator: \.isWhitespace).contains { token in
            token == appDataScope || token.hasSuffix("/drive.appdata")
        }
    }

    private static func containsDriveWriteScope(_ scopeString: String?) -> Bool {
        guard let scopeString else { return false }
        return scopeString.split(whereSeparator: \.isWhitespace).contains { token in
            token == driveWriteScope || token.hasSuffix("/auth/drive")
        }
    }

    private func generateCodeVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return base64URLEncode(Data(bytes))
    }

    private func generateCodeChallenge(from verifier: String) -> String {
        let digest = SHA256.hash(data: Data(verifier.utf8))
        return base64URLEncode(Data(digest))
    }

    private func base64URLEncode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private func formEncodedBody(_ params: [String: String]) -> Data? {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        let encoded = params.map { key, value in
            let k = key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key
            let v = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
            return "\(k)=\(v)"
        }
        .joined(separator: "&")
        return encoded.data(using: .utf8)
    }
}

private struct TVPingResponse: Decodable {
    let app: String
    let signedIn: Bool
    let device: String
}

private struct TokenResponse: Decodable {
    let accessToken: String
    let refreshToken: String?
    let expiresIn: Int
    let scope: String?

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresIn = "expires_in"
        case scope
    }
}

enum AuthError: LocalizedError {
    case invalidURL
    case missingCode
    case missingRefreshToken
    case invalidGrant(String)
    case invalidResponse
    case sessionStartFailed
    case invalidTVHost
    case tvPushFailed(String)
    case oauthError(String)
    case tokenExchangeFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Failed to construct Google OAuth URL."
        case .missingCode:
            return "Authorization code was not returned by Google."
        case .missingRefreshToken:
            return "Session expired. Please sign in with Google again."
        case .invalidGrant:
            return "Google session expired or was revoked (invalid_grant). Please sign in again."
        case .invalidResponse:
            return "Unexpected response from Google OAuth."
        case .sessionStartFailed:
            return "Could not open sign-in session."
        case .invalidTVHost:
            return "Enter a valid TV LAN IP address (e.g. 192.168.1.181)."
        case .tvPushFailed(let details):
            return "TV sign-in failed: \(details)"
        case .oauthError(let message):
            return "Google Sign-In error: \(message)"
        case .tokenExchangeFailed(let details):
            return "Token exchange failed: \(details)"
        }
    }
}
