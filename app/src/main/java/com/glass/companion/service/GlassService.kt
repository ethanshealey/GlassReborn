package com.glass.companion.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.glass.companion.GlassApp
import com.glass.companion.GlassProtocol
import com.glass.companion.MainActivity
import com.glass.companion.R
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface

/**
 * Foreground service that:
 *  1. Manages the BLE central connection to iPhone
 *  2. Runs the WiFi WebSocket server for media transfer
 *  3. Dispatches incoming messages to activities via LocalBroadcast
 *  4. Sends Glass status heartbeats to iPhone
 *  5. Auto-reconnects on disconnect
 */
class GlassService : Service(), BleConnectionManager.Listener {

    private lateinit var ble: BleConnectionManager
    private lateinit var wifiServer: WifiServer
    private lateinit var photoDir: File
    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())
    private val broadcaster by lazy { LocalBroadcastManager.getInstance(this) }

    private var wifiConnected = false

    override fun onCreate() {
        super.onCreate()
        photoDir = File(getExternalFilesDir(null), "Photos").also { it.mkdirs() }

        // Partial wake lock keeps BLE alive when Glass display sleeps
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glass:ble").apply { acquire() }

        ble = BleConnectionManager(this, this)
        wifiServer = WifiServer(
            photoDir,
            onClientConnected = { wifiConnected = true },
            onClientDisconnected = { wifiConnected = false }
        )
        wifiServer.start()

        startForeground(GlassApp.NOTIF_ID, buildNotification("Scanning for iPhone…"))
        ble.startScanning()
    }

    override fun onDestroy() {
        ble.disconnect()
        wifiServer.stop()
        if (wakeLock.isHeld) wakeLock.release()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("ble_send")?.let { ble.sendMessage(it) }
        intent?.getStringExtra("photo_path")?.let { onPhotoSaved(File(it)) }
        return START_STICKY
    }

    // ── BleConnectionManager.Listener ─────────────────────────────────────

    override fun onConnected() {
        Log.i(TAG, "iPhone connected via BLE")
        updateNotification("iPhone connected ✓")
        broadcaster.sendBroadcast(
            Intent(GlassApp.ACTION_BLE_STATE).putExtra(GlassApp.EXTRA_STATE, true)
        )
        sendStatusToPhone()
        scheduleHeartbeat()
    }

    override fun onDisconnected() {
        Log.i(TAG, "iPhone disconnected, reconnecting in 3s")
        updateNotification("Scanning for iPhone…")
        broadcaster.sendBroadcast(
            Intent(GlassApp.ACTION_BLE_STATE).putExtra(GlassApp.EXTRA_STATE, false)
        )
        handler.postDelayed({ ble.startScanning() }, 3000)
    }

    override fun onMessageReceived(json: String) {
        Log.d(TAG, "BLE rx: $json")
        try {
            dispatch(JSONObject(json))
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: $e")
        }
    }

    // ── Message dispatcher ─────────────────────────────────────────────────

    private fun dispatch(msg: JSONObject) {
        when (msg.getString("t")) {
            GlassProtocol.T_NOTIF -> broadcaster.sendBroadcast(
                Intent(GlassApp.ACTION_NOTIFICATION)
                    .putExtra(GlassApp.EXTRA_JSON, msg.toString())
            )
            GlassProtocol.T_CALL_START -> broadcaster.sendBroadcast(
                Intent(GlassApp.ACTION_CALL_START)
                    .putExtra(GlassApp.EXTRA_NAME,   msg.optString("name", "Unknown"))
                    .putExtra(GlassApp.EXTRA_NUMBER, msg.optString("number", ""))
            )
            GlassProtocol.T_CALL_END -> broadcaster.sendBroadcast(
                Intent(GlassApp.ACTION_CALL_END)
            )
            GlassProtocol.T_AI_RESP -> broadcaster.sendBroadcast(
                Intent(GlassApp.ACTION_AI_RESPONSE)
                    .putExtra(GlassApp.EXTRA_TEXT, msg.optString("text", ""))
                    .putExtra(GlassApp.EXTRA_DONE, msg.optBoolean("done", true))
            )
            GlassProtocol.T_PHONE_BATT -> broadcaster.sendBroadcast(
                Intent(GlassApp.ACTION_PHONE_BATTERY)
                    .putExtra(GlassApp.EXTRA_LEVEL, msg.optInt("level", -1))
            )
            GlassProtocol.T_PHOTO_TRIGGER -> broadcaster.sendBroadcast(
                Intent(GlassApp.ACTION_PHOTO_TRIGGER)
            )
            GlassProtocol.T_TIMEZONE -> {
                val tz = msg.optString("tz").takeIf { it.isNotBlank() } ?: return
                getSharedPreferences("glass", MODE_PRIVATE).edit()
                    .putString("timezone", tz).apply()
                broadcaster.sendBroadcast(
                    Intent(GlassApp.ACTION_TIMEZONE).putExtra(GlassApp.EXTRA_TZ, tz)
                )
            }
        }
    }

    // ── Outbound helpers ───────────────────────────────────────────────────

    fun sendCommand(cmd: String, extra: JSONObject? = null) {
        val msg = JSONObject().apply {
            put("t", GlassProtocol.T_CMD)
            put("cmd", cmd)
            extra?.keys()?.forEach { put(it, extra[it]) }
        }
        ble.sendMessage(msg.toString())
    }

    private fun sendStatusToPhone() {
        val msg = JSONObject().apply {
            put("t", GlassProtocol.T_STATUS)
            put("ip",   getWifiIp())
            put("port", GlassProtocol.WIFI_PORT)
        }
        ble.sendMessage(msg.toString())
    }

    private fun scheduleHeartbeat() {
        handler.postDelayed({
            if (ble.state == BleConnectionManager.State.READY) {
                sendStatusToPhone()
                scheduleHeartbeat()
            }
        }, 30_000)
    }

    // ── Photo saved callback (called by CameraActivity) ───────────────────

    fun onPhotoSaved(file: File) {
        wifiServer.notifyNewPhoto(file.name)
        sendCommand(
            GlassProtocol.CMD_TAKE_PHOTO,
            JSONObject().put("name", file.name).put("size", file.length())
        )
    }

    // ── Notification helpers ───────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, GlassApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
        nm.notify(GlassApp.NOTIF_ID, buildNotification(text))
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private fun getWifiIp(): String {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces().toList()
            // Prefer wlan0 (WiFi) over other interfaces (USB, rndis, etc.)
            val preferred = ifaces.sortedByDescending { it.name.startsWith("wlan") }
            preferred
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                ?.hostAddress ?: "0.0.0.0"
        } catch (e: Exception) { "0.0.0.0" }
    }

    companion object {
        private const val TAG = "GlassService"

        fun get(context: Context): GlassService? = null // accessed via binder if needed
    }
}
