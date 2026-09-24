package app.raum.matter.chip

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Matter-TLV (Spez. Anhang A), soweit raum. es braucht.
 *
 * Gelesene Werte werden auf einfache Kotlin-Typen abgebildet:
 * Ganzzahlen → [Long], Boolean, Float/Double, String, ByteArray, `null`,
 * Struktur → [TlvStruct] (Kontext-Tag → Wert), Array/Liste → [List].
 */
class TlvStruct(val fields: Map<Int, Any?>) {
    operator fun get(tag: Int): Any? = fields[tag]
    fun long(tag: Int): Long? = fields[tag] as? Long
    fun string(tag: Int): String? = fields[tag] as? String
    override fun toString() = "TlvStruct($fields)"
}

object TlvReader {
    fun read(bytes: ByteArray): Any? {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return readElement(buf).second
    }

    private object End

    /** (Kontext-Tag oder -1, Wert) */
    private fun readElement(buf: ByteBuffer): Pair<Int, Any?> {
        val control = buf.get().toInt() and 0xFF
        val tagControl = control ushr 5
        val type = control and 0x1F
        val tag = when (tagControl) {
            0 -> -1
            1 -> buf.get().toInt() and 0xFF
            2, 4 -> { buf.getShort(); -1 }            // Profil-Tags: für raum. ohne Bedeutung
            3, 5 -> { buf.getInt(); -1 }
            6 -> { buf.getShort(); buf.getShort(); buf.getShort(); -1 }
            7 -> { buf.getShort(); buf.getShort(); buf.getInt(); -1 }
            else -> error("TLV tag control $tagControl")
        }
        val value: Any? = when (type) {
            0x00 -> buf.get().toLong()
            0x01 -> buf.getShort().toLong()
            0x02 -> buf.getInt().toLong()
            0x03 -> buf.getLong()
            0x04 -> buf.get().toLong() and 0xFF
            0x05 -> buf.getShort().toLong() and 0xFFFF
            0x06 -> buf.getInt().toLong() and 0xFFFFFFFFL
            0x07 -> buf.getLong() // > Long.MAX_VALUE kommt in raum.-relevanten Attributen nicht vor
            0x08 -> false
            0x09 -> true
            0x0A -> buf.getFloat()
            0x0B -> buf.getDouble()
            in 0x0C..0x0F -> String(bytes(buf, length(buf, type - 0x0C)), Charsets.UTF_8)
            in 0x10..0x13 -> bytes(buf, length(buf, type - 0x10))
            0x14 -> null
            0x15 -> {
                val fields = LinkedHashMap<Int, Any?>()
                while (true) {
                    val (t, v) = readElement(buf)
                    if (v === End) break
                    fields[t] = v
                }
                TlvStruct(fields)
            }
            0x16, 0x17 -> buildList {
                while (true) {
                    val (_, v) = readElement(buf)
                    if (v === End) break
                    add(v)
                }
            }
            0x18 -> End
            else -> error("TLV type 0x%02X".format(type))
        }
        return tag to value
    }

    private fun length(buf: ByteBuffer, size: Int): Int = when (size) {
        0 -> buf.get().toInt() and 0xFF
        1 -> buf.getShort().toInt() and 0xFFFF
        2 -> buf.getInt()
        else -> buf.getLong().toInt()
    }

    private fun bytes(buf: ByteBuffer, n: Int) = ByteArray(n).also { buf.get(it) }
}

/** Schreibt Befehlsfelder: eine anonyme Struktur mit Kontext-Tags. */
class TlvWriter {
    private val out = ByteArrayOutputStream()

    fun startStructure(tag: Int? = null) = apply { control(tag, 0x15) }
    fun endContainer() = apply { out.write(0x18) }

    /** Ganzzahl in der kleinsten passenden Breite (Matter-Empfänger akzeptieren das). */
    fun uint(tag: Int?, value: Long) = apply {
        require(value >= 0)
        when {
            value <= 0xFF -> { control(tag, 0x04); le(value, 1) }
            value <= 0xFFFF -> { control(tag, 0x05); le(value, 2) }
            value <= 0xFFFFFFFFL -> { control(tag, 0x06); le(value, 4) }
            else -> { control(tag, 0x07); le(value, 8) }
        }
    }

    fun int(tag: Int?, value: Long) = apply {
        when (value) {
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> { control(tag, 0x00); le(value, 1) }
            in Short.MIN_VALUE..Short.MAX_VALUE -> { control(tag, 0x01); le(value, 2) }
            in Int.MIN_VALUE..Int.MAX_VALUE -> { control(tag, 0x02); le(value, 4) }
            else -> { control(tag, 0x03); le(value, 8) }
        }
    }

    fun bool(tag: Int?, value: Boolean) = apply { control(tag, if (value) 0x09 else 0x08) }
    fun nul(tag: Int?) = apply { control(tag, 0x14) }

    fun bytes(): ByteArray = out.toByteArray()

    private fun control(tag: Int?, type: Int) {
        if (tag == null) out.write(type) else { out.write((1 shl 5) or type); out.write(tag) }
    }

    private fun le(v: Long, n: Int) { for (i in 0 until n) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }

    companion object {
        /** Befehl ohne Felder */
        fun empty(): ByteArray = TlvWriter().startStructure().endContainer().bytes()
    }
}
