package com.glass.companion

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * ADB-invokable one-shot activity to connect Glass to WiFi.
 * Usage:
 *   adb shell am start -n com.glass.companion/.WifiSetupActivity \
 *       --es ssid "YourNetwork" --es password "YourPassword"
 * For open networks omit --es password.
 */
class WifiSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ssid     = intent.getStringExtra("ssid")
        val password = intent.getStringExtra("password")

        if (ssid.isNullOrBlank()) {
            Log.e(TAG, "No SSID provided — pass --es ssid \"YourNetwork\"")
            finish(); return
        }

        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wm.isWifiEnabled = true

        @Suppress("DEPRECATION")
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (password.isNullOrBlank()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"$password\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
        }

        val netId = wm.addNetwork(config)
        if (netId == -1) {
            Log.e(TAG, "addNetwork failed — check CHANGE_WIFI_STATE permission")
            finish(); return
        }

        wm.disconnect()
        wm.enableNetwork(netId, true)
        wm.reconnect()
        Log.i(TAG, "Connecting to \"$ssid\" (netId=$netId)")
        finish()
    }

    companion object { private const val TAG = "WifiSetup" }
}
