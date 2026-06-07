import SwiftUI
import UIKit
import Combine

@main
struct GlassRebornApp: App {
    @StateObject var appState = AppState()

    var body: some Scene {
        WindowGroup {
            MainView()
                .environmentObject(appState)
                .preferredColorScheme(.dark)
        }
    }
}

/**
 * Central state object that wires together BLE, AI, calls, and WiFi services.
 */
final class AppState: ObservableObject {

    // Services
    let ble  = BLEPeripheralService()
    let wifi = WiFiMediaClient()
    let ai:  AIService
    let callObserver = CallObserver()

    // Settings (persisted)
    @Published var apiKey: String {
        didSet { UserDefaults.standard.set(apiKey, forKey: "apiKey") }
    }

    @Published var glassTimezone: String {
        didSet {
            UserDefaults.standard.set(glassTimezone, forKey: "glassTimezone")
            if ble.glassConnected { ble.sendTimezone(glassTimezone) }
        }
    }

    // State
    @Published var notifications: [PhoneNotification] = []
    @Published var glassIP: String = ""
    @Published var phoneBattery: Int = UIDevice.current.batteryLevel >= 0
        ? Int(UIDevice.current.batteryLevel * 100) : -1

    struct PhoneNotification: Identifiable {
        let id: String
        let app: String
        let title: String
        let body: String
        let date: Date
    }

    init() {
        let key = UserDefaults.standard.string(forKey: "apiKey") ?? ""
        apiKey = key
        ai = AIService(apiKey: key)
        glassTimezone = UserDefaults.standard.string(forKey: "glassTimezone") ?? "America/New_York"
        UIDevice.current.isBatteryMonitoringEnabled = true
        setup()
    }

    private func setup() {
        // BLE: Glass commands → act on them
        ble.onCommandReceived = { [weak self] cmd in
            self?.handleGlassCommand(cmd)
        }
        ble.onConnectedCallback = { [weak self] in
            guard let self else { return }
            ble.sendTimezone(glassTimezone)
        }
        // BLE: Glass status (IP for WiFi) → connect WiFi
        ble.onStatusReceived = { [weak self] status in
            guard let self else { return }
            glassIP = status.ip
            if !status.ip.isEmpty { wifi.connect(ip: status.ip, port: status.port) }
        }
        // Calls → push to Glass
        callObserver.onCallStart = { [weak self] name, number in
            self?.ble.sendCallStart(name: name, number: number)
        }
        callObserver.onCallEnd = { [weak self] in
            self?.ble.sendCallEnd()
        }
        // Battery updates
        NotificationCenter.default.addObserver(
            forName: UIDevice.batteryLevelDidChangeNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            let pct = Int(UIDevice.current.batteryLevel * 100)
            self?.phoneBattery = pct
            self?.ble.sendPhoneBattery(level: pct)
        }
        // New Glass photo → refresh gallery
        wifi.onNewPhoto = { [weak self] name in
            self?.wifi.requestList()
        }
    }

    // MARK: — Command handler

    private func handleGlassCommand(_ cmd: GlassCommand) {
        switch cmd.cmd {
        case GlassProtocol.cmdVoiceInput:
            guard let text = cmd.text, !text.isEmpty else { return }
            runAI(query: text)
        case GlassProtocol.cmdTakePhoto:
            break // Glass took a photo itself; WiFi will notify us
        case GlassProtocol.cmdGalleryList:
            wifi.requestList(offset: cmd.offset ?? 0)
        case GlassProtocol.cmdDismissNotif:
            if let id = cmd.id {
                notifications.removeAll { $0.id == id }
            }
        default: break
        }
    }

    // MARK: — AI

    func runAI(query: String) {
        let aiRef = AIService(apiKey: apiKey) // use current key
        aiRef.query(
            query,
            onChunk: { [weak self] chunk in
                self?.ble.sendAIResponse(text: chunk, done: false)
            },
            onDone: { [weak self] in
                self?.ble.sendAIResponse(text: "", done: true)
            },
            onError: { [weak self] error in
                self?.ble.sendAIResponse(text: "Error: \(error.localizedDescription)", done: true)
            }
        )
    }

    // MARK: — Manual notification push

    func pushNotification(app: String, title: String, body: String) {
        let id = UUID().uuidString
        let n = PhoneNotification(id: id, app: app, title: title, body: body, date: Date())
        notifications.insert(n, at: 0)
        ble.sendNotification(id: id, app: app, title: title, body: body)
    }

    func triggerGlassCamera() {
        ble.triggerGlassCamera()
    }

    // MARK: — URL scheme  glassreborn://notif?app=X&title=Y&body=Z

    func handleURL(_ url: URL) {
        guard url.scheme == "glassreborn",
              url.host == "notif",
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let title = comps.queryItems?.first(where: { $0.name == "title" })?.value,
              !title.isEmpty
        else { return }

        let appName = comps.queryItems?.first(where: { $0.name == "app" })?.value ?? "iPhone"
        let body    = comps.queryItems?.first(where: { $0.name == "body" })?.value ?? ""
        pushNotification(app: appName, title: title, body: body)
    }
}
