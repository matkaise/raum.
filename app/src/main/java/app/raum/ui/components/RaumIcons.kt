package app.raum.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bathtub
import androidx.compose.material.icons.outlined.Bed
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material.icons.outlined.BlindsClosed
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.DeviceUnknown
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.Garage
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.Yard
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.ui.graphics.vector.ImageVector
import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCategory
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.find

/** Icons werden als stabile Schlüssel gespeichert, damit Backups unabhängig von der Icon-Bibliothek bleiben. */
object RaumIcons {
    val rooms: Map<String, ImageVector> = linkedMapOf(
        "sofa" to Icons.Outlined.Weekend,
        "kitchen" to Icons.Outlined.Kitchen,
        "dining" to Icons.Outlined.Restaurant,
        "bed" to Icons.Outlined.Bed,
        "bath" to Icons.Outlined.Bathtub,
        "desk" to Icons.Outlined.Work,
        "child" to Icons.Outlined.ChildCare,
        "door" to Icons.Outlined.DoorFront,
        "wardrobe" to Icons.Outlined.Checkroom,
        "garage" to Icons.Outlined.Garage,
        "garden" to Icons.Outlined.Yard,
        "chair" to Icons.Outlined.Chair,
        "home" to Icons.Outlined.Home,
    )

    val scenes: Map<String, ImageVector> = linkedMapOf(
        "power" to Icons.Outlined.PowerSettingsNew,
        "movie" to Icons.Outlined.Movie,
        "night" to Icons.Outlined.NightsStay,
        "sunrise" to Icons.Outlined.WbSunny,
        "away" to Icons.Outlined.Luggage,
        "home" to Icons.Outlined.Home,
        "relax" to Icons.Outlined.Spa,
        "dinner" to Icons.Outlined.Restaurant,
        "work" to Icons.Outlined.Work,
        "reading" to Icons.Outlined.LocalLibrary,
        "tv" to Icons.Outlined.Tv,
        "party" to Icons.Outlined.Celebration,
    )

    fun room(key: String): ImageVector = rooms[key] ?: Icons.Outlined.Home
    fun scene(key: String): ImageVector = scenes[key] ?: Icons.Outlined.PowerSettingsNew

    fun device(device: Device): ImageVector = when (device.category) {
        DeviceCategory.LIGHT -> Icons.Outlined.Lightbulb
        DeviceCategory.SWITCH -> Icons.Outlined.Power
        DeviceCategory.CLIMATE -> Icons.Outlined.Thermostat
        DeviceCategory.COVER ->
            if ((device.capabilities.find<CoverCapability>()?.openPercent ?: 0) > 50) Icons.Outlined.Blinds
            else Icons.Outlined.BlindsClosed
        DeviceCategory.SENSOR -> when {
            device.capabilities.find<ContactSensorCapability>() != null -> Icons.Outlined.Window
            device.capabilities.find<OccupancyCapability>() != null -> Icons.AutoMirrored.Outlined.DirectionsWalk
            device.capabilities.find<HumiditySensorCapability>() != null -> Icons.Outlined.WaterDrop
            else -> Icons.Outlined.Sensors
        }
        DeviceCategory.OTHER -> Icons.Outlined.DeviceUnknown
    }
}
