package app.raum.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.domain.models.BatteryCapability
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.CoverMovement
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCategory
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.LightColorMode
import app.raum.domain.models.RgbColor
import app.raum.domain.models.Room
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.UnknownCapability
import app.raum.domain.models.find
import app.raum.ui.theme.Accent
import app.raum.ui.theme.GlassColors
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.glass
import java.util.UUID
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

// Farben der Detailfenster (Entwurf „raum. Übersicht Redesign“, Reihe „Detailfenster“)
private val Amber = Color(0xFFFFC24F)
private val OnAmber = Color(0xFF2E1D05)
private val Sky = Color(0xFF7EC8F8)
private val Heat = Color(0xFFF0894A)
private val Cool = Color(0xFF4FB7E8)
private val Green = Color(0xFF7FD19A)
private val OnGreen = Color(0xFF0F2A18)
private val Danger = Color(0xFFB3261E)

/**
 * Gerätedetail als Fenster über dem Himmel, aufgebaut wie die Haushalt-Panels: oben Name und Zustand, links das Bild
 * der Kachel groß im Drehring (−/+ in der Lücke), rechts Auswahlkarten und ein großer Knopf. Das Zahnrad zeigt Name,
 * Raum, Infos und Entfernen im selben Fenster.
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
    // Leuchten: was der Drehregler gerade einstellt (Helligkeit, Weißton, Farbe)
    var lightMode by remember(device.id) { mutableStateOf(LightDialMode.BRIGHTNESS) }
    val send: (DeviceCommand) -> Unit = { actions.onCommand(device, it) }
    val noRipple = remember { MutableInteractionSource() }
    BackHandler { if (showSettings) showSettings = false else onDismiss() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0xFF050814).copy(alpha = 0.38f)).clickable(noRipple, null, onClick = onDismiss))
        CompositionLocalProvider(LocalContentColor provides g.ink) {
            Column(
                Modifier.align(Alignment.Center).width(1100.dp).height(660.dp)
                    .shadow(40.dp, RoundedCornerShape(40.dp), ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                    .glass(RoundedCornerShape(40.dp), g.sheet, g.edge)
                    .clickable(noRipple, null) {}
                    .padding(horizontal = 34.dp, vertical = 30.dp),
            ) {
                DetailHeader(device, rooms, showSettings, actions, onSettings = { showSettings = !showSettings }, onDismiss = onDismiss)
                Box(Modifier.padding(top = 22.dp).weight(1f).fillMaxWidth()) {
                    when {
                        showSettings -> SettingsBody(device, rooms, actions, onRemove = { confirmRemove = true }, onDone = { showSettings = false })
                        device.category == DeviceCategory.SENSOR && hasHistory(device) -> SensorHistoryView(device, Modifier.fillMaxSize())
                        else -> ControlBody(device, send, lightMode) { lightMode = it }
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

// --- Kopf -------------------------------------------------------------------------------------

/** Hintergrund und Farbe des Gerätesymbols im Kopf, je Gerätetyp. */
private fun headerTint(device: Device, g: GlassColors): Pair<Color, Color> {
    val (soft, ink, accent) = when (device.category) {
        DeviceCategory.LIGHT -> Triple(Color(0xFFFFE3A8), Color(0xFF9A6424), Accent.Light)
        DeviceCategory.COVER -> Triple(Color(0xFFD6ECFF), Color(0xFF1D6F99), Accent.Cover)
        DeviceCategory.CLIMATE -> Triple(Color(0xFFFFE0CC), Color(0xFFB4561F), Accent.Heat)
        DeviceCategory.SWITCH -> Triple(Color(0xFFD3F2DD), Color(0xFF2F6B45), Accent.Power)
        else -> Triple(g.chip, g.ink, g.ink)
    }
    return if (g.dark) accent.copy(alpha = 0.22f) to accent else soft to ink
}

@Composable
private fun DetailHeader(
    device: Device,
    rooms: List<Room>,
    settings: Boolean,
    actions: DeviceDetailActions,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val g = LocalGlass.current
    val (bg, ink) = headerTint(device, g)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Icon(RaumIcons.device(device), null, Modifier.size(26.dp), tint = ink)
        }
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            // Lange Ein-Wort-Namen („Schlafzimmerlampe“) kleiner statt mitten im Wort umbrechen
            val longName = device.displayName.length > 26
            Text(device.displayName, fontSize = if (longName) 26.sp else 32.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val room = rooms.firstOrNull { it.id == device.roomId }?.name ?: stringResource(R.string.no_room)
            val sub = if (settings) stringResource(R.string.device_settings) else "$room · ${detailState(device)}"
            Text(sub, color = if (device.isOnline || settings) g.soft else g.warn, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        CircleAction(
            if (device.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
            stringResource(if (device.favorite) R.string.favorite_remove else R.string.favorite_add),
            tint = Color(0xFFE9A94A),
        ) { actions.onFavorite(device, !device.favorite) }
        CircleAction(Icons.Outlined.Settings, stringResource(R.string.device_settings), selected = settings, onClick = onSettings)
        CircleAction(Icons.Outlined.Close, stringResource(R.string.action_close), onClick = onDismiss)
    }
}

@Composable
private fun CircleAction(icon: ImageVector, label: String, tint: Color? = null, selected: Boolean = false, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (selected) g.ink else g.chip).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (selected) g.glassSolid else tint ?: g.ink) }
}

@Composable
private fun detailState(device: Device): String {
    if (!device.isOnline) return stringResource(R.string.device_offline_since, formatAgo(device.lastSeenAt))
    val c = device.capabilities
    c.find<LightCapability>()?.let { l ->
        return when {
            !l.isOn -> stringResource(R.string.state_off)
            l.brightnessPercent != null -> stringResource(R.string.state_on) + " · ${l.brightnessPercent} %"
            else -> stringResource(R.string.state_on)
        }
    }
    c.find<SwitchCapability>()?.let { s ->
        val watts = s.powerWatts?.takeIf { s.isOn }?.let { " · ${formatWatts(it)}" }.orEmpty()
        return stringResource(if (s.isOn) R.string.state_on else R.string.state_off) + watts
    }
    c.find<ThermostatCapability>()?.let { t ->
        if (t.mode == ThermostatMode.OFF) return stringResource(R.string.state_off)
        val now = t.currentCelsius?.let { " · " + stringResource(R.string.dial_current, formatDegrees(it)) }.orEmpty()
        return stringResource(R.string.thermostat_target_short, formatDegrees(t.targetCelsius)) + now
    }
    c.find<CoverCapability>()?.let { return coverLabel(it) }
    return sensorLabel(device)
}

// --- Bausteine ----------------------------------------------------------------------------------

/** Zwei Spalten: links Bild im Ring (380 dp), rechts Auswahl und Knopf. */
@Composable
private fun TwoColumns(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(44.dp)) {
        Column(Modifier.width(380.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, content = left)
        Column(Modifier.weight(1f).fillMaxHeight(), content = right)
    }
}

@Composable
private fun DetailLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = LocalGlass.current.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp, maxLines = 1, modifier = modifier.padding(bottom = 12.dp),
    )
}

/** Große Zahl unter dem Ring mit Beschriftung. */
@Composable
private fun BigValue(value: String, label: String) {
    val g = LocalGlass.current
    Text(value, fontSize = 44.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, maxLines = 1, modifier = Modifier.padding(top = 14.dp))
    Text(label.uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, maxLines = 1)
}

/** Unten rechts: großer Knopf (Aus-/Einschalten). */
@Composable
private fun PrimaryButton(text: String, icon: ImageVector?, bg: Color, fg: Color, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(34.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(24.dp), tint = fg); Spacer(Modifier.width(12.dp)) }
        Text(text, color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Kleine Zeile unter dem Knopf; offline stattdessen der Hinweis, dass das Gerät nicht erreichbar ist. */
@Composable
private fun Hint(device: Device, text: String?) {
    val g = LocalGlass.current
    val t = if (!device.isOnline) stringResource(R.string.device_unreachable_hint) else text ?: return
    Text(
        t, color = if (device.isOnline) g.soft else g.warn, fontSize = 14.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    )
}

/** Auswahlkarte: gewählt dunkel, sonst leicht getönt. */
@Composable
private fun ChoiceCard(
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable ColumnScope.(fg: Color, soft: Color) -> Unit,
) {
    val g = LocalGlass.current
    val bg by animateColorAsState(if (selected) g.ink else g.chip, label = "card")
    val fg = if (selected) (if (g.dark) Color(0xFF10131F) else Color.White) else g.ink
    val soft = fg.copy(alpha = 0.66f)
    Column(
        modifier.alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(24.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content(fg, soft) }
}

/** Dunkle Bildfläche in einer Karte (wie die Programmkarten im Haushalt). */
@Composable
private fun ArtPanel(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3B4468), Color(0xFF1C2036)))),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Umschalter über die ganze Breite (Helligkeit · Weißton · Farbe, 24 Std · 7 Tage). */
@Composable
private fun <T> Tabs(options: List<T>, selected: T, label: @Composable (T) -> String, height: Dp = 52.dp, onSelect: (T) -> Unit) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(g.chip).padding(4.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(if (on) g.ink else Color.Transparent)
                    .clickable { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) { Text(label(o), color = if (on) g.glassSolid else g.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/** Runder Knopf in der Lücke unter dem Ring. */
@Composable
private fun GapButton(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        modifier.size(56.dp).alpha(if (enabled) 1f else 0.4f).shadow(4.dp, CircleShape).clip(CircleShape).background(g.glassSolid)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(24.dp), tint = g.ink) }
}

/**
 * Drehring 340 dp mit Bild in der Mitte und −/+ in der Lücke. [onPreview] meldet den Wert während des Ziehens,
 * damit die Zahl darunter mitläuft.
 */
@Composable
private fun RingStage(
    fraction: Float,
    accent: Color,
    enabled: Boolean,
    disk: Color,
    track: ((Offset) -> Brush)? = null,
    knobColor: ((Float) -> Color)? = null,
    marker: Float? = null,
    dec: Pair<String, () -> Unit>? = null,
    inc: Pair<String, () -> Unit>? = null,
    decIcon: ImageVector = Icons.Outlined.Remove,
    incIcon: ImageVector = Icons.Outlined.Add,
    onPreview: (Float?) -> Unit = {},
    center: @Composable (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    Box(Modifier.size(340.dp)) {
        Dial(
            fraction = fraction, accent = accent, enabled = enabled, label = "", value = { "" }, sub = { "" },
            track = track, knobColor = knobColor, marker = marker, onPreview = onPreview,
            centerContent = { f ->
                Box(Modifier.size(224.dp).clip(CircleShape).background(disk), contentAlignment = Alignment.Center) { center(f) }
            },
            modifier = Modifier.fillMaxSize(), ringWidth = 18.dp, onCommit = onCommit,
        )
        dec?.let { (label, action) -> GapButton(decIcon, label, enabled, Modifier.align(Alignment.BottomCenter).offset(x = (-40).dp, y = (-2).dp), action) }
        inc?.let { (label, action) -> GapButton(incIcon, label, enabled, Modifier.align(Alignment.BottomCenter).offset(x = 40.dp, y = (-2).dp), action) }
    }
}

// --- Bedienung je Gerätetyp ---------------------------------------------------------------------

@Composable
private fun ControlBody(device: Device, send: (DeviceCommand) -> Unit, lightMode: LightDialMode, onLightMode: (LightDialMode) -> Unit) {
    val c = device.capabilities
    val light = c.find<LightCapability>()
    val thermo = c.find<ThermostatCapability>()
    val cover = c.find<CoverCapability>()
    val switch = c.find<SwitchCapability>()
    when {
        light != null && lightDialModes(light).isNotEmpty() -> LightBody(device, light, send, lightMode, onLightMode)
        thermo != null -> ThermostatBody(device, thermo, send)
        cover != null -> CoverBody(device, cover, send)
        light != null || switch != null -> OnOffBody(device, light, switch, send)
        else -> FallbackBody(device)
    }
}

/** Helligkeits-Schnellwahl (Prozent). */
private val BrightnessPresets = listOf(R.string.light_preset_night to 5, R.string.light_preset_dim to 30, R.string.light_preset_bright to 80, R.string.light_preset_full to 100)

/** Namen der Farbtöne für die Anzeige unter dem Ring. */
private val HueNames = listOf(
    0f to R.string.hue_red, 28f to R.string.hue_orange, 52f to R.string.hue_yellow, 115f to R.string.hue_green,
    175f to R.string.hue_turquoise, 220f to R.string.hue_blue, 275f to R.string.hue_violet, 325f to R.string.hue_pink, 360f to R.string.hue_red,
)

@Composable
private fun hueName(hue: Float): String = stringResource(HueNames.minBy { abs(it.first - hue) }.second)

/** Leuchte: drei Tabs, jeder ändert nur seinen Wert. */
@Composable
private fun LightBody(device: Device, light: LightCapability, send: (DeviceCommand) -> Unit, lightMode: LightDialMode, onLightMode: (LightDialMode) -> Unit) {
    val g = LocalGlass.current
    val online = device.isOnline
    val modes = lightDialModes(light)
    val mode = lightMode.takeIf { it in modes } ?: modes.first()
    val range = light.colorTemperatureRange
    val hs = light.rgbColor?.hueSat() ?: HueSat(30f, 0.7f)
    val inColor = light.colorMode == LightColorMode.COLOR && light.rgbColor != null
    // Die Leuchte im Ring zeigt die Lichtfarbe, die gerade wirkt
    val currentLightColor = when {
        inColor -> hs.color()
        light.colorTemperatureKelvin != null && light.supportsColorTemperature -> kelvinColor(light.colorTemperatureKelvin)
        else -> null
    }
    var preview by remember(device.id, mode) { mutableStateOf<Float?>(null) }
    val buttonColor = if (inColor) lerpWhite(hs.color(), 0.35f) else Amber

    TwoColumns(
        left = {
            when (mode) {
                LightDialMode.BRIGHTNESS -> {
                    val pct = if (light.isOn) (light.brightnessPercent ?: 100) else 0
                    RingStage(
                        fraction = pct / 100f, accent = Amber, enabled = online,
                        disk = when { !light.isOn -> if (g.dark) Color(0xFF22263A) else Color(0xFFF0F1F5); g.dark -> Color(0xFF2A2418); else -> Color(0xFFFFF6E4) },
                        dec = stringResource(R.string.light_darker) to { send(DeviceCommand.SetBrightness((pct - 10).coerceAtLeast(1))) },
                        inc = stringResource(R.string.light_brighter) to { send(DeviceCommand.SetBrightness((pct + 10).coerceIn(1, 100))) },
                        onPreview = { preview = it },
                        center = { f -> LampArt(f > 0f, f, currentLightColor, online, Modifier.fillMaxSize().padding(top = 10.dp)) },
                    ) { send(DeviceCommand.SetBrightness((it * 100).roundToInt().coerceIn(1, 100))) }
                    val shown = ((preview ?: (pct / 100f)) * 100).roundToInt()
                    BigValue(if (shown <= 0) stringResource(R.string.state_off) else "$shown %", stringResource(R.string.brightness))
                }
                LightDialMode.WHITE -> {
                    val r = range!!
                    val kelvin = light.colorTemperatureKelvin ?: r.first
                    RingStage(
                        fraction = fractionOf(r, kelvin), accent = Amber, enabled = online, disk = if (g.dark) Color(0xFF2A2418) else Color(0xFFFFF2E2),
                        track = { c -> arcGradient(c) { t -> kelvinColor(kelvinAt(r, t)) } },
                        knobColor = { kelvinColor(kelvinAt(r, it)) },
                        // Feine Schritte: 100 K je Tipp
                        dec = stringResource(R.string.light_warmer) to { send(DeviceCommand.SetColorTemperature((kelvin - 100).coerceAtLeast(r.first))) },
                        inc = stringResource(R.string.light_cooler) to { send(DeviceCommand.SetColorTemperature((kelvin + 100).coerceAtMost(r.last))) },
                        onPreview = { preview = it },
                        center = { f -> LampArt(light.isOn, 1f, kelvinColor(kelvinAt(r, f)), online, Modifier.fillMaxSize().padding(top = 10.dp)) },
                    ) { send(DeviceCommand.SetColorTemperature(kelvinAt(r, it))) }
                    // Gemeldete Werte kommen aus Mired-Umrechnung („2202 K“) – auf 50 K runden wie der Ring
                    val k = preview?.let { kelvinAt(r, it) } ?: ((kelvin + 25) / 50 * 50)
                    BigValue("$k K", stringResource(R.string.light_tab_white) + " · " + whiteName(r, k))
                }
                LightDialMode.COLOR -> {
                    val sat = hs.saturation.coerceAtLeast(0.35f)
                    RingStage(
                        fraction = hs.hue / 360f, accent = Amber, enabled = online, disk = if (g.dark) Color(0xFF221A2E) else Color(0xFFF4ECFF),
                        track = { c -> arcGradient(c) { t -> HueSat(t * 360f, 0.85f).color() } },
                        knobColor = { HueSat(it * 360f, sat).color() },
                        // Feine Schritte: 5° Farbton je Tipp
                        dec = stringResource(R.string.light_hue_prev) to { send(DeviceCommand.SetColor(HueSat((hs.hue - 5f + 360f) % 360f, sat).toRgb())) },
                        inc = stringResource(R.string.light_hue_next) to { send(DeviceCommand.SetColor(HueSat((hs.hue + 5f) % 360f, sat).toRgb())) },
                        onPreview = { preview = it },
                        center = { f -> LampArt(light.isOn, 1f, HueSat(f * 360f, sat).color(), online, Modifier.fillMaxSize().padding(top = 10.dp)) },
                    ) { send(DeviceCommand.SetColor(HueSat(it * 360f, sat).toRgb())) }
                    val hue = preview?.let { it * 360f } ?: hs.hue
                    BigValue(hueName(hue), stringResource(R.string.color))
                }
            }
        },
        right = {
            if (modes.size > 1) {
                Tabs(modes, mode, { stringResource(if (it == LightDialMode.WHITE) R.string.light_tab_white else it.labelRes) }) { onLightMode(it) }
                Spacer(Modifier.height(24.dp))
            }
            when (mode) {
                LightDialMode.BRIGHTNESS -> {
                    DetailLabel(stringResource(R.string.brightness))
                    val pct = if (light.isOn) (light.brightnessPercent ?: 100) else -100
                    val nearest = BrightnessPresets.minBy { abs(it.second - pct) }.second.takeIf { abs(it - pct) <= 10 }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BrightnessPresets.forEach { (label, v) ->
                            ChoiceCard(v == nearest, online, Modifier.weight(1f), { send(DeviceCommand.SetBrightness(v)) }) { fg, soft ->
                                ArtPanel { LampArt(true, v / 100f, currentLightColor, true, Modifier.size(90.dp, 72.dp)) }
                                Text(stringResource(label), color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                                Text("$v %", color = soft, fontSize = 13.sp)
                            }
                        }
                    }
                }
                LightDialMode.WHITE -> range?.let { r ->
                    DetailLabel(stringResource(R.string.light_tab_white))
                    val current = light.colorTemperatureKelvin?.let { fractionOf(r, it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WhitePresets.forEach { (label, v) ->
                            val kelvin = kelvinAt(r, v)
                            val selected = !inColor && current != null && WhitePresets.minBy { abs(it.second - current) }.second == v && abs(v - current) < 0.08f
                            ChoiceCard(selected, online, Modifier.weight(1f), { send(DeviceCommand.SetColorTemperature(kelvin)) }) { fg, soft ->
                                ArtPanel { LampArt(true, 0.85f, kelvinColor(kelvin), true, Modifier.size(90.dp, 72.dp)) }
                                Text(stringResource(label), color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                                Text("$kelvin K", color = soft, fontSize = 13.sp)
                            }
                        }
                    }
                }
                LightDialMode.COLOR -> {
                    DetailLabel(stringResource(R.string.color))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ColorQuickPicks.forEach { pick ->
                            val selected = inColor && pick.near(hs)
                            Box(
                                Modifier.size(56.dp)
                                    .then(if (selected) Modifier.border(3.dp, g.ink, CircleShape).padding(6.dp) else Modifier)
                                    .clip(CircleShape).background(pick.color()).border(1.dp, g.edge, CircleShape)
                                    .clickable(enabled = online, onClickLabel = hueName(pick.hue)) { send(DeviceCommand.SetColor(pick.toRgb())) },
                            )
                        }
                    }
                    DetailLabel(stringResource(R.string.light_saturation), Modifier.padding(top = 24.dp))
                    SaturationBar(hs, online) { send(DeviceCommand.SetColor(HueSat(hs.hue, it).toRgb())) }
                }
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                stringResource(if (light.isOn) R.string.a11y_turn_off else R.string.a11y_turn_on), Icons.Outlined.PowerSettingsNew,
                bg = if (light.isOn) buttonColor else g.chip, fg = if (light.isOn) OnAmber else g.ink, enabled = online,
            ) { send(DeviceCommand.SetOn(!light.isOn)) }
            Hint(
                device,
                stringResource(
                    when (mode) {
                        LightDialMode.BRIGHTNESS -> R.string.detail_hint_brightness
                        LightDialMode.WHITE -> R.string.detail_hint_white
                        LightDialMode.COLOR -> R.string.detail_hint_color
                    },
                ),
            )
        },
    )
}

private fun lerpWhite(c: Color, t: Float) = androidx.compose.ui.graphics.lerp(c, Color.White, t)

/** Sättigung als Fläche von Pastell bis Kräftig; Ziehen zeigt sofort, gesendet wird beim Loslassen. */
@Composable
private fun SaturationBar(hs: HueSat, enabled: Boolean, onCommit: (Float) -> Unit) {
    val g = LocalGlass.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(hs.saturation) }
    LaunchedEffect(hs.saturation) { if (!dragging) local = hs.saturation }
    fun at(x: Float, w: Int) = (x / w).coerceIn(0.1f, 1f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(60.dp).alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(30.dp))
            .background(Brush.horizontalGradient(listOf(HueSat(hs.hue, 0.08f).color(), HueSat(hs.hue, 1f).color())))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o -> local = at(o.x, size.width); onCommit(local) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; local = at(o.x, size.width) },
                    onDragEnd = { dragging = false; onCommit(local) },
                    onDragCancel = { dragging = false; local = hs.saturation },
                ) { change, _ -> change.consume(); local = at(change.position.x, size.width) }
            },
    ) {
        // Beschriftung weicht dem Knopf aus, statt unter ihm zu verschwinden
        Text(stringResource(R.string.light_pastel), color = Color(0xFF3D2A5C), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp).alpha(if (local < 0.22f) 0f else 1f))
        Text(stringResource(R.string.light_vivid), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).alpha(if (local > 0.78f) 0f else 1f))
        val knob = 52.dp
        Box(
            Modifier.offset(x = ((maxWidth - knob - 8.dp) * local) + 4.dp).align(Alignment.CenterStart).size(knob)
                .shadow(4.dp, CircleShape).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { Text("${(local * 100).roundToInt()}", color = Color(0xFF10131F), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Nicht dimmbares Licht und Steckdosen: großes Bild, zwei Karten An/Aus, Messwerte. */
@Composable
private fun OnOffBody(device: Device, light: LightCapability?, switch: SwitchCapability?, send: (DeviceCommand) -> Unit) {
    val g = LocalGlass.current
    val online = device.isOnline
    val on = (light?.isOn ?: switch!!.isOn) && online
    val isLight = light != null || device.switchAsLight
    val accent = if (isLight) Amber else Color(0xFF5FCB86)
    val watts = switch?.powerWatts
    TwoColumns(
        left = {
            Box(Modifier.size(340.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    if (on) drawCircle(Brush.radialGradient(0.7f to accent.copy(alpha = 0.35f), 1f to Color.Transparent), size.minDimension / 2f)
                    drawCircle(if (on) accent else g.track, 140.dp.toPx(), style = Stroke(12.dp.toPx()))
                }
                Box(
                    Modifier.size(244.dp).clip(CircleShape).background(if (g.dark) Color(0xFF22263A) else if (isLight) Color(0xFFFFF6E4) else Color(0xFFEEF9F1))
                        .clickable(enabled = online, onClickLabel = stringResource(if (on) R.string.a11y_turn_off else R.string.a11y_turn_on)) { send(DeviceCommand.SetOn(!on)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLight) LampArt(on, 1f, null, online, Modifier.fillMaxSize().padding(top = 10.dp))
                    else SocketArt(on, Modifier.size(160.dp))
                }
            }
            if (watts != null && on) BigValue(formatWatts(watts), stringResource(R.string.power_now))
            else BigValue(stringResource(if (on) R.string.state_on else R.string.state_off), if (light != null) stringResource(R.string.light_not_dimmable) else "")
        },
        right = {
            DetailLabel(stringResource(R.string.detail_state))
            val stats = switch != null && (switch.powerWatts != null || switch.energyKwh != null)
            Row(Modifier.fillMaxWidth().height(if (stats) 200.dp else 290.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(true, false).forEach { target ->
                    ChoiceCard(on == target, online, Modifier.weight(1f).fillMaxHeight(), { if (on != target) send(DeviceCommand.SetOn(target)) }) { fg, soft ->
                        Spacer(Modifier.weight(1f))
                        if (isLight) LampArt(target, 1f, null, true, Modifier.size(150.dp, 120.dp)) else SocketArt(target, Modifier.size(96.dp))
                        Text(stringResource(if (target) R.string.state_on else R.string.state_off), color = fg, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (stats) {
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    switch!!.powerWatts?.let { StatBox(stringResource(R.string.power_now), if (switch.isOn) formatWatts(it) else formatWatts(0.0), Modifier.weight(1f)) }
                    switch.energyKwh?.let { StatBox(stringResource(R.string.energy_total), formatKwh(it), Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                stringResource(if (on) R.string.a11y_turn_off else R.string.a11y_turn_on), Icons.Outlined.PowerSettingsNew,
                bg = if (on) (if (isLight) Amber else Green) else g.chip, fg = if (on) (if (isLight) OnAmber else OnGreen) else g.ink, enabled = online,
            ) { send(DeviceCommand.SetOn(!on)) }
            Hint(device, stringResource(R.string.detail_hint_onoff))
        },
    )
}

/** Feste Positionen für die Karten (Prozent offen). */
private val CoverPositions = listOf(R.string.cover_pos_open to 100, R.string.cover_pos_half to 50, R.string.cover_pos_gap to 20, R.string.cover_pos_closed to 0)

@Composable
private fun CoverBody(device: Device, cover: CoverCapability, send: (DeviceCommand) -> Unit) {
    val g = LocalGlass.current
    val online = device.isOnline
    var preview by remember(device.id) { mutableStateOf<Float?>(null) }
    val pos = cover.openPercent
    TwoColumns(
        left = {
            RingStage(
                fraction = pos / 100f, accent = Sky, enabled = online, disk = if (g.dark) Color(0xFF1C2638) else Color(0xFFEEF5FC),
                dec = stringResource(R.string.cover_step_close) to { send(DeviceCommand.SetCoverPosition((pos - 10).coerceAtLeast(0))) },
                inc = stringResource(R.string.cover_step_open) to { send(DeviceCommand.SetCoverPosition((pos + 10).coerceAtMost(100))) },
                decIcon = Icons.Outlined.KeyboardArrowDown, incIcon = Icons.Outlined.KeyboardArrowUp,
                onPreview = { preview = it },
                center = { f -> WindowArt((f * 100).roundToInt(), cover.movement, online, Modifier.size(196.dp, 156.dp)) },
            ) { send(DeviceCommand.SetCoverPosition((it * 20).roundToInt() * 5)) }
            val shown = preview?.let { (it * 20).roundToInt() * 5 } ?: pos
            BigValue("$shown %", stringResource(R.string.dial_open))
        },
        right = {
            DetailLabel(stringResource(R.string.cover_position))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CoverPositions.forEach { (label, v) ->
                    ChoiceCard(abs(pos - v) <= 5, online, Modifier.weight(1f), { send(DeviceCommand.SetCoverPosition(v)) }) { fg, _ ->
                        WindowArt(v, CoverMovement.STOPPED, true, Modifier.fillMaxWidth().height(92.dp))
                        Text(stringResource(label), color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            cover.tiltOpenPercent?.let { tilt ->
                DetailLabel(stringResource(R.string.cover_tilt_label, tilt), Modifier.padding(top = 22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(R.string.tilt_closed to 0, R.string.tilt_half to 50, R.string.tilt_open to 100).forEach { (label, v) ->
                        val on = abs(tilt - v) < 20
                        Box(
                            Modifier.weight(1f).height(60.dp).alpha(if (online) 1f else 0.5f).clip(RoundedCornerShape(30.dp)).background(if (on) g.ink else g.chip)
                                .clickable(enabled = online) { send(DeviceCommand.SetCoverTilt(v)) },
                            contentAlignment = Alignment.Center,
                        ) { Text(stringResource(label), color = if (on) g.glassSolid else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Während der Fahrt ist Stopp der Hauptknopf
            val moving = cover.movement != CoverMovement.STOPPED
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.cover_open), online, Modifier.weight(1f)) { send(DeviceCommand.OpenCover) }
                Box(
                    Modifier.size(68.dp).alpha(if (online) 1f else 0.45f).clip(CircleShape).background(if (moving) g.ink else g.chip)
                        .clickable(enabled = online, onClickLabel = stringResource(R.string.cover_stop)) { send(DeviceCommand.StopCover) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Stop, stringResource(R.string.cover_stop), Modifier.size(26.dp), tint = if (moving) g.glassSolid else g.ink) }
                PillButton(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.cover_close), online, Modifier.weight(1f)) { send(DeviceCommand.CloseCover) }
            }
            Hint(device, stringResource(R.string.detail_hint_cover))
        },
    )
}

@Composable
private fun PillButton(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        modifier.height(68.dp).alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(34.dp)).background(g.chip).clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = g.ink)
        Spacer(Modifier.width(8.dp))
        Text(label, color = g.ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Schnellwahl der Solltemperatur (°C). */
private val QuickTargets = listOf(17.0, 19.0, 21.0, 23.0)

@Composable
private fun ThermostatBody(device: Device, t: ThermostatCapability, send: (DeviceCommand) -> Unit) {
    val g = LocalGlass.current
    val online = device.isOnline
    val step = Units.stepCelsius(LocalTemperatureUnit.current)
    val span = t.maxTargetCelsius - t.minTargetCelsius
    fun toC(f: Float) = (t.minTargetCelsius + f * span).let { (it / step).roundToInt() * step }.coerceIn(t.minTargetCelsius, t.maxTargetCelsius)
    fun frac(c: Double) = ((c - t.minTargetCelsius) / span).toFloat().coerceIn(0f, 1f)
    val off = t.mode == ThermostatMode.OFF
    val accent = when (t.mode) { ThermostatMode.COOL -> Cool; ThermostatMode.AUTO -> Green; else -> Heat }
    TwoColumns(
        left = {
            RingStage(
                fraction = frac(t.targetCelsius), accent = if (off) g.track else accent, enabled = online,
                disk = if (g.dark) Color(0xFF2A1E18) else Color(0xFFFFF3EA),
                marker = t.currentCelsius?.let { frac(it) },
                dec = stringResource(R.string.thermostat_decrease) to { send(DeviceCommand.SetTargetTemperature((t.targetCelsius - step).coerceAtLeast(t.minTargetCelsius))) },
                inc = stringResource(R.string.thermostat_increase) to { send(DeviceCommand.SetTargetTemperature((t.targetCelsius + step).coerceAtMost(t.maxTargetCelsius))) },
                center = { f ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 12.dp)) {
                        Icon(modeIcon(t.mode), null, Modifier.size(30.dp), tint = if (off) g.soft else accent)
                        Text(if (off) stringResource(R.string.state_off) else formatDegrees(toC(f)), fontSize = 60.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, lineHeight = 64.sp)
                        t.currentCelsius?.let { Text(stringResource(R.string.dial_current, formatDegrees(it)), color = g.soft, fontSize = 16.sp) }
                    }
                },
            ) { send(DeviceCommand.SetTargetTemperature(toC(it))) }
            Text(
                stringResource(R.string.thermostat_ring_hint).uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 16.dp),
            )
        },
        right = {
            if (t.supportedModes.size > 1) {
                DetailLabel(stringResource(R.string.mode))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThermostatMode.entries.filter { it in t.supportedModes }.forEach { m ->
                        val on = t.mode == m
                        val mAccent = when (m) { ThermostatMode.OFF -> g.soft; ThermostatMode.COOL -> Cool; ThermostatMode.AUTO -> Green; else -> Heat }
                        ChoiceCard(on, online, Modifier.weight(1f).height(84.dp), { send(DeviceCommand.SetThermostatMode(m)) }) { fg, _ ->
                            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(48.dp).clip(CircleShape).background(if (on && m != ThermostatMode.OFF) mAccent else g.glassSolid),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(modeIcon(m), null, Modifier.size(24.dp), tint = if (on && m != ThermostatMode.OFF) Color.White else mAccent) }
                                Text(stringResource(m.labelRes), color = fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 14.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            DetailLabel(stringResource(R.string.quick_select))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTargets.filter { it in t.minTargetCelsius..t.maxTargetCelsius }.forEach { v ->
                    val on = !off && abs(t.targetCelsius - v) < 0.05
                    Box(
                        Modifier.weight(1f).height(56.dp).alpha(if (online) 1f else 0.5f).clip(RoundedCornerShape(28.dp))
                            .background(if (on) accent.copy(alpha = if (g.dark) 0.35f else 0.25f) else g.chip)
                            .clickable(enabled = online) {
                                if (off) t.supportedModes.firstOrNull { it == ThermostatMode.HEAT }?.let { send(DeviceCommand.SetThermostatMode(it)) }
                                send(DeviceCommand.SetTargetTemperature(v))
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(formatDegrees(v, decimals = false), color = g.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            if (t.currentCelsius != null) {
                Box(Modifier.padding(top = 20.dp).weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(g.chip).padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp)) {
                    SensorHistoryView(device, Modifier.fillMaxSize(), compact = true)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (!online) Hint(device, null)
        },
    )
}

private fun modeIcon(m: ThermostatMode): ImageVector = when (m) {
    ThermostatMode.OFF -> Icons.Outlined.PowerSettingsNew
    ThermostatMode.HEAT -> Icons.Outlined.LocalFireDepartment
    ThermostatMode.COOL -> Icons.Outlined.AcUnit
    ThermostatMode.AUTO -> Icons.Outlined.Autorenew
}

/** Geräte ohne eigene Bedienung (Sensoren ohne Verlauf, Unbekanntes). */
@Composable
private fun FallbackBody(device: Device) {
    val g = LocalGlass.current
    TwoColumns(
        left = {
            Column(
                Modifier.size(300.dp).clip(CircleShape).background(g.chip),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(RaumIcons.device(device), null, Modifier.size(56.dp), tint = g.ink)
                Text(
                    if (device.isOnline) sensorLabel(device) else stringResource(R.string.state_offline),
                    fontSize = 28.sp, lineHeight = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 30.dp, vertical = 10.dp),
                )
            }
        },
        right = {
            Spacer(Modifier.weight(1f))
            if (device.capabilities.find<UnknownCapability>() != null) {
                Text(stringResource(R.string.device_unsupported), color = g.soft, fontSize = 16.sp)
            }
            Hint(device, null)
        },
    )
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(g.chip).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(label.uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, maxLines = 1)
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Light, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

// --- Einstellungen ----------------------------------------------------------------------------

/** Zahnrad: links Kachel-Vorschau und Geräteinfos, rechts Name, Raum, „Als Licht führen“ und Entfernen. */
@Composable
private fun SettingsBody(device: Device, rooms: List<Room>, actions: DeviceDetailActions, onRemove: () -> Unit, onDone: () -> Unit) {
    val g = LocalGlass.current
    val previewActions = remember { DeviceCardActions(onToggle = {}, onCommand = { _, _ -> }, onOpenDetails = {}) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(330.dp).fillMaxHeight()) {
            DetailLabel(stringResource(R.string.settings_tile))
            GlassDeviceTile(device, previewActions, Modifier.width(230.dp).height(220.dp), roomName = rooms.firstOrNull { it.id == device.roomId }?.name)
            DetailLabel(stringResource(R.string.settings_device), Modifier.padding(top = 22.dp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoLine(stringResource(R.string.device_vendor), device.vendorName ?: "–")
                InfoLine(stringResource(R.string.device_product), device.productName ?: "–")
                device.network?.let { n -> InfoLine(stringResource(R.string.device_connection), connectionText(n)) }
                device.endpointId?.let { InfoLine(stringResource(R.string.device_channel), stringResource(R.string.device_channel_value, it)) }
                InfoLine(stringResource(R.string.device_node_id), "0x%016X".format(device.matterNodeId.toLong()), mono = true)
                device.capabilities.find<BatteryCapability>()?.let { InfoLine(stringResource(R.string.battery), "${it.percent} %") }
                InfoLine(stringResource(R.string.last_seen), formatAgo(device.lastSeenAt))
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                DetailLabel(stringResource(R.string.name))
                var name by remember(device.id) { mutableStateOf(device.displayName) }
                val canRename = name.isNotBlank() && name.trim() != device.displayName
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                        textStyle = TextStyle(color = g.ink, fontSize = 19.sp), cursorBrush = SolidColor(g.ink),
                        modifier = Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(30.dp)).background(g.glassSolid)
                            .border(2.dp, g.ink, RoundedCornerShape(30.dp)).padding(horizontal = 22.dp),
                        decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { inner() } },
                    )
                    Box(
                        Modifier.height(60.dp).clip(RoundedCornerShape(30.dp)).background(if (canRename) g.ink else g.chip)
                            .clickable(enabled = canRename) { actions.onRename(device, name) }.padding(horizontal = 26.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.rename), color = if (canRename) g.glassSolid else g.soft, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                }

                DetailLabel(stringResource(R.string.room), Modifier.padding(top = 22.dp))
                val options: List<Room?> = rooms + listOf(null)
                FixedGrid(options, columns = 4, spacing = 10.dp) { room, mod ->
                    val on = device.roomId == room?.id
                    Row(
                        mod.height(56.dp).clip(RoundedCornerShape(20.dp)).background(if (on) g.ink else g.chip)
                            .clickable { actions.onAssignRoom(device, room?.id) }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fg = if (on) g.glassSolid else if (room == null) g.soft else g.ink
                        if (room != null) Icon(RaumIcons.room(room.icon), null, Modifier.size(22.dp), tint = fg)
                        Text(room?.name ?: stringResource(R.string.no_room), color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (room != null) 10.dp else 0.dp))
                    }
                }

                // Schaltaktor hinter dem Lichtschalter: als Licht führen (Symbol, Lichtszenen, „Alle Lichter aus“)
                if (device.switchAsLight || device.capabilities.any { it is SwitchCapability }) {
                    Row(
                        Modifier.padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(g.chip).padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.device_switch_as_light), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.device_switch_as_light_hint), color = g.soft, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Switch(
                            device.switchAsLight, { actions.onSwitchAsLight(device, it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = g.ink, checkedThumbColor = Amber),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.height(60.dp).clip(RoundedCornerShape(30.dp)).border(2.dp, if (g.dark) g.warn else Danger, RoundedCornerShape(30.dp))
                        .clickable(onClick = onRemove).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(22.dp), tint = if (g.dark) g.warn else Danger)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.device_remove), color = if (g.dark) g.warn else Danger, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.height(60.dp).clip(RoundedCornerShape(30.dp)).background(g.ink).clickable(onClick = onDone).padding(horizontal = 34.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.action_done), color = g.glassSolid, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
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
    val g = LocalGlass.current
    Row {
        Text(label, color = g.soft, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.width(130.dp))
        Text(value, color = g.ink, fontSize = if (mono) 13.sp else 14.sp, lineHeight = 20.sp, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

// --- Gemeinsam genutzt (Musik, Szenen) ------------------------------------------------------------

/**
 * Drehregler über 270°: Ziehen am Ring (oder Tippen darauf) zeigt den Wert sofort,
 * gesendet wird beim Loslassen. Mit [track] ist der ganze Ring ein Verlauf (Weißton, Farbe) statt einer Füllung;
 * [marker] setzt einen Punkt auf den Ring (Ist-Temperatur), [onPreview] meldet den Wert während des Ziehens.
 */
@Composable
internal fun Dial(
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
    ringWidth: Dp = 30.dp,
    marker: Float? = null,
    onPreview: ((Float?) -> Unit)? = null,
    onCommit: (Float) -> Unit,
) {
    val g = LocalGlass.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(fraction) }
    LaunchedEffect(fraction) { if (!dragging) local = fraction.coerceIn(0f, 1f) }
    LaunchedEffect(dragging, local) { onPreview?.invoke(if (dragging) local else null) }
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
            val ring = ringWidth.toPx()
            val r = size.minDimension / 2f - ring / 2f
            val tl = Offset(center.x - r, center.y - r)
            val arc = Size(r * 2, r * 2)
            if (track != null) {
                // Kontur darunter, damit helle Enden (kaltweiß) auf hellem Grund sichtbar bleiben
                drawArc(g.edge, 135f, 270f, false, tl, arc, style = Stroke(ring + 3.dp.toPx(), cap = StrokeCap.Round))
                drawArc(track(center), 135f, 270f, false, tl, arc, style = Stroke(ring, cap = StrokeCap.Round))
            } else {
                drawArc(g.track, 135f, 270f, false, tl, arc, style = Stroke(ring, cap = StrokeCap.Round))
                if (local > 0f) drawArc(accent, 135f, 270f * local, false, tl, arc, style = Stroke(ring, cap = StrokeCap.Round))
            }
            marker?.let { m ->
                val a = Math.toRadians((135f + 270f * m.coerceIn(0f, 1f)).toDouble())
                drawCircle(g.ink, 5.dp.toPx(), Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat()))
            }
            val angle = Math.toRadians((135f + 270f * local).toDouble())
            val knob = Offset(center.x + r * cos(angle).toFloat(), center.y + r * sin(angle).toFloat())
            drawCircle(Color.Black.copy(alpha = 0.18f), 16.dp.toPx(), knob + Offset(0f, 2.dp.toPx()))
            drawCircle(Color.White, 14.dp.toPx(), knob)
            knobColor?.let { drawCircle(it(local), 9.dp.toPx(), knob) } ?: if (track == null) drawCircle(accent, 14.dp.toPx(), knob, style = Stroke(3.5.dp.toPx())) else Unit
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

@get:StringRes
val ThermostatMode.labelRes: Int
    get() = when (this) {
        ThermostatMode.OFF -> R.string.thermostat_off
        ThermostatMode.HEAT -> R.string.thermostat_heat
        ThermostatMode.COOL -> R.string.thermostat_cool
        ThermostatMode.AUTO -> R.string.thermostat_auto
    }

@Composable
fun ValueBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineMedium)
    }
}

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
