package com.nexus.asisten.core

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.nexus.asisten.MainActivity

object GameUtils {
    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }

    /** Buka aplikasi sebagai jendela mengambang (jika perangkat mendukung freeform). */
    fun launchFloating(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val dm = ctx.resources.displayMetrics
        val w = (dm.widthPixels * 0.55f).toInt()
        val h = (dm.heightPixels * 0.7f).toInt()
        val left = (dm.widthPixels - w) / 2
        val top = (dm.heightPixels - h) / 2
        val opts = ActivityOptions.makeBasic().setLaunchBounds(Rect(left, top, left + w, top + h))
        ctx.startActivity(i, opts.toBundle())
        return true
    }

    fun drawableToBitmap(d: Drawable, size: Int = 192): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    /** Pintasan layar utama: buka game lewat Nexus (profil + pembersihan otomatis). */
    fun pinShortcut(ctx: Context, pkg: String, name: String, icon: Drawable): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_QUICK_LAUNCH)
            .putExtra(MainActivity.EXTRA_PKG, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val info = ShortcutInfoCompat.Builder(ctx, "nx_$pkg")
            .setShortLabel(name)
            .setIcon(IconCompat.createWithBitmap(drawableToBitmap(icon)))
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }
}
