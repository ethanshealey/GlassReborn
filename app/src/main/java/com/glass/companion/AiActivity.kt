package com.glass.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.glass.companion.service.GlassService
import org.json.JSONObject

/**
 * AI assistant screen.
 * Tap to speak → sends text to iPhone via BLE → iPhone calls Claude API → streams response back.
 */
class AiActivity : AppCompatActivity() {

    private lateinit var tvState: TextView
    private lateinit var tvResponse: TextView
    private lateinit var tvQuery: TextView
    private lateinit var scrollResponse: ScrollView

    private val aiReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != GlassApp.ACTION_AI_RESPONSE) return
            val text = intent.getStringExtra(GlassApp.EXTRA_TEXT) ?: return
            val done = intent.getBooleanExtra(GlassApp.EXTRA_DONE, true)
            tvResponse.append(text)
            scrollResponse.post { scrollResponse.fullScroll(ScrollView.FOCUS_DOWN) }
            if (done) tvState.text = getString(R.string.tap_to_speak)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai)

        tvState      = findViewById(R.id.tvState)
        tvResponse   = findViewById(R.id.tvResponse)
        tvQuery      = findViewById(R.id.tvQuery)
        scrollResponse = findViewById(R.id.scrollResponse)

        LocalBroadcastManager.getInstance(this).registerReceiver(
            aiReceiver, IntentFilter(GlassApp.ACTION_AI_RESPONSE)
        )
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(aiReceiver)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            startVoiceInput(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startVoiceInput() {
        tvState.text = getString(R.string.listening)
        tvResponse.text = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your question")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            startActivityForResult(intent, REQ_SPEECH)
        } catch (e: Exception) {
            tvState.text = "Speech not available"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull() ?: return
            tvQuery.text = text
            tvState.text = getString(R.string.thinking)
            // Send to iPhone for AI processing
            val msg = JSONObject().apply {
                put("t", GlassProtocol.T_CMD)
                put("cmd", GlassProtocol.CMD_VOICE_INPUT)
                put("text", text)
            }.toString()
            startService(Intent(this, GlassService::class.java)
                .putExtra("ble_send", msg))
        } else {
            tvState.text = getString(R.string.tap_to_speak)
        }
    }

    companion object {
        private const val REQ_SPEECH = 100
    }
}
