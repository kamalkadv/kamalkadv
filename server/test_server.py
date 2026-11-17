#!/usr/bin/env python3
"""Tes server KioskOS. Jalankan: python3 -m unittest server/test_server.py -v"""
import base64
import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN = "token-uji-123"
TOKEN2 = "token-operator-456"


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.data = os.path.join(cls.tmp, "data")
        key = ec.generate_private_key(ec.SECP256R1())
        cls.pub = key.public_key()
        cls.key_path = os.path.join(cls.tmp, "key.pem")
        with open(cls.key_path, "wb") as f:
            f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                      serialization.NoEncryption()))
        tokens = os.path.join(cls.tmp, "admins.txt")
        with open(tokens, "w") as f:
            f.write("# komentar\noperator:%s\n" % hashlib.sha256(TOKEN2.encode()).hexdigest())
        cls.port = free_port()
        env = dict(os.environ, DATA_DIR=cls.data, KEY_PATH=cls.key_path, ADMIN_TOKEN=TOKEN,
                   ADMIN_TOKENS_FILE=tokens, PORT=str(cls.port), HOST="127.0.0.1")
        env.pop("DB_PATH", None)
        cls.proc = subprocess.Popen([sys.executable, os.path.join(HERE, "server.py")], env=env,
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(100):
            try:
                urllib.request.urlopen(cls.url("/"), timeout=1)
                break
            except OSError:
                time.sleep(0.1)
        else:
            raise RuntimeError("server tidak jalan")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(5)
        shutil.rmtree(cls.tmp)

    @classmethod
    def url(cls, path):
        return f"http://127.0.0.1:{cls.port}{path}"

    def req(self, method, path, body=None, token=TOKEN, headers=None):
        h = dict(headers or {})
        if token:
            h["Authorization"] = "Bearer " + token
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        r = urllib.request.Request(self.url(path), data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(r, timeout=5) as resp:
                return resp.status, dict(resp.headers), resp.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def signed_get(self, path):
        code, headers, body = self.req("GET", path, token=None)
        self.assertEqual(code, 200, body)
        sig = base64.b64decode(headers["X-Signature"])
        self.pub.verify(sig, body, ec.ECDSA(hashes.SHA256()))  # error jika tanda tangan salah
        return json.loads(body)

    def put_ota_file(self, name, content):
        os.makedirs(os.path.join(self.data, "ota"), exist_ok=True)
        with open(os.path.join(self.data, "ota", name), "wb") as f:
            f.write(content)

    def release(self, rid, **kw):
        body = dict(id=rid, version=kw.pop("version", rid), device="emu64x", build_utc=1000,
                    filename=f"{rid}.zip", offset=100, size=200, headers=["FILE_HASH=abc"],
                    sha256="00" * 32)
        body.update(kw)
        if "url" not in body:
            self.put_ota_file(body["filename"], b"x" * 400)
        return self.req("POST", "/admin/api/releases", body)

    def ota_check(self, serial="DEV1", device="emu64x", build_utc=500, fp="fp/old", channel="stable"):
        return self.signed_get(f"/api/v1/ota/check?serial={serial}&device={device}"
                               f"&fingerprint={fp}&build_utc={build_utc}&channel={channel}&nonce=n1")

    # ---------------------------------------------------------- status perangkat
    def test_status_unknown_device_is_signed_and_not_enrolled(self):
        d = self.signed_get("/api/v1/devices/status?serial=UNKNOWN1&nonce=abc")
        self.assertEqual((d["serial"], d["nonce"], d["enrolled"], d["locked"]), ("UNKNOWN1", "abc", False, False))

    def test_status_rejects_bad_input(self):
        self.assertEqual(self.req("GET", "/api/v1/devices/status?serial=../x&nonce=a", token=None)[0], 400)
        self.assertEqual(self.req("GET", "/api/v1/devices/status?serial=A", token=None)[0], 400)

    def test_lock_unlock_release_flow_and_audit(self):
        self.assertEqual(self.req("PUT", "/admin/api/devices/FLOW1",
                                  {"locked": True, "message": "Bayar", "contact": "0812"})[0], 200)
        d = self.signed_get("/api/v1/devices/status?serial=FLOW1&nonce=n&imei=3550")
        self.assertTrue(d["enrolled"] and d["locked"])
        self.assertEqual((d["message"], d["contact"]), ("Bayar", "0812"))

        self.req("PUT", "/admin/api/devices/FLOW1", {"locked": False})
        self.assertFalse(self.signed_get("/api/v1/devices/status?serial=FLOW1&nonce=n")["locked"])

        self.assertEqual(self.req("DELETE", "/admin/api/devices/FLOW1")[0], 200)
        d = self.signed_get("/api/v1/devices/status?serial=FLOW1&nonce=n")
        self.assertEqual((d["enrolled"], d["locked"]), (False, False))

        devs = json.loads(self.req("GET", "/admin/api/devices")[2])
        self.assertEqual(devs["FLOW1"]["imei"], "3550")
        self.assertIn("last_seen", devs["FLOW1"])

        entries = json.loads(self.req("GET", "/admin/api/audit")[2])["entries"]
        flow = [e for e in entries if e["target"] == "FLOW1"]
        self.assertEqual([e["action"] for e in flow], ["device.release", "device.update", "device.update"])
        self.assertTrue(all(e["admin"] == "admin" for e in flow))

    def test_legacy_admin_paths_still_work(self):
        self.assertEqual(self.req("PUT", "/admin/devices/LEG1", {"locked": True})[0], 200)
        self.assertTrue(self.signed_get("/api/v1/devices/status?serial=LEG1&nonce=n")["locked"])
        self.assertEqual(self.req("DELETE", "/admin/devices/LEG1")[0], 200)

    def test_admin_requires_valid_token(self):
        for method, path, body in [("GET", "/admin/api/devices", None),
                                   ("PUT", "/admin/api/devices/X1", {"locked": True}),
                                   ("DELETE", "/admin/api/devices/X1", None),
                                   ("GET", "/admin/api/releases", None),
                                   ("POST", "/admin/api/releases", {}),
                                   ("GET", "/admin/api/audit", None)]:
            self.assertEqual(self.req(method, path, body, token=None)[0], 401, path)
            self.assertEqual(self.req(method, path, body, token="salah")[0], 401, path)
        self.assertFalse(self.signed_get("/api/v1/devices/status?serial=X1&nonce=n")["locked"])

    def test_second_admin_from_tokens_file_is_audited_by_name(self):
        self.assertEqual(json.loads(self.req("GET", "/admin/api/whoami", token=TOKEN2)[2])["admin"], "operator")
        self.req("PUT", "/admin/api/devices/OPS1", {"locked": True}, token=TOKEN2)
        entries = json.loads(self.req("GET", "/admin/api/audit")[2])["entries"]
        self.assertEqual([e["admin"] for e in entries if e["target"] == "OPS1"], ["operator"])

    def test_admin_input_validation(self):
        self.assertEqual(self.req("PUT", "/admin/api/devices/bad%2Fserial", {"locked": True})[0], 400)
        self.assertEqual(self.req("PUT", "/admin/api/devices/V1", {"locked": "ya"})[0], 400)
        self.assertEqual(self.req("PUT", "/admin/api/devices/V1", {"message": "x" * 501})[0], 400)
        code, _, body = self.req("PUT", "/admin/api/devices/V1", {"locked": True, "admin": "hack", "x": 1})
        self.assertEqual(code, 200)
        self.assertNotIn("x", json.loads(body)["V1"])

    def test_allowed_apps(self):
        apps = ["com.whatsapp", "id.dana", "com.whatsapp"]
        self.assertEqual(self.req("PUT", "/admin/api/devices/APP1",
                                  {"locked": True, "allowed_apps": apps})[0], 200)
        d = self.signed_get("/api/v1/devices/status?serial=APP1&nonce=n")
        self.assertEqual(d["allowed_apps"], ["com.whatsapp", "id.dana"])  # duplikat dibuang
        # Perangkat tanpa daftar -> daftar kosong.
        self.assertEqual(self.signed_get("/api/v1/devices/status?serial=NOAPPS&nonce=n")["allowed_apps"], [])
        # Paket berbahaya & format salah ditolak, data lama tidak berubah.
        for bad in (["com.android.settings"], ["com.android.vending"], ["id.kioskos.lock"],
                    ["bukan paket"], ["../x"], "com.whatsapp", [1], ["a.b"] * 13):
            self.assertEqual(self.req("PUT", "/admin/api/devices/APP1", {"allowed_apps": bad})[0], 400, bad)
        self.assertEqual(self.signed_get("/api/v1/devices/status?serial=APP1&nonce=n")["allowed_apps"],
                         ["com.whatsapp", "id.dana"])
        self.assertEqual(self.req("PUT", "/admin/api/devices/APP1", {"allowed_apps": []})[0], 200)
        self.assertEqual(self.signed_get("/api/v1/devices/status?serial=APP1&nonce=n")["allowed_apps"], [])

    def test_app_presets(self):
        self.assertEqual(self.req("GET", "/admin/api/app-presets", token=None)[0], 401)
        apps = json.loads(self.req("GET", "/admin/api/app-presets")[2])["apps"]
        self.assertIn("com.whatsapp", [a["package"] for a in apps])

    # ---------------------------------------------------------- OTA
    def test_release_requires_file_or_url(self):
        body = dict(id="nofile", version="1", device="emu64x", build_utc=1, filename="nofile.zip",
                    offset=0, size=1, headers=[], sha256="0")
        self.assertEqual(self.req("POST", "/admin/api/releases", body)[0], 400)
        self.assertEqual(self.req("POST", "/admin/api/releases", dict(body, url="https://cdn/x.zip"))[0], 201)
        self.assertEqual(self.req("POST", "/admin/api/releases", dict(body, url="https://cdn/x.zip"))[0], 409)

    def test_release_rejects_bad_fields(self):
        self.assertEqual(self.release("../evil")[0], 400)
        self.assertEqual(self.req("POST", "/admin/api/releases", {"id": "a"})[0], 400)
        self.assertEqual(self.release("badh", headers="FILE_HASH=1")[0], 400)

    def test_ota_selection(self):
        # Device lain dan channel lain tidak boleh tercampur.
        self.release("sel-a-full", device="selA", build_utc=2000, version="2.0")
        self.release("sel-a-old", device="selA", build_utc=1500, version="1.5")
        self.release("sel-a-beta", device="selA", build_utc=3000, channel="beta", version="3.0b")
        self.release("sel-a-incr", device="selA", build_utc=2000, version="2.0",
                     pre_fingerprint="fp/1.5")

        u = self.ota_check(device="selA", build_utc=1000, fp="fp/1.0")["update"]
        self.assertEqual(u["id"], "sel-a-full")  # inkremental tidak cocok fingerprint -> full
        self.assertFalse(u["incremental"])
        self.assertTrue(u["url"].endswith("/ota/sel-a-full.zip"))
        self.assertEqual((u["offset"], u["size"], u["headers"]), (100, 200, ["FILE_HASH=abc"]))

        u = self.ota_check(device="selA", build_utc=1500, fp="fp/1.5")["update"]
        self.assertEqual(u["id"], "sel-a-incr")  # inkremental diutamakan bila cocok
        self.assertTrue(u["incremental"])

        self.assertIsNone(self.ota_check(device="selA", build_utc=2000)["update"])  # sudah terbaru
        self.assertIsNone(self.ota_check(device="other", build_utc=0)["update"])
        self.assertEqual(self.ota_check(device="selA", build_utc=2000, channel="beta")["update"]["id"], "sel-a-beta")

    def test_ota_pause_and_rollout(self):
        self.release("ro-1", device="roDev", build_utc=5000)
        self.assertEqual(self.ota_check(device="roDev")["update"]["id"], "ro-1")

        self.assertEqual(self.req("PATCH", "/admin/api/releases/ro-1", {"paused": True})[0], 200)
        self.assertIsNone(self.ota_check(device="roDev")["update"])
        self.req("PATCH", "/admin/api/releases/ro-1", {"paused": False, "rollout": 0})
        self.assertIsNone(self.ota_check(device="roDev")["update"])

        self.req("PATCH", "/admin/api/releases/ro-1", {"rollout": 30})
        got = sum(1 for i in range(200) if self.ota_check(serial=f"S{i}", device="roDev")["update"])
        self.assertTrue(40 <= got <= 80, got)  # ~30% dari 200 perangkat
        # Perangkat yang sama selalu masuk/keluar rollout secara konsisten.
        first = self.ota_check(serial="S7", device="roDev")["update"]
        self.assertEqual(first, self.ota_check(serial="S7", device="roDev")["update"])
        self.assertEqual(self.req("PATCH", "/admin/api/releases/nope", {"paused": True})[0], 404)

    def test_ota_check_records_build_for_known_device(self):
        self.req("PUT", "/admin/api/devices/BLD1", {"locked": False})
        self.ota_check(serial="BLD1", device="emu64x", build_utc=777, fp="fp/abc")
        dev = json.loads(self.req("GET", "/admin/api/devices")[2])["BLD1"]
        self.assertEqual((dev["build_utc"], dev["fingerprint"]), (777, "fp/abc"))

    def test_ota_file_download_with_range(self):
        self.put_ota_file("range.zip", bytes(range(256)) * 4)
        code, h, body = self.req("GET", "/ota/range.zip", token=None)
        self.assertEqual((code, len(body), h["Accept-Ranges"]), (200, 1024, "bytes"))
        code, h, body = self.req("GET", "/ota/range.zip", token=None, headers={"Range": "bytes=10-19"})
        self.assertEqual((code, body, h["Content-Range"]), (206, bytes(range(10, 20)), "bytes 10-19/1024"))
        code, _, body = self.req("GET", "/ota/range.zip", token=None, headers={"Range": "bytes=1020-"})
        self.assertEqual((code, body), (206, bytes(range(252, 256))))
        code, _, body = self.req("GET", "/ota/range.zip", token=None, headers={"Range": "bytes=-2"})
        self.assertEqual((code, body), (206, bytes([254, 255])))
        self.assertEqual(self.req("GET", "/ota/range.zip", token=None, headers={"Range": "bytes=5000-"})[0], 416)
        self.assertEqual(self.req("HEAD", "/ota/range.zip", token=None)[0], 200)

    def test_ota_file_path_traversal_blocked(self):
        for p in ("/ota/..%2Fkey.pem", "/ota/../key.pem", "/ota/devices.json", "/ota/%2e%2e%2fx.zip"):
            self.assertEqual(self.req("GET", p, token=None)[0], 404, p)

    # ---------------------------------------------------------- dashboard
    def test_dashboard_served_with_security_headers(self):
        code, h, body = self.req("GET", "/admin/", token=None)
        self.assertEqual(code, 200)
        self.assertIn(b"KioskOS Admin", body)
        self.assertIn("default-src 'self'", h["Content-Security-Policy"])
        self.assertEqual(h["X-Frame-Options"], "DENY")
        for f in ("admin.js", "admin.css"):
            self.assertEqual(self.req("GET", "/admin/static/" + f, token=None)[0], 200)
        self.assertEqual(self.req("GET", "/admin/static/..%2Fserver.py", token=None)[0], 404)


if __name__ == "__main__":
    unittest.main()
