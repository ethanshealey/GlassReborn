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
        state = State.SCANNING
        adapter.startLeScan(arrayOf(GlassProtocol.SERVICE_UUID), leScanCallback)
        Log.d(TAG, "BLE scan started")
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

    private val leScanCallback = BluetoothAdapter.LeScanCallback { device, _, _ ->
        stopScanning()
        state = State.CONNECTING
        Log.d(TAG, "Found device: ${device.address}, connecting…")
        gatt = device.connectGatt(context, false, gattCallback)
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
