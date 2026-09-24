package app.raum.matter.commissioning

import kotlin.random.Random

/**
 * Erzeugt Kopplungscodes für ein geöffnetes Kopplungsfenster (Multi-Admin, Matter-Spez. 5.1.3/5.1.4):
 * 11-stelliger manueller Code und QR-Inhalt „MT:…“. Gegenstück zu [SetupCodeParser].
 */
object SetupCodeGenerator {
    private const val BASE38 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-."

    /** Rendezvous-Bits im QR-Code */
    const val DISCOVERY_ON_NETWORK = 0b100
    const val DISCOVERY_BLE = 0b010

    fun randomPasscode(random: Random = Random.Default): Long {
        while (true) {
            val p = random.nextLong(1, 99_999_999L)
            if (p !in SetupCodeParser.INVALID_PASSCODES) return p
        }
    }

    fun randomDiscriminator(random: Random = Random.Default): Int = random.nextInt(0, 0x1000)

    /** 11-stelliger Code ohne Hersteller-/Produkt-ID. */
    fun manualCode(discriminator: Int, passcode: Long): String {
        require(discriminator in 0..0xFFF && passcode in 1..99_999_998L)
        val short = discriminator shr 8 // obere 4 Bit
        val chunk1 = short shr 2
        val chunk2 = ((short and 0b11) shl 14) or (passcode and 0x3FFF).toInt()
        val chunk3 = (passcode shr 14).toInt()
        val digits = "%d%05d%04d".format(chunk1, chunk2, chunk3)
        return digits + Verhoeff.checkDigit(digits)
    }

    /** „34970112332“ → „3497-011-2332“ (wie auf Geräteetiketten) */
    fun formatManual(code: String): String = "${code.substring(0, 4)}-${code.substring(4, 7)}-${code.substring(7)}"

    /** QR-Inhalt „MT:…“ – 88 Bit, niederwertige Bits zuerst, Base38-kodiert. */
    fun qrPayload(
        vendorId: Int, productId: Int, discriminator: Int, passcode: Long,
        flow: Int = 0, discovery: Int = DISCOVERY_ON_NETWORK,
    ): String {
        val bytes = ByteArray(11)
        var offset = 0
        fun put(value: Long, bits: Int) {
            for (i in 0 until bits) {
                if ((value shr i) and 1L == 1L) {
                    val bit = offset + i
                    bytes[bit / 8] = (bytes[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
                }
            }
            offset += bits
        }
        put(0, 3)                          // Version
        put(vendorId.toLong(), 16)
        put(productId.toLong(), 16)
        put(flow.toLong(), 2)              // Standard-Kopplung
        put(discovery.toLong(), 8)
        put(discriminator.toLong(), 12)
        put(passcode, 27)
        put(0, 4)                          // Auffüllung
        return "MT:" + base38(bytes)
    }

    private fun base38(bytes: ByteArray): String = buildString {
        var i = 0
        while (i < bytes.size) {
            val n = minOf(3, bytes.size - i)
            var value = 0L
            for (j in 0 until n) value = value or ((bytes[i + j].toLong() and 0xFF) shl (8 * j))
            val chars = when (n) { 3 -> 5; 2 -> 4; else -> 2 }
            repeat(chars) { append(BASE38[(value % 38).toInt()]); value /= 38 }
            i += n
        }
    }
}
