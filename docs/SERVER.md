# Server & Dashboard Admin

Server KioskOS (`server/server.py`) menangani tiga hal:

- **Status kunci** per perangkat (dicek KioskLock setiap ~15 menit dan saat boot).
- **Update OTA**: memilih paket yang tepat untuk tiap HP dan menyajikan filenya.
- **Dashboard admin** di `/admin/` untuk petugas.

Server hanya butuh Python 3.9+ dan paket `cryptography`. Data disimpan sebagai file JSON
di `DATA_DIR`.

## Menjalankan

```bash
cd server
pip install -r requirements.txt
python3 gen_keys.py                      # sekali: kunci penandatangan respons
python3 manage.py add-admin budi         # token ditampilkan SEKALI, simpan baik-baik
python3 manage.py add-admin siti
ADMIN_TOKENS_FILE=data/admins.txt python3 server.py
```

Buka `http://127.0.0.1:8080/admin/`, lalu masuk dengan token admin.

> Untuk pengujian cepat: `ADMIN_TOKEN=rahasia python3 server.py` membuat satu admin
> bernama `admin`.

Setiap admin punya token sendiri dan server hanya menyimpan hash-nya. Semua tindakan
tercatat atas nama admin tersebut di **Log audit**. Untuk mencabut akses, jalankan
`manage.py remove-admin <nama>` lalu restart server.

## Dashboard

| Tab | Isi |
|---|---|
| **Perangkat** | Ringkasan jumlah (terkunci/aktif/lepas), cari per serial/IMEI/catatan, filter status. Tombol **Kunci** (dengan pesan, kontak, dan **aplikasi yang tetap bisa dibuka**), **Ubah** (untuk perangkat yang sedang terkunci), **Buka kunci**, **Lepas** (lunas), **Daftarkan perangkat**. Terlihat juga kapan HP terakhir online dan versi build-nya. |
| **Rilis OTA** | Daftar rilis, **rollout %** untuk peluncuran bertahap, dan tombol **Jeda/Lanjutkan**. |
| **Log audit** | Siapa mengubah apa, kapan, dan dari IP mana. |

Di HP, tabel tampil sebagai kartu sehingga dashboard bisa dipakai petugas lapangan.

Perubahan status berlaku saat HP sinkron berikutnya, maksimal sekitar 15 menit, atau langsung
jika pengguna menekan **Cek status** di layar kunci.

## Aplikasi yang diizinkan saat terkunci

Saat mengunci atau mengubah perangkat, admin bisa mencentang aplikasi yang tetap bisa dibuka,
misalnya WhatsApp untuk menghubungi kantor atau aplikasi bank/e-wallet untuk membayar. Bisa
juga mengetik nama paket lain. Maksimal 12 aplikasi per perangkat.

- Daftar centang diambil dari `server/app_presets.json`. Ubah sesuai kebutuhan dan pastikan
  nama paketnya benar (`adb shell pm list packages | grep <nama>`).
- Aplikasi yang tidak terpasang di HP otomatis tidak ditampilkan.
- **Tidak bisa diizinkan:** Settings, Play Store, pemasang aplikasi, pengelola file, launcher,
  SystemUI, setup wizard, dan aplikasi KioskOS sendiri. Daftar ini diblokir di server
  (`DENIED_PACKAGES`) **dan** di HP (`LockController`). Jadi walau server dibobol atau admin
  salah ketik, HP tetap menolaknya.
- Daftar ikut tersimpan di partisi FRP, jadi tetap berlaku setelah factory reset.
- Aplikasi yang diizinkan hanya bisa membuka aplikasinya sendiri. Jika aplikasi itu mencoba
  membuka aplikasi lain (browser, kamera, pemilih file), Android memblokirnya selama terkunci.
  Uji alur pembayaran tiap aplikasi bank sebelum dipakai.

## Endpoint

Respons ke perangkat ditandatangani (header `X-Signature`, ECDSA P-256) dan memuat `nonce`
dari request, sehingga tidak bisa dipalsukan atau diputar ulang.

| Method | Path | Untuk |
|---|---|---|
| GET | `/api/v1/devices/status?serial&imei&nonce` | KioskLock |
| GET | `/api/v1/ota/check?serial&device&fingerprint&build_utc&channel&nonce` | KioskUpdater |
| GET/HEAD | `/ota/<file>.zip` (mendukung `Range`) | update_engine |
| GET | `/admin/` | Dashboard |
| GET | `/admin/api/whoami`, `/admin/api/devices`, `/admin/api/releases`, `/admin/api/audit`, `/admin/api/app-presets` | Admin |
| PUT / DELETE | `/admin/api/devices/<serial>` | Admin: ubah / lepas perangkat |
| POST | `/admin/api/releases` | Admin: daftarkan rilis (`31-publish-release.sh`) |
| PATCH | `/admin/api/releases/<id>` | Admin: `rollout`, `paused`, `channel` |

Untuk admin, kirim header `Authorization: Bearer <token>`.

### Pemilihan update OTA

Untuk setiap HP, server memilih rilis yang memenuhi semua syarat berikut:

- `device` dan `channel` sama dengan HP;
- build-nya lebih baru dari build HP;
- tidak sedang dijeda;
- HP masuk kelompok rollout. Kelompoknya ditentukan dari hash serial, jadi HP yang sama
  selalu konsisten masuk atau tidak masuk.

Dari rilis yang memenuhi syarat, server memilih yang terbaru. Untuk versi yang sama, paket
**inkremental** dipilih bila fingerprint HP cocok dengan build asal paket itu. Jika tidak
cocok, server memilih paket **full**.

## Deploy produksi

Contoh di satu VPS. Sesuaikan dengan infrastruktur Anda.

```nginx
# /etc/nginx/sites-enabled/kioskos
server {
    listen 443 ssl http2;
    server_name lock.perusahaan.co.id;
    ssl_certificate     /etc/letsencrypt/live/lock.perusahaan.co.id/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/lock.perusahaan.co.id/privkey.pem;

    client_max_body_size 64k;

    # Batasi dashboard ke jaringan kantor / VPN.
    location /admin/ {
        allow 203.0.113.0/24;
        deny all;
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
    }
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
    }
}
```

```ini
# /etc/systemd/system/kioskos.service
[Service]
User=kioskos
WorkingDirectory=/srv/kioskos/server
Environment=DATA_DIR=/srv/kioskos/data
Environment=ADMIN_TOKENS_FILE=/srv/kioskos/data/admins.txt
Environment=PUBLIC_BASE_URL=https://lock.perusahaan.co.id
ExecStart=/usr/bin/python3 server.py
Restart=always
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/srv/kioskos/data

[Install]
WantedBy=multi-user.target
```

Checklist:

- [ ] HTTPS dengan sertifikat valid. Build `user` menolak HTTP.
- [ ] `private_key.pem` hanya bisa dibaca user `kioskos` (`chmod 600`). Untuk skala besar,
      pindahkan penandatanganan ke HSM/KMS.
- [ ] Backup `DATA_DIR` setiap hari, terutama `devices.json` dan `audit.log`.
- [ ] Dashboard dibatasi ke IP kantor/VPN. Token admin saja tidak cukup jika bocor.
- [ ] File OTA besar sebaiknya disajikan lewat CDN (`OTA_BASE_URL` saat publish).
- [ ] Server ini memakai file JSON dan cocok untuk ribuan perangkat. Untuk puluhan ribu
      perangkat, pindahkan penyimpanan ke database (PostgreSQL). Antarmuka HTTP-nya tetap sama.

## Tes

```bash
python3 -m unittest server/test_server.py -v
```

Tes ini menjalankan server sungguhan di port acak dan mengecek:

- tanda tangan setiap respons;
- autentikasi dan validasi input;
- pemilihan rilis (full/inkremental, channel, rollout, jeda);
- unduhan Range dan blokir path traversal;
- header keamanan dashboard.
