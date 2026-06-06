import SwiftUI

struct ConnectionView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        NavigationView {
            List {
                // BLE status
                Section("Bluetooth") {
                    HStack {
                        Circle()
                            .fill(app.ble.glassConnected ? Color.green : Color.red)
                            .frame(width: 10, height: 10)
                        Text(app.ble.glassConnected ? "Glass connected" : "Waiting for Glass…")
                        Spacer()
                        if !app.ble.glassConnected {
                            ProgressView().scaleEffect(0.7)
                        }
                    }
                    Text("Glass advertises on boot. Make sure Bluetooth is on.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                // WiFi media server
                Section("Media (WiFi)") {
                    HStack {
                        Circle()
                            .fill(app.wifi.connected ? Color.green : Color.secondary)
                            .frame(width: 10, height: 10)
                        Text(app.wifi.connected
                             ? "Media server connected"
                             : app.glassIP.isEmpty ? "Waiting for Glass IP…"
                                                   : "Connecting to \(app.glassIP)…")
                    }
                }

                // Phone battery
                Section("Phone battery") {
                    HStack {
                        Image(systemName: batteryIcon)
                            .foregroundColor(batteryColor)
                        Text(app.phoneBattery >= 0 ? "\(app.phoneBattery)%" : "Unknown")
                        Spacer()
                        Button("Push to Glass") {
                            if app.phoneBattery >= 0 {
                                app.ble.sendPhoneBattery(level: app.phoneBattery)
                            }
                        }
                        .buttonStyle(.bordered)
                        .disabled(!app.ble.glassConnected)
                    }
                }

                // Camera remote
                Section("Camera") {
                    Button {
                        app.triggerGlassCamera()
                    } label: {
                        Label("Trigger Glass Camera", systemImage: "camera")
                    }
                    .disabled(!app.ble.glassConnected)
                }
            }
            .navigationTitle("Glass Companion")
        }
    }

    private var batteryIcon: String {
        switch app.phoneBattery {
        case 80...:  return "battery.100"
        case 30...:  return "battery.50"
        default:     return "battery.25"
        }
    }

    private var batteryColor: Color {
        switch app.phoneBattery {
        case 80...:  return .green
        case 30...:  return .yellow
        default:     return .red
        }
    }
}
