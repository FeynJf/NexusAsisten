package com.nexus.asisten.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

/** Palet Nexus Gamer: hitam pekat, aksen neon violet + cyan + magenta. */
object NX {
    val BG = Color.parseColor("#06070E")
    val SURFACE_DEEP = Color.parseColor("#080A14")
    val SURFACE_HI = Color.parseColor("#161B30")
    val SURFACE = Color.parseColor("#0C101B")
    val SURFACE2 = Color.parseColor("#141A2E")
    val LINE = Color.parseColor("#26FFFFFF")
    val TEXT = Color.parseColor("#F4F6FF")
    val TEXT2 = Color.parseColor("#AAB3D2")
    val MUTED = Color.parseColor("#667094")
    val ACCENT = Color.parseColor("#8B5CFF")
    val ACCENT_DEEP = Color.parseColor("#5B34E8")
    val MAGENTA = Color.parseColor("#FF4FD8")
    val BLUE = Color.parseColor("#2B8CFF")
    val CYAN = Color.parseColor("#22E4FF")
    val GOOD = Color.parseColor("#3DF0A0")
    val WARN = Color.parseColor("#FFB84D")
    val HOT = Color.parseColor("#FF4D6A")
    val PRO = Color.parseColor("#FF7A3D")
    val SCRIM = Color.parseColor("#B3000000")
    val GLASS = Color.parseColor("#D905070D")
}

fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

/** Latar layar: hitam dengan cahaya neon violet (kanan atas) dan cyan (kiri). */
fun screenBg(): Drawable {
    val d = Resources.getSystem().displayMetrics.density
    val base = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor("#0D0A24"), NX.BG, Color.parseColor("#03040A"))
    )
    val glowA = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        setGradientCenter(0.92f, 0.02f)
        gradientRadius = 380f * d
        colors = intArrayOf(withAlpha(NX.ACCENT, 0x46), 0)
    }
    val glowB = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        setGradientCenter(0.0f, 0.42f)
        gradientRadius = 300f * d
        colors = intArrayOf(withAlpha(NX.CYAN, 0x1C), 0)
    }
    val glowC = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        setGradientCenter(0.95f, 0.98f)
        gradientRadius = 320f * d
        colors = intArrayOf(withAlpha(NX.MAGENTA, 0x22), 0)
    }
    return LayerDrawable(arrayOf(base, glowA, glowB, glowC))
}

/** Kartu kaca hitam dengan garis tepi gradien neon (stroke gradien tidak ada di GradientDrawable, jadi digambar manual). */
class NeonDrawable(
    private val top: Int,
    private val bottom: Int,
    private val radius: Float,
    private val c1: Int = withAlpha(NX.ACCENT, 0x99),
    private val c2: Int = withAlpha(NX.CYAN, 0x66),
    private val strokePx: Float = 1.5f
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun onBoundsChange(b: android.graphics.Rect) {
        fill.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        // Kilau kaca tipis di bagian atas kartu.
        sheen.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.top + b.height() * 0.5f,
            0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        // Tepi neon tiga warna: violet -> cyan -> magenta, mengalir diagonal.
        line.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(c1, c2, withAlpha(NX.MAGENTA, 0x55)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        line.strokeWidth = strokePx
    }

    override fun draw(c: Canvas) {
        rect.set(bounds)
        rect.inset(strokePx / 2f, strokePx / 2f)
        c.drawRoundRect(rect, radius, radius, fill)
        c.drawRoundRect(rect, radius, radius, sheen)
        c.drawRoundRect(rect, radius, radius, line)
    }

    override fun getOutline(outline: Outline) = outline.setRoundRect(bounds, radius)
    override fun setAlpha(alpha: Int) { fill.alpha = alpha; sheen.alpha = alpha; line.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf; sheen.colorFilter = cf; line.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Cahaya neon berwarna di sekeliling view (bayangan berwarna). */
fun View.glow(color: Int, elevDp: Float = 10f) {
    elevation = elevDp * resources.displayMetrics.density
    outlineSpotShadowColor = color
    outlineAmbientShadowColor = color
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
    pressFx()
}

fun Context.tv(text: CharSequence, sp: Float = 14f, color: Int = NX.TEXT, weight: Int = 400): TextView {
    val t = TextView(this)
    t.text = text
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    t.setTextColor(color)
    t.typeface = when {
        weight >= 700 -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
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

/** Kartu standar: kaca hitam + tepi neon. */
fun Context.card(radiusDp: Int = 22, color: Int = NX.SURFACE): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val r = dpf(radiusDp.toFloat())
    background = if (color == NX.SURFACE)
        NeonDrawable(Color.parseColor("#F2131A33"), Color.parseColor("#F20A0D1A"), r, strokePx = dpf(1.3f))
    else rounded(color, r, NX.LINE, 1)
    clipToOutline = false
}

fun Context.sectionTitle(text: String): TextView =
    tv(text, 11f, NX.CYAN, 700).also {
        it.isAllCaps = true
        it.letterSpacing = 0.18f
        // Penanda gradien kecil di depan judul bagian.
        val bar = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(NX.CYAN, NX.ACCENT)).apply {
            cornerRadius = dpf(2f)
            setSize(dp(3), dp(12))
        }
        it.setCompoundDrawablesWithIntrinsicBounds(bar, null, null, null)
        it.compoundDrawablePadding = dp(8)
    }

fun Context.chip(text: String, selected: Boolean, accent: Int = NX.ACCENT, onClick: () -> Unit): TextView {
    val c = tv(text, 12.5f, if (selected) Color.WHITE else NX.TEXT2, if (selected) 700 else 500)
    c.gravity = Gravity.CENTER
    c.setPadding(dp(12), dp(11), dp(12), dp(11))
    val r = dpf(14f)
    val base = if (selected)
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, withAlpha(accent, 0xA0), withAlpha(NX.BLUE, 0xB0)))
            .also { it.cornerRadius = r; it.setStroke(1, withAlpha(Color.WHITE, 0x66)) }
    else rounded(NX.SURFACE2, r, NX.LINE, 1)
    c.rippleBg(base, r)
    if (selected) c.glow(accent, 8f)
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
                    Fx.say(this, labels[i].replace("\n", " · ") + " dipilih", Fx.OK)
                }
            }
            row.add(ch, 0, WRAP, 1f, 0, 0, if (i == labels.size - 1) 0 else 8, 0)
        }
    }
    build(selected)
    return row
}

fun Context.gradientButton(text: String, onClick: () -> Unit): TextView {
    val b = tv(text, 15f, Color.WHITE, 700)
    b.isAllCaps = true
    b.letterSpacing = 0.12f
    b.gravity = Gravity.CENTER
    b.setPadding(dp(20), dp(15), dp(20), dp(15))
    val r = dpf(16f)
    b.rippleBg(
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(NX.ACCENT_DEEP, NX.BLUE, NX.CYAN))
            .also { it.cornerRadius = r; it.setStroke(1, withAlpha(Color.WHITE, 0x55)) }, r
    )
    b.glow(NX.ACCENT, 14f)
    b.setOnClickListener { onClick() }
    return b
}

fun Context.ghostButton(text: String, onClick: () -> Unit): TextView {
    val b = tv(text, 14f, NX.TEXT, 700)
    b.isAllCaps = true
    b.letterSpacing = 0.06f
    b.gravity = Gravity.CENTER
    b.setPadding(dp(18), dp(14), dp(18), dp(14))
    val r = dpf(16f)
    b.rippleBg(rounded(withAlpha(Color.WHITE, 0x0C), r, withAlpha(NX.CYAN, 0x77), dp(1)), r)
    b.setOnClickListener { onClick() }
    return b
}

fun Context.styledSwitch(checked: Boolean, onChange: (Boolean) -> Unit): SwitchCompat {
    val sw = SwitchCompat(this)
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    sw.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, NX.TEXT2))
    sw.trackTintList = ColorStateList(states, intArrayOf(NX.ACCENT_DEEP, NX.SURFACE2))
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
    val sw = styledSwitch(checked) { c ->
        onChange(c)
        Fx.say(this, "$title: " + if (c) "aktif" else "mati", if (c) Fx.OK else Fx.INFO)
    }
    row.addView(sw)
    row.setOnClickListener { sw.toggle() }
    row.pressFx(0.98f)
    return row
}

fun Context.divider(): View = View(this).apply { setBackgroundColor(NX.LINE) }

fun Context.linkRow(title: String, sub: String?, iconRes: Int, onClick: () -> Unit): LinearLayout {
    val row = hbox()
    row.setPadding(dp(16), dp(14), dp(12), dp(14))
    val badge = android.widget.FrameLayout(this)
    badge.background = gradientRounded(withAlpha(NX.ACCENT, 0x44), withAlpha(NX.CYAN, 0x22), dpf(12f), GradientDrawable.Orientation.TL_BR)
        .also { it.setStroke(1, withAlpha(NX.ACCENT, 0x77)) }
    badge.addView(icon(iconRes, NX.CYAN, 20), android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    row.add(badge, dp(38), dp(38), 0f, 0, 0, 14, 0)
    val t = vbox()
    t.add(tv(title, 15f, NX.TEXT, 500))
    if (sub != null) t.add(tv(sub, 12.5f, NX.TEXT2), t = 2)
    row.add(t, 0, WRAP, 1f)
    row.add(icon(com.nexus.asisten.R.drawable.ic_arrow_right, NX.MUTED, 22), dp(22), dp(22))
    row.setOnClickListener { onClick() }
    row.pressFx(0.98f)
    return row
}

fun fmtDuration(ms: Long): String {
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "$h j $m m" else "$m m"
}

/** Tinggi status bar (px). */
fun Context.statusInset(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
}

/** Tinggi area navigasi bawah (px): tombol 3 tombol atau area gestur. */
fun Context.navInset(): Int {
    val modeId = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
    val mode = if (modeId > 0) resources.getInteger(modeId) else 0
    val id = resources.getIdentifier(if (mode == 2) "navigation_bar_gesture_height" else "navigation_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
}
