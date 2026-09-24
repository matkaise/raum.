/*
 * raum. Bridge – eigene, code-basierte Cluster für Thermostat (0x0201) und Storen (Window Covering, 0x0102).
 * Das SDK 1.6 bringt beide nur für das ältere, zap-basierte Datenmodell mit. Umfang bewusst schlank:
 * genau das, was raum. an echten Geräten steuern kann, nach der Matter-Spezifikation (Pflichtattribute).
 */
#pragma once

#include "BridgeTypes.h"

#include <app/server-cluster/DefaultServerCluster.h>

#include <optional>

namespace raum {

/** Befehlsarten an raum. – muss zu BridgeMessages (Kotlin) passen. */
enum CommandType : int
{
    kCmdSetpoint   = 10, // Wert: 0,01 °C
    kCmdSystemMode = 11, // Wert: Matter SystemModeEnum
    kCmdCoverOpen  = 20,
    kCmdCoverClose = 21,
    kCmdCoverStop  = 22,
    kCmdCoverGoTo  = 23, // Wert: Prozent offen (0–100)
    kCmdColorHS    = 30, // Wert: Farbton << 8 | Sättigung (je 0–254)
    kCmdColorXY    = 31, // Wert: X << 16 | Y (je 0–65279, vorzeichenlos lesen)
    kCmdColorTemp  = 32, // Wert: Mired
};

class ThermostatBridgeCluster : public chip::app::DefaultServerCluster
{
public:
    using Config = DeviceConfig;

    ThermostatBridgeCluster(chip::EndpointId endpoint, const Config & config, BridgeCallbacks & callbacks);

    /** Zustand aus raum. (ohne Rückmeldung als Befehl) */
    void Update(int localC100, int setpointC100, int systemMode);

    chip::app::DataModel::ActionReturnStatus ReadAttribute(const chip::app::DataModel::ReadAttributeRequest & request,
                                                           chip::app::AttributeValueEncoder & encoder) override;
    chip::app::DataModel::ActionReturnStatus WriteAttribute(const chip::app::DataModel::WriteAttributeRequest & request,
                                                            chip::app::AttributeValueDecoder & decoder) override;
    CHIP_ERROR Attributes(const chip::app::ConcreteClusterPath & path,
                          chip::ReadOnlyBufferBuilder<chip::app::DataModel::AttributeEntry> & builder) override;
    CHIP_ERROR AcceptedCommands(const chip::app::ConcreteClusterPath & path,
                                chip::ReadOnlyBufferBuilder<chip::app::DataModel::AcceptedCommandEntry> & builder) override;
    std::optional<chip::app::DataModel::ActionReturnStatus> InvokeCommand(const chip::app::DataModel::InvokeRequest & request,
                                                                          chip::TLV::TLVReader & input,
                                                                          chip::app::CommandHandler * handler) override;

private:
    const Config mConfig;
    BridgeCallbacks & mCallbacks;
    chip::app::DataModel::Nullable<int16_t> mLocal;
    int16_t mSetpoint;
    uint8_t mSystemMode;

    bool ModeAllowed(uint8_t mode) const;
    chip::app::DataModel::ActionReturnStatus ChangeSetpoint(int value);
};

class CoverBridgeCluster : public chip::app::DefaultServerCluster
{
public:
    CoverBridgeCluster(chip::EndpointId endpoint, BridgeCallbacks & callbacks);

    /** @param closed100ths 0 = offen, 10000 = zu (Matter-Sicht); movement 0 steht, 1 öffnet, 2 schließt */
    void Update(int closed100ths, int movement);

    chip::app::DataModel::ActionReturnStatus ReadAttribute(const chip::app::DataModel::ReadAttributeRequest & request,
                                                           chip::app::AttributeValueEncoder & encoder) override;
    chip::app::DataModel::ActionReturnStatus WriteAttribute(const chip::app::DataModel::WriteAttributeRequest & request,
                                                            chip::app::AttributeValueDecoder & decoder) override;
    CHIP_ERROR Attributes(const chip::app::ConcreteClusterPath & path,
                          chip::ReadOnlyBufferBuilder<chip::app::DataModel::AttributeEntry> & builder) override;
    CHIP_ERROR AcceptedCommands(const chip::app::ConcreteClusterPath & path,
                                chip::ReadOnlyBufferBuilder<chip::app::DataModel::AcceptedCommandEntry> & builder) override;
    std::optional<chip::app::DataModel::ActionReturnStatus> InvokeCommand(const chip::app::DataModel::InvokeRequest & request,
                                                                          chip::TLV::TLVReader & input,
                                                                          chip::app::CommandHandler * handler) override;

private:
    BridgeCallbacks & mCallbacks;
    chip::app::DataModel::Nullable<uint16_t> mCurrent;
    chip::app::DataModel::Nullable<uint16_t> mTarget;
    uint8_t mMovement = 0;
    uint8_t mMode     = 0;

    void SetTarget(uint16_t target);
};

/** Farbe und Farbtemperatur (Color Control, 0x0300). Übergänge setzt raum. sofort – das Gerät blendet selbst. */
class ColorBridgeCluster : public chip::app::DefaultServerCluster
{
public:
    ColorBridgeCluster(chip::EndpointId endpoint, const DeviceConfig & config, BridgeCallbacks & callbacks);

    /** Zustand aus raum.; mode: 0 Farbe, 2 Farbtemperatur, -1 unverändert */
    void Update(int mode, int hue, int saturation, int x, int y, int mireds);

    chip::app::DataModel::ActionReturnStatus ReadAttribute(const chip::app::DataModel::ReadAttributeRequest & request,
                                                           chip::app::AttributeValueEncoder & encoder) override;
    chip::app::DataModel::ActionReturnStatus WriteAttribute(const chip::app::DataModel::WriteAttributeRequest & request,
                                                            chip::app::AttributeValueDecoder & decoder) override;
    CHIP_ERROR Attributes(const chip::app::ConcreteClusterPath & path,
                          chip::ReadOnlyBufferBuilder<chip::app::DataModel::AttributeEntry> & builder) override;
    CHIP_ERROR AcceptedCommands(const chip::app::ConcreteClusterPath & path,
                                chip::ReadOnlyBufferBuilder<chip::app::DataModel::AcceptedCommandEntry> & builder) override;
    std::optional<chip::app::DataModel::ActionReturnStatus> InvokeCommand(const chip::app::DataModel::InvokeRequest & request,
                                                                          chip::TLV::TLVReader & input,
                                                                          chip::app::CommandHandler * handler) override;

private:
    const DeviceConfig mConfig;
    BridgeCallbacks & mCallbacks;
    uint8_t mMode    = 0; // ColorModeEnum
    uint8_t mOptions = 0;
    uint8_t mHue = 0, mSaturation = 0;
    uint16_t mX = 24939, mY = 24701; // Weißpunkt (≈ D65)
    uint16_t mMireds;
    chip::app::DataModel::Nullable<uint16_t> mStartUpMireds;

    uint16_t Features() const;
    void SetMode(uint8_t mode);
    void SetHueSat(uint8_t hue, uint8_t saturation, bool notify);
    void SetXY(uint16_t x, uint16_t y, bool notify);
    void SetMireds(uint16_t mireds, bool notify);
};

} // namespace raum
