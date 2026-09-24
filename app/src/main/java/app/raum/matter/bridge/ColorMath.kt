package app.raum.matter.bridge

import app.raum.domain.models.RgbColor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Farbumrechnung für die Bridge: raum. speichert RGB, Matter spricht Farbton/Sättigung (0–254) und
 * CIE-1931-xy (0–65279, Wert/65536). Helligkeit bleibt außen vor – die regelt Level Control.
 * sRGB mit Weißpunkt D65.
 */
object ColorMath {
    const val MAX_XY = 0xFEFF
    private const val WHITE_X = 0.3127
    private const val WHITE_Y = 0.3290

    fun rgbToXy(c: RgbColor): Pair<Int, Int> {
        fun lin(v: Int): Double = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val r = lin(c.red); val g = lin(c.green); val b = lin(c.blue)
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        val sum = x + y + z
        val (cx, cy) = if (sum <= 0.0) WHITE_X to WHITE_Y else x / sum to y / sum
        return toMatter(cx) to toMatter(cy)
    }

    /** Voll gesättigte Farbe zu diesem Farbort, hellster Kanal = 255. */
    fun xyToRgb(xMatter: Int, yMatter: Int): RgbColor {
        val x = xMatter / 65536.0
        val y = yMatter / 65536.0
        if (y <= 0.0) return RgbColor(255, 255, 255)
        val bigX = x / y
        val bigZ = (1 - x - y) / y
        val r = (3.2406 * bigX - 1.5372 - 0.4986 * bigZ).coerceAtLeast(0.0)
        val g = (-0.9689 * bigX + 1.8758 + 0.0415 * bigZ).coerceAtLeast(0.0)
        val b = (0.0557 * bigX - 0.2040 + 1.0570 * bigZ).coerceAtLeast(0.0)
        val max = maxOf(r, g, b).takeIf { it > 0 } ?: return RgbColor(255, 255, 255)
        fun enc(v: Double): Int {
            val n = v / max
            val s = if (n <= 0.0031308) 12.92 * n else 1.055 * n.pow(1 / 2.4) - 0.055
            return (s * 255).roundToInt().coerceIn(0, 255)
        }
        return RgbColor(enc(r), enc(g), enc(b))
    }

    fun kelvinToMireds(kelvin: Int): Int = (1_000_000.0 / kelvin.coerceAtLeast(1)).roundToInt()
    fun miredsToKelvin(mireds: Int): Int = (1_000_000.0 / mireds.coerceAtLeast(1)).roundToInt()

    private fun toMatter(v: Double) = (v * 65536).roundToInt().coerceIn(0, MAX_XY)
}
