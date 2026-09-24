/*
 * raum. Bridge – gemeinsame Typen (Geräteart, Zustand aus raum., Rückmeldungen an raum.).
 */
#pragma once

#include <lib/core/DataModelTypes.h>

#include <climits>
#include <cstdint>

namespace raum {

/** Muss zu app.raum.matter.bridge.BridgeKind passen. */
enum class Kind : int
{
    kOnOffLight         = 0,
    kDimmableLight      = 1,
    kOnOffPlug          = 2,
    kContactSensor      = 3,
    kOccupancySensor    = 4,
    kTemperatureSensor  = 5,
    kHumiditySensor     = 6,
    kTempHumiditySensor = 7,
    kThermostat         = 8,
    kCover              = 9,
    kColorLight         = 10, // Extended Color Light: Farbe (HS + XY) und Farbtemperatur
    kColorTempLight     = 11, // Color Temperature Light: nur Farbtemperatur
};

/** Befehle anderer Apps an raum. melden. */
class BridgeCallbacks
{
public:
    virtual ~BridgeCallbacks()                                    = default;
    virtual void OnOnOff(chip::EndpointId endpoint, bool on)      = 0;
    virtual void OnLevel(chip::EndpointId endpoint, uint8_t level) = 0;
    /** Weitere Befehle (Thermostat, Storen) – Art siehe CommandType in RaumClusters.h */
    virtual void OnCommand(chip::EndpointId endpoint, int type, int value) = 0;
};

/** Aktueller Zustand aus raum. (nicht belegte Werte bleiben unverändert). */
struct DeviceState
{
    bool reachable = true;
    bool on        = false;
    int level      = -1;  // 1–254, -1 = unbekannt
    bool contact   = false; // true = geschlossen (BooleanState TRUE)
    bool occupied  = false;
    int tempC100   = INT32_MIN; // 0,01 °C
    int humidity100 = -1;       // 0,01 %
    int setpointC100 = INT32_MIN; // Thermostat-Sollwert
    int systemMode   = -1;        // Matter SystemModeEnum
    int coverClosed100ths = -1;   // 0 offen … 10000 zu
    int coverMovement = 0;        // 0 steht, 1 öffnet, 2 schließt
    int colorMode = -1;           // -1 ohne, 0 Farbe, 2 Farbtemperatur
    int hue = -1, saturation = -1; // 0–254
    int colorX = -1, colorY = -1;  // 0–65279
    int mireds = -1;
};

/** Feste Eigenschaften je Gerät (ändern sich nur mit dem Gerät). */
struct DeviceConfig
{
    bool heat     = true;
    bool cool     = false;
    bool autoMode = false;
    int16_t minC100 = 500;
    int16_t maxC100 = 3000;
    // Farbleuchte: Farbtemperatur-Bereich des Geräts in Mired
    bool color = false;
    bool colorTemperature = false;
    uint16_t minMireds = 153;
    uint16_t maxMireds = 500;
};

} // namespace raum
