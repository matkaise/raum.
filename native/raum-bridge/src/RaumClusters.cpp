/*
 * raum. Bridge – Thermostat- und Storen-Cluster (siehe RaumClusters.h).
 */
#include "RaumClusters.h"

#include <app/server-cluster/AttributeListBuilder.h>
#include <clusters/ColorControl/Commands.h>
#include <clusters/ColorControl/Enums.h>
#include <clusters/ColorControl/Metadata.h>
#include <clusters/Thermostat/Commands.h>
#include <clusters/Thermostat/Enums.h>
#include <clusters/Thermostat/Metadata.h>
#include <clusters/WindowCovering/Commands.h>
#include <clusters/WindowCovering/Enums.h>
#include <clusters/WindowCovering/Metadata.h>
#include <lib/support/TypeTraits.h>

#include <algorithm>

using namespace chip;
using namespace chip::app;
using chip::Protocols::InteractionModel::Status;

namespace raum {

// === Thermostat ======================================================================================================

namespace TS = chip::app::Clusters::Thermostat;

ThermostatBridgeCluster::ThermostatBridgeCluster(EndpointId endpoint, const Config & config, BridgeCallbacks & callbacks) :
    DefaultServerCluster({ endpoint, TS::Id }), mConfig(config), mCallbacks(callbacks), mSetpoint(2000),
    mSystemMode(to_underlying(config.heat ? TS::SystemModeEnum::kHeat : TS::SystemModeEnum::kCool))
{}

bool ThermostatBridgeCluster::ModeAllowed(uint8_t mode) const
{
    switch (static_cast<TS::SystemModeEnum>(mode))
    {
    case TS::SystemModeEnum::kOff:
        return true;
    case TS::SystemModeEnum::kHeat:
        return mConfig.heat;
    case TS::SystemModeEnum::kCool:
        return mConfig.cool;
    case TS::SystemModeEnum::kAuto:
        return mConfig.autoMode;
    default:
        return false;
    }
}

DataModel::ActionReturnStatus ThermostatBridgeCluster::ReadAttribute(const DataModel::ReadAttributeRequest & request,
                                                                     AttributeValueEncoder & encoder)
{
    using namespace TS::Attributes;
    switch (request.path.mAttributeId)
    {
    case FeatureMap::Id: {
        BitFlags<TS::Feature> features;
        features.Set(TS::Feature::kHeating, mConfig.heat).Set(TS::Feature::kCooling, mConfig.cool);
        return encoder.Encode(features);
    }
    case ClusterRevision::Id:
        return encoder.Encode(TS::kRevision);
    case LocalTemperature::Id:
        return encoder.Encode(mLocal);
    case ControlSequenceOfOperation::Id:
        return encoder.Encode(mConfig.heat && mConfig.cool ? TS::ControlSequenceOfOperationEnum::kCoolingAndHeating
                                  : mConfig.heat           ? TS::ControlSequenceOfOperationEnum::kHeatingOnly
                                                           : TS::ControlSequenceOfOperationEnum::kCoolingOnly);
    case SystemMode::Id:
        return encoder.Encode(static_cast<TS::SystemModeEnum>(mSystemMode));
    case OccupiedHeatingSetpoint::Id:
    case OccupiedCoolingSetpoint::Id:
        return encoder.Encode(mSetpoint);
    case AbsMinHeatSetpointLimit::Id:
    case MinHeatSetpointLimit::Id:
    case AbsMinCoolSetpointLimit::Id:
    case MinCoolSetpointLimit::Id:
        return encoder.Encode(mConfig.minC100);
    case AbsMaxHeatSetpointLimit::Id:
    case MaxHeatSetpointLimit::Id:
    case AbsMaxCoolSetpointLimit::Id:
    case MaxCoolSetpointLimit::Id:
        return encoder.Encode(mConfig.maxC100);
    default:
        return Status::UnsupportedAttribute;
    }
}

CHIP_ERROR ThermostatBridgeCluster::Attributes(const ConcreteClusterPath & path, ReadOnlyBufferBuilder<DataModel::AttributeEntry> & builder)
{
    using namespace TS::Attributes;
    const bool heat = mConfig.heat, cool = mConfig.cool;
    const AttributeListBuilder::OptionalAttributeEntry optional[] = {
        { heat, OccupiedHeatingSetpoint::kMetadataEntry }, { heat, AbsMinHeatSetpointLimit::kMetadataEntry },
        { heat, AbsMaxHeatSetpointLimit::kMetadataEntry }, { heat, MinHeatSetpointLimit::kMetadataEntry },
        { heat, MaxHeatSetpointLimit::kMetadataEntry },    { cool, OccupiedCoolingSetpoint::kMetadataEntry },
        { cool, AbsMinCoolSetpointLimit::kMetadataEntry }, { cool, AbsMaxCoolSetpointLimit::kMetadataEntry },
        { cool, MinCoolSetpointLimit::kMetadataEntry },    { cool, MaxCoolSetpointLimit::kMetadataEntry },
    };
    AttributeListBuilder list(builder);
    return list.Append(Span(kMandatoryMetadata), Span(optional));
}

DataModel::ActionReturnStatus ThermostatBridgeCluster::ChangeSetpoint(int value)
{
    if (value < mConfig.minC100 || value > mConfig.maxC100)
        return Status::ConstraintError;
    if (SetAttributeValue(mSetpoint, static_cast<int16_t>(value), TS::Attributes::OccupiedHeatingSetpoint::Id))
    {
        NotifyAttributeChanged(TS::Attributes::OccupiedCoolingSetpoint::Id);
        mCallbacks.OnCommand(mPath.mEndpointId, kCmdSetpoint, value);
    }
    return Status::Success;
}

DataModel::ActionReturnStatus ThermostatBridgeCluster::WriteAttribute(const DataModel::WriteAttributeRequest & request,
                                                                      AttributeValueDecoder & decoder)
{
    using namespace TS::Attributes;
    switch (request.path.mAttributeId)
    {
    case SystemMode::Id: {
        TS::SystemModeEnum mode;
        ReturnErrorOnFailure(decoder.Decode(mode));
        const uint8_t m = to_underlying(mode);
        if (!ModeAllowed(m))
            return Status::ConstraintError;
        if (SetAttributeValue(mSystemMode, m, SystemMode::Id))
            mCallbacks.OnCommand(mPath.mEndpointId, kCmdSystemMode, m);
        return Status::Success;
    }
    case OccupiedHeatingSetpoint::Id:
    case OccupiedCoolingSetpoint::Id: {
        const bool supported = request.path.mAttributeId == OccupiedHeatingSetpoint::Id ? mConfig.heat : mConfig.cool;
        if (!supported)
            return Status::UnsupportedAttribute;
        int16_t value;
        ReturnErrorOnFailure(decoder.Decode(value));
        return ChangeSetpoint(value);
    }
    case ControlSequenceOfOperation::Id: {
        // Fest durch das Gerät vorgegeben – nur den bestehenden Wert akzeptieren
        TS::ControlSequenceOfOperationEnum value;
        ReturnErrorOnFailure(decoder.Decode(value));
        const auto current = mConfig.heat && mConfig.cool ? TS::ControlSequenceOfOperationEnum::kCoolingAndHeating
            : mConfig.heat                                 ? TS::ControlSequenceOfOperationEnum::kHeatingOnly
                                                           : TS::ControlSequenceOfOperationEnum::kCoolingOnly;
        return value == current ? Status::Success : Status::ConstraintError;
    }
    default:
        return Status::UnsupportedWrite;
    }
}

CHIP_ERROR ThermostatBridgeCluster::AcceptedCommands(const ConcreteClusterPath &,
                                                     ReadOnlyBufferBuilder<DataModel::AcceptedCommandEntry> & builder)
{
    static constexpr DataModel::AcceptedCommandEntry kCommands[] = { TS::Commands::SetpointRaiseLower::kMetadataEntry };
    return builder.ReferenceExisting(kCommands);
}

std::optional<DataModel::ActionReturnStatus> ThermostatBridgeCluster::InvokeCommand(const DataModel::InvokeRequest & request,
                                                                                    TLV::TLVReader & input, CommandHandler *)
{
    switch (request.path.mCommandId)
    {
    case TS::Commands::SetpointRaiseLower::Id: {
        TS::Commands::SetpointRaiseLower::DecodableType data;
        ReturnErrorOnFailure(data.Decode(input));
        // Betrag in 0,1 °C; raum. kennt einen gemeinsamen Sollwert für Heizen und Kühlen
        const int target = std::clamp(mSetpoint + data.amount * 10, static_cast<int>(mConfig.minC100), static_cast<int>(mConfig.maxC100));
        return ChangeSetpoint(target);
    }
    default:
        return Status::UnsupportedCommand;
    }
}

void ThermostatBridgeCluster::Update(int localC100, int setpointC100, int systemMode)
{
    if (localC100 == INT32_MIN)
        SetAttributeValue(mLocal, DataModel::NullNullable, TS::Attributes::LocalTemperature::Id);
    else
        SetAttributeValue(mLocal, static_cast<int16_t>(localC100), TS::Attributes::LocalTemperature::Id);
    if (setpointC100 != INT32_MIN)
    {
        const auto value = static_cast<int16_t>(std::clamp(setpointC100, static_cast<int>(mConfig.minC100), static_cast<int>(mConfig.maxC100)));
        if (SetAttributeValue(mSetpoint, value, TS::Attributes::OccupiedHeatingSetpoint::Id))
            NotifyAttributeChanged(TS::Attributes::OccupiedCoolingSetpoint::Id);
    }
    if (systemMode >= 0)
        SetAttributeValue(mSystemMode, static_cast<uint8_t>(systemMode), TS::Attributes::SystemMode::Id);
}

// === Storen (Window Covering) =======================================================================================

namespace WC = chip::app::Clusters::WindowCovering;

namespace {
constexpr uint16_t kFullyClosed = 10000;
}

CoverBridgeCluster::CoverBridgeCluster(EndpointId endpoint, BridgeCallbacks & callbacks) :
    DefaultServerCluster({ endpoint, WC::Id }), mCallbacks(callbacks)
{}

DataModel::ActionReturnStatus CoverBridgeCluster::ReadAttribute(const DataModel::ReadAttributeRequest & request,
                                                                AttributeValueEncoder & encoder)
{
    using namespace WC::Attributes;
    switch (request.path.mAttributeId)
    {
    case FeatureMap::Id: {
        BitFlags<WC::Feature> features(WC::Feature::kLift, WC::Feature::kPositionAwareLift);
        return encoder.Encode(features);
    }
    case ClusterRevision::Id:
        return encoder.Encode(WC::kRevision);
    case Type::Id:
        return encoder.Encode(WC::Type::kRollerShade);
    case EndProductType::Id:
        return encoder.Encode(WC::EndProductType::kRollerShade);
    case ConfigStatus::Id:
        return encoder.Encode(BitMask<WC::ConfigStatus>(WC::ConfigStatus::kOperational, WC::ConfigStatus::kLiftPositionAware));
    case OperationalStatus::Id:
        // Bits 0–1 global, 2–3 Hub – beide gleich: 0 steht, 1 öffnet, 2 schließt
        return encoder.Encode(static_cast<uint8_t>(mMovement | (mMovement << 2)));
    case Mode::Id:
        return encoder.Encode(BitMask<WC::Mode>(mMode));
    case CurrentPositionLiftPercent100ths::Id:
        return encoder.Encode(mCurrent);
    case TargetPositionLiftPercent100ths::Id:
        return encoder.Encode(mTarget);
    case CurrentPositionLiftPercentage::Id: {
        DataModel::Nullable<uint8_t> percent;
        if (!mCurrent.IsNull())
            percent.SetNonNull(static_cast<uint8_t>(mCurrent.Value() / 100));
        return encoder.Encode(percent);
    }
    default:
        return Status::UnsupportedAttribute;
    }
}

CHIP_ERROR CoverBridgeCluster::Attributes(const ConcreteClusterPath & path, ReadOnlyBufferBuilder<DataModel::AttributeEntry> & builder)
{
    using namespace WC::Attributes;
    const AttributeListBuilder::OptionalAttributeEntry optional[] = {
        { true, CurrentPositionLiftPercent100ths::kMetadataEntry },
        { true, TargetPositionLiftPercent100ths::kMetadataEntry },
        { true, CurrentPositionLiftPercentage::kMetadataEntry },
    };
    AttributeListBuilder list(builder);
    return list.Append(Span(kMandatoryMetadata), Span(optional));
}

DataModel::ActionReturnStatus CoverBridgeCluster::WriteAttribute(const DataModel::WriteAttributeRequest & request,
                                                                 AttributeValueDecoder & decoder)
{
    if (request.path.mAttributeId == WC::Attributes::Mode::Id)
    {
        BitMask<WC::Mode> mode;
        ReturnErrorOnFailure(decoder.Decode(mode));
        // Motorrichtung/Kalibrierung regelt das echte Gerät selbst – nur merken
        SetAttributeValue(mMode, mode.Raw(), WC::Attributes::Mode::Id);
        return Status::Success;
    }
    return Status::UnsupportedWrite;
}

CHIP_ERROR CoverBridgeCluster::AcceptedCommands(const ConcreteClusterPath &, ReadOnlyBufferBuilder<DataModel::AcceptedCommandEntry> & builder)
{
    static constexpr DataModel::AcceptedCommandEntry kCommands[] = {
        WC::Commands::UpOrOpen::kMetadataEntry,
        WC::Commands::DownOrClose::kMetadataEntry,
        WC::Commands::StopMotion::kMetadataEntry,
        WC::Commands::GoToLiftPercentage::kMetadataEntry,
    };
    return builder.ReferenceExisting(kCommands);
}

void CoverBridgeCluster::SetTarget(uint16_t target)
{
    SetAttributeValue(mTarget, target, WC::Attributes::TargetPositionLiftPercent100ths::Id);
}

std::optional<DataModel::ActionReturnStatus> CoverBridgeCluster::InvokeCommand(const DataModel::InvokeRequest & request,
                                                                               TLV::TLVReader & input, CommandHandler *)
{
    const EndpointId ep = mPath.mEndpointId;
    switch (request.path.mCommandId)
    {
    case WC::Commands::UpOrOpen::Id:
        SetTarget(0);
        mCallbacks.OnCommand(ep, kCmdCoverOpen, 0);
        return Status::Success;
    case WC::Commands::DownOrClose::Id:
        SetTarget(kFullyClosed);
        mCallbacks.OnCommand(ep, kCmdCoverClose, 0);
        return Status::Success;
    case WC::Commands::StopMotion::Id:
        if (!mCurrent.IsNull())
            SetTarget(mCurrent.Value());
        mCallbacks.OnCommand(ep, kCmdCoverStop, 0);
        return Status::Success;
    case WC::Commands::GoToLiftPercentage::Id: {
        WC::Commands::GoToLiftPercentage::DecodableType data;
        ReturnErrorOnFailure(data.Decode(input));
        const uint16_t closed = data.liftPercent100thsValue;
        if (closed > kFullyClosed)
            return Status::ConstraintError;
        SetTarget(closed);
        // raum. denkt in „Prozent offen“
        mCallbacks.OnCommand(ep, kCmdCoverGoTo, 100 - (closed + 50) / 100);
        return Status::Success;
    }
    default:
        return Status::UnsupportedCommand;
    }
}

void CoverBridgeCluster::Update(int closed100ths, int movement)
{
    if (closed100ths >= 0)
    {
        const auto value = static_cast<uint16_t>(std::min(closed100ths, static_cast<int>(kFullyClosed)));
        if (SetAttributeValue(mCurrent, value, WC::Attributes::CurrentPositionLiftPercent100ths::Id))
            NotifyAttributeChanged(WC::Attributes::CurrentPositionLiftPercentage::Id);
        if (mTarget.IsNull())
            SetTarget(value);
    }
    const auto m = static_cast<uint8_t>(std::clamp(movement, 0, 2));
    if (SetAttributeValue(mMovement, m, WC::Attributes::OperationalStatus::Id) && m == 0 && !mCurrent.IsNull())
        SetTarget(mCurrent.Value()); // angekommen oder gestoppt
}

// === Farbe (Color Control) =========================================================================================

namespace CC = chip::app::Clusters::ColorControl;

namespace {
constexpr uint8_t kMaxHueSat  = 254;
constexpr uint16_t kMaxXY     = 0xFEFF;
constexpr uint8_t kModeHS     = to_underlying(CC::ColorModeEnum::kCurrentHueAndCurrentSaturation);
constexpr uint8_t kModeXY     = to_underlying(CC::ColorModeEnum::kCurrentXAndCurrentY);
constexpr uint8_t kModeTemp   = to_underlying(CC::ColorModeEnum::kColorTemperatureMireds);

int Step(int value, CC::StepModeEnum mode, int size)
{
    return mode == CC::StepModeEnum::kDown ? value - size : value + size;
}
} // namespace

ColorBridgeCluster::ColorBridgeCluster(EndpointId endpoint, const DeviceConfig & config, BridgeCallbacks & callbacks) :
    DefaultServerCluster({ endpoint, CC::Id }), mConfig(config), mCallbacks(callbacks),
    mMode(config.color ? kModeHS : kModeTemp), mMireds(static_cast<uint16_t>((config.minMireds + config.maxMireds) / 2))
{}

uint16_t ColorBridgeCluster::Features() const
{
    uint16_t f = 0;
    if (mConfig.color)
        f |= to_underlying(CC::Feature::kHueAndSaturation) | to_underlying(CC::Feature::kXy);
    if (mConfig.colorTemperature)
        f |= to_underlying(CC::Feature::kColorTemperature);
    return f;
}

DataModel::ActionReturnStatus ColorBridgeCluster::ReadAttribute(const DataModel::ReadAttributeRequest & request,
                                                                AttributeValueEncoder & encoder)
{
    using namespace CC::Attributes;
    switch (request.path.mAttributeId)
    {
    case FeatureMap::Id:
        return encoder.Encode(static_cast<uint32_t>(Features()));
    case ClusterRevision::Id:
        return encoder.Encode(CC::kRevision);
    case ColorMode::Id:
        return encoder.Encode(static_cast<CC::ColorModeEnum>(mMode));
    case EnhancedColorMode::Id:
        return encoder.Encode(static_cast<CC::EnhancedColorModeEnum>(mMode));
    case Options::Id:
        return encoder.Encode(BitMask<CC::OptionsBitmap>(mOptions));
    case NumberOfPrimaries::Id:
        return encoder.Encode(DataModel::Nullable<uint8_t>());
    case ColorCapabilities::Id:
        return encoder.Encode(BitMask<CC::ColorCapabilitiesBitmap>(Features()));
    case RemainingTime::Id:
        return encoder.Encode(static_cast<uint16_t>(0));
    case CurrentHue::Id:
        return encoder.Encode(mHue);
    case CurrentSaturation::Id:
        return encoder.Encode(mSaturation);
    case CurrentX::Id:
        return encoder.Encode(mX);
    case CurrentY::Id:
        return encoder.Encode(mY);
    case ColorTemperatureMireds::Id:
        return encoder.Encode(mMireds);
    case ColorTempPhysicalMinMireds::Id:
    case CoupleColorTempToLevelMinMireds::Id:
        return encoder.Encode(mConfig.minMireds);
    case ColorTempPhysicalMaxMireds::Id:
        return encoder.Encode(mConfig.maxMireds);
    case StartUpColorTemperatureMireds::Id:
        return encoder.Encode(mStartUpMireds);
    default:
        return Status::UnsupportedAttribute;
    }
}

CHIP_ERROR ColorBridgeCluster::Attributes(const ConcreteClusterPath & path, ReadOnlyBufferBuilder<DataModel::AttributeEntry> & builder)
{
    using namespace CC::Attributes;
    const bool color = mConfig.color, temp = mConfig.colorTemperature;
    const AttributeListBuilder::OptionalAttributeEntry optional[] = {
        { true, RemainingTime::kMetadataEntry },
        { color, CurrentHue::kMetadataEntry },
        { color, CurrentSaturation::kMetadataEntry },
        { color, CurrentX::kMetadataEntry },
        { color, CurrentY::kMetadataEntry },
        { temp, ColorTemperatureMireds::kMetadataEntry },
        { temp, ColorTempPhysicalMinMireds::kMetadataEntry },
        { temp, ColorTempPhysicalMaxMireds::kMetadataEntry },
        { temp, CoupleColorTempToLevelMinMireds::kMetadataEntry },
        { temp, StartUpColorTemperatureMireds::kMetadataEntry },
    };
    AttributeListBuilder list(builder);
    return list.Append(Span(kMandatoryMetadata), Span(optional));
}

DataModel::ActionReturnStatus ColorBridgeCluster::WriteAttribute(const DataModel::WriteAttributeRequest & request,
                                                                 AttributeValueDecoder & decoder)
{
    using namespace CC::Attributes;
    switch (request.path.mAttributeId)
    {
    case Options::Id: {
        BitMask<CC::OptionsBitmap> options;
        ReturnErrorOnFailure(decoder.Decode(options));
        SetAttributeValue(mOptions, options.Raw(), Options::Id);
        return Status::Success;
    }
    case StartUpColorTemperatureMireds::Id: {
        if (!mConfig.colorTemperature)
            return Status::UnsupportedAttribute;
        DataModel::Nullable<uint16_t> value;
        ReturnErrorOnFailure(decoder.Decode(value));
        if (!value.IsNull() && (value.Value() < mConfig.minMireds || value.Value() > mConfig.maxMireds))
            return Status::ConstraintError;
        mStartUpMireds = value;
        NotifyAttributeChanged(StartUpColorTemperatureMireds::Id);
        return Status::Success;
    }
    default:
        return Status::UnsupportedWrite;
    }
}

CHIP_ERROR ColorBridgeCluster::AcceptedCommands(const ConcreteClusterPath &, ReadOnlyBufferBuilder<DataModel::AcceptedCommandEntry> & builder)
{
    using namespace CC::Commands;
    static constexpr DataModel::AcceptedCommandEntry kColor[] = {
        MoveToHue::kMetadataEntry, StepHue::kMetadataEntry,   MoveToSaturation::kMetadataEntry, StepSaturation::kMetadataEntry,
        MoveToHueAndSaturation::kMetadataEntry, MoveToColor::kMetadataEntry, StepColor::kMetadataEntry,
    };
    static constexpr DataModel::AcceptedCommandEntry kTemp[] = { MoveToColorTemperature::kMetadataEntry,
                                                                 StepColorTemperature::kMetadataEntry };
    static constexpr DataModel::AcceptedCommandEntry kStop[] = { StopMoveStep::kMetadataEntry };
    ReturnErrorOnFailure(builder.EnsureAppendCapacity((mConfig.color ? std::size(kColor) : 0) +
                                                      (mConfig.colorTemperature ? std::size(kTemp) : 0) + 1));
    if (mConfig.color)
        ReturnErrorOnFailure(builder.AppendElements(Span(kColor)));
    if (mConfig.colorTemperature)
        ReturnErrorOnFailure(builder.AppendElements(Span(kTemp)));
    return builder.AppendElements(Span(kStop));
}

void ColorBridgeCluster::SetMode(uint8_t mode)
{
    if (SetAttributeValue(mMode, mode, CC::Attributes::ColorMode::Id))
        NotifyAttributeChanged(CC::Attributes::EnhancedColorMode::Id);
}

void ColorBridgeCluster::SetHueSat(uint8_t hue, uint8_t saturation, bool notify)
{
    SetAttributeValue(mHue, std::min(hue, kMaxHueSat), CC::Attributes::CurrentHue::Id);
    SetAttributeValue(mSaturation, std::min(saturation, kMaxHueSat), CC::Attributes::CurrentSaturation::Id);
    if (notify)
    {
        SetMode(kModeHS);
        mCallbacks.OnCommand(mPath.mEndpointId, kCmdColorHS, (mHue << 8) | mSaturation);
    }
}

void ColorBridgeCluster::SetXY(uint16_t x, uint16_t y, bool notify)
{
    SetAttributeValue(mX, std::min(x, kMaxXY), CC::Attributes::CurrentX::Id);
    SetAttributeValue(mY, std::min(y, kMaxXY), CC::Attributes::CurrentY::Id);
    if (notify)
    {
        SetMode(kModeXY);
        mCallbacks.OnCommand(mPath.mEndpointId, kCmdColorXY, static_cast<int>((static_cast<uint32_t>(mX) << 16) | mY));
    }
}

void ColorBridgeCluster::SetMireds(uint16_t mireds, bool notify)
{
    SetAttributeValue(mMireds, std::clamp(mireds, mConfig.minMireds, mConfig.maxMireds), CC::Attributes::ColorTemperatureMireds::Id);
    if (notify)
    {
        SetMode(kModeTemp);
        mCallbacks.OnCommand(mPath.mEndpointId, kCmdColorTemp, mMireds);
    }
}

std::optional<DataModel::ActionReturnStatus> ColorBridgeCluster::InvokeCommand(const DataModel::InvokeRequest & request,
                                                                               TLV::TLVReader & input, CommandHandler *)
{
    using namespace CC::Commands;
    const bool color = mConfig.color, temp = mConfig.colorTemperature;
    switch (request.path.mCommandId)
    {
    case MoveToHue::Id: {
        VerifyOrReturnValue(color, Status::UnsupportedCommand);
        MoveToHue::DecodableType d;
        ReturnErrorOnFailure(d.Decode(input));
        VerifyOrReturnValue(d.hue <= kMaxHueSat, Status::ConstraintError);
        SetHueSat(d.hue, mSaturation, true);
        return Status::Success;
    }
    case MoveToSaturation::Id: {
        VerifyOrReturnValue(color, Status::UnsupportedCommand);
        MoveToSaturation::DecodableType d;
        ReturnErrorOnFailure(d.Decode(input));
        VerifyOrReturnValue(d.saturation <= kMaxHueSat, Status::ConstraintError);
        SetHueSat(mHue, d.saturation, true);
        return Status::Success;
    }
    case MoveToHueAndSaturation::Id: {
        VerifyOrReturnValue(color, Status::UnsupportedCommand);
        MoveToHueAndSaturation::DecodableType d;
        ReturnErrorOnFailure(d.Decode(input));
        VerifyOrReturnValue(d.hue <= kMaxHueSat && d.saturation <= kMaxHueSat, Status::ConstraintError);
        SetHueSat(d.hue, d.saturation, true);
        return Status::Success;
    }
    case StepHue::Id: {
        VerifyOrReturnValue(color, Status::UnsupportedCommand);
        StepHue::DecodableType d;
        ReturnErrorOnFailure(d.Decode(input));
        const int hue = ((Step(mHue, d.stepMode, d.stepSize) % 255) + 255) % 255; // Farbton läuft im Kreis
        SetHueSat(static_cast<uint8_t>(hue), mSaturation, true);
        return Status::Success;
    }
    case StepSaturation::Id: {
        VerifyOrReturnValue(color, Status::UnsupportedCommand);
        StepSaturation::DecodableType d;
        ReturnErrorOnFailure(d.Decode(input));
        SetHueSat(mHue, static_cast<uint8_t>(std::clamp(Step(mSaturation, d.stepMode, d.stepSize), 0, 254)), true);
        return Status::Success;
    }
    case MoveToColor::Id: {
        VerifyOrReturnValue(color, Status::UnsupportedCommand);
        MoveToColor::DecodableType d;
        ReturnErrorOnFailure(d.Decode(input));
        VerifyOrReturnValue(d.colorX <= kMaxXY && d.colorY <= kMaxXY, Status::ConstraintError);
        SetXY(d.colorX, d.colorY, true);
        return Status::Success;
    }
    case StepColor::Id: {
        VerifyOrReturnValue(color, Status::UnsupportedCommand);
        StepColor::DecodableType d;
        ReturnErrorOnFailure(d.Decode(input));
        SetXY(static_cast<uint16_t>(std::clamp(mX + d.stepX, 0, static_cast<int>(kMaxXY))),
              static_cast<uint16_t>(std::clamp(mY + d.stepY, 0, static_cast<int>(kMaxXY))), true);
        return Status::Success;
    }
    case MoveToColorTemperature::Id: {
        VerifyOrReturnValue(temp, Status::UnsupportedCommand);
        MoveToColorTemperature::DecodableType d;
        ReturnErrorOnFailure(d.Decode(input));
        SetMireds(d.colorTemperatureMireds, true);
        return Status::Success;
    }
    case StepColorTemperature::Id: {
        VerifyOrReturnValue(temp, Status::UnsupportedCommand);
        StepColorTemperature::DecodableType d;
        ReturnErrorOnFailure(d.Decode(input));
        int lo = mConfig.minMireds, hi = mConfig.maxMireds;
        if (d.colorTemperatureMinimumMireds != 0)
            lo = std::max(lo, static_cast<int>(d.colorTemperatureMinimumMireds));
        if (d.colorTemperatureMaximumMireds != 0)
            hi = std::min(hi, static_cast<int>(d.colorTemperatureMaximumMireds));
        SetMireds(static_cast<uint16_t>(std::clamp(Step(mMireds, d.stepMode, d.stepSize), lo, std::max(lo, hi))), true);
        return Status::Success;
    }
    case StopMoveStep::Id:
        // Übergänge laufen im Gerät – hier ist nichts anzuhalten
        return Status::Success;
    default:
        return Status::UnsupportedCommand;
    }
}

void ColorBridgeCluster::Update(int mode, int hue, int saturation, int x, int y, int mireds)
{
    if (mConfig.color && hue >= 0 && saturation >= 0)
        SetHueSat(static_cast<uint8_t>(hue), static_cast<uint8_t>(saturation), false);
    if (mConfig.color && x >= 0 && y >= 0)
        SetXY(static_cast<uint16_t>(x), static_cast<uint16_t>(y), false);
    if (mConfig.colorTemperature && mireds > 0)
        SetMireds(static_cast<uint16_t>(mireds), false);
    if (mode == kModeTemp && mConfig.colorTemperature)
        SetMode(kModeTemp);
    else if (mode == kModeHS && mConfig.color && mMode == kModeTemp)
        SetMode(kModeHS); // Farbe aktiv – HS oder XY, je nachdem, womit zuletzt gesteuert wurde
}

} // namespace raum
