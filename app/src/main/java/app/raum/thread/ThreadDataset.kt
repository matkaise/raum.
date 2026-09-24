package app.raum.thread

/**
 * Thread Operational Dataset (Thread-Spez. 8.10, MeshCoP-TLVs) – die Zugangsdaten eines Thread-Netzes.
 * Enthält den Netzwerkschlüssel: nie loggen, nie anzeigen, nur verschlüsselt speichern (LOG-003).
 */
class ThreadDataset private constructor(private val raw: ByteArray, tlvs: Map<Int, ByteArray>) {

    val networkName: String = tlvs.getValue(T_NETWORK_NAME).decodeToString()
    val channel: Int = tlvs.getValue(T_CHANNEL).let { ((it[1].toInt() and 0xFF) shl 8) or (it[2].toInt() and 0xFF) }
    val panId: Int = tlvs.getValue(T_PAN_ID).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
    /** Erweiterte PAN-ID in Hex (16 Zeichen) – kennzeichnet das Netz eindeutig, auch in _meshcop._udp. */
    val extPanId: String = tlvs.getValue(T_EXT_PAN_ID).toHex()
    val meshLocalPrefix: String = tlvs.getValue(T_MESH_LOCAL_PREFIX).let { p ->
        (0 until 8 step 2).joinToString(":") { i -> "%x".format(((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)) } + "::/64"
    }
    val activeTimestamp: Long? = tlvs[T_ACTIVE_TIMESTAMP]?.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }?.ushr(16)

    /** Die TLVs, wie sie ans Gerät gehen (AddOrUpdateThreadNetwork). */
    fun bytes(): ByteArray = raw.copyOf()
    fun hex(): String = raw.toHex()

    val summary: ThreadNetworkSummary get() = ThreadNetworkSummary(networkName, channel, panId, extPanId)

    override fun equals(other: Any?) = other is ThreadDataset && other.raw.contentEquals(raw)
    override fun hashCode() = raw.contentHashCode()
    /** Bewusst ohne Schlüssel */
    override fun toString() = "ThreadDataset($networkName, ch $channel, pan 0x%04X, xp $extPanId)".format(panId)

    enum class Error { EMPTY, NOT_HEX, MALFORMED, TOO_LONG, MISSING_FIELDS }

    sealed interface ParseResult {
        data class Ok(val dataset: ThreadDataset) : ParseResult
        data class Invalid(val error: Error) : ParseResult
    }

    companion object {
        const val T_CHANNEL = 0
        const val T_PAN_ID = 1
        const val T_EXT_PAN_ID = 2
        const val T_NETWORK_NAME = 3
        const val T_PSKC = 4
        const val T_NETWORK_KEY = 5
        const val T_MESH_LOCAL_PREFIX = 7
        const val T_SECURITY_POLICY = 12
        const val T_ACTIVE_TIMESTAMP = 14
        const val T_CHANNEL_MASK = 53

        /** Matter: CommissioningParameters::kMaxThreadDatasetLen */
        const val MAX_BYTES = 254

        private val FIXED_LENGTHS = mapOf(
            T_CHANNEL to 3, T_PAN_ID to 2, T_EXT_PAN_ID to 8, T_PSKC to 16, T_NETWORK_KEY to 16,
            T_MESH_LOCAL_PREFIX to 8, T_ACTIVE_TIMESTAMP to 8,
        )
        private val REQUIRED = setOf(T_CHANNEL, T_PAN_ID, T_EXT_PAN_ID, T_NETWORK_NAME, T_NETWORK_KEY, T_MESH_LOCAL_PREFIX)

        /**
         * Liest ein Dataset als Hex-Text – so, wie es `ot-ctl dataset active -x`, die OTBR-REST-API oder
         * Home Assistant ausgeben. Leerzeichen, Zeilenumbrüche, Doppelpunkte und „0x“ sind erlaubt.
         */
        fun parse(input: String): ParseResult {
            val clean = input.trim().removePrefix("0x").removePrefix("0X").filterNot { it.isWhitespace() || it == ':' || it == '-' }
            if (clean.isEmpty()) return ParseResult.Invalid(Error.EMPTY)
            if (clean.length % 2 != 0 || clean.any { Character.digit(it, 16) < 0 }) return ParseResult.Invalid(Error.NOT_HEX)
            val bytes = ByteArray(clean.length / 2) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
            return parse(bytes)
        }

        fun parse(bytes: ByteArray): ParseResult {
            if (bytes.isEmpty()) return ParseResult.Invalid(Error.EMPTY)
            if (bytes.size > MAX_BYTES) return ParseResult.Invalid(Error.TOO_LONG)
            val tlvs = mutableMapOf<Int, ByteArray>()
            var i = 0
            while (i < bytes.size) {
                if (i + 2 > bytes.size) return ParseResult.Invalid(Error.MALFORMED)
                val type = bytes[i].toInt() and 0xFF
                val len = bytes[i + 1].toInt() and 0xFF
                if (len == 0xFF) return ParseResult.Invalid(Error.MALFORMED) // erweiterte Länge kommt in Datasets nicht vor
                if (i + 2 + len > bytes.size) return ParseResult.Invalid(Error.MALFORMED)
                val value = bytes.copyOfRange(i + 2, i + 2 + len)
                FIXED_LENGTHS[type]?.let { if (it != len) return ParseResult.Invalid(Error.MALFORMED) }
                if (type == T_NETWORK_NAME && (len == 0 || len > 16)) return ParseResult.Invalid(Error.MALFORMED)
                if (type in tlvs) return ParseResult.Invalid(Error.MALFORMED)
                tlvs[type] = value
                i += 2 + len
            }
            if (!tlvs.keys.containsAll(REQUIRED)) return ParseResult.Invalid(Error.MISSING_FIELDS)
            return ParseResult.Ok(ThreadDataset(bytes.copyOf(), tlvs))
        }
    }
}

/** Was von einem Thread-Netz angezeigt werden darf – ohne Schlüssel. */
data class ThreadNetworkSummary(val networkName: String, val channel: Int, val panId: Int, val extPanId: String)

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
