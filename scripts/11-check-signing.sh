#!/usr/bin/env bash
# Tahap 11: pastikan hasil build benar-benar ditandatangani dengan kunci Anda,
# bukan test-keys AOSP. Jalankan setelah 03-build-emulator.sh.
set -euo pipefail
source "$(dirname "$0")/config.sh"

cd "$AOSP_DIR"
[[ -f out/.kioskos_last_target ]] || die "Belum ada hasil build. Jalankan 03-build-emulator.sh dulu."
[[ -f "$KEYS_DIR/platform.x509.pem" ]] || die "Kunci belum ada. Jalankan 10-gen-keys.sh dulu."
TARGET="$(cat out/.kioskos_last_target)"
set +u
source build/envsetup.sh >/dev/null
lunch "$TARGET" >/dev/null
set -u

APKSIGNER="$(command -v apksigner || true)"
if [[ -z "$APKSIGNER" ]]; then
    log "Build apksigner"
    m apksigner >/dev/null
    APKSIGNER="$ANDROID_HOST_OUT/bin/apksigner"
fi

fp() { openssl x509 -in "$1" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f'; }
apk_fp() { "$APKSIGNER" verify --print-certs "$1" | awk -F': ' '/Signer #1 certificate SHA-256 digest/ {print $2; exit}'; }

MINE="$(fp "$KEYS_DIR/platform.x509.pem")"
TEST="$(fp build/make/target/product/security/platform.x509.pem)"
OUT="$ANDROID_PRODUCT_OUT"

fail=0
check() {
    local label="$1" apk
    apk="$(find "$OUT" -path "$OUT/obj" -prune -o -name "$2" -print -quit)"
    if [[ -z "$apk" ]]; then warn "$label: $2 tidak ditemukan"; fail=1; return; fi
    local got; got="$(apk_fp "$apk")"
    if [[ "$got" == "$MINE" ]]; then
        echo "    OK    $label ditandatangani kunci platform Anda"
    elif [[ "$got" == "$TEST" ]]; then
        echo "    GAGAL $label masih memakai test-key AOSP"; fail=1
    else
        echo "    ?     $label ditandatangani kunci lain: $got"; fail=1
    fi
}

log "Cek tanda tangan ($TARGET)"
check "KioskLock"   KioskLock.apk
check "framework"   framework-res.apk
check "Settings"    Settings.apk

if (( fail )); then
    die "Ada yang belum memakai kunci Anda. Jalankan 02-install-kioskos.sh lalu build ulang."
fi
log "Semua OK"
