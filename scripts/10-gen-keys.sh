#!/usr/bin/env bash
# Tahap 10: buat kunci tanda tangan SENDIRI untuk KioskOS.
#
#   1. Release keys (APK/OS): releasekey, platform, shared, media, networkstack,
#      sdk_sandbox, bluetooth, nfc. Pengganti test-keys AOSP yang publik.
#      KioskLock ditandatangani dengan "platform"; siapa pun yang punya kunci ini bisa
#      membuat aplikasi dengan hak setara KioskLock.
#   2. Kunci AVB (Android Verified Boot) + avb_pkmd.bin, untuk dipasang ke HP dengan
#      `fastboot flash avb_custom_key` sebelum bootloader dikunci.
#
# Aman dijalankan ulang: kunci yang sudah ada TIDAK ditimpa.
set -euo pipefail
source "$(dirname "$0")/config.sh"

command -v openssl >/dev/null || die "openssl belum terpasang."
RSA_BITS="${RSA_BITS:-4096}"
APK_KEYS=(releasekey platform shared media networkstack sdk_sandbox bluetooth nfc)

umask 077
mkdir -p "$KEYS_DIR"
chmod 700 "$KEYS_DIR"
log "Lokasi kunci: $KEYS_DIR"
echo "    Subject: $KEY_SUBJECT"

# Sama dengan development/tools/make_key, tanpa password (lihat docs/SIGNING.md).
make_key() {
    local name="$1" tmp
    if [[ -f "$KEYS_DIR/$name.pk8" && -f "$KEYS_DIR/$name.x509.pem" ]]; then
        echo "    [ada]  $name"
        return
    fi
    [[ -e "$KEYS_DIR/$name.pk8" || -e "$KEYS_DIR/$name.x509.pem" ]] \
        && die "Kunci $name tidak lengkap. Periksa manual; skrip tidak menimpa kunci."
    tmp="$(mktemp "$KEYS_DIR/.tmp.XXXXXX")"
    openssl genrsa -f4 -out "$tmp" "$RSA_BITS" 2>/dev/null
    openssl req -new -x509 -sha256 -key "$tmp" -days 10000 \
        -subj "$KEY_SUBJECT" -out "$KEYS_DIR/$name.x509.pem"
    openssl pkcs8 -in "$tmp" -topk8 -outform DER -nocrypt -out "$KEYS_DIR/$name.pk8"
    rm -f "$tmp"
    echo "    [baru] $name"
}

log "Release keys (RSA $RSA_BITS)"
for k in "${APK_KEYS[@]}"; do make_key "$k"; done

log "Kunci AVB"
if [[ -f "$KEYS_DIR/avb.pem" ]]; then
    echo "    [ada]  avb.pem"
else
    openssl genrsa -f4 -out "$KEYS_DIR/avb.pem" 4096 2>/dev/null
    echo "    [baru] avb.pem"
fi

# avb_pkmd.bin = public key dalam format yang dibaca bootloader (fastboot flash avb_custom_key).
AVBTOOL="$AOSP_DIR/external/avb/avbtool.py"
if [[ -f "$AVBTOOL" ]]; then
    python3 "$AVBTOOL" extract_public_key --key "$KEYS_DIR/avb.pem" --output "$KEYS_DIR/avb_pkmd.bin"
else
    # Source AOSP belum ada: pakai implementasi setara avbtool encode_rsa_key().
    python3 - "$KEYS_DIR/avb.pem" "$KEYS_DIR/avb_pkmd.bin" <<'PY'
import struct, subprocess, sys
pem, out = sys.argv[1], sys.argv[2]
mod_hex = subprocess.check_output(
    ["openssl", "rsa", "-in", pem, "-noout", "-modulus"], text=True).strip().split("=", 1)[1]
n = int(mod_hex, 16)
bits = n.bit_length()
b = 2 ** 32
n0inv = b - pow(n % b, -1, b)
rr = pow(2, 2 * bits, n)
with open(out, "wb") as f:
    f.write(struct.pack("!II", bits, n0inv))
    f.write(n.to_bytes(bits // 8, "big"))
    f.write(rr.to_bytes(bits // 8, "big"))
PY
fi
echo "    avb_pkmd.bin: $(sha256sum "$KEYS_DIR/avb_pkmd.bin" | cut -c1-16)…"

chmod 600 "$KEYS_DIR"/*
log "Sidik jari sertifikat platform (catat & simpan):"
openssl x509 -in "$KEYS_DIR/platform.x509.pem" -noout -fingerprint -sha256 | sed 's/^/    /'

cat <<MSG

$(printf '\033[1;33m')PENTING$(printf '\033[0m')
  * BACKUP folder ini sekarang ke 2 tempat offline (mis. USB terenkripsi + brankas).
    Kunci hilang = Anda tidak bisa lagi mengirim update ke HP yang sudah terjual.
  * Kunci bocor = siapa pun bisa membuat ROM/aplikasi yang dipercaya HP Anda.
  * Jangan commit ke git, jangan kirim lewat chat/email.

Berikutnya: ./scripts/02-install-kioskos.sh (otomatis memakai kunci ini), lalu build ulang
dengan ./scripts/03-build-emulator.sh dan jalankan emulator dengan --wipe.
MSG
