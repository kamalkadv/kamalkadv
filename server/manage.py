#!/usr/bin/env python3
"""Kelola admin dashboard KioskOS.

  python3 manage.py add-admin <nama>     buat token baru (ditampilkan SEKALI), simpan hash-nya
  python3 manage.py list-admins
  python3 manage.py remove-admin <nama>

File token: ADMIN_TOKENS_FILE (default: data/admins.txt). Jalankan server dengan
  ADMIN_TOKENS_FILE=data/admins.txt python3 server.py
Server membaca file ini saat start; restart server setelah menambah/menghapus admin.
"""
import hashlib
import os
import re
import secrets
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PATH = os.environ.get("ADMIN_TOKENS_FILE", os.path.join(HERE, "data", "admins.txt"))


def read():
    if not os.path.exists(PATH):
        return []
    with open(PATH) as f:
        return [l.rstrip("\n") for l in f if l.strip() and not l.startswith("#")]


def write(lines):
    os.makedirs(os.path.dirname(PATH), exist_ok=True)
    fd = os.open(PATH + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write("# nama:sha256(token) - dibuat oleh manage.py\n")
        f.writelines(l + "\n" for l in lines)
    os.replace(PATH + ".tmp", PATH)


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    cmd = argv[1]
    lines = read()
    names = [l.split(":", 1)[0] for l in lines]
    if cmd == "add-admin" and len(argv) == 3:
        name = argv[2]
        if not re.match(r"^[A-Za-z0-9._@-]{1,64}$", name):
            sys.exit("Nama hanya boleh huruf, angka, . _ @ -")
        if name in names:
            sys.exit(f"Admin '{name}' sudah ada. Hapus dulu untuk membuat token baru.")
        token = secrets.token_urlsafe(32)
        lines.append(f"{name}:{hashlib.sha256(token.encode()).hexdigest()}")
        write(lines)
        print(f"Admin '{name}' ditambahkan ke {PATH}")
        print("Token (simpan sekarang, TIDAK bisa ditampilkan lagi):")
        print(token)
        print("Restart server agar admin baru berlaku.")
    elif cmd == "list-admins":
        print("\n".join(names) or "(belum ada admin)")
    elif cmd == "remove-admin" and len(argv) == 3:
        if argv[2] not in names:
            sys.exit(f"Admin '{argv[2]}' tidak ada.")
        write([l for l in lines if l.split(":", 1)[0] != argv[2]])
        print(f"Admin '{argv[2]}' dihapus. Restart server agar berlaku.")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
