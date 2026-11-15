package com.danypfeiler.xairfeedback

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.danypfeiler.xairfeedback.core.Config
import com.danypfeiler.xairfeedback.core.Engine
import com.danypfeiler.xairfeedback.core.Scales
import com.danypfeiler.xairfeedback.core.XAirClient
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Pantalla unica: arriba el estado y el boton principal (fijos), abajo todo lo demas en tarjetas. */
class MainActivity : Activity() {
    private lateinit var btnMain: TextView
    private lateinit var dot: View
    private lateinit var txtStatus: TextView
    private lateinit var txtSource: TextView
    private lateinit var spectrum: SpectrumView
    private lateinit var cutsBox: LinearLayout
    private lateinit var txtLog: TextView
    private lateinit var simCard: LinearLayout

    private lateinit var edHost: EditText
    private lateinit var edPort: EditText
    private lateinit var btnTest: TextView
    private lateinit var swSim: Switch
    private val srcChips = LinkedHashMap<String, Chip>()
    private lateinit var destChoice: Choice
    private val chBandChips = LinkedHashMap<Int, Chip>()
    private val busBandChips = LinkedHashMap<Int, Chip>()
    private lateinit var profileChoice: Choice
    private lateinit var txtProfile: TextView
    private lateinit var actionChoice: Choice
    private lateinit var swRestore: Switch
    private lateinit var swOverflow: Switch
    private lateinit var swFader: Switch
    private lateinit var edFaderMax: EditText
    private lateinit var edRelease: EditText
    private lateinit var edMinFreq: EditText

    private lateinit var simChannel: Choice
    private lateinit var simPath: Choice
    private lateinit var simForce: Choice
    private lateinit var edSimFreq: EditText
    private lateinit var swBand: Switch

    private var logExpanded = false
    private val profileHelp = mapOf(
        "bajo" to "Solo corta feedback muy claro. Cortes de 3 a 6 dB, angostos. Casi no toca el sonido.",
        "medio" to "Equilibrio entre rapidez y cuidado del sonido. Primer corte de 6 dB, hasta 9 dB.",
        "alto" to "Reacciona antes y corta más (hasta 12 dB, más ancho). Puede cortar alguna nota sostenida."
    )
    private val ticker = object : Runnable {
        override fun run() {
            if (AppState.running) {
                spectrum.invalidate()
                val src = AppState.specSrc
                if (src != null) txtSource.text = AppState.srcName(src)
            }
            AppState.main.postDelayed(this, 70)
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    // ------------------------------------------------------------------ construccion
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val cfg = Prefs.load(this)
        if (!AppState.running) {
            AppState.names.clear()
            AppState.names.putAll(Prefs.loadNames(this))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        // --- cabecera fija: titulo, estado y boton principal
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
        }
        titleRow.addView(Ui.text(this, "XAir Feedback", 20f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, wrap, 1f))
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
            background = Ui.round(Ui.CARD, dp(16))
            setPadding(dp(10), dp(6), dp(12), dp(6))
        }
        dot = View(this)
        pill.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(7) })
        txtStatus = Ui.text(this, "Detenido", 12f, Ui.TEXT).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(190)
        }
        pill.addView(txtStatus)
        titleRow.addView(pill)
        header.addView(titleRow)
        btnMain = Ui.text(this, "INICIAR", 16f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setOnClickListener { if (AppState.running) FeedbackService.stop(this@MainActivity) else start() }
        }
        header.addView(btnMain, LinearLayout.LayoutParams(match, dp(52)).apply { topMargin = dp(12) })
        root.addView(header)

        // --- contenido desplazable
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(28))
        }

        // analizador + cortes
        val cAn = card(content, "ANALIZADOR")
        txtSource = Ui.text(this, "", 13f, Ui.ACCENT, bold = true)
        (cAn.getChildAt(0) as LinearLayout).addView(txtSource)
        spectrum = SpectrumView(this)
        cAn.addView(spectrum, LinearLayout.LayoutParams(match, dp(190)))
        cAn.addView(caption("CORTES ACTIVOS"), top(14))
        cutsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cAn.addView(cutsBox, top(6))

        // simulador
        simCard = card(content, "SIMULADOR")
        simCard.addView(Ui.text(this, "Provoca un feedback y mira cómo reacciona. Nada de esto llega a la consola.", 12f, Ui.MUTED))
        simCard.addView(caption("Canal"), top(10))
        simChannel = Choice(this, false).apply {
            for (n in 1..4) add(n.toString(), n.toString())
            value = "1"
        }
        simCard.addView(simChannel, top(6))
        val sr = row(simCard, 10)
        sr.addView(Ui.text(this, "Frecuencia (Hz)", 14f), LinearLayout.LayoutParams(0, wrap, 1f))
        edSimFreq = field("2500", 5, decimal = false).also { sr.addView(it) }
        simCard.addView(caption("Camino"), top(10))
        simPath = Choice(this, false).apply {
            add("lr", "PA")
            for (b in 1..Scales.N_BUSES) add("bus/$b", "Monitor $b")
            value = "lr"
        }
        simCard.addView(simPath, top(6))
        simCard.addView(caption("Fuerza"), top(10))
        simForce = Choice(this, false).apply {
            add("3", "Suave"); add("5", "Medio"); add("8", "Fuerte")
            value = "5"
        }
        simCard.addView(simForce, top(6))
        val sb = row(simCard, 12)
        sb.addView(button("Generar", Ui.ACCENT) { simFeedback(null) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        sb.addView(button("Al azar", Ui.FIELD) { simRandom() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        sb.addView(button("Quitar", Ui.FIELD) { simClear() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        swBand = switch("Banda tocando", true) { on -> AppState.sim?.bandPlaying = on }
        simCard.addView(swBand, top(8))

        // registro
        val cLog = card(content, "REGISTRO")
        txtLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Ui.MUTED)
            setLineSpacing(dp(2).toFloat(), 1f)
            setOnClickListener {
                logExpanded = !logExpanded
                refresh()
            }
        }
        cLog.addView(txtLog)

        // 1 conexion
        val c1 = card(content, "CONEXIÓN")
        val r1 = row(c1, 0)
        val hostBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hostBox.addView(Ui.text(this, "IP de la consola", 12f, Ui.MUTED))
        edHost = field(cfg.host, 0, decimal = false, text = true)
        hostBox.addView(edHost, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
        r1.addView(hostBox, LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = dp(8) })
        val portBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        portBox.addView(Ui.text(this, "Puerto", 12f, Ui.MUTED))
        edPort = field(cfg.port.toString(), 4, decimal = false)
        portBox.addView(edPort, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(4) })
        r1.addView(portBox)
        btnTest = button("Probar conexión y leer nombres", Ui.FIELD) { testConnection() }
        c1.addView(btnTest, LinearLayout.LayoutParams(match, dp(44)).apply { topMargin = dp(10) })
        swSim = switch("Usar simulador (sin consola)", cfg.useSim) { refresh() }
        c1.addView(swSim, top(8))

        // 2 fuentes
        val c2 = card(content, "QUÉ ANALIZAR")
        val flow = FlowLayout(this)
        for (key in Scales.ALL_SOURCES) {
            srcChips[key] = Chip(this, chipLabel(key), key in cfg.sources).also { flow.addView(it) }
        }
        c2.addView(flow)
        c2.addView(Ui.text(this, "Una sola fuente reacciona más rápido; con varias, las va rotando.", 12f, Ui.MUTED), top(10))

        // 3 destino
        val c3 = card(content, "DÓNDE CORTAR")
        c3.addView(Ui.text(this, "Cuando el feedback viene de un canal:", 12f, Ui.MUTED))
        destChoice = Choice(this, true).apply {
            add("auto", "En el EQ de ese canal", "Afecta PA y monitores. Recomendado.")
            add("lr", "En el Main LR", "Solo el PA; afecta a toda la mezcla.")
            for (b in 1..Scales.N_BUSES) add("bus$b", busTitle(b), "Solo ese monitor.")
            value = if (cfg.dest in listOf("auto", "lr") || cfg.dest.startsWith("bus")) cfg.dest else "auto"
        }
        c3.addView(destChoice, top(8))

        // 4 bandas
        val c4 = card(content, "BANDAS DE EQ QUE PUEDE USAR")
        c4.addView(Ui.text(this, "Canal", 12f, Ui.MUTED))
        val fb1 = FlowLayout(this)
        for (b in 1..4) chBandChips[b] = Chip(this, b.toString(), b in cfg.chBands).also { fb1.addView(it) }
        c4.addView(fb1, top(6))
        c4.addView(Ui.text(this, "Main LR y buses", 12f, Ui.MUTED), top(12))
        val fb2 = FlowLayout(this)
        for (b in 1..6) busBandChips[b] = Chip(this, b.toString(), b in cfg.busBands).also { fb2.addView(it) }
        c4.addView(fb2, top(6))

        // 5 agresividad
        val c5 = card(content, "AGRESIVIDAD")
        txtProfile = Ui.text(this, "", 12f, Ui.MUTED)
        profileChoice = Choice(this, false).apply {
            add("bajo", "Bajo"); add("medio", "Medio"); add("alto", "Alto")
            value = if (cfg.profile in profileHelp) cfg.profile else "medio"
            onChange = { txtProfile.text = profileHelp[it] }
        }
        txtProfile.text = profileHelp[profileChoice.value]
        c5.addView(profileChoice)
        c5.addView(txtProfile, top(8))

        // 6 accion
        val c6 = card(content, "ACCIÓN")
        actionChoice = Choice(this, false).apply {
            add("warn", "Solo avisar"); add("cut", "Cortar solo")
            value = if (cfg.apply) "cut" else "warn"
        }
        c6.addView(actionChoice)
        swRestore = switch("Al detener, devolver el EQ a como estaba", cfg.restoreOnStop) {}
        c6.addView(swRestore, top(12))
        swOverflow = switch("Sin bandas en el canal: seguir en Main LR", cfg.overflowLr) {}
        c6.addView(swOverflow, top(6))
        swFader = switch("Último recurso: bajar el volumen del micrófono", cfg.faderGuard) {}
        c6.addView(swFader, top(6))
        edFaderMax = numberRow(c6, "Bajada máxima de volumen (dB)", fmt(cfg.faderMax), decimal = true)
        edRelease = numberRow(c6, "Soltar un corte si no vuelve en (min, 0 = nunca)", fmt(cfg.releaseMin), decimal = true)
        edMinFreq = numberRow(c6, "No cortar por debajo de (Hz)", fmt(cfg.minFreq), decimal = false)
        c6.addView(Ui.text(this, "Los cambios se aplican al volver a iniciar.", 12f, Ui.MUTED), top(10))

        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    // ------------------------------------------------------------------ piezas de interfaz
    private fun top(v: Int) = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(v) }

    private fun caption(t: String) = Ui.text(this, t, 11f, Ui.MUTED, bold = true).apply { letterSpacing = 0.08f }

    /** Tarjeta con titulo. El primer hijo es la fila del titulo (se le puede agregar algo a la derecha). */
    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(Ui.CARD, dp(16))
            setPadding(dp(14), dp(14), dp(14), dp(16))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
        }
        head.addView(caption(title), LinearLayout.LayoutParams(0, wrap, 1f))
        box.addView(head, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(10) })
        parent.addView(box, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
        return box
    }

    private fun row(parent: LinearLayout, topDp: Int): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
        }
        parent.addView(r, top(topDp))
        return r
    }

    private fun field(value: String, ems: Int, decimal: Boolean, text: Boolean = false) = EditText(this).apply {
        setText(value)
        if (ems > 0) setEms(ems)
        textSize = 15f
        setTextColor(Ui.TEXT)
        setSingleLine(true)
        background = Ui.round(Ui.FIELD, dp(10), Ui.LINE, dp(1))
        setPadding(dp(12), dp(9), dp(12), dp(9))
        inputType = when {
            text -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else -> InputType.TYPE_CLASS_NUMBER
        }
    }

    private fun numberRow(parent: LinearLayout, label: String, value: String, decimal: Boolean): EditText {
        val r = row(parent, 10)
        r.addView(Ui.text(this, label, 14f), LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = dp(10) })
        val e = field(value, 3, decimal)
        e.gravity = Gravity.CENTER
        r.addView(e)
        return e
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) =
        Ui.text(this, label, 14f, if (color == Ui.ACCENT) Color.parseColor("#06201D") else Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = Ui.round(color, dp(12))
            setOnClickListener { onClick() }
        }

    private fun switch(label: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 14f
        setTextColor(Ui.TEXT)
        isChecked = on
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun num(e: EditText, default: Double, lo: Double, hi: Double): Double =
        (e.text.toString().replace(',', '.').toDoubleOrNull() ?: default).coerceIn(lo, hi)

    /** Texto de la pastilla de una fuente: el nombre de la consola si lo hay, si no el numero. */
    private fun chipLabel(key: String): String {
        val name = AppState.names[key]
        return when {
            key == "lr" -> name ?: "Main LR (PA)"
            key.startsWith("bus") -> if (name != null) "Bus " + key.substring(3) + " · " + name else "Bus " + key.substring(3)
            else -> if (name != null) key.substring(2) + " · " + name else "Ch " + key.substring(2)
        }
    }

    private fun busTitle(b: Int): String {
        val name = AppState.names["bus$b"]
        return if (name != null) "En el Bus $b ($name)" else "En el Bus $b"
    }

    // ------------------------------------------------------------------ configuracion y acciones
    private fun collect() = Config(
        host = edHost.text.toString().trim().ifEmpty { Config().host },
        port = num(edPort, 10024.0, 1.0, 65535.0).toInt(),
        sources = srcChips.filter { it.value.checked }.keys.toList(),
        dest = destChoice.value,
        chBands = chBandChips.filter { it.value.checked }.keys.toList(),
        busBands = busBandChips.filter { it.value.checked }.keys.toList(),
        profile = profileChoice.value,
        apply = actionChoice.value == "cut",
        restoreOnStop = swRestore.isChecked,
        overflowLr = swOverflow.isChecked,
        faderGuard = swFader.isChecked,
        faderMax = num(edFaderMax, 6.0, 0.0, 20.0),
        releaseMin = num(edRelease, 0.0, 0.0, 240.0),
        minFreq = num(edMinFreq, 160.0, 40.0, 2000.0),
        useSim = swSim.isChecked
    )

    private fun start() {
        if (AppState.running) return
        val cfg = collect()
        if (cfg.sources.isEmpty()) {
            AppState.log("Elegí al menos una fuente en QUÉ ANALIZAR.")
            return
        }
        if (cfg.apply && cfg.chBands.isEmpty() && cfg.busBands.isEmpty()) {
            AppState.log("Elegí al menos una banda de EQ que el programa pueda usar.")
            return
        }
        Prefs.save(this, cfg)
        AppState.status = "Iniciando..."
        AppState.running = true
        refresh()
        try {
            FeedbackService.start(this)
        } catch (e: Exception) {
            AppState.running = false
            AppState.status = "Detenido"
            AppState.log("ERROR: no pude iniciar el servicio: $e")
        }
    }

    private fun testConnection() {
        if (AppState.running) {
            AppState.log("Detené el análisis antes de probar la conexión.")
            return
        }
        val host = edHost.text.toString().trim()
        val port = num(edPort, 10024.0, 1.0, 65535.0).toInt()
        val binder = AppState.wifiBinder(this)
        val ctx = applicationContext
        AppState.log("Probando $host:$port ...")
        Thread {
            var info: List<Any>? = null
            var names: Map<String, String> = emptyMap()
            var err = "sin respuesta"
            try {
                val m = XAirClient(host, port, binder)
                info = m.info(2000)
                if (info != null) names = Engine.fetchNames(m)
                m.close()
            } catch (e: Exception) {
                err = e.toString()
            }
            if (info != null) {
                AppState.log("Consola encontrada: " + info.joinToString(" / "))
                Prefs.saveNames(ctx, names)
                AppState.post {
                    AppState.names.clear()
                    AppState.names.putAll(names)
                }
                AppState.log(if (names.isEmpty()) "La consola no tiene nombres cargados en los canales."
                             else "Nombres leídos: " + names.values.joinToString(", "))
            } else {
                AppState.log("ERROR: no hay respuesta de $host ($err). Revisá que el teléfono esté en el Wi-Fi de la consola y la IP.")
            }
        }.start()
    }

    private fun simFeedback(freqOverride: Double?) {
        val sim = AppState.sim
        if (sim == null) {
            AppState.log("Primero tocá INICIAR con el simulador activado.")
            return
        }
        val ch = simChannel.value.toIntOrNull() ?: 1
        val freq = freqOverride ?: num(edSimFreq, 2500.0, 60.0, 15000.0)
        sim.addFeedback(ch, freq, simPath.value, simForce.value.toDoubleOrNull() ?: 5.0)
        AppState.log("[simulador] feedback en %s, %.0f Hz".format(AppState.srcName("ch$ch"), freq))
    }

    private fun simRandom() {
        val f = exp(ln(250.0) + Math.random() * (ln(6000.0) - ln(250.0))).roundToInt().toDouble()
        edSimFreq.setText(f.toInt().toString())
        simFeedback(f)
    }

    private fun simClear() {
        val sim = AppState.sim ?: return
        sim.clearFeedback()
        AppState.log("[simulador] feedbacks quitados")
    }

    // ------------------------------------------------------------------ refresco
    private fun refresh() {
        val running = AppState.running
        val alert = running && System.currentTimeMillis() < AppState.alertUntil
        btnMain.text = if (running) "DETENER" else "INICIAR"
        btnMain.background = Ui.round(if (running) Color.parseColor("#C0392B") else Color.parseColor("#17907F"), dp(14))
        txtStatus.text = AppState.status
        val color = when {
            !running -> Ui.GREY
            alert -> Ui.RED
            AppState.status.startsWith("Analizando") -> Ui.GREEN
            else -> Ui.AMBER
        }
        dot.background = Ui.round(color, dp(5))
        if (!running) txtSource.text = ""

        val simOn = swSim.isChecked
        simCard.visibility = if (simOn) View.VISIBLE else View.GONE
        edHost.isEnabled = !simOn
        edPort.isEnabled = !simOn
        edHost.alpha = if (simOn) 0.45f else 1f
        edPort.alpha = if (simOn) 0.45f else 1f
        btnTest.alpha = if (simOn) 0.45f else 1f
        btnTest.isEnabled = !simOn

        for ((key, chip) in srcChips) chip.text = chipLabel(key)
        for (b in 1..Scales.N_BUSES) destChoice.setTitle("bus$b", busTitle(b))

        cutsBox.removeAllViews()
        val cuts = AppState.cuts
        if (cuts.isEmpty()) {
            cutsBox.addView(Ui.text(this, "Ninguno", 13f, Ui.GREY))
        } else {
            for (r in cuts) {
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setGravity(Gravity.CENTER_VERTICAL)
                    background = Ui.round(Ui.FIELD, dp(10))
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                val what = if (r.freq == null) "Volumen" else "%.0f Hz".format(r.freq)
                val where = if (r.freq == null) r.where else r.where + " · banda " + r.band
                line.addView(Ui.text(this, what, 15f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(dp(86), wrap))
                line.addView(Ui.text(this, where, 13f, Ui.MUTED), LinearLayout.LayoutParams(0, wrap, 1f))
                line.addView(Ui.text(this, "-%.0f dB".format(r.depth), 15f, Ui.RED, bold = true))
                cutsBox.addView(line, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
            }
        }

        val sb = SpannableStringBuilder()
        val lines = AppState.logLines.takeLast(if (logExpanded) 80 else 8)
        if (lines.isEmpty()) sb.append("Sin actividad todavía.")
        for (line in lines) {
            val from = sb.length
            sb.append(line).append('\n')
            val c = when {
                "ALERTA" in line || "ERROR" in line || "AVISO" in line -> Ui.RED
                "corte" in line || "volumen" in line -> Ui.GREEN
                else -> null
            }
            if (c != null) sb.setSpan(ForegroundColorSpan(c), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (AppState.logLines.size > 8) sb.append(if (logExpanded) "Tocá para ver menos" else "Tocá para ver más")
        txtLog.text = sb
        spectrum.invalidate()
    }

    override fun onStart() {
        super.onStart()
        AppState.ui = { refresh() }
        refresh()
        AppState.main.post(ticker)
    }

    override fun onStop() {
        AppState.ui = null
        AppState.main.removeCallbacks(ticker)
        if (!AppState.running) Prefs.save(this, collect())
        super.onStop()
    }
}
