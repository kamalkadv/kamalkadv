#!/usr/bin/env bash
# Tahap 30: buat RILIS PRODUKSI yang ditandatangani + paket OTA.
#
#   VERSION=1.2.0 ./scripts/30-release.sh
#   VERSION=1.3.0 PREV_TARGET_FILES=<dist>/1.2.0/signed-target_files.zip ./scripts/30-release.sh
#
# Alur standar Google:
#   1. build target-files dengan test-keys (SIGN_AT_BUILD=0)
#   2. tanda tangani ulang semuanya dengan kunci Anda: APK, APEX, kunci OTA, dan AVB
#      (sign_target_files_apks)
#   3. buat paket OTA full (dan inkremental jika PREV_TARGET_FILES diisi)
#   4. tulis metadata JSON untuk server (scripts/31-publish-release.sh)
#
# Hasil: $DIST_DIR/$VERSION/
#   signed-target_files.zip   SIMPAN: dasar OTA inkremental rilis berikutnya & image pabrik
#   <device>-<versi>-full.zip (+ -incr.zip) dan *.json
set -euo pipefail
source "$(dirname "$0")/config.sh"

VERSION="${VERSION:-}"
[[ "$VERSION" =~ ^[0-9A-Za-z._-]+$ ]] || die "Set VERSION, mis.: VERSION=1.2.0 $0"
VARIANT="${RELEASE_VARIANT:-user}"
DIST_DIR="${DIST_DIR:-$AOSP_DIR/out/kioskos-dist}"
CHANNEL="${CHANNEL:-stable}"
PREV_TARGET_FILES="${PREV_TARGET_FILES:-}"
OUT_V="$DIST_DIR/$VERSION"

cd "$AOSP_DIR"
[[ -f "$KEYS_DIR/releasekey.pk8" && -f "$KEYS_DIR/avb.pem" ]] \
    || die "Kunci belum ada di $KEYS_DIR. Jalankan 10-gen-keys.sh dulu."
[[ -f vendor/kioskos/products/$PRODUCT.mk ]] || die "Produk $PRODUCT belum dibuat (02-install-kioskos.sh)."
if grep -q '^PRODUCT_DEFAULT_DEV_CERTIFICATE' "vendor/kioskos/products/$PRODUCT.mk"; then
    die "Produk ditandatangani saat build. Untuk rilis jalankan: SIGN_AT_BUILD=0 SERVER_URL=https://... ./scripts/02-install-kioskos.sh"
fi
grep -q 'http://' vendor/kioskos/emu_overlay/vendor/kioskos/apps/KioskLock/res/values/config.xml 2>/dev/null \
    && warn "URL server di ROM masih http://; build 'user' akan MENOLAK server ini. Set SERVER_URL=https://..."
[[ -z "$PREV_TARGET_FILES" || -f "$PREV_TARGET_FILES" ]] || die "PREV_TARGET_FILES tidak ditemukan: $PREV_TARGET_FILES"
[[ ! -e "$OUT_V" ]] || die "$OUT_V sudah ada. Pakai VERSION lain atau hapus folder itu."

detect_release
TARGET="$PRODUCT-$RELEASE-$VARIANT"
set +u
source build/envsetup.sh >/dev/null
log "lunch $TARGET"
lunch "$TARGET"
set -u

log "Build target-files + otatools (-j$JOBS)"
m -j"$JOBS" target-files-package otatools
HOST_BIN="$ANDROID_HOST_OUT/bin"
TF="$(ls -t "$ANDROID_PRODUCT_OUT"/obj/PACKAGING/target_files_intermediates/*-target_files*.zip 2>/dev/null | head -n1 || true)"
[[ -f "$TF" ]] || die "target-files tidak ditemukan setelah build."
echo "    $TF"

mkdir -p "$OUT_V"

log "Siapkan kunci APEX"
mapfile -t APEX_ARGS < <(python3 "$REPO_ROOT/scripts/tools/apex_keys.py" "$TF" "$KEYS_DIR/apex" "$KEY_SUBJECT")
echo "    $(( ${#APEX_ARGS[@]} / 4 )) APEX ditandatangani ulang"

log "Argumen kunci"
# -d memetakan testkey->releasekey, platform, shared, media, networkstack. Kunci lain eksplisit.
KEY_ARGS=(-d "$KEYS_DIR")
for k in sdk_sandbox bluetooth nfc; do
    [[ -f "$KEYS_DIR/$k.pk8" ]] && KEY_ARGS+=(-k "build/make/target/product/security/$k=$KEYS_DIR/$k")
done
# AVB: tanda tangani vbmeta utama dan semua partisi berantai yang ada di build ini,
# hanya dengan opsi yang dikenali sign_target_files_apks versi ini.
SIGN_HELP="$("$HOST_BIN/sign_target_files_apks" --help 2>&1 || true)"
AVB_ARGS=()
while IFS= read -r part; do
    if grep -q -- "--avb_${part}_key" <<<"$SIGN_HELP"; then
        AVB_ARGS+=("--avb_${part}_key" "$KEYS_DIR/avb.pem" "--avb_${part}_algorithm" SHA256_RSA4096)
    fi
done < <(unzip -p "$TF" META/misc_info.txt | sed -nE 's/^avb_([a-z0-9_]+)_key_path=.*/\1/p' | sort -u)
grep -q -- "--avb_vbmeta_key" <<<"${AVB_ARGS[*]:-}" \
    || AVB_ARGS+=(--avb_vbmeta_key "$KEYS_DIR/avb.pem" --avb_vbmeta_algorithm SHA256_RSA4096)
echo "    AVB: $(( ${#AVB_ARGS[@]} / 4 )) partisi/vbmeta"

log "Tanda tangani ulang target-files"
# -o: ganti kunci OTA (otacerts & kunci payload update_engine) dengan releasekey Anda,
#     sehingga HP hanya menerima OTA yang Anda tanda tangani.
"$HOST_BIN/sign_target_files_apks" -o "${KEY_ARGS[@]}" "${APEX_ARGS[@]}" "${AVB_ARGS[@]}" \
    "$TF" "$OUT_V/signed-target_files.zip"

DEVICE="$(unzip -p "$OUT_V/signed-target_files.zip" SYSTEM/build.prop 2>/dev/null \
    | sed -n 's/^ro.product.system.device=//p' | head -n1)"
DEVICE="${DEVICE:-$(basename "$ANDROID_PRODUCT_OUT")}"

log "Paket OTA full"
FULL="$OUT_V/$DEVICE-$VERSION-full.zip"
"$HOST_BIN/ota_from_target_files" -k "$KEYS_DIR/releasekey" "$OUT_V/signed-target_files.zip" "$FULL"
python3 "$REPO_ROOT/scripts/tools/ota_metadata.py" "$FULL" "$VERSION" --channel "$CHANNEL" > "${FULL%.zip}.json"

if [[ -n "$PREV_TARGET_FILES" ]]; then
    log "Paket OTA inkremental (dari $(basename "$(dirname "$PREV_TARGET_FILES")"))"
    INCR="$OUT_V/$DEVICE-$VERSION-incr.zip"
    "$HOST_BIN/ota_from_target_files" -k "$KEYS_DIR/releasekey" -i "$PREV_TARGET_FILES" \
        "$OUT_V/signed-target_files.zip" "$INCR"
    python3 "$REPO_ROOT/scripts/tools/ota_metadata.py" "$INCR" "$VERSION" --channel "$CHANNEL" > "${INCR%.zip}.json"
fi

log "Selesai: $OUT_V"
ls -lh "$OUT_V" | sed 's/^/    /'
cat <<MSG

Berikutnya:
  * Simpan signed-target_files.zip (dasar OTA inkremental untuk rilis berikutnya).
  * Uji paket OTA di 1-2 HP dulu, lalu publikasikan bertahap:
      ROLLOUT=10 ./scripts/31-publish-release.sh $OUT_V/*.json
MSG
