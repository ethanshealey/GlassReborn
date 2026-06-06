import Foundation
import Combine

/**
 * Connects to the WebSocket server running on Glass (ws://glass-ip:8765).
 * Used for high-bandwidth operations: gallery listing and photo download.
 * iPhone is the client; Glass is the server.
 */
final class WiFiMediaClient: NSObject, ObservableObject {

    @Published var photos: [PhotoItem] = []
    @Published var connected = false

    private var webSocketTask: URLSessionWebSocketTask?
    private var session: URLSession!
    private var pendingFileData: (name: String, size: Int64, chunks: Data)?
    var onNewPhoto: ((String) -> Void)?

    override init() {
        super.init()
        session = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
    }

    struct PhotoItem: Identifiable {
        let id = UUID()
        let name: String
        let size: Int64
        let date: Date
        var localURL: URL?
    }

    // MARK: — Connection

    func connect(ip: String, port: Int = GlassProtocol.wifiPort) {
        guard let url = URL(string: "ws://\(ip):\(port)") else { return }
        disconnect()
        webSocketTask = session.webSocketTask(with: url)
        webSocketTask?.resume()
        connected = true
        receiveLoop()
        requestList()
    }

    func disconnect() {
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        connected = false
    }

    func requestList(offset: Int = 0, count: Int = 50) {
        send(["t": "LIST", "offset": offset, "count": count])
    }

    func downloadPhoto(name: String) {
        send(["t": "DOWNLOAD", "name": name])
    }

    // MARK: — Private

    private func send(_ dict: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let text = String(data: data, encoding: .utf8) else { return }
        webSocketTask?.send(.string(text)) { _ in }
    }

    private func receiveLoop() {
        webSocketTask?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let msg):
                self.handleMessage(msg)
                self.receiveLoop()
            case .failure:
                DispatchQueue.main.async { self.connected = false }
            }
        }
    }

    private func handleMessage(_ msg: URLSessionWebSocketTask.Message) {
        switch msg {
        case .string(let json):
            guard let data = json.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let t = obj["t"] as? String else { return }

            switch t {
            case "LIST_RESP":
                let items = (obj["items"] as? [[String: Any]]) ?? []
                let parsed = items.compactMap { item -> PhotoItem? in
                    guard let name = item["name"] as? String else { return nil }
                    let size = item["size"] as? Int64 ?? 0
                    let ms   = item["date"] as? Double ?? 0
                    return PhotoItem(name: name, size: size, date: Date(timeIntervalSince1970: ms / 1000))
                }
                DispatchQueue.main.async { self.photos = parsed }

            case "FILE_START":
                let name = (obj["name"] as? String) ?? ""
                let size = (obj["size"] as? Int64) ?? 0
                pendingFileData = (name: name, size: size, chunks: Data())

            case "NEW_PHOTO":
                if let name = obj["name"] as? String {
                    DispatchQueue.main.async { self.onNewPhoto?(name) }
                    requestList()
                }
            default: break
            }

        case .data(let raw):
            // Binary chunk from FILE_START → accumulate
            if var pending = pendingFileData {
                pending.chunks.append(raw)
                if pending.chunks.count >= pending.size {
                    savePhoto(name: pending.name, data: pending.chunks)
                    pendingFileData = nil
                } else {
                    pendingFileData = pending
                }
            }

        @unknown default: break
        }
    }

    private func savePhoto(name: String, data: Data) {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("GlassPhotos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(name)
        try? data.write(to: url)
        DispatchQueue.main.async {
            if let idx = self.photos.firstIndex(where: { $0.name == name }) {
                self.photos[idx] = PhotoItem(name: name, size: Int64(data.count),
                                              date: Date(), localURL: url)
            }
        }
    }
}

extension WiFiMediaClient: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        DispatchQueue.main.async { self.connected = false }
    }
}
