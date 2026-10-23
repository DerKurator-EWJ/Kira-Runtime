package de.equinox.v05

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Read-only renderer for DreamWorldState.
 *
 * The view never invents semantic state and never writes to MemoryModel. It presents the
 * persistent DREAM world as a 2.5D endless field: moving horizon, terrain strands,
 * relation arcs and world entities.  A later OpenGL/Vulkan backend can replace this view
 * without changing DreamRuntime.
 */
class DreamWorldView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var world: DreamWorldState? = null
    private var animate = false
    private var lastSetAtMs = 0L

    fun setWorldState(state: DreamWorldState?, animate: Boolean) {
        world = state
        this.animate = animate && state != null
        lastSetAtMs = SystemClock.uptimeMillis()
        visibility = if (state != null) VISIBLE else GONE
        invalidate()
    }

    fun clearWorld() {
        world = null
        animate = false
        visibility = GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = world ?: return
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val now = SystemClock.uptimeMillis()
        val frameT = ((now - lastSetAtMs).coerceAtLeast(0L) / 1000.0).coerceAtMost(2.0)
        val baseHue = (state.environment.hue * 360.0).toFloat()

        drawSky(canvas, w, h, state, baseHue)
        drawEndlessFloor(canvas, w, h, state, baseHue, frameT)
        drawLinks(canvas, w, h, state, baseHue, frameT)
        drawNodes(canvas, w, h, state, baseHue, frameT)
        drawAtmosphere(canvas, w, h, state, baseHue)

        if (animate) postInvalidateOnAnimation()
    }

    private fun drawSky(canvas: Canvas, w: Float, h: Float, state: DreamWorldState, hue: Float) {
        val top = hsv(hue + 28f, 0.56f * state.environment.saturation.toFloat(), (0.10 + state.environment.luminance * 0.22).toFloat(), 255)
        val mid = hsv(hue, 0.70f * state.environment.saturation.toFloat(), (0.06 + state.environment.luminance * 0.16).toFloat(), 255)
        val bottom = Color.rgb(2, 3, 5)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(top, mid, bottom), floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawEndlessFloor(
        canvas: Canvas,
        w: Float,
        h: Float,
        state: DreamWorldState,
        hue: Float,
        frameT: Double,
    ) {
        val horizon = h * (0.46f + state.camera.pitch.toFloat() * 0.16f)
        val curve = state.environment.curvature.coerceIn(0.0, 1.0)
        val motion = state.camera.yaw + state.tick * 0.018 + frameT * 0.11
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1f

        // Perspective lanes: give DREAM a world-like spatial frame without fixed assets.
        repeat(15) { lane ->
            val u = (lane - 7) / 7.0
            val path = Path()
            repeat(30) { s ->
                val depth = s / 29.0
                val perspective = depth * depth
                val y = horizon + (h - horizon) * perspective.toFloat()
                val bend = sin(motion + u * 1.8 + depth * 2.4) * curve * w * 0.035
                val x = w * 0.5 + (u * (w * 0.11 + perspective * w * 0.62)).toFloat() + bend.toFloat()
                if (s == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
            }
            val alpha = (20 + 46 * state.stability + 28 * state.energy).toInt().coerceIn(14, 110)
            stroke.color = hsv(hue + lane * 2.3f, 0.44f, 0.72f, alpha)
            canvas.drawPath(path, stroke)
        }

        // Cross-depth ripples make the floor read as a moving infinite surface.
        repeat(13) { ring ->
            val phase = ((ring / 13.0 + (state.tick * 0.012 + frameT * 0.08)) % 1.0)
            val depth = phase * phase
            val y = horizon + (h - horizon) * depth.toFloat()
            val half = (w * (0.06 + depth * 0.66)).toFloat()
            stroke.strokeWidth = (0.6 + depth * 1.4).toFloat()
            stroke.color = hsv(hue - 16f, 0.40f, 0.82f, (18 + depth * 70).toInt())
            canvas.drawLine(w * 0.5f - half, y, w * 0.5f + half, y, stroke)
        }
    }

    private data class Projected(val x: Float, val y: Float, val scale: Float, val depth: Float)

    private fun project(node: DreamNode, state: DreamWorldState, w: Float, h: Float, frameT: Double): Projected {
        val cam = state.camera
        val yaw = cam.yaw + frameT * 0.018
        val dx = node.x - cam.x
        val dy = node.y - cam.y
        val dz = node.z - cam.z
        val cy = cos(yaw)
        val sy = sin(yaw)
        val rx = dx * cy - dz * sy
        val rz = dx * sy + dz * cy
        val depth = (1.85 + rz).coerceIn(0.40, 3.2)
        val perspective = cam.zoom / depth
        val px = w * 0.5f + (rx * w * 0.36 * perspective).toFloat()
        val py = h * 0.47f + (dy * h * 0.31 * perspective).toFloat() - (rz * h * 0.045).toFloat()
        return Projected(px, py, (perspective * node.scale).toFloat().coerceIn(0.16f, 2.8f), depth.toFloat())
    }

    private fun drawLinks(
        canvas: Canvas,
        w: Float,
        h: Float,
        state: DreamWorldState,
        hue: Float,
        frameT: Double,
    ) {
        val nodes = state.nodes.associateBy { it.id }
        state.links.take(140).forEachIndexed { index, link ->
            val a = nodes[link.fromId] ?: return@forEachIndexed
            val b = nodes[link.toId] ?: return@forEachIndexed
            val pa = project(a, state, w, h, frameT)
            val pb = project(b, state, w, h, frameT)
            if (!insideLoose(pa, w, h) && !insideLoose(pb, w, h)) return@forEachIndexed
            val flicker = 0.58 + 0.42 * sin(state.tick * 0.08 + link.phase + frameT * 3.0)
            val alpha = (14 + 92 * link.strength * flicker * (0.45 + state.stability * 0.55)).toInt().coerceIn(8, 128)
            stroke.color = hsv(hue + 24f + (index % 5) * 3f, 0.44f, 0.94f, alpha)
            stroke.strokeWidth = (0.45 + link.strength * 1.8).toFloat()
            val path = Path()
            path.moveTo(pa.x, pa.y)
            val mx = (pa.x + pb.x) * 0.5f
            val my = (pa.y + pb.y) * 0.5f
            val normalX = -(pb.y - pa.y)
            val normalY = pb.x - pa.x
            val norm = max(1f, kotlin.math.sqrt(normalX * normalX + normalY * normalY))
            val bend = (6f + state.environment.curvature.toFloat() * 22f) * sin(link.phase + frameT * 0.8).toFloat()
            path.quadTo(mx + normalX / norm * bend, my + normalY / norm * bend, pb.x, pb.y)
            canvas.drawPath(path, stroke)
        }
    }

    private fun drawNodes(
        canvas: Canvas,
        w: Float,
        h: Float,
        state: DreamWorldState,
        hue: Float,
        frameT: Double,
    ) {
        state.nodes
            .map { it to project(it, state, w, h, frameT) }
            .sortedByDescending { it.second.depth }
            .forEachIndexed { index, (node, p) ->
                if (!insideLoose(p, w, h)) return@forEachIndexed
                val pulse = 0.74 + 0.26 * sin(node.phase + state.tick * 0.05 + frameT * (1.0 + state.environment.pulse * 3.0))
                val r = (3.0 + 13.0 * node.energy * p.scale * pulse).toFloat().coerceIn(2.2f, 34f)
                val nodeHue = hue + ((node.id ushr 13) and 0x3f).toFloat() - 28f + state.tension.toFloat() * 18f
                val outer = hsv(nodeHue, 0.62f, 0.92f, (38 + node.energy * 80).toInt())
                val inner = hsv(nodeHue + 18f, 0.30f, 1.0f, (145 + node.energy * 90).toInt().coerceAtMost(245))
                paint.style = Paint.Style.FILL
                paint.shader = RadialGradient(p.x, p.y, r * 2.4f, intArrayOf(inner, outer, Color.TRANSPARENT), floatArrayOf(0f, 0.22f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(p.x, p.y, r * 2.4f, paint)
                paint.shader = null

                stroke.color = hsv(nodeHue, 0.28f, 1.0f, (90 + node.energy * 120).toInt().coerceAtMost(230))
                stroke.strokeWidth = (0.7f + p.scale * 0.7f)
                canvas.drawCircle(p.x, p.y, r, stroke)

                // A tiny directional mark turns nodes into world entities instead of particles.
                if (index % 2 == 0 && r > 5f) {
                    val tx = p.x + cos(node.phase).toFloat() * r * 1.8f
                    val ty = p.y + sin(node.phase).toFloat() * r * 1.8f
                    stroke.color = hsv(nodeHue + 30f, 0.25f, 1f, 84)
                    stroke.strokeWidth = 0.8f
                    canvas.drawLine(p.x, p.y, tx, ty, stroke)
                }
            }
    }

    private fun drawAtmosphere(canvas: Canvas, w: Float, h: Float, state: DreamWorldState, hue: Float) {
        val fogAlpha = (state.environment.fog * 96).toInt().coerceIn(0, 92)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, h * 0.35f, 0f, h,
            intArrayOf(Color.TRANSPARENT, hsv(hue - 20f, 0.24f, 0.32f, fogAlpha)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.35f, w, h, paint)
        paint.shader = null
    }

    private fun insideLoose(p: Projected, w: Float, h: Float): Boolean =
        p.x > -w * 0.3f && p.x < w * 1.3f && p.y > -h * 0.3f && p.y < h * 1.3f

    private fun hsv(hue: Float, saturation: Float, value: Float, alpha: Int): Int =
        Color.HSVToColor(alpha.coerceIn(0, 255), floatArrayOf(((hue % 360f) + 360f) % 360f, saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))
}
