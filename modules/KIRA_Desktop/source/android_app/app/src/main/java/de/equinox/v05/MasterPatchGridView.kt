package de.equinox.v05

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Kira v0.8.8 master patch bay.
 *
 * Rows are INPUT: text / audio / camera.
 * Columns are OUTPUT: text / video / audio.
 * Every active cell is a visible "cable".  Cable colour belongs to the
 * destination medium: text=red, video=blue, audio=yellow.
 */
class MasterPatchGridView(context: Context) : View(context) {
    private val routes = BooleanArray(9) { true }
    private var onRouteChanged: ((input: Int, output: Int, enabled: Boolean) -> Unit)? = null

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(205, 5, 7, 8)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(70, 230, 236, 234)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(17f)
        typeface = android.graphics.Typeface.MONOSPACE
        color = Color.rgb(192, 204, 196)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(9f)
        typeface = android.graphics.Typeface.MONOSPACE
        color = Color.rgb(94, 110, 99)
    }
    private val cablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val inputSymbols = arrayOf("✎", "🎙", "◉")
    private val outputSymbols = arrayOf("✎", "🎞", "♫")
    private val outputColors = intArrayOf(
        Color.rgb(255, 72, 72),
        Color.rgb(72, 144, 255),
        Color.rgb(255, 216, 72),
    )

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true
        contentDescription = "Master Patch Grid"
    }

    fun setRoutes(values: BooleanArray) {
        for (i in routes.indices) routes[i] = values.getOrElse(i) { true }
        invalidate()
    }

    fun routeSnapshot(): BooleanArray = routes.copyOf()

    fun setOnRouteChanged(listener: (input: Int, output: Int, enabled: Boolean) -> Unit) {
        onRouteChanged = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(250f).toInt()
        setMeasuredDimension(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(32f)
        val top = dp(34f)
        val rightPad = dp(8f)
        val bottomPad = dp(8f)
        val gridW = width - left - rightPad
        val gridH = height - top - bottomPad
        val cell = min(gridW / 3f, gridH / 3f)
        val radius = dp(12f)

        // Destination legend above the patch bay.
        for (c in 0 until 3) {
            val cx = left + c * cell + cell / 2f
            textPaint.color = outputColors[c]
            canvas.drawText(outputSymbols[c], cx, top - dp(12f), textPaint)
        }
        canvas.save()
        canvas.rotate(-90f, dp(10f), top + gridH / 2f)
        hintPaint.color = Color.rgb(102, 118, 106)
        canvas.drawText("IN → OUT", dp(10f), top + gridH / 2f, hintPaint)
        canvas.restore()

        for (r in 0 until 3) {
            val cy = top + r * cell + cell / 2f
            textPaint.color = Color.rgb(165, 180, 168)
            canvas.drawText(inputSymbols[r], left - dp(16f), cy + dp(6f), textPaint)
            for (c in 0 until 3) {
                val l = left + c * cell + dp(4f)
                val t = top + r * cell + dp(4f)
                val rect = RectF(l, t, l + cell - dp(8f), t + cell - dp(8f))
                boxPaint.color = Color.argb(if (routes[r * 3 + c]) 218 else 166, 3, 5, 6)
                canvas.drawRoundRect(rect, radius, radius, boxPaint)
                borderPaint.color = if (routes[r * 3 + c]) Color.argb(92, 230, 236, 234) else Color.argb(34, 230, 236, 234)
                canvas.drawRoundRect(rect, radius, radius, borderPaint)

                val x1 = rect.left + rect.width() * 0.24f
                val y1 = rect.top + rect.height() * 0.28f
                val x2 = rect.right - rect.width() * 0.24f
                val y2 = rect.bottom - rect.height() * 0.28f
                val color = outputColors[c]
                dotPaint.color = Color.argb(if (routes[r * 3 + c]) 245 else 65, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawCircle(x1, y1, dp(3.2f), dotPaint)
                canvas.drawCircle(x2, y2, dp(3.2f), dotPaint)
                if (routes[r * 3 + c]) {
                    cablePaint.color = color
                    cablePaint.setShadowLayer(dp(5f), 0f, 0f, color)
                    canvas.drawLine(x1, y1, x2, y2, cablePaint)
                    cablePaint.clearShadowLayer()
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val left = dp(32f)
        val top = dp(34f)
        val gridW = width - left - dp(8f)
        val gridH = height - top - dp(8f)
        val cell = min(gridW / 3f, gridH / 3f)
        val c = ((event.x - left) / cell).toInt()
        val r = ((event.y - top) / cell).toInt()
        if (r !in 0..2 || c !in 0..2) return true
        val index = r * 3 + c
        routes[index] = !routes[index]
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
        onRouteChanged?.invoke(r, c, routes[index])
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
