package com.danypfeiler.xairfeedback.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Lo que el banco de cortes necesita de la consola. */
interface Mixer {
    fun send(path: String, vararg args: Any)
    fun get(path: String, tries: Int = 3): Any?
}

/** Cliente UDP de la consola. socketSetup permite atar el socket a la red Wi-Fi en Android. */
class XAirClient(host: String, private val port: Int, socketSetup: ((DatagramSocket) -> Unit)? = null) : Mixer {
    private val addr: InetAddress = InetAddress.getByName(host)
    private val sock = DatagramSocket()
    private val buf = ByteArray(8192)
    private var lastSub = 0L

    init {
        sock.soTimeout = 300
        try { socketSetup?.invoke(sock) } catch (e: Exception) { /* seguir con la red por defecto */ }
    }

    fun close() { try { sock.close() } catch (e: Exception) { } }

    @Throws(IOException::class)
    override fun send(path: String, vararg args: Any) {
        val data = Osc.encode(path, *args)
        sock.send(DatagramPacket(data, data.size, addr, port))
    }

    @Throws(IOException::class)
    fun recv(): Osc.Msg? {
        val p = DatagramPacket(buf, buf.size)
        try {
            sock.receive(p)
        } catch (e: SocketTimeoutException) {
            return null
        }
        return Osc.decode(buf, p.length)
    }

    /** Pide un parametro y devuelve su primer valor (o null). */
    override fun get(path: String, tries: Int): Any? {
        repeat(tries) {
            try {
                send(path)
                val end = System.currentTimeMillis() + 600
                while (System.currentTimeMillis() < end) {
                    val m = recv()
                    if (m != null && m.addr == path && m.args.isNotEmpty()) return m.args[0]
                }
            } catch (e: IOException) {
                return null
            }
        }
        return null
    }

    /** Devuelve [ip, nombre, modelo, firmware] o null. */
    fun info(waitMs: Long = 1000): List<Any>? {
        try {
            send("/xinfo")
            val end = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < end) {
                val m = recv()
                if (m != null && m.addr == "/xinfo") return m.args
            }
        } catch (e: IOException) {
        }
        return null
    }

    @Throws(IOException::class)
    fun subscribeRta(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastSub > 4000) {       // la consola corta el envio a los ~10 s
            send("/meters", "/meters/4")
            lastSub = now
        }
    }

    @Throws(IOException::class)
    fun nextRta(): FloatArray? {
        subscribeRta()
        val m = recv() ?: return null
        if (m.addr != "/meters/4" || m.args.isEmpty()) return null
        val blob = m.args[0] as? ByteArray ?: return null
        val vals = Osc.parseMeterBlob(blob)
        return if (vals.size >= 100) vals.copyOf(100) else null
    }
}
