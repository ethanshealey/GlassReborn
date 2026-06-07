package com.glass.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class GalleryActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var imgFull: ImageView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView

    private lateinit var photoDir: File
    private var photos = emptyList<File>()
    private var selectedIndex = -1

    private lateinit var adapter: PhotoAdapter
    private lateinit var llm: LinearLayoutManager
    private lateinit var snapHelper: LinearSnapHelper
    private var swipeStartX = 0f
    private var swipeStartY = 0f

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        rv      = findViewById(R.id.rvPhotos)
        imgFull = findViewById(R.id.imgFull)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvCount = findViewById(R.id.tvPhotoCount)

        photoDir = File(getExternalFilesDir(null), "Photos")

        setupCarousel()
        loadPhotos()
    }

    override fun onResume() {
        super.onResume()
        loadPhotos()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Carousel setup ────────────────────────────────────────────────────

    private fun setupCarousel() {
        llm = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rv.layoutManager = llm
        rv.clipToPadding = false
        rv.overScrollMode = View.OVER_SCROLL_NEVER

        rv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val itemW = dpToPx(120 + 12)
                val pad   = (rv.width - itemW) / 2
                rv.setPadding(pad, 0, pad, 0)
                applyScaleToVisible()
            }
        })

        snapHelper = LinearSnapHelper()
        snapHelper.attachToRecyclerView(rv)

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = applyScaleToVisible()
        })
    }

    private fun applyScaleToVisible() {
        val centerX = rv.width / 2f
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val childCenterX = (child.left + child.right) / 2f
            val fraction = (abs(centerX - childCenterX) / (rv.width / 2f)).coerceIn(0f, 1f)
            val scale    = MAX_SCALE - (MAX_SCALE - MIN_SCALE) * fraction
            child.scaleX = scale
            child.scaleY = scale
        }
    }

    // ── Data ──────────────────────────────────────────────────────────────

    private fun loadPhotos() {
        photos = photoDir.listFiles { f ->
            f.extension.lowercase() in setOf("jpg", "jpeg", "png")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        tvCount.text = "${photos.size} photo${if (photos.size != 1) "s" else ""}"
        val empty = photos.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rv.visibility      = if (empty) View.GONE    else View.VISIBLE

        adapter = PhotoAdapter(photos, scope) { openPhoto(it) }
        rv.adapter = adapter
    }

    // ── Full-screen viewer ────────────────────────────────────────────────

    private fun openPhoto(index: Int) {
        if (photos.isEmpty()) return
        val clamped = index.coerceIn(0, photos.lastIndex)
        selectedIndex = clamped
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(photos[clamped].absolutePath)
            } ?: return@launch
            imgFull.setImageBitmap(bmp)
        }
        imgFull.visibility = View.VISIBLE
        rv.visibility      = View.GONE
        tvCount.visibility = View.GONE
    }

    private fun closeFullView() {
        imgFull.setImageBitmap(null)
        imgFull.visibility = View.GONE
        rv.visibility      = View.VISIBLE
        tvCount.visibility = View.VISIBLE
        selectedIndex      = -1
    }

    // ── Input ─────────────────────────────────────────────────────────────

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { swipeStartX = event.x; swipeStartY = event.y }
            MotionEvent.ACTION_UP -> {
                val diffX = event.x - swipeStartX
                val diffY = event.y - swipeStartY
                if (diffY > 60 && abs(diffY) > abs(diffX)) {
                    if (imgFull.visibility == View.VISIBLE) closeFullView() else finish()
                    return true
                }
                if (imgFull.visibility == View.VISIBLE) {
                    when {
                        diffX > 60   -> openPhoto(selectedIndex - 1)
                        diffX < -60  -> openPhoto(selectedIndex + 1)
                        abs(diffX) < 30 -> closeFullView()
                    }
                } else {
                    when {
                        diffX > 60   -> scrollCarousel(-1)
                        diffX < -60  -> scrollCarousel(+1)
                        abs(diffX) < 30 -> openCentrePhoto()
                    }
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (imgFull.visibility == View.VISIBLE) closeFullView() else openCentrePhoto()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (imgFull.visibility == View.VISIBLE) { closeFullView(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun scrollCarousel(delta: Int) {
        val snapView = snapHelper.findSnapView(llm)
        val current  = if (snapView != null) llm.getPosition(snapView)
                       else llm.findFirstVisibleItemPosition()
        rv.smoothScrollToPosition((current + delta).coerceIn(0, photos.lastIndex))
    }

    private fun openCentrePhoto() {
        val snapView = snapHelper.findSnapView(llm) ?: return
        openPhoto(llm.getPosition(snapView))
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MAX_SCALE = 1.15f
        private const val MIN_SCALE = 0.78f
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    private class PhotoAdapter(
        private val files: List<File>,
        private val scope: CoroutineScope,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<PhotoAdapter.VH>() {

        // Shared thumbnail cache: holds up to 24 decoded bitmaps
        private val cache = LruCache<String, Bitmap>(24)

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val img: ImageView = view.findViewById(R.id.imgThumb)
            var loadJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_gallery_photo, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val key = files[position].absolutePath

            // Instant display if already cached
            val cached = cache.get(key)
            if (cached != null) {
                holder.img.setImageBitmap(cached)
                return
            }

            holder.img.setImageBitmap(null)
            holder.loadJob?.cancel()
            holder.loadJob = scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    // inSampleSize=8: 5MP image → ~324×243px, plenty for a 120dp thumbnail
                    BitmapFactory.decodeFile(key, BitmapFactory.Options().apply { inSampleSize = 8 })
                } ?: return@launch
                cache.put(key, bmp)
                // Only apply if this ViewHolder still represents the same position
                if (holder.bindingAdapterPosition == position) {
                    holder.img.setImageBitmap(bmp)
                }
            }
        }

        override fun onViewRecycled(holder: VH) {
            holder.loadJob?.cancel()
            holder.loadJob = null
        }

        override fun getItemCount() = files.size
    }
}
