package de.equinox.v05

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Global immutable BASE layer.
 *
 * v0.9.8 moves the layer to active/previous package directories. Installation is staged and
 * swapped as one directory, LIVE/PERSONAL memory is never rewritten, and the previous BASE is
 * retained for an explicit rollback. The old v0.9.7 root/store layout is migrated once.
 */
class BaseKnowledgeRepository(private val context: Context) {
    data class Manifest(
        val packId: String = "",
        val version: Int = 0,
        val stableIds: Boolean = true,
        val sourceId: String = "",
        val sourceVersion: String = "",
        val macroCount: Int = 0,
        val relationCount: Int = 0,
        val importedAtMs: Long = 0L,
        val namespace: String = "",
        val packageDigest: String = "",
        val highestTier: String = "—",
        val surfaceCount: Int = 0,
        val semanticRelationCount: Int = 0,
        val traitCount: Int = 0,
    ) {
        val installed: Boolean get() = packId.isNotBlank() && version > 0
    }

    data class InstallResult(
        val success: Boolean,
        val manifest: Manifest = Manifest(),
        val message: String = "",
    )

    data class BaseSurfaceHit(
        val id: Int,
        val canonical: String,
        val modality: String,
        val kind: String,
    )

    private val root = File(context.filesDir, ROOT_DIR).apply { mkdirs() }
    private val activeDir = File(root, ACTIVE_DIR)
    private val previousDir = File(root, PREVIOUS_DIR)
    private val stageDir = File(root, STAGE_DIR)
    private val swapDir = File(root, SWAP_DIR)
    private val markerFile = File(root, INFLIGHT_FILE)
    private val disabledFlag = File(root, DISABLED_FLAG)

    init {
        migrateLegacyLayout()
        recoverInterruptedInstall()
    }

    /** Public indexed store mounted by MemoryModel. */
    val store = IndexedLongTermStore(context, STORE_DIR)

    @Volatile private var surfaceIndexCache: Map<String, List<BaseSurfaceHit>>? = null

    fun isEnabled(): Boolean = !disabledFlag.exists()

    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (enabled) disabledFlag.delete() else disabledFlag.writeText("blank-mode")
        true
    }.getOrDefault(false)

    fun manifest(): Manifest = readManifest(activeDir)
    fun previousManifest(): Manifest = readManifest(previousDir)
    fun canRollback(): Boolean = previousManifest().installed && File(previousDir, STORE_SUBDIR).exists()

    fun surfaceMatches(text: String, limit: Int = 8): List<BaseSurfaceHit> {
        if (!isEnabled()) return emptyList()
        val key = KiraBasePackageCodec.normalizeSurface(text)
        if (key.isBlank()) return emptyList()
        val index = surfaceIndexCache ?: synchronized(this) {
            surfaceIndexCache ?: readSurfaceIndex(activeDir).also { surfaceIndexCache = it }
        }
        return index[key].orEmpty().take(limit.coerceIn(1, 32))
    }

    fun semanticRelations(limit: Int = Int.MAX_VALUE): List<BaseSemanticRelationRecord> =
        readSemanticRelations(activeDir).take(limit.coerceAtLeast(0))

    fun traits(limit: Int = Int.MAX_VALUE): List<BaseTraitRecord> =
        readTraits(activeDir).take(limit.coerceAtLeast(0))

    fun installPackage(payload: KiraBasePackagePayload): InstallResult = install(payload.toCompiledBasePack())

    /**
     * Transactional staged install. The active package directory is never edited in place.
     * A completed previous package is retained for explicit rollback.
     */
    @Synchronized
    fun install(pack: CompiledBasePack): InstallResult {
        val validation = validateCompiledPack(pack)
        if (validation != null) return InstallResult(false, manifest(), validation)
        val current = manifest()
        if (current.installed && current.packId == pack.packId && pack.version < current.version) {
            return InstallResult(false, current, "Downgrade blockiert: installiert ist ${current.packId} v${current.version}")
        }

        stageDir.deleteRecursively()
        stageDir.mkdirs()
        val stageStore = IndexedLongTermStore(context, "$ROOT_DIR/$STAGE_DIR/$STORE_SUBDIR")
        stageStore.deleteAll()
        val stageResult = runCatching {
            stageStore.writeSnapshot(
                meta = IndexedLongTermStore.Meta(0L, KnowledgeIdSpace.BASE_MACRO_MIN, true),
                quants = emptyList(),
                macros = pack.macros,
                relations = pack.relations,
                exchanges = emptyList(),
            )
        }.getOrElse {
            stageDir.deleteRecursively()
            return InstallResult(false, current, "Staging fehlgeschlagen: ${it.message ?: it::class.java.simpleName}")
        }

        if (!validateStageStore(stageStore, pack)) {
            stageDir.deleteRecursively()
            return InstallResult(false, current, "Staging-Validierung fehlgeschlagen")
        }
        if (!writePackageSidecars(stageDir, pack)) {
            stageDir.deleteRecursively()
            return InstallResult(false, current, "BASE-Sidecars konnten nicht geschrieben werden")
        }
        if (!writeManifest(stageDir, pack)) {
            stageDir.deleteRecursively()
            return InstallResult(false, current, "Manifest konnte nicht geschrieben werden")
        }
        val staged = readManifest(stageDir)
        if (!staged.installed || staged.macroCount != pack.macros.size || staged.surfaceCount != pack.surfaces.size) {
            stageDir.deleteRecursively()
            return InstallResult(false, current, "Staging-Manifest konnte nicht rückgelesen werden")
        }

        markerFile.writeText("install:${pack.packId}:${pack.version}")
        swapDir.deleteRecursively()
        return try {
            // Keep exactly one known-good predecessor.
            previousDir.deleteRecursively()
            if (activeDir.exists() && !activeDir.renameTo(previousDir)) error("Bestehende BASE konnte nicht gesichert werden")
            if (!stageDir.renameTo(activeDir)) error("Neue BASE konnte nicht aktiviert werden")
            markerFile.delete()
            surfaceIndexCache = null
            val installed = manifest()
            InstallResult(
                true,
                installed,
                "${installed.packId} v${installed.version} installiert · ${installed.surfaceCount} surfaces · ${installed.macroCount} Makros",
            )
        } catch (e: Exception) {
            activeDir.deleteRecursively()
            if (previousDir.exists()) previousDir.renameTo(activeDir)
            stageDir.deleteRecursively()
            markerFile.delete()
            surfaceIndexCache = null
            InstallResult(false, manifest(), "Install fehlgeschlagen: ${e.message ?: e::class.java.simpleName}")
        } finally {
            if (stageResult.bytes < 0L) stageDir.deleteRecursively()
        }
    }

    /** Swap active and previous packages. LIVE/PERSONAL files are outside this tree. */
    @Synchronized
    fun rollback(): InstallResult {
        if (!canRollback()) return InstallResult(false, manifest(), "Kein vorheriges BASE-Paket vorhanden")
        markerFile.writeText("rollback")
        swapDir.deleteRecursively()
        return try {
            if (activeDir.exists() && !activeDir.renameTo(swapDir)) error("Aktive BASE konnte nicht geparkt werden")
            if (!previousDir.renameTo(activeDir)) error("Vorherige BASE konnte nicht aktiviert werden")
            if (swapDir.exists() && !swapDir.renameTo(previousDir)) error("Aktuelle BASE konnte nicht als Rollback-Ziel gesichert werden")
            markerFile.delete()
            surfaceIndexCache = null
            val active = manifest()
            InstallResult(true, active, "Rollback aktiv: ${active.packId} v${active.version}")
        } catch (e: Exception) {
            // Best-effort reversal of every partial swap state.
            if (swapDir.exists()) {
                if (activeDir.exists() && !previousDir.exists()) activeDir.renameTo(previousDir)
                activeDir.deleteRecursively()
                swapDir.renameTo(activeDir)
            }
            markerFile.delete()
            surfaceIndexCache = null
            InstallResult(false, manifest(), "Rollback fehlgeschlagen: ${e.message ?: e::class.java.simpleName}")
        }
    }

    /** Remove the active BASE but keep it as the one-step rollback target. */
    @Synchronized
    fun removeActive(): InstallResult {
        val current = manifest()
        if (!current.installed) return InstallResult(true, current, "Keine BASE installiert")
        markerFile.writeText("remove")
        return try {
            previousDir.deleteRecursively()
            if (!activeDir.renameTo(previousDir)) error("BASE konnte nicht ausgehängt werden")
            markerFile.delete()
            surfaceIndexCache = null
            InstallResult(true, Manifest(), "BASE entfernt · LIVE/PERSONAL unverändert")
        } catch (e: Exception) {
            markerFile.delete()
            InstallResult(false, manifest(), "BASE entfernen fehlgeschlagen: ${e.message ?: e::class.java.simpleName}")
        }
    }

    private fun validateCompiledPack(pack: CompiledBasePack): String? {
        if (pack.packId.isBlank() || pack.version <= 0) return "Ungültige BASE-Metadaten"
        if (pack.macros.isEmpty() && pack.surfaces.isEmpty()) return "BASE enthält weder Surfaces noch Makros"
        if (pack.macros.any { !KnowledgeIdSpace.isBaseId(it.id) }) return "BASE enthält Macro-IDs außerhalb des reservierten Bereichs"
        if (pack.surfaces.any { !KnowledgeIdSpace.isBaseId(it.id) }) return "BASE enthält Surface-IDs außerhalb des reservierten Bereichs"
        if (pack.macros.map { it.id }.toSet().size != pack.macros.size) return "BASE enthält doppelte Macro-IDs"
        if (pack.surfaces.map { it.id }.toSet().size != pack.surfaces.size) return "BASE enthält doppelte Surface-IDs"
        val surfaceIds = pack.surfaces.mapTo(HashSet()) { it.id }
        val macroById = pack.macros.associateBy { it.id }
        if (surfaceIds.any { it in macroById }) return "BASE Surface/Macro-ID-Kollision"
        if (pack.surfaces.isNotEmpty()) {
            for (macro in pack.macros) {
                if (macro.level <= 0) return "BASE enthält ungültiges Macro-Level"
                if (macro.level == 1 && macro.tokens.any { it !in surfaceIds }) return "F-Macro enthält unaufgelöste Surface-Referenz"
                if (macro.level > 1) {
                    for (token in macro.tokens) {
                        val child = macroById[token] ?: return "Höheres Macro enthält unaufgelöste Referenz"
                        val cross = KiraModalityClassifier.familyOf(macro.modality) == KiraModalityFamily.CROSSMODAL
                        if (!cross && child.level != macro.level - 1) return "BASE enthält einen Tier-Sprung"
                        if (child.level >= macro.level) return "BASE enthält zyklische/ungültige Macro-Tiefe"
                    }
                }
            }
            val allIds = surfaceIds + macroById.keys
            if (pack.semanticRelations.any { it.fromId !in allIds || it.toId !in allIds }) return "BASE enthält unaufgelöste semantische Relation"
            if (pack.traits.any { it.ownerId !in allIds }) return "BASE enthält unaufgelöstes Trait"
        } else {
            // Legacy converter: level-1 tokens are grounded native sensor IDs, not package surfaces.
            val ids = macroById.keys
            if (pack.macros.any { macro -> macro.level > 1 && macro.tokens.any { it !in ids } }) return "Legacy BASE enthält unaufgelöste höhere Macro-Referenz"
        }
        return null
    }

    private fun validateStageStore(stageStore: IndexedLongTermStore, pack: CompiledBasePack): Boolean {
        val headers = stageStore.readMacroHeaders()
        if (headers.size != pack.macros.size || stageStore.readRelations().size != pack.relations.size) return false
        return runCatching {
            headers.forEach { h ->
                val tokens = stageStore.tokenList(h.tokenOffsetBytes, h.tokenCount)
                for (i in tokens.indices) tokens[i]
            }
            true
        }.getOrDefault(false)
    }

    private fun writeManifest(dir: File, pack: CompiledBasePack): Boolean = runCatching {
        dir.mkdirs()
        val file = File(dir, MANIFEST_FILE)
        val temp = File(dir, "$MANIFEST_FILE.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeUTF(pack.packId.take(128))
            out.writeInt(pack.version)
            out.writeBoolean(true)
            out.writeUTF(pack.sourceId.take(256))
            out.writeUTF(pack.sourceVersion.take(128))
            out.writeInt(pack.macros.size)
            out.writeInt(pack.relations.size)
            out.writeLong(System.currentTimeMillis())
            out.writeUTF(pack.namespace.take(256))
            out.writeUTF(pack.packageDigest.take(128))
            out.writeUTF(pack.highestTier.take(32))
            out.writeInt(pack.surfaces.size)
            out.writeInt(pack.semanticRelations.size)
            out.writeInt(pack.traits.size)
        }
        if (file.exists()) file.delete()
        require(temp.renameTo(file))
        true
    }.getOrDefault(false)

    private fun readManifest(dir: File): Manifest {
        val file = File(dir, MANIFEST_FILE)
        if (!file.exists()) return Manifest()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                require(input.readInt() == MAGIC)
                val format = input.readInt()
                require(format in 1..FORMAT_VERSION)
                val packId = input.readUTF()
                val version = input.readInt()
                val stableIds = input.readBoolean()
                if (format == 1) return@use Manifest(packId, version, stableIds)
                val sourceId = input.readUTF()
                val sourceVersion = input.readUTF()
                val macroCount = input.readInt().coerceAtLeast(0)
                val relationCount = input.readInt().coerceAtLeast(0)
                val importedAt = input.readLong().coerceAtLeast(0L)
                if (format == 2) return@use Manifest(packId, version, stableIds, sourceId, sourceVersion, macroCount, relationCount, importedAt)
                Manifest(
                    packId = packId,
                    version = version,
                    stableIds = stableIds,
                    sourceId = sourceId,
                    sourceVersion = sourceVersion,
                    macroCount = macroCount,
                    relationCount = relationCount,
                    importedAtMs = importedAt,
                    namespace = input.readUTF(),
                    packageDigest = input.readUTF(),
                    highestTier = input.readUTF(),
                    surfaceCount = input.readInt().coerceAtLeast(0),
                    semanticRelationCount = input.readInt().coerceAtLeast(0),
                    traitCount = input.readInt().coerceAtLeast(0),
                )
            }
        }.getOrElse { Manifest() }
    }

    private fun writePackageSidecars(dir: File, pack: CompiledBasePack): Boolean = runCatching {
        dir.mkdirs()
        writeSurfaces(File(dir, SURFACE_FILE), pack.surfaces)
        writeSemanticRelations(File(dir, SEMANTIC_RELATION_FILE), pack.semanticRelations)
        writeTraits(File(dir, TRAIT_FILE), pack.traits)
        writeSurfaceIndex(File(dir, SURFACE_INDEX_FILE), pack.surfaces)
        true
    }.getOrDefault(false)

    private fun writeSurfaces(file: File, rows: List<BaseSurfaceRecord>) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(SIDECAR_MAGIC_SURFACE); out.writeInt(SIDECAR_VERSION); out.writeInt(rows.size)
            rows.forEach { r ->
                out.writeInt(r.id); out.writeUTF(r.externalId.take(2048)); out.writeUTF(r.modality.take(128)); out.writeUTF(r.kind.take(128));
                out.writeUTF(r.canonical.take(16_000)); out.writeDouble(r.confidence); out.writeLong(r.support)
            }
        }
    }

    private fun writeSemanticRelations(file: File, rows: List<BaseSemanticRelationRecord>) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(SIDECAR_MAGIC_RELATION); out.writeInt(SIDECAR_VERSION); out.writeInt(rows.size)
            rows.forEach { r ->
                out.writeInt(r.fromId); out.writeInt(r.toId); out.writeUTF(r.kind.take(256)); out.writeDouble(r.strength); out.writeDouble(r.confidence)
                out.writeByte(r.polarity.ordinal); out.writeUTF(r.evidenceType.take(256))
            }
        }
    }

    private fun writeTraits(file: File, rows: List<BaseTraitRecord>) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(SIDECAR_MAGIC_TRAIT); out.writeInt(SIDECAR_VERSION); out.writeInt(rows.size)
            rows.forEach { r ->
                out.writeInt(r.ownerId); out.writeUTF(r.key.take(512))
                when {
                    r.numberValue != null -> { out.writeByte(1); out.writeDouble(r.numberValue) }
                    r.textValue != null -> { out.writeByte(2); out.writeUTF(r.textValue.take(16_000)) }
                    else -> out.writeByte(0)
                }
                out.writeDouble(r.confidence)
            }
        }
    }

    private fun writeSurfaceIndex(file: File, rows: List<BaseSurfaceRecord>) {
        val indexed = rows.asSequence()
            .filter { it.canonical.isNotBlank() }
            .map { KiraBasePackageCodec.normalizeSurface(it.canonical) to it }
            .filter { it.first.isNotBlank() }
            .sortedWith(compareBy<Pair<String, BaseSurfaceRecord>> { it.first }.thenBy { it.second.id })
            .toList()
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(SIDECAR_MAGIC_INDEX); out.writeInt(SIDECAR_VERSION); out.writeInt(indexed.size)
            indexed.forEach { (key, r) ->
                out.writeUTF(key.take(16_000)); out.writeInt(r.id); out.writeUTF(r.canonical.take(16_000)); out.writeUTF(r.modality.take(128)); out.writeUTF(r.kind.take(128))
            }
        }
    }

    private fun readSurfaceIndex(dir: File): Map<String, List<BaseSurfaceHit>> {
        val file = File(dir, SURFACE_INDEX_FILE)
        if (!file.exists()) return emptyMap()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                require(input.readInt() == SIDECAR_MAGIC_INDEX); require(input.readInt() == SIDECAR_VERSION)
                val count = input.readInt().coerceIn(0, KiraBasePackageSpec.MAX_INDEX_ROWS)
                val out = linkedMapOf<String, MutableList<BaseSurfaceHit>>()
                repeat(count) {
                    val key = input.readUTF(); val id = input.readInt(); val canonical = input.readUTF(); val modality = input.readUTF(); val kind = input.readUTF()
                    out.getOrPut(key) { mutableListOf() }.add(BaseSurfaceHit(id, canonical, modality, kind))
                }
                out.mapValues { it.value.toList() }
            }
        }.getOrElse { emptyMap() }
    }

    private fun readSemanticRelations(dir: File): List<BaseSemanticRelationRecord> {
        val file = File(dir, SEMANTIC_RELATION_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                require(input.readInt() == SIDECAR_MAGIC_RELATION); require(input.readInt() == SIDECAR_VERSION)
                val count = input.readInt().coerceIn(0, KiraBasePackageSpec.MAX_RELATIONS)
                List(count) {
                    BaseSemanticRelationRecord(
                        input.readInt(), input.readInt(), input.readUTF(), input.readDouble(), input.readDouble(),
                        ForgeRelationPolarity.entries.getOrElse(input.readUnsignedByte()) { ForgeRelationPolarity.NEUTRAL }, input.readUTF(),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun readTraits(dir: File): List<BaseTraitRecord> {
        val file = File(dir, TRAIT_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                require(input.readInt() == SIDECAR_MAGIC_TRAIT); require(input.readInt() == SIDECAR_VERSION)
                val count = input.readInt().coerceIn(0, KiraBasePackageSpec.MAX_TRAITS)
                List(count) {
                    val owner = input.readInt(); val key = input.readUTF(); val type = input.readUnsignedByte()
                    val number = if (type == 1) input.readDouble() else null
                    val text = if (type == 2) input.readUTF() else null
                    val confidence = input.readDouble()
                    BaseTraitRecord(owner, key, number, text, confidence)
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun migrateLegacyLayout() {
        if (activeDir.exists()) return
        val legacyStore = File(root, "store")
        val legacyManifest = File(root, MANIFEST_FILE)
        if (!legacyStore.exists() && !legacyManifest.exists()) return
        activeDir.mkdirs()
        if (legacyStore.exists()) legacyStore.renameTo(File(activeDir, STORE_SUBDIR))
        if (legacyManifest.exists()) legacyManifest.renameTo(File(activeDir, MANIFEST_FILE))
        listOf(SURFACE_FILE, SEMANTIC_RELATION_FILE, TRAIT_FILE, SURFACE_INDEX_FILE).forEach { name ->
            val old = File(root, name)
            if (old.exists()) old.renameTo(File(activeDir, name))
        }
    }

    private fun recoverInterruptedInstall() {
        if (!markerFile.exists()) {
            stageDir.deleteRecursively()
            swapDir.deleteRecursively()
            return
        }
        val activeManifest = readManifest(activeDir)
        if (!activeManifest.installed && previousDir.exists()) {
            activeDir.deleteRecursively()
            previousDir.renameTo(activeDir)
        } else if (!activeManifest.installed && swapDir.exists()) {
            activeDir.deleteRecursively()
            swapDir.renameTo(activeDir)
        }
        stageDir.deleteRecursively()
        swapDir.deleteRecursively()
        markerFile.delete()
    }

    companion object {
        const val ROOT_DIR = "equinox_v06_base"
        const val ACTIVE_DIR = "active"
        const val PREVIOUS_DIR = "previous"
        const val STAGE_DIR = "stage"
        const val SWAP_DIR = "swap"
        const val STORE_SUBDIR = "store"
        const val STORE_DIR = "$ROOT_DIR/$ACTIVE_DIR/$STORE_SUBDIR"

        private const val MANIFEST_FILE = "manifest.bin"
        private const val SURFACE_FILE = "surfaces.bin"
        private const val SEMANTIC_RELATION_FILE = "semantic_relations.bin"
        private const val TRAIT_FILE = "traits.bin"
        private const val SURFACE_INDEX_FILE = "surface_index.bin"
        private const val DISABLED_FLAG = "disabled.flag"
        private const val INFLIGHT_FILE = "install.inflight"

        private const val MAGIC = 0x4551424D // EQBM
        private const val FORMAT_VERSION = 3
        private const val SIDECAR_VERSION = 1
        private const val SIDECAR_MAGIC_SURFACE = 0x45515346 // EQSF
        private const val SIDECAR_MAGIC_RELATION = 0x45515253 // EQRS
        private const val SIDECAR_MAGIC_TRAIT = 0x45515452 // EQTR
        private const val SIDECAR_MAGIC_INDEX = 0x45514958 // EQIX
    }
}
