package app.raum.matter.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import app.raum.BuildConfig
import chip.platform.AndroidChipPlatformException
import chip.platform.ConfigurationManager
import chip.platform.ConfigurationManager.kConfigKey_HardwareVersion
import chip.platform.ConfigurationManager.kConfigKey_HardwareVersionString
import chip.platform.ConfigurationManager.kConfigKey_ManufacturingDate
import chip.platform.ConfigurationManager.kConfigKey_PartNumber
import chip.platform.ConfigurationManager.kConfigKey_ProductId
import chip.platform.ConfigurationManager.kConfigKey_ProductLabel
import chip.platform.ConfigurationManager.kConfigKey_ProductName
import chip.platform.ConfigurationManager.kConfigKey_SerialNum
import chip.platform.ConfigurationManager.kConfigKey_SoftwareVersion
import chip.platform.ConfigurationManager.kConfigKey_SoftwareVersionString
import chip.platform.ConfigurationManager.kConfigNamespace_ChipFactory
import java.util.UUID

/**
 * Geräte-Identität der Bridge (BasicInformation) und Ablage der SDK-Konfiguration im Bridge-Prozess.
 * Eigene Datei – der Controller im Hauptprozess nutzt die Standarddatei des SDK, zwei Prozesse dürfen
 * nicht in dieselbe SharedPreferences-Datei schreiben.
 */
@SuppressLint("UseKtx")
class BridgeConfigurationManager(context: Context) : ConfigurationManager {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(namespace: String?, name: String?): String = when {
        namespace != null && name != null -> "$namespace:$name"
        namespace != null -> "$namespace:"
        else -> throw AndroidChipPlatformException()
    }

    override fun readConfigValueLong(namespace: String?, name: String?): Long = when (key(namespace, name)) {
        factory(kConfigKey_ProductId) -> PRODUCT_ID
        factory(kConfigKey_HardwareVersion) -> 1L
        factory(kConfigKey_SoftwareVersion) -> BuildConfig.VERSION_CODE.toLong()
        else -> key(namespace, name).let { k -> if (prefs.contains(k)) prefs.getLong(k, 0) else throw AndroidChipPlatformException() }
    }

    override fun readConfigValueStr(namespace: String?, name: String?): String = when (key(namespace, name)) {
        factory(kConfigKey_ProductName) -> BuildConfig.BRIDGE_PRODUCT_NAME.take(32)
        factory(kConfigKey_HardwareVersionString) -> "1"
        factory(kConfigKey_SoftwareVersionString) -> BuildConfig.VERSION_NAME.take(64)
        factory(kConfigKey_ManufacturingDate) -> "2026-09-24"
        factory(kConfigKey_PartNumber) -> "raum-panel"
        factory(kConfigKey_ProductLabel) -> "raum."
        // Seriennummer je Installation – andere Apps erkennen die Bridge daran wieder
        factory(kConfigKey_SerialNum) -> prefs.getString(SERIAL, null)
            ?: UUID.randomUUID().toString().replace("-", "").take(16).also { prefs.edit().putString(SERIAL, it).apply() }
        else -> prefs.getString(key(namespace, name), null) ?: throw AndroidChipPlatformException()
    }

    override fun readConfigValueBin(namespace: String?, name: String?): ByteArray =
        prefs.getString(key(namespace, name), null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: throw AndroidChipPlatformException()

    override fun writeConfigValueLong(namespace: String?, name: String?, value: Long) {
        prefs.edit().putLong(key(namespace, name), value).apply()
    }

    override fun writeConfigValueStr(namespace: String?, name: String?, value: String?) {
        prefs.edit().putString(key(namespace, name), value).apply()
    }

    override fun writeConfigValueBin(namespace: String?, name: String?, value: ByteArray?) {
        val k = key(namespace, name)
        if (value == null) prefs.edit().remove(k).apply()
        else prefs.edit().putString(k, Base64.encodeToString(value, Base64.NO_WRAP)).apply()
    }

    override fun clearConfigValue(namespace: String?, name: String?) {
        when {
            namespace != null && name != null -> prefs.edit().remove(key(namespace, name)).apply()
            namespace != null -> {
                val prefix = key(namespace, null)
                prefs.edit().apply { prefs.all.keys.filter { it.startsWith(prefix) }.forEach(::remove) }.apply()
            }
            else -> prefs.edit().clear().apply()
        }
    }

    override fun configValueExists(namespace: String?, name: String?): Boolean = prefs.contains(key(namespace, name))

    private fun factory(name: String) = "$kConfigNamespace_ChipFactory:$name"

    companion object {
        /** Aus gradle.properties (raumVendorId/raumBridgeProductId). Die Echtheitszertifikate müssen dazu passen –
         *  ohne eigene gilt nur die Test-ID 0xFFF1/0x8000 der SDK-Testzertifikate (siehe BridgeAttestation). */
        val VENDOR_ID: Int = BuildConfig.MATTER_VENDOR_ID
        val PRODUCT_ID: Long = BuildConfig.BRIDGE_PRODUCT_ID.toLong()
        private const val SERIAL = "raum:serial"
        /** Konfiguration des Bridge-Stacks inkl. Seriennummer – ein Werksreset gibt der Bridge eine neue Identität */
        internal const val PREFS = "raum_bridge_config"
    }
}
