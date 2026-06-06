package com.glass.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject

/**
 * Full-screen overlay that appears when a notification or call arrives.
 * Swipe down (KEYCODE_BACK) or timeout dismisses it.
 */
class NotificationActivity : AppCompatActivity() {

    private lateinit var tvApp: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvBody: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var autoDismissMs = 8000L

    private val callEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == GlassApp.ACTION_CALL_END) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification)

        tvApp   = findViewById(R.id.tvApp)
        tvTitle = findViewById(R.id.tvTitle)
        tvBody  = findViewById(R.id.tvBody)

        val json = intent.getStringExtra(GlassApp.EXTRA_JSON) ?: run { finish(); return }
        renderMessage(JSONObject(json))

        LocalBroadcastManager.getInstance(this).registerReceiver(
            callEndReceiver, IntentFilter(GlassApp.ACTION_CALL_END)
        )
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(callEndReceiver)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun renderMessage(msg: JSONObject) {
        when (msg.getString("t")) {
            GlassProtocol.T_NOTIF -> {
                tvApp.text   = msg.optString("app", "").uppercase()
                tvTitle.text = msg.optString("title", "")
                tvBody.text  = msg.optString("body", "")
                autoDismissMs = msg.optLong("timeout", 8000L)
                scheduleAutoDismiss()
            }
            GlassProtocol.T_CALL_START -> {
                tvApp.text   = "📞  INCOMING CALL"
                tvTitle.text = msg.optString("name", "Unknown")
                tvBody.text  = msg.optString("number", "")
                // Don't auto-dismiss calls — wait for CALL_END broadcast
            }
        }
    }

    private fun scheduleAutoDismiss() {
        handler.postDelayed({ finish() }, autoDismissMs)
    }
}
