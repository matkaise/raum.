package app.raum.automation.conditions

import app.raum.domain.models.Comparison
import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceProperty
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.LightCapability
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.SensorMetric
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.find

/** Liest Automations-relevante Werte aus dem Capability-Modell (keine Matter-Details). */
object DeviceReadings {

    /** null = Gerät hat diese Eigenschaft nicht oder ist offline (dann gilt nichts als erfüllt). */
    fun property(device: Device?, property: DeviceProperty): Boolean? {
        if (device == null || !device.isOnline) return null
        val caps = device.capabilities
        return when (property) {
            DeviceProperty.POWER -> caps.find<LightCapability>()?.isOn ?: caps.find<SwitchCapability>()?.isOn
            DeviceProperty.CONTACT_OPEN -> caps.find<ContactSensorCapability>()?.isOpen
            DeviceProperty.OCCUPIED -> caps.find<OccupancyCapability>()?.isOccupied
        }
    }

    fun metric(device: Device?, metric: SensorMetric): Double? {
        if (device == null || !device.isOnline) return null
        val caps = device.capabilities
        return when (metric) {
            SensorMetric.TEMPERATURE -> caps.find<TemperatureSensorCapability>()?.celsius ?: caps.find<ThermostatCapability>()?.currentCelsius
            SensorMetric.HUMIDITY -> caps.find<HumiditySensorCapability>()?.percent
        }
    }

    fun supports(device: Device, property: DeviceProperty): Boolean {
        val caps = device.capabilities
        return when (property) {
            DeviceProperty.POWER -> caps.find<LightCapability>() != null || caps.find<SwitchCapability>() != null
            DeviceProperty.CONTACT_OPEN -> caps.find<ContactSensorCapability>() != null
            DeviceProperty.OCCUPIED -> caps.find<OccupancyCapability>() != null
        }
    }

    fun supports(device: Device, metric: SensorMetric): Boolean {
        val caps = device.capabilities
        return when (metric) {
            SensorMetric.TEMPERATURE -> caps.find<TemperatureSensorCapability>() != null || caps.find<ThermostatCapability>() != null
            SensorMetric.HUMIDITY -> caps.find<HumiditySensorCapability>() != null
        }
    }

    fun compare(value: Double, comparison: Comparison, threshold: Double): Boolean = when (comparison) {
        Comparison.ABOVE -> value > threshold
        Comparison.BELOW -> value < threshold
    }
}
