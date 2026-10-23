package de.equinox.v05

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Kira v0.9.3 — Universal Macro Compiler contract.
 *
 * This file deliberately contains no Android dependency. It defines the one structural
 * language used by TEXT, AUDIO, VISION, MIND/relations, CROSSMODAL and future FORGE input.
 * A modality may keep its own encoder alphabet, but once an ID reaches MemoryModel it is
 * promoted, ranked and queried through this contract only.
 */
enum class KiraModalityFamily {
    TEXT,
    AUDIO,
    VISION,
    CROSSMODAL,
    FORGE,
    OTHER,
}

object KiraModalityClassifier {
    fun familyOf(modality: String): KiraModalityFamily {
        val m = modality.trim().lowercase()
        return when {
            m == "crossmodal" || m.startsWith("joint") -> KiraModalityFamily.CROSSMODAL
            m.startsWith("text") || m == "mind" || m.startsWith("language") -> KiraModalityFamily.TEXT
            m.startsWith("audio") || m.startsWith("music") -> KiraModalityFamily.AUDIO
            m.startsWith("vision") || m.startsWith("video") || m.startsWith("camera") -> KiraModalityFamily.VISION
            m.startsWith("forge") || m.startsWith("base") -> KiraModalityFamily.FORGE
            else -> KiraModalityFamily.OTHER
        }
    }
}

/**
 * One promotion score for every input family.
 *
 * support          = enough independent recurrence to justify a dictionary entry
 * stability        = evidence relative to the tier's required evidence
 * coherence        = how consistently the candidate's children belong together
 * recurrence       = how densely it recurs in its observed stream interval
 * compressionGain  = how much shorter the stream becomes after promotion
 *
 * Tier labels are *never* assigned by score. Depth 1 is F, depth 2 E ... Ω+n.
 */
data class UniversalPromotionScore(
    val eligible: Boolean,
    val requiredHits: Long,
    val support: Double,
    val stability: Double,
    val coherence: Double,
    val recurrence: Double,
    val compressionGain: Double,
    val score: Double,
    val priority: Double,
)

object UniversalMacroCompiler {
    fun evaluatePromotion(
        patternLength: Int,
        hits: Long,
        sourceLevel: Int,
        coherence: Double = 1.0,
        recurrence: Double = 1.0,
        minimumHits: Long = 0L,
    ): UniversalPromotionScore {
        val base = AdaptiveHierarchyPolicy.evaluate(
            patternLength = patternLength,
            hits = hits,
            sourceLevel = sourceLevel,
            minimumHits = minimumHits,
        )
        if (patternLength < 2 || hits <= 0L || base.requiredHits == Long.MAX_VALUE) {
            return UniversalPromotionScore(
                eligible = false,
                requiredHits = base.requiredHits,
                support = 0.0,
                stability = 0.0,
                coherence = coherence.coerceIn(0.0, 1.0),
                recurrence = recurrence.coerceIn(0.0, 1.0),
                compressionGain = 0.0,
                score = 0.0,
                priority = 0.0,
            )
        }

        val safeCoherence = coherence.coerceIn(0.0, 1.0)
        val safeRecurrence = recurrence.coerceIn(0.0, 1.0)
        val support = (hits.toDouble() / base.requiredHits.coerceAtLeast(1L).toDouble()).coerceIn(0.0, 1.0)
        val stability = (hits.toDouble() / (hits.toDouble() + base.requiredHits.coerceAtLeast(1L).toDouble())).coerceIn(0.0, 1.0)
        val compression = base.gainRatio.coerceIn(0.0, 1.0)

        // Geometric aggregation makes one genuinely weak dimension matter, while the small
        // epsilon avoids a mathematically dead candidate during its early evidence phase.
        val product = max(1e-12, support * stability * max(0.05, safeCoherence) * max(0.05, safeRecurrence) * max(0.01, compression))
        val score = product.pow(0.2).coerceIn(0.0, 1.0)

        // Keep the proven compression eligibility gate from Step 13, but rank every modality
        // with the same structural score. This avoids changing the meaning of existing stores.
        val eligible = base.eligible && safeCoherence >= 0.20
        val priority = if (eligible) {
            base.priority * (0.55 + 0.45 * score) * (1.0 + 0.04 * sourceLevel.coerceAtLeast(0))
        } else 0.0

        return UniversalPromotionScore(
            eligible = eligible,
            requiredHits = base.requiredHits,
            support = support,
            stability = stability,
            coherence = safeCoherence,
            recurrence = safeRecurrence,
            compressionGain = compression,
            score = score,
            priority = priority,
        )
    }

    /**
     * Stable, monotonic helper used by diagnostics/tests. The exact value is not a tier.
     */
    fun maturityFromEvidence(support: Long, parentReuse: Int, relationMass: Long): Double {
        return (
            0.55 * ln(1.0 + support.coerceAtLeast(0L).toDouble()) +
                0.30 * ln(1.0 + parentReuse.coerceAtLeast(0).toDouble()) +
                0.15 * ln(1.0 + relationMass.coerceAtLeast(0L).toDouble())
            ).coerceAtLeast(0.0)
    }
}

/** Read-only universal node projection. MemoryModel remains the owner of mutable state. */
data class KiraMemoryNode(
    val id: Int,
    val modality: String,
    val family: KiraModalityFamily,
    val origin: KnowledgeOrigin,
    val depth: Int,
    val tier: String,
    val members: List<Int>,
    val support: Long,
    val stability: Double,
    val coherence: Double,
    val recurrence: Double,
    val compressionGain: Double,
    val relevance: Double,
    val activation: Double,
    val expectation: Double,
    val retrieved: Boolean,
)

data class KiraMemoryEdge(
    val fromId: Int,
    val toId: Int,
    val weight: Long,
    val kind: String,
)

data class KiraMemoryQuery(
    val cueIds: Set<Int> = emptySet(),
    val families: Set<KiraModalityFamily> = emptySet(),
    val modalities: Set<String> = emptySet(),
    val minDepth: Int = 1,
    val maxDepth: Int? = null,
    val includeActive: Boolean = true,
    val includeRetrieved: Boolean = true,
    val includeParents: Boolean = true,
    val includeRelations: Boolean = true,
    val limit: Int = 64,
) {
    companion object {
        fun active(limit: Int = 64): KiraMemoryQuery = KiraMemoryQuery(
            includeParents = false,
            includeRelations = false,
            limit = limit,
        )
    }
}

data class KiraMemoryQueryResult(
    val nodes: List<KiraMemoryNode>,
    val edges: List<KiraMemoryEdge>,
    val cueIds: Set<Int>,
) {
    fun highestTier(): String = nodes.maxByOrNull { it.depth }?.tier ?: "—"
    fun families(): Set<KiraModalityFamily> = nodes.mapTo(linkedSetOf()) { it.family }
}
