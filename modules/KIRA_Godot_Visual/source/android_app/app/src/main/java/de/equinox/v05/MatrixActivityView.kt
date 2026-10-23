package de.equinox.v05

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.abs

/**
 * Data-driven activity rain for the terminal surface.
 *
 * It never invents semantic tokens: glyph streams, column positions and speeds are
 * deterministically derived from the real 63-bit Equinox IDs supplied by the MIND/
 * FABRIC path. Animation runs only while processing is active. When processing ends
 * the last frame is frozen and no further frame callbacks are scheduled.
 */
class MatrixActivityView(context: Context) : View(context) {
    data class FlowDroplet(
        val id: Long,
        val xNorm: Float,
        val yNorm: Float,
        val intensity: Float,
    )

    private data class Column(
        val id: Long,
        val xNorm: Float,
        val speedPxPerSecond: Float,
        val phase: Int,
        var headY: Float,
        val glyphs: CharArray,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 15f * resources.displayMetrics.scaledDensity
        color = Color.rgb(0, 255, 0)
    }
    private val columns = mutableListOf<Column>()
    private var activeIds: List<Long> = emptyList()
    private var processing = false
    private var lastFrameMs = 0L
    private var speedMultiplier = 0.32f
    private var pulseEnabled = true
    private var glyphsVisible = true
    private var flowFrameListener: ((List<FlowDroplet>) -> Unit)? = null

    /** Presentation speed only; this never changes Equinox compute speed. */
    fun setSpeedMultiplier(value: Float) {
        speedMultiplier = value.coerceIn(0.05f, 2.5f)
    }

    fun setPulseEnabled(value: Boolean) {
        pulseEnabled = value
        invalidate()
    }

    fun setGlyphsVisible(value: Boolean) {
        glyphsVisible = value
        invalidate()
    }

    fun setFlowFrameListener(listener: ((List<FlowDroplet>) -> Unit)?) {
        flowFrameListener = listener
        invalidate()
    }

    fun setFlowState(ids: List<Long>, animate: Boolean) {
        val next = sanitize(ids)
        if (next != activeIds) {
            activeIds = next
            rebuildColumns(keepHeads = true)
        }
        if (animate && activeIds.isNotEmpty()) {
            if (!processing) lastFrameMs = SystemClock.uptimeMillis()
            processing = true
            postInvalidateOnAnimation()
        } else {
            processing = false
            invalidate()
        }
    }

    fun startProcessing(ids: List<Long>) {
        activeIds = sanitize(ids)
        rebuildColumns(keepHeads = false)
        processing = true
        lastFrameMs = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    fun updateActiveIds(ids: List<Long>) {
        val next = sanitize(ids)
        if (next == activeIds) return
        activeIds = next
        rebuildColumns(keepHeads = true)
        invalidate()
    }

    fun stopAndFreeze(ids: List<Long> = emptyList()) {
        if (ids.isNotEmpty()) updateActiveIds(ids)
        processing = false
        invalidate()
    }

    fun isProcessing(): Boolean = processing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildColumns(keepHeads = false)
    }

    override fun onDetachedFromWindow() {
        processing = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (columns.isEmpty() || width <= 0 || height <= 0) return

        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs).coerceAtMost(48L) / 1000f)
        lastFrameMs = now
        val step = paint.textSize * 1.18f
        val droplets = ArrayList<FlowDroplet>(columns.size)

        columns.forEach { column ->
            if (processing) {
                column.headY += column.speedPxPerSecond * speedMultiplier * dt
                val trailHeight = step * column.glyphs.size
                if (column.headY - trailHeight > height) {
                    column.headY = -step * (2 + column.phase % 9)
                }
            }

            val x = column.xNorm * width
            column.glyphs.indices.forEach { i ->
                val y = column.headY - (column.glyphs.size - 1 - i) * step
                if (y < -step || y > height + step) return@forEach
                val tail = (i + 1).toFloat() / column.glyphs.size.toFloat()
                val pulse = if (pulseEnabled && processing) {
                    0.72f + 0.28f * kotlin.math.sin(now * 0.0017f + column.phase * 0.37f)
                } else 1f
                paint.alpha = ((28 + 150 * tail) * pulse).toInt().coerceIn(0, 190)
                if (glyphsVisible) canvas.drawText(column.glyphs[i].toString(), x, y, paint)
            }
            if (column.headY >= -step && column.headY <= height + step) {
                droplets += FlowDroplet(
                    id = column.id,
                    xNorm = column.xNorm,
                    yNorm = (column.headY / height.toFloat()).coerceIn(-0.08f, 1.08f),
                    intensity = (0.45f + ((column.phase % 9) / 12f)).coerceIn(0.35f, 1f),
                )
            }
        }
        paint.alpha = 255
        flowFrameListener?.invoke(droplets)

        // No idle animation loop: this is the software equivalent of the requested
        // "0-activity" state. The already rendered frame simply remains on screen.
        if (processing) postInvalidateOnAnimation()
    }

    private fun sanitize(ids: List<Long>): List<Long> = ids
        .asSequence()
        .filter { it != 0L }
        .distinct()
        .take(32)
        .toList()

    private fun rebuildColumns(keepHeads: Boolean) {
        if (width <= 0 || height <= 0 || activeIds.isEmpty()) {
            columns.clear()
            return
        }
        val oldColumns = if (keepHeads) columns.toList() else emptyList()
        val oldById = oldColumns.associateBy { it.id }
        columns.clear()
        activeIds.forEachIndexed { index, id ->
            val mixed = id xor (id ushr 29) xor (id shl 17)
            val unsigned = id and Long.MAX_VALUE
            val hex = java.lang.Long.toHexString(unsigned).padStart(16, '0')
            val binaryTail = java.lang.Long.toBinaryString(unsigned).takeLast(8).padStart(8, '0')
            val source = (hex + binaryTail).toCharArray()
            val length = 9 + ((unsigned ushr 7) % 9L).toInt()
            val glyphs = CharArray(length) { i -> source[(i + index) % source.size] }
            val prior = oldById[id] ?: oldColumns.getOrNull(index)
            val xBucket = abs((mixed xor (mixed ushr 32)).toInt()).toLong() % 10_000L
            val derivedX = (xBucket / 10_000f).coerceIn(0.01f, 0.985f)
            val derivedSpeed = (26f + ((unsigned ushr 13) % 78L).toFloat()) * resources.displayMetrics.density
            val derivedPhase = ((unsigned ushr 23) % 31L).toInt()
            // A new question changes the glyph identity, not the physical stream. If an
            // ID disappears, its lane is recycled in-place so the matrix never visually
            // reloads between turns.
            val xNorm = prior?.xNorm ?: derivedX
            val speed = prior?.speedPxPerSecond ?: derivedSpeed
            val phase = prior?.phase ?: derivedPhase
            val initialY = prior?.headY ?: (-paint.textSize * (1 + phase % 13))
            columns += Column(id, xNorm, speed, phase, initialY, glyphs)
        }
    }
}
