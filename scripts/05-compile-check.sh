#!/usr/bin/env bash
# Pengecekan compile CEPAT untuk KioskLock (beberapa detik), tanpa source AOSP.
#
# Mengompilasi semua kode Java KioskLock terhadap framework Android lengkap (termasuk
# hidden/system API) dari artefak Robolectric "android-all" di Maven Central, yang dibangun
# dari AOSP. Cocok dijalankan setiap kali mengubah kode, sebelum build AOSP yang lama.
#
# Yang TIDAK dicek: resource/manifest (aapt2), izin, perilaku saat runtime.
# Build AOSP sungguhan (03-build-emulator.sh) tetap jadi penentu akhir.
set -euo pipefail
source "$(dirname "$0")/config.sh"

# Versi framework: sesuaikan dengan versi Android target (lihat maven-metadata.xml).
ANDROID_ALL_VERSION="${ANDROID_ALL_VERSION:-15-robolectric-14663667-2}"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/kioskos"
JAR="$CACHE/android-all-$ANDROID_ALL_VERSION.jar"
APP="$REPO_ROOT/vendor/kioskos/apps/KioskLock"

command -v javac >/dev/null || die "javac tidak ada. Pasang JDK 17+: sudo apt-get install openjdk-17-jdk-headless"

if [[ ! -s "$JAR" ]]; then
    log "Download framework Android ($ANDROID_ALL_VERSION, ~190 MB, sekali saja)"
    mkdir -p "$CACHE"
    curl -fL --progress-bar -o "$JAR.part" \
        "https://repo1.maven.org/maven2/org/robolectric/android-all/$ANDROID_ALL_VERSION/android-all-$ANDROID_ALL_VERSION.jar"
    mv "$JAR.part" "$JAR"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log "Compile KioskLock"
python3 "$REPO_ROOT/scripts/tools/gen_r.py" "$APP/res" "$WORK/gen/id/kioskos/lock/R.java"
mkdir -p "$WORK/classes"
mapfile -t SOURCES < <(find "$APP/src" -name '*.java')
if javac -encoding UTF-8 -Xlint:-deprecation -nowarn \
        -cp "$JAR" -d "$WORK/classes" "${SOURCES[@]}" "$WORK/gen/id/kioskos/lock/R.java"; then
    log "OK: ${#SOURCES[@]} file Java ter-compile tanpa error"
else
    die "Compile gagal (lihat error di atas)"
fi
