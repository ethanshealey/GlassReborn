package com.glass.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.glass.companion.service.GlassService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startService(Intent(context, GlassService::class.java))
        }
    }
}
