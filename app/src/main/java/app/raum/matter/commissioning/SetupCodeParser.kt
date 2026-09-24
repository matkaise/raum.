package app.raum.matter.commissioning

/**
 * Parser für den numerischen Matter-Setup-Code (Manual Pairing Code, COM-001).
 *
 * Aufbau laut Matter Core Spec 5.1.4:
 *  - 11 Ziffern: Chunk1 (1) + Chunk2 (5) + Chunk3 (4) + Prüfziffer (1)
 *  - 21 Ziffern: zusätzlich Vendor-ID (5) und Product-ID (5) vor der Prüfziffer
 *  - Prüfziffer nach dem Verhoeff-Verfahren
 *
 * QR-Payloads ("MT:...") werden in M2 über das Matter SDK dekodiert (COM-008).
 */
object SetupCodeParser {

    data class ManualPairingCode(
        val shortDiscriminator: Int,
        val passcode: Long,
        val vendorId: Int?,
        val productId: Int?,
    ) {
        /** Normalisierte Darstellung, z. B. zur Duplikaterkennung (COM-010). */
        val key: String get() = "$shortDiscriminator:$passcode"
    }

    sealed interface ParseResult {
        data class Valid(val code: ManualPairingCode) : ParseResult
        data class Invalid(val reason: InvalidReason) : ParseResult
    }

    /** Warum ein Code abgelehnt wurde – der Text dazu kommt aus den Sprachressourcen. */
    enum class InvalidReason { QR_NOT_SUPPORTED, WRONG_LENGTH, CHECK_DIGIT, INCONSISTENT, INVALID, FORBIDDEN_PASSCODE }

    internal val INVALID_PASSCODES = setOf(
        0L, 11111111L, 22222222L, 33333333L, 44444444L, 55555555L,
        66666666L, 77777777L, 88888888L, 99999999L, 12345678L, 87654321L,
    )

    /** Entfernt Leerzeichen und Bindestriche, wie sie auf Etiketten üblich sind ("3497-011-2332"). */
    fun normalize(input: String): String = input.filter { it.isDigit() }

    fun parse(input: String): ParseResult {
        if (input.trim().startsWith("MT:", ignoreCase = true)) {
            return ParseResult.Invalid(InvalidReason.QR_NOT_SUPPORTED)
        }
        val digits = normalize(input)
        if (digits.length != 11 && digits.length != 21) {
            return ParseResult.Invalid(InvalidReason.WRONG_LENGTH)
        }
        if (!Verhoeff.isValid(digits)) {
            return ParseResult.Invalid(InvalidReason.CHECK_DIGIT)
        }
        val chunk1 = digits.substring(0, 1).toInt()
        val chunk2 = digits.substring(1, 6).toInt()
        val chunk3 = digits.substring(6, 10).toInt()

        val hasVidPid = (chunk1 shr 2) and 1 == 1
        if (hasVidPid != (digits.length == 21)) {
            return ParseResult.Invalid(InvalidReason.INCONSISTENT)
        }
        if (chunk1 > 7) return ParseResult.Invalid(InvalidReason.INVALID)

        val shortDiscriminator = ((chunk1 and 0b11) shl 2) or ((chunk2 shr 14) and 0b11)
        val passcode = (chunk2 and 0x3FFF).toLong() or (chunk3.toLong() shl 14)

        if (passcode in INVALID_PASSCODES || passcode > 99999998L) {
            return ParseResult.Invalid(InvalidReason.FORBIDDEN_PASSCODE)
        }

        val vendorId = if (hasVidPid) digits.substring(10, 15).toInt() else null
        val productId = if (hasVidPid) digits.substring(15, 20).toInt() else null

        return ParseResult.Valid(ManualPairingCode(shortDiscriminator, passcode, vendorId, productId))
    }
}

internal object Verhoeff {
    private val d = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
        intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
        intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
        intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val p = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
        intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
        intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )
    private val inv = intArrayOf(0, 4, 3, 2, 1, 5, 6, 7, 8, 9)

    fun isValid(digits: String): Boolean {
        var c = 0
        digits.reversed().forEachIndexed { i, ch -> c = d[c][p[i % 8][ch.digitToInt()]] }
        return c == 0
    }

    fun checkDigit(digits: String): Int {
        var c = 0
        digits.reversed().forEachIndexed { i, ch -> c = d[c][p[(i + 1) % 8][ch.digitToInt()]] }
        return inv[c]
    }
}
