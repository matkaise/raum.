package app.raum.ui.household

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.homeconnect.HcAppliance
import app.raum.homeconnect.HcKeys
import app.raum.homeconnect.HcLink
import app.raum.homeconnect.HcMode
import app.raum.homeconnect.HcType
import app.raum.homeconnect.HomeConnectState
import app.raum.homeconnect.OperationState
import app.raum.homeconnect.labelRes
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel

/**
 * „Haushalt“ (Entwurf raum Redesign, Turn 10): Home-Connect-Geräte links als Karten mit Fortschrittsring,
 * rechts das gewählte Gerät – Kaffee als Tassen-Karussell, Backofen mit Drehring, Wäsche/Geschirr mit Trommel bzw.
 * Innenansicht, Restzeit und Programmwahl.
 */
@Composable
fun HouseholdScreen(
    requestedAppliance: String?,
    onRequestHandled: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: HouseholdViewModel = koinViewModel(),
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val selectedId by vm.selected.collectAsStateWithLifecycle()
    val toast = rememberToast(vm.service)
    val now = rememberNow()

    LaunchedEffect(requestedAppliance) {
        if (requestedAppliance != null) { vm.select(requestedAppliance); onRequestHandled() }
    }
    val selected = st.appliances.firstOrNull { it.haId == selectedId } ?: st.appliances.firstOrNull()
    LaunchedEffect(selected?.haId) { if (selected != null && selected.haId != selectedId) vm.select(selected.haId) }

    Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 24.dp, top = 20.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 20.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nav_household), style = app.raum.ui.theme.SkyTextStyle, color = SkyText, fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp, lineHeight = 58.sp)
                Text(headline(st), style = app.raum.ui.theme.SkyTextStyle, color = SkyTextSoft, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (toast != null) ToastChip(toast)
        }
        when {
            st.appliances.isEmpty() -> EmptyHousehold(st, onOpenSettings)
            selected != null -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ApplianceList(st, selected.haId, now, vm.service, vm::select)
                DetailPanel(selected, st, vm, now, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun headline(st: HomeConnectState): String {
    val n = st.appliances.count { it.connected }
    val base = when {
        st.link == HcLink.CONNECTING && st.appliances.isEmpty() -> stringResource(R.string.hc_connecting)
        st.link == HcLink.RETRYING -> stringResource(R.string.hc_link_retrying_short)
        st.link == HcLink.NEEDS_SIGN_IN -> stringResource(R.string.hc_link_reauth)
        else -> pluralStringResource(R.plurals.hc_connected_count, n, n)
    }
    return listOfNotNull("Home Connect", if (st.mode == HcMode.DEMO) stringResource(R.string.hc_mode_demo) else null, base).joinToString(" · ")
}

@Composable
private fun ToastChip(text: String) {
    val g = LocalGlass.current
    Row(
        Modifier.height(48.dp).shadow(12.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(g.glassSolid).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(20.dp), tint = HcColors.DoneInk)
        Text(text, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun EmptyHousehold(st: HomeConnectState, onOpenSettings: () -> Unit) {
    val g = LocalGlass.current
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp).glass(RoundedCornerShape(32.dp), g.glass, g.edge).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val loading = st.link == HcLink.CONNECTING || st.link == HcLink.LIVE
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(28.dp), color = g.ink, strokeWidth = 3.dp)
            else Icon(Icons.Outlined.CloudOff, null, Modifier.size(28.dp), tint = g.soft)
            Text(
                stringResource(if (loading) R.string.hc_empty_loading else R.string.hc_empty_title),
                color = g.ink, fontSize = 22.sp, fontWeight = FontWeight.Medium,
            )
        }
        st.linkError?.let { Text(stringResource(it.failure.labelRes) + (it.detail?.let { d -> " – $d" } ?: ""), color = g.warn, fontSize = 15.sp) }
        if (!loading) Text(stringResource(R.string.hc_empty_text), color = g.soft, fontSize = 16.sp)
        ActionButton(stringResource(R.string.hc_open_settings), Icons.Outlined.Settings, g.chip, g.ink, Modifier.width(320.dp), height = 52.dp, onClick = onOpenSettings)
    }
}

// --- Liste ------------------------------------------------------------------------------------

@Composable
private fun ApplianceList(st: HomeConnectState, selected: String, now: java.time.Instant, service: app.raum.homeconnect.HomeConnectService, onPick: (String) -> Unit) {
    val g = LocalGlass.current
    val many = st.appliances.size > 5
    Column(
        Modifier.width(288.dp).fillMaxHeight().then(if (many) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        st.appliances.forEach { a ->
            val on = a.haId == selected
            val s = summarize(a, service, now)
            val bg by animateColorAsState(if (on) g.glassSolid else g.glass, label = "card")
            Row(
                (if (many) Modifier.height(116.dp) else Modifier.weight(1f).heightIn(max = 140.dp)).fillMaxWidth()
                    .then(if (on) Modifier.shadow(14.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = 0.16f)) else Modifier)
                    .clip(RoundedCornerShape(28.dp)).background(bg)
                    .border(1.dp, if (on) Color.White.copy(alpha = 0.95f) else g.edge, RoundedCornerShape(28.dp))
                    .clickable { onPick(a.haId) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ApplianceRing(a.type, s, 64.dp, 28.dp)
                Column(Modifier.weight(1f)) {
                    Text(a.name, color = g.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.status, color = statusColor(s), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    if (s.sub.isNotBlank()) Text(s.sub, color = subColor(s), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun statusColor(s: ApplianceSummary): Color {
    val g = LocalGlass.current
    return when {
        s.alert -> HcColors.Alert
        s.done -> if (g.dark) HcColors.Done else HcColors.DoneInk
        else -> g.ink
    }
}

// --- Detail -----------------------------------------------------------------------------------

@Composable
private fun DetailPanel(a: HcAppliance, st: HomeConnectState, vm: HouseholdViewModel, now: java.time.Instant, modifier: Modifier) {
    val g = LocalGlass.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    Column(modifier.glass(RoundedCornerShape(36.dp), g.glassStrong, g.edge).padding(horizontal = 28.dp, vertical = 24.dp)) {
        DetailHeader(a)
        val programs = st.programs[a.haId].orEmpty()
        val isBusy = a.haId in busy
        when (a.type) {
            HcType.COFFEE -> CoffeePanel(a, programs, vm, isBusy)
            HcType.OVEN -> OvenPanel(a, programs, vm, isBusy, now)
            HcType.WASHER, HcType.DRYER, HcType.DISHWASHER, HcType.WASHER_DRYER -> LaundryPanel(a, programs, vm, isBusy, now)
            else -> GenericPanel(a, vm, isBusy)
        }
    }
}

@Composable
private fun DetailHeader(a: HcAppliance) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(iconFor(a.type), null, Modifier.size(30.dp), tint = g.soft)
        Column(Modifier.weight(1f)) {
            Text(a.name, color = g.ink, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(a.brand, "Home Connect").filter { it.isNotBlank() }.joinToString(" · "), color = g.soft, fontSize = 14.sp)
        }
        headerChips(a).forEach { HeadChip(it) }
    }
}

data class ChipSpec(val icon: ImageVector, val text: String, val warn: Boolean = false, val tint: Color? = null)

/**
 * Chips im Kopf des Detailbereichs – nur was dort nicht ohnehin groß steht: Zustand nur bei Backofen und Geräten ohne
 * eigene Zustandsanzeige, Tür und Warnungen immer. Kein „Wasser ok / Bohnen ok“ – nur Probleme.
 */
@Composable
private fun headerChips(a: HcAppliance): List<ChipSpec> {
    val chips = mutableListOf<ChipSpec>()
    val accent = accentFor(a.type)
    if (!a.connected) return listOf(ChipSpec(Icons.Outlined.CloudOff, stringResource(R.string.hc_status_offline), warn = true))
    val ownStateView = a.type in setOf(HcType.COFFEE, HcType.WASHER, HcType.DRYER, HcType.DISHWASHER, HcType.WASHER_DRYER)
    if (!ownStateView) when (a.operation) {
        OperationState.RUN -> chips += ChipSpec(Icons.Outlined.PowerSettingsNew, stringResource(R.string.hc_chip_running), tint = accent)
        OperationState.PAUSE -> chips += ChipSpec(Icons.Outlined.PowerSettingsNew, stringResource(R.string.hc_status_paused), tint = accent)
        OperationState.DELAYED_START -> chips += ChipSpec(Icons.Outlined.PowerSettingsNew, stringResource(R.string.hc_chip_delayed), tint = accent)
        OperationState.FINISHED -> chips += ChipSpec(Icons.Outlined.CheckCircle, stringResource(R.string.hc_status_finished), tint = HcColors.Done)
        else -> chips += ChipSpec(
            Icons.Outlined.PowerSettingsNew,
            stringResource(if (!a.poweredOn) (if (a.power == "Standby") R.string.hc_status_standby else R.string.hc_status_off) else R.string.hc_status_ready),
        )
    }
    if (a.doorLocked && a.operation.active) chips += ChipSpec(Icons.Outlined.Lock, stringResource(R.string.hc_door_locked))
    if (a.doorOpen == true) chips += ChipSpec(Icons.Outlined.DoorFront, stringResource(R.string.hc_door_open), warn = a.operation.active)
    a.warnings.forEach { key ->
        val icon = when (key) {
            HcKeys.COFFEE_WATER_EMPTY -> Icons.Outlined.WaterDrop
            HcKeys.COFFEE_BEANS_EMPTY -> Icons.Outlined.Grain
            HcKeys.COFFEE_TRAY_FULL -> Icons.Outlined.Delete
            else -> Icons.Outlined.NotificationsActive
        }
        chips += ChipSpec(icon, stringResource(app.raum.homeconnect.HomeConnectService.warningLabel(key)), warn = true)
    }
    return chips
}

@Composable
fun HeadChip(c: ChipSpec) {
    val g = LocalGlass.current
    val bg = when {
        c.warn -> HcColors.Alert.copy(alpha = 0.16f)
        c.tint != null -> c.tint.copy(alpha = 0.28f)
        else -> g.chip
    }
    val fg = if (c.warn) (if (g.dark) Color(0xFFFFB4A8) else Color(0xFFB3261E)) else g.ink
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(bg).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(c.icon, null, Modifier.size(18.dp), tint = fg)
        Text(c.text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Kühlschrank und andere Geräte ohne eigene Ansicht: Zustand und Ein/Aus. */
@Composable
private fun ColumnScope.GenericPanel(a: HcAppliance, vm: HouseholdViewModel, busy: Boolean) {
    val g = LocalGlass.current
    val now = rememberNow()
    val s = summarize(a, vm.service, now)
    Spacer(Modifier.height(24.dp))
    Text(s.status, color = statusColor(s), fontSize = 56.sp, fontWeight = FontWeight.Light)
    if (s.sub.isNotBlank()) Text(s.sub, color = subColor(s), fontSize = 18.sp)
    Spacer(Modifier.height(20.dp))
    listOf(HcKeys.FRIDGE_SETPOINT to R.string.hc_fridge, HcKeys.FREEZER_SETPOINT to R.string.hc_freezer).forEach { (key, label) ->
        a.number(key)?.let { v ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(Icons.Outlined.Thermostat, null, tint = g.soft)
                Text("${stringResource(label)}: ${v.toInt()}°", color = g.ink, fontSize = 20.sp)
            }
        }
    }
    Spacer(Modifier.weight(1f))
    if (a.type != HcType.FRIDGE && a.powerValues?.size != 1) {
        ActionButton(
            stringResource(if (a.poweredOn) R.string.hc_power_off else R.string.hc_power_on), Icons.Outlined.PowerSettingsNew,
            if (a.poweredOn) g.chip else HcColors.Other, if (a.poweredOn) g.ink else HcColors.OnAccent,
            Modifier.fillMaxWidth(), enabled = !busy && a.connected,
        ) { vm.setPower(a.haId, !a.poweredOn) }
    }
}

// --- Bedienelemente ---------------------------------------------------------------------------

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val g = LocalGlass.current
    Row(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(text.uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f), maxLines = 1)
        trailing?.let { Text(it, color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
    }
}

/** Segmentschalter auf Chip-Grund: gewählter Wert dunkel (hell im Dunkelmodus). */
@Composable
fun Segmented(labels: List<String>, selected: Int, modifier: Modifier = Modifier, enabled: Boolean = true, onPick: (Int) -> Unit) {
    val g = LocalGlass.current
    Row(modifier.clip(RoundedCornerShape(26.dp)).background(g.chip).padding(4.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                // Lange Beschriftungen („Sehr hoch“, „Schranktrocken“) bekommen mehr Platz
                Modifier.weight(maxOf(1f, l.length / 6f)).height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (on) g.ink else Color.Transparent)
                    .clickable(enabled = enabled) { onPick(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(l, color = if (on) inverseInk() else g.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

/** Mehrfachauswahl als Segmentzeile (Optionen des Geschirrspülers). */
@Composable
fun ToggleRow(labels: List<String>, on: List<Boolean>, modifier: Modifier = Modifier, enabled: Boolean = true, onToggle: (Int) -> Unit) {
    val g = LocalGlass.current
    Row(modifier.clip(RoundedCornerShape(26.dp)).background(g.chip).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        labels.forEachIndexed { i, l ->
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (on[i]) g.ink else Color.Transparent)
                    .clickable(enabled = enabled) { onToggle(i) },
                contentAlignment = Alignment.Center,
            ) { Text(l, color = if (on[i]) inverseInk() else g.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp)) }
        }
    }
}

@Composable
fun inverseInk(): Color = if (LocalGlass.current.dark) Color(0xFF10131F) else Color.White

/** Programmkachel im Raster. */
@Composable
fun ProgramTile(label: String, selected: Boolean, modifier: Modifier, enabled: Boolean = true, icon: ImageVector? = null, available: Boolean = true, onClick: () -> Unit) {
    val g = LocalGlass.current
    val fg = if (selected) inverseInk() else g.ink
    Column(
        // Gerade nicht verfügbare Programme blasser – wählbar bleiben sie, das Gerät entscheidet beim Start
        modifier.alpha(if (available || selected) 1f else 0.5f).clip(RoundedCornerShape(if (icon != null) 20.dp else 16.dp)).background(if (selected) g.ink else g.chip)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(28.dp), tint = fg); Spacer(Modifier.height(4.dp)) }
        // Silbentrennung statt Umbruch mitten im Wort („Maschinen-pflege“ statt „Maschinenpfleg|e“)
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = androidx.compose.ui.text.TextStyle(hyphens = androidx.compose.ui.text.style.Hyphens.Auto, lineBreak = androidx.compose.ui.text.style.LineBreak.Paragraph))
    }
}

/** Raster mit fester Spaltenzahl (Programme). */
@Composable
fun <T> TileGrid(items: List<T>, columns: Int, gap: Dp = 8.dp, content: @Composable RowScope.(T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { content(it) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Großer Knopf (Starten, Pausieren …), optional mit Fortschrittsfüllung. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionButton(
    text: String, icon: ImageVector, bg: Color, fg: Color, modifier: Modifier = Modifier,
    height: Dp = 64.dp, enabled: Boolean = true, progress: Float? = null, onClick: () -> Unit,
) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(bg)
            .combinedClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        progress?.let { p -> Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Color.White.copy(alpha = 0.28f))) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = fg.copy(alpha = if (enabled) 1f else 0.6f))
            Text(text, color = fg.copy(alpha = if (enabled) 1f else 0.6f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Hinweiszeile unter dem Startknopf (Fernstart, Tür). */
@Composable
fun FootNote(ok: Boolean, text: String) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.NotificationsActive, null, Modifier.size(16.dp), tint = if (ok) HcColors.DoneInk else g.warn)
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (ok) g.soft else g.warn, fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** Gemeinsame Startprüfung für den Hinweis unter dem Knopf. */
@Composable
fun startHint(a: HcAppliance): Pair<Boolean, String> = when {
    !a.connected -> false to stringResource(R.string.hc_status_offline_sub)
    !a.remoteStartAllowed -> false to stringResource(R.string.hc_hint_remote_start)
    a.localControl -> false to stringResource(R.string.hc_fail_local)
    a.doorOpen == true -> false to stringResource(R.string.hc_door_open)
    else -> true to listOfNotNull(
        stringResource(R.string.hc_hint_remote_ok),
        when (a.doorOpen) { false -> stringResource(R.string.hc_door_closed); else -> null },
    ).joinToString(" · ")
}
