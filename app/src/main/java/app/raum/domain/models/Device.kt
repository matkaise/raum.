package app.raum.domain.models

import app.raum.R
import androidx.annotation.StringRes

import java.time.Instant
import java.util.UUID

/** Stabile lokale Geräte-ID je Matter-Node (auch für Nodes ohne gespeicherte Metadaten) – zugleich der Hauptkanal. */
fun deviceIdForNode(nodeId: ULong): UUID = UUID.nameUUIDFromBytes("raum-device-$nodeId".toByteArray())

/** Stabile lokale Geräte-ID für einen weiteren Kanal eines Nodes (z. B. zweites Relais, zweite Leuchte). */
fun deviceIdForChannel(nodeId: ULong, endpoint: Int): UUID = UUID.nameUUIDFromBytes("raum-device-$nodeId-ep$endpoint".toByteArray())

enum class OnlineState { ONLINE, OFFLINE, UNKNOWN }

enum class DeviceCategory(@StringRes val labelRes: Int) {
    LIGHT(R.string.category_light),
    SWITCH(R.string.category_switch),
    CLIMATE(R.string.category_climate),
    COVER(R.string.category_cover),
    SENSOR(R.string.category_sensor),
    OTHER(R.string.category_other),
}

/** Live-Zustand eines Matter-Nodes, wie ihn der Controller liefert. */
data class DeviceState(
    val nodeId: ULong,
    val onlineState: OnlineState,
    val capabilities: List<Capability>,
    val lastSeenAt: Instant? = null,
    /** Vom Gerät gemeldete Angaben (Matter BasicInformation), falls bekannt */
    val vendorName: String? = null,
    val productName: String? = null,
    /** Vom Nutzer in einer anderen App vergebener Name (NodeLabel) */
    val label: String? = null,
    /** Wie das Gerät im Netz hängt (Thread-Diagnose, Spez. 8.3) */
    val network: DeviceNetwork? = null,
    /** Weitere unabhängig steuerbare Kanäle; der Hauptkanal steckt in [capabilities]. */
    val channels: List<DeviceChannel> = emptyList(),
)

/**
 * Ein weiterer Kanal eines Nodes mit eigenem Endpunkt (Mehrkanal-Relais, Leuchte + Steckdose, Bridge mit mehreren
 * Leuchten). raum. zeigt ihn als eigenes Gerät; Befehle gehen an [endpoint].
 */
data class DeviceChannel(val endpoint: Int, val capabilities: List<Capability>)

enum class NetworkTransport { THREAD, WIFI, ETHERNET }

/** Rolle im Thread-Netz (ThreadNetworkDiagnostics › RoutingRole). */
enum class ThreadRole(@StringRes val labelRes: Int) {
    SLEEPY_END_DEVICE(R.string.thread_role_sleepy),
    END_DEVICE(R.string.thread_role_end_device),
    REED(R.string.thread_role_reed),
    ROUTER(R.string.thread_role_router),
    LEADER(R.string.thread_role_leader),
    DETACHED(R.string.thread_role_detached),
}

data class DeviceNetwork(
    val transport: NetworkTransport,
    val threadRole: ThreadRole? = null,
    val threadNetworkName: String? = null,
    /** Erweiterte PAN-ID in Hex – vergleichbar mit dem hinterlegten Dataset */
    val threadExtPanId: String? = null,
    val threadChannel: Int? = null,
)

/** Lokal gespeicherte Metadaten zu einem Gerät (Name, Raum, Favorit). */
data class DeviceMetadata(
    val id: UUID,
    val matterNodeId: ULong,
    val displayName: String,
    val roomId: UUID?,
    val vendorName: String?,
    val productName: String?,
    val favorite: Boolean,
    /** Weiterer Kanal des Nodes ([DeviceChannel.endpoint]); null = Hauptkanal. */
    val endpointId: Int? = null,
)

/** Kombiniertes Modell für UI und Domain (Spez. 10.2). */
data class Device(
    val id: UUID,
    val matterNodeId: ULong,
    val displayName: String,
    val roomId: UUID?,
    val vendorName: String?,
    val productName: String?,
    val onlineState: OnlineState,
    val favorite: Boolean,
    val lastSeenAt: Instant?,
    val capabilities: List<Capability>,
    val network: DeviceNetwork? = null,
    /** Weiterer Kanal eines Nodes (Befehle gehen an diesen Endpunkt); null = Hauptkanal. */
    val endpointId: Int? = null,
) {
    val isOnline: Boolean get() = onlineState == OnlineState.ONLINE

    /** Hauptkanal des Nodes – nur er steht für den ganzen Node (Teilen, Entfernen, Node-Infos). */
    val isPrimaryChannel: Boolean get() = endpointId == null

    val category: DeviceCategory
        get() = when {
            capabilities.any { it is LightCapability } -> DeviceCategory.LIGHT
            capabilities.any { it is SwitchCapability } -> DeviceCategory.SWITCH
            capabilities.any { it is ThermostatCapability } -> DeviceCategory.CLIMATE
            capabilities.any { it is CoverCapability } -> DeviceCategory.COVER
            capabilities.any {
                it is ContactSensorCapability || it is OccupancyCapability ||
                    it is TemperatureSensorCapability || it is HumiditySensorCapability
            } -> DeviceCategory.SENSOR
            else -> DeviceCategory.OTHER
        }

    /** "Aktiv" im Sinne der Übersicht: eingeschaltet, geöffnet, belegt oder heizend. */
    val isActive: Boolean
        get() = isOnline && capabilities.any {
            when (it) {
                is LightCapability -> it.isOn
                is SwitchCapability -> it.isOn
                is ContactSensorCapability -> it.isOpen
                is CoverCapability -> it.movement != CoverMovement.STOPPED
                else -> false
            }
        }
}
