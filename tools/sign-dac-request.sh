#!/bin/bash
# Spielt für Tests den PKI-Anbieter: signiert eine Zertifikatsanforderung (CSR) aus raum. mit dem Test-PAI aus
# tools/make-attestation.sh und verpackt ein Paket OHNE Schlüssel (dac.der, pai.der, cd.der) zum Import in raum.
#
#   tools/sign-dac-request.sh <anforderung.csr> <Ordner von make-attestation.sh>
#
# Der private Schlüssel bleibt dabei im Keystore des Panels – hier kommt nur der öffentliche Teil an.
set -euo pipefail
CSR=${1:?CSR-Datei}; PKI=${2:?Ordner mit pai-cert.pem, pai-key.pem, pai.der, cd.der}
for f in pai-cert.pem pai-key.pem pai.der cd.der; do [ -f "$PKI/$f" ] || { echo "$PKI/$f fehlt – zuerst tools/make-attestation.sh"; exit 1; }; done
openssl req -in "$CSR" -verify -noout >/dev/null
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
# Matter-Vorgaben für das DAC: CA:FALSE und nur „digitalSignature“, beide kritisch; SKID/AKID
cat > "$WORK/ext.cnf" <<'EOF'
[dac]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
EOF
SERIAL=0x$(openssl rand -hex 12)
openssl x509 -req -in "$CSR" -CA "$PKI/pai-cert.pem" -CAkey "$PKI/pai-key.pem" -set_serial "$SERIAL" -days 7300 -sha256 \
  -extfile "$WORK/ext.cnf" -extensions dac -outform DER -out "$WORK/dac.der" 2>/dev/null
cp "$PKI/pai.der" "$PKI/cd.der" "$WORK/"
OUT="$(pwd)/raum-bridge-certificate-$(basename "$CSR" .csr).zip"
rm -f "$OUT"; (cd "$WORK" && zip -q "$OUT" dac.der pai.der cd.der)
openssl x509 -inform DER -in "$WORK/dac.der" -noout -subject
echo "Paket ohne Schlüssel: $OUT"
