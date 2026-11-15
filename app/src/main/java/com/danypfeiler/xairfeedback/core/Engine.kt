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
    /** Se consulta en cada (re)conexion, para atar el socket a la red Wi-Fi vigente en ese momento. */
    private val socketSetup: (() -> ((DatagramSocket) -> Unit)?)? = null
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

    /** Conexion intercambiable: el banco de cortes sobrevive a las reconexiones. */
    private class Link : Mixer {
        @Volatile var client: XAirClient? = null
        override fun send(path: String, vararg args: Any) {
            (client ?: throw IOException("sin conexion")).send(path, *args)
        }
        override fun get(path: String, tries: Int): Any? = client?.get(path, tries)
    }

    private fun now() = System.currentTimeMillis() / 1000.0

    private fun open(): XAirClient = XAirClient(cfg.host, cfg.port, socketSetup?.invoke())

    private fun work() {
        val sources = if (cfg.sources.isEmpty()) listOf("ch1") else cfg.sources
        val prof = Profile.of(cfg.profile)
        val link = Link()
        val bank = NotchBank(link, cfg, { log(it) }, { targetName(it) })
        val dets = sources.associateWith { Detector(prof, cfg.minFreq, cfg.maxFreq, cfg.minLevel) }
        var oldSrc: Int? = null
        var oldPos: Int? = null
        var everConnected = false
        var lost = false
        try {
            while (!stopFlag) {
                try {
                    val m = open()
                    link.client = m
                    listener.onStatus((if (everConnected) "Reconectando con " else "Buscando la consola en ") + cfg.host + "...")
                    var info: List<Any>? = null
                    var misses = 0
                    while (!stopFlag) {
                        info = m.info()
                        if (info != null) break
                        listener.onStatus("Sin respuesta de ${cfg.host} - reintentando...")
                        if (++misses >= 4) throw IOException("sin respuesta")   // reabrir el socket: pudo cambiar la red
                        pause(1500)
                    }
                    if (info == null) break
                    if (!everConnected) {
                        everConnected = true
                        log("Conectado: " + info.drop(1).joinToString(" / "))
                        names = fetchNames(m)
                        listener.onNames(names)
                        oldSrc = (m.get("/-prefs/rta/source") as? Number)?.toInt()
                        oldPos = (m.get("/-prefs/rta/pos") as? Number)?.toInt()
                        val list = sources.joinToString(", ") { srcName(it) }
                        if (cfg.apply) {
                            for (s in sources) bank.prepare(destTarget(s))
                            log("MODO CORTAR. Analizo: $list. Agresividad: ${cfg.profile}.")
                        } else {
                            log("MODO SOLO AVISAR (no toco la consola). Analizo: $list.")
                        }
                    } else {
                        val n = if (cfg.apply) bank.reapply() else 0
                        log("Reconectado con la consola." + if (n > 0) " Volvi a aplicar $n cortes." else "")
                    }
                    lost = false
                    session(m, sources, dets, bank)
                } catch (e: IOException) {
                    if (stopFlag) break
                    if (everConnected && !lost) {
                        lost = true
                        log("AVISO: se perdio la conexion con la consola (${e.message}). Reintento hasta recuperarla.")
                    }
                    listener.onStatus("Sin conexion - reintentando...")
                    link.client?.close()
                    link.client = null
                    pause(2000)
                }
            }
        } finally {
            finishUp(link, bank, oldSrc, oldPos)
        }
    }

    /** Al detener: restaurar la consola. Si justo no hay conexion, un ultimo intento y, si falla, avisar. */
    private fun finishUp(link: Link, bank: NotchBank, oldSrc: Int?, oldPos: Int?) {
        try {
            val pending = cfg.apply && cfg.restoreOnStop && bank.hasChanges
            if (link.client == null && (pending || oldSrc != null)) {
                try {
                    val m = open()
                    if (m.info(1500) != null) link.client = m else m.close()
                } catch (e: Exception) {
                }
            }
            if (link.client == null) {
                if (pending) log("AVISO: no hay conexion con la consola, asi que NO pude quitar los cortes. " +
                                 "Siguen puestos: revisa el EQ y el volumen en la app X AIR.")
                return
            }
            if (cfg.apply) {
                if (cfg.restoreOnStop) {
                    if (bank.restoreAll()) log("EQ y volumen restaurados a como estaban antes de iniciar.")
                } else if (bank.hasChanges) {
                    log("Deje puestos los cortes en la consola (opcion 'restaurar' desactivada).")
                }
                listener.onCuts(bank.snapshot())
            }
            if (oldSrc != null) link.send("/-prefs/rta/source", oldSrc)
            if (oldPos != null) link.send("/-prefs/rta/pos", oldPos)
        } catch (e: IOException) {
            log("AVISO: fallo la red al restaurar la consola; revisa el EQ en la app X AIR.")
        } finally {
            link.client?.close()
        }
    }

    /** Analiza hasta que se pida detener. Lanza IOException si se pierde la conexion. */
    private fun session(m: XAirClient, sources: List<String>, dets: Map<String, Detector>, bank: NotchBank) {
        var idx = 0
        val lastWarn = HashMap<String, Double>()

        fun select(i: Int): Double {
            m.send("/-prefs/rta/source", Scales.srcIndex(sources[i]))
            m.send("/-prefs/rta/pos", 1)            // post-EQ
            dets.getValue(sources[i]).reset()
            return now()
        }

        var tSwitch = select(idx)
        m.subscribeRta(true)
        var lastData = now()
        var lastNudge = now()
        var lastRelease = now()
        listener.onStatus("Analizando " + srcName(sources[idx]))
        while (!stopFlag) {
            val fr = m.nextRta()
            val t = now()
            if (fr == null) {
                if (t - lastData > 8.0) throw IOException("sin datos del analizador")
                if (t - lastData > 2.5 && t - lastNudge > 2.5) {       // primero insistir por el mismo socket
                    lastNudge = t
                    listener.onStatus("Sin datos del analizador - reintentando...")
                    m.subscribeRta(true)
                    tSwitch = select(idx)
                }
                continue
            }
            if (t - lastData > 2.5) listener.onStatus("Analizando " + srcName(sources[idx]))
            lastData = t
            val src = sources[idx]
            if (t - tSwitch < 0.25) continue          // dejar que el RTA se acomode tras cambiar de fuente
            val det = dets.getValue(src)
            val strong = det.update(fr)
            listener.onSpectrum(src, fr, det.lastCandidates)
            for (hit in strong) {
                val where = "%s: feedback en %.0f Hz (%s)".format(srcName(src), hit.freq, hit.why)
                if (cfg.apply) {
                    val act = bank.handle(destTarget(src), hit, t, Scales.srcTarget(src))
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
                if (bank.releaseTick(t)) listener.onCuts(bank.snapshot())
            }
            if (sources.size > 1) {
                val held = t - tSwitch
                if (held >= cfg.dwell && (!det.busy() || held >= 4 * cfg.dwell)) {
                    idx = (idx + 1) % sources.size
                    tSwitch = select(idx)
                    listener.onStatus("Analizando " + srcName(sources[idx]))
                }
            }
        }
    }
}
