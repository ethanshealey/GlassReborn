package com.glass.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.glass.companion.service.GlassService
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager
    private lateinit var tvStatus: TextView
    private lateinit var pageIndicator: LinearLayout

    private val cards = listOf(
        CardDef("⏰", "Status",        "connection & battery"),
        CardDef("🔔", "Notifications", "phone alerts"),
        CardDef("📷", "Camera",        "tap to capture"),
        CardDef("🤖", "AI",            "tap to speak"),
        CardDef("🖼", "Gallery",       "Glass photos"),
    )

    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                GlassApp.ACTION_BLE_STATE -> {
                    val connected = intent.getBooleanExtra(GlassApp.EXTRA_STATE, false)
                    tvStatus.text = if (connected) "●" else "●"
                    tvStatus.setTextColor(
                        if (connected) getColor2(R.color.status_ok)
                        else getColor2(R.color.status_error)
                    )
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
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(bleReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver)
    }

    // Glass touchpad: tap activates the current card
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            activateCurrentCard()
            return true
        }
        return super.onKeyDown(keyCode, event)
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

    data class CardDef(val icon: String, val title: String, val subtitle: String)

    inner class CardPagerAdapter : PagerAdapter() {
        override fun getCount() = cards.size
        override fun isViewFromObject(view: View, obj: Any) = view === obj

        override fun instantiateItem(container: android.view.ViewGroup, position: Int): Any {
            val view = layoutInflater.inflate(R.layout.item_card, container, false)
            val card = cards[position]
            view.findViewById<TextView>(R.id.tvCardIcon).text = card.icon
            view.findViewById<TextView>(R.id.tvCardTitle).text = card.title
            view.findViewById<TextView>(R.id.tvCardSubtitle).text = card.subtitle
            container.addView(view)
            return view
        }

        override fun destroyItem(container: android.view.ViewGroup, position: Int, obj: Any) {
            container.removeView(obj as View)
        }
    }
}
