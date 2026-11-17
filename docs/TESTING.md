# Menguji KioskOS di Emulator

Setelah [BUILD.md](BUILD.md) dan [SIGNING.md](SIGNING.md), langkah berikutnya adalah
membuktikan alur utamanya bekerja: **kunci → coba kabur → reboot → buka → factory reset →
terkunci lagi**.

## Uji otomatis

```bash
./scripts/04-run-emulator.sh --wipe     # terminal 1: emulator bersih (build userdebug)
./scripts/20-test-emulator.sh           # terminal 2: semua tes (~10-15 menit)
./scripts/20-test-emulator.sh --no-reset   # lebih cepat, tanpa tes factory reset
```

Skrip menjalankan **server ujinya sendiri** di port 8080. Matikan dulu `server.py`
yang mungkin sedang Anda jalankan. Data server uji disimpan di folder sementara, jadi
tidak mengganggu `server/devices.json`.

### Daftar tes

| # | Tes | Yang dibuktikan |
|---|---|---|
| 1 | Instalasi | KioskLock ada di `priv-app`, menjadi **Device Owner** otomatis, partisi FRP tersedia |
| 2 | Daftarkan (tidak dikunci) | Factory reset dari Settings & safe mode diblokir, layar kunci **tidak** muncul |
| 3 | Kunci | Layar kunci muncul, Lock Task Mode aktif |
| 4 | Coba kabur | Home, Back, Recents, intent HOME, membuka aplikasi lain, **Settings**, dan **panel Wi-Fi bawaan** tidak bisa keluar dari layar kunci |
| 4c | Aplikasi yang diizinkan | Aplikasi yang diizinkan admin bisa dibuka; sinkron berkala tidak menarik pengguna keluar dari aplikasi itu; Home kembali ke layar kunci; setelah izin dicabut aplikasi tidak bisa dibuka |
| 4b | Layar Wi-Fi | Tombol Wi-Fi membuka pemilih Wi-Fi KioskLock; Home tidak bisa keluar; Back kembali ke layar kunci |
| 5 | Reboot saat terkunci, **server mati** | Status dibaca dari penyimpanan lokal, tetap terkunci tanpa internet |
| 6 | Buka kunci | Layar kunci hilang, Lock Task Mode mati |
| 7 | **Factory reset**, server online | Device Owner terpasang lagi sendiri, perangkat terkunci lagi dari server (cara kerja Knox Guard) |
| 8 | **Factory reset**, server **offline** | Terkunci lagi hanya dari partisi FRP, tanpa internet (hanya jika ada FRP) |
| 9 | Lepas (lunas) | Semua restriksi dicabut |

Contoh hasil:

```
==> Ringkasan: 19 lulus, 0 gagal, 2 dilewati
    LULUS  KioskLock terpasang
    LULUS  KioskLock adalah Device Owner
    LEWATI Partisi FRP tidak ada di image emulator ini (lihat docs/TESTING.md)
    ...
    LULUS  Terkunci lagi setelah reset (dari server)
    LEWATI Butuh partisi FRP; image emulator ini tidak punya. Uji di HP sungguhan.
```

Skrip keluar dengan kode `1` jika ada tes yang gagal, sehingga bisa dipakai di CI.

> Tes 7 dan 8 mematikan emulator lalu menyalakannya lagi **tanpa jendela**
> (`-no-window`). Log-nya ada di `~/aosp/out/kioskos-emulator.log`. Untuk melihat
> layarnya, jalankan dengan `EMU_EXTRA_ARGS="" ./scripts/20-test-emulator.sh`.

## Tentang partisi FRP di emulator

Image emulator AOSP standar kemungkinan **tidak punya partisi FRP**. Cek dengan
`adb shell getprop ro.frp.pst`; kosong berarti tidak ada. Akibatnya:

- `PersistentDataBlockService` tidak berjalan dan KioskLock tidak punya penyimpanan
  yang selamat dari reset.
- **Tes 7 tetap bisa lulus**, karena setelah reset KioskLock jadi Device Owner lagi lalu
  bertanya ke server. Ini jalur utama, sama seperti Knox Guard.
- **Tes 8 dilewati.** Mengunci tanpa internet setelah reset hanya bisa dibuktikan di HP
  sungguhan yang punya partisi `frp` (hampir semua HP Android dengan GMS punya).

Artinya, di emulator: **reset + internet = tetap terkunci**. Kalau HP di-reset lalu
tidak pernah tersambung ke internet, HP baru terkunci lagi saat pertama online. Di HP
sungguhan dengan partisi FRP, HP langsung terkunci tanpa menunggu online.

## Uji manual (yang tidak bisa diotomatiskan)

Lakukan sekali di emulator dengan jendela, saat perangkat terkunci:

- [ ] Pesan & nomor kontak dari server tampil di layar kunci.
- [ ] **Panggilan darurat** membuka dialer darurat, dan nomor darurat (112) bisa dipanggil.
- [ ] **Sambungkan Wi-Fi** membuka layar Wi-Fi KioskLock (bukan Settings). Daftar jaringan
      muncul dan diurutkan dari sinyal terkuat.
- [ ] Sambung ke jaringan berkata sandi: kata sandi salah memunculkan pesan gagal, kata sandi
      benar membuat status "Tersambung ke …" lalu cek status ke server berjalan otomatis.
- [ ] **Jaringan lain…** bisa menyambung ke SSID tersembunyi.
- [ ] Aplikasi yang diizinkan (mis. WhatsApp, aplikasi bank) tampil sebagai ikon dan bisa dipakai
      sampai selesai, termasuk login/OTP. Catat jika aplikasi itu mencoba membuka aplikasi lain
      (browser, kamera, pemilih file): itu akan diblokir. Izinkan juga aplikasi tersebut bila
      memang dibutuhkan.
- [ ] Jika Wi-Fi mati: tombol **Nyalakan Wi-Fi** muncul dan berfungsi.
- [ ] Keyboard: tombol pengaturan / mikrofon di keyboard tidak membuka aplikasi lain.
- [ ] Status bar tidak bisa ditarik turun.
- [ ] Tombol power: menu power muncul (restart/matikan boleh), tapi tidak ada jalan keluar.
- [ ] **Cek status** setelah server membuka kunci: layar kunci hilang tanpa restart.
- [ ] Settings → System → Reset: opsi factory reset tidak bisa dipakai saat terdaftar.
- [ ] Developer Options (build `user`): tidak bisa diaktifkan saat terdaftar.

## Membaca log saat ada yang gagal

```bash
adb logcat -s 'KioskLock.Provision' 'KioskLock.Controller' 'KioskLock.Store' \
               'KioskLock.Server' 'KioskLock.Lock'
```

| Gejala | Kemungkinan penyebab |
|---|---|
| `Belum menjadi device owner` | `setDeviceOwner` gagal: setup sudah selesai sebelum `SetupActivity` jalan, atau signature hidden API berbeda di versi Anda. Lihat log `KioskLock.Provision`. |
| `Tanda tangan respons TIDAK valid` | Public key di image tidak cocok dengan `server/private_key.pem`. Jalankan ulang `02-install-kioskos.sh` lalu build ulang. |
| `Gagal menghubungi server` | Server mati, atau URL server bukan `10.0.2.2:8080`. |
| `PersistentDataBlock tidak tersedia` | Normal di emulator tanpa FRP (lihat di atas). |
| `Tidak diizinkan menulis persistent block` | Overlay `config_persistentDataPackageName` tidak terpasang. Cek `adb shell cmd overlay list`. |
| Tes 4 gagal (bisa keluar) | Catat tombol/cara yang berhasil keluar. Ini bug keamanan yang harus diperbaiki sebelum produksi. |

## Celah yang perlu diwaspadai

- **Batasan layar Wi-Fi KioskLock.** WPA-Enterprise (EAP) dan WEP tidak didukung, dan
  Wi-Fi dengan **halaman login** (captive portal, misalnya Wi-Fi hotel/kafe) tidak bisa
  dipakai, karena halaman loginnya dibuka oleh aplikasi lain yang diblokir saat terkunci.
  Untuk kasus ini pengguna perlu data seluler atau Wi-Fi rumah biasa.
- **Keyboard (IME).** Keyboard adalah aplikasi terpisah yang tetap tampil saat terkunci.
  Aktivitas lain yang dibuka keyboard (pengaturan, input suara) seharusnya diblokir Lock
  Task, tapi uji dengan keyboard yang benar-benar dipasang di ROM Anda.
- **ADB di build debug.** Agar tes otomatis jalan, build `userdebug` tidak mematikan ADB
  (`config_block_debugging_on_debuggable_builds=false`). Build `user` untuk produksi
  **selalu** mematikan ADB saat perangkat terdaftar.
- **Reset lewat recovery.** Emulator `-wipe-data` setara dengan factory reset dari
  recovery (menu tombol volume + power), yang tidak bisa diblokir oleh Device Owner.
  Itulah sebabnya tes 7 dan 8 penting.
