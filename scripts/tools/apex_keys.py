#!/usr/bin/env python3
"""Siapkan kunci per-APEX dan cetak argumen untuk sign_target_files_apks.

  apex_keys.py <target_files.zip> <folder kunci apex> <subject sertifikat>

Modul APEX (mis. com.android.art) punya dua kunci: kunci payload (AVB, .pem) dan kunci
container (APK, .x509.pem + .pk8). Di AOSP keduanya test-key publik. Skrip ini membuat
pasangan kunci SENDIRI untuk tiap APEX yang belum punya (tidak pernah menimpa), lalu
mencetak argumen --extra_apks / --extra_apex_payload_key, satu per baris.

APEX berstatus PRESIGNED (prebuilt yang sudah ditandatangani, mis. modul Mainline Google)
dilewati: tidak bisa dan tidak perlu ditandatangani ulang.
"""
import os
import re
import subprocess
import sys
import zipfile


def parse_apexkeys(text):
    entries = []
    for line in text.splitlines():
        fields = dict(re.findall(r'(\w+)="([^"]*)"', line))
        if fields.get("name"):
            entries.append(fields)
    return entries


def needs_signing(e):
    return "PRESIGNED" not in (e.get("private_key"), e.get("container_certificate"),
                               e.get("container_private_key"))


def make_keys(prefix, subject):
    """prefix.pem (payload, RSA 4096) + prefix.x509.pem/.pk8 (container)."""
    if not os.path.exists(prefix + ".pem"):
        subprocess.run(["openssl", "genrsa", "-f4", "-out", prefix + ".pem", "4096"],
                       check=True, capture_output=True)
    if not (os.path.exists(prefix + ".x509.pem") and os.path.exists(prefix + ".pk8")):
        tmp = prefix + ".container.tmp"
        subprocess.run(["openssl", "genrsa", "-f4", "-out", tmp, "4096"], check=True, capture_output=True)
        subprocess.run(["openssl", "req", "-new", "-x509", "-sha256", "-key", tmp, "-days", "10000",
                        "-subj", subject, "-out", prefix + ".x509.pem"], check=True, capture_output=True)
        subprocess.run(["openssl", "pkcs8", "-in", tmp, "-topk8", "-outform", "DER", "-nocrypt",
                        "-out", prefix + ".pk8"], check=True, capture_output=True)
        os.remove(tmp)
    for ext in (".pem", ".x509.pem", ".pk8"):
        os.chmod(prefix + ext, 0o600)


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    tf, key_dir, subject = sys.argv[1:]
    with zipfile.ZipFile(tf) as z:
        try:
            text = z.read("META/apexkeys.txt").decode()
        except KeyError:
            return  # build tanpa APEX
    os.makedirs(key_dir, mode=0o700, exist_ok=True)
    skipped = 0
    for e in parse_apexkeys(text):
        name = e["name"]
        if not needs_signing(e):
            skipped += 1
            continue
        base = re.sub(r"\.c?apex$", "", name)
        if not re.match(r"^[A-Za-z0-9._-]+$", base):
            sys.exit(f"nama APEX tidak wajar: {name}")
        prefix = os.path.join(key_dir, base)
        make_keys(prefix, subject)
        print("--extra_apks")
        print(f"{name}={prefix}")
        print("--extra_apex_payload_key")
        print(f"{name}={prefix}.pem")
    if skipped:
        print(f"apex_keys: {skipped} APEX PRESIGNED dilewati", file=sys.stderr)


if __name__ == "__main__":
    main()
