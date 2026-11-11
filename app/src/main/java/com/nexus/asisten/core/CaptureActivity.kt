package com.nexus.asisten.core

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/** Activity transparan hanya untuk meminta izin MediaProjection, lalu menyerahkan ke service. */
class CaptureActivity : Activity() {
    private var action: String = ScreenCaptureService.ACTION_SHOT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        action = intent.getStringExtra("action") ?: ScreenCaptureService.ACTION_SHOT
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 77)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenCaptureService::class.java)
                .setAction(action)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
        } else {
            Toast.makeText(this, "Izin perekaman layar ditolak", Toast.LENGTH_SHORT).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
