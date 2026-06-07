package com.glass.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.glass.companion.service.GlassService
import org.json.JSONObject
import kotlin.math.abs

/**
 * AI assistant screen.
 * Tap to speak → sends text to iPhone via BLE → iPhone calls Claude API → streams response back.
 */
class AiActivity : AppCompatActivity() {

    private lateinit var tvState: TextView
    private lateinit var tvResponse: TextView
    private lateinit var tvQuery: TextView
    private lateinit var scrollResponse: ScrollView

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private val scrollStep get() = (scrollResponse.height * 0.4f).toInt().coerceAtLeast(80)
    private var isFirstChunk = false
    private val prefs by lazy { getSharedPreferences("ai", MODE_PRIVATE) }

    private val aiReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != GlassApp.ACTION_AI_RESPONSE) return
            val text = intent.getStringExtra(GlassApp.EXTRA_TEXT) ?: return
            val done = intent.getBooleanExtra(GlassApp.EXTRA_DONE, true)
            // Clear the old response only on the very first chunk of the new one
            if (isFirstChunk) { tvResponse.text = ""; isFirstChunk = false }
            tvResponse.append(text)
            scrollResponse.post { scrollResponse.fullScroll(ScrollView.FOCUS_DOWN) }
            if (done) {
                tvState.text = getString(R.string.tap_to_speak)
                prefs.edit()
                    .putString("last_query", tvQuery.text.toString())
                    .putString("last_response", tvResponse.text.toString())
                    .apply()
            }
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

        // Restore last session
        prefs.getString("last_query", null)?.let { tvQuery.text = it }
        prefs.getString("last_response", null)?.let { tvResponse.text = it }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(aiReceiver)
        super.onDestroy()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { swipeStartX = event.x; swipeStartY = event.y }
            MotionEvent.ACTION_UP -> {
                val diffX = event.x - swipeStartX
                val diffY = event.y - swipeStartY
                when {
                    // Dominant downward swipe → exit
                    diffY > 60 && abs(diffY) > abs(diffX) -> { finish(); return true }
                    // Swipe forward → scroll down
                    diffX > 60 && abs(diffX) > abs(diffY) -> {
                        scrollResponse.smoothScrollBy(0, scrollStep); return true
                    }
                    // Swipe backward → scroll up
                    diffX < -60 && abs(diffX) > abs(diffY) -> {
                        scrollResponse.smoothScrollBy(0, -scrollStep); return true
                    }
                    // Tap → new voice query
                    abs(diffX) < 30 && abs(diffY) < 30 -> { startVoiceInput(); return true }
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            startVoiceInput(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startVoiceInput() {
        tvState.text = getString(R.string.listening)
        isFirstChunk = true  // old response stays visible until new one arrives
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
