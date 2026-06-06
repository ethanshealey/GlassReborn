# Glass Reborn — Setup Guide

## Architecture

```
iPhone (BLE peripheral)  ←→  Google Glass XE (BLE central)
      ↕ WiFi WebSocket (media/photos)
```

iPhone advertises a BLE service. Glass scans, connects, and the two exchange JSON messages
chunked into 20-byte BLE packets. Photos travel over WiFi (Glass runs a WebSocket server
on port 8765; iPhone is the client).

---

## 1. Build & Install the Glass App (Android)

### Requirements
- Android Studio Hedgehog or newer
- Java 17 (for Gradle tooling)
- ADB set up (USB or WiFi ADB)
- Glass connected via USB or on the same WiFi

### Steps
```bash
# In the repo root:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

On first launch, Glass will ask to become the default Home app — **allow it**.
The app starts `GlassService` automatically on boot via `BootReceiver`.

### Permissions on Glass
The app needs Location permission for BLE scanning (Android API 19 requirement).
Grant it in Settings → Apps → Glass Reborn → Permissions.

---

## 2. Build & Install the iOS App

### Requirements
- Xcode 15+
- iPhone running iOS 15+
- Apple Developer account (free tier works for sideloading)

### Create the Xcode project
1. Open Xcode → **File → New → Project**
2. Choose **iOS → App**
3. Product Name: `GlassReborn`
4. Bundle ID: `com.glass.companion`
5. Interface: **SwiftUI**, Language: **Swift**
6. Save into `ios/` directory

### Add the source files
Drag all files from `ios/GlassCompanion/` into the Xcode project navigator, replacing
the default `ContentView.swift`. Make sure **"Copy items if needed"** is checked.

### Replace Info.plist
Replace Xcode's generated `Info.plist` with the one in `ios/GlassCompanion/Info.plist`
(or merge the keys manually). The BLE peripheral background mode is critical.

### Sign & Install
1. Select your development team in Signing & Capabilities
2. Connect your iPhone via USB
3. **Product → Run** (or `Cmd+R`)

---

## 3. First Connection

1. **On iPhone:** Open Glass Reborn. Grant Bluetooth permission.
2. **On Glass:** The app starts scanning automatically. You should see the status dot turn green.
3. **On iPhone:** Connection tab shows "Glass connected ✓".
4. Both devices need to be within ~10 meters.

### WiFi media
Once BLE connects, Glass sends its WiFi IP to iPhone. The Gallery tab will show photos
automatically. Both devices should be on the same WiFi network for best results;
alternatively, enable iPhone hotspot and connect Glass to it via Settings → WiFi.

---

## 4. AI Assistant Setup

1. Get a Claude API key from [console.anthropic.com](https://console.anthropic.com)
2. In the iOS app → **Settings → Claude API Key**, paste your key
3. On Glass, navigate to the **AI** card and tap the touchpad to speak
4. The query goes to iPhone via BLE → Claude API → streams back to Glass

---

## 5. Notification Forwarding (iOS Limitations)

iOS does not allow apps to read other apps' notifications (unlike Android's
`NotificationListenerService`). Workarounds:

### Option A: Manual push (built-in)
Notifications tab → **+** button → fill in app/title/body → Send.

### Option B: iOS Shortcuts automation
1. Open the **Shortcuts** app → **Automation** → **+**
2. Trigger: "When I receive a message from [contact]" or similar
3. Action: **Open URL** with:
   ```
   glasscompanion://notify?app=Messages&title=John&body=Hey!
   ```
   (Use Shortcuts variables for dynamic values)
4. This opens Glass Companion briefly and forwards the notification via BLE.

### Option C: CallKit (automatic, already built-in)
Incoming phone calls are automatically detected via `CXCallObserver` and pushed to Glass.

---

## 6. Glass Gestures

| Gesture | Action |
|---------|--------|
| Swipe right | Next card |
| Swipe left | Previous card |
| Tap | Activate current card |
| Swipe down | Back (does nothing at home screen) |

**Cards (left to right):** Status · Notifications · Camera · AI · Gallery

---

## 7. Taking Photos

- **From Glass:** Navigate to Camera card, tap the touchpad
- **Remotely from iPhone:** Connection tab → **Trigger Glass Camera**
- Photos save to `GlassReborn/Photos/` on Glass external storage
- Browse and download in the Gallery tab (requires WiFi connection)

---

## 8. Future Features

- [ ] SMS forwarding via Shortcuts deep-link
- [ ] Navigation turn-by-turn display
- [ ] Music playback controls
- [ ] Weather widget on status card
- [ ] Video streaming (Glass WebView + WiFi URL)
- [ ] Teleprompter mode
