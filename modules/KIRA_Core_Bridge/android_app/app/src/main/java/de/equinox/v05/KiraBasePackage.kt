package de.equinox.v05

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * Kira v0.9.8 — immutable BASE package boundary.
 *
 * Forge Intermediate is a teacher-neutral BUILD format. A .kirabase file is the compact runtime
 * artefact Kira actually mounts. Teacher vectors are intentionally absent: the forge must turn
 * them into grounded surfaces, sparse relations, traits and F→Ω+n macro candidates first.
 */
object KiraBasePackageSpec {
    const val FORMAT = "kira-base-package-v1"
    const val SCHEMA_VERSION = 1
    const val FORGE_SCHEMA_VERSION = 1

    const val MANIFEST = "manifest.json"
    const val CHECKSUMS = "checksums.json"
    const val SURFACES = "surfaces.bin"
    const val MACROS = "macros.bin"
    const val RELATIONS = "relations.bin"
    const val TRAITS = "traits.bin"
    const val INDEX = "index.bin"

    val REQUIRED_ENTRIES = setOf(MANIFEST, CHECKSUMS, SURFACES, MACROS, RELATIONS, TRAITS, INDEX)

    const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 1024L * 1024L * 1024L
    const val MAX_SURFACES = 750_000
    const val MAX_MACROS = 500_000
    const val MAX_RELATIONS = 2_000_000
    const val MAX_TRAITS = 2_000_000
    const val MAX_INDEX_ROWS = 1_000_000

    const val MAGIC_SURFACE = 0x4B425346 // KBSF
    const val MAGIC_MACRO = 0x4B424D41 // KBMA
    const val MAGIC_RELATION = 0x4B425245 // KBRE
    const val MAGIC_TRAIT = 0x4B425452 // KBTR
    const val MAGIC_INDEX = 0x4B424958 // KBIX
    const val BIN_VERSION = 1
}

data class BaseSurfaceRecord(
    val id: Int,
    val externalId: String,
    val modality: String,
    val kind: String,
    val canonical: String,
    val confidence: Double = 1.0,
    val support: Long = 1L,
)

data class BaseSemanticRelationRecord(
    val fromId: Int,
    val toId: Int,
    val kind: String,
    val strength: Double,
    val confidence: Double,
    val polarity: ForgeRelationPolarity = ForgeRelationPolarity.NEUTRAL,
    val evidenceType: String = "explicit",
)

data class BaseTraitRecord(
    val ownerId: Int,
    val key: String,
    val numberValue: Double? = null,
    val textValue: String? = null,
    val confidence: Double = 1.0,
)

data class KiraBasePackageManifest(
    val packageId: String,
    val version: Int,
    val namespace: String,
    val sourceId: String,
    val sourceVersion: String,
    val adapterId: String,
    val adapterVersion: String,
    val sourceModality: String,
    val modelFamily: String = "",
    val revision: String = "",
    val license: String = "",
    val fingerprint: String = "",
    val forgeSchemaVersion: Int = KiraBasePackageSpec.FORGE_SCHEMA_VERSION,
    val createdAtMs: Long = 0L,
    val surfaceCount: Int = 0,
    val macroCount: Int = 0,
    val relationCount: Int = 0,
    val traitCount: Int = 0,
    val indexRows: Int = 0,
    val highestDepth: Int = 0,
    val highestTier: String = "—",
    val payloadDigest: String = "",
)

data class KiraBasePackagePayload(
    val manifest: KiraBasePackageManifest,
    val surfaces: List<BaseSurfaceRecord>,
    val macros: List<IndexedLongTermStore.StoredMacro>,
    val semanticRelations: List<BaseSemanticRelationRecord>,
    val traits: List<BaseTraitRecord>,
    val surfaceIndex: Map<String, List<Int>>,
    val payloadDigest: String,
) {
    fun toCompiledBasePack(): CompiledBasePack {
        val weights = linkedMapOf<Long, Long>()
        semanticRelations.forEach { relation ->
            if (relation.fromId == relation.toId || relation.strength <= 0.0) return@forEach
            val key = pairKey(relation.fromId, relation.toId)
            val weight = max(1L, (relation.strength.coerceIn(0.0, 1.0) * relation.confidence.coerceIn(0.0, 1.0) * 1000.0).toLong())
            weights[key] = max(weights[key] ?: 0L, weight)
        }
        return CompiledBasePack(
            packId = manifest.packageId,
            version = manifest.version,
            sourceId = manifest.sourceId,
            sourceVersion = manifest.sourceVersion,
            macros = macros,
            relations = weights.map { IndexedLongTermStore.StoredRelation(it.key, it.value) },
            namespace = manifest.namespace,
            packageDigest = payloadDigest,
            highestTier = manifest.highestTier,
            surfaces = surfaces,
            semanticRelations = semanticRelations,
            traits = traits,
        )
    }

    companion object {
        private fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)
    }
}

data class KiraBasePackageIssue(
    val severity: Severity,
    val code: String,
    val message: String,
) {
    enum class Severity { WARNING, ERROR }
}

data class KiraBasePackageReport(
    val valid: Boolean,
    val payload: KiraBasePackagePayload?,
    val issues: List<KiraBasePackageIssue>,
) {
    val errors get() = issues.filter { it.severity == KiraBasePackageIssue.Severity.ERROR }
    val warnings get() = issues.filter { it.severity == KiraBasePackageIssue.Severity.WARNING }
}

object KiraBasePackageCodec {
    fun readAndValidate(input: InputStream): KiraBasePackageReport {
        val issues = mutableListOf<KiraBasePackageIssue>()
        fun error(code: String, message: String) { issues += KiraBasePackageIssue(KiraBasePackageIssue.Severity.ERROR, code, message) }
        fun warn(code: String, message: String) { issues += KiraBasePackageIssue(KiraBasePackageIssue.Severity.WARNING, code, message) }

        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (entry.isDirectory) continue
                    if (name.contains("../") || name.startsWith('/')) {
                        error("ZIP_PATH", "Unsicherer ZIP-Pfad '$name'")
                        continue
                    }
                    if (name !in KiraBasePackageSpec.REQUIRED_ENTRIES) {
                        warn("EXTRA_ENTRY", "Unbekannter Package-Eintrag '$name' wird ignoriert")
                        continue
                    }
                    if (name in entries) {
                        error("DUP_ENTRY", "Doppelter Package-Eintrag '$name'")
                        continue
                    }
                    val bytes = readLimited(zip, KiraBasePackageSpec.MAX_ENTRY_BYTES)
                    total += bytes.size
                    if (total > KiraBasePackageSpec.MAX_TOTAL_BYTES) {
                        error("PACKAGE_SIZE", "BASE-Package überschreitet das Größenlimit")
                        break
                    }
                    entries[name] = bytes
                }
            }
        } catch (e: Exception) {
            error("ZIP", "BASE-Package konnte nicht gelesen werden: ${e.message ?: e::class.java.simpleName}")
        }

        val missing = KiraBasePackageSpec.REQUIRED_ENTRIES - entries.keys
        if (missing.isNotEmpty()) error("MISSING", "Fehlende Package-Einträge: ${missing.sorted().joinToString()}")
        if (issues.any { it.severity == KiraBasePackageIssue.Severity.ERROR }) return KiraBasePackageReport(false, null, issues)

        val manifest = runCatching { parseManifest(entries.getValue(KiraBasePackageSpec.MANIFEST)) }
            .getOrElse { error("MANIFEST", "manifest.json ungültig: ${it.message ?: it::class.java.simpleName}"); null }
        val checksumJson = runCatching { JSONObject(entries.getValue(KiraBasePackageSpec.CHECKSUMS).toString(Charsets.UTF_8)) }
            .getOrElse { error("CHECKSUM_JSON", "checksums.json ungültig"); null }
        if (manifest == null || checksumJson == null) return KiraBasePackageReport(false, null, issues)

        val filesJson = checksumJson.optJSONObject("files") ?: JSONObject()
        val payloadEntries = listOf(
            KiraBasePackageSpec.SURFACES,
            KiraBasePackageSpec.MACROS,
            KiraBasePackageSpec.RELATIONS,
            KiraBasePackageSpec.TRAITS,
            KiraBasePackageSpec.INDEX,
        )
        val digestLines = mutableListOf<String>()
        payloadEntries.sorted().forEach { name ->
            val expected = filesJson.optString(name).lowercase()
            val actual = sha256(entries.getValue(name))
            if (expected.isBlank() || expected != actual) error("CHECKSUM", "$name SHA-256 stimmt nicht")
            digestLines += "$name:$actual"
        }
        val payloadDigest = sha256((digestLines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
        val expectedPayloadDigest = checksumJson.optString("payloadDigest").lowercase()
        if (expectedPayloadDigest.isBlank() || expectedPayloadDigest != payloadDigest) error("PAYLOAD_DIGEST", "Payload-Digest stimmt nicht")
        if (manifest.payloadDigest.isNotBlank() && manifest.payloadDigest.lowercase() != payloadDigest) error("MANIFEST_DIGEST", "Manifest verweist auf anderen Payload-Digest")

        val surfaces = decodeSurfaces(entries.getValue(KiraBasePackageSpec.SURFACES), issues)
        val macros = decodeMacros(entries.getValue(KiraBasePackageSpec.MACROS), issues)
        val relations = decodeRelations(entries.getValue(KiraBasePackageSpec.RELATIONS), issues)
        val traits = decodeTraits(entries.getValue(KiraBasePackageSpec.TRAITS), issues)
        val index = decodeIndex(entries.getValue(KiraBasePackageSpec.INDEX), issues)

        validateManifest(manifest, surfaces.size, macros.size, relations.size, traits.size, index.values.sumOf { it.size }, issues)
        validateStructure(surfaces, macros, relations, traits, index, issues)
        if (manifest.forgeSchemaVersion != KiraBasePackageSpec.FORGE_SCHEMA_VERSION) {
            error("FORGE_SCHEMA", "Package wurde nicht aus Forge Intermediate v${KiraBasePackageSpec.FORGE_SCHEMA_VERSION} gebaut")
        }
        if (manifest.packageId.isBlank() || manifest.namespace.isBlank() || manifest.version <= 0) error("IDENTITY", "Package-Identität unvollständig")
        if (manifest.sourceId.isBlank() || manifest.adapterId.isBlank()) error("SOURCE", "Teacher/Adapter-Metadaten unvollständig")
        if (manifest.license.isBlank()) warn("LICENSE", "Teacher-Lizenz ist nicht im Package angegeben")

        val valid = issues.none { it.severity == KiraBasePackageIssue.Severity.ERROR }
        val payload = if (valid) KiraBasePackagePayload(manifest, surfaces, macros, relations, traits, index, payloadDigest) else null
        return KiraBasePackageReport(valid, payload, issues)
    }

    private fun parseManifest(bytes: ByteArray): KiraBasePackageManifest {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optString("format") == KiraBasePackageSpec.FORMAT) { "format muss ${KiraBasePackageSpec.FORMAT} sein" }
        require(root.optInt("schemaVersion", 0) == KiraBasePackageSpec.SCHEMA_VERSION) { "schemaVersion nicht unterstützt" }
        val pack = root.optJSONObject("package") ?: JSONObject()
        val source = root.optJSONObject("source") ?: JSONObject()
        val counts = root.optJSONObject("counts") ?: JSONObject()
        return KiraBasePackageManifest(
            packageId = pack.optString("id").trim(),
            version = pack.optInt("version", 0),
            namespace = pack.optString("namespace").trim(),
            sourceId = source.optString("id").trim(),
            sourceVersion = source.optString("version").trim(),
            adapterId = source.optString("adapterId").trim(),
            adapterVersion = source.optString("adapterVersion").trim(),
            sourceModality = source.optString("modality").trim(),
            modelFamily = source.optString("modelFamily").trim(),
            revision = source.optString("revision").trim(),
            license = source.optString("license").trim(),
            fingerprint = source.optString("fingerprint").trim(),
            forgeSchemaVersion = root.optInt("forgeSchemaVersion", 0),
            createdAtMs = root.optLong("createdAtMs", 0L),
            surfaceCount = counts.optInt("surfaces", 0),
            macroCount = counts.optInt("macros", 0),
            relationCount = counts.optInt("relations", 0),
            traitCount = counts.optInt("traits", 0),
            indexRows = counts.optInt("indexRows", 0),
            highestDepth = root.optInt("highestDepth", 0),
            highestTier = root.optString("highestTier", "—"),
            payloadDigest = root.optString("payloadDigest").lowercase(),
        )
    }

    private fun decodeSurfaces(bytes: ByteArray, issues: MutableList<KiraBasePackageIssue>): List<BaseSurfaceRecord> = decode("SURFACES", bytes, issues) { input ->
        require(input.readInt() == KiraBasePackageSpec.MAGIC_SURFACE)
        require(input.readInt() == KiraBasePackageSpec.BIN_VERSION)
        val count = input.readInt().coerceAtLeast(0)
        require(count <= KiraBasePackageSpec.MAX_SURFACES)
        List(count) {
            BaseSurfaceRecord(
                id = input.readInt(),
                externalId = readString(input),
                modality = readString(input),
                kind = readString(input),
                canonical = readString(input),
                confidence = input.readDouble(),
                support = input.readLong(),
            )
        }
    }

    private fun decodeMacros(bytes: ByteArray, issues: MutableList<KiraBasePackageIssue>): List<IndexedLongTermStore.StoredMacro> = decode("MACROS", bytes, issues) { input ->
        require(input.readInt() == KiraBasePackageSpec.MAGIC_MACRO)
        require(input.readInt() == KiraBasePackageSpec.BIN_VERSION)
        val count = input.readInt().coerceAtLeast(0)
        require(count <= KiraBasePackageSpec.MAX_MACROS)
        List(count) {
            val id = input.readInt()
            val level = input.readInt()
            val modality = readString(input)
            val tokenCount = input.readInt().coerceAtLeast(0)
            require(tokenCount <= ForgeIntermediateSpec.MAX_MEMBERS)
            val tokens = List(tokenCount) { input.readInt() }
            val weight = input.readLong()
            val confidence = input.readDouble()
            require(level > 0)
            require(weight > 0L)
            require(confidence.isFinite() && confidence in 0.0..1.0)
            val externalId = readString(input)
            IndexedLongTermStore.StoredMacro(
                id = id,
                level = level,
                modality = modality,
                tokens = tokens,
                weight = max(1L, (weight.toDouble() * confidence.coerceIn(0.0, 1.0)).toLong()),
                hz = 0.0,
                firstObs = 0L,
                lastObs = 0L,
                signature = "forge|${(level - 1).coerceAtLeast(0)}|$modality|${tokens.joinToString(",")}|$externalId",
            )
        }
    }

    private fun decodeRelations(bytes: ByteArray, issues: MutableList<KiraBasePackageIssue>): List<BaseSemanticRelationRecord> = decode("RELATIONS", bytes, issues) { input ->
        require(input.readInt() == KiraBasePackageSpec.MAGIC_RELATION)
        require(input.readInt() == KiraBasePackageSpec.BIN_VERSION)
        val count = input.readInt().coerceAtLeast(0)
        require(count <= KiraBasePackageSpec.MAX_RELATIONS)
        List(count) {
            val from = input.readInt()
            val to = input.readInt()
            val kind = readString(input)
            val strength = input.readDouble()
            val confidence = input.readDouble()
            val polarity = ForgeRelationPolarity.entries.getOrElse(input.readUnsignedByte()) { ForgeRelationPolarity.NEUTRAL }
            val evidence = readString(input)
            BaseSemanticRelationRecord(from, to, kind, strength, confidence, polarity, evidence)
        }
    }

    private fun decodeTraits(bytes: ByteArray, issues: MutableList<KiraBasePackageIssue>): List<BaseTraitRecord> = decode("TRAITS", bytes, issues) { input ->
        require(input.readInt() == KiraBasePackageSpec.MAGIC_TRAIT)
        require(input.readInt() == KiraBasePackageSpec.BIN_VERSION)
        val count = input.readInt().coerceAtLeast(0)
        require(count <= KiraBasePackageSpec.MAX_TRAITS)
        List(count) {
            val owner = input.readInt()
            val key = readString(input)
            val type = input.readUnsignedByte()
            val number = if (type == 1) input.readDouble() else null
            val text = if (type == 2) readString(input) else null
            val confidence = input.readDouble()
            BaseTraitRecord(owner, key, number, text, confidence)
        }
    }

    private fun decodeIndex(bytes: ByteArray, issues: MutableList<KiraBasePackageIssue>): Map<String, List<Int>> = decode("INDEX", bytes, issues) { input ->
        require(input.readInt() == KiraBasePackageSpec.MAGIC_INDEX)
        require(input.readInt() == KiraBasePackageSpec.BIN_VERSION)
        val count = input.readInt().coerceAtLeast(0)
        require(count <= KiraBasePackageSpec.MAX_INDEX_ROWS)
        val out = linkedMapOf<String, MutableList<Int>>()
        repeat(count) {
            val key = readString(input)
            val id = input.readInt()
            out.getOrPut(key) { mutableListOf() }.add(id)
        }
        out.mapValues { it.value.distinct() }
    }

    private inline fun <T> decode(label: String, bytes: ByteArray, issues: MutableList<KiraBasePackageIssue>, block: (DataInputStream) -> T): T {
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val value = block(input)
                if (input.available() != 0) issues += KiraBasePackageIssue(KiraBasePackageIssue.Severity.WARNING, "${label}_TRAILING", "$label enthält ${input.available()} zusätzliche Bytes")
                value
            }
        } catch (e: Exception) {
            issues += KiraBasePackageIssue(KiraBasePackageIssue.Severity.ERROR, label, "$label beschädigt: ${e.message ?: e::class.java.simpleName}")
            when (label) {
                "INDEX" -> emptyMap<String, List<Int>>() as T
                else -> emptyList<Any>() as T
            }
        }
    }

    private fun validateManifest(
        manifest: KiraBasePackageManifest,
        surfaces: Int,
        macros: Int,
        relations: Int,
        traits: Int,
        indexRows: Int,
        issues: MutableList<KiraBasePackageIssue>,
    ) {
        fun error(code: String, message: String) { issues += KiraBasePackageIssue(KiraBasePackageIssue.Severity.ERROR, code, message) }
        if (manifest.surfaceCount != surfaces) error("COUNT_SURFACE", "Manifest surfaces=${manifest.surfaceCount}, gelesen=$surfaces")
        if (manifest.macroCount != macros) error("COUNT_MACRO", "Manifest macros=${manifest.macroCount}, gelesen=$macros")
        if (manifest.relationCount != relations) error("COUNT_RELATION", "Manifest relations=${manifest.relationCount}, gelesen=$relations")
        if (manifest.traitCount != traits) error("COUNT_TRAIT", "Manifest traits=${manifest.traitCount}, gelesen=$traits")
        if (manifest.indexRows != indexRows) error("COUNT_INDEX", "Manifest indexRows=${manifest.indexRows}, gelesen=$indexRows")
    }

    private fun validateStructure(
        surfaces: List<BaseSurfaceRecord>,
        macros: List<IndexedLongTermStore.StoredMacro>,
        relations: List<BaseSemanticRelationRecord>,
        traits: List<BaseTraitRecord>,
        index: Map<String, List<Int>>,
        issues: MutableList<KiraBasePackageIssue>,
    ) {
        fun error(code: String, message: String) { issues += KiraBasePackageIssue(KiraBasePackageIssue.Severity.ERROR, code, message) }
        fun warn(code: String, message: String) { issues += KiraBasePackageIssue(KiraBasePackageIssue.Severity.WARNING, code, message) }
        val surfaceIds = surfaces.mapTo(linkedSetOf()) { it.id }
        val macroIds = macros.mapTo(linkedSetOf()) { it.id }
        if (surfaceIds.size != surfaces.size) error("SURFACE_ID_DUP", "Doppelte Surface-ID")
        if (macroIds.size != macros.size) error("MACRO_ID_DUP", "Doppelte Macro-ID")
        if (surfaceIds.any { !KnowledgeIdSpace.isBaseId(it) } || macroIds.any { !KnowledgeIdSpace.isBaseId(it) }) error("BASE_ID_RANGE", "Package enthält IDs außerhalb des reservierten BASE-Bereichs")
        if (surfaceIds.any { it in macroIds }) error("ID_OVERLAP", "Surface- und Macro-IDs überlappen")
        surfaces.forEach { s ->
            if (s.externalId.isBlank() || s.modality.isBlank()) error("SURFACE_META", "Surface ${s.id} ohne externe ID/Modalität")
            if (!s.confidence.isFinite() || s.confidence !in 0.0..1.0 || s.support <= 0L) error("SURFACE_SCORE", "Surface ${s.id} hat ungültige Evidenz")
        }
        val macroById = macros.associateBy { it.id }
        macros.forEach { macro ->
            if (macro.level <= 0) error("MACRO_LEVEL", "Macro ${macro.id} level<=0")
            if (macro.tokens.size < 2) warn("MACRO_SMALL", "Macro ${macro.id} hat weniger als zwei members")
            if (macro.level == 1) {
                macro.tokens.forEach { if (it !in surfaceIds) error("MACRO_SURFACE_REF", "F-Macro ${macro.id} referenziert unbekannte Surface $it") }
            } else {
                macro.tokens.forEach { childId ->
                    val child = macroById[childId]
                    if (child == null) error("MACRO_REF", "Macro ${macro.id} referenziert unbekanntes Macro $childId")
                    else if (KiraModalityClassifier.familyOf(macro.modality) != KiraModalityFamily.CROSSMODAL && child.level != macro.level - 1) {
                        error("TIER_GAP", "Macro ${macro.id} (${macro.level}) referenziert ${child.id} (${child.level})")
                    } else if (child.level >= macro.level) error("MACRO_CYCLE_LEVEL", "Macro ${macro.id} referenziert gleiches/höheres Tier")
                }
            }
        }
        val allIds = surfaceIds + macroIds
        relations.forEachIndexed { i, r ->
            if (r.fromId !in allIds || r.toId !in allIds) error("RELATION_REF", "Relation[$i] referenziert unbekannte ID")
            if (!r.strength.isFinite() || r.strength !in 0.0..1.0 || !r.confidence.isFinite() || r.confidence !in 0.0..1.0) error("RELATION_SCORE", "Relation[$i] hat ungültige Werte")
            if (r.evidenceType.equals("embedding_similarity", true) && r.polarity == ForgeRelationPolarity.OPPOSE) error("EMBEDDING_ANTITHESIS", "Embedding-Similarity darf keine Antithese sein")
        }
        traits.forEachIndexed { i, t ->
            if (t.ownerId !in allIds) error("TRAIT_REF", "Trait[$i] owner unbekannt")
            if (t.key.isBlank()) error("TRAIT_KEY", "Trait[$i] ohne key")
            if ((t.numberValue == null) == (t.textValue == null)) error("TRAIT_VALUE", "Trait[$i] benötigt genau einen Wert")
            if (t.numberValue != null && !t.numberValue.isFinite()) error("TRAIT_NUMBER", "Trait[$i] NaN/Infinity")
            if (!t.confidence.isFinite() || t.confidence !in 0.0..1.0) error("TRAIT_CONFIDENCE", "Trait[$i] confidence außerhalb 0..1")
        }
        index.forEach { (key, ids) ->
            if (key != normalizeSurface(key)) warn("INDEX_NORMALIZE", "Index-Key '$key' ist nicht normalisiert")
            ids.forEach { if (it !in surfaceIds) error("INDEX_REF", "Index '$key' referenziert unbekannte Surface $it") }
        }
    }

    fun normalizeSurface(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            total += n
            require(total <= limit) { "ZIP-Eintrag überschreitet Größenlimit" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun readString(input: DataInputStream): String {
        val len = input.readInt()
        require(len in 0..16_777_216) { "Ungültige String-Länge $len" }
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}
