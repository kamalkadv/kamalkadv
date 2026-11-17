# Kunci Tanda Tangan: Release Keys & AVB

Tanpa kunci sendiri, semua perlindungan KioskOS bisa dibobol. Dokumen ini menjelaskan
kunci apa saja yang dibutuhkan, cara membuatnya, dan cara memakainya.

## Kenapa wajib

AOSP datang dengan **test-keys** yang bisa diunduh siapa pun. ROM yang ditandatangani
test-keys berarti:

- Siapa pun bisa membuat aplikasi yang ditandatangani kunci "platform" yang sama,
  lalu aplikasi itu punya hak setara KioskLock (bisa mencabut Device Owner, dan lain-lain).
- Siapa pun bisa membuat update OTA palsu yang diterima HP Anda.

Ada **dua jenis kunci**:

| Kunci | Fungsi | Dipakai di |
|---|---|---|
| **Release keys** (`releasekey`, `platform`, `shared`, `media`, …) | Menandatangani aplikasi sistem & OTA | Saat build |
| **Kunci AVB** (`avb.pem`) | Menandatangani image boot/system/vendor agar bootloader hanya mau menjalankan ROM Anda | Saat build + dipasang ke bootloader HP |

## Membuat kunci

Jalankan setelah source AOSP selesai di-sync:

```bash
./scripts/10-gen-keys.sh
```

Opsi lewat variabel environment:

```bash
KEY_SUBJECT='/C=ID/ST=Jawa Barat/L=Bandung/O=PT Contoh/OU=Android/CN=Contoh OS' ./scripts/10-gen-keys.sh
RSA_BITS=4096   # default; 2048 lebih cepat tapi kurang kuat
```

> `KEY_SUBJECT` tertanam di setiap APK dan bisa dibaca siapa pun. Pakai nama
> perusahaan, jangan nama atau email pribadi.

Hasilnya ada di `$AOSP_DIR/vendor/kioskos-keys/` (default `~/aosp/vendor/kioskos-keys/`):

```
releasekey.pk8 / .x509.pem    aplikasi umum + OTA
platform.pk8   / .x509.pem    framework & KioskLock
shared, media, networkstack, sdk_sandbox, bluetooth, nfc
avb.pem                        kunci privat AVB
avb_pkmd.bin                   kunci publik AVB untuk bootloader
```

Skrip **tidak pernah menimpa** kunci yang sudah ada, jadi aman dijalankan ulang.

### Backup: lakukan sekarang

- **Kunci hilang:** Anda tidak bisa mengirim update lagi ke HP yang sudah beredar.
  HP harus di-flash ulang satu per satu.
- **Kunci bocor:** semua HP yang beredar bisa dibobol.

Simpan salinan di minimal dua tempat offline, misalnya USB terenkripsi di brankas
dan password manager perusahaan. Jangan masukkan ke git, Google Drive pribadi, atau chat.

Kunci dibuat **tanpa password** agar build otomatis tidak macet. Karena itu, simpan
di disk terenkripsi dan batasi siapa yang bisa login ke mesin build. Untuk produksi
yang lebih serius, simpan di HSM dan tanda tangani lewat pipeline rilis (lihat bawah).

## Memakai kunci di build emulator

```bash
./scripts/10-gen-keys.sh
./scripts/02-install-kioskos.sh      # otomatis menambahkan PRODUCT_DEFAULT_DEV_CERTIFICATE
./scripts/03-build-emulator.sh
./scripts/11-check-signing.sh        # pastikan KioskLock & framework pakai kunci Anda
./scripts/04-run-emulator.sh --wipe  # WAJIB --wipe: kunci platform berubah
```

Contoh output `11-check-signing.sh`:

```
    OK    KioskLock ditandatangani kunci platform Anda
    OK    framework ditandatangani kunci platform Anda
    OK    Settings ditandatangani kunci platform Anda
==> Semua OK
```

> Kenapa harus `--wipe`? Data aplikasi lama tercatat dengan sertifikat test-key.
> Setelah kunci platform berubah, Android menolak data itu dan emulator bisa bootloop.
> Hal yang sama berlaku di HP sungguhan: ganti kunci = wajib factory reset.

Emulator **tidak** memverifikasi AVB, jadi kunci AVB baru berguna di HP sungguhan.

## Memakai kunci AVB di HP sungguhan

Contoh untuk Google Pixel, yang mendukung kunci AVB pengguna. Untuk HP dari ODM,
langkah ini biasanya dilakukan pabrik di jalur produksi (kunci di-fuse ke chip).

### 1. Konfigurasi build

Di `BoardConfig.mk` device (bukan di makefile produk):

```make
BOARD_AVB_ENABLE := true
BOARD_AVB_ALGORITHM := SHA256_RSA4096
BOARD_AVB_KEY_PATH := vendor/kioskos-keys/avb.pem

# Partisi berantai (chained), jika device memakainya: cek BoardConfig asli device Anda.
BOARD_AVB_VBMETA_SYSTEM_KEY_PATH := vendor/kioskos-keys/avb.pem
BOARD_AVB_VBMETA_SYSTEM_ALGORITHM := SHA256_RSA4096
```

Kunci yang sama boleh dipakai untuk vbmeta utama dan partisi berantai. Bisa juga
dibuat terpisah per partisi agar lebih aman.

### 2. Flash dan kunci bootloader

```bash
fastboot flashing unlock                     # sekali, menghapus data
fastboot flash avb_custom_key vendor/kioskos-keys/avb_pkmd.bin
fastboot flashall -w                         # atau update image ROM Anda
fastboot flashing lock                       # kunci ulang; data dihapus lagi
```

Setelah dikunci:

- Saat boot muncul peringatan kuning **"Your device is loading a different operating
  system"**. Ini normal untuk ROM dengan kunci sendiri; GrapheneOS juga begitu.
- Matikan **OEM unlocking**. KioskLock melakukannya otomatis saat perangkat terdaftar.
  Tanpa itu, bootloader tidak bisa di-unlock lagi.

> **Jangan kunci bootloader sebelum yakin ROM boot normal dengan kunci tersebut.**
> Kalau image tidak cocok dengan `avb_custom_key`, HP tidak bisa boot dan karena
> bootloader terkunci Anda tidak bisa memperbaikinya lewat fastboot. Uji dulu dalam
> kondisi unlocked: boot harus sukses dan `adb shell getprop ro.boot.verifiedbootstate`
> harus terbaca. Baru setelah itu kunci.

## Untuk produksi (nanti)

Cara `PRODUCT_DEFAULT_DEV_CERTIFICATE` cukup untuk prototipe. Untuk rilis resmi,
standar Google adalah:

1. Build `target-files` dengan test-keys (`m target-files-package otatools`).
2. Tanda tangani ulang di mesin terpisah yang aman dengan `sign_target_files_apks`,
   termasuk kunci per-**APEX** (`--extra_apex_payload_key`) yang belum dicakup skrip ini.
3. Buat OTA dengan `ota_from_target_files`.

Dengan cara ini, kunci tidak perlu ada di mesin build harian. Skrip untuk langkah ini
akan dibuat bersama server OTA.
