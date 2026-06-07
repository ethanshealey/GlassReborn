import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var app: AppState
    @State private var keyDraft = ""
    @State private var showKeyCopied = false

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Claude API Key"),
                        footer: Text("Get your key at console.anthropic.com. Used for the AI assistant on Glass.")) {
                    SecureField("sk-ant-…", text: $keyDraft)
                        .onAppear { keyDraft = app.apiKey }
                    Button("Save Key") {
                        app.apiKey = keyDraft
                    }
                    .disabled(keyDraft == app.apiKey)
                }

                Section("Glass Status") {
                    LabeledContent("BLE", value: app.ble.glassConnected ? "Connected ✓" : "Disconnected")
                    LabeledContent("WiFi", value: app.wifi.connected ? "Connected ✓" : "Disconnected")
                    LabeledContent("Glass IP", value: app.glassIP.isEmpty ? "—" : app.glassIP)
                    LabeledContent("Phone Battery", value: app.phoneBattery >= 0 ? "\(app.phoneBattery)%" : "—")
                }

                Section(header: Text("iOS Shortcuts Setup"),
                        footer: Text("To forward notifications from any app to Glass, create a Shortcut: trigger on 'When I receive a notification from [App]', action: Open URL: glassreborn://notify?app=App&title=[Title]&body=[Body]")) {
                    Text("Shortcut URL scheme:")
                        .font(.caption)
                    Text("glassreborn://notify")
                        .font(.system(.caption, design: .monospaced))
                        .foregroundColor(.cyan)
                }

                Section(header: Text("Glass Clock"),
                        footer: Text("The timezone used for the clock on Glass.")) {
                    Picker("Timezone", selection: $app.glassTimezone) {
                        ForEach(timezones, id: \.id) { tz in
                            Text(tz.label).tag(tz.id)
                        }
                    }
                }

                Section("About") {
                    LabeledContent("Version", value: "1.0")
                    LabeledContent("Protocol", value: "BLE (peripheral) + WiFi WS")
                    LabeledContent("AI Model", value: "claude-haiku-4-5")
                }
            }
            .navigationTitle("Settings")
        }
        .onOpenURL { url in
            handleShortcutURL(url)
        }
    }

    private struct TZ: Identifiable {
        let id: String   // IANA timezone identifier
        let label: String
    }
    private let timezones: [TZ] = [
        TZ(id: "America/New_York",    label: "US Eastern"),
        TZ(id: "America/Chicago",     label: "US Central"),
        TZ(id: "America/Denver",      label: "US Mountain"),
        TZ(id: "America/Los_Angeles", label: "US Pacific"),
        TZ(id: "America/Anchorage",   label: "US Alaska"),
        TZ(id: "Pacific/Honolulu",    label: "US Hawaii"),
        TZ(id: "Europe/London",       label: "UK / GMT"),
        TZ(id: "Europe/Paris",        label: "Central Europe"),
        TZ(id: "Asia/Tokyo",          label: "Japan"),
        TZ(id: "Australia/Sydney",    label: "Australia East"),
        TZ(id: "UTC",                 label: "UTC"),
    ]

    private func handleShortcutURL(_ url: URL) {
        guard url.scheme == "glassreborn",
              url.host == "notify",
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }
        let params = Dictionary(uniqueKeysWithValues:
            (comps.queryItems ?? []).compactMap { item in
                item.value.map { (item.name, $0) }
            }
        )
        app.pushNotification(
            app:   params["app"]   ?? "Shortcut",
            title: params["title"] ?? "",
            body:  params["body"]  ?? ""
        )
    }
}
