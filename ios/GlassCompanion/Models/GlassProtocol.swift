import Foundation
import CoreBluetooth

/// Shared protocol constants and BLE chunking — mirror of the Android GlassProtocol object.
enum GlassProtocol {

    // MARK: — BLE UUIDs
    static let serviceUUID   = CBUUID(string: "f3641400-00b0-4240-ba50-05ca45bf8abc")
    static let cmdCharUUID   = CBUUID(string: "f3641401-00b0-4240-ba50-05ca45bf8abc") // Glass writes
    static let dataCharUUID  = CBUUID(string: "f3641402-00b0-4240-ba50-05ca45bf8abc") // iPhone notifies
    static let wifiPort: Int = 8765

    // MARK: — Message type constants
    // iPhone → Glass
    static let tNotif        = "NOTIF"
    static let tCallStart    = "CALL_START"
    static let tCallEnd      = "CALL_END"
    static let tAiResp       = "AI_RESP"
    static let tPhoneBatt    = "PHONE_BATT"
    static let tPhotoTrigger = "PHOTO_TRIG"

    // Glass → iPhone
    static let tCmd          = "CMD"
    static let tStatus       = "STATUS"

    // Command subtypes
    static let cmdVoiceInput   = "VOICE"
    static let cmdTakePhoto    = "PHOTO"
    static let cmdGalleryList  = "GALLERY"
    static let cmdDismissNotif = "DISMISS"
    static let cmdCallAccept   = "ACCEPT"
    static let cmdCallReject   = "REJECT"

    // MARK: — BLE chunking (20-byte MTU, 17 bytes payload)
    static let payloadSize = 17

    static func encodeChunks(msgId: UInt8, message: String) -> [Data] {
        let bytes = Array(message.utf8)
        let total = max(1, (bytes.count + payloadSize - 1) / payloadSize)
        return (0..<total).map { i in
            let start  = i * payloadSize
            let end    = min(start + payloadSize, bytes.count)
            var chunk  = Data(count: 3 + (end - start))
            chunk[0]   = msgId
            chunk[1]   = UInt8(i)
            chunk[2]   = UInt8(total)
            for j in start..<end { chunk[3 + j - start] = bytes[j] }
            return chunk
        }
    }

    // MARK: — Reassembler (Glass → iPhone direction)
    class Reassembler {
        private var slots: [UInt8: [UInt8?: [UInt8]]] = [:]

        func feed(_ chunk: Data) -> String? {
            guard chunk.count >= 3 else { return nil }
            let msgId = chunk[0], idx = Int(chunk[1]), total = Int(chunk[2])
            var buckets = slots[msgId] ?? [:]
            buckets[UInt8(idx)] = Array(chunk[3...])
            slots[msgId] = buckets
            guard buckets.count == total else { return nil }
            slots.removeValue(forKey: msgId)
            let assembled = (0..<total).flatMap { buckets[UInt8($0)] ?? [] }
            return String(bytes: assembled, encoding: .utf8)
        }
    }
}

// MARK: — Codable message models

struct NotifMessage: Codable {
    let t: String
    let id: String
    let app: String
    let title: String
    let body: String
}

struct CallMessage: Codable {
    let t: String
    let name: String?
    let number: String?
}

struct AiResponseMessage: Codable {
    let t: String
    let text: String
    let done: Bool
}

struct GlassStatus: Codable {
    let t: String
    let ip: String
    let port: Int
}

struct GlassCommand: Codable {
    let t: String
    let cmd: String
    let text: String?
    let id: String?
    let offset: Int?
}
