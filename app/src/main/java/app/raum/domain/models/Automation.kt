package app.raum.domain.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Lokale Automation (Spez. 7.8): Trigger → Bedingungen → Aktionen (AUT-002).
 *
 * - Ein beliebiger Trigger löst aus (ODER).
 * - Alle Bedingungen müssen erfüllt sein (UND).
 * - Aktionen laufen der Reihe nach.
 *
 * Trigger, Bedingungen und Aktionen werden als JSON gespeichert; die [SerialName]s sind Speicherformat.
 */
data class Automation(
    val id: UUID,
    val name: String,
    val enabled: Boolean,
    val triggers: List<Trigger> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val actions: List<AutomationAction> = emptyList(),
) {
    /** Unvollständige Automationen (z. B. aus einer Migration) werden nie ausgeführt. */
    val isComplete: Boolean get() = triggers.isNotEmpty() && actions.isNotEmpty()
}

/** Wochentage nach ISO-8601: 1 = Montag … 7 = Sonntag. Leere Menge = jeden Tag. */
typealias Weekdays = Set<Int>

/** Binäre Gerätezustände, auf die Trigger und Bedingungen reagieren können. */
@Serializable
enum class DeviceProperty {
    /** Licht oder Zwischenstecker eingeschaltet */
    POWER,
    /** Kontaktsensor geöffnet */
    CONTACT_OPEN,
    /** Bewegung/Präsenz erkannt */
    OCCUPIED,
}

@Serializable
enum class SensorMetric { TEMPERATURE, HUMIDITY }

@Serializable
enum class Comparison { ABOVE, BELOW }

@Serializable
enum class SunEvent { SUNRISE, SUNSET }

@Serializable
sealed interface Trigger {
    /** Uhrzeit, optional nur an bestimmten Wochentagen. */
    @Serializable @SerialName("time")
    data class TimeOfDay(val minuteOfDay: Int, val weekdays: Weekdays = emptySet()) : Trigger

    /** Sonnenaufgang/-untergang am hinterlegten Standort, mit Versatz in Minuten. */
    @Serializable @SerialName("sun")
    data class Sun(val event: SunEvent, val offsetMinutes: Int = 0, val weekdays: Weekdays = emptySet()) : Trigger

    /** Gerätezustand wechselt auf [value] (flankengesteuert). */
    @Serializable @SerialName("device_state")
    data class DeviceStateChanged(
        @Serializable(with = UuidSerializer::class) val deviceId: UUID,
        val property: DeviceProperty,
        val value: Boolean,
    ) : Trigger

    /** Messwert über-/unterschreitet einen Grenzwert (nur beim Überschreiten, nicht dauerhaft). */
    @Serializable @SerialName("sensor_threshold")
    data class SensorThreshold(
        @Serializable(with = UuidSerializer::class) val deviceId: UUID,
        val metric: SensorMetric,
        val comparison: Comparison,
        val threshold: Double,
    ) : Trigger

    /** Gerät wird erreichbar ([online] = true) oder nicht erreichbar. */
    @Serializable @SerialName("connectivity")
    data class Connectivity(
        @Serializable(with = UuidSerializer::class) val deviceId: UUID,
        val online: Boolean,
    ) : Trigger

    @Serializable @SerialName("system_start")
    data object SystemStart : Trigger
}

@Serializable
sealed interface Condition {
    /** Zeitfenster [fromMinute, toMinute); darf über Mitternacht gehen (22:00–06:00). */
    @Serializable @SerialName("time_window")
    data class TimeWindow(val fromMinute: Int, val toMinute: Int) : Condition

    @Serializable @SerialName("weekdays")
    data class OnWeekdays(val weekdays: Weekdays) : Condition

    @Serializable @SerialName("device_state")
    data class DeviceStateIs(
        @Serializable(with = UuidSerializer::class) val deviceId: UUID,
        val property: DeviceProperty,
        val value: Boolean,
    ) : Condition

    @Serializable @SerialName("sensor_value")
    data class SensorValue(
        @Serializable(with = UuidSerializer::class) val deviceId: UUID,
        val metric: SensorMetric,
        val comparison: Comparison,
        val threshold: Double,
    ) : Condition
}

@Serializable
sealed interface AutomationAction {
    /** Gerät in einen Zielzustand bringen (Befehlsfolge wie bei Szenen). */
    @Serializable @SerialName("control_device")
    data class ControlDevice(
        @Serializable(with = UuidSerializer::class) val deviceId: UUID,
        val commands: List<DeviceCommand>,
    ) : AutomationAction

    @Serializable @SerialName("run_scene")
    data class RunScene(@Serializable(with = UuidSerializer::class) val sceneId: UUID) : AutomationAction

    @Serializable @SerialName("delay")
    data class Delay(val seconds: Int) : AutomationAction

    /** Lokale Benachrichtigung auf dem Panel. */
    @Serializable @SerialName("notify")
    data class Notify(val message: String) : AutomationAction
}

object AutomationLimits {
    const val MAX_DELAY_SECONDS = 24 * 60 * 60
}
