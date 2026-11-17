#!/usr/bin/env bash
# Tahap 31: publikasikan rilis OTA ke server KioskOS.
#
#   KIOSKOS_ADMIN_TOKEN=... ./scripts/31-publish-release.sh <metadata.json> [...]
#
# Untuk setiap JSON (hasil 30-release.sh): unggah file zip-nya lalu daftarkan ke server.
#
# Variabel:
#   PUBLISH_URL          URL server untuk API admin (default http://127.0.0.1:8080)
#   KIOSKOS_ADMIN_TOKEN  token admin (manage.py add-admin)
#   OTA_UPLOAD_DEST      tujuan zip: folder lokal (mis. server/data/ota) atau
#                        user@host:/srv/kioskos/data/ota (pakai scp). Kosong = tidak diunggah.
#   OTA_BASE_URL         jika file disajikan CDN: URL folder publiknya (https://cdn.../ota).
#                        Kosong = disajikan server KioskOS sendiri di /ota/<file>.
#   ROLLOUT              persen perangkat yang menerima (default 10, mulai kecil!)
#   PAUSED               1 = daftarkan dalam keadaan dijeda
set -euo pipefail
source "$(dirname "$0")/config.sh"

PUBLISH_URL="${PUBLISH_URL:-http://127.0.0.1:8080}"
TOKEN="${KIOSKOS_ADMIN_TOKEN:-}"
ROLLOUT="${ROLLOUT:-10}"
PAUSED="${PAUSED:-0}"
OTA_UPLOAD_DEST="${OTA_UPLOAD_DEST:-}"
OTA_BASE_URL="${OTA_BASE_URL:-}"

(( $# > 0 )) || die "Pemakaian: $0 <metadata.json> [...]"
[[ -n "$TOKEN" ]] || die "Set KIOSKOS_ADMIN_TOKEN (token admin dari server/manage.py add-admin)."
[[ "$ROLLOUT" =~ ^[0-9]+$ ]] && (( ROLLOUT <= 100 )) || die "ROLLOUT harus 0-100."

for meta in "$@"; do
    [[ -f "$meta" ]] || die "Tidak ada: $meta"
    zip="$(dirname "$meta")/$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["filename"])' "$meta")"
    [[ -f "$zip" ]] || die "File OTA untuk $meta tidak ditemukan: $zip"

    # Pastikan zip yang diunggah memang yang dijelaskan metadata.
    want="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sha256"])' "$meta")"
    got="$(sha256sum "$zip" | cut -d' ' -f1)"
    [[ "$want" == "$got" ]] || die "sha256 $zip tidak cocok dengan $meta"

    log "$(basename "$zip")"
    if [[ -n "$OTA_UPLOAD_DEST" ]]; then
        if [[ "$OTA_UPLOAD_DEST" == *:* ]]; then
            scp -q "$zip" "$OTA_UPLOAD_DEST/"
        else
            mkdir -p "$OTA_UPLOAD_DEST"
            cp "$zip" "$OTA_UPLOAD_DEST/"
        fi
        echo "    diunggah ke $OTA_UPLOAD_DEST"
    fi

    body="$(python3 - "$meta" "$ROLLOUT" "$PAUSED" "$OTA_BASE_URL" <<'PY'
import json, sys
meta, rollout, paused, base = json.load(open(sys.argv[1])), int(sys.argv[2]), sys.argv[3] == "1", sys.argv[4]
meta["rollout"] = rollout
meta["paused"] = paused
if base:
    meta["url"] = base.rstrip("/") + "/" + meta["filename"]
print(json.dumps(meta))
PY
)"
    resp="$(curl -sS -w '\n%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' -d "$body" "$PUBLISH_URL/admin/api/releases")"
    code="${resp##*$'\n'}"
    if [[ "$code" == 201 ]]; then
        echo "    terdaftar: rollout ${ROLLOUT}%$([[ "$PAUSED" == 1 ]] && echo ', DIJEDA')"
    else
        die "Server menolak ($code): ${resp%$'\n'*}"
    fi
done

log "Selesai. Pantau & naikkan rollout di dashboard: $PUBLISH_URL/admin/#releases"
