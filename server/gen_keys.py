#!/usr/bin/env python3
"""Buat pasangan kunci ECDSA P-256 untuk menandatangani respons server.

- private_key.pem : simpan RAHASIA di server.
- Public key Base64 yang dicetak: tempel ke
  vendor/kioskos/apps/KioskLock/res/values/config.xml -> config_server_public_key
"""
import base64
import os
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "private_key.pem")
if os.path.exists(out):
    sys.exit(f"{out} sudah ada; hapus manual jika memang ingin membuat ulang.")

key = ec.generate_private_key(ec.SECP256R1())
fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "wb") as f:
    f.write(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ))

spki = key.public_key().public_bytes(
    serialization.Encoding.DER,
    serialization.PublicFormat.SubjectPublicKeyInfo,
)
print("Private key disimpan di", out)
print("config_server_public_key =")
print(base64.b64encode(spki).decode())
