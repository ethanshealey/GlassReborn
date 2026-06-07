import Foundation
import Combine

final class WiFiMediaClient: NSObject, ObservableObject {

    @Published var photos: [PhotoItem] = []
    @Published var connected = false
    @Published var downloadProgress: (done: Int, total: Int)? = nil

    private var webSocketTask: URLSessionWebSocketTask?
    private var session: URLSession!
    private var pendingFileData: (name: String, chunks: Data)?
    private var downloadQueue: [String] = []
    private var isDownloading = false
    var onNewPhoto: ((String) -> Void)?

    private let photoDir: URL = {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir  = docs.appendingPathComponent("GlassPhotos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    override init() {
        super.init()
        session = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
    }

    struct PhotoItem: Identifiable {
        let id   = UUID()
        let name: String
        let size: Int64
        let date: Date
        var localURL: URL?
    }

    // MARK: — Connection

    private var connectedURL: URL?

    func connect(ip: String, port: Int = GlassProtocol.wifiPort) {
        guard let url = URL(string: "ws://\(ip):\(port)") else { return }
        if connected && connectedURL == url { return }
        disconnect()
        connectedURL = url
        webSocketTask = session.webSocketTask(with: url)
        webSocketTask?.resume()
        receiveLoop()
    }

    func disconnect() {
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask  = nil
        connectedURL   = nil
        connected      = false
        downloadQueue  = []
        isDownloading  = false
        downloadProgress = nil
    }

    func requestList(offset: Int = 0, count: Int = 50) {
        send(["t": "LIST", "offset": offset, "count": count])
    }

    // MARK: — Private

    private func send(_ dict: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let text = String(data: data, encoding: .utf8) else { return }
        webSocketTask?.send(.string(text)) { error in
            if let error { print("WiFi send error: \(error)") }
        }
    }

    private func receiveLoop() {
        webSocketTask?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let msg):
                self.handleMessage(msg)
                self.receiveLoop()
            case .failure(let err):
                print("WiFi receive error: \(err)")
                DispatchQueue.main.async { self.connected = false }
            }
        }
    }

    private func handleMessage(_ msg: URLSessionWebSocketTask.Message) {
        switch msg {
        case .string(let json):
            guard let data = json.data(using: .utf8),
                  let obj  = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let t    = obj["t"] as? String else { return }

            switch t {
            case "LIST_RESP":
                let items = (obj["items"] as? [[String: Any]]) ?? []
                let parsed: [PhotoItem] = items.compactMap { item in
                    guard let name = item["name"] as? String else { return nil }
                    let size = (item["size"] as? NSNumber)?.int64Value ?? 0
                    let ms   = (item["date"] as? NSNumber)?.doubleValue ?? 0
                    let localURL = localFileURL(name)
                    let exists   = FileManager.default.fileExists(atPath: localURL.path)
                    return PhotoItem(name: name, size: size,
                                     date: Date(timeIntervalSince1970: ms / 1000),
                                     localURL: exists ? localURL : nil)
                }
                DispatchQueue.main.async {
                    self.photos = parsed
                    self.enqueueMissing(parsed)
                }

            case "FILE_START":
                let name = (obj["name"] as? String) ?? ""
                pendingFileData = (name: name, chunks: Data())

            case "NEW_PHOTO":
                if let name = obj["name"] as? String {
                    DispatchQueue.main.async { self.onNewPhoto?(name) }
                    requestList()
                }

            default: break
            }

        case .data(let raw):
            if var pending = pendingFileData {
                pending.chunks.append(raw)
                pendingFileData = pending
                // Glass sends each photo as a single binary frame.
                // Save immediately when the frame is complete (next receive call
                // will be the next message, so this fires once per file).
                savePhoto(name: pending.name, data: pending.chunks)
                pendingFileData = nil
                processNextDownload()
            }

        @unknown default: break
        }
    }

    // MARK: — Auto-download queue

    private func enqueueMissing(_ list: [PhotoItem]) {
        let missing = list.filter { $0.localURL == nil }.map { $0.name }
        guard !missing.isEmpty else { return }
        downloadQueue.append(contentsOf: missing)
        downloadProgress = (done: 0, total: downloadQueue.count)
        if !isDownloading { processNextDownload() }
    }

    private func processNextDownload() {
        guard !downloadQueue.isEmpty else {
            isDownloading    = false
            downloadProgress = nil
            return
        }
        isDownloading = true
        let total = (downloadProgress?.total ?? downloadQueue.count)
        let done  = total - downloadQueue.count
        downloadProgress = (done: done, total: total)
        let name = downloadQueue.removeFirst()
        send(["t": "DOWNLOAD", "name": name])
    }

    // MARK: — Save

    private func localFileURL(_ name: String) -> URL { photoDir.appendingPathComponent(name) }

    private func savePhoto(name: String, data: Data) {
        guard !data.isEmpty else { return }
        let url = localFileURL(name)
        try? data.write(to: url)
        DispatchQueue.main.async {
            if let idx = self.photos.firstIndex(where: { $0.name == name }) {
                self.photos[idx].localURL = url
            }
        }
    }
}

extension WiFiMediaClient: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didOpenWithProtocol protocol: String?) {
        DispatchQueue.main.async {
            self.connected = true
            self.requestList()
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        DispatchQueue.main.async { self.connected = false }
    }
}
