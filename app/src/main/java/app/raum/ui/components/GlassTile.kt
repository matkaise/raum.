package app.raum.ui.components

import app.raum.domain.models.CoverMovement
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCategory
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.find
import app.raum.ui.theme.Accent
import app.raum.ui.theme.GlassColors
import app.raum.ui.theme.LocalGlass
import kotlin.math.roundToInt

/** Akzentfarbe je Gerätetyp (Ring, Regler). */
fun accentOf(device: Device): Color = when (device.category) {
    DeviceCategory.LIGHT -> Accent.Light
    DeviceCategory.CLIMATE -> Accent.Heat
    DeviceCategory.COVER -> Accent.Cover
    DeviceCategory.SWITCH -> Accent.Power
    else -> Accent.Sensor
}

/** Füllstand des Rings um das Icon: Helligkeit, Öffnung, Sollwert – 0 … 1. */
fun levelOf(device: Device): Float {
    val c = device.capabilities
    c.find<LightCapability>()?.let { return if (!it.isOn) 0f else (it.brightnessPercent ?: 100) / 100f }
    c.find<SwitchCapability>()?.let { return if (it.isOn) 1f else 0f }
    c.find<ThermostatCapability>()?.let {
        return ((it.targetCelsius - it.minTargetCelsius) / (it.maxTargetCelsius - it.minTargetCelsius)).toFloat().coerceIn(0f, 1f)
    }
    c.find<CoverCapability>()?.let { return it.openPercent / 100f }
    return 0f
}

/** Icon im Kreis mit Fortschrittsring (Entwurf: 52 dp, Ring 4 dp). */
@Composable
fun RingBadge(icon: ImageVector, level: Float, accent: Color, lit: Boolean, size: Dp = 52.dp, tint: Color? = null) {
    val g = LocalGlass.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = Offset(stroke / 2, stroke / 2)
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(g.track, 0f, 360f, false, inset, arc, style = Stroke(stroke))
            if (level > 0f) drawArc(accent, -90f, 360f * level.coerceIn(0f, 1f), false, inset, arc, style = Stroke(stroke))
        }
        Box(Modifier.size(size - 8.dp).clip(CircleShape).background(g.glassSolid), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = tint ?: if (lit) (if (g.dark) accent else Accent.LightDeep) else g.soft)
        }
    }
}

/**
 * Gerätekachel auf Glas (Übersicht, Räume): Tippen schaltet, der Knopf oben rechts öffnet die
 * Detailansicht. Dimmbare Leuchten haben einen Zieh-Regler, Thermostate und Storen +/−-Tasten.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassDeviceTile(
    device: Device,
    actions: DeviceCardActions,
    modifier: Modifier = Modifier,
    roomName: String? = null,
) {
    val g = LocalGlass.current
    val caps = device.capabilities
    val light = caps.find<LightCapability>()
    val switch = caps.find<SwitchCapability>()
    val thermo = caps.find<ThermostatCapability>()
    val cover = caps.find<CoverCapability>()
    val online = device.isOnline
    val togglable = light != null || switch != null
    val lit = device.isActive
    val accent = accentOf(device)
    val bg by animateColorAsState(
        if (lit && light != null) (if (g.dark) Color(0xFFFFBE64).copy(alpha = 0.22f) else Color(0xFFFFECC8).copy(alpha = 0.72f)) else g.glass,
        label = "tileBg",
    )
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .alpha(if (online) 1f else 0.55f)
            .clip(shape).background(bg, shape).border(1.dp, g.edge, shape)
            .combinedClickable(
                onClick = { if (togglable && online) actions.onToggle(device) else actions.onOpenDetails(device) },
                onLongClick = { actions.onOpenDetails(device) },
                onClickLabel = stringResource(if (togglable) (if (lit) R.string.a11y_turn_off else R.string.a11y_turn_on) else R.string.a11y_open_details),
            )
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            RingBadge(RaumIcons.device(device), if (online) levelOf(device) else 0f, accent, lit && online,
                tint = light?.rgbColor?.takeIf { light.isOn && online }?.let { Color(it.argb) })
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(g.chip).clickable { actions.onOpenDetails(device) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.OpenInFull, stringResource(R.string.a11y_open_details), Modifier.size(20.dp), tint = g.ink) }
        }
        Spacer(Modifier.weight(1f))
        Text(device.displayName, color = g.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        val sub = tileSub(device, roomName)
        if (sub.isNotEmpty()) {
            Text(sub, color = g.soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp).padding(top = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        when {
            online && light?.brightnessPercent != null ->
                BrightnessBar(light, g) { actions.onCommand(device, DeviceCommand.SetBrightness(it)) }
            online && thermo != null -> {
                val step = Units.stepCelsius(LocalTemperatureUnit.current)
                Stepper(
                    g, Icons.Outlined.Remove, Icons.Outlined.Add,
                    if (thermo.mode == ThermostatMode.OFF) stringResource(R.string.state_off) else formatDegrees(thermo.targetCelsius),
                    decLabel = stringResource(R.string.thermostat_decrease), incLabel = stringResource(R.string.thermostat_increase),
                    onDec = { actions.onCommand(device, DeviceCommand.SetTargetTemperature((thermo.targetCelsius - step).coerceAtLeast(thermo.minTargetCelsius))) },
                    onInc = { actions.onCommand(device, DeviceCommand.SetTargetTemperature((thermo.targetCelsius + step).coerceAtMost(thermo.maxTargetCelsius))) },
                )
            }
            online && cover != null -> {
                // Während der Fahrt wird der Knopf der laufenden Richtung zum Stopp (wie ein Taster an der Wand)
                val closing = cover.movement == CoverMovement.CLOSING
                val opening = cover.movement == CoverMovement.OPENING
                val stop = stringResource(R.string.cover_stop)
                Stepper(
                    g,
                    if (closing) Icons.Outlined.Stop else Icons.Outlined.KeyboardArrowDown,
                    if (opening) Icons.Outlined.Stop else Icons.Outlined.KeyboardArrowUp,
                    coverShortLabel(cover),
                    decLabel = if (closing) stop else stringResource(R.string.cover_close),
                    incLabel = if (opening) stop else stringResource(R.string.cover_open),
                    onDec = { actions.onCommand(device, if (closing) DeviceCommand.StopCover else DeviceCommand.CloseCover) },
                    onInc = { actions.onCommand(device, if (opening) DeviceCommand.StopCover else DeviceCommand.OpenCover) },
                )
            }
            else -> Text(tileBig(device), color = g.ink, fontSize = 30.sp, lineHeight = 32.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
    }
}

@Composable
private fun tileSub(device: Device, roomName: String?): String {
    val caps = device.capabilities
    val detail = when {
        !device.isOnline -> null
        caps.find<ThermostatCapability>() != null -> caps.find<ThermostatCapability>()!!.currentCelsius?.let { stringResource(R.string.tile_current, formatDegrees(it)) }
        caps.find<SwitchCapability>()?.let { it.isOn && it.powerWatts != null } == true -> formatWatts(caps.find<SwitchCapability>()!!.powerWatts!!)
        else -> null
    }
    return listOfNotNull(detail, roomName).joinToString(" · ")
}

@Composable
private fun tileBig(device: Device): String {
    if (!device.isOnline) return stringResource(R.string.state_offline)
    val caps = device.capabilities
    caps.find<LightCapability>()?.let { return stringResource(if (it.isOn) R.string.state_on else R.string.state_off) }
    caps.find<SwitchCapability>()?.let { return stringResource(if (it.isOn) R.string.state_on else R.string.state_off) }
    return sensorLabel(device).substringBefore(" · ")
}

/**
 * Helligkeitsregler: Ziehen ändert nur die Anzeige, gesendet wird beim Loslassen (keine Befehlsflut).
 * Kurzes Tippen setzt die Helligkeit auf die getippte Stelle.
 */
@Composable
private fun BrightnessBar(light: LightCapability, g: GlassColors, onCommit: (Int) -> Unit) {
    val actual = if (light.isOn) (light.brightnessPercent ?: 100) else 0
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(actual.toFloat()) }
    LaunchedEffect(actual) { if (!dragging) local = actual.toFloat() }
    val shown = local.roundToInt()
    val fill = if (g.dark) Accent.Light.copy(alpha = 0.55f) else Color(0xFFFFD58C)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(g.chip)
            .pointerInput(Unit) {
                detectTapGestures { o -> val v = (o.x / size.width * 100f).coerceIn(1f, 100f); local = v; onCommit(v.roundToInt()) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; local = (o.x / size.width * 100f).coerceIn(1f, 100f) },
                    onDragEnd = { dragging = false; onCommit(local.roundToInt().coerceIn(1, 100)) },
                    onDragCancel = { dragging = false; local = actual.toFloat() },
                ) { change, _ -> change.consume(); local = (change.position.x / size.width * 100f).coerceIn(1f, 100f) }
            },
    ) {
        if (shown > 0) {
            Box(Modifier.fillMaxHeight().width((maxWidth * (shown / 100f)).coerceAtLeast(56.dp)).clip(RoundedCornerShape(28.dp)).background(fill))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (shown > 0) "$shown %" else stringResource(R.string.state_off), color = g.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Brightness6, null, Modifier.size(20.dp), tint = g.soft)
        }
    }
}

@Composable
private fun Stepper(
    g: GlassColors,
    decIcon: ImageVector,
    incIcon: ImageVector,
    text: String,
    decLabel: String,
    incLabel: String,
    onDec: () -> Unit,
    onInc: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(g.chip).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundGlassButton(decIcon, decLabel, g, onDec)
        Text(text, color = g.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f).widthIn(min = 0.dp))
        RoundGlassButton(incIcon, incLabel, g, onInc)
    }
}

@Composable
private fun RoundGlassButton(icon: ImageVector, label: String, g: GlassColors, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(g.glassSolid).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(24.dp), tint = g.ink)
    }
}

/** Kacheln in festen Spalten, ohne eigenes Scrollen (für scrollende Seiten). */
@Composable
fun <T> FixedGrid(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    spacing: Dp = 14.dp,
    content: @Composable (T, Modifier) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                row.forEach { content(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
