package com.glass.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.glass.companion.service.GlassService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class CameraActivity : AppCompatActivity() {

    private lateinit var previewImage: ImageView
    private lateinit var tvFlash: View
    private lateinit var tvHint: TextView

    private var camera: Camera? = null
    private var previewRunning = false
    private var sensorAngle = 0
    private var previewW = 0
    private var previewH = 0
    private var remoteShotPending = false

    // Camera HAL requires an output target to call startPreview(); we give it a
    // dummy SurfaceTexture and receive frames exclusively via the preview callback.
    private val dummyTexture = SurfaceTexture(10)

    // Dedicated thread for NV21 → Bitmap decode so the main thread stays free.
    private val decodeThread = HandlerThread("CamDecode").apply { start() }
    private val decodeHandler = Handler(decodeThread.looper)
    private val frameReady = AtomicBoolean(true) // false while a frame is being decoded

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var photoDir: File

    private val remoteTriggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == GlassApp.ACTION_PHOTO_TRIGGER) takePhoto()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        previewImage = findViewById(R.id.previewImage)
        tvFlash      = findViewById(R.id.tvFlash)
        tvHint       = findViewById(R.id.tvShutterHint)

        photoDir = File(getExternalFilesDir(null), "Photos").also { it.mkdirs() }
        remoteShotPending = intent.getBooleanExtra("remote", false)

        LocalBroadcastManager.getInstance(this).registerReceiver(
            remoteTriggerReceiver, IntentFilter(GlassApp.ACTION_PHOTO_TRIGGER)
        )
    }

    override fun onResume() {
        super.onResume()
        openCamera()
        if (remoteShotPending) {
            remoteShotPending = false
            mainHandler.postDelayed(::takePhoto, 800)
        }
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacksAndMessages(null)
        releaseCamera()
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(remoteTriggerReceiver)
        decodeThread.quitSafely()
        dummyTexture.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            takePhoto(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Camera lifecycle ──────────────────────────────────────────────────

    private fun openCamera() {
        if (camera != null) return
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(0, info)
        sensorAngle = info.orientation

        camera = Camera.open(0).also { cam ->
            val params = cam.parameters
            // If the sensor is portrait-mounted (90° or 270°), its native sizes are
            // portrait too — target portrait dimensions so bestPreviewSize picks correctly.
            val rotated = sensorAngle == 90 || sensorAngle == 270
            val (tw, th) = if (rotated) Pair(240, 320) else Pair(320, 240)
            val best = bestPreviewSize(params, tw, th)
            previewW = best.first
            previewH = best.second
            params.setPreviewSize(previewW, previewH)
            cam.parameters = params

            cam.setPreviewTexture(dummyTexture)
            addPreviewBuffers(cam)
            cam.setPreviewCallbackWithBuffer(previewCallback)
            cam.startPreview()
            previewRunning = true
        }
    }

    private fun releaseCamera() {
        camera?.apply {
            setPreviewCallbackWithBuffer(null)
            if (previewRunning) stopPreview()
            release()
        }
        camera = null
        previewRunning = false
    }

    private fun addPreviewBuffers(cam: Camera) {
        val size = previewW * previewH * 3 / 2 // NV21 byte count
        cam.addCallbackBuffer(ByteArray(size))
        cam.addCallbackBuffer(ByteArray(size))
    }

    // ── Preview callback ──────────────────────────────────────────────────

    private val previewCallback = Camera.PreviewCallback { data, cam ->
        if (data == null) return@PreviewCallback
        if (frameReady.compareAndSet(true, false)) {
            val copy = data.copyOf()      // copy before returning buffer to camera
            cam.addCallbackBuffer(data)   // return immediately so HAL can reuse it
            decodeHandler.post { decodeAndShow(copy) }
        } else {
            cam.addCallbackBuffer(data)   // decoder busy — drop this frame
        }
    }

    private fun decodeAndShow(data: ByteArray) {
        try {
            val yuv = YuvImage(data, ImageFormat.NV21, previewW, previewH, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, previewW, previewH), 75, out)
            var bmp: Bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
                ?: return
            if (sensorAngle != 0) {
                val m = Matrix().apply { postRotate(sensorAngle.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false)
            }
            val frame = bmp
            mainHandler.post { previewImage.setImageBitmap(frame) }
        } finally {
            frameReady.set(true)
        }
    }

    // ── Preview size selection ────────────────────────────────────────────

    private fun bestPreviewSize(params: Camera.Parameters, targetW: Int, targetH: Int): Pair<Int, Int> {
        val targetRatio = targetW.toFloat() / targetH.toFloat()
        val sizes = params.supportedPreviewSizes ?: return Pair(targetW, targetH)
        return sizes
            .sortedWith(compareBy(
                { Math.abs(it.width.toFloat() / it.height.toFloat() - targetRatio) },
                { Math.abs(it.width - targetW) }
            ))
            .first()
            .let { Pair(it.width, it.height) }
    }

    // ── Photo capture ─────────────────────────────────────────────────────

    private fun takePhoto() {
        val cam = camera ?: return
        cam.takePicture(null, null) { jpeg, _ ->
            val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(photoDir, "Glass_$ts.jpg")
            FileOutputStream(file).use { it.write(jpeg) }

            flashEffect()
            tvHint.text = getString(R.string.photo_saved)
            mainHandler.postDelayed({ tvHint.text = getString(R.string.tap_to_shoot) }, 2000)

            startService(Intent(this, GlassService::class.java)
                .putExtra("photo_path", file.absolutePath))

            // takePicture clears the buffer queue — re-add before restarting preview
            cam.startPreview()
            addPreviewBuffers(cam)
            previewRunning = true
        }
    }

    private fun flashEffect() {
        tvFlash.visibility = View.VISIBLE
        mainHandler.postDelayed({ tvFlash.visibility = View.GONE }, 100)
    }
}
