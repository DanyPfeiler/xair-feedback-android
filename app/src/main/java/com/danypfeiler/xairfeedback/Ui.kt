package com.danypfeiler.xairfeedback

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/** Colores y piezas visuales de la app (tema oscuro, pensado para leerse en un escenario). */
object Ui {
    val BG = Color.parseColor("#0E1217")
    val CARD = Color.parseColor("#171D25")
    val FIELD = Color.parseColor("#222B37")
    val LINE = Color.parseColor("#2C3745")
    val TEXT = Color.parseColor("#E8EDF2")
    val MUTED = Color.parseColor("#8A96A3")
    val ACCENT = Color.parseColor("#2EC4B6")
    val AMBER = Color.parseColor("#FFB02E")
    val RED = Color.parseColor("#FF5A5A")
    val GREEN = Color.parseColor("#3DDC84")
    val GREY = Color.parseColor("#5D6875")

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun round(color: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color)
        setCornerRadius(radius.toFloat())
        if (stroke != null) setStroke(strokeWidth, stroke)
    }

    fun text(ctx: Context, t: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
}

/** Acomoda los hijos en renglones, bajando al siguiente cuando no entran. */
class FlowLayout(ctx: Context) : ViewGroup(ctx) {
    private val gap = Ui.dp(ctx, 8)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            x += c.measuredWidth + gap
            rowH = max(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = max(rowH, c.measuredHeight)
        }
    }
}

/** Pastilla que se prende y se apaga al tocarla. */
class Chip(ctx: Context, label: String, checked: Boolean = false) : TextView(ctx) {
    var onChange: ((Boolean) -> Unit)? = null
    var checked: Boolean = checked
        set(v) {
            field = v
            style()
        }

    init {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        minWidth = Ui.dp(ctx, 44)
        setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 9), Ui.dp(ctx, 14), Ui.dp(ctx, 9))
        style()
        setOnClickListener {
            this.checked = !this.checked
            onChange?.invoke(this.checked)
        }
    }

    private fun style() {
        val r = Ui.dp(context, 20)
        if (checked) {
            background = Ui.round(Ui.ACCENT, r)
            setTextColor(Color.parseColor("#06201D"))
            typeface = Typeface.DEFAULT_BOLD
        } else {
            background = Ui.round(Ui.FIELD, r, Ui.LINE, Ui.dp(context, 1))
            setTextColor(Ui.TEXT)
            typeface = Typeface.DEFAULT
        }
    }
}

/**
 * Selector de una sola opcion. Horizontal: botones pegados (para textos cortos).
 * Vertical: renglones con titulo y descripcion (para opciones que hay que explicar).
 */
class Choice(ctx: Context, private val vertical: Boolean) : LinearLayout(ctx) {
    private class Item(val key: String, val view: LinearLayout, val title: TextView, val sub: TextView?)

    private val items = ArrayList<Item>()
    var onChange: ((String) -> Unit)? = null
    var value: String = ""
        set(v) {
            field = v
            style()
        }

    init {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        if (!vertical) {
            background = Ui.round(Ui.FIELD, Ui.dp(ctx, 12))
            setPadding(Ui.dp(ctx, 3), Ui.dp(ctx, 3), Ui.dp(ctx, 3), Ui.dp(ctx, 3))
        }
    }

    fun add(key: String, title: String, subtitle: String? = null) {
        val ctx = context
        val box = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setGravity(if (vertical) Gravity.START else Gravity.CENTER)
            val h = Ui.dp(ctx, if (vertical) 14 else 8)
            setPadding(h, Ui.dp(ctx, 10), h, Ui.dp(ctx, 10))
        }
        val t = Ui.text(ctx, title, 14f)
        if (!vertical) t.gravity = Gravity.CENTER
        box.addView(t)
        var s: TextView? = null
        if (subtitle != null) {
            s = Ui.text(ctx, subtitle, 12f, Ui.MUTED)
            box.addView(s)
        }
        box.setOnClickListener {
            value = key
            onChange?.invoke(key)
        }
        val lp = if (vertical) LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            if (items.isNotEmpty()) topMargin = Ui.dp(ctx, 8)
        } else LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        addView(box, lp)
        items.add(Item(key, box, t, s))
        style()
    }

    fun setTitle(key: String, title: String) {
        items.firstOrNull { it.key == key }?.title?.text = title
    }

    private fun style() {
        val ctx = context
        for (it in items) {
            val on = it.key == value
            if (vertical) {
                it.view.background = if (on) Ui.round(Color.parseColor("#12332F"), Ui.dp(ctx, 12), Ui.ACCENT, Ui.dp(ctx, 2))
                                     else Ui.round(Ui.FIELD, Ui.dp(ctx, 12), Ui.LINE, Ui.dp(ctx, 1))
                it.title.setTextColor(Ui.TEXT)
                it.title.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            } else {
                it.view.background = if (on) Ui.round(Ui.ACCENT, Ui.dp(ctx, 10)) else null
                it.title.setTextColor(if (on) Color.parseColor("#06201D") else Ui.TEXT)
                it.title.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }
}
