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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
 * Gerätekachel auf Glas (Übersicht, Räume, Geräte) nach Entwurf „raum. Übersicht Redesign“: oben Name und Raum,
 * in der Mitte ein Bild des Zustands (Leuchte, Fenster, Steckdose, Thermostat-Ring), unten die Bedienung.
 * Tippen schaltet, langes Drücken oder der Knopf oben rechts öffnet die Detailansicht.
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
    val lit = device.isActive && online
    val lightColor = light?.rgbColor?.takeIf { light.colorMode == app.raum.domain.models.LightColorMode.COLOR }?.let { Color(it.argb) }
    // Eingeschaltet färbt sich die Kachel: Licht warm (oder in seiner Farbe), Steckdosen grün
    val (top, bottom) = when {
        lit && light != null && g.dark -> (lightColor ?: Color(0xFFFFBE64)).copy(alpha = 0.26f) to (lightColor ?: Color(0xFFFFBE64)).copy(alpha = 0.16f)
        lit && light != null -> (lightColor?.let { lerp(Color.White, it, 0.18f) } ?: Color(0xFFFFF3DC)).copy(alpha = 0.92f) to
            (lightColor?.let { lerp(Color.White, it, 0.45f) } ?: Color(0xFFFFD896)).copy(alpha = 0.92f)
        lit && switch != null && g.dark -> Accent.Power.copy(alpha = 0.22f) to Accent.Power.copy(alpha = 0.12f)
        lit && switch != null -> Color(0xFFE9FBEF).copy(alpha = 0.9f) to Color(0xFFBFECCD).copy(alpha = 0.9f)
        else -> g.glass to g.glass
    }
    val c0 by animateColorAsState(top, label = "tileTop")
    val c1 by animateColorAsState(bottom, label = "tileBottom")
    val warmInk = !g.dark && lit && (light != null || switch != null)
    val subColor = when {
        warmInk && light != null -> Color(0xFF7A5A2C)
        warmInk -> Color(0xFF2F6B45)
        else -> g.soft
    }
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .alpha(if (online) 1f else 0.55f)
            .clip(shape).background(Brush.verticalGradient(listOf(c0, c1)), shape).border(1.dp, g.edge, shape)
            .combinedClickable(
                onClick = { if (togglable && online) actions.onToggle(device) else actions.onOpenDetails(device) },
                onLongClick = { actions.onOpenDetails(device) },
                onClickLabel = stringResource(if (togglable) (if (lit) R.string.a11y_turn_off else R.string.a11y_turn_on) else R.string.a11y_open_details),
            )
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(device.displayName, color = g.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = tileSub(device, roomName)
                if (sub.isNotEmpty()) {
                    val thermoActive = online && thermo != null && thermo.mode != ThermostatMode.OFF
                    Text(
                        sub, color = if (thermoActive && !g.dark) thermoInk(thermo!!.mode) else subColor,
                        fontSize = 13.sp, fontWeight = if (thermoActive) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(g.chip).clickable { actions.onOpenDetails(device) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.OpenInFull, stringResource(R.string.a11y_open_details), Modifier.size(18.dp), tint = g.ink) }
        }
        val art = Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)
        when {
            thermo != null && online -> {
                val step = Units.stepCelsius(LocalTemperatureUnit.current)
                val off = thermo.mode == ThermostatMode.OFF
                ThermoRing(
                    level = if (off) null else levelOf(device),
                    accent = thermoAccent(thermo.mode),
                    text = if (off) stringResource(R.string.state_off) else formatDegrees(thermo.targetCelsius),
                    textDim = off,
                    decLabel = stringResource(R.string.thermostat_decrease), incLabel = stringResource(R.string.thermostat_increase),
                    onDec = { actions.onCommand(device, DeviceCommand.SetTargetTemperature((thermo.targetCelsius - step).coerceAtLeast(thermo.minTargetCelsius))) },
                    onInc = { actions.onCommand(device, DeviceCommand.SetTargetTemperature((thermo.targetCelsius + step).coerceAtMost(thermo.maxTargetCelsius))) },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp),
                )
                return@Column
            }
            light != null -> LampArt(light.isOn, (light.brightnessPercent ?: 100) / 100f, lightColor, online, art)
            cover != null -> WindowArt(cover.openPercent, cover.movement, online, art)
            switch != null -> SocketArt(switch.isOn && online, art)
            else -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                    Text(tileBig(device), color = g.ink, fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
                }
                return@Column
            }
        }
        when {
            !online -> Text(stringResource(R.string.state_offline), color = g.soft, fontSize = 14.sp, modifier = Modifier.height(52.dp).padding(start = 4.dp, top = 16.dp))
            light?.brightnessPercent != null ->
                BrightnessBar(light, lightColor, g) { actions.onCommand(device, DeviceCommand.SetBrightness(it)) }
            light != null || switch != null -> OnOffPill(lit, if (light != null) (lightColor ?: Color(0xFFFFC24F)) else Color(0xFF7FD19A), g) { actions.onToggle(device) }
            cover != null -> {
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
        }
    }
}

private fun thermoAccent(mode: ThermostatMode): Color = when (mode) {
    ThermostatMode.COOL -> Color(0xFF4FB7E8)
    ThermostatMode.AUTO -> Color(0xFF7FC89A)
    else -> Color(0xFFF0894A)
}

private fun thermoInk(mode: ThermostatMode): Color = when (mode) {
    ThermostatMode.COOL -> Color(0xFF1D6F99)
    ThermostatMode.AUTO -> Color(0xFF2F6B45)
    else -> Color(0xFFB4561F)
}

/** An/Aus-Fläche für Schalter und nicht dimmbares Licht: an gefüllt. */
@Composable
private fun OnOffPill(on: Boolean, fill: Color, g: GlassColors, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) (if (g.dark) fill.copy(alpha = 0.55f) else fill) else g.chip, label = "pill")
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(stringResource(if (on) R.string.state_on else R.string.state_off), color = if (on) g.ink else g.soft, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
 * Kurzes Tippen setzt die Helligkeit auf die getippte Stelle. Links „An“/„Aus“, rechts der Wert.
 */
@Composable
private fun BrightnessBar(light: LightCapability, color: Color?, g: GlassColors, onCommit: (Int) -> Unit) {
    val actual = if (light.isOn) (light.brightnessPercent ?: 100) else 0
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(actual.toFloat()) }
    LaunchedEffect(actual) { if (!dragging) local = actual.toFloat() }
    val shown = local.roundToInt()
    val base = color ?: Color(0xFFFFC24F)
    val fill = if (g.dark) base.copy(alpha = 0.55f) else base
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(g.chip)
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
            Box(Modifier.fillMaxHeight().width((maxWidth * (shown / 100f)).coerceAtLeast(52.dp)).clip(RoundedCornerShape(26.dp)).background(fill))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (shown > 0) R.string.state_on else R.string.state_off), color = if (shown > 0) g.ink else g.soft, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (shown > 0) Text("$shown %", color = if (g.dark) g.ink else Color(0xFF5A3D12), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
