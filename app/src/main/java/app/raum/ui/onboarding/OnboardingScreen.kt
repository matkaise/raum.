package app.raum.ui.onboarding

import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import app.raum.ui.appliance.localBackupTime
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.data.backup.BackupCodec
import app.raum.data.preferences.TemperatureUnit
import app.raum.i18n.AppLanguage
import app.raum.i18n.ErrorTexts
import app.raum.i18n.Strings
import app.raum.platform.CheckState
import app.raum.security.AdminPinStore
import app.raum.ui.appliance.PinPad
import app.raum.ui.components.currentLocale
import app.raum.ui.settings.LocationPicker
import app.raum.ui.settings.TimeZonePicker
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.TimeZone

/** Geführte Erstinbetriebnahme (ONB-001 – ONB-006). */
@Composable
fun OnboardingScreen(vm: OnboardingViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val steps = OnboardingFlow.steps(state.path)

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Linke Spalte: Marke und Schrittliste
        Column(
            Modifier.width(380.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(40.dp),
        ) {
            Text("raum.", fontSize = 56.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(stringResource(R.string.onb_tagline), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(48.dp))
            val current = steps.indexOf(state.step)
            steps.forEachIndexed { i, s ->
                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val done = i < current
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(
                            when {
                                done -> MaterialTheme.colorScheme.secondary
                                i == current -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size(20.dp))
                        else Text("${i + 1}", color = if (i == current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        stringResource(s.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (i <= current) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Rechte Spalte: aktueller Schritt
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 64.dp, vertical = 48.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 880.dp)) {
                when (state.step) {
                    OnboardingStep.WELCOME -> WelcomeStep(vm, state)
                    OnboardingStep.RESTORE -> RestoreStep(vm, state)
                    OnboardingStep.HOME -> HomeStep(vm, state)
                    OnboardingStep.REGION -> RegionStep(vm, state)
                    OnboardingStep.PIN -> PinStep(vm, state)
                    OnboardingStep.CHECK -> CheckStep(vm, state)
                    OnboardingStep.FABRIC -> FabricStep(vm, state)
                    OnboardingStep.DONE -> DoneStep(vm, state)
                }
            }
            // Fußzeile: Zurück / Weiter
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (OnboardingFlow.canGoBack(state.step, state.restored)) {
                    TextButton(onClick = vm::back, modifier = Modifier.height(56.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.action_back))
                    }
                }
                Spacer(Modifier.weight(1f))
                val nextEnabled = when (state.step) {
                    OnboardingStep.FABRIC -> state.fabric != null
                    OnboardingStep.HOME, OnboardingStep.REGION, OnboardingStep.CHECK -> true
                    else -> false // Willkommen, Wiederherstellen, PIN und Fertig haben eigene Knöpfe
                }
                if (state.step in setOf(OnboardingStep.HOME, OnboardingStep.REGION, OnboardingStep.CHECK, OnboardingStep.FABRIC)) {
                    Button(onClick = vm::next, enabled = nextEnabled && !state.busy, modifier = Modifier.height(56.dp).widthIn(min = 180.dp)) {
                        Text(stringResource(R.string.action_next))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, text: String? = null) {
    Text(title, style = MaterialTheme.typography.displaySmall)
    if (text != null) {
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
}

// --- 1. Willkommen ---------------------------------------------------------------------

@Composable
private fun WelcomeStep(vm: OnboardingViewModel, state: OnboardingState) {
    val language by vm.language.collectAsStateWithLifecycle()
    StepTitle(stringResource(R.string.onb_welcome_title), stringResource(R.string.onb_welcome_text))
    Label(stringResource(R.string.settings_language))
    SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 560.dp)) {
        AppLanguage.entries.forEachIndexed { i, l ->
            SegmentedButton(
                selected = language == l, onClick = { vm.setLanguage(l) },
                shape = SegmentedButtonDefaults.itemShape(i, AppLanguage.entries.size),
            ) { Text(stringResource(l.labelRes)) }
        }
    }
    Spacer(Modifier.height(40.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        ChoiceCard(Icons.Outlined.Home, stringResource(R.string.onb_new_title), stringResource(if (state.existingHome) R.string.onb_new_text_handover else R.string.onb_new_text), Modifier.weight(1f)) {
            vm.choose(OnboardingPath.NEW)
        }
        ChoiceCard(Icons.Outlined.Restore, stringResource(R.string.onb_restore_title), stringResource(R.string.onb_restore_text), Modifier.weight(1f)) {
            vm.choose(OnboardingPath.RESTORE)
        }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = modifier.height(200.dp)) {
        Column(Modifier.padding(24.dp)) {
            Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- Wiederherstellen --------------------------------------------------------------------

@Composable
private fun RestoreStep(vm: OnboardingViewModel, state: OnboardingState) {
    val strings = koinInject<Strings>()
    var password by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.inspectBackup(uri, password)
    }
    StepTitle(stringResource(R.string.onb_restore_title), stringResource(R.string.onb_restore_step_text))
    OutlinedTextField(
        password, { password = it },
        label = { Text(stringResource(R.string.password_min, BackupCodec.MIN_PASSWORD_LENGTH)) },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    FilledTonalButton(
        // Fokus abgeben, sonst öffnet sich nach der Dateiauswahl die Tastatur erneut
        onClick = { focus.clearFocus(); open.launch(arrayOf("*/*")) },
        enabled = password.length >= BackupCodec.MIN_PASSWORD_LENGTH && !state.busy,
        modifier = Modifier.height(56.dp),
    ) { Icon(Icons.Outlined.Restore, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.onb_choose_backup)) }
    if (state.busy) { Spacer(Modifier.height(16.dp)); CircularProgressIndicator() }
    state.error?.let { Spacer(Modifier.height(16.dp)); Text(it, color = MaterialTheme.colorScheme.error) }

    state.restorePlan?.let { plan ->
        Spacer(Modifier.height(24.dp))
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.restore_source, plan.home.name, localBackupTime(plan.source.createdAt), plan.source.appVersion),
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(
                        pluralStringResource(R.plurals.rooms_count, plan.rooms.size, plan.rooms.size),
                        pluralStringResource(R.plurals.devices_count, plan.devices.size, plan.devices.size),
                        pluralStringResource(R.plurals.scenes_count, plan.scenes.size, plan.scenes.size),
                        pluralStringResource(R.plurals.automations_count, plan.automations.size, plan.automations.size),
                    ).joinToString(" · ")
                )
                plan.warnings.forEach { w ->
                    Row { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(ErrorTexts.restoreWarning(w, strings)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = vm::restore, modifier = Modifier.height(56.dp)) { Text(stringResource(R.string.restore)) }
                    TextButton(onClick = vm::dismissPlan, modifier = Modifier.height(56.dp)) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}

// --- 2. Zuhause ------------------------------------------------------------------

@Composable
private fun HomeStep(vm: OnboardingViewModel, state: OnboardingState) {
    val focus = LocalFocusManager.current
    StepTitle(stringResource(R.string.onb_home_title), stringResource(R.string.onb_home_text))
    OutlinedTextField(
        state.homeName, vm::setHomeName, label = { Text(stringResource(R.string.name)) }, singleLine = true,
        placeholder = { Text(stringResource(R.string.home_default_name), style = MaterialTheme.typography.headlineSmall) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.clearFocus(); vm.next() }),
        textStyle = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
    )
}

// --- 3. Region ---------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegionStep(vm: OnboardingViewModel, state: OnboardingState) {
    val unit by vm.temperatureUnit.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    StepTitle(stringResource(R.string.onb_region_title), stringResource(R.string.onb_region_text))

    Label(stringResource(R.string.onb_timezone))
    TimeZonePicker(state.timeZone, vm.canSetTimeZone, vm::setTimeZone)

    Label(stringResource(R.string.settings_temperature_unit))
    SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 480.dp)) {
        TemperatureUnit.entries.forEachIndexed { i, u ->
            SegmentedButton(selected = unit == u, onClick = { vm.setTemperatureUnit(u) }, shape = SegmentedButtonDefaults.itemShape(i, TemperatureUnit.entries.size)) {
                Text(stringResource(u.labelRes))
            }
        }
    }

    Label(stringResource(R.string.onb_location))
    Text(stringResource(R.string.onb_location_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    val locationName by vm.locationName.collectAsStateWithLifecycle()
    LocationPicker(
        location = location, locationName = locationName, zone = state.timeZone,
        onSelect = vm::setLocation, onClear = { vm.setLocation(null, null) },
        onUseZone = if (vm.canSetTimeZone) vm::setTimeZone else null,
    )

    // Wetter – freiwillig, braucht Internet (MET Norway)
    Label(stringResource(R.string.weather_show))
    val weatherOn by vm.weatherEnabled.collectAsStateWithLifecycle()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(max = 640.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (location == null) R.string.weather_needs_location else R.string.weather_explain_short),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            Switch(checked = weatherOn && location != null, onCheckedChange = vm::setWeatherEnabled, enabled = location != null)
        }
    }
}

// --- 4. PIN (optional) ---------------------------------------------------------------

@Composable
private fun PinStep(vm: OnboardingViewModel, state: OnboardingState) {
    StepTitle(stringResource(R.string.onb_pin_title), stringResource(R.string.onb_pin_text))
    if (state.pinSet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.pin_is_set), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = vm::next, modifier = Modifier.height(56.dp)) { Text(stringResource(R.string.action_next)) }
        return
    }
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(if (first == null) R.string.pin_new else R.string.pin_repeat), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            PinPad(pin, { pin = it; error = null })
        }
        // Bestätigen neben dem Ziffernblock (passt so ohne Scrollen auf 10"), darunter das Überspringen
        Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(40.dp))
            Button(
                enabled = pin.length >= AdminPinStore.MIN_LENGTH,
                onClick = {
                    val f = first
                    when {
                        f == null && !vm.pinValid(pin) -> error = R.string.pin_format
                        f == null -> { first = pin }
                        f != pin -> { error = R.string.pin_mismatch; first = null }
                        else -> vm.setPin(pin)
                    }
                    pin = ""
                },
                modifier = Modifier.height(56.dp).fillMaxWidth(),
            ) { Text(stringResource(if (first == null) R.string.action_next else R.string.onb_pin_save)) }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            // Überspringen mit ehrlichem Hinweis
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.onb_pin_skip_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = vm::skipPin, modifier = Modifier.height(56.dp)) { Text(stringResource(R.string.onb_pin_skip)) }
                }
            }
        }
    }
}

// --- 5. Systemprüfung -----------------------------------------------------------

@Composable
private fun CheckStep(vm: OnboardingViewModel, state: OnboardingState) {
    StepTitle(stringResource(R.string.onb_check_title), stringResource(R.string.onb_check_text))
    if (state.checks.isEmpty()) { CircularProgressIndicator(); return }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.checks.forEach { c ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (icon, color) = when (c.state) {
                        CheckState.OK -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.secondary
                        CheckState.WARNING -> Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.error
                        CheckState.INFO -> Icons.Outlined.Info to MaterialTheme.colorScheme.tertiary
                    }
                    Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(c.titleRes), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (c.arg != null) stringResource(c.messageRes, c.arg) else stringResource(c.messageRes),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = vm::runChecks) { Text(stringResource(R.string.onb_check_again)) }
}

// --- 6. Matter-Fabric -----------------------------------------------------------

@Composable
private fun FabricStep(vm: OnboardingViewModel, state: OnboardingState) {
    StepTitle(stringResource(R.string.onb_fabric_title), stringResource(R.string.onb_fabric_text))
    val fabric = state.fabric
    when {
        fabric != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.onb_fabric_ready), style = MaterialTheme.typography.titleMedium)
                Text("Fabric ID %016X".format(fabric.fabricId.toLong()), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.error != null -> Column {
            Text(state.error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::retryFabric) { Text(stringResource(R.string.retry)) }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(32.dp)); Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.onb_fabric_creating), style = MaterialTheme.typography.titleMedium)
        }
    }
    if (vm.isMock) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onb_fabric_mock_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // Thread optional (ONB-007/008) – erst, wenn die Fabric steht
    if (fabric != null) {
        Spacer(Modifier.height(32.dp))
        Column(Modifier.widthIn(max = 880.dp)) { app.raum.ui.settings.ThreadSetupCard() }
    }
}

// --- 7. Fertig -------------------------------------------------------------------

@Composable
private fun DoneStep(vm: OnboardingViewModel, state: OnboardingState) {
    StepTitle(stringResource(R.string.onb_done_title), stringResource(if (state.restored) R.string.onb_done_restored_text else R.string.onb_done_text))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 560.dp)) {
        if (!state.restored) {
            Button(onClick = { vm.finish(demo = false, addDevice = true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.onb_done_add_device))
            }
        }
        OutlinedButton(onClick = { vm.finish(demo = false, addDevice = false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text(stringResource(if (state.restored) R.string.onb_done_start else R.string.onb_done_later))
        }
        if (vm.isMock && !state.restored && !state.existingHome) {
            TextButton(onClick = { vm.finish(demo = true, addDevice = false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Outlined.Weekend, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.onb_done_demo))
            }
        }
    }
}
