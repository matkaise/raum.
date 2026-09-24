package app.raum.ui.settings

import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Cloud
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.BuildConfig
import app.raum.R
import app.raum.data.preferences.TemperatureUnit
import app.raum.data.preferences.ThemeMode
import app.raum.data.preferences.MatterMode
import app.raum.i18n.AppLanguage
import app.raum.ui.HomeUiState
import app.raum.ui.appliance.ApplianceViewModel
import app.raum.ui.appliance.DisplayCard
import app.raum.ui.appliance.SecurityCard
import app.raum.ui.components.rememberLogTimeFormatter
import org.koin.compose.viewmodel.koinViewModel

/** Kategorien der Einstellungen – das Protokoll bewusst weit unten (Diagnose, nicht Alltag). */
enum class SettingsSection(@StringRes val titleRes: Int, val icon: ImageVector) {
    HOME(R.string.settings_home, Icons.Outlined.Home),
    NETWORK(R.string.settings_network, Icons.Outlined.Wifi),
    DISPLAY(R.string.settings_display_design, Icons.Outlined.Palette),
    REGION(R.string.settings_language_region, Icons.Outlined.Language),
    WEATHER(R.string.settings_weather, Icons.Outlined.Cloud),
    SHARE(R.string.settings_share, Icons.Outlined.Share),
    MATTER(R.string.settings_matter, Icons.Outlined.DeviceHub),
    SECURITY(R.string.security_maintenance, Icons.Outlined.Shield),
    LOG(R.string.event_log, Icons.Outlined.ReceiptLong),
    ABOUT(R.string.settings_about, Icons.Outlined.Info),
}

/** Einstellungen (Spez. 9.7): Kategorienliste links, Inhalt rechts. */
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
    androidx.compose.runtime.LaunchedEffect(requested) {
        requested?.let { name -> SettingsSection.entries.firstOrNull { it.name == name }?.let { section = it } }
        requests.openSettings.value = null
    }

    // Netzwerkstatus nur beobachten, solange die Einstellungen offen sind
    DisposableEffect(Unit) {
        viewModel.network.start()
        onDispose { viewModel.network.stop() }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 16.dp, top = 40.dp, bottom = 24.dp),
        ) {
            Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 12.dp, bottom = 20.dp))
            SettingsSection.entries.forEach { s ->
                if (s == SettingsSection.LOG) Spacer(Modifier.height(16.dp)) // Diagnose abgesetzt
                SectionItem(s, summary(s, state, viewModel, appliance), selected = section == s) { section = s }
            }
        }

        Box(Modifier.weight(1f).fillMaxHeight().padding(end = 40.dp)) {
            if (section == SettingsSection.LOG) {
                // Protokoll füllt die Höhe und scrollt selbst
                Column(Modifier.fillMaxSize().padding(top = 40.dp, bottom = 32.dp)) {
                    DetailTitle(section)
                    LogPanel(viewModel, Modifier.weight(1f))
                }
            } else {
                // eigene Scrollposition je Kategorie – sonst öffnet die nächste mittendrin
                val scroll = key(section) { rememberScrollState() }
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 40.dp, bottom = 32.dp)) {
                    DetailTitle(section)
                    when (section) {
                        SettingsSection.HOME -> HomeSection(state, viewModel)
                        SettingsSection.NETWORK -> NetworkSection(viewModel)
                        SettingsSection.DISPLAY -> DisplaySection(viewModel, appliance)
                        SettingsSection.REGION -> RegionSection(viewModel)
                        SettingsSection.SHARE -> ShareSection(roomName = { state.roomName(it.roomId) })
                        SettingsSection.WEATHER -> WeatherSection(state, viewModel, onOpenRegion = { section = SettingsSection.REGION })
                        SettingsSection.MATTER -> { ThreadSection(); MatterSection(viewModel) }
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
private fun summary(s: SettingsSection, state: HomeUiState, vm: SettingsViewModel, appliance: ApplianceViewModel): String = when (s) {
    SettingsSection.HOME -> listOfNotNull(
        state.home?.name,
        pluralStringResource(R.plurals.rooms_count, state.rooms.size, state.rooms.size),
        pluralStringResource(R.plurals.devices_count, state.devices.size, state.devices.size),
    ).joinToString(" · ")
    SettingsSection.NETWORK -> {
        val st by vm.network.status.collectAsStateWithLifecycle()
        when {
            st.ethernet -> stringResource(R.string.net_summary_lan)
            st.wifiConnected -> st.ssid?.let { stringResource(R.string.net_summary_wifi, it) } ?: stringResource(R.string.net_wifi_connected_unknown)
            else -> stringResource(R.string.net_not_connected)
        }
    }
    SettingsSection.DISPLAY -> {
        val theme by vm.themeMode.collectAsStateWithLifecycle()
        val d by appliance.displaySettings.collectAsStateWithLifecycle()
        listOf(
            stringResource(theme.labelRes),
            d.sleepAfterSeconds?.let { sec -> if (sec < 60) stringResource(R.string.sleep_after_short_s, sec) else stringResource(R.string.sleep_after_short_m, sec / 60) }
                ?: stringResource(R.string.sleep_never_short),
        ).joinToString(" · ")
    }
    SettingsSection.REGION -> {
        val lang by vm.language.collectAsStateWithLifecycle()
        val unit by vm.temperatureUnit.collectAsStateWithLifecycle()
        val place by vm.locationName.collectAsStateWithLifecycle()
        val loc by vm.location.collectAsStateWithLifecycle()
        listOfNotNull(
            stringResource(lang.labelRes), if (unit == TemperatureUnit.CELSIUS) "°C" else "°F",
            place ?: if (loc != null) stringResource(R.string.location_coordinates) else stringResource(R.string.location_missing_short),
        ).joinToString(" · ")
    }
    SettingsSection.WEATHER -> weatherSummary(vm)
    SettingsSection.SHARE -> shareSummary()
    SettingsSection.MATTER -> threadSummary()
    SettingsSection.SECURITY -> {
        val pinSet by appliance.pinSet.collectAsStateWithLifecycle()
        stringResource(if (pinSet) R.string.pin_is_set_short else R.string.pin_missing_short)
    }
    SettingsSection.LOG -> {
        val last by vm.lastLogEntry.collectAsStateWithLifecycle()
        val fmt = rememberLogTimeFormatter()
        last?.let { stringResource(R.string.log_last_entry, fmt.format(it.timestamp)) } ?: stringResource(R.string.log_empty)
    }
    SettingsSection.ABOUT -> stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)
}

@Composable
private fun SectionItem(s: SettingsSection, summary: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background
    val warn = s == SettingsSection.SECURITY && summary == stringResource(R.string.pin_missing_short)
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(s.icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(s.titleRes), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                summary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailTitle(s: SettingsSection) {
    Text(stringResource(s.titleRes), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 24.dp))
}

/** Gruppe mit optionaler Überschrift – Grundbaustein aller Kategorien. */
@Composable
internal fun Group(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun Info(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(220.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

// --- Zuhause ------------------------------------------------------------------------------

@Composable
private fun HomeSection(state: HomeUiState, vm: SettingsViewModel) {
    val current = state.home?.name ?: ""
    var name by remember(current) { mutableStateOf(current) }
    Group(stringResource(R.string.name)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.widthIn(max = 480.dp).weight(1f, fill = false))
            FilledTonalButton(onClick = { vm.renameHome(name) }, enabled = name.isNotBlank() && name.trim() != current, modifier = Modifier.height(56.dp)) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatTile(stringResource(R.string.nav_rooms), state.rooms.size.toString(), Modifier.weight(1f))
        StatTile(stringResource(R.string.nav_devices), state.devices.size.toString(), Modifier.weight(1f),
            detail = if (state.offline.isNotEmpty()) stringResource(R.string.offline_count, state.offline.size) else null)
        StatTile(stringResource(R.string.nav_scenes), state.scenes.size.toString(), Modifier.weight(1f))
        StatTile(stringResource(R.string.nav_automations), state.automations.size.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxHeight()) {
        Column(Modifier.padding(20.dp)) {
            Text(value, style = MaterialTheme.typography.displaySmall)
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

// --- Display und Design --------------------------------------------------------------------

@Composable
private fun DisplaySection(vm: SettingsViewModel, appliance: ApplianceViewModel) {
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val kioskStatus by appliance.kioskStatus.collectAsStateWithLifecycle()
    Group(stringResource(R.string.settings_appearance)) {
        SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(selected = theme == mode, onClick = { vm.setThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) { Text(stringResource(mode.labelRes)) }
            }
        }
    }
    DisplayCard(appliance, canTurnOff = kioskStatus.isDeviceOwner)
}

// --- Sprache und Region --------------------------------------------------------------------

@Composable
private fun RegionSection(vm: SettingsViewModel) {
    val language by vm.language.collectAsStateWithLifecycle()
    val unit by vm.temperatureUnit.collectAsStateWithLifecycle()
    val zone by vm.timeZone.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val locationName by vm.locationName.collectAsStateWithLifecycle()

    Group(stringResource(R.string.settings_language)) {
        SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            AppLanguage.entries.forEachIndexed { i, l ->
                SegmentedButton(selected = language == l, onClick = { vm.setLanguage(l) },
                    shape = SegmentedButtonDefaults.itemShape(i, AppLanguage.entries.size)) { Text(stringResource(l.labelRes)) }
            }
        }
    }
    Group(stringResource(R.string.settings_temperature_unit)) {
        SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            TemperatureUnit.entries.forEachIndexed { i, u ->
                SegmentedButton(selected = unit == u, onClick = { vm.setTemperatureUnit(u) },
                    shape = SegmentedButtonDefaults.itemShape(i, TemperatureUnit.entries.size)) { Text(stringResource(u.labelRes)) }
            }
        }
    }
    Group(stringResource(R.string.onb_timezone)) {
        TimeZonePicker(zone, vm.canSetTimeZone, vm::setTimeZone)
    }
    Group(stringResource(R.string.location_title)) {
        Text(stringResource(R.string.location_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (location == null) Text(stringResource(R.string.location_none), color = MaterialTheme.colorScheme.error)
        LocationPicker(
            location = location, locationName = locationName, zone = zone,
            onSelect = vm::setLocation, onClear = { vm.setLocation(null, null) },
            onUseZone = if (vm.canSetTimeZone) vm::setTimeZone else null,
        )
    }
}

// --- Matter und Thread ---------------------------------------------------------------------

@Composable
private fun MatterSection(vm: SettingsViewModel) {
    val fabric by vm.fabric.collectAsStateWithLifecycle()
    val storage by vm.credentialStorage.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current
    var confirm by remember { mutableStateOf<MatterMode?>(null) }
    Group {
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
            if (st.unreadable > 0) Text(stringResource(R.string.fabric_keys_unreadable), color = MaterialTheme.colorScheme.error)
        }
    }
    Group(stringResource(R.string.matter_mode_title)) {
        Text(stringResource(R.string.matter_mode_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            listOf(MatterMode.MATTER to R.string.matter_mode_real, MatterMode.SIMULATION to R.string.matter_mode_simulation).forEachIndexed { i, (mode, label) ->
                SegmentedButton(selected = vm.matterMode == mode, onClick = { if (vm.matterMode != mode) confirm = mode },
                    shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label)) }
            }
        }
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
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
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
        Text(stringResource(R.string.settings_license_geonames), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_license_met), style = MaterialTheme.typography.bodyLarge)
    }
}
