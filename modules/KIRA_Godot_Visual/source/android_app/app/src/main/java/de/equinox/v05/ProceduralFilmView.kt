package de.equinox.v05

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Bootstrap film generator: produces moving frames even with an empty learned memory. */
class ProceduralFilmView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var directive: FilmGenerationDirective? = null
    private var ids: List<Long> = emptyList()
    private var animate = false

    fun setState(d: FilmGenerationDirective?, activeIds: List<Long>, animate: Boolean) {
        directive = d
        ids = activeIds.take(32)
        this.animate = animate && d != null
        visibility = if (d != null) VISIBLE else GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = directive ?: return
        canvas.drawColor(Color.BLACK)
        val t = SystemClock.uptimeMillis() / 1000.0 * (0.18 + d.motion * 0.9 + d.audioDrive * 0.28 + d.cameraDrive * 0.22)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val count = (18 + d.intensity * 24 + d.textDrive * 8 + d.cameraDrive * 10).toInt().coerceIn(18, 60)
        val baseSeed = if (ids.isEmpty()) d.seed else ids.fold(d.seed) { a, b -> a xor b }
        repeat(count) { i ->
            val seed = mix(baseSeed + i * 0x9E37L)
            val phase = ((seed ushr 8) and 0xffff).toDouble() / 65535.0 * PI * 2.0
            val y0 = ((seed ushr 24) and 0xffff).toFloat() / 65535f * h
            val amp = h * (0.025f + (((seed ushr 40) and 0xff).toFloat() / 255f) * 0.12f) *
                (0.45f + d.intensity + d.cameraDrive * 0.35f + d.audioDrive * 0.12f)
            val path = Path()
            val steps = 18
            for (s in 0..steps) {
                val x = w * s / steps.toFloat()
                val nx = s / steps.toDouble()
                val textOrder = (1.0 - d.textDrive * 0.28).coerceIn(0.62, 1.0)
                val cameraWarp = sin(nx * PI * (1.0 + d.cameraDrive * 4.0) + phase * 0.4 + t * 0.55) * h * d.cameraDrive * 0.028
                val audioPulse = cos(t * (0.7 + d.audioDrive * 2.2) + phase) * h * (d.pulse + d.audioDrive * 0.35f) * 0.028f
                val y = y0 + sin(nx * PI * (2.0 + (i % 5)) * textOrder + phase + t * (0.7 + i % 3 * 0.2)) * amp + cameraWarp + audioPulse
                if (s == 0) path.moveTo(x, y.toFloat()) else path.lineTo(x, y.toFloat())
            }
            val hue = ((seed and 0xffff).toFloat() / 65535f * 300f + t.toFloat() * 8f) % 360f
            paint.color = Color.HSVToColor((55 + d.intensity * 145).toInt().coerceIn(40, 210), floatArrayOf(hue, 0.55f, 0.85f))
            paint.strokeWidth = 0.8f + d.intensity * 2.8f
            canvas.drawPath(path, paint)
        }
        if (animate) postInvalidateOnAnimation()
    }

    private fun mix(v: Long): Long {
        var x = v
        x = (x xor (x ushr 33)) * -49064778989728563L
        x = (x xor (x ushr 33)) * -4265267296055464877L
        return x xor (x ushr 33)
    }
}
