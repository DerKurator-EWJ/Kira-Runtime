package de.equinox.v05

import kotlin.math.abs

/**
 * Kira v0.9.7 — Forge Intermediate Specification v1.
 *
 * This is the frozen, teacher-neutral boundary between an external forge/oven and Kira.
 * Hugging Face, PyTorch, ONNX, a CSV corpus or any later teacher may have its own adapter,
 * but the Kira core only accepts this vocabulary. No transformer layer/head/tensor name is a
 * runtime concept unless an adapter deliberately turns it into a semantic Kira record.
 *
 * Important: this is an INTERMEDIATE document, not an installed BASE package. v0.9.8 will
 * materialise a validated compilation plan atomically into the indexed BASE store.
 */
object ForgeIntermediateSpec {
    const val FORMAT = "kira-forge-intermediate-v1"
    const val SCHEMA_VERSION = 1
    const val MAX_ATOMS = 500_000
    const val MAX_RELATIONS = 2_000_000
    const val MAX_SEQUENCES = 250_000
    const val MAX_MACRO_CANDIDATES = 250_000
    const val MAX_TRAITS = 2_000_000
    const val MAX_VECTORS = 250_000
    const val MAX_VECTOR_DIMS = 4096
    const val MAX_MEMBERS = 256
}

enum class ForgeAtomKind { CONCEPT, FEATURE, EVENT, TRAIT, SYMBOL, STATE, OTHER }
enum class ForgeRelationPolarity { SUPPORT, OPPOSE, NEUTRAL }
enum class ForgeRelationDirection { DIRECTED, BIDIRECTIONAL }

data class ForgePackageDescriptor(
    val id: String,
    val version: Int,
    /** Stable across updates of the same knowledge family. It participates in Kira stable IDs. */
    val namespace: String,
)

data class ForgeSourceDescriptor(
    val id: String,
    val version: String,
    val adapterId: String,
    val adapterVersion: String,
    val modality: String,
    val modelFamily: String = "",
    val revision: String = "",
    val license: String = "",
    /** Optional hash/fingerprint of the teacher artefact. */
    val fingerprint: String = "",
)

data class ForgeAtom(
    val id: String,
    val modality: String,
    val kind: ForgeAtomKind,
    val canonical: String,
    /** Optional adapter-supplied semantic key. If blank, Kira derives one from semantic content. */
    val stableKey: String = "",
    val confidence: Double = 1.0,
    val support: Long = 1L,
    val sourceKey: String = "",
)

data class ForgeVector(
    val id: String,
    val ownerId: String,
    val space: String,
    val values: DoubleArray,
)

data class ForgeRelation(
    val fromId: String,
    val toId: String,
    val kind: String,
    val strength: Double,
    val confidence: Double = 1.0,
    val polarity: ForgeRelationPolarity = ForgeRelationPolarity.NEUTRAL,
    val direction: ForgeRelationDirection = ForgeRelationDirection.DIRECTED,
    /** e.g. explicit, embedding_similarity, classifier, corpus_cooccurrence. */
    val evidenceType: String = "explicit",
    val sourceKey: String = "",
)

data class ForgeSequence(
    val id: String,
    val modality: String,
    val members: List<String>,
    val stableKey: String = "",
    val confidence: Double = 1.0,
    val support: Long = 1L,
    val sourceKey: String = "",
)

data class ForgeMacroCandidate(
    val id: String,
    val modality: String,
    val members: List<String>,
    val stableKey: String = "",
    val support: Long,
    val coherence: Double = 1.0,
    val recurrence: Double = 1.0,
    val confidence: Double = 1.0,
    val sourceKey: String = "",
)

data class ForgeTrait(
    val ownerId: String,
    val key: String,
    val numberValue: Double? = null,
    val textValue: String? = null,
    val confidence: Double = 1.0,
    val sourceKey: String = "",
)

data class ForgeIntermediateDocument(
    val format: String = ForgeIntermediateSpec.FORMAT,
    val schemaVersion: Int = ForgeIntermediateSpec.SCHEMA_VERSION,
    val packageInfo: ForgePackageDescriptor,
    val source: ForgeSourceDescriptor,
    val atoms: List<ForgeAtom> = emptyList(),
    val vectors: List<ForgeVector> = emptyList(),
    val relations: List<ForgeRelation> = emptyList(),
    val sequences: List<ForgeSequence> = emptyList(),
    val macroCandidates: List<ForgeMacroCandidate> = emptyList(),
    val traits: List<ForgeTrait> = emptyList(),
)

data class ForgeValidationIssue(val severity: Severity, val code: String, val message: String) {
    enum class Severity { WARNING, ERROR }
}

data class ForgeCompiledNode(
    val stableId: Int,
    val externalId: String,
    val modality: String,
    val family: KiraModalityFamily,
    val kind: String,
    /** 0 = atom/sequence surface, 1 = F, 2 = E ... */
    val depth: Int,
    val tier: String,
    val memberIds: List<Int>,
    val support: Long,
    val confidence: Double,
    val promotion: UniversalPromotionScore? = null,
    val canonical: String = "",
)

data class ForgeCompiledRelation(
    val fromId: Int,
    val toId: Int,
    val kind: String,
    val strength: Double,
    val confidence: Double,
    val polarity: ForgeRelationPolarity,
    val direction: ForgeRelationDirection,
    val evidenceType: String,
)

data class ForgeCompilationPlan(
    val valid: Boolean,
    val packageInfo: ForgePackageDescriptor,
    val source: ForgeSourceDescriptor,
    val nodes: List<ForgeCompiledNode>,
    val relations: List<ForgeCompiledRelation>,
    val traits: List<ForgeTrait>,
    val vectorCount: Int,
    val highestDepth: Int,
    val highestTier: String,
    val stableIds: Map<String, Int>,
    val issues: List<ForgeValidationIssue>,
) {
    val errors: List<ForgeValidationIssue> get() = issues.filter { it.severity == ForgeValidationIssue.Severity.ERROR }
    val warnings: List<ForgeValidationIssue> get() = issues.filter { it.severity == ForgeValidationIssue.Severity.WARNING }
}

object ForgeIntermediateCompiler {
    fun validateAndPlan(document: ForgeIntermediateDocument): ForgeCompilationPlan {
        val issues = mutableListOf<ForgeValidationIssue>()
        fun error(code: String, text: String) { issues += ForgeValidationIssue(ForgeValidationIssue.Severity.ERROR, code, text) }
        fun warn(code: String, text: String) { issues += ForgeValidationIssue(ForgeValidationIssue.Severity.WARNING, code, text) }

        if (document.format != ForgeIntermediateSpec.FORMAT) error("FORMAT", "format muss '${ForgeIntermediateSpec.FORMAT}' sein")
        if (document.schemaVersion != ForgeIntermediateSpec.SCHEMA_VERSION) error("SCHEMA", "schemaVersion muss ${ForgeIntermediateSpec.SCHEMA_VERSION} sein")
        if (document.packageInfo.id.isBlank()) error("PACKAGE_ID", "package.id fehlt")
        if (document.packageInfo.version <= 0) error("PACKAGE_VERSION", "package.version muss > 0 sein")
        if (document.packageInfo.namespace.isBlank()) error("NAMESPACE", "package.namespace fehlt")
        if (document.source.id.isBlank()) error("SOURCE_ID", "source.id fehlt")
        if (document.source.adapterId.isBlank()) error("ADAPTER_ID", "source.adapterId fehlt")
        if (document.source.adapterVersion.isBlank()) error("ADAPTER_VERSION", "source.adapterVersion fehlt")
        if (document.source.modality.isBlank()) error("SOURCE_MODALITY", "source.modality fehlt")
        if (document.atoms.size > ForgeIntermediateSpec.MAX_ATOMS) error("ATOM_LIMIT", "zu viele atoms")
        if (document.relations.size > ForgeIntermediateSpec.MAX_RELATIONS) error("RELATION_LIMIT", "zu viele relations")
        if (document.sequences.size > ForgeIntermediateSpec.MAX_SEQUENCES) error("SEQUENCE_LIMIT", "zu viele sequences")
        if (document.macroCandidates.size > ForgeIntermediateSpec.MAX_MACRO_CANDIDATES) error("MACRO_LIMIT", "zu viele macroCandidates")
        if (document.traits.size > ForgeIntermediateSpec.MAX_TRAITS) error("TRAIT_LIMIT", "zu viele traits")
        if (document.vectors.size > ForgeIntermediateSpec.MAX_VECTORS) error("VECTOR_LIMIT", "zu viele vectors")

        val structuralIds = linkedSetOf<String>()
        fun claim(id: String, type: String) {
            if (id.isBlank()) error("EMPTY_ID", "$type id fehlt")
            else if (!structuralIds.add(id)) error("DUPLICATE_ID", "doppelte strukturelle id '$id'")
        }
        document.atoms.forEach { claim(it.id, "atom") }
        document.sequences.forEach { claim(it.id, "sequence") }
        document.macroCandidates.forEach { claim(it.id, "macroCandidate") }

        val atomById = document.atoms.associateBy { it.id }
        val sequenceById = document.sequences.associateBy { it.id }
        val macroById = document.macroCandidates.associateBy { it.id }

        document.atoms.forEach { atom ->
            if (atom.canonical.isBlank()) error("ATOM_CANONICAL", "atom '${atom.id}' hat kein canonical")
            if (atom.modality.isBlank()) error("ATOM_MODALITY", "atom '${atom.id}' hat keine modality")
            if (!atom.confidence.isFinite() || atom.confidence !in 0.0..1.0) error("ATOM_CONFIDENCE", "atom '${atom.id}' confidence außerhalb 0..1")
            if (atom.support <= 0L) error("ATOM_SUPPORT", "atom '${atom.id}' support muss > 0 sein")
            if (KiraModalityClassifier.familyOf(atom.modality) == KiraModalityFamily.OTHER) warn("UNKNOWN_MODALITY", "atom '${atom.id}' nutzt unbekannte modality '${atom.modality}'")
        }

        document.sequences.forEach { seq ->
            if (seq.modality.isBlank()) error("SEQUENCE_MODALITY", "sequence '${seq.id}' hat keine modality")
            if (seq.members.isEmpty()) error("SEQUENCE_EMPTY", "sequence '${seq.id}' hat keine members")
            if (seq.members.size > ForgeIntermediateSpec.MAX_MEMBERS) error("SEQUENCE_MEMBERS", "sequence '${seq.id}' hat zu viele members")
            seq.members.forEach { if (it !in atomById) error("SEQUENCE_REF", "sequence '${seq.id}' referenziert unbekanntes atom '$it'") }
            if (!seq.confidence.isFinite() || seq.confidence !in 0.0..1.0) error("SEQUENCE_CONFIDENCE", "sequence '${seq.id}' confidence außerhalb 0..1")
            if (seq.support <= 0L) error("SEQUENCE_SUPPORT", "sequence '${seq.id}' support muss > 0 sein")
            val parentFamily = KiraModalityClassifier.familyOf(seq.modality)
            val childFamilies = seq.members.mapNotNull(atomById::get).map { KiraModalityClassifier.familyOf(it.modality) }.toSet()
            if (parentFamily != KiraModalityFamily.CROSSMODAL && childFamilies.any { it != parentFamily }) {
                error("SEQUENCE_MODALITY_MIX", "sequence '${seq.id}' mischt Modalitäten ohne crossmodal")
            }
        }

        document.macroCandidates.forEach { macro ->
            if (macro.modality.isBlank()) error("MACRO_MODALITY", "macro '${macro.id}' hat keine modality")
            if (macro.members.size < 2) error("MACRO_MEMBERS", "macro '${macro.id}' benötigt mindestens 2 members")
            if (macro.members.size > ForgeIntermediateSpec.MAX_MEMBERS) error("MACRO_MEMBERS", "macro '${macro.id}' hat zu viele members")
            macro.members.forEach { member ->
                if (member !in atomById && member !in sequenceById && member !in macroById) error("MACRO_REF", "macro '${macro.id}' referenziert unbekanntes member '$member'")
                if (member == macro.id) error("MACRO_SELF", "macro '${macro.id}' darf sich nicht selbst referenzieren")
            }
            if (macro.support <= 0L) error("MACRO_SUPPORT", "macro '${macro.id}' support muss > 0 sein")
            if (!macro.coherence.isFinite() || macro.coherence !in 0.0..1.0) error("MACRO_COHERENCE", "macro '${macro.id}' coherence außerhalb 0..1")
            if (!macro.recurrence.isFinite() || macro.recurrence !in 0.0..1.0) error("MACRO_RECURRENCE", "macro '${macro.id}' recurrence außerhalb 0..1")
            if (!macro.confidence.isFinite() || macro.confidence !in 0.0..1.0) error("MACRO_CONFIDENCE", "macro '${macro.id}' confidence außerhalb 0..1")
        }

        val depthMemo = hashMapOf<String, Int>()
        val visit = hashSetOf<String>()
        fun depthOf(id: String): Int {
            depthMemo[id]?.let { return it }
            if (!visit.add(id)) {
                error("MACRO_CYCLE", "Zyklus in macroCandidates bei '$id'")
                return 0
            }
            val depth = when {
                id in atomById || id in sequenceById -> 0
                id in macroById -> 1 + (macroById.getValue(id).members.maxOfOrNull(::depthOf) ?: 0)
                else -> 0
            }
            visit.remove(id)
            depthMemo[id] = depth
            return depth
        }
        document.macroCandidates.forEach { depthOf(it.id) }

        // Modality mixing is legal only when the parent declares crossmodal.
        fun modalityOf(id: String): String? = atomById[id]?.modality ?: sequenceById[id]?.modality ?: macroById[id]?.modality
        document.macroCandidates.forEach { macro ->
            val parentFamily = KiraModalityClassifier.familyOf(macro.modality)
            val childFamilies = macro.members.mapNotNull(::modalityOf).map(KiraModalityClassifier::familyOf).filter { it != KiraModalityFamily.OTHER }.toSet()
            if (parentFamily != KiraModalityFamily.CROSSMODAL && childFamilies.any { it != parentFamily }) {
                error("MODALITY_MIX", "macro '${macro.id}' mischt Modalitäten ohne crossmodal")
            }
            if (parentFamily == KiraModalityFamily.CROSSMODAL && childFamilies.size < 2) {
                warn("CROSSMODAL_WEAK", "crossmodal macro '${macro.id}' enthält weniger als zwei bekannte Modalitätsfamilien")
            }
            val parentDepth = depthMemo[macro.id] ?: 1
            if (parentFamily != KiraModalityFamily.CROSSMODAL && parentDepth > 1) {
                val childDepths = macro.members.map { depthMemo[it] ?: 0 }.toSet()
                if (childDepths != setOf(parentDepth - 1)) {
                    error("TIER_GAP", "macro '${macro.id}' muss im Tier ${AbstractionTier.labelForMacroDepth(parentDepth)} nur direkte Kinder aus ${AbstractionTier.labelForMacroDepth(parentDepth - 1)} enthalten")
                }
            }
        }

        document.vectors.forEach { vector ->
            if (vector.id.isBlank()) error("VECTOR_ID", "vector id fehlt")
            if (vector.ownerId !in structuralIds) error("VECTOR_OWNER", "vector '${vector.id}' owner '${vector.ownerId}' unbekannt")
            if (vector.space.isBlank()) error("VECTOR_SPACE", "vector '${vector.id}' space fehlt")
            if (vector.values.isEmpty() || vector.values.size > ForgeIntermediateSpec.MAX_VECTOR_DIMS) error("VECTOR_DIMS", "vector '${vector.id}' dimensions ungültig")
            if (vector.values.any { !it.isFinite() }) error("VECTOR_VALUE", "vector '${vector.id}' enthält NaN/Infinity")
            if (vector.values.all { abs(it) < 1e-15 }) warn("ZERO_VECTOR", "vector '${vector.id}' ist ein Nullvektor")
        }
        if (document.vectors.map { it.id }.toSet().size != document.vectors.size) error("VECTOR_DUP", "doppelte vector ids")

        document.relations.forEachIndexed { index, rel ->
            if (rel.fromId !in structuralIds || rel.toId !in structuralIds) error("RELATION_REF", "relation[$index] referenziert unbekannte ids")
            if (rel.fromId == rel.toId) warn("RELATION_SELF", "relation[$index] ist ein Self-Link")
            if (rel.kind.isBlank()) error("RELATION_KIND", "relation[$index].kind fehlt")
            if (!rel.strength.isFinite() || rel.strength !in 0.0..1.0) error("RELATION_STRENGTH", "relation[$index] strength außerhalb 0..1")
            if (!rel.confidence.isFinite() || rel.confidence !in 0.0..1.0) error("RELATION_CONFIDENCE", "relation[$index] confidence außerhalb 0..1")
            // Embedding distance is semantic proximity, never logical contradiction by itself.
            if (rel.evidenceType.equals("embedding_similarity", true) && rel.polarity == ForgeRelationPolarity.OPPOSE) {
                error("EMBEDDING_ANTITHESIS", "embedding_similarity darf nicht direkt als OPPOSE/Antithese importiert werden")
            }
        }

        document.traits.forEachIndexed { index, trait ->
            if (trait.ownerId !in structuralIds) error("TRAIT_OWNER", "trait[$index] owner '${trait.ownerId}' unbekannt")
            if (trait.key.isBlank()) error("TRAIT_KEY", "trait[$index].key fehlt")
            val values = listOfNotNull(trait.numberValue, trait.textValue).size
            if (values != 1) error("TRAIT_VALUE", "trait[$index] benötigt genau einen number/text Wert")
            if (trait.numberValue != null && !trait.numberValue.isFinite()) error("TRAIT_NUMBER", "trait[$index] number ist NaN/Infinity")
            if (!trait.confidence.isFinite() || trait.confidence !in 0.0..1.0) error("TRAIT_CONFIDENCE", "trait[$index] confidence außerhalb 0..1")
        }

        if (issues.any { it.severity == ForgeValidationIssue.Severity.ERROR }) {
            return ForgeCompilationPlan(false, document.packageInfo, document.source, emptyList(), emptyList(), document.traits, document.vectors.size, 0, "—", emptyMap(), issues)
        }

        val keyMemo = hashMapOf<String, String>()
        fun normalized(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")
        fun structuralKey(id: String): String {
            keyMemo[id]?.let { return it }
            val key = atomById[id]?.let { a ->
                a.stableKey.ifBlank { "atom|${normalized(a.modality)}|${a.kind.name.lowercase()}|${normalized(a.canonical)}" }
            } ?: sequenceById[id]?.let { s ->
                s.stableKey.ifBlank { "sequence|${normalized(s.modality)}|" + s.members.joinToString(">") { structuralKey(it) } }
            } ?: macroById[id]?.let { m ->
                m.stableKey.ifBlank { "macro|${normalized(m.modality)}|" + m.members.joinToString(">") { structuralKey(it) } }
            } ?: "missing|$id"
            keyMemo[id] = key
            return key
        }

        val stableIds = linkedMapOf<String, Int>()
        val used = hashMapOf<Int, String>()
        structuralIds.sorted().forEach { id ->
            val semanticKey = structuralKey(id)
            val stable = stableId(document.packageInfo.namespace, semanticKey)
            val previous = used.putIfAbsent(stable, id)
            if (previous != null && previous != id) error("STABLE_ID_COLLISION", "Stable-ID-Kollision '$previous' ↔ '$id' ($stable)")
            stableIds[id] = stable
        }

        if (issues.any { it.severity == ForgeValidationIssue.Severity.ERROR }) {
            return ForgeCompilationPlan(false, document.packageInfo, document.source, emptyList(), emptyList(), document.traits, document.vectors.size, 0, "—", stableIds, issues)
        }

        val nodes = ArrayList<ForgeCompiledNode>(structuralIds.size)
        document.atoms.forEach { atom ->
            nodes += ForgeCompiledNode(
                stableId = stableIds.getValue(atom.id), externalId = atom.id, modality = atom.modality,
                family = KiraModalityClassifier.familyOf(atom.modality), kind = "atom:${atom.kind.name.lowercase()}",
                depth = 0, tier = "ATOM", memberIds = emptyList(), support = atom.support,
                confidence = atom.confidence, canonical = atom.canonical,
            )
        }
        document.sequences.forEach { seq ->
            nodes += ForgeCompiledNode(
                stableId = stableIds.getValue(seq.id), externalId = seq.id, modality = seq.modality,
                family = KiraModalityClassifier.familyOf(seq.modality), kind = "sequence", depth = 0,
                tier = "SEQUENCE", memberIds = seq.members.map(stableIds::getValue), support = seq.support,
                confidence = seq.confidence,
            )
        }
        document.macroCandidates.sortedBy { depthMemo[it.id] ?: 1 }.forEach { macro ->
            val depth = (depthMemo[macro.id] ?: 1).coerceAtLeast(1)
            val promotion = UniversalMacroCompiler.evaluatePromotion(
                patternLength = macro.members.size,
                hits = macro.support,
                sourceLevel = depth - 1,
                coherence = macro.coherence,
                recurrence = macro.recurrence,
            )
            nodes += ForgeCompiledNode(
                stableId = stableIds.getValue(macro.id), externalId = macro.id, modality = macro.modality,
                family = KiraModalityClassifier.familyOf(macro.modality), kind = "macro_candidate",
                depth = depth, tier = AbstractionTier.labelForMacroDepth(depth),
                memberIds = macro.members.map(stableIds::getValue), support = macro.support,
                confidence = macro.confidence, promotion = promotion,
            )
        }

        val relations = document.relations.flatMap { rel ->
            val a = stableIds.getValue(rel.fromId)
            val b = stableIds.getValue(rel.toId)
            val direct = ForgeCompiledRelation(a, b, rel.kind, rel.strength, rel.confidence, rel.polarity, rel.direction, rel.evidenceType)
            if (rel.direction == ForgeRelationDirection.BIDIRECTIONAL) listOf(direct, direct.copy(fromId = b, toId = a)) else listOf(direct)
        }
        val highestDepth = nodes.maxOfOrNull { it.depth } ?: 0
        return ForgeCompilationPlan(
            valid = true,
            packageInfo = document.packageInfo,
            source = document.source,
            nodes = nodes.sortedWith(compareBy<ForgeCompiledNode> { it.depth }.thenBy { it.stableId }),
            relations = relations,
            traits = document.traits,
            vectorCount = document.vectors.size,
            highestDepth = highestDepth,
            highestTier = if (highestDepth <= 0) "ATOM" else AbstractionTier.labelForMacroDepth(highestDepth),
            stableIds = stableIds,
            issues = issues,
        )
    }

    /** Stable across package versions as long as namespace + semantic key stay unchanged. */
    fun stableId(namespace: String, semanticKey: String): Int {
        val hash = fnv1a64("kira-forge-v1|${namespace.trim().lowercase()}|$semanticKey") and Long.MAX_VALUE
        val range = KnowledgeIdSpace.BASE_MACRO_MAX.toLong() - KnowledgeIdSpace.BASE_MACRO_MIN.toLong() + 1L
        return (KnowledgeIdSpace.BASE_MACRO_MIN.toLong() + (hash % range)).toInt()
    }

    private fun fnv1a64(text: String): Long {
        var hash = -3750763034362895579L
        text.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= 1099511628211L
        }
        return hash
    }
}
