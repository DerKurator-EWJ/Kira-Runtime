package de.equinox.v05

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kira v0.8.3 media stage.
 *
 * The uploaded medium is a passive OUTPUT surface.  Two independent state renderers
 * may deform it:
 *  - ID WATER: organic refraction driven only by current 63-bit IDs.
 *  - NOW GATE: an originless neural relation field.  No node/edge geometry is drawn;
 *    relations are visible only because the underlying pixels bend and pulse.
 *
 * The two effects are deliberately independent from the visible ID-matrix glyphs.
 */
class MediaBackdropView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    enum class Kind { NONE, IMAGE, VIDEO }

    private val imageLayer = DeformedImageLayer(context)
    private val videoLayer = TextureView(context)
    private val waterOptics = IdWaterOpticsOverlay(context)
    private var videoWarp: VideoRuntimeWarp? = null

    private var mediaPlayer: MediaPlayer? = null
    private var currentUri: Uri? = null
    private var currentMime: String? = null
    private var kind: Kind = Kind.NONE
    private var mediaEnabled = false

    private var idWaterEnabled = false
    private var waterStrength = 0.72f
    private var waterIds: List<Long> = emptyList()
    private var waterAnimating = false

    private var nowGateEnabled = false
    private var nowGateSpeed = 0.42f
    private var nowGateWorking = WorkingSetSnapshot(emptyList(), emptyList())
    private var nowGateAnimating = false

    // v0.8.5: FILM is an operational procedural director. It does not pretend to be
    // a diffusion/video model; it bends the selected medium from the shared state and
    // exposes a stable backend seam for a future real generator.
    private var filmDirective: FilmGenerationDirective? = null
    private var filmAnimating = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        videoLayer.surfaceTextureListener = this
        addView(imageLayer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(videoLayer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(waterOptics, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        imageLayer.visibility = View.GONE
        videoLayer.visibility = View.GONE
        waterOptics.visibility = View.GONE
        visibility = View.GONE
        if (Build.VERSION.SDK_INT >= 33) videoWarp = VideoRuntimeWarp(videoLayer)
    }

    fun setMedia(uri: Uri?, mime: String?) {
        releasePlayer()
        currentUri = uri
        currentMime = mime
        kind = when {
            uri == null -> Kind.NONE
            mime?.startsWith("video/") == true -> Kind.VIDEO
            else -> Kind.IMAGE
        }
        when (kind) {
            Kind.NONE -> imageLayer.clearImage()
            Kind.IMAGE -> imageLayer.loadImage(uri!!)
            Kind.VIDEO -> Unit
        }
        updateLayers()
        pushEffectState()
        if (kind == Kind.VIDEO && mediaEnabled && videoLayer.isAvailable) startVideoIfReady()
    }

    fun setMediaEnabled(value: Boolean) {
        mediaEnabled = value
        updateLayers()
        if (value && kind == Kind.VIDEO && videoLayer.isAvailable) startVideoIfReady()
        if (!value) mediaPlayer?.pause()
        pushEffectState()
    }

    fun setIdWaterEnabled(value: Boolean) {
        idWaterEnabled = value
        pushEffectState()
    }

    fun setWaterStrength(value: Float) {
        waterStrength = value.coerceIn(0f, 1.6f)
        pushEffectState()
    }

    fun setIdWaterState(ids: List<Long>, animate: Boolean) {
        waterIds = ids.asSequence().filter { it != 0L }.distinct().take(24).toList()
        waterAnimating = animate
        pushEffectState()
    }

    fun setNowGateEnabled(value: Boolean) {
        nowGateEnabled = value
        pushEffectState()
    }

    fun setNowGateSpeed(value: Float) {
        nowGateSpeed = value.coerceIn(0.05f, 2.5f)
        pushEffectState()
    }

    fun setNowGateState(snapshot: WorkingSetSnapshot?, animate: Boolean) {
        nowGateWorking = snapshot ?: WorkingSetSnapshot(emptyList(), emptyList())
        nowGateAnimating = animate
        pushEffectState()
    }

    fun setFilmGeneration(directive: FilmGenerationDirective?, animate: Boolean) {
        filmDirective = directive
        filmAnimating = animate
        pushEffectState()
    }

    /** Backward-compatible wrappers for v0.8.2 callers. */
    fun setRefractionEnabled(value: Boolean) = setIdWaterEnabled(value)
    fun setEffectStrength(value: Float) = setWaterStrength(value)
    fun updateDroplets(@Suppress("UNUSED_PARAMETER") droplets: List<MatrixActivityView.FlowDroplet>) = Unit

    fun hasMedia(): Boolean = kind != Kind.NONE && currentUri != null
    fun mediaKind(): Kind = kind
    fun mediaUri(): Uri? = currentUri
    fun mediaMime(): String? = currentMime

    fun clearMedia() {
        releasePlayer()
        currentUri = null
        currentMime = null
        kind = Kind.NONE
        imageLayer.clearImage()
        updateLayers()
        pushEffectState()
    }

    private fun pushEffectState() {
        val showEffects = mediaEnabled && kind != Kind.NONE
        imageLayer.setWater(idWaterEnabled && showEffects, waterStrength, waterIds, waterAnimating)
        imageLayer.setNowGate(nowGateEnabled && showEffects, nowGateSpeed, nowGateWorking, nowGateAnimating)
        imageLayer.setFilm(filmDirective?.takeIf { showEffects }, filmAnimating)

        // Highlights/shadows are optical support for the water lens only.  They are
        // generated directly from IDs and never from MatrixActivityView columns.
        waterOptics.setState(
            enabled = idWaterEnabled && showEffects,
            strength = waterStrength,
            ids = waterIds,
            animate = waterAnimating,
        )
        waterOptics.visibility = if (idWaterEnabled && showEffects) View.VISIBLE else View.GONE

        videoWarp?.setState(
            enabled = showEffects,
            waterEnabled = idWaterEnabled,
            waterStrength = waterStrength,
            ids = waterIds,
            waterAnimate = waterAnimating,
            gateEnabled = nowGateEnabled,
            gateSpeed = nowGateSpeed,
            working = nowGateWorking,
            gateAnimate = nowGateAnimating,
            film = filmDirective?.takeIf { showEffects },
            filmAnimate = filmAnimating,
        )
    }

    private fun updateLayers() {
        val show = mediaEnabled && kind != Kind.NONE
        visibility = if (show) View.VISIBLE else View.GONE
        imageLayer.visibility = if (show && kind == Kind.IMAGE) View.VISIBLE else View.GONE
        videoLayer.visibility = if (show && kind == Kind.VIDEO) View.VISIBLE else View.GONE
        waterOptics.visibility = if (show && idWaterEnabled) View.VISIBLE else View.GONE
        if (!show || kind != Kind.VIDEO) mediaPlayer?.pause()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (kind == Kind.VIDEO && mediaEnabled) startVideoIfReady()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyVideoCrop()
        videoWarp?.onSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        releasePlayer()
        videoWarp?.stop()
        super.onDetachedFromWindow()
    }

    private fun startVideoIfReady() {
        val uri = currentUri ?: return
        if (!videoLayer.isAvailable || !mediaEnabled || kind != Kind.VIDEO) return
        releasePlayer()
        val surfaceTexture = videoLayer.surfaceTexture ?: return
        val surface = Surface(surfaceTexture)
        val player = MediaPlayer()
        mediaPlayer = player
        runCatching {
            player.setDataSource(context, uri)
            player.setSurface(surface)
            surface.release()
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.setOnPreparedListener {
                applyVideoCrop()
                videoWarp?.onSizeChanged(videoLayer.width, videoLayer.height)
                pushEffectState()
                if (mediaEnabled && kind == Kind.VIDEO) it.start()
            }
            player.setOnVideoSizeChangedListener { _, _, _ -> applyVideoCrop() }
            player.prepareAsync()
        }.onFailure {
            runCatching { surface.release() }
            releasePlayer()
        }
    }

    private fun applyVideoCrop() {
        val player = mediaPlayer ?: return
        val vw = player.videoWidth
        val vh = player.videoHeight
        val w = videoLayer.width
        val h = videoLayer.height
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        val scale = max(w.toFloat() / vw.toFloat(), h.toFloat() / vh.toFloat())
        val sx = (vw * scale) / w.toFloat()
        val sy = (vh * scale) / h.toFloat()
        videoLayer.setTransform(Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
    }

    private fun releasePlayer() {
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        mediaPlayer = null
    }
}

/** Shared deterministic geometry.  No visible shape is emitted by this model. */
private object BackdropField {
    data class Lens(
        val id: Long,
        val x: Float,
        val y: Float,
        val rx: Float,
        val ry: Float,
        val intensity: Float,
        val phase: Float,
    )

    data class Nerve(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val strength: Float,
        val phase: Float,
    )

    data class NodePulse(val x: Float, val y: Float, val strength: Float, val phase: Float)

    fun water(ids: List<Long>, time: Float): List<Lens> = ids.take(16).mapIndexed { index, id ->
        val a = mix(id xor (index.toLong() shl 27))
        val b = mix(id xor 0x4f1bbcdcL)
        val x0 = 0.06f + fraction(a) * 0.88f
        val baseY = fraction(a ushr 17) * 1.32f - 0.16f
        val speed = 0.018f + fraction(b) * 0.075f
        val y = wrap(baseY + time * speed, -0.18f, 1.18f)
        val drift = sin(time * (0.24f + fraction(b ushr 11) * 0.42f) + fraction(a ushr 31) * 6.28f) * (0.006f + fraction(b ushr 23) * 0.026f)
        val radius = 0.018f + fraction(a ushr 29) * 0.040f
        val stretch = 1.05f + fraction(b ushr 37) * 1.45f
        Lens(
            id = id,
            x = (x0 + drift).coerceIn(-0.08f, 1.08f),
            y = y,
            rx = radius,
            ry = radius * stretch,
            intensity = 0.45f + fraction(a ushr 43) * 0.55f,
            phase = fraction(b ushr 49) * (PI.toFloat() * 2f),
        )
    }

    fun nerves(snapshot: WorkingSetSnapshot): Pair<List<Nerve>, List<NodePulse>> {
        val activeNodes = snapshot.nodes
            .sortedByDescending { max(it.activation, it.expectation) + it.structuralRelevance * 0.35 }
            .take(18)
        if (activeNodes.isEmpty()) return emptyList<Nerve>() to emptyList()

        val positions = activeNodes.associate { node ->
            val mixed = mix(node.id.toLong() xor (node.abstractionLevel.toLong() shl 36))
            val x = 0.07f + fraction(mixed) * 0.86f
            val y = 0.07f + fraction(mixed ushr 24) * 0.86f
            node.id to Pair(x, y)
        }
        val nodePulses = activeNodes.mapNotNull { node ->
            val p = positions[node.id] ?: return@mapNotNull null
            val mixed = mix(node.id.toLong() xor 0x19e3779bL)
            NodePulse(
                x = p.first,
                y = p.second,
                strength = max(node.activation, node.expectation).toFloat().coerceIn(0.10f, 1.35f),
                phase = fraction(mixed ushr 31) * (PI.toFloat() * 2f),
            )
        }

        val exchangeNerves = snapshot.exchanges
            .asSequence()
            .filter { positions.containsKey(it.fromId) && positions.containsKey(it.toId) }
            .sortedByDescending { abs(it.value) }
            .take(10)
            .map { edge ->
                val a = positions.getValue(edge.fromId)
                val b = positions.getValue(edge.toId)
                val mixed = mix((edge.fromId.toLong() shl 32) xor (edge.toId.toLong() and 0xffffffffL))
                Nerve(a.first, a.second, b.first, b.second, abs(edge.value).toFloat().coerceIn(0.10f, 1.4f), fraction(mixed) * 6.28f)
            }.toList()

        if (exchangeNerves.isNotEmpty()) return exchangeNerves to nodePulses

        // A sparse working set may temporarily have nodes but no exchange edge.  Pair
        // nearby active nodes deterministically so the field can still breathe without
        // inventing a privileged centre or persistent topology.
        val fallback = activeNodes.zipWithNext().take(8).mapNotNull { (left, right) ->
            val a = positions[left.id] ?: return@mapNotNull null
            val b = positions[right.id] ?: return@mapNotNull null
            val mixed = mix(left.id.toLong() xor (right.id.toLong() shl 21))
            Nerve(a.first, a.second, b.first, b.second, 0.18f, fraction(mixed) * 6.28f)
        }
        return fallback to nodePulses
    }

    fun mix(value: Long): Long {
        var x = value + 0x9E3779B97F4A7C15UL.toLong()
        x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        x = (x xor (x ushr 27)) * 0x94D049BB133111EBUL.toLong()
        return x xor (x ushr 31)
    }

    fun fraction(value: Long): Float = ((value and 0xffffL).toFloat() / 65535f).coerceIn(0f, 1f)

    private fun wrap(value: Float, min: Float, max: Float): Float {
        val span = max - min
        var v = value
        while (v > max) v -= span
        while (v < min) v += span
        return v
    }
}

/**
 * Actual bitmap deformation.  The Now Gate never draws a tree, line or node; the
 * underlying bitmap mesh itself is displaced by a distributed relation field.
 */
private class DeformedImageLayer(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var waterEnabled = false
    private var waterStrength = 0.72f
    private var waterIds: List<Long> = emptyList()
    private var waterAnimating = false
    private var gateEnabled = false
    private var gateSpeed = 0.42f
    private var gateWorking = WorkingSetSnapshot(emptyList(), emptyList())
    private var gateAnimating = false
    private var film: FilmGenerationDirective? = null
    private var filmAnimating = false
    private var startedMs = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val meshW = 22
    private val meshH = 36
    private val verts = FloatArray((meshW + 1) * (meshH + 1) * 2)

    fun loadImage(uri: Uri) {
        bitmap = null
        invalidate()
        val resolver = context.contentResolver
        val targetW = resources.displayMetrics.widthPixels.coerceAtLeast(720)
        val targetH = resources.displayMetrics.heightPixels.coerceAtLeast(1280)
        thread(name = "Kira-Backdrop-Image") {
            val decoded = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > targetW * 2 || bounds.outHeight / sample > targetH * 2) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }.getOrNull()
            post {
                bitmap = decoded
                startedMs = SystemClock.uptimeMillis()
                invalidate()
            }
        }
    }

    fun clearImage() {
        bitmap = null
        invalidate()
    }

    fun setWater(enabled: Boolean, strength: Float, ids: List<Long>, animate: Boolean) {
        waterEnabled = enabled
        waterStrength = strength.coerceIn(0f, 1.6f)
        waterIds = ids
        waterAnimating = animate
        ensureAnimation()
    }

    fun setNowGate(enabled: Boolean, speed: Float, snapshot: WorkingSetSnapshot, animate: Boolean) {
        gateEnabled = enabled
        gateSpeed = speed.coerceIn(0.05f, 2.5f)
        gateWorking = snapshot
        gateAnimating = animate
        ensureAnimation()
    }

    fun setFilm(directive: FilmGenerationDirective?, animate: Boolean) {
        film = directive
        filmAnimating = animate
        ensureAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (width <= 0 || height <= 0) return
        if (startedMs == 0L) startedMs = SystemClock.uptimeMillis()
        val seconds = (SystemClock.uptimeMillis() - startedMs) / 1000f

        if (!waterEnabled && !gateEnabled && film == null) {
            canvas.drawBitmap(bmp, centerCropMatrix(bmp), paint)
            return
        }

        val base = centerCropGeometry(bmp)
        val waterTime = if (waterAnimating) seconds else 0f
        val lenses = if (waterEnabled) BackdropField.water(waterIds, waterTime) else emptyList()
        val (nerves, nodes) = if (gateEnabled) BackdropField.nerves(gateWorking) else emptyList<BackdropField.Nerve>() to emptyList()
        val gateTime = seconds * gateSpeed

        var out = 0
        for (iy in 0..meshH) {
            val fy = iy.toFloat() / meshH.toFloat()
            val y = base.top + fy * base.height
            val ny = y / height.toFloat()
            for (ix in 0..meshW) {
                val fx = ix.toFloat() / meshW.toFloat()
                val x = base.left + fx * base.width
                val nx = x / width.toFloat()
                var dx = 0f
                var dy = 0f

                if (waterEnabled && lenses.isNotEmpty()) {
                    lenses.forEach { lens ->
                        val ex = (nx - lens.x) / lens.rx.coerceAtLeast(0.006f)
                        val ey = (ny - lens.y) / lens.ry.coerceAtLeast(0.008f)
                        val q = sqrt(ex * ex + ey * ey)
                        if (q < 1.35f) {
                            val falloff = (1f - q / 1.35f).coerceAtLeast(0f)
                            val lensing = sin(falloff * PI.toFloat()) * lens.intensity * waterStrength
                            // Pull the mesh toward the optical centre, then add a tiny
                            // downward shear: this reads as refraction rather than a disk.
                            dx += (lens.x - nx) * width * 0.045f * lensing
                            dy += (lens.y - ny) * height * 0.034f * lensing + falloff * height * 0.0025f * waterStrength
                        }
                    }
                }

                if (gateEnabled) {
                    nerves.forEachIndexed { index, nerve ->
                        val warp = relationWarp(nx, ny, nerve, gateTime, index)
                        dx += warp.first * width
                        dy += warp.second * height
                    }
                    nodes.forEachIndexed { index, node ->
                        val vx = nx - node.x
                        val vy = ny - node.y
                        val d2 = vx * vx + vy * vy
                        val radius = 0.035f + 0.018f * node.strength.coerceIn(0f, 1f)
                        if (d2 < radius * radius * 5.0f) {
                            val d = sqrt(d2).coerceAtLeast(0.0005f)
                            val pulse = (0.5f + 0.5f * sin(gateTime * 1.8f + node.phase + index * 0.19f)).pow(3)
                            val falloff = exp(-d2 / (radius * radius * 1.7f)) * node.strength * pulse
                            dx += (vx / d) * falloff * width * 0.0028f
                            dy += (vy / d) * falloff * height * 0.0028f
                        }
                    }
                }

                film?.let { directive ->
                    val time = if (filmAnimating) seconds else 0f
                    val seedPhase = ((directive.seed ushr 17) and 0xffffL).toFloat() / 65535f * PI.toFloat() * 2f
                    val motion = directive.motion.coerceIn(0.05f, 1f)
                    val strength = directive.intensity.coerceIn(0f, 1f)
                    val pulse = 0.55f + 0.45f * sin(time * (0.35f + motion * 1.7f) + seedPhase) * directive.pulse.coerceIn(0f, 1f)
                    val waveA = sin((ny * (5.5f + directive.stateInfluence * 5f) + time * motion + seedPhase) * PI.toFloat())
                    val waveB = cos((nx * (4.0f + directive.stateInfluence * 4f) - time * motion * 0.72f + seedPhase * 0.7f) * PI.toFloat())
                    dx += waveA * width * 0.0042f * strength * pulse
                    dy += waveB * height * 0.0034f * strength * pulse
                }

                verts[out++] = x + dx
                verts[out++] = y + dy
            }
        }
        canvas.drawBitmapMesh(bmp, meshW, meshH, verts, 0, null, 0, paint)
        ensureAnimation()
    }

    private fun relationWarp(nx: Float, ny: Float, nerve: BackdropField.Nerve, time: Float, index: Int): Pair<Float, Float> {
        val ax = nerve.x1
        val ay = nerve.y1
        val bx = nerve.x2
        val by = nerve.y2
        val vx = bx - ax
        val vy = by - ay
        val len2 = (vx * vx + vy * vy).coerceAtLeast(0.00001f)
        val t = (((nx - ax) * vx + (ny - ay) * vy) / len2).coerceIn(0f, 1f)
        val px = ax + vx * t
        val py = ay + vy * t
        val dx = nx - px
        val dy = ny - py
        val dist = sqrt(dx * dx + dy * dy)
        val width = 0.010f + 0.010f * nerve.strength.coerceIn(0f, 1f)
        if (dist > width * 4.2f) return 0f to 0f

        val lineLength = sqrt(len2)
        val normalX = if (lineLength > 0f) -vy / lineLength else 0f
        val normalY = if (lineLength > 0f) vx / lineLength else 0f
        val band = exp(-(dist * dist) / (width * width * 1.55f))
        // Narrow, intermittent flashes: the connection itself is never drawn.  The
        // background simply kinks along the temporary relation path.
        val flash = max(0f, sin(time * 3.2f + nerve.phase + index * 0.73f)).pow(7)
        val lightning = sin(t * (17f + index * 1.7f) + nerve.phase * 2.1f + time * 7.5f)
        val amplitude = band * (0.18f + flash * 0.82f) * nerve.strength.coerceIn(0.08f, 1.4f)
        return Pair(
            normalX * amplitude * (0.0015f + 0.0065f * flash) * lightning,
            normalY * amplitude * (0.0015f + 0.0065f * flash) * lightning,
        )
    }

    private data class Crop(val left: Float, val top: Float, val width: Float, val height: Float)

    private fun centerCropGeometry(bitmap: Bitmap): Crop {
        val bw = bitmap.width.toFloat().coerceAtLeast(1f)
        val bh = bitmap.height.toFloat().coerceAtLeast(1f)
        val scale = max(width / bw, height / bh)
        val w = bw * scale
        val h = bh * scale
        return Crop((width - w) * 0.5f, (height - h) * 0.5f, w, h)
    }

    private fun centerCropMatrix(bitmap: Bitmap): Matrix {
        val crop = centerCropGeometry(bitmap)
        val scale = crop.width / bitmap.width.toFloat().coerceAtLeast(1f)
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(crop.left, crop.top)
        }
    }

    private fun ensureAnimation() {
        val animate = (waterEnabled && waterAnimating && waterIds.isNotEmpty()) ||
            (gateEnabled && (gateAnimating || gateWorking.nodes.isNotEmpty())) ||
            (film != null && filmAnimating)
        if (animate && visibility == VISIBLE) postInvalidateOnAnimation() else invalidate()
    }
}

/**
 * Highlights and shadows for ID water.  Paths are deliberately irregular and
 * continuously reshaped; no rigid circles are used.
 */
private class IdWaterOpticsOverlay(context: Context) : View(context) {
    private var enabled = false
    private var strength = 0.72f
    private var ids: List<Long> = emptyList()
    private var animate = false
    private var startedMs = 0L
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    fun setState(enabled: Boolean, strength: Float, ids: List<Long>, animate: Boolean) {
        this.enabled = enabled
        this.strength = strength.coerceIn(0f, 1.6f)
        this.ids = ids
        this.animate = animate
        if (startedMs == 0L) startedMs = SystemClock.uptimeMillis()
        if (enabled && animate && ids.isNotEmpty()) postInvalidateOnAnimation() else invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabled || ids.isEmpty() || width <= 0 || height <= 0) return
        val seconds = if (animate) (SystemClock.uptimeMillis() - startedMs) / 1000f else 0f
        BackdropField.water(ids, seconds).forEachIndexed { index, lens ->
            val cx = lens.x * width
            val cy = lens.y * height
            val rx = lens.rx * min(width, height) * (0.82f + strength * 0.30f)
            val ry = lens.ry * min(width, height) * (0.82f + strength * 0.30f)
            if (cy < -ry * 2f || cy > height + ry * 2f) return@forEachIndexed
            val path = organicDropPath(cx, cy, rx, ry, lens.phase + seconds * 0.7f, lens.id)

            canvas.save()
            canvas.clipPath(path)
            fill.shader = RadialGradient(
                cx - rx * 0.28f,
                cy - ry * 0.30f,
                max(rx, ry) * 1.25f,
                intArrayOf(
                    Color.argb((42 * strength).toInt().coerceIn(0, 70), 250, 255, 252),
                    Color.argb((10 * strength).toInt().coerceIn(0, 26), 205, 255, 226),
                    Color.argb((24 * strength).toInt().coerceIn(0, 46), 2, 8, 5),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.32f, 0.74f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(cx - rx * 1.5f, cy - ry * 1.5f, cx + rx * 1.5f, cy + ry * 1.5f, fill)
            fill.shader = null
            canvas.restore()

            stroke.strokeWidth = max(0.8f, min(rx, ry) * 0.045f)
            stroke.color = Color.argb((58 * strength).toInt().coerceIn(0, 95), 235, 255, 240)
            val highlight = Path().apply {
                moveTo(cx - rx * 0.42f, cy - ry * 0.15f)
                cubicTo(cx - rx * 0.28f, cy - ry * 0.58f, cx + rx * 0.10f, cy - ry * 0.64f, cx + rx * 0.26f, cy - ry * 0.38f)
            }
            canvas.drawPath(highlight, stroke)

            stroke.strokeWidth = max(1f, min(rx, ry) * 0.065f)
            stroke.color = Color.argb((48 * strength).toInt().coerceIn(0, 80), 0, 0, 0)
            val shadow = Path().apply {
                moveTo(cx - rx * 0.18f, cy + ry * 0.58f)
                cubicTo(cx + rx * 0.18f, cy + ry * 0.76f, cx + rx * 0.58f, cy + ry * 0.34f, cx + rx * 0.46f, cy + ry * 0.10f)
            }
            canvas.drawPath(shadow, stroke)

            // A thin wake makes moving drops feel like fluid mass, not falling icons.
            if (lens.ry > lens.rx * 1.2f) {
                stroke.strokeWidth = max(0.6f, rx * 0.026f)
                stroke.color = Color.argb((24 * strength).toInt().coerceIn(0, 48), 225, 255, 235)
                canvas.drawLine(cx, cy - ry * 0.78f, cx + sin(lens.phase + index) * rx * 0.15f, cy - ry * 1.22f, stroke)
            }
        }
        if (enabled && animate && ids.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun organicDropPath(cx: Float, cy: Float, rx: Float, ry: Float, phase: Float, id: Long): Path {
        val path = Path()
        val points = 18
        for (i in 0 until points) {
            val a = (i.toFloat() / points.toFloat()) * PI.toFloat() * 2f
            val mixed = BackdropField.mix(id xor (i.toLong() shl 19))
            val jitter = (BackdropField.fraction(mixed) - 0.5f) * 0.16f
            val breathing = sin(phase * 0.9f + i * 0.73f) * 0.035f
            val sx = 1f + jitter + breathing
            val sy = 1f + jitter * 0.55f - breathing * 0.35f
            val x = cx + cos(a) * rx * sx
            val y = cy + sin(a) * ry * sy
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }
}

/**
 * API 33+ local pixel warp for video.  Older Android versions keep the video itself
 * untouched rather than faking Now Gate with drawn lines; ID-water highlights still
 * remain available as an overlay.
 */
private class VideoRuntimeWarp(private val view: TextureView) {
    private var controller: Any? = null

    fun setState(
        enabled: Boolean,
        waterEnabled: Boolean,
        waterStrength: Float,
        ids: List<Long>,
        waterAnimate: Boolean,
        gateEnabled: Boolean,
        gateSpeed: Float,
        working: WorkingSetSnapshot,
        gateAnimate: Boolean,
        film: FilmGenerationDirective?,
        filmAnimate: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < 33) return
        val c = (controller as? Api33VideoWarp) ?: Api33VideoWarp(view).also { controller = it }
        c.setState(enabled, waterEnabled, waterStrength, ids, waterAnimate, gateEnabled, gateSpeed, working, gateAnimate, film, filmAnimate)
    }

    fun onSizeChanged(width: Int, height: Int) {
        if (Build.VERSION.SDK_INT >= 33) (controller as? Api33VideoWarp)?.onSizeChanged(width, height)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 33) (controller as? Api33VideoWarp)?.stop()
    }
}

@android.annotation.TargetApi(33)
private class Api33VideoWarp(private val view: TextureView) {
    private val shader = android.graphics.RuntimeShader(
        """
        uniform shader content;
        uniform float2 resolution;
        uniform float time;
        uniform float gateStrength;
        uniform float gateSpeed;
        uniform float waterStrength;
        uniform float filmStrength;
        uniform float filmMotion;
        uniform float filmPulse;
        uniform float filmSeed;
        uniform float4 relA;
        uniform float4 relB;
        uniform float4 relC;
        uniform float4 relD;
        uniform float4 dropA;
        uniform float4 dropB;
        uniform float4 dropC;
        uniform float4 dropD;

        float2 nerve(float2 uv, float4 r, float seed) {
            float2 a=r.xy; float2 b=r.zw; float2 v=b-a;
            float d=max(dot(v,v),0.00001); float t=clamp(dot(uv-a,v)/d,0.0,1.0);
            float2 p=a+v*t; float2 q=uv-p; float dist=length(q);
            float2 n=float2(-v.y,v.x)/max(length(v),0.0001);
            float band=exp(-(dist*dist)/0.00042);
            float flash=pow(max(0.0,sin(time*gateSpeed*3.0+seed)),7.0);
            float lightning=sin(t*21.0+time*gateSpeed*7.0+seed*2.0);
            return n*band*(0.0012+0.0062*flash)*lightning*gateStrength;
        }
        float2 water(float2 uv, float4 d) {
            float2 q=(uv-d.xy)/float2(max(d.z,0.006),max(d.z*1.65,0.008));
            float l=length(q); float f=max(0.0,1.0-l/1.35);
            return (d.xy-uv)*sin(f*3.14159)*d.w*0.045*waterStrength;
        }
        float2 filmWarp(float2 uv) {
            float pulse=0.55+0.45*sin(time*(0.35+filmMotion*1.7)+filmSeed)*filmPulse;
            float ax=sin((uv.y*(5.5+filmStrength*5.0)+time*filmMotion+filmSeed)*3.14159);
            float ay=cos((uv.x*(4.0+filmStrength*4.0)-time*filmMotion*0.72+filmSeed*0.7)*3.14159);
            return float2(ax*0.0042,ay*0.0034)*filmStrength*pulse;
        }
        half4 main(float2 frag) {
            float2 uv=frag/resolution;
            float2 delta=float2(0.0);
            delta += nerve(uv,relA,0.3)+nerve(uv,relB,1.7)+nerve(uv,relC,3.1)+nerve(uv,relD,4.6);
            delta += water(uv,dropA)+water(uv,dropB)+water(uv,dropC)+water(uv,dropD);
            delta += filmWarp(uv);
            return content.eval((uv+delta)*resolution);
        }
        """.trimIndent()
    )
    private val effect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
    private var enabled = false
    private var waterEnabled = false
    private var waterStrength = 0f
    private var ids: List<Long> = emptyList()
    private var waterAnimate = false
    private var gateEnabled = false
    private var gateSpeed = 0.42f
    private var working = WorkingSetSnapshot(emptyList(), emptyList())
    private var gateAnimate = false
    private var film: FilmGenerationDirective? = null
    private var filmAnimate = false
    private var width = 1
    private var height = 1
    private var startMs = SystemClock.uptimeMillis()
    private var posted = false

    private val frame = object : Runnable {
        override fun run() {
            posted = false
            if (!enabled) return
            updateShader()
            if ((waterEnabled && waterAnimate && ids.isNotEmpty()) ||
                (gateEnabled && (gateAnimate || working.nodes.isNotEmpty())) ||
                (film != null && filmAnimate)) postFrame()
        }
    }

    fun setState(
        enabled: Boolean,
        waterEnabled: Boolean,
        waterStrength: Float,
        ids: List<Long>,
        waterAnimate: Boolean,
        gateEnabled: Boolean,
        gateSpeed: Float,
        working: WorkingSetSnapshot,
        gateAnimate: Boolean,
        film: FilmGenerationDirective?,
        filmAnimate: Boolean,
    ) {
        this.enabled = enabled
        this.waterEnabled = waterEnabled
        this.waterStrength = waterStrength
        this.ids = ids
        this.waterAnimate = waterAnimate
        this.gateEnabled = gateEnabled
        this.gateSpeed = gateSpeed
        this.working = working
        this.gateAnimate = gateAnimate
        this.film = film
        this.filmAnimate = filmAnimate
        view.setRenderEffect(if (enabled && (waterEnabled || gateEnabled || film != null)) effect else null)
        updateShader()
        postFrame()
    }

    fun onSizeChanged(width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        updateShader()
    }

    fun stop() {
        enabled = false
        view.removeCallbacks(frame)
        posted = false
        view.setRenderEffect(null)
    }

    private fun postFrame() {
        if (!posted) {
            posted = true
            view.postOnAnimation(frame)
        }
    }

    private fun updateShader() {
        val seconds = (SystemClock.uptimeMillis() - startMs) / 1000f
        shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        shader.setFloatUniform("time", seconds)
        shader.setFloatUniform("gateStrength", if (gateEnabled) 1f else 0f)
        shader.setFloatUniform("gateSpeed", gateSpeed)
        shader.setFloatUniform("waterStrength", if (waterEnabled) waterStrength else 0f)
        val filmNow = film
        shader.setFloatUniform("filmStrength", filmNow?.intensity ?: 0f)
        shader.setFloatUniform("filmMotion", filmNow?.motion ?: 0f)
        shader.setFloatUniform("filmPulse", filmNow?.pulse ?: 0f)
        val filmSeed = filmNow?.seed ?: 0L
        shader.setFloatUniform("filmSeed", ((filmSeed ushr 17) and 0xffffL).toFloat() / 65535f * 6.28318f)

        val nerves = BackdropField.nerves(working).first.take(4)
        val relNames = arrayOf("relA", "relB", "relC", "relD")
        relNames.forEachIndexed { index, name ->
            val r = nerves.getOrNull(index)
            shader.setFloatUniform(name, r?.x1 ?: -2f, r?.y1 ?: -2f, r?.x2 ?: -2f, r?.y2 ?: -2f)
        }

        val drops = BackdropField.water(ids, if (waterAnimate) seconds else 0f).take(4)
        val dropNames = arrayOf("dropA", "dropB", "dropC", "dropD")
        dropNames.forEachIndexed { index, name ->
            val d = drops.getOrNull(index)
            shader.setFloatUniform(name, d?.x ?: -2f, d?.y ?: -2f, d?.rx ?: 0.001f, d?.intensity ?: 0f)
        }
        view.invalidate()
    }
}
