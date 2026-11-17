#!/usr/bin/env bash
# Tahap 3: build image emulator KioskOS.
# Build pertama 2-6 jam (tergantung CPU); build berikutnya jauh lebih cepat.
set -euo pipefail
source "$(dirname "$0")/config.sh"

cd "$AOSP_DIR"
[[ -f vendor/kioskos/products/$PRODUCT.mk ]] || die "Produk belum dibuat. Jalankan 02-install-kioskos.sh."

# ccache mempercepat build ulang.
if command -v ccache >/dev/null; then
    export USE_CCACHE=1 CCACHE_EXEC="$(command -v ccache)"
    ccache -M 50G >/dev/null
fi

detect_release
TARGET="$PRODUCT-$RELEASE-$VARIANT"

set +u  # envsetup.sh tidak kompatibel dengan 'set -u'
source build/envsetup.sh
log "lunch $TARGET"
lunch "$TARGET"
set -u
mkdir -p out && echo "$TARGET" > out/.kioskos_last_target

log "Build (-j$JOBS)"
m -j"$JOBS"

log "Build selesai: $ANDROID_PRODUCT_OUT"
log "Berikutnya: jalankan server (lihat docs/BUILD.md), lalu ./scripts/04-run-emulator.sh"
