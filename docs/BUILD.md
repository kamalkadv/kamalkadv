# Build KioskOS untuk Emulator

Panduan dari PC Linux kosong sampai emulator Android jalan dengan KioskLock terpasang.

## Kebutuhan PC

| | Minimal | Disarankan |
|---|---|---|
| OS | Ubuntu 22.04 / 24.04, x86_64 | sama |
| CPU | 8 core | 16+ core |
| RAM | 32 GB | 64 GB |
| Disk kosong | 400 GB (SSD) | 500 GB+ NVMe |
| Lainnya | Virtualisasi (KVM) aktif di BIOS untuk emulator | |

> Windows: pakai PC Linux terpisah atau dual-boot. WSL2 bisa untuk build, tapi
> emulatornya sulit dijalankan di sana. macOS tidak didukung untuk build AOSP.

## Urutan perintah

Jalankan dari folder repo ini:

```bash
./scripts/00-setup-host.sh       # sekali saja: paket, tool 'repo', cek RAM/disk/KVM
# buka terminal baru
./scripts/01-sync-aosp.sh        # download AOSP (~100+ GB, bisa berjam-jam)
./scripts/02-install-kioskos.sh  # pasang KioskOS + buat produk "kiosk_emu"
./scripts/03-build-emulator.sh   # build (pertama kali 2-6 jam)
./scripts/04-run-emulator.sh     # jalankan emulator
```

Pengaturan bisa diubah lewat variabel environment (lihat `scripts/config.sh`):

```bash
AOSP_DIR=/mnt/ssd/aosp ./scripts/01-sync-aosp.sh   # lokasi source tree (default ~/aosp)
JOBS=8 ./scripts/03-build-emulator.sh              # kurangi jika RAM pas-pasan
AOSP_BRANCH=android-15.0.0_r36 ./scripts/01-sync-aosp.sh   # versi Android tertentu
RELEASE=trunk_staging ./scripts/03-build-emulator.sh       # jika deteksi otomatis gagal
```

> **Kunci sendiri:** sebelum build yang serius, jalankan `./scripts/10-gen-keys.sh`
> agar ROM tidak ditandatangani test-keys AOSP yang publik. Lihat [SIGNING.md](SIGNING.md).

### Apa yang dilakukan skrip 02

1. Menyalin `vendor/kioskos/` ke `$AOSP_DIR/vendor/kioskos/`.
2. Memakai kunci tanda tangan sendiri jika sudah dibuat dengan `10-gen-keys.sh`.
3. Membuat kunci server uji (`server/private_key.pem`, tidak ikut di-commit).
4. Membuat produk `kiosk_emu` = emulator standar AOSP (`sdk_phone64_x86_64`) + KioskOS,
   dengan KioskLock diarahkan ke `http://10.0.2.2:8080`, yaitu server di PC Anda
   dilihat dari dalam emulator.

**Setiap kali mengubah kode di repo ini, jalankan ulang 02 lalu 03.** Build ulang
jauh lebih cepat dari build pertama.

## Menjalankan server uji

Di terminal terpisah, sebelum menyalakan emulator:

```bash
cd server
ADMIN_TOKEN=rahasia python3 server.py
```

Server mendengarkan di `127.0.0.1:8080`, yang dari emulator terlihat sebagai `10.0.2.2:8080`.

> HTTP polos hanya diizinkan untuk `10.0.2.2` **dan** hanya di build `userdebug`/`eng`.
> Build `user` (produksi) tetap wajib HTTPS.

## Cek cepat setelah emulator menyala

```bash
adb shell getprop ro.frp.pst                  # harus ada isinya (partisi FRP)
adb shell pm list packages | grep kioskos     # package:id.kioskos.lock
adb shell dumpsys device_policy | grep -A3 "Device Owner"
adb shell getprop ro.serialno                 # serial untuk didaftarkan di server
adb logcat -s 'KioskLock.*'                   # log dari aplikasi
```

Lalu coba kunci perangkat:

```bash
SERIAL=$(adb shell getprop ro.serialno | tr -d '\r')
curl -X PUT -H 'Authorization: Bearer rahasia' \
  -d '{"locked":true,"message":"Uji kunci","contact":"0812-0000-0000"}' \
  http://127.0.0.1:8080/admin/devices/$SERIAL
```

Tekan **Cek status** di HP (atau tunggu sinkron otomatis). Uji lengkap dan otomatis,
termasuk simulasi factory reset, ada di [TESTING.md](TESTING.md):
`./scripts/20-test-emulator.sh`.

## Masalah umum

| Gejala | Penyebab / solusi |
|---|---|
| `repo sync` putus-putus | Jalankan ulang `01-sync-aosp.sh`; skrip melanjutkan dari posisi terakhir. |
| Build gagal `Killed` / `out of memory` | RAM kurang: `JOBS=4 ./scripts/03-build-emulator.sh`, atau tambah swap 32 GB. |
| `Tidak menemukan definisi sdk_phone64_x86_64` | Versi AOSP memakai nama produk emulator lain. Cari dengan `grep -r "PRODUCT_NAME.*sdk_phone" device/generic/goldfish`. |
| `lunch` gagal karena release config | Lihat pilihan di `build/release/release_configs/`, lalu set `RELEASE=<nama>`. |
| Error compile di `Provisioner.java` (`setDeviceOwner`) | Signature hidden API berbeda di versi Android Anda. Cek `frameworks/base/core/java/android/app/admin/DevicePolicyManager.java`. |
| Error `artifact path requirement` | Tambahkan path yang disebut di pesan error ke `PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST` di skrip 02. |
| Emulator sangat lambat / tidak mau jalan | KVM tidak aktif. Cek `ls /dev/kvm`, aktifkan virtualisasi di BIOS, dan pastikan user ada di grup `kvm`. |
| `getprop ro.frp.pst` kosong | Image emulator tidak punya partisi FRP; status kunci tidak akan bertahan setelah reset. Lihat [TESTING.md](TESTING.md#tentang-partisi-frp-di-emulator). |
