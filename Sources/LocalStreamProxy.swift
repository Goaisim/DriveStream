import Foundation
import Network

final class LocalStreamProxy: ObservableObject {
    static let shared = LocalStreamProxy()
    static let port: UInt16 = 8765

    @MainActor @Published private(set) var lastStatus: String = "Proxy idle"

    private let queue = DispatchQueue(label: "com.drake.drivestream.proxy", qos: .userInitiated)
    private var listener: NWListener?
    private var activeHandlers: [ObjectIdentifier: ProxyConnectionHandler] = [:]
    private var readyWaiters: [CheckedContinuation<Int, Never>] = []

    private init() {}

    func start() {
        queue.async { [weak self] in
            self?.ensureListenerRunning()
        }
    }

    func ensureReady() async -> Int {
        await withCheckedContinuation { (continuation: CheckedContinuation<Int, Never>) in
            queue.async { [weak self] in
                guard let self else {
                    continuation.resume(returning: Int(Self.port))
                    return
                }
                self.ensureListenerRunning()
                if self.listener?.state == .ready {
                    continuation.resume(returning: Int(Self.port))
                    return
                }
                self.readyWaiters.append(continuation)
                self.queue.asyncAfter(deadline: .now() + 3.0) { [weak self] in
                    self?.flushReadyWaiters()
                }
            }
        }
    }

    func streamURL(for fileId: String, port: Int = Int(LocalStreamProxy.port)) -> URL? {
        var components = URLComponents()
        components.scheme = "http"
        components.host = "127.0.0.1"
        components.port = port
        components.path = "/stream"
        components.queryItems = [URLQueryItem(name: "fileId", value: fileId)]
        return components.url
    }

    fileprivate func publishStatus(_ message: String) {
        Task { @MainActor [weak self] in
            self?.lastStatus = message
        }
    }

    // MARK: - Listener Setup

    private func ensureListenerRunning() {
        if let listener {
            switch listener.state {
            case .ready, .setup:
                return
            default:
                break
            }
        }

        listener?.cancel()

        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        parameters.requiredLocalEndpoint = NWEndpoint.hostPort(
            host: .ipv4(.loopback),
            port: NWEndpoint.Port(rawValue: Self.port)!
        )

        do {
            let newListener = try NWListener(using: parameters)
            newListener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.publishStatus("Proxy listening :\(Self.port)")
                    self.flushReadyWaiters()
                case .waiting(let err):
                    self.publishStatus("Proxy waiting: \(err.localizedDescription)")
                case .failed(let err):
                    self.publishStatus("Proxy failed: \(err.localizedDescription)")
                    self.listener = nil
                    self.flushReadyWaiters()
                case .cancelled:
                    self.listener = nil
                    self.flushReadyWaiters()
                default:
                    break
                }
            }
            newListener.newConnectionHandler = { [weak self] connection in
                self?.handleNewConnection(connection)
            }
            newListener.start(queue: queue)
            self.listener = newListener
        } catch {
            publishStatus("Proxy error: \(error.localizedDescription)")
            self.listener = nil
            flushReadyWaiters()
        }
    }

    private func flushReadyWaiters() {
        let waiters = readyWaiters
        readyWaiters.removeAll()
        for waiter in waiters {
            waiter.resume(returning: Int(Self.port))
        }
    }

    private func handleNewConnection(_ connection: NWConnection) {
        let handler = ProxyConnectionHandler(connection: connection, queue: queue) { [weak self] finishedHandler in
            guard let self else { return }
            self.activeHandlers.removeValue(forKey: ObjectIdentifier(finishedHandler))
        }
        activeHandlers[ObjectIdentifier(handler)] = handler
        handler.start()
    }
}

// MARK: - Independent Per-Connection Streaming Handler

private final class ProxyConnectionHandler: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate {
    private let connection: NWConnection
    private let queue: DispatchQueue
    private let onFinished: (ProxyConnectionHandler) -> Void

    private var requestBuffer = Data()
    private var activeTask: URLSessionDataTask?
    private var urlSession: URLSession?
    private var isClosed = false
    private var didRetryAuth = false

    private var requestMethod = "GET"
    private var targetFileId = ""
    private var effectiveRangeHeader = "bytes=0-"
    private var currentAccessToken = ""

    init(
        connection: NWConnection,
        queue: DispatchQueue,
        onFinished: @escaping (ProxyConnectionHandler) -> Void
    ) {
        self.connection = connection
        self.queue = queue
        self.onFinished = onFinished
        super.init()
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .cancelled, .failed:
                self?.teardown()
            default:
                break
            }
        }
        connection.start(queue: queue)
        receiveHTTPHeader()
    }

    private func receiveHTTPHeader() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, isComplete, error in
            guard let self, !self.isClosed else { return }

            if let data, !data.isEmpty {
                self.requestBuffer.append(data)
                if let range = self.requestBuffer.range(of: Data("\r\n\r\n".utf8)) {
                    let headerData = self.requestBuffer.subdata(in: 0..<range.lowerBound)
                    self.processRequestHeader(headerData)
                    return
                }
            }

            if isComplete || error != nil || self.requestBuffer.count > 65_536 {
                self.sendSimpleError(status: 400, message: "Bad Request")
                return
            }

            self.receiveHTTPHeader()
        }
    }

    private func processRequestHeader(_ headerData: Data) {
        guard let headerString = String(data: headerData, encoding: .utf8) else {
            sendSimpleError(status: 400, message: "Invalid HTTP Header")
            return
        }

        let lines = headerString.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else {
            sendSimpleError(status: 400, message: "Empty Request")
            return
        }

        let parts = requestLine.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count >= 2 else {
            sendSimpleError(status: 400, message: "Malformed Request Line")
            return
        }

        requestMethod = String(parts[0]).uppercased()
        let rawTarget = String(parts[1])
        let normalizedTarget = rawTarget.hasPrefix("http://") || rawTarget.hasPrefix("https://")
            ? rawTarget
            : "http://127.0.0.1" + rawTarget

        guard let components = URLComponents(string: normalizedTarget),
              components.path == "/stream",
              let fileId = components.queryItems?.first(where: { $0.name == "fileId" })?.value,
              !fileId.isEmpty else {
            sendSimpleError(status: 404, message: "Missing fileId")
            return
        }
        targetFileId = fileId

        var parsedRange: String?
        for line in lines.dropFirst() {
            if let colonIndex = line.firstIndex(of: ":") {
                let name = line[..<colonIndex].trimmingCharacters(in: .whitespaces).lowercased()
                let value = line[line.index(after: colonIndex)...].trimmingCharacters(in: .whitespaces)
                if name == "range", !value.isEmpty {
                    parsedRange = value
                }
            }
        }
        effectiveRangeHeader = parsedRange ?? "bytes=0-"

        startUpstreamRequest(forceRefresh: false)
    }

    private func startUpstreamRequest(forceRefresh: Bool) {
        guard !isClosed else { return }

        Task { [weak self] in
            guard let self else { return }
            do {
                let token = forceRefresh
                    ? try await AuthManager.shared.refreshAccessToken()
                    : try await AuthManager.shared.validAccessToken()
                self.queue.async {
                    self.launchDriveStream(accessToken: token)
                }
            } catch {
                LocalStreamProxy.shared.publishStatus("OAuth token error: \(error.localizedDescription)")
                self.queue.async {
                    self.sendSimpleError(status: 401, message: "OAuth Error")
                }
            }
        }
    }

    private func launchDriveStream(accessToken: String) {
        guard !isClosed else { return }
        guard let url = URL(
            string: "https://www.googleapis.com/drive/v3/files/\(targetFileId)?alt=media&acknowledgeAbuse=true&supportsAllDrives=true"
        ) else {
            sendSimpleError(status: 400, message: "Invalid File ID")
            return
        }

        currentAccessToken = accessToken

        var request = URLRequest(url: url)
        request.httpMethod = requestMethod == "HEAD" ? "HEAD" : "GET"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        request.setValue(effectiveRangeHeader, forHTTPHeaderField: "Range")

        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 0
        config.httpAdditionalHeaders = ["Accept-Encoding": "identity"]

        let delegateQueue = OperationQueue()
        delegateQueue.underlyingQueue = queue
        delegateQueue.maxConcurrentOperationCount = 1

        let session = URLSession(configuration: config, delegate: self, delegateQueue: delegateQueue)
        self.urlSession = session

        LocalStreamProxy.shared.publishStatus("Connecting Drive (\(effectiveRangeHeader))…")
        let task = session.dataTask(with: request)
        self.activeTask = task
        task.resume()
    }

    // MARK: - URLSessionTaskDelegate & URLSessionDataDelegate

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        var redirected = request
        if let host = redirected.url?.host?.lowercased(),
           host.hasSuffix("googleapis.com") || host.hasSuffix("googleusercontent.com") {
            redirected.setValue("Bearer \(currentAccessToken)", forHTTPHeaderField: "Authorization")
        }
        redirected.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        redirected.setValue(effectiveRangeHeader, forHTTPHeaderField: "Range")
        completionHandler(redirected)
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard !isClosed, let http = response as? HTTPURLResponse else {
            completionHandler(.cancel)
            teardown()
            return
        }

        if http.statusCode == 401 && !didRetryAuth {
            didRetryAuth = true
            completionHandler(.cancel)
            urlSession?.invalidateAndCancel()
            urlSession = nil
            activeTask = nil
            startUpstreamRequest(forceRefresh: true)
            return
        }

        let contentType = http.value(forHTTPHeaderField: "Content-Type") ?? "video/x-matroska"
        let contentLength = http.value(forHTTPHeaderField: "Content-Length") ?? ""
        let contentRange = http.value(forHTTPHeaderField: "Content-Range") ?? ""

        let sizeLabel: String
        if let bytes = Int64(contentLength), bytes > 0 {
            sizeLabel = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
        } else {
            sizeLabel = "unknown size"
        }

        let headerText: String
        if http.statusCode == 206 || !contentRange.isEmpty {
            var lines = [
                "HTTP/1.1 \(http.statusCode) Partial Content",
                "Content-Type: \(contentType)",
                "Accept-Ranges: bytes"
            ]
            if !contentLength.isEmpty { lines.append("Content-Length: \(contentLength)") }
            if !contentRange.isEmpty { lines.append("Content-Range: \(contentRange)") }
            lines.append("Connection: close")
            headerText = lines.joined(separator: "\r\n") + "\r\n\r\n"
        } else {
            let reason = http.statusCode == 200
                ? "OK"
                : HTTPURLResponse.localizedString(forStatusCode: http.statusCode).capitalized
            var lines = [
                "HTTP/1.1 \(http.statusCode) \(reason)",
                "Content-Type: \(contentType)",
                "Accept-Ranges: bytes"
            ]
            if !contentLength.isEmpty { lines.append("Content-Length: \(contentLength)") }
            lines.append("Connection: close")
            headerText = lines.joined(separator: "\r\n") + "\r\n\r\n"
        }

        LocalStreamProxy.shared.publishStatus("Drive HTTP \(http.statusCode) (Range \(effectiveRangeHeader)) — \(sizeLabel)")

        connection.send(content: Data(headerText.utf8), completion: .contentProcessed { [weak self] error in
            if error != nil {
                dataTask.cancel()
                self?.teardown()
            }
        })

        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard !isClosed, !data.isEmpty else { return }
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            if error != nil {
                dataTask.cancel()
                self?.teardown()
            }
        })
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard !isClosed else { return }
        if didRetryAuth && activeTask !== task {
            return
        }
        if let error = error as NSError?, error.code != NSURLErrorCancelled {
            LocalStreamProxy.shared.publishStatus("Drive stream error: \(error.localizedDescription)")
        }
        connection.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
            self?.teardown()
        })
    }

    // MARK: - Helpers

    private func sendSimpleError(status: Int, message: String) {
        guard !isClosed else { return }
        let body = Data(message.utf8)
        let response = "HTTP/1.1 \(status) \(message)\r\nContent-Length: \(body.count)\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n"
        var payload = Data(response.utf8)
        payload.append(body)
        connection.send(content: payload, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
            self?.teardown()
        })
    }

    private func teardown() {
        guard !isClosed else { return }
        isClosed = true
        activeTask?.cancel()
        activeTask = nil
        urlSession?.invalidateAndCancel()
        urlSession = nil
        connection.cancel()
        onFinished(self)
    }
}
