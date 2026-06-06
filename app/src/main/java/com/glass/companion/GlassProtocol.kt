package com.glass.companion

import java.util.UUID

/**
 * Shared protocol constants and BLE chunking for Glass <-> iPhone communication.
 *
 * Architecture:
 *   iPhone = BLE peripheral (advertises, Glass connects to it)
 *   Glass  = BLE central   (scans, connects)
 *
 * BLE MTU on Glass XE is 20 bytes (API 19 can't negotiate MTU).
 * Each chunk: [msgId:1][chunkIdx:1][totalChunks:1][payload:≤17 bytes]
 * Max message: 256 chunks × 17 bytes = 4352 bytes (AI responses fit easily).
 */
object GlassProtocol {

    // ── BLE UUIDs ──────────────────────────────────────────────────────────
    val SERVICE_UUID: UUID   = UUID.fromString("f3641400-00b0-4240-ba50-05ca45bf8abc")
    val CMD_CHAR_UUID: UUID  = UUID.fromString("f3641401-00b0-4240-ba50-05ca45bf8abc") // Glass writes
    val DATA_CHAR_UUID: UUID = UUID.fromString("f3641402-00b0-4240-ba50-05ca45bf8abc") // iPhone notifies
    val CCCD_UUID: UUID      = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // notifications

    // ── WiFi media server ──────────────────────────────────────────────────
    const val WIFI_PORT = 8765

    // ── Message type constants (short strings to save BLE bandwidth) ───────
    // iPhone → Glass
    const val T_NOTIF        = "NOTIF"        // phone notification
    const val T_CALL_START   = "CALL_START"   // incoming call
    const val T_CALL_END     = "CALL_END"     // call ended/rejected
    const val T_AI_RESP      = "AI_RESP"      // AI assistant response (streamed)
    const val T_PHONE_BATT   = "PHONE_BATT"  // phone battery level
    const val T_PHOTO_TRIGGER = "PHOTO_TRIG" // remote camera trigger

    // Glass → iPhone
    const val T_CMD          = "CMD"          // command from Glass
    const val T_STATUS       = "STATUS"       // Glass status heartbeat

    // Command sub-types
    const val CMD_VOICE_INPUT   = "VOICE"     // voice query text
    const val CMD_TAKE_PHOTO    = "PHOTO"     // trigger camera
    const val CMD_GALLERY_LIST  = "GALLERY"   // request gallery listing
    const val CMD_DISMISS_NOTIF = "DISMISS"   // dismiss a notification
    const val CMD_CALL_ACCEPT   = "ACCEPT"    // accept incoming call
    const val CMD_CALL_REJECT   = "REJECT"    // reject incoming call

    // ── Chunking ──────────────────────────────────────────────────────────
    private const val PAYLOAD_SIZE = 17 // 20 - 3 header bytes

    fun encodeChunks(msgId: Byte, message: String): List<ByteArray> {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val total = ((bytes.size + PAYLOAD_SIZE - 1) / PAYLOAD_SIZE).coerceAtLeast(1)
        return (0 until total).map { i ->
            val start = i * PAYLOAD_SIZE
            val end = minOf(start + PAYLOAD_SIZE, bytes.size)
            val chunk = ByteArray(3 + (end - start))
            chunk[0] = msgId
            chunk[1] = i.toByte()
            chunk[2] = total.toByte()
            if (end > start) System.arraycopy(bytes, start, chunk, 3, end - start)
            chunk
        }
    }

    /** Reassembles chunks for a single msgId; returns null if not all arrived yet. */
    class Reassembler {
        private val pending = HashMap<Byte, Array<ByteArray?>>()

        fun feed(chunk: ByteArray): String? {
            if (chunk.size < 3) return null
            val msgId   = chunk[0]
            val idx     = chunk[1].toInt() and 0xFF
            val total   = chunk[2].toInt() and 0xFF
            val slots   = pending.getOrPut(msgId) { arrayOfNulls(total) }
            if (idx < slots.size) slots[idx] = chunk.copyOfRange(3, chunk.size)
            if (slots.any { it == null }) return null
            pending.remove(msgId)
            val data = slots.filterNotNull().flatMap { it.toList() }.toByteArray()
            return String(data, Charsets.UTF_8)
        }
    }
}
