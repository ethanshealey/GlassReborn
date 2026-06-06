package com.glass.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.glass.companion.service.GlassService
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("DEPRECATION")
class CameraActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var tvFlash: View
    private lateinit var tvHint: TextView
    private var camera: Camera? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var photoDir: File

    private val remoteTriggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == GlassApp.ACTION_PHOTO_TRIGGER) takePhoto()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        surfaceView = findViewById(R.id.surfaceView)
        tvFlash     = findViewById(R.id.tvFlash)
        tvHint      = findViewById(R.id.tvShutterHint)

        photoDir = File(getExternalFilesDir(null), "Photos").also { it.mkdirs() }
        surfaceView.holder.addCallback(this)

        if (intent.getBooleanExtra("remote", false)) {
            // Small delay to let preview start before shooting
            handler.postDelayed(::takePhoto, 600)
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(
            remoteTriggerReceiver,
            IntentFilter(GlassApp.ACTION_PHOTO_TRIGGER)
        )
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(remoteTriggerReceiver)
        releaseCamera()
        super.onDestroy()
    }

    // Tap to shoot
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            takePhoto(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── SurfaceHolder.Callback ─────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        camera = Camera.open().also { cam ->
            cam.setPreviewDisplay(holder)
            val params = cam.parameters
            params.setPreviewSize(640, 360)
            cam.parameters = params
            cam.startPreview()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
        camera?.stopPreview()
        camera?.startPreview()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = releaseCamera()

    // ── Photo capture ──────────────────────────────────────────────────────

    private fun takePhoto() {
        camera?.takePicture(null, null) { jpeg, _ ->
            val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(photoDir, "Glass_$ts.jpg")
            FileOutputStream(file).use { it.write(jpeg) }

            flashEffect()
            tvHint.text = getString(R.string.photo_saved)
            handler.postDelayed({
                tvHint.text = getString(R.string.tap_to_shoot)
            }, 2000)

            // Notify service so it can tell iPhone
            startService(Intent(this, GlassService::class.java)
                .putExtra("photo_path", file.absolutePath))

            // Restart preview
            camera?.startPreview()
        }
    }

    private fun flashEffect() {
        tvFlash.visibility = View.VISIBLE
        handler.postDelayed({ tvFlash.visibility = View.GONE }, 100)
    }

    private fun releaseCamera() {
        camera?.stopPreview()
        camera?.release()
        camera = null
    }
}
