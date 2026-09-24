#!/bin/bash
# Erzeugt ein Echtheitspaket (Matter-Attestation) für die raum. Bridge: PAA → PAI → DAC plus Certification
# Declaration, verpackt als ZIP zum Import in raum. (Einstellungen → Mit anderen Apps teilen → Bridge).
#
#   tools/make-attestation.sh <VID> <PID> [Ausgabeordner] [PAA-Zertifikat PAA-Schlüssel]
#
# Ohne PAA wird eine Test-PAA erzeugt. Die Certification Declaration ist mit dem TEST-Schlüssel des SDK signiert –
# damit gilt das Paket nur für Entwicklung und Tests (chip-tool, Testgeräte). Für die Auslieferung stammen PAI/DAC
# von einer bei der CSA gelisteten PAA (PKI-Anbieter) und die CD von der CSA nach der Zertifizierung (docs/VENDOR.md).
# Jedes Panel braucht ein EIGENES DAC – das Skript je Gerät erneut aufrufen (PAA/PAI werden wiederverwendet).
set -euo pipefail
VID=${1:?Hersteller-ID, z. B. FFF2}; PID=${2:?Produkt-ID, z. B. 8001}
VID=${VID#0x}; VID=${VID#0X}; PID=${PID#0x}; PID=${PID#0X}
OUT=${3:-attestation-$VID-$PID}
SDK_DIR=${SDK_DIR:-$HOME/dev/connectedhomeip}
CC=$SDK_DIR/out/darwin-arm64-chip-cert/chip-cert
[ -x "$CC" ] || { echo "chip-cert fehlt: im SDK ./scripts/build/build_examples.py --target darwin-arm64-chip-cert build"; exit 1; }
mkdir -p "$OUT"; chmod 700 "$OUT"; cd "$OUT"
FROM=$(date -u +%Y-%m-%d)

# PAA – vorhandene verwenden (4./5. Argument) oder Test-PAA anlegen
if [ -n "${4:-}" ]; then
  cp "$4" paa-cert.pem; cp "$5" paa-key.pem
elif [ ! -f paa-cert.pem ]; then
  "$CC" gen-att-cert --type a --subject-cn "raum. Test PAA" --subject-vid "$VID" --valid-from "$FROM" --lifetime 7300 \
    --out paa-cert.pem --out-key paa-key.pem
fi
# PAI je Produkt (wird wiederverwendet)
if [ ! -f pai-cert.pem ]; then
  "$CC" gen-att-cert --type i --subject-cn "raum. PAI" --subject-vid "$VID" --subject-pid "$PID" --ca-cert paa-cert.pem --ca-key paa-key.pem \
    --valid-from "$FROM" --lifetime 7300 --out pai-cert.pem --out-key pai-key.pem
fi
# DAC je Gerät – eindeutige Kennung im Namen
SERIAL=$(openssl rand -hex 8)
"$CC" gen-att-cert --type d --subject-cn "raum. Bridge $SERIAL" --subject-vid "$VID" --subject-pid "$PID" --ca-cert pai-cert.pem --ca-key pai-key.pem \
  --valid-from "$FROM" --lifetime 7300 --out dac-cert.pem --out-key dac-key.pem
# Certification Declaration (Test-Signatur des SDK, Gerätetyp Aggregator 0x000E, Zertifizierungsart 0 = Entwicklung)
"$CC" gen-cd --key "$SDK_DIR/credentials/test/certification-declaration/Chip-Test-CD-Signing-Key.pem" \
  --cert "$SDK_DIR/credentials/test/certification-declaration/Chip-Test-CD-Signing-Cert.pem" \
  --out cd.der --format-version 1 --vendor-id "$VID" --product-id "$PID" --device-type-id 000E \
  --certificate-id ZIG20142ZB330003-24 --security-level 0 --security-info 0 --version-number 1 --certification-type 0

"$CC" convert-cert paa-cert.pem paa.der --x509-der
"$CC" convert-cert pai-cert.pem pai.der --x509-der
"$CC" convert-cert dac-cert.pem dac.der --x509-der
openssl pkcs8 -topk8 -nocrypt -in dac-key.pem -outform DER -out dac-key.der

BUNDLE="raum-bridge-attestation-$VID-$PID-$SERIAL.zip"
rm -f "$BUNDLE"; zip -q -j "$BUNDLE" dac.der pai.der cd.der dac-key.der
rm -f dac-key.der dac-key.pem   # der Schlüssel existiert danach nur noch im Paket
chmod 600 "$BUNDLE"
echo "Paket: $OUT/$BUNDLE  (nach dem Import in raum. löschen)"
echo "Test-PAA für chip-tool: $OUT/paa.der  (chip-tool … --paa-trust-store-path $OUT)"
