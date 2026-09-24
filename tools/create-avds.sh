#!/usr/bin/env bash
# Legt die Emulatorprofile aus Spez. 13.1 an: raum-dev (1920x1200, 8 GB) und raum-low (1280x800, 4 GB).
# Voraussetzung: Android SDK mit cmdline-tools; ANDROID_HOME gesetzt.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
ARCH="$(uname -m | sed 's/arm64/arm64-v8a/; s/x86_64/x86_64/')"

# AOSP-Images ohne Google Play Services (Spez. 3.3: Panels ohne GMS müssen voll unterstützt werden)
IMG_DEV="system-images;android-34;default;$ARCH"
IMG_LOW="system-images;android-30;default;$ARCH"

(yes || true) | "$SDKMANAGER" --install "$IMG_DEV" "$IMG_LOW" "emulator" "platform-tools"

create() {
  local name=$1 image=$2 width=$3 height=$4 ram=$5 density=$6 cores=$7
  echo no | "$AVDMANAGER" create avd --force --name "$name" --package "$image" --device "pixel_tablet"
  local cfg="$HOME/.android/avd/$name.avd/config.ini"
  sed -i.bak '/^hw.lcd.width=/d; /^hw.lcd.height=/d; /^hw.lcd.density=/d; /^hw.ramSize=/d; /^hw.cpu.ncore=/d; /^hw.initialOrientation=/d; /^hw.gpu.enabled=/d; /^hw.gpu.mode=/d; /^hw.sensors.proximity=/d; /^hw.sensors.light=/d' "$cfg"
  cat >> "$cfg" <<CFG
hw.lcd.width=$width
hw.lcd.height=$height
hw.lcd.density=$density
hw.ramSize=$ram
hw.cpu.ncore=$cores
hw.initialOrientation=landscape
hw.gpu.enabled=yes
hw.gpu.mode=host
hw.sensors.proximity=yes
hw.sensors.light=yes
CFG
  echo "✓ $name angelegt"
}

create raum-dev "$IMG_DEV" 1920 1200 8192 240 4
create raum-low "$IMG_LOW" 1280 800 4096 160 2
echo "Start: \$ANDROID_HOME/emulator/emulator -avd raum-dev"
