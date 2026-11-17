#!/usr/bin/env bash
# Tahap 4: jalankan emulator dari hasil build.
#   ./scripts/04-run-emulator.sh          -> boot biasa
#   ./scripts/04-run-emulator.sh --wipe   -> simulasi FACTORY RESET (hapus userdata)
#   Argumen lain diteruskan ke emulator, mis.: ./scripts/04-run-emulator.sh --wipe -no-window
set -euo pipefail
source "$(dirname "$0")/config.sh"

cd "$AOSP_DIR"
[[ -f out/.kioskos_last_target ]] || die "Belum ada hasil build. Jalankan 03-build-emulator.sh dulu."
TARGET="$(cat out/.kioskos_last_target)"
set +u
source build/envsetup.sh >/dev/null
lunch "$TARGET" >/dev/null
set -u
[[ -d "${ANDROID_PRODUCT_OUT:-}" ]] || die "Folder hasil build $TARGET tidak ditemukan."

args=(-no-snapshot)
if [[ "${1:-}" == "--wipe" ]]; then
    log "Factory reset: userdata dihapus, partisi lain tetap"
    args+=(-wipe-data)
    shift
fi
args+=("$@")
log "emulator ${args[*]}"
exec emulator "${args[@]}"
