#!/usr/bin/env bash
# Tahap 1: download source AOSP (~100+ GB, bisa berjam-jam tergantung internet).
# Aman dijalankan ulang: kalau terputus, jalankan lagi untuk melanjutkan.
set -euo pipefail
source "$(dirname "$0")/config.sh"
export PATH="$HOME/.bin:$PATH"
command -v repo >/dev/null || die "'repo' belum terpasang. Jalankan 00-setup-host.sh dulu."

mkdir -p "$AOSP_DIR"
cd "$AOSP_DIR"

if [[ ! -d .repo ]]; then
    log "repo init ($AOSP_BRANCH) di $AOSP_DIR"
    # --partial-clone & --depth mempercepat download dan menghemat disk.
    repo init --partial-clone --no-use-superproject --depth=1 \
        -b "$AOSP_BRANCH" -u https://android.googlesource.com/platform/manifest
else
    log "Source tree sudah ada, lanjut sync"
fi

log "repo sync (-j$JOBS)"
for attempt in 1 2 3; do
    if repo sync -c -j"$JOBS" --no-tags --no-clone-bundle --optimized-fetch --prune \
            --fail-fast; then
        log "Sync selesai. Berikutnya: ./scripts/02-install-kioskos.sh"
        exit 0
    fi
    warn "Sync gagal (percobaan $attempt/3), coba lagi dalam 30 detik..."
    sleep 30
done
die "repo sync gagal 3x. Cek koneksi internet lalu jalankan skrip ini lagi."
