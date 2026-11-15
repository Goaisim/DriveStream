import AVFoundation
import SwiftUI
import UIKit
import WebKit

// MARK: - Layer 1: AdBlock Engine (WKContentRuleList + Cached Host Set)

@MainActor
final class AdBlockEngine {
    static let shared = AdBlockEngine()

    private static let baseRuleIdentifier = "DriveStreamBaseBlocker_v1"
    private static let yoyoRuleIdentifier = "DriveStreamYoyoBlocker_v1"
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

    private static let blockedURLPatterns: [String] = [
        "/ads/",
        "/adserver",
        "/popunder",
        "/pop\\.js",
        "banner\\.js",
        "prebid",
        "vast\\.xml"
    ]

    private static let hiddenCSSSelector =
        ".ad, .ads, .adsbygoogle, [id^=\"google_ads\"], [class*=\"popup\"], [id*=\"popup\"], iframe[src*=\"ads\"], iframe[src*=\"doubleclick\"]"

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
        let lower = host.lowercased()
        if blockedDomains.contains(lower) {
            return true
        }
        var slice = lower[...]
        while let dot = slice.firstIndex(of: ".") {
            slice = slice[slice.index(after: dot)...]
            if slice.contains("."), blockedDomains.contains(String(slice)) {
                return true
            }
        }
        return false
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
        var rules: [[String: Any]] = []

        for domain in baseBlockedDomains {
            let escaped = domain.replacingOccurrences(of: ".", with: "\\.")
            rules.append([
                "trigger": [
                    "url-filter": "^https?://([^/:]*\\.)?\(escaped)[:/?#]"
                ],
                "action": [
                    "type": "block"
                ]
            ])
        }

        for pattern in blockedURLPatterns {
            rules.append([
                "trigger": [
                    "url-filter": pattern
                ],
                "action": [
                    "type": "block"
                ]
            ])
        }

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
        let rules: [[String: Any]] = domains.map { domain in
            let escaped = domain.replacingOccurrences(of: ".", with: "\\.")
            return [
                "trigger": [
                    "url-filter": "^https?://([^/:]*\\.)?\(escaped)[:/?#]"
                ],
                "action": [
                    "type": "block"
                ]
            ]
        }
        guard !rules.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: rules) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
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

    private let initialURL: URL
    private var allowedRootDomain: String?
    private var allowBackForwardCrossDomain = false
    private var didStartInitialLoad = false
    private var didTeardown = false
    private var observers: [NSKeyValueObservation] = []

    private static let messageHandlerName = "adBlocker"

    init(initialURL: URL) {
        self.initialURL = initialURL
        self.currentURL = initialURL
        self.addressText = initialURL.absoluteString
        self.allowedRootDomain = Self.rootDomain(for: initialURL.host)

        let config = WKWebViewConfiguration()
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
        webView.goBack()
    }

    func goForward() {
        guard webView.canGoForward else { return }
        allowBackForwardCrossDomain = true
        webView.goForward()
    }

    func reload() {
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

    // MARK: - WKUIDelegate (Popup & Alert Blocking)

    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        blockedCount += 1

        guard navigationAction.navigationType == .linkActivated,
              let targetURL = navigationAction.request.url,
              let scheme = targetURL.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let targetHost = targetURL.host,
              !AdBlockEngine.shared.isBlockedHost(targetHost),
              let currentRoot = Self.rootDomain(for: (webView.url ?? currentURL)?.host),
              let targetRoot = Self.rootDomain(for: targetHost),
              targetRoot == currentRoot else {
            return nil
        }

        webView.load(navigationAction.request)
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

        let scheme = url.scheme?.lowercased() ?? ""
        let isMainFrame = navigationAction.targetFrame?.isMainFrame ?? true

        if scheme != "http" && scheme != "https" {
            let allowedSubframeInternal = !isMainFrame && (
                url.absoluteString == "about:blank" ||
                url.absoluteString == "about:srcdoc" ||
                scheme == "blob" ||
                scheme == "data"
            )
            if allowedSubframeInternal {
                decisionHandler(.allow)
                return
            }
            blockedCount += 1
            decisionHandler(.cancel)
            return
        }

        if let host = url.host, AdBlockEngine.shared.isBlockedHost(host) {
            blockedCount += 1
            decisionHandler(.cancel)
            return
        }

        if isMainFrame {
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
        }

        decisionHandler(.allow)
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
        blockedCount += 1
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

    // MARK: - Layer 3: Injected Document-Start Protection Script

    private static let adBlockUserScript = #"""
    (function() {
        if (window.__dsAdBlockInstalled) { return; }
        window.__dsAdBlockInstalled = true;

        function reportBlock(kind) {
            try {
                if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.adBlocker) {
                    window.webkit.messageHandlers.adBlocker.postMessage({ type: kind });
                }
            } catch (e) {}
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

        // 3. Remove transparent click-hijack overlays & auto-click Skip Ad buttons
        function intersectsVideo(rect, videoRects) {
            for (let i = 0; i < videoRects.length; i++) {
                const v = videoRects[i];
                if (rect.left < v.right && rect.right > v.left && rect.top < v.bottom && rect.bottom > v.top) {
                    return true;
                }
            }
            return false;
        }

        function isTransparentOverlay(el, style) {
            const opacity = parseFloat(style.opacity);
            if (!isNaN(opacity) && opacity < 0.05) {
                return true;
            }
            const bg = (style.backgroundColor || '').toLowerCase();
            const bgImage = style.backgroundImage || 'none';
            if ((bg === 'transparent' || bg === 'rgba(0, 0, 0, 0)') && bgImage === 'none') {
                if (el.tagName === 'A') {
                    return true;
                }
                const text = (el.textContent || '').trim();
                if (text === '' && !el.querySelector('button, input, select, textarea, svg, img, video')) {
                    return true;
                }
            }
            return false;
        }

        const skipSelectors = [
            '.ytp-ad-skip-button',
            '.ytp-ad-skip-button-modern',
            '.ytp-skip-ad-button',
            '.vast-skip-button',
            '.jw-skip',
            '[class*="skip-ad" i]',
            '[class*="skip_ad" i]',
            '[id*="skip-ad" i]',
            '[id*="skip_ad" i]',
            '[aria-label*="skip ad" i]'
        ].join(',');

        function sweepOverlaysAndSkipAds() {
            if (!document.documentElement) { return; }

            const videos = document.querySelectorAll('video');
            const videoRects = [];
            for (let i = 0; i < videos.length; i++) {
                const r = videos[i].getBoundingClientRect();
                if (r.width > 40 && r.height > 40) {
                    videoRects.push(r);
                }
            }

            const vw = window.innerWidth || 0;
            const vh = window.innerHeight || 0;

            const candidates = document.querySelectorAll('div, a');
            for (let i = 0; i < candidates.length; i++) {
                const el = candidates[i];
                if (!el.isConnected) { continue; }
                if (el.querySelector('video')) { continue; }

                const style = window.getComputedStyle(el);
                if (!style) { continue; }
                if (style.display === 'none' || style.visibility === 'hidden' || style.pointerEvents === 'none') {
                    continue;
                }
                const pos = style.position;
                if (pos !== 'fixed' && pos !== 'absolute') { continue; }

                const z = parseInt(style.zIndex, 10);
                if (isNaN(z) || z <= 999) { continue; }

                const rect = el.getBoundingClientRect();
                if (rect.width < 120 || rect.height < 120) { continue; }

                const coversPlayer = intersectsVideo(rect, videoRects) ||
                    (vw > 0 && vh > 0 && rect.width >= vw * 0.45 && rect.height >= vh * 0.45);
                if (!coversPlayer) { continue; }

                if (isTransparentOverlay(el, style)) {
                    el.remove();
                    reportBlock('overlay');
                }
            }

            function tryClickSkipButton(btn) {
                if (!btn || btn.disabled || typeof btn.click !== 'function') { return; }
                if (btn.dataset && btn.dataset.dsSkipped === '1') { return; }
                const rect = btn.getBoundingClientRect();
                if (rect.width > 0 && rect.height > 0) {
                    if (btn.dataset) { btn.dataset.dsSkipped = '1'; }
                    btn.click();
                    reportBlock('skip_ad');
                }
            }

            const explicitSkipButtons = document.querySelectorAll(skipSelectors);
            for (let i = 0; i < explicitSkipButtons.length; i++) {
                tryClickSkipButton(explicitSkipButtons[i]);
            }

            const genericButtons = document.querySelectorAll('button, [role="button"]');
            for (let i = 0; i < genericButtons.length; i++) {
                const btn = genericButtons[i];
                const label = (btn.innerText || btn.textContent || '').trim();
                if (/^skip(\s+ads?)?$/i.test(label)) {
                    tryClickSkipButton(btn);
                }
            }
        }

        let domDirty = true;
        const observer = new MutationObserver(function() {
            domDirty = true;
        });

        function attachObserver() {
            const root = document.documentElement;
            if (!root) { return; }
            observer.observe(root, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['style', 'class']
            });
            sweepOverlaysAndSkipAds();
        }

        if (document.documentElement) {
            attachObserver();
        } else {
            document.addEventListener('DOMContentLoaded', attachObserver, { once: true });
        }
        window.addEventListener('load', function() { domDirty = true; }, { once: true });

        setInterval(function() {
            if (domDirty) {
                domDirty = false;
                sweepOverlaysAndSkipAds();
            }
        }, 800);
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
        }
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
