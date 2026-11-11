package app.raum.ui.household

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.CoffeeMaker
import androidx.compose.material.icons.outlined.DryCleaning
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.LocalPizza
import androidx.compose.material.icons.outlined.OutdoorGrill
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignCenter
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.raum.R
import app.raum.homeconnect.HcAppliance
import app.raum.homeconnect.HcKeys
import app.raum.homeconnect.HcType
import app.raum.homeconnect.HomeConnectService
import app.raum.homeconnect.OperationState
import app.raum.homeconnect.enumTail
import app.raum.homeconnect.isCareProgram
import app.raum.ui.theme.LocalGlass
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Farben aus dem Entwurf „Haushalt“. */
object HcColors {
    val Coffee = Color(0xFFC98B4E)
    val Oven = Color(0xFFFF8A3D)
    val Washer = Color(0xFF6CB6F0)
    val Dish = Color(0xFF7FD1D6)
    val Dryer = Color(0xFF8FD6A0)
    val Fridge = Color(0xFF9FD3FF)
    val Other = Color(0xFFC9B6F0)
    val Done = Color(0xFF8FD6A0)
    val DoneInk = Color(0xFF2E8B4E)
    val Alert = Color(0xFFD9534F)
    val OnAccent = Color(0xFF0D1B26)
}

fun accentFor(type: HcType): Color = when (type) {
    HcType.COFFEE -> HcColors.Coffee
    HcType.OVEN -> HcColors.Oven
    HcType.WASHER, HcType.WASHER_DRYER -> HcColors.Washer
    HcType.DISHWASHER -> HcColors.Dish
    HcType.DRYER -> HcColors.Dryer
    HcType.FRIDGE -> HcColors.Fridge
    HcType.OTHER -> HcColors.Other
}

fun iconFor(type: HcType): ImageVector = when (type) {
    HcType.COFFEE -> Icons.Outlined.CoffeeMaker
    HcType.OVEN -> HcIcons.Oven
    HcType.WASHER, HcType.WASHER_DRYER -> Icons.Outlined.LocalLaundryService
    HcType.DISHWASHER -> HcIcons.Dishwasher
    HcType.DRYER -> Icons.Outlined.DryCleaning
    HcType.FRIDGE, HcType.OTHER -> Icons.Outlined.Kitchen
}

/** Betriebsarten des Backofens nach Programmschlüssel. */
fun ovenIcon(programKey: String): ImageVector = when (enumTail(programKey)) {
    "HotAir", "HotAirEco", "HotAir30Steam", "HotAirGentle" -> Icons.Outlined.Air
    "TopBottomHeating", "TopBottomHeatingEco" -> Icons.Outlined.VerticalAlignCenter
    "BottomHeating" -> Icons.Outlined.VerticalAlignBottom
    "PizzaSetting" -> Icons.Outlined.LocalPizza
    "GrillLargeArea", "GrillSmallArea", "HotAirGrilling" -> Icons.Outlined.OutdoorGrill
    "Defrost", "FrozenHeatupSpecial" -> Icons.Outlined.AcUnit
    "KeepWarm", "WarmingDrawer", "PreheatOvenware" -> Icons.Outlined.Whatshot
    "SlowCook", "Desiccation" -> Icons.Outlined.HourglassTop
    "PreHeating", "IntensiveHeat" -> Icons.Outlined.LocalFireDepartment
    else -> if ("Eco" in programKey) Icons.Outlined.Eco else HcIcons.Oven
}

/** Eigene Umriss-Symbole (Material bietet keinen Backofen und keinen Geschirrspüler). */
object HcIcons {
    val Oven: ImageVector by lazy {
        ImageVector.Builder("Oven", 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
                moveTo(5.5f, 3f); lineTo(18.5f, 3f); arcTo(2.5f, 2.5f, 0f, false, true, 21f, 5.5f); lineTo(21f, 18.5f)
                arcTo(2.5f, 2.5f, 0f, false, true, 18.5f, 21f); lineTo(5.5f, 21f); arcTo(2.5f, 2.5f, 0f, false, true, 3f, 18.5f)
                lineTo(3f, 5.5f); arcTo(2.5f, 2.5f, 0f, false, true, 5.5f, 3f); close()
                moveTo(3f, 8.5f); lineTo(21f, 8.5f)
                moveTo(7.5f, 11.5f); lineTo(16.5f, 11.5f); lineTo(16.5f, 17.5f); lineTo(7.5f, 17.5f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(6.5f, 5.75f); arcTo(1f, 1f, 0f, true, true, 8.5f, 5.75f); arcTo(1f, 1f, 0f, true, true, 6.5f, 5.75f); close()
                moveTo(10f, 5.75f); arcTo(1f, 1f, 0f, true, true, 12f, 5.75f); arcTo(1f, 1f, 0f, true, true, 10f, 5.75f); close()
            }
        }.build()
    }

    val Dishwasher: ImageVector by lazy {
        ImageVector.Builder("Dishwasher", 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
                moveTo(6.5f, 2.5f); lineTo(17.5f, 2.5f); arcTo(2.5f, 2.5f, 0f, false, true, 20f, 5f); lineTo(20f, 19f)
                arcTo(2.5f, 2.5f, 0f, false, true, 17.5f, 21.5f); lineTo(6.5f, 21.5f); arcTo(2.5f, 2.5f, 0f, false, true, 4f, 19f)
                lineTo(4f, 5f); arcTo(2.5f, 2.5f, 0f, false, true, 6.5f, 2.5f); close()
                moveTo(4f, 7f); lineTo(20f, 7f)
                moveTo(8f, 14f); arcTo(4f, 4f, 0f, true, true, 16f, 14f); arcTo(4f, 4f, 0f, true, true, 8f, 14f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(6.5f, 4.75f); arcTo(1f, 1f, 0f, true, true, 8.5f, 4.75f); arcTo(1f, 1f, 0f, true, true, 6.5f, 4.75f); close()
            }
        }.build()
    }
}

/** Uhr für Restzeiten und Countdowns (sekündlich). */
@Composable
fun rememberNow(periodMs: Long = 1000): Instant {
    val now by produceState(Instant.now(), periodMs) {
        while (true) { delay(periodMs); value = Instant.now() }
    }
    return now
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
/** Uhrzeit auf die Minute gerundet (00:59:58 → 01:00). */
fun hm(t: Instant): String = t.plusSeconds(30).atZone(ZoneId.systemDefault()).format(HHMM)

/** Sekunden → „1:24“ (wie das Plugin: auf die Minute gerundet). */
fun hmDuration(seconds: Long): String {
    val m = Math.round(seconds.coerceAtLeast(0) / 60.0)
    return "${m / 60}:${"%02d".format(m % 60)}"
}

/** Kurzstatus für Karten, Liste und Übersicht. */
data class ApplianceSummary(
    val status: String,
    /** Zweite Zeile – leer, wenn es nichts Wichtiges zu sagen gibt (kein „Tür geschlossen“ als Füllstoff). */
    val sub: String,
    /** 0..1 für den Ring */
    val progress: Float,
    val accent: Color,
    val live: Boolean,
    val done: Boolean,
    /** Status selbst ist ein Problem (Störung, Aktion nötig) */
    val alert: Boolean,
    /** Zweite Zeile ist ein Hinweis (Nachfüllen, Tür offen) */
    val subAlert: Boolean = false,
)

@Composable
fun summarize(a: HcAppliance, service: HomeConnectService?, now: Instant): ApplianceSummary {
    val accent = accentFor(a.type)
    val program = service?.programName(a.haId, a.activeProgram).orEmpty()
    val op = a.operation
    val remaining = a.remainingSeconds(now)
    val progress = (a.progressPercent ?: 0) / 100f
    val warning = a.warnings.firstOrNull()?.let { stringResource(HomeConnectService.warningLabel(it)) }
    val finishAt = remaining?.let { hm(now.plusSeconds(it)) }
    // Hinweis als zweite Zeile: Nachfüllen o. Ä. vor offener Tür; der Status selbst bleibt der Betriebszustand
    val notice = warning ?: if (a.doorOpen == true && !op.active) stringResource(R.string.hc_door_open) else null
    if (!a.connected) return ApplianceSummary(stringResource(R.string.hc_status_offline), "", 0f, accent, false, false, false)
    return when (op) {
        OperationState.DELAYED_START -> {
            val at = a.delayedStartAt()
            ApplianceSummary(
                at?.let { stringResource(R.string.hc_status_start_at, hm(it)) } ?: stringResource(R.string.hc_status_delayed),
                warning ?: program, 0f, accent, true, false, false, subAlert = warning != null,
            )
        }
        OperationState.RUN, OperationState.ACTION_REQUIRED, OperationState.PAUSE -> {
            val paused = op == OperationState.PAUSE
            when {
                // Spülen beim Ein-/Ausschalten, Reinigung, Entkalkung – kein Getränk
                isCareProgram(a.type, a.activeProgram) -> ApplianceSummary(stringResource(R.string.hc_status_care), "", progress, accent, true, false, false)
                a.type == HcType.COFFEE -> ApplianceSummary(stringResource(R.string.hc_status_brewing), program, progress, accent, true, false, false)
                a.type == HcType.OVEN -> {
                    val cavity = a.number(HcKeys.OVEN_CAVITY)
                    val target = a.number(HcKeys.OVEN_SETPOINT)
                    val reached = HcKeys.OVEN_PREHEAT_DONE in a.events || (cavity != null && target != null && cavity >= target - 0.5)
                    val p = if (cavity != null && target != null) ((cavity - 21) / (target - 21).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) else progress
                    ApplianceSummary(
                        when {
                            // Ohne Zieltemperatur (Mikrowelle): Restzeit statt „Heizt“
                            target == null -> remaining?.let { stringResource(R.string.hc_status_remaining, hmDuration(it)) } ?: stringResource(R.string.hc_status_running)
                            reached -> stringResource(R.string.hc_status_reached, target.toInt())
                            cavity != null -> stringResource(R.string.hc_status_heating, cavity.toInt())
                            else -> stringResource(R.string.hc_status_running)
                        },
                        program, if (target == null) progress else p, accent, true, false, false,
                    )
                }
                else -> ApplianceSummary(
                    when {
                        paused -> stringResource(R.string.hc_status_paused)
                        op == OperationState.ACTION_REQUIRED -> stringResource(R.string.hc_status_action)
                        remaining != null -> stringResource(R.string.hc_status_remaining, hmDuration(remaining))
                        else -> stringResource(R.string.hc_status_running)
                    },
                    // Fertig-Uhrzeit ist hier wichtiger als der Programmname
                    finishAt?.takeIf { !paused }?.let { stringResource(R.string.hc_status_finish_at_long, it) } ?: program,
                    progress, accent, true, false, op == OperationState.ACTION_REQUIRED,
                )
            }
        }
        OperationState.FINISHED -> ApplianceSummary(
            stringResource(R.string.hc_status_finished),
            a.finishedAt?.let { stringResource(R.string.hc_status_finished_since, hm(it)) } ?: "",
            1f, HcColors.Done, false, true, false,
        )
        OperationState.ERROR -> ApplianceSummary(stringResource(R.string.hc_status_error), warning.orEmpty(), 0f, accent, false, false, true)
        else -> ApplianceSummary(
            when {
                !a.poweredOn -> stringResource(if (a.power == "Standby") R.string.hc_status_standby else R.string.hc_status_off)
                else -> stringResource(R.string.hc_status_ready)
            },
            notice.orEmpty(), 0f, accent, false, false, false, subAlert = notice != null,
        )
    }
}

/** Farbe der zweiten Zeile. */
@Composable
fun subColor(s: ApplianceSummary): Color = if (s.subAlert) LocalGlass.current.warn else LocalGlass.current.soft

/** Fortschrittsring mit Gerätesymbol in der Mitte (Karten im Entwurf). */
@Composable
fun ApplianceRing(type: HcType, summary: ApplianceSummary, size: Dp, iconSize: Dp) = ApplianceRing(iconFor(type), summary, size, iconSize)

@Composable
fun ApplianceRing(icon: ImageVector, summary: ApplianceSummary, size: Dp, iconSize: Dp) {
    val g = LocalGlass.current
    val ringColor = if (summary.done) HcColors.Done else summary.accent
    val iconColor = when {
        summary.alert -> HcColors.Alert
        summary.live || summary.done -> ringColor
        else -> g.soft
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = 5.dp.toPx()
            drawArc(g.track, 0f, 360f, false, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w), style = Stroke(w))
            if (summary.progress > 0f) drawArc(ringColor, -90f, 360f * summary.progress, false, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Butt))
        }
        Box(Modifier.size(size - 10.dp).clip(CircleShape).background(g.glassSolid), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(iconSize), tint = iconColor)
        }
    }
}

/** Kurz eingeblendeter Hinweis (z. B. „Backofen: vorgeheizt“) – verschwindet nach 4 s. */
@Composable
fun rememberToast(service: HomeConnectService): String? {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(service) {
        service.notices.collect { n ->
            text = n.text
        }
    }
    LaunchedEffect(text) { if (text != null) { delay(4000); text = null } }
    return text
}
