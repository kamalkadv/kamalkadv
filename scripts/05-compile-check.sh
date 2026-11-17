#!/usr/bin/env bash
# Pengecekan compile CEPAT untuk aplikasi KioskOS (beberapa detik), tanpa source AOSP.
#
# Mengompilasi kode Java KioskLock, KioskUpdater, dan kioskos-common terhadap framework Android lengkap (termasuk
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
VENDOR="$REPO_ROOT/vendor/kioskos"
# nama-aplikasi:package
APPS=(KioskLock:id.kioskos.lock KioskUpdater:id.kioskos.updater)

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

log "Compile kioskos-common + ${APPS[*]%%:*}"
SOURCES=()
mapfile -t -O 0 SOURCES < <(find "$VENDOR/libs" -name '*.java')
for entry in "${APPS[@]}"; do
    app="${entry%%:*}" pkg="${entry##*:}"
    r="$WORK/gen/${pkg//.//}/R.java"
    python3 "$REPO_ROOT/scripts/tools/gen_r.py" "$VENDOR/apps/$app/res" "$r" "$pkg"
    mapfile -t -O "${#SOURCES[@]}" SOURCES < <(find "$VENDOR/apps/$app/src" -name '*.java')
    SOURCES+=("$r")
done
mkdir -p "$WORK/classes"
if javac -encoding UTF-8 -Xlint:-deprecation -nowarn \
        -cp "$JAR" -d "$WORK/classes" "${SOURCES[@]}"; then
    log "OK: ${#SOURCES[@]} file Java ter-compile tanpa error"
else
    die "Compile gagal (lihat error di atas)"
fi
