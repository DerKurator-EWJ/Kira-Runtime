# Kira v0.9.3 — Universal Macro Compiler

v0.9.3 removes the remaining split between modality-specific macro *semantics*. Encoders may
still produce different alphabets, but once an ID reaches MemoryModel, promotion, tiering,
structural ranking and retrieval use one contract.

## Core changes

- Added `UniversalMacroCompiler.kt`.
- Added canonical modality families: TEXT, AUDIO, VISION, CROSSMODAL, FORGE, OTHER.
- F → E → D → C → B → A → S → SS → SSS → Ω → Ω+n remains a depth-only hierarchy.
- Auto-promotion, manual consolidation and audio+vision joint promotion now use the same
  `UniversalMacroCompiler.evaluatePromotion(...)` policy.
- Promotion diagnostics expose support, stability, coherence, recurrence and compression gain.
- Existing stores remain compatible; no persistent file-format migration is required.

## One sparse query path

MemoryModel now owns a universal member index and exposes:

- `queryMemory(KiraMemoryQuery)`
- `unifiedWorkingSnapshot(KiraMemoryQuery)`

A cue can be a level-0 token/encoder ID or an already learned macro. Query enters through the
same sparse member/parent and relation indexes instead of scanning the whole memory graph.

Main runtime consumers now read the universal working projection. MIND is a view filtered from
that projection, not a separate macro hierarchy.

## Text recall

Readable text surfaces are still retained because hashed IDs cannot reconstruct spelling.
However, text-memory selection is now augmented by the same F→Ω+n macro query used by the
other modalities. Chat history remains UI-only.

## Routing

`CoreStimulusMask`, DREAM influence routing and output filtering now classify modalities through
the same family classifier. Existing `crossmodal` nodes can therefore no longer bypass the
MASTER masks merely because their name did not start with the old `joint` prefix.

## Scope

v0.9.3 does **not** yet make arbitrary text+audio+vision coincidence into a new cross-modal
macro. The currently learned coincidence layer remains audio+vision. General N-way cross-modal
association belongs to the later full cross-modal runtime step; the macro representation and
query contract introduced here are already modality-neutral.

## Validation

- `host_tests/run_mind_core_test.sh` → `MindCoreSelfTest OK`
- `host_tests/run_store_v2_test.sh` → `IndexedStoreV2SelfTest OK`
- Universal compiler/classifier tests added to MindCoreSelfTest.
- Full Android build not run in this environment because Android SDK/Gradle are unavailable.
