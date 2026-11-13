package app.raum.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.raum.R
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextStyle
import app.raum.ui.theme.glass

/*
 * Bausteine der Einstellungen im Stil der übrigen Bereiche: Glaskarten mit kleiner Überschrift,
 * dunkle Umschalter, Erklärungen nur auf Nachfrage (i) und Technisches unter „Erweitert“.
 */

/** Schrift auf dunkler Auswahl – im Dunkelmodus ist „ink“ hell. */
@Composable
internal fun onInk(): Color = if (LocalGlass.current.dark) Color(0xFF10131F) else Color.White

@Composable
internal fun CardLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = LocalGlass.current.soft, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
}

/** Glaskarte; Überschrift, (i) und Knopf oben in einer Zeile. */
@Composable
internal fun Group(
    title: String? = null,
    info: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = LocalGlass.current
    Column(
        modifier.fillMaxWidth().padding(bottom = 14.dp).glass(RoundedCornerShape(28.dp), g.glass, g.edge)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null || trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (title != null) CardLabel(title, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                trailing?.invoke()
                info?.let { InfoButton(it) }
            }
            CompositionLocalProvider(LocalContentColor provides g.ink) { content() }
        } else if (info != null) {
            // Ohne Überschrift steht das (i) neben dem Inhalt statt in einer eigenen Zeile
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompositionLocalProvider(LocalContentColor provides g.ink) { content() }
                }
                InfoButton(info)
            }
        } else {
            CompositionLocalProvider(LocalContentColor provides g.ink) { content() }
        }
    }
}

/** (i) – die Erklärung erscheint als Sprechblase und verschwindet beim nächsten Tippen. */
@Composable
internal fun InfoButton(text: String) {
    val g = LocalGlass.current
    var open by remember { mutableStateOf(false) }
    val y = with(LocalDensity.current) { 44.dp.roundToPx() }
    Box {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(g.ink.copy(alpha = 0.07f))
                .clickable(onClickLabel = stringResource(R.string.settings_more_info), role = Role.Button) { open = !open },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Info, stringResource(R.string.settings_more_info), Modifier.size(18.dp), tint = g.ink) }
        if (open) {
            Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, y), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                Text(
                    text, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp,
                    modifier = Modifier.widthIn(max = 420.dp).shadow(20.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF10131F)).clickable { open = false }.padding(horizontal = 18.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/** Umschalter über die Breite: gewählt dunkel. */
@Composable
internal fun <T> Seg(
    options: List<Pair<T, String>>,
    selected: T?,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    val g = LocalGlass.current
    Row(modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(g.ink.copy(alpha = 0.07f)).padding(4.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val ok = enabled(value)
            Box(
                Modifier.weight(1f).fillMaxHeight().alpha(if (ok) 1f else 0.4f).clip(RoundedCornerShape(22.dp))
                    .background(if (on) g.ink else Color.Transparent)
                    .clickable(enabled = ok && !on, role = Role.RadioButton) { onSelect(value) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) onInk() else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Pillenknopf; [primary] dunkel gefüllt. */
@Composable
internal fun ActionPill(text: String, icon: ImageVector? = null, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.height(48.dp).alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(24.dp))
            .background(if (primary) g.ink else g.ink.copy(alpha = 0.07f)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val fg = if (primary) onInk() else g.ink
        icon?.let { Icon(it, null, Modifier.size(20.dp), tint = fg) }
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Kleine Pille in einer Reihe (Ruhezustand nach …). */
@Composable
internal fun ChoicePill(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier.height(44.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(22.dp))
            .background(if (selected) g.ink else g.ink.copy(alpha = 0.07f)).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) onInk() else g.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

/** Schalter im Stil der Abläufe: dunkle Bahn mit grünem Knopf. */
@Composable
internal fun GlassSwitch(on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier.size(56.dp, 32.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(16.dp))
            .background(if (on) g.ink else g.ink.copy(alpha = 0.15f)).clickable(enabled = enabled, role = Role.Switch) { onChange(!on) },
    ) {
        Box(
            Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 4.dp).size(24.dp)
                .clip(CircleShape).background(if (on) Color(0xFFA8E6B8) else Color.White),
        )
    }
}

/** Zeile mit Text und Schalter als eigene kleine Karte. */
@Composable
internal fun SwitchCard(label: String, on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val g = LocalGlass.current
    Row(
        modifier.height(76.dp).glass(RoundedCornerShape(28.dp), g.glass, g.edge).clickable(enabled = enabled) { onChange(!on) }.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = g.ink.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        GlassSwitch(on, enabled, onChange)
    }
}

/** Runder Farbkreis mit Symbol (Kopf von Diensten, Kacheln). */
@Composable
internal fun IconDisk(icon: ImageVector, bg: Color, fg: Color, size: Int = 48) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size((size / 2).dp), tint = fg)
    }
}

/** Kopf eines Dienstes: Symbol, Name, Untertitel, (i) und Umschalter. */
@Composable
internal fun ServiceHero(icon: ImageVector, bg: Color, fg: Color, title: String, subtitle: String?, info: String?, trailing: @Composable () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).glass(RoundedCornerShape(28.dp), g.glass, g.edge).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconDisk(icon, bg, fg, 60)
        Column(Modifier.weight(1f)) {
            Text(title, color = g.ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = g.soft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing()
        info?.let { InfoButton(it) }
    }
}

/** Statuskachel: Symbol, Titel, Zustand, optional ein Knopf. */
@Composable
internal fun StatusTile(
    icon: ImageVector,
    bg: Color,
    fg: Color,
    title: String,
    status: String,
    modifier: Modifier = Modifier,
    problem: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
) {
    val g = LocalGlass.current
    Column(
        modifier.height(196.dp).glass(RoundedCornerShape(28.dp), g.glass, g.edge).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconDisk(icon, bg, fg)
        Column {
            Text(title, color = g.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(status, color = if (problem) Color(0xFFB3261E) else g.soft, fontSize = 14.sp, fontWeight = if (problem) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        action?.let { (label, onClick) -> ActionPill(label, primary = problem, onClick = onClick) }
    }
}

/** „Erweitert ›“ auf dem Himmel – klappt Technisches auf. */
@Composable
internal fun Advanced(label: String = stringResource(R.string.settings_advanced), content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(label) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).clickable { open = !open }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = SkyTextStyle, color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(22.dp).rotate(turn), tint = SkyText)
    }
    if (open) Column(Modifier.padding(top = 8.dp), content = content)
}
