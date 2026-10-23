package de.equinox.v05

import kotlin.math.abs
import kotlin.math.max

/**
 * Kira v0.9.6 — shared live cross-modal runtime contract.
 *
 * IN decides which stimulus families are admitted. MASTER decides which admitted
 * families are allowed to affect each output. This runtime turns that 3x3 patch
 * matrix into one deterministic projection consumed by TEXT, VIDEO/DREAM and AUDIO.
 * It owns no memory and performs no learning.
 */
enum class KiraInputLane { TEXT, AUDIO, CAMERA }
enum class KiraOutputLane { TEXT, VIDEO, AUDIO }

data class KiraRouteMatrix(
    private val routes: BooleanArray = BooleanArray(9) { true },
) {
    init { require(routes.size == 9) { "3x3 MASTER matrix required" } }

    fun enabled(input: KiraInputLane, output: KiraOutputLane): Boolean =
        routes[input.ordinal * 3 + output.ordinal]

    fun withRoute(input: KiraInputLane, output: KiraOutputLane, enabled: Boolean): KiraRouteMatrix {
        val copy = routes.copyOf()
        copy[input.ordinal * 3 + output.ordinal] = enabled
        return KiraRouteMatrix(copy)
    }

    fun snapshot(): BooleanArray = routes.copyOf()
}

data class KiraInputAvailability(
    val text: Boolean = true,
    val audio: Boolean = false,
    val camera: Boolean = false,
) {
    fun enabled(lane: KiraInputLane): Boolean = when (lane) {
        KiraInputLane.TEXT -> text
        KiraInputLane.AUDIO -> audio
        KiraInputLane.CAMERA -> camera
    }
}

data class KiraCrossModalSample(
    val id: Long,
    val family: KiraModalityFamily,
    val activation: Double,
    val expectation: Double = 0.0,
    val relevance: Double = 0.0,
    val exchangeBias: Double = 0.0,
    val depth: Int = 1,
)

data class KiraCrossModalProjection(
    val output: KiraOutputLane,
    val textDrive: Double = 0.0,
    val audioDrive: Double = 0.0,
    val cameraDrive: Double = 0.0,
    val energy: Double = 0.0,
    val motion: Double = 0.0,
    val pulse: Double = 0.0,
    val novelty: Double = 0.0,
    val tension: Double = 0.0,
    val coherence: Double = 0.0,
    val activeIds: List<Long> = emptyList(),
    val seed: Long = 0L,
) {
    val anyLiveDrive: Boolean get() = maxOf(textDrive, audioDrive, cameraDrive) > 0.035

    fun strongestInput(): KiraInputLane? = when (maxOf(textDrive, audioDrive, cameraDrive)) {
        textDrive -> KiraInputLane.TEXT.takeIf { textDrive > 0.035 }
        audioDrive -> KiraInputLane.AUDIO.takeIf { audioDrive > 0.035 }
        else -> KiraInputLane.CAMERA.takeIf { cameraDrive > 0.035 }
    }
}

object KiraCrossModalRuntime {
    fun project(
        output: KiraOutputLane,
        routes: KiraRouteMatrix,
        availability: KiraInputAvailability,
        samples: List<KiraCrossModalSample>,
        liveText: String = "",
    ): KiraCrossModalProjection {
        fun routed(lane: KiraInputLane): Boolean = availability.enabled(lane) && routes.enabled(lane, output)

        val textSamples = if (routed(KiraInputLane.TEXT)) samples.filter { it.family == KiraModalityFamily.TEXT } else emptyList()
        val audioSamples = if (routed(KiraInputLane.AUDIO)) samples.filter {
            it.family == KiraModalityFamily.AUDIO || it.family == KiraModalityFamily.CROSSMODAL
        } else emptyList()
        val cameraSamples = if (routed(KiraInputLane.CAMERA)) samples.filter {
            it.family == KiraModalityFamily.VISION || it.family == KiraModalityFamily.CROSSMODAL
        } else emptyList()

        fun drive(values: List<KiraCrossModalSample>): Double {
            if (values.isEmpty()) return 0.0
            val strongest = values.sortedByDescending { it.activation }.take(12)
            return strongest.map {
                (0.62 * it.activation + 0.18 * it.expectation + 0.20 * it.relevance).coerceIn(0.0, 1.0)
            }.average().coerceIn(0.0, 1.0)
        }

        val liveTextDrive = if (routed(KiraInputLane.TEXT) && liveText.isNotBlank()) {
            (0.18 + liveText.trim().length / 220.0).coerceIn(0.18, 0.86)
        } else 0.0
        val textDrive = max(drive(textSamples), liveTextDrive)
        val audioDrive = drive(audioSamples)
        val cameraDrive = drive(cameraSamples)

        val routedSamples = linkedSetOf<KiraCrossModalSample>().apply {
            addAll(textSamples)
            addAll(audioSamples)
            addAll(cameraSamples)
            // FORGE/OTHER are persistent context, not an IN lane. They may shape coherence
            // only when at least one live route is open, but never create a fake live drive.
            if (textDrive + audioDrive + cameraDrive > 0.0) {
                addAll(samples.filter { it.family == KiraModalityFamily.FORGE || it.family == KiraModalityFamily.OTHER })
            }
        }.toList()

        val relation = if (routedSamples.isEmpty()) 0.0 else routedSamples
            .sortedByDescending { it.relevance }
            .take(16)
            .map { it.relevance.coerceIn(0.0, 1.0) }
            .average()
        val expectation = if (routedSamples.isEmpty()) 0.0 else routedSamples
            .sortedByDescending { it.expectation }
            .take(16)
            .map { it.expectation.coerceIn(0.0, 1.0) }
            .average()
        val exchange = if (routedSamples.isEmpty()) 0.0 else routedSamples
            .take(24)
            .map { abs(it.exchangeBias).coerceIn(0.0, 1.0) }
            .average()
        val depth = if (routedSamples.isEmpty()) 0.0 else (
            routedSamples.maxOfOrNull { it.depth.coerceAtLeast(1) } ?: 1
        ).coerceAtMost(12) / 12.0

        val energy = (0.28 * textDrive + 0.36 * audioDrive + 0.36 * cameraDrive + 0.18 * relation)
            .coerceIn(0.0, 1.0)
        val motion = (0.10 * textDrive + 0.38 * audioDrive + 0.52 * cameraDrive + 0.12 * exchange)
            .coerceIn(0.0, 1.0)
        val pulse = (0.16 * textDrive + 0.58 * audioDrive + 0.18 * cameraDrive + 0.18 * expectation)
            .coerceIn(0.0, 1.0)
        val novelty = (0.22 * textDrive + 0.22 * audioDrive + 0.38 * cameraDrive + 0.18 * (1.0 - relation))
            .coerceIn(0.0, 1.0)
        val tension = (0.14 * textDrive + 0.36 * audioDrive + 0.20 * cameraDrive + 0.30 * exchange)
            .coerceIn(0.0, 1.0)
        val coherence = (0.42 * relation + 0.30 * expectation + 0.18 * depth + 0.10 * (1.0 - novelty))
            .coerceIn(0.0, 1.0)

        val ids = routedSamples.asSequence()
            .sortedByDescending { it.activation }
            .map { it.id }
            .filter { it != 0L }
            .distinct()
            .take(64)
            .toMutableList()
        if (liveTextDrive > 0.0 && liveText.isNotBlank()) {
            ids.add(0, EquinoxId64.stableId("crossmodal_text_surface", ChatTextLogic.normalize(liveText)))
        }
        val distinctIds = ids.filter { it != 0L }.distinct().take(64)
        val seed = distinctIds.fold(0x4B4952415F583936L xor output.ordinal.toLong()) { acc, id -> mix64(acc xor id) }

        return KiraCrossModalProjection(
            output = output,
            textDrive = textDrive,
            audioDrive = audioDrive,
            cameraDrive = cameraDrive,
            energy = energy,
            motion = motion,
            pulse = pulse,
            novelty = novelty,
            tension = tension,
            coherence = coherence,
            activeIds = distinctIds,
            seed = seed,
        )
    }

    private fun mix64(value: Long): Long {
        var x = value + 0x9E3779B97F4A7C15UL.toLong()
        x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        x = (x xor (x ushr 27)) * 0x94D049BB133111EBUL.toLong()
        return x xor (x ushr 31)
    }
}
