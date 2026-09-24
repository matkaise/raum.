package app.raum.platform.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** Helligkeits- und Näherungssensor des Panels (Spez. 3.1). Fehlende Sensoren liefern null. */
class AmbientSensors(context: Context) {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val lightSensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    val hasLight: Boolean get() = lightSensor != null
    val hasProximity: Boolean get() = proximitySensor != null

    /** Umgebungslicht in Lux. */
    val lux: Flow<Float?> = sensorFlow(lightSensor) { it.values[0] }

    /** true = Objekt nah (Person vor dem Panel). */
    val near: Flow<Boolean?> = sensorFlow(proximitySensor) { e ->
        // Viele Sensoren melden nur 0 (nah) oder maximumRange (fern)
        e.values[0] < (proximitySensor?.maximumRange ?: 5f).coerceAtMost(5f)
    }.distinctUntilChanged()

    private fun <T> sensorFlow(sensor: Sensor?, map: (SensorEvent) -> T): Flow<T?> {
        if (sensor == null || manager == null) return flowOf(null)
        return callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) { trySend(map(event)) }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            awaitClose { manager.unregisterListener(listener) }
        }
    }
}
