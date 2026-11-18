#!/usr/bin/env bash
# Coba KioskLock edisi Device Owner (tanpa ROM custom) di emulator Android biasa.
#
# Butuh: JDK 17+, Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT, atau Android CLI),
# adb di PATH, dan SATU emulator yang sudah menyala dari image "Google APIs" (BUKAN
# "Google Play"), yang baru dibuat atau di-wipe dan belum ada akun Google-nya.
#
#   ./scripts/40-dpc-try.sh            build + pasang + jadikan Device Owner + jalankan server
#   ./scripts/40-dpc-try.sh --no-server   tanpa menjalankan server (sudah jalan sendiri)
#
# Setelah selesai, buka dashboard di http://127.0.0.1:8080/admin/ dan kunci perangkatnya.
set -euo pipefail
source "$(dirname "$0")/config.sh"

RUN_SERVER=1
[[ "${1:-}" == "--no-server" ]] && RUN_SERVER=0
DPC="$REPO_ROOT/android-dpc"
PKG=id.kioskos.lock
ADMIN="$PKG/.KioskAdminReceiver"
PORT="${PORT:-8080}"
# Dari dalam emulator, 10.0.2.2 = PC ini. HTTP polos hanya diizinkan untuk alamat itu dan
# hanya di build non-"user" (image Google APIs). HP sungguhan butuh server HTTPS.
DPC_SERVER_URL="${DPC_SERVER_URL:-http://10.0.2.2:$PORT}"

command -v adb >/dev/null || die "adb tidak ditemukan. Pasang Android SDK platform-tools (atau Android CLI) lalu tambahkan ke PATH."
command -v java >/dev/null || die "Java tidak ditemukan. Pasang JDK 17+."

# ---------------------------------------------------------------- SDK
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then
    # Tebak dari lokasi adb: <sdk>/platform-tools/adb
    adb_real="$(readlink -f "$(command -v adb)")"
    [[ "$(basename "$(dirname "$adb_real")")" == platform-tools ]] && SDK="$(dirname "$(dirname "$adb_real")")"
fi
[[ -n "$SDK" && -d "$SDK" ]] || die "Lokasi Android SDK tidak diketahui. Set ANDROID_HOME=/path/ke/sdk"
echo "sdk.dir=$SDK" > "$DPC/local.properties"
log "Android SDK: $SDK"

# ---------------------------------------------------------------- emulator
log "Cek perangkat"
mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
(( ${#devices[@]} == 1 )) || die "Harus tepat 1 perangkat/emulator terhubung (sekarang ${#devices[@]}). Lihat: adb devices"
build_type="$(adb shell getprop ro.build.type | tr -d '\r')"
sdk_int="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
echo "    ${devices[0]}  (Android API $sdk_int, build $build_type)"
(( sdk_int >= 30 )) || die "Butuh Android 11 (API 30) atau lebih baru."
if [[ "$build_type" == user && "$DPC_SERVER_URL" == http://* ]]; then
    warn "Build 'user' (image Google Play / HP sungguhan) menolak server HTTP."
    warn "Pakai emulator image 'Google APIs', atau set DPC_SERVER_URL=https://... ke server HTTPS."
fi

# ---------------------------------------------------------------- konfigurasi server di APK
log "Kunci server uji"
if [[ ! -f "$REPO_ROOT/server/private_key.pem" ]]; then
    python3 -c 'import cryptography' 2>/dev/null \
        || python3 -m pip install --user -q -r "$REPO_ROOT/server/requirements.txt"
    python3 "$REPO_ROOT/server/gen_keys.py" >/dev/null
fi
PUBKEY="$(openssl pkey -in "$REPO_ROOT/server/private_key.pem" -pubout -outform DER | base64 -w0)"
mkdir -p "$DPC/app/src/debug/res/values"
cat > "$DPC/app/src/debug/res/values/server.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<!-- Dibuat otomatis oleh scripts/40-dpc-try.sh - tidak di-commit. -->
<resources>
    <string name="config_server_url" translatable="false">$DPC_SERVER_URL</string>
    <string name="config_server_public_key" translatable="false">$PUBKEY</string>
</resources>
XML
echo "    Server di APK: $DPC_SERVER_URL"

# ---------------------------------------------------------------- build & pasang
log "Build APK (pertama kali butuh beberapa menit untuk mengunduh Gradle & plugin Android)"
(cd "$DPC" && ./gradlew --console=plain :app:assembleDebug)
APK="$DPC/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || die "APK tidak ditemukan: $APK"

log "Pasang ke emulator"
adb install -r "$APK" >/dev/null

log "Jadikan Device Owner"
owners="$(adb shell dumpsys device_policy 2>/dev/null | tr -d '\r' | grep -A4 -i 'Device Owner' || true)"
if grep -q "$PKG" <<<"$owners"; then
    echo "    Sudah Device Owner"
else
    out="$(adb shell dpm set-device-owner "$ADMIN" 2>&1 | tr -d '\r' || true)"
    echo "    $out"
    if ! grep -qi "success" <<<"$out"; then
        cat >&2 <<MSG

Gagal menjadikan Device Owner. Penyebab umum:
  * Sudah ada akun (mis. akun Google) di emulator  -> wipe emulator / buat AVD baru tanpa login
  * Sudah ada Device Owner lain                    -> wipe emulator
  * Image "Google Play"                            -> pakai image "Google APIs"
MSG
        exit 1
    fi
fi
# set-device-owner memicu KioskAdminReceiver.onEnabled: izin, lokasi, kebijakan, jadwal sinkron.

SERIAL="$(adb shell getprop ro.serialno | tr -d '\r')"

# ---------------------------------------------------------------- server
if (( RUN_SERVER )); then
    if curl -s -o /dev/null "http://127.0.0.1:$PORT/"; then
        warn "Port $PORT sudah dipakai; diasumsikan server KioskOS sudah berjalan."
    else
        TOKEN="${ADMIN_TOKEN:-coba-$RANDOM$RANDOM}"
        log "Jalankan server (log: server/data/server.log)"
        mkdir -p "$REPO_ROOT/server/data"
        (cd "$REPO_ROOT/server" && ADMIN_TOKEN="$TOKEN" PORT="$PORT" \
            nohup python3 server.py >"$REPO_ROOT/server/data/server.log" 2>&1 &)
        for _ in $(seq 1 30); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 0.5; done
        curl -s -o /dev/null -X PUT -H "Authorization: Bearer $TOKEN" \
            -d '{"enrolled":true,"locked":false,"note":"Emulator uji (edisi Device Owner)"}' \
            "http://127.0.0.1:$PORT/admin/api/devices/$SERIAL"
        echo "    Token admin dashboard: $TOKEN"
    fi
fi

cat <<MSG

$(printf '\033[1;32m')Siap.$(printf '\033[0m') Serial emulator: $SERIAL
  1. Buka dashboard: http://127.0.0.1:$PORT/admin/  (token di atas)
  2. Klik "Kunci" pada $SERIAL, isi pesan, centang aplikasi bila perlu.
  3. Di emulator: tunggu sinkron, atau paksa sekarang:
       adb shell cmd jobscheduler run -f $PKG 1
  4. Log aplikasi:  adb logcat -s 'KioskLock.*'
  5. Uji otomatis (menjalankan server ujinya sendiri, jadi matikan server di atas dulu):
       pkill -f 'python3 server.py'; EDITION=dpc ./scripts/20-test-emulator.sh

Catatan edisi Device Owner: factory reset MENGHAPUS aplikasi & status kunci (tidak ada
partisi FRP). Di produksi, HP didaftarkan ulang lewat QR code / zero-touch (docs/DPC.md).
MSG
