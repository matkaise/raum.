package app.raum.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.raum.domain.models.Device
import app.raum.domain.models.Scene
import app.raum.ui.theme.SectionLabelStyle
import app.raum.ui.theme.glass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = SectionLabelStyle, color = androidx.compose.material3.LocalContentColor.current, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.padding(start = 20.dp, bottom = 24.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, color = SkyText, fontSize = 56.sp, fontWeight = FontWeight.Light, lineHeight = 60.sp, letterSpacing = (-1.5).sp)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = SkyTextSoft)
            }
        }
        trailing()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(64.dp), tint = SkyTextSoft)
        Spacer(Modifier.size(16.dp))
        Text(title, color = SkyText, fontSize = 32.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = SkyTextSoft)
        Spacer(Modifier.size(24.dp))
        action()
    }
}

/** Callback-Bündel, damit Bildschirme Gerätekarten einheitlich verdrahten. */
data class DeviceCardActions(
    val onToggle: (Device) -> Unit,
    val onCommand: (Device, app.raum.domain.models.DeviceCommand) -> Unit,
    val onOpenDetails: (Device) -> Unit,
)

/** Glasfläche für Formulare und Listen auf dem Himmel. */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val g = app.raum.ui.theme.LocalGlass.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides g.ink) {
        Column(
            modifier.fillMaxWidth().glass(androidx.compose.foundation.shape.RoundedCornerShape(32.dp), g.glass, g.edge).padding(24.dp),
            content = content,
        )
    }
}
