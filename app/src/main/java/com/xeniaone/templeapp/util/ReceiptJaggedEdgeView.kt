package com.xeniaone.templeapp.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Custom view that draws a realistic jagged / perforated paper edge
 * for thermal receipts as seen in the Sabarimala Kiosk proposal design.
 */
class ReceiptJaggedEdgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val path = Path()

    var isTopEdge: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    init {
        // Read custom attribute or tag if present
        if (tag == "bottom") {
            isTopEdge = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        path.reset()
        val toothWidth = 20f
        val count = (w / toothWidth).toInt().coerceAtLeast(1)
        val actualToothWidth = w / count

        if (isTopEdge) {
            // Teeth point UP towards y = 0, flat bottom at y = h
            path.moveTo(0f, h)
            for (i in 0 until count) {
                val startX = i * actualToothWidth
                val midX = startX + actualToothWidth / 2f
                val endX = (i + 1) * actualToothWidth
                path.lineTo(midX, 2f)
                path.lineTo(endX, h)
            }
            path.lineTo(w, h)
            path.close()
        } else {
            // Flat top at y = 0, teeth point DOWN towards y = h
            path.moveTo(0f, 0f)
            for (i in 0 until count) {
                val startX = i * actualToothWidth
                val midX = startX + actualToothWidth / 2f
                val endX = (i + 1) * actualToothWidth
                path.lineTo(midX, h - 2f)
                path.lineTo(endX, 0f)
            }
            path.lineTo(w, 0f)
            path.close()
        }
        canvas.drawPath(path, paint)
    }
}
