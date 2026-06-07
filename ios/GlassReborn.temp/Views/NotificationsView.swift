import SwiftUI

struct NotificationsView: View {
    @EnvironmentObject var app: AppState
    @State private var showManual = false
    @State private var manualApp   = ""
    @State private var manualTitle = ""
    @State private var manualBody  = ""

    var body: some View {
        NavigationView {
            Group {
                if app.notifications.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "bell.slash")
                            .font(.system(size: 48))
                            .foregroundColor(.secondary)
                        Text("No notifications sent to Glass yet.")
                            .foregroundColor(.secondary)
                        Text("Use the + button to push a manual notification,\nor set up iOS Shortcuts to forward app alerts.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(app.notifications) { n in
                            NotificationRow(notification: n)
                        }
                        .onDelete { idx in
                            app.notifications.remove(atOffsets: idx)
                        }
                    }
                }
            }
            .navigationTitle("Notifications")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showManual = true } label: {
                        Image(systemName: "plus")
                    }
                    .disabled(!app.ble.glassConnected)
                }
            }
            .sheet(isPresented: $showManual) {
                ManualNotificationSheet(
                    app: $manualApp, title: $manualTitle, body: $manualBody
                ) {
                    app.pushNotification(app: manualApp, title: manualTitle, body: manualBody)
                    manualApp = ""; manualTitle = ""; manualBody = ""
                    showManual = false
                }
            }
        }
    }
}

private struct NotificationRow: View {
    let notification: AppState.PhoneNotification

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(notification.app.uppercased())
                    .font(.caption)
                    .foregroundColor(.cyan)
                Spacer()
                Text(notification.date, style: .time)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            Text(notification.title)
                .font(.headline)
            if !notification.body.isEmpty {
                Text(notification.body)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct ManualNotificationSheet: View {
    @Binding var app: String
    @Binding var title: String
    @Binding var body: String
    let onSend: () -> Void
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationView {
            Form {
                Section("App Name") {
                    TextField("e.g. Messages", text: $app)
                }
                Section("Title") {
                    TextField("Notification title", text: $title)
                }
                Section("Message") {
                    TextField("Notification body", text: $body)
                }
            }
            .navigationTitle("Push Notification")
            .navigationBarItems(
                leading: Button("Cancel") { dismiss() },
                trailing: Button("Send") { onSend() }
                    .disabled(title.isEmpty)
            )
        }
    }
}
