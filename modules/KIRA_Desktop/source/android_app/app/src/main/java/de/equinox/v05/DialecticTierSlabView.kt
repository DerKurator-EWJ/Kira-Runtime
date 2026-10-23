package de.equinox.v05

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Two minimal glass bands: light thesis tier, dark antithesis tier. Only tier symbols are rendered. */
class DialecticTierSlabView(context: Context) : View(context) {
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 242, 242, 242) }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 0, 0, 0) }
    private val lightBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(95, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.2f }
    private val darkBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 140, 150, 145); style = Paint.Style.STROKE; strokeWidth = 1.2f }
    private val blackText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 18, 16); textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.MONOSPACE }
    private val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 232, 227); textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.MONOSPACE }
    private var state = DialecticDecisionState()

    fun setState(value: DialecticDecisionState) { state = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val d = resources.displayMetrics.density
        val gap = 6f * d
        val half = (height - gap) / 2f
        val radius = 14f * d
        val top = RectF(0f, 0f, width.toFloat(), half)
        val bottom = RectF(0f, half + gap, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(top, radius, radius, light); canvas.drawRoundRect(top, radius, radius, lightBorder)
        canvas.drawRoundRect(bottom, radius, radius, dark); canvas.drawRoundRect(bottom, radius, radius, darkBorder)
        drawTiers(canvas, state.tierLabels(DialecticBranch.THESIS), top, blackText, !state.open && state.winner == DialecticBranch.THESIS)
        drawTiers(canvas, state.tierLabels(DialecticBranch.ANTITHESIS), bottom, whiteText, !state.open && state.winner == DialecticBranch.ANTITHESIS)
    }

    private fun drawTiers(canvas: Canvas, raw: List<String>, box: RectF, paint: Paint, winner: Boolean) {
        val labels = if (raw.size <= 11) raw else raw.take(9) + listOf(raw.last())
        if (labels.isEmpty()) return
        paint.textSize = (if (winner) 14.5f else 13f) * resources.displayMetrics.scaledDensity
        paint.alpha = if (winner) 255 else 155
        val baseline = box.centerY() - (paint.ascent() + paint.descent()) / 2f
        labels.forEachIndexed { i, label ->
            val x = (i + 1f) * box.width() / (labels.size + 1f)
            canvas.drawText(label, x, baseline, paint)
        }
    }
}
