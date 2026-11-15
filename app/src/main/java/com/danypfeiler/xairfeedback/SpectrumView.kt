package com.danypfeiler.xairfeedback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import com.danypfeiler.xairfeedback.core.Scales

/** Dibuja las 100 bandas del analizador, los picos sospechosos y las marcas de los cortes. */
class SpectrumView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val bar = Paint().apply { color = Color.parseColor("#2AA7A0") }
    private val cand = Paint().apply { color = Color.parseColor("#FFB02E") }
    private val grid = Paint().apply { color = Color.parseColor("#232C38"); strokeWidth = d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7F8B99"); textSize = 9 * d }
    private val cut = Paint().apply {
        color = Color.parseColor("#FF5A5A"); strokeWidth = 1.5f * d
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
    }
    private val cutText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8A8A"); textSize = 10 * d; isFakeBoldText = true
    }
    private val dbMin = -90f
    private val left = 26 * d
    private val bottom = 16 * d
    private val top = 6 * d

    private fun x(bin: Double) = left + (width - left - 6 * d) * (bin / 99.0).toFloat()
    private fun y(db: Float) = top + (height - bottom - top) * (-db.coerceIn(dbMin, 0f)) / (-dbMin)

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#10151C"))
        val base = height - bottom
        var db = 0
        while (db >= -90) {
            val yy = y(db.toFloat())
            c.drawLine(left, yy, width - 6 * d, yy, grid)
            c.drawText(db.toString(), 2 * d, yy + 3 * d, text)
            db -= 30
        }
        val marks = listOf(100.0 to "100", 500.0 to "500", 1000.0 to "1k", 2000.0 to "2k", 5000.0 to "5k", 10000.0 to "10k")
        for ((f, label) in marks) {
            val xx = x(Scales.freqBin(f))
            c.drawLine(xx, top, xx, base, grid)
            c.drawText(label, xx - text.measureText(label) / 2, height - 3 * d, text)
        }
        val frame = AppState.specFrame
        if (frame != null && frame.size >= 100) {
            val cands = AppState.specCands
            val bw = (width - left - 6 * d) / 99f * 0.8f
            for (i in 0 until 100) {
                val xx = x(i.toDouble())
                c.drawRect(xx - bw / 2, y(frame[i]), xx + bw / 2, base, if (cands.contains(i)) cand else bar)
            }
        }
        var row = 0
        for (r in AppState.cuts) {
            val binf = r.binf ?: continue
            val xx = x(binf)
            c.drawLine(xx, top, xx, base, cut)
            val label = "%.0f Hz -%.0f".format(r.freq ?: 0.0, r.depth)
            val tx = if (xx + cutText.measureText(label) + 4 * d > width) xx - cutText.measureText(label) - 3 * d else xx + 3 * d
            c.drawText(label, tx, top + 11 * d + (row % 3) * 12 * d, cutText)
            row++
        }
    }
}
