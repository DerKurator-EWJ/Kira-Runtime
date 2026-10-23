package de.equinox.v05

import android.content.Context
import java.io.File

fun main() {
    val root = File(System.getProperty("java.io.tmpdir"), "kira-indexed-store-v2-selftest")
    root.deleteRecursively()
    root.mkdirs()
    val store = IndexedLongTermStore(Context(root), "unified")

    store.writeSnapshot(
        meta = IndexedLongTermStore.Meta(observations = 77L, nextMacroId = 1_000_123, macroFrozen = false),
        quants = listOf(IndexedLongTermStore.StoredQuant(42, 9L, 0.0, "text", 0)),
        macros = emptyList(),
        relations = emptyList(),
        exchanges = emptyList(),
        candidates = listOf(
            IndexedLongTermStore.StoredCandidate(
                streamLevel = 0,
                modality = "text",
                tokens = listOf(11, 22, 33),
                hits = 6L,
                firstSeenStreamPosition = 2L,
                lastSeenStreamPosition = 18L,
            )
        ),
        streamStates = listOf(
            IndexedLongTermStore.StoredStreamState("0|text", 18L, 15L, 12L)
        ),
        crossModalCandidates = listOf(
            IndexedLongTermStore.StoredCrossModalCandidate(1_000_010, 1_000_020, 5L)
        ),
        mindTriples = listOf(
            IndexedLongTermStore.StoredMindTriple(101L, 202L, 303L)
        ),
        textMemories = listOf(
            IndexedLongTermStore.StoredTextMemory(
                normalized = "aeron ist blau",
                surface = "Aeron ist blau",
                hits = 3L,
                ordinal = 9L,
            )
        )
    )

    val reopened = IndexedLongTermStore(Context(root), "unified")
    check(reopened.readMeta()?.observations == 77L)
    check(reopened.readCandidates().single().hits == 6L)
    check(reopened.readCandidates().single().tokens == listOf(11, 22, 33))
    check(reopened.readStreamStates().single().position == 18L)
    check(reopened.readCrossModalCandidates().single().hits == 5L)
    check(reopened.readMindTriples().single() == IndexedLongTermStore.StoredMindTriple(101L, 202L, 303L))
    check(reopened.readTextMemories().single().surface == "Aeron ist blau")
    check(reopened.readTextMemories().single().hits == 3L)

    println("IndexedStoreV2SelfTest OK")
}
