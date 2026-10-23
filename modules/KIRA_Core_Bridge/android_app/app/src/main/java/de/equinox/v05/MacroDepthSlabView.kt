package de.equinox.v05

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/**
 * Product-facing read-only microscope for the currently used macro hierarchy.
 * It never writes to MemoryModel. The glass slab deliberately exposes depth,
 * nesting and the actual active IDs used by the current core mode.
 */
class MacroDepthSlabView(context: Context) : View(context) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 0, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(155, 0, 235, 95)
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 255, 120) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(184, 225, 194)
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val dimText = Paint(text).apply { color = Color.rgb(92, 130, 102) }

    private var mode = CoreOutputMode.WRITE
    private var nodes: List<ActiveMacroNode> = emptyList()

    fun setState(mode: CoreOutputMode, snapshot: WorkingSetSnapshot?) {
        this.mode = mode
        this.nodes = snapshot?.nodes.orEmpty()
            .sortedWith(compareBy<ActiveMacroNode> { it.abstractionLevel }.thenByDescending { max(it.activation, it.expectation) })
            .take(28)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val pad = 10f * resources.displayMetrics.density
        val box = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(box, 16f, 16f, bg)
        canvas.drawRoundRect(box, 16f, 16f, border)

        val maxDepth = nodes.maxOfOrNull { it.abstractionLevel } ?: 0
        val tierLabel = nodes.maxByOrNull { it.abstractionLevel }?.abstractionTier ?: "—"
        canvas.drawText("${mode.symbol} ${mode.label}  ·  TIEFE $maxDepth  ·  $tierLabel", pad, pad + text.textSize, text)

        if (nodes.isEmpty()) {
            canvas.drawText("keine aktive Makrostruktur", pad, pad + text.textSize * 2.4f, dimText)
            return
        }

        val groups = nodes.groupBy { it.abstractionLevel }.toSortedMap()
        val topY = pad + text.textSize * 2.15f
        val bottomY = height - pad - text.textSize * 1.55f
        val levels = groups.keys.toList()
        val usableH = (bottomY - topY).coerceAtLeast(12f)
        val rowStep = usableH / max(1, levels.size - 1)
        val left = pad
        val right = width - pad
        var previousCenters: List<Pair<Float, Float>> = emptyList()

        levels.forEachIndexed { row, depth ->
            val y = if (levels.size == 1) (topY + bottomY) / 2f else topY + row * rowStep
            val candidates = groups[depth].orEmpty().sortedByDescending { max(it.activation, it.expectation) }.take(7)
            val centers = candidates.mapIndexed { index, n ->
                val x = left + (index + 1f) * (right - left) / (candidates.size + 1f)
                val strength = max(n.activation, n.expectation).coerceIn(0.0, 1.0).toFloat()
                if (previousCenters.isNotEmpty()) {
                    val parent = previousCenters[index % previousCenters.size]
                    val path = Path().apply {
                        moveTo(parent.first, parent.second)
                        cubicTo(parent.first, (parent.second + y) / 2f, x, (parent.second + y) / 2f, x, y)
                    }
                    line.alpha = (65 + strength * 130f).toInt()
                    canvas.drawPath(path, line)
                }
                nodePaint.alpha = (100 + strength * 155f).toInt()
                canvas.drawCircle(x, y, 2.2f * resources.displayMetrics.density + strength * 2.4f, nodePaint)
                x to y
            }
            canvas.drawText("${AbstractionTier.labelForMacroDepth(depth.coerceAtLeast(1))} · $depth", left, y - 5f, dimText)
            previousCenters = centers
        }

        val ids = nodes.sortedByDescending { it.abstractionLevel * 2.0 + max(it.activation, it.expectation) }
            .take(3)
            .joinToString("  ") { "${it.modality.take(3)}:${it.id}" }
        canvas.drawText(ids, pad, height - pad, dimText)
    }
}
