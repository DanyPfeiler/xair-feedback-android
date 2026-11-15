package com.danypfeiler.xairfeedback.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** OSC minimo: lo justo para hablar con una consola X AIR. */
object Osc {
    class Msg(val addr: String, val args: List<Any>)

    private fun pad(out: ByteArrayOutputStream) {
        while (out.size() % 4 != 0) out.write(0)
    }

    private fun str(out: ByteArrayOutputStream, s: String) {
        out.write(s.toByteArray(Charsets.US_ASCII))
        out.write(0)
        pad(out)
    }

    fun encode(addr: String, vararg args: Any): ByteArray {
        val out = ByteArrayOutputStream()
        str(out, addr)
        val tags = StringBuilder(",")
        val data = ByteArrayOutputStream()
        for (a in args) {
            when (a) {
                is Int -> { tags.append('i'); data.write(ByteBuffer.allocate(4).putInt(a).array()) }
                is Boolean -> { tags.append('i'); data.write(ByteBuffer.allocate(4).putInt(if (a) 1 else 0).array()) }
                is Float -> { tags.append('f'); data.write(ByteBuffer.allocate(4).putFloat(a).array()) }
                is Double -> { tags.append('f'); data.write(ByteBuffer.allocate(4).putFloat(a.toFloat()).array()) }
                is String -> { tags.append('s'); str(data, a) }
                is ByteArray -> {
                    tags.append('b')
                    data.write(ByteBuffer.allocate(4).putInt(a.size).array())
                    data.write(a)
                    pad(data)
                }
                else -> throw IllegalArgumentException("tipo OSC no soportado: " + a.javaClass)
            }
        }
        str(out, tags.toString())
        out.write(data.toByteArray())
        return out.toByteArray()
    }

    fun decode(data: ByteArray, len: Int): Msg? {
        try {
            var o = 0
            fun readStr(): String {
                var end = o
                while (end < len && data[end].toInt() != 0) end++
                val s = String(data, o, end - o, Charsets.US_ASCII)
                o = end + 1
                o += (4 - o % 4) % 4
                return s
            }
            val addr = readStr()
            if (o >= len) return Msg(addr, emptyList())
            val tags = readStr()
            val args = ArrayList<Any>()
            val bb = ByteBuffer.wrap(data, 0, len)
            for (t in tags.drop(1)) {
                when (t) {
                    'i' -> { args.add(bb.getInt(o)); o += 4 }
                    'f' -> { args.add(bb.getFloat(o)); o += 4 }
                    's' -> args.add(readStr())
                    'b' -> {
                        val n = bb.getInt(o); o += 4
                        if (n < 0 || o + n > len) return null
                        args.add(data.copyOfRange(o, o + n))
                        o += n + (4 - n % 4) % 4
                    }
                    else -> return null
                }
            }
            return Msg(addr, args)
        } catch (e: RuntimeException) {
            return null
        }
    }

    /** Blob de medidores X AIR: int32 LE (cantidad) + N x int16 LE. dB = valor / 256. */
    fun parseMeterBlob(blob: ByteArray): FloatArray {
        if (blob.size < 4) return FloatArray(0)
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val count = minOf(bb.getInt(0), (blob.size - 4) / 2)
        if (count <= 0) return FloatArray(0)
        return FloatArray(count) { bb.getShort(4 + 2 * it) / 256f }
    }

    fun meterBlob(values: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(4 + 2 * values.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(values.size)
        for (v in values) bb.putShort((v * 256f).toInt().coerceIn(-32768, 32767).toShort())
        return bb.array()
    }
}
