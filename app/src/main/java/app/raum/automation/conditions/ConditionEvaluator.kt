package app.raum.automation.conditions

import app.raum.domain.models.Condition
import app.raum.domain.models.Device
import java.time.ZonedDateTime
import java.util.UUID

/** Bewertet Bedingungen (UND-verknüpft). Reine Funktion – gut testbar. */
object ConditionEvaluator {

    data class Result(val satisfied: Boolean, val failed: Condition? = null)

    fun evaluate(conditions: List<Condition>, now: ZonedDateTime, devices: Map<UUID, Device>): Result {
        for (c in conditions) {
            if (!isSatisfied(c, now, devices)) return Result(false, c)
        }
        return Result(true)
    }

    fun isSatisfied(condition: Condition, now: ZonedDateTime, devices: Map<UUID, Device>): Boolean = when (condition) {
        is Condition.TimeWindow -> inWindow(now.hour * 60 + now.minute, condition.fromMinute, condition.toMinute)
        is Condition.OnWeekdays -> condition.weekdays.isEmpty() || now.dayOfWeek.value in condition.weekdays
        is Condition.DeviceStateIs -> DeviceReadings.property(devices[condition.deviceId], condition.property) == condition.value
        is Condition.SensorValue -> DeviceReadings.metric(devices[condition.deviceId], condition.metric)
            ?.let { DeviceReadings.compare(it, condition.comparison, condition.threshold) } ?: false
    }

    /** [from, to); bei from > to über Mitternacht; from == to bedeutet ganztägig. */
    fun inWindow(minute: Int, from: Int, to: Int): Boolean = when {
        from == to -> true
        from < to -> minute in from until to
        else -> minute >= from || minute < to
    }
}
