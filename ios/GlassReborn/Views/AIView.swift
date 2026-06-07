import SwiftUI

struct AIView: View {
    @EnvironmentObject var app: AppState
    @State private var query = ""
    @State private var isLoading = false
    @State private var selected: AppState.Conversation?

    var body: some View {
        NavigationView {
            Group {
                if app.conversations.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "brain")
                            .font(.system(size: 48))
                            .foregroundColor(.secondary)
                        Text("No questions yet.")
                            .foregroundColor(.secondary)
                        Text("Ask something on Glass or use the field below.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(app.conversations) { convo in
                            Button { selected = convo } label: {
                                ConversationRow(convo: convo)
                            }
                            .buttonStyle(.plain)
                        }
                        .onDelete { idx in
                            app.conversations.remove(atOffsets: idx)
                        }
                    }
                }
            }
            .navigationTitle("AI History")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if !app.conversations.isEmpty {
                        EditButton()
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                queryBar
            }
            .sheet(item: $selected) { convo in
                ConversationDetailView(convoId: convo.id)
                    .environmentObject(app)
            }
        }
    }

    private var queryBar: some View {
        HStack(spacing: 8) {
            TextField("Ask something…", text: $query)
                .textFieldStyle(.roundedBorder)
            Button {
                guard !query.isEmpty else { return }
                isLoading = true
                let q = query
                query = ""
                app.runAI(query: q)
                // isLoading resets when the first conversation finishes streaming
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    isLoading = false
                }
            } label: {
                Image(systemName: isLoading ? "ellipsis.circle" : "arrow.up.circle.fill")
                    .font(.title2)
                    .foregroundColor(isLoading ? .secondary : .cyan)
            }
            .disabled(query.isEmpty || app.apiKey.isEmpty || isLoading)
        }
        .padding(12)
        .background(.regularMaterial)
    }
}

private struct ConversationRow: View {
    let convo: AppState.Conversation

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(convo.query)
                    .font(.headline)
                    .lineLimit(1)
                Spacer()
                if convo.isStreaming {
                    ProgressView().scaleEffect(0.7)
                }
            }
            if convo.response.isEmpty {
                Text("Thinking…")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .italic()
            } else {
                Text(convo.response)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
            }
            Text(convo.date, style: .relative)
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 2)
    }
}

private struct ConversationDetailView: View {
    let convoId: UUID
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) var dismiss

    private var convo: AppState.Conversation? {
        app.conversations.first { $0.id == convoId }
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // Question
                    VStack(alignment: .leading, spacing: 4) {
                        Label("Question", systemImage: "questionmark.circle")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text(convo?.query ?? "")
                            .font(.title3)
                            .fontWeight(.semibold)
                    }

                    Divider()

                    // Response
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Label("Response", systemImage: "brain")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            if convo?.isStreaming == true {
                                ProgressView().scaleEffect(0.6)
                            }
                        }
                        if let text = convo?.response, !text.isEmpty {
                            Text(text)
                                .font(.body)
                        } else {
                            Text("Waiting for response…")
                                .foregroundColor(.secondary)
                                .italic()
                        }
                    }

                    if let date = convo?.date {
                        Divider()
                        Text(date, style: .date)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                        + Text(" at ")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                        + Text(date, style: .time)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle("Response")
            .navigationBarItems(trailing: Button("Done") { dismiss() })
        }
    }
}
