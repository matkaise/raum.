package app.raum.ui.automations

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
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
import app.raum.domain.usecases.SceneTargets
import app.raum.ui.components.FixedGrid
import app.raum.ui.components.LocalTemperatureUnit
import app.raum.ui.components.Units
import app.raum.ui.components.currentLocale
import app.raum.ui.components.moodSky
import app.raum.ui.theme.LocalGlass
import java.util.UUID

/** Gemeinsamer Kontext der Dialoge. */
class EditorContext(
    val devices: List<Device>,
    val roomName: (UUID?) -> String?,
    val scenes: List<Scene>,
    val hasLocation: Boolean,
    val describer: AutomationDescriber,
    val flowName: String,
)

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
        Text(show(threshold), color = LocalGlass.current.ink, fontSize = 32.sp, fontWeight = FontWeight.Light)
    }
    DeviceReadings.metric(device, metric)?.let {
        Text(stringResource(R.string.current_value, show(it)), color = LocalGlass.current.soft)
    }
}

// ---------------------------------------------------------------------------------
// Fenster
// ---------------------------------------------------------------------------------

/** Eine Art zur Auswahl links im Fenster. */
private class TypeOption<T>(val value: T, val kind: StepKind, @StringRes val title: Int, @StringRes val hint: Int)

/**
 * Fenster im Stil der Detailfenster: links die Art als Karten, rechts die Felder,
 * unten der Satz und Abbrechen/Übernehmen.
 */
@Composable
private fun <T> ItemSheet(
    title: String,
    subtitle: String,
    types: List<TypeOption<T>>,
    type: T,
    onType: (T) -> Unit,
    summary: String?,
    canApply: Boolean,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = LocalGlass.current
    val noRipple = remember { MutableInteractionSource() }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0xFF050814).copy(alpha = 0.4f)).clickable(noRipple, null, onClick = onDismiss))
        CompositionLocalProvider(LocalContentColor provides g.ink) {
            Column(
                Modifier.align(Alignment.Center).width(1100.dp).height(688.dp)
                    .shadow(40.dp, RoundedCornerShape(40.dp)).clip(RoundedCornerShape(40.dp)).background(g.sheet)
                    .clickable(noRipple, null) {}.padding(horizontal = 34.dp, vertical = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = g.ink, fontSize = 32.sp)
                        Text(subtitle, color = g.soft, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(g.ink.copy(alpha = 0.07f)).clickable(onClickLabel = stringResource(R.string.action_close), onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Close, stringResource(R.string.action_close), tint = g.ink) }
                }
                Row(Modifier.weight(1f).padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FieldLabel(stringResource(R.string.item_kind), Modifier.padding(bottom = 4.dp))
                        types.forEach { TypeCard(it, it.value == type) { onType(it.value) } }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(summary ?: stringResource(R.string.item_incomplete), color = if (summary != null) g.ink else g.soft, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(Modifier.height(60.dp).clip(RoundedCornerShape(30.dp)).background(g.ink.copy(alpha = 0.07f)).clickable(onClick = onDismiss).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.action_cancel), color = g.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        Modifier.height(60.dp).alpha(if (canApply) 1f else 0.45f).clip(RoundedCornerShape(30.dp)).background(g.ink)
                            .clickable(enabled = canApply, onClick = onApply).padding(horizontal = 30.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.action_apply), color = onInk(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun <T> TypeCard(option: TypeOption<T>, selected: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    val st = option.kind.style()
    Row(
        Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(22.dp)).background(if (selected) g.ink else g.ink.copy(alpha = 0.06f))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(st.bg), contentAlignment = Alignment.Center) { Icon(st.icon, null, Modifier.size(22.dp), tint = st.fg) }
        Column(Modifier.weight(1f)) {
            Text(stringResource(option.title), color = if (selected) onInk() else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(option.hint), color = if (selected) onInk().copy(alpha = 0.7f) else g.soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(text, color = if (error) Color(0xFFB3261E) else LocalGlass.current.soft, fontSize = 15.sp, lineHeight = 21.sp)
}

/** „Wenn …“, „Nur wenn …“, „Dann …“ */
@Composable
private fun sheetTitle(@StringRes section: Int) = stringResource(section) + " …"

// ---------------------------------------------------------------------------------
// Auslöser
// ---------------------------------------------------------------------------------

private enum class TriggerType { TIME, SUN, DEVICE, SENSOR, CONNECTIVITY, START }

private val TRIGGER_TYPES = listOf(
    TypeOption(TriggerType.TIME, StepKind.TIME, R.string.trigger_type_time, R.string.trigger_hint_time),
    TypeOption(TriggerType.SUN, StepKind.SUN, R.string.trigger_type_sun, R.string.trigger_hint_sun),
    TypeOption(TriggerType.DEVICE, StepKind.MOTION, R.string.trigger_type_device, R.string.trigger_hint_device),
    TypeOption(TriggerType.SENSOR, StepKind.TEMPERATURE, R.string.trigger_type_sensor, R.string.trigger_hint_sensor),
    TypeOption(TriggerType.CONNECTIVITY, StepKind.CONNECTIVITY, R.string.trigger_type_connectivity, R.string.trigger_hint_connectivity),
    TypeOption(TriggerType.START, StepKind.START, R.string.trigger_type_start, R.string.trigger_hint_start),
)

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
    val d = ctx.describer

    ItemSheet(
        title = sheetTitle(R.string.section_when),
        subtitle = stringResource(R.string.trigger_for, ctx.flowName.ifBlank { stringResource(R.string.automation_new) }),
        types = TRIGGER_TYPES, type = type,
        onType = { if (it != type) { type = it; if (it == TriggerType.DEVICE || it == TriggerType.SENSOR || it == TriggerType.CONNECTIVITY) deviceId = null } },
        summary = result?.let { stringResource(R.string.section_when) + " " + d.trigger(it) },
        canApply = result != null, onApply = { result?.let(onSave) }, onDismiss = onDismiss,
    ) {
        when (type) {
            TriggerType.TIME -> Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                ClockRing(minute, arcStart = 0, arcEnd = minute, onChange = { minute = it }) {
                    ClockCenter(minute, d.weekdays(weekdays).replaceFirstChar { it.uppercase() })
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FieldLabel(stringResource(R.string.weekdays))
                    WeekdayChips(weekdays) { weekdays = it }
                    Spacer(Modifier.height(8.dp))
                    Hint(stringResource(R.string.time_ring_hint))
                }
            }
            TriggerType.SUN -> {
                if (!ctx.hasLocation) Hint(stringResource(R.string.trigger_sun_no_location), error = true)
                ChoiceChips(labeled(listOf(SunEvent.SUNRISE to R.string.sunrise, SunEvent.SUNSET to R.string.sunset)), sunEvent) { sunEvent = it }
                FieldLabel(stringResource(R.string.sun_offset), Modifier.padding(top = 8.dp))
                Stepper(
                    onMinus = { offset = (offset - 5).coerceAtLeast(-120) }, onPlus = { offset = (offset + 5).coerceAtMost(120) },
                    minusEnabled = offset > -120, plusEnabled = offset < 120,
                ) {
                    Text(
                        when {
                            offset == 0 -> stringResource(R.string.offset_exact)
                            offset > 0 -> stringResource(R.string.offset_after, offset)
                            else -> stringResource(R.string.offset_before, -offset)
                        },
                        color = LocalGlass.current.ink, fontSize = 22.sp, fontWeight = FontWeight.Light,
                    )
                }
                FieldLabel(stringResource(R.string.weekdays), Modifier.padding(top = 8.dp))
                WeekdayChips(weekdays) { weekdays = it }
            }
            TriggerType.DEVICE -> DeviceStateFields(ctx, deviceId, property, value, ::valueLabels) { dv, p, v -> deviceId = dv; property = p; value = v }
            TriggerType.SENSOR -> SensorFields(ctx, deviceId, metric, comparison, threshold) { dv, m, c, t -> deviceId = dv; metric = m; comparison = c; threshold = t }
            TriggerType.CONNECTIVITY -> {
                FieldLabel(stringResource(R.string.device))
                DeviceRadioList(ctx.devices, ctx.roomName, deviceId) { deviceId = it.id }
                FieldLabel(stringResource(R.string.state), Modifier.padding(top = 8.dp))
                ChoiceChips(labeled(listOf(false to R.string.connectivity_lost, true to R.string.connectivity_back)), online) { online = it }
            }
            TriggerType.START -> Hint(stringResource(R.string.trigger_start_hint))
        }
    }
}

// ---------------------------------------------------------------------------------
// Bedingungen
// ---------------------------------------------------------------------------------

private enum class ConditionType { TIME, DAYS, DEVICE, SENSOR }

private val CONDITION_TYPES = listOf(
    TypeOption(ConditionType.TIME, StepKind.TIME, R.string.condition_type_time, R.string.condition_hint_time),
    TypeOption(ConditionType.DAYS, StepKind.WEEKDAYS, R.string.weekdays, R.string.condition_hint_days),
    TypeOption(ConditionType.DEVICE, StepKind.MOTION, R.string.trigger_type_device, R.string.condition_hint_device),
    TypeOption(ConditionType.SENSOR, StepKind.TEMPERATURE, R.string.trigger_type_sensor, R.string.trigger_hint_sensor),
)

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
    var editingFrom by remember { mutableStateOf(true) }
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
    val d = ctx.describer

    ItemSheet(
        title = sheetTitle(R.string.section_only_if),
        subtitle = stringResource(R.string.condition_for, ctx.flowName.ifBlank { stringResource(R.string.automation_new) }),
        types = CONDITION_TYPES, type = type,
        onType = { if (it != type) { type = it; deviceId = null } },
        summary = result?.let { stringResource(R.string.section_only_if) + " " + d.condition(it) },
        canApply = result != null, onApply = { result?.let(onSave) }, onDismiss = onDismiss,
    ) {
        when (type) {
            ConditionType.TIME -> Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                val active = if (editingFrom) from else to
                ClockRing(
                    active, arcStart = from, arcEnd = to, otherMarker = if (editingFrom) to else from,
                    onChange = { if (editingFrom) from = it else to = it },
                ) { ClockCenter(active, stringResource(if (editingFrom) R.string.from else R.string.to)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FieldLabel(stringResource(R.string.condition_type_time))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EndCard(stringResource(R.string.from), d.time(from), editingFrom, Modifier.weight(1f)) { editingFrom = true }
                        EndCard(stringResource(R.string.to), d.time(to), !editingFrom, Modifier.weight(1f)) { editingFrom = false }
                    }
                    if (from > to) Hint(stringResource(R.string.over_midnight))
                    Spacer(Modifier.height(4.dp))
                    Hint(stringResource(R.string.time_window_hint))
                }
            }
            ConditionType.DAYS -> {
                FieldLabel(stringResource(R.string.weekdays))
                WeekdayChips(days) { days = it }
            }
            ConditionType.DEVICE -> DeviceStateFields(ctx, deviceId, property, value, ::stateLabels) { dv, p, v -> deviceId = dv; property = p; value = v }
            ConditionType.SENSOR -> SensorFields(ctx, deviceId, metric, comparison, threshold) { dv, m, c, t -> deviceId = dv; metric = m; comparison = c; threshold = t }
        }
    }
}

/** „Von 18:00“ / „Bis 07:00“ – wählt, welches Ende der Ring verstellt. */
@Composable
private fun EndCard(label: String, time: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    Column(
        modifier.height(84.dp).clip(RoundedCornerShape(22.dp)).background(if (selected) g.ink else g.ink.copy(alpha = 0.06f))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = if (selected) onInk().copy(alpha = 0.7f) else g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(time, color = if (selected) onInk() else g.ink, fontSize = 30.sp, fontWeight = FontWeight.Light)
    }
}

// ---------------------------------------------------------------------------------
// Aktionen
// ---------------------------------------------------------------------------------

private enum class ActionType { DEVICE, SCENE, DELAY, NOTIFY }
private enum class DelayUnit(val seconds: Int, @StringRes val labelRes: Int) {
    SEC(1, R.string.unit_seconds), MIN(60, R.string.unit_minutes), HOUR(3600, R.string.unit_hours)
}

private val ACTION_TYPES = listOf(
    TypeOption(ActionType.DEVICE, StepKind.LIGHT, R.string.action_type_device, R.string.action_hint_device),
    TypeOption(ActionType.SCENE, StepKind.SCENE, R.string.action_type_scene, R.string.action_hint_scene),
    TypeOption(ActionType.DELAY, StepKind.DELAY, R.string.action_type_delay, R.string.action_hint_delay),
    TypeOption(ActionType.NOTIFY, StepKind.NOTIFY, R.string.action_type_notify, R.string.action_hint_notify),
)

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
    var pickingDevice by remember { mutableStateOf(false) }

    val d = ctx.describer
    val delaySeconds = amount * unit.seconds
    val result: AutomationAction? = when (type) {
        ActionType.DEVICE -> {
            val dv = deviceId; val t = target
            if (dv != null && t != null) AutomationAction.ControlDevice(dv, SceneTargets.toCommands(t)) else null
        }
        ActionType.SCENE -> sceneId?.let { AutomationAction.RunScene(it) }
        ActionType.DELAY -> delaySeconds.takeIf { it in 1..AutomationLimits.MAX_DELAY_SECONDS }?.let { AutomationAction.Delay(it) }
        ActionType.NOTIFY -> message.trim().takeIf { it.isNotEmpty() }?.let { AutomationAction.Notify(it) }
    }
    val g = LocalGlass.current

    ItemSheet(
        title = sheetTitle(R.string.section_then),
        subtitle = stringResource(R.string.action_for, ctx.flowName.ifBlank { stringResource(R.string.automation_new) }),
        types = ACTION_TYPES, type = type, onType = { type = it },
        summary = result?.let { stringResource(R.string.section_then) + " " + d.action(it) },
        canApply = result != null, onApply = { result?.let(onSave) }, onDismiss = onDismiss,
    ) {
        when (type) {
            ActionType.DEVICE -> {
                // Erst das Gerät wählen, dann steht die Bedienung direkt oben – kein Scrollen unter die Geräteliste
                val device = ctx.devices.firstOrNull { it.id == deviceId }
                val t = target
                if (device == null || t == null || pickingDevice) {
                    FieldLabel(stringResource(R.string.device))
                    DeviceRadioList(ctx.devices.filter(SceneTargets::isSceneCapable), ctx.roomName, deviceId) { dv ->
                        if (dv.id != deviceId) { deviceId = dv.id; target = SceneTargets.defaultActionFor(dv) }
                        pickingDevice = false
                    }
                } else {
                    ActionTargetPanel(device, ctx.roomName(device.roomId), t, onChange = { target = it }, onChangeDevice = { pickingDevice = true })
                }
            }
            ActionType.SCENE -> {
                if (ctx.scenes.isEmpty()) Hint(stringResource(R.string.scenes_none_available))
                FixedGrid(ctx.scenes, columns = 3, spacing = 8.dp) { scene, mod -> SceneTile(scene, scene.id == sceneId, mod) { sceneId = scene.id } }
            }
            ActionType.DELAY -> {
                FieldLabel(stringResource(R.string.action_type_delay))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stepper(onMinus = { amount = (amount - 1).coerceAtLeast(1) }, onPlus = { amount += 1 }, minusEnabled = amount > 1) {
                        Text("$amount", color = g.ink, fontSize = 40.sp, fontWeight = FontWeight.Light)
                    }
                    ChoiceChips(labeled(DelayUnit.entries.map { it to it.labelRes }), unit) { unit = it }
                }
                Hint(
                    if (delaySeconds > AutomationLimits.MAX_DELAY_SECONDS) stringResource(R.string.delay_max)
                    else stringResource(R.string.delay_waits, d.duration(delaySeconds)),
                    error = delaySeconds > AutomationLimits.MAX_DELAY_SECONDS,
                )
            }
            ActionType.NOTIFY -> {
                FieldLabel(stringResource(R.string.notice_text))
                BasicTextField(
                    value = message, onValueChange = { message = it.take(120) }, singleLine = true,
                    textStyle = TextStyle(color = g.ink, fontSize = 18.sp), cursorBrush = SolidColor(g.ink),
                    modifier = Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp)).background(g.ink.copy(alpha = 0.05f))
                        .border(2.dp, g.ink, RoundedCornerShape(30.dp)).padding(horizontal = 22.dp),
                    decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { inner() } },
                )
                Hint(stringResource(R.string.notice_hint))
            }
        }
    }
}

/** Szene als kleine Stimmungskachel. */
@Composable
private fun SceneTile(scene: Scene, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        modifier.height(76.dp).then(if (selected) Modifier.border(3.dp, g.ink, RoundedCornerShape(22.dp)).padding(5.dp) else Modifier)
            .clip(RoundedCornerShape(if (selected) 17.dp else 22.dp)).background(Brush.linearGradient(moodSky(scene.icon)))
            .clickable(role = Role.RadioButton, onClickLabel = scene.name, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(scene.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
