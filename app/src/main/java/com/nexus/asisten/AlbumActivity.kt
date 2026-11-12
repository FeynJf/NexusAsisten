package com.nexus.asisten

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexus.asisten.core.ScreenCaptureService
import com.nexus.asisten.ui.*
import java.util.concurrent.Executors

/** Album game: cuplikan dan rekaman yang dibuat Nexus. */
class AlbumActivity : AppCompatActivity() {

    private var videoTab = false
    private var items: List<Uri> = emptyList()
    private lateinit var tabHolder: FrameLayout
    private lateinit var rv: RecyclerView
    private lateinit var empty: TextView
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = screenBg()
        root.addView(topBar("Album game", { finish() }))
        tabHolder = FrameLayout(this)
        root.addView(tabHolder)
        val body = FrameLayout(this)
        rv = RecyclerView(this)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = Adapter()
        Fx.listAnim(rv)
        rv.setPadding(dp(10), dp(10), dp(10), dp(10))
        rv.clipToPadding = false
        body.addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))
        empty = tv("Belum ada isi. Ambil cuplikan atau rekam dari panel Asisten Game.", 14f, NX.MUTED)
        empty.gravity = Gravity.CENTER
        body.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH).also { it.setMargins(dp(40), 0, dp(40), 0) })
        root.add(body, MATCH, 0, 1f)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        tabHolder.removeAllViews()
        tabHolder.addView(tabBar(listOf("Cuplikan", "Rekaman"), if (videoTab) 1 else 0) { videoTab = it == 1; load() })
        val video = videoTab
        pool.execute {
            val list = query(video)
            main.post { items = list; rv.adapter?.notifyDataSetChanged(); empty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE }
        }
    }

    private fun query(video: Boolean): List<Uri> {
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val dir = if (video) ScreenCaptureService.DIR_VIDEOS else ScreenCaptureService.DIR_IMAGES
        val out = mutableListOf<Uri>()
        try {
            contentResolver.query(
                base, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
                arrayOf("$dir%"), "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) out.add(ContentUris.withAppendedId(base, c.getLong(0)))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun open(u: Uri) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, if (videoTab) "video/*" else "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (_: Exception) { Fx.say(this, "Tidak ada aplikasi pembuka", Fx.WARN) }
    }

    private fun menu(u: Uri) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Bagikan", "Hapus")) { _, w ->
                if (w == 0) {
                    val s = Intent(Intent.ACTION_SEND).setType(if (videoTab) "video/mp4" else "image/png")
                        .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(s, "Bagikan"))
                } else {
                    try { contentResolver.delete(u, null, null) } catch (_: Exception) {
                        Fx.say(this, "Tidak bisa menghapus file ini", Fx.WARN)
                    }
                    load()
                }
            }.show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val box: FrameLayout, val img: ImageView, val play: ImageView) : RecyclerView.ViewHolder(box)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val size = (resources.displayMetrics.widthPixels - dp(20)) / 3
            val box = FrameLayout(this@AlbumActivity)
            box.layoutParams = RecyclerView.LayoutParams(size, size)
            box.setPadding(dp(3), dp(3), dp(3), dp(3))
            val img = ImageView(this@AlbumActivity)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.background = rounded(NX.SURFACE2, dpf(12f))
            img.clipToOutline = true
            img.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            box.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
            val play = icon(R.drawable.ic_play, android.graphics.Color.WHITE, 28)
            box.addView(play, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            return VH(box, img, play)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val u = items[pos]
            h.img.setImageDrawable(null)
            h.img.tag = u
            h.play.visibility = if (videoTab) android.view.View.VISIBLE else android.view.View.GONE
            pool.execute {
                try {
                    val bmp = contentResolver.loadThumbnail(u, Size(360, 360), null)
                    main.post { if (h.img.tag == u) h.img.setImageBitmap(bmp) }
                } catch (_: Exception) {
                }
            }
            h.box.setOnClickListener { open(u) }
            h.box.setOnLongClickListener { menu(u); true }
        }
    }
}
