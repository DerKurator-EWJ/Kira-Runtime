# Equinox v0.7 — Unified Android Mind + External Oven

This release joins the existing Android HYPNO app, Equinox Language v0.3 and Forge v0.56 into one operational layout while keeping the external teacher optional.

## One system, two sources

### On-device / autonomous

The Android app now contains a native Kotlin Equinox Language runtime:

- Tree / thesis / antithesis
- MIRROR -> synthesis + dissonance
- SEAL / HOLD_INFINITY
- Forest / Loom wake scheduling
- Universal semantic compute fabric
- the 9 v0.3 ISA primitives: RELATE, SPLIT, MIRROR, UNIFY, DISSONATE, HOLD, WAKE, SEAL, EMIT
- deterministic 63-bit BLAKE2b IDs compatible with the Python v0.3 language
- persistent local numeric triples
- direct HYPNO projection of the active MIND working set

Open **ARCHIVE CONTROL -> MIND** to edit/run TREE, FOREST or FABRIC source locally. No Python, Forge, network or Hugging Face runtime is required for this path.

### Optional external oven

`external_oven/` contains Forge v0.56 plus `hf_oven_to_android.py`.

The external path is:

`local HF model -> TransformersCausalLMBackend -> Forge hierarchy/facets/macros -> direct flat compiler -> knowledge.eqkernel.json`

The Android MIND page mounts that file as a read-only BASE layer after validating:

- format `equinox-unified-numeric-execution-kernel-v1`
- runtime format version `0.55`
- canonical SHA-256 pack digest

After mounting, Forge and the teacher can be disconnected. Local Equinox Language execution continues independently and never overwrites the external BASE pack.

## Runtime layers

- **FORGE BASE**: optional, externally compiled, immutable while mounted.
- **LOCAL MIND**: on-device language output, persistent and independently clearable.
- **ACTIVE MIND**: recent triples projected to HYPNO as a WorkingSetSnapshot.
- **Legacy ARCHIVE**: the pre-existing sensor MemoryModel and `equinox-external-knowledge-v1` import remain available for backward compatibility.

## MIND source examples

### Fabric

```
FABRIC local CELLS 2
WORK vogel Vogel faehigkeit fliegen MIRROR tree:Vogel HOLD_INFINITY
WORK omega Omega verfeinerung naechste_schranke RELATE process:Omega HOLD_INFINITY
RUN
```

### Tree

```
TREE Vogel
THESIS flug root Vogel faehigkeit fliegen
ANTITHESIS boden root Vogel faehigkeit nicht_fliegen
MIRROR root
SEAL
```

### Forest

```
FOREST Welt
BEGIN_TREE A
THESIS t root A r plus
ANTITHESIS a root A r minus
MIRROR root
SEAL
END_TREE
BEGIN_TREE B
THESIS t root B r plus
ANTITHESIS a root B r minus
MIRROR root
SEAL
END_TREE
RELATE A * touches B * ONEWAY
EXTERNAL A root delta
RUN
SETTLE_ALL
SEAL_FOREST
```

A WAKE does not itself propagate. Only a later structural delta in B may wake a tree connected to B.

## External HF oven

See `external_oven/README_ANDROID_BRIDGE.md`.

Forge intentionally uses `local_files_only=True`; this project does not silently download a model.

## Validation in this release

- Android BLAKE2b semantic IDs checked against Equinox Language v0.3 Python reference.
- v0.3 reference MIRROR packet ID and output triples match the Python implementation exactly.
- Tree synthesis + dissonance coexist.
- Forest A->B->C wake boundary checked.
- `HOLD_INFINITY` remains suspendable rather than an automatic loop.
- `hf_oven_to_android.py` syntax/help path checked.
- Forge v0.56 direct compiler reference run produced an Android-mountable `knowledge.eqkernel.json` (included under `external_oven/sample_output`).

## Android build

This remains an Android Studio Gradle project (`compileSdk 35`, Java/Kotlin 17). The execution environment used to assemble this release does not contain an Android SDK/Gradle installation, so no APK is claimed here; source-level Kotlin core tests and Forge compiler tests were run instead.

Version:

- `versionCode 64`
- `versionName 0.7-unified-mind-oven`
