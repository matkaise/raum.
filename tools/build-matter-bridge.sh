#!/bin/bash
# Baut die raum. Bridge (Matter-Server, native/raum-bridge) als libRaumBridge.so für Android arm64
# und legt sie nach app/src/main/jniLibs/arm64-v8a. Braucht das SDK aus tools/build-matter-sdk.sh
# (gleiche Version, NDK, JDK 17, native zap auf Apple Silicon).
set -euo pipefail
SDK_DIR=${SDK_DIR:-$HOME/dev/connectedhomeip}
RAUM=$(cd "$(dirname "$0")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}
PROFILE=${PROFILE:-release}

cd "$SDK_DIR"
set +u; source scripts/activate.sh >/dev/null; set -u
if [ "$(uname -m)" = arm64 ] && [ -z "${ZAP_INSTALL_PATH:-}" ]; then
  eval "$(python3 scripts/tools/zap/zap_download.py --sdk-root . --zap RELEASE --extract-root "$HOME/dev/tools/zap" | grep '^export')"
fi

# Quellen in den SDK-Baum spiegeln – gleiche Struktur wie die Android-Beispiele
DEST=examples/raum-bridge/android
mkdir -p "$DEST/third_party"
rsync -a --delete --exclude build_overrides --exclude third_party "$RAUM/native/raum-bridge/" "$DEST/"
ln -sfn ../../build_overrides "$DEST/build_overrides"
ln -sfn ../../../../ "$DEST/third_party/connectedhomeip"

OUT=out/android-arm64-raum-bridge-$PROFILE
ARGS="target_os=\"android\" target_cpu=\"arm64\" android_ndk_root=\"$ANDROID_NDK_HOME\" android_sdk_root=\"$ANDROID_HOME\""
if [ "$PROFILE" = release ]; then ARGS="$ARGS is_debug=false"; fi
gn gen --check --fail-on-unused-args "$OUT" --root="$DEST" --args="$ARGS"
ninja -C "$OUT" jni

STRIP=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip
mkdir -p "$RAUM/app/src/main/jniLibs/arm64-v8a"
"$STRIP" --strip-unneeded -o "$RAUM/app/src/main/jniLibs/arm64-v8a/libRaumBridge.so" "$OUT/lib/jni/arm64-v8a/libRaumBridge.so"
echo "libRaumBridge.so nach $RAUM kopiert."
