package com.danypfeiler.xairfeedback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.danypfeiler.xairfeedback.core.CutRow
import com.danypfeiler.xairfeedback.core.Engine
import com.danypfeiler.xairfeedback.core.Simulator

/**
 * Servicio en primer plano: mantiene el motor trabajando con la pantalla apagada o la app en
 * segundo plano. Muestra una notificacion fija con el estado y un boton para detener.
 */
class FeedbackService : Service(), Engine.Listener {
    companion object {
        const val ACTION_START = "com.danypfeiler.xairfeedback.START"
        const val ACTION_STOP = "com.danypfeiler.xairfeedback.STOP"
        private const val CHANNEL = "estado"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, FeedbackService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FeedbackService::class.java).setAction(ACTION_STOP))
        }
    }

    private var engine: Engine? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotifText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val e = engine
            if (e != null && e.isAlive) e.requestStop() else finish()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification("Iniciando..."))
        if (engine?.isAlive == true) return START_STICKY
        startEngine()
        return START_STICKY
    }

    private fun startEngine() {
        var cfg = Prefs.load(this)
        acquireLocks()
        var binder = AppState.wifiBinder(this)
        if (cfg.useSim) {
            val sim = Simulator()
            sim.start()
            AppState.sim = sim
            cfg = cfg.copy(host = "127.0.0.1", port = sim.port)
            binder = null
            AppState.log("--- SIMULADOR: nada de esto llega a la consola real ---")
        } else if (binder == null) {
            AppState.log("AVISO: el telefono no esta conectado a ningun Wi-Fi. Conectalo al Wi-Fi de la consola.")
        }
        AppState.post {
            AppState.running = true
            AppState.cuts = emptyList()
        }
        val e = Engine(cfg, this, binder)
        engine = e
        e.start()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xairfeedback:motor").apply { acquire() }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "xairfeedback:wifi").apply { acquire() }
        } catch (e: Exception) {
            AppState.log("AVISO: no pude pedir que el telefono se mantenga despierto ($e).")
        }
    }

    private fun releaseLocks() {
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        wake = null
        wifiLock = null
    }

    private fun finish() {
        releaseLocks()
        AppState.sim?.requestStop()
        AppState.sim = null
        engine = null
        AppState.running = false
        AppState.status = "Detenido"
        AppState.ui?.invoke()
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.requestStop()
        releaseLocks()
        super.onDestroy()
    }

    // ---- avisos del motor (llegan desde su hilo) ------------------------------------------
    override fun onLog(text: String) {
        AppState.log(text)
        if ("ALERTA" in text) AppState.main.post { updateNotification(text.substringAfter("ALERTA: ")) }
    }

    override fun onStatus(text: String) {
        AppState.post { AppState.status = text }
        AppState.main.post { if (engine != null) updateNotification(text) }
    }

    override fun onSpectrum(src: String, frame: FloatArray, candidates: IntArray) {
        AppState.specCands = candidates
        AppState.specSrc = src
        AppState.specFrame = frame
    }

    override fun onCuts(cuts: List<CutRow>) {
        AppState.post { AppState.cuts = cuts }
    }

    override fun onStopped() {
        AppState.main.post { finish() }
    }

    // ---- notificacion ---------------------------------------------------------------------
    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Estado del supresor", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, FeedbackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("XAir Feedback activo")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Detener", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotifText) return
        lastNotifText = text
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, notification(text))
        } catch (e: Exception) {
        }
    }
}
