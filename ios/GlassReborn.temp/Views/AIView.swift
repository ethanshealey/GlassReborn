import SwiftUI

/** Lets you type a query and manually test the AI pipeline before using Glass voice. */
struct AIView: View {
    @EnvironmentObject var app: AppState
    @State private var query = ""
    @State private var response = ""
    @State private var isLoading = false

    var body: some View {
        NavigationView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Voice queries come in from Glass automatically.\nUse this to test the AI pipeline directly.")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding(.horizontal)

                HStack {
                    TextField("Type a question…", text: $query)
                        .textFieldStyle(.roundedBorder)
                    Button {
                        runQuery()
                    } label: {
                        Image(systemName: isLoading ? "stop.circle" : "arrow.up.circle.fill")
                            .font(.title2)
                    }
                    .disabled(query.isEmpty || app.apiKey.isEmpty)
                }
                .padding(.horizontal)

                if app.apiKey.isEmpty {
                    Text("⚠️ Set your Claude API key in Settings first.")
                        .font(.caption)
                        .foregroundColor(.orange)
                        .padding(.horizontal)
                }

                Divider()

                ScrollView {
                    Text(response.isEmpty ? "Response appears here…" : response)
                        .foregroundColor(response.isEmpty ? .secondary : .primary)
                        .padding()
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Spacer()
            }
            .navigationTitle("AI Assistant")
        }
    }

    private func runQuery() {
        isLoading = true
        response = ""
        let ai = AIService(apiKey: app.apiKey)
        ai.query(
            query,
            onChunk: { chunk in
                response += chunk
                app.ble.sendAIResponse(text: chunk, done: false)
            },
            onDone: {
                isLoading = false
                app.ble.sendAIResponse(text: "", done: true)
            },
            onError: { err in
                isLoading = false
                response = "Error: \(err.localizedDescription)"
                app.ble.sendAIResponse(text: response, done: true)
            }
        )
    }
}
