#!/bin/bash
# Baut den Matter-Controller (connectedhomeip) für Android arm64 und kopiert ihn nach app/libs/matter + jniLibs.
# Voraussetzungen (siehe docs/platforms/android/android_building.md im SDK): NDK 28.2.13676358, JDK 17,
# kotlinc 2.1.10 im PATH. Auf Apple Silicon zusätzlich eine native zap-Version (zap_download.py), sonst
# scheitert die Code-Generierung ohne Rosetta.
set -euo pipefail
SDK_DIR=${SDK_DIR:-$HOME/dev/connectedhomeip}   # außerhalb von iCloud klonen!
TAG=${TAG:-v1.6.0.0}
RAUM=$(cd "$(dirname "$0")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}

if [ ! -d "$SDK_DIR" ]; then
  git clone --depth 1 --branch "$TAG" https://github.com/project-chip/connectedhomeip.git "$SDK_DIR"
  (cd "$SDK_DIR" && python3 scripts/checkout_submodules.py --shallow --platform android && bash -c 'source scripts/bootstrap.sh')
fi
cd "$SDK_DIR"
source scripts/activate.sh
if [ "$(uname -m)" = arm64 ] && [ -z "${ZAP_INSTALL_PATH:-}" ]; then
  eval "$(python3 scripts/tools/zap/zap_download.py --sdk-root . --zap RELEASE --extract-root "$HOME/dev/tools/zap" | grep '^export')"
fi
# Bricht bei der Beispiel-App ab oder baut sie mit – für raum. zählen nur die Bibliotheken
./scripts/build/build_examples.py --build-profile release --target android-arm64-chip-tool build || true

OUT=out/android-arm64-chip-tool-release/lib
STRIP=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip
mkdir -p "$RAUM/app/libs/matter" "$RAUM/app/src/main/jniLibs/arm64-v8a"
for j in CHIPController CHIPInteractionModel OnboardingPayload libMatterTlv CHIPClusters CHIPClusterID; do  # libMatterJson (braucht Gson) nicht nötig
  cp "$OUT/src/controller/java/$j.jar" "$RAUM/app/libs/matter/"
done
cp "$OUT/src/platform/android/AndroidPlatform.jar" "$RAUM/app/libs/matter/"
for so in libCHIPController.so libc++_shared.so; do
  "$STRIP" --strip-unneeded -o "$RAUM/app/src/main/jniLibs/arm64-v8a/$so" "$OUT/jni/arm64-v8a/$so"
done
# PAA-Stammzertifikate (Echtheitsprüfung beim Koppeln): vollständig aus dem CSA-Register,
# sonst Rückfall auf den (unvollständigen) Spiegel im SDK
if ! python3 "$RAUM/tools/update-paa.py"; then
  mkdir -p "$RAUM/app/src/main/assets/paa"
  cp credentials/production/paa-root-certs/*.der "$RAUM/app/src/main/assets/paa/"
fi
echo "Matter-SDK $TAG nach $RAUM kopiert."
