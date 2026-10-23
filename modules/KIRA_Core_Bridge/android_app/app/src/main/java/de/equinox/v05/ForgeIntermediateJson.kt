package de.equinox.v05

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/** Android JSON adapter for the pure Forge Intermediate v1 contract. */
object ForgeIntermediateJson {
    data class ParseResult(
        val document: ForgeIntermediateDocument?,
        val plan: ForgeCompilationPlan?,
        val parserIssues: List<ForgeValidationIssue> = emptyList(),
    ) {
        val valid: Boolean get() = document != null && plan?.valid == true && parserIssues.none { it.severity == ForgeValidationIssue.Severity.ERROR }
        val issues: List<ForgeValidationIssue> get() = parserIssues + (plan?.issues ?: emptyList())
    }

    fun parseAndValidate(input: InputStream): ParseResult = runCatching {
        input.bufferedReader().use { parseAndValidate(it.readText()) }
    }.getOrElse { failure("READ", "Datei konnte nicht gelesen werden: ${it.message ?: it::class.java.simpleName}") }

    fun parseAndValidate(text: String): ParseResult {
        val root = try { JSONObject(text) } catch (e: Exception) {
            return failure("JSON", "Ungültiges JSON: ${e.message ?: "Parserfehler"}")
        }
        return runCatching {
            val packageJson = root.optJSONObject("package") ?: JSONObject()
            val sourceJson = root.optJSONObject("source") ?: JSONObject()
            val document = ForgeIntermediateDocument(
                format = root.optString("format"),
                schemaVersion = root.optInt("schemaVersion", 0),
                packageInfo = ForgePackageDescriptor(
                    id = packageJson.optString("id").trim(),
                    version = packageJson.optInt("version", 0),
                    namespace = packageJson.optString("namespace").trim(),
                ),
                source = ForgeSourceDescriptor(
                    id = sourceJson.optString("id").trim(),
                    version = sourceJson.optString("version").trim(),
                    adapterId = sourceJson.optString("adapterId").trim(),
                    adapterVersion = sourceJson.optString("adapterVersion").trim(),
                    modality = sourceJson.optString("modality").trim(),
                    modelFamily = sourceJson.optString("modelFamily").trim(),
                    revision = sourceJson.optString("revision").trim(),
                    license = sourceJson.optString("license").trim(),
                    fingerprint = sourceJson.optString("fingerprint").trim(),
                ),
                atoms = parseAtoms(root.optJSONArray("atoms")),
                vectors = parseVectors(root.optJSONArray("vectors")),
                relations = parseRelations(root.optJSONArray("relations")),
                sequences = parseSequences(root.optJSONArray("sequences")),
                macroCandidates = parseMacros(root.optJSONArray("macroCandidates")),
                traits = parseTraits(root.optJSONArray("traits")),
            )
            ParseResult(document, ForgeIntermediateCompiler.validateAndPlan(document))
        }.getOrElse { failure("PARSE", "Forge-v1 Dokument konnte nicht gelesen werden: ${it.message ?: it::class.java.simpleName}") }
    }

    private fun parseAtoms(rows: JSONArray?): List<ForgeAtom> = objects(rows).map { o ->
        ForgeAtom(
            id = o.optString("id").trim(), modality = o.optString("modality").trim(),
            kind = enumValue(o.optString("kind"), ForgeAtomKind.OTHER), canonical = o.optString("canonical"),
            stableKey = o.optString("stableKey").trim(), confidence = finite(o, "confidence", 1.0),
            support = o.optLong("support", 1L), sourceKey = o.optString("sourceKey").trim(),
        )
    }

    private fun parseVectors(rows: JSONArray?): List<ForgeVector> = objects(rows).map { o ->
        val values = o.optJSONArray("values") ?: JSONArray()
        ForgeVector(
            id = o.optString("id").trim(), ownerId = o.optString("ownerId").trim(), space = o.optString("space").trim(),
            values = DoubleArray(values.length()) { i -> values.optDouble(i, Double.NaN) },
        )
    }

    private fun parseRelations(rows: JSONArray?): List<ForgeRelation> = objects(rows).map { o ->
        ForgeRelation(
            fromId = o.optString("from").trim(), toId = o.optString("to").trim(), kind = o.optString("kind").trim(),
            strength = finite(o, "strength", Double.NaN), confidence = finite(o, "confidence", 1.0),
            polarity = enumValue(o.optString("polarity"), ForgeRelationPolarity.NEUTRAL),
            direction = enumValue(o.optString("direction"), ForgeRelationDirection.DIRECTED),
            evidenceType = o.optString("evidenceType", "explicit").trim(), sourceKey = o.optString("sourceKey").trim(),
        )
    }

    private fun parseSequences(rows: JSONArray?): List<ForgeSequence> = objects(rows).map { o ->
        ForgeSequence(
            id = o.optString("id").trim(), modality = o.optString("modality").trim(), members = strings(o.optJSONArray("members")),
            stableKey = o.optString("stableKey").trim(), confidence = finite(o, "confidence", 1.0),
            support = o.optLong("support", 1L), sourceKey = o.optString("sourceKey").trim(),
        )
    }

    private fun parseMacros(rows: JSONArray?): List<ForgeMacroCandidate> = objects(rows).map { o ->
        ForgeMacroCandidate(
            id = o.optString("id").trim(), modality = o.optString("modality").trim(), members = strings(o.optJSONArray("members")),
            stableKey = o.optString("stableKey").trim(), support = o.optLong("support", 0L),
            coherence = finite(o, "coherence", 1.0), recurrence = finite(o, "recurrence", 1.0),
            confidence = finite(o, "confidence", 1.0), sourceKey = o.optString("sourceKey").trim(),
        )
    }

    private fun parseTraits(rows: JSONArray?): List<ForgeTrait> = objects(rows).map { o ->
        ForgeTrait(
            ownerId = o.optString("ownerId").trim(), key = o.optString("key").trim(),
            numberValue = if (o.has("number")) o.optDouble("number", Double.NaN) else null,
            textValue = if (o.has("text")) o.optString("text") else null,
            confidence = finite(o, "confidence", 1.0), sourceKey = o.optString("sourceKey").trim(),
        )
    }

    private fun objects(rows: JSONArray?): List<JSONObject> {
        if (rows == null) return emptyList()
        return List(rows.length()) { i -> rows.optJSONObject(i) ?: JSONObject() }
    }

    private fun strings(rows: JSONArray?): List<String> {
        if (rows == null) return emptyList()
        return List(rows.length()) { i -> rows.optString(i).trim() }.filter { it.isNotBlank() }
    }

    private fun finite(o: JSONObject, key: String, fallback: Double): Double {
        val value = o.optDouble(key, fallback)
        return if (value.isFinite()) value else value
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: fallback

    private fun failure(code: String, message: String) = ParseResult(
        document = null,
        plan = null,
        parserIssues = listOf(ForgeValidationIssue(ForgeValidationIssue.Severity.ERROR, code, message)),
    )
}
