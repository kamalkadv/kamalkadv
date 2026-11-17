#!/usr/bin/env python3
"""Buat metadata rilis (JSON) dari paket OTA A/B untuk server KioskOS.

  ota_metadata.py <ota.zip> <versi> [--channel stable] [--notes "..."]

update_engine tidak mengunduh seluruh zip: ia mengunduh payload.bin langsung dari dalam zip
memakai HTTP Range, jadi butuh offset & ukuran payload.bin serta isi payload_properties.txt.
payload.bin selalu disimpan tanpa kompresi (ZIP_STORED) oleh ota_from_target_files.
"""
import argparse
import hashlib
import json
import os
import re
import struct
import sys
import zipfile

LOCAL_HEADER = struct.Struct("<4s5H3L2H")  # 30 byte local file header


def entry_data_offset(zip_path, info):
    """Offset byte data sebuah entry. Dihitung dari local header (panjang 'extra'-nya bisa
    berbeda dengan yang di central directory)."""
    with open(zip_path, "rb") as f:
        f.seek(info.header_offset)
        h = LOCAL_HEADER.unpack(f.read(LOCAL_HEADER.size))
        if h[0] != b"PK\x03\x04":
            raise ValueError("local header zip tidak valid")
        name_len, extra_len = h[9], h[10]
        return info.header_offset + LOCAL_HEADER.size + name_len + extra_len


def parse_kv(text):
    out = {}
    for line in text.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def build(zip_path, version, channel="stable", notes=""):
    with zipfile.ZipFile(zip_path) as z:
        names = set(z.namelist())
        for required in ("payload.bin", "payload_properties.txt", "META-INF/com/android/metadata"):
            if required not in names:
                raise ValueError(f"{required} tidak ada: bukan paket OTA A/B?")
        payload = z.getinfo("payload.bin")
        if payload.compress_type != zipfile.ZIP_STORED:
            raise ValueError("payload.bin terkompresi; update_engine butuh ZIP_STORED")
        headers = [l.strip() for l in z.read("payload_properties.txt").decode().splitlines() if l.strip()]
        meta = parse_kv(z.read("META-INF/com/android/metadata").decode())
    if meta.get("ota-type") != "AB":
        raise ValueError(f"ota-type={meta.get('ota-type')}; KioskUpdater hanya mendukung AB")
    device = meta.get("pre-device", "").split("|")[0]
    if not device or "post-timestamp" not in meta:
        raise ValueError("metadata OTA tidak lengkap (pre-device / post-timestamp)")

    h = hashlib.sha256()
    with open(zip_path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)

    pre_fp = meta.get("pre-build", "").split("|")[0]
    kind = "incr" if pre_fp else "full"
    rid = f"{device}-{version}-{kind}"
    if pre_fp:
        rid += "-from-" + meta.get("pre-build-incremental", hashlib.sha1(pre_fp.encode()).hexdigest()[:8])
    rid = re.sub(r"[^A-Za-z0-9._-]", "_", rid)[:128]

    rel = {
        "id": rid,
        "version": version,
        "device": device,
        "channel": channel,
        "build_utc": int(meta["post-timestamp"]),
        "post_fingerprint": meta.get("post-build", "").split("|")[0],
        "filename": os.path.basename(zip_path),
        "offset": entry_data_offset(zip_path, payload),
        "size": payload.file_size,
        "headers": headers,
        "sha256": h.hexdigest(),
        "file_size": os.path.getsize(zip_path),
    }
    if pre_fp:
        rel["pre_fingerprint"] = pre_fp
    if notes:
        rel["notes"] = notes
    return rel


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ota_zip")
    ap.add_argument("version")
    ap.add_argument("--channel", default="stable")
    ap.add_argument("--notes", default="")
    a = ap.parse_args()
    try:
        print(json.dumps(build(a.ota_zip, a.version, a.channel, a.notes), indent=2))
    except (ValueError, zipfile.BadZipFile, KeyError) as e:
        sys.exit(f"ota_metadata: {e}")


if __name__ == "__main__":
    main()
