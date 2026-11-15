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
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.danypfeiler.xairfeedback.core.Config
import com.danypfeiler.xairfeedback.core.Scales
import com.danypfeiler.xairfeedback.core.XAirClient
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtTitle: TextView
    private lateinit var spectrum: SpectrumView
    private lateinit var txtCuts: TextView
    private lateinit var txtLog: TextView
    private lateinit var simPanel: LinearLayout

    private lateinit var edHost: EditText
    private lateinit var edPort: EditText
    private lateinit var btnTest: Button
    private lateinit var cbSim: CheckBox
    private val cbSrc = LinkedHashMap<String, CheckBox>()
    private lateinit var rgDest: RadioGroup
    private val destIds = LinkedHashMap<String, Int>()
    private val cbChBand = LinkedHashMap<Int, CheckBox>()
    private val cbBusBand = LinkedHashMap<Int, CheckBox>()
    private lateinit var rgProfile: RadioGroup
    private val profileIds = LinkedHashMap<String, Int>()
    private lateinit var txtProfile: TextView
    private lateinit var rbWarn: RadioButton
    private lateinit var rbCut: RadioButton
    private lateinit var cbRestore: CheckBox
    private lateinit var cbOverflow: CheckBox
    private lateinit var cbFader: CheckBox
    private lateinit var edFaderMax: EditText
    private lateinit var edRelease: EditText
    private lateinit var edMinFreq: EditText

    private lateinit var spSimCh: Spinner
    private lateinit var edSimFreq: EditText
    private lateinit var spSimPath: Spinner
    private lateinit var spSimForce: Spinner
    private lateinit var cbBand: CheckBox

    private var nextId = 1000
    private val profileHelp = mapOf(
        "bajo" to "Bajo: solo corta feedback muy claro. Cortes de 3 a 6 dB, angostos. Casi no toca el sonido.",
        "medio" to "Medio: equilibrio. Primer corte de 6 dB, hasta 9 dB.",
        "alto" to "Alto: reacciona antes y corta mas (hasta 12 dB, mas ancho). Puede cortar alguna nota sostenida."
    )
    private val ticker = object : Runnable {
        override fun run() {
            if (AppState.running) {
                spectrum.invalidate()
                val src = AppState.specSrc
                if (src != null) txtTitle.text = "Analizador: " + Scales.srcLabel(src)
            }
            AppState.main.postDelayed(this, 70)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------------ construccion
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Prefs.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(24))
        }

        // --- botones y estado
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnStart = Button(this).apply {
            text = "INICIAR"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F8A4C"))
            setOnClickListener { start() }
        }
        btnStop = Button(this).apply {
            text = "DETENER"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA3333"))
            setOnClickListener { FeedbackService.stop(this@MainActivity) }
        }
        bar.addView(btnStart, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        bar.addView(btnStop, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(bar)
        txtStatus = label("Detenido", bold = true).also { root.addView(it, pad(top = 6)) }

        // --- analizador
        txtTitle = label("Analizador", bold = true).also { root.addView(it, pad(top = 8)) }
        spectrum = SpectrumView(this)
        root.addView(spectrum, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))

        root.addView(label("Cortes activos", bold = true), pad(top = 10))
        txtCuts = label("Ninguno").also { root.addView(it) }

        // --- simulador
        simPanel = section(root, "Simulador: provoca un feedback y mira como reacciona")
        val r1 = row(simPanel)
        r1.addView(label("Canal "))
        spSimCh = spinner((1..Scales.N_CHANNELS).map { it.toString() }).also { r1.addView(it) }
        r1.addView(label("  Hz "))
        edSimFreq = edit("2500", 5, decimal = false).also { r1.addView(it) }
        val r2 = row(simPanel)
        r2.addView(label("Por "))
        spSimPath = spinner(listOf("PA (Main LR)") + (1..Scales.N_BUSES).map { "Monitor Bus $it" }).also { r2.addView(it) }
        r2.addView(label("  Fuerza "))
        spSimForce = spinner(listOf("Suave", "Medio", "Fuerte")).also { it.setSelection(1); r2.addView(it) }
        val r3 = row(simPanel)
        r3.addView(Button(this).apply { text = "Generar"; setOnClickListener { simFeedback(null) } })
        r3.addView(Button(this).apply { text = "Al azar"; setOnClickListener { simRandom() } })
        r3.addView(Button(this).apply { text = "Quitar"; setOnClickListener { simClear() } })
        cbBand = CheckBox(this).apply {
            text = "Banda tocando"
            isChecked = true
            setOnCheckedChangeListener { _, on -> AppState.sim?.bandPlaying = on }
        }
        simPanel.addView(cbBand)

        // --- registro
        root.addView(label("Registro", bold = true), pad(top = 10))
        txtLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setBackgroundColor(Color.parseColor("#F4F4F4"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setTextIsSelectable(true)
        }
        root.addView(txtLog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // --- 1 conexion
        val s1 = section(root, "1. Conexion")
        val c1 = row(s1)
        c1.addView(label("IP "))
        edHost = edit(cfg.host, 12, decimal = false, text = true).also { c1.addView(it) }
        c1.addView(label("  Puerto "))
        edPort = edit(cfg.port.toString(), 5, decimal = false).also { c1.addView(it) }
        btnTest = Button(this).apply { text = "Probar conexion"; setOnClickListener { testConnection() } }
        s1.addView(btnTest, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cbSim = CheckBox(this).apply {
            text = "Usar simulador (sin consola)"
            isChecked = cfg.useSim
            setOnCheckedChangeListener { _, _ -> refresh() }
        }
        s1.addView(cbSim)

        // --- 2 fuentes
        val s2 = section(root, "2. Que analizar")
        val grid = GridLayout(this).apply { columnCount = 4 }
        for (n in 1..Scales.N_CHANNELS) {
            val key = "ch$n"
            cbSrc[key] = CheckBox(this).apply { text = "Ch $n"; isChecked = key in cfg.sources }.also { grid.addView(it) }
        }
        s2.addView(grid)
        for (key in listOf("lr") + (1..Scales.N_BUSES).map { "bus$it" }) {
            cbSrc[key] = CheckBox(this).apply { text = Scales.srcLabel(key); isChecked = key in cfg.sources }.also { s2.addView(it) }
        }
        s2.addView(hint("Una sola fuente reacciona mas rapido; con varias, las va rotando."))

        // --- 3 destino
        val s3 = section(root, "3. Donde cortar cuando el feedback viene de un canal")
        rgDest = RadioGroup(this)
        val dests = listOf("auto" to "En el EQ de ese canal (afecta PA y monitores) - recomendado",
                           "lr" to "En el Main LR: solo el PA (afecta a toda la mezcla)") +
                    (1..Scales.N_BUSES).map { "bus$it" to "En el Bus $it: solo ese monitor" }
        for ((key, txt) in dests) {
            val id = nextId++
            destIds[key] = id
            rgDest.addView(RadioButton(this).apply { this.id = id; text = txt })
        }
        rgDest.check(destIds[cfg.dest] ?: destIds.getValue("auto"))
        s3.addView(rgDest)

        // --- 4 bandas
        val s4 = section(root, "4. Bandas de EQ que el programa puede usar")
        val b1 = row(s4)
        b1.addView(label("Canal: "))
        for (b in 1..4) cbChBand[b] = CheckBox(this).apply { text = b.toString(); isChecked = b in cfg.chBands }.also { b1.addView(it) }
        val b2 = row(s4)
        b2.addView(label("Main/Bus: "))
        for (b in 1..6) cbBusBand[b] = CheckBox(this).apply { text = b.toString(); isChecked = b in cfg.busBands }.also { b2.addView(it) }

        // --- 5 agresividad
        val s5 = section(root, "5. Agresividad")
        rgProfile = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (p in listOf("bajo", "medio", "alto")) {
            val id = nextId++
            profileIds[p] = id
            rgProfile.addView(RadioButton(this).apply { this.id = id; text = p.replaceFirstChar { it.uppercase() } + "  " })
        }
        rgProfile.check(profileIds[cfg.profile] ?: profileIds.getValue("medio"))
        txtProfile = hint("")
        rgProfile.setOnCheckedChangeListener { _, _ -> txtProfile.text = profileHelp[selected(profileIds, rgProfile, "medio")] }
        txtProfile.text = profileHelp[selected(profileIds, rgProfile, "medio")]
        s5.addView(rgProfile)
        s5.addView(txtProfile)

        // --- 6 accion
        val s6 = section(root, "6. Accion")
        val rgAct = RadioGroup(this)
        rbWarn = RadioButton(this).apply { id = nextId++; text = "Solo avisar (no toca la consola)" }
        rbCut = RadioButton(this).apply { id = nextId++; text = "Cortar automaticamente" }
        rgAct.addView(rbWarn)
        rgAct.addView(rbCut)
        rgAct.check(if (cfg.apply) rbCut.id else rbWarn.id)
        s6.addView(rgAct)
        cbRestore = CheckBox(this).apply { text = "Al detener, devolver el EQ a como estaba"; isChecked = cfg.restoreOnStop }.also { s6.addView(it) }
        cbOverflow = CheckBox(this).apply { text = "Sin bandas en el canal: seguir cortando en Main LR"; isChecked = cfg.overflowLr }.also { s6.addView(it) }
        val f1 = row(s6)
        cbFader = CheckBox(this).apply { text = "Ultimo recurso: bajar el volumen hasta"; isChecked = cfg.faderGuard }.also { f1.addView(it) }
        edFaderMax = edit(fmt(cfg.faderMax), 2, decimal = true).also { f1.addView(it) }
        f1.addView(label(" dB"))
        val f2 = row(s6)
        f2.addView(label("Soltar un corte si no vuelve en "))
        edRelease = edit(fmt(cfg.releaseMin), 3, decimal = true).also { f2.addView(it) }
        f2.addView(label(" min (0 = nunca)"))
        val f3 = row(s6)
        f3.addView(label("No cortar por debajo de "))
        edMinFreq = edit(fmt(cfg.minFreq), 4, decimal = false).also { f3.addView(it) }
        f3.addView(label(" Hz"))
        s6.addView(hint("Los cambios se aplican al volver a iniciar."))

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    // ------------------------------------------------------------------ piezas de interfaz
    private fun label(t: String, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(Color.parseColor("#222222"))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(Color.parseColor("#666666"))
    }

    private fun pad(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun section(parent: LinearLayout, title: String): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(title, bold = true).apply { setTextColor(Color.parseColor("#0B5D5A")) })
        parent.addView(box, pad(top = 14))
        return box
    }

    private fun row(parent: LinearLayout): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(android.view.Gravity.CENTER_VERTICAL)
        }
        parent.addView(r)
        return r
    }

    private fun edit(value: String, ems: Int, decimal: Boolean, text: Boolean = false) = EditText(this).apply {
        setText(value)
        setEms(ems)
        textSize = 14f
        setSingleLine(true)
        inputType = when {
            text -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else -> InputType.TYPE_CLASS_NUMBER
        }
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun num(e: EditText, default: Double, lo: Double, hi: Double): Double =
        (e.text.toString().replace(',', '.').toDoubleOrNull() ?: default).coerceIn(lo, hi)

    private fun selected(ids: Map<String, Int>, rg: RadioGroup, default: String): String =
        ids.entries.firstOrNull { it.value == rg.checkedRadioButtonId }?.key ?: default

    // ------------------------------------------------------------------ configuracion y acciones
    private fun collect() = Config(
        host = edHost.text.toString().trim().ifEmpty { Config().host },
        port = num(edPort, 10024.0, 1.0, 65535.0).toInt(),
        sources = cbSrc.filter { it.value.isChecked }.keys.toList(),
        dest = selected(destIds, rgDest, "auto"),
        chBands = cbChBand.filter { it.value.isChecked }.keys.toList(),
        busBands = cbBusBand.filter { it.value.isChecked }.keys.toList(),
        profile = selected(profileIds, rgProfile, "medio"),
        apply = rbCut.isChecked,
        restoreOnStop = cbRestore.isChecked,
        overflowLr = cbOverflow.isChecked,
        faderGuard = cbFader.isChecked,
        faderMax = num(edFaderMax, 6.0, 0.0, 20.0),
        releaseMin = num(edRelease, 0.0, 0.0, 240.0),
        minFreq = num(edMinFreq, 160.0, 40.0, 2000.0),
        useSim = cbSim.isChecked
    )

    private fun start() {
        if (AppState.running) return
        val cfg = collect()
        if (cfg.sources.isEmpty()) {
            AppState.log("Elegi al menos una fuente para analizar (seccion 2).")
            return
        }
        if (cfg.apply && cfg.chBands.isEmpty() && cfg.busBands.isEmpty()) {
            AppState.log("Elegi al menos una banda de EQ que el programa pueda usar (seccion 4).")
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
            AppState.log("ERROR: no pude iniciar el servicio: $e")
        }
    }

    private fun testConnection() {
        val host = edHost.text.toString().trim()
        val port = num(edPort, 10024.0, 1.0, 65535.0).toInt()
        val binder = AppState.wifiBinder(this)
        AppState.log("Probando $host:$port ...")
        Thread {
            var info: List<Any>? = null
            var err = "sin respuesta"
            try {
                val m = XAirClient(host, port, binder)
                info = m.info(2000)
                m.close()
            } catch (e: Exception) {
                err = e.toString()
            }
            if (info != null) {
                AppState.log("Consola encontrada: " + info.joinToString(" / "))
            } else {
                AppState.log("ERROR: no hay respuesta de $host ($err). Revisa que el telefono este en el Wi-Fi de la consola y la IP.")
            }
        }.start()
    }

    private fun simFeedback(freqOverride: Double?) {
        val sim = AppState.sim
        if (sim == null) {
            AppState.log("Primero toca INICIAR con el simulador activado.")
            return
        }
        val ch = spSimCh.selectedItemPosition + 1
        val freq = freqOverride ?: num(edSimFreq, 2500.0, 60.0, 15000.0)
        val pathPos = spSimPath.selectedItemPosition
        val path = if (pathPos == 0) "lr" else "bus/$pathPos"
        val excess = doubleArrayOf(3.0, 5.0, 8.0)[spSimForce.selectedItemPosition.coerceIn(0, 2)]
        sim.addFeedback(ch, freq, path, excess)
        AppState.log("[simulador] feedback en canal %d, %.0f Hz, por %s".format(ch, freq, spSimPath.selectedItem))
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
        btnStart.isEnabled = !running
        btnStart.alpha = if (running) 0.45f else 1f
        btnStop.isEnabled = running
        btnStop.alpha = if (running) 1f else 0.45f
        txtStatus.text = AppState.status
        val simOn = cbSim.isChecked
        simPanel.visibility = if (simOn) View.VISIBLE else View.GONE
        edHost.isEnabled = !simOn
        edPort.isEnabled = !simOn
        btnTest.isEnabled = !simOn

        val cuts = AppState.cuts
        txtCuts.text = if (cuts.isEmpty()) "Ninguno" else cuts.joinToString("\n") { r ->
            if (r.freq == null) "%s: volumen -%.0f dB".format(r.where, r.depth)
            else "%s, banda %s: %.0f Hz, -%.0f dB".format(r.where, r.band, r.freq, r.depth)
        }

        val sb = SpannableStringBuilder()
        for (line in AppState.logLines.takeLast(40)) {
            val from = sb.length
            sb.append(line).append('\n')
            val color = when {
                "ALERTA" in line || "ERROR" in line || "AVISO" in line -> Color.parseColor("#B00020")
                "corte" in line || "volumen" in line -> Color.parseColor("#0B5D2A")
                else -> null
            }
            if (color != null) sb.setSpan(ForegroundColorSpan(color), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
