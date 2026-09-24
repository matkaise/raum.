/*
 * raum. Bridge – überbrückte Geräte (siehe BridgedDevice.h).
 */
#include "BridgedDevice.h"

#include <devices/Types.h>
#include <lib/support/logging/CHIPLogging.h>

using namespace chip;
using namespace chip::app;
using namespace chip::app::Clusters;

namespace raum {
namespace {

// Gerätetypen je Art – jeweils plus „Bridged Node“. Statisch, weil der Endpunkt nur eine Sicht darauf hält.
constexpr DataModel::DeviceTypeEntry kOnOffLightTypes[]       = { Device::Type::kOnOffLight, Device::Type::kBridgedNode };
constexpr DataModel::DeviceTypeEntry kDimmableLightTypes[]    = { Device::Type::kDimmableLight, Device::Type::kBridgedNode };
constexpr DataModel::DeviceTypeEntry kPlugTypes[]             = { Device::Type::kOnOffPlugInUnit, Device::Type::kBridgedNode };
constexpr DataModel::DeviceTypeEntry kContactTypes[]          = { Device::Type::kContactSensor, Device::Type::kBridgedNode };
constexpr DataModel::DeviceTypeEntry kOccupancyTypes[]        = { Device::Type::kOccupancySensor, Device::Type::kBridgedNode };
constexpr DataModel::DeviceTypeEntry kTemperatureTypes[]      = { Device::Type::kTemperatureSensor, Device::Type::kBridgedNode };
constexpr DataModel::DeviceTypeEntry kHumidityTypes[]         = { Device::Type::kHumiditySensor, Device::Type::kBridgedNode };
constexpr DataModel::DeviceTypeEntry kTempHumidityTypes[]     = { Device::Type::kTemperatureSensor, Device::Type::kHumiditySensor,
                                                                  Device::Type::kBridgedNode };
constexpr DataModel::DeviceTypeEntry kThermostatTypes[]       = { Device::Type::kThermostat, Device::Type::kBridgedNode };
constexpr DataModel::DeviceTypeEntry kCoverTypes[]            = { Device::Type::kWindowCovering, Device::Type::kBridgedNode };
constexpr DataModel::DeviceTypeEntry kColorLightTypes[]       = { Device::Type::kExtendedColorLight, Device::Type::kBridgedNode };
constexpr DataModel::DeviceTypeEntry kColorTempLightTypes[]   = { Device::Type::kColorTemperatureLight, Device::Type::kBridgedNode };

Span<const DataModel::DeviceTypeEntry> TypesFor(Kind kind)
{
    switch (kind)
    {
    case Kind::kOnOffLight:
        return Span<const DataModel::DeviceTypeEntry>(kOnOffLightTypes);
    case Kind::kDimmableLight:
        return Span<const DataModel::DeviceTypeEntry>(kDimmableLightTypes);
    case Kind::kOnOffPlug:
        return Span<const DataModel::DeviceTypeEntry>(kPlugTypes);
    case Kind::kContactSensor:
        return Span<const DataModel::DeviceTypeEntry>(kContactTypes);
    case Kind::kOccupancySensor:
        return Span<const DataModel::DeviceTypeEntry>(kOccupancyTypes);
    case Kind::kTemperatureSensor:
        return Span<const DataModel::DeviceTypeEntry>(kTemperatureTypes);
    case Kind::kHumiditySensor:
        return Span<const DataModel::DeviceTypeEntry>(kHumidityTypes);
    case Kind::kThermostat:
        return Span<const DataModel::DeviceTypeEntry>(kThermostatTypes);
    case Kind::kCover:
        return Span<const DataModel::DeviceTypeEntry>(kCoverTypes);
    case Kind::kColorLight:
        return Span<const DataModel::DeviceTypeEntry>(kColorLightTypes);
    case Kind::kColorTempLight:
        return Span<const DataModel::DeviceTypeEntry>(kColorTempLightTypes);
    case Kind::kTempHumiditySensor:
    default:
        return Span<const DataModel::DeviceTypeEntry>(kTempHumidityTypes);
    }
}

bool HasTemperature(Kind k)
{
    return k == Kind::kTemperatureSensor || k == Kind::kTempHumiditySensor;
}
bool HasHumidity(Kind k)
{
    return k == Kind::kHumiditySensor || k == Kind::kTempHumiditySensor;
}

template <typename T>
void RemoveIfConstructed(CodeDrivenDataModelProvider & provider, LazyRegisteredServerCluster<T> & cluster)
{
    if (cluster.IsConstructed())
    {
        LogErrorOnFailure(provider.RemoveCluster(&cluster.Cluster()));
        cluster.Destroy();
    }
}

} // namespace

BridgedDevice::BridgedDevice(Kind kind, std::string uniqueId, std::string name, std::string vendor, std::string product,
                             const DeviceConfig & config, TimerDelegate & timerDelegate, BridgeCallbacks & callbacks) :
    SingleEndpointDevice(TypesFor(kind)),
    mKind(kind), mUniqueId(std::move(uniqueId)), mName(std::move(name)), mVendor(std::move(vendor)), mProduct(std::move(product)),
    mConfig(config), mTimerDelegate(timerDelegate), mCallbacks(callbacks)
{}

OnOffCluster * BridgedDevice::OnOff()
{
    if (mLightOnOff.IsConstructed())
        return &mLightOnOff.Cluster();
    if (mPlugOnOff.IsConstructed())
        return &mPlugOnOff.Cluster();
    return nullptr;
}

CHIP_ERROR BridgedDevice::Register(EndpointId endpoint, CodeDrivenDataModelProvider & provider, EndpointId parentId)
{
    ReturnErrorOnFailure(SingleEndpointRegistration(endpoint, provider, parentId));

    mIdentify.Create(IdentifyCluster::Config(endpoint, mTimerDelegate));
    ReturnErrorOnFailure(provider.AddCluster(mIdentify.Registration()));

    // Name, Hersteller, Erreichbarkeit – so zeigen andere Apps das Gerät an
    BridgedDeviceBasicInformationCluster::FixedData fixed;
    fixed.uniqueId = mUniqueId;
    if (!mVendor.empty())
        fixed.vendorName = mVendor;
    if (!mProduct.empty())
        fixed.productName = mProduct;
    BridgedDeviceBasicInformationCluster::MutableData mutableData;
    mutableData.reachable = true;
    mutableData.nodeLabel = mName.substr(0, 32);
    mBasicInfo.Create(endpoint, std::move(mutableData), std::move(fixed),
                      BridgedDeviceBasicInformationCluster::Context{ .delegate = *this, .timerDelegate = mTimerDelegate });
    ReturnErrorOnFailure(provider.AddCluster(mBasicInfo.Registration()));

    switch (mKind)
    {
    case Kind::kOnOffLight:
    case Kind::kDimmableLight:
    case Kind::kColorLight:
    case Kind::kColorTempLight: {
        mLightOnOff.Create(endpoint, OnOffLightingCluster::Context{ .timerDelegate = mTimerDelegate, .effectDelegate = *this });
        mLightOnOff.Cluster().AddDelegate(this);
        ReturnErrorOnFailure(provider.AddCluster(mLightOnOff.Registration()));
        if (mKind != Kind::kOnOffLight) // Farb- und Weißtonleuchten sind immer dimmbar (Pflicht laut Gerätetyp)
        {
            LevelControlCluster::Config config(endpoint, mTimerDelegate, *this);
            config.WithOnOff(mLightOnOff.Cluster());
            config.WithLighting(DataModel::NullNullable);
            mLevel.Create(config);
            mLightOnOff.Cluster().AddDelegate(&mLevel.Cluster());
            ReturnErrorOnFailure(provider.AddCluster(mLevel.Registration()));
        }
        if (mKind == Kind::kColorLight || mKind == Kind::kColorTempLight)
        {
            mColor.Create(endpoint, mConfig, mCallbacks);
            ReturnErrorOnFailure(provider.AddCluster(mColor.Registration()));
        }
        break;
    }
    case Kind::kOnOffPlug:
        mPlugOnOff.Create(endpoint, OnOffCluster::Context{ .timerDelegate = mTimerDelegate });
        mPlugOnOff.Cluster().AddDelegate(this);
        ReturnErrorOnFailure(provider.AddCluster(mPlugOnOff.Registration()));
        break;
    case Kind::kContactSensor:
        mBooleanState.Create(endpoint);
        ReturnErrorOnFailure(provider.AddCluster(mBooleanState.Registration()));
        break;
    case Kind::kOccupancySensor:
        mOccupancy.Create(OccupancySensingCluster::Config(endpoint).WithFeatures(OccupancySensing::Feature::kOther));
        ReturnErrorOnFailure(provider.AddCluster(mOccupancy.Registration()));
        break;
    case Kind::kThermostat:
        mThermostat.Create(endpoint, mConfig, mCallbacks);
        ReturnErrorOnFailure(provider.AddCluster(mThermostat.Registration()));
        break;
    case Kind::kCover:
        mCover.Create(endpoint, mCallbacks);
        ReturnErrorOnFailure(provider.AddCluster(mCover.Registration()));
        break;
    default:
        break;
    }
    if (HasTemperature(mKind))
    {
        TemperatureMeasurementCluster::StartupConfiguration config;
        config.minMeasuredValue.SetNonNull(static_cast<int16_t>(-4000));
        config.maxMeasuredValue.SetNonNull(static_cast<int16_t>(8500));
        mTemperature.Create(endpoint, TemperatureMeasurementCluster::OptionalAttributeSet(0), config);
        ReturnErrorOnFailure(provider.AddCluster(mTemperature.Registration()));
    }
    if (HasHumidity(mKind))
    {
        RelativeHumidityMeasurementCluster::Config config;
        config.minMeasuredValue.SetNonNull(static_cast<uint16_t>(0));
        config.maxMeasuredValue.SetNonNull(static_cast<uint16_t>(10000));
        mHumidity.Create(endpoint, config);
        ReturnErrorOnFailure(provider.AddCluster(mHumidity.Registration()));
    }
    return provider.AddEndpoint(mEndpointRegistration);
}

void BridgedDevice::Unregister(CodeDrivenDataModelProvider & provider)
{
    SingleEndpointUnregistration(provider);
    if (mLevel.IsConstructed() && mLightOnOff.IsConstructed())
        mLightOnOff.Cluster().RemoveDelegate(&mLevel.Cluster());
    RemoveIfConstructed(provider, mLevel);
    if (mLightOnOff.IsConstructed())
        mLightOnOff.Cluster().RemoveDelegate(this);
    if (mPlugOnOff.IsConstructed())
        mPlugOnOff.Cluster().RemoveDelegate(this);
    RemoveIfConstructed(provider, mLightOnOff);
    RemoveIfConstructed(provider, mPlugOnOff);
    RemoveIfConstructed(provider, mBooleanState);
    RemoveIfConstructed(provider, mOccupancy);
    RemoveIfConstructed(provider, mTemperature);
    RemoveIfConstructed(provider, mHumidity);
    RemoveIfConstructed(provider, mThermostat);
    RemoveIfConstructed(provider, mCover);
    RemoveIfConstructed(provider, mColor);
    RemoveIfConstructed(provider, mBasicInfo);
    RemoveIfConstructed(provider, mIdentify);
}

void BridgedDevice::Apply(const DeviceState & state)
{
    mApplying = true;
    if (mBasicInfo.IsConstructed() && mBasicInfo.Cluster().GetReachable() != state.reachable)
        mBasicInfo.Cluster().SetReachable(state.reachable);
    if (auto * onOff = OnOff(); onOff != nullptr && onOff->GetOnOff() != state.on)
        LogErrorOnFailure(onOff->SetOnOff(state.on));
    if (mLevel.IsConstructed() && state.level > 0)
    {
        auto current = mLevel.Cluster().GetCurrentLevel();
        auto target  = static_cast<uint8_t>(state.level);
        if (current.IsNull() || current.Value() != target)
        {
            // Ohne Übergang, ohne Ein/Aus-Kopplung: nur den Wert spiegeln
            (void) mLevel.Cluster().MoveToLevel(target, DataModel::MakeNullable<uint16_t>(0),
                                                BitMask<LevelControl::OptionsBitmap>(LevelControl::OptionsBitmap::kExecuteIfOff),
                                                BitMask<LevelControl::OptionsBitmap>(LevelControl::OptionsBitmap::kExecuteIfOff));
        }
    }
    if (mBooleanState.IsConstructed())
        (void) mBooleanState.Cluster().SetStateValue(state.contact);
    if (mOccupancy.IsConstructed())
        mOccupancy.Cluster().SetOccupancy(state.occupied);
    if (mThermostat.IsConstructed())
        mThermostat.Cluster().Update(state.tempC100, state.setpointC100, state.systemMode);
    if (mCover.IsConstructed())
        mCover.Cluster().Update(state.coverClosed100ths, state.coverMovement);
    if (mColor.IsConstructed())
        mColor.Cluster().Update(state.colorMode, state.hue, state.saturation, state.colorX, state.colorY, state.mireds);
    if (mTemperature.IsConstructed() && state.tempC100 != INT32_MIN)
        LogErrorOnFailure(mTemperature.Cluster().SetMeasuredValue(DataModel::MakeNullable(static_cast<int16_t>(state.tempC100))));
    if (mHumidity.IsConstructed() && state.humidity100 >= 0)
        LogErrorOnFailure(mHumidity.Cluster().SetMeasuredValue(DataModel::MakeNullable(static_cast<uint16_t>(state.humidity100))));
    mApplying = false;
}

void BridgedDevice::OnOnOffChanged(bool on)
{
    if (!mApplying)
        mCallbacks.OnOnOff(mEndpointId, on);
}

void BridgedDevice::OnLevelChanged(uint8_t level)
{
    if (!mApplying)
        mCallbacks.OnLevel(mEndpointId, level);
}

} // namespace raum
