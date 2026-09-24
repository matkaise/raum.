package app.raum.diagnostics

import app.raum.BuildConfig
import app.raum.R
import app.raum.data.database.RaumDatabase
import app.raum.domain.models.Device
import app.raum.domain.models.Room
import app.raum.i18n.Strings
import app.raum.platform.kiosk.KioskStatus
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Textberichte für Übergabe (RST-004) und Diagnose (LOG-004) in der App-Sprache.
 * Enthalten nie Schlüssel oder PINs (LOG-003).
 */
object Reports {

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    data class SystemInfo(val androidRelease: String, val sdk: Int, val manufacturer: String, val model: String)

    /**
     * Neutrale Systemübersicht für die Wohnungsübergabe: welche Technik ist verbaut, wo sitzt sie.
     * Bewusst ohne Gerätenamen, Szenen, Automationen, Protokoll, Standort und Nutzungsdaten.
     */
    fun handover(
        rooms: List<Room>,
        devices: List<Device>,
        system: SystemInfo,
        strings: Strings,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): String = buildString {
        val noRoom = strings.get(R.string.no_room)
        val unknown = strings.get(R.string.time_unknown)
        appendLine(strings.get(R.string.report_handover_title))
        appendLine(strings.get(R.string.report_created, now.format(DATE)))
        appendLine()
        appendLine(strings.get(R.string.report_panel, "${system.manufacturer} ${system.model}", system.androidRelease, BuildConfig.VERSION_NAME))
        appendLine(strings.get(R.string.report_matter_devices, devices.size))
        appendLine()
        val byRoom = devices.groupBy { d -> rooms.firstOrNull { it.id == d.roomId }?.name ?: noRoom }
        (rooms.map { it.name } + noRoom).distinct().forEach { room ->
            val list = byRoom[room].orEmpty()
            if (list.isEmpty() && room == noRoom) return@forEach
            appendLine("## $room")
            if (list.isEmpty()) appendLine("   " + strings.get(R.string.report_no_devices))
            list.groupBy { Triple(strings.get(it.category.labelRes), it.vendorName ?: unknown, it.productName ?: unknown) }
                .toSortedMap(compareBy<Triple<String, String, String>>({ it.first }, { it.second }, { it.third }))
                .forEach { (k, v) -> appendLine("   ${v.size} × ${k.first}: ${k.second} ${k.third}") }
            appendLine()
        }
        appendLine(strings.get(R.string.report_handover_hints))
    }

    /** Diagnosepaket (LOG-004): Systemzustand, Zählungen und die letzten Protokolleinträge. */
    fun diagnostics(
        system: SystemInfo,
        kiosk: KioskStatus,
        devices: List<Device>,
        roomsCount: Int,
        scenesCount: Int,
        automationsCount: Int,
        log: List<LogEntry>,
        strings: Strings,
        extra: Map<String, String> = emptyMap(),
        now: ZonedDateTime = ZonedDateTime.now(),
    ): String = buildString {
        // Diagnose ist für Techniker: Kopfzeilen in der App-Sprache, Werte technisch
        appendLine(strings.get(R.string.report_diagnostics_title, now.format(DATE)))
        appendLine("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), DB schema v${RaumDatabase.VERSION}")
        appendLine("Device ${system.manufacturer} ${system.model}, Android ${system.androidRelease} (API ${system.sdk})")
        appendLine("Device owner: ${kiosk.isDeviceOwner}, kiosk: ${kiosk.lockTaskActive}, launcher: ${kiosk.isDefaultLauncher}, ADB: ${kiosk.adbEnabled}")
        extra.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine("Rooms $roomsCount · devices ${devices.size} (offline ${devices.count { !it.isOnline }}) · scenes $scenesCount · automations $automationsCount")
        appendLine()
        appendLine(strings.get(R.string.report_devices_header))
        devices.sortedBy { it.matterNodeId }.forEach { d ->
            appendLine(" 0x%X %-8s %-10s %s %s".format(d.matterNodeId.toLong(), d.onlineState, d.category.name, d.vendorName ?: "-", d.productName ?: "-"))
        }
        appendLine()
        appendLine(strings.get(R.string.report_log_header, log.size))
        log.forEach { e ->
            appendLine("${TS.format(e.timestamp)} ${e.level.name.padEnd(7)} ${e.category.name.padEnd(10)} ${e.deviceName?.let { "[$it] " } ?: ""}${e.message}")
        }
    }
}
