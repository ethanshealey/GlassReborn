package com.glass.companion

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Browse photos stored on Glass (taken locally or sent from iPhone). */
class GalleryActivity : AppCompatActivity() {

    private lateinit var grid: GridView
    private lateinit var imgFull: ImageView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView

    private lateinit var photoDir: File
    private var photos = emptyList<File>()
    private var selectedIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        grid    = findViewById(R.id.gridPhotos)
        imgFull = findViewById(R.id.imgFull)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvCount = findViewById(R.id.tvPhotoCount)

        photoDir = File(getExternalFilesDir(null), "Photos")
        loadPhotos()

        grid.setOnItemClickListener { _, _, pos, _ -> openPhoto(pos) }
    }

    override fun onResume() {
        super.onResume()
        loadPhotos() // refresh in case new photos were taken
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (imgFull.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { openPhoto(selectedIndex + 1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT  -> { openPhoto(selectedIndex - 1); return true }
                KeyEvent.KEYCODE_BACK       -> { closeFullView(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun loadPhotos() {
        photos = (photoDir.listFiles { f ->
            f.extension.lowercase() in setOf("jpg", "jpeg", "png")
        }?.sortedByDescending { it.lastModified() } ?: emptyList())

        tvCount.text = "${photos.size} photo${if (photos.size != 1) "s" else ""}"
        tvEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility    = if (photos.isEmpty()) View.GONE else View.VISIBLE
        grid.adapter = PhotoAdapter(this, photos)
    }

    private fun openPhoto(index: Int) {
        val clamped = index.coerceIn(0, photos.lastIndex)
        selectedIndex = clamped
        val bmp = BitmapFactory.decodeFile(photos[clamped].absolutePath) ?: return
        imgFull.setImageBitmap(bmp)
        imgFull.visibility = View.VISIBLE
        grid.visibility    = View.GONE
    }

    private fun closeFullView() {
        imgFull.visibility = View.GONE
        grid.visibility    = View.VISIBLE
        selectedIndex = -1
    }

    private class PhotoAdapter(
        private val ctx: Context,
        private val files: List<File>
    ) : BaseAdapter() {
        override fun getCount() = files.size
        override fun getItem(pos: Int) = files[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val img = (convertView as? ImageView) ?: ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = ViewGroup.LayoutParams(80, 80)
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = BitmapFactory.decodeFile(files[pos].absolutePath, opts)
            img.setImageBitmap(bmp)
            return img
        }
    }
}
