#!/usr/bin/env bash
# Langzeit-/Dauerlasttest (Spez. 14.1, Abnahme Nr. 12).
#
# Lässt raum. auf einem Emulator oder Panel laufen, erzeugt Last (Navigation, Sensorwechsel,
# Mock-Simulation, Automationen) und protokolliert alle INTERVAL Sekunden:
#   Zeit, PID, Speicher (PSS/Java-Heap), Threads, Abstürze.
# Ergebnis: CSV + Zusammenfassung (Neustarts, Speicherwachstum).
#
# Nutzung:  tools/soak-test.sh <Minuten> [Intervall-Sekunden]
#   Abnahme: 10080 Minuten (7 Tage) auf dem T10 Pro.  Rauchtest: 10 Minuten.
set -uo pipefail

MINUTES=${1:-10}
INTERVAL=${2:-30}
PKG=app.raum.panel
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
OUT="soak-$(date +%Y%m%d-%H%M%S).csv"
IS_EMULATOR=$("$ADB" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')

# Nur sichere Ziele: Hauptnavigation. Koordinaten für 1920×1200 (Navigationsleiste links).
NAV_Y=(330 430 530 630 730 840)

echo "zeit,minute,pid,pss_kb,java_heap_kb,threads,abstuerze" > "$OUT"
"$ADB" logcat -c -b crash
START_PID=$("$ADB" shell pidof $PKG | tr -d '\r')
END=$(( $(date +%s) + MINUTES * 60 ))
i=0
echo "Soak-Test: $MINUTES min, Start-PID $START_PID, Ausgabe $OUT"

while [ "$(date +%s)" -lt "$END" ]; do
  i=$((i + 1))
  # Last: Bereich wechseln, gelegentlich Sensoren ändern (nur Emulator)
  "$ADB" shell input tap 87 "${NAV_Y[$((i % ${#NAV_Y[@]}))]}" >/dev/null 2>&1
  if [ "$IS_EMULATOR" = "1" ]; then
    "$ADB" emu sensor set light $(( (i * 137) % 1000 )) >/dev/null 2>&1
    if [ $((i % 4)) -eq 0 ]; then "$ADB" emu sensor set proximity 0 >/dev/null 2>&1; sleep 1; "$ADB" emu sensor set proximity 1 >/dev/null 2>&1; fi
  fi
  sleep "$INTERVAL"

  PID=$("$ADB" shell pidof $PKG | tr -d '\r')
  MEM=$("$ADB" shell dumpsys meminfo $PKG 2>/dev/null)
  PSS=$(echo "$MEM" | awk '/TOTAL PSS:/ {print $3; exit} /^ *TOTAL / {print $2; exit}')
  HEAP=$(echo "$MEM" | awk '/Java Heap:/ {print $3; exit}')
  THREADS=$("$ADB" shell "ls /proc/$PID/task 2>/dev/null | wc -l" | tr -d '\r ')
  CRASHES=$("$ADB" logcat -d -b crash | grep -c "FATAL EXCEPTION")
  echo "$(date +%H:%M:%S),$(( i * INTERVAL / 60 )),$PID,${PSS:-0},${HEAP:-0},${THREADS:-0},$CRASHES" | tee -a "$OUT"
done

echo
awk -F, -v start="$START_PID" 'NR==2 {first=$4} NR>1 {last=$4; if ($3!=start) restarts=1; if ($4>max) max=$4; crashes=$7}
  END {
    printf "Speicher PSS: Start %d KB, Ende %d KB, Maximum %d KB, Änderung %+d KB\n", first, last, max, last-first
    printf "Abstürze: %d · Prozess neu gestartet: %s\n", crashes, (restarts ? "ja" : "nein")
  }' "$OUT"
