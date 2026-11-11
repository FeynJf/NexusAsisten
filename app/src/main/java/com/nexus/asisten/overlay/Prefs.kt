package com.nexus.asisten.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Semua pengaturan & statistik lokal Nexus Asisten. */
object Prefs {
    private var sp: SharedPreferences? = null
    private val s: SharedPreferences get() = sp!!

    fun init(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences("nexus_asisten", Context.MODE_PRIVATE)
    }

    private fun b(k: String, d: Boolean) = s.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) = s.edit().putBoolean(k, v).apply()
    private fun i(k: String, d: Int) = s.getInt(k, d)
    private fun putI(k: String, v: Int) = s.edit().putInt(k, v).apply()

    var userName: String
        get() = s.getString("user", "Gamer") ?: "Gamer"
        set(v) = s.edit().putString("user", v).apply()

    // Pengaturan umum
    var openAnimation: Boolean get() = b("open_anim", true); set(v) = putB("open_anim", v)
    var albumEnabled: Boolean get() = b("album", true); set(v) = putB("album", v)
    var autoAdjust: Boolean get() = b("auto_adjust", true); set(v) = putB("auto_adjust", v)
    var tempWarn: Int get() = i("temp_warn", 43); set(v) = putI("temp_warn", v)
    var quickStart: Boolean get() = b("quick_start", true); set(v) = putB("quick_start", v)
    var recordMic: Boolean get() = b("rec_mic", false); set(v) = putB("rec_mic", v)
    var recordFps60: Boolean get() = b("rec_60", false); set(v) = putB("rec_60", v)
    var recordHd: Boolean get() = b("rec_hd", true); set(v) = putB("rec_hd", v)

    // Asisten Game (overlay)
    var dockLeft: Boolean get() = b("dock_left", true); set(v) = putB("dock_left", v)
    var floatingWindow: Boolean get() = b("float_win", true); set(v) = putB("float_win", v)
    var notifDots: Boolean get() = b("notif_dots", true); set(v) = putB("notif_dots", v)
    var crosshair: Boolean get() = b("crosshair", false); set(v) = putB("crosshair", v)
    var crosshairStyle: Int get() = i("crosshair_style", 0); set(v) = putI("crosshair_style", v)
    var edgeGuard: Boolean get() = b("edge_guard", false); set(v) = putB("edge_guard", v)
    var keepAwake: Boolean get() = b("keep_awake", true); set(v) = putB("keep_awake", v)
    var focusMode: Boolean get() = b("focus_mode", false); set(v) = putB("focus_mode", v)

    // Daftar game & dock
    private fun list(key: String): MutableList<String> {
        val out = mutableListOf<String>()
        try {
            val a = JSONArray(s.getString(key, "[]"))
            for (n in 0 until a.length()) out.add(a.getString(n))
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveList(key: String, l: List<String>) {
        val a = JSONArray()
        l.forEach { a.put(it) }
        s.edit().putString(key, a.toString()).apply()
    }

    var games: List<String> get() = list("games"); set(v) = saveList("games", v)
    var dockApps: List<String> get() = list("dock"); set(v) = saveList("dock", v)
    var lastGame: String? get() = s.getString("last_game", null); set(v) = s.edit().putString("last_game", v).apply()

    // Profil per game
    fun profile(pkg: String): GameProfile = GameProfile.fromJson(s.getString("profile_$pkg", null))
    fun saveProfile(pkg: String, p: GameProfile) = s.edit().putString("profile_$pkg", p.toJson()).apply()

    // Nilai asli pengaturan sistem untuk dipulihkan
    var origPeak: Float get() = s.getFloat("orig_peak", -2f); set(v) = s.edit().putFloat("orig_peak", v).apply()
    var origMin: Float get() = s.getFloat("orig_min", -2f); set(v) = s.edit().putFloat("orig_min", v).apply()
    var origDnd: Int get() = i("orig_dnd", -1); set(v) = putI("orig_dnd", v)

    // Waktu bermain internal (per hari, 30 hari terakhir)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun addPlay(pkg: String, ms: Long) {
        if (ms <= 0) return
        val o = try { JSONObject(s.getString("pd_$pkg", "{}")) } catch (_: Exception) { JSONObject() }
        val today = dayFmt.format(Calendar.getInstance().time)
        o.put(today, o.optLong(today, 0L) + ms)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -40)
        val cutoff = dayFmt.format(cal.time)
        val keys = o.keys().asSequence().toList()
        keys.filter { it < cutoff }.forEach { o.remove(it) }
        s.edit().putString("pd_$pkg", o.toString())
            .putLong("pt_$pkg", s.getLong("pt_$pkg", 0L) + ms).apply()
    }

    fun playTotalInternal(pkg: String): Long = s.getLong("pt_$pkg", 0L)

    fun play30Internal(pkg: String): Long {
        val o = try { JSONObject(s.getString("pd_$pkg", "{}")) } catch (_: Exception) { return 0L }
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -30)
        val cutoff = dayFmt.format(cal.time)
        var sum = 0L
        o.keys().forEach { if (it >= cutoff) sum += o.optLong(it, 0L) }
        return sum
    }
}
