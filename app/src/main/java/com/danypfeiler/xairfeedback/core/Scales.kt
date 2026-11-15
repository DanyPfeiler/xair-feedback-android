package com.danypfeiler.xairfeedback.core

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/** Conversiones entre unidades reales y los valores 0..1 que usa la consola. */
object Scales {
    const val EQ_TYPE_PEQ = 2          // LCut, LShv, PEQ, VEQ, HShv, HCut
    const val N_CHANNELS = 12
    const val N_BUSES = 2

    fun freqToFloat(f: Double) = (ln(f / 20.0) / ln(1000.0)).coerceIn(0.0, 1.0)
    fun floatToFreq(x: Double) = 20.0 * 1000.0.pow(x)
    fun gainToFloat(g: Double) = ((g + 15.0) / 30.0).coerceIn(0.0, 1.0)
    fun floatToGain(x: Double) = -15.0 + 30.0 * x
    fun qToFloat(q: Double) = (ln(q / 10.0) / ln(0.03)).coerceIn(0.0, 1.0)
    fun floatToQ(x: Double) = 10.0 * 0.03.pow(x)

    /** Fader X AIR (0..1) a dB. 0.75 = 0 dB, 1.0 = +10 dB. */
    fun faderToDb(x: Double): Double = when {
        x >= 0.5 -> x * 40.0 - 30.0
        x >= 0.25 -> x * 80.0 - 50.0
        x >= 0.0625 -> x * 160.0 - 70.0
        else -> x * 480.0 - 90.0
    }

    fun dbToFader(d: Double): Double = when {
        d >= -10.0 -> (d + 30.0) / 40.0
        d >= -30.0 -> (d + 50.0) / 80.0
        d >= -60.0 -> (d + 70.0) / 160.0
        else -> (d + 90.0) / 480.0
    }.coerceIn(0.0, 1.0)

    /** Frecuencia del bin (puede ser fraccionario) del RTA: 100 bandas log de 20 Hz a 20 kHz. */
    fun binFreq(i: Double) = 20.0 * 1000.0.pow(i / 99.0)
    fun freqBin(f: Double) = 99.0 * log10(f / 20.0) / 3.0

    // ---- fuentes: "ch1".."ch12", "lr", "bus1", "bus2"

    /** Indice de /-prefs/rta/source. Ch01-16 = 0-15, Bus1-6 = 21-26, Main LR = 31. */
    fun srcIndex(key: String): Int = when {
        key == "lr" -> 31
        key.startsWith("bus") -> 20 + key.substring(3).toInt()
        else -> key.substring(2).toInt() - 1
    }

    fun srcLabel(key: String): String = when {
        key == "lr" -> "Main LR (PA)"
        key.startsWith("bus") -> "Bus " + key.substring(3) + " (monitor)"
        else -> "Canal " + key.substring(2)
    }

    /** Ruta OSC del EQ que corresponde a esa fuente. */
    fun srcTarget(key: String): String = when {
        key == "lr" -> "lr"
        key.startsWith("bus") -> "bus/" + key.substring(3)
        else -> "ch/%02d".format(key.substring(2).toInt())
    }

    /** "ch/01" -> "ch1", "bus/1" -> "bus1", "lr" -> "lr". */
    fun targetKey(t: String): String = when {
        t == "lr" -> "lr"
        t.startsWith("bus/") -> "bus" + t.substring(4)
        else -> "ch" + t.substring(3).toInt()
    }

    val ALL_SOURCES: List<String> =
        (1..N_CHANNELS).map { "ch$it" } + "lr" + (1..N_BUSES).map { "bus$it" }

    fun targetLabel(t: String): String = when {
        t == "lr" -> "Main LR"
        t.startsWith("bus/") -> "Bus " + t.substring(4)
        else -> "Canal " + t.substring(3).toInt()
    }
}
