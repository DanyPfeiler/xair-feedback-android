package com.danypfeiler.xairfeedback.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Simulador de una XR12: responde por OSC en 127.0.0.1 igual que la consola y modela el feedback
 * como un lazo que crece mientras su ganancia sea positiva y se apaga cuando los cortes del EQ
 * (canal + camino PA o monitor) o la bajada del fader la vuelven negativa.
 */
class Simulator : Thread("xair-sim") {
    class Tone(val ch: Int, val freq: Double, val path: String, val excess: Double) { var level = -60.0 }

    private val sock = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val port: Int = sock.localPort
    private val params = HashMap<String, Any>()
    private val lock = Any()
    private val tones = ArrayList<Tone>()
    private val rnd = Random()
    @Volatile var bandPlaying = true
    @Volatile private var stopFlag = false
    private var client: SocketAddress? = null
    private var subUntil = 0L
    private var noteT = 0L
    private var notes = mapOf(1 to 220.0, 2 to 330.0, 4 to 262.0)

    init {
        isDaemon = true
        sock.soTimeout = 20
        params["/-prefs/rta/source"] = 0
        params["/-prefs/rta/pos"] = 1
    }

    /** path: "lr" (por el PA) o "bus/1", "bus/2" (por un monitor). excess: dB de ganancia de lazo. */
    fun addFeedback(channel: Int, freq: Double, path: String = "lr", excess: Double = 5.0) {
        synchronized(lock) { tones.add(Tone(channel, freq, path, excess)) }
    }

    fun clearFeedback() { synchronized(lock) { tones.clear() } }
    fun toneLevels(): List<Double> = synchronized(lock) { tones.map { it.level } }
    fun param(path: String): Any? = synchronized(lock) { params[path] }
    fun paramKeys(): Set<String> = synchronized(lock) { HashSet(params.keys) }
    fun requestStop() { stopFlag = true }

    private val demoNames = mapOf("/ch/01/config/name" to "Voz Lider", "/ch/02/config/name" to "Coros",
                                  "/ch/03/config/name" to "Pista", "/ch/04/config/name" to "Acordeon")

    private fun default(path: String): Any? = if (path.endsWith("/config/name")) (demoNames[path] ?: "") else when (path.substringAfterLast('/')) {
        "f" -> 0.5f
        "g" -> 0.5f
        "q" -> 0.4647f
        "type" -> 2
        "on" -> 1
        "mode" -> 0
        "fader" -> 0.75f
        else -> null
    }

    private fun value(path: String): Any? = params[path] ?: default(path)
    private fun dbl(path: String): Double = (value(path) as? Number)?.toDouble() ?: 0.0
    private fun int(path: String): Int = (value(path) as? Number)?.toInt() ?: 0

    /** Atenuacion (dB, negativa) del EQ de "ch/01", "lr" o "bus/1" en esa frecuencia. */
    private fun eqAtt(target: String, f: Double): Double {
        if (int("/$target/eq/on") == 0) return 0.0
        var att = 0.0
        val last = if (target.startsWith("ch/")) 4 else 6
        for (b in 1..last) {
            val g = Scales.floatToGain(dbl("/$target/eq/$b/g"))
            if (abs(g) < 0.05 || int("/$target/eq/$b/type") != 2) continue
            val fc = Scales.floatToFreq(dbl("/$target/eq/$b/f"))
            val q = Scales.floatToQ(dbl("/$target/eq/$b/q"))
            val x = abs(ln(f / fc) / ln(2.0)) * q * 1.2
            att += g * exp(-x * x)
        }
        return att
    }

    private fun stepTones(dt: Double) {
        for (t in tones) {
            val chT = "ch/%02d".format(t.ch)
            val net = t.excess + eqAtt(chT, t.freq) + eqAtt(t.path, t.freq) + Scales.faderToDb(dbl("/$chT/mix/fader"))
            t.level = (t.level + (if (net > 0) 4.0 else 12.0) * net * dt).coerceIn(-85.0, -3.0)
            if (t.level <= -84.0 && net > 0) t.level = -60.0     // el lazo vuelve a arrancar desde el ruido
        }
    }

    private fun tone(fl: FloatArray, f: Double, lvl: Double) {
        val x = Scales.freqBin(f)
        for (k in x.toInt() - 2..x.toInt() + 3) {
            if (k in 0..99) fl[k] = maxOf(fl[k], (lvl - 10.0 * (k - x) * (k - x)).toFloat())
        }
    }

    private fun jit(a: Double) = (rnd.nextDouble() * 2 - 1) * a

    private fun music(fl: FloatArray, ch: Int, gain: Double) {
        when (ch) {
            1, 2 -> {                                        // voz: nota con armonicos + cuerpo ancho
                val f0 = notes.getValue(ch)
                for (k in 1..8) tone(fl, f0 * k, -22 - 3.5 * k + gain + jit(2.0))
                for (i in 45 until 80) fl[i] = maxOf(fl[i], (-50 + gain + jit(4.0)).toFloat())
            }
            4 -> {                                           // acordeon: acorde rico en armonicos
                val f0 = notes.getValue(4)
                for (r in doubleArrayOf(1.0, 1.26, 1.5)) {
                    for (k in 1..6) tone(fl, f0 * r * k, -24 - 3.0 * k + gain + jit(1.5))
                }
            }
            3 -> {                                           // pista: banda ancha
                for (i in 10 until 90) fl[i] = maxOf(fl[i], (-38 - abs(i - 45) * 0.35 + gain + jit(4.0)).toFloat())
            }
        }
    }

    private fun frame(): FloatArray {
        val now = System.currentTimeMillis()
        if (now - noteT > 450) {                             // las notas van cambiando
            noteT = now
            fun pick(vararg v: Double) = v[rnd.nextInt(v.size)]
            notes = mapOf(1 to pick(196.0, 220.0, 247.0, 262.0, 294.0, 330.0),
                          2 to pick(262.0, 294.0, 330.0, 392.0),
                          4 to pick(196.0, 220.0, 262.0, 294.0))
        }
        val src = int("/-prefs/rta/source")
        val fl = FloatArray(100) { (-78 + jit(1.5)).toFloat() }
        val chans: List<Int>
        val bus: String?
        when {
            src in 0..15 -> { chans = listOf(src + 1); bus = null }
            src == 31 -> { chans = listOf(1, 2, 3, 4); bus = "lr" }
            else -> { chans = listOf(1, 2, 4); bus = "bus/" + (src - 20) }
        }
        if (bandPlaying) for (c in chans) music(fl, c, if (bus == null) 0.0 else -4.0)
        for (t in tones) {
            if (t.ch in chans) tone(fl, t.freq, if (bus == null) t.level else t.level + eqAtt(bus, t.freq))
        }
        return fl
    }

    private fun reply(data: ByteArray, to: SocketAddress) {
        try { sock.send(DatagramPacket(data, data.size, to)) } catch (e: Exception) { }
    }

    override fun run() {
        val buf = ByteArray(4096)
        var last = System.currentTimeMillis()
        while (!stopFlag) {
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                val from = p.socketAddress
                val msg = Osc.decode(buf, p.length)
                if (msg != null) {
                    client = from
                    when {
                        msg.addr == "/meters" -> subUntil = System.currentTimeMillis() + 10000
                        msg.addr == "/xinfo" -> reply(Osc.encode("/xinfo", "127.0.0.1", "XR12-Simulada", "XR12", "sim-1.0"), from)
                        msg.args.isNotEmpty() -> synchronized(lock) { params[msg.addr] = msg.args[0] }
                        else -> {
                            val v = synchronized(lock) { value(msg.addr) }
                            if (v != null) reply(Osc.encode(msg.addr, v), from)
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
            } catch (e: Exception) {
                if (stopFlag) break
            }
            val now = System.currentTimeMillis()
            if (now - last >= 50) {
                val dt = (now - last) / 1000.0
                last = now
                val c = client
                val blob: ByteArray? = synchronized(lock) {
                    stepTones(dt)
                    if (c != null && now < subUntil) Osc.meterBlob(frame()) else null
                }
                if (blob != null && c != null) reply(Osc.encode("/meters/4", blob), c)
            }
        }
        try { sock.close() } catch (e: Exception) { }
    }
}
