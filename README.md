# raum.

**A local-first Matter wall controller for Android panels.**

raum. turns a wall-mounted Android panel (reference hardware: SMATEK T10 Pro) into a dedicated smart-home controller.
It speaks Matter directly: it has its own fabric, and it controls devices over Wi-Fi and Thread. It needs no cloud,
no account and no Google services. The panel can also act as a Matter bridge, so the devices it manages appear in
Apple Home, Google Home, Alexa or SmartThings.

> Status: working prototype. Tested on the Android emulator against real Matter/Thread devices and with `chip-tool`.
> Tests on real panel hardware and a Matter certification are still to come.
> The in-app UI is available in German and English. Most project documentation in `docs/` is currently in German
> ([Deutsche README](README.de.md)).

## Features

| Area | What it does |
|---|---|
| **Matter controller** | Own fabric based on connectedhomeip v1.6. Adds devices by setup code (QR or 11-digit code) over the network, or over Bluetooth with Thread/Wi-Fi credential handover. Supports subscriptions, commands and multi-admin. Checks device attestation against bundled PAA certificates |
| **Thread** | Imports a Thread dataset (hex) or takes it from an OpenThread Border Router. Discovers border routers (`_meshcop._udp`). Shows diagnostics: role, network and channel per device, reachability, IPv6 |
| **Home UI** | Jetpack Compose UI for a landscape wall panel. Overview with a sky that follows the real sun position, indoor climate and hints. Rooms, device cards and detail sheets, favourites, light/dark theme |
| **Scenes & automations** | Scene editor. Automations with triggers (time, sunrise/sunset, device state, measurements, reachability, boot), conditions and actions. Runs a local scheduler with loop protection and a persistent event log |
| **Matter bridge** | raum. can itself be added to Apple/Google/Amazon ecosystems as a Matter aggregator. It runs a second CHIP stack in a separate process with a code-driven data model and exposes lights (dimmable, colour, colour temperature), plugs, sensors, thermostats and window coverings |
| **Attestation** | Supports its own vendor/product ID. The bridge's DAC key is generated inside the Android Keystore (StrongBox where available) and exported only as a PKCS#10 CSR. Certificate bundles can be imported and are validated |
| **Appliance mode** | Device owner and kiosk (lock task, launcher), boot autostart, foreground service with crash recovery. Screen sleep via the proximity sensor, automatic brightness, optional admin PIN and maintenance mode |
| **Operations** | Encrypted backup and restore, factory reset and handover, diagnostics bundle, signed local updates, restore points before migrations |
| **Security** | Matter fabric keys, Thread credentials and secrets are encrypted with Android Keystore (AES-GCM). No cleartext traffic except for local border-router REST |
| **Weather (opt-in)** | MET Norway forecast using a rounded location, with an animated sky and a 7-day strip. Off by default |
| **Offline place search** | Built-in GeoNames list (~64,000 places), so sunrise and sunset are computed without location services or internet |

## Architecture

```
app/src/main/java/app/raum/
├── ui/            Compose UI (overview, rooms, devices, scenes, automations, settings, share/bridge)
├── domain/        Capability model, devices, rooms, scenes, commands, use cases
├── automation/    Engine, triggers (sun calculator), conditions, plain-language describer
├── matter/
│   ├── controller/  MatterController interface + mock home for simulation
│   ├── chip/        Real controller on the connectedhomeip Java/JNI API, cluster mapping, encrypted KVS
│   └── bridge/      Matter bridge: process ":bridge", Messenger IPC, attestation store, CSR builder
├── thread/        Thread dataset, border-router discovery, diagnostics
├── platform/      Foreground service, kiosk, display/sensors, Bluetooth
├── security/      Admin PIN (PBKDF2), Keystore cipher, secret store
├── data/          Room database, settings
└── di/            Koin module (switches simulation ↔ real Matter)

native/raum-bridge/   C++ Matter bridge (aggregator, bridged endpoints, custom Thermostat/WindowCovering/ColorControl clusters)
tools/                SDK/bridge build scripts, emulator setup, attestation test tooling, soak test
```

The UI knows no Matter cluster IDs. Controllers deliver `DeviceState` objects with capability lists, and commands go
in as `DeviceCommand`. This keeps the UI identical in simulation and on real devices.

## Building

Stack: AGP 9.4.1, Gradle 9.7.1, Kotlin 2.4.20, Compose, compileSdk/targetSdk 37, minSdk 30. The app is `arm64-v8a` only.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The prebuilt Matter SDK artifacts are checked in: JARs in `app/libs/matter/`, native libraries in
`app/src/main/jniLibs/arm64-v8a/`, and PAA certificates in `app/src/main/assets/paa/`. To rebuild them from source:

- `tools/build-matter-sdk.sh` builds connectedhomeip v1.6.0.0 (needs NDK 28.2, JDK 17 and about 15 GB of disk).
- `tools/build-matter-bridge.sh` builds `libRaumBridge.so` from `native/raum-bridge`.

Build outputs go to `build.nosync/`, which keeps them out of iCloud sync on macOS.

Emulator profiles: `tools/create-avds.sh`, then run `emulator -avd raum-dev`. The emulator can reach real Matter
and Thread devices on the home network through the host.

**Simulation mode** (Settings → Matter & Thread) runs a virtual home with 20 devices and needs no hardware.

## Documentation (German)

- [OPERATIONS.md](docs/OPERATIONS.md): installation, updates, backup and recovery, Thread, bridge
- [SECURITY.md](docs/SECURITY.md): security review and hardening
- [VENDOR.md](docs/VENDOR.md): path to your own vendor ID, PKI and Matter certification

## Third-party components

- [connectedhomeip](https://github.com/project-chip/connectedhomeip) (Apache License 2.0): prebuilt Matter SDK
  libraries and bridge example code.
- PAA certificates from the CSA Distributed Compliance Ledger.
- [GeoNames](https://www.geonames.org/) place data (CC BY 4.0).
- Weather data from [MET Norway](https://api.met.no/) (CC BY 4.0, opt-in).
