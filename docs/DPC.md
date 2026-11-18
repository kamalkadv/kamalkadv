# Edisi Device Owner (tanpa ROM custom)

KioskLock juga bisa berjalan sebagai **aplikasi Device Owner** biasa di HP/tablet/emulator
Android 11+ mana pun, tanpa build AOSP. Proyeknya ada di `android-dpc/` dan memakai kode yang
sama dengan versi ROM (`vendor/kioskos/apps/KioskLock/src`), ditambah `src-dpc/` untuk bagian
yang di ROM memakai API sistem.

| | ROM KioskOS (AOSP) | Edisi Device Owner |
|---|---|---|
| Layar kunci, kiosk, aplikasi diizinkan, pemilih Wi-Fi, dashboard | Ya | Ya |
| Jadi Device Owner otomatis | Saat first boot | `adb shell dpm set-device-owner` (uji), QR code / zero-touch (produksi) |
| Bertahan setelah factory reset | Ya (partisi FRP) | **Tidak**: daftar ulang lewat QR / zero-touch, lalu status diambil dari server |
| Matikan OEM unlocking | Ya | Tidak (butuh izin sistem) |
| Update OS sendiri (KioskUpdater) | Ya | Tidak (update dari pabrik HP) |
| Bisa dijual ke perangkat merek apa pun | Tidak | Ya |

## Mencoba di mini PC

### 1. Kebutuhan

- Linux/macOS/Windows x86_64 dengan **virtualisasi aktif** (KVM di Linux) untuk emulator.
  Mini PC ARM umumnya tidak bisa menjalankan emulator x86_64.
- RAM minimal 8 GB, disk kosong sekitar 15 GB.
- **JDK 17 atau lebih baru.**
- **Android SDK.** Bisa lewat [Android CLI](https://developer.android.com/cli) atau `sdkmanager`.
  Paket yang dibutuhkan:
  - `platform-tools` (adb)
  - `emulator`
  - `platforms;android-35`
  - `build-tools;35.0.0`
  - system image **Google APIs** (bukan Google Play), misalnya
    `system-images;android-35;google_apis;x86_64`

Dengan `sdkmanager` / `avdmanager` (Android SDK command-line tools):

```bash
sdkmanager "platform-tools" "emulator" "platforms;android-35" "build-tools;35.0.0" \
           "system-images;android-35;google_apis;x86_64"
avdmanager create avd -n kiosk35 -k "system-images;android-35;google_apis;x86_64" -d pixel_6
emulator -avd kiosk35 -no-snapshot -wipe-data &
adb wait-for-device
```

Android CLI juga bisa membuat dan menjalankan emulator. Cek perintahnya dengan
`android --help`. Yang penting: imagenya **Google APIs** dan **jangan login akun Google**
di emulator. `dpm set-device-owner` gagal kalau sudah ada akun.

> Kenapa bukan image Google Play? Image Play berjenis build `user`, dan di build `user`
> KioskLock hanya mau bicara dengan server **HTTPS**. Image Google APIs berjenis
> `userdebug`, sehingga boleh memakai server lokal `http://10.0.2.2:8080`.

### 2. Clone & jalankan

```bash
git clone -b claude/android-device-lock-kiosk-rlgdlz https://github.com/kamalkadv/kamalkadv.git
cd kamalkadv
pip install -r server/requirements.txt
export ANDROID_HOME=~/Android/Sdk      # sesuaikan dengan lokasi SDK Anda
./scripts/40-dpc-try.sh
```

Skrip ini:

1. Mengisi URL server dan public key ke APK debug.
2. Build APK dengan Gradle. Pertama kali butuh beberapa menit untuk mengunduh Gradle dan
   plugin Android.
3. Memasang APK lalu menjadikannya Device Owner.
4. Menjalankan server dan mendaftarkan emulator. Token admin ditampilkan di layar.

Lalu buka `http://127.0.0.1:8080/admin/`, klik **Kunci** pada perangkat, dan dalam beberapa
detik sampai ~15 menit emulator terkunci. Untuk memaksa sinkron sekarang:

```bash
adb shell cmd jobscheduler run -f id.kioskos.lock 1
```

### 3. Uji otomatis

```bash
pkill -f 'python3 server.py'                  # tes menjalankan server ujinya sendiri
EDITION=dpc ./scripts/20-test-emulator.sh
```

Pengecekan priv-app, FRP, dan factory reset dilewati karena tidak berlaku untuk edisi ini.

### Pakai Claude Code di mini PC

Supaya Claude bisa build, menjalankan emulator, dan mengambil screenshot langsung, jalankan
Claude Code **di mini PC**. Caranya: aplikasi Claude Desktop, atau `claude remote-control`
di terminal dari folder repo. Android CLI juga tersedia sebagai plugin Claude Code.

## Masalah umum

| Gejala | Solusi |
|---|---|
| `Plugin [id: 'com.android.application', version: '8.7.3'] was not found` | Versi plugin Android tidak tersedia/terlalu baru untuk Gradle Anda. Ganti versi di `android-dpc/build.gradle.kts` dengan yang dipakai Android Studio/CLI Anda. |
| `SDK location not found` | Set `ANDROID_HOME`, atau pastikan `adb` berasal dari `<sdk>/platform-tools`. |
| `Failed to find target with hash string 'android-35'` | Pasang `platforms;android-35` (dan `build-tools;35.0.0`). |
| `Not allowed to set the device owner because there are already some accounts` | Wipe emulator (`emulator -avd kiosk35 -wipe-data`) dan jangan login akun Google. |
| `Gagal terhubung` di layar kunci | Server tidak jalan, atau image `user` (Google Play) menolak HTTP. |
| Daftar Wi-Fi kosong | Lokasi harus aktif; KioskLock menyalakannya sendiri setelah jadi Device Owner. Cek `adb logcat -s KioskLock.Provision`. Emulator hanya punya satu jaringan tiruan (`AndroidWifi`). |

Untuk menghapus Device Owner saat pengujian (hanya bisa di build debug):

```bash
adb shell dpm remove-active-admin id.kioskos.lock/.KioskAdminReceiver
```

Kalau ditolak, wipe emulator.

## Produksi: pendaftaran lewat QR code

Di HP sungguhan, Device Owner dipasang saat **setup awal**: ketuk layar sambutan 6 kali,
lalu scan QR berisi JSON seperti ini:

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "id.kioskos.lock/.KioskAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://lock.perusahaan.co.id/apk/kiosklock.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "<sha256 sertifikat penanda tangan APK, base64 url-safe>",
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true
}
```

HP akan mengunduh APK, memverifikasi tanda tangannya, menjadikannya Device Owner, lalu
menjalankan `ProvisioningModeActivity` dan `PolicyComplianceActivity`.

Untuk produksi:

- APK ditandatangani **kunci rilis Anda sendiri**, bukan kunci debug.
- URL server di build release memakai HTTPS. Ganti `config_server_url` dan
  `config_server_public_key` di resource release.
- Agar HP otomatis terdaftar lagi setelah factory reset, pakai **Android Enterprise
  zero-touch** (HP dari reseller resmi) atau **Knox Mobile Enrollment** (Samsung).

> Alur QR ini belum diuji di perangkat sungguhan.
