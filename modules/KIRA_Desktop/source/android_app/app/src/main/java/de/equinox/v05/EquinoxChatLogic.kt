package de.equinox.v05

enum class ChatRole { USER, EQUINOX }
enum class ChatDisplayMode { MATRIX, CLEAN, RAW }
enum class ChatReplyKind { BASE, ASSOCIATIVE, REPEATED, UNSTABLE, MODULE, IDENTITY, QUESTION, NATIVE }

/**
 * Terminal identity is deliberately presentation metadata. Prefixes must never be
 * injected into the semantic payload that is learned by MIND/FABRIC.
 */
object EquinoxTerminalIdentity {
    const val CURATOR_PREFIX = "Kurator"
    const val KIRA_PREFIX = "Kira"
    const val COMPOSER_PREFIX = "Kurator"
}

/**
 * Non-learned dialogue constitution. These rules are protocol, not memory:
 * learning may add facts about Kira or the Kurator, but it cannot rename the
 * terminal identities or overwrite who is speaking to whom.
 */
object KiraDialoguePolicy {
    private fun normalized(text: String): String = text
        .trim()
        .lowercase()
        .replace('ß', 's')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun identityReply(input: String): String? {
        val n = normalized(input)
        if (n.isBlank()) return null

        val asksKira = n in setOf(
            "wer bist du", "was bist du", "wie heist du", "wie heißt du",
            "was ist dein name", "bist du kira", "wer ist kira"
        )
        if (asksKira) return "Ich bin Kira."

        val asksCurator = n in setOf(
            "wer bin ich", "was bin ich", "wie heise ich", "wie heiße ich",
            "wie ist mein name", "bin ich der kurator"
        )
        if (asksCurator) return "Du bist der Kurator."

        if (n == "du bist kira") return "Ja. Ich bin Kira."
        if (n == "ich bin der kurator") return "Ja. Du bist der Kurator."

        val renameKira = n.startsWith("nenn dich ") || n.startsWith("nenne dich ") ||
            n.startsWith("ab jetzt bist du ") || n.startsWith("dein name ist ") ||
            n.startsWith("du heist ab jetzt ") || n.startsWith("du heißt ab jetzt ")
        if (renameKira) return "Ich bin Kira."

        return null
    }

    fun isIdentityControlInput(input: String): Boolean = identityReply(input) != null
}

data class ChatMemorySample(
    val text: String,
    val normalized: String = ChatTextLogic.normalize(text),
    /** Conversation order, used only as a small tie-breaker rather than authority. */
    val ordinal: Int = 0,
)

data class ChatContextSignal(
    val sourceModality: String,
    val sourceId: Long,
    val activation: Double,
)

data class ChatReplyPlan(
    val kind: ChatReplyKind,
    val text: String,
    val relatedText: String? = null,
    val relatedTexts: List<String> = emptyList(),
    val similarity: Double = 0.0,
    val occurrence: Int = 1,
    val baseMatches: List<String> = emptyList(),
    val focusTokens: List<String> = emptyList(),
    val variant: Int = 0,
)

object ChatTextLogic {
    private val questionWords = setOf(
        "wer", "was", "wann", "wo", "wohin", "woher", "warum", "wieso", "weshalb",
        "wie", "welche", "welcher", "welches", "kann", "können", "ist", "sind", "hat", "haben",
        "macht", "machen", "gibt", "darf", "soll", "sollte", "muss", "müssen"
    )
    private val lowInformationWords = questionWords + setOf(
        "ein", "eine", "einer", "eines", "einem", "einen", "der", "die", "das", "den", "dem", "des",
        "und", "oder", "aber", "auch", "nur", "noch", "schon", "mal", "denn", "dann", "da", "dort",
        "zu", "zum", "zur", "von", "vom", "für", "mit", "ohne", "auf", "in", "im", "an", "am", "aus",
        "ich", "du", "er", "sie", "es", "wir", "ihr", "mir", "dir", "mich", "dich", "sich",
        "sein", "seine", "seiner", "seinem", "seinen", "werden", "wird", "wurde", "wurden"
    )
    private val negations = setOf("nicht", "kein", "keine", "keiner", "keines", "keinem", "keinen", "nie", "niemals")

    fun normalize(text: String): String = text
        .trim()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun tokens(text: String): Set<String> = normalize(text)
        .split(' ')
        .filter { it.length >= 2 }
        .toSet()

    private fun orderedTokens(text: String): List<String> = normalize(text)
        .split(' ')
        .filter { it.length >= 2 }

    private fun contentTokens(text: String): List<String> = orderedTokens(text)
        .filter { it !in lowInformationWords }
        .distinct()
        .take(8)

    /** Real BLAKE2b-63 IDs touched by the local chat-ingest path. */
    fun activityIdsForInput(text: String): List<Long> {
        val normalized = normalize(text)
        if (normalized.isBlank()) return emptyList()
        val ids = linkedSetOf<Long>()
        ids += EquinoxId64.stableId("surface", normalized)
        ids += EquinoxId64.stableId("relation", "chat_role")
        ids += EquinoxId64.stableId("relation", "chat_mentions")
        ids += EquinoxId64.stableId("relation", "chat_surface")
        ids += EquinoxId64.stableId("relation", "chat_next")
        tokens(text).sorted().take(24).forEach { token ->
            ids += EquinoxId64.stableId("concept", token)
        }
        return ids.toList()
    }

    fun similarity(a: String, b: String): Double {
        val aa = tokens(a)
        val bb = tokens(b)
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        val intersection = aa.intersect(bb).size.toDouble()
        val union = aa.union(bb).size.toDouble().coerceAtLeast(1.0)
        return intersection / union
    }

    /**
     * Recall is deliberately competitive instead of winner-takes-all.  Several
     * memories may remain candidates for the same concept.  A question-like memory
     * is strongly down-ranked when Kira is trying to answer a question, so asking
     * "was ist toastbrot" once cannot become the permanent answer to itself.
     */
    fun rankedRelated(input: String, prior: List<ChatMemorySample>, limit: Int = 5): List<Pair<ChatMemorySample, Double>> {
        val normalized = normalize(input)
        val focus = contentTokens(input).toSet()
        val inputQuestion = looksLikeQuestion(input)
        val maxOrdinal = prior.maxOfOrNull { it.ordinal }?.coerceAtLeast(1) ?: 1

        return prior.asSequence()
            .filter { it.normalized.isNotBlank() && it.normalized != normalized }
            .map { sample ->
                val sampleTokens = tokens(sample.text)
                val lexical = similarity(input, sample.text)
                val focusCoverage = if (focus.isEmpty()) 0.0 else focus.intersect(sampleTokens).size.toDouble() / focus.size.toDouble()
                val sampleQuestion = looksLikeQuestion(sample.text)
                val recency = (sample.ordinal.coerceAtLeast(0).toDouble() / maxOrdinal.toDouble()) * 0.075
                val declarativeBonus = if (inputQuestion && !sampleQuestion) 0.20 else 0.0
                val questionPenalty = if (inputQuestion && sampleQuestion) 0.34 else 0.0
                val definitionBonus = if (inputQuestion && isDefinitionLikeQuestion(input) && isDefinitionLikeStatement(sample.text)) 0.12 else 0.0
                val score = (lexical * 0.58 + focusCoverage * 0.50 + recency + declarativeBonus + definitionBonus - questionPenalty)
                    .coerceIn(0.0, 1.35)
                sample to score
            }
            .filter { it.second >= if (inputQuestion) 0.24 else 0.22 }
            .sortedWith(compareByDescending<Pair<ChatMemorySample, Double>> { it.second }
                .thenByDescending { it.first.ordinal }
                .thenByDescending { it.first.text.length })
            .let { ranked ->
                val diverse = mutableListOf<Pair<ChatMemorySample, Double>>()
                ranked.forEach { candidate ->
                    val duplicate = diverse.any { similarity(it.first.text, candidate.first.text) > 0.86 }
                    if (!duplicate) diverse += candidate
                }
                diverse.take(limit)
            }
    }

    fun bestRelated(input: String, prior: List<ChatMemorySample>): Pair<ChatMemorySample, Double>? =
        rankedRelated(input, prior, limit = 1).firstOrNull()

    fun compileMessageTriples(messageId: Long, role: ChatRole, text: String, previousMessageId: Long? = null): List<MindTriple> {
        val triples = linkedSetOf<MindTriple>()
        val roleRel = EquinoxId64.stableId("relation", "chat_role")
        val mentionsRel = EquinoxId64.stableId("relation", "chat_mentions")
        val surfaceRel = EquinoxId64.stableId("relation", "chat_surface")
        val nextRel = EquinoxId64.stableId("relation", "chat_next")
        triples += MindTriple(messageId, roleRel, EquinoxId64.stableId("chat_role", role.name.lowercase()))
        val normalized = normalize(text)
        if (normalized.isNotBlank()) triples += MindTriple(messageId, surfaceRel, EquinoxId64.stableId("surface", normalized))
        tokens(text).sorted().take(32).forEach { token ->
            triples += MindTriple(messageId, mentionsRel, EquinoxId64.stableId("concept", token))
        }
        previousMessageId?.let { previous -> triples += MindTriple(previous, nextRel, messageId) }
        return triples.toList()
    }

    /**
     * Product chat shows only the resulting surface. The recursive/mirrored relation
     * path is available separately via relationTrace() and never needs to clutter the
     * ordinary conversation.
     */
    data class ResolvedChatSurface(
        val text: String,
        val kind: ChatReplyKind,
    )

    /**
     * Resolves the product-facing answer. Kira may turn an unresolved internal state
     * into a concise question rather than exposing the recursive loop itself.
     */
    fun resolveReply(
        input: String,
        plan: ChatReplyPlan,
        contextSignals: List<ChatContextSignal> = emptyList(),
        dialectic: DialecticDecisionState? = null,
    ): ResolvedChatSurface {
        if (plan.kind == ChatReplyKind.MODULE) return ResolvedChatSurface(plan.text, ChatReplyKind.MODULE)
        if (plan.kind == ChatReplyKind.IDENTITY) return ResolvedChatSurface(plan.text, ChatReplyKind.IDENTITY)
        if (plan.kind == ChatReplyKind.QUESTION) return ResolvedChatSurface(plan.text, ChatReplyKind.QUESTION)

        KiraNativeTextEngine.generatePerception(input, contextSignals)?.let { generated ->
            return ResolvedChatSurface(generated.text, ChatReplyKind.NATIVE)
        }

        autonomousQuestion(input, plan, contextSignals, dialectic)?.let { question ->
            return ResolvedChatSurface(question, ChatReplyKind.QUESTION)
        }

        KiraNativeTextEngine.generate(input, plan, dialectic)?.let { generated ->
            return ResolvedChatSurface(generated.text, ChatReplyKind.NATIVE)
        }

        val text = when (plan.kind) {
            ChatReplyKind.BASE -> if (looksLikeQuestion(input)) {
                plan.baseMatches.firstOrNull()?.let(::relationSurface)?.takeIf { it.isNotBlank() } ?: "bekannt"
            } else "bekannt"
            ChatReplyKind.ASSOCIATIVE -> if (looksLikeQuestion(input)) {
                stableAnswer(plan.relatedTexts.ifEmpty { listOfNotNull(plan.relatedText) }, plan.variant, dialectic)
            } else "verbunden"
            ChatReplyKind.REPEATED -> if (looksLikeQuestion(input)) "noch offen" else "erinnert"
            ChatReplyKind.UNSTABLE -> if (looksLikeQuestion(input)) "noch offen" else "gelernt"
            ChatReplyKind.MODULE, ChatReplyKind.IDENTITY, ChatReplyKind.QUESTION, ChatReplyKind.NATIVE -> plan.text
        }
        return ResolvedChatSurface(text, plan.kind)
    }

    fun structuralReply(
        input: String,
        plan: ChatReplyPlan,
        contextSignals: List<ChatContextSignal> = emptyList(),
        dialectic: DialecticDecisionState? = null,
    ): String = resolveReply(input, plan, contextSignals, dialectic).text

    /** Visible only when the user enables RELATIONSWEG in the output features. */
    fun relationTrace(
        input: String,
        plan: ChatReplyPlan,
        contextSignals: List<ChatContextSignal> = emptyList(),
        dialectic: DialecticDecisionState? = null,
    ): String {
        val primary = mirrorChain(letterTokens(input))
        val candidates = plan.relatedTexts.ifEmpty { listOfNotNull(plan.relatedText) }
            .map(::relationSurface).filter { it.isNotBlank() }.take(3)
        val context = symbolicContext(contextSignals)
        val winner = dialectic?.takeUnless { it.open }?.winner?.let { if (it == DialecticBranch.THESIS) "these" else "antithese" }
        return buildString {
            append(primary)
            if (candidates.isNotEmpty()) append(" · erinnern ↔ ").append(candidates.joinToString(" · "))
            if (context.isNotBlank()) append(" · ").append(context)
            if (winner != null) append(" · stabil ↔ ").append(winner)
            else if (dialectic?.open == true) append(" · entscheidung ↔ offen")
        }
    }

    private fun autonomousQuestion(
        input: String,
        plan: ChatReplyPlan,
        contextSignals: List<ChatContextSignal>,
        dialectic: DialecticDecisionState?,
    ): String? {
        val memories = plan.relatedTexts.ifEmpty { listOfNotNull(plan.relatedText) }
            .filter { it.isNotBlank() && !looksLikeQuestion(it) }
            .distinct()

        // Two incompatible memories with almost equal support are not silently forced
        // into a winner. Kira asks the Kurator which branch should receive more weight.
        val thesis = memories.filterNot(::hasNegation)
        val antithesis = memories.filter(::hasNegation)
        if (looksLikeQuestion(input) && thesis.isNotEmpty() && antithesis.isNotEmpty()) {
            val ts = dialectic?.thesisStability ?: 0.5
            val as_ = dialectic?.antithesisStability ?: 0.5
            if (dialectic?.open == true || kotlin.math.abs(ts - as_) <= 0.10) {
                val t = relationSurface(thesis.first())
                val a = relationSurface(antithesis.first())
                if (t.isNotBlank() && a.isNotBlank()) {
                    return "Welche Aussage soll ich höher gewichten: „$t“ oder „$a“?"
                }
            }
        }

        // A direct question with no stable evidence should become a request for missing
        // information instead of a dead-end 'noch offen'.
        if (plan.kind == ChatReplyKind.UNSTABLE && looksLikeQuestion(input)) {
            return "Das ist für mich noch offen. Was soll ich dazu lernen?"
        }

        // A genuinely cross-modal event can also make Kira curious. This only triggers
        // when at least two non-text sensor families are simultaneously strong, avoiding
        // a question on every ordinary microphone/camera frame.
        if (plan.kind == ChatReplyKind.UNSTABLE && !looksLikeQuestion(input)) {
            val sensorFamilies = contextSignals.asSequence()
                .filter { it.activation >= 0.65 }
                .mapNotNull { signal ->
                    when (KiraModalityClassifier.familyOf(signal.sourceModality)) {
                        KiraModalityFamily.AUDIO -> "Audio"
                        KiraModalityFamily.VISION -> "Kamera"
                        KiraModalityFamily.CROSSMODAL -> "Audio/Kamera"
                        else -> null
                    }
                }
                .distinct()
                .toList()
            if (sensorFamilies.size >= 2) {
                return "Ich habe dazu gleichzeitig ${sensorFamilies.joinToString(" und ")}-Reize. Soll ich sie damit verknüpfen?"
            }
        }

        // If the same unresolved material returns, Kira is allowed to initiate the next
        // information-gathering step even when the Kurator did not ask a question.
        if (plan.kind == ChatReplyKind.REPEATED && plan.occurrence >= 4) {
            return "Das bleibt für mich offen. Was ist daran für dich entscheidend?"
        }

        return null
    }

    private fun stableAnswer(memories: List<String>, variant: Int, dialectic: DialecticDecisionState?): String {
        val facts = memories.asSequence()
            .filter { it.isNotBlank() && !looksLikeQuestion(it) }
            .map(::relationSurface)
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (facts.isEmpty()) return "noch offen"

        val thesis = facts.filterNot(::hasNegation)
        val antithesis = facts.filter(::hasNegation)
        if (thesis.isNotEmpty() && antithesis.isNotEmpty()) {
            val t = rotate(thesis, variant).first()
            val a = rotate(antithesis, variant + 1).first()
            val tScore = (dialectic?.thesisStability ?: 0.5) + 0.02
            val aScore = dialectic?.antithesisStability ?: 0.5
            return if (aScore > tScore) a else t
        }
        return rotate(facts, variant).first()
    }

    private fun <T> rotate(items: List<T>, offset: Int): List<T> {
        if (items.size <= 1) return items
        val start = ((offset % items.size) + items.size) % items.size
        return items.drop(start) + items.take(start)
    }

    fun looksLikeQuestion(text: String): Boolean {
        if ('?' in text) return true
        val first = letterTokens(text).firstOrNull() ?: return false
        return first in questionWords
    }

    private fun isDefinitionLikeQuestion(text: String): Boolean {
        val t = orderedTokens(text)
        if (t.isEmpty()) return false
        return (t.firstOrNull() == "was" && t.getOrNull(1) in setOf("ist", "sind")) ||
            (t.firstOrNull() == "wer" && t.getOrNull(1) in setOf("ist", "sind"))
    }

    private fun isDefinitionLikeStatement(text: String): Boolean {
        val t = orderedTokens(text)
        return t.any { it in setOf("ist", "sind", "bedeutet", "heißt", "nennt") }
    }

    private fun hasNegation(text: String): Boolean = orderedTokens(text).any { it in negations }

    private fun letterTokens(text: String): List<String> = text
        .lowercase()
        .split(Regex("[^\\p{L}]+"))
        .filter { it.isNotBlank() }
        .take(18)

    private fun relationSurface(text: String): String = text
        .lowercase()
        .replace(Regex("[^\\p{L}\\s,.;:!?()\\-–—]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('·', '-', '–', '—', ',', ';', ':')

    private fun mirrorChain(words: List<String>): String {
        if (words.isEmpty()) return "◇"
        val forward = words.joinToString(" → ")
        val backward = words.asReversed().joinToString(" ← ")
        return "$forward ⇄ $backward"
    }

    private fun symbolicContext(signals: List<ChatContextSignal>): String {
        if (signals.isEmpty()) return ""
        val contextWords = linkedSetOf<String>()
        signals.forEach { signal ->
            when (KiraModalityClassifier.familyOf(signal.sourceModality)) {
                KiraModalityFamily.AUDIO -> contextWords += "hören"
                KiraModalityFamily.VISION -> contextWords += "sehen"
                KiraModalityFamily.CROSSMODAL -> {
                    contextWords += "hören"
                    contextWords += "sehen"
                }
                else -> letterTokens(signal.sourceModality).forEach { contextWords += it }
            }
        }
        return if (contextWords.isEmpty()) "" else "kontext ↔ " + contextWords.joinToString(" ↔ ")
    }

    fun planReply(
        input: String,
        occurrence: Int,
        prior: List<ChatMemorySample>,
        baseMatches: List<String> = emptyList(),
    ): ChatReplyPlan {
        val clean = input.trim()
        require(clean.isNotEmpty()) { "empty chat input" }
        KiraDialoguePolicy.identityReply(clean)?.let { identity ->
            return ChatReplyPlan(
                kind = ChatReplyKind.IDENTITY,
                text = identity,
                occurrence = occurrence,
                focusTokens = emptyList(),
                variant = occurrence + prior.size,
            )
        }
        val uniqueBase = baseMatches.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5)
        val focus = contentTokens(clean)
        val ranked = rankedRelated(clean, prior, limit = 5)
        val question = looksLikeQuestion(clean)

        // Local declarative evidence gets first chance to answer a question.  This
        // prevents a generic BASE hit or an earlier identical question from becoming
        // an immutable response.
        if (question && ranked.isNotEmpty()) {
            val related = ranked.map { it.first.text }
            return ChatReplyPlan(
                kind = ChatReplyKind.ASSOCIATIVE,
                text = "Mehrere lokale Relationen konkurrieren um die Antwort.",
                relatedText = related.firstOrNull(),
                relatedTexts = related,
                similarity = ranked.first().second,
                occurrence = occurrence,
                focusTokens = focus,
                variant = occurrence + prior.size,
            )
        }

        if (uniqueBase.isNotEmpty()) {
            val named = uniqueBase.joinToString(", ")
            return ChatReplyPlan(
                kind = ChatReplyKind.BASE,
                text = "BASE-Treffer: $named. Die Eingabe wurde lokal verknüpft.",
                occurrence = occurrence,
                baseMatches = uniqueBase,
                focusTokens = focus,
                variant = occurrence + prior.size,
            )
        }

        if (ranked.isNotEmpty()) {
            val related = ranked.map { it.first.text }
            return ChatReplyPlan(
                kind = ChatReplyKind.ASSOCIATIVE,
                text = "Mehrere lokale Spuren bleiben als konkurrierende Relationen aktiv.",
                relatedText = related.firstOrNull(),
                relatedTexts = related,
                similarity = ranked.first().second,
                occurrence = occurrence,
                focusTokens = focus,
                variant = occurrence + prior.size,
            )
        }

        if (occurrence > 1) {
            return ChatReplyPlan(
                kind = ChatReplyKind.REPEATED,
                text = "Die Eingabe ist erneut aufgetreten; sie bleibt offen für weitere Relationen.",
                occurrence = occurrence,
                focusTokens = focus,
                variant = occurrence + prior.size,
            )
        }

        return ChatReplyPlan(
            kind = ChatReplyKind.UNSTABLE,
            text = "Noch keine stabile Antwort. Die Eingabe bleibt als neue lokale Spur offen.",
            occurrence = occurrence,
            focusTokens = focus,
            variant = occurrence + prior.size,
        )
    }
}
