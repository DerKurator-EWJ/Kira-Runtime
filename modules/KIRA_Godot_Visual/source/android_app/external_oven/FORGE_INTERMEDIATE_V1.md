# Kira Forge Intermediate Specification v1

`kira-forge-intermediate-v1` is the frozen external boundary introduced in Kira v0.9.7.
It is deliberately independent from Hugging Face, PyTorch, Transformers and the internal format
of any teacher. A teacher-specific adapter must translate what it extracts into this schema.
Kira never imports raw model weights as knowledge.

## Contract

A document contains one package/source descriptor and six optional record families:

- `atoms`: semantic primitives such as concepts, features, events, symbols or states.
- `relations`: typed weighted links between structural records.
- `sequences`: ordered observations over atoms.
- `macroCandidates`: proposed recurring structures. Kira calculates F→Ω+n depth and promotion score.
- `traits`: scalar/text attributes attached to a structural record.
- `vectors`: optional compile-time teacher vectors. They are evidence, not Kira truth.

A valid document can be validated and converted into a deterministic `ForgeCompilationPlan` by
Kira v0.9.7. v0.9.7 intentionally does not install the plan yet; atomic BASE materialisation is
reserved for v0.9.8.

## Stable identity

`package.namespace` must stay constant across updates of the same knowledge family. Kira stable IDs
are derived from:

`kira-forge-v1 | namespace | semantic-key`

Package version is intentionally not part of this key, therefore an unchanged concept keeps the same
Kira ID in package v1, v2, v3, ... . Changing the namespace deliberately creates a new identity space.

If `stableKey` is absent, Kira derives one from semantic content:

- atom: modality + kind + normalized canonical value
- sequence: modality + ordered child semantic keys
- macro candidate: modality + ordered child semantic keys

Any stable-ID collision rejects the document.

## Macro depth

Adapters never assign `F`, `E`, `SSS`, `Ω` or `Ω+n` directly. Kira computes hierarchy depth:

- atoms/sequences = depth 0
- macro over primitives = depth 1 = F
- macro over an F structure = depth 2 = E
- ...
- depth 10 = Ω
- depth 11 = Ω+1, etc.

Promotion eligibility is evaluated by Kira's `UniversalMacroCompiler` from support, coherence,
recurrence, stability and compression gain.

## Relation polarity

`SUPPORT`, `OPPOSE` and `NEUTRAL` describe semantic evidence, not positive/negative sentiment.
A critical rule is enforced by the validator: `embedding_similarity` may not directly emit `OPPOSE`.
Embedding distance is semantic proximity and is not proof of logical contradiction. True antithesis
must come from explicit contradiction/NLI evidence or another adapter with appropriate semantics.

## Minimal example

```json
{
  "format": "kira-forge-intermediate-v1",
  "schemaVersion": 1,
  "package": {"id":"de-basic","version":1,"namespace":"kira.de.basic"},
  "source": {
    "id":"teacher-x","version":"1","adapterId":"text-embedding-v1",
    "adapterVersion":"1","modality":"text"
  },
  "atoms": [
    {"id":"vogel","modality":"text","kind":"CONCEPT","canonical":"Vogel","support":12},
    {"id":"tier","modality":"text","kind":"CONCEPT","canonical":"Tier","support":15}
  ],
  "relations": [
    {"from":"vogel","to":"tier","kind":"association","strength":0.91,
     "confidence":0.90,"polarity":"NEUTRAL","direction":"DIRECTED",
     "evidenceType":"embedding_similarity"}
  ]
}
```

See `forge_intermediate_v1.example.json` and `kira_forge_intermediate_v1.schema.json`.
