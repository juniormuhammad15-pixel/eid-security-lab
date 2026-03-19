const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = process.env.PORT || 3000;
const FCM_SERVER_KEY = process.env.FCM_SERVER_KEY || '';
const DASHBOARD_PASSWORD = process.env.DASHBOARD_PASSWORD || 'lab2024';

// In-memory state
const cameras = {}; // { cameraId: { name, token, status, lastSeen, snapshots[] } }
const peers = {};   // WebRTC signaling peers
const sessions = new Set(); // authenticated dashboard sessions

// Snapshot dir
const SNAP_DIR = path.join(__dirname, 'snapshots');
if (!fs.existsSync(SNAP_DIR)) fs.mkdirSync(SNAP_DIR, { recursive: true });

// ── HTTP Server ──────────────────────────────────────
const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost`);
  const pathname = url.pathname;

  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  // ── API Routes ──
  if (pathname.startsWith('/api/')) {
    handleAPI(req, res, pathname, url);
    return;
  }

  // ── Snapshots ──
  if (pathname.startsWith('/snapshots/')) {
    const file = path.join(SNAP_DIR, path.basename(pathname));
    if (fs.existsSync(file)) {
      res.writeHead(200, { 'Content-Type': 'image/jpeg' });
      fs.createReadStream(file).pipe(res);
    } else { res.writeHead(404); res.end(); }
    return;
  }

  // ── Static files ──
  let filePath = path.join(__dirname, 'public', pathname === '/' ? 'index.html' : pathname);
  const ext = path.extname(filePath);
  const mime = { '.html':'text/html','.js':'application/javascript','.css':'text/css',
    '.json':'application/json','.png':'image/png','.svg':'image/svg+xml','.ico':'image/x-icon' };
  fs.readFile(filePath, (err, data) => {
    if (err) {
      fs.readFile(path.join(__dirname, 'public', 'index.html'), (e, d) => {
        if (e) { res.writeHead(404); res.end('Not found'); return; }
        res.writeHead(200, { 'Content-Type': 'text/html' }); res.end(d);
      });
      return;
    }
    res.writeHead(200, { 'Content-Type': mime[ext] || 'text/plain' });
    res.end(data);
  });
});

// ── WebSocket (manual upgrade) ───────────────────────
const clients = new Map(); // ws → { type, id }

server.on('upgrade', (req, socket, head) => {
  // Simple WebSocket handshake
  const key = req.headers['sec-websocket-key'];
  if (!key) { socket.destroy(); return; }
  const accept = require('crypto')
    .createHash('sha1')
    .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
    .digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
  );
  setupWS(socket, req);
});

function setupWS(socket, req) {
  socket.on('data', buf => {
    try {
      const msg = decodeWS(buf);
      if (!msg) return;
      const data = JSON.parse(msg);
      handleWS(socket, data);
    } catch(e) {}
  });
  socket.on('close', () => {
    const info = clients.get(socket);
    if (info && (info.type === 'camera' || info.type === 'ios')) {
      if (cameras[info.id]) cameras[info.id].status = 'offline';
      broadcast({ type: 'camera_offline', cameraId: info.id });
    }
    clients.delete(socket);
  });
  socket.on('error', () => {});
}

function handleWS(socket, data) {
  switch(data.type) {

    case 'camera_register':
      clients.set(socket, { type: 'camera', id: data.cameraId });
      cameras[data.cameraId] = {
        name: data.name || `Camera ${data.cameraId}`,
        token: data.fcmToken || '',
        platform: 'android',
        status: 'online',
        lastSeen: Date.now(),
        snapshots: getSnapshots(data.cameraId)
      };
      broadcast({ type: 'camera_online', cameraId: data.cameraId, name: cameras[data.cameraId].name, platform: 'android' });
      sendWS(socket, { type: 'registered', cameraId: data.cameraId });
      break;

    case 'ios_register':
      clients.set(socket, { type: 'ios', id: data.cameraId });
      cameras[data.cameraId] = {
        name: data.name || `iPhone ${data.cameraId}`,
        token: '',
        platform: 'ios',
        capabilities: data.capabilities || ['audio','snapshot','camera_roll'],
        status: 'online',
        lastSeen: Date.now(),
        snapshots: getSnapshots(data.cameraId)
      };
      broadcast({ type: 'camera_online', cameraId: data.cameraId, name: cameras[data.cameraId].name, platform: 'ios', capabilities: cameras[data.cameraId].capabilities });
      sendWS(socket, { type: 'registered', cameraId: data.cameraId });
      break;

    case 'audio_started':
      broadcast({ type: 'ios_audio_started', cameraId: data.cameraId });
      if (cameras[data.cameraId]) cameras[data.cameraId].audioActive = true;
      break;

    case 'audio_stopped':
      broadcast({ type: 'ios_audio_stopped', cameraId: data.cameraId });
      if (cameras[data.cameraId]) cameras[data.cameraId].audioActive = false;
      break;

    case 'audio_chunk':
      // Works for both Android PCM and iOS WebM chunks
      broadcast({ type: 'ios_audio_chunk', cameraId: data.cameraId, chunk: data.chunk, mimeType: data.mimeType, sampleRate: data.sampleRate });
      if (cameras[data.cameraId]) cameras[data.cameraId].lastSeen = Date.now();
      break;

    case 'gallery_image':
      // Relay gallery image from Android to dashboard
      broadcast({ type: 'gallery_image', cameraId: data.cameraId, image: data.image, filename: data.filename, index: data.index, total: data.total });
      break;

    case 'gallery_done':
      broadcast({ type: 'gallery_done', cameraId: data.cameraId, count: data.count });
      break;

    case 'request_gallery':
      // Dashboard requesting gallery from specific camera
      relayToCamera(data.cameraId, { type: 'request_gallery', count: data.count || 10 });
      break;

    case 'request_snapshot':
      relayToCamera(data.cameraId, { type: 'request_snapshot', cameraId: data.cameraId });
      break;

    case 'dashboard_auth':
      if (data.password === DASHBOARD_PASSWORD) {
        clients.set(socket, { type: 'dashboard' });
        sendWS(socket, { type: 'auth_ok', cameras: getCameraList() });
      } else {
        sendWS(socket, { type: 'auth_fail' });
      }
      break;

    case 'webrtc_offer':
    case 'webrtc_answer':
    case 'webrtc_ice':
      // Relay WebRTC signaling between camera and dashboard
      relayToTarget(data.targetId, data);
      break;

    case 'snapshot':
      saveSnapshot(data.cameraId, data.image);
      if (cameras[data.cameraId]) {
        cameras[data.cameraId].snapshots = getSnapshots(data.cameraId);
        cameras[data.cameraId].lastSeen = Date.now();
      }
      broadcast({ type: 'new_snapshot', cameraId: data.cameraId, filename: data.filename });
      break;

    case 'motion':
      broadcast({ type: 'motion_alert', cameraId: data.cameraId, ts: Date.now() });
      break;

    case 'ping':
      if (clients.get(socket)?.type === 'camera') {
        const id = clients.get(socket).id;
        if (cameras[id]) cameras[id].lastSeen = Date.now();
      }
      sendWS(socket, { type: 'pong' });
      break;
  }
}

// ── API ──────────────────────────────────────────────
function handleAPI(req, res, pathname, url) {
  res.setHeader('Content-Type', 'application/json');

  // Wake camera via FCM
  if (pathname === '/api/wake' && req.method === 'POST') {
    let body = '';
    req.on('data', d => body += d);
    req.on('end', () => {
      const { cameraId } = JSON.parse(body || '{}');
      const cam = cameras[cameraId];
      if (!cam || !cam.token) {
        res.writeHead(404); res.end(JSON.stringify({ error: 'Camera not found or no FCM token' }));
        return;
      }
      sendFCM(cam.token, { type: 'wake', cameraId }).then(ok => {
        res.end(JSON.stringify({ success: ok }));
      });
    });
    return;
  }

  // Sleep camera
  if (pathname === '/api/sleep' && req.method === 'POST') {
    let body = '';
    req.on('data', d => body += d);
    req.on('end', () => {
      const { cameraId } = JSON.parse(body || '{}');
      relayToCamera(cameraId, { type: 'sleep_command' });
      res.end(JSON.stringify({ success: true }));
    });
    return;
  }

  // List cameras
  if (pathname === '/api/cameras') {
    res.end(JSON.stringify(getCameraList()));
    return;
  }

  // List snapshots for a camera
  if (pathname.startsWith('/api/snapshots/')) {
    const cameraId = pathname.split('/')[3];
    res.end(JSON.stringify(getSnapshots(cameraId)));
    return;
  }

  res.writeHead(404); res.end(JSON.stringify({ error: 'Not found' }));
}

// ── Helpers ──────────────────────────────────────────
function getCameraList() {
  return Object.entries(cameras).map(([id, c]) => ({
    id, name: c.name, status: c.status, lastSeen: c.lastSeen,
    snapshotCount: c.snapshots.length,
    latestSnapshot: c.snapshots[c.snapshots.length - 1] || null
  }));
}

function getSnapshots(cameraId) {
  return fs.readdirSync(SNAP_DIR)
    .filter(f => f.startsWith(`${cameraId}_`))
    .sort().slice(-50) // keep last 50 per camera
    .map(f => ({ filename: f, url: `/snapshots/${f}`, ts: f.split('_')[1] }));
}

function saveSnapshot(cameraId, base64) {
  if (!base64) return;
  const data = base64.replace(/^data:image\/\w+;base64,/, '');
  const filename = `${cameraId}_${Date.now()}.jpg`;
  fs.writeFileSync(path.join(SNAP_DIR, filename), Buffer.from(data, 'base64'));
}

function broadcast(data) {
  for (const [socket, info] of clients) {
    if (info.type === 'dashboard') sendWS(socket, data);
  }
}

function relayToTarget(targetId, data) {
  for (const [socket, info] of clients) {
    if (info.id === targetId || info.type === targetId) sendWS(socket, data);
  }
}

function relayToCamera(cameraId, data) {
  for (const [socket, info] of clients) {
    if (info.type === 'camera' && info.id === cameraId) sendWS(socket, data);
  }
}

function sendWS(socket, data) {
  try {
    const msg = JSON.stringify(data);
    const buf = Buffer.from(msg);
    const frame = Buffer.alloc(buf.length + 10);
    frame[0] = 0x81;
    let offset = 2;
    if (buf.length < 126) { frame[1] = buf.length; }
    else if (buf.length < 65536) {
      frame[1] = 126; frame.writeUInt16BE(buf.length, 2); offset = 4;
    } else {
      frame[1] = 127; frame.writeBigUInt64BE(BigInt(buf.length), 2); offset = 10;
    }
    buf.copy(frame, offset);
    socket.write(frame.slice(0, offset + buf.length));
  } catch(e) {}
}

function decodeWS(buf) {
  if (buf.length < 2) return null;
  const masked = (buf[1] & 0x80) !== 0;
  let len = buf[1] & 0x7f;
  let offset = 2;
  if (len === 126) { len = buf.readUInt16BE(2); offset = 4; }
  else if (len === 127) { len = Number(buf.readBigUInt64BE(2)); offset = 10; }
  if (masked) {
    const mask = buf.slice(offset, offset + 4); offset += 4;
    const data = buf.slice(offset, offset + len);
    for (let i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
    return data.toString();
  }
  return buf.slice(offset, offset + len).toString();
}

// ── FCM Push ─────────────────────────────────────────
function sendFCM(token, payload) {
  return new Promise(resolve => {
    if (!FCM_SERVER_KEY) { resolve(false); return; }
    const body = JSON.stringify({
      to: token,
      priority: 'high',
      data: payload,
      android: { priority: 'high' }
    });
    const opts = {
      hostname: 'fcm.googleapis.com',
      path: '/fcm/send',
      method: 'POST',
      headers: {
        'Authorization': `key=${FCM_SERVER_KEY}`,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body)
      }
    };
    const req = https.request(opts, r => {
      r.on('data', () => {}); r.on('end', () => resolve(r.statusCode === 200));
    });
    req.on('error', () => resolve(false));
    req.write(body); req.end();
  });
}

// ── Start ─────────────────────────────────────────────
server.listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat()
    .filter(i => i.family === 'IPv4' && !i.internal).map(i => i.address);
  console.log('\n🌙 ══════════════════════════════════════════');
  console.log('   Eid Lab Security System — Server');
  console.log('══════════════════════════════════════════\n');
  console.log(`✅  Running on port ${PORT}`);
  ips.forEach(ip => console.log(`   http://${ip}:${PORT}`));
  console.log(`\n🔐  Dashboard password: ${DASHBOARD_PASSWORD}`);
  console.log('\n💡  Set env vars:');
  console.log('   FCM_SERVER_KEY=your_key');
  console.log('   DASHBOARD_PASSWORD=yourpassword');
  console.log('\n══════════════════════════════════════════\n');
});
