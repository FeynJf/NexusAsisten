package com.nexus.asisten

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.nexus.asisten.core.Avatar
import com.nexus.asisten.data.Prefs
import com.nexus.asisten.ui.*
import java.security.MessageDigest

/** Profil: masuk/keluar akun Google (Gmail), nama tampilan, dan foto profil. */
class ProfileActivity : AppCompatActivity() {

    private companion object { const val RC_SIGN_IN = 4201 }

    private lateinit var root: LinearLayout

    private fun client() = GoogleSignIn.getClient(
        this,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile().build()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        val scroll = ScrollView(this)
        scroll.background = screenBg()
        scroll.isVerticalScrollBarEnabled = false
        root = vbox(18, 12)
        scroll.addView(root)
        setContentView(scroll)
        build()
    }

    private fun build() {
        root.removeAllViews()
        val bar = hbox()
        val back = FrameLayout(this)
        val r = dpf(15f)
        back.rippleBg(rounded(withAlpha(Color.WHITE, 0x10), r, NX.LINE, 1), r)
        back.addView(icon(R.drawable.ic_back, NX.TEXT, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        back.setOnClickListener { finish() }
        bar.add(back, dp(44), dp(44), 0f, 0, 0, 14, 0)
        bar.add(tv("PROFIL", 22f, NX.TEXT, 700).also { it.letterSpacing = 0.2f }, 0, WRAP, 1f)
        root.add(bar, b = 18)

        val logged = Prefs.googleEmail.isNotEmpty()
        val c = vbox(20, 24)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.background = NeonDrawable(
            Color.parseColor("#FF2A1A8C"), Color.parseColor("#FF080A1C"), dpf(30f),
            withAlpha(Color.WHITE, 0x66), withAlpha(NX.CYAN, 0x88), dpf(1.4f)
        )
        val ring = FrameLayout(this)
        ring.setPadding(dp(4), dp(4), dp(4), dp(4))
        ring.background = rounded(0, dpf(56f), withAlpha(NX.CYAN, 0xCC), dp(2))
        ring.glow(NX.CYAN, 14f)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.background = circle(NX.SURFACE2)
        iv.clipToOutline = true
        iv.outlineProvider = ViewOutlineProvider.BACKGROUND
        Avatar.into(this, iv)
        ring.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        c.add(ring, dp(104), dp(104))
        c.add(tv(Prefs.userName, 24f, NX.TEXT, 700).also { it.gravity = Gravity.CENTER }, WRAP, WRAP, 0f, 0, 14, 0, 0)
        c.add(
            tv(if (logged) Prefs.googleEmail else "Belum masuk", 13.5f, if (logged) NX.TEXT2 else NX.MUTED).also { it.gravity = Gravity.CENTER },
            WRAP, WRAP, 0f, 0, 2, 0, 0
        )
        val chip = tv(if (logged) "● Terhubung dengan Google" else "● Mode tamu", 11.5f, if (logged) NX.GOOD else NX.WARN, 700)
        chip.setPadding(dp(12), dp(6), dp(12), dp(6))
        chip.background = rounded(withAlpha(if (logged) NX.GOOD else NX.WARN, 0x1E), dpf(12f), withAlpha(if (logged) NX.GOOD else NX.WARN, 0x66), 1)
        c.add(chip, WRAP, WRAP, 0f, 0, 14, 0, 0)
        root.add(c, b = 18)

        if (!logged) {
            val g = card(24)
            g.setPadding(dp(18), dp(18), dp(18), dp(18))
            g.addView(sectionTitle("Akun Google"))
            g.add(tv("Masuk dengan Gmail untuk menyimpan nama dan foto profil Anda di Nexus.", 13f, NX.TEXT2), t = 8, b = 16)
            g.addView(googleButton())
            root.add(g, b = 16)
        } else {
            val g = card(24)
            g.setPadding(dp(18), dp(18), dp(18), dp(18))
            g.addView(sectionTitle("Akun Google"))
            g.add(tv("Anda masuk sebagai ${Prefs.googleName.ifEmpty { Prefs.userName }}.", 13f, NX.TEXT2), t = 8, b = 14)
            g.addView(ghostButton("Keluar dari akun") { signOut() })
            root.add(g, b = 16)
        }

        val n = card(24)
        n.setPadding(dp(18), dp(16), dp(18), dp(16))
        n.addView(sectionTitle("Nama tampilan"))
        val row = hbox()
        row.add(tv(Prefs.userName, 17f, NX.TEXT, 700), 0, WRAP, 1f)
        row.addView(tv("UBAH", 12f, NX.ACCENT, 700))
        row.pressFx(0.97f)
        row.setOnClickListener { renameDialog() }
        n.add(row, t = 10)
        root.add(n)
        root.add(android.view.View(this), MATCH, dp(28))
    }

    private fun googleButton(): TextView {
        val label = SpannableString("G  Masuk dengan Google")
        label.setSpan(ForegroundColorSpan(Color.parseColor("#4285F4")), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(ForegroundColorSpan(Color.parseColor("#1B1F33")), 1, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val b = tv("", 15f, Color.parseColor("#1B1F33"), 700)
        b.text = label
        b.gravity = Gravity.CENTER
        b.setPadding(dp(16), dp(15), dp(16), dp(15))
        b.background = rounded(Color.WHITE, dpf(18f))
        b.glow(NX.ACCENT, 10f)
        b.pressFx(0.96f)
        b.setOnClickListener { signIn() }
        return b
    }

    private fun signIn() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(client().signInIntent, RC_SIGN_IN)
        } catch (e: Throwable) {
            Fx.say(this, "Layanan Google tidak tersedia di HP ini", Fx.WARN)
        }
    }

    private fun signOut() {
        client().signOut().addOnCompleteListener {
            Prefs.googleEmail = ""; Prefs.googleName = ""; Prefs.googlePhoto = ""
            Avatar.clear(this)
            Fx.say(this, "Sudah keluar")
            build()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_SIGN_IN) return
        try {
            val acct = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            Prefs.googleEmail = acct.email ?: ""
            Prefs.googleName = acct.displayName ?: ""
            Prefs.googlePhoto = acct.photoUrl?.toString() ?: ""
            val first = (acct.givenName ?: acct.displayName ?: "").trim()
            if (first.isNotEmpty()) Prefs.userName = first
            Fx.say(this, "Berhasil masuk", Fx.OK)
            build()
            if (Prefs.googlePhoto.isNotEmpty()) Avatar.fetch(this, Prefs.googlePhoto) { if (!isFinishing) build() }
        } catch (e: ApiException) {
            when (e.statusCode) {
                12501 -> Fx.say(this, "Masuk dibatalkan")
                7 -> Fx.say(this, "Tidak ada koneksi internet", Fx.WARN)
                else -> setupDialog(e.statusCode)
            }
        } catch (e: Throwable) {
            Fx.say(this, "Gagal masuk: ${e.message ?: "-"}", Fx.WARN)
        }
    }

    private fun sha1(): String = try {
        val pi = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val sig = pi.signingInfo.apkContentsSigners[0]
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
    } catch (e: Throwable) { "-" }

    /** Kode 10/12500 hampir selalu berarti OAuth Client Android belum didaftarkan: tampilkan data yang dibutuhkan. */
    private fun setupDialog(code: Int) {
        val sha = sha1()
        AlertDialog.Builder(this)
            .setTitle("Login Google belum siap (kode $code)")
            .setMessage(
                "Daftarkan aplikasi ini di Google Cloud Console (APIs & Services > Credentials > OAuth client ID > Android):\n\n" +
                    "Package: $packageName\nSHA-1: $sha\n\n" +
                    "Lalu isi OAuth consent screen dan tambahkan email Anda sebagai Test user."
            )
            .setPositiveButton("Salin SHA-1") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("sha1", sha))
                Fx.say(this, "SHA-1 disalin", Fx.OK)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun renameDialog() {
        val et = EditText(this)
        et.setText(Prefs.userName)
        et.setTextColor(NX.TEXT)
        et.setSingleLine()
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et)
        AlertDialog.Builder(this)
            .setTitle("Nama tampilan")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) { Prefs.userName = n; build() }
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
