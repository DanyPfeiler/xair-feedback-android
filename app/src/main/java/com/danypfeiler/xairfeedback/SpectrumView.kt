package com.danypfeiler.xairfeedback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Outline
import android.graphics.Paint
import android.view.View
import android.view.ViewOutlineProvider
import com.danypfeiler.xairfeedback.core.Scales

/** Dibuja las 100 bandas del analizador, los picos sospechosos y las marcas de los cortes. */
class SpectrumView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val bg = Color.parseColor("#0A0E13")
    private val bar = Paint().apply { color = Ui.ACCENT }
    private val barDim = Paint().apply { color = Color.parseColor("#1D5C59") }
    private val cand = Paint().apply { color = Ui.AMBER }
    private val grid = Paint().apply { color = Color.parseColor("#1B232D"); strokeWidth = d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GREY; textSize = 9 * d }
    private val peakText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.AMBER; textSize = 12 * d; isFakeBoldText = true }
    private val idle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GREY; textSize = 13 * d }
    private val cut = Paint().apply {
        color = Ui.RED; strokeWidth = 1.5f * d
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
    }
    private val cutText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF8A8A"); textSize = 10 * d; isFakeBoldText = true }
    private val dbMin = -90f
    private val left = 26 * d
    private val bottom = 16 * d
    private val top = 8 * d

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 12 * d)
            }
        }
        clipToOutline = true
    }

    private fun x(bin: Double) = left + (width - left - 8 * d) * (bin / 99.0).toFloat()
    private fun y(db: Float) = top + (height - bottom - top) * (-db.coerceIn(dbMin, 0f)) / (-dbMin)

    override fun onDraw(c: Canvas) {
        c.drawColor(bg)
        val base = height - bottom
        var db = 0
        while (db >= -90) {
            val yy = y(db.toFloat())
            c.drawLine(left, yy, width - 8 * d, yy, grid)
            c.drawText(db.toString(), 4 * d, yy + 3 * d, text)
            db -= 30
        }
        val marks = listOf(100.0 to "100", 500.0 to "500", 1000.0 to "1k", 2000.0 to "2k", 5000.0 to "5k", 10000.0 to "10k")
        for ((f, label) in marks) {
            val xx = x(Scales.freqBin(f))
            c.drawLine(xx, top, xx, base, grid)
            c.drawText(label, xx - text.measureText(label) / 2, height - 4 * d, text)
        }
        val frame = AppState.specFrame
        if (!AppState.running || frame == null || frame.size < 100) {
            val msg = "Toca INICIAR para ver el analizador"
            c.drawText(msg, (width - idle.measureText(msg)) / 2, height / 2f, idle)
        } else {
            val cands = AppState.specCands
            val bw = (width - left - 8 * d) / 99f * 0.78f
            var best = -1
            for (i in 0 until 100) {
                val xx = x(i.toDouble())
                val isCand = cands.contains(i)
                if (isCand && (best < 0 || frame[i] > frame[best])) best = i
                val p = if (isCand) cand else if (frame[i] > -60f) bar else barDim
                c.drawRect(xx - bw / 2, y(frame[i]), xx + bw / 2, base, p)
            }
            if (best >= 0) {
                val label = "Pico: %.0f Hz".format(Scales.binFreq(best.toDouble()))
                c.drawText(label, width - peakText.measureText(label) - 12 * d, height - bottom - 8 * d, peakText)
            }
        }
        var row = 0
        for (r in AppState.cuts) {
            val binf = r.binf ?: continue
            val xx = x(binf)
            c.drawLine(xx, top, xx, base, cut)
            val label = "%.0f Hz -%.0f".format(r.freq ?: 0.0, r.depth)
            val w = cutText.measureText(label)
            val tx = if (xx + w + 6 * d > width) xx - w - 4 * d else xx + 4 * d
            c.drawText(label, tx, top + 11 * d + (row % 3) * 12 * d, cutText)
            row++
        }
    }
}
