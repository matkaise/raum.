package app.raum.matter.chip

/** Matter-Kennungen, die raum. nutzt (Application Cluster Spec 1.x, Device Library). Nur hier – nie in der UI. */
object Cluster {
    const val DESCRIPTOR = 0x001DL
    const val BASIC_INFORMATION = 0x0028L
    const val BRIDGED_DEVICE_BASIC_INFORMATION = 0x0039L
    const val POWER_SOURCE = 0x002FL
    const val ADMIN_COMMISSIONING = 0x003CL
    const val OPERATIONAL_CREDENTIALS = 0x003EL
    const val NETWORK_COMMISSIONING = 0x0031L
    const val THREAD_NETWORK_DIAGNOSTICS = 0x0035L
    const val BOOLEAN_STATE = 0x0045L
    const val ON_OFF = 0x0006L
    const val LEVEL_CONTROL = 0x0008L
    const val COLOR_CONTROL = 0x0300L
    const val THERMOSTAT = 0x0201L
    const val WINDOW_COVERING = 0x0102L
    const val TEMPERATURE_MEASUREMENT = 0x0402L
    const val RELATIVE_HUMIDITY = 0x0405L
    const val OCCUPANCY_SENSING = 0x0406L
    const val ELECTRICAL_POWER_MEASUREMENT = 0x0090L
    const val ELECTRICAL_ENERGY_MEASUREMENT = 0x0091L
}

object Attr {
    // Descriptor
    const val DEVICE_TYPE_LIST = 0x0000L
    const val SERVER_LIST = 0x0001L
    const val PARTS_LIST = 0x0003L
    // Basic Information
    const val VENDOR_NAME = 0x0001L
    const val VENDOR_ID = 0x0002L
    const val PRODUCT_NAME = 0x0003L
    const val PRODUCT_ID = 0x0004L
    const val NODE_LABEL = 0x0005L
    // gemeinsam genutzte „Wert“-Attribute (OnOff, CurrentLevel, StateValue, MeasuredValue, Occupancy …)
    const val VALUE = 0x0000L
    // Level Control
    const val MIN_LEVEL = 0x0002L
    const val MAX_LEVEL = 0x0003L
    // Color Control
    const val CURRENT_HUE = 0x0000L
    const val CURRENT_SATURATION = 0x0001L
    const val COLOR_TEMPERATURE_MIREDS = 0x0007L
    const val COLOR_MODE = 0x0008L
    const val COLOR_CAPABILITIES = 0x400AL
    const val COLOR_TEMP_MIN_MIREDS = 0x400BL
    const val COLOR_TEMP_MAX_MIREDS = 0x400CL
    // Thermostat
    const val LOCAL_TEMPERATURE = 0x0000L
    const val ABS_MIN_HEAT = 0x0003L
    const val ABS_MAX_HEAT = 0x0004L
    const val OCCUPIED_COOLING_SETPOINT = 0x0011L
    const val OCCUPIED_HEATING_SETPOINT = 0x0012L
    const val MIN_HEAT_LIMIT = 0x0015L
    const val MAX_HEAT_LIMIT = 0x0016L
    const val CONTROL_SEQUENCE = 0x001BL
    const val SYSTEM_MODE = 0x001CL
    // Window Covering
    const val OPERATIONAL_STATUS = 0x000AL
    const val CURRENT_LIFT_PERCENT_100THS = 0x000EL
    // Power Source
    const val BAT_PERCENT_REMAINING = 0x000CL
    // Electrical Power/Energy
    const val ACTIVE_POWER = 0x0008L
    const val CUMULATIVE_ENERGY_IMPORTED = 0x0001L
    // global
    const val FEATURE_MAP = 0xFFFCL
    // Thread Network Diagnostics
    const val THREAD_CHANNEL = 0x0000L
    const val ROUTING_ROLE = 0x0001L
    const val THREAD_NETWORK_NAME = 0x0002L
    const val EXTENDED_PAN_ID = 0x0004L
    // Operational Credentials
    const val FABRICS = 0x0001L
    const val CURRENT_FABRIC_INDEX = 0x0005L
}

object Cmd {
    const val OFF = 0x00L
    const val ON = 0x01L
    const val MOVE_TO_LEVEL_WITH_ON_OFF = 0x04L
    const val MOVE_TO_HUE_AND_SATURATION = 0x06L
    const val MOVE_TO_COLOR_TEMPERATURE = 0x0AL
    const val COVER_UP_OR_OPEN = 0x00L
    const val COVER_DOWN_OR_CLOSE = 0x01L
    const val COVER_STOP = 0x02L
    const val COVER_GO_TO_LIFT_PERCENTAGE = 0x05L
    const val REMOVE_FABRIC = 0x0AL
    const val REVOKE_COMMISSIONING = 0x02L
}

object DeviceType {
    const val ROOT_NODE = 0x0016L
    const val POWER_SOURCE = 0x0011L
    const val AGGREGATOR = 0x000EL
    const val BRIDGED_NODE = 0x0013L
    const val ON_OFF_LIGHT = 0x0100L
    const val DIMMABLE_LIGHT = 0x0101L
    const val COLOR_TEMPERATURE_LIGHT = 0x010CL
    const val EXTENDED_COLOR_LIGHT = 0x010DL
    const val ON_OFF_PLUG = 0x010AL
    const val DIMMABLE_PLUG = 0x010BL
    const val MOUNTED_ON_OFF_CONTROL = 0x010FL
    const val THERMOSTAT = 0x0301L
    const val WINDOW_COVERING = 0x0202L
    const val CONTACT_SENSOR = 0x0015L
    const val OCCUPANCY_SENSOR = 0x0107L
    const val TEMPERATURE_SENSOR = 0x0302L
    const val HUMIDITY_SENSOR = 0x0307L

    val LIGHTS = setOf(ON_OFF_LIGHT, DIMMABLE_LIGHT, COLOR_TEMPERATURE_LIGHT, EXTENDED_COLOR_LIGHT)
    val PLUGS = setOf(ON_OFF_PLUG, DIMMABLE_PLUG, MOUNTED_ON_OFF_CONTROL)
    /** Nur Infrastruktur – kein eigenes „Gerät“ für raum. */
    val UTILITY = setOf(ROOT_NODE, POWER_SOURCE, AGGREGATOR, BRIDGED_NODE)
}
