# raum. – Sicherheitsprüfung (M7)

Stand: 22.09.2026 · App 0.7.0 · DB-Schema v2 · Prüfung gegen Spezifikation Kapitel 11 und 12.4.

## Ergebnisübersicht

| Bereich | Anforderung | Umsetzung | Status |
|---|---|---|---|
| Local-first | 11.1, 12.4 | Keine Telemetrie, kein Konto; Ortssuche über eingebaute Liste statt Ortungsdienst. Einzige Internetverbindung: freiwilliges Wetter (ab Werk aus, siehe unten) | ⚠️ Abweichung (opt-in) |
| Teilen (Multi-Admin) | – | Kopplungsfenster nur auf Nutzeraktion, PIN-geschützt, 15 min gültig, wird beim Schließen des Dialogs widerrufen; neue Admins und Entzug im Protokoll; „Übergabe vorbereiten“ entfernt alle fremden Admins vor dem Auflösen der eigenen Fabric – je Gerät frisch gelesen und durch erneutes Lesen bestätigt; nicht bestätigte Geräte werden angezeigt, die eigenen Schlüssel nur nach ausdrücklicher Wahl („Unvollständig abschließen“) gelöscht | ✅ |
| Bridge | – | Einschalten und Koppeln PIN-geschützt; Kopplungsfenster nur auf Nutzeraktion (15 min); Befehle nur für freigegebene Geräte und nur von gekoppelten Apps, jeweils mit Quelle im Protokoll; über denselben Weg wie Bedienung am Panel (Offline-Schutz). Werksreset und Übergabe löschen alle Bridge-Kopplungen – durch Beenden des Bridge-Prozesses und direktes Löschen seines Speichers, auch bei ausgeschalteter Bridge; sonst bricht der Reset ab. Die echte Bridge ist ein Matter-Server im LAN (mDNS, UDP/TCP 5540) im eigenen Prozess `:bridge` – nur bei eingeschalteter Bridge; Zugriff nur für per Kopplung aufgenommene Apps (CASE, ACL je Fabric). Kopplungsfenster mit einmaligem Code je Öffnung, nur mDNS (kein BLE), nicht beim Start. Bridge-Identität (Fabrics anderer Apps, Betriebsschlüssel) in eigenem AES-GCM/Keystore-Speicher (`raum_bridge_kvs`). Echtheitsnachweis: DAC-Schlüssel wird im Android-Keystore des Panels erzeugt (StrongBox, wenn vorhanden; nur CSR verlässt das Gerät) oder – alternativ – mit Schlüssel importiert; Kette, IDs und Schlüsselzugehörigkeit geprüft, Signatur im Keystore; ohne eigenes: Test-DAC des SDK (0xFFF1/0x8000). Siehe docs/VENDOR.md | ✅ / ⚠️ Test-DAC bis zur Zertifizierung |
| Internet-Berechtigung | 11.1 | `INTERNET` nur für das Wetter (HTTPS zu api.met.no; `usesCleartextTraffic=false`) und später Matter über IP (M2) | ✅ |
| WLAN-Verwaltung | 11.1 | Verbinden/Schalten nur als Device Owner. Berechtigungen: `ACCESS/CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` (`neverForLocation`), `ACCESS_FINE/COARSE_LOCATION` – Letztere nur, weil Android Netznamen und Suchergebnisse sonst verbirgt; raum. fragt nie einen Standort ab. Als Device Owner erteilt raum. sich diese Berechtigungen selbst. WLAN-Passwörter speichert Android, nicht raum. | ✅ |
| Klartextverkehr | 11.1 | `usesCleartextTraffic="false"`. Einzige Ausnahme: das Dataset eines OpenThread Border Routers per HTTP (REST-API, Port 8081 – dort nur HTTP), über einen eigenen Minimal-Client, der **nur private/Link-lokale Adressen** (10/8, 172.16/12, 192.168/16, fe80::/10, fc00::/7) zulässt und nur auf ausdrückliche Nutzeraktion. Ansonsten fragt raum. dort nur `/node/state` ab (Erkennung) | ✅ (begrenzte Ausnahme) |
| Thread-Dataset und WLAN für Commissioning | 11.2, 10.3 | AES-256-GCM mit Schlüssel im Android Keystore (`raum_secrets_v1`, hardwaregestützt, sofern vorhanden); Schlüsselname als AAD. Auf dem Datenträger nur IV + Chiffrat; Netzname nur zur Anzeige aus dem entschlüsselten Dataset. Nie im Protokoll, nie angezeigt (nur Netzname, Kanal, PAN-IDs). Ändern PIN-geschützt; Werksreset und Übergabe löschen beides. Gerätetest prüft, dass kein Klartext gespeichert wird | ✅ |
| Bluetooth | 11.1 | `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`; bis Android 11 `BLUETOOTH`/`BLUETOOTH_ADMIN` + Standort (von Android für BLE-Suche verlangt). Suche nur während einer Kopplung (Matter-Dienst 0xFFF6, gefiltert nach Diskriminator) | ✅ |
| Android-Backup | 12.4 | `allowBackup=false`, `fullBackupContent=false`, `dataExtractionRules` schließen Cloud- und Gerät-zu-Gerät-Übertragung aus | ✅ |
| Google Play Services | 11.1 | Keine Abhängigkeit; läuft auf AOSP-Images | ✅ |
| Administrator-PIN | 11.2, 11.3 | PBKDF2-HMAC-SHA256, 120 000 Iterationen, 16-Byte-Salt; Vergleich in konstanter Zeit; Sperre nach 5 Fehlversuchen mit Verdopplung bis 15 min, übersteht Neustarts | ✅ |
| Wartungsmodus | 11.3, SYS-006 | Mit PIN: nur nach PIN. Ohne PIN: offen, mit dauerhaftem Warnhinweis (bewusste Abweichung, siehe unten). Automatische Sperre nach 5 min ohne Bedienung; kritische Aktionen mit zweiter Bestätigung | ⚠️ Abweichung |
| Kiosk | 11.3 | Device Owner, Lock Task, kein Keyguard, keine Statusleiste; nur raum. und der System-Dateidialog sind freigegeben | ✅ |
| Sicherungen | BAK-002 | AES-256-GCM, Schlüssel aus Passwort (PBKDF2, 310 000 Iterationen), Kopf authentisiert; ohne PIN und ohne Fabric-Schlüssel | ✅ |
| Werksreset | RST-001/002/003 | Fabric auflösen, Tabellen leeren, `secure_delete=ON`, WAL-Checkpoint + `VACUUM`, Einstellungen und PIN löschen. Bestätigung mit PIN – ohne PIN mit ausdrücklicher zweiter Bestätigung | ✅ / ⚠️ ohne PIN |
| Logs | LOG-003 | Protokoll enthält keine PIN, Passwörter oder Schlüssel; Diagnoseexport nur auf Nutzeraktion | ✅ |
| Updates | 11.4 | Nur gleicher Paketname, identisches Signaturzertifikat (SHA-256), höhere Version; Sicherungspunkt vor Installation | ✅ |
| Release-Build | 11.4 | R8-Minifizierung + Ressourcen-Shrinking; Signatur aus `keystore.properties` (nicht eingecheckt) | ✅ |
| ADB im Endkundenbetrieb | 11.1 | Schaltbar im Wartungsmodus (Device Owner). **Muss vor Auslieferung deaktiviert werden.** | ⚠️ Betriebsschritt |
| Matter-Credentials | 11.2 | Root-CA-Schlüssel, Betriebsschlüssel, Zertifikate und Sitzungsschlüssel des Matter-SDK liegen in `EncryptedKeyValueStore`: jeder Eintrag einzeln AES-256-GCM, Schlüssel nicht exportierbar im Android Keystore (`raum_matter_kvs_v1`, TEE/StrongBox, sofern vorhanden – Anzeige unter Einstellungen → Matter und Thread), Eintragsname als AAD. Bestehende Installationen werden beim Start übernommen (zurückgelesen und verglichen, erst dann Klartext gelöscht; abgebrochene Übernahme wird wiederholt, ohne neuere Werte zu überschreiben). Ist der Keystore nicht nutzbar, bleibt es beim bisherigen Speicher (Anzeige „unverschlüsselt“) – die Fabric geht nie verloren. Werksreset löscht alles. **Grenze:** Das SDK rechnet mit den Schlüsseln im Arbeitsspeicher; Signieren direkt im Keystore bräuchte einen eigenen Operational-Credentials-Issuer und eine neue Fabric (alle Geräte neu koppeln) – bewusst nicht umgesetzt | ✅ (verschlüsselt, Signatur im Prozess) |
| Echtheitsprüfung (Attestation) | 10.x | PAA-Stammzertifikate lokal (kein DCL-Abruf); Geräte ohne passende PAA nur nach ausdrücklicher Bestätigung, mit Protokolleintrag | ✅ |
| Matter im LAN | 11.1 | Im Modus „Echte Geräte“ lauscht raum. als Controller (UDP 5540, mDNS `_matter._tcp`). Test-Hersteller-ID 0xFFF1 | ⚠️ vor Auslieferung eigene VID |

## Android Lint

`./gradlew :app:lintDebug`: **0 Fehler**. Behoben wurden u. a. zwei API-Level-Fehler (Aufrufe ab API 31/33 ohne
Absicherung – hätten auf Android-11-Panels, z. B. dem T10 Pro mit aktueller Firmware, zum Absturz geführt) und
Activity-Casts aus `LocalContext`.

Bewusst beibehalten (mit Begründung im Code):

- `WakelockTimeout` – dauerhafter partieller WakeLock im Kerndienst; das Panel hängt am Netzteil, Automationen und
  Sensoren müssen bei ausgeschaltetem Display laufen.
- `ApplySharedPref` – `commit()` für PIN-Fehlversuche und Reset, weil unmittelbar danach ein Neustart folgen kann.
- `DiscouragedApi` (feste Querformat-Ausrichtung) – Wandpanel ist fest montiert; das Layout funktioniert trotzdem
  bei anderen Größen.

## Exportierte Komponenten

| Komponente | Grund | Schutz |
|---|---|---|
| `MainActivity` | Launcher/HOME | – (Startbildschirm) |
| `BootReceiver` | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` | Beide Broadcasts sind systemgeschützt; Receiver startet nur den eigenen Dienst |
| `RaumDeviceAdminReceiver` | Device Admin | `BIND_DEVICE_ADMIN` |
| `CoreService`, `UpdateStatusReceiver` | – | nicht exportiert |

## Bewusste Abweichung: Wetter aus dem Internet (11.1)

Auf Wunsch des Auftraggebers (23.09.2026) kann raum. die Wettervorhersage von **MET Norway** (Norwegisches
Meteorologisches Institut, api.met.no, CC BY 4.0) anzeigen. Das ist die einzige Verbindung ins Internet.

| Punkt | Umsetzung |
|---|---|
| Einwilligung | Ab Werk **aus**; Schalter in Einstellungen → Wetter und in der Einrichtung, mit Erklärung, was gesendet wird |
| Übertragene Daten | Standort auf 2 Nachkommastellen gerundet (~1 km) und User-Agent `raum-panel/<Version>` (+ Betreiberkontakt, siehe unten). Kein Konto, keine Geräte- oder Nutzerkennung, keine Gerätedaten |
| Häufigkeit | Frühestens alle 30 min, sonst nach `Expires` (höchstens 2 h); bedingte Abfrage (`If-Modified-Since`); bei Fehlern 1–60 min Rückoff, bei 429 deutlich länger. „Jetzt aktualisieren“ höchstens alle 10 min |
| Ausfall | Kern (Steuerung, Szenen, Automationen) hängt nicht davon ab; ohne Netz zeigt die Übersicht den letzten Stand („Stand 13:00“, bis 48 h) oder nur den Himmel |
| Datenhaltung | Letzte Antwort in `files/weather.json`; gelöscht beim Abschalten, bei „Übergabe vorbereiten“ und beim Werksreset. Nicht in Diagnosepaket oder Übergabeprotokoll |
| Transport | Nur HTTPS, Zertifikatsprüfung durch Android; keine eigene Vertrauenskette |

**Nutzungsbedingungen MET Norway:** Anfragen müssen eine eindeutige Kennung mit Kontakt tragen. Vor Auslieferung
in `gradle.properties` setzen: `raumWeatherContact=mailto:betrieb@example.com` (oder eine Website). Fehlt der
Kontakt, kann MET Anfragen mit 403 ablehnen – raum. zeigt dann „vom Dienst abgelehnt“.

Lokale Alternative ohne Internet: einen Matter-Temperatursensor als Außensensor wählen (Einstellungen → Wetter).

## Bewusste Abweichung: Administrator-PIN optional

Die Spezifikation verlangt die PIN als MUSS (ONB-004 „Administrator-PIN festlegen“, RST-003 „Reset mit PIN
bestätigen“, SYS-006 „Wartungsmodus nur mit PIN“). Auf Entscheidung des Auftraggebers (22.09.2026) ist die PIN
**optional**: Für ein Panel im eigenen Haushalt ohne Gäste/Mieter ist eine vergessene PIN das größere Risiko
(ohne Hintertür bleibt nur der Android-Werksreset).

Folgen ohne PIN:

| Risiko | Wirkung | Gegenmaßnahme |
|---|---|---|
| Jede Person am Panel öffnet den Wartungsmodus | Kiosk pausieren, Android-Einstellungen, ADB schalten, Device-Owner abgeben | Roter Hinweis im Wartungsmodus mit „PIN festlegen“, Hinweis in Einstellungen → Sicherheit, Protokolleintrag „Wartungsmodus ohne PIN geöffnet“ |
| Jede Person kann zurücksetzen | Datenverlust (Sicherung schützt) | Erklärdialog + zweite, ausdrückliche Bestätigung |
| **Wer zuerst eine PIN festlegt, wird Administrator** | Ein Gast/Mitbewohner kann die Bewohner aussperren | Nur durch Festlegen einer eigenen PIN vermeidbar; Hinweis beim Überspringen in der Einrichtung |
| Überspringen fällt später nicht auf | – | Protokolleintrag „Administrator-PIN bei der Einrichtung übersprungen“; Diagnosepaket enthält „Admin PIN set: false“ |

Empfehlung: Bei vermieteten Wohnungen, Ferienwohnungen und Übergaben an Dritte immer eine PIN festlegen. Soll die
PIN wieder Pflicht werden, genügt es, in der Einrichtung „Ohne PIN fortfahren“ zu entfernen und
`ApplianceViewModel.enterWithoutPin()` stets `false` liefern zu lassen.

## Restrisiken und Empfehlungen

1. **PIN-Länge**: 4 Ziffern sind durch die Sperre online gut geschützt, bei Zugriff auf die Datei (Root/ADB) aber
   offline in kurzer Zeit zu erraten. Deshalb ist ADB im Endkundenbetrieb zu deaktivieren; ggf. 6 Ziffern vorschreiben.
2. **Sicherungspasswort**: Mindestlänge 8 Zeichen. Wer das Passwort verliert, kann die Sicherung nicht wiederherstellen
   (gewollt).
3. **Signaturschlüssel**: Nur der Release-Schlüssel darf Updates erzeugen. Er gehört offline gesichert; ein Verlust
   macht weitere Updates ohne Neuinstallation unmöglich. Schlüsselrotation wird bewusst nicht unterstützt.
4. **Offen**: Eigene Hersteller-ID, Zertifizierung und DAC/PAI von einem gelisteten PKI-Anbieter (docs/VENDOR.md).
   Empfohlen: DAC-Schlüssel im Panel erzeugen (CSR); nur wenn der PKI-Anbieter das nicht kann, ein Paket mit
   unverschlüsseltem Schlüssel importieren und danach löschen.
   Fabric-Schlüssel sind seit
   2026-09-24 verschlüsselt (Keystore); wer Schlüssel nie im App-Speicher haben will, braucht einen eigenen
   Operational-Credentials-Issuer mit Keystore-Signatur – das erzwingt eine neue Fabric.
6. **Keystore-Verlust**: Geht der Keystore-Schlüssel verloren (z. B. Werksreset des Android-Systems), sind die
   Fabric-Schlüssel nicht mehr lesbar; raum. meldet das in den Einstellungen. Geräte müssen dann neu gekoppelt werden –
   wie bei jedem Verlust der Fabric.
5. **OTBR-REST ohne Authentisierung**: Die REST-API des OpenThread Border Routers liefert das Dataset jedem im LAN.
   Das ist eine Eigenschaft des Border Routers, nicht von raum.; wer das nicht möchte, schaltet die REST-API dort ab und
   gibt das Dataset in raum. von Hand ein.
