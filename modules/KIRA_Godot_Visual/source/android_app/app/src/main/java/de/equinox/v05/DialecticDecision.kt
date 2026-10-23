package de.equinox.v05

/** Compatibility surface for the existing tier slab and chat API.
 * v0.9.4 delegates all actual decisions to KiraDecisionCore. */
enum class DialecticBranch { THESIS, ANTITHESIS }

data class DialecticEvidence(
    val id: Int,
    val depth: Int,
    val activation: Double,
    val expectation: Double,
    val exchangeBias: Double,
    val relevance: Double,
    val maturity: Double,
    val recurrence: Double,
    val reuse: Double,
    val relationSupport: Double,
    val contextEntropy: Double,
    val persistence: Double,
    val generality: Double,
    val modality: String = "other",
    val retrieved: Boolean = false,
)

data class DialecticDecisionState(
    val thesisDepth: Int = 1,
    val antithesisDepth: Int = 1,
    val thesisStability: Double = 0.0,
    val antithesisStability: Double = 0.0,
    val winner: DialecticBranch = DialecticBranch.THESIS,
    val open: Boolean = false,
    val confidence: Double = 0.0,
) {
    fun tierLabels(branch: DialecticBranch): List<String> {
        val depth = if (branch == DialecticBranch.THESIS) thesisDepth else antithesisDepth
        return (1..depth.coerceAtLeast(1)).map(AbstractionTier::labelForMacroDepth)
    }
}

object DialecticDecisionEngine {
    fun evaluate(evidence: List<DialecticEvidence>): DialecticDecisionState {
        val state = KiraDecisionCore.evaluate(evidence.map { e ->
            KiraDecisionEvidence(
                id = e.id, depth = e.depth, modality = e.modality,
                activation = e.activation, expectation = e.expectation,
                exchangeBias = e.exchangeBias, relevance = e.relevance,
                maturity = e.maturity, recurrence = e.recurrence, reuse = e.reuse,
                relationSupport = e.relationSupport, contextEntropy = e.contextEntropy,
                persistence = e.persistence, generality = e.generality, retrieved = e.retrieved,
            )
        })
        return state.toDialectic()
    }
}

fun KiraDecisionState.toDialectic(): DialecticDecisionState = DialecticDecisionState(
    thesisDepth = thesisDepth,
    antithesisDepth = antithesisDepth,
    thesisStability = thesisStability,
    antithesisStability = antithesisStability,
    winner = when (winner) {
        KiraDecisionBranch.ANTITHESIS -> DialecticBranch.ANTITHESIS
        else -> DialecticBranch.THESIS
    },
    open = status != KiraDecisionStatus.STABLE,
    confidence = confidence,
)
