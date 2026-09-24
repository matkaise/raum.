package app.raum.ui.appliance

import androidx.compose.ui.res.stringResource

import app.raum.ui.components.rememberLongDateFormatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.StartupStep
import app.raum.ui.components.rememberNow
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Ruhebildschirm: schwarz mit gedimmter Uhr. Berührung oder Annäherung weckt
 * (die weckende Berührung wird in der Activity verschluckt).
 */
@Composable
fun ScreensaverOverlay(visible: Boolean, statusLine: String) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val now by rememberNow()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(now.format(TIME), color = Color(0xFF8A8579), fontSize = 160.sp, fontWeight = FontWeight.Thin)
                Text(now.format(rememberLongDateFormatter()), color = Color(0xFF5E5A52), fontSize = 28.sp)
                Spacer(Modifier.height(24.dp))
                Text(statusLine, color = Color(0xFF4A473F), fontSize = 20.sp)
            }
        }
    }
}

/** Startbildschirm mit Fortschritt (SYS-008). */
@Composable
fun StartupOverlay(step: StartupStep) {
    AnimatedVisibility(step != StartupStep.READY, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("raum.", fontSize = 64.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                StartupStep.entries.filter { it != StartupStep.READY }.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            s.ordinal < step.ordinal -> Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
                            s == step -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            else -> Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline)
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(s.labelRes), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
