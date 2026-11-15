package com.danypfeiler.xairfeedback.core

import java.io.IOException
import java.net.DatagramSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hilo que conecta con la consola, analiza el RTA y corta. Avisa lo que pasa por el Listener. */
class Engine(
    private val cfg: Config,
    private val listener: Listener,
    private val socketSetup: ((DatagramSocket) -> Unit)? = null
) : Thread("xair-engine") {

    interface Listener {
        fun onLog(text: String)
        fun onStatus(text: String)
        fun onSpectrum(src: String, frame: FloatArray, candidates: IntArray)
        fun onCuts(cuts: List<CutRow>)
        fun onStopped()
        /** Nombres configurados en la consola, por clave de fuente ("ch1", "lr", "bus1"). */
        fun onNames(names: Map<String, String>) {}
    }

    companion object {
        /** Lee de la consola el nombre de cada canal, bus y Main LR. Los que no tienen nombre no aparecen. */
        fun fetchNames(m: XAirClient): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var misses = 0
            for (key in Scales.ALL_SOURCES) {
                val v = m.get("/" + Scales.srcTarget(key) + "/config/name", 1)
                if (v == null) {
                    if (++misses >= 3) break          // la consola no responde a esto: no insistir
                    continue
                }
                misses = 0
                val name = (v as? String)?.trim() ?: ""
                if (name.isNotEmpty()) out[key] = name
            }
            return out
        }
    }

    @Volatile private var stopFlag = false
    private var names: Map<String, String> = emptyMap()

    private fun srcName(key: String) = names[key] ?: Scales.srcLabel(key)
    private fun targetName(t: String) = names[Scales.targetKey(t)] ?: Scales.targetLabel(t)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    init { isDaemon = true }

    fun requestStop() {
        stopFlag = true
        interrupt()
    }

    private fun log(text: String) = listener.onLog("[" + clock.format(Date()) + "] " + text)

    private fun pause(ms: Long) {
        try { sleep(ms) } catch (e: InterruptedException) { }
    }

    private fun destTarget(src: String): String =
        if (cfg.dest == "auto" || !src.startsWith("ch")) Scales.srcTarget(src) else Scales.srcTarget(cfg.dest)

    override fun run() {
        try {
            work()
        } catch (e: Exception) {
            log("ERROR inesperado: $e")
        } finally {
            listener.onStatus("Detenido")
            listener.onStopped()
        }
    }

    private fun work() {
        val sources = if (cfg.sources.isEmpty()) listOf("ch1") else cfg.sources
        val m: XAirClient
        try {
            m = XAirClient(cfg.host, cfg.port, socketSetup)
        } catch (e: Exception) {
            log("ERROR: no pude abrir la conexion a ${cfg.host}: $e")
            return
        }
        listener.onStatus("Buscando la consola en ${cfg.host}...")
        var info: List<Any>? = null
        while (!stopFlag) {
            info = m.info()
            if (info != null) break
            listener.onStatus("Sin respuesta de ${cfg.host} - reintentando...")
            pause(1500)
        }
        if (info == null) {
            m.close()
            return
        }
        log("Conectado: " + info.drop(1).joinToString(" / "))
        names = fetchNames(m)
        listener.onNames(names)

        val oldSrc = (m.get("/-prefs/rta/source") as? Number)?.toInt()
        val oldPos = (m.get("/-prefs/rta/pos") as? Number)?.toInt()
        val prof = Profile.of(cfg.profile)
        val dets = sources.associateWith { Detector(prof, cfg.minFreq, cfg.maxFreq, cfg.minLevel) }
        val bank = NotchBank(m, cfg, { log(it) }, { targetName(it) })
        val names = sources.joinToString(", ") { srcName(it) }
        if (cfg.apply) {
            for (s in sources) bank.prepare(destTarget(s))
            log("MODO CORTAR. Analizo: $names. Agresividad: ${cfg.profile}.")
        } else {
            log("MODO SOLO AVISAR (no toco la consola). Analizo: $names.")
        }

        var idx = 0
        val lastWarn = HashMap<String, Double>()

        fun now() = System.currentTimeMillis() / 1000.0

        fun select(i: Int): Double {
            m.send("/-prefs/rta/source", Scales.srcIndex(sources[i]))
            m.send("/-prefs/rta/pos", 1)            // post-EQ
            dets.getValue(sources[i]).reset()
            return now()
        }

        var tSwitch = now()
        try { tSwitch = select(idx) } catch (e: IOException) { }
        var lastData = now()
        var lastRelease = now()
        listener.onStatus("Analizando " + srcName(sources[idx]))
        try {
            while (!stopFlag) {
                val fr: FloatArray?
                try {
                    fr = m.nextRta()
                } catch (e: IOException) {
                    listener.onStatus("Red caida (${e.message}) - reintentando...")
                    pause(2000)
                    continue
                }
                val t = now()
                if (fr == null) {
                    if (t - lastData > 3.0) {
                        listener.onStatus("Sin datos del analizador - reintentando...")
                        lastData = t
                        try {
                            m.subscribeRta(true)
                            tSwitch = select(idx)
                        } catch (e: IOException) {
                        }
                    }
                    continue
                }
                lastData = t
                val src = sources[idx]
                if (t - tSwitch < 0.25) continue          // dejar que el RTA se acomode tras cambiar de fuente
                val det = dets.getValue(src)
                val strong = det.update(fr)
                listener.onSpectrum(src, fr, det.lastCandidates)
                for (hit in strong) {
                    val where = "%s: feedback en %.0f Hz (%s)".format(srcName(src), hit.freq, hit.why)
                    if (cfg.apply) {
                        val act = try {
                            bank.handle(destTarget(src), hit, t, Scales.srcTarget(src))
                        } catch (e: IOException) {
                            null
                        }
                        if (act != null) {
                            log("$where -> $act")
                            listener.onCuts(bank.snapshot())
                        }
                    } else {
                        val key = src + ":" + hit.bin
                        if (t - (lastWarn[key] ?: 0.0) > 3.0) {
                            lastWarn[key] = t
                            log("$where -> cortaria ahi (solo aviso)")
                        }
                    }
                }
                if (cfg.apply && t - lastRelease > 5.0) {
                    lastRelease = t
                    try {
                        if (bank.releaseTick(t)) listener.onCuts(bank.snapshot())
                    } catch (e: IOException) {
                    }
                }
                if (sources.size > 1) {
                    val held = t - tSwitch
                    if (held >= cfg.dwell && (!det.busy() || held >= 4 * cfg.dwell)) {
                        idx = (idx + 1) % sources.size
                        try { tSwitch = select(idx) } catch (e: IOException) { }
                        listener.onStatus("Analizando " + srcName(sources[idx]))
                    }
                }
            }
        } finally {
            try {
                if (cfg.apply) {
                    if (cfg.restoreOnStop) {
                        if (bank.restoreAll()) log("EQ y volumen restaurados a como estaban antes de iniciar.")
                    } else if (bank.hasChanges) {
                        log("Deje puestos los cortes en la consola (opcion 'restaurar' desactivada).")
                    }
                    listener.onCuts(bank.snapshot())
                }
                if (oldSrc != null) m.send("/-prefs/rta/source", oldSrc)
                if (oldPos != null) m.send("/-prefs/rta/pos", oldPos)
            } catch (e: IOException) {
            }
            m.close()
        }
    }
}
