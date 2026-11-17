#!/usr/bin/env bash
# Tahap 20: uji otomatis KioskOS di emulator.
#
# Syarat: emulator hasil build kiosk_emu (userdebug) SUDAH menyala (./scripts/04-run-emulator.sh)
# dan port 8080 di PC kosong (skrip menjalankan server ujinya sendiri).
#
#   ./scripts/20-test-emulator.sh            # semua tes, termasuk factory reset
#   ./scripts/20-test-emulator.sh --no-reset # tanpa tes factory reset (lebih cepat)
#
# Tes factory reset mematikan emulator lalu menyalakannya lagi dengan -wipe-data
# (tanpa jendela). Log emulator: out/kioskos-emulator.log di source AOSP.
set -euo pipefail
source "$(dirname "$0")/config.sh"

DO_RESET=1
[[ "${1:-}" == "--no-reset" ]] && DO_RESET=0
EMU_EXTRA_ARGS="${EMU_EXTRA_ARGS:--no-window}"
PORT=8080
SERVER_DIR="$REPO_ROOT/server"

command -v adb >/dev/null || die "adb tidak ditemukan. Jalankan dari terminal yang sudah 'lunch', atau tambahkan platform-tools ke PATH."
command -v curl >/dev/null || die "curl tidak ditemukan."
[[ -f "$SERVER_DIR/private_key.pem" ]] || die "Kunci server belum ada. Jalankan 02-install-kioskos.sh dulu."

# ---------------------------------------------------------------- utilitas
PASS=0; FAIL=0; SKIP=0
RESULTS=()
pass() { PASS=$((PASS+1)); RESULTS+=("LULUS  $1"); printf '    \033[1;32mLULUS\033[0m %s\n' "$1"; }
fail() { FAIL=$((FAIL+1)); RESULTS+=("GAGAL  $1"); printf '    \033[1;31mGAGAL\033[0m %s\n' "$1"; }
skip() { SKIP=$((SKIP+1)); RESULTS+=("LEWATI $1"); printf '    \033[1;33mLEWATI\033[0m %s\n' "$1"; }

# Output perintah di perangkat. Tidak pernah menghentikan skrip (set -e) bila perintah gagal.
ash() { adb shell "$@" 2>/dev/null | tr -d '\r' || true; }
# contains <teks> <pola>: hindari "cmd | grep -q" karena SIGPIPE + pipefail = gagal palsu.
contains() { grep -qE -- "$2" <<<"$1"; }

# wait_for <detik> <perintah...>: ulangi perintah sampai sukses atau waktu habis.
wait_for() {
    local timeout="$1"; shift
    local end=$((SECONDS + timeout))
    until "$@"; do
        (( SECONDS >= end )) && return 1
        sleep 2
    done
}

wait_boot() {
    timeout 300 adb wait-for-device || return 1
    wait_for 300 bash -c '[[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" == 1 ]]'
}

# ---------------------------------------------------------------- server uji
TOKEN="test-$RANDOM$RANDOM"
TMPDIR_TEST="$(mktemp -d)"
SERVER_PID=""

start_server() {
    if curl -s -o /dev/null "http://127.0.0.1:$PORT/"; then
        die "Port $PORT sudah dipakai. Matikan server lain dulu (skrip ini menjalankan servernya sendiri)."
    fi
    ADMIN_TOKEN="$TOKEN" PORT="$PORT" DB_PATH="$TMPDIR_TEST/devices.json" \
        python3 "$SERVER_DIR/server.py" >"$TMPDIR_TEST/server.log" 2>&1 &
    SERVER_PID=$!
    wait_for 15 curl -s -o /dev/null "http://127.0.0.1:$PORT/" \
        || die "Server uji gagal jalan. Log: $TMPDIR_TEST/server.log"
}

stop_server() {
    if [[ -n "$SERVER_PID" ]]; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
        SERVER_PID=""
    fi
}

cleanup() {
    stop_server
    [[ -s "$TMPDIR_TEST/server.log" ]] && echo "    (log server uji: $TMPDIR_TEST/server.log)"
    true
}
trap cleanup EXIT

admin() { # admin <METHOD> <json>
    curl -sf -X "$1" -H "Authorization: Bearer $TOKEN" ${2:+-d "$2"} \
        "http://127.0.0.1:$PORT/admin/devices/$SERIAL" >/dev/null
}

# ---------------------------------------------------------------- pengecekan perangkat
PKG=id.kioskos.lock

force_sync() {
    # Job 1 = sinkron berkala KioskLock; -f = jalankan sekarang walau syaratnya belum terpenuhi.
    ash cmd jobscheduler run -f "$PKG" 1 >/dev/null || true
}

top_activity() {
    ash dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity|ResumedActivity:' || true
}
lock_activity_on_top() { contains "$(top_activity)" "$PKG/\.LockActivity"; }
lock_activity_gone() { ! lock_activity_on_top; }
wifi_activity_on_top() { contains "$(top_activity)" "$PKG/\.WifiActivity"; }
settings_on_top() { contains "$(top_activity)" "com\.android\.settings/"; }

# tap_id <resource-id>: ketuk elemen UI berdasarkan resource-id (lewat uiautomator dump).
tap_id() {
    local xml bounds x1 y1 x2 y2
    xml="$(ash 'uiautomator dump /sdcard/kiosk_ui.xml >/dev/null && cat /sdcard/kiosk_ui.xml')"
    bounds="$(grep -oE "resource-id=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" <<<"$xml" \
        | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -n1 || true)"
    [[ -n "$bounds" ]] || return 1
    read -r x1 y1 x2 y2 <<<"$(tr -c '0-9' ' ' <<<"$bounds")"
    ash input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}

lock_task_state() { # LOCKED / NONE / PINNED / "" (tidak terbaca)
    ash dumpsys activity | grep -m1 -oE 'mLockTaskModeState=[A-Z]+' | cut -d= -f2 || true
}

is_device_owner() {
    contains "$(ash dumpsys device_policy | grep -A4 -i 'Device Owner' || true)" "$PKG"
}
has_restriction() { contains "$(ash dumpsys device_policy)" "$1"; }

# ---------------------------------------------------------------- mulai
log "Menunggu emulator"
wait_boot || die "Emulator tidak terdeteksi/selesai boot. Jalankan ./scripts/04-run-emulator.sh dulu."
[[ "$(ash getprop ro.build.type)" != user ]] \
    || die "Build 'user' mematikan ADB saat terdaftar; uji otomatis butuh build userdebug."

SERIAL="$(ash getprop ro.serialno)"
FRP="$(ash getprop ro.frp.pst)"
echo "    Serial : $SERIAL"
echo "    FRP    : ${FRP:-(tidak ada)}"
echo "    Build  : $(ash getprop ro.build.fingerprint)"

log "Menjalankan server uji di port $PORT"
start_server

log "Tes 1: instalasi"
if contains "$(ash pm list packages)" "^package:$PKG\$"; then pass "KioskLock terpasang"; else fail "KioskLock terpasang"; fi
if contains "$(ash pm path "$PKG")" '/priv-app/'; then pass "KioskLock sebagai priv-app"; else fail "KioskLock sebagai priv-app"; fi
if is_device_owner; then pass "KioskLock adalah Device Owner"
else fail "KioskLock adalah Device Owner (lihat logcat -s KioskLock.Provision)"; fi
if [[ -n "$FRP" ]]; then pass "Partisi FRP tersedia ($FRP)"
else skip "Partisi FRP tidak ada di image emulator ini (lihat docs/TESTING.md)"; fi

log "Tes 2: daftarkan perangkat (tidak dikunci)"
admin PUT '{"enrolled":true,"locked":false}'
force_sync
if wait_for 60 has_restriction no_factory_reset; then pass "Factory reset dari Settings diblokir"
else fail "Factory reset dari Settings diblokir"; fi
if has_restriction no_safe_boot; then pass "Safe mode diblokir"; else fail "Safe mode diblokir"; fi
if lock_activity_gone; then pass "Layar kunci TIDAK muncul saat status tidak dikunci"
else fail "Layar kunci TIDAK muncul saat status tidak dikunci"; fi

log "Tes 3: kunci perangkat"
admin PUT '{"locked":true,"message":"Uji otomatis: perangkat dikunci","contact":"0800-000-000"}'
force_sync
if wait_for 60 lock_activity_on_top; then pass "Layar kunci muncul"; else fail "Layar kunci muncul"; fi
state="$(lock_task_state)"
case "$state" in
    LOCKED) pass "Lock Task Mode aktif" ;;
    "")     skip "Status Lock Task Mode tidak terbaca dari dumpsys di versi ini" ;;
    *)      fail "Lock Task Mode aktif (status: $state)" ;;
esac

log "Tes 4: coba keluar dari layar kunci"
ash input keyevent KEYCODE_HOME;     sleep 2
if lock_activity_on_top; then pass "Tombol Home tidak bisa keluar"; else fail "Tombol Home tidak bisa keluar"; fi
ash input keyevent KEYCODE_BACK;     sleep 2
if lock_activity_on_top; then pass "Tombol Back tidak bisa keluar"; else fail "Tombol Back tidak bisa keluar"; fi
ash input keyevent KEYCODE_APP_SWITCH; sleep 2
if lock_activity_on_top; then pass "Tombol Recents tidak bisa keluar"; else fail "Tombol Recents tidak bisa keluar"; fi
ash am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null; sleep 2
if lock_activity_on_top; then pass "Membuka HOME diarahkan ke layar kunci"; else fail "Membuka HOME diarahkan ke layar kunci"; fi
# Coba buka aplikasi lain yang TIDAK ada di daftar izin lock task.
other="$(ash cmd package query-activities --brief -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER | grep '/' \
    | grep -vE "^($PKG|com.android.phone)/" | head -n1 || true)"
if [[ -z "$other" ]]; then
    skip "Tidak ada aplikasi launcher lain untuk dicoba"
else
    ash am start -n "$other" >/dev/null || true; sleep 2
    if lock_activity_on_top; then pass "Aplikasi lain ($other) tidak bisa dibuka"
    else fail "Aplikasi lain ($other) tidak bisa dibuka"; fi
fi
# Settings sengaja TIDAK diizinkan saat terkunci (celah lama lewat panel Wi-Fi).
ash am start -a android.settings.SETTINGS >/dev/null || true; sleep 2
if ! settings_on_top && lock_activity_on_top; then pass "Settings tidak bisa dibuka"
else fail "Settings tidak bisa dibuka"; ash input keyevent KEYCODE_BACK; fi
ash am start -a android.settings.panel.action.WIFI >/dev/null || true; sleep 2
if ! settings_on_top && lock_activity_on_top; then pass "Panel Wi-Fi bawaan (Settings) tidak bisa dibuka"
else fail "Panel Wi-Fi bawaan (Settings) tidak bisa dibuka"; ash input keyevent KEYCODE_BACK; fi

log "Tes 4b: layar Wi-Fi milik KioskLock"
if ! tap_id "$PKG:id/btn_wifi"; then
    skip "Tombol Wi-Fi tidak ditemukan lewat uiautomator"
else
    sleep 2
    if wait_for 10 wifi_activity_on_top; then pass "Tombol Wi-Fi membuka pemilih Wi-Fi KioskLock"
    else fail "Tombol Wi-Fi membuka pemilih Wi-Fi KioskLock"; fi
    ash input keyevent KEYCODE_HOME; sleep 2
    if wifi_activity_on_top || lock_activity_on_top; then pass "Home dari layar Wi-Fi tidak bisa keluar"
    else fail "Home dari layar Wi-Fi tidak bisa keluar"; fi
    ash input keyevent KEYCODE_BACK; sleep 2
    if wait_for 10 lock_activity_on_top; then pass "Back dari layar Wi-Fi kembali ke layar kunci"
    else fail "Back dari layar Wi-Fi kembali ke layar kunci"; fi
fi

log "Tes 5: reboot saat terkunci (tanpa server)"
stop_server
adb reboot
sleep 5
wait_boot || die "Emulator tidak kembali setelah reboot."
if wait_for 90 lock_activity_on_top; then pass "Tetap terkunci setelah reboot walau server mati"
else fail "Tetap terkunci setelah reboot walau server mati"; fi
start_server

log "Tes 6: buka kunci"
admin PUT '{"locked":false}'
force_sync
if wait_for 60 lock_activity_gone; then pass "Layar kunci hilang"; else fail "Layar kunci hilang"; fi
state="$(lock_task_state)"
case "$state" in
    NONE) pass "Lock Task Mode mati" ;;
    "")   skip "Status Lock Task Mode tidak terbaca dari dumpsys di versi ini" ;;
    *)    fail "Lock Task Mode mati (status: $state)" ;;
esac

restart_emulator_wiped() {
    log "    Matikan emulator lalu nyalakan dengan -wipe-data (factory reset)"
    adb emu kill >/dev/null 2>&1 || true
    wait_for 60 bash -c '! adb get-state >/dev/null 2>&1' || true
    # shellcheck disable=SC2086
    nohup "$REPO_ROOT/scripts/04-run-emulator.sh" --wipe $EMU_EXTRA_ARGS \
        >"$AOSP_DIR/out/kioskos-emulator.log" 2>&1 &
    sleep 10
    wait_boot
}

if (( DO_RESET )); then
    log "Tes 7: factory reset saat terkunci, server ONLINE (seperti Knox Guard)"
    admin PUT '{"locked":true,"message":"Uji reset online"}'
    force_sync
    wait_for 60 lock_activity_on_top || warn "Layar kunci belum muncul sebelum reset"
    restart_emulator_wiped || die "Emulator tidak menyala lagi. Log: $AOSP_DIR/out/kioskos-emulator.log"
    if wait_for 60 is_device_owner; then pass "Device Owner terpasang lagi otomatis setelah reset"
    else fail "Device Owner terpasang lagi otomatis setelah reset"; fi
    if wait_for 180 lock_activity_on_top; then pass "Terkunci lagi setelah reset (dari server)"
    else fail "Terkunci lagi setelah reset (dari server)"; fi

    log "Tes 8: factory reset saat terkunci, server OFFLINE (status dari partisi FRP)"
    if [[ -z "$FRP" ]]; then
        skip "Butuh partisi FRP; image emulator ini tidak punya. Uji di HP sungguhan."
    else
        stop_server
        restart_emulator_wiped || die "Emulator tidak menyala lagi. Log: $AOSP_DIR/out/kioskos-emulator.log"
        if wait_for 120 lock_activity_on_top; then pass "Terkunci lagi setelah reset TANPA internet/server"
        else fail "Terkunci lagi setelah reset TANPA internet/server"; fi
        start_server
    fi

    log "Kembalikan ke kondisi tidak terkunci"
    admin PUT '{"locked":false}'
    force_sync
    wait_for 60 lock_activity_gone || warn "Layar kunci belum hilang; tekan 'Cek status' di emulator."
else
    skip "Tes factory reset (dijalankan dengan --no-reset)"
fi

log "Tes 9: lepas perangkat (lunas)"
admin DELETE
force_sync
no_restrictions() { ! has_restriction no_factory_reset; }
if wait_for 60 no_restrictions; then
    pass "Semua restriksi dicabut"
else
    fail "Semua restriksi dicabut"
fi

# ---------------------------------------------------------------- ringkasan
echo
log "Ringkasan: $PASS lulus, $FAIL gagal, $SKIP dilewati"
printf '    %s\n' "${RESULTS[@]}"
if (( FAIL )); then
    echo
    echo "    Cek log aplikasi:  adb logcat -d -s 'KioskLock.*'"
    exit 1
fi
