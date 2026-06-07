import Foundation

final class AIService: NSObject {

    private let apiKey: String
    private let model    = "claude-haiku-4-5-20251001"
    private let endpoint = URL(string: "https://api.anthropic.com/v1/messages")!

    private var onChunk: ((String) -> Void)?
    private var onDone:  (() -> Void)?
    private var onError: ((Error) -> Void)?
    private var buffer   = ""
    private var task: URLSessionDataTask?
    private lazy var session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)

    init(apiKey: String) {
        self.apiKey = apiKey
    }

    func query(
        _ text: String,
        context: String = "You are a concise assistant for Google Glass. Keep answers under 3 sentences.",
        onChunk: @escaping (String) -> Void,
        onDone:  @escaping () -> Void,
        onError: @escaping (Error) -> Void
    ) {
        self.onChunk = onChunk
        self.onDone  = onDone
        self.onError = onError
        self.buffer  = ""

        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json",  forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey,              forHTTPHeaderField: "x-api-key")
        request.setValue("2023-06-01",        forHTTPHeaderField: "anthropic-version")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")

        let body: [String: Any] = [
            "model": model,
            "max_tokens": 256,
            "stream": true,
            "system": context,
            "messages": [["role": "user", "content": text]]
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        task = session.dataTask(with: request)
        task?.resume()
    }

    private func processSSELine(_ line: String) {
        guard line.hasPrefix("data: ") else { return }
        let json = String(line.dropFirst(6))
        if json == "[DONE]" { return }
        guard let d   = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let delta = (obj["delta"] as? [String: Any])?["text"] as? String
        else { return }
        DispatchQueue.main.async { self.onChunk?(delta) }
    }
}

extension AIService: URLSessionDataDelegate {

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive data: Data) {
        buffer += String(data: data, encoding: .utf8) ?? ""
        // Process complete SSE lines as they arrive
        while let range = buffer.range(of: "\n") {
            let line = String(buffer[buffer.startIndex..<range.lowerBound])
            buffer.removeSubrange(buffer.startIndex..<range.upperBound)
            processSSELine(line)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    didCompleteWithError error: Error?) {
        if let error {
            DispatchQueue.main.async { self.onError?(error) }
        } else {
            DispatchQueue.main.async { self.onDone?() }
        }
    }
}
