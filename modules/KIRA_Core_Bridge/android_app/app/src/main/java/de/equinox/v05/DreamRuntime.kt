package de.equinox.v05

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kira v0.9.1 DREAM runtime.
 *
 * DREAM is deliberately not a frame generator. It owns a persistent world-state that
 * evolves by ticks. Renderers and the orchestra only *observe* this state.  This keeps
 * future learned / oven-compiled data independent from the Android UI and from a
 * particular video backend.
 */
enum class DreamControlMode(val label: String, val externalWeight: Double, val memoryWeight: Double) {
    AUTONOMOUS("AUTONOM", 0.0, 0.72),
    REACTIVE("REAKTIV", 0.38, 0.62),
    INSTRUMENT("INSTRUMENT", 0.82, 0.34),
    FUSION("FUSION", 0.58, 0.82),
}

data class DreamInfluence(
    val text: Double = 0.0,
    val audio: Double = 0.0,
    val camera: Double = 0.0,
    val energy: Double = 0.0,
    val tension: Double = 0.0,
    val novelty: Double = 0.0,
    val coherence: Double = 0.0,
    val motion: Double = 0.0,
    val hueBias: Double = 0.0,
    val activeIds: List<Long> = emptyList(),
) {
    fun clamped(): DreamInfluence = copy(
        text = text.coerceIn(0.0, 1.0),
        audio = audio.coerceIn(0.0, 1.0),
        camera = camera.coerceIn(0.0, 1.0),
        energy = energy.coerceIn(0.0, 1.0),
        tension = tension.coerceIn(0.0, 1.0),
        novelty = novelty.coerceIn(0.0, 1.0),
        coherence = coherence.coerceIn(0.0, 1.0),
        motion = motion.coerceIn(0.0, 1.0),
        hueBias = wrap01(hueBias),
        activeIds = activeIds.filter { it != 0L }.distinct().take(64),
    )

    companion object {
        fun fromContext(context: KiraModuleContext): DreamInfluence {
            val s = context.state
            val hue = if (context.activeIds.isEmpty()) 0.0 else {
                val mixed = context.activeIds.fold(0x4B4952415F44524DL) { acc, id -> mix64(acc xor id) }
                ((mixed ushr 11) and 0xffffL).toDouble() / 65535.0
            }
            return DreamInfluence(
                text = if (context.text.isBlank()) 0.0 else min(1.0, 0.18 + context.text.length / 180.0),
                energy = (0.34 * s.activity + 0.26 * s.relationEnergy + 0.18 * s.memoryEnergy + 0.22 * s.directActivation).coerceIn(0.0, 1.0),
                tension = (0.48 * abs(s.signedExchange) + 0.30 * s.novelty + 0.22 * s.relationEnergy).coerceIn(0.0, 1.0),
                novelty = s.novelty.coerceIn(0.0, 1.0),
                coherence = (0.36 * s.relevance + 0.28 * s.prediction + 0.22 * (1.0 - s.novelty) + 0.14 * s.tierDepth).coerceIn(0.0, 1.0),
                motion = (0.42 * s.activity + 0.30 * s.crossmodal + 0.18 * s.novelty + 0.10 * abs(s.signedExchange)).coerceIn(0.0, 1.0),
                hueBias = hue,
                activeIds = context.activeIds,
            )
        }

        private fun wrap01(value: Double): Double {
            var v = value % 1.0
            if (v < 0.0) v += 1.0
            return v
        }

        private fun mix64(value: Long): Long {
            var x = value + 0x9E3779B97F4A7C15UL.toLong()
            x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
            x = (x xor (x ushr 27)) * 0x94D049BB133111EBUL.toLong()
            return x xor (x ushr 31)
        }
    }
}

data class DreamNode(
    val id: Long,
    val x: Double,
    val y: Double,
    val z: Double,
    val vx: Double,
    val vy: Double,
    val vz: Double,
    val energy: Double,
    val phase: Double,
    val scale: Double,
    val age: Long,
    val lineage: Int,
)

data class DreamLink(
    val fromId: Long,
    val toId: Long,
    val strength: Double,
    val phase: Double,
)

data class DreamCamera(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val zoom: Double = 1.0,
)

data class DreamEnvironment(
    val hue: Double = 0.48,
    val saturation: Double = 0.54,
    val luminance: Double = 0.42,
    val fog: Double = 0.22,
    val curvature: Double = 0.35,
    val pulse: Double = 0.20,
)

data class DreamWorldState(
    val seed: Long,
    val tick: Long,
    val nodes: List<DreamNode>,
    val links: List<DreamLink>,
    val energy: Double,
    val novelty: Double,
    val stability: Double,
    val tension: Double,
    val camera: DreamCamera,
    val environment: DreamEnvironment,
    val controlMode: DreamControlMode,
) {
    fun compactSummary(): String =
        "${controlMode.label} · tick $tick · ${nodes.size} nodes · ${links.size} links · " +
            "E ${fmt(energy)} N ${fmt(novelty)} S ${fmt(stability)} T ${fmt(tension)}"

    private fun fmt(v: Double): String = ((v.coerceIn(0.0, 1.0) * 100.0).toInt()).toString().padStart(2, '0')
}

/**
 * Reserved future input for oven/Hugging-Face compiled knowledge.
 * The runtime only sees normalized native traits, never a specific external model.
 */
data class DreamKnowledgeAtom(
    val id: Long,
    val shapeBias: Double,
    val motionBias: Double,
    val colorBias: Double,
    val relationBias: Double,
    val stabilityBias: Double,
)

class DreamRuntime(
    seed: Long = 0x4B4952415F44524DL,
    restored: DreamWorldState? = null,
) {
    private var knowledge: List<DreamKnowledgeAtom> = emptyList()
    private var state: DreamWorldState = restored ?: bootstrap(seed)

    fun snapshot(): DreamWorldState = state

    fun setKnowledge(atoms: List<DreamKnowledgeAtom>) {
        knowledge = atoms
            .filter { it.id != 0L }
            .distinctBy { it.id }
            .take(512)
    }

    fun knowledgeSize(): Int = knowledge.size

    fun reset(seed: Long = state.seed) {
        state = bootstrap(seed)
    }

    /**
     * One world step. `external` is normally derived from the MASTER-routed input state.
     * The world always evolves, including with a totally empty memory/input state.
     */
    fun step(
        context: KiraModuleContext = KiraModuleContext(""),
        external: DreamInfluence = DreamInfluence.fromContext(context),
        mode: DreamControlMode = DreamControlMode.REACTIVE,
        influenceStrength: Double = 0.58,
        speed: Double = 1.0,
    ): DreamWorldState {
        val old = state
        val ext = external.clamped()
        val ctx = DreamInfluence.fromContext(context).clamped()
        val strength = influenceStrength.coerceIn(0.0, 1.0)
        val dt = (0.055 * speed.coerceIn(0.25, 3.0)).coerceIn(0.014, 0.18)

        val externalWeight = mode.externalWeight * strength
        val memoryWeight = mode.memoryWeight * (0.35 + 0.65 * strength)
        val knowledgeWeight = if (knowledge.isEmpty()) 0.0 else (0.18 + 0.42 * strength)

        val worldNoise = unit(hash64(old.seed xor (old.tick + 1L) * -7046029254386353131L))
        val bootstrapEnergy = 0.26 + 0.08 * sin((old.tick + 1L) * 0.017)
        val incomingEnergy = blend3(ctx.energy, ext.energy, bootstrapEnergy, memoryWeight, externalWeight)
        val incomingNovelty = blend3(ctx.novelty, ext.novelty, 0.22 + worldNoise * 0.18, memoryWeight, externalWeight)
        val incomingTension = blend3(ctx.tension, ext.tension, 0.16 + (1.0 - worldNoise) * 0.12, memoryWeight, externalWeight)
        val incomingCoherence = blend3(ctx.coherence, ext.coherence, 0.54, memoryWeight, externalWeight)
        val incomingMotion = blend3(ctx.motion, ext.motion, 0.30 + worldNoise * 0.20, memoryWeight, externalWeight)

        val knowledgeMotion = if (knowledge.isEmpty()) 0.0 else knowledge.map { it.motionBias }.average().coerceIn(0.0, 1.0)
        val knowledgeRelation = if (knowledge.isEmpty()) 0.0 else knowledge.map { it.relationBias }.average().coerceIn(0.0, 1.0)
        val knowledgeColor = if (knowledge.isEmpty()) 0.0 else circularMean01(knowledge.map { wrap01(it.colorBias) })
        val knowledgeStability = if (knowledge.isEmpty()) 0.0 else knowledge.map { it.stabilityBias }.average().coerceIn(0.0, 1.0)

        val energy = lerp(old.energy, (incomingEnergy + knowledgeMotion * knowledgeWeight * 0.28).coerceIn(0.0, 1.0), 0.08)
        val novelty = lerp(old.novelty, incomingNovelty, 0.10)
        val tension = lerp(old.tension, incomingTension, 0.09)
        val stabilityTarget = (0.18 + incomingCoherence * 0.54 + knowledgeStability * knowledgeWeight * 0.28 - novelty * 0.18).coerceIn(0.0, 1.0)
        val stability = lerp(old.stability, stabilityTarget, 0.07)

        val stimulusIds = (ext.activeIds + ctx.activeIds).filter { it != 0L }.distinct().take(48)
        var nodes = synchronizeStimulusNodes(old.nodes, stimulusIds, old.seed, old.tick)
        nodes = evolveNodes(
            nodes = nodes,
            tick = old.tick + 1,
            dt = dt,
            energy = energy,
            novelty = novelty,
            tension = tension,
            coherence = incomingCoherence,
            motion = (incomingMotion + knowledgeMotion * knowledgeWeight * 0.34).coerceIn(0.0, 1.0),
            modalityBias = ext,
            seed = old.seed,
        )

        val desiredCount = (28 + energy * 20 + novelty * 16 + ext.camera * 10 + ext.audio * 6 + ext.text * 4)
            .toInt().coerceIn(24, 78)
        nodes = resizeWorld(nodes, desiredCount, old.seed, old.tick + 1, novelty)

        val links = buildLinks(nodes, coherence = incomingCoherence, relationBias = knowledgeRelation * knowledgeWeight, tick = old.tick + 1)
        val camera = evolveCamera(old.camera, nodes, old.tick + 1, incomingMotion, ext, dt)

        val contextHue = if (ext.activeIds.isEmpty() && ctx.activeIds.isEmpty()) old.environment.hue else circularLerp(old.environment.hue, wrap01((ext.hueBias + ctx.hueBias) * 0.5), 0.045)
        val hue = if (knowledge.isEmpty()) contextHue else circularLerp(contextHue, knowledgeColor, 0.025 * knowledgeWeight)
        val environment = DreamEnvironment(
            hue = hue,
            saturation = lerp(old.environment.saturation, (0.34 + tension * 0.36 + novelty * 0.20).coerceIn(0.18, 0.92), 0.05),
            luminance = lerp(old.environment.luminance, (0.25 + energy * 0.45 + stability * 0.12).coerceIn(0.18, 0.86), 0.04),
            fog = lerp(old.environment.fog, (0.12 + (1.0 - stability) * 0.40 + tension * 0.18).coerceIn(0.05, 0.72), 0.045),
            curvature = lerp(old.environment.curvature, (0.18 + novelty * 0.44 + ext.camera * 0.24).coerceIn(0.08, 0.86), 0.05),
            pulse = lerp(old.environment.pulse, (0.12 + energy * 0.32 + ext.audio * 0.34 + tension * 0.18).coerceIn(0.05, 0.92), 0.08),
        )

        state = DreamWorldState(
            seed = old.seed,
            tick = old.tick + 1,
            nodes = nodes,
            links = links,
            energy = energy,
            novelty = novelty,
            stability = stability,
            tension = tension,
            camera = camera,
            environment = environment,
            controlMode = mode,
        )
        return state
    }

    private fun bootstrap(seed: Long): DreamWorldState {
        val nodes = (0 until 30).map { index -> createNode(seed, index.toLong(), tick = 0L, lineage = 0) }
        val links = buildLinks(nodes, coherence = 0.56, relationBias = 0.0, tick = 0L)
        return DreamWorldState(
            seed = seed,
            tick = 0L,
            nodes = nodes,
            links = links,
            energy = 0.30,
            novelty = 0.24,
            stability = 0.58,
            tension = 0.18,
            camera = DreamCamera(),
            environment = DreamEnvironment(),
            controlMode = DreamControlMode.REACTIVE,
        )
    }

    private fun synchronizeStimulusNodes(
        current: List<DreamNode>,
        activeIds: List<Long>,
        seed: Long,
        tick: Long,
    ): List<DreamNode> {
        if (activeIds.isEmpty()) return current
        val byId = current.associateBy { it.id }.toMutableMap()
        activeIds.take(24).forEachIndexed { index, id ->
            val existing = byId[id]
            if (existing != null) {
                byId[id] = existing.copy(energy = (existing.energy + 0.18).coerceAtMost(1.0), age = 0L)
            } else {
                val node = createNode(seed xor id, index.toLong() + 10_000L, tick, lineage = 1).copy(id = id, energy = 0.74)
                byId[id] = node
            }
        }
        return byId.values.toList()
    }

    private fun evolveNodes(
        nodes: List<DreamNode>,
        tick: Long,
        dt: Double,
        energy: Double,
        novelty: Double,
        tension: Double,
        coherence: Double,
        motion: Double,
        modalityBias: DreamInfluence,
        seed: Long,
    ): List<DreamNode> {
        if (nodes.isEmpty()) return nodes
        val cx = nodes.map { it.x }.average()
        val cy = nodes.map { it.y }.average()
        return nodes.mapIndexed { index, node ->
            val dx = node.x - cx
            val dy = node.y - cy
            val radius = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001)
            val tangentX = -dy / radius
            val tangentY = dx / radius
            val radialX = dx / radius
            val radialY = dy / radius
            val n = signedUnit(hash64(seed xor node.id xor tick * 0x9E3779B97F4A7C15UL.toLong()))
            val phaseDrive = sin(node.phase + tick * (0.017 + motion * 0.021) + index * 0.13)

            // No fixed centre: centre of mass itself drifts, while every node contributes.
            val curl = (0.10 + motion * 0.46 + modalityBias.audio * 0.18) * (0.65 + node.energy * 0.35)
            val expand = (novelty - coherence * 0.54) * 0.24 + modalityBias.camera * 0.12
            val jitter = 0.018 + tension * 0.032 + novelty * 0.024
            var vx = node.vx * (0.968 - stabilityDamping(coherence) * 0.012) + tangentX * curl * dt + radialX * expand * dt + n * jitter
            var vy = node.vy * (0.968 - stabilityDamping(coherence) * 0.012) + tangentY * curl * dt + radialY * expand * dt + phaseDrive * jitter
            var vz = node.vz * 0.975 + sin(node.phase * 0.7 + tick * 0.011) * (0.010 + modalityBias.text * 0.016)

            val speed = sqrt(vx * vx + vy * vy + vz * vz)
            val maxSpeed = 0.018 + 0.055 * (0.35 + motion * 0.65)
            if (speed > maxSpeed) {
                val k = maxSpeed / speed
                vx *= k; vy *= k; vz *= k
            }

            var x = node.x + vx * dt * 18.0
            var y = node.y + vy * dt * 18.0
            var z = node.z + vz * dt * 15.0
            x = wrapSigned(x, 1.45)
            y = wrapSigned(y, 1.45)
            z = wrapSigned(z, 1.15)

            val targetEnergy = (0.16 + energy * 0.52 + novelty * 0.18 + if (node.age < 5) 0.20 else 0.0).coerceIn(0.05, 1.0)
            node.copy(
                x = x,
                y = y,
                z = z,
                vx = vx,
                vy = vy,
                vz = vz,
                energy = lerp(node.energy, targetEnergy, 0.045),
                phase = wrapRadians(node.phase + dt * (0.32 + motion * 1.1 + tension * 0.44)),
                scale = lerp(node.scale, (0.55 + node.energy * 0.75 + coherence * 0.25).coerceIn(0.35, 1.55), 0.035),
                age = node.age + 1,
            )
        }
    }

    private fun resizeWorld(
        input: List<DreamNode>,
        desired: Int,
        seed: Long,
        tick: Long,
        novelty: Double,
    ): List<DreamNode> {
        val mutable = input.toMutableList()
        if (mutable.size < desired) {
            val addCount = min(2 + (novelty * 3).toInt(), desired - mutable.size)
            repeat(addCount) { offset ->
                val serial = tick * 97L + mutable.size + offset
                mutable += createNode(seed, serial, tick, lineage = 0)
            }
        } else if (mutable.size > desired) {
            mutable.sortWith(compareByDescending<DreamNode> { it.energy + if (it.age < 12) 0.15 else 0.0 }.thenBy { it.id })
            while (mutable.size > desired) mutable.removeAt(mutable.lastIndex)
        }
        return mutable
    }

    private fun buildLinks(
        nodes: List<DreamNode>,
        coherence: Double,
        relationBias: Double,
        tick: Long,
    ): List<DreamLink> {
        if (nodes.size < 2) return emptyList()
        val perNode = (1 + (coherence * 2.4 + relationBias * 1.4).toInt()).coerceIn(1, 4)
        val seen = HashSet<Pair<Long, Long>>()
        val links = ArrayList<DreamLink>()
        nodes.forEach { node ->
            nodes.asSequence()
                .filter { it.id != node.id }
                .map { other ->
                    val dx = node.x - other.x
                    val dy = node.y - other.y
                    val dz = node.z - other.z
                    val d2 = dx * dx + dy * dy + dz * dz
                    other to d2
                }
                .sortedBy { it.second }
                .take(perNode)
                .forEach { (other, d2) ->
                    val a = min(node.id, other.id)
                    val b = max(node.id, other.id)
                    if (seen.add(a to b)) {
                        val distance = sqrt(d2).coerceAtLeast(0.001)
                        val strength = ((1.15 - distance / 2.7) * (0.44 + coherence * 0.46 + relationBias * 0.22))
                            .coerceIn(0.08, 1.0)
                        links += DreamLink(a, b, strength, wrapRadians((hash64(a xor b) and 0xffffL) / 65535.0 * PI * 2.0 + tick * 0.009))
                    }
                }
        }
        return links.sortedByDescending { it.strength }.take(160)
    }

    private fun evolveCamera(
        camera: DreamCamera,
        nodes: List<DreamNode>,
        tick: Long,
        motion: Double,
        influence: DreamInfluence,
        dt: Double,
    ): DreamCamera {
        val targetX = if (nodes.isEmpty()) 0.0 else nodes.map { it.x * it.energy }.sum() / nodes.map { it.energy }.sum().coerceAtLeast(0.01)
        val targetY = if (nodes.isEmpty()) 0.0 else nodes.map { it.y * it.energy }.sum() / nodes.map { it.energy }.sum().coerceAtLeast(0.01)
        val orbit = 0.16 + motion * 0.28 + influence.camera * 0.18
        val tx = targetX * 0.35 + cos(tick * 0.0063) * orbit
        val ty = targetY * 0.35 + sin(tick * 0.0051) * orbit
        return DreamCamera(
            x = lerp(camera.x, tx, 0.018 + dt * 0.05),
            y = lerp(camera.y, ty, 0.018 + dt * 0.05),
            z = lerp(camera.z, sin(tick * 0.0037) * 0.16, 0.012),
            yaw = wrapRadians(camera.yaw + dt * (0.025 + motion * 0.06)),
            pitch = lerp(camera.pitch, sin(tick * 0.0027) * 0.08 + influence.audio * 0.05, 0.018),
            zoom = lerp(camera.zoom, (0.90 + influence.text * 0.06 + influence.camera * 0.10 - influence.audio * 0.03).coerceIn(0.72, 1.18), 0.025),
        )
    }

    private fun createNode(seed: Long, serial: Long, tick: Long, lineage: Int): DreamNode {
        val h0 = hash64(seed xor serial * -7046029254386353131L xor tick * 0x632BE59BD9B4E019L)
        val h1 = hash64(h0 xor 0x9E3779B97F4A7C15UL.toLong())
        val h2 = hash64(h1 xor 0xD1B54A32D192ED03UL.toLong())
        val id = if (h0 == 0L) serial + 1L else h0
        return DreamNode(
            id = id,
            x = signedUnit(h0) * 1.22,
            y = signedUnit(h1) * 1.22,
            z = signedUnit(h2) * 0.92,
            vx = signedUnit(hash64(h0 xor 0x11111111L)) * 0.018,
            vy = signedUnit(hash64(h1 xor 0x22222222L)) * 0.018,
            vz = signedUnit(hash64(h2 xor 0x33333333L)) * 0.012,
            energy = 0.24 + unit(hash64(h0 xor h2)) * 0.42,
            phase = unit(hash64(h1 xor h2)) * PI * 2.0,
            scale = 0.55 + unit(hash64(h0 + h1)) * 0.70,
            age = 0L,
            lineage = lineage,
        )
    }

    private fun blend3(memory: Double, external: Double, bootstrap: Double, memoryWeight: Double, externalWeight: Double): Double {
        val mw = memoryWeight.coerceAtLeast(0.0)
        val ew = externalWeight.coerceAtLeast(0.0)
        val bw = max(0.18, 1.0 - min(0.82, mw + ew))
        return ((memory.coerceIn(0.0, 1.0) * mw + external.coerceIn(0.0, 1.0) * ew + bootstrap.coerceIn(0.0, 1.0) * bw) /
            (mw + ew + bw).coerceAtLeast(0.0001)).coerceIn(0.0, 1.0)
    }

    companion object {
        fun hash64(value: Long): Long {
            var x = value + 0x9E3779B97F4A7C15UL.toLong()
            x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
            x = (x xor (x ushr 27)) * 0x94D049BB133111EBUL.toLong()
            return x xor (x ushr 31)
        }

        private fun unit(value: Long): Double = ((value ushr 11) and ((1L shl 53) - 1)).toDouble() / (1L shl 53).toDouble()
        private fun signedUnit(value: Long): Double = unit(value) * 2.0 - 1.0
        private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t.coerceIn(0.0, 1.0)
        private fun wrapSigned(v: Double, edge: Double): Double {
            var x = v
            val span = edge * 2.0
            while (x > edge) x -= span
            while (x < -edge) x += span
            return x
        }
        private fun wrapRadians(v: Double): Double {
            var x = v % (PI * 2.0)
            if (x < 0.0) x += PI * 2.0
            return x
        }
        private fun wrap01(value: Double): Double {
            var v = value % 1.0
            if (v < 0.0) v += 1.0
            return v
        }
        private fun circularLerp(a: Double, b: Double, t: Double): Double {
            val aa = wrap01(a)
            val bb = wrap01(b)
            var delta = bb - aa
            if (delta > 0.5) delta -= 1.0
            if (delta < -0.5) delta += 1.0
            return wrap01(aa + delta * t.coerceIn(0.0, 1.0))
        }
        private fun circularMean01(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sx = values.sumOf { cos(wrap01(it) * PI * 2.0) }
            val sy = values.sumOf { sin(wrap01(it) * PI * 2.0) }
            return wrap01(atan2(sy, sx) / (PI * 2.0))
        }
        private fun stabilityDamping(coherence: Double): Double = coherence.coerceIn(0.0, 1.0)
    }
}

/**
 * Compact persistence for the runtime world.  It only serializes numeric native state,
 * so it is safe to keep in SharedPreferences and independent from external model formats.
 */
object DreamWorldCodec {
    fun encode(state: DreamWorldState): String {
        val head = listOf(
            "1", state.seed, state.tick, state.energy, state.novelty, state.stability, state.tension,
            state.camera.x, state.camera.y, state.camera.z, state.camera.yaw, state.camera.pitch, state.camera.zoom,
            state.environment.hue, state.environment.saturation, state.environment.luminance,
            state.environment.fog, state.environment.curvature, state.environment.pulse,
            state.controlMode.name,
        ).joinToString(",")
        val nodes = state.nodes.joinToString(";") { n ->
            listOf(n.id, n.x, n.y, n.z, n.vx, n.vy, n.vz, n.energy, n.phase, n.scale, n.age, n.lineage).joinToString(",")
        }
        val links = state.links.joinToString(";") { l ->
            listOf(l.fromId, l.toId, l.strength, l.phase).joinToString(",")
        }
        return "$head|$nodes|$links"
    }

    fun decode(raw: String?): DreamWorldState? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val parts = raw.split('|', limit = 3)
            if (parts.size < 2) return@runCatching null
            val h = parts[0].split(',')
            if (h.size < 20 || h[0] != "1") return@runCatching null
            val nodes = parts[1].takeIf { it.isNotBlank() }?.split(';').orEmpty().mapNotNull { row ->
                val p = row.split(',')
                if (p.size < 12) null else DreamNode(
                    id = p[0].toLong(), x = p[1].toDouble(), y = p[2].toDouble(), z = p[3].toDouble(),
                    vx = p[4].toDouble(), vy = p[5].toDouble(), vz = p[6].toDouble(), energy = p[7].toDouble(),
                    phase = p[8].toDouble(), scale = p[9].toDouble(), age = p[10].toLong(), lineage = p[11].toInt(),
                )
            }
            val links = if (parts.size >= 3 && parts[2].isNotBlank()) parts[2].split(';').mapNotNull { row ->
                val p = row.split(',')
                if (p.size < 4) null else DreamLink(p[0].toLong(), p[1].toLong(), p[2].toDouble(), p[3].toDouble())
            } else emptyList()
            DreamWorldState(
                seed = h[1].toLong(), tick = h[2].toLong(), nodes = nodes, links = links,
                energy = h[3].toDouble(), novelty = h[4].toDouble(), stability = h[5].toDouble(), tension = h[6].toDouble(),
                camera = DreamCamera(h[7].toDouble(), h[8].toDouble(), h[9].toDouble(), h[10].toDouble(), h[11].toDouble(), h[12].toDouble()),
                environment = DreamEnvironment(h[13].toDouble(), h[14].toDouble(), h[15].toDouble(), h[16].toDouble(), h[17].toDouble(), h[18].toDouble()),
                controlMode = runCatching { DreamControlMode.valueOf(h[19]) }.getOrDefault(DreamControlMode.REACTIVE),
            )
        }.getOrNull()?.takeIf { it.nodes.isNotEmpty() }
    }
}
