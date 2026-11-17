#!/usr/bin/env python3
"""
Server KioskOS: status kunci perangkat, update OTA, dan dashboard admin.

Konsepnya mirip Knox Guard: status perangkat disimpan di server berdasarkan serial, jadi tetap
berlaku walau HP di-factory reset. Hanya butuh Python 3.9+ dan paket `cryptography`.

Untuk produksi: jalankan di belakang reverse proxy HTTPS (nginx/Caddy), simpan DATA_DIR di
disk yang di-backup, simpan private key di HSM/KMS, dan sajikan file OTA lewat CDN.

Endpoint perangkat (respons JSON ditandatangani, header X-Signature = ECDSA-P256-SHA256):
  GET /api/v1/devices/status?serial=..&imei=..&nonce=..
  GET /api/v1/ota/check?serial=..&device=..&fingerprint=..&build_utc=..&channel=..&nonce=..
  GET /ota/<file>                         file OTA (mendukung HTTP Range)

Dashboard admin:  GET /admin/
API admin (header "Authorization: Bearer <token>"):
  GET    /admin/api/whoami
  GET    /admin/api/devices
  PUT    /admin/api/devices/<serial>      {"enrolled","locked","message","contact","note"}
  DELETE /admin/api/devices/<serial>      lepas perangkat (lunas)
  GET    /admin/api/releases
  POST   /admin/api/releases              metadata rilis OTA (lihat scripts/31-publish-release.sh)
  PATCH  /admin/api/releases/<id>         {"rollout","paused","channel"}
  GET    /admin/api/audit?limit=200
  (alias lama: PUT/DELETE /admin/devices/<serial>, GET /admin/devices)

Konfigurasi (environment):
  DATA_DIR          folder data (default: server/data)
  KEY_PATH          private key penandatangan (default: server/private_key.pem)
  ADMIN_TOKEN       satu token admin bernama "admin" (cara cepat untuk pengujian)
  ADMIN_TOKENS_FILE file "nama:sha256(token)" per baris (lihat manage.py add-admin)
  PUBLIC_BASE_URL   URL publik server, untuk membentuk URL file OTA (default: dari header Host)
  HOST, PORT        alamat dengarkan (default 127.0.0.1:8080)
  DB_PATH           (lama) lokasi devices.json, menimpa DATA_DIR/devices.json
"""
import base64
import hashlib
import hmac
import json
import mimetypes
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.environ.get("DATA_DIR", os.path.join(HERE, "data"))
DEVICES_PATH = os.environ.get("DB_PATH", os.path.join(DATA_DIR, "devices.json"))
RELEASES_PATH = os.path.join(DATA_DIR, "releases.json")
AUDIT_PATH = os.path.join(DATA_DIR, "audit.log")
OTA_DIR = os.path.join(DATA_DIR, "ota")
STATIC_DIR = os.path.join(HERE, "static")
KEY_PATH = os.environ.get("KEY_PATH", os.path.join(HERE, "private_key.pem"))
PUBLIC_BASE_URL = os.environ.get("PUBLIC_BASE_URL", "").rstrip("/")
HOST = os.environ.get("HOST", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8080"))

SERIAL_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
RELEASE_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
OTA_FILE_RE = re.compile(r"^[A-Za-z0-9._-]{1,200}\.zip$")
MAX_BODY = 64 * 1024

_lock = threading.Lock()


# ------------------------------------------------------------------ penyimpanan

def _load(path, default):
    if not os.path.exists(path):
        return default
    with open(path) as f:
        return json.load(f)


def _save(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(data, f, indent=2, sort_keys=True)
    os.replace(tmp, path)


def audit(admin, ip, action, target, data=None):
    os.makedirs(DATA_DIR, exist_ok=True)
    entry = {"ts": int(time.time()), "admin": admin, "ip": ip, "action": action,
             "target": target, "data": data or {}}
    with open(AUDIT_PATH, "a") as f:
        f.write(json.dumps(entry, sort_keys=True) + "\n")


def read_audit(limit):
    if not os.path.exists(AUDIT_PATH):
        return []
    with open(AUDIT_PATH) as f:
        lines = f.readlines()[-limit:]
    return [json.loads(line) for line in reversed(lines) if line.strip()]


# ------------------------------------------------------------------ kunci & admin

def _load_private_key():
    with open(KEY_PATH, "rb") as f:
        return serialization.load_pem_private_key(f.read(), password=None)


PRIVATE_KEY = _load_private_key()


def sign(body: bytes) -> str:
    sig = PRIVATE_KEY.sign(body, ec.ECDSA(hashes.SHA256()))  # DER, cocok dengan SHA256withECDSA
    return base64.b64encode(sig).decode()


def load_admins():
    """Kembalikan {sha256(token): nama}."""
    admins = {}
    if os.environ.get("ADMIN_TOKEN"):
        admins[hashlib.sha256(os.environ["ADMIN_TOKEN"].encode()).hexdigest()] = "admin"
    path = os.environ.get("ADMIN_TOKENS_FILE")
    if path and os.path.exists(path):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and ":" in line:
                    name, digest = line.split(":", 1)
                    admins[digest.strip().lower()] = name.strip()
    return admins


ADMINS = load_admins()


def admin_for(auth_header):
    if not auth_header.startswith("Bearer "):
        return None
    digest = hashlib.sha256(auth_header[7:].encode()).hexdigest()
    for known, name in ADMINS.items():
        if hmac.compare_digest(known, digest):
            return name
    return None


# ------------------------------------------------------------------ logika OTA

def in_rollout(serial, release):
    rollout = int(release.get("rollout", 100))
    if rollout >= 100:
        return True
    bucket = int(hashlib.sha256(f"{serial}:{release['id']}".encode()).hexdigest()[:8], 16) % 100
    return bucket < rollout


def pick_release(releases, serial, device, fingerprint, build_utc, channel):
    """Rilis terbaru yang lebih baru dari build perangkat. Inkremental diutamakan bila cocok."""
    candidates = [
        r for r in releases.values()
        if not r.get("paused")
        and r.get("device") == device
        and r.get("channel", "stable") == channel
        and int(r.get("build_utc", 0)) > build_utc
        and in_rollout(serial, r)
        and (not r.get("pre_fingerprint") or r["pre_fingerprint"] == fingerprint)
    ]
    if not candidates:
        return None
    newest = max(int(r["build_utc"]) for r in candidates)
    newest_set = [r for r in candidates if int(r["build_utc"]) == newest]
    # Untuk build yang sama: inkremental (lebih kecil) dulu, kalau tidak ada pakai full.
    newest_set.sort(key=lambda r: 0 if r.get("pre_fingerprint") else 1)
    return newest_set[0]


# ------------------------------------------------------------------ HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "KioskOS/1.1"

    # ---- utilitas ----
    def log_message(self, fmt, *args):  # jangan cetak query string (berisi serial/IMEI) ke log
        path = self.path.split("?", 1)[0]
        print(f"{self.client_address[0]} {self.command} {path} -> {args[1] if len(args) > 1 else ''}")

    def _send(self, code, obj, signed=False, extra_headers=None):
        body = json.dumps(obj, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if signed:
            self.send_header("X-Signature", sign(body))
        for k, v in (extra_headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _error(self, code, msg):
        self._send(code, {"error": msg})

    def _read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        if length > MAX_BODY:
            raise ValueError("body terlalu besar")
        data = json.loads(self.rfile.read(length) or b"{}")
        if not isinstance(data, dict):
            raise ValueError("body harus objek JSON")
        return data

    def _admin(self):
        name = admin_for(self.headers.get("Authorization", ""))
        if not name:
            self._error(401, "unauthorized")
        return name

    def _ip(self):
        return self.headers.get("X-Forwarded-For", self.client_address[0]).split(",")[0].strip()

    def _base_url(self):
        if PUBLIC_BASE_URL:
            return PUBLIC_BASE_URL
        proto = self.headers.get("X-Forwarded-Proto", "http")
        return f"{proto}://{self.headers.get('Host', f'{HOST}:{PORT}')}"

    # ---- routing ----
    def do_GET(self):
        url = urlparse(self.path)
        p, q = url.path, parse_qs(url.query)
        if p == "/api/v1/devices/status":
            return self.device_status(q)
        if p == "/api/v1/ota/check":
            return self.ota_check(q)
        if p.startswith("/ota/"):
            return self.serve_ota(unquote(p[len("/ota/"):]), head=False)
        if p in ("/admin", "/admin/"):
            return self.serve_static("admin.html")
        if p.startswith("/admin/static/"):
            return self.serve_static(p[len("/admin/static/"):])
        if p == "/admin/api/whoami":
            name = self._admin()
            return name and self._send(200, {"admin": name})
        if p in ("/admin/api/devices", "/admin/devices"):
            return self._admin() and self.list_devices()
        if p == "/admin/api/releases":
            return self._admin() and self.list_releases()
        if p == "/admin/api/audit":
            if not self._admin():
                return
            limit = max(1, min(1000, int(q.get("limit", ["200"])[0] or 200)))
            return self._send(200, {"entries": read_audit(limit)})
        if p == "/":
            return self._send(200, {"service": "kioskos", "ok": True})
        self._error(404, "not found")

    def do_HEAD(self):
        p = urlparse(self.path).path
        if p.startswith("/ota/"):
            return self.serve_ota(unquote(p[len("/ota/"):]), head=True)
        self.send_response(405)
        self.end_headers()

    def do_PUT(self):
        m = re.match(r"^/admin/(?:api/)?devices/([^/]+)$", urlparse(self.path).path)
        if not m:
            return self._error(404, "not found")
        admin = self._admin()
        if admin:
            self.update_device(admin, unquote(m.group(1)))

    def do_DELETE(self):
        m = re.match(r"^/admin/(?:api/)?devices/([^/]+)$", urlparse(self.path).path)
        if not m:
            return self._error(404, "not found")
        admin = self._admin()
        if admin:
            self.release_device(admin, unquote(m.group(1)))

    def do_POST(self):
        if urlparse(self.path).path != "/admin/api/releases":
            return self._error(404, "not found")
        admin = self._admin()
        if admin:
            self.create_release(admin)

    def do_PATCH(self):
        m = re.match(r"^/admin/api/releases/([^/]+)$", urlparse(self.path).path)
        if not m:
            return self._error(404, "not found")
        admin = self._admin()
        if admin:
            self.update_release(admin, unquote(m.group(1)))

    # ---- endpoint perangkat ----
    def device_status(self, q):
        serial = q.get("serial", [""])[0]
        nonce = q.get("nonce", [""])[0]
        imei = q.get("imei", [""])[0]
        if not SERIAL_RE.match(serial) or not nonce or len(nonce) > 64:
            return self._error(400, "serial dan nonce wajib")
        with _lock:
            devices = _load(DEVICES_PATH, {})
            dev = devices.get(serial)
            if dev is not None:
                dev["last_seen"] = int(time.time())
                if imei:
                    dev["imei"] = imei[:32]
                _save(DEVICES_PATH, devices)
            dev = dev or {}
        self._send(200, {
            "serial": serial,
            "nonce": nonce,
            "enrolled": bool(dev.get("enrolled", False)),
            "locked": bool(dev.get("locked", False)),
            "message": dev.get("message", ""),
            "contact": dev.get("contact", ""),
            "issued_at": int(time.time()),
        }, signed=True)

    def ota_check(self, q):
        get = lambda k: q.get(k, [""])[0]
        serial, nonce, device = get("serial"), get("nonce"), get("device")
        fingerprint, channel = get("fingerprint"), get("channel") or "stable"
        try:
            build_utc = int(get("build_utc") or 0)
        except ValueError:
            return self._error(400, "build_utc tidak valid")
        if not SERIAL_RE.match(serial) or not nonce or len(nonce) > 64 or not device:
            return self._error(400, "serial, device, dan nonce wajib")
        with _lock:
            releases = _load(RELEASES_PATH, {})
            devices = _load(DEVICES_PATH, {})
            if serial in devices:
                devices[serial].update({"fingerprint": fingerprint[:200], "build_utc": build_utc,
                                        "last_seen": int(time.time())})
                _save(DEVICES_PATH, devices)
        rel = pick_release(releases, serial, device, fingerprint, build_utc, channel)
        update = None
        if rel:
            update = {k: rel[k] for k in ("id", "version", "build_utc", "offset", "size",
                                          "headers", "sha256", "file_size") if k in rel}
            update["incremental"] = bool(rel.get("pre_fingerprint"))
            update["url"] = rel.get("url") or f"{self._base_url()}/ota/{rel['filename']}"
        self._send(200, {"serial": serial, "nonce": nonce, "update": update,
                         "issued_at": int(time.time())}, signed=True)

    def serve_ota(self, name, head):
        if not OTA_FILE_RE.match(name):
            return self._error(404, "not found")
        path = os.path.join(OTA_DIR, name)
        if not os.path.isfile(path):
            return self._error(404, "not found")
        size = os.path.getsize(path)
        start, end, code = 0, size - 1, 200
        rng = self.headers.get("Range")
        if rng:  # update_engine mengunduh payload memakai Range
            m = re.match(r"^bytes=(\d*)-(\d*)$", rng.strip())
            if not m or (not m.group(1) and not m.group(2)):
                return self._error(416, "range tidak valid")
            if m.group(1):
                start = int(m.group(1))
                end = int(m.group(2)) if m.group(2) else size - 1
            else:
                start = max(0, size - int(m.group(2)))
            end = min(end, size - 1)
            if start > end or start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return
            code = 206
        self.send_response(code)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if head:
            return
        with open(path, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                chunk = f.read(min(1 << 20, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def serve_static(self, name):
        if not re.match(r"^[A-Za-z0-9_.-]+$", name):
            return self._error(404, "not found")
        path = os.path.join(STATIC_DIR, name)
        if not os.path.isfile(path):
            return self._error(404, "not found")
        with open(path, "rb") as f:
            body = f.read()
        ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype.endswith("javascript"):
            ctype += "; charset=utf-8"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Security-Policy",
                         "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'; "
                         "base-uri 'none'; form-action 'none'")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()
        self.wfile.write(body)

    # ---- endpoint admin: perangkat ----
    def list_devices(self):
        with _lock:
            self._send(200, _load(DEVICES_PATH, {}))

    def update_device(self, admin, serial):
        if not SERIAL_RE.match(serial):
            return self._error(400, "serial tidak valid")
        try:
            data = self._read_json()
        except ValueError as e:
            return self._error(400, str(e))
        changes = {}
        for k in ("enrolled", "locked"):
            if k in data:
                if not isinstance(data[k], bool):
                    return self._error(400, f"{k} harus true/false")
                changes[k] = data[k]
        for k, limit in (("message", 500), ("contact", 100), ("note", 500)):
            if k in data:
                if not isinstance(data[k], str) or len(data[k]) > limit:
                    return self._error(400, f"{k} harus teks maks. {limit} karakter")
                changes[k] = data[k]
        with _lock:
            devices = _load(DEVICES_PATH, {})
            dev = devices.get(serial, {"created_at": int(time.time())})
            dev.update(changes)
            dev.setdefault("enrolled", True)
            if changes.get("locked"):
                dev["enrolled"] = True
            if not dev["enrolled"]:
                dev["locked"] = False
            dev["updated_at"] = int(time.time())
            dev["updated_by"] = admin
            devices[serial] = dev
            _save(DEVICES_PATH, devices)
        audit(admin, self._ip(), "device.update", serial, changes)
        self._send(200, {serial: dev})

    def release_device(self, admin, serial):
        if not SERIAL_RE.match(serial):
            return self._error(400, "serial tidak valid")
        with _lock:
            devices = _load(DEVICES_PATH, {})
            # Record tetap disimpan dengan enrolled=false agar perangkat menerima status "lepas".
            dev = devices.get(serial, {"created_at": int(time.time())})
            dev.update({"enrolled": False, "locked": False, "updated_at": int(time.time()),
                        "updated_by": admin})
            devices[serial] = dev
            _save(DEVICES_PATH, devices)
        audit(admin, self._ip(), "device.release", serial)
        self._send(200, {serial: dev})

    # ---- endpoint admin: rilis OTA ----
    def list_releases(self):
        with _lock:
            self._send(200, _load(RELEASES_PATH, {}))

    def create_release(self, admin):
        try:
            data = self._read_json()
        except ValueError as e:
            return self._error(400, str(e))
        required = ("id", "version", "device", "build_utc", "filename", "offset", "size",
                    "headers", "sha256")
        missing = [k for k in required if k not in data]
        if missing:
            return self._error(400, "field wajib: " + ", ".join(missing))
        if not RELEASE_ID_RE.match(str(data["id"])) or not OTA_FILE_RE.match(str(data["filename"])):
            return self._error(400, "id/filename tidak valid")
        if not isinstance(data["headers"], list) or not all(isinstance(h, str) for h in data["headers"]):
            return self._error(400, "headers harus daftar teks")
        try:
            for k in ("build_utc", "offset", "size"):
                data[k] = int(data[k])
            data["rollout"] = max(0, min(100, int(data.get("rollout", 100))))
        except (TypeError, ValueError):
            return self._error(400, "build_utc/offset/size/rollout harus angka")
        if not data.get("url") and not os.path.isfile(os.path.join(OTA_DIR, data["filename"])):
            return self._error(400, f"file {data['filename']} belum ada di {OTA_DIR} dan tidak ada 'url'")
        allowed = set(required) | {"url", "rollout", "paused", "channel", "pre_fingerprint",
                                   "post_fingerprint", "file_size", "notes"}
        rel = {k: v for k, v in data.items() if k in allowed}
        rel.setdefault("channel", "stable")
        rel["paused"] = bool(rel.get("paused", False))
        rel["created_at"] = int(time.time())
        rel["created_by"] = admin
        with _lock:
            releases = _load(RELEASES_PATH, {})
            if rel["id"] in releases:
                return self._error(409, "id rilis sudah ada")
            releases[rel["id"]] = rel
            _save(RELEASES_PATH, releases)
        audit(admin, self._ip(), "release.create", rel["id"],
              {k: rel.get(k) for k in ("version", "device", "channel", "rollout")})
        self._send(201, rel)

    def update_release(self, admin, rid):
        try:
            data = self._read_json()
        except ValueError as e:
            return self._error(400, str(e))
        changes = {}
        if "rollout" in data:
            try:
                changes["rollout"] = max(0, min(100, int(data["rollout"])))
            except (TypeError, ValueError):
                return self._error(400, "rollout harus angka 0-100")
        if "paused" in data:
            changes["paused"] = bool(data["paused"])
        if "channel" in data:
            if not isinstance(data["channel"], str) or not re.match(r"^[a-z0-9_-]{1,32}$", data["channel"]):
                return self._error(400, "channel tidak valid")
            changes["channel"] = data["channel"]
        with _lock:
            releases = _load(RELEASES_PATH, {})
            if rid not in releases:
                return self._error(404, "rilis tidak ditemukan")
            releases[rid].update(changes)
            releases[rid]["updated_at"] = int(time.time())
            releases[rid]["updated_by"] = admin
            _save(RELEASES_PATH, releases)
            rel = releases[rid]
        audit(admin, self._ip(), "release.update", rid, changes)
        self._send(200, rel)


def main():
    os.makedirs(OTA_DIR, exist_ok=True)
    if not ADMINS:
        print("PERINGATAN: tidak ada admin (ADMIN_TOKEN / ADMIN_TOKENS_FILE); dashboard tidak bisa dipakai.")
    print(f"KioskOS server di http://{HOST}:{PORT}  (dashboard: /admin/, data: {DATA_DIR})")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
