package com.danypfeiler.xairfeedback

import com.danypfeiler.xairfeedback.core.Config
import com.danypfeiler.xairfeedback.core.CutRow
import com.danypfeiler.xairfeedback.core.Engine
import com.danypfeiler.xairfeedback.core.Scales
import com.danypfeiler.xairfeedback.core.Simulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Pruebas del motor completo contra el simulador (UDP en localhost). */
class EngineSimTest {
    private class Result(val logs: List<String>, val during: Map<String, Any?>, val after: Map<String, Any?>,
                         val finalLevels: List<Double>)

    private fun run(cfg: Config, secs: Double, setup: (Simulator) -> Unit = {}): Result {
        val sim = Simulator()
        sim.start()
        setup(sim)
        val logs = Collections.synchronizedList(ArrayList<String>())
        val engine = Engine(cfg.copy(host = "127.0.0.1", port = sim.port, dwell = 0.6), object : Engine.Listener {
            override fun onLog(text: String) { logs.add(text); println(text) }
            override fun onStatus(text: String) {}
            override fun onSpectrum(src: String, frame: FloatArray, candidates: IntArray) {}
            override fun onCuts(cuts: List<CutRow>) {}
            override fun onStopped() {}
        })
        engine.start()
        Thread.sleep((secs * 1000).toLong())
        val finals = sim.toneLevels()
        val during = sim.paramKeys().associateWith { sim.param(it) }
        engine.requestStop()
        engine.join(5000)
        Thread.sleep(200)
        val after = sim.paramKeys().associateWith { sim.param(it) }
        sim.requestStop()
        return Result(ArrayList(logs), during, after, finals)
    }

    private fun gain(map: Map<String, Any?>, path: String): Double =
        Scales.floatToGain(((map[path] as? Number) ?: 0.5f).toDouble())

    @Test fun cortaEnElCanalYRestaura() {
        val r = run(Config(sources = listOf("ch1"), apply = true), 12.0) { it.addFeedback(1, 2500.0) }
        assertTrue("el feedback se apago", r.finalLevels[0] < -60)
        assertTrue("hubo corte en la banda 3 del canal 1", gain(r.during, "/ch/01/eq/3/g") < -2)
        assertEquals("EQ restaurado", 0.0, gain(r.after, "/ch/01/eq/3/g"), 0.05)
        assertEquals("fuente del RTA restaurada", 0, (r.after["/-prefs/rta/source"] as Number).toInt())
    }

    @Test fun soloAvisarNoEscribe() {
        val r = run(Config(sources = listOf("ch1"), apply = false), 8.0) { it.addFeedback(1, 1200.0) }
        assertTrue(r.logs.any { "solo aviso" in it })
        assertFalse(r.during.keys.any { "/eq/" in it })
    }

    @Test fun detectaEnCanalYCortaEnMainLr() {
        val r = run(Config(sources = listOf("ch1"), dest = "lr", apply = true), 12.0) { it.addFeedback(1, 800.0) }
        assertTrue(r.finalLevels[0] < -60)
        assertTrue(gain(r.during, "/lr/eq/4/g") < -2)
        assertFalse(r.during.containsKey("/ch/01/eq/3/g"))
    }

    @Test fun feedbackPorMonitorSeCortaEnElBus() {
        val r = run(Config(sources = listOf("bus1"), apply = true), 12.0) { it.addFeedback(2, 500.0, "bus/1") }
        assertTrue(r.finalLevels[0] < -60)
        assertTrue(gain(r.during, "/bus/1/eq/4/g") < -2)
    }

    @Test fun musicaSinFeedbackNoCorta() {
        val r = run(Config(sources = listOf("ch1", "ch2", "ch4", "lr"), apply = true, profile = "medio"), 20.0)
        assertEquals(0, r.logs.count { "corte nuevo" in it })
    }

    @Test fun sinBandasSigueEnMainLr() {
        val r = run(Config(sources = listOf("ch1"), apply = true), 26.0) { s ->
            for (f in doubleArrayOf(400.0, 1000.0, 1450.0, 2500.0, 6500.0)) s.addFeedback(1, f)
        }
        assertTrue("los cinco se apagaron", r.finalLevels.all { it < -60 })
        assertTrue(r.logs.any { "en Main LR" in it && "sin bandas libres" in it })
    }

    @Test fun ultimoRecursoBajaElVolumenYLoRestaura() {
        val r = run(Config(sources = listOf("ch1"), apply = true, overflowLr = false), 28.0) { s ->
            for (f in doubleArrayOf(500.0, 1250.0, 1800.0, 4400.0)) s.addFeedback(1, f)
        }
        assertTrue(r.finalLevels.all { it < -60 })
        assertTrue(((r.during["/ch/01/mix/fader"] as? Number) ?: 0.75f).toDouble() < 0.74)
        assertEquals(0.75, ((r.after["/ch/01/mix/fader"] as? Number) ?: 0.75f).toDouble(), 0.001)
        assertFalse(r.during.keys.any { it.startsWith("/lr/eq") })
    }

    @Test fun feedbackImposibleTerminaEnAlerta() {
        val r = run(Config(sources = listOf("ch1"), apply = true), 30.0) { it.addFeedback(1, 2000.0, "lr", 30.0) }
        assertTrue(r.logs.any { "Hay que bajarlo a mano" in it })
    }

    /** La consola desaparece 12 s y vuelve reiniciada: el motor reconecta solo y repone el corte. */
    @Test fun reconectaSoloYReponeLosCortes() {
        val sim = Simulator()
        sim.start()
        sim.addFeedback(1, 2500.0)
        val logs = Collections.synchronizedList(ArrayList<String>())
        val engine = Engine(Config(host = "127.0.0.1", port = sim.port, sources = listOf("ch1"), apply = true),
            object : Engine.Listener {
                override fun onLog(text: String) { logs.add(text); println(text) }
                override fun onStatus(text: String) {}
                override fun onSpectrum(src: String, frame: FloatArray, candidates: IntArray) {}
                override fun onCuts(cuts: List<CutRow>) {}
                override fun onStopped() {}
            })
        engine.start()
        Thread.sleep(9000)
        assertTrue("corto antes del corte de red", Scales.floatToGain((sim.param("/ch/01/eq/3/g") as Number).toDouble()) < -2)
        sim.offline = true
        sim.reboot()
        Thread.sleep(12000)
        assertTrue("aviso la perdida", logs.any { "se perdio la conexion" in it })
        assertTrue("el motor sigue vivo", engine.isAlive)
        sim.offline = false
        Thread.sleep(9000)
        assertTrue("reconecto", logs.any { "Reconectado" in it })
        assertTrue("repuso el corte", Scales.floatToGain(((sim.param("/ch/01/eq/3/g") as? Number) ?: 0.5f).toDouble()) < -2)
        engine.requestStop()
        engine.join(6000)
        Thread.sleep(200)
        assertEquals("restauro al detener", 0.0, Scales.floatToGain(((sim.param("/ch/01/eq/3/g") as? Number) ?: 0.5f).toDouble()), 0.05)
        sim.requestStop()
    }
}
