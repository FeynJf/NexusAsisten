package com.nexus.asisten.data

import org.json.JSONObject

/** Pengaturan performa per game. */
data class GameProfile(
    val mode: Int = MODE_BALANCED,
    val refreshHz: Int = 0,          // 0 = biarkan sistem
    val ultraTouch: Boolean = false,
    val touchPrecision: Int = 0,     // -2..2
    val swipeSens: Int = 0,          // -2..2
    val blockCalls: Boolean = false,
    val blockNotif: Boolean = false
) {
    fun toJson(): String = JSONObject().apply {
        put("mode", mode); put("hz", refreshHz); put("ultra", ultraTouch)
        put("prec", touchPrecision); put("swipe", swipeSens)
        put("calls", blockCalls); put("notif", blockNotif)
    }.toString()

    companion object {
        const val MODE_SAVER = 0
        const val MODE_BALANCED = 1
        const val MODE_PRO = 2

        fun fromJson(s: String?): GameProfile {
            if (s.isNullOrEmpty()) return GameProfile()
            return try {
                val o = JSONObject(s)
                GameProfile(
                    o.optInt("mode", MODE_BALANCED), o.optInt("hz", 0), o.optBoolean("ultra", false),
                    o.optInt("prec", 0), o.optInt("swipe", 0),
                    o.optBoolean("calls", false), o.optBoolean("notif", false)
                )
            } catch (e: Exception) {
                GameProfile()
            }
        }
    }
}
