# Konfigurasi bersama untuk semua skrip. Bisa di-override lewat environment, contoh:
#   AOSP_DIR=/data/aosp JOBS=8 ./scripts/03-build-emulator.sh

# Lokasi source tree AOSP (butuh ~400 GB kosong).
AOSP_DIR="${AOSP_DIR:-$HOME/aosp}"

# Branch manifest AOSP. "android-latest-release" = rilis stabil terbaru dari Google.
# Bisa diganti tag tertentu, mis. android-15.0.0_r36.
AOSP_BRANCH="${AOSP_BRANCH:-android-latest-release}"

# Jumlah job paralel untuk sync & build. Default: jumlah core CPU.
JOBS="${JOBS:-$(nproc)}"

# Nama produk emulator KioskOS dan varian build.
PRODUCT="${PRODUCT:-kiosk_emu}"
VARIANT="${VARIANT:-userdebug}"

# Release config untuk lunch (Android 14+). Kosong = deteksi otomatis.
RELEASE="${RELEASE:-}"

# Lokasi kunci tanda tangan (release keys + AVB). Harus di dalam source tree AOSP agar
# bisa dipakai build, tapi TIDAK di dalam repo git ini. Wajib di-backup offline.
KEYS_DIR="${KEYS_DIR:-$AOSP_DIR/vendor/kioskos-keys}"

# Subject sertifikat. Jangan isi email/nama pribadi; sertifikat bisa dibaca siapa pun dari APK.
KEY_SUBJECT="${KEY_SUBJECT:-/C=ID/ST=Jakarta/L=Jakarta/O=KioskOS/OU=Android/CN=KioskOS}"

# URL server KioskOS yang ditanam di ROM (KioskLock & KioskUpdater).
# Default: server di PC dilihat dari emulator. Untuk build "user"/produksi WAJIB https://.
SERVER_URL="${SERVER_URL:-http://10.0.2.2:8080}"

# Identitas di layar kunci (opsional). BRAND_LOGO: file PNG persegi, mis. 192x192 px.
BRAND_NAME="${BRAND_NAME:-}"
BRAND_LOGO="${BRAND_LOGO:-}"

# 1 = tanda tangani saat build dengan KEYS_DIR (cepat, untuk pengujian).
# 0 = build dengan test-keys, tanda tangani ulang saat rilis (30-release.sh).
SIGN_AT_BUILD="${SIGN_AT_BUILD:-1}"

# Root repo ini.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# detect_release: isi $RELEASE (nama release config untuk lunch, Android 14+) jika kosong.
# Harus dipanggil dari root source AOSP.
detect_release() {
    [[ -n "$RELEASE" ]] && return
    local available candidate
    available="$(find build/release -path '*release_configs*' \( -name '*.textproto' -o -name '*.scl' \) \
        -printf '%f\n' 2>/dev/null | sed -E 's/\.(textproto|scl)$//' | sort -u)"
    for candidate in aosp_current trunk_staging; do
        if grep -qx "$candidate" <<<"$available"; then RELEASE="$candidate"; return; fi
    done
    RELEASE="$(head -n1 <<<"$available")"
    [[ -n "$RELEASE" ]] || die "Tidak bisa mendeteksi release config. Set manual: RELEASE=<nama>"
}

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }
