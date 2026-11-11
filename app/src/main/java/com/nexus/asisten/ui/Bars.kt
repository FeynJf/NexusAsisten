package com.nexus.asisten.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import com.nexus.asisten.R

fun Context.topBar(title: String, onBack: () -> Unit, trailing: View? = null): LinearLayout {
    val bar = hbox()
    bar.setPadding(dp(8), dp(10), dp(16), dp(10))
    val back = icon(R.drawable.ic_back, NX.TEXT, 24)
    back.setPadding(dp(8), dp(8), dp(8), dp(8))
    back.setOnClickListener { onBack() }
    bar.add(back, dp(40), dp(40), 0f, 0, 0, 6, 0)
    bar.add(tv(title, 20f, NX.TEXT, 700), 0, WRAP, 1f)
    if (trailing != null) bar.addView(trailing)
    return bar
}

/** Tab dengan garis bawah aksen pada tab terpilih. */
fun Context.tabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
    val row = hbox(false)
    row.setPadding(dp(12), 0, dp(12), 0)
    labels.forEachIndexed { i, label ->
        val on = i == selected
        val t = vbox()
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.setPadding(dp(8), dp(8), dp(8), 0)
        t.addView(tv(label, 15f, if (on) NX.TEXT else NX.MUTED, if (on) 700 else 500))
        val bar = View(this)
        bar.setBackgroundColor(if (on) NX.ACCENT else 0)
        t.add(bar, MATCH, dp(3), 0f, 0, 8, 0, 0)
        t.setOnClickListener { onSelect(i) }
        row.add(t, WRAP, WRAP, 0f, 0, 0, 4, 0)
    }
    return row
}

fun Context.appIcon(d: Drawable?, sizeDp: Int, radiusDp: Int): ImageView {
    val iv = ImageView(this)
    iv.setImageDrawable(d)
    iv.background = rounded(NX.SURFACE2, dpf(radiusDp.toFloat()))
    iv.clipToOutline = true
    iv.outlineProvider = ViewOutlineProvider.BACKGROUND
    iv.scaleType = ImageView.ScaleType.CENTER_CROP
    iv.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    return iv
}
