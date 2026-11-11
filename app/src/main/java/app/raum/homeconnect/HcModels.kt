package app.raum.homeconnect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Duration
import java.time.Instant

/**
 * Home Connect (Bosch/Siemens/Neff/Gaggenau) – Schlüssel der offiziellen Cloud-API.
 * Quelle: api-docs.home-connect.com und das Indigo-Plugin simons-plugins/indigo-home-connect.
 */
object HcKeys {
    const val OPERATION_STATE = "BSH.Common.Status.OperationState"
    const val DOOR_STATE = "BSH.Common.Status.DoorState"
    const val REMOTE_START = "BSH.Common.Status.RemoteControlStartAllowed"
    const val REMOTE_CONTROL = "BSH.Common.Status.RemoteControlActive"
    const val LOCAL_CONTROL = "BSH.Common.Status.LocalControlActive"
    const val POWER_STATE = "BSH.Common.Setting.PowerState"
    const val ACTIVE_PROGRAM = "BSH.Common.Root.ActiveProgram"
    const val SELECTED_PROGRAM = "BSH.Common.Root.SelectedProgram"
    const val PROGRESS = "BSH.Common.Option.ProgramProgress"
    const val REMAINING = "BSH.Common.Option.RemainingProgramTime"
    const val ELAPSED = "BSH.Common.Option.ElapsedProgramTime"
    const val DURATION = "BSH.Common.Option.Duration"
    const val START_IN_RELATIVE = "BSH.Common.Option.StartInRelative"
    const val PROGRAM_FINISHED = "BSH.Common.Event.ProgramFinished"
    const val PROGRAM_ABORTED = "BSH.Common.Event.ProgramAborted"
    const val PAUSE = "BSH.Common.Command.PauseProgram"
    const val RESUME = "BSH.Common.Command.ResumeProgram"

    const val COFFEE_BEANS = "ConsumerProducts.CoffeeMaker.Option.BeanAmount"
    const val COFFEE_FILL = "ConsumerProducts.CoffeeMaker.Option.FillQuantity"
    const val COFFEE_TEMP = "ConsumerProducts.CoffeeMaker.Option.CoffeeTemperature"
    const val HOT_WATER_TEMP = "ConsumerProducts.CoffeeMaker.Option.HotWaterTemperature"
    const val COFFEE_BEANS_EMPTY = "ConsumerProducts.CoffeeMaker.Event.BeanContainerEmpty"
    const val COFFEE_WATER_EMPTY = "ConsumerProducts.CoffeeMaker.Event.WaterTankEmpty"
    const val COFFEE_TRAY_FULL = "ConsumerProducts.CoffeeMaker.Event.DripTrayFull"

    const val OVEN_SETPOINT = "Cooking.Oven.Option.SetpointTemperature"
    const val OVEN_CAVITY = "Cooking.Oven.Status.CurrentCavityTemperature"
    const val OVEN_PREHEAT_DONE = "Cooking.Oven.Event.PreheatFinished"

    const val WASHER_TEMP = "LaundryCare.Washer.Option.Temperature"
    const val WASHER_SPIN = "LaundryCare.Washer.Option.SpinSpeed"
    const val DRYER_TARGET = "LaundryCare.Dryer.Option.DryingTarget"

    const val DISH_RINSE_AID = "Dishcare.Dishwasher.Event.RinseAidNearlyEmpty"
    const val DISH_SALT = "Dishcare.Dishwasher.Event.SaltNearlyEmpty"

    const val FRIDGE_SETPOINT = "Refrigeration.FridgeFreezer.Setting.SetpointTemperatureRefrigerator"
    const val FREEZER_SETPOINT = "Refrigeration.FridgeFreezer.Setting.SetpointTemperatureFreezer"
    const val FRIDGE_DOOR_ALARM = "Refrigeration.FridgeFreezer.Event.DoorAlarmRefrigerator"
    const val FREEZER_DOOR_ALARM = "Refrigeration.FridgeFreezer.Event.DoorAlarmFreezer"

    /** Hinweise, die raum. als Warnung zeigt (Nachfüllen, Leeren). */
    val WARNING_EVENTS = setOf(COFFEE_BEANS_EMPTY, COFFEE_WATER_EMPTY, COFFEE_TRAY_FULL, DISH_RINSE_AID, DISH_SALT, FRIDGE_DOOR_ALARM, FREEZER_DOOR_ALARM)
}

/**
 * Pflegeprogramm statt Getränk (Kaffeevollautomat spült beim Ein-/Ausschalten, entkalkt, reinigt):
 * „…Program.ApplianceOffRinsing“, „…Program.Cleaning…“ – alles außer Beverage/CoffeeWorld.
 */
fun isCareProgram(type: HcType, key: String?): Boolean =
    type == HcType.COFFEE && key != null && ".Beverage." !in key && ".CoffeeWorld." !in key

/** „BSH.Common.EnumType.OperationState.Run“ → „Run“. */
fun enumTail(value: String?): String? = value?.substringAfterLast('.')

enum class HcType {
    COFFEE, OVEN, DISHWASHER, WASHER, DRYER, WASHER_DRYER, FRIDGE, OTHER;

    companion object {
        fun from(apiType: String?): HcType = when (apiType) {
            "CoffeeMaker" -> COFFEE
            "Oven" -> OVEN
            "Dishwasher" -> DISHWASHER
            "Washer" -> WASHER
            "Dryer" -> DRYER
            "WasherDryer" -> WASHER_DRYER
            "FridgeFreezer", "Refrigerator", "Freezer", "WineCooler" -> FRIDGE
            else -> OTHER
        }
    }
}

enum class OperationState {
    INACTIVE, READY, DELAYED_START, RUN, PAUSE, ACTION_REQUIRED, FINISHED, ERROR, ABORTING, UNKNOWN;

    /** Programm läuft, wartet oder ist pausiert – Stopp ist möglich. */
    val active: Boolean get() = this == RUN || this == DELAYED_START || this == PAUSE || this == ACTION_REQUIRED

    companion object {
        fun from(value: String?): OperationState = when (enumTail(value)) {
            "Inactive" -> INACTIVE
            "Ready" -> READY
            "DelayedStart" -> DELAYED_START
            "Run" -> RUN
            "Pause" -> PAUSE
            "ActionRequired" -> ACTION_REQUIRED
            "Finished" -> FINISHED
            "Error" -> ERROR
            "Aborting" -> ABORTING
            null -> UNKNOWN
            else -> UNKNOWN
        }
    }
}

/** Ein Haushaltsgerät mit seinem zuletzt bekannten Zustand (aus REST-Abfragen und dem Ereignisstrom). */
data class HcAppliance(
    val haId: String,
    val name: String,
    val brand: String,
    val type: HcType,
    val connected: Boolean,
    /** Status, Einstellungen und Optionen des aktiven Programms – flach nach Schlüssel. */
    val values: Map<String, JsonPrimitive> = emptyMap(),
    val activeProgram: String? = null,
    val selectedProgram: String? = null,
    /** Aktive Hinweise (EventPresentState Present/Confirmed) mit Zeitpunkt. */
    val events: Map<String, Instant> = emptyMap(),
    /** Wann die Restzeit zuletzt gemeldet wurde – dazwischen zählt raum. selbst herunter. */
    val remainingAt: Instant? = null,
    val finishedAt: Instant? = null,
    /** Erlaubte Werte für den Betriebszustand (z. B. Trockner: nur „On“). */
    val powerValues: List<String>? = null,
    val commands: Set<String>? = null,
) {
    val operation: OperationState get() = OperationState.from(string(HcKeys.OPERATION_STATE))
    val power: String? get() = enumTail(string(HcKeys.POWER_STATE))
    val poweredOn: Boolean get() = power == null || power == "On"
    val doorOpen: Boolean? get() = enumTail(string(HcKeys.DOOR_STATE))?.let { it == "Open" || it == "AjarOpen" }
    val doorLocked: Boolean get() = enumTail(string(HcKeys.DOOR_STATE)) == "Locked"
    val remoteStartAllowed: Boolean get() = bool(HcKeys.REMOTE_START) ?: false
    val localControl: Boolean get() = bool(HcKeys.LOCAL_CONTROL) ?: false
    val progressPercent: Int? get() = number(HcKeys.PROGRESS)?.toInt()?.coerceIn(0, 100)
    val warnings: Set<String> get() = events.keys intersect HcKeys.WARNING_EVENTS

    fun string(key: String): String? = values[key]?.contentOrNull
    fun number(key: String): Double? = values[key]?.doubleOrNull
    fun bool(key: String): Boolean? = values[key]?.let { it.booleanOrNull ?: it.contentOrNull?.lowercase()?.let { s -> s == "true" || s == "on" } }

    /** Restzeit jetzt – zwischen zwei Meldungen lokal heruntergezählt (nicht im Pausen-Zustand). */
    fun remainingSeconds(now: Instant): Long? {
        val reported = number(HcKeys.REMAINING)?.toLong() ?: return null
        val at = remainingAt ?: return reported
        if (operation != OperationState.RUN) return reported
        return (reported - Duration.between(at, now).seconds).coerceAtLeast(0)
    }

    /** Startzeitvorwahl: wann das Programm beginnt. */
    fun delayedStartAt(): Instant? {
        if (operation != OperationState.DELAYED_START) return null
        val rel = number(HcKeys.START_IN_RELATIVE)?.toLong() ?: return null
        return (remainingAt ?: Instant.now()).plusSeconds(rel)
    }
}

/** Option eines Programms mit den Grenzen, die das Gerät meldet. */
@Serializable
data class HcOptionDef(
    val key: String,
    val name: String,
    /** „Int“, „Double“, „Boolean“ oder ein Aufzählungstyp wie „ConsumerProducts.CoffeeMaker.EnumType.BeanAmount“ */
    val type: String,
    val unit: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val allowed: List<String> = emptyList(),
    val display: List<String> = emptyList(),
    val default: JsonPrimitive? = null,
) {
    val isBoolean: Boolean get() = type == "Boolean"
    val isEnum: Boolean get() = allowed.isNotEmpty()
    val isNumber: Boolean get() = !isEnum && (type == "Int" || type == "Double" || min != null)

    /**
     * Wert auf das gültige Raster bringen: Zahlen auf min + n·step gerundet und begrenzt, Aufzählungen nur mit
     * erlaubtem Wert (sonst null = nicht senden). Das Gerät verwirft ungültige Werte sonst stillschweigend.
     */
    fun fit(value: JsonPrimitive): JsonPrimitive? {
        if (isEnum) return value.takeIf { it.contentOrNull in allowed }
        if (isBoolean) return value
        val v = value.doubleOrNull ?: return value
        val lo = min ?: v
        val hi = max ?: v
        val st = step?.takeIf { it > 0 } ?: 1.0
        val snapped = (lo + Math.round((v.coerceIn(lo, hi) - lo) / st) * st).coerceIn(lo, hi)
        return if (type == "Double") JsonPrimitive(snapped) else JsonPrimitive(Math.round(snapped))
    }

    /** Anzeigename für einen erlaubten Wert („Mild“ statt „ConsumerProducts…BeanAmount.Mild“). */
    fun label(value: String): String = display.getOrNull(allowed.indexOf(value))?.takeIf { it.isNotBlank() } ?: prettify(enumTail(value) ?: value)
}

@Serializable
data class HcProgram(
    val key: String,
    val name: String,
    /** Leer, solange die Programmdetails nicht geladen sind. */
    val options: List<HcOptionDef> = emptyList(),
    val detailed: Boolean = false,
    /**
     * Meldet das Gerät das Programm gerade als verfügbar? Während ein Programm läuft oder je nach Wahlschalter
     * schrumpft die Liste „available“ auf eins – angezeigt wird deshalb die vollständige Liste.
     */
    val available: Boolean = true,
    /**
     * Grenzen nach Auswahl des Programms am Gerät gelesen. Vorher melden manche Geräte pauschale Werte
     * (Kaffeevollautomat: Menge 20–400 in 5er-Schritten statt Heißwasser 100–400 in 20er-Schritten).
     */
    val refined: Boolean = false,
) {
    fun option(key: String): HcOptionDef? = options.firstOrNull { it.key == key }
}

/** „Eco50“ → „Eco 50“, „TopBottomHeating“ → „Top Bottom Heating“ – Ersatz, falls die API keinen Namen liefert. */
fun prettify(raw: String): String {
    val out = StringBuilder()
    raw.forEachIndexed { i, c ->
        val prev = raw.getOrNull(i - 1)
        if (prev != null && ((c.isUpperCase() && prev.isLowerCase()) || (c.isDigit() && prev.isLetter()) || (c.isLetter() && prev.isDigit()))) out.append(' ')
        out.append(c)
    }
    return out.toString().replace("GC", "").trim()
}

/** Ein Ereignis aus dem Strom /api/homeappliances/events. */
data class HcEvent(val type: String, val haId: String?, val items: List<HcItem> = emptyList())

data class HcItem(val key: String, val value: JsonPrimitive?, val timestamp: Long? = null, val uri: String? = null)

/** Warum ein Befehl nicht ausgeführt wurde – raum. prüft vorher selbst, um das Tageskontingent zu schonen. */
enum class HcFailure {
    OFFLINE, REMOTE_START_OFF, LOCAL_CONTROL, NOT_READY, POWER_READ_ONLY, POWER_ON_TIMEOUT, NOT_RUNNING,
    RATE_LIMITED, AUTH, BUDGET, DOOR_OPEN, REJECTED, NETWORK,
}

class HcException(val failure: HcFailure, val detail: String? = null, val retryAfterSeconds: Long? = null) : Exception(detail ?: failure.name)
