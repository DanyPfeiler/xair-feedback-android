package com.danypfeiler.xairfeedback.core

import kotlin.math.abs

/**
 * Administra los cortes en uno o varios EQ (canal, Main LR, buses), la bajada de volumen de
 * ultimo recurso, y deja todo como estaba al restaurar.
 */
class NotchBank(
    private val m: Mixer, private val cfg: Config, private val log: (String) -> Unit,
    private val label: (String) -> String = { Scales.targetLabel(it) }
) {
    private class Saved(val f: Float, val g: Float, val q: Float, val type: Int)
    private class Target(val bands: List<Int>, val saved: Map<Int, Saved>, val eqOn: Boolean)
    private class Notch(val target: String, val band: Int, val binf: Double, val freq: Double, var depth: Double,
                        var tAction: Double, var tTrig: Double, var levelAt: Double)
    private class Fader(val orig: Double) { var red = 0.0; var t = 0.0; var alerted = false }

    private val p = Profile.of(cfg.profile)
    private val targets = HashMap<String, Target?>()
    private val notches = ArrayList<Notch>()
    private val faders = HashMap<String, Fader?>()
    private val fullAlert = HashMap<String, Double>()

    val hasChanges: Boolean get() = notches.isNotEmpty() || faders.values.any { it != null && it.red > 0 }

    private fun path(target: String, band: Int, leaf: String) = "/$target/eq/$band/$leaf"

    private fun num(v: Any?): Number? = v as? Number

    /** Lee y guarda el estado actual del EQ de ese destino. true si se puede usar. */
    fun prepare(target: String): Boolean {
        if (targets.containsKey(target)) return targets[target] != null
        val bands = if (target.startsWith("ch/")) cfg.chBands else cfg.busBands
        val name = label(target)
        val eqOn = num(m.get("/$target/eq/on"))
        if (eqOn == null) {
            log("No pude leer el EQ de $name: ahi solo voy a avisar.")
            targets[target] = null
            return false
        }
        if (!target.startsWith("ch/")) {
            val mode = num(m.get("/$target/eq/mode", 1))
            if (mode != null && mode.toInt() != 0) {
                log("AVISO: el EQ de $name esta en modo grafico (GEQ/TEQ); los cortes parametricos " +
                    "pueden no actuar. Pasalo a PEQ en la app.")
            }
        }
        val saved = HashMap<Int, Saved>()
        for (b in bands) {
            val f = num(m.get(path(target, b, "f")))
            val g = num(m.get(path(target, b, "g")))
            val q = num(m.get(path(target, b, "q")))
            val ty = num(m.get(path(target, b, "type")))
            if (f == null || g == null || q == null || ty == null) {
                log("No pude leer la banda $b de $name: no la uso.")
                continue
            }
            saved[b] = Saved(f.toFloat(), g.toFloat(), q.toFloat(), ty.toInt())
            val gdb = Scales.floatToGain(g.toDouble())
            if (abs(gdb) > 0.4) {
                log("AVISO: la banda %d de %s tiene un ajuste tuyo (%+.1f dB en %.0f Hz). Si hace falta la voy a usar y la devuelvo al detener."
                    .format(b, name, gdb, Scales.floatToFreq(f.toDouble())))
            }
        }
        if (saved.isEmpty()) {
            targets[target] = null
            return false
        }
        if (eqOn.toInt() == 0) {
            log("El EQ de $name estaba apagado: lo enciendo (vuelve a su estado al detener).")
            m.send("/$target/eq/on", 1)
        }
        targets[target] = Target(saved.keys.sorted(), saved, eqOn.toInt() != 0)
        return true
    }

    private fun write(n: Notch) {
        m.send(path(n.target, n.band, "type"), Scales.EQ_TYPE_PEQ)
        m.send(path(n.target, n.band, "f"), Scales.freqToFloat(n.freq).toFloat())
        m.send(path(n.target, n.band, "q"), Scales.qToFloat(p.q).toFloat())
        m.send(path(n.target, n.band, "g"), Scales.gainToFloat(-n.depth).toFloat())
    }

    private fun restoreBand(target: String, band: Int) {
        val s = targets[target]?.saved?.get(band) ?: return
        m.send(path(target, band, "g"), s.g)
        m.send(path(target, band, "f"), s.f)
        m.send(path(target, band, "q"), s.q)
        m.send(path(target, band, "type"), s.type)
    }

    /** Ultimo recurso: baja 2 dB el fader del canal de origen (hasta faderMax). */
    private fun faderStep(srcT: String?, hit: Hit, now: Double, why: String): String? {
        val key = srcT ?: "?"
        if (!(cfg.faderGuard && srcT != null && srcT.startsWith("ch/"))) {
            if (now - (fullAlert[key] ?: 0.0) < 15) return null
            fullAlert[key] = now
            return "ALERTA: feedback en %.0f Hz y %s. Hay que bajar el volumen a mano.".format(hit.freq, why)
        }
        val name = label(srcT)
        if (!faders.containsKey(srcT)) {
            val x = num(m.get("/$srcT/mix/fader"))
            faders[srcT] = if (x == null) null else Fader(x.toDouble())
        }
        val st = faders[srcT] ?: return null
        if (now - st.t < 1.5) return null
        if (st.red + 2.0 > cfg.faderMax + 1e-6) {
            if (st.alerted) return null
            st.alerted = true
            return ("ALERTA: %s ya tiene los cortes y la bajada de volumen al maximo (-%.0f dB) y %.0f Hz sigue " +
                    "sonando. Hay que bajarlo a mano.").format(name, st.red, hit.freq)
        }
        st.red += 2.0
        st.t = now
        m.send("/$srcT/mix/fader", Scales.dbToFader(Scales.faderToDb(st.orig) - st.red).toFloat())
        return "%s: bajo el volumen de %s a -%.0f dB (ultimo recurso)".format(why, name, st.red)
    }

    /** Aplica o profundiza un corte; si no alcanza, pasa al Main LR y por ultimo al fader. */
    fun handle(target: String, hit: Hit, now: Double, srcT: String?): String? {
        val chain = ArrayList<String>()
        chain.add(target)
        if (cfg.overflowLr && target.startsWith("ch/") && cfg.busBands.isNotEmpty()) chain.add("lr")
        // otro destino acaba de cortar esa misma frecuencia: darle tiempo
        if (notches.any { it.target !in chain && abs(it.binf - hit.binf) <= 1.3 && now - it.tAction < 3.0 }) return null
        val near = notches.filter { it.target in chain && abs(it.binf - hit.binf) <= 1.3 }
        if (near.isNotEmpty()) {
            for (n in near) n.tTrig = now
            if (near.any { now - it.tAction < p.cooldown }) return null
            val last = near.last()
            if (hit.level < last.levelAt - 3.0) {           // ya esta bajando: esperar
                last.tAction = now
                last.levelAt = hit.level
                return null
            }
            for (n in near) {
                if (n.depth + p.step <= p.maxCut + 1e-6) {
                    n.depth += p.step
                    n.tAction = now
                    n.levelAt = hit.level
                    write(n)
                    return "profundizo a -%.0f dB el corte de %.0f Hz en %s (banda %d)"
                        .format(n.depth, n.freq, label(n.target), n.band)
                }
            }
        }
        for (t in chain) {
            if (near.any { it.target == t }) continue        // ahi ya hay un corte al maximo para esto
            if (!prepare(t)) continue
            val info = targets[t] ?: continue
            val used = notches.filter { it.target == t }.map { it.band }
            val free = info.bands.firstOrNull { it !in used } ?: continue
            val n = Notch(t, free, hit.binf, hit.freq, p.firstCut, now, now, hit.level)
            notches.add(n)
            write(n)
            val extra = if (t == target) "" else " (sin bandas libres en " + label(target) + ")"
            return "corte nuevo: %.0f Hz, -%.0f dB en %s (banda %d)%s"
                .format(n.freq, n.depth, label(t), n.band, extra)
        }
        if (near.isNotEmpty()) {
            for (n in near) n.tAction = now
            return faderStep(srcT, hit, now, "corte de %.0f Hz al maximo".format(hit.freq))
        }
        return faderStep(srcT, hit, now, "no quedan bandas libres")
    }

    /** Afloja de a poco los cortes que hace rato no vuelven a dispararse. */
    fun releaseTick(now: Double): Boolean {
        val rel = cfg.releaseMin * 60.0
        if (rel <= 0) return false
        var changed = false
        for (n in ArrayList(notches)) {
            if (now - n.tTrig >= rel) {
                n.depth -= p.step
                n.tTrig = now
                n.tAction = now
                changed = true
                if (n.depth <= 0.01) {
                    restoreBand(n.target, n.band)
                    notches.remove(n)
                    log("solte el corte de %.0f Hz en %s (no volvio a sonar)".format(n.freq, label(n.target)))
                } else {
                    write(n)
                    log("aflojo a -%.0f dB el corte de %.0f Hz en %s".format(n.depth, n.freq, label(n.target)))
                }
            }
        }
        return changed
    }

    fun snapshot(): List<CutRow> {
        val rows = ArrayList<CutRow>()
        for (n in notches) rows.add(CutRow(label(n.target), n.band.toString(), n.freq, n.depth, n.binf))
        for ((t, st) in faders) {
            if (st != null && st.red > 0) rows.add(CutRow(label(t), "volumen", null, st.red, null))
        }
        return rows
    }

    fun restoreAll(): Boolean {
        var done = false
        for ((target, info) in targets) {
            if (info == null) continue
            for (b in info.saved.keys) restoreBand(target, b)
            if (!info.eqOn) m.send("/$target/eq/on", 0)
            done = true
        }
        for ((t, st) in faders) {
            if (st != null && st.red > 0) {
                m.send("/$t/mix/fader", st.orig.toFloat())
                done = true
            }
        }
        faders.clear()
        notches.clear()
        return done
    }
}
