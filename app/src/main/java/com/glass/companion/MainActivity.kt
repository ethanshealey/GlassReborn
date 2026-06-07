package com.glass.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.glass.companion.service.GlassService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager
    private lateinit var tvStatus: TextView
    private lateinit var pageIndicator: LinearLayout
    private var touchStartX = 0f
    private var genericStartX = 0f

    // Status card live views (non-null while the ViewPager holds the card in memory)
    private var statusView: View? = null
    private var bleConnected = false
    private var phoneBattery = -1
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("glass", MODE_PRIVATE) }
    private val clockTZ get() = TimeZone.getTimeZone(
        prefs.getString("timezone", "America/New_York") ?: "America/New_York"
    )

    private val clockTick = object : Runnable {
        override fun run() {
            updateStatusClock()
            handler.postDelayed(this, 30_000)
        }
    }

    private val cards = listOf(
        CardDef(R.drawable.ic_status,        "Status",        "connection & battery"),
        CardDef(R.drawable.ic_notifications, "Notifications", "phone alerts"),
        CardDef(R.drawable.ic_camera,        "Camera",        "tap to capture"),
        CardDef(R.drawable.ic_ai,            "AI",            "tap to speak"),
        CardDef(R.drawable.ic_gallery,       "Gallery",       "Glass photos"),
    )

    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                GlassApp.ACTION_BLE_STATE -> {
                    bleConnected = intent.getBooleanExtra(GlassApp.EXTRA_STATE, false)
                    tvStatus.text = "●"
                    tvStatus.setTextColor(
                        if (bleConnected) getColor2(R.color.status_ok)
                        else getColor2(R.color.status_error)
                    )
                    updateStatusCard()
                }
                GlassApp.ACTION_PHONE_BATTERY -> {
                    phoneBattery = intent.getIntExtra(GlassApp.EXTRA_LEVEL, -1)
                    updateStatusCard()
                }
                GlassApp.ACTION_NOTIFICATION -> {
                    val json = intent.getStringExtra(GlassApp.EXTRA_JSON) ?: return
                    showNotificationOverlay(json)
                }
                GlassApp.ACTION_CALL_START -> {
                    val name   = intent.getStringExtra(GlassApp.EXTRA_NAME) ?: "Unknown"
                    val number = intent.getStringExtra(GlassApp.EXTRA_NUMBER) ?: ""
                    showCallOverlay(name, number)
                }
                GlassApp.ACTION_PHOTO_TRIGGER -> startActivity(
                    Intent(this@MainActivity, CameraActivity::class.java)
                        .putExtra("remote", true)
                )
                GlassApp.ACTION_TIMEZONE -> updateStatusCard()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!prefs.contains("timezone"))
            prefs.edit().putString("timezone", "America/New_York").apply()

        viewPager     = findViewById(R.id.viewPager)
        tvStatus      = findViewById(R.id.tvStatus)
        pageIndicator = findViewById(R.id.pageIndicator)

        viewPager.adapter = CardPagerAdapter()
        viewPager.offscreenPageLimit = cards.size
        buildIndicator()
        viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) = updateIndicator(position)
        })

        // Start the background service
        startService(Intent(this, GlassService::class.java))

        val filter = IntentFilter().apply {
            addAction(GlassApp.ACTION_BLE_STATE)
            addAction(GlassApp.ACTION_NOTIFICATION)
            addAction(GlassApp.ACTION_CALL_START)
            addAction(GlassApp.ACTION_PHOTO_TRIGGER)
            addAction(GlassApp.ACTION_PHONE_BATTERY)
            addAction(GlassApp.ACTION_TIMEZONE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(bleReceiver, filter)
        handler.post(clockTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockTick)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchStartX = ev.x
            MotionEvent.ACTION_UP -> {
                val diff = ev.x - touchStartX
                when {
                    diff > 80  -> { if (viewPager.currentItem > 0) viewPager.currentItem -= 1 }
                    diff < -80 -> { if (viewPager.currentItem < cards.size - 1) viewPager.currentItem += 1 }
                    diff in -30f..30f -> activateCurrentCard()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (viewPager.currentItem < cards.size - 1)
                        viewPager.currentItem += 1
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (viewPager.currentItem > 0)
                        viewPager.currentItem -= 1
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER -> {
                    activateCurrentCard()
                    return true
                }
                KeyEvent.KEYCODE_CAMERA -> {
                    startActivity(Intent(this, CameraActivity::class.java))
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> genericStartX = event.x
            MotionEvent.ACTION_UP -> {
                val diff = event.x - genericStartX
                when {
                    diff > 80  -> { if (viewPager.currentItem < cards.size - 1) viewPager.currentItem += 1 }
                    diff < -80 -> { if (viewPager.currentItem > 0) viewPager.currentItem -= 1 }
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // Swipe-down on Glass returns here (we're the home screen, so just ignore)
    override fun onBackPressed() { /* do nothing — we're the launcher */ }

    private fun activateCurrentCard() {
        when (viewPager.currentItem) {
            0 -> { /* status card — no action */ }
            1 -> { /* notifications — scroll handled inside card */ }
            2 -> startActivity(Intent(this, CameraActivity::class.java))
            3 -> startActivity(Intent(this, AiActivity::class.java))
            4 -> startActivity(Intent(this, GalleryActivity::class.java))
        }
    }

    private fun showNotificationOverlay(json: String) {
        startActivity(
            Intent(this, NotificationActivity::class.java)
                .putExtra(GlassApp.EXTRA_JSON, json)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun showCallOverlay(name: String, number: String) {
        val json = JSONObject().apply {
            put("t", GlassProtocol.T_CALL_START)
            put("name", name)
            put("number", number)
        }.toString()
        showNotificationOverlay(json)
    }

    // ── Status card updates ───────────────────────────────────────────────

    private fun updateStatusClock() {
        val sv  = statusView ?: return
        val tz  = clockTZ
        val now = Date()
        sv.findViewById<TextView>(R.id.tvTime).text =
            SimpleDateFormat("h:mm", Locale.getDefault()).apply { timeZone = tz }.format(now)
        sv.findViewById<TextView>(R.id.tvDate).text =
            SimpleDateFormat("EEE, MMM d", Locale.getDefault()).apply { timeZone = tz }.format(now)
    }

    private fun updateStatusCard() {
        val sv = statusView ?: return
        updateStatusClock()

        val bleText = sv.findViewById<TextView>(R.id.tvBleStatus)
        if (bleConnected) {
            bleText.text = "● iPhone connected"
            bleText.setTextColor(getColor2(R.color.status_ok))
        } else {
            bleText.text = "● Scanning for iPhone…"
            bleText.setTextColor(getColor2(R.color.status_error))
        }

        val phoneBattView = sv.findViewById<TextView>(R.id.tvPhoneBattery)
        if (bleConnected && phoneBattery >= 0) {
            phoneBattView.text = "Phone battery  $phoneBattery%"
            phoneBattView.visibility = View.VISIBLE
        } else {
            phoneBattView.visibility = View.GONE
        }

        val glassBatt = glassBatteryLevel()
        sv.findViewById<TextView>(R.id.tvGlassBattery).text =
            if (glassBatt >= 0) "Glass battery  $glassBatt%" else "Glass battery  —"
    }

    private fun glassBatteryLevel(): Int {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    // ── Page indicator dots ────────────────────────────────────────────────

    private fun buildIndicator() {
        pageIndicator.removeAllViews()
        repeat(cards.size) { i ->
            val dot = TextView(this).apply {
                text = "●"
                textSize = 8f
                setPadding(4, 0, 4, 0)
            }
            pageIndicator.addView(dot)
            updateDot(dot, i == 0)
        }
    }

    private fun updateIndicator(selected: Int) {
        repeat(pageIndicator.childCount) { i ->
            updateDot(pageIndicator.getChildAt(i) as TextView, i == selected)
        }
    }

    private fun updateDot(dot: TextView, active: Boolean) {
        dot.setTextColor(if (active) getColor2(R.color.accent) else getColor2(R.color.divider))
    }

    @Suppress("DEPRECATION")
    private fun getColor2(resId: Int) = resources.getColor(resId)

    // ── Card adapter ──────────────────────────────────────────────────────

    data class CardDef(val iconRes: Int, val title: String, val subtitle: String)

    inner class CardPagerAdapter : PagerAdapter() {
        override fun getCount() = cards.size
        override fun isViewFromObject(view: View, obj: Any) = view === obj

        override fun instantiateItem(container: android.view.ViewGroup, position: Int): Any {
            val view = if (position == 0) {
                layoutInflater.inflate(R.layout.card_status, container, false).also {
                    statusView = it
                    updateStatusCard()
                }
            } else {
                layoutInflater.inflate(R.layout.item_card, container, false).also { v ->
                    val card = cards[position]
                    v.findViewById<ImageView>(R.id.imgCardIcon).setImageResource(card.iconRes)
                    v.findViewById<TextView>(R.id.tvCardTitle).text = card.title
                    v.findViewById<TextView>(R.id.tvCardSubtitle).text = card.subtitle
                }
            }
            container.addView(view)
            return view
        }

        override fun destroyItem(container: android.view.ViewGroup, position: Int, obj: Any) {
            if (position == 0) statusView = null
            container.removeView(obj as View)
        }
    }
}
