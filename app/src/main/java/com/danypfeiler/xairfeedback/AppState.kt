package com.danypfeiler.xairfeedback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.danypfeiler.xairfeedback.core.Config
import com.danypfeiler.xairfeedback.core.CutRow
import com.danypfeiler.xairfeedback.core.Simulator
import java.net.DatagramSocket

/** Estado compartido entre el servicio (que trabaja) y la pantalla (que muestra). */
object AppState {
    val main = Handler(Looper.getMainLooper())

    // Solo se tocan desde el hilo principal
    val logLines = ArrayList<String>()
    var cuts: List<CutRow> = emptyList()
    var status = "Detenido"
    var running = false
    var ui: (() -> Unit)? = null
    val names = HashMap<String, String>()      // nombres leidos de la consola
    var alertUntil = 0L                         // hasta cuando mostrar el estado en rojo

    fun srcName(key: String): String = names[key] ?: com.danypfeiler.xairfeedback.core.Scales.srcLabel(key)

    // Lo escribe el motor, lo lee la pantalla
    @Volatile var specSrc: String? = null
    @Volatile var specFrame: FloatArray? = null
    @Volatile var specCands: IntArray = IntArray(0)
    @Volatile var sim: Simulator? = null
    /** Red Wi-Fi vigente segun el sistema (la mantiene el servicio mientras trabaja). */
    @Volatile var wifiNetwork: android.net.Network? = null

    fun log(text: String) {
        main.post {
            logLines.add(text)
            while (logLines.size > 300) logLines.removeAt(0)
            ui?.invoke()
        }
    }

    fun post(block: () -> Unit) {
        main.post {
            block()
            ui?.invoke()
        }
    }

    /** Ata el socket a la red Wi-Fi: si no, con un Wi-Fi sin internet Android puede usar los datos moviles. */
    fun wifiBinder(ctx: Context): ((DatagramSocket) -> Unit)? {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val wifi = wifiNetwork ?: cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return null
            val bind: (DatagramSocket) -> Unit = { s -> wifi.bindSocket(s) }
            bind
        } catch (e: Exception) {
            null
        }
    }
}

/** Guarda y lee la configuracion. */
object Prefs {
    private const val FILE = "config"

    private fun ints(s: String?): List<Int> =
        (s ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }

    fun load(ctx: Context): Config {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = Config()
        return Config(
            host = p.getString("host", d.host) ?: d.host,
            port = p.getInt("port", d.port),
            sources = (p.getString("sources", null) ?: d.sources.joinToString(",")).split(",").filter { it.isNotBlank() },
            dest = p.getString("dest", d.dest) ?: d.dest,
            chBands = if (p.contains("chBands")) ints(p.getString("chBands", "")) else d.chBands,
            busBands = if (p.contains("busBands")) ints(p.getString("busBands", "")) else d.busBands,
            profile = p.getString("profile", d.profile) ?: d.profile,
            apply = p.getBoolean("apply", d.apply),
            restoreOnStop = p.getBoolean("restoreOnStop", d.restoreOnStop),
            overflowLr = p.getBoolean("overflowLr", d.overflowLr),
            faderGuard = p.getBoolean("faderGuard", d.faderGuard),
            faderMax = p.getFloat("faderMax", d.faderMax.toFloat()).toDouble(),
            releaseMin = p.getFloat("releaseMin", d.releaseMin.toFloat()).toDouble(),
            minFreq = p.getFloat("minFreq", d.minFreq.toFloat()).toDouble(),
            useSim = p.getBoolean("useSim", d.useSim)
        )
    }

    fun loadNames(ctx: Context): Map<String, String> {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val out = HashMap<String, String>()
        for (key in com.danypfeiler.xairfeedback.core.Scales.ALL_SOURCES) {
            val n = p.getString("name_$key", null)
            if (!n.isNullOrBlank()) out[key] = n
        }
        return out
    }

    fun saveNames(ctx: Context, names: Map<String, String>) {
        val e = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        for (key in com.danypfeiler.xairfeedback.core.Scales.ALL_SOURCES) {
            val n = names[key]
            if (n.isNullOrBlank()) e.remove("name_$key") else e.putString("name_$key", n)
        }
        e.apply()
    }

    fun save(ctx: Context, c: Config) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("host", c.host)
            .putInt("port", c.port)
            .putString("sources", c.sources.joinToString(","))
            .putString("dest", c.dest)
            .putString("chBands", c.chBands.joinToString(","))
            .putString("busBands", c.busBands.joinToString(","))
            .putString("profile", c.profile)
            .putBoolean("apply", c.apply)
            .putBoolean("restoreOnStop", c.restoreOnStop)
            .putBoolean("overflowLr", c.overflowLr)
            .putBoolean("faderGuard", c.faderGuard)
            .putFloat("faderMax", c.faderMax.toFloat())
            .putFloat("releaseMin", c.releaseMin.toFloat())
            .putFloat("minFreq", c.minFreq.toFloat())
            .putBoolean("useSim", c.useSim)
            .apply()
    }
}
