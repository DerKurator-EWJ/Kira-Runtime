package de.equinox.v05

/**
 * Kira v0.9.5 – small native surface generator.
 *
 * This is intentionally not an LLM. It turns the readable surfaces selected by the
 * Unified Memory / Universal Macro / Decision pipeline into short product-facing
 * German answers. The generator owns no memory and never learns from its own output.
 */
data class KiraNativeTextResult(
    val text: String,
    val confidence: Double,
    val usedMemories: List<String> = emptyList(),
    val intent: String = "unknown",
)

object KiraNativeTextEngine {
    private val negations = setOf("nicht", "kein", "keine", "keiner", "keines", "keinem", "keinen", "nie", "niemals")
    private val copulas = setOf("ist", "sind", "war", "waren", "heißt", "bedeutet")
    private val yesNoStarts = setOf(
        "ist", "sind", "war", "waren", "hat", "haben", "kann", "können", "wird", "werden",
        "darf", "dürfen", "soll", "sollen", "muss", "müssen"
    )
    private val definitionStarts = setOf("was", "wer")
    private val openQuestionStarts = setOf("wie", "wo", "wohin", "woher", "wann", "warum", "wieso", "weshalb", "welche", "welcher", "welches")

    private data class Clause(
        val surface: String,
        val tokens: List<String>,
        val subject: String,
        val verb: String,
        val complement: String,
        val negated: Boolean,
    )


    /**
     * v0.9.6 sensor-to-text bridge. Kira never invents semantic labels for raw
     * microphone/camera structure; she can however report that routed perceptual
     * activity exists and how strong it currently is.
     */
    fun generatePerception(
        input: String,
        contextSignals: List<ChatContextSignal>,
    ): KiraNativeTextResult? {
        if (contextSignals.isEmpty()) return null
        val n = ChatTextLogic.normalize(input)
        val asksAudio = listOf("was horst du", "was hörst du", "horst du", "hörst du", "audio", "mikrofon", "ton", "klang")
            .any { it in n }
        val asksVision = listOf("was siehst du", "siehst du", "kamera", "bild", "video", "visuell")
            .any { it in n }
        val asksPerception = asksAudio || asksVision || listOf(
            "was nimmst du wahr", "was passiert gerade", "was ist gerade los", "was registrierst du"
        ).any { it in n }
        if (!asksPerception) return null

        fun strongest(family: KiraModalityFamily): Double = contextSignals.asSequence()
            .filter { KiraModalityClassifier.familyOf(it.sourceModality) == family }
            .maxOfOrNull { it.activation.coerceIn(0.0, 1.0) } ?: 0.0

        val audio = maxOf(strongest(KiraModalityFamily.AUDIO), strongest(KiraModalityFamily.CROSSMODAL))
        val vision = maxOf(strongest(KiraModalityFamily.VISION), strongest(KiraModalityFamily.CROSSMODAL))
        val parts = mutableListOf<String>()
        if ((!asksVision || asksAudio) && audio > 0.04) parts += "${strengthWord(audio)} Audio-Reiz"
        if ((!asksAudio || asksVision) && vision > 0.04) parts += "${strengthWord(vision)} Kamera-Reiz"
        if (parts.isEmpty()) return KiraNativeTextResult(
            text = "Über diesen Text-Ausgang ist gerade kein entsprechender Sensorreiz aktiv.",
            confidence = 0.92,
            intent = "perception_empty",
        )
        val joined = when (parts.size) {
            1 -> parts.first()
            else -> parts.dropLast(1).joinToString(", ") + " und " + parts.last()
        }
        return KiraNativeTextResult(
            text = "Ich registriere gerade $joined. Eine sprachliche Bedeutung dafür habe ich erst, wenn ich dazu stabile Relationen gelernt habe.",
            confidence = 0.88,
            intent = "perception_state",
        )
    }

    private fun strengthWord(value: Double): String = when {
        value >= 0.72 -> "einen starken"
        value >= 0.38 -> "einen mittleren"
        else -> "einen schwachen"
    }

    fun generate(
        input: String,
        plan: ChatReplyPlan,
        dialectic: DialecticDecisionState? = null,
    ): KiraNativeTextResult? {
        if (plan.kind in setOf(ChatReplyKind.MODULE, ChatReplyKind.IDENTITY, ChatReplyKind.QUESTION)) return null
        val clean = input.trim()
        if (clean.isBlank()) return null

        val question = ChatTextLogic.looksLikeQuestion(clean)
        val memories = plan.relatedTexts
            .ifEmpty { listOfNotNull(plan.relatedText) }
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !ChatTextLogic.looksLikeQuestion(it) }
            .distinctBy(ChatTextLogic::normalize)
            .toList()

        if (question) {
            if (memories.isEmpty()) {
                if (plan.kind == ChatReplyKind.BASE && plan.baseMatches.isNotEmpty()) {
                    return KiraNativeTextResult(
                        text = "Dazu habe ich BASE-Wissen, aber noch keine lesbare Relation.",
                        confidence = 0.25,
                        intent = "base_unresolved",
                    )
                }
                return null
            }
            return answerQuestion(clean, memories, dialectic)
        }

        return when (plan.kind) {
            ChatReplyKind.REPEATED -> KiraNativeTextResult(
                text = "Das ist bereits bekannt.",
                confidence = 0.80,
                intent = "ack_repeated",
            )
            ChatReplyKind.ASSOCIATIVE -> KiraNativeTextResult(
                text = "Verstanden. Das ergänzt mein bisheriges Wissen.",
                confidence = 0.70,
                usedMemories = memories.take(2),
                intent = "ack_associative",
            )
            ChatReplyKind.BASE -> KiraNativeTextResult(
                text = "Verstanden. Dazu ist bereits BASE-Wissen vorhanden.",
                confidence = 0.72,
                intent = "ack_base",
            )
            ChatReplyKind.UNSTABLE -> KiraNativeTextResult(
                text = "Verstanden.",
                confidence = 0.62,
                intent = "ack_new",
            )
            else -> null
        }
    }

    private fun answerQuestion(
        input: String,
        memories: List<String>,
        dialectic: DialecticDecisionState?,
    ): KiraNativeTextResult? {
        val inputTokens = tokens(input)
        if (inputTokens.isEmpty()) return null
        val first = inputTokens.first()
        val intent = when {
            first in definitionStarts && inputTokens.getOrNull(1) in copulas -> "definition"
            first in yesNoStarts -> "yes_no"
            first in openQuestionStarts -> "open_question"
            else -> "question"
        }

        val selected = selectMemories(input, memories, intent, dialectic)
        if (selected.isEmpty()) return null

        if (intent == "yes_no") {
            val best = selected.first()
            val clause = parseClause(best)
            val questionNegated = inputTokens.any { it in negations }
            val factNegated = clause?.negated ?: tokens(best).any { it in negations }
            val agrees = questionNegated == factNegated
            val prefix = if (agrees) "Ja." else "Nein."
            return KiraNativeTextResult(
                text = "$prefix ${sentence(best)}",
                confidence = (0.72 + 0.18 * (dialectic?.confidence ?: 0.0)).coerceIn(0.0, 0.95),
                usedMemories = listOf(best),
                intent = intent,
            )
        }

        val surfaces = selected.take(if (intent == "definition") 2 else 1).map(::sentence)
        return KiraNativeTextResult(
            text = surfaces.joinToString(" "),
            confidence = (0.68 + 0.20 * (dialectic?.confidence ?: 0.0)).coerceIn(0.0, 0.94),
            usedMemories = selected.take(surfaces.size),
            intent = intent,
        )
    }

    private fun selectMemories(
        input: String,
        memories: List<String>,
        intent: String,
        dialectic: DialecticDecisionState?,
    ): List<String> {
        val inputContent = contentTokens(input)
        val hasPositive = memories.any { !containsNegation(it) }
        val hasNegative = memories.any(::containsNegation)
        val preferredNegative = if (hasPositive && hasNegative && dialectic?.open == false) {
            dialectic.winner == DialecticBranch.ANTITHESIS
        } else null

        return memories.asSequence()
            .filter { memory -> preferredNegative?.let { containsNegation(memory) == it } ?: true }
            .mapIndexed { index, memory ->
                val clause = parseClause(memory)
                val overlap = if (inputContent.isEmpty()) 0.0 else {
                    inputContent.intersect(contentTokens(memory)).size.toDouble() / inputContent.size.toDouble()
                }
                var score = ChatTextLogic.similarity(input, memory) * 0.55 + overlap * 0.58 - index * 0.015
                if (intent == "definition" && clause?.verb in copulas) score += 0.38
                if (intent == "yes_no" && clause != null) {
                    val qVerb = tokens(input).firstOrNull().orEmpty()
                    if (verbFamily(qVerb) == verbFamily(clause.verb)) score += 0.26
                }
                memory to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy(ChatTextLogic::normalize)
            .toList()
    }

    private fun parseClause(text: String): Clause? {
        val raw = text.trim().trimEnd('.', '!', '?')
        val words = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 3) return null
        val normalized = words.map { normalizeWord(it) }
        val verbIndex = normalized.indexOfFirst { word ->
            word in copulas || word in yesNoStarts || word in setOf(
                "lebt", "leben", "mag", "mögen", "braucht", "brauchen", "besteht", "bestehen",
                "fliegt", "fliegen", "liegt", "liegen", "kommt", "kommen", "enthält", "enthalten",
                "erzeugt", "erzeugen", "verwendet", "verwenden"
            )
        }
        if (verbIndex <= 0 || verbIndex >= words.lastIndex) return null
        return Clause(
            surface = raw,
            tokens = normalized,
            subject = words.take(verbIndex).joinToString(" "),
            verb = normalized[verbIndex],
            complement = words.drop(verbIndex + 1).joinToString(" "),
            negated = normalized.any { it in negations },
        )
    }

    private fun verbFamily(verb: String): String = when (normalizeWord(verb)) {
        "ist", "sind", "war", "waren" -> "sein"
        "hat", "haben" -> "haben"
        "kann", "können" -> "können"
        "wird", "werden" -> "werden"
        "darf", "dürfen" -> "dürfen"
        "soll", "sollen" -> "sollen"
        "muss", "müssen" -> "müssen"
        else -> normalizeWord(verb)
    }

    private fun sentence(text: String): String {
        val clean = text.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ';', ':')
        if (clean.isBlank()) return ""
        val capitalized = clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return "$capitalized."
    }

    private fun containsNegation(text: String): Boolean = tokens(text).any { it in negations }

    private fun tokens(text: String): List<String> = ChatTextLogic.normalize(text)
        .split(' ')
        .filter { it.isNotBlank() }

    private fun contentTokens(text: String): Set<String> = tokens(text)
        .filter { it.length >= 2 && it !in setOf(
            "wer", "was", "wann", "wo", "wohin", "woher", "warum", "wieso", "weshalb", "wie",
            "welche", "welcher", "welches", "ist", "sind", "hat", "haben", "kann", "können",
            "ein", "eine", "einer", "eines", "einem", "einen", "der", "die", "das", "den", "dem", "des"
        ) }
        .toSet()

    private fun normalizeWord(word: String): String = word.lowercase().trim(',', '.', ';', ':', '!', '?', '(', ')')
}
