package com.nexus.asisten.core

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject

/**
 * Menyalakan Firebase dari berkas google-services.json yang disimpan di assets.
 * Dibaca saat aplikasi berjalan (tanpa plugin Gradle), jadi build tidak gagal walau nama paket di berkas
 * berbeda. Untuk mengganti proyek Firebase cukup timpa berkas itu dengan hasil unduhan baru.
 */
object FirebaseConfig {
    private const val FILE = "google-services.json"

    class Info(val webClientId: String, val packageInJson: String, val packageMatches: Boolean, val projectId: String)

    @Volatile private var info: Info? = null

    /** Mengembalikan info konfigurasi, atau null bila berkas hilang/rusak. Aman dipanggil berulang. */
    @Synchronized
    fun ensure(ctx: Context): Info? {
        info?.let { return it }
        return try {
            val app = ctx.applicationContext
            val root = JSONObject(app.assets.open(FILE).bufferedReader().use { it.readText() })
            val project = root.getJSONObject("project_info")
            val clients = root.getJSONArray("client")
            // Pilih klien yang namanya sama dengan paket aplikasi; kalau tidak ada, pakai yang pertama.
            var chosen = clients.getJSONObject(0)
            var matches = false
            for (i in 0 until clients.length()) {
                val c = clients.getJSONObject(i)
                val pkg = c.getJSONObject("client_info").getJSONObject("android_client_info").getString("package_name")
                if (pkg == app.packageName) { chosen = c; matches = true; break }
            }
            val pkgJson = chosen.getJSONObject("client_info").getJSONObject("android_client_info").getString("package_name")
            val appId = chosen.getJSONObject("client_info").getString("mobilesdk_app_id")
            val apiKey = chosen.getJSONArray("api_key").getJSONObject(0).getString("current_key")
            var web = ""
            val oc = chosen.optJSONArray("oauth_client")
            if (oc != null) for (i in 0 until oc.length()) {
                val o = oc.getJSONObject(i)
                if (o.optInt("client_type") == 3) { web = o.getString("client_id"); break }
            }
            if (FirebaseApp.getApps(app).isEmpty()) {
                val b = FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setApplicationId(appId)
                    .setProjectId(project.getString("project_id"))
                if (project.optString("storage_bucket").isNotEmpty()) b.setStorageBucket(project.getString("storage_bucket"))
                FirebaseApp.initializeApp(app, b.build())
            }
            Info(web, pkgJson, matches, project.getString("project_id")).also { info = it }
        } catch (e: Throwable) {
            null
        }
    }
}
