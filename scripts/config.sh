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

# Root repo ini.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }
