# GlassReborn — Android / Glass XE App

The Glass side of GlassReborn. Runs on **Google Glass Explorer Edition (XE)** (Android 4.4.2, API 19). Acts as the home launcher, manages a persistent BLE connection to the companion iPhone app, and provides camera, AI assistant, notifications, and gallery features.

---

## Hardware context

| Property | Value |
|---|---|
| Device | Google Glass Explorer Edition (XE12 / XE22) |
| Android version | 4.4.2 (API 19) |
| Display | 640 × 360 px, landscape-only |
| Input | Capacitive touchpad on right temple (not a touchscreen) |
| BLE MTU | 20 bytes fixed (API 21 MTU negotiation unavailable) |
| Camera | 5 MP, fixed-focus |

---

## Building

### Prerequisites

- Android Studio (for its bundled JBR — standard JDK may not be on `PATH`)
- Android SDK with API 19 platform
- `local.properties` in the repo root:
  ```
  sdk.dir=/Users/<you>/Library/Android/sdk
  ```
- Glass connected via USB with ADB enabled (**Settings → Device Info → Enable debugging**)

### Build and install

```bash
# From the repo root (where gradlew lives)
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    ./gradlew assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Gradle versions

This project requires **Gradle 8.9** and **AGP 8.x**. Gradle 9.x is incompatible with the API 19 build configuration. The wrapper is pinned in `gradle/wrapper/gradle-wrapper.properties`.

---

## First-run Glass setup

### 1 — Disable the Glass system UI (one-time)

Two Glass system services intercept all touchpad input by default and must be disabled:

```bash
adb shell pm disable com.google.android.glass.systemui
adb shell pm disable com.google.glass.gesture
adb reboot
```

After rebooting, Glass prompts for a home app — choose **Glass Reborn**. On all subsequent reboots this app launches automatically.

### 2 — Connect Glass to WiFi (one-time)

The gallery and photo transfer require Glass and iPhone to be on the same WiFi network. Use the built-in `WifiSetupActivity`:

```bash
adb shell am start -n com.glass.companion/.WifiSetupActivity \
    --es ssid "YourNetworkName" --es password "YourPassword"
```

For open networks, omit `--es password`.

Verify the connection:
```bash
adb shell ip addr show wlan0
# Should show: inet 192.168.x.x/...
```

---

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/glass/companion/
│   ├── GlassApp.kt               Application subclass; LocalBroadcast constants
│   ├── GlassProtocol.kt          BLE UUIDs, message type constants, chunking codec
│   ├── MainActivity.kt           Home launcher — ViewPager with 5 cards
│   ├── CameraActivity.kt         Camera1 preview (NV21→Bitmap) and capture
│   ├── AiActivity.kt             Voice input + streaming AI response display
│   ├── NotificationActivity.kt   Full-screen notification/call overlay
│   ├── GalleryActivity.kt        Horizontal photo carousel + full-screen viewer
│   ├── WifiSetupActivity.kt      ADB-invokable WiFi credential helper
│   ├── BootReceiver.kt           Starts GlassService after reboot
│   └── service/
│       ├── GlassService.kt       Foreground service; owns BLE + WiFi server
│       ├── BleConnectionManager.kt  BLE GATT central (API 19 scan API)
│       └── WifiServer.kt         WebSocket server for photo transfer (port 8765)
└── res/
    ├── layout/
    │   ├── activity_main.xml         ViewPager + status dot + page indicator
    │   ├── item_card.xml             Card template (SF Symbol icon / title / subtitle)
    │   ├── card_status.xml           Status card (clock, BLE status, battery)
    │   ├── activity_camera.xml       ImageView preview + shutter hint
    │   ├── activity_ai.xml           State label + query + scrollable response
    │   ├── activity_notification.xml Full-screen alert overlay
    │   ├── activity_gallery.xml      RecyclerView carousel + full-screen ImageView
    │   └── item_gallery_photo.xml    Single carousel item
    ├── drawable/
    │   ├── bg_pill.xml               Rounded rectangle for status labels
    │   ├── ic_launcher.png           App icon (192 px)
    │   ├── ic_status.png             SF Symbol: dot.radiowaves.left.and.right
    │   ├── ic_notifications.png      SF Symbol: bell
    │   ├── ic_camera.png             SF Symbol: camera
    │   ├── ic_ai.png                 SF Symbol: brain
    │   └── ic_gallery.png            SF Symbol: photo
    ├── mipmap-{mdpi…xxxhdpi}/        Launcher icon at all densities
    └── values/
        ├── colors.xml                Black/cyan/white palette
        ├── strings.xml               User-visible strings
        └── themes.xml                AppCompat dark theme
```

---

## Architecture

Three layers communicate exclusively via `LocalBroadcastManager`:

```
┌─────────────────────────────────────────────────────┐
│                    Activities (UI)                   │
│  MainActivity  CameraActivity  AiActivity  ...       │
│       ↑  LocalBroadcast (data in)                    │
│       ↓  startService() extras (commands out)        │
├─────────────────────────────────────────────────────┤
│               GlassService (foreground)              │
│       ↑↓  BleConnectionManager                      │
│       ↑↓  WifiServer                                │
├─────────────────────────────────────────────────────┤
│                  Hardware / OS                       │
│  Bluetooth GATT (central)    WiFi WebSocket :8765    │
│         ↕                            ↕               │
│   iPhone BLE peripheral       iPhone WS client       │
└─────────────────────────────────────────────────────┘
```

Activities never touch BLE or WiFi directly. `GlassService` is the sole owner of both connections.

---

## Components in detail

### GlassProtocol

Pure Kotlin `object` — no Android dependencies. Single source of truth for the Glass ↔ iPhone wire protocol.

**BLE UUIDs** (must match the iOS `GlassProtocol.swift` exactly):

| Name | UUID | Direction |
|---|---|---|
| Service | `f3641400-00b0-4240-ba50-05ca45bf8abc` | — |
| CMD_CHAR | `f3641401-…` | Glass → iPhone (write) |
| DATA_CHAR | `f3641402-…` | iPhone → Glass (notify) |
| CCCD | `00002902-…` | Standard notifications descriptor |

**Message types (`"t"` field in every JSON packet):**

| Constant | Value | Direction | Purpose |
|---|---|---|---|
| `T_NOTIF` | `"NOTIF"` | iPhone → Glass | Phone notification |
| `T_CALL_START` | `"CALL_START"` | iPhone → Glass | Incoming call |
| `T_CALL_END` | `"CALL_END"` | iPhone → Glass | Call ended |
| `T_AI_RESP` | `"AI_RESP"` | iPhone → Glass | Streamed AI chunk |
| `T_PHONE_BATT` | `"PHONE_BATT"` | iPhone → Glass | iPhone battery % |
| `T_PHOTO_TRIGGER` | `"PHOTO_TRIG"` | iPhone → Glass | Remote shutter |
| `T_TIMEZONE` | `"TIMEZONE"` | iPhone → Glass | IANA timezone string |
| `T_CMD` | `"CMD"` | Glass → iPhone | Voice query / gallery request |
| `T_STATUS` | `"STATUS"` | Glass → iPhone | Glass WiFi IP + port |

**BLE chunking** — 20-byte ATT MTU:
```
[msgId:1][chunkIdx:1][totalChunks:1][payload: up to 17 bytes UTF-8]
```
`encodeChunks()` splits outbound strings. `Reassembler` reassembles inbound chunks.

---

### GlassService

Foreground service — Android keeps it alive even when the display is off.

- Holds a `PARTIAL_WAKE_LOCK` so BLE stays alive during display sleep
- Owns `BleConnectionManager` and `WifiServer`
- Dispatches incoming BLE messages to activities via `LocalBroadcast`
- Handles `ble_send` and `photo_path` extras from `startService()` calls
- Sends a `STATUS` heartbeat (Glass WiFi IP + port) to iPhone every 30 seconds

**Incoming message dispatch:**

| `"t"` | Action |
|---|---|
| `NOTIF` | `ACTION_NOTIFICATION` broadcast |
| `CALL_START` | `ACTION_CALL_START` broadcast |
| `AI_RESP` | `ACTION_AI_RESPONSE` broadcast |
| `PHONE_BATT` | `ACTION_PHONE_BATTERY` broadcast |
| `PHOTO_TRIG` | `ACTION_PHOTO_TRIGGER` broadcast |
| `TIMEZONE` | Stored in SharedPreferences (`"glass"` / `"timezone"`); `ACTION_TIMEZONE` broadcast |

---

### BleConnectionManager

Glass is the **central** (scans, connects). iPhone is the **peripheral** (advertises).

Uses the deprecated `BluetoothAdapter.startLeScan()` — intentional, because `BluetoothLeScanner` requires API 21. The UUID filter parameter in `startLeScan` is broken on API 19, so an unfiltered scan is used and matches are found by local name (`"GlassReborn"`) or by parsing the 128-bit service UUID from the raw advertisement bytes.

**Connection state machine:**
```
IDLE → startScanning() → SCANNING
SCANNING  → "GlassReborn" found → CONNECTING
CONNECTING → GATT connected → discoverServices()
           → services found → subscribe DATA_CHAR (write CCCD)
           → onDescriptorWrite → READY → onConnected()
READY → disconnected → IDLE → onDisconnected() → retry in 3s
```

Outbound writes are serialized via an `ArrayDeque` queue — GATT only allows one in-flight write at a time.

---

### WifiServer

`WebSocketServer` (java-websocket library) on port 8765. Used for photo transfer — photos are too large for the 20-byte BLE MTU.

**Protocol:**

| Message | Direction | Fields |
|---|---|---|
| `LIST` | iPhone → Glass | `offset`, `count` |
| `LIST_RESP` | Glass → iPhone | `total`, `offset`, `items[]` (name/size/date) |
| `DOWNLOAD` | iPhone → Glass | `name` |
| `FILE_START` | Glass → iPhone | `name`, `size` |
| *(binary frame)* | Glass → iPhone | Raw JPEG bytes |
| `NEW_PHOTO` | Glass → iPhone | `name` — new photo taken |

Path traversal is guarded: `handleDownload()` checks that the resolved path starts with `photoDir.canonicalPath`.

---

### MainActivity

Home launcher with `HOME` + `LAUNCHER` intent-filter categories.

**Cards (ViewPager, 5 items):**

| Index | Title | Icon | Tap action |
|---|---|---|---|
| 0 | Status | Signal waves | None — live clock, BLE, battery |
| 1 | Notifications | Bell | None — full-screen overlays appear automatically |
| 2 | Camera | Camera | Opens `CameraActivity` |
| 3 | AI | Brain | Opens `AiActivity` |
| 4 | Gallery | Photo | Opens `GalleryActivity` |

Icons are SF Symbols exported from macOS and stored as PNGs, tinted with the accent colour at runtime.

**Touchpad input** (Glass touchpad is not a touchscreen):

| Gesture | Handler | Threshold | Action |
|---|---|---|---|
| Swipe forward | `onGenericMotionEvent` | X diff > 80 px | Next card |
| Swipe back | `onGenericMotionEvent` | X diff < −80 px | Previous card |
| Tap | `dispatchKeyEvent` | `KEYCODE_DPAD_CENTER` | Activate current card |
| Camera button | `dispatchKeyEvent` | `KEYCODE_CAMERA` | Open camera directly |
| Swipe down | `onBackPressed` | — | No-op (home screen) |

**Status card clock** uses the timezone stored in SharedPreferences (key `"timezone"`, default `"America/New_York"`). Updated via `T_TIMEZONE` BLE message whenever iPhone connects.

---

### CameraActivity

Uses the deprecated Camera1 API — Camera2 requires API 21.

**Preview approach:** The Glass camera HAL cannot render to a `Surface` correctly (produces diagonal stripe artifacts). The working solution bypasses the HAL surface entirely:

1. `SurfaceTexture(10)` — dummy output target; HAL needs something to attach to
2. `setPreviewCallbackWithBuffer()` — receives raw NV21 frames
3. `HandlerThread("CamDecode")` — background decode: `YuvImage` → JPEG → `Bitmap`
4. Decoded bitmap posted to `previewImage: ImageView` on the main thread
5. `AtomicBoolean(frameReady)` drops frames when the decoder is busy (~5–15 fps)

**Capture:**
1. `takePicture()` → JPEG written to `<external-files>/Photos/Glass_<timestamp>.jpg`
2. Brief white flash overlay
3. `GlassService` notified via `startService(photo_path=...)` → broadcasts `NEW_PHOTO` to iPhone over WiFi

---

### AiActivity

Tap-to-speak AI assistant using Android's built-in `RecognizerIntent`.

**Flow:**
1. Tap (or `KEYCODE_DPAD_CENTER`) → system speech dialog
2. Recognized text sent to iPhone via BLE:
   `{ "t": "CMD", "cmd": "VOICE", "text": "..." }`
3. iPhone streams Claude response back chunk by chunk
4. Each chunk appended to a `ScrollView`; old response stays visible until first new chunk arrives
5. Last response persisted to SharedPreferences — restored when re-entering the activity

**Touchpad scrolling:**

| Gesture | Action |
|---|---|
| Swipe forward | Scroll response down (40% of view height) |
| Swipe back | Scroll response up |
| Tap | Start new voice query |
| Swipe down | Exit |

---

### GalleryActivity

Horizontal photo carousel using `RecyclerView` + `LinearSnapHelper`.

- Thumbnails decoded on a `Dispatchers.IO` coroutine — main thread never blocks
- `LruCache<String, Bitmap>` (24 entries) — revisiting a photo is instant
- `inSampleSize = 8` for thumbnails (5 MP → ~324×243 px, plenty for a 120 dp tile)
- Centre item scales to **1.15×**; edges shrink to **0.78×** via `OnScrollListener`
- Full-screen view decodes at native resolution on a background coroutine

**Touchpad controls:**

| Gesture | Action |
|---|---|
| Swipe forward | Next photo |
| Swipe back | Previous photo |
| Tap | Open centre photo full-screen |
| Tap (full-screen) | Return to carousel |
| Swipe forward/back (full-screen) | Page through photos |
| Swipe down | Exit (or collapse full-screen first) |

---

### WifiSetupActivity

ADB-only helper to connect Glass to WiFi without needing root or the Glass settings UI (which requires the `SETUP_WIFI` permission):

```bash
adb shell am start -n com.glass.companion/.WifiSetupActivity \
    --es ssid "NetworkName" --es password "Password"
# Omit --es password for open networks
```

Uses `WifiManager.addNetwork()` + `enableNetwork()`. Finishes immediately after queuing the connection.

---

## Permissions

| Permission | Reason |
|---|---|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | BLE scan and GATT (API 19 style) |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Required by Android for BLE scanning on API 19–22 |
| `CHANGE_WIFI_STATE` | `WifiSetupActivity` — add and enable networks |
| `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE` | Read Glass WiFi IP to send to iPhone |
| `INTERNET` | WebSocket server |
| `CAMERA` | Camera preview and capture |
| `WRITE_EXTERNAL_STORAGE` | Save photos (capped at API 28) |
| `READ_EXTERNAL_STORAGE` | Read photos in gallery (capped at API 32) |
| `RECEIVE_BOOT_COMPLETED` | Auto-start `GlassService` on reboot |
| `FOREGROUND_SERVICE` | Keep `GlassService` alive |
| `WAKE_LOCK` | `PARTIAL_WAKE_LOCK` keeps BLE alive when display is off |
| `RECORD_AUDIO` | `RecognizerIntent` for voice queries |

---

## Known constraints

**API 19 limitations:**

- BLE MTU fixed at 20 bytes → chunking protocol required
- `BluetoothLeScanner` unavailable → deprecated `startLeScan` with manual UUID parsing
- `BluetoothGatt.requestMtu()` unavailable (API 21+)
- Camera2 API unavailable → Camera1 with NV21 software decode workaround
- `screenrecord` works but `scrcpy` does not (scrcpy minimum: API 21)

**Glass touchpad quirks:**

- Swipes arrive via `onGenericMotionEvent`, not `dispatchTouchEvent`
- With `systemui` enabled, all input goes to the Glass timeline
- Swipe direction determined by X-axis delta; threshold is 60–80 px depending on activity
- Swipe down (`diffY > 60 && |diffY| > |diffX|`) is the universal back gesture

**Camera:**

- Direct HAL surface rendering produces diagonal stripe artifacts (broken on Glass XE)
- Software NV21→Bitmap decode is the only working preview approach
- JPEG capture uses a separate ISP pipeline and works correctly
