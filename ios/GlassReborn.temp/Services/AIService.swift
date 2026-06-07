import Foundation

/**
 * Calls the Anthropic Claude API and streams the response token-by-token.
 * Uses claude-haiku-4-5 for fast, low-latency responses on a heads-up display.
 */
final class AIService {

    private let apiKey: String
    private let model = "claude-haiku-4-5-20251001"
    private let endpoint = URL(string: "https://api.anthropic.com/v1/messages")!

    init(apiKey: String) {
        self.apiKey = apiKey
    }

    /**
     * Sends a query and calls `onChunk` for each streamed text delta,
     * then calls `onDone` when the response is complete.
     */
    func query(
        _ text: String,
        context: String = "You are a concise assistant for Google Glass. Keep answers under 3 sentences.",
        onChunk: @escaping (String) -> Void,
        onDone: @escaping () -> Void,
        onError: @escaping (Error) -> Void
    ) {
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json",       forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey,                   forHTTPHeaderField: "x-api-key")
        request.setValue("2023-06-01",             forHTTPHeaderField: "anthropic-version")
        request.setValue("text/event-stream",      forHTTPHeaderField: "Accept")

        let body: [String: Any] = [
            "model": model,
            "max_tokens": 256,
            "stream": true,
            "system": context,
            "messages": [["role": "user", "content": text]]
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            if let error { DispatchQueue.main.async { onError(error) }; return }
            guard let data else { DispatchQueue.main.async { onDone() }; return }

            // Parse SSE lines
            let text = String(data: data, encoding: .utf8) ?? ""
            var accumulated = ""
            for line in text.components(separatedBy: "\n") {
                if line.hasPrefix("data: ") {
                    let json = String(line.dropFirst(6))
                    if json == "[DONE]" { break }
                    if let d = json.data(using: .utf8),
                       let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
                       let delta = (obj["delta"] as? [String: Any])?["text"] as? String {
                        accumulated += delta
                        DispatchQueue.main.async { onChunk(delta) }
                    }
                }
            }
            DispatchQueue.main.async { onDone() }
        }
        task.resume()
    }
}
