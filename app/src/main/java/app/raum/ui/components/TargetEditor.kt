package app.raum.ui.components

import app.raum.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.raum.domain.models.Device
import app.raum.domain.models.LightCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.find
import app.raum.domain.usecases.DeviceTarget

/** Editor für den Zielzustand eines Geräts – genutzt von Szenen und Automationen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TargetEditor(device: Device?, target: DeviceTarget, onChange: (DeviceTarget) -> Unit) {
    when (target) {
        is DeviceTarget.Light -> {
            val cap = device?.capabilities?.find<LightCapability>()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OnOffSelector(target.on) { on ->
                    onChange(
                        if (!on) target.copy(on = false)
                        else target.copy(
                            on = true,
                            brightnessPercent = target.brightnessPercent ?: cap?.brightnessPercent,
                            colorTemperatureKelvin = target.colorTemperatureKelvin
                                ?: cap?.colorTemperatureKelvin?.takeIf { target.color == null },
                        )
                    )
                }
                if (target.on) {
                    if (cap?.isDimmable == true || target.brightnessPercent != null) {
                        LabeledSlider(stringResource(R.string.brightness), (target.brightnessPercent ?: 100).toFloat(), 1f..100f, "${target.brightnessPercent ?: 100} %") {
                            onChange(target.copy(brightnessPercent = it.toInt()))
                        }
                    }
                    val range = cap?.colorTemperatureRange
                    val canColor = cap?.supportsColor == true || target.color != null
                    val canTemp = range != null || target.colorTemperatureKelvin != null
                    if (canColor && canTemp) {
                        val colorMode = target.color != null
                        SingleChoiceSegmentedButtonRow {
                            SegmentedButton(
                                selected = !colorMode,
                                onClick = { onChange(target.copy(color = null, colorTemperatureKelvin = cap?.colorTemperatureKelvin ?: 2700)) },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                            ) { Text(stringResource(R.string.white_tone)) }
                            SegmentedButton(
                                selected = colorMode,
                                onClick = { onChange(target.copy(colorTemperatureKelvin = null, color = cap?.rgbColor ?: ColorPresets.first())) },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                            ) { Text(stringResource(R.string.color)) }
                        }
                    }
                    if (canTemp && target.color == null) {
                        val r = range ?: 2200..6500
                        val k = target.colorTemperatureKelvin ?: r.first
                        LabeledSlider(stringResource(R.string.color_temperature), k.toFloat(), r.first.toFloat()..r.last.toFloat(), "$k K") {
                            onChange(target.copy(colorTemperatureKelvin = (it / 50).toInt() * 50))
                        }
                    }
                    if (canColor && (target.color != null || !canTemp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ColorPresets.forEach { c ->
                                val selected = c == target.color
                                Box(
                                    Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color(c.argb))
                                        .border(
                                            if (selected) 3.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                            CircleShape,
                                        )
                                        .clickable { onChange(target.copy(color = c, colorTemperatureKelvin = null)) },
                                )
                            }
                        }
                    }
                }
            }
        }

        is DeviceTarget.Switch -> OnOffSelector(target.on) { onChange(target.copy(on = it)) }

        is DeviceTarget.Thermostat -> {
            val cap = device?.capabilities?.find<ThermostatCapability>()
            val min = cap?.minTargetCelsius ?: 5.0
            val max = cap?.maxTargetCelsius ?: 30.0
            val step = Units.stepCelsius(LocalTemperatureUnit.current)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.target_temperature), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    FilledTonalIconButton(
                        onClick = { onChange(target.copy(targetCelsius = (target.targetCelsius - step).coerceAtLeast(min))) },
                        modifier = Modifier.size(56.dp),
                    ) { Icon(Icons.Outlined.Remove, stringResource(R.string.thermostat_decrease)) }
                    Text(
                        formatTemperature(target.targetCelsius, compact = false),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    FilledTonalIconButton(
                        onClick = { onChange(target.copy(targetCelsius = (target.targetCelsius + step).coerceAtMost(max))) },
                        modifier = Modifier.size(56.dp),
                    ) { Icon(Icons.Outlined.Add, stringResource(R.string.thermostat_increase)) }
                }
                val modes = ThermostatMode.entries.filter { it in (cap?.supportedModes ?: setOfNotNull(target.mode)) }
                if (modes.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.mode), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(100.dp))
                        FilterChip(selected = target.mode == null, onClick = { onChange(target.copy(mode = null)) }, label = { Text(stringResource(R.string.unchanged)) }, modifier = Modifier.height(48.dp))
                        modes.forEach { m ->
                            FilterChip(selected = target.mode == m, onClick = { onChange(target.copy(mode = m)) }, label = { Text(stringResource(m.labelRes)) }, modifier = Modifier.height(48.dp))
                        }
                    }
                }
            }
        }

        is DeviceTarget.Cover -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = when (target.openPercent) {
                100 -> stringResource(R.string.cover_state_open)
                0 -> stringResource(R.string.cover_state_closed)
                else -> stringResource(R.string.cover_percent_open, target.openPercent)
            }
            LabeledSlider(stringResource(R.string.position), target.openPercent.toFloat(), 0f..100f, label) {
                onChange(target.copy(openPercent = (it / 5).toInt() * 5))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = target.openPercent == 0, onClick = { onChange(target.copy(openPercent = 0)) }, label = { Text(stringResource(R.string.cover_close)) }, modifier = Modifier.height(48.dp))
                FilterChip(selected = target.openPercent == 100, onClick = { onChange(target.copy(openPercent = 100)) }, label = { Text(stringResource(R.string.cover_open)) }, modifier = Modifier.height(48.dp))
            }
        }
    }
}

@Composable
fun OnOffSelector(on: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.width(280.dp)) {
        SegmentedButton(selected = !on, onClick = { onChange(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.state_off)) }
        SegmentedButton(selected = on, onClick = { onChange(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.state_on)) }
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.height(48.dp))
    }
}

