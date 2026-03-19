package com.eidlab.cam

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class EidFCMService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        when (data["type"]) {
            "wake" -> {
                // Silently start the stream service
                val intent = Intent(this, StreamService::class.java).apply {
                    action = StreamService.ACTION_START
                }
                ContextCompat.startForegroundService(this, intent)
            }
            "sleep" -> {
                val intent = Intent(this, StreamService::class.java).apply {
                    action = StreamService.ACTION_STOP
                }
                startService(intent)
            }
        }
    }

    override fun onNewToken(token: String) {
        // Save new FCM token and re-register with server
        val prefs = getSharedPreferences("eid_cam", MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
    }
}
