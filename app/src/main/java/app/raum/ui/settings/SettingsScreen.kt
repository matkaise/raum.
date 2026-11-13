package app.raum.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.BuildConfig
import app.raum.R
import app.raum.data.preferences.TemperatureUnit
import app.raum.data.preferences.ThemeMode
import app.raum.i18n.AppLanguage
import app.raum.platform.display.SleepAction
import app.raum.ui.HomeUiState
import app.raum.ui.appliance.ApplianceViewModel
import app.raum.ui.appliance.SecurityCard
import app.raum.ui.components.currentLocale
import app.raum.ui.components.rememberLogTimeFormatter
import app.raum.ui.overview.SkyChip
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextStyle
import app.raum.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

/** Gruppen der Kategorienliste. */
enum class SettingsGroup(@StringRes val titleRes: Int) {
    HOME(R.string.settings_group_home), SERVICES(R.string.settings_group_services), SYSTEM(R.string.settings_group_system),
}

/** Kategorien der Einstellungen – das Protokoll bewusst weit unten (Diagnose, nicht Alltag). */
enum class SettingsSection(@StringRes val titleRes: Int, val icon: ImageVector, val group: SettingsGroup) {
    HOME(R.string.settings_home, Icons.Outlined.Home, SettingsGroup.HOME),
    NETWORK(R.string.settings_network, Icons.Outlined.Wifi, SettingsGroup.HOME),
    DISPLAY(R.string.settings_display_design, Icons.Outlined.Palette, SettingsGroup.HOME),
    REGION(R.string.settings_language_region, Icons.Outlined.Language, SettingsGroup.HOME),
    WEATHER(R.string.settings_weather, Icons.Outlined.Cloud, SettingsGroup.HOME),
    HOME_CONNECT(R.string.settings_home_connect, Icons.Outlined.Kitchen, SettingsGroup.SERVICES),
    GROHE(R.string.settings_grohe, Icons.Outlined.WaterDrop, SettingsGroup.SERVICES),
    SONOS(R.string.settings_sonos, Icons.Outlined.Speaker, SettingsGroup.SERVICES),
    SHARE(R.string.settings_share, Icons.Outlined.Share, SettingsGroup.SERVICES),
    MATTER(R.string.settings_matter, Icons.Outlined.DeviceHub, SettingsGroup.SYSTEM),
    SECURITY(R.string.security_maintenance, Icons.Outlined.Shield, SettingsGroup.SYSTEM),
    LOG(R.string.event_log, Icons.Outlined.ReceiptLong, SettingsGroup.SYSTEM),
    ABOUT(R.string.settings_about, Icons.Outlined.Info, SettingsGroup.SYSTEM),
}

/** Kurzstatus einer Kategorie; [problem] färbt ihn rot und setzt einen Punkt. */
private data class SectionStatus(val text: String, val problem: Boolean = false)

/** Einstellungen (Spez. 9.7): Kategorien links in Gruppen, Inhalt rechts mit Titel und Zustand als Tag. */
@Composable
fun SettingsScreen(
    state: HomeUiState,
    onOpenMaintenance: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    appliance: ApplianceViewModel = koinViewModel(),
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.HOME) }
    // Sprung aus anderen Bildschirmen (z. B. „Thread einrichten“ nach einem Kopplungsfehler)
    val requests = org.koin.compose.koinInject<app.raum.ui.UiRequests>()
    val requested by requests.openSettings.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        requested?.let { name -> SettingsSection.entries.firstOrNull { it.name == name }?.let { section = it } }
        requests.openSettings.value = null
    }

    // Netzwerkstatus nur beobachten, solange die Einstellungen offen sind
    DisposableEffect(Unit) {
        viewModel.network.start()
        onDispose { viewModel.network.stop() }
    }

    val statuses = SettingsSection.entries.associateWith { status(it, state, viewModel, appliance) }

    Row(Modifier.fillMaxSize()) {
        val g = LocalGlass.current
        Column(
            Modifier.padding(start = 8.dp, end = 24.dp, top = 24.dp, bottom = 24.dp).width(300.dp).fillMaxHeight()
                .glass(RoundedCornerShape(32.dp), g.glass, g.edge)
                .verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            SettingsGroup.entries.forEach { grp ->
                CardLabel(stringResource(grp.titleRes), Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp))
                SettingsSection.entries.filter { it.group == grp }.forEach { s ->
                    SectionItem(s, statuses.getValue(s), selected = section == s) { section = s }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxHeight().padding(end = 24.dp)) {
            if (section == SettingsSection.LOG) {
                // Protokoll füllt die Höhe und scrollt selbst
                Column(Modifier.fillMaxSize().padding(top = 26.dp, bottom = 32.dp)) {
                    DetailTitle(section, statuses.getValue(section), appliance)
                    LogPanel(viewModel, Modifier.weight(1f))
                }
            } else {
                // eigene Scrollposition je Kategorie – sonst öffnet die nächste mittendrin
                val scroll = key(section) { rememberScrollState() }
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 26.dp, bottom = 32.dp)) {
                    DetailTitle(section, statuses.getValue(section), appliance)
                    when (section) {
                        SettingsSection.HOME -> HomeSection(state, viewModel)
                        SettingsSection.NETWORK -> NetworkSection(viewModel)
                        SettingsSection.DISPLAY -> DisplaySection(viewModel, appliance)
                        SettingsSection.REGION -> RegionSection(viewModel)
                        SettingsSection.SHARE -> ShareSection(roomName = { state.roomName(it.roomId) })
                        SettingsSection.WEATHER -> WeatherSection(state, viewModel, onOpenRegion = { section = SettingsSection.REGION })
                        SettingsSection.HOME_CONNECT -> HomeConnectSection()
                        SettingsSection.GROHE -> GroheSection()
                        SettingsSection.SONOS -> SonosSection()
                        SettingsSection.MATTER -> ThreadSection(advanced = { MatterAdvanced(viewModel) })
                        SettingsSection.SECURITY -> SecurityCard(appliance, onOpenMaintenance)
                        SettingsSection.ABOUT -> AboutSection()
                        SettingsSection.LOG -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun status(s: SettingsSection, state: HomeUiState, vm: SettingsViewModel, appliance: ApplianceViewModel): SectionStatus = when (s) {
    SettingsSection.HOME -> SectionStatus(
        listOf(
            pluralStringResource(R.plurals.rooms_count, state.rooms.size, state.rooms.size),
            pluralStringResource(R.plurals.devices_count, state.devices.size, state.devices.size),
        ).joinToString(" · "),
    )
    SettingsSection.NETWORK -> {
        val st by vm.network.status.collectAsStateWithLifecycle()
        when {
            st.ethernet -> SectionStatus(stringResource(R.string.net_summary_lan))
            st.wifiConnected -> SectionStatus(st.ssid?.let { stringResource(R.string.net_summary_wifi, it) } ?: stringResource(R.string.net_wifi_connected_unknown))
            else -> SectionStatus(stringResource(R.string.net_not_connected), problem = true)
        }
    }
    SettingsSection.DISPLAY -> {
        val theme by vm.themeMode.collectAsStateWithLifecycle()
        val d by appliance.displaySettings.collectAsStateWithLifecycle()
        SectionStatus(
            listOf(
                stringResource(theme.labelRes),
                d.sleepAfterSeconds?.let { sec -> if (sec < 60) stringResource(R.string.sleep_after_short_s, sec) else stringResource(R.string.sleep_after_short_m, sec / 60) }
                    ?: stringResource(R.string.sleep_never_short),
            ).joinToString(" · "),
        )
    }
    SettingsSection.REGION -> {
        val lang by vm.language.collectAsStateWithLifecycle()
        val place by vm.locationName.collectAsStateWithLifecycle()
        val loc by vm.location.collectAsStateWithLifecycle()
        SectionStatus(
            listOf(
                stringResource(lang.labelRes),
                place ?: if (loc != null) stringResource(R.string.location_coordinates) else stringResource(R.string.location_missing_short),
            ).joinToString(" · "),
            problem = loc == null,
        )
    }
    SettingsSection.WEATHER -> SectionStatus(weatherSummary(vm))
    SettingsSection.HOME_CONNECT -> SectionStatus(homeConnectSummary())
    SettingsSection.GROHE -> SectionStatus(groheSummary())
    SettingsSection.SONOS -> SectionStatus(sonosSummary())
    SettingsSection.SHARE -> SectionStatus(shareSummary())
    SettingsSection.MATTER -> threadStatus().let { (text, problem) -> SectionStatus(text, problem) }
    SettingsSection.SECURITY -> {
        val pinSet by appliance.pinSet.collectAsStateWithLifecycle()
        SectionStatus(stringResource(if (pinSet) R.string.pin_is_set_short else R.string.pin_missing_short), problem = !pinSet)
    }
    SettingsSection.LOG -> {
        val last by vm.lastLogEntry.collectAsStateWithLifecycle()
        val fmt = rememberLogTimeFormatter()
        SectionStatus(last?.let { stringResource(R.string.log_last_entry, fmt.format(it.timestamp)) } ?: stringResource(R.string.log_empty))
    }
    SettingsSection.ABOUT -> SectionStatus(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
}

@Composable
private fun SectionItem(s: SettingsSection, status: SectionStatus, selected: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().height(58.dp)
            .then(if (selected) Modifier.shadow(8.dp, RoundedCornerShape(29.dp), ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f)) else Modifier)
            .clip(RoundedCornerShape(29.dp)).background(if (selected) g.glassSolid else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(if (selected) Color(0xFFFFE1A3) else g.ink.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) { Icon(s.icon, null, Modifier.size(20.dp), tint = if (selected) Color(0xFF8A5A12) else g.soft) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(s.titleRes), color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                status.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (status.problem) Color(0xFFB3261E) else g.soft,
            )
        }
        if (status.problem) Box(Modifier.padding(start = 6.dp).size(9.dp).clip(CircleShape).background(Color(0xFFE5675C)))
    }
}

/** Titel mit Zustand als Tag – wie in den anderen Bereichen. */
@Composable
private fun DetailTitle(s: SettingsSection, status: SectionStatus, appliance: ApplianceViewModel) {
    Row(Modifier.padding(start = 4.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(s.titleRes), style = SkyTextStyle, color = SkyText, fontSize = 44.sp, fontWeight = FontWeight.Light, lineHeight = 52.sp, letterSpacing = (-1).sp)
        when (s) {
            SettingsSection.HOME, SettingsSection.REGION -> Unit
            SettingsSection.DISPLAY -> {
                val lux by appliance.lux.collectAsStateWithLifecycle()
                if (appliance.hasLightSensor) lux?.let {
                    SkyChip(Icons.Outlined.LightMode, Color(0xFFFFE1A3), stringResource(R.string.settings_ambient_tag, "%.0f lx".format(it)), height = 40)
                }
            }
            // Symbol der Kategorie statt Häkchen – „Aus“ oder „nicht eingerichtet“ ist kein Erfolg
            else -> SkyChip(
                if (status.problem) Icons.Outlined.WarningAmber else s.icon,
                if (status.problem) Color(0xFFFFBE6E) else SkyText,
                status.text, warning = status.problem, height = 40,
            )
        }
    }
}

@Composable
internal fun Info(label: String, value: String) {
    val g = LocalGlass.current
    Row {
        Text(label, color = g.soft, fontSize = 15.sp, modifier = Modifier.width(200.dp))
        Text(value, color = g.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }
}

/** Eingabefeld als Pille wie im Szenen-Editor. */
@Composable
internal fun PillField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val g = LocalGlass.current
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = g.ink, fontSize = 17.sp), cursorBrush = SolidColor(g.ink),
        modifier = modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(g.glassSolid)
            .border(2.dp, g.ink, RoundedCornerShape(28.dp)).padding(horizontal = 18.dp),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                leading?.invoke()
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, color = g.soft, fontSize = 17.sp, maxLines = 1)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

// --- Zuhause ------------------------------------------------------------------------------

@Composable
private fun HomeSection(state: HomeUiState, vm: SettingsViewModel) {
    val current = state.home?.name ?: ""
    var name by remember(current) { mutableStateOf(current) }
    Group(stringResource(R.string.name)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillField(name, { name = it }, Modifier.widthIn(max = 480.dp).weight(1f, fill = false))
            ActionPill(stringResource(R.string.action_save), primary = true, enabled = name.isNotBlank() && name.trim() != current) { vm.renameHome(name) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StatTile(stringResource(R.string.nav_rooms), state.rooms.size.toString(), Modifier.weight(1f))
        StatTile(stringResource(R.string.nav_devices), state.devices.size.toString(), Modifier.weight(1f),
            detail = if (state.offline.isNotEmpty()) stringResource(R.string.offline_count, state.offline.size) else null)
        StatTile(stringResource(R.string.nav_scenes), state.scenes.size.toString(), Modifier.weight(1f))
        StatTile(stringResource(R.string.nav_automations_short), state.automations.size.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    val g = LocalGlass.current
    Column(modifier.height(132.dp).glass(RoundedCornerShape(28.dp), g.glass, g.edge).padding(20.dp)) {
        Text(value, color = g.ink, fontSize = 40.sp, fontWeight = FontWeight.Light)
        Text(label, color = g.soft, fontSize = 15.sp)
        if (detail != null) Text(detail, color = Color(0xFFB3261E), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// --- Display und Design --------------------------------------------------------------------

private val SLEEP_OPTIONS = listOf(30, 60, 120, 300, 600, null)

@Composable
private fun sleepLabel(seconds: Int?): String = when {
    seconds == null -> stringResource(R.string.never)
    seconds < 60 -> stringResource(R.string.duration_seconds, seconds)
    else -> stringResource(R.string.duration_minutes, seconds / 60)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplaySection(vm: SettingsViewModel, appliance: ApplianceViewModel) {
    val g = LocalGlass.current
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val kioskStatus by appliance.kioskStatus.collectAsStateWithLifecycle()
    val s by appliance.displaySettings.collectAsStateWithLifecycle()
    val canTurnOff = kioskStatus.isDeviceOwner

    Group(stringResource(R.string.settings_appearance)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeMode.entries.forEach { m ->
                val on = theme == m
                val preview = when (m) {
                    ThemeMode.SYSTEM -> Brush.horizontalGradient(0f to Color(0xFF9FC6F0), 0.5f to Color(0xFF9FC6F0), 0.5f to Color(0xFF1B2555), 1f to Color(0xFF1B2555))
                    ThemeMode.LIGHT -> Brush.linearGradient(listOf(Color(0xFF7FB2E8), Color(0xFFCFE4F7)))
                    ThemeMode.DARK -> Brush.linearGradient(listOf(Color(0xFF0B1026), Color(0xFF3E4C7E)))
                }
                Column(
                    Modifier.weight(1f).height(118.dp).clip(RoundedCornerShape(22.dp)).background(if (on) g.ink else g.ink.copy(alpha = 0.06f))
                        .clickable(role = Role.RadioButton) { vm.setThemeMode(m) }.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(preview))
                    Text(stringResource(m.labelRes), color = if (on) onInk() else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                }
            }
        }
    }

    Group(stringResource(R.string.settings_sleep_title), info = if (!canTurnOff) stringResource(R.string.display_off_requires_owner) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_sleep_after_short), color = g.soft, fontSize = 15.sp, modifier = Modifier.width(64.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SLEEP_OPTIONS.forEach { sec -> ChoicePill(sleepLabel(sec), s.sleepAfterSeconds == sec) { appliance.updateDisplay { it.copy(sleepAfterSeconds = sec) } } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_sleep_then), color = g.soft, fontSize = 15.sp, modifier = Modifier.width(64.dp))
            Seg(
                SleepAction.entries.map { it to stringResource(it.labelRes) }, s.sleepAction, Modifier.width(380.dp),
                enabled = { it != SleepAction.DISPLAY_OFF || canTurnOff },
            ) { a -> appliance.updateDisplay { it.copy(sleepAction = a) } }
            Spacer(Modifier.weight(1f))
            ActionPill(stringResource(R.string.settings_sleep_now_short), Icons.Outlined.NightsStay, onClick = appliance::sleepNow)
        }
    }

    Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SwitchCard(stringResource(R.string.proximity_wake), s.proximityWake, Modifier.weight(1f), enabled = appliance.hasProximitySensor) { v ->
            appliance.updateDisplay { it.copy(proximityWake = v) }
        }
        SwitchCard(stringResource(R.string.auto_brightness), s.autoBrightness, Modifier.weight(1f), enabled = appliance.hasLightSensor) { v ->
            appliance.updateDisplay { it.copy(autoBrightness = v) }
        }
    }

    Group {
        if (s.autoBrightness && appliance.hasLightSensor) {
            LevelBar(stringResource(R.string.settings_min_brightness), s.minBrightness, 0.02f..0.6f) { v -> appliance.updateDisplay { it.copy(minBrightness = v) } }
        } else {
            LevelBar(stringResource(R.string.brightness), s.manualBrightness, 0.05f..1f) { v -> appliance.updateDisplay { it.copy(manualBrightness = v) } }
        }
    }
}

/** Helligkeit als Fläche wie auf den Kacheln: Tippen oder Ziehen stellt den Wert. */
@Composable
private fun LevelBar(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val g = LocalGlass.current
    val change by rememberUpdatedState(onChange)
    fun at(x: Float, w: Int) = (range.start + (x / w).coerceIn(0f, 1f) * (range.endInclusive - range.start))
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(g.ink.copy(alpha = 0.07f))
            .pointerInput(range) { detectTapGestures { o -> change(at(o.x, size.width)) } }
            .pointerInput(range) { detectHorizontalDragGestures { c, _ -> c.consume(); change(at(c.position.x, size.width)) } },
    ) {
        val f = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxHeight().width((maxWidth * f).coerceAtLeast(52.dp)).clip(RoundedCornerShape(26.dp)).background(Color(0xFFFFC24F)))
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()} %", color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// --- Sprache und Region --------------------------------------------------------------------

@Composable
private fun RegionSection(vm: SettingsViewModel) {
    val g = LocalGlass.current
    val language by vm.language.collectAsStateWithLifecycle()
    val unit by vm.temperatureUnit.collectAsStateWithLifecycle()
    val zone by vm.timeZone.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val locationName by vm.locationName.collectAsStateWithLifecycle()
    var zoneOpen by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Group(stringResource(R.string.settings_language), modifier = Modifier.weight(1.3f)) {
            Seg(AppLanguage.entries.map { it to stringResource(it.labelRes) }, language, Modifier.fillMaxWidth()) { vm.setLanguage(it) }
        }
        Group(stringResource(R.string.settings_unit), modifier = Modifier.weight(1f)) {
            Seg(TemperatureUnit.entries.map { it to if (it == TemperatureUnit.CELSIUS) "°C" else "°F" }, unit, Modifier.fillMaxWidth()) { vm.setTemperatureUnit(it) }
        }
    }

    Group {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconDisk(Icons.Outlined.Schedule, Color(0xFFE8E6F5), Color(0xFF3D3A6C), 44)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_zone_title, zoneCity(zone, currentLocale())), color = g.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(zoneName(zone, currentLocale()) + " · " + zoneLabel(zone).substringAfter('(').removeSuffix(")"), color = g.soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (vm.canSetTimeZone) ActionPill(stringResource(if (zoneOpen) R.string.action_done else R.string.settings_change)) { zoneOpen = !zoneOpen }
        }
        if (zoneOpen) TimeZonePicker(zone, vm.canSetTimeZone) { vm.setTimeZone(it); zoneOpen = false }
    }

    Group(stringResource(R.string.location_title), trailing = { Text(stringResource(R.string.location_for), color = g.soft, fontSize = 13.sp) }) {
        LocationPicker(
            location = location, locationName = locationName, zone = zone,
            onSelect = vm::setLocation, onClear = { vm.setLocation(null, null) },
            onUseZone = if (vm.canSetTimeZone) vm::setTimeZone else null,
        )
    }
}

// --- Matter (unter „Erweitert“ der Seite Matter und Thread) ----------------------------------

@Composable
private fun MatterAdvanced(vm: SettingsViewModel) {
    val fabric by vm.fabric.collectAsStateWithLifecycle()
    val storage by vm.credentialStorage.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current
    var confirm by remember { mutableStateOf<app.raum.data.preferences.MatterMode?>(null) }
    Group(stringResource(R.string.settings_matter_controller)) {
        Info(stringResource(R.string.settings_controller), stringResource(if (vm.isMockController) R.string.controller_mock else R.string.controller_chip))
        Info(stringResource(R.string.settings_fabric), fabric?.let { "%016X".format(it.fabricId.toLong()) } ?: "–")
        storage?.let { st ->
            Info(
                stringResource(R.string.settings_fabric_keys),
                when {
                    !st.encrypted -> stringResource(R.string.fabric_keys_plain)
                    else -> stringResource(
                        R.string.fabric_keys_encrypted,
                        stringResource(
                            when (st.protection) {
                                app.raum.security.KeyProtection.STRONGBOX -> R.string.key_protection_strongbox
                                app.raum.security.KeyProtection.TEE -> R.string.key_protection_tee
                                app.raum.security.KeyProtection.SOFTWARE -> R.string.key_protection_software
                                app.raum.security.KeyProtection.UNKNOWN -> R.string.key_protection_unknown
                            },
                        ),
                    )
                },
            )
            if (st.unreadable > 0) Text(stringResource(R.string.fabric_keys_unreadable), color = Color(0xFFB3261E))
        }
    }
    Group(stringResource(R.string.matter_mode_title), info = stringResource(R.string.matter_mode_text)) {
        Seg(
            listOf(app.raum.data.preferences.MatterMode.MATTER to stringResource(R.string.matter_mode_real), app.raum.data.preferences.MatterMode.SIMULATION to stringResource(R.string.matter_mode_simulation)),
            vm.matterMode, Modifier.widthIn(max = 520.dp).fillMaxWidth(),
        ) { if (vm.matterMode != it) confirm = it }
    }
    confirm?.let { mode ->
        app.raum.ui.components.RaumDialog(
            title = stringResource(R.string.matter_mode_confirm_title),
            onDismiss = { confirm = null },
            width = 560.dp,
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    vm.setMatterMode(mode)
                    // Prozess beenden – CoreService (START_STICKY) und Launcher starten raum. neu
                    activity?.finishAffinity(); android.os.Process.killProcess(android.os.Process.myPid())
                }) { Text(stringResource(R.string.matter_mode_restart)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        ) { Text(stringResource(R.string.matter_mode_confirm_text)) }
    }
}

// --- Über raum. ----------------------------------------------------------------------------

@Composable
private fun AboutSection() {
    Group {
        Info("raum.", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Info("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Info(stringResource(R.string.device), "${Build.MANUFACTURER} ${Build.MODEL}")
        Info(stringResource(R.string.settings_database), stringResource(R.string.settings_database_value, app.raum.data.database.RaumDatabase.VERSION))
        Info(stringResource(R.string.settings_internet), stringResource(R.string.settings_internet_value))
    }
    Group(stringResource(R.string.settings_licenses)) {
        val g = LocalGlass.current
        listOf(
            R.string.settings_license_geonames, R.string.settings_license_open_meteo, R.string.settings_license_met,
            R.string.settings_license_home_connect, R.string.settings_license_grohe, R.string.settings_license_sonos,
        ).forEach { Text(stringResource(it), color = g.ink, fontSize = 15.sp) }
    }
}
