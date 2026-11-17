# Rilis Produksi & Update OTA

Dokumen ini menjelaskan cara membuat ROM resmi yang ditandatangani dengan kunci Anda,
lalu mengirim update ke HP yang sudah beredar.

## Gambaran

```
 PC build (tanpa kunci)              Mesin rilis (ada kunci)                 Server
 ─────────────────────               ───────────────────────                 ──────
 build target-files (test-keys) ──►  sign_target_files_apks
                                     (APK, APEX, kunci OTA, AVB)
                                         │
                                         ├─► signed-target_files.zip  (SIMPAN)
                                         ├─► OTA full / inkremental ──► 31-publish ──► /ota/*.zip
                                         └─► metadata JSON           ──►            ──► rilis (rollout %)

 HP: KioskUpdater ── cek /api/v1/ota/check ──► server pilih paket
                 └─ update_engine.applyPayload(url, offset, size) ──► unduh (Range), verifikasi,
                    pasang ke slot cadangan ──► restart saat HP diam & dicas
```

**Siapa yang menjamin keamanan update?** `update_engine` di HP. Ia hanya mau memasang
payload yang ditandatangani `releasekey` Anda. Kunci publiknya ditanam ke ROM oleh
`sign_target_files_apks -o`. Server yang dibobol pun tidak bisa memasang ROM palsu, paling
jauh hanya menahan update. Tanda tangan respons server adalah lapisan tambahan.

## Persiapan sekali

```bash
./scripts/10-gen-keys.sh        # release keys + AVB (lihat SIGNING.md); BACKUP!
```

Ubah produk ke mode rilis: build **tanpa** tanda tangan, dengan server HTTPS.

```bash
SIGN_AT_BUILD=0 SERVER_URL=https://lock.perusahaan.co.id ./scripts/02-install-kioskos.sh
```

> `kiosk_emu` dipakai di sini sebagai contoh alur. Emulator tidak memakai OTA A/B
> seperti HP sungguhan. Untuk HP, ganti `PRODUCT` dengan produk device Anda yang
> meng-inherit `vendor/kioskos/kioskos.mk`.

## Membuat rilis

```bash
# Rilis pertama (paket full)
VERSION=1.0.0 ./scripts/30-release.sh

# Rilis berikutnya: full + inkremental dari rilis sebelumnya
VERSION=1.1.0 PREV_TARGET_FILES=~/aosp/out/kioskos-dist/1.0.0/signed-target_files.zip \
  ./scripts/30-release.sh
```

Yang dilakukan skrip 30:

1. `m target-files-package otatools` dengan varian `user`.
2. **Kunci APEX:** `scripts/tools/apex_keys.py` membuat kunci sendiri untuk setiap modul
   APEX (payload + container) di `$KEYS_DIR/apex/`. APEX `PRESIGNED`, yaitu prebuilt yang
   sudah ditandatangani, dilewati.
3. `sign_target_files_apks`:
   - `-d $KEYS_DIR` (+ `sdk_sandbox`, `bluetooth`, `nfc`) untuk semua APK;
   - `-o` untuk mengganti kunci OTA dan kunci payload `update_engine`;
   - kunci APEX;
   - AVB untuk `vbmeta` dan setiap partisi berantai yang ada di build.
4. `ota_from_target_files -k releasekey` untuk paket full, dan paket inkremental bila
   `PREV_TARGET_FILES` diisi.
5. `scripts/tools/ota_metadata.py` menulis JSON rilis. Isinya:
   - offset dan ukuran `payload.bin` di dalam zip, karena `update_engine` mengunduh
     langsung bagian itu;
   - header `payload_properties.txt`;
   - device, timestamp, fingerprint, dan sha256.

Hasilnya di `~/aosp/out/kioskos-dist/<versi>/`. **Simpan `signed-target_files.zip`
setiap versi.** File itu dasar OTA inkremental berikutnya dan sumber image pabrik
(`img_from_target_files`).

## Mempublikasikan

```bash
export KIOSKOS_ADMIN_TOKEN=...                    # token dari manage.py add-admin
PUBLISH_URL=https://lock.perusahaan.co.id \
OTA_UPLOAD_DEST=deploy@lock.perusahaan.co.id:/srv/kioskos/data/ota \
ROLLOUT=5 \
  ./scripts/31-publish-release.sh ~/aosp/out/kioskos-dist/1.1.0/*.json
```

Skrip 31 memastikan sha256 zip cocok dengan metadata sebelum mengunggah, lalu mendaftarkan
rilis ke server.

### Strategi peluncuran

1. **Rollout 0% / dijeda.** Uji dulu di 2–3 HP internal di channel `beta`
   (`CHANNEL=beta` saat `30-release.sh`, dan HP uji memakai `config_ota_channel=beta`).
2. **5%:** pantau 1–2 hari di dashboard. Kolom **Build** menunjukkan HP yang sudah naik versi.
3. **25% → 50% → 100%.**
4. Ada masalah? Tekan **Jeda** di dashboard dan rollout langsung berhenti. HP yang gagal boot
   ke versi baru otomatis kembali ke slot lama (A/B).

> HP yang sudah ter-update **tidak bisa diturunkan versinya** lewat OTA (`update_engine`
> menolak downgrade). Perbaikan selalu berupa rilis baru dengan versi lebih tinggi.

## KioskUpdater di HP

Aplikasi `KioskUpdater` (`vendor/kioskos/apps/KioskUpdater`) ikut terpasang lewat
`kioskos.mk`.

| Pengaturan (`res/values/config.xml`) | Default | Arti |
|---|---|---|
| `config_server_url`, `config_server_public_key` | diisi `02-install-kioskos.sh` | sama dengan KioskLock |
| `config_ota_channel` | `stable` | channel rilis |
| `config_ota_check_interval_hours` | `6` | seberapa sering cek |
| `config_ota_unmetered_only` | `true` | hanya cek/unduh lewat Wi-Fi, agar tidak menghabiskan kuota pelanggan |

Alurnya:

1. Job berkala mengecek server.
2. Jika ada update, KioskUpdater memanggil `UpdateEngine.applyPayload()`.
3. `update_engine` mengunduh dan memasang update di latar belakang. Perangkat tetap bisa
   dipakai selama proses ini.
4. Setelah selesai, restart dijadwalkan saat HP **diam dan sedang dicas**.

Update tetap dipasang walau HP sedang terkunci, karena patch keamanan tetap penting.

Log di HP:

```bash
adb logcat -s KioskUpdater KioskUpdater.Job update_engine
```

## Status pengujian & yang perlu diverifikasi di HP sungguhan

**Sudah diuji di lingkungan pengembangan:**

- **Compile:** KioskUpdater dan `kioskos-common` ter-compile terhadap framework Android 15,
  termasuk API `UpdateEngine`.
- **Alur rilis:** `30-release.sh` dijalankan dengan tool AOSP tiruan untuk mengecek
  argumen `sign_target_files_apks`/`ota_from_target_files`, kunci APEX, AVB per partisi,
  dan metadata.
- **Publikasi dan pemilihan paket:** `31-publish-release.sh` dijalankan ke server
  sungguhan, dengan simulasi HP:
  - HP versi lama mendapat paket full;
  - HP versi sebelumnya mendapat paket inkremental;
  - payload yang diunduh lewat HTTP Range identik byte per byte dengan aslinya.

**Belum diuji, dan wajib dicek di HP A/B sungguhan (mis. Pixel) sebelum produksi:**

- [ ] `sign_target_files_apks` dan `ota_from_target_files` di versi AOSP Anda menerima semua
      argumen. Beberapa opsi `--avb_*` berbeda antar versi; skrip hanya mengirim yang ada di
      `--help`.
- [ ] **SELinux:** KioskUpdater (domain `priv_app`) diizinkan memanggil `update_engine`.
      Jika `adb logcat | grep avc` menunjukkan penolakan, tambahkan aturan sepolicy di
      `system_ext`.
- [ ] `update_engine` mau mengunduh dari server Anda. Build `user` mewajibkan HTTPS.
- [ ] Restart otomatis terjadi saat HP diam dan dicas, dan setelah restart HP berjalan di
      versi baru (`getprop ro.build.fingerprint`).
- [ ] Rollback: rilis yang sengaja dibuat gagal boot harus kembali ke slot lama.
- [ ] Setelah update, KioskLock tetap Device Owner dan status kunci tetap berlaku.

**Tidak didukung:** perangkat non-A/B. KioskUpdater akan mencatat log dan tidak melakukan
apa-apa.
