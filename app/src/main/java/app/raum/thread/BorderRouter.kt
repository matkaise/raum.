package app.raum.thread

import java.net.InetAddress

/** Zustand der Thread-Schnittstelle eines Border Routers (MeshCoP „sb“, Bits 3–4). */
enum class ThreadInterfaceState { UNKNOWN, NOT_INITIALIZED, INACTIVE, ACTIVE }

/** Ein im Netz gefundener Thread Border Router (DNS-SD `_meshcop._udp`, Thread-Spez. 8.4.8.3). */
data class BorderRouter(
    /** DNS-SD-Instanzname – eindeutig im Netz */
    val id: String,
    val vendor: String?,
    val model: String?,
    val networkName: String?,
    /** Erweiterte PAN-ID in Hex (16 Zeichen) */
    val extPanId: String?,
    val threadVersion: String?,
    val state: ThreadInterfaceState,
    val host: InetAddress? = null,
    val port: Int = 0,
    /** OpenThread-REST-API erreichbar – Zugangsdaten lassen sich übernehmen (ONB-008). */
    val restAvailable: Boolean = false,
) {
    val displayName: String get() = model?.takeIf { it.isNotBlank() && !it.equals("BorderRouter", true) } ?: id
    val active: Boolean get() = state == ThreadInterfaceState.ACTIVE

    /** Geschlossene Ökosysteme geben ihre Thread-Zugangsdaten nicht heraus. */
    val closedEcosystem: Boolean
        get() = listOf("apple", "google", "amazon", "samsung", "eero").any { vendor?.contains(it, ignoreCase = true) == true }
}

/** Liest die TXT-Einträge einer `_meshcop._udp`-Ankündigung (Werte als Bytes, wie Android sie liefert). */
object MeshcopTxt {
    fun parse(id: String, txt: Map<String, ByteArray?>, host: InetAddress? = null, port: Int = 0): BorderRouter {
        fun str(key: String) = txt[key]?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() }
        return BorderRouter(
            id = id,
            vendor = str("vn"),
            model = str("mn"),
            networkName = str("nn"),
            // xp ist binär (8 Bytes); manche Implementierungen senden Hex-Text
            extPanId = txt["xp"]?.let { v ->
                when {
                    v.size == 8 -> v.toHex()
                    v.size == 16 && v.all { Character.digit(it.toInt().toChar(), 16) >= 0 } -> v.decodeToString().lowercase()
                    else -> null
                }
            },
            threadVersion = str("tv"),
            state = txt["sb"]?.takeIf { it.size == 4 }?.let { sb ->
                val bits = ((sb[0].toLong() and 0xFF) shl 24) or ((sb[1].toLong() and 0xFF) shl 16) or
                    ((sb[2].toLong() and 0xFF) shl 8) or (sb[3].toLong() and 0xFF)
                when (((bits shr 3) and 0x3).toInt()) {
                    0 -> ThreadInterfaceState.NOT_INITIALIZED
                    1 -> ThreadInterfaceState.INACTIVE
                    2 -> ThreadInterfaceState.ACTIVE
                    else -> ThreadInterfaceState.UNKNOWN
                }
            } ?: ThreadInterfaceState.UNKNOWN,
            host = host,
            port = port,
        )
    }
}
