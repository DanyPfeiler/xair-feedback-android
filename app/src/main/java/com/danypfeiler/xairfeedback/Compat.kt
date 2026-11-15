package com.danypfeiler.xairfeedback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent

/**
 * Llamadas de Android 8 (API 26) hechas por reflexion, para que el proyecto tambien se pueda
 * compilar con herramientas antiguas (android.jar de API 23). La app exige Android 8 o superior,
 * asi que estas funciones siempre existen en el telefono.
 */
object Compat {
    fun createChannel(nm: NotificationManager, id: String, name: String) {
        val cls = Class.forName("android.app.NotificationChannel")
        val ch = cls.getConstructor(String::class.java, CharSequence::class.java, Int::class.javaPrimitiveType)
            .newInstance(id, name, 2)                                   // 2 = IMPORTANCE_LOW
        NotificationManager::class.java.getMethod("createNotificationChannel", cls).invoke(nm, ch)
    }

    fun notificationBuilder(ctx: Context, channel: String): Notification.Builder =
        Notification.Builder::class.java.getConstructor(Context::class.java, String::class.java)
            .newInstance(ctx, channel)

    fun startForegroundService(ctx: Context, intent: Intent) {
        Context::class.java.getMethod("startForegroundService", Intent::class.java).invoke(ctx, intent)
    }
}
