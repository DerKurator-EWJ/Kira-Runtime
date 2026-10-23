package de.equinox.v05

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Step 23: compact indexed on-device store.
 *
 * The persistent graph is split into small typed files instead of one monolithic JSON blob.
 * Macro headers are indexed at startup, while their token payload stays in tokens.bin and is
 * fetched lazily through [DiskTokenList]. No external database/library is required.
 *
 * This is intentionally a storage concern only: it does not alter learning, rank, recall,
 * prediction, DREAM, cross-modal semantics or overexchange math.
 */
class IndexedLongTermStore(context: Context, storeDirName: String = STORE_DIR) {
    data class Meta(
        val observations: Long,
        val nextMacroId: Int,
        val macroFrozen: Boolean
    )

    data class StoredQuant(
        val id: Int,
        val weight: Long,
        val hz: Double,
        val modality: String,
        val displayColor: Int
    )

    data class StoredMacro(
        val id: Int,
        val level: Int,
        val modality: String,
        val tokens: List<Int>,
        val weight: Long,
        val hz: Double,
        val firstObs: Long,
        val lastObs: Long,
        val signature: String
    )

    data class MacroHeader(
        val id: Int,
        val level: Int,
        val modality: String,
        val weight: Long,
        val hz: Double,
        val firstObs: Long,
        val lastObs: Long,
        val tokenOffsetBytes: Long,
        val tokenCount: Int,
        val signature: String
    )

    data class StoredRelation(val key: Long, val weight: Long)
    data class StoredExchange(val key: Long, val ema: Double, val samples: Long)

    /** v0.9.2: evidence below macro-promotion is persistent learning state, not scratch RAM. */
    data class StoredCandidate(
        val streamLevel: Int,
        val modality: String,
        val tokens: List<Int>,
        val hits: Long,
        val firstSeenStreamPosition: Long,
        val lastSeenStreamPosition: Long
    )

    /** Per-stream clocks keep candidate ageing/non-overlap continuous across app restarts. */
    data class StoredStreamState(
        val key: String,
        val position: Long,
        val coveredUntil: Long,
        val lastAutoPromotionAt: Long
    )

    data class StoredCrossModalCandidate(val audioMacroId: Int, val visualMacroId: Int, val hits: Long)
    data class StoredMindTriple(val subject: Long, val relation: Long, val objectId: Long)
    data class StoredTextMemory(
        val normalized: String,
        val surface: String,
        val hits: Long,
        val ordinal: Long
    )
    data class MacroLocation(val offsetBytes: Long, val tokenCount: Int)
    data class WriteResult(val macroLocations: Map<Int, MacroLocation>, val bytes: Long)
    data class StoreStats(
        val exists: Boolean,
        val bytes: Long,
        val macroHeaders: Int,
        val tokenReads: Long
    )

    private val root = File(context.filesDir, storeDirName)
    private val metaFile get() = File(root, META_FILE)
    private val quantFile get() = File(root, QUANT_FILE)
    private val macroFile get() = File(root, MACRO_FILE)
    private val tokenFile get() = File(root, TOKEN_FILE)
    private val relationFile get() = File(root, RELATION_FILE)
    private val exchangeFile get() = File(root, EXCHANGE_FILE)
    private val candidateFile get() = File(root, CANDIDATE_FILE)
    private val streamStateFile get() = File(root, STREAM_STATE_FILE)
    private val crossModalCandidateFile get() = File(root, CROSSMODAL_CANDIDATE_FILE)
    private val mindTripleFile get() = File(root, MIND_TRIPLE_FILE)
    private val textMemoryFile get() = File(root, TEXT_MEMORY_FILE)
    private val tokenReadCounter = AtomicLong(0L)

    fun exists(): Boolean = metaFile.exists() && macroFile.exists() && tokenFile.exists()

    fun rootDirectory(): File = root

    fun stats(): StoreStats = StoreStats(
        exists = exists(),
        bytes = directoryBytes(root),
        macroHeaders = runCatching { macroCountOnly() }.getOrDefault(0),
        tokenReads = tokenReadCounter.get()
    )

    fun deleteAll() {
        root.deleteRecursively()
    }

    fun readMeta(): Meta? {
        if (!metaFile.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(metaFile))).use { input ->
                require(input.readInt() == MAGIC_META) { "Bad Equinox meta magic" }
                val version = input.readInt()
                require(version in 1..STORE_VERSION) { "Unsupported Equinox store version $version" }
                Meta(
                    observations = input.readLong(),
                    nextMacroId = input.readInt(),
                    macroFrozen = input.readBoolean()
                )
            }
        }.getOrNull()
    }

    fun readQuants(): List<StoredQuant> {
        if (!quantFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(quantFile))).use { input ->
                require(input.readInt() == MAGIC_QUANT)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<StoredQuant>(count).apply {
                    repeat(count) {
                        add(
                            StoredQuant(
                                id = input.readInt(),
                                weight = input.readLong(),
                                hz = input.readDouble(),
                                modality = input.readUTF(),
                                displayColor = input.readInt()
                            )
                        )
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun readMacroHeaders(): List<MacroHeader> {
        if (!macroFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(macroFile))).use { input ->
                require(input.readInt() == MAGIC_MACRO)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<MacroHeader>(count).apply {
                    repeat(count) {
                        add(
                            MacroHeader(
                                id = input.readInt(),
                                level = input.readInt(),
                                modality = input.readUTF(),
                                weight = input.readLong(),
                                hz = input.readDouble(),
                                firstObs = input.readLong(),
                                lastObs = input.readLong(),
                                tokenOffsetBytes = input.readLong(),
                                tokenCount = input.readInt(),
                                signature = input.readUTF()
                            )
                        )
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun readRelations(): List<StoredRelation> {
        if (!relationFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(relationFile))).use { input ->
                require(input.readInt() == MAGIC_RELATION)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<StoredRelation>(count).apply {
                    repeat(count) { add(StoredRelation(input.readLong(), input.readLong())) }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun readExchanges(): List<StoredExchange> {
        if (!exchangeFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(exchangeFile))).use { input ->
                require(input.readInt() == MAGIC_EXCHANGE)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<StoredExchange>(count).apply {
                    repeat(count) {
                        add(StoredExchange(input.readLong(), input.readDouble(), input.readLong()))
                    }
                }
            }
        }.getOrElse { emptyList() }
    }


    fun readCandidates(): List<StoredCandidate> {
        if (!candidateFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(candidateFile))).use { input ->
                require(input.readInt() == MAGIC_CANDIDATE)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<StoredCandidate>(count).apply {
                    repeat(count) {
                        val level = input.readInt()
                        val modality = input.readUTF()
                        val hits = input.readLong()
                        val first = input.readLong()
                        val last = input.readLong()
                        val n = input.readInt().coerceAtLeast(0)
                        val tokens = ArrayList<Int>(n)
                        repeat(n) { tokens += input.readInt() }
                        add(StoredCandidate(level, modality, tokens, hits, first, last))
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun readStreamStates(): List<StoredStreamState> {
        if (!streamStateFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(streamStateFile))).use { input ->
                require(input.readInt() == MAGIC_STREAM_STATE)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<StoredStreamState>(count).apply {
                    repeat(count) {
                        add(StoredStreamState(input.readUTF(), input.readLong(), input.readLong(), input.readLong()))
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun readCrossModalCandidates(): List<StoredCrossModalCandidate> {
        if (!crossModalCandidateFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(crossModalCandidateFile))).use { input ->
                require(input.readInt() == MAGIC_CROSSMODAL_CANDIDATE)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<StoredCrossModalCandidate>(count).apply {
                    repeat(count) { add(StoredCrossModalCandidate(input.readInt(), input.readInt(), input.readLong())) }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun readMindTriples(): List<StoredMindTriple> {
        if (!mindTripleFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(mindTripleFile))).use { input ->
                require(input.readInt() == MAGIC_MIND_TRIPLE)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<StoredMindTriple>(count).apply {
                    repeat(count) { add(StoredMindTriple(input.readLong(), input.readLong(), input.readLong())) }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun readTextMemories(): List<StoredTextMemory> {
        if (!textMemoryFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(textMemoryFile))).use { input ->
                require(input.readInt() == MAGIC_TEXT_MEMORY)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<StoredTextMemory>(count).apply {
                    repeat(count) {
                        add(StoredTextMemory(input.readUTF(), input.readUTF(), input.readLong(), input.readLong()))
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    /** A disk-backed immutable list. Size checks do not touch disk; individual values do. */
    fun tokenList(offsetBytes: Long, count: Int): List<Int> = DiskTokenList(this, offsetBytes, count)

    internal fun readToken(offsetBytes: Long, index: Int): Int {
        tokenReadCounter.incrementAndGet()
        RandomAccessFile(tokenFile, "r").use { raf ->
            raf.seek(offsetBytes + index.toLong() * 4L)
            return raf.readInt()
        }
    }

    /**
     * Writes a complete consistent checkpoint into a sibling temp directory and swaps it in.
     * Existing disk-backed token lists keep reading the old store while the new checkpoint is
     * being produced. The caller can then rebind them using the returned offsets.
     */
    fun writeSnapshot(
        meta: Meta,
        quants: Collection<StoredQuant>,
        macros: Collection<StoredMacro>,
        relations: Collection<StoredRelation>,
        exchanges: Collection<StoredExchange>,
        candidates: Collection<StoredCandidate> = emptyList(),
        streamStates: Collection<StoredStreamState> = emptyList(),
        crossModalCandidates: Collection<StoredCrossModalCandidate> = emptyList(),
        mindTriples: Collection<StoredMindTriple> = emptyList(),
        textMemories: Collection<StoredTextMemory> = emptyList()
    ): WriteResult {
        val parent = root.parentFile ?: error("No filesDir parent")
        val temp = File(parent, "${root.name}_tmp")
        val backup = File(parent, "${root.name}_bak")
        temp.deleteRecursively()
        temp.mkdirs()

        val locations = linkedMapOf<Int, MacroLocation>()

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, META_FILE)))).use { out ->
            out.writeInt(MAGIC_META)
            out.writeInt(STORE_VERSION)
            out.writeLong(meta.observations)
            out.writeInt(meta.nextMacroId)
            out.writeBoolean(meta.macroFrozen)
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, QUANT_FILE)))).use { out ->
            out.writeInt(MAGIC_QUANT)
            out.writeInt(quants.size)
            quants.forEach { q ->
                out.writeInt(q.id)
                out.writeLong(q.weight)
                out.writeDouble(q.hz)
                out.writeUTF(q.modality)
                out.writeInt(q.displayColor)
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, TOKEN_FILE)))).use { tokenOut ->
            DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, MACRO_FILE)))).use { macroOut ->
                macroOut.writeInt(MAGIC_MACRO)
                macroOut.writeInt(macros.size)
                var tokenOffset = 0L
                macros.forEach { m ->
                    val count = m.tokens.size
                    val location = MacroLocation(tokenOffset, count)
                    locations[m.id] = location

                    macroOut.writeInt(m.id)
                    macroOut.writeInt(m.level)
                    macroOut.writeUTF(m.modality)
                    macroOut.writeLong(m.weight)
                    macroOut.writeDouble(m.hz)
                    macroOut.writeLong(m.firstObs)
                    macroOut.writeLong(m.lastObs)
                    macroOut.writeLong(location.offsetBytes)
                    macroOut.writeInt(count)
                    macroOut.writeUTF(m.signature)

                    for (i in 0 until count) tokenOut.writeInt(m.tokens[i])
                    tokenOffset += count.toLong() * 4L
                }
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, RELATION_FILE)))).use { out ->
            out.writeInt(MAGIC_RELATION)
            out.writeInt(relations.size)
            relations.forEach { r ->
                out.writeLong(r.key)
                out.writeLong(r.weight)
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, EXCHANGE_FILE)))).use { out ->
            out.writeInt(MAGIC_EXCHANGE)
            out.writeInt(exchanges.size)
            exchanges.forEach { e ->
                out.writeLong(e.key)
                out.writeDouble(e.ema)
                out.writeLong(e.samples)
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, CANDIDATE_FILE)))).use { out ->
            out.writeInt(MAGIC_CANDIDATE)
            out.writeInt(candidates.size)
            candidates.forEach { c ->
                out.writeInt(c.streamLevel)
                out.writeUTF(c.modality)
                out.writeLong(c.hits)
                out.writeLong(c.firstSeenStreamPosition)
                out.writeLong(c.lastSeenStreamPosition)
                out.writeInt(c.tokens.size)
                c.tokens.forEach(out::writeInt)
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, STREAM_STATE_FILE)))).use { out ->
            out.writeInt(MAGIC_STREAM_STATE)
            out.writeInt(streamStates.size)
            streamStates.forEach { state ->
                out.writeUTF(state.key)
                out.writeLong(state.position)
                out.writeLong(state.coveredUntil)
                out.writeLong(state.lastAutoPromotionAt)
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, CROSSMODAL_CANDIDATE_FILE)))).use { out ->
            out.writeInt(MAGIC_CROSSMODAL_CANDIDATE)
            out.writeInt(crossModalCandidates.size)
            crossModalCandidates.forEach { c ->
                out.writeInt(c.audioMacroId)
                out.writeInt(c.visualMacroId)
                out.writeLong(c.hits)
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, MIND_TRIPLE_FILE)))).use { out ->
            out.writeInt(MAGIC_MIND_TRIPLE)
            out.writeInt(mindTriples.size)
            mindTriples.forEach { t ->
                out.writeLong(t.subject)
                out.writeLong(t.relation)
                out.writeLong(t.objectId)
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, TEXT_MEMORY_FILE)))).use { out ->
            out.writeInt(MAGIC_TEXT_MEMORY)
            out.writeInt(textMemories.size)
            textMemories.forEach { m ->
                out.writeUTF(m.normalized)
                out.writeUTF(m.surface)
                out.writeLong(m.hits)
                out.writeLong(m.ordinal)
            }
        }

        backup.deleteRecursively()
        if (root.exists() && !root.renameTo(backup)) {
            temp.deleteRecursively()
            error("Could not rotate Equinox long-term store")
        }
        if (!temp.renameTo(root)) {
            if (backup.exists()) backup.renameTo(root)
            error("Could not install Equinox long-term store")
        }
        backup.deleteRecursively()

        return WriteResult(locations, directoryBytes(root))
    }

    private fun macroCountOnly(): Int {
        if (!macroFile.exists()) return 0
        DataInputStream(BufferedInputStream(FileInputStream(macroFile))).use { input ->
            require(input.readInt() == MAGIC_MACRO)
            return input.readInt().coerceAtLeast(0)
        }
    }

    private fun directoryBytes(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private class DiskTokenList(
        private val store: IndexedLongTermStore,
        private val offsetBytes: Long,
        override val size: Int
    ) : AbstractList<Int>() {
        override fun get(index: Int): Int {
            if (index !in 0 until size) throw IndexOutOfBoundsException("$index / $size")
            return store.readToken(offsetBytes, index)
        }
    }

    companion object {
        const val STORE_DIR = "equinox_v06_store"
        private const val STORE_VERSION = 2
        private const val META_FILE = "meta.bin"
        private const val QUANT_FILE = "quants.bin"
        private const val MACRO_FILE = "macros.idx"
        private const val TOKEN_FILE = "tokens.bin"
        private const val RELATION_FILE = "relations.bin"
        private const val EXCHANGE_FILE = "exchange.bin"
        private const val CANDIDATE_FILE = "candidates.bin"
        private const val STREAM_STATE_FILE = "streams.bin"
        private const val CROSSMODAL_CANDIDATE_FILE = "crossmodal_candidates.bin"
        private const val MIND_TRIPLE_FILE = "mind_triples.bin"
        private const val TEXT_MEMORY_FILE = "text_memory.bin"

        private const val MAGIC_META = 0x45514D54 // EQMT
        private const val MAGIC_QUANT = 0x45515154 // EQQT
        private const val MAGIC_MACRO = 0x45514D43 // EQMC
        private const val MAGIC_RELATION = 0x4551524C // EQRL
        private const val MAGIC_EXCHANGE = 0x45514558 // EQEX
        private const val MAGIC_CANDIDATE = 0x45514344 // EQCD
        private const val MAGIC_STREAM_STATE = 0x45515354 // EQST
        private const val MAGIC_CROSSMODAL_CANDIDATE = 0x45514358 // EQCX
        private const val MAGIC_MIND_TRIPLE = 0x45514D49 // EQMI
        private const val MAGIC_TEXT_MEMORY = 0x45515458 // EQTX
    }
}
