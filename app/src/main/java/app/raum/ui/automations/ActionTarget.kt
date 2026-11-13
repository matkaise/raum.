package app.raum.ui.automations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.domain.models.CoverMovement
import app.raum.domain.models.Device
import app.raum.domain.models.LightCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.find
import app.raum.domain.usecases.DeviceTarget
import app.raum.ui.components.ColorQuickPicks
import app.raum.ui.components.Dial
import app.raum.ui.components.HueSat
import app.raum.ui.components.LampArt
import app.raum.ui.components.LightDialMode
import app.raum.ui.components.LocalTemperatureUnit
import app.raum.ui.components.SocketArt
import app.raum.ui.components.Units
import app.raum.ui.components.WhitePresets
import app.raum.ui.components.WindowArt
import app.raum.ui.components.arcGradient
import app.raum.ui.components.currentLocale
import app.raum.ui.components.fractionOf
import app.raum.ui.components.hueSat
import app.raum.ui.components.kelvinAt
import app.raum.ui.components.kelvinColor
import app.raum.ui.components.labelRes
import app.raum.ui.theme.LocalGlass
import kotlin.math.abs
import kotlin.math.roundToInt

private val Amber = Color(0xFFFFC24F)
private val OnAmber = Color(0xFF2E1D05)
private val Heat = Color(0xFFF0894A)
private val Sky = Color(0xFF7EC8F8)
private val RING = 280.dp

private val BrightnessPresets = listOf(R.string.light_preset_night to 5, R.string.light_preset_dim to 30, R.string.light_preset_bright to 80, R.string.light_preset_full to 100)
private val CoverPositions = listOf(R.string.cover_pos_open to 100, R.string.cover_pos_half to 50, R.string.cover_pos_gap to 20, R.string.cover_pos_closed to 0)
private val QuickTargets = listOf(17.0, 19.0, 21.0, 23.0)

/** Kleines Bild des Zielzustands im Kreis – wie auf den Kacheln. */
@Composable
fun TargetArt(target: DeviceTarget?, device: Device?, size: Dp = 56.dp) {
    val bg = when (target) {
        is DeviceTarget.Light -> if (target.on) Color(0xFFFFF3DC) else Color.White
        is DeviceTarget.Thermostat -> Color(0xFFFFE0CC)
        else -> Color.White
    }
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        when (target) {
            is DeviceTarget.Light -> LampArt(target.on, (target.brightnessPercent ?: 100) / 100f, lightColor(target), true, Modifier.size(size * 0.8f, size * 0.66f))
            is DeviceTarget.Cover -> WindowArt(target.openPercent, CoverMovement.STOPPED, true, Modifier.size(size * 0.74f, size * 0.6f))
            is DeviceTarget.Switch -> SocketArt(target.on, Modifier.size(size * 0.7f))
            is DeviceTarget.Thermostat -> Icon(Icons.Outlined.Thermostat, null, Modifier.size(size * 0.5f), tint = Color(0xFFB4561F))
            null -> {
                val st = FlowLabels.deviceKind(device).style()
                Icon(st.icon, null, Modifier.size(size * 0.45f), tint = st.fg)
            }
        }
    }
}

private fun lightColor(t: DeviceTarget.Light): Color? = t.color?.let { Color(it.argb) } ?: t.colorTemperatureKelvin?.let { kelvinColor(it) }

/**
 * Gewähltes Gerät oben als Leiste („Anderes Gerät“), darunter die Bedienung wie im Gerätefenster:
 * links der Ring mit Bild und −/+, rechts Auswahlkarten. Nichts wird gesendet – es ist der Zielzustand der Aktion.
 */
@Composable
fun ActionTargetPanel(device: Device, roomName: String?, target: DeviceTarget, onChange: (DeviceTarget) -> Unit, onChangeDevice: () -> Unit) {
    val g = LocalGlass.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(g.ink.copy(alpha = 0.05f)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TargetArt(target, device)
            Column(Modifier.weight(1f)) {
                Text(device.displayName, color = g.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    roomName?.let { Text(it, color = g.soft, fontSize = 13.sp, maxLines = 1) }
                    if (!device.isOnline) {
                        Text((if (roomName != null) " · " else "") + stringResource(R.string.state_offline), color = Color(0xFFB3261E), fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(g.ink.copy(alpha = 0.07f)).clickable(onClick = onChangeDevice).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(20.dp), tint = g.ink)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_other_device), color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        when (target) {
            is DeviceTarget.Light -> LightTarget(device, target, onChange)
            is DeviceTarget.Switch -> SwitchTarget(target, onChange)
            is DeviceTarget.Cover -> CoverTarget(target, onChange)
            is DeviceTarget.Thermostat -> ThermostatTarget(device, target, onChange)
        }
    }
}

// --- Bausteine ----------------------------------------------------------------------------------

@Composable
private fun Stage(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.width(RING), horizontalAlignment = Alignment.CenterHorizontally, content = left)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = right)
    }
}

/** Ring wie im Gerätefenster, kleiner: Bild in der Scheibe, −/+ in der Lücke. */
@Composable
private fun TargetRing(
    fraction: Float,
    accent: Color,
    disk: Color,
    enabled: Boolean = true,
    track: ((Offset) -> Brush)? = null,
    knobColor: ((Float) -> Color)? = null,
    dec: Pair<String, () -> Unit>,
    inc: Pair<String, () -> Unit>,
    onPreview: (Float?) -> Unit,
    center: @Composable (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    Box(Modifier.size(RING)) {
        Dial(
            fraction = fraction, accent = accent, enabled = enabled, label = "", value = { "" }, sub = { "" },
            track = track, knobColor = knobColor, onPreview = onPreview,
            centerContent = { f -> Box(Modifier.size(184.dp).clip(CircleShape).background(disk), contentAlignment = Alignment.Center) { center(f) } },
            modifier = Modifier.fillMaxSize(), ringWidth = 16.dp, onCommit = onCommit,
        )
        GapButton(Icons.Outlined.Remove, dec.first, enabled, (-36).dp, dec.second)
        GapButton(Icons.Outlined.Add, inc.first, enabled, 36.dp, inc.second)
    }
}

@Composable
private fun BoxScope.GapButton(icon: ImageVector, label: String, enabled: Boolean, x: Dp, onClick: () -> Unit) {
    Box(
        Modifier.align(Alignment.BottomCenter).offset(x = x, y = (-2).dp).size(52.dp).alpha(if (enabled) 1f else 0.4f)
            .shadow(4.dp, CircleShape).clip(CircleShape).background(Color.White)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(22.dp), tint = Color(0xFF10131F)) }
}

@Composable
private fun BigValue(value: String, label: String) {
    val g = LocalGlass.current
    Text(value, color = g.ink, fontSize = 34.sp, fontWeight = FontWeight.Light, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
    Text(label.uppercase(), color = g.soft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, maxLines = 1)
}

/** Aus | An über die ganze Breite – An in Bernstein wie der große Knopf im Gerätefenster. */
@Composable
private fun OnOffSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(g.ink.copy(alpha = 0.07f)).padding(4.dp)) {
        listOf(false to R.string.state_off, true to R.string.state_on).forEach { (v, label) ->
            val sel = on == v
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp))
                    .background(if (sel) (if (v) Amber else g.ink) else Color.Transparent).clickable { onChange(v) },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(label), color = if (sel) (if (v) OnAmber else onInk()) else g.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun <T> Tabs(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(g.ink.copy(alpha = 0.07f)).padding(4.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(if (on) g.ink else Color.Transparent).clickable { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) { Text(label(o), color = if (on) onInk() else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/** Auswahlkarte: Titel und Wert, gewählt dunkel. */
@Composable
private fun Choice(title: String, value: String?, selected: Boolean, modifier: Modifier, bg: Color? = null, onClick: () -> Unit) {
    val g = LocalGlass.current
    val fg = if (selected && bg == null) onInk() else if (bg != null) OnAmber else g.ink
    Column(
        modifier.height(72.dp).clip(RoundedCornerShape(20.dp)).background(if (selected && bg == null) g.ink else bg ?: g.ink.copy(alpha = 0.06f))
            .then(if (selected && bg != null) Modifier.border(3.dp, g.ink, RoundedCornerShape(20.dp)) else Modifier)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        value?.let { Text(it, color = fg.copy(alpha = 0.66f), fontSize = 12.sp, maxLines = 1) }
    }
}

// --- je Gerätetyp -------------------------------------------------------------------------------

@Composable
private fun LightTarget(device: Device, t: DeviceTarget.Light, onChange: (DeviceTarget) -> Unit) {
    val g = LocalGlass.current
    val cap = device.capabilities.find<LightCapability>()
    val range = cap?.colorTemperatureRange?.takeIf { it.last > it.first } ?: if (t.colorTemperatureKelvin != null) 2200..6500 else null
    val modes = listOfNotNull(
        LightDialMode.BRIGHTNESS.takeIf { cap?.isDimmable == true || t.brightnessPercent != null },
        LightDialMode.WHITE.takeIf { range != null },
        LightDialMode.COLOR.takeIf { cap?.supportsColor == true || t.color != null },
    )
    var mode by remember { mutableStateOf(if (t.color != null && LightDialMode.COLOR in modes) LightDialMode.COLOR else modes.firstOrNull()) }
    var preview by remember(mode) { mutableStateOf<Float?>(null) }
    val hs = t.color?.hueSat() ?: HueSat(30f, 0.7f)
    val sat = hs.saturation.coerceAtLeast(0.35f)
    // Einschalten übernimmt die Helligkeit der Leuchte, falls die Aktion noch keine hat
    fun on(v: Boolean) = onChange(if (!v) t.copy(on = false) else t.copy(on = true, brightnessPercent = t.brightnessPercent ?: cap?.brightnessPercent))
    fun white(k: Int) = onChange(t.copy(on = true, colorTemperatureKelvin = k, color = null))
    fun color(h: HueSat) = onChange(t.copy(on = true, color = h.toRgb(), colorTemperatureKelvin = null))

    Stage(
        left = {
            val lit = lightColor(t)
            when (if (t.on) mode else null) {
                LightDialMode.WHITE -> {
                    val r = range!!
                    val k = t.colorTemperatureKelvin ?: r.first
                    TargetRing(
                        fractionOf(r, k), Amber, Color(0xFFFFF2E2),
                        track = { c -> arcGradient(c) { kelvinColor(kelvinAt(r, it)) } }, knobColor = { kelvinColor(kelvinAt(r, it)) },
                        dec = stringResource(R.string.light_warmer) to { white((k - 100).coerceAtLeast(r.first)) },
                        inc = stringResource(R.string.light_cooler) to { white((k + 100).coerceAtMost(r.last)) },
                        onPreview = { preview = it },
                        center = { f -> LampArt(true, 1f, kelvinColor(kelvinAt(r, f)), true, Modifier.fillMaxSize().padding(top = 8.dp)) },
                    ) { white(kelvinAt(r, it)) }
                    BigValue("${preview?.let { kelvinAt(r, it) } ?: k} K", stringResource(R.string.light_tab_white))
                }
                LightDialMode.COLOR -> {
                    TargetRing(
                        hs.hue / 360f, Amber, Color(0xFFF4ECFF),
                        track = { c -> arcGradient(c) { HueSat(it * 360f, 0.85f).color() } }, knobColor = { HueSat(it * 360f, sat).color() },
                        dec = stringResource(R.string.light_hue_prev) to { color(HueSat((hs.hue - 5f + 360f) % 360f, sat)) },
                        inc = stringResource(R.string.light_hue_next) to { color(HueSat((hs.hue + 5f) % 360f, sat)) },
                        onPreview = { preview = it },
                        center = { f -> LampArt(true, 1f, HueSat(f * 360f, sat).color(), true, Modifier.fillMaxSize().padding(top = 8.dp)) },
                    ) { color(HueSat(it * 360f, sat)) }
                    BigValue(stringResource(R.string.color), stringResource(R.string.scene_light_tone))
                }
                else -> {
                    // Helligkeit – oder ausgeschaltet: Ring leer, Leuchte dunkel
                    val pct = if (t.on) t.brightnessPercent ?: 100 else 0
                    val dimmable = LightDialMode.BRIGHTNESS in modes
                    TargetRing(
                        pct / 100f, Amber, if (t.on) Color(0xFFFFF6E4) else Color(0xFFF0F1F5), enabled = dimmable,
                        dec = stringResource(R.string.light_darker) to { onChange(t.copy(on = true, brightnessPercent = (pct - 10).coerceAtLeast(1))) },
                        inc = stringResource(R.string.light_brighter) to { onChange(t.copy(on = true, brightnessPercent = (pct + 10).coerceIn(1, 100))) },
                        onPreview = { preview = it },
                        center = { f -> LampArt(f > 0f || (t.on && !dimmable), if (dimmable) f else 1f, lit, true, Modifier.fillMaxSize().padding(top = 8.dp)) },
                    ) { onChange(t.copy(on = true, brightnessPercent = (it * 100).roundToInt().coerceIn(1, 100))) }
                    val shown = ((preview ?: (pct / 100f)) * 100).roundToInt()
                    BigValue(if (!t.on) stringResource(R.string.state_off) else if (dimmable) "$shown %" else stringResource(R.string.state_on), stringResource(R.string.brightness))
                }
            }
        },
        right = {
            OnOffSwitch(t.on, ::on)
            if (t.on && mode != null) {
                if (modes.size > 1) Tabs(modes, mode!!, { stringResource(if (it == LightDialMode.WHITE) R.string.light_tab_white else it.labelRes) }) { mode = it }
                when (mode) {
                    LightDialMode.BRIGHTNESS -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val pct = t.brightnessPercent ?: 100
                        BrightnessPresets.forEach { (label, v) ->
                            Choice(stringResource(label), "$v %", abs(pct - v) <= 5, Modifier.weight(1f)) { onChange(t.copy(on = true, brightnessPercent = v)) }
                        }
                    }
                    LightDialMode.WHITE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val r = range!!
                        WhitePresets.forEach { (label, f) ->
                            val k = kelvinAt(r, f)
                            val sel = t.color == null && t.colorTemperatureKelvin?.let { abs(it - k) < 100 } == true
                            Choice(stringResource(label), "$k K", sel, Modifier.weight(1f), bg = kelvinColor(k)) { white(k) }
                        }
                    }
                    LightDialMode.COLOR -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ColorQuickPicks.forEach { pick ->
                            val sel = t.color != null && pick.near(hs)
                            Box(
                                Modifier.size(44.dp).then(if (sel) Modifier.border(3.dp, g.ink, CircleShape).padding(5.dp) else Modifier)
                                    .clip(CircleShape).background(pick.color()).clickable { color(pick) },
                            )
                        }
                    }
                    null -> Unit
                }
            }
        },
    )
}

@Composable
private fun SwitchTarget(t: DeviceTarget.Switch, onChange: (DeviceTarget) -> Unit) {
    Stage(
        left = {
            Box(Modifier.size(RING).padding(30.dp).clip(CircleShape).background(if (t.on) Color(0xFFE9FBEF) else Color(0xFFF0F1F5)), contentAlignment = Alignment.Center) {
                SocketArt(t.on, Modifier.size(110.dp))
            }
            BigValue(stringResource(if (t.on) R.string.state_on else R.string.state_off), stringResource(R.string.target_state))
        },
        right = { OnOffSwitch(t.on) { onChange(t.copy(on = it)) } },
    )
}

@Composable
private fun CoverTarget(t: DeviceTarget.Cover, onChange: (DeviceTarget) -> Unit) {
    var preview by remember { mutableStateOf<Float?>(null) }
    Stage(
        left = {
            TargetRing(
                t.openPercent / 100f, Sky, Color(0xFFEAF5FD),
                dec = stringResource(R.string.less) to { onChange(t.copy(openPercent = (t.openPercent - 10).coerceAtLeast(0))) },
                inc = stringResource(R.string.more) to { onChange(t.copy(openPercent = (t.openPercent + 10).coerceAtMost(100))) },
                onPreview = { preview = it },
                center = { f -> WindowArt((f * 100).roundToInt(), CoverMovement.STOPPED, true, Modifier.size(120.dp, 98.dp)) },
            ) { onChange(t.copy(openPercent = (it * 100).roundToInt())) }
            BigValue("${preview?.let { (it * 100).roundToInt() } ?: t.openPercent} %", stringResource(R.string.position))
        },
        right = {
            FieldLabel(stringResource(R.string.position))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CoverPositions.forEach { (label, v) ->
                    Choice(stringResource(label), "$v %", t.openPercent == v, Modifier.weight(1f)) { onChange(t.copy(openPercent = v)) }
                }
            }
            t.tiltOpenPercent?.let { tilt ->
                FieldLabel(stringResource(R.string.cover_tilt_label, tilt), Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.tilt_closed to 0, R.string.tilt_half to 50, R.string.tilt_open to 100).forEach { (label, v) ->
                        Choice(stringResource(label), null, abs(tilt - v) < 20, Modifier.weight(1f)) { onChange(t.copy(tiltOpenPercent = v)) }
                    }
                }
            }
        },
    )
}

@Composable
private fun ThermostatTarget(device: Device, t: DeviceTarget.Thermostat, onChange: (DeviceTarget) -> Unit) {
    val cap = device.capabilities.find<ThermostatCapability>()
    val min = cap?.minTargetCelsius ?: 5.0
    val max = cap?.maxTargetCelsius ?: 30.0
    val unit = LocalTemperatureUnit.current
    val locale = currentLocale()
    val step = Units.stepCelsius(unit)
    val modes = (cap?.supportedModes ?: setOf(ThermostatMode.OFF, ThermostatMode.HEAT)).let { s -> ThermostatMode.entries.filter { it in s } }
    val off = t.mode == ThermostatMode.OFF
    var preview by remember { mutableStateOf<Float?>(null) }
    fun at(f: Float) = (Math.round((min + f * (max - min)) / step) * step).coerceIn(min, max)
    Stage(
        left = {
            TargetRing(
                ((t.targetCelsius - min) / (max - min)).toFloat().coerceIn(0f, 1f), Heat, Color(0xFFFFF1E8), enabled = !off,
                dec = stringResource(R.string.less) to { onChange(t.copy(targetCelsius = (t.targetCelsius - step).coerceAtLeast(min))) },
                inc = stringResource(R.string.more) to { onChange(t.copy(targetCelsius = (t.targetCelsius + step).coerceAtMost(max))) },
                onPreview = { preview = it },
                center = { f ->
                    Text(
                        if (off) stringResource(R.string.state_off) else Units.format(preview?.let { at(it) } ?: t.targetCelsius, unit, locale),
                        color = Color(0xFF10131F), fontSize = 44.sp, fontWeight = FontWeight.Light,
                    )
                },
            ) { onChange(t.copy(targetCelsius = at(it))) }
            BigValue(stringResource(t.mode?.labelRes ?: R.string.thermostat_heat), stringResource(R.string.target_temperature))
        },
        right = {
            if (modes.size > 1) {
                FieldLabel(stringResource(R.string.mode))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { m -> Choice(stringResource(m.labelRes), null, t.mode == m, Modifier.weight(1f)) { onChange(t.copy(mode = m)) } }
                }
            }
            if (!off) {
                FieldLabel(stringResource(R.string.quick_select), Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickTargets.filter { it in min..max }.forEach { v ->
                        Choice(Units.format(v, unit, locale), null, abs(t.targetCelsius - v) < 0.05, Modifier.weight(1f)) { onChange(t.copy(targetCelsius = v)) }
                    }
                }
            }
        },
    )
}
