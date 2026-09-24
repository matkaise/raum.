package app.raum.ui.automations

import app.raum.R
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource

import app.raum.ui.components.currentLocale
import app.raum.ui.components.Units
import app.raum.ui.components.LocalTemperatureUnit
import org.koin.compose.koinInject
import app.raum.i18n.Strings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.raum.automation.AutomationDescriber
import app.raum.automation.conditions.DeviceReadings
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.AutomationLimits
import app.raum.domain.models.Comparison
import app.raum.domain.models.Condition
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceProperty
import app.raum.domain.models.Scene
import app.raum.domain.models.SensorMetric
import app.raum.domain.models.SunEvent
import app.raum.domain.models.Trigger
import app.raum.domain.models.Weekdays
import app.raum.domain.usecases.DeviceTarget
import app.raum.domain.usecases.SceneTargets
import app.raum.ui.components.RaumDialog
import app.raum.ui.components.TargetEditor
import java.util.UUID

/** Gemeinsamer Kontext der Dialoge. */
class EditorContext(val devices: List<Device>, val roomName: (UUID?) -> String?, val scenes: List<Scene>, val hasLocation: Boolean)

private val PROPERTY_LABEL = mapOf(
    DeviceProperty.POWER to R.string.property_power,
    DeviceProperty.CONTACT_OPEN to R.string.property_contact,
    DeviceProperty.OCCUPIED to R.string.property_occupancy,
)

private fun valueLabels(p: DeviceProperty): List<Pair<Boolean, Int>> = when (p) {
    DeviceProperty.POWER -> listOf(true to R.string.value_turned_on, false to R.string.value_turned_off)
    DeviceProperty.CONTACT_OPEN -> listOf(true to R.string.auto_state_open, false to R.string.auto_state_closed)
    DeviceProperty.OCCUPIED -> listOf(true to R.string.value_motion, false to R.string.value_no_motion)
}

private fun stateLabels(p: DeviceProperty): List<Pair<Boolean, Int>> = when (p) {
    DeviceProperty.POWER -> listOf(true to R.string.auto_state_on, false to R.string.auto_state_off)
    DeviceProperty.CONTACT_OPEN -> listOf(true to R.string.auto_state_open, false to R.string.auto_state_closed)
    DeviceProperty.OCCUPIED -> listOf(true to R.string.auto_state_occupied, false to R.string.auto_state_clear)
}

/** Löst Ressourcen-Beschriftungen für [ChoiceChips] auf. */
@Composable
private fun <T> labeled(options: List<Pair<T, Int>>): List<Pair<T, String>> = options.map { (v, res) -> v to stringResource(res) }

/** Gerät + Eigenschaft + Wert – für Trigger „Gerätezustand“ und Bedingung „Gerätezustand“. */
@Composable
private fun DeviceStateFields(
    ctx: EditorContext,
    deviceId: UUID?, property: DeviceProperty, value: Boolean,
    labels: (DeviceProperty) -> List<Pair<Boolean, Int>>,
    onChange: (UUID?, DeviceProperty, Boolean) -> Unit,
) {
    val candidates = ctx.devices.filter { d -> DeviceProperty.entries.any { DeviceReadings.supports(d, it) } }
    FieldLabel(stringResource(R.string.device))
    DeviceRadioList(candidates, ctx.roomName, deviceId) { d ->
        val p = if (DeviceReadings.supports(d, property)) property else DeviceProperty.entries.first { DeviceReadings.supports(d, it) }
        onChange(d.id, p, value)
    }
    val device = ctx.devices.firstOrNull { it.id == deviceId }
    if (device != null) {
        val props = DeviceProperty.entries.filter { DeviceReadings.supports(device, it) }
        if (props.size > 1) ChoiceChips(labeled(props.map { it to PROPERTY_LABEL.getValue(it) }), property) { onChange(deviceId, it, value) }
        FieldLabel(stringResource(R.string.state))
        ChoiceChips(labeled(labels(property)), value) { onChange(deviceId, property, it) }
    }
}

@Composable
private fun SensorFields(
    ctx: EditorContext,
    deviceId: UUID?, metric: SensorMetric, comparison: Comparison, threshold: Double,
    onChange: (UUID?, SensorMetric, Comparison, Double) -> Unit,
) {
    val candidates = ctx.devices.filter { d -> SensorMetric.entries.any { DeviceReadings.supports(d, it) } }
    FieldLabel(stringResource(R.string.sensor))
    DeviceRadioList(candidates, ctx.roomName, deviceId) { d ->
        val m = if (DeviceReadings.supports(d, metric)) metric else SensorMetric.entries.first { DeviceReadings.supports(d, it) }
        val current = DeviceReadings.metric(d, m)
        onChange(d.id, m, comparison, current?.let { Math.round(it).toDouble() } ?: threshold)
    }
    val device = ctx.devices.firstOrNull { it.id == deviceId } ?: return
    val metrics = SensorMetric.entries.filter { DeviceReadings.supports(device, it) }
    if (metrics.size > 1) {
        ChoiceChips(labeled(metrics.map { it to if (it == SensorMetric.TEMPERATURE) R.string.temperature else R.string.humidity }), metric) {
            onChange(deviceId, it, comparison, threshold)
        }
    }
    ChoiceChips(labeled(listOf(Comparison.ABOVE to R.string.above, Comparison.BELOW to R.string.below)), comparison) { onChange(deviceId, metric, it, threshold) }
    // Temperaturen: gespeichert in °C, angezeigt und verstellt in der gewählten Einheit
    val tempUnit = LocalTemperatureUnit.current
    val locale = currentLocale()
    val step = if (metric == SensorMetric.TEMPERATURE) Units.stepCelsius(tempUnit) else 1.0
    fun show(v: Double) = if (metric == SensorMetric.TEMPERATURE) Units.format(v, tempUnit, locale) else String.format(locale, "%.0f %%", v)
    Stepper(onMinus = { onChange(deviceId, metric, comparison, threshold - step) }, onPlus = { onChange(deviceId, metric, comparison, threshold + step) }) {
        Text(show(threshold), style = MaterialTheme.typography.headlineMedium)
    }
    DeviceReadings.metric(device, metric)?.let {
        Text(stringResource(R.string.current_value, show(it)), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------------
// Auslöser
// ---------------------------------------------------------------------------------

private enum class TriggerType(@StringRes val labelRes: Int) {
    TIME(R.string.trigger_type_time), SUN(R.string.trigger_type_sun), DEVICE(R.string.trigger_type_device),
    SENSOR(R.string.trigger_type_sensor), CONNECTIVITY(R.string.trigger_type_connectivity), START(R.string.trigger_type_start)
}

@Composable
fun TriggerDialog(initial: Trigger?, ctx: EditorContext, onDismiss: () -> Unit, onSave: (Trigger) -> Unit) {
    var type by remember {
        mutableStateOf(
            when (initial) {
                is Trigger.TimeOfDay, null -> TriggerType.TIME
                is Trigger.Sun -> TriggerType.SUN
                is Trigger.DeviceStateChanged -> TriggerType.DEVICE
                is Trigger.SensorThreshold -> TriggerType.SENSOR
                is Trigger.Connectivity -> TriggerType.CONNECTIVITY
                Trigger.SystemStart -> TriggerType.START
            }
        )
    }
    var minute by remember { mutableStateOf((initial as? Trigger.TimeOfDay)?.minuteOfDay ?: (7 * 60)) }
    var weekdays by remember { mutableStateOf<Weekdays>((initial as? Trigger.TimeOfDay)?.weekdays ?: (initial as? Trigger.Sun)?.weekdays ?: emptySet()) }
    var sunEvent by remember { mutableStateOf((initial as? Trigger.Sun)?.event ?: SunEvent.SUNSET) }
    var offset by remember { mutableStateOf((initial as? Trigger.Sun)?.offsetMinutes ?: 0) }
    var deviceId by remember {
        mutableStateOf(
            (initial as? Trigger.DeviceStateChanged)?.deviceId ?: (initial as? Trigger.SensorThreshold)?.deviceId
                ?: (initial as? Trigger.Connectivity)?.deviceId
        )
    }
    var property by remember { mutableStateOf((initial as? Trigger.DeviceStateChanged)?.property ?: DeviceProperty.OCCUPIED) }
    var value by remember { mutableStateOf((initial as? Trigger.DeviceStateChanged)?.value ?: true) }
    var metric by remember { mutableStateOf((initial as? Trigger.SensorThreshold)?.metric ?: SensorMetric.TEMPERATURE) }
    var comparison by remember { mutableStateOf((initial as? Trigger.SensorThreshold)?.comparison ?: Comparison.ABOVE) }
    var threshold by remember { mutableStateOf((initial as? Trigger.SensorThreshold)?.threshold ?: 22.0) }
    var online by remember { mutableStateOf((initial as? Trigger.Connectivity)?.online ?: false) }

    val result: Trigger? = when (type) {
        TriggerType.TIME -> Trigger.TimeOfDay(minute, weekdays)
        TriggerType.SUN -> Trigger.Sun(sunEvent, offset, weekdays)
        TriggerType.DEVICE -> deviceId?.let { Trigger.DeviceStateChanged(it, property, value) }
        TriggerType.SENSOR -> deviceId?.let { Trigger.SensorThreshold(it, metric, comparison, threshold) }
        TriggerType.CONNECTIVITY -> deviceId?.let { Trigger.Connectivity(it, online) }
        TriggerType.START -> Trigger.SystemStart
    }

    RaumDialog(
        title = stringResource(if (initial == null) R.string.trigger_add else R.string.trigger_edit),
        onDismiss = onDismiss,
        confirmButton = { TextButton(onClick = { result?.let(onSave) }, enabled = result != null) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        ChoiceChips(labeled(TriggerType.entries.map { it to it.labelRes }), type) {
            if (it != type) { type = it; if (it == TriggerType.DEVICE || it == TriggerType.SENSOR || it == TriggerType.CONNECTIVITY) deviceId = null }
        }
        when (type) {
            TriggerType.TIME -> {
                TimeField(minute) { minute = it }
                FieldLabel(stringResource(R.string.weekdays)); WeekdayChips(weekdays) { weekdays = it }
            }
            TriggerType.SUN -> {
                if (!ctx.hasLocation) {
                    Text(stringResource(R.string.trigger_sun_no_location),
                        color = MaterialTheme.colorScheme.error)
                }
                ChoiceChips(labeled(listOf(SunEvent.SUNRISE to R.string.sunrise, SunEvent.SUNSET to R.string.sunset)), sunEvent) { sunEvent = it }
                FieldLabel(
                    when {
                        offset == 0 -> stringResource(R.string.offset_exact)
                        offset > 0 -> stringResource(R.string.offset_after, offset)
                        else -> stringResource(R.string.offset_before, -offset)
                    }
                )
                Slider(value = offset.toFloat(), onValueChange = { offset = (it / 5).toInt() * 5 }, valueRange = -120f..120f)
                FieldLabel(stringResource(R.string.weekdays)); WeekdayChips(weekdays) { weekdays = it }
            }
            TriggerType.DEVICE -> DeviceStateFields(ctx, deviceId, property, value, ::valueLabels) { d, p, v -> deviceId = d; property = p; value = v }
            TriggerType.SENSOR -> SensorFields(ctx, deviceId, metric, comparison, threshold) { d, m, c, t -> deviceId = d; metric = m; comparison = c; threshold = t }
            TriggerType.CONNECTIVITY -> {
                FieldLabel(stringResource(R.string.device))
                DeviceRadioList(ctx.devices, ctx.roomName, deviceId) { deviceId = it.id }
                ChoiceChips(labeled(listOf(false to R.string.connectivity_lost, true to R.string.connectivity_back)), online) { online = it }
            }
            TriggerType.START -> Text(stringResource(R.string.trigger_start_hint))
        }
    }
}

// ---------------------------------------------------------------------------------
// Bedingungen
// ---------------------------------------------------------------------------------

private enum class ConditionType(@StringRes val labelRes: Int) {
    TIME(R.string.condition_type_time), DAYS(R.string.weekdays), DEVICE(R.string.trigger_type_device), SENSOR(R.string.trigger_type_sensor)
}

@Composable
fun ConditionDialog(initial: Condition?, ctx: EditorContext, onDismiss: () -> Unit, onSave: (Condition) -> Unit) {
    var type by remember {
        mutableStateOf(
            when (initial) {
                is Condition.TimeWindow, null -> ConditionType.TIME
                is Condition.OnWeekdays -> ConditionType.DAYS
                is Condition.DeviceStateIs -> ConditionType.DEVICE
                is Condition.SensorValue -> ConditionType.SENSOR
            }
        )
    }
    var from by remember { mutableStateOf((initial as? Condition.TimeWindow)?.fromMinute ?: (18 * 60)) }
    var to by remember { mutableStateOf((initial as? Condition.TimeWindow)?.toMinute ?: (7 * 60)) }
    var days by remember { mutableStateOf<Weekdays>((initial as? Condition.OnWeekdays)?.weekdays ?: (1..5).toSet()) }
    var deviceId by remember { mutableStateOf((initial as? Condition.DeviceStateIs)?.deviceId ?: (initial as? Condition.SensorValue)?.deviceId) }
    var property by remember { mutableStateOf((initial as? Condition.DeviceStateIs)?.property ?: DeviceProperty.OCCUPIED) }
    var value by remember { mutableStateOf((initial as? Condition.DeviceStateIs)?.value ?: true) }
    var metric by remember { mutableStateOf((initial as? Condition.SensorValue)?.metric ?: SensorMetric.TEMPERATURE) }
    var comparison by remember { mutableStateOf((initial as? Condition.SensorValue)?.comparison ?: Comparison.ABOVE) }
    var threshold by remember { mutableStateOf((initial as? Condition.SensorValue)?.threshold ?: 22.0) }

    val result: Condition? = when (type) {
        ConditionType.TIME -> Condition.TimeWindow(from, to)
        ConditionType.DAYS -> days.takeIf { it.isNotEmpty() }?.let { Condition.OnWeekdays(it) }
        ConditionType.DEVICE -> deviceId?.let { Condition.DeviceStateIs(it, property, value) }
        ConditionType.SENSOR -> deviceId?.let { Condition.SensorValue(it, metric, comparison, threshold) }
    }

    RaumDialog(
        title = stringResource(if (initial == null) R.string.condition_add else R.string.condition_edit),
        onDismiss = onDismiss,
        confirmButton = { TextButton(onClick = { result?.let(onSave) }, enabled = result != null) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        ChoiceChips(labeled(ConditionType.entries.map { it to it.labelRes }), type) { if (it != type) { type = it; deviceId = null } }
        when (type) {
            ConditionType.TIME -> {
                FieldLabel(stringResource(R.string.from)); TimeField(from) { from = it }
                FieldLabel(stringResource(R.string.to)); TimeField(to) { to = it }
                if (from > to) Text(stringResource(R.string.over_midnight), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ConditionType.DAYS -> WeekdayChips(days) { days = it }
            ConditionType.DEVICE -> DeviceStateFields(ctx, deviceId, property, value, ::stateLabels) { d, p, v -> deviceId = d; property = p; value = v }
            ConditionType.SENSOR -> SensorFields(ctx, deviceId, metric, comparison, threshold) { d, m, c, t -> deviceId = d; metric = m; comparison = c; threshold = t }
        }
    }
}

// ---------------------------------------------------------------------------------
// Aktionen
// ---------------------------------------------------------------------------------

private enum class ActionType(@StringRes val labelRes: Int) {
    DEVICE(R.string.action_type_device), SCENE(R.string.action_type_scene), DELAY(R.string.action_type_delay), NOTIFY(R.string.action_type_notify)
}
private enum class DelayUnit(val seconds: Int, @StringRes val labelRes: Int) {
    SEC(1, R.string.unit_seconds), MIN(60, R.string.unit_minutes), HOUR(3600, R.string.unit_hours)
}

@Composable
fun ActionDialog(initial: AutomationAction?, ctx: EditorContext, onDismiss: () -> Unit, onSave: (AutomationAction) -> Unit) {
    var type by remember {
        mutableStateOf(
            when (initial) {
                is AutomationAction.ControlDevice, null -> ActionType.DEVICE
                is AutomationAction.RunScene -> ActionType.SCENE
                is AutomationAction.Delay -> ActionType.DELAY
                is AutomationAction.Notify -> ActionType.NOTIFY
            }
        )
    }
    val initialControl = initial as? AutomationAction.ControlDevice
    var deviceId by remember { mutableStateOf(initialControl?.deviceId) }
    var target by remember {
        mutableStateOf(initialControl?.let { SceneTargets.fromCommands(it.commands, ctx.devices.firstOrNull { d -> d.id == it.deviceId }) })
    }
    var sceneId by remember { mutableStateOf((initial as? AutomationAction.RunScene)?.sceneId) }
    val initialDelay = (initial as? AutomationAction.Delay)?.seconds ?: 180
    var unit by remember {
        mutableStateOf(DelayUnit.entries.last { initialDelay % it.seconds == 0 && initialDelay >= it.seconds || it == DelayUnit.SEC })
    }
    var amount by remember { mutableStateOf(initialDelay / unit.seconds) }
    var message by remember { mutableStateOf((initial as? AutomationAction.Notify)?.message ?: "") }

    val strings = koinInject<Strings>()
    val describer = remember(strings) { AutomationDescriber(emptyMap(), emptyMap(), strings) }
    val delaySeconds = amount * unit.seconds
    val result: AutomationAction? = when (type) {
        ActionType.DEVICE -> {
            val d = deviceId; val t = target
            if (d != null && t != null) AutomationAction.ControlDevice(d, SceneTargets.toCommands(t)) else null
        }
        ActionType.SCENE -> sceneId?.let { AutomationAction.RunScene(it) }
        ActionType.DELAY -> delaySeconds.takeIf { it in 1..AutomationLimits.MAX_DELAY_SECONDS }?.let { AutomationAction.Delay(it) }
        ActionType.NOTIFY -> message.trim().takeIf { it.isNotEmpty() }?.let { AutomationAction.Notify(it) }
    }

    RaumDialog(
        title = stringResource(if (initial == null) R.string.action_add_title else R.string.action_edit_title),
        onDismiss = onDismiss,
        confirmButton = { TextButton(onClick = { result?.let(onSave) }, enabled = result != null) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        ChoiceChips(labeled(ActionType.entries.map { it to it.labelRes }), type) { type = it }
        when (type) {
            ActionType.DEVICE -> {
                FieldLabel(stringResource(R.string.device))
                DeviceRadioList(ctx.devices.filter(SceneTargets::isSceneCapable), ctx.roomName, deviceId) { d ->
                    if (d.id != deviceId) { deviceId = d.id; target = SceneTargets.defaultActionFor(d) }
                }
                val device = ctx.devices.firstOrNull { it.id == deviceId }
                val t = target
                if (device != null && t != null) {
                    FieldLabel(stringResource(R.string.target_state))
                    TargetEditor(device, t) { target = it }
                }
            }
            ActionType.SCENE -> {
                if (ctx.scenes.isEmpty()) Text(stringResource(R.string.scenes_none_available))
                ChoiceChips(ctx.scenes.map { it.id to it.name }, sceneId) { sceneId = it }
            }
            ActionType.DELAY -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stepper(onMinus = { amount = (amount - 1).coerceAtLeast(1) }, onPlus = { amount += 1 }, minusEnabled = amount > 1) {
                        Text("$amount", style = MaterialTheme.typography.displaySmall)
                    }
                    ChoiceChips(labeled(DelayUnit.entries.map { it to it.labelRes }), unit) { unit = it }
                }
                Text(
                    if (delaySeconds > AutomationLimits.MAX_DELAY_SECONDS) stringResource(R.string.delay_max)
                    else stringResource(R.string.delay_waits, describer.duration(delaySeconds)),
                    color = if (delaySeconds > AutomationLimits.MAX_DELAY_SECONDS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ActionType.NOTIFY -> {
                OutlinedTextField(
                    value = message, onValueChange = { message = it.take(120) },
                    label = { Text(stringResource(R.string.notice_text)) }, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.notice_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

