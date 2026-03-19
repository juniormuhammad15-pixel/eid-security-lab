package com.eidlab.cam

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Shows a completely black screen while the camera streams in background.
 * User sees nothing — phone looks off/dormant while actually streaming.
 */
class BlackScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dim screen to minimum
        val params = window.attributes
        params.screenBrightness = 0.01f  // Nearly off
        window.attributes = params
        // Prevent screenshot / screen recording of this activity
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Full black layout
        setContentView(R.layout.activity_black)
    }

    override fun onBackPressed() {
        // Do nothing — prevent accidental exit
    }
}
