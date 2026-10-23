package de.equinox.v05

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Kira v0.9.4 – one decision mechanism for every output.
 *
 * This core does not own memory and never learns. It receives evidence projected from the
 * universal F→Ω+n memory hierarchy and forms two competing paths. THESIS means the currently
 * reinforcing path; ANTITHESIS means the currently competing/corrective path. Neither branch is
 * intrinsically true or false. OPEN means the evidence is too close/weak to force a winner.
 */
enum class KiraDecisionBranch { THESIS, ANTITHESIS }
enum class KiraDecisionStatus { EMPTY, OPEN, STABLE }

data class KiraDecisionEvidence(
    val id: Int,
    val depth: Int,
    val modality: String,
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
    val retrieved: Boolean = false,
)

data class KiraDecisionVote(
    val id: Int,
    val branch: KiraDecisionBranch,
    val depth: Int,
    val tier: String,
    val strength: Double,
    val modality: String,
)

data class KiraDecisionState(
    val status: KiraDecisionStatus = KiraDecisionStatus.EMPTY,
    val winner: KiraDecisionBranch? = null,
    val thesisStability: Double = 0.0,
    val antithesisStability: Double = 0.0,
    val confidence: Double = 0.0,
    val openness: Double = 1.0,
    val thesisDepth: Int = 1,
    val antithesisDepth: Int = 1,
    val votes: List<KiraDecisionVote> = emptyList(),
) {
    fun tierLabels(branch: KiraDecisionBranch): List<String> {
        val depth = if (branch == KiraDecisionBranch.THESIS) thesisDepth else antithesisDepth
        return (1..depth.coerceAtLeast(1)).map(AbstractionTier::labelForMacroDepth)
    }

    fun branchFor(id: Int): KiraDecisionBranch? = votes.firstOrNull { it.id == id }?.branch
    fun selectedIds(): Set<Int> = winner?.let { w -> votes.filter { it.branch == w }.mapTo(linkedSetOf()) { it.id } } ?: emptySet()
}

object KiraDecisionCore {
    private const val MIN_STABLE_EVIDENCE = 0.22
    private const val OPEN_MARGIN = 0.085

    fun evaluate(evidence: List<KiraDecisionEvidence>): KiraDecisionState {
        if (evidence.isEmpty()) return KiraDecisionState()

        val votes = evidence.map { e ->
            val act = e.activation.coerceIn(0.0, 1.0)
            val exp = e.expectation.coerceIn(0.0, 1.0)
            val rel = e.relevance.coerceIn(0.0, 1.0)
            val maturity = e.maturity.coerceIn(0.0, 1.0)
            val recurrence = e.recurrence.coerceIn(0.0, 1.0)
            val reuse = e.reuse.coerceIn(0.0, 1.0)
            val relation = e.relationSupport.coerceIn(0.0, 1.0)
            val persistence = e.persistence.coerceIn(0.0, 1.0)
            val generality = e.generality.coerceIn(0.0, 1.0)
            val entropy = e.contextEntropy.coerceIn(0.0, 1.0)
            val depthWeight = (ln(1.0 + e.depth.coerceAtLeast(1).toDouble()) / ln(12.0)).coerceIn(0.0, 1.0)

            val structural = (
                0.20 * act + 0.13 * exp + 0.18 * rel + 0.10 * maturity +
                    0.08 * recurrence + 0.07 * reuse + 0.08 * relation +
                    0.06 * persistence + 0.04 * generality + 0.06 * depthWeight
                ).coerceIn(0.0, 1.0)

            // Direction comes from the mismatch between what is active and what was expected,
            // plus the signed relation exchange. Entropy is corrective evidence, not "negative" truth.
            val signedExchange = e.exchangeBias / (1.0 + abs(e.exchangeBias))
            val direction = (
                0.48 * (act - exp) +
                    0.34 * signedExchange -
                    0.18 * (entropy - 0.5)
                ).coerceIn(-1.0, 1.0)
            val branch = if (direction >= 0.0) KiraDecisionBranch.THESIS else KiraDecisionBranch.ANTITHESIS
            val contradictionEnergy = abs(direction)
            val strength = (structural * (0.74 + contradictionEnergy * 0.26) + if (e.retrieved) 0.025 else 0.0)
                .coerceIn(0.0, 1.0)
            KiraDecisionVote(
                id = e.id,
                branch = branch,
                depth = e.depth.coerceAtLeast(1),
                tier = AbstractionTier.labelForMacroDepth(e.depth.coerceAtLeast(1)),
                strength = strength,
                modality = e.modality,
            )
        }

        fun branchStability(branch: KiraDecisionBranch): Double {
            val branchVotes = votes.filter { it.branch == branch && it.strength >= 0.05 }
                .sortedByDescending { it.strength }
                .take(12)
            if (branchVotes.isEmpty()) return 0.0
            val weighted = branchVotes.map { it.strength * (1.0 + minOf(it.depth, 12) * 0.018) }
            return (weighted.average() * (0.84 + 0.16 * (branchVotes.size / 6.0).coerceIn(0.0, 1.0))).coerceIn(0.0, 1.0)
        }

        fun depth(branch: KiraDecisionBranch): Int = votes
            .filter { it.branch == branch && it.strength >= MIN_STABLE_EVIDENCE }
            .maxOfOrNull { it.depth } ?: 1

        val thesis = branchStability(KiraDecisionBranch.THESIS)
        val anti = branchStability(KiraDecisionBranch.ANTITHESIS)
        val strongest = max(thesis, anti)
        val margin = abs(thesis - anti)
        val enoughEvidence = strongest >= MIN_STABLE_EVIDENCE
        val open = !enoughEvidence || margin <= OPEN_MARGIN
        val winner = if (open) null else if (anti > thesis) KiraDecisionBranch.ANTITHESIS else KiraDecisionBranch.THESIS
        val confidence = if (!enoughEvidence) 0.0 else (margin / max(0.001, strongest)).coerceIn(0.0, 1.0)
        val openness = if (!enoughEvidence) 1.0 else (1.0 - confidence).coerceIn(0.0, 1.0)

        return KiraDecisionState(
            status = if (open) KiraDecisionStatus.OPEN else KiraDecisionStatus.STABLE,
            winner = winner,
            thesisStability = thesis,
            antithesisStability = anti,
            confidence = confidence,
            openness = openness,
            thesisDepth = depth(KiraDecisionBranch.THESIS),
            antithesisDepth = depth(KiraDecisionBranch.ANTITHESIS),
            votes = votes.sortedWith(compareByDescending<KiraDecisionVote> { it.strength }.thenByDescending { it.depth }),
        )
    }
}
