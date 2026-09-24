package app.raum.matter.controller

/** Bekannte Ökosysteme nach CSA-Hersteller-ID. */
object Ecosystems {
    const val APPLE = 0x1349
    const val GOOGLE = 0x6006
    const val AMAZON = 0x1217

    fun name(f: AdminFabric): String? = when {
        f.own -> "raum."
        f.vendorId == APPLE -> "Apple Home"
        f.vendorId == GOOGLE -> "Google Home"
        f.vendorId == AMAZON -> "Amazon Alexa"
        f.label.isNotBlank() -> f.label
        else -> null
    }
}
