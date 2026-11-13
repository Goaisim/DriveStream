import Foundation
import Network

final class LocalStreamProxy {
    static let shared = LocalStreamProxy()
    static let port: UInt16 = 8765

    private let queue = DispatchQueue(label: "com.drake.drivestream.proxy", qos: .userInitiated)
    private var listener: NWListener?
    private weak var authManager: AuthManager?

    private init() {}

    func start(authManager: AuthManager) {
        self.authManager = authManager
        queue.async { [weak self] in
            self?.ensureListenerRunning()
        }
    }

    func streamURL(for fileId: String) -> URL? {
        var components = URLComponents()
        components.scheme = "http"
        components.host = "127.0.0.1"
        components.port = Int(Self.port)
        components.path = "/stream"
        components.queryItems = [URLQueryItem(name: "fileId", value: fileId)]
        return components.url
    }

    // MARK: - Listener Setup

    private func ensureListenerRunning() {
        if let listener, listener.state == .ready || listener.state == .setup {
            return
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
                if case .failed = state {
                    self?.listener = nil
                }
            }
            newListener.newConnectionHandler = { [weak self] connection in
                self?.handleNewConnection(connection)
            }
            newListener.start(queue: queue)
            self.listener = newListener
        } catch {
            self.listener = nil
        }
    }

    private func handleNewConnection(_ connection: NWConnection) {
        let handler = ProxyConnectionHandler(connection: connection, authManager: authManager, queue: queue)
        handler.start()
    }
}

// MARK: - Per-Connection Streaming Handler

private final class ProxyConnectionHandler: NSObject, URLSessionDataDelegate {
    private let connection: NWConnection
    private weak var authManager: AuthManager?
    private let queue: DispatchQueue

    private var requestBuffer = Data()
    private var activeTask: URLSessionDataTask?
    private var urlSession: URLSession?
    private var isCancelled = false
    private var didRetryAuth = false

    private var requestMethod = "GET"
    private var targetFileId = ""
    private var clientRangeHeader: String?

    init(connection: NWConnection, authManager: AuthManager?, queue: DispatchQueue) {
        self.connection = connection
        self.authManager = authManager
        self.queue = queue
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
            guard let self, !self.isCancelled else { return }

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

        guard let components = URLComponents(string: "http://127.0.0.1" + rawTarget),
              components.path == "/stream",
              let fileId = components.queryItems?.first(where: { $0.name == "fileId" })?.value,
              !fileId.isEmpty else {
            sendSimpleError(status: 404, message: "Missing fileId")
            return
        }
        targetFileId = fileId

        for line in lines.dropFirst() {
            if let colonIndex = line.firstIndex(of: ":") {
                let name = line[..<colonIndex].trimmingCharacters(in: .whitespaces).lowercased()
                let value = line[line.index(after: colonIndex)...].trimmingCharacters(in: .whitespaces)
                if name == "range" {
                    clientRangeHeader = value
                }
            }
        }

        startUpstreamRequest(forceRefresh: false)
    }

    private func startUpstreamRequest(forceRefresh: Bool) {
        guard !isCancelled, let authManager else {
            sendSimpleError(status: 401, message: "Unauthorized")
            return
        }

        Task { [weak self] in
            guard let self else { return }
            do {
                let token = forceRefresh
                    ? try await authManager.refreshAccessToken()
                    : try await authManager.validAccessToken()
                self.queue.async {
                    self.launchDriveStream(accessToken: token)
                }
            } catch {
                self.queue.async {
                    self.sendSimpleError(status: 401, message: "OAuth Error")
                }
            }
        }
    }

    private func launchDriveStream(accessToken: String) {
        guard !isCancelled else { return }
        guard let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(targetFileId)?alt=media") else {
            sendSimpleError(status: 400, message: "Invalid File ID")
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = requestMethod == "HEAD" ? "HEAD" : "GET"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        if let range = clientRangeHeader {
            request.setValue(range, forHTTPHeaderField: "Range")
        }

        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 0

        let delegateQueue = OperationQueue()
        delegateQueue.underlyingQueue = queue
        delegateQueue.maxConcurrentOperationCount = 1

        let session = URLSession(configuration: config, delegate: self, delegateQueue: delegateQueue)
        self.urlSession = session

        let task = session.dataTask(with: request)
        self.activeTask = task
        task.resume()
    }

    // MARK: - URLSessionDataDelegate

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard !isCancelled, let http = response as? HTTPURLResponse else {
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

        let reason = HTTPURLResponse.localizedString(forStatusCode: http.statusCode).capitalized
        var headerText = "HTTP/1.1 \(http.statusCode) \(reason)\r\n"

        let forwardKeys = ["content-type", "content-length", "content-range", "accept-ranges", "etag", "last-modified"]
        var hasAcceptRanges = false
        for (rawKey, rawValue) in http.allHeaderFields {
            let key = String(describing: rawKey)
            let lower = key.lowercased()
            if forwardKeys.contains(lower) {
                headerText += "\(key): \(rawValue)\r\n"
                if lower == "accept-ranges" {
                    hasAcceptRanges = true
                }
            }
        }
        if !hasAcceptRanges && (http.statusCode == 200 || http.statusCode == 206) {
            headerText += "Accept-Ranges: bytes\r\n"
        }
        headerText += "Connection: close\r\n\r\n"

        let headerData = Data(headerText.utf8)
        connection.send(content: headerData, completion: .contentProcessed { [weak self] error in
            if error != nil {
                self?.teardown()
            }
        })

        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard !isCancelled, !data.isEmpty else { return }
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            if error != nil {
                self?.teardown()
            }
        })
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard !isCancelled else { return }
        if didRetryAuth && activeTask !== task {
            return
        }
        connection.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
            self?.teardown()
        })
    }

    // MARK: - Helpers

    private func sendSimpleError(status: Int, message: String) {
        guard !isCancelled else { return }
        let body = Data(message.utf8)
        let response = "HTTP/1.1 \(status) \(message)\r\nContent-Length: \(body.count)\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n"
        var payload = Data(response.utf8)
        payload.append(body)
        connection.send(content: payload, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
            self?.teardown()
        })
    }

    private func teardown() {
        guard !isCancelled else { return }
        isCancelled = true
        activeTask?.cancel()
        activeTask = nil
        urlSession?.invalidateAndCancel()
        urlSession = nil
        connection.cancel()
    }
}
