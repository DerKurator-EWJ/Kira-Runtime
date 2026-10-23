# Kira BASE Package v1 (.kirabase)

`Forge Intermediate v1` is the teacher-neutral build document. `Kira BASE Package v1` is the immutable runtime artefact mounted by Kira v0.9.8+.

Pipeline:

```
Teacher / Hugging Face
        ↓ adapter
Forge Intermediate v1
        ↓ forge compiler
*.kirabase
        ↓ validate / stage / commit
Kira BASE (read-only)
        +
Kira LIVE/PERSONAL (unchanged)
```

A package is a ZIP container with exactly the runtime data Kira needs:

- `manifest.json` — package/source/adapter identity, counts and highest tier
- `surfaces.bin` — grounded atom/sequence surfaces and canonical labels
- `macros.bin` — F → Ω+n macro hierarchy
- `relations.bin` — sparse semantic relations with polarity/evidence retained
- `traits.bin` — scalar/text traits for later DREAM/audio/vision use
- `index.bin` — normalized surface → stable-ID lookup
- `checksums.json` — SHA-256 for every binary payload and one aggregate payload digest

Teacher vectors are intentionally not shipped. They are build evidence, not runtime knowledge.

Build and verify:

```bash
python kira_base_package_v1.py build forge_intermediate_v1.example.json sample_output/example.kirabase
python kira_base_package_v1.py verify sample_output/example.kirabase
```

Stable IDs depend on Forge schema + package namespace + semantic structural key, not package version. Updating the same knowledge family therefore keeps unchanged identities stable.
