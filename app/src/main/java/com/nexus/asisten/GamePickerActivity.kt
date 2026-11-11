package com.nexus.asisten

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexus.asisten.data.GameApp
import com.nexus.asisten.data.GameRepo
import com.nexus.asisten.data.Prefs
import com.nexus.asisten.overlay.OverlayService
import com.nexus.asisten.ui.*
import java.util.concurrent.Executors

/** Memilih game (MODE_GAMES) atau aplikasi dock Asisten Game (MODE_DOCK). */
class GamePickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_GAMES = "games"
        const val MODE_DOCK = "dock"
    }

    private var dock = false
    private var all: List<GameApp> = emptyList()
    private var shown: List<GameApp> = emptyList()
    private lateinit var adapter: Adapter
    private val selected = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        dock = intent.getStringExtra(EXTRA_MODE) == MODE_DOCK
        selected.addAll(if (dock) Prefs.dockApps else Prefs.games)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(NX.BG)
        root.addView(topBar(if (dock) "Aplikasi di dock (maks. 5)" else "Kelola game", { finish() }))

        val search = EditText(this)
        search.hint = "Cari aplikasi"
        search.setHintTextColor(NX.MUTED)
        search.setTextColor(NX.TEXT)
        search.setSingleLine()
        search.setPadding(dp(16), dp(12), dp(16), dp(12))
        search.background = rounded(NX.SURFACE, dpf(14f), NX.LINE, 1)
        root.add(search, MATCH, WRAP, 0f, 16, 4, 16, 8)

        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = Adapter()
        rv.adapter = adapter
        rv.clipToPadding = false
        rv.setPadding(dp(8), 0, dp(8), dp(16))
        root.add(rv, MATCH, 0, 1f)
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filter(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        Executors.newSingleThreadExecutor().execute {
            val apps = GameRepo.launchableApps(this)
            runOnUiThread { all = apps; filter("") }
        }
    }

    private fun filter(q: String) {
        shown = if (q.isBlank()) all else all.filter { it.name.contains(q, true) }
        adapter.notifyDataSetChanged()
    }

    private fun toggle(app: GameApp, on: Boolean): Boolean {
        if (on) {
            if (dock && selected.size >= 5) {
                Toast.makeText(this, "Dock maksimal 5 aplikasi", Toast.LENGTH_SHORT).show()
                return false
            }
            selected.add(app.pkg)
        } else selected.remove(app.pkg)
        if (dock) Prefs.dockApps = selected.toList() else Prefs.games = selected.toList()
        return true
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val row: LinearLayout, val icon: android.widget.ImageView, val name: android.widget.TextView,
                       val tag: android.widget.TextView, val check: CheckBox) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = hbox()
            row.setPadding(dp(12), dp(10), dp(12), dp(10))
            row.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            val ic = appIcon(null, 44, 12)
            row.add(ic, dp(44), dp(44), 0f, 0, 0, 14, 0)
            val t = vbox()
            val n = tv("", 15f, NX.TEXT, 500)
            n.maxLines = 1
            val g = tv("Game terdeteksi", 11.5f, NX.CYAN)
            t.addView(n); t.add(g, t = 2)
            row.add(t, 0, WRAP, 1f)
            val cb = CheckBox(this@GamePickerActivity)
            cb.buttonTintList = android.content.res.ColorStateList.valueOf(NX.ACCENT)
            cb.isClickable = false
            row.addView(cb)
            return VH(row, ic, n, g, cb)
        }

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val app = shown[pos]
            h.icon.setImageDrawable(app.icon)
            h.name.text = app.name
            h.tag.visibility = if (app.isGame) android.view.View.VISIBLE else android.view.View.GONE
            h.check.isChecked = app.pkg in selected
            h.row.setOnClickListener {
                val want = app.pkg !in selected
                if (toggle(app, want)) h.check.isChecked = want
            }
        }
    }
}
