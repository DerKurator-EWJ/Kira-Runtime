# Kira v0.9.7 — Forge Intermediate Specification v1

v0.9.7 freezes the teacher-neutral boundary between external ovens/teachers and the Kira core.
New external model adapters should emit `kira-forge-intermediate-v1`; Kira no longer needs to know
whether the source was Hugging Face, PyTorch, ONNX, a corpus or another system.

## New contract

`ForgeIntermediate.kt` defines six record families:

- atoms: semantic primitives (concept/feature/event/trait/symbol/state)
- relations: typed, weighted and polarity-aware links
- sequences: ordered observations over atoms
- macro candidates: recurring structures that Kira evaluates with the Universal Macro Compiler
- traits: scalar/text attributes
- vectors: optional teacher evidence, not Kira truth

The Android JSON adapter is `ForgeIntermediateJson.kt`.

## Deterministic identity

`package.namespace` + a semantic key determines the reserved Kira BASE ID. Package version is not
part of the ID, so unchanged concepts keep their identity across BASE v1/v2/v3 updates. The validator
rejects collisions.

If adapters do not supply `stableKey`, Kira derives it from canonical semantic content and recursively
from ordered member identities.

## F → Ω+n ownership

Teacher adapters do not assign Kira tiers. Kira computes hierarchy depth from macro composition and
maps it through the universal tier list:

`F → E → D → C → B → A → S → SS → SSS → Ω → Ω+n`

The same `UniversalMacroCompiler` evaluates promotion support/coherence/recurrence. For non-crossmodal
higher tiers, direct children must belong to the immediately lower tier; this prevents arbitrary tier gaps.

## Thesis / antithesis safety

Relation polarity supports `SUPPORT`, `OPPOSE` and `NEUTRAL`, but the validator explicitly rejects
`embedding_similarity` as direct `OPPOSE`. Vector proximity is semantic proximity, not logical contradiction.
True opposition must arrive as explicit contradiction/NLI-style evidence from an adapter that has that meaning.

## Android UI

`ARCHIVE INTAKE → FORGE v1 · DRY-RUN` parses and validates a `*.kiraforge.json` file and shows:

- package/source/adapter
- atom/sequence/macro counts
- relation/vector/trait counts
- compiled node count
- highest Kira macro depth/tier
- warnings/errors

v0.9.7 deliberately does not install this plan. Atomic BASE package materialisation and hot mount are the v0.9.8 milestone.

Existing `.eqkernel.json` and `equinox-external-knowledge-v1` paths remain available as explicitly marked legacy compatibility paths.

## External oven files

- `external_oven/FORGE_INTERMEDIATE_V1.md`
- `external_oven/kira_forge_intermediate_v1.schema.json`
- `external_oven/forge_intermediate_v1.example.json`
- `external_oven/kira_forge_intermediate_v1.py`

The Python helper is dependency-free and performs cheap adapter-side checks. Android always validates again.

## Validation

- `host_tests/run_mind_core_test.sh` → `MindCoreSelfTest OK`
- `host_tests/run_store_v2_test.sh` → `IndexedStoreV2SelfTest OK`
- Forge plan tests cover deterministic IDs across package versions, namespace separation, F/E depth and rejection of embedding-as-antithesis
- `ForgeIntermediateJson.kt` syntax/type-checked against minimal `org.json` host stubs
- Python example validator → OK
- Python files → `py_compile` OK
- full Android APK build unavailable in this container because Android SDK/Gradle wrapper are absent

Version: `0.9.7-kira-forge-intermediate-v1` / versionCode `99`.
