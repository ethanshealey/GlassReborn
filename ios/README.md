# GlassReborn — iOS Companion App

The iPhone side of GlassReborn. Acts as a **BLE peripheral** that Glass connects to, and as the bridge between Glass and the outside world (notifications, Claude AI, phone calls, photo gallery).

---

## Requirements

- iPhone with iOS 16 or later
- Xcode 15 or later
- A Claude API key (from [console.anthropic.com](https://console.anthropic.com)) for the AI assistant

---

## Building

Open `ios/GlassReborn/GlassReborn.xcodeproj` in Xcode, select your iPhone as the run destination, and press **Run** (⌘R).

BLE peripheral mode requires a **physical device** — it does not work in the iOS Simulator.

---

## Project structure

```
ios/GlassReborn/
├── GlassReborn/
│   ├── GlassRebornApp.swift      App entry point; AppState (central state object)
│   ├── Info.plist                Permissions + URL scheme + background modes
│   └── Assets.xcassets/
│       ├── AppIcon.appiconset/   App icon (1024×1024)
│       └── GlassLogo.imageset/   Tab bar logo (1×/2×/3×)
├── Models/
│   └── GlassProtocol.swift       BLE UUIDs, message constants, chunking codec
├── Services/
│   ├── BLEPeripheralService.swift BLE peripheral — advertises and handles GATT
│   ├── WiFiMediaClient.swift      WebSocket client for photo gallery
│   ├── AIService.swift            Claude API SSE streaming client
│   └── CallObserver.swift         CXCallObserver — detects incoming/active calls
└── Views/
    ├── MainView.swift             TabView root
    ├── ConnectionView.swift       BLE + WiFi status dashboard
    ├── NotificationsView.swift    Push notifications to Glass; manual + Shortcuts
    ├── MediaGalleryView.swift     Browse, auto-download, and save Glass photos
    ├── AIView.swift               Send queries and view AI responses
    └── SettingsView.swift         API key, timezone, Shortcuts setup guide
```

---

## Architecture

All state lives in `AppState` (an `ObservableObject`). Views observe it via `@EnvironmentObject`. Services are owned by `AppState` and communicate back via closures.

```
AppState
├── BLEPeripheralService  ── advertises, receives Glass commands, sends events
├── WiFiMediaClient       ── WebSocket connection to Glass for photo transfer
├── AIService             ── SSE streaming to Claude API
└── CallObserver          ── forwards calls to Glass via BLE
```

---

## BLE roles

| Role | Device |
|---|---|
| **Peripheral** (advertiser) | iPhone |
| **Central** (scanner / connector) | Glass |

The iPhone creates a `CBPeripheralManager` with two GATT characteristics:

| Characteristic | UUID suffix | Direction | Use |
|---|---|---|---|
| `CMD_CHAR` | `…401` | Glass → iPhone (write) | Commands from Glass |
| `DATA_CHAR` | `…402` | iPhone → Glass (notify) | Events pushed to Glass |

`BLEPeripheralService` advertises the service UUID `f3641400-…` and local name `GlassReborn`. When Glass subscribes to `DATA_CHAR` notifications, `glassConnected` is set to `true` and the timezone is immediately sent to Glass.

Large messages are chunked into 20-byte packets (Glass API 19 MTU limit). See `GlassProtocol.encodeChunks` and `GlassProtocol.Reassembler`.

---

## WiFi photo gallery

When BLE connects, Glass sends its local WiFi IP in a `STATUS` message. The iPhone opens a WebSocket to `ws://<glass-ip>:8765`.

On connection:
1. `LIST` request goes out — Glass responds with `LIST_RESP` (photo names, sizes, dates)
2. Photos already saved locally get their thumbnail set immediately
3. Missing photos are queued and auto-downloaded sequentially in the background
4. A `done/total` counter appears in the Gallery toolbar while downloading

Tapping a photo opens a detail view. If downloaded, a **Save to Photos** button saves it to the Camera Roll via `PHPhotoLibrary`.

---

## Notification forwarding

### Manual
Notifications tab → **+** → fill in App / Title / Message → Send.

### Via iOS Shortcuts
The app registers the `glassreborn://` URL scheme. Create an iOS Shortcut automation:

- **Trigger:** When I receive a notification from [App]
- **Action:** Open URL
  ```
  glassreborn://notif?app=Messages&title=[Notification Title]&body=[Notification Body]
  ```

The `handleURL(_:)` method in `AppState` parses the URL and calls `pushNotification`.

### Calls
`CallObserver` uses `CXCallObserver` to detect call state changes and automatically forwards them to Glass over BLE — no setup required.

---

## AI assistant

`AIService` sends requests to the Anthropic Claude API (`claude-haiku-4-5-20251001`) with streaming enabled. It uses `URLSessionDataDelegate` to process SSE chunks as they arrive, forwarding each text delta to Glass over BLE in real time.

Enter your API key in **Settings → Claude API Key**.

---

## Timezone

Glass has no automatic timezone detection. The iPhone sends the selected IANA timezone string to Glass over BLE on every connection. Glass stores it in SharedPreferences and uses it for the status card clock.

Default is **US Eastern** (`America/New_York`). Change it in **Settings → Glass Clock**.

---

## Permissions and background modes

| Permission / Mode | Purpose |
|---|---|
| `NSBluetoothAlwaysUsageDescription` | BLE peripheral advertising |
| `NSBluetoothPeripheralUsageDescription` | GATT service setup |
| `NSLocalNetworkUsageDescription` | WebSocket connection to Glass over LAN |
| `NSMicrophoneUsageDescription` | AI voice queries |
| `NSPhotoLibraryAddUsageDescription` | Save Glass photos to Camera Roll |
| `UIBackgroundModes: bluetooth-peripheral` | Keep BLE advertising in background |
| `UIBackgroundModes: bluetooth-central` | Maintain BLE connection in background |
| `CFBundleURLSchemes: glassreborn` | iOS Shortcuts notification forwarding |
