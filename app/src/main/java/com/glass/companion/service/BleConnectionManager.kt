package com.glass.companion.service

import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.glass.companion.GlassProtocol
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE central for Glass XE (API 19).
 *
 * Uses the deprecated BluetoothAdapter.startLeScan() API because
 * BluetoothLeScanner requires API 21. Glass XE is API 19.
 *
 * Roles: iPhone = peripheral (advertises), Glass = central (connects).
 */
@Suppress("DEPRECATION")
class BleConnectionManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onMessageReceived(json: String)
    }

    enum class State { IDLE, SCANNING, CONNECTING, READY }

    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private val reassembler = GlassProtocol.Reassembler()
    private val writeQueue = ArrayDeque<ByteArray>()
    private var isWriting = false
    private val msgCounter = AtomicInteger(0)

    var state = State.IDLE
        private set

    // ── Public API ────────────────────────────────────────────────────────

    fun startScanning() {
        if (state != State.IDLE) return
        Log.d(TAG, "BLE adapter enabled=${adapter.isEnabled} state=${adapter.state}")
        state = State.SCANNING
        // Pass no UUID filter — the filter API is broken on API 19 (callback never fires).
        // We match by local name "GlassReborn" inside the callback instead.
        val started = adapter.startLeScan(leScanCallback)
        Log.d(TAG, "BLE scan started=$started")
    }

    fun stopScanning() {
        adapter.stopLeScan(leScanCallback)
    }

    fun disconnect() {
        stopScanning()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        state = State.IDLE
    }

    fun sendMessage(json: String) {
        val id = (msgCounter.getAndIncrement() and 0xFF).toByte()
        val chunks = GlassProtocol.encodeChunks(id, json)
        writeQueue.addAll(chunks)
        processNextWrite()
    }

    // ── BLE scan callback ─────────────────────────────────────────────────

    private val leScanCallback = BluetoothAdapter.LeScanCallback { device, rssi, scanRecord ->
        val name = device.name
        Log.d(TAG, "BLE device: name=$name addr=${device.address} rssi=$rssi")

        val isOurs = name == "GlassReborn" || containsServiceUuid(scanRecord)
        if (!isOurs) return@LeScanCallback

        Log.d(TAG, "Found GlassReborn at ${device.address}, connecting…")
        stopScanning()
        state = State.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback)
    }

    // Parse raw BLE advertisement bytes for our 128-bit service UUID (little-endian).
    // iOS sometimes omits the local name from the advertisement packet.
    private fun containsServiceUuid(record: ByteArray?): Boolean {
        record ?: return false
        // Our UUID in little-endian byte order
        val target = byteArrayOf(
            0x00.toByte(), 0x14.toByte(), 0x64.toByte(), 0xf3.toByte(),
            0xb0.toByte(), 0x00.toByte(), 0x40.toByte(), 0x42.toByte(),
            0xba.toByte(), 0x50.toByte(), 0x05.toByte(), 0xca.toByte(),
            0x45.toByte(), 0xbf.toByte(), 0x8a.toByte(), 0xbc.toByte()
        )
        var i = 0
        while (i < record.size - 2) {
            val len  = record[i].toInt() and 0xFF
            val type = record[i + 1].toInt() and 0xFF
            if (len == 0) break
            // 0x06 = Incomplete, 0x07 = Complete list of 128-bit UUIDs
            if ((type == 0x06 || type == 0x07) && len - 1 >= 16) {
                val uuidStart = i + 2
                if (uuidStart + 16 <= record.size) {
                    if (record.copyOfRange(uuidStart, uuidStart + 16).contentEquals(target)) {
                        return true
                    }
                }
            }
            i += 1 + len
        }
        return false
    }

    // ── GATT callback ─────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected, discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected")
                    state = State.IDLE
                    gatt = null
                    listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                g.disconnect(); return
            }
            val service = g.getService(GlassProtocol.SERVICE_UUID) ?: run {
                Log.w(TAG, "GlassCompanion service not found on device")
                g.disconnect(); return
            }
            cmdChar = service.getCharacteristic(GlassProtocol.CMD_CHAR_UUID)
            val dataChar = service.getCharacteristic(GlassProtocol.DATA_CHAR_UUID)

            if (cmdChar == null || dataChar == null) {
                Log.w(TAG, "Required characteristics missing"); g.disconnect(); return
            }

            // Enable notifications on DATA_CHAR (iPhone → Glass)
            g.setCharacteristicNotification(dataChar, true)
            val cccd = dataChar.getDescriptor(GlassProtocol.CCCD_UUID)
            cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "CCCD written, BLE ready")
            state = State.READY
            gatt = g
            listener.onConnected()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == GlassProtocol.DATA_CHAR_UUID) {
                val chunk = characteristic.value ?: return
                val json = reassembler.feed(chunk) ?: return
                listener.onMessageReceived(json)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isWriting = false
            processNextWrite()
        }
    }

    // ── Write queue (BLE writes must be serialized) ────────────────────────

    private fun processNextWrite() {
        if (isWriting || writeQueue.isEmpty()) return
        val g = gatt ?: return
        val c = cmdChar ?: return
        isWriting = true
        val chunk = writeQueue.removeFirst()
        c.value = chunk
        g.writeCharacteristic(c)
    }

    companion object {
        private const val TAG = "BleConnectionManager"
    }
}
