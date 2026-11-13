package app.raum.ui.automations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.outlined.Window
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.raum.R
import app.raum.automation.AutomationDescriber
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.Comparison
import app.raum.domain.models.Condition
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceProperty
import app.raum.domain.models.LightCapability
import app.raum.domain.models.SensorMetric
import app.raum.domain.models.SunEvent
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.Trigger
import app.raum.domain.models.Weekdays
import app.raum.domain.models.find
import app.raum.i18n.Strings
import java.util.UUID

/** Art eines Schritts – bestimmt Symbol und Farbe in Liste, Editor und Auswahlfenstern. */
enum class StepKind {
    TIME, SUN, MOTION, CONTACT, POWER, TEMPERATURE, HUMIDITY, CONNECTIVITY, START, WEEKDAYS,
    LIGHT, COVER, SWITCH, THERMOSTAT, DEVICE, SCENE, DELAY, NOTIFY,
}

/** Kurzform eines Schritts: Titel für Karte und Kette, Untertitel für die Editorkarte. */
data class StepLabel(val kind: StepKind, val title: String, val subtitle: String)

class StepStyle(val bg: Brush, val fg: Color, val icon: ImageVector)

private val Ink = Color(0xFF10131F)

private fun solid(bg: Long, fg: Long, icon: ImageVector) = StepStyle(Brush.linearGradient(listOf(Color(bg), Color(bg))), Color(fg), icon)

fun StepKind.style(): StepStyle = when (this) {
    StepKind.TIME -> solid(0xFFE8E6F5, 0xFF3D3A6C, Icons.Outlined.Schedule)
    StepKind.WEEKDAYS -> solid(0xFFE8E6F5, 0xFF3D3A6C, Icons.Outlined.Event)
    StepKind.SUN -> solid(0xFFFFF1C4, 0xFF9A6424, Icons.Outlined.WbTwilight)
    StepKind.MOTION -> solid(0xFFFFF3DC, 0xFF9A6424, Icons.Outlined.DirectionsWalk)
    StepKind.CONTACT -> solid(0xFFFFE0CC, 0xFFB4561F, Icons.Outlined.Window)
    StepKind.POWER -> solid(0xFFFFF3DC, 0xFF9A6424, Icons.Outlined.PowerSettingsNew)
    StepKind.TEMPERATURE -> solid(0xFFE3F4FC, 0xFF1D6F99, Icons.Outlined.Thermostat)
    StepKind.HUMIDITY -> solid(0xFFE3F4FC, 0xFF1D6F99, Icons.Outlined.WaterDrop)
    StepKind.CONNECTIVITY -> solid(0xFFFDE2DF, 0xFFB3261E, Icons.Outlined.WifiOff)
    StepKind.START -> solid(0xFFE9FBEF, 0xFF2F6B45, Icons.Outlined.RestartAlt)
    StepKind.LIGHT -> solid(0xFFFFF3DC, 0xFF7A5A2C, Icons.Outlined.Lightbulb)
    StepKind.COVER -> solid(0xFFD6ECFF, 0xFF1D6F99, Icons.Outlined.Blinds)
    StepKind.SWITCH -> solid(0xFFE9FBEF, 0xFF2F6B45, Icons.Outlined.Power)
    StepKind.THERMOSTAT -> solid(0xFFFFE0CC, 0xFFB4561F, Icons.Outlined.Thermostat)
    StepKind.DEVICE -> StepStyle(Brush.linearGradient(listOf(Ink.copy(alpha = 0.07f), Ink.copy(alpha = 0.07f))), Ink, Icons.Outlined.Sensors)
    StepKind.SCENE -> StepStyle(Brush.linearGradient(listOf(Color(0xFF3F4A86), Color(0xFFC98A74))), Color.White, Icons.Outlined.AutoAwesome)
    StepKind.DELAY -> StepStyle(Brush.linearGradient(listOf(Ink.copy(alpha = 0.07f), Ink.copy(alpha = 0.07f))), Ink, Icons.Outlined.HourglassEmpty)
    StepKind.NOTIFY -> StepStyle(Brush.linearGradient(listOf(Ink.copy(alpha = 0.07f), Ink.copy(alpha = 0.07f))), Ink, Icons.Outlined.ChatBubbleOutline)
}

/** Symbol für Abläufe ohne Auslöser. */
val FallbackFlowIcon: ImageVector = Icons.Outlined.AutoMode

/**
 * Kurze Beschriftungen der Schritte („06:30 · Mo–Fr“, „Bewegung Flur · Bewegung erkannt“) –
 * der ganze Satz steht weiterhin über [AutomationDescriber.sentence].
 */
class FlowLabels(
    private val devices: Map<UUID, Device>,
    private val d: AutomationDescriber,
    private val strings: Strings,
    private val roomName: (UUID?) -> String? = { null },
) {
    private fun s(id: Int, vararg args: Any) = strings.get(id, *args)
    private fun name(id: UUID) = devices[id]?.displayName ?: s(R.string.auto_deleted_device)
    private fun days(w: Weekdays) = if (w.isEmpty() || w.size == 7) "" else " · " + d.weekdays(w)
    private fun cmp(c: Comparison) = if (c == Comparison.ABOVE) ">" else "<"

    fun trigger(t: Trigger): StepLabel = when (t) {
        is Trigger.TimeOfDay -> StepLabel(StepKind.TIME, d.time(t.minuteOfDay) + days(t.weekdays), s(R.string.trigger_type_time))
        is Trigger.Sun -> StepLabel(StepKind.SUN, sunTitle(t.event, t.offsetMinutes) + days(t.weekdays), s(R.string.trigger_type_sun))
        is Trigger.DeviceStateChanged -> StepLabel(
            propertyKind(t.property),
            name(t.deviceId) + " · " + s(transitionRes(t.property, t.value)),
            listOfNotNull(roomName(devices[t.deviceId]?.roomId), s(R.string.trigger_type_device)).joinToString(" · "),
        )
        is Trigger.SensorThreshold -> StepLabel(
            metricKind(t.metric), "${name(t.deviceId)} ${cmp(t.comparison)} ${d.value(t.metric, t.threshold)}", s(R.string.trigger_type_sensor),
        )
        is Trigger.Connectivity -> StepLabel(
            StepKind.CONNECTIVITY,
            name(t.deviceId) + " " + s(if (t.online) R.string.connectivity_back else R.string.connectivity_lost),
            s(R.string.trigger_type_connectivity),
        )
        Trigger.SystemStart -> StepLabel(StepKind.START, s(R.string.trigger_type_start), s(R.string.auto_trigger_system_start))
    }

    fun condition(c: Condition): StepLabel = when (c) {
        is Condition.TimeWindow -> StepLabel(
            StepKind.TIME, "${d.time(c.fromMinute)} – ${d.time(c.toMinute)}",
            s(R.string.condition_type_time) + if (c.fromMinute > c.toMinute) " · " + s(R.string.over_midnight_short) else "",
        )
        is Condition.OnWeekdays -> StepLabel(StepKind.WEEKDAYS, d.weekdays(c.weekdays).replaceFirstChar { it.uppercase() }, s(R.string.weekdays))
        is Condition.DeviceStateIs -> StepLabel(
            propertyKind(c.property), name(c.deviceId) + " · " + d.state(c.property, c.value),
            listOfNotNull(roomName(devices[c.deviceId]?.roomId), s(R.string.trigger_type_device)).joinToString(" · "),
        )
        is Condition.SensorValue -> StepLabel(
            metricKind(c.metric), "${name(c.deviceId)} ${cmp(c.comparison)} ${d.value(c.metric, c.threshold)}", s(R.string.trigger_type_sensor),
        )
    }

    fun action(a: AutomationAction): StepLabel {
        val title = d.action(a).replaceFirstChar { it.uppercase() }
        return when (a) {
            is AutomationAction.ControlDevice -> {
                val device = devices[a.deviceId]
                StepLabel(deviceKind(device), title, listOfNotNull(s(R.string.action_type_device), roomName(device?.roomId)).joinToString(" · "))
            }
            is AutomationAction.RunScene -> StepLabel(StepKind.SCENE, title, s(R.string.action_type_scene))
            is AutomationAction.Delay -> StepLabel(StepKind.DELAY, title, s(R.string.action_type_delay))
            is AutomationAction.Notify -> StepLabel(StepKind.NOTIFY, title, s(R.string.action_type_notify))
        }
    }

    private fun sunTitle(event: SunEvent, offset: Int): String {
        val base = s(if (event == SunEvent.SUNRISE) R.string.auto_sunrise else R.string.auto_sunset)
        return when {
            offset > 0 -> "$base +${d.duration(offset * 60)}"
            offset < 0 -> "$base −${d.duration(-offset * 60)}"
            else -> base
        }
    }

    private fun transitionRes(p: DeviceProperty, value: Boolean) = when (p) {
        DeviceProperty.POWER -> if (value) R.string.value_turned_on else R.string.value_turned_off
        DeviceProperty.CONTACT_OPEN -> if (value) R.string.auto_state_open else R.string.auto_state_closed
        DeviceProperty.OCCUPIED -> if (value) R.string.value_motion else R.string.value_no_motion
    }

    companion object {
        fun propertyKind(p: DeviceProperty) = when (p) {
            DeviceProperty.POWER -> StepKind.POWER
            DeviceProperty.CONTACT_OPEN -> StepKind.CONTACT
            DeviceProperty.OCCUPIED -> StepKind.MOTION
        }

        fun metricKind(m: SensorMetric) = if (m == SensorMetric.TEMPERATURE) StepKind.TEMPERATURE else StepKind.HUMIDITY

        fun deviceKind(device: Device?): StepKind {
            val c = device?.capabilities ?: return StepKind.DEVICE
            return when {
                c.find<LightCapability>() != null -> StepKind.LIGHT
                c.find<CoverCapability>() != null -> StepKind.COVER
                c.find<ThermostatCapability>() != null -> StepKind.THERMOSTAT
                c.find<SwitchCapability>() != null -> StepKind.SWITCH
                else -> StepKind.DEVICE
            }
        }
    }
}
