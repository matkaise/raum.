#!/usr/bin/env python3
"""Lädt alle freigegebenen PAA-Stammzertifikate aus dem offiziellen CSA-Register (DCL, MainNet)
nach app/src/main/assets/paa/ (DER). raum. prüft damit die Echtheit von Matter-Geräten lokal.

Aufruf: tools/update-paa.py            (ersetzt den Inhalt von assets/paa)
Quelle: https://on.dcl.csa-iot.org/dcl/pki/root-certificates
"""
import base64, json, os, re, sys, urllib.parse, urllib.request

DCL = "https://on.dcl.csa-iot.org"
DEST = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "paa")

def get(path):
    with urllib.request.urlopen(DCL + path, timeout=30) as r:
        return json.load(r)

def main():
    roots = get("/dcl/pki/root-certificates")["approvedRootCertificates"]["certs"]
    certs = {}
    for r in roots:
        subj = urllib.parse.quote(r["subject"], safe="")
        skid = urllib.parse.quote(r["subjectKeyId"], safe="")
        try:
            entries = get(f"/dcl/pki/certificates/{subj}/{skid}")["approvedCertificates"]["certs"]
        except Exception as e:
            print(f"  übersprungen {r['subjectKeyId']}: {e}", file=sys.stderr); continue
        for e in entries:
            if not e.get("isRoot", True):
                continue
            pem = e["pemCert"]
            der = base64.b64decode("".join(l for l in pem.strip().splitlines() if "-----" not in l))
            name = re.sub(r"[^A-Za-z0-9]+", "_", e.get("subjectAsText", "paa"))[:60].strip("_")
            vid = e.get("vid")
            key = r["subjectKeyId"].replace(":", "")
            suffix = f"_vid_0x{vid:04X}" if vid and f"vid_0x{vid:04X}".lower() not in name.lower() else ""
            certs[key] = (f"{name}{suffix}_{key[:8]}.der", der)
    if not certs:
        sys.exit("Keine Zertifikate erhalten – Abbruch, bestehende Liste bleibt.")
    os.makedirs(DEST, exist_ok=True)
    for f in os.listdir(DEST):
        if f.endswith(".der"):
            os.remove(os.path.join(DEST, f))
    for fname, der in certs.values():
        open(os.path.join(DEST, fname), "wb").write(der)
    print(f"{len(certs)} PAA-Stammzertifikate nach {os.path.normpath(DEST)}")

if __name__ == "__main__":
    main()
