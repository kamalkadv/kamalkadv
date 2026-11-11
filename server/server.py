#!/usr/bin/env python3
"""
Server contoh untuk KioskLock (mirip konsep Knox Guard: status perangkat disimpan di server
berdasarkan serial/IMEI, jadi tetap berlaku walau HP di-factory reset).

HANYA CONTOH untuk pengembangan. Untuk produksi: jalankan di belakang reverse proxy HTTPS,
pakai database sungguhan, audit log, dan autentikasi admin yang kuat.

Endpoint perangkat:
  GET  /api/v1/devices/status?serial=..&imei=..&nonce=..
       -> body JSON + header X-Signature (ECDSA P-256 SHA-256, Base64)

Endpoint admin (header "Authorization: Bearer <ADMIN_TOKEN>"):
  GET  /admin/devices
  PUT  /admin/devices/<serial>   body: {"enrolled":true,"locked":true,"message":"..","contact":".."}
  DELETE /admin/devices/<serial> (lepas perangkat: lunas)

Menjalankan:
  pip install -r requirements.txt
  python3 gen_keys.py            # sekali, buat private_key.pem + public key Base64
  ADMIN_TOKEN=rahasia python3 server.py
"""
import base64
import hmac
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

HERE = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.environ.get("DB_PATH", os.path.join(HERE, "devices.json"))
KEY_PATH = os.environ.get("KEY_PATH", os.path.join(HERE, "private_key.pem"))
ADMIN_TOKEN = os.environ.get("ADMIN_TOKEN", "")
HOST = os.environ.get("HOST", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8080"))

_lock = threading.Lock()


def load_db():
    if not os.path.exists(DB_PATH):
        return {}
    with open(DB_PATH) as f:
        return json.load(f)


def save_db(db):
    tmp = DB_PATH + ".tmp"
    with open(tmp, "w") as f:
        json.dump(db, f, indent=2)
    os.replace(tmp, DB_PATH)


with open(KEY_PATH, "rb") as f:
    PRIVATE_KEY = serialization.load_pem_private_key(f.read(), password=None)


def sign(body: bytes) -> str:
    sig = PRIVATE_KEY.sign(body, ec.ECDSA(hashes.SHA256()))  # DER, cocok dengan SHA256withECDSA
    return base64.b64encode(sig).decode()


class Handler(BaseHTTPRequestHandler):
    server_version = "KioskLockServer/1.0"

    def _send(self, code, obj, signed=False):
        body = json.dumps(obj, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        if signed:
            self.send_header("X-Signature", sign(body))
        self.end_headers()
        self.wfile.write(body)

    def _is_admin(self):
        auth = self.headers.get("Authorization", "")
        return bool(ADMIN_TOKEN) and hmac.compare_digest(auth, "Bearer " + ADMIN_TOKEN)

    def _read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 64 * 1024:
            return {}
        return json.loads(self.rfile.read(length) or b"{}")

    # ---- perangkat ----
    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/api/v1/devices/status":
            q = parse_qs(url.query)
            serial = q.get("serial", [""])[0]
            nonce = q.get("nonce", [""])[0]
            imei = q.get("imei", [""])[0]
            if not serial or not nonce or len(nonce) > 64:
                return self._send(400, {"error": "serial dan nonce wajib"})
            with _lock:
                db = load_db()
                dev = db.get(serial, {})
                if dev:
                    dev["last_seen"] = int(time.time())
                    if imei:
                        dev["imei"] = imei
                    save_db(db)
            resp = {
                "serial": serial,
                "nonce": nonce,
                "enrolled": bool(dev.get("enrolled", False)),
                "locked": bool(dev.get("locked", False)),
                "message": dev.get("message", ""),
                "contact": dev.get("contact", ""),
                "issued_at": int(time.time()),
            }
            return self._send(200, resp, signed=True)

        if url.path == "/admin/devices":
            if not self._is_admin():
                return self._send(401, {"error": "unauthorized"})
            with _lock:
                return self._send(200, load_db())

        self._send(404, {"error": "not found"})

    # ---- admin ----
    def do_PUT(self):
        if not self.path.startswith("/admin/devices/"):
            return self._send(404, {"error": "not found"})
        if not self._is_admin():
            return self._send(401, {"error": "unauthorized"})
        serial = self.path.rsplit("/", 1)[-1]
        data = self._read_json()
        with _lock:
            db = load_db()
            dev = db.get(serial, {})
            for k in ("enrolled", "locked", "message", "contact"):
                if k in data:
                    dev[k] = data[k]
            dev.setdefault("enrolled", True)
            dev["updated_at"] = int(time.time())
            db[serial] = dev
            save_db(db)
        self._send(200, {serial: dev})

    def do_DELETE(self):
        if not self.path.startswith("/admin/devices/"):
            return self._send(404, {"error": "not found"})
        if not self._is_admin():
            return self._send(401, {"error": "unauthorized"})
        serial = self.path.rsplit("/", 1)[-1]
        with _lock:
            db = load_db()
            # Tetap simpan record dengan enrolled=false agar perangkat menerima status "lepas".
            dev = db.get(serial, {})
            dev.update({"enrolled": False, "locked": False, "updated_at": int(time.time())})
            db[serial] = dev
            save_db(db)
        self._send(200, {serial: dev})


if __name__ == "__main__":
    if not ADMIN_TOKEN:
        print("PERINGATAN: ADMIN_TOKEN kosong, endpoint admin dinonaktifkan.")
    print(f"KioskLock server di http://{HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
