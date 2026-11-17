#!/usr/bin/env bash
# Tahap 2: pasang vendor/kioskos ke source tree AOSP dan buat produk emulator "kiosk_emu".
# Jalankan ulang setiap kali Anda mengubah kode di repo ini.
set -euo pipefail
source "$(dirname "$0")/config.sh"

command -v rsync >/dev/null || die "rsync belum terpasang. Jalankan 00-setup-host.sh (atau: sudo apt-get install rsync)."
[[ -d "$AOSP_DIR/build/make" ]] || die "Source AOSP belum ada di $AOSP_DIR. Jalankan 01-sync-aosp.sh."
DEST="$AOSP_DIR/vendor/kioskos"

log "Salin vendor/kioskos -> $DEST"
mkdir -p "$DEST"
# Disalin (bukan symlink) karena Soong tidak selalu mengikuti symlink direktori.
# Folder hasil generate (products/, emu_overlay/) dikecualikan dari --delete.
rsync -a --delete --exclude products/ --exclude emu_overlay/ \
    "$REPO_ROOT/vendor/kioskos/" "$DEST/"

log "Cari makefile produk emulator sdk_phone64_x86_64"
cd "$AOSP_DIR"
EMU_MK="$(grep -rlE '^PRODUCT_NAME[[:space:]]*:?=[[:space:]]*sdk_phone64_x86_64[[:space:]]*$' \
    device/generic/goldfish --include='*.mk' 2>/dev/null | head -n1 || true)"
[[ -n "$EMU_MK" ]] || die "Tidak menemukan definisi sdk_phone64_x86_64 di device/generic/goldfish."
echo "    $EMU_MK"

log "Kunci server untuk pengujian"
KEY_DIR="$REPO_ROOT/server"
PUBKEY_FILE="$KEY_DIR/public_key.b64"
if [[ ! -f "$KEY_DIR/private_key.pem" ]]; then
    python3 -c 'import cryptography' 2>/dev/null \
        || python3 -m pip install --user -q -r "$KEY_DIR/requirements.txt"
    python3 "$KEY_DIR/gen_keys.py" | tail -n1 > "$PUBKEY_FILE"
fi
if [[ ! -s "$PUBKEY_FILE" ]]; then
    # private_key.pem dibuat manual sebelumnya: turunkan public key-nya.
    openssl pkey -in "$KEY_DIR/private_key.pem" -pubout -outform DER | base64 -w0 > "$PUBKEY_FILE"
fi
PUBKEY="$(tr -d '\n' < "$PUBKEY_FILE")"

log "Kunci tanda tangan OS"
SIGNING_MK=""
if [[ "$SIGN_AT_BUILD" == 0 ]]; then
    # Alur rilis produksi: build dengan test-keys, lalu ditandatangani ulang oleh
    # 30-release.sh (sign_target_files_apks). Kunci tidak perlu ada di mesin build.
    echo "    SIGN_AT_BUILD=0: build memakai test-keys, ditandatangani ulang saat rilis"
elif [[ -f "$KEYS_DIR/releasekey.pk8" ]]; then
    KEYS_REL="$(realpath --relative-to="$AOSP_DIR" "$KEYS_DIR")"
    [[ "$KEYS_REL" != ..* ]] \
        || die "KEYS_DIR ($KEYS_DIR) harus berada di dalam $AOSP_DIR agar bisa dipakai build."
    # certificate: "platform" / "shared" / "media" dicari di folder yang sama dengan releasekey.
    SIGNING_MK="PRODUCT_DEFAULT_DEV_CERTIFICATE := $KEYS_REL/releasekey"
    echo "    Memakai kunci sendiri: $KEYS_REL"
else
    warn "Belum ada kunci sendiri; build memakai test-keys AOSP (publik, JANGAN untuk produksi)."
    echo "    Buat dengan: ./scripts/10-gen-keys.sh"
fi

log "Buat produk $PRODUCT"
mkdir -p "$DEST/products"

cat > "$DEST/products/AndroidProducts.mk" <<MK
# Dibuat otomatis oleh scripts/02-install-kioskos.sh - jangan diedit manual.
PRODUCT_MAKEFILES := \$(LOCAL_DIR)/$PRODUCT.mk
MK

cat > "$DEST/products/$PRODUCT.mk" <<MK
# Dibuat otomatis oleh scripts/02-install-kioskos.sh - jangan diedit manual.
# Emulator x86_64 standar AOSP + KioskOS.
\$(call inherit-product, $EMU_MK)
\$(call inherit-product, vendor/kioskos/kioskos.mk)

# Arahkan KioskLock & KioskUpdater ke $SERVER_URL.
PRODUCT_PACKAGE_OVERLAYS += vendor/kioskos/emu_overlay

# Beberapa produk generic membatasi file apa saja yang boleh masuk partisi system.
PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST += \\
    system_ext/priv-app/KioskLock/% \\
    system_ext/priv-app/KioskUpdater/% \\
    system_ext/etc/permissions/privapp-permissions-kiosklock.xml \\
    system_ext/etc/permissions/privapp-permissions-kioskupdater.xml

# Kunci tanda tangan (kosong = test-keys AOSP).
$SIGNING_MK

PRODUCT_NAME := $PRODUCT
PRODUCT_BRAND := KioskOS
PRODUCT_MODEL := KioskOS Emulator
MK

for app in KioskLock KioskUpdater; do
    mkdir -p "$DEST/emu_overlay/vendor/kioskos/apps/$app/res/values"
    cat > "$DEST/emu_overlay/vendor/kioskos/apps/$app/res/values/config.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<!-- Dibuat otomatis oleh scripts/02-install-kioskos.sh - jangan diedit manual. -->
<resources>
    <string name="config_server_url" translatable="false">$SERVER_URL</string>
    <string name="config_server_public_key" translatable="false">$PUBKEY</string>
</resources>
XML
done

# Identitas perusahaan di layar kunci.
BRAND_DIR="$DEST/emu_overlay/vendor/kioskos/apps/KioskLock/res"
rm -f "$BRAND_DIR/values/brand.xml" "$BRAND_DIR/drawable/brand_logo.png"
if [[ -n "$BRAND_NAME" ]]; then
    # Escape ala string resource Android: \ " ' dan @/? di awal, lalu &<> untuk XML.
    name_xml="$(python3 "$REPO_ROOT/scripts/tools/android_string.py" "$BRAND_NAME")"
    cat > "$BRAND_DIR/values/brand.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<!-- Dibuat otomatis oleh scripts/02-install-kioskos.sh (BRAND_NAME). -->
<resources>
    <string name="config_brand_name" translatable="false">$name_xml</string>
</resources>
XML
    echo "    Nama di layar kunci: $BRAND_NAME"
fi
if [[ -n "$BRAND_LOGO" ]]; then
    [[ -f "$BRAND_LOGO" && "$(head -c 8 "$BRAND_LOGO" | od -An -tx1 | tr -d ' \n')" == 89504e470d0a1a0a ]] \
        || die "BRAND_LOGO harus file PNG: $BRAND_LOGO"
    mkdir -p "$BRAND_DIR/drawable"
    cp "$BRAND_LOGO" "$BRAND_DIR/drawable/brand_logo.png"
    echo "    Logo di layar kunci: $BRAND_LOGO"
fi

log "Selesai. Berikutnya: ./scripts/03-build-emulator.sh"
