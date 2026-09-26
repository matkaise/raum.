package app.raum.matter.chip

import app.raum.domain.models.BatteryCapability
import app.raum.domain.models.Capability
import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.CoverMovement
import app.raum.domain.models.DeviceChannel
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.DeviceNetwork
import app.raum.domain.models.NetworkTransport
import app.raum.domain.models.ThreadRole
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.LightCapability
import app.raum.domain.models.LightColorMode
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.RgbColor
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.UnknownCapability
import app.raum.matter.controller.AdminFabric
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Ein Attribut an einem Endpunkt. */
data class AttrPath(val endpoint: Int, val cluster: Long, val attribute: Long)

/** Zuletzt bekannte Attributwerte eines Nodes (aus Lesen/Abo, dekodiert mit [TlvReader]). */
class NodeData(val values: Map<AttrPath, Any?>) {
    operator fun get(endpoint: Int, cluster: Long, attribute: Long): Any? = values[AttrPath(endpoint, cluster, attribute)]
    fun has(endpoint: Int, cluster: Long, attribute: Long) = AttrPath(endpoint, cluster, attribute) in values

    fun endpointsWith(cluster: Long): List<Int> =
        values.keys.asSequence().filter { it.cluster == cluster }.map { it.endpoint }.distinct().sorted().toList()

    fun deviceTypes(endpoint: Int): List<Long> =
        (this[endpoint, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST] as? List<*>).orEmpty().mapNotNull { (it as? TlvStruct)?.long(0) }

    fun allDeviceTypes(): List<Long> = values.keys.map { it.endpoint }.distinct().sorted().flatMap(::deviceTypes).distinct()

    fun merge(update: Map<AttrPath, Any?>) = NodeData(values + update)
}

/** Was an ein Gerät geschickt wird. Felder/Werte sind TLV-kodiert. */
sealed interface MatterAction {
    val endpoint: Int
    data class Invoke(override val endpoint: Int, val cluster: Long, val command: Long, val fields: ByteArray) : MatterAction
    data class Write(override val endpoint: Int, val cluster: Long, val attribute: Long, val value: ByteArray) : MatterAction
}

data class NodeInfo(val vendorName: String?, val productName: String?, val nodeLabel: String?, val vendorId: Long?, val productId: Long?)

/**
 * Übersetzt zwischen Matter (Endpunkte, Cluster, Attribute) und raum. (Capabilities, DeviceCommands) – Spez. 5.4.
 * Rein funktional, ohne Matter-SDK: vollständig per Unit-Test prüfbar.
 */
object ClusterMapper {

    /** Cluster, die raum. abonniert (Wildcard-Endpunkt, alle Attribute). */
    val SUBSCRIBED = listOf(
        Cluster.ON_OFF, Cluster.LEVEL_CONTROL, Cluster.COLOR_CONTROL, Cluster.THERMOSTAT, Cluster.WINDOW_COVERING,
        Cluster.BOOLEAN_STATE, Cluster.OCCUPANCY_SENSING, Cluster.TEMPERATURE_MEASUREMENT, Cluster.RELATIVE_HUMIDITY,
        Cluster.POWER_SOURCE, Cluster.ELECTRICAL_POWER_MEASUREMENT, Cluster.ELECTRICAL_ENERGY_MEASUREMENT,
    )

    /** Einmal gelesen nach dem Koppeln bzw. beim Start. */
    val READ_ONCE = listOf(Cluster.DESCRIPTOR, Cluster.BASIC_INFORMATION)

    /** Einzelne Attribute auf Endpunkt 0: Verbindungsart und Thread-Diagnose (nicht die großen Tabellen). */
    val NETWORK_PATHS = listOf(
        Cluster.NETWORK_COMMISSIONING to Attr.FEATURE_MAP,
        Cluster.THREAD_NETWORK_DIAGNOSTICS to Attr.THREAD_CHANNEL,
        Cluster.THREAD_NETWORK_DIAGNOSTICS to Attr.ROUTING_ROLE,
        Cluster.THREAD_NETWORK_DIAGNOSTICS to Attr.THREAD_NETWORK_NAME,
        Cluster.THREAD_NETWORK_DIAGNOSTICS to Attr.EXTENDED_PAN_ID,
    )

    /** Ein Abo-Pfad: Endpunkt null = alle Endpunkte, Attribut null = alle Attribute des Clusters. */
    data class SubscriptionPath(val endpoint: Int?, val cluster: Long, val attribute: Long?)

    /**
     * Was raum. bei einem Gerät abonniert. Kennt raum. die Cluster des Geräts schon (Descriptor › ServerList aus dem
     * Zwischenspeicher), nur diese – Geräte haben nur wenige Abo-Pfade frei (Matter garantiert 3 je Abo), und
     * Pfade auf fehlende Cluster kosten Kapazität und liefern Fehler. Sonst (erste Kopplung) alles Relevante.
     */
    fun subscriptionPaths(n: NodeData?): List<SubscriptionPath> {
        val present = n?.let(::serverClusters)
        val clusters = (SUBSCRIBED.filter { present == null || it in present.all }) + READ_ONCE
        val network = NETWORK_PATHS.filter { (cluster, _) -> present == null || cluster in present.root }
        return clusters.map { SubscriptionPath(null, it, null) } +
            listOf(Attr.FABRICS, Attr.CURRENT_FABRIC_INDEX).map { SubscriptionPath(0, Cluster.OPERATIONAL_CREDENTIALS, it) } +
            network.map { (cluster, attr) -> SubscriptionPath(0, cluster, attr) }
    }

    private class Present(val all: Set<Long>, val root: Set<Long>)

    /** Cluster laut ServerList aller Endpunkte; null, wenn (noch) keine bekannt ist. */
    private fun serverClusters(n: NodeData): Present? {
        val lists = n.endpointsWith(Cluster.DESCRIPTOR).mapNotNull { ep ->
            (n[ep, Cluster.DESCRIPTOR, Attr.SERVER_LIST] as? List<*>)?.let { ep to it.filterIsInstance<Long>().toSet() }
        }
        if (lists.isEmpty()) return null
        return Present(lists.flatMap { it.second }.toSet(), lists.firstOrNull { it.first == 0 }?.second.orEmpty())
    }

    /** Verbindungsart (NetworkCommissioning-Features) und Thread-Zustand; null, solange nichts bekannt ist. */
    fun network(n: NodeData): DeviceNetwork? {
        val features = n[0, Cluster.NETWORK_COMMISSIONING, Attr.FEATURE_MAP] as? Long
        val hasThreadDiag = n.has(0, Cluster.THREAD_NETWORK_DIAGNOSTICS, Attr.ROUTING_ROLE)
        val transport = when {
            features != null && features and 0x2L != 0L -> NetworkTransport.THREAD
            features != null && features and 0x1L != 0L -> NetworkTransport.WIFI
            features != null && features and 0x4L != 0L -> NetworkTransport.ETHERNET
            hasThreadDiag -> NetworkTransport.THREAD
            else -> return null
        }
        if (transport != NetworkTransport.THREAD) return DeviceNetwork(transport)
        return DeviceNetwork(
            transport = transport,
            threadRole = when (n[0, Cluster.THREAD_NETWORK_DIAGNOSTICS, Attr.ROUTING_ROLE] as? Long) {
                1L -> ThreadRole.DETACHED
                2L -> ThreadRole.SLEEPY_END_DEVICE
                3L -> ThreadRole.END_DEVICE
                4L -> ThreadRole.REED
                5L -> ThreadRole.ROUTER
                6L -> ThreadRole.LEADER
                else -> null
            },
            threadNetworkName = (n[0, Cluster.THREAD_NETWORK_DIAGNOSTICS, Attr.THREAD_NETWORK_NAME] as? String)?.takeIf { it.isNotBlank() },
            threadExtPanId = (n[0, Cluster.THREAD_NETWORK_DIAGNOSTICS, Attr.EXTENDED_PAN_ID] as? Long)?.let { "%016x".format(it) },
            threadChannel = (n[0, Cluster.THREAD_NETWORK_DIAGNOSTICS, Attr.THREAD_CHANNEL] as? Long)?.toInt(),
        )
    }

    private const val TRANSITION = 5L // Zehntelsekunden – kurz, aber nicht hart

    // --- Lesen ------------------------------------------------------------------------------

    fun info(n: NodeData) = NodeInfo(
        vendorName = n[0, Cluster.BASIC_INFORMATION, Attr.VENDOR_NAME] as? String,
        productName = n[0, Cluster.BASIC_INFORMATION, Attr.PRODUCT_NAME] as? String,
        nodeLabel = (n[0, Cluster.BASIC_INFORMATION, Attr.NODE_LABEL] as? String)?.takeIf { it.isNotBlank() },
        vendorId = n[0, Cluster.BASIC_INFORMATION, Attr.VENDOR_ID] as? Long,
        productId = n[0, Cluster.BASIC_INFORMATION, Attr.PRODUCT_ID] as? Long,
    )

    /**
     * @param primary Endpunkt des Hauptkanals (fest gebunden, siehe [PrimaryEndpoints]); fehlt er inzwischen, hat das
     *   Hauptgerät keine Schaltfunktion mehr – es springt nicht auf einen anderen Endpunkt.
     */
    fun capabilities(n: NodeData, primary: Int? = defaultPrimaryEndpoint(n)): List<Capability> {
        val caps = mutableListOf<Capability>()
        primary?.takeIf { it in n.endpointsWith(Cluster.ON_OFF) }?.let { ep ->
            caps += if (isLight(n, ep)) light(n, ep)
                else switch(n, ep, anyMeter = n.endpointsWith(Cluster.ON_OFF).none { it != ep && isChannel(n, it) })
        }
        thermostat(n)?.let(caps::add)
        cover(n)?.let(caps::add)
        contact(n)?.let(caps::add)
        n.endpointsWith(Cluster.OCCUPANCY_SENSING).firstOrNull()?.let { ep ->
            (n[ep, Cluster.OCCUPANCY_SENSING, Attr.VALUE] as? Long)?.let { caps += OccupancyCapability(it and 1L == 1L) }
        }
        n.endpointsWith(Cluster.TEMPERATURE_MEASUREMENT).firstOrNull()?.let { ep ->
            (n[ep, Cluster.TEMPERATURE_MEASUREMENT, Attr.VALUE] as? Long)?.let { caps += TemperatureSensorCapability(it / 100.0) }
        }
        n.endpointsWith(Cluster.RELATIVE_HUMIDITY).firstOrNull()?.let { ep ->
            (n[ep, Cluster.RELATIVE_HUMIDITY, Attr.VALUE] as? Long)?.let { caps += HumiditySensorCapability(it / 100.0) }
        }
        // Batterie: halbe Prozent (0–200); netzbetriebene Geräte haben keinen Wert
        n.endpointsWith(Cluster.POWER_SOURCE).firstNotNullOfOrNull { ep -> n[ep, Cluster.POWER_SOURCE, Attr.BAT_PERCENT_REMAINING] as? Long }
            ?.let { caps += BatteryCapability((it / 2.0).roundToInt().coerceIn(0, 100)) }

        if (caps.none { it !is BatteryCapability }) {
            // Nichts Steuerbares erkannt: nur lesen (Spez. 6.3)
            caps += UnknownCapability(n.allDeviceTypes().filterNot { it in DeviceType.UTILITY })
        }
        return caps
    }

    /**
     * Weitere unabhängig schaltbare Kanäle neben dem Hauptkanal: jeder weitere On/Off-Endpunkt mit Licht- oder
     * Steckdosen-/Schaltaktor-Gerätetyp (Mehrkanal-Relais, Leuchte + Steckdose, Bridge mit mehreren Leuchten).
     * Endpunkte anderer Gerätetypen (z. B. Klimagerät mit On/Off) bleiben Teil des Hauptgeräts.
     */
    fun channels(n: NodeData, primary: Int? = defaultPrimaryEndpoint(n)): List<DeviceChannel> {
        if (primary == null) return emptyList()
        return n.endpointsWith(Cluster.ON_OFF).filter { it != primary && isChannel(n, it) }.map { ep ->
            val cap: Capability = if (isLight(n, ep)) light(n, ep) else switch(n, ep, anyMeter = false)
            DeviceChannel(ep, listOf(cap))
        }
    }

    private fun isLight(n: NodeData, ep: Int) = n.deviceTypes(ep).any { it in DeviceType.LIGHTS }
    private fun isChannel(n: NodeData, ep: Int) = n.deviceTypes(ep).any { it in DeviceType.LIGHTS || it in DeviceType.PLUGS }

    /** Vorschlag für den Hauptkanal: erste Leuchte, sonst erster anderer On/Off-Endpunkt. Wird beim ersten Mal gebunden. */
    fun defaultPrimaryEndpoint(n: NodeData): Int? = lightEndpoint(n) ?: switchEndpoint(n)

    /** Gerätetypen aller On/Off-Endpunkte bekannt – erst dann ist [defaultPrimaryEndpoint] endgültig. */
    fun onOffTypesKnown(n: NodeData): Boolean =
        n.endpointsWith(Cluster.ON_OFF).all { n.has(it, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) }

    private fun lightEndpoint(n: NodeData): Int? = n.endpointsWith(Cluster.ON_OFF).firstOrNull { ep ->
        n.deviceTypes(ep).any { it in DeviceType.LIGHTS }
    }

    private fun switchEndpoint(n: NodeData): Int? = n.endpointsWith(Cluster.ON_OFF).firstOrNull { ep ->
        n.deviceTypes(ep).none { it in DeviceType.LIGHTS }
    }

    private fun light(n: NodeData, ep: Int): LightCapability {
        val on = n[ep, Cluster.ON_OFF, Attr.VALUE] as? Boolean ?: false
        val level = if (n.has(ep, Cluster.LEVEL_CONTROL, Attr.VALUE)) {
            // Stufe 1–254; die kleinste Stufe ist noch Licht – nicht als 0 % anzeigen
            val raw = n[ep, Cluster.LEVEL_CONTROL, Attr.VALUE] as? Long ?: 0L
            (raw * 100.0 / 254).roundToInt().coerceIn(if (raw > 0) 1 else 0, 100)
        } else null
        val caps = n[ep, Cluster.COLOR_CONTROL, Attr.COLOR_CAPABILITIES] as? Long ?: 0L
        val hasCt = caps and 0x10L != 0L && n.has(ep, Cluster.COLOR_CONTROL, Attr.COLOR_TEMPERATURE_MIREDS)
        val hasHs = caps and 0x01L != 0L
        val mireds = n[ep, Cluster.COLOR_CONTROL, Attr.COLOR_TEMPERATURE_MIREDS] as? Long
        val minM = n[ep, Cluster.COLOR_CONTROL, Attr.COLOR_TEMP_MIN_MIREDS] as? Long
        val maxM = n[ep, Cluster.COLOR_CONTROL, Attr.COLOR_TEMP_MAX_MIREDS] as? Long
        val rgb = if (hasHs) hsvToRgb(
            (n[ep, Cluster.COLOR_CONTROL, Attr.CURRENT_HUE] as? Long ?: 0L) * 360.0 / 254,
            (n[ep, Cluster.COLOR_CONTROL, Attr.CURRENT_SATURATION] as? Long ?: 0L) / 254.0,
        ) else null
        val mode = when {
            !hasCt && !hasHs -> null
            (n[ep, Cluster.COLOR_CONTROL, Attr.COLOR_MODE] as? Long) == 2L || !hasHs -> LightColorMode.TEMPERATURE
            else -> LightColorMode.COLOR
        }
        return LightCapability(
            isOn = on,
            brightnessPercent = level,
            colorTemperatureKelvin = if (hasCt && mireds != null && mireds > 0) (1_000_000 / mireds).toInt() else null,
            colorTemperatureRange = if (hasCt && minM != null && maxM != null && minM > 0 && maxM > 0) (1_000_000 / maxM).toInt()..(1_000_000 / minM).toInt() else null,
            rgbColor = rgb,
            colorMode = mode,
        )
    }

    /** @param anyMeter Messwerte auch von anderen Endpunkten übernehmen (nur wenn der Node keinen weiteren Kanal hat). */
    private fun switch(n: NodeData, ep: Int, anyMeter: Boolean): SwitchCapability {
        fun meters(cluster: Long) = if (n.endpointsWith(cluster).contains(ep)) listOf(ep) else if (anyMeter) n.endpointsWith(cluster) else emptyList()
        val power = meters(Cluster.ELECTRICAL_POWER_MEASUREMENT).firstNotNullOfOrNull {
            n[it, Cluster.ELECTRICAL_POWER_MEASUREMENT, Attr.ACTIVE_POWER] as? Long
        }
        val energy = meters(Cluster.ELECTRICAL_ENERGY_MEASUREMENT).firstNotNullOfOrNull {
            (n[it, Cluster.ELECTRICAL_ENERGY_MEASUREMENT, Attr.CUMULATIVE_ENERGY_IMPORTED] as? TlvStruct)?.long(0)
        }
        return SwitchCapability(
            isOn = n[ep, Cluster.ON_OFF, Attr.VALUE] as? Boolean ?: false,
            powerWatts = power?.let { it / 1000.0 },         // mW
            energyKwh = energy?.let { it / 1_000_000.0 },    // mWh
        )
    }

    private fun thermostat(n: NodeData): ThermostatCapability? {
        val ep = n.endpointsWith(Cluster.THERMOSTAT).firstOrNull() ?: return null
        fun c(attr: Long) = (n[ep, Cluster.THERMOSTAT, attr] as? Long)?.let { it / 100.0 }
        val mode = when (n[ep, Cluster.THERMOSTAT, Attr.SYSTEM_MODE] as? Long) {
            0L -> ThermostatMode.OFF; 1L -> ThermostatMode.AUTO; 3L -> ThermostatMode.COOL; else -> ThermostatMode.HEAT
        }
        val supported = when (n[ep, Cluster.THERMOSTAT, Attr.CONTROL_SEQUENCE] as? Long) {
            0L, 1L -> setOf(ThermostatMode.OFF, ThermostatMode.COOL)
            4L, 5L -> setOf(ThermostatMode.OFF, ThermostatMode.HEAT, ThermostatMode.COOL, ThermostatMode.AUTO)
            else -> setOf(ThermostatMode.OFF, ThermostatMode.HEAT)
        }
        val target = if (mode == ThermostatMode.COOL) c(Attr.OCCUPIED_COOLING_SETPOINT) else c(Attr.OCCUPIED_HEATING_SETPOINT)
        return ThermostatCapability(
            currentCelsius = c(Attr.LOCAL_TEMPERATURE),
            targetCelsius = target ?: 20.0,
            mode = mode,
            supportedModes = supported,
            minTargetCelsius = c(Attr.MIN_HEAT_LIMIT) ?: c(Attr.ABS_MIN_HEAT) ?: 5.0,
            maxTargetCelsius = c(Attr.MAX_HEAT_LIMIT) ?: c(Attr.ABS_MAX_HEAT) ?: 30.0,
        )
    }

    private fun cover(n: NodeData): CoverCapability? {
        val ep = n.endpointsWith(Cluster.WINDOW_COVERING).firstOrNull() ?: return null
        // Matter: 0 = offen, 10000 = geschlossen – raum.: Prozent offen
        val closed100ths = n[ep, Cluster.WINDOW_COVERING, Attr.CURRENT_LIFT_PERCENT_100THS] as? Long ?: 0L
        val movement = when ((n[ep, Cluster.WINDOW_COVERING, Attr.OPERATIONAL_STATUS] as? Long ?: 0L) and 0b11) {
            1L -> CoverMovement.OPENING; 2L -> CoverMovement.CLOSING; else -> CoverMovement.STOPPED
        }
        return CoverCapability((100 - closed100ths / 100.0).roundToInt().coerceIn(0, 100), movement)
    }

    private fun contact(n: NodeData): ContactSensorCapability? {
        val ep = n.endpointsWith(Cluster.BOOLEAN_STATE).firstOrNull { DeviceType.CONTACT_SENSOR in n.deviceTypes(it) } ?: return null
        // StateValue TRUE = Kontakt geschlossen
        return ContactSensorCapability(isOpen = n[ep, Cluster.BOOLEAN_STATE, Attr.VALUE] != true)
    }

    /** Fabric-Liste (OperationalCredentials.Fabrics) → verbundene Apps; eigene per CurrentFabricIndex. */
    fun admins(n: NodeData): List<AdminFabric> {
        val own = n[0, Cluster.OPERATIONAL_CREDENTIALS, Attr.CURRENT_FABRIC_INDEX] as? Long
        return (n[0, Cluster.OPERATIONAL_CREDENTIALS, Attr.FABRICS] as? List<*>).orEmpty().mapNotNull { e ->
            val s = e as? TlvStruct ?: return@mapNotNull null
            val index = s.long(0xFE) ?: return@mapNotNull null
            AdminFabric(index.toInt(), (s.long(2) ?: 0L).toInt(), s.string(5).orEmpty(), own = index == own)
        }.sortedBy { !it.own }
    }

    // --- Schreiben --------------------------------------------------------------------------

    /**
     * null = vom Gerät nicht unterstützt.
     * @param channel Endpunkt eines weiteren Kanals ([channels]); null = Hauptkanal. Ein Kanal kann nur schalten,
     *   dimmen und – bei Leuchten – Farbe/Farbtemperatur.
     * @param primary gebundener Endpunkt des Hauptkanals (wie bei [capabilities]).
     */
    fun actions(cmd: DeviceCommand, n: NodeData, channel: Int? = null, primary: Int? = defaultPrimaryEndpoint(n)): List<MatterAction>? {
        if (channel != null && channel !in channels(n, primary).map { it.endpoint }) return null
        val onOffEp = channel ?: primary?.takeIf { it in n.endpointsWith(Cluster.ON_OFF) }
        val lightEp = onOffEp?.takeIf { isLight(n, it) }
        if (channel != null && cmd !is DeviceCommand.SetOn && cmd !is DeviceCommand.SetBrightness &&
            cmd !is DeviceCommand.SetColorTemperature && cmd !is DeviceCommand.SetColor) return null
        return when (cmd) {
            is DeviceCommand.SetOn -> onOffEp?.let { listOf(MatterAction.Invoke(it, Cluster.ON_OFF, if (cmd.on) Cmd.ON else Cmd.OFF, TlvWriter.empty())) }
            is DeviceCommand.SetBrightness -> {
                val ep = onOffEp?.takeIf { n.endpointsWith(Cluster.LEVEL_CONTROL).contains(it) } ?: return null
                if (cmd.percent <= 0) return listOf(MatterAction.Invoke(ep, Cluster.ON_OFF, Cmd.OFF, TlvWriter.empty()))
                val level = (cmd.percent * 254 / 100.0).roundToLong().coerceIn(1, 254)
                listOf(MatterAction.Invoke(ep, Cluster.LEVEL_CONTROL, Cmd.MOVE_TO_LEVEL_WITH_ON_OFF,
                    TlvWriter().startStructure().uint(0, level).uint(1, TRANSITION).uint(2, 0).uint(3, 0).endContainer().bytes()))
            }
            is DeviceCommand.SetColorTemperature -> {
                val ep = lightEp?.takeIf { n.has(it, Cluster.COLOR_CONTROL, Attr.COLOR_TEMPERATURE_MIREDS) } ?: return null
                val minM = n[ep, Cluster.COLOR_CONTROL, Attr.COLOR_TEMP_MIN_MIREDS] as? Long ?: 153
                val maxM = n[ep, Cluster.COLOR_CONTROL, Attr.COLOR_TEMP_MAX_MIREDS] as? Long ?: 500
                val mireds = (1_000_000.0 / cmd.kelvin).roundToLong().coerceIn(minM, maxM)
                listOf(MatterAction.Invoke(ep, Cluster.COLOR_CONTROL, Cmd.MOVE_TO_COLOR_TEMPERATURE,
                    TlvWriter().startStructure().uint(0, mireds).uint(1, TRANSITION).uint(2, 0).uint(3, 0).endContainer().bytes()))
            }
            is DeviceCommand.SetColor -> {
                val ep = lightEp?.takeIf { ((n[it, Cluster.COLOR_CONTROL, Attr.COLOR_CAPABILITIES] as? Long ?: 0L) and 1L) != 0L } ?: return null
                val (h, s) = rgbToHueSat(cmd.color)
                listOf(MatterAction.Invoke(ep, Cluster.COLOR_CONTROL, Cmd.MOVE_TO_HUE_AND_SATURATION,
                    TlvWriter().startStructure().uint(0, h).uint(1, s).uint(2, TRANSITION).uint(3, 0).uint(4, 0).endContainer().bytes()))
            }
            is DeviceCommand.SetTargetTemperature -> {
                val ep = n.endpointsWith(Cluster.THERMOSTAT).firstOrNull() ?: return null
                val cooling = cmd.mode?.let { it == ThermostatMode.COOL } ?: ((n[ep, Cluster.THERMOSTAT, Attr.SYSTEM_MODE] as? Long) == 3L)
                listOf(MatterAction.Write(ep, Cluster.THERMOSTAT,
                    if (cooling) Attr.OCCUPIED_COOLING_SETPOINT else Attr.OCCUPIED_HEATING_SETPOINT,
                    TlvWriter().int(null, (cmd.celsius * 100).roundToLong()).bytes()))
            }
            is DeviceCommand.SetThermostatMode -> {
                val ep = n.endpointsWith(Cluster.THERMOSTAT).firstOrNull() ?: return null
                val v = when (cmd.mode) { ThermostatMode.OFF -> 0L; ThermostatMode.AUTO -> 1L; ThermostatMode.COOL -> 3L; ThermostatMode.HEAT -> 4L }
                listOf(MatterAction.Write(ep, Cluster.THERMOSTAT, Attr.SYSTEM_MODE, TlvWriter().uint(null, v).bytes()))
            }
            is DeviceCommand.SetCoverPosition -> n.endpointsWith(Cluster.WINDOW_COVERING).firstOrNull()?.let { ep ->
                listOf(MatterAction.Invoke(ep, Cluster.WINDOW_COVERING, Cmd.COVER_GO_TO_LIFT_PERCENTAGE,
                    TlvWriter().startStructure().uint(0, ((100 - cmd.openPercent.coerceIn(0, 100)) * 100).toLong()).endContainer().bytes()))
            }
            DeviceCommand.OpenCover -> coverCmd(n, Cmd.COVER_UP_OR_OPEN)
            DeviceCommand.CloseCover -> coverCmd(n, Cmd.COVER_DOWN_OR_CLOSE)
            DeviceCommand.StopCover -> coverCmd(n, Cmd.COVER_STOP)
        }
    }

    private fun coverCmd(n: NodeData, cmd: Long) = n.endpointsWith(Cluster.WINDOW_COVERING).firstOrNull()?.let { ep ->
        listOf(MatterAction.Invoke(ep, Cluster.WINDOW_COVERING, cmd, TlvWriter.empty()))
    }

    // --- Farben -------------------------------------------------------------------------------

    internal fun hsvToRgb(hue: Double, sat: Double): RgbColor {
        val c = sat
        val x = c * (1 - kotlin.math.abs((hue / 60) % 2 - 1))
        val m = 1 - c
        val (r, g, b) = when ((hue / 60).toInt() % 6) {
            0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        fun to8(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return RgbColor(to8(r), to8(g), to8(b))
    }

    /** RGB → (Hue, Sättigung) in Matter-Einheiten 0–254; Helligkeit regelt LevelControl. */
    internal fun rgbToHueSat(c: RgbColor): Pair<Long, Long> {
        val r = c.red / 255.0; val g = c.green / 255.0; val b = c.blue / 255.0
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val hue = when {
            d == 0.0 -> 0.0
            max == r -> 60 * (((g - b) / d) % 6)
            max == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }.let { if (it < 0) it + 360 else it }
        val sat = if (max == 0.0) 0.0 else d / max
        return (hue * 254 / 360).roundToLong().coerceIn(0, 254) to (sat * 254).roundToLong().coerceIn(0, 254)
    }
}
