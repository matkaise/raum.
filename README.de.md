# raum.

Lokaler Matter-Wandcontroller für Android-Panels (Referenz: SMATEK T10 Pro).
Spezifikation: `raum_matter_controller_spezifikation.md` (v0.1).

## Stand: M0 – M1, M3 – M5, M7, Einrichtung

| Meilenstein | Inhalt | Status |
|---|---|---|
| M0 Projektgrundlage | Gradle/Kotlin/Compose-Projekt, Modulstruktur, CI-Workflow, Emulatorprofile, Designsystem, Navigation | ✅ |
| M1 UI mit Mock-Geräten | Übersicht, Räume (inkl. Verwaltung), Geräteliste, Gerätekarten & Detailansicht, Szenen ausführen, Mock-Zustände, hell/dunkel | ✅ |
| M2 Matter-Grundintegration | Matter-SDK connectedhomeip v1.6 (selbst gebaut, arm64), echte Fabric, Koppeln per Setup-Code über das Netzwerk (Multi-Admin), Abos, Befehle, Echtheitsprüfung mit lokalen PAA-Zertifikaten, Multi-Admin (Kopplungsfenster, Fabric-Liste), Umschaltung Simulation ↔ echt | ✅ Kern (mit echten Thread-Geräten getestet: IKEA TIMMERFLOTTE, Feller Roomthermostat) |
| M3 Räume und Szenen | Room/SQLite-Persistenz (Schema v1, exportiert), Raumzuordnung & Favoriten dauerhaft, Szeneneditor (anlegen, bearbeiten, duplizieren, löschen, aktuelle Zustände übernehmen, ausprobieren) | ✅ |
| M4 Automationen | Trigger (Uhrzeit, Sonne, Gerätezustand, Messwert, Erreichbarkeit, Systemstart) → Bedingungen → Aktionen, Editor mit Zusammenfassung, lokaler Scheduler, Schleifenschutz, persistentes Ereignisprotokoll mit Rotation und Filtern, DB-Migration v1→v2 | ✅ |
| M5 Appliance-Modus | Foreground Service (Neustart nach Absturz), Autostart nach Boot, Device Owner + Kiosk (Lock Task, Launcher, kein Keyguard/Statusleiste), Ruhezustand mit Näherungssensor, automatische Helligkeit, Administrator-PIN, Wartungsmodus, Startbildschirm, Absturzprotokoll | ✅ |
| M6 Thread | Thread-Dataset importieren (Hex) oder von einem OpenThread Border Router übernehmen, verschlüsselt im Android-Keystore; Border-Router-Suche (`_meshcop._udp`) mit Zustand; Diagnose (Border Router, erreichbare Thread-Geräte, IPv6, Rolle/Netz/Kanal je Gerät); neue Geräte per Bluetooth koppeln mit Übergabe von Thread- oder WLAN-Zugangsdaten; verständliche Fehler (kein Thread-Netz, Beitritt fehlgeschlagen, nicht erreichbar, Bluetooth fehlt); Thread in der Einrichtung; Hinweis „Border Router nicht erreichbar“ in der Übersicht | ✅ Software (Border-Router-Suche und Diagnose mit echtem Apple-Router getestet; Bluetooth-Erstkopplung braucht ein echtes Android-Gerät) |
| Einrichtung (ONB) | Geführte Erstinbetriebnahme: Sprache, neu oder aus Sicherung, Name, Zeitzone/Einheit/Standort, optionale Administrator-PIN, Systemprüfung, Matter-Fabric, erstes Gerät oder Beispielwohnung; setzt nach Unterbrechung fort | ✅ |
| Übersicht & Einstellungen (UI) | Hero mit Himmel nach echtem Sonnenstand, Uhr, Innenklima, Sonnenbogen und Hinweis-Chips; Einstellungen als Kategorien (Zuhause, Netzwerk, Display, Region, Matter, Sicherheit, Protokoll, Info); WLAN verbinden; Offline-Ortssuche | ✅ |
| Wetter (opt-in) | Vorhersage von MET Norway (ab Werk aus, gerundeter Standort), animierter Himmel im Hero (Wolken, Regen, Schnee, Nebel, Gewitter), Regen-Ausblick, optionaler Matter-Außensensor | ✅ |
| Wettervorhersage | 7-Tage-Leiste unter dem Hero, Detail je Tag mit Stundenwerten und Temperaturkurve, eigene gezeichnete Wettersymbole | ✅ |
| Teilen (Multi-Admin) | Geräte zusätzlich in Apple Home, Google Home, Alexa, SmartThings: Kopplungsfenster je Gerät (QR + 11-stelliger Code, 15 min), „alle nacheinander“, verbundene Apps anzeigen und entfernen, PIN-geschützt – gegen den Simulator, echt mit M2 | ✅ (Simulator) |
| Bridge | raum. als Matter-Bridge: einmal in Apple Home/Google Home/Alexa koppeln, alle freigegebenen Geräte erscheinen dort (neue automatisch); Geräteauswahl nach Räumen, verbundene Apps entfernen, Doppelt-Warnung; Befehle anderer Apps laufen über raum. Echt: eigener Matter-Server (Aggregator, code-basiertes Datenmodell) im Prozess `:bridge`, Leuchten (auch Farbe und Weißton), Steckdosen, Sensoren, Thermostate und Storen (eigene Cluster für Thermostat, Storen, Farbe) | ✅ (mit chip-tool getestet; Apple/Google brauchen ein Gerät im LAN) |
| M7 Stabilisierung | Verschlüsselte Sicherung/Wiederherstellung mit Kompatibilitätsprüfung, Werksreset und Übergabe, Übergabeprotokoll, Diagnosepaket, signierte lokale Updates, Wiederherstellungspunkte vor Migration/Update, Sicherheitsprüfung (Lint, Härtung, R8-Release), Lasttest, Soak-Test-Skript, Betriebsdokumentation | ✅ |

English overview: [README.md](README.md)

Dokumentation: [Betrieb, Updates, Recovery](docs/OPERATIONS.md) · [Sicherheitsprüfung](docs/SECURITY.md) · [Eigene Hersteller-ID](docs/VENDOR.md)

## Sprachen

Deutsch und Englisch (`values-de/`, `values/` = Englisch und Rückfall für alle übrigen Systemsprachen).
Die App-Sprache ist unabhängig von der Systemsprache des Panels einstellbar (Einstellungen → Sprache und Region),
ebenso °C/°F.

- Oberfläche: `stringResource(R.string.…)`; Texte außerhalb von Compose (Meldungen, Protokoll, Automations-Sätze,
  Berichte, Benachrichtigung) über `i18n.Strings`.
- Satzbausteine der Automationen sind Vorlagen mit nummerierten Platzhaltern (`%1$s`, `%2$s`), damit jede Sprache
  ihre eigene Wortstellung nutzen kann.
- `TranslationCompletenessTest` prüft, dass beide Sprachen dieselben Schlüssel und Platzhalter haben.
- Neue Sprache: Eintrag in `AppLanguage` + `values-xx/strings.xml`.
- Nicht übersetzt werden gespeicherte Nutzerdaten (Raum-/Gerätenamen) und Protokolleinträge (bleiben in der Sprache,
  in der sie geschrieben wurden).

Bereits vorgezogen, weil ohne Matter-SDK testbar:
- Parser für den numerischen Setup-Code inkl. Verhoeff-Prüfziffer (COM-001)
- Commissioning-Dialog mit Fortschritt, Fehler-/Wiederholungspfad, Duplikaterkennung und Benennung (COM-005 – COM-007, COM-010) – gegen den Mock
- Optimistische Befehle mit Rollback (CTRL-001), Offline-Schutz (CTRL-005), Gruppenaktionen (ROM-004), Teilfehler bei Szenen (SCN-006)
- Ereignisprotokoll im Speicher mit Größen-/Altersgrenze (LOG-001/002)

## Bauen

Stack: AGP 9.4.1 (Kotlin eingebaut), Gradle 9.7.1, Kotlin 2.4.20, Compose BOM 2026.09.00, compileSdk/targetSdk 37, minSdk 30.
JDK: das von Android Studio mitgelieferte (JBR 25).

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest :app:installDebug
```

Emulatoren (Spez. 13.1) anlegen und starten:

```bash
tools/create-avds.sh
$ANDROID_HOME/emulator/emulator -avd raum-dev
```

**Matter-SDK:** `tools/build-matter-sdk.sh` baut connectedhomeip (v1.6.0.0) für arm64 und legt JARs nach
`app/libs/matter/`, die native Bibliothek nach `app/src/main/jniLibs/arm64-v8a/` und die PAA-Zertifikate nach
`app/src/main/assets/paa/`. Braucht NDK 28.2, JDK 17, kotlinc 2.1.10; SDK außerhalb von iCloud klonen (~15 GB).
Die Bridge (`native/raum-bridge`) baut `tools/build-matter-bridge.sh` als `libRaumBridge.so` im selben SDK-Baum.
Die App ist deshalb auf `arm64-v8a` beschränkt (Emulator raum-dev und T10 Pro). APK: Release ~18 MB, Debug ~90 MB.

**Betriebsart:** Einstellungen → Matter und Thread → „Echte Geräte (Matter)“ oder „Simulation“ (Neustart). Neue
Installationen starten mit echtem Matter, Installationen von vor M2 behalten die Simulation. Der Emulator erreicht
echte Matter- und Thread-Geräte im Heimnetz über den Mac (mDNS und IPv6 durchgereicht) – für erste Tests reicht er.

Wetter (MET Norway) verlangt einen Kontakt im User-Agent – vor Auslieferung in `gradle.properties` setzen:
`raumWeatherContact=mailto:betrieb@example.com`.

Tests:

```bash
./gradlew :app:testDebugUnitTest            # 130 Unit-Tests (JVM)
./gradlew :app:connectedDebugAndroidTest    # 17 DB-/Migrationstests, Emulator muss laufen
```

Die APKs bleiben nach dem Testlauf installiert (`gradle.properties`), weil eine Device-Owner-App nicht deinstalliert werden kann.

Alternativ den Ordner in Android Studio öffnen und die Run-Konfiguration `app` auf `raum-dev` starten.

**Hinweis iCloud:** Der Schreibtisch wird per iCloud synchronisiert. iCloud legt in Build-Ordnern Duplikate
(„Foo 2.class“) an, die den Build brechen. Deshalb liegen die Build-Ausgaben in `build.nosync/` und `.gradle.nosync/`
(Ordner mit der Endung `.nosync` werden nicht synchronisiert). Besser wäre ein Projektordner außerhalb von iCloud.

## Architektur

```
app/src/main/java/app/raum/
├── ui/                 Compose-Oberfläche (overview, rooms, devices, scenes, automations, settings, components, theme)
├── domain/
│   ├── models/         Capability-Modell, Device, Room, Scene, DeviceCommand
│   ├── repositories/   HomeRepository (Room; In-Memory für Unit-Tests)
│   └── usecases/       DeviceService (optimistisch), SceneRunner, SceneTargets, CapabilityReducer
├── automation/
│   ├── engine/         AutomationEngine (Scheduler, Geräte-Trigger, Schleifenschutz, Ausführung)
│   ├── triggers/       SunCalculator, GeoLocation
│   ├── conditions/     ConditionEvaluator, DeviceReadings
│   └── AutomationDescriber  (Klartext-Zusammenfassung, AUT-009)
├── platform/
│   ├── service/        CoreService (Foreground, START_STICKY, WakeLock), BootReceiver, CrashRecorder
│   ├── kiosk/          KioskManager, RaumDeviceAdminReceiver
│   ├── display/        DisplayController (Ruhezustand, Helligkeit), ScreenPower, DisplayActuator
│   └── sensors/        AmbientSensors (Licht, Näherung)
├── security/           AdminPinStore (PBKDF2, Sperre), MaintenanceSession
├── matter/
│   ├── commissioning/  SetupCodeParser
│   └── controller/     MatterController-Schnittstelle, mock/ (virtuelle Wohnung)
├── data/database/     Room: Entities, HomeDao, RaumDatabase, RoomHomeRepository
├── data/               Einstellungen, In-Memory-Repository
├── diagnostics/        EventLog
└── di/                 Koin-Modul – hier wird Mock ↔ echter Controller getauscht
```

Die UI kennt keine Matter-Cluster-IDs (Spez. 5.4): Der `MatterController` liefert `DeviceState`
mit `Capability`-Listen, Befehle gehen als `DeviceCommand` hinein.

## Mock-Controller

Die virtuelle Wohnung (`MockHomeSeed`, am Ende der Einrichtung wählbar) enthält 6 Räume, 20 Geräte aller MVP-Kategorien und 5 Szenen.
Simuliert werden Latenz, laufende Storen, Heizverhalten, Sensordrift, Präsenzwechsel und ein Offline-Gerät (Balkontür).

Testcodes für „Gerät hinzufügen“:
- `3497-011-2332` – Erfolg (neue Leuchte); ein zweites Mal → „bereits vorhanden“
- `1235-530-7536`, `2398-211-5066` – weitere gültige Codes
- beliebiger Code mit falscher Prüfziffer → Eingabefehler

## Datenbank

- `app/schemas/` enthält das exportierte Schema je Version – **mit einchecken**.
- Jede Schemaänderung: `version` in `RaumDatabase` erhöhen, Migration (AutoMigration oder manuell) ergänzen,
  Test in `RaumDatabaseMigrationTest` hinzufügen. Destruktive Migrationen sind ausgeschlossen.
- Szenenaktionen werden als JSON-Befehle gespeichert; die `@SerialName`s in `DeviceCommand` sind Speicherformat
  (abgesichert durch `CommandSerializationTest`).
- Das Zuhause entsteht in der Einrichtung – leer oder (nur Mock) als Beispielwohnung; danach nie wieder überschrieben.
  Zurücksetzen: Wartungsmodus → „raum. vollständig zurücksetzen“ (startet die Einrichtung erneut).

## Automationen

- Ein Auslöser genügt (ODER), alle Bedingungen müssen gelten (UND), Aktionen laufen der Reihe nach.
- Geräte-Trigger sind flankengesteuert und nutzen nur **bestätigte** Gerätezustände (keine optimistischen).
- Scheduler prüft jede Minute; nach kurzen Aussetzern werden bis zu 5 Minuten nachgeholt, längere Ausfälle nicht.
- Schleifenschutz: keine parallele Ausführung derselben Automation; mehr als 6 Auslösungen pro Minute → 5 Minuten Pause
  (sichtbar in der Liste, „Fortsetzen“ möglich).
- Sonnenzeiten offline aus dem hinterlegten Standort (Einstellungen → Sprache und Region → Standort), Genauigkeit ±1 Minute.
  Der Ort wird über eine **eingebaute Ortsliste** gesucht (GeoNames, ~64 000 Orte ab 5 000 Einwohnern, CC BY 4.0) –
  keine Ortung, kein Internet. WLAN-basierte Ortung bräuchte Google-Dienste oder einen Internetdienst und entfällt
  deshalb bewusst. Neu erzeugen: `tools/build-cities.py cities5000.txt admin1CodesASCII.txt` (Quelle
  download.geonames.org); das Ergebnis `app/src/main/assets/cities.dat` ist gzip, heißt aber absichtlich nicht `.gz`,
  weil der Android-Build `.gz`-Assets entpackt.
- Die Engine läuft im Prozess des Foreground Service (CoreService) und damit auch bei ausgeschaltetem Display.

## Appliance-Betrieb (Panel)

Einrichtung auf einem frischen Gerät ohne Google-/Nutzerkonten:

```bash
adb install app-release.apk
adb shell dpm set-device-owner app.raum.panel/app.raum.platform.kiosk.RaumDeviceAdminReceiver
```

Danach ist raum. Startbildschirm, der Kiosk (Lock Task) ist aktiv, Statusleiste und Sperrbildschirm sind aus.
Die Einrichtung fragt nach einer Administrator-PIN. Sie ist optional (bewusste Abweichung von der Spezifikation,
siehe [SECURITY.md](docs/SECURITY.md)); ohne PIN ist der Wartungsmodus (Android-Einstellungen, ADB, Neustart,
Device-Owner abgeben) für jede Person am Panel offen und zeigt einen Warnhinweis. Er sperrt sich nach 5 Minuten
ohne Bedienung.

Emulator: Licht- und Näherungssensor lassen sich simulieren:

```bash
adb emu sensor set light 500
adb emu sensor set proximity 0   # 0 = nah, 1 = frei
```

Nur auf dem T10 Pro prüfbar: echte Sensorkennlinien (Lux-Kalibrierung, Näherungsschwelle), Verhalten des
Display-Aus über die Hersteller-Firmware, PoE-Kaltstart, Dauerbetrieb über 7 Tage.
