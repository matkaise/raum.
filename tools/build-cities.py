#!/usr/bin/env python3
"""Erzeugt app/src/main/assets/cities.dat (gzip; bewusst nicht .gz, AGP würde entpacken) aus GeoNames (CC BY 4.0, https://www.geonames.org).

Aufruf:  tools/build-cities.py cities5000.txt admin1CodesASCII.txt
Quelle:  https://download.geonames.org/export/dump/cities5000.zip, admin1CodesASCII.txt

Zeilenformat (Tab-getrennt):
  name  alternativen(|)  land(ISO)  region  breite  länge  zeitzone
Sortiert nach Einwohnerzahl (absteigend) – die Zeilennummer ist damit das Größenmaß.
Stadtteile (PPLX) und historische/aufgegebene Orte werden ausgelassen.
Alternativnamen nur in lateinischer Schrift (für die Suche, z. B. „München“ zu „Munich“).
"""
import gzip, os, sys, unicodedata

SKIP = {"PPLX", "PPLH", "PPLQ", "PPLW", "PPLCH"}

def latin(s):
    for ch in s:
        if ch in " -'’.()":
            continue
        try:
            if "LATIN" not in unicodedata.name(ch):
                return False
        except ValueError:
            return False
    return True

def norm(s):
    s = unicodedata.normalize("NFD", s.lower().replace("ß", "ss"))
    return "".join(c for c in s if not unicodedata.combining(c))

def key(s):
    """Wie die App-Suche: ohne Akzente, ae/oe/ue = a/o/u."""
    return norm(s).replace("ae", "a").replace("oe", "o").replace("ue", "u")

def main(cities, admin1):
    regions = {}
    for line in open(admin1, encoding="utf-8"):
        code, name, *_ = line.rstrip("\n").split("\t")
        regions[code] = name
    out = []
    for line in open(cities, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        name, alts, lat, lon, code, cc, a1, pop, tz = f[1], f[3], f[4], f[5], f[7], f[8], f[10], int(f[14] or 0), f[17]
        if code in SKIP:
            continue
        # je Schreibweise (ohne Akzente) die Variante mit den meisten Sonderzeichen: „München“ statt „Munchen“
        best = {}
        for a in alts.split(","):
            a = a.strip()
            if not a or any(c.isdigit() for c in a) or (a.isupper() and len(a) <= 4) or not latin(a) or a.startswith("Lungsod") or a.islower():
                continue
            n = key(a)
            if n == key(name):
                continue
            if n not in best or sum(ord(c) > 127 for c in a) > sum(ord(c) > 127 for c in best[n]):
                best[n] = a
        candidates = sorted(best.values())
        # Großstädte: alle Exonyme; kleine Orte: deutsche Schreibweisen zuerst (Mülhausen, Bozen …)
        if pop < 100_000:
            candidates.sort(key=lambda a: (not any(c in a for c in "äöüÄÖÜß"), len(a)))
            candidates = candidates[:3]
        keep = candidates
        out.append((pop, "\t".join([name, "|".join(keep), cc, regions.get(f"{cc}.{a1}", ""),
                                    f"{float(lat):.3f}", f"{float(lon):.3f}", tz])))
    out.sort(key=lambda t: -t[0])  # große Orte zuerst: frühes Abbrechen bei der Suche möglich
    dest = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "cities.dat")
    with gzip.open(dest, "wt", encoding="utf-8", compresslevel=9) as g:
        g.write("\n".join(line for _, line in out) + "\n")
    print(f"{len(out)} Orte → {os.path.getsize(dest) / 1e6:.2f} MB")

if __name__ == "__main__":
    main(*sys.argv[1:3])
