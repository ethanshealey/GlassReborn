package com.glass.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class GlassApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "glass_companion"
        const val NOTIF_ID   = 1

        // LocalBroadcast actions
        const val ACTION_BLE_STATE       = "glass.BLE_STATE"
        const val ACTION_NOTIFICATION    = "glass.NOTIFICATION"
        const val ACTION_CALL_START      = "glass.CALL_START"
        const val ACTION_CALL_END        = "glass.CALL_END"
        const val ACTION_AI_RESPONSE     = "glass.AI_RESPONSE"
        const val ACTION_PHONE_BATTERY   = "glass.PHONE_BATTERY"
        const val ACTION_PHOTO_TRIGGER   = "glass.PHOTO_TRIGGER"
        const val ACTION_TIMEZONE        = "glass.TIMEZONE"

        const val EXTRA_STATE     = "state"
        const val EXTRA_JSON      = "json"
        const val EXTRA_TEXT      = "text"
        const val EXTRA_DONE      = "done"
        const val EXTRA_LEVEL     = "level"
        const val EXTRA_NAME      = "name"
        const val EXTRA_NUMBER    = "number"
        const val EXTRA_TZ        = "tz"
    }
}
