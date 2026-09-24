# raum. – Eigene Hersteller-ID und Echtheitszertifikate (Matter)

Bis zur Auslieferung nutzt raum. die **Test-Hersteller-ID 0xFFF1** der CSA und die **Testzertifikate des Matter-SDK**.
Damit funktioniert alles in Entwicklung und Test – Apple Home, Google Home und Alexa zeigen aber Warnungen oder lehnen
die Bridge ab. Für den Echtbetrieb braucht es eine eigene Hersteller-ID, eigene Echtheitszertifikate und eine
Matter-Zertifizierung. raum. ist darauf vorbereitet: der Wechsel ist Konfiguration plus ein Zertifikatsimport je Panel.

## Was raum. schon kann

| Baustein | Umsetzung |
|---|---|
| Hersteller-/Produkt-ID | `gradle.properties`: `raumVendorId`, `raumVendorName`, `raumBridgeProductId`, `raumBridgeProductName` (Standard: 0xFFF1 / 0x8000). Gilt für den Controller (so erscheint raum. in der Liste „verbundene Apps“ anderer Geräte) und für die Bridge (BasicInformation, Kopplungscode, DNS-SD) |
| Echtheitsnachweis der Bridge | Eigenes Zertifikatspaket (DAC, PAI, Certification Declaration) je Panel; der private DAC-Schlüssel liegt **nur im Android-Keystore** (nicht exportierbar), signiert wird dort – er erreicht nie den nativen Speicher |
| Schlüssel im Panel erzeugen (empfohlen) | Einstellungen → Mit anderen Apps teilen → Bridge → „Schlüssel erzeugen und Anforderung speichern“ (PIN): P-256-Schlüssel entsteht im Android-Keystore (StrongBox, wenn vorhanden), nicht exportierbar; gespeichert wird nur die Zertifikatsanforderung (CSR, PKCS#10) mit Hersteller-/Produkt-ID. Anzeige „Anforderung vom … wartet auf ihr Zertifikat“ |
| Import | „Zertifikat importieren“ (PIN): Paket **ohne** Schlüssel (dac.der, pai.der, cd.der) zur offenen Anforderung – das DAC muss zum wartenden Schlüssel passen – oder Paket **mit** Schlüssel (zusätzlich dac-key.der). Prüft Kette DAC→PAI und die IDs; danach startet die Bridge neu, der alte Schlüssel wird entfernt. Ein bisher aktives Zertifikat bleibt bis dahin in Betrieb; verbundene Apps müssen nicht neu koppeln |
| Anzeige | „Echtheitsnachweis der Bridge“: Testzertifikat / eigenes Zertifikat / **fehlt** (eigene ID ohne passendes Zertifikat) |
| Werksreset | Zertifikate gehören zur Hardware und bleiben erhalten |
| Testwerkzeuge | `tools/make-attestation.sh <VID> <PID>` erzeugt Test-PAA, PAI, DAC und eine mit dem SDK-Testschlüssel signierte CD als Importpaket; `tools/sign-dac-request.sh <csr> <ordner>` spielt den PKI-Anbieter und signiert eine Anforderung aus raum. zu einem Paket ohne Schlüssel |

Getestet (2026-09-24, Emulator), jeweils mit Test-ID 0xFFF2/0x8001 und `chip-tool` **ohne**
`--bypass-attestation-verifier`, nur mit der Test-PAA als Wurzel – „AttestationVerification“ erfolgreich:
- Paket mit Schlüssel importiert (BasicInformation meldet 0xFFF2/0x8001),
- Schlüssel im Keystore erzeugt → Anforderung gespeichert (OpenSSL: „verify OK“) → mit `sign-dac-request.sh`
  signiert → Paket ohne Schlüssel importiert. Der private Schlüssel hat das Panel dabei nie verlassen.

## Weg zum Echtbetrieb

1. **CSA-Mitgliedschaft und Hersteller-ID.** Die Hersteller-ID (VID) vergibt die Connectivity Standards Alliance nur an
   Mitglieder (Firma, kostenpflichtig; Konditionen bei der CSA). Die VID gilt für alle Produkte des Herstellers.
2. **Produkt-IDs festlegen** – mindestens eine für die raum. Bridge (der Controller selbst braucht keine Zertifizierung).
3. **PKI.** Eine Produkt-Wurzel (PAA) von einem bei der CSA gelisteten PKI-Anbieter oder eine eigene, im
   Distributed Compliance Ledger (DCL) eingetragene PAA; darunter ein PAI je Produkt und ein **eigenes DAC je Panel**.
4. **Zertifizierung.** Prüfung in einem autorisierten Testlabor (ATL); danach stellt die CSA die Certification
   Declaration (CD) für VID/PID aus. Sie ersetzt die Test-CD im Paket.
5. **DCL-Einträge.** Hersteller, Modell (VID/PID, Name, Kopplungsablauf) und Konformität – daran prüfen Apple, Google
   und Amazon die Echtheit.
6. **Build.** In `gradle.properties` die IDs eintragen, bauen, signieren (siehe OPERATIONS.md, Updates).
7. **Inbetriebnahme je Panel.** Empfohlen: in raum. „Schlüssel erzeugen und Anforderung speichern“, die CSR an den
   PKI-Anbieter geben, das zurückgelieferte Paket (dac.der, pai.der, cd.der) importieren. Alternativ ein Paket mit
   Schlüssel (dac-key.der, PKCS#8) importieren und danach löschen. Die Anzeige muss „eigenes Zertifikat“ zeigen.
8. **Ökosysteme.** Mit zertifiziertem Gerät keine Warnung in Apple Home; Google Home: Projekt in der Google Home
   Developer Console mit VID/PID anlegen; Alexa/SmartThings nehmen zertifizierte Geräte ohne Weiteres auf.

## Hinweise

- **Wechsel der Controller-ID** betrifft nur neue Kopplungen; bereits gekoppelte Geräte führen raum. weiter unter der
  alten ID (reine Anzeige in anderen Apps).
- **Schlüssel im Panel** ist der sichere Weg: der Schlüssel existiert nie außerhalb des Geräts. Pakete mit Schlüssel
  nur, wenn der PKI-Anbieter keine CSR annimmt.
- **Test-IDs** 0xFFF1–0xFFF4 sind für Entwicklung reserviert; Pakete aus `make-attestation.sh` taugen nur dafür (Test-CD).

```bash
tools/make-attestation.sh FFF2 8001 /pfad/zum/ausgabeordner
```
