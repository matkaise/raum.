package app.raum.ui.components

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import app.raum.R
import androidx.annotation.StringRes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.raum.domain.models.BatteryCapability
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.RgbColor
import app.raum.domain.models.Room
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.UnknownCapability
import app.raum.domain.models.find
import java.util.UUID
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import app.raum.domain.models.LightColorMode
import app.raum.domain.models.DeviceCategory
import app.raum.ui.theme.Accent
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.glass
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Farbauswahl für Leuchten (Detailansicht und Szeneneditor). */
val ColorPresets = listOf(
    RgbColor(255, 170, 90), RgbColor(255, 120, 60), RgbColor(255, 80, 80), RgbColor(255, 90, 170),
    RgbColor(170, 90, 255), RgbColor(120, 80, 255), RgbColor(70, 140, 255), RgbColor(60, 200, 170),
    RgbColor(120, 220, 90), RgbColor(255, 230, 120),
)

data class DeviceDetailActions(
    val onCommand: (Device, DeviceCommand) -> Unit,
    val onFavorite: (Device, Boolean) -> Unit,
    val onRename: (Device, String) -> Unit,
    val onAssignRoom: (Device, UUID?) -> Unit,
    val onRemove: (Device) -> Unit,
    val onSwitchAsLight: (Device, Boolean) -> Unit,
)

/**
 * Gerätedetail als Glasfenster über dem Himmel: links Drehregler, Einschaltknopf oder Messwert,
 * rechts Name, Zustand und Bedienung. Das Zahnrad zeigt Name, Raum, Infos und Entfernen.
 */
@Composable
fun DeviceDetailSheet(
    device: Device,
    rooms: List<Room>,
    actions: DeviceDetailActions,
    onDismiss: () -> Unit,
) {
    val g = LocalGlass.current
    var confirmRemove by remember { mutableStateOf(false) }
    var showSettings by remember(device.id) { mutableStateOf(false) }
    // Thermostate: Verlauf der Ist-Temperatur auf Wunsch statt der Steuerung
    var showHistory by remember(device.id) { mutableStateOf(false) }
    // Leuchten: was der Drehregler gerade einstellt (Helligkeit, Weißton, Farbe)
    var lightMode by remember(device.id) { mutableStateOf(LightDialMode.BRIGHTNESS) }
    val thermostatHistory = device.capabilities.find<ThermostatCapability>()?.currentCelsius != null
    val send: (DeviceCommand) -> Unit = { actions.onCommand(device, it) }
    val noRipple = remember { MutableInteractionSource() }
    BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0xFF050814).copy(alpha = 0.35f)).clickable(noRipple, null, onClick = onDismiss))
        CompositionLocalProvider(LocalContentColor provides g.ink) {
            val sensor = device.category == DeviceCategory.SENSOR && hasHistory(device)
            if ((sensor || (showHistory && thermostatHistory)) && !showSettings) {
                SensorPanel(
                    device, rooms, actions,
                    onSettings = { showHistory = false; showSettings = true }, onDismiss = onDismiss,
                    onBack = if (sensor) null else ({ showHistory = false }),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else Row(
                Modifier.align(Alignment.Center).width(860.dp).height(580.dp)
                    .shadow(40.dp, RoundedCornerShape(40.dp), ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                    .glass(RoundedCornerShape(40.dp), g.sheet, g.edge)
                    .clickable(noRipple, null) {}
                    .padding(36.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                Box(Modifier.width(400.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { Visual(device, send, lightMode) { lightMode = it } }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        CircleAction(
                            if (device.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            stringResource(if (device.favorite) R.string.favorite_remove else R.string.favorite_add),
                            tint = Color(0xFFE9A94A),
                        ) { actions.onFavorite(device, !device.favorite) }
                        if (thermostatHistory) {
                            CircleAction(Icons.AutoMirrored.Outlined.ShowChart, stringResource(R.string.history_show)) { showSettings = false; showHistory = true }
                        }
                        CircleAction(Icons.Outlined.Settings, stringResource(R.string.device_settings), selected = showSettings) { showSettings = !showSettings }
                        CircleAction(Icons.Outlined.Close, stringResource(R.string.action_close), onClick = onDismiss)
                    }
                    Text(rooms.firstOrNull { it.id == device.roomId }?.name ?: stringResource(R.string.no_room), color = g.soft, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                    // Lange Ein-Wort-Namen („Schlafzimmerlampe“) kleiner statt mitten im Wort umbrechen
                    val longName = device.displayName.split(' ').maxOf { it.length } > 14
                    Text(device.displayName, fontSize = if (longName) 32.sp else 40.sp, lineHeight = if (longName) 36.sp else 44.sp,
                        letterSpacing = (-0.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        detailState(device), fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp),
                        color = if (device.isOnline) g.soft else g.warn,
                    )
                    if (showSettings) {
                        Column(
                            Modifier.padding(top = 20.dp).weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            DeviceSettings(device, rooms, actions)
                            HorizontalDivider(color = g.track)
                            DeviceInfo(device)
                            OutlinedButton(
                                onClick = { confirmRemove = true },
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = g.warn),
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.device_remove))
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Column(
                                Modifier.align(Alignment.BottomStart).fillMaxWidth().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) { Controls(device, send, lightMode) { lightMode = it } }
                        }
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        RaumAlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.device_remove_title, device.displayName)) },
            text = { Text(stringResource(R.string.device_remove_text)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; actions.onRemove(device); onDismiss() }) {
                    Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun CircleAction(icon: ImageVector, label: String, tint: Color? = null, selected: Boolean = false, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (selected) g.ink else g.chip).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (selected) g.glassSolid else tint ?: g.ink) }
}

/** Sensoren (Entwurf): Kopfzeile, Messwerte und Verlauf über die ganze Breite. */
@Composable
private fun SensorPanel(
    device: Device,
    rooms: List<Room>,
    actions: DeviceDetailActions,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
    /** Zurück zur Steuerung (Thermostat); null bei reinen Sensoren. */
    onBack: (() -> Unit)? = null,
) {
    val g = LocalGlass.current
    val noRipple = remember { MutableInteractionSource() }
    Column(
        modifier.width(860.dp).height(580.dp)
            .shadow(40.dp, RoundedCornerShape(40.dp), ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .glass(RoundedCornerShape(40.dp), g.sheet, g.edge)
            .clickable(noRipple, null) {}
            .padding(36.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(g.chip), contentAlignment = Alignment.Center) {
                Icon(RaumIcons.device(device), null, Modifier.size(30.dp), tint = g.ink)
            }
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(rooms.firstOrNull { it.id == device.roomId }?.name ?: stringResource(R.string.no_room), color = g.soft, fontSize = 15.sp)
                Text(device.displayName, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CircleAction(
                if (device.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                stringResource(if (device.favorite) R.string.favorite_remove else R.string.favorite_add),
                tint = Color(0xFFE9A94A),
            ) { actions.onFavorite(device, !device.favorite) }
            onBack?.let { CircleAction(Icons.Outlined.Tune, stringResource(R.string.history_back_controls), selected = false, onClick = it) }
            CircleAction(Icons.Outlined.Settings, stringResource(R.string.device_settings), onClick = onSettings)
            CircleAction(Icons.Outlined.Close, stringResource(R.string.action_close), onClick = onDismiss)
        }
        SensorHistoryView(device, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun detailState(device: Device): String {
    if (!device.isOnline) return stringResource(R.string.device_offline_since, formatAgo(device.lastSeenAt))
    val c = device.capabilities
    c.find<LightCapability>()?.let { l ->
        return when {
            !l.isOn -> stringResource(R.string.state_off)
            l.brightnessPercent != null -> "${l.brightnessPercent} %"
            else -> stringResource(R.string.state_on)
        }
    }
    c.find<SwitchCapability>()?.let { s ->
        val watts = s.powerWatts?.takeIf { s.isOn }?.let { " · ${formatWatts(it)}" }.orEmpty()
        return stringResource(if (s.isOn) R.string.state_on else R.string.state_off) + watts
    }
    c.find<ThermostatCapability>()?.let { t ->
        return if (t.mode == ThermostatMode.OFF) stringResource(R.string.state_off) else stringResource(R.string.thermostat_target_short, formatTemperature(t.targetCelsius))
    }
    c.find<CoverCapability>()?.let { return coverLabel(it) }
    return sensorLabel(device)
}

/** Linke Hälfte: Drehregler (dimmbar, Thermostat, Storen), Einschaltknopf oder Messwert. */
@Composable
private fun Visual(device: Device, send: (DeviceCommand) -> Unit, lightMode: LightDialMode, onLightMode: (LightDialMode) -> Unit) {
    val g = LocalGlass.current
    val c = device.capabilities
    val light = c.find<LightCapability>()
    val thermo = c.find<ThermostatCapability>()
    val cover = c.find<CoverCapability>()
    val switch = c.find<SwitchCapability>()
    val online = device.isOnline
    when {
        light != null && lightDialModes(light).isNotEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val modes = lightDialModes(light)
            val mode = lightMode.takeIf { it in modes } ?: modes.first()
            if (modes.size > 1) {
                LightModeTabs(modes, mode, onLightMode)
                Spacer(Modifier.height(12.dp))
            }
            LightDial(light, mode, online, send, Modifier.size(if (modes.size > 1) 360.dp else 380.dp))
        }
        thermo != null -> {
            val step = Units.stepCelsius(LocalTemperatureUnit.current)
            val span = thermo.maxTargetCelsius - thermo.minTargetCelsius
            fun toC(f: Float) = (thermo.minTargetCelsius + f * span).let { (it / step).roundToInt() * step }.coerceIn(thermo.minTargetCelsius, thermo.maxTargetCelsius)
            Dial(
                fraction = ((thermo.targetCelsius - thermo.minTargetCelsius) / span).toFloat(), accent = Accent.Heat, enabled = online,
                label = stringResource(R.string.thermostat_target).uppercase(),
                value = { formatDegrees(toC(it)) },
                sub = { thermo.currentCelsius?.let { stringResource(R.string.dial_current, formatDegrees(it)) }.orEmpty() },
            ) { send(DeviceCommand.SetTargetTemperature(toC(it))) }
        }
        cover != null -> Dial(
            fraction = cover.openPercent / 100f, accent = Accent.Cover, enabled = online,
            label = stringResource(R.string.dial_open).uppercase(),
            value = { "${(it * 20).roundToInt() * 5}" }, sub = { "%" },
        ) { send(DeviceCommand.SetCoverPosition((it * 20).roundToInt() * 5)) }
        light != null || switch != null -> {
            val on = light?.isOn ?: switch!!.isOn
            PowerButton(on, if (light != null) Accent.Light else Accent.Power, online) { send(DeviceCommand.SetOn(!on)) }
        }
        else -> Column(
            Modifier.size(300.dp).clip(CircleShape).background(g.chip),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(RaumIcons.device(device), null, Modifier.size(56.dp), tint = g.ink)
            Spacer(Modifier.height(10.dp))
            Text(
                if (online) sensorLabel(device) else stringResource(R.string.state_offline),
                fontSize = 28.sp, lineHeight = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 30.dp),
            )
        }
    }
}

/** Drehregler einer Leuchte im gewählten Modus. */
@Composable
private fun LightDial(light: LightCapability, mode: LightDialMode, online: Boolean, send: (DeviceCommand) -> Unit, modifier: Modifier) {
    val g = LocalGlass.current
    when (mode) {
        LightDialMode.BRIGHTNESS -> Dial(
            fraction = if (light.isOn) (light.brightnessPercent ?: 100) / 100f else 0f, accent = Accent.Light, enabled = online, glow = light.isOn,
            label = stringResource(R.string.brightness).uppercase(),
            value = { if (it <= 0f) stringResource(R.string.state_off) else "${(it * 100).roundToInt().coerceAtLeast(1)}" },
            sub = { if (it <= 0f) "" else "%" },
            modifier = modifier,
        ) { send(DeviceCommand.SetBrightness((it * 100).roundToInt().coerceIn(1, 100))) }
        LightDialMode.WHITE -> {
            val range = light.colorTemperatureRange!!
            val kelvin = light.colorTemperatureKelvin ?: range.first
            Dial(
                fraction = fractionOf(range, kelvin), accent = Accent.Light, enabled = online,
                label = stringResource(R.string.light_mode_white).uppercase(),
                value = { "${kelvinAt(range, it)}" },
                sub = { "K · " + whiteName(range, kelvinAt(range, it)) },
                track = { c -> arcGradient(c) { t -> kelvinColor(kelvinAt(range, t)) } },
                knobColor = { kelvinColor(kelvinAt(range, it)) },
                modifier = modifier,
            ) { send(DeviceCommand.SetColorTemperature(kelvinAt(range, it))) }
        }
        LightDialMode.COLOR -> {
            val current = light.rgbColor?.hueSat() ?: HueSat(30f, 0.7f)
            // Ring = Farbton; die Sättigung bleibt (Regler rechts)
            fun at(f: Float) = HueSat(f * 360f, current.saturation.coerceAtLeast(0.35f))
            Dial(
                fraction = current.hue / 360f, accent = Accent.Light, enabled = online,
                label = stringResource(R.string.color).uppercase(),
                value = { "" }, sub = { "" },
                track = { c -> arcGradient(c) { t -> HueSat(t * 360f, 0.85f).color() } },
                knobColor = { at(it).color() },
                centerContent = { f ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.color).uppercase(), color = g.soft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
                        Spacer(Modifier.height(16.dp))
                        Box(
                            Modifier.size(132.dp)
                                .shadow(30.dp, CircleShape, ambientColor = at(f).color(), spotColor = at(f).color())
                                .clip(CircleShape).background(at(f).color()).border(1.dp, g.edge, CircleShape),
                        )
                    }
                },
                modifier = modifier,
            ) { send(DeviceCommand.SetColor(at(it).toRgb())) }
        }
    }
}

/**
 * Drehregler über 270°: Ziehen am Ring (oder Tippen darauf) zeigt den Wert sofort,
 * gesendet wird beim Loslassen. Mit [track] ist der ganze Ring ein Verlauf (Weißton, Farbe) statt einer Füllung.
 */
@Composable
private fun Dial(
    fraction: Float,
    accent: Color,
    enabled: Boolean,
    label: String,
    value: @Composable (Float) -> String,
    sub: @Composable (Float) -> String,
    glow: Boolean = false,
    track: ((Offset) -> Brush)? = null,
    knobColor: ((Float) -> Color)? = null,
    centerContent: (@Composable (Float) -> Unit)? = null,
    modifier: Modifier = Modifier.size(380.dp),
    onCommit: (Float) -> Unit,
) {
    val g = LocalGlass.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(fraction) }
    LaunchedEffect(fraction) { if (!dragging) local = fraction.coerceIn(0f, 1f) }
    fun at(p: Offset, size: IntSize): Float {
        val a = Math.toDegrees(atan2((p.x - size.width / 2f).toDouble(), -(p.y - size.height / 2f).toDouble())).toFloat()
        return ((a + 135f) / 270f).coerceIn(0f, 1f)
    }
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { p -> local = at(p, size); onCommit(local) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { p -> dragging = true; local = at(p, size) },
                    onDragEnd = { dragging = false; onCommit(local) },
                    onDragCancel = { dragging = false; local = fraction },
                ) { change, _ -> change.consume(); local = at(change.position, size) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ring = 30.dp.toPx()
            val r = size.minDimension / 2f - ring / 2f
            val tl = Offset(center.x - r, center.y - r)
            val arc = Size(r * 2, r * 2)
            if (track != null) {
                // Kontur darunter, damit helle Enden (kaltweiß) auf hellem Grund sichtbar bleiben
                drawArc(g.edge, 135f, 270f, false, tl, arc, style = Stroke(ring + 3.dp.toPx(), cap = StrokeCap.Round))
                drawArc(track(center), 135f, 270f, false, tl, arc, style = Stroke(ring, cap = StrokeCap.Round))
            } else {
                drawArc(g.track, 135f, 270f, false, tl, arc, style = Stroke(ring))
                if (local > 0f) drawArc(accent, 135f, 270f * local, false, tl, arc, style = Stroke(ring))
            }
            val angle = Math.toRadians((135f + 270f * local).toDouble())
            val knob = Offset(center.x + r * cos(angle).toFloat(), center.y + r * sin(angle).toFloat())
            drawCircle(Color.Black.copy(alpha = 0.18f), 16.dp.toPx(), knob + Offset(0f, 2.dp.toPx()))
            drawCircle(Color.White, 14.dp.toPx(), knob)
            knobColor?.let { drawCircle(it(local), 9.dp.toPx(), knob) }
            if (glow && local > 0f) {
                drawCircle(Brush.radialGradient(listOf(Accent.Light.copy(alpha = 0.15f + local * 0.35f), Color.Transparent), center, size.minDimension / 2f - 60.dp.toPx()))
            }
        }
        if (centerContent != null) centerContent(local) else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = g.soft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Text(value(local), fontSize = 92.sp, fontWeight = FontWeight.Light, lineHeight = 96.sp, letterSpacing = (-3).sp, modifier = Modifier.padding(top = 6.dp))
            val s = sub(local)
            if (s.isNotEmpty()) Text(s, color = g.soft, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun PowerButton(on: Boolean, accent: Color, enabled: Boolean, onToggle: () -> Unit) {
    val g = LocalGlass.current
    val bg by animateColorAsState(if (on) accent.copy(alpha = 0.55f) else g.chip, label = "pwr")
    Box(
        Modifier.size(300.dp)
            .then(if (on) Modifier.shadow(40.dp, CircleShape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(CircleShape)
            .background(if (on) Brush.radialGradient(0f to accent, 0.6f to accent.copy(alpha = 0.33f), 0.72f to Color.Transparent) else Brush.linearGradient(listOf(bg, bg)))
            .clickable(enabled = enabled, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.size(220.dp).clip(CircleShape).background(g.glassSolid),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.PowerSettingsNew, stringResource(if (on) R.string.a11y_turn_off else R.string.a11y_turn_on), Modifier.size(72.dp),
                tint = if (!on) g.soft else if (g.dark) accent else if (accent == Accent.Power) Color(0xFF2E6B3A) else Accent.LightDeep,
            )
            Text(stringResource(if (on) R.string.state_on else R.string.state_off), fontSize = 30.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Rechte Hälfte unten: Schalten, Lichtfarbe, Sollwert, Storen, Messwerte. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(device: Device, send: (DeviceCommand) -> Unit, lightMode: LightDialMode, onLightMode: (LightDialMode) -> Unit) {
    val g = LocalGlass.current
    val enabled = device.isOnline
    val caps = device.capabilities

    caps.find<LightCapability>()?.let { light ->
        // Links steht dann der Drehregler statt des Einschaltknopfs
        if (lightDialModes(light).isNotEmpty()) {
            WidePill(
                Icons.Outlined.PowerSettingsNew, stringResource(if (light.isOn) R.string.a11y_turn_off else R.string.a11y_turn_on),
                bg = if (light.isOn) g.chip else Accent.Light, fg = if (light.isOn) g.ink else Accent.OnLight, enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { send(DeviceCommand.SetOn(!light.isOn)) }
        }
        val modes = lightDialModes(light)
        val mode = lightMode.takeIf { it in modes } ?: modes.firstOrNull()
        val range = light.colorTemperatureRange
        when (mode) {
            // Helligkeit: aktuelle Lichtfarbe zeigen – Tippen wechselt in den passenden Modus
            LightDialMode.BRIGHTNESS -> if (modes.size > 1) {
                val inColor = light.colorMode == LightColorMode.COLOR && light.rgbColor != null
                val kelvin = light.colorTemperatureKelvin
                if (inColor || (kelvin != null && range != null)) {
                    val swatch = if (inColor) Color(light.rgbColor!!.argb) else kelvinColor(kelvin!!)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(g.chip)
                            .clickable { onLightMode(if (inColor) LightDialMode.COLOR else LightDialMode.WHITE) }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(swatch).border(1.dp, g.edge, CircleShape))
                        Spacer(Modifier.width(14.dp))
                        Text(
                            if (inColor) stringResource(R.string.color) else "${whiteName(range!!, kelvin!!)} · $kelvin K",
                            fontSize = 17.sp, modifier = Modifier.weight(1f),
                        )
                        Text(stringResource(R.string.light_change), color = g.soft, fontSize = 15.sp)
                    }
                }
            }
            LightDialMode.WHITE -> if (range != null) Column {
                SectionLabel(stringResource(R.string.light_quick_white))
                val current = light.colorTemperatureKelvin?.let { fractionOf(range, it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WhitePresets.forEach { (label, v) ->
                        val kelvin = kelvinAt(range, v)
                        val nearest = current != null && WhitePresets.minBy { abs(it.second - current) }.second == v
                        val selected = light.colorMode != LightColorMode.COLOR && nearest
                        Box(
                            Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp)).background(kelvinColor(kelvin))
                                .border(if (selected) 3.dp else 1.dp, if (selected) g.ink else g.edge, RoundedCornerShape(28.dp))
                                .clickable(enabled = enabled) { send(DeviceCommand.SetColorTemperature(kelvin)) },
                            contentAlignment = Alignment.Center,
                        ) { Text(stringResource(label), color = Accent.OnLight, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    }
                }
            }
            LightDialMode.COLOR -> {
                val current = light.rgbColor?.hueSat()
                val active = light.colorMode == LightColorMode.COLOR
                CommitSlider(
                    label = stringResource(R.string.light_saturation),
                    value = (current?.saturation ?: 0.7f) * 100f, range = 10f..100f, enabled = enabled,
                    format = { "${it.roundToInt()} %" },
                ) { send(DeviceCommand.SetColor(HueSat(current?.hue ?: 30f, it / 100f).toRgb())) }
                Column {
                    SectionLabel(stringResource(R.string.light_quick_color))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColorQuickPicks.forEach { pick ->
                            val selected = active && current != null && pick.near(current)
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(pick.color())
                                    .border(if (selected) 3.dp else 1.dp, if (selected) g.ink else g.edge, CircleShape)
                                    .clickable(enabled = enabled) { send(DeviceCommand.SetColor(pick.toRgb())) },
                            )
                        }
                    }
                }
            }
            null -> Unit
        }
    }

    caps.find<SwitchCapability>()?.let { s ->
        if (s.powerWatts != null || s.energyKwh != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                s.powerWatts?.let { StatBox(stringResource(R.string.power_now), if (s.isOn) formatWatts(it) else formatWatts(0.0), Modifier.weight(1f)) }
                s.energyKwh?.let { StatBox(stringResource(R.string.energy_total), formatKwh(it), Modifier.weight(1f)) }
            }
        }
    }

    caps.find<ThermostatCapability>()?.let { t ->
        val step = Units.stepCelsius(LocalTemperatureUnit.current)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundControl(Icons.Outlined.Remove, stringResource(R.string.thermostat_decrease), enabled && t.targetCelsius > t.minTargetCelsius) {
                send(DeviceCommand.SetTargetTemperature((t.targetCelsius - step).coerceAtLeast(t.minTargetCelsius)))
            }
            RoundControl(Icons.Outlined.Add, stringResource(R.string.thermostat_increase), enabled && t.targetCelsius < t.maxTargetCelsius) {
                send(DeviceCommand.SetTargetTemperature((t.targetCelsius + step).coerceAtMost(t.maxTargetCelsius)))
            }
        }
        if (t.supportedModes.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ThermostatMode.entries.filter { it in t.supportedModes }.forEach { mode ->
                    val on = t.mode == mode
                    val heat = mode != ThermostatMode.OFF
                    WidePill(
                        if (mode == ThermostatMode.HEAT) Icons.Outlined.LocalFireDepartment else null, stringResource(mode.labelRes),
                        bg = if (on) (if (heat) Accent.Heat else g.glassSolid) else g.chip,
                        fg = if (on && heat) Accent.OnHeat else g.ink, enabled = enabled,
                        border = if (on && !heat) g.ink else null,
                    ) { send(DeviceCommand.SetThermostatMode(mode)) }
                }
            }
        }
    }

    caps.find<CoverCapability>()?.let {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            WidePill(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.cover_open), g.chip, g.ink, enabled, Modifier.weight(1f)) { send(DeviceCommand.OpenCover) }
            RoundControl(Icons.Outlined.Stop, stringResource(R.string.cover_stop), enabled) { send(DeviceCommand.StopCover) }
            WidePill(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.cover_close), g.chip, g.ink, enabled, Modifier.weight(1f)) { send(DeviceCommand.CloseCover) }
        }
        it.tiltOpenPercent?.let { tilt ->
            Column {
                SectionLabel(stringResource(R.string.cover_tilt_label, tilt))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(0, 25, 50, 75, 100).forEach { v ->
                        val on = abs(tilt - v) < 13
                        WidePill(
                            null, "$v",
                            bg = if (on) g.glassSolid else g.chip, fg = g.ink, enabled = enabled, modifier = Modifier.weight(1f),
                            border = if (on) g.ink else null,
                        ) { send(DeviceCommand.SetCoverTilt(v)) }
                    }
                }
            }
        }
    }

    if (caps.find<UnknownCapability>() != null) {
        Text(stringResource(R.string.device_unsupported), color = g.soft, fontSize = 16.sp)
    }
    if (!enabled) {
        Text(stringResource(R.string.device_unreachable_hint), color = g.warn, fontSize = 16.sp)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = LocalGlass.current.soft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, maxLines = 1, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun WidePill(
    icon: ImageVector?,
    label: String,
    bg: Color,
    fg: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    border: Color? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier.height(64.dp).alpha(if (enabled) 1f else 0.45f).clip(shape).background(bg)
            .then(if (border != null) Modifier.border(2.dp, border, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg); Spacer(Modifier.width(6.dp)) }
        Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RoundControl(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier.size(64.dp).alpha(if (enabled) 1f else 0.45f).clip(CircleShape).background(g.chip).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(28.dp), tint = g.ink) }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(g.chip).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(label, color = g.soft, fontSize = 14.sp, maxLines = 1)
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Light, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@get:StringRes
val ThermostatMode.labelRes: Int
    get() = when (this) {
        ThermostatMode.OFF -> R.string.thermostat_off
        ThermostatMode.HEAT -> R.string.thermostat_heat
        ThermostatMode.COOL -> R.string.thermostat_cool
        ThermostatMode.AUTO -> R.string.thermostat_auto
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceSettings(device: Device, rooms: List<Room>, actions: DeviceDetailActions) {
    var name by remember(device.id) { mutableStateOf(device.displayName) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(
                onClick = { actions.onRename(device, name) },
                enabled = name.isNotBlank() && name.trim() != device.displayName,
            ) { Text(stringResource(R.string.rename)) }
        }
        Text(stringResource(R.string.room), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rooms.forEach { room ->
                FilterChip(
                    selected = device.roomId == room.id,
                    onClick = { actions.onAssignRoom(device, room.id) },
                    label = { Text(room.name) },
                    leadingIcon = { Icon(RaumIcons.room(room.icon), null, Modifier.size(18.dp)) },
                    modifier = Modifier.height(48.dp),
                )
            }
            FilterChip(
                selected = device.roomId == null,
                onClick = { actions.onAssignRoom(device, null) },
                label = { Text(stringResource(R.string.no_room)) },
                modifier = Modifier.height(48.dp),
            )
        }
        // Schaltaktor hinter dem Lichtschalter: als Licht führen (Symbol, Lichtszenen, „Alle Lichter aus“)
        if (device.switchAsLight || device.capabilities.any { it is SwitchCapability }) {
            Column {
                LabeledSwitch(stringResource(R.string.device_switch_as_light), device.switchAsLight, enabled = true) {
                    actions.onSwitchAsLight(device, it)
                }
                Text(stringResource(R.string.device_switch_as_light_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DeviceInfo(device: Device) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.device_info), style = MaterialTheme.typography.titleMedium)
        InfoLine(stringResource(R.string.device_vendor), device.vendorName ?: "–")
        InfoLine(stringResource(R.string.device_product), device.productName ?: "–")
        InfoLine(stringResource(R.string.device_node_id), "0x%016X".format(device.matterNodeId.toLong()), mono = true)
        device.endpointId?.let { InfoLine(stringResource(R.string.device_channel), stringResource(R.string.device_channel_value, it)) }
        device.network?.let { n -> InfoLine(stringResource(R.string.device_connection), connectionText(n)) }
        device.capabilities.find<BatteryCapability>()?.let { InfoLine(stringResource(R.string.battery), "${it.percent} %") }
        InfoLine(stringResource(R.string.last_seen), formatAgo(device.lastSeenAt))
    }
}

/** „Thread · Router · Netz „Zuhause“ · Kanal 15“ bzw. „WLAN“ */
@Composable
private fun connectionText(n: app.raum.domain.models.DeviceNetwork): String = listOfNotNull(
    stringResource(
        when (n.transport) {
            app.raum.domain.models.NetworkTransport.THREAD -> R.string.transport_thread
            app.raum.domain.models.NetworkTransport.WIFI -> R.string.transport_wifi
            app.raum.domain.models.NetworkTransport.ETHERNET -> R.string.transport_ethernet
        },
    ),
    n.threadRole?.let { stringResource(it.labelRes) },
    n.threadNetworkName?.let { stringResource(R.string.thread_router_network, it) },
    n.threadChannel?.let { stringResource(R.string.thread_channel_short, it) },
).joinToString(" · ")

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(200.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.let { if (mono) it.copy(fontFamily = FontFamily.Monospace) else it })
    }
}

@Composable
fun ValueBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Slider, der während des Ziehens nur lokal aktualisiert und erst beim Loslassen sendet –
 * verhindert eine Flut von Matter-Befehlen.
 */
@Composable
fun CommitSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { if (!dragging) local = value }
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(format(local), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = local.coerceIn(range),
            onValueChange = { dragging = true; local = it },
            onValueChangeFinished = { dragging = false; onCommit(local) },
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.height(48.dp),
        )
    }
}
