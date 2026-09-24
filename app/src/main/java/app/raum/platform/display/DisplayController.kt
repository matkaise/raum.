package app.raum.platform.display

import app.raum.R
import androidx.annotation.StringRes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10

enum class SleepAction(@StringRes val labelRes: Int) { SCREENSAVER(R.string.sleep_screensaver), DISPLAY_OFF(R.string.sleep_display_off) }

data class DisplaySettings(
    /** null = nie in den Ruhezustand */
    val sleepAfterSeconds: Int? = 120,
    val sleepAction: SleepAction = SleepAction.SCREENSAVER,
    val proximityWake: Boolean = true,
    val autoBrightness: Boolean = true,
    val manualBrightness: Float = 0.7f,
    val minBrightness: Float = 0.08f,
    val maxBrightness: Float = 1.0f,
)

enum class DisplayMode { ACTIVE, SCREENSAVER, OFF }

/** Abbildung Umgebungslicht → Displayhelligkeit (logarithmisch, wie das Auge wahrnimmt). */
object BrightnessCurve {
    /** 0 lx → min, ab ~1000 lx (helles Tageslicht innen) → max. */
    fun target(lux: Float, min: Float, max: Float): Float {
        val t = (log10(lux.coerceAtLeast(0f) + 1f) / log10(1001f)).coerceIn(0f, 1f)
        return min + (max - min) * t
    }
}

/**
 * Steuert Ruhezustand und Helligkeit des Panels (Spez. 4.1, M5).
 *
 * Plattformunabhängig: Sensorwerte kommen als Flows, die Zeit über [nowMs]. Die Android-Seite
 * (Fensterhelligkeit, Display aus/an) setzt nur um, was [mode] und [brightness] vorgeben.
 */
class DisplayController(
    private val settings: StateFlow<DisplaySettings>,
    private val lux: Flow<Float?>,
    private val proximityNear: Flow<Boolean?>,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Darf das Display wirklich ausgeschaltet werden (nur als Device Owner)? */
    private val canTurnOff: () -> Boolean = { false },
    private val tickMs: Long = 500,
) {
    private val _mode = MutableStateFlow(DisplayMode.ACTIVE)
    val mode: StateFlow<DisplayMode> = _mode.asStateFlow()

    private val _brightness = MutableStateFlow(settings.value.manualBrightness)
    /** Fensterhelligkeit 0..1 */
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    private val _lux = MutableStateFlow<Float?>(null)
    val currentLux: StateFlow<Float?> = _lux.asStateFlow()
    private val _near = MutableStateFlow<Boolean?>(null)
    val currentNear: StateFlow<Boolean?> = _near.asStateFlow()

    @Volatile private var lastActivity = nowMs()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { lux.collect { _lux.value = it } }
        scope.launch {
            proximityNear.collect { near ->
                val before = _near.value
                _near.value = near
                // Annäherung (frei → nah) weckt bzw. hält wach
                if (near == true && before != true && settings.value.proximityWake) {
                    if (_mode.value == DisplayMode.ACTIVE) userActivity() else wake()
                }
            }
        }
        scope.launch {
            while (true) {
                tick()
                delay(tickMs)
            }
        }
    }

    /** Berührung o. Ä. im aktiven Zustand. */
    fun userActivity() {
        lastActivity = nowMs()
    }

    /** Aus Ruhezustand/Display aus aufwecken. */
    fun wake() {
        lastActivity = nowMs()
        if (_mode.value != DisplayMode.ACTIVE) {
            _mode.value = DisplayMode.ACTIVE
            // Beim Aufwecken sofort passende Helligkeit statt langsamem Hochfahren
            _brightness.value = targetBrightness()
        }
    }

    /** Sofort in den Ruhezustand (z. B. Taste „Bildschirm aus“). */
    fun sleepNow() = goToSleep()

    internal fun tick() {
        val s = settings.value
        val timeout = s.sleepAfterSeconds
        if (_mode.value == DisplayMode.ACTIVE && timeout != null && nowMs() - lastActivity >= timeout * 1000L) {
            goToSleep()
        }
        val target = targetBrightness()
        val current = _brightness.value
        // Sanfte Annäherung, kleine Schwankungen ignorieren (kein Flackern)
        val next = if (abs(target - current) < 0.01f) target else current + (target - current) * 0.35f
        if (abs(next - current) >= 0.005f || next == target) _brightness.value = next
    }

    private fun goToSleep() {
        val s = settings.value
        _mode.value = if (s.sleepAction == SleepAction.DISPLAY_OFF && canTurnOff()) DisplayMode.OFF else DisplayMode.SCREENSAVER
    }

    private fun targetBrightness(): Float {
        val s = settings.value
        return when (_mode.value) {
            DisplayMode.OFF -> 0f
            DisplayMode.SCREENSAVER -> minOf(s.minBrightness, 0.05f)
            DisplayMode.ACTIVE -> {
                val l = _lux.value
                if (s.autoBrightness && l != null) BrightnessCurve.target(l, s.minBrightness, s.maxBrightness)
                else s.manualBrightness
            }
        }.coerceIn(0f, 1f)
    }
}
