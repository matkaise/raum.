# raum. – Betrieb, Updates und Recovery (M7)

Dieses Dokument beschreibt, wie ein raum.-Panel gesichert, aktualisiert, zurückgesetzt und im Fehlerfall
wiederhergestellt wird. Grundlage: Spezifikation 7.10, 7.11, 11.4 und offene Entscheidung 6.

## 0. Erstinbetriebnahme (ONB-001 – ONB-006)

Beim ersten Start – und nach „Vollständig zurücksetzen“ oder „Übergabe vorbereiten“ – führt raum. durch die Einrichtung:

1. **Willkommen:** Sprache wählen (Deutsch, Englisch, Systemsprache); „Neu einrichten“ oder „Aus Sicherung wiederherstellen“.
2. **Zuhause** (nur neu): Name; leer = „Zuhause“. Nach einer Übergabe ist der bisherige Name vorgeschlagen.
   **Sicherung** (nur Wiederherstellung): Passwort, Datei wählen, Vorschau mit Warnungen, übernehmen.
3. **Region:** Zeitzone (nur als Device Owner änderbar, sonst gilt die Android-Einstellung), °C/°F, optional Standort
   über die Offline-Ortssuche (Vorschläge aus der eingestellten Zeitzone; liegt der Ort in einer anderen Zone, bietet
   raum. an, sie zu übernehmen).
4. **Administrator-PIN:** festlegen (empfohlen) oder bewusst überspringen – siehe [SECURITY.md](SECURITY.md#bewusste-abweichung-administrator-pin-optional).
5. **Systemprüfung:** Netzwerk (LAN/WLAN), Bluetooth, Kioskbetrieb, Uhrzeit, Sensoren. Hinweise blockieren nicht.
6. **Matter:** eigene Fabric wird angelegt (Mock: virtuelle Fabric-ID, dauerhaft gespeichert).
7. **Fertig:** „Erstes Gerät hinzufügen“ (öffnet direkt den Dialog), „Später“ oder – nur Mock, nicht nach Übergabe –
   „Mit Beispielwohnung ausprobieren“.

Der Fortschritt wird gespeichert: Nach Stromausfall oder Absturz geht es beim unterbrochenen Schritt weiter, eine
bereits übernommene Sicherung wird nicht erneut abgefragt. Bestehende Installationen (Zuhause vorhanden, keine
laufende Einrichtung) überspringen die Einrichtung beim Update automatisch.

## 0. Echte Matter-Geräte (M2)

**Voraussetzungen:** Panel im selben Netz wie die Geräte (LAN/WLAN mit IPv6), für Thread-Geräte ein Thread Border
Router (z. B. HomePod mini, Apple TV 4K, Nest Hub 2, Echo 4). Betriebsart „Echte Geräte (Matter)“.

**Gerät, das schon in einer anderen App ist (empfohlen für den Start):** In der anderen App ein Kopplungsfenster
öffnen – etwa Google Home: Gerät → Einstellungen → „Verknüpfte Matter-Apps und -Dienste“ → „App verknüpfen“;
Alexa: Geräteeinstellungen → „Andere Assistenten und Apps“; Home Assistant: Gerät → „Gerät teilen“. Den angezeigten
11-stelligen Code in raum. unter Geräte → Gerät hinzufügen eingeben. raum. koppelt über das Netzwerk – ohne
Bluetooth und ohne Thread-Zugangsdaten.

**Neues Gerät** (Erstkopplung per Bluetooth und Thread-/WLAN-Zugangsdaten): folgt mit M6.

**Echtheitsprüfung:** raum. prüft Geräte gegen die mitgelieferten Stammzertifikate der Hersteller (PAA, derzeit 74,
vollständige Liste aus dem CSA-Verzeichnis DCL). Fehlt ein Hersteller, meldet raum. „Echtheit nicht bestätigt“ und
bietet „Trotzdem hinzufügen“ an (im Protokoll vermerkt). PAA-Liste aktualisieren: `python3 tools/update-paa.py`
(lädt von `on.dcl.csa-iot.org`; `tools/build-matter-sdk.sh` ruft es auf und nimmt sonst den SDK-Spiegel).

**Selbstheilung:** Bricht das Koppeln nach dem Aufnehmen in die Fabric ab (App beendet, Zeitüberschreitung), übernimmt
raum. das Gerät trotzdem: Es sucht per mDNS (`_matter._tcp`) nach Knoten der eigenen Fabric, die raum. noch nicht kennt,
und nimmt sie auf (Name aus BasicInformation). Erneutes Koppeln meldet dann „Gerät ist schon in raum.“ statt eines
Echtheitsfehlers.

**Hinweise:** Batteriebetriebene Thread-Sensoren schlafen und melden sich teils nur alle paar Minuten; raum. zeigt bis
dahin die zuletzt bekannten Werte (auch nach Neustart) und meldet ein Gerät erst nach 10 Minuten Funkstille als offline.
Nicht erreichbare Geräte werden jede Minute erneut verbunden. „Funkstille“ heißt: kein Bericht **und** kein bestehendes
Abo – Geräte ohne Wertänderung (z. B. ein Shelly-Schalter, der nicht betätigt wird) senden nur leere Lebenszeichen, die
das Matter-SDK selbst überwacht; solange das Abo steht, gelten sie als erreichbar.

**Getestet (2026-09-23, Emulator):** IKEA TIMMERFLOTTE (Thread, Temperatur/Feuchte/Batterie) und Feller Roomthermostat
über Apple Border Router, beide per Multi-Admin-Code aus einer anderen App, Echtheitsprüfung bestanden.

**Virtuelle Testgeräte auf dem Mac:** Im SDK (`source scripts/activate.sh`) bauen, z. B.
`./scripts/build/build_examples.py --target darwin-arm64-all-devices-temperature-sensor build` (weitere Ziele:
`darwin-arm64-light`, `darwin-arm64-contact-sensor`), dann `all-devices-app --device temperature-sensor` starten und in raum. den Code `34970112332` eingeben (Test-PAA:
„Trotzdem hinzufügen“). Vor dem Beenden in raum. „Aus raum. entfernen“. Test-Hersteller-ID ist 0xFFF1 – vor Auslieferung durch eine eigene ersetzen
(`ChipMatterController.VENDOR_ID`).

## 0a. Netzwerk

**Wo:** Einstellungen → Netzwerk. Zeigt LAN/WLAN, IP-Adresse und verfügbare WLANs.

- Als Device Owner (Kioskbetrieb) verbindet raum. direkt: Netz antippen, Passwort eingeben (WPA2/WPA3; offene Netze
  mit Warnhinweis). Firmen-WLAN (802.1X) und WEP werden nicht unterstützt. „Vergessen“ entfernt das aktuelle Netz.
- Ohne Device Owner öffnet der Knopf das WLAN-Panel von Android.
- Android 11/12 listet WLANs nur bei eingeschaltetem Standortdienst; raum. bietet an, ihn einzuschalten (es wird
  nichts geortet). Ab Android 13 ist das nicht nötig.
- Empfehlung für den Dauerbetrieb bleibt LAN (PoE).

## 0a2. Thread und neue Geräte (M6)

**Wo:** Einstellungen → Matter und Thread (Thread-Netz, Border Router, Diagnose, neue Geräte) und in der Einrichtung
beim Schritt „Matter-Fabric“. Änderungen an Zugangsdaten sind PIN-geschützt, falls eine PIN gesetzt ist.

- **Zwei Wege für Thread-Geräte:**
  1. *Gerät ist schon in Apple Home, Google Home oder Alexa* → dort ein Kopplungsfenster öffnen und den Code in raum.
     eingeben (siehe Abschnitt 0). Braucht keine Thread-Zugangsdaten. **Empfohlen bei Apple-/Google-/Amazon-Netzen**,
     denn deren Border Router geben die Zugangsdaten nicht heraus.
  2. *Neues Gerät direkt mit raum.* → per Bluetooth. Dafür braucht raum. das **Thread Operational Dataset** des Netzes:
     - von einem **OpenThread Border Router** (auch Home-Assistant-Add-on) mit REST-API: in der Border-Router-Liste
       „Zugangsdaten übernehmen“ (raum. holt `GET http://<router>:8081/node/dataset/active`, nur im lokalen Netz);
     - oder als Hex-Text eingeben („Zugangsdaten eingeben“), z. B. aus `ot-ctl dataset active -x` oder Home Assistant.
- **WLAN-Geräte (Matter over Wi-Fi):** unter „Neue Geräte koppeln“ das WLAN für neue Geräte festlegen (meist 2,4 GHz).
  raum. gibt es nur beim Koppeln weiter; das Panel-WLAN selbst bleibt unberührt.
- **Beim Koppeln** sucht raum. gleichzeitig per Bluetooth und im Netzwerk. Es fragt das Gerät, ob es Thread oder
  WLAN spricht, und reicht nur die passenden Zugangsdaten nach. Findet sich innerhalb von ~30 s kein Gerät, meldet raum.
  „nicht gefunden“ (vorher: Zeitüberschreitung nach Minuten).
- **Bluetooth:** Als Geräteeigentümer erteilt sich raum. die Berechtigungen selbst und schaltet Bluetooth bei Bedarf ein;
  sonst fragt Android nach. Unter Android 11 braucht die Bluetooth-Suche den Standortdienst (raum. ortet nichts).
- **Diagnose:** gefundene Border Router (aktiv/inaktiv, welches Netz), wie viele Thread-Geräte erreichbar sind, in
  welchem Netz sie hängen, ob das Panel IPv6 im Heimnetz hat. Je Gerät zeigt die Detailansicht „Verbindung“, etwa
  „Thread · schlafendes Endgerät · Netz „…“ · Kanal 25“ oder „WLAN“. Sind alle Thread-Geräte offline und kein Border
  Router aktiv, zeigt die Übersicht „Thread Border Router nicht erreichbar“.
- **Fehlerbilder beim Koppeln:** kein Thread-Netz hinterlegt / kein WLAN hinterlegt (Knopf „Einrichten“ führt direkt
  hierher), Beitritt ins Thread-Netz bzw. WLAN fehlgeschlagen (Dataset veraltet, Passwort, 2,4 GHz), Gerät im Netz aber
  nicht erreichbar (Border Router, IPv6), nicht gefunden und Bluetooth nicht verfügbar.
- **Simulation:** Codes `3333-171-2336` (neuer Thread-Sensor, braucht ein hinterlegtes Dataset) und `3169-331-2339`
  (neue WLAN-Steckdose, braucht ein WLAN für neue Geräte). Die Beispielwohnung zeigt Sensoren und Leuchten als
  Thread-Geräte im Netz „raum-demo“.
- **Werksreset und Übergabe** löschen Dataset und WLAN-Zugangsdaten.
- **Referenz-OTBR (Spez. 8.3):** ot-br-posix mit aktivierter REST-API (Port 8081) bzw. das Home-Assistant-Add-on
  „OpenThread Border Router“. Getestet ist bisher die Suche und Diagnose mit einem Apple Border Router; die Übernahme per
  REST und die Bluetooth-Erstkopplung stehen mit echter Hardware noch aus (Emulator hat kein echtes Bluetooth).

## 0b. Wetter (freiwillig)

**Wo:** Einstellungen → Wetter (oder in der Einrichtung beim Standort). Braucht einen Standort und Internet.

- Quelle: MET Norway (api.met.no), Lizenz CC BY 4.0 – Namensnennung steht in Einstellungen → Wetter und → Über raum.
- Die Übersicht zeigt Außentemperatur, Wetterlage, Tageshöchst/-tiefst und einen Hinweis wie „Regen ab 15:00 Uhr“;
  der Himmel ist animiert (Wolken, Regen, Schnee, Nebel, Gewitter). Die Animationen laufen nur bei aktivem Display
  und stehen, wenn in Android „Animationen entfernen“ aktiv ist.
- **Außensensor:** Ein Matter-Temperatursensor kann als „Außen“ gewählt werden – dann stammt die Außentemperatur
  vom Sensor (auch ohne Internet) und er zählt nicht mehr zur Innentemperatur.
- **Vor Auslieferung:** `raumWeatherContact` in `gradle.properties` setzen (siehe SECURITY.md).
- Fehlerbilder: „keine Verbindung“ (Netz), „vom Dienst abgelehnt“ (Kennung/Kontakt fehlt), „zu viele Anfragen“
  (raum. wartet automatisch länger). Im Debug-Build gibt es unter Einstellungen → Wetter eine Vorschau aller Lagen.

## 0c. Mit anderen Apps teilen (Matter Multi-Admin)

**Wo:** Einstellungen → Mit anderen Apps teilen. Ist eine Administrator-PIN gesetzt, wird sie abgefragt.

- Jedes Matter-Gerät kann zusätzlich in Apple Home, Google Home, Amazon Alexa, SmartThings u. a. sein; raum. bleibt
  voll funktionsfähig. raum. öffnet am Gerät ein Kopplungsfenster (15 min) und zeigt QR-Code und 11-stelligen Code;
  in der anderen App „Gerät hinzufügen“ (Google/Alexa: „Matter-Gerät“) wählen und scannen.
- „Alle nacheinander teilen“ führt Gerät für Gerät durch; raum. erkennt die Kopplung und bietet das nächste an.
- Unter jedem Gerät stehen die verbundenen Apps; das × entzieht einer App den Zugriff (raum. selbst ist ausgenommen).
- Voraussetzungen der anderen Apps: meist eine eigene Steuerzentrale (Apple TV/HomePod, Nest Hub, Echo); für
  Thread-Geräte muss diese Thread unterstützen. Pro Gerät sind mindestens 5 Apps möglich.
- Im Modus „Echte Geräte“ öffnet raum. das Kopplungsfenster am echten Gerät (Matter-SDK).

**Alles auf einmal – raum. als Bridge:** Schalter „Alles auf einmal (Bridge)“, dann „Mit einer App verbinden“:
ein QR-Code für das ganze Panel. Die andere App findet alle freigegebenen Geräte (Namen werden übertragen, Räume
fragt sie selbst ab); neue, umbenannte oder entfernte Geräte übernimmt sie automatisch.

| | Bridge | Direkt je Gerät |
|---|---|---|
| Aufwand | ein Code | ein Code je Gerät |
| Panel aus/offline | Geräte in der anderen App **nicht** steuerbar | weiter steuerbar |
| Thread-Geräte | auch ohne Thread-Zentrale der anderen App | nur mit Thread-Zentrale |
| Auswahl | „Geräte auswählen“ (neue automatisch dabei) | je Gerät |

**Echte Bridge (seit 2026-09-24):** Im Modus „Echte Geräte“ läuft die Bridge als eigener Matter-Server im
Android-Prozess `:bridge` (`libRaumBridge.so`, Quellen in `native/raum-bridge`, Bau mit `tools/build-matter-bridge.sh`).
Sie ist ein eigenes Matter-Gerät „raum. Bridge“ (Aggregator) mit eigener, verschlüsselt gespeicherter Identität und
kündigt sich per mDNS im LAN an (UDP/TCP 5540). Jedes freigegebene Gerät erscheint als „Bridged Node“ mit dauerhaft
gleichem Endpunkt, Name, Hersteller und Erreichbarkeit.

- **Unterstützt:** Leuchten (ein/aus, dimmbar), Steckdosen/Schalter, Kontakt-, Präsenz-, Temperatur- und
  Feuchtesensoren, **Thermostate** (Ist-Temperatur, Sollwert, Betriebsart Aus/Heizen/Kühlen/Auto je nach Gerät,
  „Sollwert rauf/runter“; Grenzen aus raum., Werte außerhalb werden abgelehnt) und **Storen/Rollläden** (Position,
  läuft/steht, Öffnen, Schließen, Stopp, auf Position fahren). Thermostat und Storen sind eigene, schlanke Cluster
  (`native/raum-bridge/src/RaumClusters.cpp`), weil das SDK 1.6 sie für das code-basierte Datenmodell nicht mitbringt.
  **Farbleuchten** erscheinen als „Extended Color Light“ (Farbton/Sättigung, Farbort xy, Farbtemperatur),
  **Weißtonleuchten** als „Color Temperature Light“ (Farbtemperatur im Bereich des Geräts). raum. speichert Farben als
  RGB und rechnet um (sRGB/D65 ↔ xy, Farbton/Sättigung, Kelvin ↔ Mired); Übergangszeiten übernimmt das Gerät.
  Farbbefehle an reine Weißtonleuchten lehnt die Bridge ab (`UNSUPPORTED_COMMAND`).
- Jedes Kopplungsfenster bekommt einen neuen, einmaligen Code (15 min, nur über das Netzwerk, kein Bluetooth).
- Getestet im Emulator mit `chip-tool` über eine Portweiterleitung: Kopplung, Geräte- und Werteliste (IKEA-Sensor,
  Shelly), Ein/Aus und Dimmen aus der anderen App bis zum Gerät, Rückmeldung von Änderungen in raum., neue Geräte
  ohne Neukopplung, Entfernen einer App. Der Emulator ist hinter einer NAT – Apple Home/Google Home erreichen ihn nicht;
  der Test mit diesen Apps braucht ein Android-Gerät im Heimnetz (bzw. das T10 Pro).
- **Befehle ohne echte Geräte testen (nur Debug-Build):** `adb shell run-as app.raum.panel touch files/bridge-real-in-simulation`,
  dann Betriebsart „Simulation“ – die echte Bridge zeigt die simulierten Geräte der Beispielwohnung (Thermostate, Storen
  bewegen sich simuliert). Danach die Datei löschen und zurück auf „Echte Geräte“. Getestet: Sollwert setzen und
  rauf/runter, Betriebsart, Grenzen (`CONSTRAINT_ERROR`), Storen auf Position mit laufender Positions- und
  Statusmeldung, Schließen, Stopp; Farbe per Farbton/Sättigung und per xy, Farbtemperatur; Änderungen in raum.
  (z. B. Farbe gewählt) erscheinen in der Bridge.
- **Emulator-Test nachstellen:** `adb emu redir add udp:5540:5540`; nach der Kopplung die Betriebsadresse auf dem Mac
  ankündigen (`dns-sd -P <FabricID>-<NodeID> _matter._tcp local 5540 raum-bridge-test.local 127.0.0.1`), dann
  `chip-tool pairing already-discovered <node> <PIN aus dem Code> 127.0.0.1 5540 --bypass-attestation-verifier true`.

Dasselbe Gerät nicht mit derselben App direkt **und** über die Bridge teilen – es erscheint dort sonst doppelt
(raum. warnt). Befehle anderer Apps stehen im Protokoll („Befehl über Apple Home“).

**Vor dem Echtbetrieb:** Ohne Konfiguration nutzt die Bridge die Test-Hersteller-ID 0xFFF1/Produkt 0x8000 und die
Test-Echtheitszertifikate des SDK; eigene IDs und Zertifikate: siehe [VENDOR.md](VENDOR.md). Apple Home zeigt damit „nicht zertifiziertes Gerät“ und bietet „Trotzdem hinzufügen“; Google Home verlangt die
Registrierung der Test-IDs in der Google-Home-Developer-Console; Alexa und SmartThings nehmen Testgeräte nicht zuverlässig
auf. Für den Echtbetrieb: eigene CSA-Hersteller-ID, eigenes Echtheitszertifikat (DAC/PAI) und Matter-Zertifizierung.

## 1. Sicherung und Wiederherstellung

**Wo:** Einstellungen → Sicherheit und Wartung → Wartungsmodus (PIN) → Sicherung.

| | |
|---|---|
| Inhalt | Zuhause, Räume, Geräte (Name, Raum, Favorit), Szenen, Automationen, Einstellungen (Design, Display, Standort) |
| Nicht enthalten | Administrator-PIN, Ereignisprotokoll, Matter-Fabric-Schlüssel |
| Verschlüsselung | AES-256-GCM, Schlüssel aus dem Sicherungspasswort (mind. 8 Zeichen) |
| Ziel | Systemdialog zur Dateiauswahl: USB-Stick, interner Speicher oder installierte Netzwerk-Anbieter (SMB, WebDAV …) |
| Format | `raum-sicherung-JJJJ-MM-TT.raumbak` |

Vor dem Wiederherstellen zeigt raum. eine Vorschau (Datum, Version, Umfang) und prüft die Kompatibilität (BAK-004):

- Sicherungen aus **neueren** raum.-Versionen werden abgelehnt → zuerst raum. aktualisieren.
- Verweise auf fehlende Geräte/Räume/Szenen werden bereinigt und als Hinweis angezeigt; betroffene Automationen
  werden deaktiviert.
- **Fabric (BAK-005/006):** Die Sicherung enthält keine Matter-Schlüssel. Auf **demselben** Panel bleiben die Geräte
  verbunden. Auf einem **Ersatzpanel** erscheinen sie offline und müssen per Setup-Code neu hinzugefügt werden – die
  Vorschau weist darauf hin. Ob Fabric-Credentials sicher exportierbar sind, wird mit dem Matter-SDK (M2) entschieden.

## 2. Automatische Wiederherstellungspunkte

raum. legt selbst Kopien der Datenbank an (`noBackupFilesDir/recovery/`, die letzten 3 werden behalten):

- **vor jeder Datenbank-Migration** (App-Update mit neuem Schema) – vor dem ersten Öffnen,
- **vor jeder Update-Installation** über den Wartungsmodus.

Die Kopien sind konsistent (`VACUUM INTO`) und unverschlüsselt im App-privaten Speicher; sie werden beim
vollständigen Zurücksetzen gelöscht. Zurückspielen ist ein Technikerschritt (siehe Abschnitt 5).

## 3. Updates (Entscheidung: lokale Datei)

**Wo:** Wartungsmodus → Update → Update-Datei auswählen (APK von USB).

Prüfungen vor der Installation:

1. Paketname muss `app.raum.panel` sein.
2. Signaturzertifikat muss **identisch** mit der installierten Version sein (SHA-256-Vergleich). Fremd signierte oder
   Debug-APKs auf einem Release-Panel werden abgelehnt.
3. Versionscode muss **höher** sein. Android erlaubt kein Downgrade mit Datenerhalt.
4. Android prüft beim Installieren zusätzlich die APK-Integrität.

Ablauf: Wiederherstellungspunkt → Installation (als Device Owner ohne Rückfrage) → Android startet raum. neu
(`MY_PACKAGE_REPLACED` → Kerndienst). Das Ergebnis steht im Protokoll.

**Release erstellen:** `keystore.properties` im Projektverzeichnis anlegen (nicht einchecken):

```properties
storeFile=../keys/raum-release.jks
storePassword=…
keyAlias=raum
keyPassword=…
```

Danach `./gradlew :app:assembleRelease`. `versionCode` bei jedem Release erhöhen.

## 4. Zurücksetzen und Wohnungsübergabe

**Wo:** Wartungsmodus → Zurücksetzen. Beide Varianten erklären vorab getrennt, was mit der Matter-Fabric und was mit
den Daten passiert (Spez. 11.3), und verlangen danach die PIN erneut (RST-003). Ist keine PIN festgelegt, folgt
stattdessen eine zweite, ausdrückliche Bestätigung. Danach startet die Einrichtung (Abschnitt 0).

| | Übergabe vorbereiten (RST-005) | Vollständig zurücksetzen (RST-001/002) |
|---|---|---|
| Matter-Fabric | aufgelöst | aufgelöst |
| Geräte, Szenen, Automationen, Protokoll, PIN | gelöscht | gelöscht |
| Zuhause-Name, Räume | bleiben | gelöscht |
| Display-, Standort-, Design-Einstellungen | bleiben | gelöscht |
| Wiederherstellungspunkte | bleiben | gelöscht |
| Kiosk/Device Owner | bleibt | bleibt |
| Einrichtung | läuft erneut (Name vorgeschlagen) | läuft erneut |
| Zugriff anderer Apps (Apple Home …) | **entfernt** (erreichbare Geräte) | bleibt |
| Bridge | aus, alle Kopplungen gelöscht | aus, alle Kopplungen gelöscht |

Gelöschte Datenbankinhalte werden überschrieben (`secure_delete`, WAL-Checkpoint, `VACUUM`).

**Übergabeprotokoll (RST-004):** Wartungsmodus → Export. Enthält Panel, Softwarestand und je Raum die verbauten
Gerätetypen (Hersteller/Modell) – keine Gerätenamen, Szenen, Automationen, Standort oder Nutzungsdaten.

Empfohlene Reihenfolge bei Auszug: Sicherung erstellen → Übergabeprotokoll exportieren → „Übergabe vorbereiten“ →
neue Bewohner durchlaufen die Einrichtung, legen ihre eigene PIN fest und fügen die Geräte per Setup-Code hinzu.

## 5. Recovery-Szenarien

| Situation | Vorgehen |
|---|---|
| App stürzt ab | Automatisch: Neustart durch das System (Foreground Service, `START_STICKY`, Launcher). Ursache steht im Protokoll („Neustart nach Absturz …“). |
| Stromausfall | Automatisch: Autostart nach dem Booten. Unterbrochene Automationen mit Wartezeit sind im Protokoll als „Gestartet“ ohne „Ausgeführt“ sichtbar. |
| Konfiguration versehentlich zerstört | Sicherung wiederherstellen. |
| Update mit fehlerhafter Migration | Korrigierte, **höhere** Version installieren; bei Datenverlust Sicherung oder Wiederherstellungspunkt zurückspielen. |
| Wiederherstellungspunkt zurückspielen (Techniker) | ADB im Wartungsmodus aktivieren; Kopie aus `recovery/` als `databases/raum.db` einsetzen (nur mit debugfähigem Service-Build oder über ein Service-Werkzeug); `-wal`/`-shm` löschen; raum. neu starten. |
| Panel defekt, Ersatzgerät | Ersatzpanel als Device Owner einrichten → Sicherung wiederherstellen → Geräte per Setup-Code neu hinzufügen (Fabric-Schlüssel nicht in der Sicherung). |
| PIN vergessen | Kein Hintertür-Mechanismus. Werksreset des Android-Geräts (entfernt auch den Device-Owner-Status) und Neueinrichtung; vorher erstellte Sicherung einspielen. |

## 6. Diagnose

**Diagnosepaket (LOG-004):** Wartungsmodus → Export. Textdatei mit Version, Gerät, Kiosk-Status, Gerätezuständen
(ohne Namen), Zählungen und den letzten 2 000 Protokolleinträgen. Enthält keine PIN, Passwörter oder Schlüssel.

## 7. Langzeittest (Abnahme Nr. 12)

```bash
tools/soak-test.sh 10080 60   # 7 Tage, Messung jede Minute
```

Erzeugt Last (Navigation, Sensorwechsel auf dem Emulator, Mock-Simulation mit Automationen) und schreibt eine CSV mit
PID, Speicher (PSS, Java-Heap), Threads und Abstürzen. Abnahmekriterium: keine Abstürze, kein Prozessneustart,
kein stetiges Speicherwachstum. Der 7-Tage-Lauf muss auf dem T10 Pro erfolgen.
