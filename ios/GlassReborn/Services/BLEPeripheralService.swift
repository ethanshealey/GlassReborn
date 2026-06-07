import Foundation
import CoreBluetooth
import Combine

/**
 * iPhone acts as BLE peripheral. Glass (central) scans for and connects to us.
 *
 * After connection, Glass:
 *   - subscribes to DATA_CHAR notifications (we push phone events to Glass)
 *   - writes to CMD_CHAR (Glass sends commands/voice queries to us)
 */
final class BLEPeripheralService: NSObject, ObservableObject {

    @Published var state: CBManagerState = .unknown
    @Published var glassConnected = false

    private var peripheralManager: CBPeripheralManager!
    private var cmdCharacteristic: CBMutableCharacteristic!
    private var dataCharacteristic: CBMutableCharacteristic!

    private var subscribedCentral: CBCentral?
    private let reassembler = GlassProtocol.Reassembler()
    private var msgCounter: UInt8 = 0

    private let writeQueue = DispatchQueue(label: "ble.write", qos: .userInitiated)
    private var pendingChunks: [Data] = []
    private var isSending = false

    var onCommandReceived:  ((GlassCommand) -> Void)?
    var onStatusReceived:   ((GlassStatus)  -> Void)?
    var onConnectedCallback: (() -> Void)?

    override init() {
        super.init()
        peripheralManager = CBPeripheralManager(delegate: self, queue: .global(qos: .userInitiated))
    }

    // MARK: — Public API

    func send(json: String) {
        guard let central = subscribedCentral else { return }
        let id = msgCounter; msgCounter &+= 1
        let chunks = GlassProtocol.encodeChunks(msgId: id, message: json)
        writeQueue.async { [weak self] in
            self?.enqueue(chunks: chunks, central: central)
        }
    }

    func sendNotification(id: String, app: String, title: String, body: String) {
        let msg = ["t": GlassProtocol.tNotif, "id": id, "app": app,
                   "title": title, "body": body]
        send(json: encode(msg))
    }

    func sendCallStart(name: String, number: String) {
        send(json: encode(["t": GlassProtocol.tCallStart, "name": name, "number": number]))
    }

    func sendCallEnd() {
        send(json: encode(["t": GlassProtocol.tCallEnd]))
    }

    func sendAIResponse(text: String, done: Bool) {
        send(json: encode(["t": GlassProtocol.tAiResp, "text": text, "done": done]))
    }

    func sendPhoneBattery(level: Int) {
        send(json: encode(["t": GlassProtocol.tPhoneBatt, "level": level]))
    }

    func triggerGlassCamera() {
        send(json: encode(["t": GlassProtocol.tPhotoTrigger]))
    }

    func sendTimezone(_ identifier: String) {
        send(json: encode(["t": GlassProtocol.tTimezone, "tz": identifier]))
    }

    // MARK: — Private helpers

    private func startAdvertising() {
        // Clear any stale services from a previous session before re-adding.
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()

        cmdCharacteristic = CBMutableCharacteristic(
            type: GlassProtocol.cmdCharUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        dataCharacteristic = CBMutableCharacteristic(
            type: GlassProtocol.dataCharUUID,
            properties: [.notify],
            value: nil,
            permissions: [.readable]
        )
        let service = CBMutableService(type: GlassProtocol.serviceUUID, primary: true)
        service.characteristics = [cmdCharacteristic, dataCharacteristic]
        peripheralManager.add(service)
        // Advertising starts in peripheralManager(_:didAdd:error:) once the service is confirmed added.
    }

    private func enqueue(chunks: [Data], central: CBCentral) {
        pendingChunks.append(contentsOf: chunks)
        if !isSending { sendNextChunk(central: central) }
    }

    private func sendNextChunk(central: CBCentral) {
        guard !pendingChunks.isEmpty else { isSending = false; return }
        isSending = true
        let chunk = pendingChunks.removeFirst()
        let sent = peripheralManager.updateValue(
            chunk,
            for: dataCharacteristic,
            onSubscribedCentrals: [central]
        )
        if sent {
            // Small gap between chunks so Glass reassembler can keep up
            writeQueue.asyncAfter(deadline: .now() + 0.02) { [weak self] in
                self?.sendNextChunk(central: central)
            }
        }
        // If not sent (queue full), peripheralManagerIsReady will retry
    }

    private func encode(_ dict: [String: Any]) -> String {
        let data = try? JSONSerialization.data(withJSONObject: dict)
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }
}

// MARK: — CBPeripheralManagerDelegate

extension BLEPeripheralService: CBPeripheralManagerDelegate {

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        DispatchQueue.main.async { self.state = peripheral.state }
        if peripheral.state == .poweredOn { startAdvertising() }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let e = error {
            print("BLE add service error: \(e)")
            return
        }
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [GlassProtocol.serviceUUID],
            CBAdvertisementDataLocalNameKey: "GlassReborn"
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let e = error { print("BLE advertising error: \(e)") }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        subscribedCentral = central
        DispatchQueue.main.async {
            self.glassConnected = true
            self.onConnectedCallback?()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentral = nil
        DispatchQueue.main.async { self.glassConnected = false }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveWrite requests: [CBATTRequest]) {
        for req in requests {
            guard req.characteristic.uuid == GlassProtocol.cmdCharUUID,
                  let data = req.value else { continue }
            peripheral.respond(to: req, withResult: .success)
            if let json = reassembler.feed(data) { handleIncoming(json: json) }
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        // Resume sending after back-pressure
        if let central = subscribedCentral, !pendingChunks.isEmpty {
            sendNextChunk(central: central)
        }
    }

    private func handleIncoming(json: String) {
        guard let data = json.data(using: .utf8) else { return }
        if let cmd = try? JSONDecoder().decode(GlassCommand.self, from: data) {
            DispatchQueue.main.async { self.onCommandReceived?(cmd) }
        } else if let status = try? JSONDecoder().decode(GlassStatus.self, from: data) {
            DispatchQueue.main.async { self.onStatusReceived?(status) }
        }
    }
}
