package app.raum.domain.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

data class Home(
    val id: UUID,
    val name: String,
    val createdAt: Instant,
)

data class Room(
    val id: UUID,
    val name: String,
    /** Schlüssel für [app.raum.ui.components.RaumIcons]. */
    val icon: String,
    val sortOrder: Int,
)

data class Scene(
    val id: UUID,
    val name: String,
    val icon: String,
    val actions: List<SceneAction>,
)

/** Eine Szenenaktion ist ein Matter-Befehl an ein bestimmtes Gerät. */
data class SceneAction(
    val deviceId: UUID,
    val command: DeviceCommand,
)

/**
 * Geräteunabhängiger Befehl auf Capability-Ebene.
 * Wird vom Matter-Adapter in konkrete Cluster-Kommandos übersetzt.
 *
 * Serialisierbar, weil Szenen (und später Automationen/Backups) Befehle speichern.
 * Die [SerialName]s sind Teil des Speicherformats und dürfen nicht geändert werden.
 */
@Serializable
sealed interface DeviceCommand {
    @Serializable @SerialName("on")
    data class SetOn(val on: Boolean) : DeviceCommand

    @Serializable @SerialName("brightness")
    data class SetBrightness(val percent: Int) : DeviceCommand

    @Serializable @SerialName("color_temperature")
    data class SetColorTemperature(val kelvin: Int) : DeviceCommand

    @Serializable @SerialName("color")
    data class SetColor(val color: RgbColor) : DeviceCommand

    @Serializable @SerialName("target_temperature")
    data class SetTargetTemperature(
        val celsius: Double,
        /**
         * Für welchen Modus der Sollwert gilt (Kühlen → Kühl-, sonst Heiz-Sollwert). null = aktueller Modus des Geräts.
         * Szenen setzen ihn, weil der vorangehende Moduswechsel beim Gerät noch nicht zurückgemeldet sein kann.
         */
        val mode: ThermostatMode? = null,
    ) : DeviceCommand

    @Serializable @SerialName("thermostat_mode")
    data class SetThermostatMode(val mode: ThermostatMode) : DeviceCommand

    @Serializable @SerialName("cover_position")
    data class SetCoverPosition(val openPercent: Int) : DeviceCommand

    @Serializable @SerialName("cover_open")
    data object OpenCover : DeviceCommand

    @Serializable @SerialName("cover_close")
    data object CloseCover : DeviceCommand

    @Serializable @SerialName("cover_stop")
    data object StopCover : DeviceCommand
}
