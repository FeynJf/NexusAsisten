package com.nexus.asisten.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

/** Palet Nexus: indigo gelap + violet sebagai aksen, cyan untuk data langsung. */
object NX {
    val BG = Color.parseColor("#0B0F1A")
    val SURFACE_DEEP = Color.parseColor("#0F1524")
    val SURFACE = Color.parseColor("#131A2B")
    val SURFACE2 = Color.parseColor("#1B2540")
    val LINE = Color.parseColor("#2A3656")
    val TEXT = Color.parseColor("#EAF0FF")
    val TEXT2 = Color.parseColor("#9AA7C7")
    val MUTED = Color.parseColor("#65729A")
    val ACCENT = Color.parseColor("#7B6CFF")
    val ACCENT_DEEP = Color.parseColor("#5A48F0")
    val CYAN = Color.parseColor("#3DE0FF")
    val GOOD = Color.parseColor("#3DDC97")
    val WARN = Color.parseColor("#FFB347")
    val HOT = Color.parseColor("#FF5D5D")
    val PRO = Color.parseColor("#FF7A3D")
    val SCRIM = Color.parseColor("#99050810")
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun gradientRounded(
    c1: Int, c2: Int, radiusPx: Float,
    orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.LEFT_RIGHT
): GradientDrawable = GradientDrawable(orientation, intArrayOf(c1, c2)).apply { cornerRadius = radiusPx }

fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun View.rippleBg(base: Drawable, radiusPx: Float) {
    background = RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF), base, rounded(Color.WHITE, radiusPx)
    )
}

fun Context.tv(text: CharSequence, sp: Float = 14f, color: Int = NX.TEXT, weight: Int = 400): TextView {
    val t = TextView(this)
    t.text = text
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    t.setTextColor(color)
    t.typeface = when {
        weight >= 700 -> Typeface.create("sans-serif", Typeface.BOLD)
        weight >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }
    return t
}

fun Context.icon(res: Int, tint: Int = NX.TEXT, sizeDp: Int = 24): ImageView {
    val i = ImageView(this)
    i.setImageResource(res)
    i.setColorFilter(tint)
    i.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    return i
}

fun LinearLayout.add(
    v: View, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f,
    l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
): View {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(context.dp(l), context.dp(t), context.dp(r), context.dp(b))
    addView(v, p)
    return v
}

fun Context.vbox(padH: Int = 0, padV: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
}

fun Context.hbox(center: Boolean = true): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    if (center) gravity = Gravity.CENTER_VERTICAL
}

fun Context.card(radiusDp: Int = 20, color: Int = NX.SURFACE): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(color, dpf(radiusDp.toFloat()), NX.LINE, 1)
}

fun Context.sectionTitle(text: String): TextView =
    tv(text, 13f, NX.TEXT2, 500)

fun Context.chip(text: String, selected: Boolean, accent: Int = NX.ACCENT, onClick: () -> Unit): TextView {
    val c = tv(text, 13f, if (selected) Color.WHITE else NX.TEXT2, 500)
    c.gravity = Gravity.CENTER
    c.setPadding(dp(14), dp(11), dp(14), dp(11))
    val r = dpf(12f)
    val base = if (selected) gradientRounded(accent, accent, r) else rounded(NX.SURFACE2, r, NX.LINE, 1)
    c.rippleBg(base, r)
    c.setOnClickListener { onClick() }
    return c
}

/** Deretan chip pilihan tunggal. Dibangun ulang saat pilihan berubah. */
fun Context.chipRow(
    labels: List<String>, selected: Int, accents: List<Int>? = null,
    onSelect: (Int) -> Unit
): LinearLayout {
    val row = hbox()
    fun build(sel: Int) {
        row.removeAllViews()
        labels.forEachIndexed { i, label ->
            val ch = chip(label, i == sel, accents?.getOrNull(i) ?: NX.ACCENT) {
                if (i != sel) {
                    build(i)
                    onSelect(i)
                }
            }
            row.add(ch, 0, WRAP, 1f, 0, 0, if (i == labels.size - 1) 0 else 8, 0)
        }
    }
    build(selected)
    return row
}

fun Context.gradientButton(text: String, onClick: () -> Unit): TextView {
    val b = tv(text, 16f, Color.WHITE, 700)
    b.gravity = Gravity.CENTER
    b.setPadding(dp(20), dp(15), dp(20), dp(15))
    b.rippleBg(gradientRounded(NX.ACCENT, NX.ACCENT_DEEP, dpf(16f)), dpf(16f))
    b.setOnClickListener { onClick() }
    return b
}

fun Context.ghostButton(text: String, onClick: () -> Unit): TextView {
    val b = tv(text, 15f, NX.TEXT, 500)
    b.gravity = Gravity.CENTER
    b.setPadding(dp(18), dp(14), dp(18), dp(14))
    b.rippleBg(rounded(Color.TRANSPARENT, dpf(16f), NX.LINE, dp(1)), dpf(16f))
    b.setOnClickListener { onClick() }
    return b
}

fun Context.styledSwitch(checked: Boolean, onChange: (Boolean) -> Unit): SwitchCompat {
    val sw = SwitchCompat(this)
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    sw.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, NX.TEXT2))
    sw.trackTintList = ColorStateList(states, intArrayOf(NX.ACCENT, NX.SURFACE2))
    sw.isChecked = checked
    sw.setOnCheckedChangeListener { _, c -> onChange(c) }
    return sw
}

fun Context.switchRow(
    title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit
): LinearLayout {
    val row = hbox()
    row.setPadding(dp(16), dp(14), dp(16), dp(14))
    val texts = vbox()
    texts.add(tv(title, 15f, NX.TEXT, 500))
    if (desc != null) texts.add(tv(desc, 12.5f, NX.TEXT2), t = 3)
    row.add(texts, 0, WRAP, 1f, 0, 0, 12, 0)
    val sw = styledSwitch(checked, onChange)
    row.addView(sw)
    row.setOnClickListener { sw.toggle() }
    return row
}

fun Context.divider(): View = View(this).apply { setBackgroundColor(NX.LINE) }

fun Context.linkRow(title: String, sub: String?, iconRes: Int, onClick: () -> Unit): LinearLayout {
    val row = hbox()
    row.setPadding(dp(16), dp(14), dp(12), dp(14))
    val ic = icon(iconRes, NX.ACCENT, 22)
    row.add(ic, dp(22), dp(22), 0f, 0, 0, 14, 0)
    val t = vbox()
    t.add(tv(title, 15f, NX.TEXT, 500))
    if (sub != null) t.add(tv(sub, 12.5f, NX.TEXT2), t = 2)
    row.add(t, 0, WRAP, 1f)
    row.add(icon(com.nexus.asisten.R.drawable.ic_arrow_right, NX.MUTED, 22), dp(22), dp(22))
    row.setOnClickListener { onClick() }
    return row
}

fun fmtDuration(ms: Long): String {
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "$h j $m m" else "$m m"
}
