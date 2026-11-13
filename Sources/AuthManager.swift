import AuthenticationServices
import CryptoKit
import Foundation
import Security
import UIKit

@MainActor
final class AuthManager: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = AuthManager()
    static let clientID = "134960325804-ohug3donf2m9l58oj794tah7f0il4arb.apps.googleusercontent.com"
    static let callbackScheme = "com.googleusercontent.apps.134960325804-ohug3donf2m9l58oj794tah7f0il4arb"
    static let redirectURI = "com.googleusercontent.apps.134960325804-ohug3donf2m9l58oj794tah7f0il4arb:/oauthredirect"
    static let scope = "https://www.googleapis.com/auth/drive.readonly"

    private enum Keys {
        static let accessToken = "oauth_access_token"
        static let refreshToken = "oauth_refresh_token"
        static let expirationDate = "oauth_expiration_date"
    }

    @Published private(set) var isAuthenticated = false
    @Published private(set) var isSigningIn = false
    @Published var authError: String?

    private var activeSession: ASWebAuthenticationSession?
    private var refreshTask: Task<String, Error>?

    override init() {
        super.init()
        let hasToken = UserDefaults.standard.string(forKey: Keys.accessToken) != nil
        let hasRefresh = UserDefaults.standard.string(forKey: Keys.refreshToken) != nil
        isAuthenticated = hasToken || hasRefresh
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
            isAuthenticated = true
        } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin {
            // User cancelled login; keep error blank
        } catch {
            authError = error.localizedDescription
        }
    }

    func signOut() {
        refreshTask?.cancel()
        refreshTask = nil
        UserDefaults.standard.removeObject(forKey: Keys.accessToken)
        UserDefaults.standard.removeObject(forKey: Keys.refreshToken)
        UserDefaults.standard.removeObject(forKey: Keys.expirationDate)
        isAuthenticated = false
        authError = nil
    }

    func validAccessToken() async throws -> String {
        if let token = UserDefaults.standard.string(forKey: Keys.accessToken),
           let expiry = UserDefaults.standard.object(forKey: Keys.expirationDate) as? Date,
           expiry.timeIntervalSinceNow > 60 {
            return token
        }
        return try await refreshAccessToken()
    }

    func refreshAccessToken() async throws -> String {
        if let existingTask = refreshTask {
            return try await existingTask.value
        }

        let task = Task<String, Error> {
            defer { refreshTask = nil }

            guard let refreshToken = UserDefaults.standard.string(forKey: Keys.refreshToken), !refreshToken.isEmpty else {
                signOut()
                throw AuthError.missingRefreshToken
            }

            let params = [
                "client_id": Self.clientID,
                "refresh_token": refreshToken,
                "grant_type": "refresh_token"
            ]

            let response = try await postTokenRequest(params: params)
            saveTokens(response)
            return response.accessToken
        }

        refreshTask = task
        return try await task.value
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
            if http.statusCode == 400 || http.statusCode == 401 {
                let message = String(data: data, encoding: .utf8) ?? ""
                if message.contains("invalid_grant") {
                    signOut()
                }
            }
            let body = String(data: data, encoding: .utf8) ?? "HTTP \(http.statusCode)"
            throw AuthError.tokenExchangeFailed(body)
        }

        return try JSONDecoder().decode(TokenResponse.self, from: data)
    }

    private func saveTokens(_ response: TokenResponse) {
        UserDefaults.standard.set(response.accessToken, forKey: Keys.accessToken)
        if let refresh = response.refreshToken, !refresh.isEmpty {
            UserDefaults.standard.set(refresh, forKey: Keys.refreshToken)
        }
        let expiry = Date().addingTimeInterval(TimeInterval(response.expiresIn))
        UserDefaults.standard.set(expiry, forKey: Keys.expirationDate)
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

private struct TokenResponse: Decodable {
    let accessToken: String
    let refreshToken: String?
    let expiresIn: Int

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresIn = "expires_in"
    }
}

enum AuthError: LocalizedError {
    case invalidURL
    case missingCode
    case missingRefreshToken
    case invalidResponse
    case sessionStartFailed
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
        case .invalidResponse:
            return "Unexpected response from Google OAuth."
        case .sessionStartFailed:
            return "Could not open sign-in session."
        case .oauthError(let message):
            return "Google Sign-In error: \(message)"
        case .tokenExchangeFailed(let details):
            return "Token exchange failed: \(details)"
        }
    }
}
