package app.raum.homeconnect

import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * Fünf simulierte Geräte wie im Entwurf „Haushalt“ – zum Ausprobieren ohne Home-Connect-Konto und ohne Internet.
 * Verhält sich wie die Cloud: REST-Antworten plus Ereignisstrom (STATUS/NOTIFY/EVENT).
 */
class DemoHomeConnect(private val german: () -> Boolean, private val clock: Clock = Clock.systemUTC()) : HcBackend {

    private class Dev(
        val haId: String, val nameDe: String, val nameEn: String, val type: HcType,
        val programs: List<HcProgram>,
    ) {
        val values = mutableMapOf<String, JsonPrimitive>()
        var active: String? = null
        val events = mutableSetOf<String>()
        /** Simulation: Programmstart und Dauer in Sekunden */
        var startedMs = 0L
        var totalSec = 0L
        var pausedElapsed: Long? = null
        var delayUntilMs = 0L
        var cavity = 21.0
        var finishedMs = 0L
    }

    private fun t(de: String, en: String) = if (german()) de else en
    private fun s(v: String) = JsonPrimitive(v)
    private fun n(v: Number) = JsonPrimitive(v)
    private fun b(v: Boolean) = JsonPrimitive(v)
    private fun op(state: String) = s("BSH.Common.EnumType.OperationState.$state")
    private val present = s("BSH.Common.EnumType.EventPresentState.Present")
    private val off = s("BSH.Common.EnumType.EventPresentState.Off")

    private val bus = MutableSharedFlow<HcEvent>(extraBufferCapacity = 64)

    // --- Programme ------------------------------------------------------------------------

    private fun enumOpt(key: String, nameDe: String, nameEn: String, type: String, values: List<Pair<String, Pair<String, String>>>, default: String) =
        HcOptionDef(key, t(nameDe, nameEn), type, allowed = values.map { "$type.${it.first}" }, display = values.map { t(it.second.first, it.second.second) }, default = s("$type.$default"))

    private fun coffee(key: String, de: String, en: String, min: Int, max: Int, def: Int, beans: Boolean = true): HcProgram {
        val opts = buildList {
            if (beans) add(enumOpt(HcKeys.COFFEE_BEANS, "Kaffeestärke", "Strength", "ConsumerProducts.CoffeeMaker.EnumType.BeanAmount",
                listOf("VeryMild" to ("Sehr mild" to "Very mild"), "Mild" to ("Mild" to "Mild"), "Normal" to ("Normal" to "Normal"),
                    "Strong" to ("Stark" to "Strong"), "VeryStrong" to ("Sehr stark" to "Very strong")), "Normal"))
            add(HcOptionDef(HcKeys.COFFEE_FILL, t("Füllmenge", "Fill quantity"), "Int", "ml", min.toDouble(), max.toDouble(), 5.0, default = n(def)))
            add(enumOpt(HcKeys.COFFEE_TEMP, "Temperatur", "Temperature", "ConsumerProducts.CoffeeMaker.EnumType.CoffeeTemperature",
                listOf("88C" to ("Normal" to "Normal"), "92C" to ("Hoch" to "High"), "96C" to ("Sehr hoch" to "Very high")), "88C"))
        }
        return HcProgram("ConsumerProducts.CoffeeMaker.Program.$key", t(de, en), opts, detailed = true)
    }

    private fun oven(key: String, de: String, en: String, def: Int, min: Int = 30, max: Int = 275) = HcProgram(
        "Cooking.Oven.Program.HeatingMode.$key", t(de, en), listOf(
            HcOptionDef(HcKeys.OVEN_SETPOINT, t("Temperatur", "Temperature"), "Double", "°C", min.toDouble(), max.toDouble(), 5.0, default = n(def)),
            HcOptionDef(HcKeys.DURATION, t("Dauer", "Duration"), "Int", "seconds", 0.0, 86_340.0, 60.0, default = n(0)),
        ), detailed = true,
    )

    private val washerTemps = listOf("Cold" to ("Kalt" to "Cold"), "GC20" to ("20°" to "20°"), "GC30" to ("30°" to "30°"), "GC40" to ("40°" to "40°"), "GC60" to ("60°" to "60°"), "GC90" to ("90°" to "90°"))
    private val spins = listOf("Off" to ("Ohne" to "None"), "RPM800" to ("800" to "800"), "RPM1000" to ("1000" to "1000"), "RPM1200" to ("1200" to "1200"), "RPM1400" to ("1400" to "1400"))

    private fun washer(key: String, de: String, en: String, temp: String, spin: String = "RPM1400", temps: Int = washerTemps.size) = HcProgram(
        "LaundryCare.Washer.Program.$key", t(de, en), listOf(
            enumOpt(HcKeys.WASHER_TEMP, "Temperatur", "Temperature", "LaundryCare.Washer.EnumType.Temperature", washerTemps.take(temps), temp),
            enumOpt(HcKeys.WASHER_SPIN, "Schleudern", "Spin speed", "LaundryCare.Washer.EnumType.SpinSpeed", spins, spin),
        ), detailed = true,
    )

    private fun dryer(key: String, de: String, en: String) = HcProgram(
        "LaundryCare.Dryer.Program.$key", t(de, en), listOf(
            enumOpt(HcKeys.DRYER_TARGET, "Trockenziel", "Drying target", "LaundryCare.Dryer.EnumType.DryingTarget",
                listOf("IronDry" to ("Bügeltrocken" to "Iron dry"), "CupboardDry" to ("Schranktrocken" to "Cupboard dry"),
                    "CupboardDryPlus" to ("Schrank +" to "Cupboard +"), "ExtraDry" to ("Extratrocken" to "Extra dry")), "CupboardDry"),
        ), detailed = true,
    )

    private fun dish(key: String, de: String, en: String) = HcProgram(
        "Dishcare.Dishwasher.Program.$key", t(de, en), listOf(
            HcOptionDef("Dishcare.Dishwasher.Option.VarioSpeedPlus", t("Zeit sparen", "Speed"), "Boolean", default = b(false)),
            HcOptionDef("Dishcare.Dishwasher.Option.HalfLoad", t("Halbe Beladung", "Half load"), "Boolean", default = b(false)),
            HcOptionDef("Dishcare.Dishwasher.Option.ExtraDry", t("Extra trocken", "Extra dry"), "Boolean", default = b(true)),
            HcOptionDef(HcKeys.START_IN_RELATIVE, t("Startzeit", "Start in"), "Int", "seconds", 0.0, 86_340.0, 60.0, default = n(0)),
        ), detailed = true,
    )

    /** Dauer je Programm in Minuten (nur Simulation). */
    private val minutes = mapOf(
        "Cotton" to 118, "EasyCare" to 85, "Mix" to 75, "DelicatesSilk" to 45, "Wool" to 40, "Super153" to 15,
        "Synthetic" to 70, "Towels" to 120, "Shirts15" to 40,
        "Eco50" to 225, "Auto2" to 135, "Intensiv70" to 150, "Quick45" to 29, "Glas40" to 110, "PreRinse" to 15,
    )

    private val devices: List<Dev> by lazy {
        listOf(
            Dev("SIEMENS-TI9575X1DE-68A40E1F2C11", "Kaffeevollautomat", "Coffee machine", HcType.COFFEE, listOf(
                coffee("Beverage.Ristretto", "Ristretto", "Ristretto", 20, 40, 25),
                coffee("Beverage.Espresso", "Espresso", "Espresso", 35, 60, 40),
                coffee("Beverage.EspressoMacchiato", "Espresso Macchiato", "Espresso macchiato", 40, 70, 50),
                coffee("Beverage.Coffee", "Caffè Crema", "Caffè crema", 80, 250, 125),
                coffee("Beverage.Cappuccino", "Cappuccino", "Cappuccino", 100, 300, 190),
                coffee("CoffeeWorld.FlatWhite", "Flat White", "Flat white", 120, 250, 160),
                coffee("Beverage.LatteMacchiato", "Latte Macchiato", "Latte macchiato", 200, 400, 300),
                coffee("Beverage.CaffeLatte", "Caffè Latte", "Caffè latte", 150, 350, 250),
                coffee("CoffeeWorld.Americano", "Americano", "Americano", 100, 300, 200),
                coffee("Beverage.HotWater", "Heißes Wasser", "Hot water", 50, 500, 250, beans = false),
            )).apply {
                values += mapOf(HcKeys.OPERATION_STATE to op("Ready"), HcKeys.POWER_STATE to s("BSH.Common.EnumType.PowerState.On"), HcKeys.REMOTE_START to b(true))
            },
            Dev("SIEMENS-HB978GXB6-3C1F6A0D9B22", "Backofen", "Oven", HcType.OVEN, listOf(
                oven("HotAir", "Heißluft", "Hot air", 180),
                oven("TopBottomHeating", "Ober-/Unterhitze", "Top/bottom heat", 200),
                oven("PizzaSetting", "Pizzastufe", "Pizza setting", 220),
                oven("GrillLargeArea", "Grill", "Grill", 250, 150, 300),
                oven("Defrost", "Auftauen", "Defrost", 40, 30, 60),
                oven("KeepWarm", "Warmhalten", "Keep warm", 70, 60, 100),
            )).apply {
                values += mapOf(HcKeys.OPERATION_STATE to op("Ready"), HcKeys.POWER_STATE to s("BSH.Common.EnumType.PowerState.Standby"),
                    HcKeys.DOOR_STATE to s("BSH.Common.EnumType.DoorState.Closed"), HcKeys.REMOTE_START to b(true), HcKeys.OVEN_CAVITY to n(21))
            },
            Dev("SIEMENS-WM14VG43-0A7B8C9D1E33", "Waschmaschine", "Washing machine", HcType.WASHER, listOf(
                washer("Cotton", "Baumwolle", "Cotton", "GC40"),
                washer("EasyCare", "Pflegeleicht", "Easy care", "GC40", "RPM1200"),
                washer("Mix", "Mix", "Mix", "GC40", "RPM1200"),
                washer("DelicatesSilk", "Feinwäsche", "Delicates", "GC30", "RPM800", 4),
                washer("Wool", "Wolle", "Wool", "Cold", "RPM800", 3),
                washer("Super153", "Schnell 15′", "Quick 15′", "GC20", "RPM1200", 4),
            )).apply {
                values += mapOf(HcKeys.OPERATION_STATE to op("Run"), HcKeys.POWER_STATE to s("BSH.Common.EnumType.PowerState.On"),
                    HcKeys.DOOR_STATE to s("BSH.Common.EnumType.DoorState.Locked"), HcKeys.REMOTE_START to b(false),
                    HcKeys.WASHER_TEMP to s("LaundryCare.Washer.EnumType.Temperature.GC40"), HcKeys.WASHER_SPIN to s("LaundryCare.Washer.EnumType.SpinSpeed.RPM1400"))
                active = "LaundryCare.Washer.Program.Cotton"
                totalSec = 118 * 60L
                startedMs = clock.millis() - 34 * 60_000L
            },
            Dev("SIEMENS-SN85TX00CE-5E6F7A8B9C44", "Geschirrspüler", "Dishwasher", HcType.DISHWASHER, listOf(
                dish("Eco50", "Eco 50°", "Eco 50°"), dish("Auto2", "Auto 45–65°", "Auto 45–65°"), dish("Intensiv70", "Intensiv 70°", "Intensive 70°"),
                dish("Quick45", "Schnell 45°", "Quick 45°"), dish("Glas40", "Glas 40°", "Glass 40°"), dish("PreRinse", "Vorspülen", "Pre-rinse"),
            )).apply {
                values += mapOf(HcKeys.POWER_STATE to s("BSH.Common.EnumType.PowerState.On"), HcKeys.REMOTE_START to b(true),
                    HcKeys.DOOR_STATE to s("BSH.Common.EnumType.DoorState.Closed"), "Dishcare.Dishwasher.Option.ExtraDry" to b(true))
                events += HcKeys.DISH_RINSE_AID
                val zone = ZoneId.systemDefault()
                val oneAm = LocalDate.now(zone).plusDays(1).atTime(LocalTime.of(1, 0)).atZone(zone).toInstant().toEpochMilli()
                schedule(this, "Dishcare.Dishwasher.Program.Eco50", 245 * 60L, oneAm)
            },
            Dev("BOSCH-WQB246C9-7F8A9B0C1D55", "Trockner", "Dryer", HcType.DRYER, listOf(
                dryer("Cotton", "Baumwolle", "Cotton"), dryer("Synthetic", "Pflegeleicht", "Easy care"), dryer("Mix", "Mix", "Mix"),
                dryer("Towels", "Handtücher", "Towels"), dryer("Shirts15", "Hemden", "Shirts"), dryer("Wool", "Wolle Finish", "Wool finish"),
            )).apply {
                values += mapOf(HcKeys.OPERATION_STATE to op("Finished"), HcKeys.POWER_STATE to s("BSH.Common.EnumType.PowerState.On"),
                    HcKeys.REMOTE_START to b(true), HcKeys.DOOR_STATE to s("BSH.Common.EnumType.DoorState.Closed"),
                    HcKeys.DRYER_TARGET to s("LaundryCare.Dryer.EnumType.DryingTarget.CupboardDry"), HcKeys.PROGRESS to n(100))
            },
        )
    }

    private fun schedule(d: Dev, key: String, totalSec: Long, atMs: Long) {
        d.active = key
        d.totalSec = totalSec
        d.delayUntilMs = atMs
        d.values[HcKeys.OPERATION_STATE] = op("DelayedStart")
        d.values[HcKeys.START_IN_RELATIVE] = n(max(0L, (atMs - clock.millis()) / 1000))
        d.values[HcKeys.REMAINING] = n(totalSec)
        d.values[HcKeys.PROGRESS] = n(0)
    }

    private fun dev(haId: String) = devices.firstOrNull { it.haId == haId } ?: throw HcException(HcFailure.OFFLINE)

    // --- REST -----------------------------------------------------------------------------

    override suspend fun appliances() = devices.map { HcAppliance(it.haId, t(it.nameDe, it.nameEn), if (it.haId.startsWith("BOSCH")) "Bosch" else "Siemens", it.type, true) }
    override suspend fun status(haId: String) = dev(haId).let { d -> refreshTiming(d); d.values.filterKeys { "Status" in it || "Option" in it }.map { HcItem(it.key, it.value) } }
    override suspend fun settings(haId: String) = dev(haId).values.filterKeys { "Setting" in it }.map { HcItem(it.key, it.value) }
    override suspend fun activeProgram(haId: String) = dev(haId).let { d -> d.active?.let { it to d.values.filterKeys { k -> "Option" in k }.map { e -> HcItem(e.key, e.value) } } }
    override suspend fun availablePrograms(haId: String) = dev(haId).programs.map { it.copy(options = emptyList(), detailed = false) }
    override suspend fun allPrograms(haId: String) = availablePrograms(haId)
    override suspend fun programDetail(haId: String, key: String) = dev(haId).programs.firstOrNull { it.key == key }
    override suspend fun powerValues(haId: String): List<String> = when (dev(haId).type) {
        HcType.DRYER -> listOf("BSH.Common.EnumType.PowerState.On")
        HcType.OVEN, HcType.COFFEE -> listOf("BSH.Common.EnumType.PowerState.On", "BSH.Common.EnumType.PowerState.Standby")
        else -> listOf("BSH.Common.EnumType.PowerState.On", "BSH.Common.EnumType.PowerState.Off")
    }
    override suspend fun commands(haId: String) = if (dev(haId).type in setOf(HcType.WASHER, HcType.DRYER, HcType.DISHWASHER)) setOf(HcKeys.PAUSE, HcKeys.RESUME) else emptySet()

    override suspend fun start(haId: String, key: String, options: Map<String, JsonPrimitive>, units: Map<String, String>) {
        delay(300)
        @Suppress("NAME_SHADOWING") val options = selectedOptions.remove(haId).orEmpty() + options
        val d = dev(haId)
        if (d.values[HcKeys.REMOTE_START]?.contentOrNull != "true") throw HcException(HcFailure.REMOTE_START_OFF)
        d.values.putAll(options)
        d.events.remove(HcKeys.PROGRAM_FINISHED)
        val rel = options[HcKeys.START_IN_RELATIVE]?.doubleOrNull?.toLong() ?: 0
        val total = when (d.type) {
            HcType.COFFEE -> 25L
            HcType.OVEN -> options[HcKeys.DURATION]?.doubleOrNull?.toLong() ?: 0
            HcType.DISHWASHER -> {
                val base = (minutes[enumTail(key)] ?: 120) * 60L
                val speed = options["Dishcare.Dishwasher.Option.VarioSpeedPlus"]?.contentOrNull == "true"
                val dry = options["Dishcare.Dishwasher.Option.ExtraDry"]?.contentOrNull == "true"
                (if (speed) base * 55 / 100 else base) + (if (dry) 20 * 60 else 0)
            }
            else -> (minutes[enumTail(key)] ?: 90) * 60L
        }
        if (rel > 0) {
            schedule(d, key, total, clock.millis() + rel * 1000)
        } else {
            d.active = key; d.totalSec = total; d.startedMs = clock.millis(); d.pausedElapsed = null; d.delayUntilMs = 0
            d.values[HcKeys.OPERATION_STATE] = op("Run")
            d.values[HcKeys.POWER_STATE] = s("BSH.Common.EnumType.PowerState.On")
            if (d.type != HcType.COFFEE && d.type != HcType.OVEN) d.values[HcKeys.DOOR_STATE] = s("BSH.Common.EnumType.DoorState.Locked")
            refreshTiming(d)
        }
        notify(d, HcKeys.ACTIVE_PROGRAM to s(key), HcKeys.OPERATION_STATE to d.values[HcKeys.OPERATION_STATE]!!, *options.toList().toTypedArray(), *timing(d))
    }

    override suspend fun stop(haId: String) {
        delay(200)
        val d = dev(haId)
        finish(d, aborted = true)
    }

    private val selectedOptions = mutableMapOf<String, MutableMap<String, JsonPrimitive>>()

    override suspend fun select(haId: String, key: String) { selectedOptions[haId] = mutableMapOf() }

    override suspend fun setSelectedOption(haId: String, key: String, value: JsonPrimitive, unit: String?) {
        selectedOptions.getOrPut(haId) { mutableMapOf() }[key] = value
    }

    override suspend fun command(haId: String, key: String) {
        val d = dev(haId)
        when (key) {
            HcKeys.PAUSE -> { d.pausedElapsed = (clock.millis() - d.startedMs) / 1000; d.values[HcKeys.OPERATION_STATE] = op("Pause") }
            HcKeys.RESUME -> { d.startedMs = clock.millis() - (d.pausedElapsed ?: 0) * 1000; d.pausedElapsed = null; d.values[HcKeys.OPERATION_STATE] = op("Run") }
            else -> throw HcException(HcFailure.REJECTED)
        }
        notify(d, HcKeys.OPERATION_STATE to d.values[HcKeys.OPERATION_STATE]!!, *timing(d))
    }

    override suspend fun setSetting(haId: String, key: String, value: JsonPrimitive) {
        val d = dev(haId)
        d.values[key] = value
        notify(d, key to value)
        if (key == HcKeys.POWER_STATE && enumTail(value.contentOrNull) == "On" && d.values[HcKeys.OPERATION_STATE]?.contentOrNull?.endsWith("Inactive") == true) {
            d.values[HcKeys.OPERATION_STATE] = op("Ready")
            notify(d, HcKeys.OPERATION_STATE to op("Ready"))
        }
    }

    // --- Simulation -----------------------------------------------------------------------

    private fun timing(d: Dev): Array<Pair<String, JsonPrimitive>> = listOfNotNull(
        d.values[HcKeys.REMAINING]?.let { HcKeys.REMAINING to it },
        d.values[HcKeys.PROGRESS]?.let { HcKeys.PROGRESS to it },
        d.values[HcKeys.START_IN_RELATIVE]?.let { HcKeys.START_IN_RELATIVE to it },
    ).toTypedArray()

    private fun refreshTiming(d: Dev) {
        val state = enumTail(d.values[HcKeys.OPERATION_STATE]?.contentOrNull)
        if (state == "DelayedStart") {
            d.values[HcKeys.START_IN_RELATIVE] = n(max(0L, (d.delayUntilMs - clock.millis()) / 1000))
            return
        }
        if ((state != "Run" && state != "Pause") || d.totalSec <= 0) return
        val elapsed = d.pausedElapsed ?: ((clock.millis() - d.startedMs) / 1000)
        d.values[HcKeys.REMAINING] = n(max(0L, d.totalSec - elapsed))
        d.values[HcKeys.PROGRESS] = n(min(100L, elapsed * 100 / d.totalSec))
    }

    private fun notify(d: Dev, vararg items: Pair<String, JsonPrimitive>) {
        bus.tryEmit(HcEvent("NOTIFY", d.haId, items.map { HcItem(it.first, it.second) }))
    }

    private fun event(d: Dev, key: String, on: Boolean) {
        if (on) d.events += key else d.events -= key
        bus.tryEmit(HcEvent("EVENT", d.haId, listOf(HcItem(key, if (on) present else off))))
    }

    private fun finish(d: Dev, aborted: Boolean) {
        d.active = null; d.pausedElapsed = null; d.delayUntilMs = 0; d.totalSec = 0; d.finishedMs = clock.millis()
        d.values.remove(HcKeys.REMAINING); d.values.remove(HcKeys.START_IN_RELATIVE)
        val next = if (aborted || d.type == HcType.OVEN) "Ready" else "Finished"
        d.values[HcKeys.OPERATION_STATE] = op(next)
        d.values[HcKeys.PROGRESS] = n(if (aborted) 0 else 100)
        if (d.type != HcType.COFFEE && d.type != HcType.OVEN) d.values[HcKeys.DOOR_STATE] = s("BSH.Common.EnumType.DoorState.Closed")
        bus.tryEmit(HcEvent("STATUS", d.haId, listOf(HcItem(HcKeys.OPERATION_STATE, op(next)), HcItem(HcKeys.DOOR_STATE, d.values[HcKeys.DOOR_STATE] ?: s("")))))
        notify(d, HcKeys.ACTIVE_PROGRAM to s(""), HcKeys.PROGRESS to d.values[HcKeys.PROGRESS]!!)
        if (!aborted) event(d, HcKeys.PROGRAM_FINISHED, true)
    }

    private suspend fun ProducerScope<HcEvent>.tick() {
        var ticks = 0
        while (true) {
            delay(1000)
            ticks++
            val now = clock.millis()
            for (d in devices) {
                val state = enumTail(d.values[HcKeys.OPERATION_STATE]?.contentOrNull)
                when {
                    state == "DelayedStart" && now >= d.delayUntilMs -> {
                        d.startedMs = d.delayUntilMs; d.delayUntilMs = 0
                        d.values[HcKeys.OPERATION_STATE] = op("Run")
                        d.values.remove(HcKeys.START_IN_RELATIVE)
                        refreshTiming(d)
                        notify(d, HcKeys.OPERATION_STATE to op("Run"), *timing(d))
                    }
                    state == "Run" && d.type == HcType.OVEN -> {
                        val target = d.values[HcKeys.OVEN_SETPOINT]?.doubleOrNull ?: 180.0
                        val before = d.cavity
                        d.cavity = min(target, d.cavity + max(1.5, (target - d.cavity) * 0.06))
                        if (ticks % 2 == 0 || d.cavity >= target) bus.tryEmit(HcEvent("STATUS", d.haId, listOf(HcItem(HcKeys.OVEN_CAVITY, n(Math.round(d.cavity))))))
                        if (before < target - 0.5 && d.cavity >= target - 0.5) event(d, HcKeys.OVEN_PREHEAT_DONE, true)
                        if (d.totalSec > 0) {
                            refreshTiming(d)
                            if (ticks % 10 == 0) notify(d, *timing(d))
                            if ((now - d.startedMs) / 1000 >= d.totalSec) { finish(d, aborted = false); d.events.remove(HcKeys.OVEN_PREHEAT_DONE) }
                        }
                    }
                    state == "Run" -> {
                        refreshTiming(d)
                        val elapsed = (now - d.startedMs) / 1000
                        if (elapsed >= d.totalSec) finish(d, aborted = false)
                        else if (d.type == HcType.COFFEE || ticks % 20 == 0) notify(d, *timing(d))
                    }
                    d.type == HcType.OVEN && state != "Run" && d.cavity > 21.5 -> {
                        d.cavity = max(21.0, d.cavity - 1.0)
                        if (ticks % 5 == 0) bus.tryEmit(HcEvent("STATUS", d.haId, listOf(HcItem(HcKeys.OVEN_CAVITY, n(Math.round(d.cavity))))))
                        if (HcKeys.OVEN_PREHEAT_DONE in d.events) event(d, HcKeys.OVEN_PREHEAT_DONE, false)
                    }
                    // Kaffee: nach „Fertig“ wieder bereit
                    state == "Finished" && d.type == HcType.COFFEE && now - d.finishedMs > 5000 -> {
                        d.values[HcKeys.OPERATION_STATE] = op("Ready")
                        bus.tryEmit(HcEvent("STATUS", d.haId, listOf(HcItem(HcKeys.OPERATION_STATE, op("Ready")))))
                        event(d, HcKeys.PROGRAM_FINISHED, false)
                    }
                }
            }
            if (ticks % 50 == 0) send(HcEvent("KEEP-ALIVE", null))
        }
    }

    override fun events(): Flow<HcEvent> = channelFlow {
        send(HcEvent(HcCloudBackend.START, null))
        launch { bus.collect { send(it) } }
        // Hinweise kennt die Cloud nur aus dem Strom – nach dem Verbinden wie eine echte Maschine melden
        delay(1500)
        devices.filter { it.events.isNotEmpty() }.forEach { d -> send(HcEvent("EVENT", d.haId, d.events.map { HcItem(it, present) })) }
        tick()
    }
}
