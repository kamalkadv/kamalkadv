#!/usr/bin/env bash
# Tahap 0: siapkan PC build (Ubuntu 22.04 / 24.04 x86_64).
# Memasang paket yang dibutuhkan AOSP, tool "repo", dan mengecek RAM/disk/KVM.
set -euo pipefail
source "$(dirname "$0")/config.sh"

[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] \
    || die "AOSP hanya bisa di-build di Linux x86_64."

log "Cek spesifikasi"
ram_gb=$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 / 1024 ))
mkdir -p "$AOSP_DIR"
disk_gb=$(( $(df -Pk "$AOSP_DIR" | awk 'NR==2 {print $4}') / 1024 / 1024 ))
echo "    RAM : ${ram_gb} GB   (disarankan 64 GB, minimal 32 GB)"
echo "    Disk: ${disk_gb} GB kosong di $AOSP_DIR   (butuh ~400 GB)"
(( ram_gb >= 30 ))  || warn "RAM kurang dari 32 GB: build bisa gagal/sangat lambat. Tambah swap atau kurangi JOBS."
(( disk_gb >= 350 )) || warn "Disk kosong kurang dari ~400 GB: sync/build kemungkinan gagal."
if [[ -e /dev/kvm ]]; then
    echo "    KVM : ada (emulator bisa jalan cepat)"
else
    warn "/dev/kvm tidak ada: emulator x86_64 akan sangat lambat atau tidak bisa jalan."
fi

log "Pasang paket build (butuh sudo)"
sudo apt-get update
sudo apt-get install -y \
    git git-lfs gnupg flex bison build-essential zip unzip curl rsync \
    zlib1g-dev libc6-dev-i386 x11proto-core-dev libx11-dev lib32z1-dev \
    libgl1-mesa-dev libxml2-utils xsltproc fontconfig python3 python3-pip \
    python3-venv ccache openssl

log "Pasang tool 'repo'"
mkdir -p "$HOME/.bin"
curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo -o "$HOME/.bin/repo"
chmod a+rx "$HOME/.bin/repo"
if ! grep -q 'HOME/.bin' "$HOME/.bashrc" 2>/dev/null; then
    echo 'export PATH="$HOME/.bin:$PATH"' >> "$HOME/.bashrc"
fi

if [[ -z "$(git config --global user.email || true)" ]]; then
    warn "git belum dikonfigurasi. Jalankan:"
    echo '      git config --global user.name  "Nama Anda"'
    echo '      git config --global user.email "email@anda"'
fi

if [[ -e /dev/kvm ]] && ! groups | grep -qw kvm; then
    sudo usermod -aG kvm "$USER"
    warn "User ditambahkan ke grup 'kvm'. Logout & login lagi agar berlaku."
fi

log "Selesai. Buka terminal baru (agar PATH ter-update), lalu jalankan ./scripts/01-sync-aosp.sh"
