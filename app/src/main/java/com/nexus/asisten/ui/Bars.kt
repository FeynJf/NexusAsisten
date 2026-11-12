package com.nexus.asisten.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.nexus.asisten.R

fun Context.topBar(title: String, onBack: () -> Unit, trailing: View? = null): LinearLayout {
    val bar = hbox()
    bar.setPadding(dp(14), dp(12), dp(16), dp(10))
    val back = FrameLayout(this)
    val r = dpf(14f)
    back.rippleBg(rounded(withAlpha(Color.WHITE, 0x10), r, NX.LINE, 1), r)
    back.addView(icon(R.drawable.ic_back, NX.TEXT, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    back.setOnClickListener { onBack() }
    bar.add(back, dp(42), dp(42), 0f, 0, 0, 12, 0)
    bar.add(tv(title, 22f, NX.TEXT, 700).also { it.isAllCaps = true; it.letterSpacing = 0.04f }, 0, WRAP, 1f)
    if (trailing != null) bar.addView(trailing)
    return bar
}

/** Tab berbentuk kapsul; tab terpilih berisi gradien neon. */
fun Context.tabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
    val wrap = hbox()
    wrap.setPadding(dp(16), dp(4), dp(16), dp(10))
    val pill = hbox()
    pill.setPadding(dp(4), dp(4), dp(4), dp(4))
    pill.background = rounded(withAlpha(Color.WHITE, 0x0D), dpf(18f), NX.LINE, 1)
    labels.forEachIndexed { i, label ->
        val on = i == selected
        val t = tv(label, 13.5f, if (on) Color.WHITE else NX.MUTED, if (on) 700 else 500)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), dp(10), dp(8), dp(10))
        if (on) {
            t.background = gradientRounded(NX.ACCENT_DEEP, NX.BLUE, dpf(14f))
            t.glow(NX.ACCENT, 6f)
        }
        t.setOnClickListener { if (!on) onSelect(i) }
        t.pressFx(0.96f)
        pill.add(t, 0, WRAP, 1f)
    }
    wrap.add(pill, MATCH, WRAP, 1f)
    return wrap
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
