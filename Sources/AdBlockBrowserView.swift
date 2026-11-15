import AVFoundation
import SwiftUI
import UIKit
import WebKit

// MARK: - Layer 1: AdBlock Engine (WKContentRuleList + Cached Host Set)

@MainActor
final class AdBlockEngine {
    static let shared = AdBlockEngine()

    private static let baseRuleIdentifier = "DriveStreamBaseBlocker_v2"
    private static let yoyoRuleIdentifier = "DriveStreamYoyoBlocker_v2"
    private static let yoyoURL = URL(string: "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0")!
    private static let cacheFileName = "yoyo_ad_domains.txt"
    private static let cacheTTL: TimeInterval = 60 * 60 * 24

    static let baseBlockedDomains: [String] = [
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adnxs.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "adsterra.com",
        "exoclick.com",
        "juicyads.com",
        "onclickads.net",
        "outbrain.com",
        "taboola.com",
        "yandex.ru",
        "amazon-adsystem.com",
        "scorecardresearch.com",
        "zedo.com",
        "bidvertiser.com",
        "hilltopads.net",
        "trafficjunky.net",
        "mgid.com",
        "revcontent.com"
    ]

    private static let baseBlockedDomainSet: Set<String> = Set(baseBlockedDomains)

    nonisolated static let streamingBypassTokens: [String] = [
        ".m3u8", ".ts", ".m4s", ".mp4", ".mpd", ".aac", ".key",
        "blob:", "data:", "ws:", "wss:",
        "jwplayer", "clappr", "hls.js", "video.js",
        "embed", "player", "stream"
    ]

    nonisolated private static let mediaBypassRegexes: [String] = [
        "^https?://[^/?#]+[/?][^#]*\\.m3u8",
        "^https?://[^/?#]+[/?][^#]*\\.ts",
        "^https?://[^/?#]+[/?][^#]*\\.m4s",
        "^https?://[^/?#]+[/?][^#]*\\.mp4",
        "^https?://[^/?#]+[/?][^#]*\\.mpd",
        "^https?://[^/?#]+[/?][^#]*\\.aac",
        "^https?://[^/?#]+[/?][^#]*\\.key",
        "^https?://[^/?#]+[/?][^#]*blob:",
        "^https?://[^/?#]+[/?][^#]*data:",
        "^https?://[^/?#]+[/?][^#]*ws:",
        "^https?://[^/?#]+[/?][^#]*wss:",
        "^https?://[^/?#]+[/?][^#]*jwplayer",
        "^https?://[^/?#]+[/?][^#]*clappr",
        "^https?://[^/?#]+[/?][^#]*hls\\.js",
        "^https?://[^/?#]+[/?][^#]*video\\.js",
        "^https?://[^/?#]+[/?][^#]*embed",
        "^https?://[^/?#]+[/?][^#]*player",
        "^https?://[^/?#]+[/?][^#]*stream"
    ]

    private static let hiddenCSSSelector =
        ".ad, .ads, .adsbygoogle, [id^=\"google_ads\"], [class*=\"popup\"]:not(.jwplayer):not(.jw-wrapper):not(.clappr-player):not([data-player]):not([class*=\"player\"]):not([id*=\"player\"]):not([class*=\"jw-\"]), [id*=\"popup\"]:not(.jwplayer):not(.jw-wrapper):not(.clappr-player):not([data-player]):not([class*=\"player\"]):not([id*=\"player\"]):not([class*=\"jw-\"]), iframe[src*=\"/ads/\"], iframe[src*=\"doubleclick\"]"

    private(set) var blockedDomains: Set<String>
    private(set) var baseRuleList: WKContentRuleList?
    private(set) var yoyoRuleList: WKContentRuleList?

    private var baseCompileTask: Task<WKContentRuleList?, Never>?
    private var yoyoRefreshStarted = false
    private var yoyoDomains: [String] = []

    private init() {
        blockedDomains = Set(Self.baseBlockedDomains)
        loadCachedYoyoDomains()
        startBackgroundYoyoRefreshIfNeeded()
    }

    func prepare() {
        if baseRuleList == nil && baseCompileTask == nil {
            Task { [weak self] in
                _ = await self?.ensureBaseRulesCompiled()
            }
        }
        startBackgroundYoyoRefreshIfNeeded()
    }

    func isBlockedHost(_ host: String) -> Bool {
        matchesDomainSet(host, in: blockedDomains)
    }

    func isBaseBlockedHost(_ host: String) -> Bool {
        matchesDomainSet(host, in: Self.baseBlockedDomainSet)
    }

    private func matchesDomainSet(_ host: String, in domainSet: Set<String>) -> Bool {
        let lower = host.lowercased()
        if domainSet.contains(lower) {
            return true
        }
        var slice = lower[...]
        while let dot = slice.firstIndex(of: ".") {
            slice = slice[slice.index(after: dot)...]
            if slice.contains("."), domainSet.contains(String(slice)) {
                return true
            }
        }
        return false
    }

    nonisolated func isStreamingOrPlayerURL(_ url: URL) -> Bool {
        let scheme = url.scheme?.lowercased() ?? ""
        if scheme == "blob" || scheme == "data" || scheme == "ws" || scheme == "wss" {
            return true
        }
        let path = url.path.lowercased()
        let query = url.query?.lowercased() ?? ""
        let pathAndQuery = query.isEmpty ? path : "\(path)?\(query)"
        return Self.streamingBypassTokens.contains { pathAndQuery.contains($0) }
    }

    func ensureBaseRulesCompiled() async -> WKContentRuleList? {
        if let baseRuleList {
            return baseRuleList
        }
        if let baseCompileTask {
            return await baseCompileTask.value
        }

        let task = Task<WKContentRuleList?, Never> { [weak self] in
            guard let self else { return nil }
            guard let jsonString = Self.buildBaseRuleJSON(),
                  let store = WKContentRuleListStore.default() else {
                return nil
            }
            let compiled = await Self.compileRuleList(
                store: store,
                identifier: Self.baseRuleIdentifier,
                jsonString: jsonString
            )
            self.baseRuleList = compiled
            return compiled
        }
        baseCompileTask = task
        let result = await task.value
        baseCompileTask = nil
        return result
    }

    func ensureYoyoRulesCompiled() async -> WKContentRuleList? {
        if let yoyoRuleList {
            return yoyoRuleList
        }
        guard !yoyoDomains.isEmpty, let store = WKContentRuleListStore.default() else {
            return nil
        }

        let domainsSnapshot = yoyoDomains
        let jsonString = await Task.detached(priority: .utility) {
            Self.buildDomainBlockJSON(for: domainsSnapshot)
        }.value

        guard let jsonString else { return nil }
        let compiled = await Self.compileRuleList(
            store: store,
            identifier: Self.yoyoRuleIdentifier,
            jsonString: jsonString
        )
        yoyoRuleList = compiled
        return compiled
    }

    private func startBackgroundYoyoRefreshIfNeeded() {
        guard !yoyoRefreshStarted else { return }
        yoyoRefreshStarted = true

        let cacheURL = Self.cacheFileURL()
        let needsNetworkFetch: Bool = {
            guard let cacheURL,
                  let attrs = try? FileManager.default.attributesOfItem(atPath: cacheURL.path),
                  let modified = attrs[.modificationDate] as? Date else {
                return true
            }
            return Date().timeIntervalSince(modified) > Self.cacheTTL
        }()

        guard needsNetworkFetch else {
            Task { [weak self] in
                _ = await self?.ensureYoyoRulesCompiled()
            }
            return
        }

        let yoyoURL = Self.yoyoURL
        Task.detached(priority: .background) { [weak self] in
            var request = URLRequest(url: yoyoURL)
            request.timeoutInterval = 20
            guard let (data, response) = try? await URLSession.shared.data(for: request),
                  let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode),
                  let text = String(data: data, encoding: .utf8) else {
                return
            }

            let parsed = Self.parseYoyoList(text)
            guard !parsed.isEmpty else { return }

            if let cacheURL {
                try? text.write(to: cacheURL, atomically: true, encoding: .utf8)
            }

            await MainActor.run { [weak self] in
                guard let self else { return }
                self.yoyoDomains = parsed
                self.blockedDomains.formUnion(parsed)
                self.yoyoRuleList = nil
            }
            _ = await self?.ensureYoyoRulesCompiled()
        }
    }

    private func loadCachedYoyoDomains() {
        guard let cacheURL = Self.cacheFileURL(),
              let text = try? String(contentsOf: cacheURL, encoding: .utf8) else {
            return
        }
        let parsed = Self.parseYoyoList(text)
        guard !parsed.isEmpty else { return }
        yoyoDomains = parsed
        blockedDomains.formUnion(parsed)
    }

    nonisolated private static func parseYoyoList(_ text: String) -> [String] {
        var domains: [String] = []
        domains.reserveCapacity(4000)

        text.enumerateLines { rawLine, _ in
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard !line.isEmpty,
                  !line.hasPrefix("#"),
                  line.contains("."),
                  line.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "-") }) else {
                return
            }
            domains.append(line)
        }
        return domains
    }

    nonisolated private static func cacheFileURL() -> URL? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
            .appendingPathComponent(cacheFileName)
    }

    private static func buildBaseRuleJSON() -> String? {
        var rules = domainBlockRuleObjects(for: baseBlockedDomains)
        rules.append(contentsOf: mediaBypassRuleObjects())
        rules.append([
            "trigger": [
                "url-filter": ".*"
            ],
            "action": [
                "type": "css-display-none",
                "selector": hiddenCSSSelector
            ]
        ])

        guard let data = try? JSONSerialization.data(withJSONObject: rules) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    nonisolated private static func buildDomainBlockJSON(for domains: [String]) -> String? {
        var rules = domainBlockRuleObjects(for: domains)
        guard !rules.isEmpty else { return nil }
        rules.append(contentsOf: mediaBypassRuleObjects())
        guard let data = try? JSONSerialization.data(withJSONObject: rules) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    nonisolated private static func domainBlockRuleObjects(for domains: [String]) -> [[String: Any]] {
        domains.map { domain in
            let escaped = domain.replacingOccurrences(of: ".", with: "\\.")
            return [
                "trigger": [
                    "url-filter": "^https?://([^/:]*\\.)?\(escaped)[:/?#]",
                    "load-type": ["third-party"]
                ],
                "action": [
                    "type": "block"
                ]
            ]
        }
    }

    nonisolated private static func mediaBypassRuleObjects() -> [[String: Any]] {
        mediaBypassRegexes.map { pattern in
            [
                "trigger": [
                    "url-filter": pattern
                ],
                "action": [
                    "type": "ignore-previous-rules"
                ]
            ]
        }
    }

    private static func compileRuleList(
        store: WKContentRuleListStore,
        identifier: String,
        jsonString: String
    ) async -> WKContentRuleList? {
        await withCheckedContinuation { continuation in
            store.compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: jsonString) { ruleList, _ in
                continuation.resume(returning: ruleList)
            }
        }
    }
}

// MARK: - Weak Script Message Proxy

private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    weak var delegate: WKScriptMessageHandler?

    init(delegate: WKScriptMessageHandler) {
        self.delegate = delegate
    }

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        delegate?.userContentController(userContentController, didReceive: message)
    }
}

// MARK: - Detected Stream Models

struct DetectedHlsStream: Equatable {
    let m3u8Url: String
    let referer: String
    let origin: String
    let userAgent: String
}

struct NativeStreamPlayback: Identifiable {
    let id = UUID()
    let title: String
    let asset: AVURLAsset
}

// MARK: - Browser View Model (Layers 2 & 3 + Navigation State)

@MainActor
private final class AdBlockBrowserViewModel: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    let webView: WKWebView

    @Published var addressText: String
    @Published private(set) var currentURL: URL?
    @Published private(set) var canGoBack = false
    @Published private(set) var canGoForward = false
    @Published private(set) var isLoading = false
    @Published private(set) var estimatedProgress: Double = 0
    @Published var strictRedirectLock = true
    @Published private(set) var blockedCount = 0
    @Published private(set) var detectedStream: DetectedHlsStream?

    private let initialURL: URL
    private var allowedRootDomain: String?
    private var allowBackForwardCrossDomain = false
    private var didStartInitialLoad = false
    private var didTeardown = false
    private var observers: [NSKeyValueObservation] = []

    private static let messageHandlerName = "adBlocker"
    private static let mobileSafariUA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

    init(initialURL: URL) {
        self.initialURL = initialURL
        self.currentURL = initialURL
        self.addressText = initialURL.absoluteString
        self.allowedRootDomain = Self.rootDomain(for: initialURL.host)

        let config = WKWebViewConfiguration()
        config.websiteDataStore = WKWebsiteDataStore.default()
        config.allowsInlineMediaPlayback = true
        config.allowsPictureInPictureMediaPlayback = true
        config.allowsAirPlayForMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.defaultWebpagePreferences.preferredContentMode = .recommended

        let contentController = WKUserContentController()
        let userScript = WKUserScript(
            source: Self.adBlockUserScript,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false
        )
        contentController.addUserScript(userScript)
        config.userContentController = contentController

        let webView = WKWebView(frame: .zero, configuration: config)
        let darkColor = UIColor(red: 0.04, green: 0.04, blue: 0.06, alpha: 1)
        webView.isOpaque = false
        webView.backgroundColor = darkColor
        webView.scrollView.backgroundColor = darkColor
        webView.allowsBackForwardNavigationGestures = true
        webView.customUserAgent = Self.mobileSafariUA
        self.webView = webView

        super.init()

        contentController.add(WeakScriptMessageHandler(delegate: self), name: Self.messageHandlerName)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        installObservers()
    }

    func start() async {
        guard !didStartInitialLoad else { return }
        didStartInitialLoad = true

        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            // Proceed even if audio session activation fails on simulator
        }

        AdBlockEngine.shared.prepare()
        if let baseRules = await AdBlockEngine.shared.ensureBaseRulesCompiled() {
            webView.configuration.userContentController.add(baseRules)
        }
        if let yoyoRules = AdBlockEngine.shared.yoyoRuleList {
            webView.configuration.userContentController.add(yoyoRules)
        } else {
            Task { [weak self] in
                if let yoyoRules = await AdBlockEngine.shared.ensureYoyoRulesCompiled(),
                   let self, !self.didTeardown {
                    self.webView.configuration.userContentController.add(yoyoRules)
                }
            }
        }

        webView.load(URLRequest(url: initialURL))
    }

    func teardown() {
        guard !didTeardown else { return }
        didTeardown = true

        observers.forEach { $0.invalidate() }
        observers.removeAll()
        webView.stopLoading()
        webView.configuration.userContentController.removeScriptMessageHandler(forName: Self.messageHandlerName)
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
    }

    func submitAddressInput(_ raw: String) {
        guard let url = resolveURLOrSearch(from: raw) else { return }
        allowedRootDomain = Self.rootDomain(for: url.host)
        currentURL = url
        addressText = url.absoluteString
        detectedStream = nil
        webView.load(URLRequest(url: url))
    }

    func syncAddressBarToCurrentURL() {
        if let currentURL {
            addressText = currentURL.absoluteString
        }
    }

    func goBack() {
        guard webView.canGoBack else { return }
        allowBackForwardCrossDomain = true
        detectedStream = nil
        webView.goBack()
    }

    func goForward() {
        guard webView.canGoForward else { return }
        allowBackForwardCrossDomain = true
        detectedStream = nil
        webView.goForward()
    }

    func reload() {
        detectedStream = nil
        if webView.url != nil {
            webView.reload()
        } else {
            allowedRootDomain = Self.rootDomain(for: initialURL.host)
            webView.load(URLRequest(url: initialURL))
        }
    }

    func toggleStrictRedirectLock() {
        strictRedirectLock.toggle()
        if strictRedirectLock {
            allowedRootDomain = Self.rootDomain(for: (currentURL ?? initialURL).host)
        }
    }

    func buildNativePlayback() async -> NativeStreamPlayback? {
        guard let stream = detectedStream,
              let url = URL(string: stream.m3u8Url) else {
            return nil
        }

        webView.evaluateJavaScript(
            "document.querySelectorAll('video').forEach(function(v){try{v.pause();}catch(e){}});",
            completionHandler: nil
        )

        var headers: [String: String] = [:]
        if !stream.referer.isEmpty { headers["Referer"] = stream.referer }
        if !stream.origin.isEmpty { headers["Origin"] = stream.origin }
        if !stream.userAgent.isEmpty { headers["User-Agent"] = stream.userAgent }

        let cookies = await webView.configuration.websiteDataStore.httpCookieStore.allCookies()
        var options: [String: Any] = ["AVURLAssetHTTPHeaderFieldsKey": headers]
        if !cookies.isEmpty {
            options[AVURLAssetHTTPCookiesKey] = cookies
        }

        let asset = AVURLAsset(url: url, options: options)
        let title = (webView.title?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false)
            ? webView.title!
            : (url.host ?? "Live Stream")
        return NativeStreamPlayback(title: title, asset: asset)
    }

    // MARK: - WKUIDelegate (Popup & Alert Blocking)

    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if navigationAction.navigationType == .linkActivated,
           let targetURL = navigationAction.request.url,
           let scheme = targetURL.scheme?.lowercased(),
           scheme == "http" || scheme == "https",
           let targetHost = targetURL.host,
           !AdBlockEngine.shared.isBlockedHost(targetHost),
           let currentRoot = Self.rootDomain(for: (webView.url ?? currentURL)?.host),
           let targetRoot = Self.rootDomain(for: targetHost),
           targetRoot == currentRoot {
            webView.load(navigationAction.request)
            return nil
        }

        blockedCount += 1
        return nil
    }

    func webView(
        _ webView: WKWebView,
        runJavaScriptAlertPanelWithMessage message: String,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping () -> Void
    ) {
        blockedCount += 1
        completionHandler()
    }

    // MARK: - WKNavigationDelegate (Domain, Scheme & Strict Redirect Blocking)

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel)
            return
        }

        if AdBlockEngine.shared.isStreamingOrPlayerURL(url) {
            decisionHandler(.allow)
            return
        }

        let scheme = url.scheme?.lowercased() ?? ""
        let isMainFrame = navigationAction.targetFrame?.isMainFrame == true

        if scheme != "http" && scheme != "https" {
            let allowedSubframeInternal = !isMainFrame && (
                url.absoluteString == "about:blank" ||
                url.absoluteString == "about:srcdoc"
            )
            if allowedSubframeInternal {
                decisionHandler(.allow)
                return
            }
            blockedCount += 1
            decisionHandler(.cancel)
            return
        }

        if !isMainFrame {
            if let host = url.host, AdBlockEngine.shared.isBaseBlockedHost(host) {
                blockedCount += 1
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
            return
        }

        if let host = url.host, AdBlockEngine.shared.isBlockedHost(host) {
            blockedCount += 1
            decisionHandler(.cancel)
            return
        }

        if navigationAction.navigationType == .backForward && allowBackForwardCrossDomain {
            allowBackForwardCrossDomain = false
            allowedRootDomain = Self.rootDomain(for: url.host)
            decisionHandler(.allow)
            return
        }
        allowBackForwardCrossDomain = false

        if strictRedirectLock,
           let allowedRoot = allowedRootDomain,
           let targetRoot = Self.rootDomain(for: url.host),
           targetRoot != allowedRoot {
            blockedCount += 1
            decisionHandler(.cancel)
            return
        }

        if !strictRedirectLock, let targetRoot = Self.rootDomain(for: url.host) {
            allowedRootDomain = targetRoot
        }

        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        detectedStream = nil
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        allowBackForwardCrossDomain = false
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        allowBackForwardCrossDomain = false
    }

    // MARK: - WKScriptMessageHandler

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        guard message.name == Self.messageHandlerName else { return }
        if let dict = message.body as? [String: Any],
           let type = dict["type"] as? String,
           type == "m3u8_detected" {
            if let m3u8Url = dict["m3u8Url"] as? String, !m3u8Url.isEmpty {
                let incoming = DetectedHlsStream(
                    m3u8Url: m3u8Url,
                    referer: (dict["referer"] as? String) ?? "",
                    origin: (dict["origin"] as? String) ?? "",
                    userAgent: (dict["userAgent"] as? String) ?? ""
                )
                updateDetectedStream(incoming)
            }
            return
        }
        blockedCount += 1
    }

    private func updateDetectedStream(_ incoming: DetectedHlsStream) {
        let lowerNew = incoming.m3u8Url.lowercased()
        if (lowerNew.contains("audio") || lowerNew.contains("subtitle") || lowerNew.contains("caption")) && detectedStream != nil {
            return
        }
        if let existing = detectedStream,
           let existingURL = URL(string: existing.m3u8Url),
           let newURL = URL(string: incoming.m3u8Url),
           existingURL.host == newURL.host {
            let lowerOld = existing.m3u8Url.lowercased()
            let oldIsMaster = lowerOld.contains("master") || lowerOld.contains("playlist") || lowerOld.contains("index")
            let newIsMaster = lowerNew.contains("master") || lowerNew.contains("playlist") || lowerNew.contains("index")
            let sameDir = existingURL.deletingLastPathComponent().path == newURL.deletingLastPathComponent().path
            if oldIsMaster && !newIsMaster && sameDir {
                return
            }
        }
        detectedStream = incoming
    }

    // MARK: - Helpers

    private func installObservers() {
        observers = [
            webView.observe(\.url, options: [.new]) { [weak self] webView, _ in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.currentURL = webView.url
                }
            },
            webView.observe(\.canGoBack, options: [.new]) { [weak self] webView, _ in
                Task { @MainActor [weak self] in
                    self?.canGoBack = webView.canGoBack
                }
            },
            webView.observe(\.canGoForward, options: [.new]) { [weak self] webView, _ in
                Task { @MainActor [weak self] in
                    self?.canGoForward = webView.canGoForward
                }
            },
            webView.observe(\.isLoading, options: [.new]) { [weak self] webView, _ in
                Task { @MainActor [weak self] in
                    self?.isLoading = webView.isLoading
                }
            },
            webView.observe(\.estimatedProgress, options: [.new]) { [weak self] webView, _ in
                Task { @MainActor [weak self] in
                    self?.estimatedProgress = webView.estimatedProgress
                }
            }
        ]
    }

    private func resolveURLOrSearch(from raw: String) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        let lower = trimmed.lowercased()
        if (lower.hasPrefix("http://") || lower.hasPrefix("https://")),
           let url = URL(string: trimmed),
           let host = url.host,
           !host.isEmpty {
            return url
        }

        if !trimmed.contains(" "),
           (trimmed.contains(".") || lower.hasPrefix("localhost")),
           let url = URL(string: "https://" + trimmed),
           let host = url.host,
           !host.isEmpty {
            return url
        }

        var components = URLComponents(string: "https://duckduckgo.com/")!
        components.queryItems = [URLQueryItem(name: "q", value: trimmed)]
        return components.url
    }

    static func rootDomain(for host: String?) -> String? {
        guard let rawHost = host?.lowercased().trimmingCharacters(in: .whitespacesAndNewlines),
              !rawHost.isEmpty else {
            return nil
        }
        let cleanHost = rawHost.hasSuffix(".") ? String(rawHost.dropLast()) : rawHost
        if cleanHost == "localhost" || cleanHost.contains(":") ||
            cleanHost.allSatisfy({ $0.isNumber || $0 == "." }) {
            return cleanHost
        }

        let parts = cleanHost.split(separator: ".").map(String.init)
        guard parts.count >= 2 else { return cleanHost }

        let multiPartPrefixes: Set<String> = ["co", "com", "net", "org", "edu", "gov", "ac", "ne", "or"]
        if parts.count >= 3,
           parts[parts.count - 1].count == 2,
           multiPartPrefixes.contains(parts[parts.count - 2]) {
            return parts.suffix(3).joined(separator: ".")
        }
        return parts.suffix(2).joined(separator: ".")
    }

    // MARK: - Layer 3: Injected Player-Safe Popup/Banner Shield & HLS Detector

    private static let adBlockUserScript = #"""
    (function() {
        if (window.__dsAdBlockInstalled) { return; }
        window.__dsAdBlockInstalled = true;

        function postBridge(payload) {
            try {
                if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.adBlocker) {
                    window.webkit.messageHandlers.adBlocker.postMessage(payload);
                }
            } catch (e) {}
        }

        function reportBlock(kind) {
            postBridge({ type: kind });
        }

        // 1. Block window.open popups
        const blockedOpen = function() {
            reportBlock('window_open');
            return null;
        };
        try {
            Object.defineProperty(window, 'open', {
                value: blockedOpen,
                writable: false,
                configurable: false
            });
        } catch (e) {
            window.open = blockedOpen;
        }

        // 2. Disable window.alert and onbeforeunload traps
        try {
            Object.defineProperty(window, 'alert', {
                value: function() { reportBlock('alert'); },
                writable: false,
                configurable: false
            });
        } catch (e) {
            window.alert = function() { reportBlock('alert'); };
        }

        try {
            Object.defineProperty(window, 'onbeforeunload', {
                get: function() { return null; },
                set: function() { reportBlock('beforeunload'); },
                configurable: false
            });
        } catch (e) {
            window.onbeforeunload = null;
        }

        const origAddEventListener = EventTarget.prototype.addEventListener;
        EventTarget.prototype.addEventListener = function(type, listener, options) {
            if (typeof type === 'string' && type.toLowerCase() === 'beforeunload') {
                reportBlock('beforeunload');
                return;
            }
            return origAddEventListener.call(this, type, listener, options);
        };

        // 3. HLS (.m3u8) live stream detector across all frames
        function reportM3u8(rawUrl) {
            try {
                if (!rawUrl) { return; }
                const absUrl = new URL(String(rawUrl), location.href).href;
                const lower = absUrl.toLowerCase();
                if ((lower.indexOf('http://') !== 0 && lower.indexOf('https://') !== 0) ||
                    lower.indexOf('.m3u8') === -1) {
                    return;
                }
                postBridge({
                    type: 'm3u8_detected',
                    m3u8Url: absUrl,
                    referer: document.referrer || location.href,
                    origin: location.origin || '',
                    userAgent: navigator.userAgent || ''
                });
            } catch (e) {}
        }

        const origXhrOpen = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url) {
            reportM3u8(url);
            return origXhrOpen.apply(this, arguments);
        };

        if (typeof window.fetch === 'function') {
            const origFetch = window.fetch;
            window.fetch = function(input, init) {
                try {
                    const target = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                    reportM3u8(target);
                } catch (e) {}
                return origFetch.apply(this, arguments);
            };
        }

        function scanMediaElements() {
            try {
                const nodes = document.querySelectorAll('video, source');
                for (let i = 0; i < nodes.length; i++) {
                    reportM3u8(nodes[i].currentSrc || nodes[i].src);
                }
            } catch (e) {}
        }

        document.addEventListener('loadstart', function(ev) {
            if (ev && ev.target) {
                reportM3u8(ev.target.currentSrc || ev.target.src);
            }
        }, true);

        // 4. Player-safe fake VPN banner shield (never touches video/player elements)
        const PLAYER_SELECTOR = 'video, iframe, canvas, .jwplayer, .jw-wrapper, [data-player], [id*="player"], [class*="player"], .clappr-player';

        function isOrTouchesPlayer(el) {
            if (!el || el.nodeType !== 1) { return false; }
            try {
                if (el.matches && el.matches(PLAYER_SELECTOR)) { return true; }
                if (el.closest && el.closest(PLAYER_SELECTOR)) { return true; }
                if (el.querySelector && el.querySelector(PLAYER_SELECTOR)) { return true; }
            } catch (e) {}
            return false;
        }

        function hideFakeVpnBanners() {
            if (!document.body) { return; }
            const nodes = document.querySelectorAll('div, section, aside, a, p, span');
            for (let i = 0; i < nodes.length; i++) {
                const el = nodes[i];
                if (!el.isConnected) { continue; }
                if (el.closest && el.closest('[data-ds-banner-hidden="1"]')) { continue; }
                const tag = el.tagName;
                if (tag === 'HTML' || tag === 'BODY' || tag === 'MAIN') { continue; }
                if (isOrTouchesPlayer(el)) { continue; }

                const style = window.getComputedStyle(el);
                if (!style || style.display === 'none' || style.visibility === 'hidden') { continue; }

                const text = (el.innerText || '').trim();
                if (!text || text.length > 320) { continue; }
                if (text.indexOf('VPN Recommended') === -1 &&
                    text.indexOf('Tap to Install and Continue Watching') === -1) {
                    continue;
                }

                let target = el;
                while (target.parentElement) {
                    const parent = target.parentElement;
                    const pTag = parent.tagName;
                    if (pTag === 'BODY' || pTag === 'HTML' || pTag === 'MAIN') { break; }
                    if (isOrTouchesPlayer(parent)) { break; }
                    const pText = (parent.innerText || '').trim();
                    if (pText.length > text.length + 12) { break; }
                    const pRect = parent.getBoundingClientRect();
                    if (pRect.height > 220) { break; }
                    target = parent;
                }

                if (target.dataset && target.dataset.dsBannerHidden === '1') { continue; }
                target.style.setProperty('display', 'none', 'important');
                if (target.dataset) { target.dataset.dsBannerHidden = '1'; }
                reportBlock('vpn_banner');
            }
        }

        let bannerTimer = null;
        function scheduleBannerSweep() {
            if (bannerTimer) { return; }
            bannerTimer = setTimeout(function() {
                bannerTimer = null;
                hideFakeVpnBanners();
                scanMediaElements();
            }, 400);
        }

        const observer = new MutationObserver(function() {
            scheduleBannerSweep();
        });

        function startBannerShield() {
            const root = document.body || document.documentElement;
            if (!root) { return; }
            observer.observe(root, { childList: true, subtree: true });
            hideFakeVpnBanners();
            scanMediaElements();
        }

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', startBannerShield, { once: true });
        } else {
            startBannerShield();
        }
        window.addEventListener('load', function() {
            hideFakeVpnBanners();
            scanMediaElements();
        }, { once: true });
    })();
    """#
}

// MARK: - WKWebView SwiftUI Wrapper

private struct BrowserWebViewContainer: UIViewRepresentable {
    let webView: WKWebView

    func makeUIView(context: Context) -> WKWebView {
        webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

// MARK: - AdBlockBrowserView

struct AdBlockBrowserView: View {
    let initialURL: URL

    @Environment(\.dismiss) private var dismiss
    @StateObject private var viewModel: AdBlockBrowserViewModel
    @FocusState private var isAddressFocused: Bool
    @State private var nativePlayback: NativeStreamPlayback?

    init(initialURL: URL) {
        self.initialURL = initialURL
        _viewModel = StateObject(wrappedValue: AdBlockBrowserViewModel(initialURL: initialURL))
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            BrowserWebViewContainer(webView: viewModel.webView)
                .ignoresSafeArea(edges: .bottom)
        }
        .background(Color(red: 0.04, green: 0.04, blue: 0.06).ignoresSafeArea())
        .preferredColorScheme(.dark)
        .task {
            await viewModel.start()
        }
        .onChange(of: viewModel.currentURL) { _, _ in
            if !isAddressFocused {
                viewModel.syncAddressBarToCurrentURL()
            }
        }
        .onChange(of: isAddressFocused) { _, focused in
            if !focused {
                viewModel.syncAddressBarToCurrentURL()
            }
        }
        .fullScreenCover(item: $nativePlayback) { item in
            let episode = PlayerEpisode(
                id: item.id.uuidString,
                title: item.title,
                shortTitle: item.title,
                subtitle: "Live HLS",
                episodeCode: nil,
                stillURL: nil,
                prefersVLC: false
            )
            VideoPlayerView(
                episodes: [episode],
                initialEpisode: episode,
                engine: .avPlayer(item.asset),
                persistProgress: false
            )
        }
        .onDisappear {
            viewModel.teardown()
        }
    }

    private var topBar: some View {
        VStack(spacing: 8) {
            HStack(spacing: 12) {
                Button {
                    viewModel.teardown()
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 32, height: 32)
                        .background(Color.white.opacity(0.1))
                        .clipShape(Circle())
                }
                .accessibilityLabel("Close Browser")

                HStack(spacing: 6) {
                    Button {
                        viewModel.goBack()
                    } label: {
                        Image(systemName: "chevron.backward")
                            .font(.system(size: 16, weight: .semibold))
                            .frame(width: 30, height: 30)
                    }
                    .disabled(!viewModel.canGoBack)
                    .foregroundStyle(viewModel.canGoBack ? .white : .white.opacity(0.3))

                    Button {
                        viewModel.goForward()
                    } label: {
                        Image(systemName: "chevron.forward")
                            .font(.system(size: 16, weight: .semibold))
                            .frame(width: 30, height: 30)
                    }
                    .disabled(!viewModel.canGoForward)
                    .foregroundStyle(viewModel.canGoForward ? .white : .white.opacity(0.3))

                    Button {
                        viewModel.reload()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(width: 30, height: 30)
                    }
                    .foregroundStyle(.white)
                }

                Spacer()

                Button {
                    viewModel.toggleStrictRedirectLock()
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: viewModel.strictRedirectLock ? "lock.shield.fill" : "lock.shield")
                            .font(.system(size: 14, weight: .semibold))
                        Text(viewModel.strictRedirectLock ? "Lock ON" : "Lock OFF")
                            .font(.caption.weight(.semibold))
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(viewModel.strictRedirectLock ? Color.blue.opacity(0.24) : Color.white.opacity(0.08))
                    .foregroundStyle(viewModel.strictRedirectLock ? .blue : .secondary)
                    .clipShape(Capsule())
                }
                .accessibilityLabel("Strict Redirect Lock")

                HStack(spacing: 4) {
                    Image(systemName: "shield.lefthalf.filled")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.green)
                    Text("\(viewModel.blockedCount)")
                        .font(.caption.bold().monospacedDigit())
                        .foregroundStyle(.white)
                }
                .padding(.horizontal, 9)
                .padding(.vertical, 6)
                .background(Color.white.opacity(0.08))
                .clipShape(Capsule())
                .accessibilityLabel("\(viewModel.blockedCount) ads and popups blocked")
            }

            HStack(spacing: 8) {
                Image(systemName: viewModel.currentURL?.scheme == "https" ? "lock.fill" : "globe")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                TextField("Enter URL or search…", text: $viewModel.addressText)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.webSearch)
                    .submitLabel(.go)
                    .focused($isAddressFocused)
                    .onSubmit {
                        isAddressFocused = false
                        viewModel.submitAddressInput(viewModel.addressText)
                    }

                if isAddressFocused && !viewModel.addressText.isEmpty {
                    Button {
                        viewModel.addressText = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color.white.opacity(0.09))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            if viewModel.detectedStream != nil {
                Button {
                    Task {
                        nativePlayback = await viewModel.buildNativePlayback()
                    }
                } label: {
                    HStack(spacing: 8) {
                        Text("⚡ Native Player")
                            .font(.subheadline.bold())
                            .foregroundStyle(.white)
                        Spacer()
                        Text("60fps HLS • PiP")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.white.opacity(0.88))
                        Image(systemName: "play.fill")
                            .font(.caption.bold())
                            .foregroundStyle(.white)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(
                        LinearGradient(
                            colors: [
                                Color(red: 0.13, green: 0.78, blue: 0.38),
                                Color(red: 0.06, green: 0.58, blue: 0.28)
                            ],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .overlay(
                        Capsule()
                            .stroke(Color(red: 0.45, green: 0.98, blue: 0.62), lineWidth: 1.2)
                    )
                    .clipShape(Capsule())
                    .shadow(color: Color.green.opacity(0.65), radius: 8, x: 0, y: 0)
                }
                .accessibilityLabel("Play detected HLS stream in Native Player")
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: viewModel.detectedStream != nil)
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background(Color(red: 0.08, green: 0.09, blue: 0.13))
        .overlay(alignment: .bottom) {
            if viewModel.isLoading {
                ProgressView(value: max(viewModel.estimatedProgress, 0.08))
                    .progressViewStyle(.linear)
                    .tint(.blue)
                    .scaleEffect(x: 1, y: 0.6, anchor: .bottom)
            } else {
                Divider()
                    .background(Color.white.opacity(0.1))
            }
        }
    }
}
