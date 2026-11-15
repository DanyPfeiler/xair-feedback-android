package com.danypfeiler.xairfeedback.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

class Hit(val bin: Int, val binf: Double, val freq: Double, val level: Double, val why: String)

/**
 * Un pico se considera feedback cuando:
 *  - sobresale >= promDb de su entorno y es angosto (>= narrowDb sobre los bins a +-2)
 *  - se mantiene en el mismo lugar y, en >= minFrames cuadros, sin familia armonica
 *    (energia a 2x, 3x, 4x, 5x o 1/2, 1/3... de nivel comparable -> eso es una nota)
 *  - y ademas crecio >= riseDb desde que aparecio, o lleva >= sustainFrames cuadros
 *  Red de seguridad: un pico fijo durante longFrames cuadros se trata como feedback igual.
 */
class Detector(private val p: Profile, minFreq: Double, maxFreq: Double, private val minLevel: Double) {
    private class Track(var bin: Int, level: Double, var lastSeen: Long) {
        var count = 1
        var clean = 0
        val firstLevel = level
        var level = level
    }

    private val minBin = maxOf(7, ceil(Scales.freqBin(minFreq)).toInt())
    private val maxBin = minOf(92, Scales.freqBin(maxFreq).toInt())
    private var tracks = ArrayList<Track>()
    private var frameNo = 0L

    var lastCandidates = IntArray(0)
        private set

    fun reset() { tracks = ArrayList() }

    /** true si hay algun pico en seguimiento (conviene no rotar la fuente todavia). */
    fun busy() = tracks.any { it.count >= 2 }

    private fun candidates(f: FloatArray): IntArray {
        val out = ArrayList<Int>()
        for (i in 7..92) {
            val v = f[i]
            if (v < minLevel || v < f[i - 1] || v < f[i + 1]) continue
            var side = 0.0
            for (k in i - 6..i - 3) side += f[k]
            for (k in i + 3..i + 6) side += f[k]
            if (v - side / 8.0 >= p.promDb && v - maxOf(f[i - 2], f[i + 2]) >= p.narrowDb) out.add(i)
        }
        return out.toIntArray()
    }

    private fun hasFamily(i: Int, f: FloatArray): Boolean {
        val v = f[i]
        for (h in 2..5) {
            val off = (10.0 * ln(h.toDouble()) / ln(2.0)).roundToInt()      // ~10 bins por octava
            for (up in booleanArrayOf(true, false)) {
                val tgt = if (up) i + off else i - off
                val margin = if (up) p.familyUpDb else p.familyDb
                if (tgt < 6 || tgt > 93) continue
                var j = tgt - 1
                if (f[tgt] > f[j]) j = tgt
                if (f[tgt + 1] > f[j]) j = tgt + 1
                if (f[j] < f[j - 1] || f[j] < f[j + 1]) continue          // es la falda de otro pico
                var around = 0.0
                for (k in j - 5..j - 3) around += f[k]
                for (k in j + 3..j + 5) around += f[k]
                if (f[j] >= v - margin && f[j] - around / 6.0 >= 3.0) return true
            }
        }
        return false
    }

    fun update(f: FloatArray): List<Hit> {
        frameNo++
        val cands = candidates(f)
        lastCandidates = cands
        val strong = ArrayList<Hit>()
        val used = HashSet<Track>()
        for (i in cands) {
            if (i < minBin || i > maxBin) continue
            var tr = tracks.firstOrNull { abs(it.bin - i) <= 1 && it !in used }
            if (tr == null) {
                tr = Track(i, f[i].toDouble(), frameNo)
                tracks.add(tr)
            } else {
                tr.count++
                tr.bin = i
                tr.level = f[i].toDouble()
                tr.lastSeen = frameNo
            }
            used.add(tr)
            if (!hasFamily(i, f)) tr.clean++
            val rise = tr.level - tr.firstLevel
            val stuck = tr.count >= p.longFrames
            val growing = rise >= p.riseDb && tr.clean >= tr.count * 0.5
            val sustained = tr.count >= p.sustainFrames && tr.clean >= p.sustainFrames * 0.6
            if (stuck || (tr.clean >= p.minFrames && (growing || sustained))) {
                val a = f[i - 1].toDouble()
                val b = f[i].toDouble()
                val c = f[i + 1].toDouble()
                val den = a - 2 * b + c
                val d = if (den != 0.0) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
                val why = when {
                    stuck && tr.clean < p.minFrames -> "fijo hace rato"
                    rise >= p.riseDb -> "creciendo %+.0f dB".format(rise)
                    else -> "sostenido"
                }
                strong.add(Hit(i, i + d, Scales.binFreq(i + d), b, why))
            }
        }
        tracks = ArrayList(tracks.filter { frameNo - it.lastSeen <= 2 })
        return strong
    }
}
