package de.equinox.v05

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Kira v0.8.5 operational module bus.
 *
 * The app no longer treats MATH / CODING / FILM / ORCHESTRA as decorative modes.
 * They are capabilities consuming one immutable state packet.  The packet is deliberately
 * independent from Android views so a future local/remote generator can replace one backend
 * without changing the UI or the MIND/FABRIC side.
 */
enum class OperationalModuleId { MATH, CODING, FILM, ORCHESTRA }

enum class CodingLanguage(val label: String) {
    KOTLIN("Kotlin"), PYTHON("Python"), JAVASCRIPT("JavaScript")
}

data class KiraStateVector(
    val activity: Double = 0.0,
    val expectation: Double = 0.0,
    val relevance: Double = 0.0,
    val relationEnergy: Double = 0.0,
    val signedExchange: Double = 0.0,
    val prediction: Double = 0.0,
    val novelty: Double = 0.0,
    val crossmodal: Double = 0.0,
    val tierDepth: Double = 0.0,
    val retrievalShare: Double = 0.0,
    val directActivation: Double = 0.0,
    val density: Double = 0.0,
    val memoryEnergy: Double = 0.0,
)

data class KiraModuleContext(
    val text: String,
    val activeIds: List<Long> = emptyList(),
    /** Ranked conversational/learned surfaces. Modules may use them as context, never as truth. */
    val memoryHints: List<String> = emptyList(),
    val state: KiraStateVector = KiraStateVector(),
)

data class MathModuleTuning(
    val precision: Int = 6,
    val functionsEnabled: Boolean = true,
)

data class CodingModuleTuning(
    val language: CodingLanguage = CodingLanguage.KOTLIN,
    /** Strict mode emits only constructs Kira can deterministically map. */
    val strict: Boolean = true,
    val useMemoryContext: Boolean = true,
)

data class FilmModuleTuning(
    /** User authored baseline; the live state is added on top. */
    val intensity: Double = 0.55,
    /** How strongly learned/retrieved state bends the procedural director. */
    val stateInfluence: Double = 0.70,
)

data class OrchestraModuleTuning(
    val memoryBlend: Double = 0.35,
    val maxVoices: Int = 9,
    val tempoScale: Double = 1.0,
)

data class KiraModuleTuning(
    val math: MathModuleTuning = MathModuleTuning(),
    val coding: CodingModuleTuning = CodingModuleTuning(),
    val film: FilmModuleTuning = FilmModuleTuning(),
    val orchestra: OrchestraModuleTuning = OrchestraModuleTuning(),
)

data class ModuleTextGeneration(
    val module: OperationalModuleId,
    val text: String,
    val confidence: Double,
    val meta: String = "",
)

data class FilmGenerationDirective(
    val enabled: Boolean,
    val intensity: Float,
    val motion: Float,
    val pulse: Float,
    val stateInfluence: Float,
    val seed: Long,
    val textDrive: Float = 0f,
    val audioDrive: Float = 0f,
    val cameraDrive: Float = 0f,
)

data class OrchestraGenerationDirective(
    val enabled: Boolean,
    val memoryBlend: Float,
    val maxVoices: Int,
    val tempoScale: Float,
)

class KiraOperationalRegistry {
    private val math = MathOperationalModule()
    private val coding = CodingOperationalModule()

    fun generateText(
        active: Set<OperationalModuleId>,
        context: KiraModuleContext,
        tuning: KiraModuleTuning,
    ): ModuleTextGeneration? {
        // Explicit coding intent outranks incidental arithmetic inside a code request.
        val code = if (OperationalModuleId.CODING in active) coding.generate(context, tuning.coding) else null
        if (code != null && coding.intentScore(context.text) >= 0.80) return code

        val mathResult = if (OperationalModuleId.MATH in active) math.generate(context, tuning.math) else null
        if (mathResult != null) return mathResult
        return code
    }

    fun filmDirective(
        active: Set<OperationalModuleId>,
        context: KiraModuleContext,
        tuning: KiraModuleTuning,
        crossModal: KiraCrossModalProjection? = null,
    ): FilmGenerationDirective? {
        if (OperationalModuleId.FILM !in active) return null
        val t = tuning.film
        val s = context.state
        val stateEnergy = (
            0.22 * s.activity +
                0.20 * s.relationEnergy +
                0.18 * s.novelty +
                0.14 * s.prediction +
                0.13 * s.crossmodal +
                0.13 * s.memoryEnergy
            ).coerceIn(0.0, 1.0)
        val influence = t.stateInfluence.coerceIn(0.0, 1.0)
        val x = crossModal
        val intensity = (
            t.intensity.coerceIn(0.0, 1.0) * (1.0 - 0.35 * influence) +
                stateEnergy * influence +
                (x?.textDrive ?: 0.0) * 0.08 +
                (x?.audioDrive ?: 0.0) * 0.08 +
                (x?.cameraDrive ?: 0.0) * 0.12
            ).coerceIn(0.0, 1.0)
        val motion = (
            0.10 + 0.42 * s.activity + 0.24 * s.novelty + 0.18 * s.signedExchange.absoluteValue +
                (x?.textDrive ?: 0.0) * 0.06 +
                (x?.audioDrive ?: 0.0) * 0.20 +
                (x?.cameraDrive ?: 0.0) * 0.34
            ).coerceIn(0.06, 1.0)
        val pulse = (
            0.15 + 0.42 * s.prediction + 0.28 * s.relationEnergy +
                (x?.textDrive ?: 0.0) * 0.08 +
                (x?.audioDrive ?: 0.0) * 0.32 +
                (x?.cameraDrive ?: 0.0) * 0.10
            ).coerceIn(0.0, 1.0)
        val seedIds = if (x?.activeIds.isNullOrEmpty()) context.activeIds else x!!.activeIds
        val seed = seedIds.fold((x?.seed ?: 0x4B495241L)) { acc, id -> mix64(acc xor id) }
        return FilmGenerationDirective(
            enabled = true,
            intensity = intensity.toFloat(),
            motion = motion.toFloat(),
            pulse = pulse.toFloat(),
            stateInfluence = influence.toFloat(),
            seed = seed,
            textDrive = (x?.textDrive ?: 0.0).toFloat(),
            audioDrive = (x?.audioDrive ?: 0.0).toFloat(),
            cameraDrive = (x?.cameraDrive ?: 0.0).toFloat(),
        )
    }

    fun orchestraDirective(
        active: Set<OperationalModuleId>,
        tuning: KiraModuleTuning,
    ): OrchestraGenerationDirective? {
        if (OperationalModuleId.ORCHESTRA !in active) return null
        val t = tuning.orchestra
        return OrchestraGenerationDirective(
            enabled = true,
            memoryBlend = t.memoryBlend.coerceIn(0.0, 1.0).toFloat(),
            maxVoices = t.maxVoices.coerceIn(2, 18),
            tempoScale = t.tempoScale.coerceIn(0.65, 1.45).toFloat(),
        )
    }

    private fun mix64(value: Long): Long {
        var x = value + 0x9E3779B97F4A7C15UL.toLong()
        x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        x = (x xor (x ushr 27)) * 0x94D049BB133111EBUL.toLong()
        return x xor (x ushr 31)
    }
}

private val Double.absoluteValue: Double get() = abs(this)

private class MathOperationalModule {
    fun generate(context: KiraModuleContext, tuning: MathModuleTuning): ModuleTextGeneration? {
        val expression = extractExpression(context.text, tuning.functionsEnabled) ?: return null
        val value = runCatching { ExpressionParser(expression, tuning.functionsEnabled).parse() }.getOrNull() ?: return null
        if (!value.isFinite()) return null
        val formatted = format(value, tuning.precision)
        return ModuleTextGeneration(
            module = OperationalModuleId.MATH,
            text = "∑ $expression = $formatted",
            confidence = 1.0,
            meta = "deterministic-math",
        )
    }

    private fun extractExpression(text: String, functionsEnabled: Boolean): String? {
        var s = text.trim()
            .replace('×', '*')
            .replace('÷', '/')
            .replace('−', '-')
        if (s.isBlank()) return null
        val lower = s.lowercase()
        val markers = listOf("/math", "mathe", "berechne", "rechne", "calc", "calculate", "was ist")
        markers.firstOrNull { lower.startsWith(it) }?.let { marker ->
            s = s.substring(marker.length).trim().trimStart(':', '=', ' ')
        }
        val functionNames = if (functionsEnabled) listOf("sqrt", "sin", "cos", "tan", "abs", "ln", "log", "floor", "ceil", "round") else emptyList()
        val startFunction = functionNames.map { s.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull()
        val startConstant = s.indexOf("pi", ignoreCase = true).takeIf { it >= 0 }
        val startNumberRaw = s.indexOfFirst { it.isDigit() || it == '(' || it == 'π' }
        val startNumber = when {
            startNumberRaw > 0 && s[startNumberRaw - 1] in setOf('-', '+') -> startNumberRaw - 1
            startNumberRaw >= 0 -> startNumberRaw
            else -> -1
        }
        val start = listOfNotNull(startFunction, startConstant, startNumber.takeIf { it >= 0 }).minOrNull() ?: return null
        var end = s.length
        val tail = s.substring(start)
        // Trim prose after the final numeric/closing-paren token but retain function names/operators.
        val allowedWord = functionNames + listOf("pi", "e")
        val pieces = tail.split(Regex("\\s+"))
        val accepted = mutableListOf<String>()
        for (piece in pieces) {
            val clean = piece.trim().trimEnd('?', '!', ';')
            if (clean.isBlank()) continue
            val lexical = clean.lowercase().trim('(', ')', ',', '.')
            val charsOkay = clean.all { ch -> ch.isDigit() || ch in ".,+-*/%^()π" || ch.isLetter() }
            if (!charsOkay) break
            if (clean.any { it.isLetter() } && lexical !in allowedWord && !allowedWord.any { lexical.startsWith(it) }) break
            accepted += clean
        }
        if (accepted.isNotEmpty()) s = accepted.joinToString(" ") else s = s.substring(start, end)
        s = s.replace(',', '.').replace('π', 'p')
        if (!s.any { it.isDigit() } && !s.contains("pi", true)) return null
        val hasOperation = s.any { it in "+-*/%^" } || functionNames.any { s.contains(it, true) }
        if (!hasOperation) return null
        return s.trim()
    }

    private fun format(value: Double, precision: Int): String {
        val scale = precision.coerceIn(0, 12)
        return BigDecimal.valueOf(value)
            .setScale(scale, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }
}

private class CodingOperationalModule {
    fun intentScore(text: String): Double {
        val t = text.lowercase()
        val strong = listOf("/code", "coding", "code ", "programmiere", "programmier", "funktion", "function", "klasse", "class ", "kotlin", "python", "javascript", "typescript")
        return when {
            strong.any { it in t } -> 1.0
            "skript" in t || "script" in t || "methode" in t -> 0.82
            else -> 0.0
        }
    }

    fun generate(context: KiraModuleContext, tuning: CodingModuleTuning): ModuleTextGeneration? {
        val score = intentScore(context.text)
        if (score <= 0.0) return null
        val language = inferLanguage(context.text, tuning.language)
        val request = context.text.trim()
        val lower = request.lowercase()
        val name = extractIdentifierAfter(lower, request, listOf("funktion", "function", "methode", "klasse", "class"))
            ?: "generatedTask"
        val arithmetic = detectArithmetic(lower)
        val isClass = listOf("klasse", "class ").any { it in lower }
        val isFunction = listOf("funktion", "function", "methode").any { it in lower } || arithmetic != null

        val code = when {
            isClass -> classSkeleton(language, sanitizeTypeName(name), context, tuning)
            isFunction -> functionSkeleton(language, sanitizeIdentifier(name), arithmetic, context, tuning)
            !tuning.strict -> genericSkeleton(language, request, context, tuning)
            else -> return null
        }
        return ModuleTextGeneration(
            module = OperationalModuleId.CODING,
            text = code,
            confidence = if (arithmetic != null || isClass) 0.96 else 0.82,
            meta = "deterministic-code:${language.name.lowercase()}",
        )
    }

    private fun inferLanguage(text: String, fallback: CodingLanguage): CodingLanguage {
        val t = text.lowercase()
        return when {
            "python" in t -> CodingLanguage.PYTHON
            "javascript" in t || "js " in t || t.endsWith(" js") -> CodingLanguage.JAVASCRIPT
            "kotlin" in t || "android" in t -> CodingLanguage.KOTLIN
            else -> fallback
        }
    }

    private fun detectArithmetic(text: String): String? = when {
        listOf("addier", "summe", " plus ").any { it in text } -> "+"
        listOf("subtrah", "minus", "differenz").any { it in text } -> "-"
        listOf("multipl", "produkt", " mal ").any { it in text } -> "*"
        listOf("divid", "quotient", "geteilt").any { it in text } -> "/"
        else -> null
    }

    private fun extractIdentifierAfter(lower: String, original: String, markers: List<String>): String? {
        for (marker in markers) {
            val idx = lower.indexOf(marker)
            if (idx < 0) continue
            val after = original.substring((idx + marker.length).coerceAtMost(original.length)).trim()
            val candidate = after.split(Regex("[^\\p{L}\\p{N}_]+"), limit = 2).firstOrNull().orEmpty()
            if (candidate.isNotBlank()) return candidate
        }
        return null
    }

    private fun contextComment(language: CodingLanguage, context: KiraModuleContext, tuning: CodingModuleTuning): String {
        if (!tuning.useMemoryContext) return ""
        val hint = context.memoryHints.firstOrNull()?.replace(Regex("\\s+"), " ")?.trim()?.take(120) ?: return ""
        return when (language) {
            CodingLanguage.KOTLIN, CodingLanguage.JAVASCRIPT -> "// relation context: $hint\n"
            CodingLanguage.PYTHON -> "# relation context: $hint\n"
        }
    }

    private fun functionSkeleton(
        language: CodingLanguage,
        name: String,
        arithmetic: String?,
        context: KiraModuleContext,
        tuning: CodingModuleTuning,
    ): String {
        val comment = contextComment(language, context, tuning)
        return when (language) {
            CodingLanguage.KOTLIN -> if (arithmetic != null) {
                "${comment}fun $name(a: Double, b: Double): Double = a $arithmetic b"
            } else {
                "${comment}fun $name(input: String): String {\n    return input\n}"
            }
            CodingLanguage.PYTHON -> if (arithmetic != null) {
                "${comment}def $name(a: float, b: float) -> float:\n    return a $arithmetic b"
            } else {
                "${comment}def $name(input_text: str) -> str:\n    return input_text"
            }
            CodingLanguage.JAVASCRIPT -> if (arithmetic != null) {
                "${comment}function $name(a, b) {\n  return a $arithmetic b;\n}"
            } else {
                "${comment}function $name(input) {\n  return input;\n}"
            }
        }
    }

    private fun classSkeleton(
        language: CodingLanguage,
        name: String,
        context: KiraModuleContext,
        tuning: CodingModuleTuning,
    ): String {
        val comment = contextComment(language, context, tuning)
        return when (language) {
            CodingLanguage.KOTLIN -> "${comment}class $name {\n    fun run(input: String): String = input\n}"
            CodingLanguage.PYTHON -> "${comment}class $name:\n    def run(self, input_text: str) -> str:\n        return input_text"
            CodingLanguage.JAVASCRIPT -> "${comment}class $name {\n  run(input) {\n    return input;\n  }\n}"
        }
    }

    private fun genericSkeleton(
        language: CodingLanguage,
        request: String,
        context: KiraModuleContext,
        tuning: CodingModuleTuning,
    ): String {
        val comment = contextComment(language, context, tuning)
        val safeRequest = request.replace(Regex("[\\r\\n]+"), " ").take(120)
        return when (language) {
            CodingLanguage.KOTLIN -> "${comment}// request: $safeRequest\nfun generatedTask(input: String): String = input"
            CodingLanguage.PYTHON -> "${comment}# request: $safeRequest\ndef generated_task(input_text: str) -> str:\n    return input_text"
            CodingLanguage.JAVASCRIPT -> "${comment}// request: $safeRequest\nconst generatedTask = input => input;"
        }
    }

    private fun sanitizeIdentifier(value: String): String {
        val clean = value.replace(Regex("[^\\p{L}\\p{N}_]"), "_").trim('_')
        val raw = clean.ifBlank { "generatedTask" }
        return if (raw.firstOrNull()?.isDigit() == true) "task_$raw" else raw.replaceFirstChar { it.lowercase() }
    }

    private fun sanitizeTypeName(value: String): String {
        val id = sanitizeIdentifier(value)
        return id.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

/** Small deterministic expression evaluator used by the MATH module. */
private class ExpressionParser(
    private val source: String,
    private val functionsEnabled: Boolean,
) {
    private var p = 0

    fun parse(): Double {
        val v = expression()
        skipSpace()
        require(p == source.length) { "unexpected token at $p" }
        return v
    }

    private fun expression(): Double {
        var v = term()
        while (true) {
            skipSpace()
            v = when {
                take('+') -> v + term()
                take('-') -> v - term()
                else -> return v
            }
        }
    }

    private fun term(): Double {
        var v = power()
        while (true) {
            skipSpace()
            v = when {
                take('*') -> v * power()
                take('/') -> v / power()
                take('%') -> v % power()
                else -> return v
            }
        }
    }

    private fun power(): Double {
        var v = unary()
        skipSpace()
        if (take('^')) v = v.pow(power())
        return v
    }

    private fun unary(): Double {
        skipSpace()
        return when {
            take('+') -> unary()
            take('-') -> -unary()
            else -> primary()
        }
    }

    private fun primary(): Double {
        skipSpace()
        if (take('(')) {
            val v = expression()
            skipSpace()
            require(take(')')) { "missing )" }
            return v
        }
        if (p < source.length && (source[p].isLetter() || source[p] == 'p')) {
            val name = identifier().lowercase()
            if (name == "pi" || name == "p") return PI
            if (name == "e") return E
            require(functionsEnabled) { "functions disabled" }
            skipSpace()
            require(take('(')) { "function requires (" }
            val v = expression()
            skipSpace()
            require(take(')')) { "missing )" }
            return when (name) {
                "sqrt" -> sqrt(v)
                "sin" -> sin(v)
                "cos" -> cos(v)
                "tan" -> tan(v)
                "abs" -> abs(v)
                "ln" -> ln(v)
                "log" -> log10(v)
                "floor" -> floor(v)
                "ceil" -> ceil(v)
                "round" -> round(v)
                else -> error("unknown function $name")
            }
        }
        return number()
    }

    private fun number(): Double {
        skipSpace()
        val start = p
        var dot = false
        while (p < source.length) {
            val ch = source[p]
            if (ch.isDigit()) p++
            else if (ch == '.' && !dot) { dot = true; p++ }
            else break
        }
        require(p > start) { "number expected" }
        return source.substring(start, p).toDouble()
    }

    private fun identifier(): String {
        val start = p
        while (p < source.length && source[p].isLetter()) p++
        return source.substring(start, p)
    }

    private fun take(ch: Char): Boolean {
        if (p < source.length && source[p] == ch) { p++; return true }
        return false
    }

    private fun skipSpace() {
        while (p < source.length && source[p].isWhitespace()) p++
    }
}
