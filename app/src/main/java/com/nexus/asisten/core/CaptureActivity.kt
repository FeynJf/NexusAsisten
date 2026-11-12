package com.nexus.asisten.core

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import com.nexus.asisten.data.Prefs

/**
 * Activity transparan untuk meminta izin (MediaProjection dan, bila perlu, mikrofon).
 * Berjalan di task sendiri (taskAffinity kosong) supaya tidak menarik task Nexus ke depan
 * dan setelah selesai langsung kembali ke game.
 */
class CaptureActivity : Activity() {
    companion object {
        const val EXTRA_THEN = "then"
        const val THEN_MIC = "mic"
        private const val RC_PROJ = 77
        private const val RC_MIC = 78
    }

    private var then: String = ScreenCaptureService.ACTION_SHOT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        then = intent.getStringExtra(EXTRA_THEN) ?: intent.getStringExtra("action") ?: ScreenCaptureService.ACTION_SHOT
        val needMic = !Perms.mic(this) && (then == THEN_MIC || (then == ScreenCaptureService.ACTION_REC && Prefs.recordMic))
        if (needMic) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), RC_MIC)
        } else {
            afterMic()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_MIC) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!ok) Toast.makeText(this, "Izin mikrofon ditolak, rekaman tanpa suara mikrofon", Toast.LENGTH_SHORT).show()
            afterMic()
        }
    }

    private fun afterMic() {
        if (then == THEN_MIC) { done(); return }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), RC_PROJ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_PROJ) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_INIT)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                    .putExtra(ScreenCaptureService.EXTRA_THEN, then)
                startForegroundService(i)
            } else {
                Toast.makeText(this, "Izin perekaman layar ditolak", Toast.LENGTH_SHORT).show()
            }
            done()
        }
    }

    private fun done() {
        finish()
        overridePendingTransition(0, 0)
    }
}
