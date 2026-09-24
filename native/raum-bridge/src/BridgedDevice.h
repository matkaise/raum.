/*
 * raum. Bridge – ein überbrücktes Gerät als Endpunkt (Matter-Spez. 9.13 Bridged Node).
 * Aufbau je nach Art: Leuchte, Steckdose, Kontakt-, Präsenz-, Temperatur-, Feuchtesensor, Thermostat, Storen.
 */
#pragma once

#include <app/clusters/boolean-state-server/BooleanStateCluster.h>
#include <app/clusters/bridged-device-basic-information-server/BridgedDeviceBasicInformationCluster.h>
#include <app/clusters/identify-server/IdentifyCluster.h>
#include <app/clusters/level-control/LevelControlCluster.h>
#include <app/clusters/level-control/LevelControlDelegate.h>
#include <app/clusters/occupancy-sensor-server/OccupancySensingCluster.h>
#include <app/clusters/on-off-server/OnOffCluster.h>
#include <app/clusters/on-off-server/OnOffDelegate.h>
#include <app/clusters/on-off-server/OnOffEffectDelegate.h>
#include <app/clusters/on-off-server/OnOffLightingCluster.h>
#include <app/clusters/relative-humidity-measurement-server/RelativeHumidityMeasurementCluster.h>
#include <app/clusters/temperature-measurement-server/TemperatureMeasurementCluster.h>
#include <app/server-cluster/ServerClusterInterfaceRegistry.h>
#include <devices/interface/SingleEndpointDevice.h>
#include <lib/support/TimerDelegate.h>

#include <string>

#include "BridgeTypes.h"
#include "RaumClusters.h"

namespace raum {

class BridgedDevice : public chip::app::SingleEndpointDevice,
                      public chip::app::Clusters::OnOffDelegate,
                      public chip::app::Clusters::LevelControlDelegate,
                      public chip::app::Clusters::OnOffEffectDelegate,
                      public chip::app::Clusters::BridgedDeviceBasicInformationDelegate
{
public:
    BridgedDevice(Kind kind, std::string uniqueId, std::string name, std::string vendor, std::string product,
                  const DeviceConfig & config, chip::TimerDelegate & timerDelegate, BridgeCallbacks & callbacks);
    ~BridgedDevice() override = default;

    CHIP_ERROR Register(chip::EndpointId endpoint, chip::app::CodeDrivenDataModelProvider & provider,
                        chip::EndpointId parentId) override;
    void Unregister(chip::app::CodeDrivenDataModelProvider & provider) override;

    /** Zustand aus raum. übernehmen – ohne ihn als Befehl zurückzumelden. */
    void Apply(const DeviceState & state);

    Kind GetKind() const { return mKind; }

    // OnOffDelegate
    void OnOffStartup(bool on) override {}
    void OnOnOffChanged(bool on) override;
    // LevelControlDelegate
    void OnLevelChanged(uint8_t level) override;

private:
    const Kind mKind;
    const std::string mUniqueId, mName, mVendor, mProduct;
    const DeviceConfig mConfig;
    chip::TimerDelegate & mTimerDelegate;
    BridgeCallbacks & mCallbacks;
    /** true, solange raum. Werte setzt – dann keine Rückmeldung als Befehl */
    bool mApplying = false;

    chip::app::LazyRegisteredServerCluster<chip::app::Clusters::IdentifyCluster> mIdentify;
    chip::app::LazyRegisteredServerCluster<chip::app::Clusters::BridgedDeviceBasicInformationCluster> mBasicInfo;
    chip::app::LazyRegisteredServerCluster<chip::app::Clusters::OnOffLightingCluster> mLightOnOff;
    chip::app::LazyRegisteredServerCluster<chip::app::Clusters::OnOffCluster> mPlugOnOff;
    chip::app::LazyRegisteredServerCluster<chip::app::Clusters::LevelControlCluster> mLevel;
    chip::app::LazyRegisteredServerCluster<chip::app::Clusters::BooleanStateCluster> mBooleanState;
    chip::app::LazyRegisteredServerCluster<chip::app::Clusters::OccupancySensingCluster> mOccupancy;
    chip::app::LazyRegisteredServerCluster<chip::app::Clusters::TemperatureMeasurementCluster> mTemperature;
    chip::app::LazyRegisteredServerCluster<chip::app::Clusters::RelativeHumidityMeasurementCluster> mHumidity;
    chip::app::LazyRegisteredServerCluster<ThermostatBridgeCluster> mThermostat;
    chip::app::LazyRegisteredServerCluster<CoverBridgeCluster> mCover;
    chip::app::LazyRegisteredServerCluster<ColorBridgeCluster> mColor;

    chip::app::Clusters::OnOffCluster * OnOff();
};

} // namespace raum
